package com.wy.openx.plugin.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 控制器注册表 —— Operit `ctx.createWebViewController(key)` 的宿主侧实现。
 *
 * ## 为什么需要
 * Operit 的交互式插件（小游戏、内嵌编辑器）是「插件 JS 逻辑 + 内嵌网页」的组合：
 * 插件用控制器控制网页（加载、执行 JS），网页通过 `window.<接口名>.<方法>()` 回调插件。
 * 缺了这套，插件 UI 第一行 `ctx.createWebViewController` 就 `not a function`，整个界面渲染失败。
 *
 * ## 两条数据流
 * ```
 * 插件 JS ──controller.loadUrl/evaluateJavascript──> handleCommand ──> 真 WebView
 * 网页 JS ──window.ArcadeHost.pageLog()──> PageBridge.invoke ──> 插件 JS（同步取返回值）
 * ```
 *
 * ## 同步性是硬要求
 * - `shouldInterceptRequest` / `shouldOverrideUrlLoading` 的 Android 签名要求**当场返回**，
 *   所以借 [PluginUiSession.invokeActionSync] 同步调 JS（带超时，超时就放行原请求）。
 * - 网页调原生接口同样要当场拿返回值，所以在 WebView 的 JS bridge 线程上同步回调。
 *
 * 所有对 WebView 的操作都切主线程执行（Android 要求），且带超时兜底 ——
 * 宁可放弃这次操作，也不能把 WebView 的 IO 线程卡死。
 */
internal object WebViewControllers {

    private val gson = Gson()

    /** 各环节超时（对齐 Operit 官方量级）。 */
    private const val MAIN_TIMEOUT_MS = 3_000L
    private const val EVAL_TIMEOUT_MS = 5_000L

    /** 注入到网页里的原生桥对象名。网页侧不直接用，走下面注入的包装函数。 */
    private const val PAGE_BRIDGE_NAME = "__openxNativeBridge"

    /**
     * 一个 controller key 的运行期状态。
     *
     * [webView] 可变：Compose 重建会换实例，bind/unbind 跟着换。
     */
    class Binding(val key: String) {
        @Volatile
        var webView: WebView? = null

        /** 网页→原生的接口登记：interfaceName -> (methodName -> actionId)。 */
        val interfaces = ConcurrentHashMap<String, Map<String, String>>()

        /** 最近一次页面状态快照（controller.getState 用）。 */
        @Volatile
        var state: Map<String, Any?> = emptyMap()
    }

    private val bindings = ConcurrentHashMap<String, Binding>()

    /**
     * 插件同步调用通道（由 [PluginUiSession] 在创建时注入）。
     *
     * 放全局而不是当参数传：`bind` 时也要用它来装页面桥，而那时拿不到 session 上下文。
     * 同一时刻只有一个 UI session 存活，所以单例安全。
     */
    @Volatile
    var syncInvoker: ((actionId: String, payloadJson: String) -> String?)? = null

    /**
     * 控制器接口的「待绑定」登记。
     *
     * 插件的顺序往往是「先 registerJavascriptInterface，再 setReady(true) 触发渲染」——
     * 登记时 WebView 还不存在，不能直接丢。存这里，等 bind 时回放。
     */
    private val pendingInterfaces = ConcurrentHashMap<String, MutableMap<String, Map<String, String>>>()

    /** Compose 侧渲染出带 controller 的 WebView 时登记，onDispose 时撤销。 */
    fun bind(key: String, webView: WebView): Binding {
        val b = bindings.getOrPut(key) { Binding(key) }
        b.webView = webView
        // 回放待绑定登记（接口声明的比 WebView 早）
        pendingInterfaces[key]?.let { pend ->
            pend.forEach { (name, methods) -> b.interfaces[name] = methods }
            if (pend.isNotEmpty()) installPageBridge(b)
        }
        return b
    }

    fun unbind(key: String, webView: WebView) {
        val b = bindings[key] ?: return
        // 只在还是同一个实例时清空：Compose 可能先 bind 新实例再 unbind 旧的
        if (b.webView === webView) b.webView = null
    }

    fun bindingOf(key: String): Binding? = bindings[key]

    /**
     * 页面加载完成后重新注入页面桥。
     *
     * bind 时也注入过一次，但那时 WebView 里还是 about:blank —— 紧接着的 loadUrl
     * 会把上一份注入连同 window 一起换掉，插件就再也找不到 window.<接口名> 了
     * （表现为网页里调宿主接口「没反应」、分数不回传）。所以 onPageFinished 必须重来一次。
     */
    fun reinstallBridge(key: String) {
        bindings[key]?.let { b -> if (b.interfaces.isNotEmpty()) installPageBridge(b) }
    }

