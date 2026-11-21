package com.wy.openx.remote

import android.content.Context
import com.wy.openx.container.FileLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程工作区的统一入口：按协议分派到 SFTP / FTP 实现。
 *
 * 界面只跟这一层打交道，不必到处判断 `if (protocol == SFTP)`；
 * 协议差异（路径语义、认证、编码）都收敛在各自的客户端里。
 *
 * 同一时刻只维持**一条**连接：手机上同时挂多个远程工作区会互相抢资源，
 * 而且用户实际只会看一个。切换服务器时先断开旧的。
 */
class RemoteManager(private val context: Context) {

    private val store = RemoteStore(context)
    private val keyStore = RemoteKeyStore(context)
    private val ssh = RemoteSshClient(context, keyStore)

    /** AI 远程执行后端，按连接 id 缓存（见 [execEngineFor]）。 */
    private val execEngines = ConcurrentHashMap<String, RemoteSshEngine>()
    private val ftp = RemoteFtpClient()

    /** 已保存的登录密钥。 */
    val keys get() = keyStore.keys

    suspend fun loadKeys() = keyStore.load()

    suspend fun saveKey(key: SavedSshKey) = keyStore.save(key)

    suspend fun deleteKey(id: String) = keyStore.delete(id)

    /** 同步引擎的宿主作用域：跨页面存活，所以放在这里而不是 Composable 里。 */
    private val syncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** 运行中的同步引擎，key 为挂载 id。 */
    private val syncEngines = java.util.concurrent.ConcurrentHashMap<String, RemoteSyncEngine>()

    private val indexStore by lazy {
        SyncIndexStore(java.io.File(context.filesDir, "sync_index"))
    }

    /** 自动同步的忽略规则（路径段）。构建产物、依赖目录不该传上去。 */
    @Volatile
    var syncIgnorePatterns: Set<String> = DEFAULT_SYNC_IGNORES
        private set

    fun setSyncIgnorePatterns(patterns: Set<String>) {
        syncIgnorePatterns = patterns
    }

    private val syncSettingsStore by lazy { RemoteSyncSettingsStore(context) }

    /**
     * 同步行为设置（.gitignore 遵循、分批上限）。
     *
     * 首次访问自己读盘，不依赖调用方先 [loadSyncSettings]：同步引擎是常驻的，
     * 可能早于设置页加载就启动，那时用默认值会静默丢掉用户改过的设置。
     */
    @Volatile
    private var cachedSyncSettings: RemoteSyncSettings? = null

    val syncSettings: RemoteSyncSettings
        get() = cachedSyncSettings ?: syncSettingsStore.load().also { cachedSyncSettings = it }

    /** 从磁盘重新载入同步设置。 */
    fun loadSyncSettings(): RemoteSyncSettings = syncSettingsStore.load().also {
        cachedSyncSettings = it
    }

    fun saveSyncSettings(settings: RemoteSyncSettings) {
        cachedSyncSettings = settings
        syncSettingsStore.save(settings)
    }

    private val _activeState = MutableStateFlow(RemoteActiveState())
    val activeState: StateFlow<RemoteActiveState> = _activeState.asStateFlow()

    /** 当前已连接的服务器 id；null 表示未连接。 */
    val activeId: String?
        get() = _activeState.value.connectionId?.takeIf { _activeState.value.state == RemoteConnState.CONNECTED }

    val connections get() = store.connections
    val mounts get() = store.mounts

    @Volatile
    private var loadedOnce = false

    suspend fun load() {
        store.load()
        loadedOnce = true
    }

    /**
     * 确保连接/挂载列表已加载（幂等）。
     *
     * 这两份列表不只在设置页用：[RemoteWorkspaceRegistry] 靠它们判断「某个路径是否属于远程工作区」。
     * 若用户没打开过设置页就调用文件工具，列表为空 → 路由一律判成「本地」→ 读写静默落到本地旧副本上。
     * 那比功能没做还危险，所以路由层每次取列表前先补这一次加载。
     */
    fun ensureLoaded() {
        if (loadedOnce) return
        runCatching { runBlocking { load() } }
    }

