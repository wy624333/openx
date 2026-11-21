package com.wy.openx.plugin.ui

import android.content.Context
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.InstalledPlugin
import com.wy.openx.plugin.PluginJsStdlib
import com.wy.openx.plugin.PluginPermission
import com.wy.openx.plugin.PluginStore
import com.wy.openx.tools.ToolRegistry
import com.wy.openx.quickjs.QuickJsEngine
import java.io.File

/**
 * 插件 UI 的运行时容器：**一次打开插件界面 = 一个 [PluginUiSession]**。
 *
 * 职责：
 *  1. 建一个独立 QuickJS 引擎（每个界面实例一个，避免插件间状态串台）
 *  2. 先注入 UI 运行时脚本（`ctx.UI` / `useState`），再注入插件自己的 UI 脚本
 *  3. 渲染 → 得到节点树；用户操作 → 回调 JS → 重新渲染
 *
 * ## 线程模型（关键）
 * QuickJS 上下文必须在**同一个线程**上使用。这里所有操作都串行化到 [lock] 里，
 * 且渲染调用由 UI 层通过 `withContext(Dispatchers.Default)` 触发 —— 不在主线程跑 JS。
 *
 * ## 生命周期
 * 界面退出时必须 [close]，否则引擎泄漏（QuickJS 的 native 资源不会自动回收）。
 */