    // ------------------------------------------------------------ 命令分发（插件 → 宿主）

    /**
     * 执行一条控制器命令，返回 `{result}` 或 `{error}` 的 JSON。
     */
    fun handleCommand(payloadJson: String): String = runCatching {
        val obj = JsonParser.parseString(payloadJson).asJsonObject
        val command = obj.get("command")?.asString.orEmpty()
        val key = obj.get("key")?.asString.orEmpty()
        val payload = obj.get("payload")?.asJsonObject ?: JsonObject()

        // 注意：不能要求 binding 必须已存在。插件的典型顺序是
        // 「先 registerJavascriptInterface，再 setReady(true) 触发渲染」——
        // 注册接口时 WebView 还不存在，硬报错会让插件整个初始化中断
        // （表现：界面永远停在「正在装载…」，因为抛错点在 setReady 之前）。
        // 没绑定时建一个占位 binding：接口登记照样攒着，等 bind 时统一回放。
        val binding = bindings.getOrPut(key) { Binding(key) }

        when (command) {
            "loadUrl" -> {
                val url = payload.get("url")?.asString.orEmpty()
                val headers = payload.get("headers")?.let { h ->
                    (runCatching { h.asJsonObject }.getOrNull())?.let { o ->
                        o.entrySet().associate { it.key to it.value.asString }
                    }
                }
                onMain(binding) { wv -> wv.loadUrl(url, headers ?: emptyMap()) }
                okJson(null)
            }
            "loadHtml" -> {
                val html = payload.get("html")?.asString.orEmpty()
                val opts = payload.get("options")?.let { runCatching { it.asJsonObject }.getOrNull() }
                val baseUrl = opts?.get("baseUrl")?.asString ?: "about:blank"
                val mime = opts?.get("mimeType")?.asString ?: "text/html"
                val enc = opts?.get("encoding")?.asString ?: "UTF-8"
                onMain(binding) { wv -> wv.loadDataWithBaseURL(baseUrl, html, mime, enc, null) }
                okJson(null)
            }
            "reload" -> { onMain(binding) { it.reload() }; okJson(null) }
            "stopLoading" -> { onMain(binding) { it.stopLoading() }; okJson(null) }
            "goBack" -> { onMain(binding) { if (it.canGoBack()) it.goBack() }; okJson(null) }
            "goForward" -> { onMain(binding) { if (it.canGoForward()) it.goForward() }; okJson(null) }
            "clearHistory" -> { onMain(binding) { it.clearHistory() }; okJson(null) }
            "getState" -> okJson(binding.state)
            "evaluateJavascript" -> {
                val script = payload.get("script")?.asString.orEmpty()
                val res = evaluateSync(binding, script, EVAL_TIMEOUT_MS)
                okJson(res)
            }
            "addJavascriptInterface" -> {
                val name = payload.get("name")?.asString.orEmpty()
                val methods = mutableMapOf<String, String>()
                payload.get("object")?.let { o ->
                    runCatching { o.asJsonObject }.getOrNull()?.entrySet()?.forEach { e ->
                        // 插件函数在 JS 侧被 normalizePropValue 转成 {__actionId: "action_N"}
                        val id = runCatching { e.value.asJsonObject.get("__actionId")?.asString }.getOrNull()
                        if (!id.isNullOrBlank()) methods[e.key] = id
                    }
                }
                if (name.isBlank() || methods.isEmpty()) return@runCatching errorJson("接口名或方法为空")
                // 存待绑定表 + 已绑定的直接登记；WebView 还没渲染时只存待绑定
                pendingInterfaces.getOrPut(key) { ConcurrentHashMap() }[name] = methods
                binding.interfaces[name] = methods
                installPageBridge(binding)
                okJson(null)
            }
            "removeJavascriptInterface" -> {
                val name = payload.get("name")?.asString.orEmpty()
                binding.interfaces.remove(name)
                onMain(binding) { wv -> runCatching { wv.removeJavascriptInterface(name) } }
                okJson(null)
            }
            else -> errorJson("未知的控制器命令：$command")
        }
    }.getOrElse { errorJson("控制器命令执行失败：${it.message}") }

    // ------------------------------------------------------------ 页面桥（网页 → 插件）

