package com.wy.openx.remote

import com.wy.openx.container.FileLogger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 挂载同步的运行状态。 */
enum class SyncState { IDLE, CONNECTING, CONNECTED, SYNCING, FAILED }

/** 一次同步的结果摘要，供卡片上显示「传了几个、跳了几个」。 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
) {
    val summary: String
        get() = buildList {
            if (uploaded > 0) add("上传 $uploaded")
            if (skipped > 0) add("跳过 $skipped")
            if (downloaded > 0) add("下载 $downloaded")
            if (deleted > 0) add("删除 $deleted")
            if (failed > 0) add("失败 $failed")
        }.joinToString(" · ").ifBlank { "无变化" }
}

/**
 * 一条挂载的同步引擎。
 *
 * 与 [RemoteManager] 分开持有**独立的连接**：同步是常驻任务，如果共用那条给文件浏览用的
 * 连接，用户点开一个目录就会把同步挤掉，反之亦然。
 *
 * 同步方向以**本地为准**（上传）：本地是用户在 OpenX 里编辑的地方，远端是部署/备份目的地。
 * 下载只在「强制下载」时执行，避免远端的东西悄悄覆盖本地改动。
 *
 * 增量判定靠 [SyncIndexStore] 的指纹（远端 SFTP/FTP 拿不到内容哈希），
 * 判不准就传——漏传远端缺文件，比多传一次代价大得多。
 */