class PluginUiSession(
    private val context: Context,
    private val plugin: InstalledPlugin,
    private val store: PluginStore,
    private val toolRegistry: ToolRegistry?,
    private val onToast: (String) -> Unit,
    /** 服务启停成功后回调（面板里启停服务后，管理页/卡片状态需要同步刷新）。 */
    private val onServiceChanged: () -> Unit = {},
    /** 宿主重渲染请求：JS 里 useState setter 调用时触发。 */
    private val onRerenderRequest: () -> Unit,
    /** 需要 Activity 的能力（文件/图片选择）；非交互场景可为 null。 */
    private val hostActions: PluginHostActions? = null,
    /** 跑挂起请求（选图/选文件等）的后台作用域。 */
    private val hostScope: kotlinx.coroutines.CoroutineScope? = null,
    /**
     * 当前渲染容器：`panel`（插件面板）或 `overlay`（桌面浮窗）。
     *
     * 插件侧读 `ctx.container`。不给这个标识的话，同一份 ui 文件在两个容器里
     * 没法区分身份 —— 只能拿 `overlay.isShowing()` 猜，而浮窗开着时面板也会读到 true。
     */
    private val container: String = "panel"
) {

    companion object {
        private const val TAG = "PluginUiSession"

        /** console 环形缓冲上限：插件写日志写疯了也不会把内存吃光。 */
        private const val MAX_CONSOLE_LINES = 300
    }

    private val lock = Any()
    // UI 轨道用更长的看门狗：用户本来就在等进度条（插件装依赖可耗时数分钟）。
    // 兼容层单条命令的上限（290s）小于它，否则会命中看门狗打断整个引擎。
    private var engine = QuickJsEngine(QuickJsEngine.UI_EXECUTION_TIMEOUT_MS).also { attachConsole(it) }
    private var bridge: PluginUiHostBridge? = null
    private var started = false

    /** 插件脚本的 console 输出（环形缓冲，调试面板展示）。 */
    private val consoleLines = ArrayDeque<String>()

    private fun attachConsole(e: QuickJsEngine) {
        e.setConsoleSink { level, message ->
            synchronized(consoleLines) {
                if (consoleLines.size >= MAX_CONSOLE_LINES) consoleLines.removeFirst()
                val prefix = when (level) {
                    "error" -> "E"
                    "warn" -> "W"
                    "debug" -> "D"
                    else -> "I"
                }
                consoleLines.addLast("$prefix  $message")
            }
        }
    }

    /** 取 console 日志（调试面板用），最新的在后面。 */
    fun consoleText(): String = synchronized(consoleLines) {
        consoleLines.joinToString("\n")
    }

    fun clearConsole() = synchronized(consoleLines) { consoleLines.clear() }

    /** 最近一次渲染的 state 快照（action 分发时回传给 JS）。 */
    @Volatile
    private var lastState: Map<String, Any?> = emptyMap()

    /** 权限集。 */
    val permissions: Set<PluginPermission> get() = plugin.manifest.grantedPermissions

    /** UI 入口脚本文件。 */
    private fun uiFile(): File? {
        val rel = plugin.manifest.ui.trim()
        if (rel.isEmpty()) return null
        val f = File(plugin.dirPath, rel)
        // 防上跳：UI 脚本必须在插件目录内
        val root = File(plugin.dirPath).canonicalPath
        return if (f.isFile && f.canonicalPath.startsWith(root)) f else null
    }

    /** 是否具备打开界面的条件。 */
    fun canOpen(): Boolean = uiFile() != null

    /**
     * 启动会话并完成首次渲染。
     * @return 渲染结果；null 表示启动失败（错误已通过 [lastError] 暴露）
     */
    fun start(): UiRenderResult? = synchronized(lock) {
        if (started) {
            lastRendered
        } else {
            started = true
            doStartLocked()
        }
    }

    /** 实际的启动流程。失败时设 [lastError] 并返回 null。 */
    private fun doStartLocked(): UiRenderResult? {
        val rel = plugin.manifest.ui.trim()
        val file = uiFile()
        if (file == null) {
            // 两种失败长得像但原因不同：字段没写 vs 写了但文件不在，分开提示免得误导。
            lastError = if (rel.isEmpty()) {
                "插件未声明 ui 入口（plugin.json 缺少 ui 字段）"
            } else {
                "ui 入口文件不存在：$rel（插件目录不完整，检查文件是否都被正确释放/导入）"
            }
            return null
        }
        // operit 包的 UI 脚本是 tsc 产物（带 require/exports），直接 eval 会挂在
        // require 上；换成兼容层生成的装载脚本（先注入 CommonJS loader 与 Tools 门面，
        // 再把界面函数挂到 globalThis.Screen）。
        val source = if (plugin.manifest.compat == com.wy.openx.plugin.compat.OperitCompat.COMPAT_TAG) {
            runCatching {
                com.wy.openx.plugin.compat.OperitCompat.buildUiEntrySource(java.io.File(plugin.dirPath), rel)
            }.onFailure { FileLogger.e(TAG, "生成兼容 UI 装载脚本失败: ${it.message}") }
                .getOrNull()
        } else {
            null
        } ?: runCatching { file.readText() }.getOrElse {
            lastError = "读取 UI 脚本失败：${it.message}"
            return null
        }

        // 1. 宿主桥 + 权限
        val host = PluginUiHostBridge(
            context = context,
            pluginId = plugin.manifest.id,
            permissions = permissions,
            store = store,
            toolRegistry = toolRegistry,
            toastImpl = onToast,
            rerenderImpl = onRerenderRequest,
            serviceChangedImpl = onServiceChanged,
            hostActions = hostActions,
            hostScope = hostScope,
            onPendingSettled = ::settlePending
        )
        bridge = host
        // WebView 控制器与页面桥都走这条同步通道回插件（界面里的资源拦截/网页回调）。
        // 必须在插件脚本 eval 之前设好：插件 onLoad 里就会用 controller。
        WebViewControllers.syncInvoker = { actionId, payloadJson ->
            runCatching { invokeActionSync(actionId, payloadJson) }.getOrNull()
        }
        runCatching { engine.bindNativeInterface(host) }.onFailure {
            FileLogger.e(TAG, "绑定宿主桥失败: ${it.message}")
        }

        // 2. 标准库补丁（Intl等）——必须早于插件脚本，也早于 UI 运行时
        val stdlibError = runCatching {
            engine.installBootstrap(PluginJsStdlib.SCRIPT, "openx_stdlib.js")
        }.exceptionOrNull()
        if (stdlibError != null) {
            lastError = "标准库补丁注入失败：${stdlibError.message}"
            return null
        }

        // 3. UI 运行时（定义 ctx / __openxRenderUi / __openxDispatchAction）
        val runtimeError = runCatching {
            engine.installBootstrap(PluginUiRuntime.SCRIPT, "openx_ui_runtime.js")
        }.exceptionOrNull()
        if (runtimeError != null) {
            lastError = "UI 运行时注入失败：${runtimeError.message}"
            return null
        }

        // 4. 插件自己的 UI 脚本
        val scriptError = runCatching {
            engine.installBootstrap(source, file.name)
        }.exceptionOrNull()
        if (scriptError != null) {
            lastError = "插件 UI 脚本报错：${scriptError.message}"
            return null
        }

        // 4. 桥函数自检：缺转发只会在用户点到那个按钮时才暴露，且很容易被插件自己的
        //    try/catch 吞掉（表现成"点击没反应"）。这里提前扫一遍。
        bridgeCheckResult = runCatching {
            engine.evaluate<String>("__openxBridgeSelfCheck()", "selfcheck.js")
        }.getOrNull()
        if (bridgeCheckResult?.contains("\"ok\":false") == true) {
            FileLogger.e(TAG, "宿主桥自检未通过：$bridgeCheckResult")
        }

        // 5. 首次渲染
        return renderLocked()
    }

    /** 启动时的桥函数自检结果（调试面板展示用）。 */
    @Volatile
    var bridgeCheckResult: String? = null
        private set

    /** 重新渲染（宿主收到 rerender 请求或 action 处理完后调用）。 */
    /**
     * 返回键优先交给插件的页内路由：能回退就就地回退并返回 true。
     *
     * 插件没用 `ctx.router` 时返回 false，调用方（面板的 BackHandler）
     * 照旧走「关闭面板」，所以对现有单页插件零影响。
     */
    fun popRouteIfPossible(): Boolean = synchronized(lock) {
        runCatching {
            engine.callFunction<String>("\$__openxRouterBack", "[]") == "1"
        }.getOrDefault(false)
    }

    fun render(): UiRenderResult? = synchronized(lock) { renderLocked() }

    /**
     * 热重载：重读磁盘上的 UI 脚本并重跑。
     *
     * 做的是**重建引擎**而不是在原引擎上重新 eval：
     *  · 插件脚本可能在顶层留下变量/闭包，重复 eval 会累积（比如重复绑定事件）
     *  · QuickJS 的 native 上下文状态无法逐个回滚，重建最干净
     * 代价是要重新加载运行时脚本（几毫秒），对"改完看一眼"的场景完全可接受。
     */
    fun reload(): UiRenderResult? = synchronized(lock) {
        runCatching { engine.close() }
        engine = QuickJsEngine(QuickJsEngine.UI_EXECUTION_TIMEOUT_MS).also { attachConsole(it) }
        bridge = null
        lastError = null
        started = true
        doStartLocked()
    }

    /** 插件文件沙箱目录（调试面板展示用）；界面未启动时返回 null。 */
    fun sandboxPathOrNull(): String? = bridge?.sandboxPath()

    /**
     * 当前节点树的 JSON（调试面板展示用）。
     * 渲染失败时返回错误信息，避免面板一片空白让人无从下手。
     */
    fun treeJson(): String = runCatching {
        if (lastRendered == null) {
            "\"\"" // 占位，面板会截断显示
        } else {
            com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(lastRendered?.tree)
        }
    }.getOrElse { "\"序列化失败：${it.message}\"" }

    /**
     * 派发一个动作（按钮点击等）。
     *
     * @return 新树 + JS 侧是否请求了额外重渲染。
     *
     * 为什么要分开返回而不是内部直接触发重组：setter（useState）是在派发**执行中途**
     * 被调用的，此刻 lock 还被持有。若那时就让 Compose 重组，重组会与本次派发
     * 抢同一份状态，界面停在旧树、所有交互看着都失效（真机踩过）。
     * 所以这里只在**派发彻底结束后**才把 needsRerender 交回给调用方。
     */
    fun dispatchAction(
        actionId: String,
        payload: Map<String, Any?> = emptyMap(),
        rawValue: Boolean = false
    ): DispatchOutcome =
        synchronized(lock) {
            val payloadJson = com.google.gson.Gson().toJson(payload)
            val script = """
                (function () {
                    var __r = __openxDispatchAction(
                        ${quote(actionId)},
                        ${quote(payloadJson)},
                        $rawValue
                    );
                    return __r;
                })()
            """.trimIndent()
            runCatching {
                val raw = engine.evaluate<String>(script, "dispatch.js")
                // 关键顺序：先问 JS “setter 调过没”，再返回给宿主。
                val need = runCatching {
                    engine.evaluate<String>("__openxConsumeRerenderRequest()", "rerender.js")
                }.getOrNull() == "true"
                DispatchOutcome(parseAndStore(raw), need)
            }.getOrElse {
                lastError = "动作执行失败：${it.message}"
                FileLogger.e(TAG, "dispatchAction 失败: ${it.message}")
                DispatchOutcome(lastRendered, false)
            }
        }

    // ---------------------------------------------------------------- 内部

    /**
     * **同步**调用插件的 JS 函数并取它的返回值。
     *
     * 专供 WebView 的 shouldInterceptRequest / shouldOverrideUrlLoading —— Android 要求
     * 这两个回调当场同步返回（没法等 Promise），所以不复用 dispatchAction：那条路还会
     * 顺带触发重渲染，语义不对。
     *
     * 返回 JS 侧 `{ok, value}` 里的 value 的 JSON 串；失败或插件抛异常返回 null。
     */
    fun invokeActionSync(actionId: String, payloadJson: String): String? = synchronized(lock) {
        runCatching {
            val raw = engine.evaluate<String>(
                "__openxInvokeActionSync(${quote(actionId)}, ${quote(payloadJson)})",
                "invoke_sync.js"
            )
            // 页面桥的回调里常会 setState（游戏分数回传就是），而这条同步通道不走
            // dispatchAction，不补渲染的话 state 变了界面不动 —— 表现为「分数一直是 0」。
            // post 到主线程：调用方是 WebView 的 IO 线程，直接改 Compose state 不安全。
            runCatching {
                if (engine.evaluate<String>("__openxConsumeRerenderRequest()", "rerender.js") == "true") {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onRerenderRequest() }
                }
            }
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            if (obj.get("ok")?.asBoolean == true) {
                obj.get("value")?.toString()
            } else {
                FileLogger.w(TAG, "同步调用未成功：${obj.get("error")}")
                null
            }
        }.getOrElse {
            FileLogger.w(TAG, "同步调用插件函数失败: ${it.message}")
            null
        }
    }

    private var lastRendered: UiRenderResult? = null

    /** 最近一次错误（页面顶部用来显示红字）。 */
    @Volatile
    var lastError: String? = null
        private set

    private fun renderLocked(): UiRenderResult? {
        var result = renderOnceLocked()
        // 渲染中同步完成的异步回调（ctx.http 在 UI 引擎里是 runBlocking 同步实现）
        // 会置 rerenderRequested —— 比如面板首次打开时自动拉模型清单，
        // .then(setModels) 在 evaluate 的微任务里同步跑完，但树已经返回旧的了。
        // 不补渲染的话界面永远停在「加载中」（真机踩过）。循环上限防死循环。
        var guard = 0
        while (result != null && guard < 3) {
            val again = runCatching {
                engine.evaluate<String>("__openxConsumeRerenderRequest()", "consume.js")
            }.getOrNull()
            if (again != "true") break
            guard++
            result = renderOnceLocked()
        }
        return result
    }

    private fun renderOnceLocked(): UiRenderResult? {
        val envJson = bridge?.loadEnvJson() ?: "{}"
        val optsJson = com.google.gson.Gson().toJson(
            mapOf(
                "permissions" to permissions.map { it.wireName },
                "container" to container,
                "env" to runCatching {
                    com.google.gson.Gson().fromJson(envJson, Map::class.java)
                }.getOrNull().orEmpty()
            )
        )
        val script = """
            (function () {
                return __openxRenderUi(${quote(optsJson)});
            })()
        """.trimIndent()
        return runCatching {
            val raw = engine.evaluate<String>(script, "render.js")
            parseAndStore(raw)
        }.getOrElse {
            lastError = "渲染失败：${it.message}"
            FileLogger.e(TAG, "render 失败: ${it.message}")
            lastRendered
        }
    }

    private fun parseAndStore(raw: Any?): UiRenderResult? {
        // 插件自定义主题在这里注入：render / 动作后重渲染 / 悬浮窗 都走这条路径，
        // 一处覆盖全部。theme 为空时 withTheme 直接返回原树，零开销。
        val result = UiNodeParser.parseRenderResult(raw, plugin.manifest.theme)
        if (result != null) {
            lastRendered = result
            lastState = result.state
            lastError = null
        } else {
            lastError = "渲染结果解析失败（JS 未返回合法节点树）"
        }
        return result
    }

    /** JSON 字符串转成 JS 字面量（用 JSON 编码，天然安全）。 */
    private fun quote(s: String): String = com.google.gson.Gson().toJson(s)

    /**
     * 结算挂起请求（选图/选文件等的结果回来时）。
     *
     * 必须做两件事，缺一不可：
     *  1. 在 [lock] 里跑 —— QuickJS 上下文只能在单线程上用
     *  2. 结算完驱动一次微任务 —— resolve 之后 JS 侧 `await` 的后续代码是在
     *     microtask 里跑的，不 drain 就永远不执行（界面停在"等待中"）
     */
    private fun settlePending(id: String, resultJson: String) {
        synchronized(lock) {
            // 先试 UI 运行时的 pending 表，再试兼容层的。
            // 两条通道各自维护一张表：UI 侧是 __openxResolvePending，兼容层是
            // __openxCompatResolvePending（它的长命令挂在后者里）。只调一个会漏。
            val ok = runCatching {
                val a = engine.evaluate<Boolean>(
                    "__openxResolvePending(${quote(id)}, ${quote(resultJson)})", "settle.js"
                ) == true
                val b = if (a) false else {
                    engine.evaluate<Boolean>(
                        "__openxCompatResolvePending(${quote(id)}, ${quote(resultJson)})", "settle.js"
                    ) == true
                }
                a || b
            }.getOrElse {
                FileLogger.e(TAG, "结算挂起请求失败: ${it.message}")
                false
            }
            if (ok) {
                // Promise 回调可能又改了 state（setImg / setErr），需要重渲染
                if (engine.evaluate<String>("__openxConsumeRerenderRequest()", "settle.js") == "true") {
                    onRerenderRequest()
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { engine.close() }
                .onFailure { FileLogger.e(TAG, "关闭引擎失败: ${it.message}") }
            started = false
        }
    }
}