    suspend fun save(connection: RemoteConnection) = store.upsert(connection)

    suspend fun delete(id: String) {
        if (_activeState.value.connectionId == id) disconnect()
        // 连带停掉该连接下所有挂载的同步，否则它们会在连接消失后一直报错
        store.mounts.value.filter { it.connectionId == id }.forEach { stopMount(it.id) }
        closeExecEngine(id)
        store.delete(id)
    }

    // ===== 挂载与同步 =====

    suspend fun saveMount(mount: RemoteMount) = store.upsertMount(mount)

    suspend fun deleteMount(id: String) {
        stopMount(id)
        indexStore.clear(id)
        store.deleteMount(id)
    }

    suspend fun reorderMounts(ids: List<String>) = store.reorderMounts(ids)

    /** 取（或创建）某挂载的同步引擎。 */
    fun syncEngineFor(mount: RemoteMount, connection: RemoteConnection): RemoteSyncEngine =
        syncEngines.getOrPut(mount.id) {
            // 本地路径是容器路径（/root/workspace/...），同步要的是宿主 File
            val hostRoot = com.wy.openx.tools.AppContextHolder.containerEngine
                ?.toHostFileIfContainerPath(mount.localMountPath)
                ?: java.io.File(mount.localMountPath)
            RemoteSyncEngine(
                context = context,
                mount = mount,
                connection = connection,
                indexStore = indexStore,
                localRoot = hostRoot,
                ignorePatterns = syncIgnorePatterns,
                settingsProvider = { syncSettings },
                keyStore = keyStore,
            )
        }

    /** 启动一条挂载：连接 + （可选）开自动同步。 */
    suspend fun startMount(mount: RemoteMount) {
        val connection = store.find(mount.connectionId) ?: error("连接不存在")
        val engine = syncEngineFor(mount, connection)
        engine.connect()
        if (mount.autoConnect) engine.startAutoSync(syncScope)
    }

    fun stopMount(mountId: String) {
        val engine = syncEngines.remove(mountId) ?: return
        syncScope.launch { runCatching { engine.disconnect() } }
    }

    /** 关闭全部同步（进程退出/清空配置时）。 */
    fun stopAllMounts() {
        syncEngines.keys.toList().forEach { stopMount(it) }
    }

    /**
     * 同步设置变了：丢弃现有引擎，下次启动挂载时按新设置重建。
     *
     * 引擎把 localRoot / ignorePatterns / settingsProvider 都固化成构造参数，
     * 就地改字段容易造成「一半旧一半新」的中间态，重建更干净。
     */
    fun resetSyncEngines() {
        stopAllMounts()
    }

    /** 当前连接状态；未连接时为 [RemoteConnState.IDLE]。 */
    fun stateOf(id: String): RemoteConnState = when {
        activeId != id -> RemoteConnState.IDLE
        else -> currentProtocolClientState(id)
    }

