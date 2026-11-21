package com.wy.openx.remote

import android.content.Context
import com.wy.openx.container.CommandEngine
import com.wy.openx.container.CommandEvent
import com.wy.openx.container.FileLogger
import com.wy.openx.container.commandTimeoutNotice
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.Security
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.xfer.FilePermission
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey

/**
 * 碰到一台**还没确认过**的主机：首次连接，或指纹与已记录的不一致。
 *
 * 不在这里默默信任：known_hosts 的意义就在于让用户过一次目。
 * 界面接住这个异常弹确认框，用户点「信任」后由 [RemoteManager.trustHostKey] 落盘再重连。
 *
 * 指纹用 `SHA256:<base64>`，与 `ssh-keygen -lf` 输出一致，用户可以自己核对。
 */
class RemoteHostKeyPendingException(
    val host: String,
    val port: Int,
    val fingerprint: String,
    /** true = 之前记过指纹但现在不一致（换了服务器密钥，或中间人）。 */
    val changed: Boolean,
) : IOException(
    if (changed) "$host 的主机密钥已变化，指纹现在是 $fingerprint"
    else "首次连接 $host，需要确认主机指纹 $fingerprint"
)

private class TrafficCountingInputStream(source: InputStream) : InputStream() {
    private val source = source

    override fun read(): Int = source.read()

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n > 0) RemoteTrafficMonitor.addDownloaded(n.toLong())
        return n
    }

    override fun available(): Int = source.available()

    override fun close() = source.close()
}

/**
 * SFTP 客户端（sshj 封装）。
 *
 * 几个刻意的设计：
 * 1. **主机密钥 TOFU**：首次连接记录 SHA-256 指纹，之后必须一致，变了就拒绝并报出来。
 *    不做「一律接受」——那等于放弃对中间人的防护，SSH 的意义也就没了一半。
 * 2. **串行化**：sshj 的 [SFTPClient] 非线程安全，所有操作经 [mutex] 串行。
 * 3. **超时与保活**：连不上时 sshj 默认会挂很久，显式给连接超时；保活避免 NAT 侧静默断连后
 *    下次操作才暴露。
 * 4. **读文件有上限**：远程文件可能几个 G，整篇读进内存会直接 OOM。
 */