class RemoteSyncEngine(
    private val context: android.content.Context,
    private val mount: RemoteMount,
    private val connection: RemoteConnection,
    private val indexStore: SyncIndexStore,
    private val localRoot: File,
    private val ignorePatterns: Set<String>,
    /** 同步行为设置（是否遵循 .gitignore、分批上限）。每次上传重新读，改设置后立即生效。 */
    private val settingsProvider: () -> RemoteSyncSettings = { RemoteSyncSettings() },
    /** 已保存密钥库：同步也要走同一套认证。 */
    private val keyStore: RemoteKeyStore,
    private val autoSyncIntervalMs: Long = AUTO_SYNC_INTERVAL_MS,
) {
    private val ssh by lazy { RemoteSshClient(context, keyStore) }
    private val ftp by lazy { RemoteFtpClient() }

    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastOutcome = MutableStateFlow(SyncOutcome())
    val lastOutcome: StateFlow<SyncOutcome> = _lastOutcome.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var loopJob: Job? = null
    private val indexIdentity by lazy {
        SyncIndexIdentity(mount.connectionId, connection.host, mount.remotePath)
    }
    private var fingerprints: MutableMap<String, FileFingerprint> = mutableMapOf()

    /** 建立连接并加载索引。失败时抛异常（消息可直接显示）。 */
    suspend fun connect() {
        _state.value = SyncState.CONNECTING
        try {
            when (connection.protocol) {
                RemoteProtocol.SFTP -> ssh.connect(connection)
                RemoteProtocol.FTP -> ftp.connect(connection)
            }
            fingerprints = indexStore.load(mount.id, indexIdentity).toMutableMap()
            _state.value = SyncState.CONNECTED
            _lastError.value = null
        } catch (e: Exception) {
            _state.value = SyncState.FAILED
            _lastError.value = e.message ?: "连接失败"
            throw e
        }
    }

    suspend fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        runCatching { ssh.disconnect() }
        runCatching { ftp.disconnect() }
        _state.value = SyncState.IDLE
    }

    /**
     * 启动自动同步循环（探活 + 增量上传）。
     *
     * 失败会**逐次拉长间隔**（翻倍，封顶 [MAX_RETRY_INTERVAL_MS]）：
     * 服务器宕了、网断了的时候，没必要每 30 秒去敲一次门；
     * 一旦恢复就立即回到常规间隔。
     */
    fun startAutoSync(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            var interval = autoSyncIntervalMs
            while (isActive) {
                delay(interval)
                val ok = runCatching {
                    if (!isAlive()) {
                        FileLogger.w(TAG, "挂载 ${mount.id} 连接已断，尝试重连")
                        connect()
                    }
                    uploadWorkspace(force = false)
                }.onFailure {
                    _lastError.value = it.message ?: "自动同步失败"
                    FileLogger.w(TAG, "自动同步失败: ${mount.id}", it)
                }.isSuccess
                interval = if (ok) autoSyncIntervalMs
                else (interval * 2).coerceAtMost(MAX_RETRY_INTERVAL_MS)
            }
        }
    }

    private suspend fun isAlive(): Boolean = when (connection.protocol) {
        RemoteProtocol.SFTP -> ssh.ping(connection)
        RemoteProtocol.FTP -> ftp.ping()
    }

    /**
     * 上传本地工作区到远端。
     *
     * 先传改动、再清远端上「本地已删掉」的文件。删除与上传共用同一趟遍历结果，
     * 不额外扫盘；批间让一下，避免连续推几百个文件把 SSH 通道占满。
     *
     * @param force 忽略指纹索引，全部重传（「强制上传」用）
     */
    suspend fun uploadWorkspace(force: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        _state.value = SyncState.SYNCING
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var consecutiveFailures = 0
        try {
            if (!localRoot.isDirectory) throw IllegalStateException("本地目录不存在：${localRoot.absolutePath}")
            val settings = settingsProvider()
            val gitignore = loadGitignore(settings)

            val files = mutableListOf<File>()
            collectFiles(localRoot, gitignore, files, 0)
            val localRelatives = files.mapTo(mutableSetOf()) {
                it.relativeTo(localRoot).invariantSeparatorsPath
            }

            val batches = files.chunked(batchSizeOf(settings))
            for ((batchIndex, batch) in batches.withIndex()) {
                for (file in batch) {
                    val relative = file.relativeTo(localRoot).invariantSeparatorsPath
                    val remotePath = joinRemote(mount.remotePath, relative)
                    val stored = if (force) null else fingerprints[relative]
                    val decision = SyncDecision.decide(stored, file.length(), file.lastModified()) {
                        sha256Of(file)
                    }
                    if (decision == SyncDecision.Action.SKIP_UNCHANGED) {
                        skipped++
                        continue
                    }
                    if (uploadWithRetry(file, relative, remotePath)) {
                        uploaded++
                        consecutiveFailures = 0
                        fingerprints[relative] = FileFingerprint(file.length(), file.lastModified(), null)
                    } else {
                        failed++
                        consecutiveFailures++
                        // 连着好几个都失败，基本是连接不可用了。继续传只会对着
                        // 每个文件各等一遍超时（几百个文件能把一轮拖几十分钟），
                        // 不如抛出去让外层退避 + 重连。
                        if (consecutiveFailures >= ABORT_AFTER_FAILURES) {
                            throw IOException("连续 $consecutiveFailures 个文件上传失败，已中止本轮同步")
                        }
                    }
                }
                if (batchIndex < batches.lastIndex) delay(BATCH_PAUSE_MS)
            }

            val deleted = deleteRemoved(localRelatives, gitignore)

            indexStore.save(mount.id, indexIdentity, fingerprints)
            val outcome = SyncOutcome(uploaded = uploaded, skipped = skipped, deleted = deleted, failed = failed)
            _lastOutcome.value = outcome
            _lastError.value = if (failed > 0) "$failed 个文件上传失败" else null
            _state.value = SyncState.CONNECTED
            outcome
        } catch (e: Exception) {
            // 中途失败也要把索引落盘：已经传上去的文件不能因为后面某个文件失败
            // 而丢掉指纹——那会让下一轮把它们全部重传。
            runCatching { indexStore.save(mount.id, indexIdentity, fingerprints) }
            _state.value = SyncState.FAILED
            _lastError.value = e.message ?: "上传失败"
            throw e
        }
    }

    /**
     * 从远端拉取到本地（「强制下载」用）。
     *
     * 只在用户明确要求时执行：远端覆盖本地是有破坏性的，不能自动发生。
     */
    suspend fun downloadWorkspace(): SyncOutcome = withContext(Dispatchers.IO) {
        _state.value = SyncState.SYNCING
        var downloaded = 0
        var failed = 0
        try {
            val entries = listRemoteRecursively(mount.remotePath)
            val gitignore = loadGitignore(settingsProvider())
            for (entry in entries) {
                if (entry.isDirectory) continue
                val relative = entry.path.removePrefix(mount.remotePath).trimStart('/')
                if (isIgnoredName(relative, gitignore)) continue
                val localFile = File(localRoot, relative)
                runCatching { downloadOne(entry.path, localFile) }
                    .onSuccess {
                        downloaded++
                        fingerprints[relative] = FileFingerprint(localFile.length(), localFile.lastModified(), null)
                    }
                    .onFailure {
                        failed++
                        FileLogger.w(TAG, "下载失败: $relative", it)
                    }
            }
            indexStore.save(mount.id, indexIdentity, fingerprints)
            val outcome = SyncOutcome(downloaded = downloaded, failed = failed)
            _lastOutcome.value = outcome
            _lastError.value = if (failed > 0) "$failed 个文件下载失败" else null
            _state.value = SyncState.CONNECTED
            outcome
        } catch (e: Exception) {
            _state.value = SyncState.FAILED
            _lastError.value = e.message ?: "下载失败"
            throw e
        }
    }

    /** 清空同步索引：下次上传视为全部未同步。 */
    fun clearIndex() {
        fingerprints = mutableMapOf()
        indexStore.clear(mount.id)
        _lastOutcome.value = SyncOutcome()
        _lastError.value = null
    }

    private suspend fun uploadOne(file: File, remotePath: String) {
        when (connection.protocol) {
            RemoteProtocol.SFTP -> ssh.uploadFile(file.absolutePath, remotePath)
            RemoteProtocol.FTP -> ftp.uploadFile(file.absolutePath, remotePath)
        }
    }

    /**
     * 上传一个文件，失败重试。
     *
     * 单文件级别的抖一下（TCP 重传、服务端一时没响应）没必要拖到下一轮同步——
     * 那可能是 30 秒之后的事。
     */
    private suspend fun uploadWithRetry(file: File, relative: String, remotePath: String): Boolean {
        var lastError: Throwable? = null
        for (attempt in 1..UPLOAD_RETRIES) {
            runCatching { uploadOne(file, remotePath) }
                .onSuccess { return true }
                .onFailure { lastError = it }
            if (attempt < UPLOAD_RETRIES) delay(RETRY_PAUSE_MS)
        }
        val message = "上传失败（已尝试 $UPLOAD_RETRIES 次）: $relative"
        val error = lastError
        if (error != null) FileLogger.w(TAG, message, error) else FileLogger.w(TAG, message)
        return false
    }

    private suspend fun downloadOne(remotePath: String, localFile: File) {
        when (connection.protocol) {
            RemoteProtocol.SFTP -> ssh.downloadFile(remotePath, localFile.absolutePath)
            RemoteProtocol.FTP -> ftp.downloadFile(remotePath, localFile.absolutePath)
        }
    }

    private suspend fun listRemoteRecursively(path: String, depth: Int = 0): List<RemoteEntry> {
        if (depth > MAX_DEPTH) return emptyList()
        val entries = when (connection.protocol) {
            RemoteProtocol.SFTP -> ssh.list(connection, path)
            RemoteProtocol.FTP -> ftp.list(connection, path)
        }
        val result = mutableListOf<RemoteEntry>()
        for (entry in entries) {
            result += entry
            if (entry.isDirectory) {
                result += listRemoteRecursively(entry.path, depth + 1)
            }
        }
        return result
    }

    /** 忽略判定：写死的路径段规则 +（开了开关时）工作区自己的 `.gitignore`。 */
    private fun isIgnoredName(relative: String, gitignore: GitignoreMatcher?): Boolean {
        if (ignorePatterns.isNotEmpty()) {
            // 路径段匹配：只写 `build` 就能忽略任意层级的 build 目录。
            val parts = relative.split('/').filter { it.isNotEmpty() }
            if (parts.any { it in ignorePatterns }) return true
        }
        return gitignore?.isIgnored(relative) == true
    }

    /** 读本地根目录的 `.gitignore`。没开开关、文件不存在或没规则时返回 null。 */
    private fun loadGitignore(settings: RemoteSyncSettings): GitignoreMatcher? {
        if (!settings.respectGitignore) return null
        val file = File(localRoot, ".gitignore")
        if (!file.isFile) return null
        return runCatching { GitignoreMatcher(file.readLines()) }
            .onFailure { FileLogger.w(TAG, "读取 .gitignore 失败，本次按无规则处理", it) }
            .getOrNull()
            ?.takeUnless { it.isEmpty() }
    }

    private fun batchSizeOf(settings: RemoteSyncSettings): Int =
        settings.batchSize.coerceIn(RemoteSyncSettings.BATCH_MIN, RemoteSyncSettings.BATCH_MAX)

    /**
     * 收集待上传文件。目录整体命中忽略规则就整棵剪掉：
     * 进了 `node_modules` 再逐个判定，光遍历就够慢。
     */
    private fun collectFiles(
        dir: File,
        gitignore: GitignoreMatcher?,
        out: MutableList<File>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (child in dir.listFiles().orEmpty()) {
            val relative = child.relativeTo(localRoot).invariantSeparatorsPath
            if (isIgnoredName(relative, gitignore)) continue
            when {
                child.isDirectory -> collectFiles(child, gitignore, out, depth + 1)
                child.isFile -> out += child
            }
        }
    }

    /**
     * 清掉远端上「本地已删掉」的文件，返回删除个数。
     *
     * 只处理**同步索引里记着的**路径——那些是我们自己传上去的文件，本地删了就该跟着删。
     * 远端原有、不在此列的目录内容一律不碰：那是用户自己放在那儿的，不属于本挂载的管辖范围。
     * 这条约束也是唯一能防止「一次误删整个远端目录」的屏障。
     */
    private suspend fun deleteRemoved(
        localRelatives: Set<String>,
        gitignore: GitignoreMatcher?,
    ): Int {
        val removed = fingerprints.keys.filter {
            it !in localRelatives && !isIgnoredName(it, gitignore)
        }
        var deleted = 0
        for (relative in removed) {
            val remotePath = joinRemote(mount.remotePath, relative)
            // 远端已经没了（别处删过、或上次删成功但索引没来得及存）也算清干净，
            // 直接从索引去掉——否则每轮同步都会对着一个不存在的文件重试一次。
            if (!remoteExists(remotePath)) {
                fingerprints.remove(relative)
                continue
            }
            runCatching { deleteOne(remotePath) }
                .onSuccess {
                    deleted++
                    fingerprints.remove(relative)
                }
                .onFailure { FileLogger.w(TAG, "删除远端失败: $relative", it) }
        }
        return deleted
    }

    private suspend fun remoteExists(path: String): Boolean = when (connection.protocol) {
        RemoteProtocol.SFTP -> ssh.exists(path)
        RemoteProtocol.FTP -> ftp.exists(path)
    }

    private suspend fun deleteOne(remotePath: String) {
        // 只删文件：索引里记的都是文件，目录留着也无害（FTP 的 rmdir 还是非递归的）。
        when (connection.protocol) {
            RemoteProtocol.SFTP -> ssh.delete(remotePath, isDirectory = false)
            RemoteProtocol.FTP -> ftp.delete(remotePath, isDirectory = false)
        }
    }

    private fun joinRemote(base: String, relative: String): String =
        if (base.endsWith("/")) base + relative else "$base/$relative"

    private companion object {
        const val TAG = "RemoteSyncEngine"

        /** 自动同步间隔。太短会一直占带宽，太长则改了文件半天不同步。 */
        const val AUTO_SYNC_INTERVAL_MS = 30_000L

        /** 递归的深度上限：防远端有循环软链、本地有深目录导致爆栈。 */
        const val MAX_DEPTH = 12

        /** 批与批之间的间隔：让出通道，别把网络一直占满。 */
        const val BATCH_PAUSE_MS = 120L

        /** 上传单个文件的重试次数（含首次）。 */
        const val UPLOAD_RETRIES = 3

        /** 单文件重试前的等待。 */
        const val RETRY_PAUSE_MS = 800L

        /** 连着这么多个文件都失败，就认为连接已经不可用，中止本轮。 */
        const val ABORT_AFTER_FAILURES = 5

        /** 连续失败时同步间隔的退避上限。 */
        const val MAX_RETRY_INTERVAL_MS = 300_000L
    }
}
