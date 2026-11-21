package com.wy.openx.plugin.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wy.openx.container.FileLogger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import com.wy.openx.plugin.ui.UiNodeParser.actionId
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.int
import com.wy.openx.plugin.ui.UiNodeParser.strOrNull

/**
 * `UI.WebView` —— 在插件界面里嵌一个网页。
 *
 * ## 为什么需要
 * Operit 的侧边栏类插件（OpenCode 面板等）本体就是一个 WebView：插件在容器里起本地
 * HTTP 服务，界面负责显示 `http://127.0.0.1:<port>`。没有这个组件，这类插件只能出工具、
 * 出不了界面。
 *
 * ## props 契约来源
 * 对齐 Operit 官方 `ToolPkgComposeDslWebView.kt`（2300 行）。这里实现它的主干：
 * `url` / `html` + `baseUrl` / `mimeType` / `encoding` / `headers`，以及全部
 * `WebSettings` 开关。**默认值与官方一致（几乎全是 true）**——包括 `allowFileAccess`
 * 与两个 `*FromFileURLs`。这是刻意的：兼容层要还原对方预期的行为，自作主张收紧会让
 * 插件里"本地 html 读同目录资源"这类正常用法失效。已装插件的界面属于用户主动导入的
 * 第三方代码，权限边界由安装时的 `filesystem` 危险权限授权来把关，而不是在这里暗中改语义。
 *
 * ## 回调
 * 官方有 13 个回调名，payload 统一是页面快照形状 `{type,url,title,loading,progress,
 * canGoBack,canGoForward}` 加各自的 extras。这里照做，插件按官方写法就能收到。
 * 函数在 runtime 的 `normalizePropValue` 里被登记成 `{__actionId}`，宿主用
 * [UiRenderContext.onAction] 回传；传**整个 payload**（`rawValue=false`），因为这些
 * 处理器要读 `progress` / `url` 字段，不是 useState setter。
 *
 * `onProgressChanged` / `onTitleChanged` 挂在 **WebChromeClient**（Android 的回调归属），
 * 页面加载类挂 WebViewClient——放错地方会静默收不到。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RenderUiNodeWebView(node: UiNode, ctx: UiRenderContext, m: Modifier): Boolean {
    if (node.type.lowercase() != "webview") return false

    val url = node.strOrNull("url")
    val html = node.strOrNull("html")
    if (url == null && html == null) {
        // url/html 都空是插件的常态（服务还没起来，先渲染占位）。什么都不画，等下一帧。
        return true
    }

    val headers = (node.props["headers"] as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            if (key.isBlank() || v == null) null else key to v.toString()
        }
        ?.toMap()
        .orEmpty()

    val settings = WebViewSettings.from(node)
    val loadKey = url ?: "${html?.hashCode()}/${settings.baseUrl}"
    // 上一次真正加载过的地址。用 remember 而不是 View.setTag(int,Any)：后者的 key
    // 必须是合法资源 id，拿一个自造的整数去用属于踩运气。
    var loadedKey by remember { mutableStateOf<String?>(null) }
    // 上一次已应用的设置。
    // WebSettings 的 setter（尤其 textZoom / useWideViewPort）会触发 WebView 重新布局，
    // 而这会把用户长按选中的文字清掉；面板又会因进度回调频繁重组，update 每帧都跑，
    // 于是表现为「长按选择闪一下就没」。只在设置真正变化时才应用。
    var appliedSettings by remember { mutableStateOf<WebViewSettings?>(null) }

    // 每帧取最新 action id：JS 侧 actionRegistry 每次 render 重建，用旧 id 派发会失败。
    val cb = remember(node.props) { CallbackIds.from(node) }
    val current by rememberUpdatedState(cb)
    val dispatch = ctx.onAction

    // Operit 的 `ctx.createWebViewController(key)`：插件把 controller 作为 WebView 的 prop 传进来，
    // 序列化后是 `{__composeWebViewController:true, key:"..."}`。宿主按 key 登记本实例，
    // 之后 controller.loadUrl / evaluateJavascript / addJavascriptInterface 才能落到这个 WebView 上。
    val controllerKey = (node.props["controller"] as? Map<*, *>)
        ?.get("key")?.toString()?.takeIf { it.isNotBlank() }
    // 回调 id 每帧重建（JS 侧 actionRegistry 每次 render 重建），必须取最新帧的
    val interceptId = rememberUpdatedState(node.actionId("onInterceptRequest"))
    val navId = rememberUpdatedState(node.actionId("onShouldOverrideUrlLoading"))

    AndroidView(
        modifier = m.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.WHITE)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_OVERLAY)
                // 登记到控制器注册表：插件的 controller 要在渲染后立刻可用
                controllerKey?.let { WebViewControllers.bind(it, this) }
                webViewClient = object : WebViewClient() {
                    /**
                     * 资源拦截：游戏类插件的 HTML/CSS/JS/PNG 全靠这里供给。
                     *
                     * 在 WebView 的 IO 线程被调，可以阻塞等 JS（[UiRenderContext.invokeSync]），
                     * 插件没返回就放行走正常网络。
                     */
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val k = controllerKey ?: return null
                        val aid = interceptId.value ?: return null
                        val b = WebViewControllers.bindingOf(k) ?: return null
                        if (request == null) return null
                        return WebViewControllers.interceptResource(
                            b, aid,
                            mapOf(
                                "url" to request.url.toString(),
                                "method" to request.method,
                                "isForMainFrame" to request.isForMainFrame,
                                "headers" to request.requestHeaders
                            )
                        )
                    }

                    /** 导航决策：插件可以放行 / 拦下 / 重写地址。 */
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val k = controllerKey ?: return false
                        val aid = navId.value ?: return false
                        val b = WebViewControllers.bindingOf(k) ?: return false
                        if (request == null) return false
                        val decision = WebViewControllers.decideNavigation(
                            b, aid,
                            mapOf(
                                "url" to request.url.toString(),
                                "method" to request.method,
                                "isForMainFrame" to request.isForMainFrame,
                                "headers" to request.requestHeaders
                            )
                        ) ?: return false
                        return when (decision) {
                            "cancel", "external" -> true
                            else -> {
                                view?.loadUrl(decision)
                                true
                            }
                        }
                    }
                    override fun onPageStarted(view: WebView?, target: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(VIEWPORT_FALLBACK_JS, null)
                        current.fire("onPageStarted", "page_started", view, mapOf("url" to (target ?: "")), dispatch)
                    }

                    override fun onPageFinished(view: WebView?, target: String?) {
                        current.fire("onPageFinished", "page_finished", view, mapOf("url" to (target ?: "")), dispatch)
                        // 页面换掉了，页面桥要重新注入一遍（见 reinstallBridge 的说明）
                        controllerKey?.let { k -> WebViewControllers.reinstallBridge(k) }
                        // 页面是 SPA（`<div id="root">` 空壳 + 一个几 MB 的 ES Module），
                        // "白屏"可能是脚本没执行、也可能是执行了但请求失败。把 DOM 状态
                        // 读出来才能区分，否则只能猜。
                        view?.let { wv ->
                            // 先装上错误监听：SPA 的渲染错误多数在异步阶段抛出，
                            // 而 WebView 只能拿到 console 输出，window.onerror 这条路不一定走那里。
                            wv.evaluateJavascript(ERROR_CAPTURE_JS, null)
                            wv.evaluateJavascript(DIAGNOSE_JS) { result ->
                                FileLogger.i(TAG, "页面状态(t0)：$result")
                            }
                            // SPA 是在 onPageFinished **之后**才异步渲染的，t0 读到的只是空壳
                            // （rootChildren=0 不代表没渲染）。隔几秒再读一次才说明问题。
                            wv.postDelayed({
                                runCatching {
                                    wv.evaluateJavascript(DIAGNOSE_JS) { result ->
                                        FileLogger.i(TAG, "页面状态(t+4s)：$result")
                                    }
                                }
                                // DIAGNOSE_JS 里的 rootWH 读的是页面 #root 元素，不是 Android View，
                                // 单凭它无法区分「宿主没给高度」和「页面 CSS 自己塔了」。
                                // 这里同时量 View 尺寸、父链每层尺寸、CSS 视口高度。
                                FileLogger.i(TAG, "View 尺寸(t+4s)：${wv.width}x${wv.height}")
                                runCatching {
                                    val sb = StringBuilder()
                                    var v: android.view.View? = wv
                                    var depth = 0
                                    while (v != null && depth < 8) {
                                        val cur = v
                                        sb.append("${cur.javaClass.simpleName}:${cur.width}x${cur.height} > ")
                                        v = cur.parent as? android.view.View
                                        depth++
                                    }
                                    FileLogger.i(TAG, "父链尺寸：$sb")
                                }
                                runCatching {
                                    wv.evaluateJavascript(
                                        "(function(){return JSON.stringify({innerH:window.innerHeight," +
                                            "docClientH:document.documentElement.clientHeight," +
                                            "htmlRectH:Math.round(document.documentElement.getBoundingClientRect().height)," +
                                            "bodyRectH:Math.round(document.body.getBoundingClientRect().height)});})()"
                                    ) { r -> FileLogger.i(TAG, "CSS 视口(t+4s)：$r") }
                                }
                            }, 4000)
                        }
                        // 兼容兜底：旧版 WebView 不支持 `dvh` 时，Tailwind 的 `h-dvh`
                        // 整条声明失效，flex 布局随之塌陷 —— 表现为「只剩顶栏、主体空白」。
                        // 页面本来就该铺满视口，补一条等价高度规则即可。
                        view?.evaluateJavascript(VIEWPORT_FALLBACK_JS) { r ->
                            FileLogger.i(TAG, "视口兜底：$r")
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        FileLogger.w(
                            TAG,
                            "加载出错 url=${request?.url} main=${request?.isForMainFrame} " +
                                "code=${error?.errorCode} desc=${error?.description}"
                        )
                        current.fire(
                            "onReceivedError", "error", view,
                            mapOf(
                                "url" to (request?.url?.toString() ?: ""),
                                "isMainFrame" to (request?.isForMainFrame ?: true),
                                "code" to (error?.errorCode ?: -1),
                                "description" to (error?.description?.toString() ?: "")
                            ),
                            dispatch
                        )
                    }

                    // 目前只记录：脚本/CSS 返回 4xx/5xx 时 onReceivedError 不会触发，
                    // 但页面会白屏，是最容易被忽略的一类失败。
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        FileLogger.w(
                            TAG,
                            "资源 HTTP 错误 url=${request?.url} status=${errorResponse?.statusCode} " +
                                "main=${request?.isForMainFrame}"
                        )
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        current.fire("onProgressChanged", "progress", view, mapOf("progress" to newProgress), dispatch)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        current.fire("onTitleChanged", "title_changed", view, mapOf("title" to (title ?: "")), dispatch)
                    }

                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        val text = message?.message() ?: ""
                        // ResizeObserver 的 loop 告警是浏览器为打断布局循环主动抛的良性事件，
                        // 页面布局稍有抖动就会反复触发，不代表出错，直接丢弃避免刷屏。
                        if (text.startsWith("ResizeObserver loop")) return true
                        // 只记 WARNING/ERROR：诊断白屏靠的就是脚本报错，而 log 级别的
                        // 输出在 SPA 里铺天盖地，全记会把日志冲掉。
                        if (message != null && message.messageLevel() != ConsoleMessage.MessageLevel.LOG) {
                            FileLogger.w(
                                TAG,
                                "控制台[${message.messageLevel()}] $text " +
                                    "@${message.sourceId()}:${message.lineNumber()}"
                            )
                        }
                        current.fire(
                            "onConsoleMessage", "console", null,
                            mapOf(
                                "message" to text,
                                "level" to (message?.messageLevel()?.name ?: ""),
                                "source" to (message?.sourceId() ?: ""),
                                "lineNumber" to (message?.lineNumber() ?: 0)
                            ),
                            dispatch
                        )
                        return true
                    }
                }
            }
        },
        update = { web ->
            if (appliedSettings != settings) {
                appliedSettings = settings
                settings.applyTo(web)
            }
            // 只在地址真正变化时加载：面板每帧都会重组（进度回调就触发重渲染），
            // 无条件 loadUrl/loadDataWithBaseURL 会让页面永远停在加载中。
            if (loadedKey != loadKey) {
                loadedKey = loadKey
                if (url != null) {
                    if (headers.isNotEmpty()) web.loadUrl(url, headers) else web.loadUrl(url)
                } else {
                    // 这里必须显式取值：开头只排除了「url 与 html 都为空」，
                    // Kotlin 推不出「url 为空则 html 必非空」这种析取关系。
                    val htmlText = html
                    if (htmlText != null) {
                        // baseUrl 可空但 Android 声明为非空参数，空串就是「无 base」
                        web.loadDataWithBaseURL(
                            settings.baseUrl ?: "", htmlText, settings.mimeType, settings.encoding, null
                        )
                    }
                }
            }
        },
        onRelease = { web ->
            // 必须停：否则页面里的 JS 定时器与网络请求继续跑，且 WebView 持有 Activity
            // context，面板关闭后泄漏。
            controllerKey?.let { WebViewControllers.unbind(it, web) }
            runCatching { web.stopLoading() }
            runCatching { web.loadUrl("about:blank") }
            runCatching { web.destroy() }
        }
    )
    return true
}