    private fun currentProtocolClientState(id: String): RemoteConnState {
        val config = store.find(id) ?: return RemoteConnState.IDLE
        return when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.state
            RemoteProtocol.FTP -> ftp.state
        }
    }

    /** 连接指定服务器；失败时抛异常（消息已中文化，可直接显示）。 */
    suspend fun connect(config: RemoteConnection) {
        if (activeId != null && activeId != config.id) disconnect()
        RemoteTrafficMonitor.reset()
        _activeState.value = RemoteActiveState(config.id, RemoteConnState.CONNECTING)
        runCatching {
            when (config.protocol) {
                RemoteProtocol.SFTP -> ssh.connect(config)
                RemoteProtocol.FTP -> ftp.connect(config)
            }
        }.onSuccess {
            _activeState.value = RemoteActiveState(config.id, RemoteConnState.CONNECTED)
        }.onFailure { error ->
            _activeState.value = RemoteActiveState(config.id, RemoteConnState.FAILED)
            throw error
        }
    }

    suspend fun disconnect() {
        runCatching { ssh.disconnect() }
        runCatching { ftp.disconnect() }
        _activeState.value = RemoteActiveState()
    }

    suspend fun list(config: RemoteConnection, path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.list(config, path)
            RemoteProtocol.FTP -> ftp.list(config, path)
        }
    }

    suspend fun browserStartPath(config: RemoteConnection): String = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.homePath()
            RemoteProtocol.FTP -> config.remotePath.ifBlank { "/" }
        }
    }

    suspend fun readText(config: RemoteConnection, path: String): String = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.readText(config, path)
            RemoteProtocol.FTP -> ftp.readText(config, path)
        }
    }

    suspend fun writeText(config: RemoteConnection, path: String, content: String) = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.writeText(path, content)
            RemoteProtocol.FTP -> ftp.writeText(path, content)
        }
    }

    suspend fun mkdir(config: RemoteConnection, path: String) = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.mkdir(path)
            RemoteProtocol.FTP -> ftp.mkdir(path)
        }
    }

    suspend fun delete(config: RemoteConnection, path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.delete(path, isDirectory)
            RemoteProtocol.FTP -> ftp.delete(path, isDirectory)
        }
    }

    suspend fun rename(config: RemoteConnection, from: String, to: String) = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.rename(from, to)
            RemoteProtocol.FTP -> ftp.rename(from, to)
        }
    }

    suspend fun exists(config: RemoteConnection, path: String): Boolean = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.exists(path)
            RemoteProtocol.FTP -> ftp.exists(path)
        }
    }

    /** 服务端复制；FTP 只能复制文本文件，目录复制不支持。 */
    suspend fun copyOnServer(config: RemoteConnection, from: String, to: String, isDirectory: Boolean) =
        withContext(Dispatchers.IO) {
            when (config.protocol) {
                RemoteProtocol.SFTP -> ssh.copyOnServer(from, to, isDirectory)
                RemoteProtocol.FTP -> {
                    if (isDirectory) error("FTP 不支持目录复制，请使用 SFTP")
                    val text = ftp.readText(config, from)
                    ftp.writeText(to, text)
                }
            }
        }

    /** 剪切 = 同服务器上改名/移动。 */
    suspend fun moveOnServer(config: RemoteConnection, from: String, to: String) = rename(config, from, to)

    suspend fun deleteRecursively(config: RemoteConnection, path: String, isDirectory: Boolean) =
        withContext(Dispatchers.IO) {
            when (config.protocol) {
                RemoteProtocol.SFTP -> ssh.deleteRecursively(path)
                RemoteProtocol.FTP -> ftp.delete(path, isDirectory)
            }
        }

    /** 探测当前连接是否还活着。 */
    suspend fun ping(config: RemoteConnection): Boolean = withContext(Dispatchers.IO) {
        when (config.protocol) {
            RemoteProtocol.SFTP -> ssh.ping(config)
            RemoteProtocol.FTP -> ftp.ping()
        }
    }

    /** 清除已保存的主机指纹（服务器换密钥后用）。 */
    fun forgetHostKey(host: String, port: Int) {
        context.getSharedPreferences("remote_host_keys", Context.MODE_PRIVATE)
            .edit().remove("$host:$port").apply()
    }

    /**
     * 用户确认信任某个主机指纹后落盘。
     *
     * [fingerprint] 由连接阶段抛出 [RemoteHostKeyPendingException] 时带上，
     * 不重新去连一次取：那样中间若有变化，信任的就不是用户看到的那一个了。
     */
    fun trustHostKey(host: String, port: Int, fingerprint: String) {
        context.getSharedPreferences("remote_host_keys", Context.MODE_PRIVATE)
            .edit().putString("$host:$port", fingerprint).apply()
        FileLogger.i("RemoteManager", "已信任主机指纹 $host:$port → $fingerprint")
    }

    /**
     * 测试一个还没保存的配置能不能连上。
     *
     * 用**临时客户端**而不是 [ssh]/[ftp]：那两个是当前正在用的连接，
     * 拿去测试会把用户的浏览会话断掉。
     *
     * 返回 Result：失败时带异常消息，界面简要显示原因；完整堆栈写日志。
     */
    suspend fun testConnection(config: RemoteConnection): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (config.protocol) {
                RemoteProtocol.SFTP -> {
                    val client = RemoteSshClient(context, keyStore)
                    try {
                        client.connect(config)
                        client.list(config, config.remotePath)
                    } finally {
                        runCatching { client.disconnect() }
                    }
                }
                RemoteProtocol.FTP -> {
                    val client = RemoteFtpClient()
                    try {
                        client.connect(config)
                        client.list(config, config.remotePath)
                    } finally {
                        runCatching { client.disconnect() }
                    }
                }
            }
            Unit
        }.onFailure { error ->
            FileLogger.e(
                "RemoteManager",
                "测试连接失败 ${config.protocol}/${config.host}:${config.port} as ${config.username}",
                error
            )
        }
    }

    fun hostKeyOf(host: String, port: Int): String? =
        context.getSharedPreferences("remote_host_keys", Context.MODE_PRIVATE)
            .getString("$host:$port", null)

    /** 全部已保存的主机指纹（`host:port` to `SHA256:...`）。 */
    fun savedHostKeys(): Map<String, String> =
        context.getSharedPreferences("remote_host_keys", Context.MODE_PRIVATE)
            .all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()

    /**
     * 可选的本地工作区（挂载编辑器下拉用）：已有项目 + 工作区根。
     *
     * 给出的是**容器路径**：挂载配置里存的就是这个口径（同步时再翻译成宿主 File），
     * 这里如果给宿主路径，用户看到和存进去的就对不上了。
     */
    fun localWorkspaces(): List<Pair<String, String>> {
        val projectsRoot = java.io.File(com.wy.openx.container.Workspace.root(context), "projects")
        val projects = projectsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { it.name to "${com.wy.openx.container.Workspace.CONTAINER_PROJECTS_PATH}/${it.name}" }
        return projects + ("工作区根" to com.wy.openx.container.Workspace.CONTAINER_PATH)
    }

    /**
     * 用**临时连接**列一次目录，不动当前正在用的那条。
     *
     * 浏览目录是为了填配置，顺手把用户正在看的服务器断掉就不对了——
     * [connect] 的语义是「切换到这台」，两者不是一回事。
     */
    /**
     * 取一台服务器的命令执行后端（AI 远程执行用）；服务器不存在则 null。
     *
     * 按 id 缓存、每个连接一个实例：连接建立与断线重连都由引擎自己管，
     * 不复用浏览用的那条通道——[connect] 是「切换当前连接」的语义，
     * 拿它跑 AI 命令会把用户正在看的服务器顶掉。
     */
    fun execEngineFor(connectionId: String): RemoteSshEngine? {
        val config = connections.value.firstOrNull { it.id == connectionId } ?: return null
        return execEngines.getOrPut(connectionId) { RemoteSshEngine(context, keyStore, config) }
    }

    private suspend fun closeExecEngine(connectionId: String) {
        execEngines.remove(connectionId)?.let { runCatching { it.disconnect() } }
    }

    suspend fun listOnce(config: RemoteConnection, path: String): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            when (config.protocol) {
                RemoteProtocol.SFTP -> {
                    val client = RemoteSshClient(context, keyStore)
                    try {
                        client.connect(config)
                        client.list(config, path)
                    } finally {
                        runCatching { client.disconnect() }
                    }
                }
                RemoteProtocol.FTP -> {
                    val client = RemoteFtpClient()
                    try {
                        client.connect(config)
                        client.list(config, path)
                    } finally {
                        runCatching { client.disconnect() }
                    }
                }
            }
        }

    private companion object {
        /**
         * 默认不同步的路径段。
         *
         * 构建产物、依赖、版本库内部数据传上去既慢又没用，还会把远端目录弄乱；
         * `.git` 传上去可能覆盖远端的仓库状态。用户可在同步设置里改。
         */
        val DEFAULT_SYNC_IGNORES = setOf(
            "build", ".gradle", "node_modules", ".git", ".idea", "__pycache__", ".DS_Store"
        )
    }
}