    /**
     * 把插件登记的接口注入网页：网页里 `window.<接口名>.<方法>(...)` 会同步回调插件函数。
     *
     * 分两步：先挂一个原生对象（[PageBridge]），再注入一段 JS 把每个接口名包装成
     * 同名 window 属性 —— 插件写 `window["ArcadeHost"].pageLog(payload)`，包装函数把参数
     * 打包成 JSON 数组交给原生，拿回返回值再还给网页。全程同步。
     */
    @SuppressLint("JavascriptInterface")
    private fun installPageBridge(binding: Binding) {
        val wv = binding.webView ?: return
        onMain(binding) { web ->
            runCatching { web.addJavascriptInterface(PageBridge(binding), PAGE_BRIDGE_NAME) }
        }
        val spec = binding.interfaces.mapValues { it.value.keys.toList() }
        if (spec.isEmpty()) return
        val specJson = gson.toJson(spec)
        val script = """
            (function () {
              var spec = $specJson;
              if (!window.$PAGE_BRIDGE_NAME) return;
              Object.keys(spec).forEach(function (name) {
                var api = {};
                spec[name].forEach(function (method) {
                  api[method] = function () {
                    var args = Array.prototype.slice.call(arguments);
                    var raw = window.$PAGE_BRIDGE_NAME.invoke(name, method, JSON.stringify(args));
                    var r = null;
                    try { r = JSON.parse(raw || 'null'); } catch (e) { r = null; }
                    if (r && r.ok === false) throw new Error(r.error || '宿主调用失败');
                    return r ? r.value : null;
                  };
                });
                window[name] = api;
              });
            })();
        """.trimIndent()
        onMain(binding) { web -> runCatching { web.evaluateJavascript(script, null) } }
    }

    /**
     * 网页侧的原生对象。方法在 WebView 的 JS bridge 线程被同步调用。
     *
     * 走 [invokeSync]（内部对 session 加锁）同步执行插件函数并取返回值 —— 与
     * `shouldInterceptRequest` 用的是同一条同步通道。
     */
    private class PageBridge(private val binding: Binding) {
        @JavascriptInterface
        fun invoke(interfaceName: String, methodName: String, argsJson: String): String {
            val actionId = binding.interfaces[interfaceName]?.get(methodName)
                ?: return """{"ok":false,"error":"未注册的接口方法：$interfaceName.$methodName"}"""
            val value = runCatching { syncInvoker?.invoke(actionId, argsJson) }.getOrNull()
                ?: return """{"ok":false,"error":"插件未返回结果（可能不是同步函数）"}"""
            val out = JsonObject()
            out.addProperty("ok", true)
            out.add("value", JsonParser.parseString(value))
            return out.toString()
        }
    }

    // ------------------------------------------------------------ 同步回调（供 WebViewClient）

    /**
     * 调用插件的资源拦截回调，返回要不要合成响应。
     *
     * 回调在 WebView 的 IO 线程执行，可以阻塞等 JS；超时或插件没返回就返回 null（放行）。
     */
    fun interceptResource(
        binding: Binding,
        actionId: String,
        request: Map<String, Any?>
    ): WebResourceResponse? {
        val invokeSync = syncInvoker ?: return null
        val raw = runCatching {
            invokeSync(actionId, gson.toJson(listOf(request)))
        }.getOrNull() ?: return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val action = obj.get("action")?.asString.orEmpty()
        return when (action) {
            "respond" -> buildResponse(obj.getAsJsonObject("response") ?: return null)
            "block" -> emptyResponse()
            "rewrite" -> obj.get("url")?.asString?.let { fetchAsResponse(it, request) }
            // allow 与未知分支都放行：returning null 让 WebView 自己加载
            else -> null
        }
    }

    /** 空响应：这次请求什么都不返回，等价于拦下（插件用 `block` 拦广告等）。 */
    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "UTF-8", 204, "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

    /**
     * `rewrite`：插件把地址换成另一个，宿主就地取回内容再交给 WebView。
     *
     * 这里跑在 WebView 的 IO 线程上，**超时必须短**——一个慢地址会拖住整页的资源加载。
     * 取不到就返回 null 退回放行，不让插件的一次改写把页面搞白。
     */
    private fun fetchAsResponse(url: String, request: Map<String, Any?>): WebResourceResponse? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            instanceFollowRedirects = true
            requestMethod = "GET"
            // 自己要原始字节：让 HttpURLConnection 自动解压会和 WebResourceResponse
            // 的 encoding 语义打架，索性不要压缩内容
            setRequestProperty("Accept-Encoding", "identity")
            @Suppress("UNCHECKED_CAST")
            (request["headers"] as? Map<Any?, Any?>)?.forEach { (k, v) ->
                val key = k?.toString().orEmpty()
                if (key.equals("Accept", true) || key.equals("Accept-Language", true)) {
                    setRequestProperty(key, v?.toString().orEmpty())
                }
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val mime = conn.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
            WebResourceResponse(
                mime,
                "UTF-8",
                code,
                conn.responseMessage ?: "OK",
                mapOf(
                    "Cache-Control" to (conn.getHeaderField("Cache-Control") ?: "no-cache"),
                    "Access-Control-Allow-Origin" to "*"
                ),
                ByteArrayInputStream(bytes)
            )
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        FileLogger.w("WebViewCtl", "rewrite 取回失败（放行）：$url - ${it.message}")
        null
    }