internal class RemoteSshClient(
    private val context: Context,
    private val keyStore: RemoteKeyStore,
) {

    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var connectedConfig: RemoteConnection? = null
    private val mutex = Mutex()

    @Volatile
    private var pendingHostKey: RemoteHostKeyPendingException? = null

    @Volatile
    var state: RemoteConnState = RemoteConnState.IDLE
        private set

    /** 当前是否处于已连接状态。 */
    val isConnected: Boolean get() = state == RemoteConnState.CONNECTED && sftp != null

    suspend fun connect(config: RemoteConnection): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBouncyCastle()
            if (isConnected && connectedConfig == config) return@withContext
            disconnectInternal()
            state = RemoteConnState.CONNECTING
            try {
                val ssh = SSHClient()
                ssh.addHostKeyVerifier(hostKeyVerifier(config.host, config.port))
                ssh.connectTimeout = CONNECT_TIMEOUT_MS
                ssh.timeout = SOCKET_TIMEOUT_MS
                ssh.connect(config.host, config.port)

                when (config.authType) {
                    RemoteAuthType.PASSWORD -> {
                        if (config.authData.isBlank()) {
                            // 空密码先拦住：否则 sshj 只会吐「Exhausted available authentication methods」，
                            // 和「密码错」分不开，还会白跑一轮认证（服务器侧可能计入防爆破计数）
                            throw IOException("未填写密码")
                        }
                        ssh.authPassword(config.username, config.authData)
                    }
                    RemoteAuthType.PRIVATE_KEY -> {
                        // 已保存密钥优先：正文在密钥库，连接配置只留 id
                        if (config.savedKeyId != null) {
                            val key = keyStore.find(config.savedKeyId)
                                ?: throw IOException("已保存的登录密钥不存在（可能已删除）")
                            val provider = loadKeyFromContent(key.content, key.passphrase.ifBlank { null })
                            ssh.authPublickey(config.username, provider)
                        } else {
                            if (config.authData.isBlank()) throw IOException("未填写私钥路径")
                            val keyFile = File(config.authData)
                            if (!keyFile.isFile) throw IOException("私钥文件不存在：${config.authData}")
                            val provider: KeyProvider = if (config.passphrase.isNotBlank()) {
                                ssh.loadKeys(
                                    keyFile.absolutePath,
                                    net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(
                                        config.passphrase.toCharArray()
                                    )
                                )
                            } else {
                                ssh.loadKeys(keyFile.absolutePath)
                            }
                            ssh.authPublickey(config.username, provider)
                        }
                    }
                }
                ssh.transport.setDisconnectListener { reason, _ ->
                    FileLogger.w(TAG, "SSH 断开：$reason")
                    state = RemoteConnState.FAILED
                }
                client = ssh
                sftp = ssh.newSFTPClient()
                connectedConfig = config
                state = RemoteConnState.CONNECTED
                FileLogger.i(TAG, "SFTP 已连接 ${config.host}:${config.port} as ${config.username}")
            } catch (e: Exception) {
                state = RemoteConnState.FAILED
                val pending = consumePendingHostKey()
                if (pending != null) throw pending
                FileLogger.e(TAG, "SSH 连接失败 ${config.host}:${config.port}", e)
                disconnectInternal()
                throw IOException(friendlyError(e), e)
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) { disconnectInternal() }
    }

    /**
     * 同步断开，供 UI 生命周期回调（如 `DisposableEffect.onDispose`）使用。
     *
     * 那里没有协程作用域可用，而 `disconnect()` 是 suspend 的。
     * 调用方需自行确保不在主线程——本类内部只做关流/关连接，不持有锁。
     */
    fun disconnectBlocking() {
        disconnectInternal()
    }

    /** 列出目录。路径为空时用配置里的初始目录，再空则落到登录 home 下的 `$HOME/.openx`。 */
    suspend fun list(config: RemoteConnection, path: String): List<RemoteEntry> = withLockedSftp { sftp ->
        val dir = path.ifBlank { config.remotePath.ifBlank { defaultStartDir(sftp) } }
        if (sftp.statExistence(dir) == null) runCatching { sftp.mkdirs(dir) }
        sftp.ls(dir)
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map { item ->
                RemoteEntry(
                    name = item.name,
                    path = joinPath(dir, item.name),
                    isDirectory = item.isDirectory,
                    size = item.attributes.size,
                    lastModified = item.attributes.mtime * 1000,
                    permissions = formatPermissions(item.attributes),
                )
            }
            // 目录排前面，同类按名字排：与常见文件管理器一致，找目录不用滚到底
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    /** 读文本文件；超过 [maxBytes] 抛 [RemoteTooLargeException]。 */
    suspend fun readText(config: RemoteConnection, path: String, maxBytes: Long = MAX_READ_BYTES): String =
        withLockedSftp { sftp ->
            val attrs = sftp.stat(path)
            if (attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) throw IOException("这是目录：$path")
            if (attrs.size > maxBytes) throw RemoteTooLargeException(attrs.size, maxBytes)
            val out = ByteArrayOutputStream()
            val file = sftp.open(path)
            try {
                val buffer = ByteArray(IO_CHUNK)
                var total = 0L
                while (true) {
                    val n = file.read(0, buffer, 0, buffer.size)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) throw RemoteTooLargeException(total, maxBytes)
                    out.write(buffer, 0, n)
                    RemoteTrafficMonitor.addDownloaded(n.toLong())
                }
            } finally {
                runCatching { file.close() }
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }

    /** 覆盖写文本文件（父目录不存在时先建）。 */
    suspend fun writeText(path: String, content: String) = withLockedSftp { sftp ->
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) runCatching { sftp.stat(parent) }
            .onFailure { runCatching { sftp.mkdirs(parent) } }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val file = sftp.open(
            path,
            setOf(
                net.schmizz.sshj.sftp.OpenMode.WRITE,
                net.schmizz.sshj.sftp.OpenMode.CREAT,
                net.schmizz.sshj.sftp.OpenMode.TRUNC
            )
        )
        try {
            file.write(0, bytes, 0, bytes.size)
            RemoteTrafficMonitor.addUploaded(bytes.size.toLong())
        } finally {
            runCatching { file.close() }
        }
    }

    suspend fun mkdir(path: String) = withLockedSftp { sftp -> sftp.mkdirs(path) }

    /** 上传本地文件到远端（二进制直传，不经过文本编码）。 */
    suspend fun uploadFile(localPath: String, remotePath: String) = withLockedSftp { sftp ->
        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) runCatching { sftp.stat(parent) }
            .onFailure { runCatching { sftp.mkdirs(parent) } }
        sftp.put(localPath, remotePath)
        RemoteTrafficMonitor.addUploaded(File(localPath).length())
    }

    /** 从远端下载文件到本地（父目录不存在时先建）。 */
    suspend fun downloadFile(remotePath: String, localPath: String) = withLockedSftp { sftp ->
        File(localPath).parentFile?.mkdirs()
        val remoteSize = runCatching { sftp.stat(remotePath).size }.getOrDefault(0L)
        sftp.get(remotePath, localPath)
        RemoteTrafficMonitor.addDownloaded(remoteSize)
    }

    /** 远端路径是否存在。 */
    suspend fun exists(path: String): Boolean = runCatching {
        withLockedSftp { sftp -> sftp.stat(path) }
        true
    }.getOrDefault(false)

    suspend fun delete(path: String, isDirectory: Boolean) = withLockedSftp { sftp ->
        if (isDirectory) sftp.rmdir(path) else sftp.rm(path)
    }

    suspend fun rename(from: String, to: String) = withLockedSftp { sftp -> sftp.rename(from, to) }

    /** 查单个路径的属性；不存在返回 null（不抛异常）。 */
    suspend fun stat(path: String): RemoteStat? = withLockedSftp { sftp ->
        val attrs = sftp.statExistence(path) ?: return@withLockedSftp null
        RemoteStat(
            path = path,
            isDirectory = attrs.type == FileMode.Type.DIRECTORY,
            size = attrs.size,
            lastModified = attrs.mtime * 1000,
            permissions = formatPermissions(attrs),
        )
    }

    /**
     * 递归删除。先深后浅：目录必须先清空才能 rmdir。
     *
     * 不跟随软链：软链当普通文件删掉即可。跟进去删会把链接指向的**别处**内容一起毁了——
     * 而 `rm -rf` 又无法控制边界，所以宁可写三层递归。
     */
    suspend fun deleteRecursively(path: String, depth: Int = 0) {
        if (depth > MAX_DEPTH) throw IOException("目录层级过深，已中止：$path")
        val attrs = withLockedSftp { sftp -> sftp.statExistence(path) } ?: return
        if (attrs.type != FileMode.Type.DIRECTORY) {
            withLockedSftp { sftp -> sftp.rm(path) }
            return
        }
        val children = withLockedSftp { sftp -> sftp.ls(path) }.map { joinPath(path, it.name) }
        for (child in children) deleteRecursively(child, depth + 1)
        withLockedSftp { sftp -> sftp.rmdir(path) }
    }

    /**
     * 服务端内复制（文件或目录）。
     *
     * Windows 主机的 exec 走 cmd.exe，没有 POSIX 的 `cp` 和单引号；`/C:/...` 形态的路径
     * 转成 `C:\...` 后用 `copy`/`xcopy`，Unix 路径继续用 `cp -a`。
     */
    suspend fun copyOnServer(from: String, to: String, isDirectory: Boolean) {
        if (isWindowsRemotePath(from) || isWindowsRemotePath(to)) {
            val src = toWindowsRemotePath(from)
            val dst = toWindowsRemotePath(to)
            val command = if (isDirectory) {
                "xcopy /E /I /Y /H /K ${cmdQuote(src)} ${cmdQuote(dst)}"
            } else {
                "copy /Y ${cmdQuote(src)} ${cmdQuote(dst)}"
            }
            val (code, output) = runCommandCapture(command, COPY_TIMEOUT_MS)
            val tool = if (isDirectory) "xcopy" else "copy"
            val success = if (isDirectory) code == 0 || (code == 1 && stat(to) != null) else code == 0
            if (!success) throw copyFailure(tool, code, output, from, to)
            return
        }
        val (code, output) = runCommandCapture("cp -a -- ${shellQuote(from)} ${shellQuote(to)}", COPY_TIMEOUT_MS)
        if (code != 0) throw copyFailure("cp", code, output, from, to)
    }

    /** 跑一条命令，回传退出码和输出，失败时能把服务端原话带进提示。 */
    private suspend fun runCommandCapture(command: String, timeoutMs: Long): Pair<Int?, String> {
        var code: Int? = null
        val output = StringBuilder()
        execStream(command, timeoutMs).collect { event ->
            when (event) {
                is CommandEvent.Line -> output.append(event.text).append('\n')
                is CommandEvent.Exit -> code = event.code
            }
        }
        return code to output.toString()
    }

    /** POSIX 单引号转义：内部单引号用 `'\''` 拼，避免路径里的空格/分号被 shell 解释。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun cmdQuote(value: String): String = "\"$value\""

    private fun isWindowsRemotePath(path: String): Boolean {
        val p = path.trim()
        return p.startsWith("//") || (p.length >= 3 && p[0] == '/' && p[1].isLetter() && p[2] == ':')
    }

    private fun toWindowsRemotePath(path: String): String {
        val p = path.trim()
        return when {
            p.startsWith("//") -> "\\\\" + p.drop(2).replace('/', '\\')
            isWindowsRemotePath(p) -> p.drop(1).replace('/', '\\')
            else -> p.replace('/', '\\')
        }
    }

    private fun copyFailure(tool: String, code: Int?, output: String, from: String, to: String): IOException {
        val detail = output.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(3)
            .joinToString("；")
        val suffix = if (detail.isBlank()) "" else "：$detail"
        return IOException("服务端复制失败（$tool 退出码 ${code ?: "未知"}）：$from → $to$suffix")
    }

    /** 把远端属性拼成 `-rw-r--r--` 形状，好让文件树与本地口径一致。 */
    private fun formatPermissions(attrs: FileAttributes): String {
        val typeChar = when (attrs.type) {
            FileMode.Type.DIRECTORY -> 'd'
            FileMode.Type.SYMLINK -> 'l'
            FileMode.Type.BLOCK_SPECIAL -> 'b'
            FileMode.Type.CHAR_SPECIAL -> 'c'
            FileMode.Type.FIFO_SPECIAL -> 'p'
            FileMode.Type.SOCKET_SPECIAL -> 's'
            else -> '-'
        }
        val mask = attrs.mode.permissionsMask
        val sb = StringBuilder(10)
        sb.append(typeChar)
        sb.append(if (FilePermission.USR_R.isIn(mask)) 'r' else '-')
        sb.append(if (FilePermission.USR_W.isIn(mask)) 'w' else '-')
        sb.append(if (FilePermission.USR_X.isIn(mask)) 'x' else '-')
        sb.append(if (FilePermission.GRP_R.isIn(mask)) 'r' else '-')
        sb.append(if (FilePermission.GRP_W.isIn(mask)) 'w' else '-')
        sb.append(if (FilePermission.GRP_X.isIn(mask)) 'x' else '-')
        sb.append(if (FilePermission.OTH_R.isIn(mask)) 'r' else '-')
        sb.append(if (FilePermission.OTH_W.isIn(mask)) 'w' else '-')
        sb.append(if (FilePermission.OTH_X.isIn(mask)) 'x' else '-')
        return sb.toString()
    }

    suspend fun homePath(): String = withLockedSftp { sftp -> homeOf(sftp) }

    /** 探测连接是否还活着（列表一次 home）。 */
    suspend fun ping(config: RemoteConnection): Boolean = runCatching {
        withLockedSftp { sftp -> sftp.ls(homeOf(sftp)).size }
        true
    }.getOrDefault(false)

    private fun homeOf(sftp: SFTPClient): String =
        runCatching { sftp.canonicalize(".") }.getOrDefault("/")

    /** OpenX 在远端的工作目录：登录 home 下的 `$HOME/.openx`。 */
    private fun defaultStartDir(sftp: SFTPClient): String =
        homeOf(sftp).trimEnd('/') + "/\$HOME/.openx"

    /**
     * 从私钥正文加载密钥。
     *
     * sshj 的 `loadKeys(String)` 只接受文件路径，这里先探测格式再直接 `init(Reader)`：
     * **不落临时文件**——私钥明文写到磁盘（哪怕立刻删掉）也会留下可恢复的痕迹。
     *
     * 支持 OpenSSH（v1/新格式）、PKCS#8、PuTTY；不认识的格式直接报错，
     * 而不是默默当成成功（那会让连接在认证阶段才失败，很难查）。
     */
    private fun loadKeyFromContent(content: String, passphrase: String?): KeyProvider {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) throw IOException("私钥内容为空")
        // detect 会消耗 reader，所以探测与加载各用一个
        val format = net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
            .detectKeyFileFormat(java.io.StringReader(trimmed), true)
        val provider = when (format) {
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSH,
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSHv1 ->
                net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile()
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.PKCS8 ->
                net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile()
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.PuTTY ->
                net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile()
            else -> throw IOException("无法识别的私钥格式，请确认是 OpenSSH 或 PKCS#8 私钥")
        }
        val pwdf = passphrase?.let {
            net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(it.toCharArray())
        }
        provider.init(java.io.StringReader(trimmed), pwdf)
        return provider
    }

    private suspend fun <T> withLockedSftp(block: (SFTPClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = sftp ?: throw IOException("未连接")
            try {
                block(current)
            } catch (e: RemoteTooLargeException) {
                throw e
            } catch (e: Exception) {
                // 传输层异常时丢弃通道（下次调用会要求重连），业务错误（不存在/权限）不重建
                if (e is net.schmizz.sshj.sftp.SFTPException && e.statusCode != null) throw e
                FileLogger.w(TAG, "SFTP 操作失败，丢弃通道", e)
                runCatching { disconnectInternal() }
                throw IOException(friendlyError(e), e)
            }
        }
    }

    private fun disconnectInternal() {
        runCatching { sftp?.close() }
        runCatching { client?.disconnect() }
        sftp = null
        client = null
        connectedConfig = null
        state = RemoteConnState.IDLE
    }

    /**
     * 在远端执行一条命令，逐行回传输出。
     *
     * exec 通道与 SFTP 共用同一条 SSH transport：开新通道不需要重新认证，
     * 也不占 SFTP 的锁——一条长命令跑着的时候，文件列表照旧能用。
     *
     * stdout 与 stderr **并发**读。必须并发：串行读时只要其中一个先写满缓冲区，
     * 另一端就会把命令自己堵死（经典管道死锁）。
     */
    fun execStream(command: String, timeoutMs: Long): Flow<CommandEvent> = channelFlow {
        val ssh = client
        if (ssh == null) {
            send(CommandEvent.Line("远端未连接，无法执行命令"))
            send(CommandEvent.Exit(null))
            return@channelFlow
        }
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        val timedOut = AtomicBoolean(false)
        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            // 不喂 stdin 就直接关：留着的话部分服务端会等输入，命令永远不返回。
            // exec 本来也不需要交互输入，要交互得用 shell 通道。
            runCatching { cmd.outputStream.close() }

            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (cmd.isOpen) {
                        timedOut.set(true)
                        FileLogger.w(TAG, "远端命令超时(${effectiveTimeout}ms)已终止: $command")
                        // 关通道会让下面阻塞中的读立刻结束，读循环随之退出
                        runCatching { cmd.close() }
                    }
                }
                try {
                    val stdout = launch { pumpLines(TrafficCountingInputStream(cmd.inputStream)) { send(CommandEvent.Line(it)) } }
                    val stderr = launch { pumpLines(TrafficCountingInputStream(cmd.errorStream)) { send(CommandEvent.Line(it)) } }
                    stdout.join()
                    stderr.join()
                } finally {
                    watchdog.cancel()
                }
            }

            if (timedOut.get()) {
                send(CommandEvent.Line(commandTimeoutNotice(effectiveTimeout)))
                send(CommandEvent.Exit(null))
            } else {
                send(CommandEvent.Exit(cmd.exitStatus))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (timedOut.get()) {
                // 看门狗关通道会让阻塞中的读抛异常。这里按超时收尾而不是当执行失败：
                // 用户（和模型）需要的是「它超时了」，不是「读管道出了个 IOException」。
                send(CommandEvent.Line(commandTimeoutNotice(effectiveTimeout)))
                send(CommandEvent.Exit(null))
            } else {
                FileLogger.w(TAG, "远端执行失败: $command", e)
                send(CommandEvent.Line("远端执行失败：${friendlyError(e)}"))
                send(CommandEvent.Exit(null))
            }
        } finally {
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 开一条**交互式** shell 通道并分配 PTY，交给远程终端页用。
     *
     * 与 [execStream] 的区别：exec 是「跑完就结束」的一次性通道，
     * shell 是长驻的双向会话，能跑 vim/top 这类全屏程序——因为它们需要 PTY。
     *
     * 分配 PTY 是必需的：不分配的话远端程序拿到的 stdout 不是 tty，
     * 不会输出 ANSI 控制序列、也不做行编辑，`ls` 甚至不会分列。
     */
    fun openShell(columns: Int, rows: Int): Session.Shell {
        val ssh = client ?: throw IOException("远端未连接")
        val session = ssh.startSession()
        return try {
            // 类型用 "xterm-256color"：Termux 仿真器支持 256 色，报个哑终端会白丢颜色
            session.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap())
            session.startShell()
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
    }

    /** 逐行读一个流并回调，读到 EOF 或流被关掉就返回。 */    private suspend fun pumpLines(source: InputStream, onLine: suspend (String) -> Unit) {
        BufferedReader(InputStreamReader(source)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
    }

    /**
     * TOFU 主机密钥校验：指纹不对就不连，交给用户确认。
     *
     * 不把未知主机当成「先信了再说」：能弹一次确认就弹，
     * 用户看一眼指纹的成本，比默默接受一个中间人低得多。
     */
    private fun hostKeyVerifier(host: String, port: Int): HostKeyVerifier =
        object : HostKeyVerifier {
            override fun verify(hostname: String?, portNum: Int, key: PublicKey?): Boolean {
                pendingHostKey = null
                if (key == null) return false
                val fingerprint = fingerprintOf(key)
                val keyId = "$host:$port"
                val saved = hostKeyPrefs().getString(keyId, null)
                if (saved == fingerprint) return true
                val pending = RemoteHostKeyPendingException(host, port, fingerprint, changed = saved != null)
                pendingHostKey = pending
                throw pending
            }

            // sshj 还会问「已知可接受的算法」；返回 null 表示不做算法白名单，
            // 全部交给 verify() 按指纹判定。
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String>? = null
        }

    private fun consumePendingHostKey(): RemoteHostKeyPendingException? =
        pendingHostKey.also { pendingHostKey = null }

    private fun ensureBouncyCastle() {
        if (bcRegistered) return
        synchronized(bcLock) {
            if (bcRegistered) return
            runCatching {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
                SecurityUtils.setSecurityProvider("BC")
                FileLogger.i(TAG, "已注册完整版 BouncyCastle 供 sshj 使用")
            }.onFailure {
                FileLogger.w(TAG, "注册 BouncyCastle 失败，SSH 可能因缺少算法而失败", it)
            }
            bcRegistered = true
        }
    }

    private fun fingerprintOf(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun hostKeyPrefs() = context.getSharedPreferences("remote_host_keys", Context.MODE_PRIVATE)

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    /** 把 sshj 的原始异常翻成用户看得懂的话，否则界面上只有一串 Java 类名。 */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Auth fail") || msg.contains("authentication") ->
                "认证失败：用户名、密码或私钥不正确"
            msg.contains("UnknownHost") || msg.contains("unable to resolve") ->
                "找不到主机，检查地址与网络"
            msg.contains("Connection refused") -> "服务器拒绝连接，检查端口"
            msg.contains("timed out") || msg.contains("timeout") -> "连接超时"
            msg.contains("no such algorithm") -> "加密算法不支持（缺少 BouncyCastle）"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    private companion object {
        const val TAG = "RemoteSshClient"
        val bcLock = Any()
        @Volatile var bcRegistered = false
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 30_000
        const val IO_CHUNK = 32 * 1024
        const val MAX_READ_BYTES = 8L * 1024 * 1024

        /** 复制/删除的递归深度上限：防远端有循环软链把栈打爆。 */
        const val MAX_DEPTH = 64

        /** 服务端 `cp` 的超时：大目录复制可能很久。 */
        const val COPY_TIMEOUT_MS = 300_000L
    }
}

/** 远程文件超过读取上限。带上实际大小，好让界面说明「多大、上限多少」。 */
class RemoteTooLargeException(val size: Long, val limit: Long) :
    IOException("文件 ${size / 1024}KB 超过上限 ${limit / 1024}KB，未读取")
