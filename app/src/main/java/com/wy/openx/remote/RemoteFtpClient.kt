package com.wy.openx.remote

import com.wy.openx.container.FileLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/**
 * FTP 客户端（commons-net 封装）。
 *
 * FTP 与 SFTP 的差别不只是协议：FTP 的「当前工作目录」是服务端会话状态，
 * 所有路径操作都受它影响，所以这里统一用**绝对路径 + 每次显式 chdir**，
 * 不依赖调用顺序，避免「先列 A 再写 B 结果写进了 A」这类串味。
 *
 * FTP 明文传输（除非显式 FTPS），界面上要提醒用户：**别在公网用 FTP 传敏感数据**。
 */
internal class RemoteFtpClient {

    private var ftp: FTPClient? = null
    private val mutex = Mutex()

    @Volatile
    var state: RemoteConnState = RemoteConnState.IDLE
        private set

    val isConnected: Boolean get() = state == RemoteConnState.CONNECTED && ftp?.isConnected == true

    suspend fun connect(config: RemoteConnection) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (isConnected) return@withContext
            state = RemoteConnState.CONNECTING
            val client = FTPClient()
            try {
                client.connectTimeout = CONNECT_TIMEOUT_MS
                client.defaultTimeout = SOCKET_TIMEOUT_MS
                client.connect(config.host, config.port)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    throw IOException("FTP 服务器拒绝连接：${client.replyString}")
                }
                // 二进制模式：文本模式会按服务端做换行转换，写回去的文件会被改坏
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.enterLocalPassiveMode()
                client.controlEncoding = "UTF-8"
                if (!client.login(config.username, config.authData)) {
                    throw IOException("FTP 登录失败：用户名或密码不正确")
                }
                ftp = client
                state = RemoteConnState.CONNECTED
                FileLogger.i(TAG, "FTP 已连接 ${config.host}:${config.port}")
            } catch (e: Exception) {
                state = RemoteConnState.FAILED
                runCatching { client.disconnect() }
                throw IOException(friendlyError(e), e)
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { ftp?.logout() }
            runCatching { ftp?.disconnect() }
            ftp = null
            state = RemoteConnState.IDLE
        }
    }

    suspend fun list(config: RemoteConnection, path: String): List<RemoteEntry> = withLocked { client ->
        val dir = path.ifBlank { config.remotePath.ifBlank { "/" } }
        if (!client.changeWorkingDirectory(dir)) throw IOException("无法进入目录：$dir")
        client.listFiles().map { f ->
            RemoteEntry(
                name = f.name,
                path = joinPath(dir, f.name),
                isDirectory = f.isDirectory,
                size = f.size,
            )
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readText(config: RemoteConnection, path: String, maxBytes: Long = MAX_READ_BYTES): String =
        withLocked { client ->
            val out = ByteArrayOutputStream()
            val ok = client.retrieveFile(path, out)
            if (!ok) throw IOException("读取失败：${client.replyString}")
            if (out.size().toLong() > maxBytes) throw RemoteTooLargeException(out.size().toLong(), maxBytes)
            RemoteTrafficMonitor.addDownloaded(out.size().toLong())
            String(out.toByteArray(), Charsets.UTF_8)
        }

    suspend fun writeText(path: String, content: String) = withLocked { client ->
        val bytes = content.toByteArray(Charsets.UTF_8)
        val stream = java.io.ByteArrayInputStream(bytes)
        if (!client.storeFile(path, stream)) throw IOException("写入失败：${client.replyString}")
        RemoteTrafficMonitor.addUploaded(bytes.size.toLong())
    }

    suspend fun mkdir(path: String) = withLocked { client ->
        if (!client.makeDirectory(path)) throw IOException("建目录失败：${client.replyString}")
    }

    /** 上传本地文件到远端（二进制直传）。 */
    suspend fun uploadFile(localPath: String, remotePath: String) = withLocked { client ->
        val file = java.io.File(localPath)
        if (!file.isFile) throw IOException("本地文件不存在：$localPath")
        val stream = file.inputStream()
        try {
            if (!client.storeFile(remotePath, stream)) throw IOException("上传失败：${client.replyString}")
            RemoteTrafficMonitor.addUploaded(file.length())
        } finally {
            runCatching { stream.close() }
        }
    }

    /** 从远端下载文件到本地（父目录不存在时先建）。 */
    suspend fun downloadFile(remotePath: String, localPath: String) = withLocked { client ->
        val file = java.io.File(localPath)
        file.parentFile?.mkdirs()
        val out = file.outputStream()
        try {
            if (!client.retrieveFile(remotePath, out)) throw IOException("下载失败：${client.replyString}")
            RemoteTrafficMonitor.addDownloaded(file.length())
        } finally {
            runCatching { out.close() }
        }
    }

    /** 远端路径是否存在（靠列父目录判断：FTP 没有可靠的 stat）。 */
    suspend fun exists(remotePath: String): Boolean = runCatching {
        withLocked { client ->
            val parent = remotePath.substringBeforeLast('/', "").ifBlank { "/" }
            val name = remotePath.substringAfterLast('/')
            client.listFiles(parent).any { it.name == name }
        }
    }.getOrDefault(false)

    suspend fun delete(path: String, isDirectory: Boolean) = withLocked { client ->
        val ok = if (isDirectory) client.removeDirectory(path) else client.deleteFile(path)
        if (!ok) throw IOException("删除失败：${client.replyString}")
    }

    suspend fun rename(from: String, to: String) = withLocked { client ->
        if (!client.rename(from, to)) throw IOException("重命名失败：${client.replyString}")
    }

    suspend fun ping(): Boolean = runCatching {
        withLocked { client -> client.sendNoOp() }
        true
    }.getOrDefault(false)

    private suspend fun <T> withLocked(block: (FTPClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val client = ftp ?: throw IOException("未连接")
            try {
                block(client)
            } catch (e: RemoteTooLargeException) {
                throw e
            } catch (e: Exception) {
                if (e is IOException && client.isConnected) throw e
                FileLogger.w(TAG, "FTP 操作失败，丢弃连接", e)
                runCatching { client.disconnect() }
                ftp = null
                state = RemoteConnState.FAILED
                throw IOException(friendlyError(e), e)
            }
        }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Connection refused") -> "服务器拒绝连接，检查端口"
            msg.contains("UnknownHost") -> "找不到主机，检查地址与网络"
            msg.contains("timed out") || msg.contains("timeout") -> "连接超时"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    private companion object {
        const val TAG = "RemoteFtpClient"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 30_000
        const val MAX_READ_BYTES = 8L * 1024 * 1024
    }
}
