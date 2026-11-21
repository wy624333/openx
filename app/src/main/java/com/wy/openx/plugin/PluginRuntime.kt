package com.wy.openx.plugin

import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.compat.CompatHostBridge
import com.wy.openx.plugin.compat.OperitCompat
import com.wy.openx.tools.JsPackageManager
import com.wy.openx.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件运行时：把 [PluginStore] 里落盘的插件脚本喂给 QuickJS，注册成 AI 可调用的工具。
 *
 * 底层复用项目已有的 [JsPackageManager]（QuickJS 引擎 + 工具桥接早就写好了，
 * 之前只是没有任何地方调用它）。这一层负责：
 *   1. 扫描已启用插件 → 读 main.js → 交给 JsPackageManager 加载
 *   2. 把加载出的工具注册进 [ToolRegistry]
 *   3. 记住「哪个插件注册了哪些工具」，卸载时按插件粒度撤掉
 *
 * ## ⚠ 包名（踩过的坑）
 * [JsPackageManager] 的包名是**工具名第一个下划线前的前缀**，不是我们传进去的 pluginId，
 * 而且基名撞车时它会追加 `#2`、`#3`…（否则后加载的插件会把前一个的包整条覆盖）。
 * 所以**必须存 [JsPackageManager.load] 返回的 [JsPackageManager.LoadedPackage.pkgName]**
 * 并原样传回 `unload()`；自己按工具名反推只能拿到基名、拿不到后缀，卸载就找不到条目。
 *
 * ## 线程模型
 * JsPackageManager 内部是单线程 QuickJS 运行时。这里有**两层**：
 *  - [lock] —— 插件之间的 load/unload 顺序（管理页快速点开关时不乱套）；
 *  - `JsPackageManager.engineLock` —— 引擎本身，覆盖**工具执行**（AI 调插件工具时）与
 *    load/unload 的并发。只靠本类的 [lock] 护不住执行路径，因为 execute 不经过这里。
 */