/**
 * 插件声明的 WebView 设置。默认值照 Operit 官方（几乎全 true）。
 */
private data class WebViewSettings(
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val databaseEnabled: Boolean,
    val allowFileAccess: Boolean,
    val allowContentAccess: Boolean,
    val allowFileAccessFromFileURLs: Boolean,
    val allowUniversalAccessFromFileURLs: Boolean,
    val supportZoom: Boolean,
    val builtInZoomControls: Boolean,
    val displayZoomControls: Boolean,
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val mediaPlaybackRequiresUserGesture: Boolean,
    val textZoom: Int,
    val userAgent: String?,
    val baseUrl: String?,
    val mimeType: String,
    val encoding: String
) {
    companion object {
        fun from(node: UiNode) = WebViewSettings(
            javaScriptEnabled = node.bool("javaScriptEnabled", true),
            domStorageEnabled = node.bool("domStorageEnabled", true),
            databaseEnabled = node.bool("databaseEnabled", true),
            allowFileAccess = node.bool("allowFileAccess", true),
            allowContentAccess = node.bool("allowContentAccess", true),
            allowFileAccessFromFileURLs = node.bool("allowFileAccessFromFileURLs", true),
            allowUniversalAccessFromFileURLs = node.bool("allowUniversalAccessFromFileURLs", true),
            supportZoom = node.bool("supportZoom", true),
            builtInZoomControls = node.bool("builtInZoomControls", node.bool("supportZoom", true)),
            displayZoomControls = node.bool("displayZoomControls", false),
            useWideViewPort = node.bool("useWideViewPort", true),
            // 默认 false（官方默认是 true）：这个开关会强制缩放页面以适应控件宽度，
            // 与页面自带的 `<meta name="viewport" content="width=device-width, initial-scale=1">`
            // 冲突——表现为内容被缩放、右侧留白、局部布局错位。尊重页面自己的 viewport 更稳。
            loadWithOverviewMode = node.bool("loadWithOverviewMode", false),
            mediaPlaybackRequiresUserGesture = node.bool("mediaPlaybackRequiresUserGesture", false),
            textZoom = node.int("textZoom", 100),
            userAgent = node.strOrNull("userAgent"),
            baseUrl = node.strOrNull("baseUrl"),
            mimeType = node.strOrNull("mimeType") ?: "text/html",
            encoding = node.strOrNull("encoding") ?: "UTF-8"
        )
    }

    fun applyTo(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = this@WebViewSettings.javaScriptEnabled
            domStorageEnabled = this@WebViewSettings.domStorageEnabled
            databaseEnabled = this@WebViewSettings.databaseEnabled
            allowFileAccess = this@WebViewSettings.allowFileAccess
            allowContentAccess = this@WebViewSettings.allowContentAccess
            allowFileAccessFromFileURLs = this@WebViewSettings.allowFileAccessFromFileURLs
            allowUniversalAccessFromFileURLs = this@WebViewSettings.allowUniversalAccessFromFileURLs
            setSupportZoom(this@WebViewSettings.supportZoom)
            builtInZoomControls = this@WebViewSettings.builtInZoomControls
            displayZoomControls = this@WebViewSettings.displayZoomControls
            useWideViewPort = this@WebViewSettings.useWideViewPort
            loadWithOverviewMode = this@WebViewSettings.loadWithOverviewMode
            mediaPlaybackRequiresUserGesture = this@WebViewSettings.mediaPlaybackRequiresUserGesture
            textZoom = this@WebViewSettings.textZoom
            this@WebViewSettings.userAgent?.let { userAgentString = it }
            stripWebViewMarker(this)

            // 彻底关闭 WebView 自动反色（深色主题下会导致整页变黑）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                try {
                    val cls = WebSettings::class.java
                    val m = cls.getMethod("setForceDark", Int::class.java)
                    m.invoke(this, WebSettings.FORCE_DARK_OFF)
                } catch (_: Throwable) {
                    try {
                        @Suppress("DEPRECATION")
                        forceDark = WebSettings.FORCE_DARK_OFF
                    } catch (_: Throwable) {
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    forceDark = WebSettings.FORCE_DARK_OFF
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/**
 * 去掉 UA 里的 WebView 标记。
 *
 * 默认 UA 形如 `...; wv) ... Version/4.0 Chrome/138...`。`; wv` 与 `Version/4.0` 是
 * 「这是内嵌浏览器」的标准标识，前端普遍据此降级功能或走不同的初始化分支——
 * **同一个页面在手机浏览器里正常、在内置 WebView 里白屏，往往就出在这里**。
 * 只抹掉这两个标记，Android 版本与设备型号保留，服务端的移动端判定不受影响。
 */
private fun stripWebViewMarker(settings: WebSettings) {
    val current = settings.userAgentString ?: return
    val cleaned = current
        .replace("; wv", "")
        .replace(Regex("Version/\\S+\\s"), "")
    if (cleaned != current) settings.userAgentString = cleaned
}

/**
 * 回调 action id 集合。名字与官方一致，插件按官方写法就能收到。
 *
 * 官方还有 `onDownloadStart` / `onReceivedHttpError` / `onReceivedSslError` /
 * `onUrlChanged` / `onStateChanged` / `onLifecycleEvent` / `onShouldOverrideUrlLoading` /
 * `onInterceptRequest`（后两个需要把宿主返回值送回去，是双向协议），本面板用不到，
 * 暂不实现。
 */
private data class CallbackIds(
    val onPageStarted: String?,
    val onPageFinished: String?,
    val onReceivedError: String?,
    val onProgressChanged: String?,
    val onTitleChanged: String?,
    val onConsoleMessage: String?
) {
    companion object {
        fun from(node: UiNode) = CallbackIds(
            onPageStarted = node.actionId("onPageStarted"),
            onPageFinished = node.actionId("onPageFinished"),
            onReceivedError = node.actionId("onReceivedError"),
            onProgressChanged = node.actionId("onProgressChanged"),
            onTitleChanged = node.actionId("onTitleChanged"),
            onConsoleMessage = node.actionId("onConsoleMessage")
        )
    }

    fun idOf(name: String): String? = when (name) {
        "onPageStarted" -> onPageStarted
        "onPageFinished" -> onPageFinished
        "onReceivedError" -> onReceivedError
        "onProgressChanged" -> onProgressChanged
        "onTitleChanged" -> onTitleChanged
        "onConsoleMessage" -> onConsoleMessage
        else -> null
    }
}

/**
 * 派发一个 WebView 回调。
 *
 * payload = 页面快照（url/title/loading/progress）+ 该事件自己的 extras + `type`，
 * 与官方 `buildComposeDslWebViewLifecyclePayload` 的形状一致。extras 放后面，
 * 让事件自带的 url（比如错误发生的那个资源地址）覆盖快照里的当前页地址。
 */
private fun CallbackIds.fire(
    name: String,
    type: String,
    view: WebView?,
    extras: Map<String, Any?>,
    dispatch: (actionId: String, payload: Map<String, Any?>, rawValue: Boolean) -> Unit
) {
    val id = idOf(name) ?: return
    val snapshot: Map<String, Any?> = if (view == null) {
        emptyMap()
    } else {
        // WebView 没有 isLoading API（Android 未公开），用 progress 推断：
        // 到 100 视为加载完成。插件主要读 progress/url，这个字段只供展示层参考。
        mapOf(
            "url" to view.url,
            "title" to view.title,
            "loading" to (view.progress < 100),
            "progress" to view.progress
        )
    }
    dispatch(id, snapshot + extras + mapOf("type" to type), false)
}

private const val TAG = "PluginWebView"

/**
 * 页面加载完成后读一次 DOM 状态。
 *
 * 这些页面普遍是 SPA（`<div id="root">` 空壳 + 一个几 MB 的 ES Module），光看"白屏"
 * 分不清是**脚本没执行**（引擎太老 / 语法不支持）还是**执行了但接口请求失败**。
 * 把 root 子元素数与 module 支持情况读出来，一眼就能分辨。
 */
/**
 * 装上页面脚本错误监听。
 *
 * 在 `onPageFinished` 之后调用（此时文档已就绪，监听能捕获后续的异步错误）。
 * 幂等：重复调用直接返回。错误会在稍后的 [DIAGNOSE_JS] 里被读出来。
 */
private const val ERROR_CAPTURE_JS = """
(function () {
  try {
    if (window.__openxErrHook) return 'hooked';
    window.__openxErrHook = true;
    window.__openxErrors = [];
    window.addEventListener('error', function (e) {
      window.__openxErrors.push('err:' + ((e && e.message) || '') + ' @' + ((e && e.filename) || '') + ':' + ((e && e.lineno) || 0));
    });
    window.addEventListener('unhandledrejection', function (e) {
      window.__openxErrors.push('rej:' + String(e && e.reason));
    });
    return 'ok';
  } catch (e) { return 'ERR:' + e.message; }
})()
"""

private const val DIAGNOSE_JS = """
(function () {
  try {
    var r = document.getElementById('root');
    var sup = function (p, v) { try { return CSS.supports(p, v); } catch (e) { return false; } };
    var kids = [];
    if (r) {
      for (var i = 0; i < Math.min(r.children.length, 4); i++) {
        var c = r.children[i];
        kids.push(c.tagName + ':' + c.clientWidth + 'x' + c.clientHeight);
      }
    }
    return JSON.stringify({
      ua: (navigator.userAgent || '').slice(0, 150),
      readyState: document.readyState,
      dvh: sup('height', '100dvh'),
      hasSel: sup('selector(:has(*))'),
      elems: document.querySelectorAll('*').length,
      rootChildren: r ? r.children.length : -1,
      rootWH: r ? (r.clientWidth + 'x' + r.clientHeight) : '-1',
      bodyWH: document.body ? (document.body.clientWidth + 'x' + document.body.clientHeight) : '-1',
      kids: kids.join(' | '),
      errors: (window.__openxErrors || []).slice(0, 3).join(' ; '),
      text: document.body ? document.body.innerText.slice(0, 30) : ''
    });
  } catch (e) { return 'ERR:' + e.message; }
})()
"""

/**
 * 视口高度兜底。
 *
 * 只救「页面拿不到高度」的 SPA 白屏：页面上已经有内容高度时**一律不动**。
 * 早先的版本无条件给 html/body 和 `#root/#app/#__next/main/.app/.app-container`
 * 设 `height:100%; overflow:hidden`，对自带布局的网页是灾难：滚动容器被改成
 * overflow:hidden，页面就滑不动了（而且它用 setTimeout 反复刷，改 CSS 也顶不住）。
 */
private const val VIEWPORT_FALLBACK_JS = """
(function () {
  try {
    function lightOnly() {
      try {
        document.documentElement.style.setProperty('color-scheme', 'light only', 'important');
        if (document.body) document.body.style.setProperty('color-scheme', 'light only', 'important');
      } catch (e) {}
    }
    // 页面自己已经有高度（普通网页、自己写了布局的插件页）时不能动它的布局。
    // 注意不能用 `scrollHeight > vh*0.6`：SPA 白屏时 scrollHeight 也约等于 vh，
    // 那样会把「本该救的白屏」误判成「已有布局」而放弃介入。
    function pageHasOwnLayout() {
      var vh = window.innerHeight || 0;
      if (!vh) return false;
      // 内容真的超出视口 → 页面自己排好版了
      if (document.documentElement.scrollHeight > vh + 20) return true;
      var el = document.querySelector('#root,#__next,#app,.app,.app-container');
      if (el) {
        // 容器有实际高度或内部有内容 → 有自己的布局
        if (el.getBoundingClientRect().height > 20) return true;
        if (el.scrollHeight > 20) return true;
      }
      return false;
    }
    var applied = false;
    function applyFix() {
      lightOnly();
      if (applied) return 'done';
      if (pageHasOwnLayout()) return 'skip';
      applied = true;
      var html = document.documentElement;
      var body = document.body;
      if (html) {
        html.style.cssText = 'color-scheme:light only!important;height:100vh!important;height:100dvh!important;margin:0!important;padding:0!important;background:#ffffff!important;';
      }
      if (body) {
        body.style.cssText = 'color-scheme:light only!important;height:100vh!important;height:100dvh!important;margin:0!important;padding:0!important;display:flex!important;flex-direction:column!important;background:#ffffff!important;';
      }
      var sels = ['#root','#app','#__next','main','.app','.app-container'];
      for (var i=0;i<sels.length;i++) {
        var el = document.querySelector(sels[i]);
        if (!el) continue;
        // 只给「没高度」的元素补高度，不覆盖已有的 overflow
        if (el.getBoundingClientRect().height > 0) continue;
        el.style.setProperty('height', '100%', 'important');
        el.style.setProperty('min-height', '0', 'important');
        el.style.setProperty('width', '100%', 'important');
        el.style.setProperty('flex', '1 1 auto', 'important');
        el.style.setProperty('display', 'flex', 'important');
        el.style.setProperty('flex-direction', 'column', 'important');
      }
      var id='__openx_vh_fix'; var s=document.getElementById(id);
      if(!s){ s=document.createElement('style'); s.id=id; (document.head||document.documentElement).appendChild(s); }
      s.textContent='html,body{color-scheme:light only!important;background:#ffffff!important;height:100vh!important;height:100dvh!important;}#root,#app,#__next,main,.app,.app-container{background:#ffffff!important;height:100%!important;min-height:0!important;flex:1 1 auto!important;}'
        + '#root>*,#app>*,#__next>*,main>*{min-height:0!important;}';
      return 'applied';
    }
    applyFix();
    setTimeout(applyFix, 50);
    setTimeout(applyFix, 150);
    setTimeout(applyFix, 400);
    setTimeout(applyFix, 900);
    setTimeout(applyFix, 1600);
    return 'ok';
  } catch (e) { return 'ERR:' + e.message; }
})()
"""