    /**
     * 调用插件的导航决策回调。返回 null 表示放行（或插件未提供该回调）。
     *
     * 返回值：`"cancel"` / `"external"` / 或重写后的 URL。
     */
    fun decideNavigation(
        binding: Binding,
        actionId: String,
        request: Map<String, Any?>
    ): String? {
        val invokeSync = syncInvoker ?: return null
        val raw = runCatching { invokeSync(actionId, gson.toJson(listOf(request))) }.getOrNull() ?: return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return when (obj.get("action")?.asString.orEmpty()) {
            "cancel" -> "cancel"
            "external" -> "external"
            "rewrite" -> obj.get("url")?.asString
            else -> null
        }
    }

    /** 插件返回的响应对象 → Android 的 [WebResourceResponse]。 */
    private fun buildResponse(resp: JsonObject): WebResourceResponse? = runCatching {
        val mime = resp.get("mimeType")?.asString ?: "text/html"
        val encoding = resp.get("encoding")?.asString ?: "UTF-8"
        val status = resp.get("statusCode")?.asInt ?: 200
        val reason = resp.get("reasonPhrase")?.asString ?: "OK"
        val headers = resp.get("headers")?.let { h ->
            runCatching { h.asJsonObject }.getOrNull()
                ?.entrySet()?.associate { it.key to it.value.asString }
        }.orEmpty()

        val body: InputStream = when {
            resp.has("text") -> ByteArrayInputStream(resp.get("text").asString.toByteArray(Charsets.UTF_8))
            resp.has("base64") -> {
                val b64 = resp.get("base64").asString
                ByteArrayInputStream(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
            resp.has("filePath") -> {
                val p = resp.get("filePath").asString
                // readResource 给的可能是宿主绝对路径，也可能是容器语义路径（/root/...），
                // 后者要用引擎的路径翻译才能读 —— 直接用 File 会拿到空文件、白屏。
                val f = File(p).takeIf { it.isFile }
                    ?: com.wy.openx.tools.AppContextHolder.containerEngine
                        ?.toHostFileIfContainerPath(p)?.takeIf { it.isFile }
                if (f != null) {
                    f.inputStream()
                } else {
                    FileLogger.w("WebViewCtl", "资源文件读不到：$p")
                    ByteArrayInputStream(ByteArray(0))
                }
            }
            else -> ByteArrayInputStream(ByteArray(0))
        }
        WebResourceResponse(mime, encoding, status, reason, headers, body)
    }.getOrNull()

    // ------------------------------------------------------------ 工具

    /** 在 WebView 上同步求值。超时返回 null。 */
    private fun evaluateSync(binding: Binding, script: String, timeoutMs: Long): String? {
        val wv = binding.webView ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        // 包一层：脚本自己抛错时也能拿到错误文本，而不是一个孤零零的 null
        val wrapped = """
            (function () {
              try { return JSON.stringify({ ok: true, value: ($script) }); }
              catch (e) { return JSON.stringify({ ok: false, error: String((e && e.message) || e) }); }
            })()
        """.trimIndent()
        onMain(binding) { web ->
            runCatching { web.evaluateJavascript(wrapped) { v -> result = v; latch.countDown() } }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        val raw = result ?: return null
        // evaluateJavascript 回传的是 JSON 字符串字面量（外层还包了一层引号）
        val decoded = runCatching { gson.fromJson(raw, String::class.java) }.getOrNull() ?: return null
        val obj = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull() ?: return null
        return if (obj.get("ok")?.asBoolean == true) {
            obj.get("value")?.takeIf { !it.isJsonNull }?.toString()
        } else {
            FileLogger.w("WebViewCtl", "求值失败：${obj.get("error")}")
            null
        }
    }

    /** 在主线程执行 WebView 操作，带超时（超时就放弃，不卡调用方线程）。 */
    private fun onMain(binding: Binding, block: (WebView) -> Unit) {
        val wv = binding.webView ?: return
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) {
            runCatching { block(wv) }
            return
        }
        val latch = CountDownLatch(1)
        Handler(main).post {
            try {
                runCatching { block(wv) }
            } finally {
                latch.countDown()
            }
        }
        latch.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun okJson(data: Any?): String =
        JsonObject().apply {
            // JS 侧约定：有 error 就抛，否则取 result（null 也能正确表达）
            add("result", if (data == null) com.google.gson.JsonNull.INSTANCE else gson.toJsonTree(data))
        }.toString()

    private fun errorJson(message: String): String =
        JsonObject().apply { addProperty("error", message) }.toString()
}