class PluginRuntime(
    private val context: android.content.Context,
    private val store: PluginStore,
    private val registry: ToolRegistry
) {

    companion object {
        private const val TAG = "PluginRuntime"
    }

    private val js = JsPackageManager()
    private val lock = Any()
    private val started = AtomicBoolean(false)

    /** 宿主能力桥对象；当前无内置桥（TTS 示例已移除），保留接口供后续接入。 */
    @Volatile private var hostBridge: Any? = null

    /** 注册宿主能力对象，供插件 JS 通过 NativeInterface 调用。 */
    fun setHostBridge(bridge: Any?) { hostBridge = bridge }

    /** pluginId → 已授权权限集（operit 兼容桥据此做运行时校验）。 */
    private val pluginPermissions = java.util.concurrent.ConcurrentHashMap<String, Set<PluginPermission>>()

    /** Operit 兼容插件调 `Tools.*` 时走的宿主桥。 */
    private val compatBridge: CompatHostBridge by lazy {
        // 具名传参：尾部 lambda 会被当成最后一个参数（terminalMaxTimeoutMs: Long）。
        // 工具轨道的命令上限用默认 45s（小于本引擎 60s 看门狗）。
        CompatHostBridge(
            context = context,
            permissionsOf = { id -> pluginPermissions[id].orEmpty() }
        )
    }

    /** 宿主桥门面 JS（assets/plugins/_shared/host_bridge.js）；缺失时为 null。 */
    private val hostBridgeScript: String? by lazy {
        runCatching {
            context.assets.open("plugins/_shared/host_bridge.js")
                .bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** pluginId → 该插件注册的工具名（卸载时用）。 */
    private val pluginTools = mutableMapOf<String, List<String>>()

    /** pluginId → 在 JsPackageManager 里的包名（下划线前缀，通常≠pluginId）。 */
    private val pluginJsPkg = mutableMapOf<String, String>()

    /** 插件 id → 加载错误。管理页据此显示红字。 */
    private val pluginErrors = mutableMapOf<String, String>()

    /**
     * operit 兼容包 registerNavigationEntry 收集到的侧边栏入口，reload 时重建。
     *
     * 存内存不落盘：manifest 的 quickActions 是静态字段，而导航入口要执行 main.js
     * 才能拿到；每次 reload 重收集，与「兼容层改进不需重新导入」的策略一致。
     */
    private val pluginNavActions = mutableMapOf<String, List<PluginQuickAction>>()

    /** 取某插件由 registerNavigationEntry 生成的侧边栏入口（空 = 没有）。 */
    fun navActionsOf(pluginId: String): List<PluginQuickAction> = pluginNavActions[pluginId].orEmpty()

    /**
     * 启动：加载所有已启用插件。
     *
     * **幂等**，且**失败不阻断**——单个插件脚本写错不能拖垮 App 启动。
     * 调用点应在 ToolRegistry.init() 之后（需要 builtinNames 快照已采集）。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        reload()
    }

    /** 重新扫描并加载全部已启用插件（先撤掉已加载的）。 */
    fun reload() {
        synchronized(lock) {
            // 先撤旧的，避免重复注册（ToolRegistry.register 会打警告并覆盖）
            pluginTools.keys.toList().forEach { unloadInternal(it) }
            pluginErrors.clear()
            pluginNavActions.clear()
            PluginHookManager.clear()
            // 钩子通道：主链路（发消息 / 工具执行）通过它回调进 QuickJS。
            // 幂等，重入也无害——逐次 reload 换掉的只是同一个 js 实例的引用。
            PluginHookManager.setInvoker { pkg, hook, args -> js.callHook(pkg, hook, args) }

            // 1. 注入宿主桥：让插件里能用 __openx.xxx()。
            //    桥脚本（assets/plugins/_shared/host_bridge.js）当前未内置，
            //    所以这里只绑 native 接口；后续要加宿主能力时再补桥脚本。
            val bridge = hostBridge ?: compatBridge
            if (bridge != null) {
                runCatching { js.bindHostBridge(bridge, hostBridgeScript) }
                    .onFailure { e -> FileLogger.e(TAG, "宿主桥注入失败: ${e.message}") }
            }

            val enabled = store.loadEnabled()
            for (plugin in enabled) {
                val id = plugin.manifest.id
                pluginPermissions[id] = plugin.manifest.grantedPermissions
                // operit 兼容插件：入口脚本由兼容层现场生成（不读 main 指向的文件），
                // 这样兼容层的改动能直接作用于已安装的插件，不必让用户重新导入。
                val src = if (plugin.manifest.compat == OperitCompat.COMPAT_TAG) {
                    runCatching { OperitCompat.buildEntrySource(java.io.File(plugin.dirPath), plugin.manifest) }
                        .onFailure { FileLogger.e(TAG, "插件 $id 兼容入口生成失败: ${it.message}") }
                        .getOrNull()
                } else {
                    store.readEntrySource(plugin)
                }
                if (src.isNullOrBlank()) {
                    // 纯界面插件是合法的：只提供 ui/*.js、没有任何后端脚本。
                    // 界面由 PluginUiScreen 单独驱动，不需要往 ToolRegistry 加东西。
                    // 不特判的话这类插件会被判成「入口脚本不存在」，管理页一直挂红。
                    val uiRel = (plugin.manifest.ui ?: "").trim()
                    if (uiRel.isNotEmpty() && java.io.File(plugin.dirPath, uiRel).isFile) {
                        FileLogger.i(TAG, "插件 $id 是纯界面插件（无入口脚本），跳过工具注册")
                        continue
                    }
                    // operit 包很多是纯 UI 的（侧边栏/仪表盘）。兼容层目前只做工具，
                    // 这类包不报错也不挂红，管理页显示正常但不提供工具。
                    if (plugin.manifest.compat == OperitCompat.COMPAT_TAG) {
                        FileLogger.i(TAG, "插件 $id 是 operit 包但没有声明工具，跳过（UI 兼容待后续阶段）")
                        continue
                    }
                    pluginErrors[id] = "入口脚本不存在：${plugin.manifest.main}"
                    continue
                }
                val result = runCatching { js.load(src) }
                val loaded = result.getOrNull()?.getOrNull()
                if (result.isFailure || loaded == null) {
                    pluginErrors[id] = result.exceptionOrNull()?.message ?: "脚本加载失败"
                    continue
                }
                val metas = loaded.tools
                // 没申请 hook 权限就不给挂：声明了也不生效，只记一条日志。
                // 否则「装了插件就自动能偷看每条消息」—— 权限体系形同虚设。
                val granted = PluginPermission.parse(plugin.manifest.permissions)
                val hooks = if (PluginPermission.HOOK in granted) {
                    loaded.hooks
                } else {
                    if (loaded.hooks.isNotEmpty()) {
                        FileLogger.w(TAG, "插件 $id 声明了钩子 ${loaded.hooks} 但未申请 hook 权限，已忽略")
                    }
                    emptyList()
                }
                // 七期起允许「只挂钩子、不提供工具」的插件：它不往 ToolRegistry
                // 里加任何东西，只在聊天流程里做手脚。两边都空才是真的写错了。
                if (metas.isEmpty() && hooks.isEmpty()) {
                    pluginErrors[id] = "脚本既没声明工具（\$ptools）也没声明钩子（\$phooks）"
                    continue
                }
                val names = metas.map { it.name }
                pluginTools[id] = names
                // operit 导航入口 → 侧边栏快捷项。只取 surface 含 main_sidebar 的：
                // 别的 surface（设置页/聊天菜单）OpenX 没有对应位置，挂上也是死按钮。
                if (plugin.manifest.compat == OperitCompat.COMPAT_TAG && loaded.navEntries.isNotEmpty()) {
                    val actions = loaded.navEntries.mapNotNull { nav -> nav.toQuickAction() }
                    if (actions.isNotEmpty()) {
                        pluginNavActions[id] = actions
                        FileLogger.i(TAG, "插件 $id 收集到 ${actions.size} 个侧边栏入口：${actions.map { it.label }}")
                    }
                }
                // 用 load 返回的**真实包名**。按工具名反推只在无冲突时才相等；
                // 基名撞车时 load 会加 #N 后缀，反推拿到的是基名 → unload 找不到条目。
                pluginJsPkg[id] = loaded.pkgName
                PluginHookManager.register(id, loaded.pkgName, hooks)
                FileLogger.i(TAG, "插件 $id 加载成功 → jsPkg=${loaded.pkgName}，工具：$names，钩子：$hooks")
            }

            // 统一注册（JsPackageManager 内部按包管理 handler，registerWith 会全部挂上）
            runCatching { js.registerWith(registry) }
                .onFailure { FileLogger.e(TAG, "工具注册失败: ${it.message}") }

            // 全部插件就位后再发 onAppStart：插件在里面访问别的插件/宿主状态时，
            // 这时才保证看得全。
            runCatching { PluginHookManager.appStart() }
                .onFailure { FileLogger.e(TAG, "onAppStart 触发失败: ${it.message}") }
        }
    }

    /**
     * 卸载单个插件：从 JS 引擎删 handler，并从 ToolRegistry 摘掉工具。
     */
    private fun unloadInternal(pluginId: String) {
        // 只挂钩子的插件没有工具，所以这里不能因 pluginTools 缺条目就提前返回。
        val names = pluginTools.remove(pluginId) ?: emptyList()
        val jsPkg = pluginJsPkg.remove(pluginId)
            // 兜底：万一包名表丢了，按同样的规则重推一次
            ?: names.firstOrNull()?.let { jsPackageNameOf(it) }
        PluginHookManager.unregister(pluginId)
        if (jsPkg != null) {
            runCatching { js.unload(jsPkg) }
                .onFailure { FileLogger.e(TAG, "JS 侧卸载 $jsPkg 失败: ${it.message}") }
        }
        // 把工具从注册表摘掉：否则模型仍能看到它，调用只会得到 handler-not-found
        names.forEach { registry.remove(it) }
        FileLogger.i(TAG, "插件 $pluginId 已卸载，移除工具：$names")
    }

    /** 当前注册出的插件工具名（供调试/统计）。 */
    fun toolNamesOf(pluginId: String): List<String> = pluginTools[pluginId].orEmpty()

    fun errorOf(pluginId: String): String? = pluginErrors[pluginId]

    /** 已加载插件数。 */
    fun loadedCount(): Int = pluginTools.size

    fun close() {
        synchronized(lock) {
            // 引擎一关，钩子通道就没有意义了；不清掉的话，主链路下次触发会往一个已销毁的引擎里写。
            PluginHookManager.setInvoker(null)
            PluginHookManager.clear()
            runCatching { js.close() }
        }
    }

    /**
     * 包名基名推导 —— **仅作 `pluginJsPkg` 缺失时的兜底**，正常路径一律用
     * [JsPackageManager.LoadedPackage.pkgName]（见 [reload]）。
     *
     * 取第一个工具名里第一个下划线之前的部分。⚠ 旧注释说"无下划线则返回空串"是错的：
     * Kotlin `substringBefore` 找不到分隔符时返回**原串**。真正返回 `js` 的情形只有
     * 「一个工具都没有」，而那种情况 [reload] 早就 continue 了，走不到这里。
     */
    private fun jsPackageNameOf(firstToolName: String): String =
        firstToolName.substringBefore('_')
}

/**
 * 把一个 .js 源文件直接当单文件插件安装。
 * 用于「用户手里只有一个 xxx.js」的常见情况——自动补一个最小 manifest。
 */
fun installSingleFileJs(store: PluginStore, fileName: String, source: String): java.io.File {
    val base = fileName.substringBeforeLast('.').ifBlank { "js_plugin" }
    val manifest = PluginManifest(
        id = "local.$base",
        name = base,
        version = "1.0.0",
        description = "从单文件 JS 导入",
        main = "main.js"
    )
    return store.install(manifest, source)
}
