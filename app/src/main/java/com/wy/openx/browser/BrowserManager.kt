package com.wy.openx.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.wy.openx.container.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 浏览器截图结果（openx 无多模态工具结果，仅用于落盘）。 */
data class BrowserImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

data class BrowserTabState(
    val id: String,
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** 是否有可后退/前进的历史（不同于「是否加载中」，供底部按钮正确禁用）。 */
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /**
     * 归属 AI 会话 id（0 = 无主：用户在面板手动开的）。
     *
     * 带上它是因为工具层在 **IO 线程**做归属过滤，而 `tabs`（mutableList）由主线程增删——
     * 直接读会竞态。本状态是 `_state` 的不可变快照，IO 线程读它安全。
     */
    val ownerConversationId: Long = 0L
)

/**
 * 一条收藏（顶栏星标与「收藏夹」抽屉共用一份）。
 *
 * [url] 存的是归一后的值（去掉尾部 `/`）：`https://a.com/` 与 `https://a.com`
 * 是同一个页面，不归一会各存一条，星标还会显示成空心。
 */
data class BrowserFavorite(
    val url: String,
    val title: String = "",
    val addedAt: Long = 0L
)

/** 收藏比较/存储用的 url 归一：去空白与尾部 `/`。 */
internal fun normalizeFavoriteUrl(url: String): String = url.trim().removeSuffix("/")

data class BrowserState(
    val tabs: List<BrowserTabState> = emptyList(),
    val activeTabId: String = "",
    val attached: Boolean = false,
    /** 收藏夹，最近收藏在前。 */
    val favorites: List<BrowserFavorite> = emptyList()
) {
    val activeTab: BrowserTabState? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    val url: String get() = activeTab?.url.orEmpty()
    val title: String get() = activeTab?.title.orEmpty()
    val loading: Boolean get() = activeTab?.loading == true
    val error: String? get() = activeTab?.error

    /** 当前页（或指定 url）是否已收藏。 */
    fun isFavorite(target: String = url): Boolean =
        target.isNotBlank() && favorites.any { it.url == normalizeFavoriteUrl(target) }
}

data class BrowserDialog(
    val type: String,
    val message: String,
    val defaultValue: String?
)

data class BrowserConsoleEntry(
    val level: String,
    val message: String,
    val line: Int,
    val source: String
)

/**
 * 内置浏览器引擎：多标签 WebView + 离屏（headless）渲染 + JS 注入 + 无障碍树 + 截图。
 *
 * 
 * - 去 Hilt（`@Singleton @Inject`）改为 [getOrCreate] 进程级单例（与 TerminalEngine 一致）；
 * - JSON 由 kotlinx.serialization 改为 Gson（openx 全仓用 Gson）；
 * - 日志走 openx 的 [FileLogger]（同时落运行日志）。
 */
class BrowserManager private constructor(
    private val appContext: Context
) {

    companion object {
        private const val TAG = "BrowserManager"
        private const val NAVIGATE_TIMEOUT_MS = 30_000L
        private const val EVAL_TIMEOUT_MS = 30_000L
        private const val WAIT_POLL_INTERVAL_MS = 500L
        private const val SCREENSHOT_QUALITY = 80
        private const val MAX_CONTENT_CHARS = 100_000
        private const val MAX_BACKBONE_NODES = 600
        private const val DOM_STABLE_QUIET_MS = 500L
        private const val DOM_STABLE_POLL_MS = 200L
        private const val GO_URL_TIMEOUT_MS = 3_000L
        private const val GO_POLL_MS = 100L
        private const val GO_LOAD_TIMEOUT_MS = 10_000L
        private const val HEADLESS_WIDTH_DP = 412
        private const val HEADLESS_HEIGHT_DP = 915
        private const val MAX_CONSOLE_LOGS = 200
        private const val DIALOG_TIMEOUT_MS = 30_000L
        /** 媒体预览包装页的缓存子目录与过期时间（仅用于清理旧文件）。 */
        private const val MEDIA_PREVIEW_DIR = "media-preview"
        private const val MEDIA_PREVIEW_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_TABS = 10

        /** 收藏夹存储：独立 SharedPreferences 文件 + 一个 JSON 字符串键。 */
        private const val PREF_FAVORITES = "openx_browser_favorites"
        private const val KEY_FAVORITES = "favorites_json"

        /** Eruda 资产路径（assets/scripts/eruda.min.js）。 */
        private const val ERUDA_ASSET = "scripts/eruda.min.js"

        /** 开启夜间模式：声明深色偏好 + 叠加半透明暗色蒙版（只压暗、不挡点击）。 */
        private const val NIGHT_MODE_ON_JS = """
(function() {
  try {
    var d = document;
    if (!d.documentElement) return;
    var id = '__openx_night_style__';
    if (!d.getElementById(id)) {
      var st = d.createElement('style');
      st.id = id;
      st.textContent = 'html{color-scheme:dark !important;}'
        + 'html::before{content:"";position:fixed;left:0;top:0;right:0;bottom:0;'
        + 'background:rgba(0,0,0,0.45);pointer-events:none;z-index:2147483647;}';
      (d.head || d.documentElement).appendChild(st);
    }
    var m = d.querySelector('meta[name="color-scheme"]');
    if (!m) { m = d.createElement('meta'); m.name = 'color-scheme'; m.content = 'dark'; (d.head || d.documentElement).appendChild(m); }
    else { m.content = 'dark'; }
  } catch (e) {}
})()
"""

        /** 关闭夜间模式：移除注入的样式与 meta。 */
        private const val NIGHT_MODE_OFF_JS = """
(function() {
  try {
    var d = document;
    var st = d.getElementById('__openx_night_style__');
    if (st && st.parentNode) st.parentNode.removeChild(st);
    var m = d.querySelector('meta[name="color-scheme"]');
    if (m) m.content = 'light';
  } catch (e) {}
})()
"""

        /** 显示 Eruda 控制台（脚本已先注入）。 */
        private const val ERUDA_SHOW_JS =
            "(function(){try{if(window.eruda){eruda.init();eruda.show();}}catch(e){}})()"

        /** 销毁 Eruda 控制台并移除悬浮球。 */
        private const val ERUDA_DESTROY_JS =
            "(function(){try{if(window.eruda){eruda.destroy();}}catch(e){}})()"

        /**
         * 桌面端 UA。
         *
         * ⚠ **必须与 [android.webkit.WebSettings.getDefaultUserAgent] 的 Android 版本无关**——
         * 这里给的是 Chrome/Windows 的固定串，站点据此返回桌面版式。
         * 去掉 `Mobile` 标识是让站点走桌面分支的关键。
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** 安卓手机 UA（Chrome Mobile）。 */
        const val ANDROID_PHONE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        /** 安卓平板 UA（Chrome Tablet，无 Mobile 标识）。 */
        const val ANDROID_TABLET_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** 苹果 iPhone UA（Mobile Safari）。 */
        const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

        /** 苹果 iPad UA（Tablet Safari）。 */
        const val IPAD_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"

        @Volatile
        private var sharedInstance: BrowserManager? = null

        /** 进程级单例：首次调用创建并持有。 */
        fun getOrCreate(context: Context): BrowserManager {
            val existing = sharedInstance
            if (existing != null) return existing
            return synchronized(this) {
                sharedInstance ?: BrowserManager(context.applicationContext).also { sharedInstance = it }
            }
        }

        fun getShared(): BrowserManager? = sharedInstance
    }

    private class TabHolder(
        val id: String,
        val webView: WebView,
        var url: String = "",
        var title: String = "",
        var loading: Boolean = false,
        var error: String? = null,
        /**
         * 拥有该标签的 **AI 会话** id（0 = 无主：浏览器面板里 UI 建的 / 用户手动开的）。
         *
         * 多会话并行时用于隔离：各会话优先操作自己的标签。无主标签**可被任何会话使用**，
         * 但**一旦被某会话使用就认领**（改成本会话），避免两个会话抢同一个页面互相导航
         * —— 这正是实测复现的串台（两个会话都落到无主 tab-5）。
         *
         * 可变：[claimIfUnowned] 在会话首次使用无主标签时改写它。写只发生在主线程，
         * 读经 `_state` 快照（`toState` 亦在主线程），故无跨线程竞态。
         */
        var ownerConversationId: Long = 0L,
        val consoleLogs: ArrayDeque<BrowserConsoleEntry> = ArrayDeque(),
        var pendingDialog: BrowserDialog? = null,
        var pendingDialogResult: JsResult? = null,
        var dialogTimeout: Runnable? = null,
        val pendingJsCalls: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap(),
        var loadDeferred: CompletableDeferred<Result<String>>? = null
    ) {
        fun toState(): BrowserTabState = BrowserTabState(
            id = id,
            url = url,
            title = title,
            loading = loading,
            error = error,
            // 直接查 WebView 的真实历史能力（在 UI 线程调用；publishState 均在主线程触发）。
            canGoBack = runCatching { webView.canGoBack() }.getOrDefault(false),
            canGoForward = runCatching { webView.canGoForward() }.getOrDefault(false),
            ownerConversationId = ownerConversationId
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<TabHolder>()
    private var activeTabId: String = ""
    /**
     * **每个 AI 会话各自的活动标签**：conversationId → tabId。
     *
     * 为什么需要：`activeTabId` 是全局单值，多会话并行时 A 调 `newTab` 会把全局活动标签
     * 改成 A 的，B 省略 `tabId` 的操作就会落到 A 的页面。按会话记录后，各会话的
     * 「省略 tabId」只在本会话的标签里解析，互不串台（见 [activeTabIdFor]）。
     *
     * 无主标签（用户手动开、cid=0）不参与这里，仍由 `activeTabId` 服务 UI。
     */
    private val perConversationActiveTab = HashMap<Long, String>()
    private var tabSeq = 1

    /** 浏览器设置持久化（UA 模式 / 夜间模式 / 开发者工具）。 */
    private val browserSettings = com.wy.openx.tools.BrowserSettingsStore(appContext)

    /**
     * 收藏夹持久化。
     *
     * 与 [browserSettings] 同构（独立 SharedPreferences 文件、改完即落盘），
     * 但单独一个文件：收藏是用户数据不是设置，清设置不该连带把收藏清掉。
     */
    private val favoritesPrefs =
        appContext.getSharedPreferences(PREF_FAVORITES, Context.MODE_PRIVATE)

    private fun loadFavorites(): List<BrowserFavorite> = runCatching {
        val raw = favoritesPrefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<BrowserFavorite>>() {}.type
        com.google.gson.Gson().fromJson<List<BrowserFavorite>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveFavorites(list: List<BrowserFavorite>) {
        runCatching {
            favoritesPrefs.edit()
                .putString(KEY_FAVORITES, com.google.gson.Gson().toJson(list))
                .apply()
        }
    }

    /** 当前收藏列表（最近收藏在前）。 */
    fun favorites(): List<BrowserFavorite> = _state.value.favorites

    /**
     * 收藏 / 取消收藏。返回 true 表示操作后处于「已收藏」。
     * 空 url（还没打开页面的新标签）不记。
     */
    fun toggleFavorite(url: String, title: String): Boolean {
        val key = normalizeFavoriteUrl(url)
        if (key.isBlank()) return false
        val existing = _state.value.favorites
        val hit = existing.any { it.url == key }
        val next = if (hit) {
            existing.filterNot { it.url == key }
        } else {
            listOf(BrowserFavorite(key, title.trim(), System.currentTimeMillis())) + existing
        }
        _state.update { it.copy(favorites = next) }
        saveFavorites(next)
        return !hit
    }

    /** 从收藏夹移除（抽屉里的删除按钮）。 */
    fun removeFavorite(url: String) {
        val key = normalizeFavoriteUrl(url)
        val next = _state.value.favorites.filterNot { it.url == key }
        _state.update { it.copy(favorites = next) }
        saveFavorites(next)
    }

    /** Eruda 脚本缓存（500KB，首次注入时读一次 assets）。 */
    private var erudaJs: String? = null

    /** 当前浏览器标识模式，见 [com.wy.openx.tools.BrowserSettingsStore] 的 `UA_*` 常量。 */
    val uaMode: String
        get() = browserSettings.uaMode

    /** 自定义 UA 字符串（[uaMode] = custom 时使用）。 */
    val customUa: String
        get() = browserSettings.customUa

    /** 是否桌面端 UA（兼容旧 API；等价于 [uaMode] = desktop_pc）。 */
    val desktopMode: Boolean
        get() = browserSettings.desktopMode

    /** 夜间模式开关。 */
    val nightMode: Boolean
        get() = browserSettings.nightMode

    /** 开发者工具（Eruda 控制台）开关。 */
    val devTools: Boolean
        get() = browserSettings.devTools

    /** 按当前 [uaMode] 给 WebView 设置 UA。 */
    private fun applyUserAgent(wv: WebView) {
        val ua = when (browserSettings.uaMode) {
            com.wy.openx.tools.BrowserSettingsStore.UA_DESKTOP_PC -> DESKTOP_UA
            com.wy.openx.tools.BrowserSettingsStore.UA_ANDROID_PHONE -> ANDROID_PHONE_UA
            com.wy.openx.tools.BrowserSettingsStore.UA_ANDROID_TABLET -> ANDROID_TABLET_UA
            com.wy.openx.tools.BrowserSettingsStore.UA_IPHONE -> IPHONE_UA
            com.wy.openx.tools.BrowserSettingsStore.UA_IPAD -> IPAD_UA
            com.wy.openx.tools.BrowserSettingsStore.UA_CUSTOM ->
                browserSettings.customUa.ifBlank { android.webkit.WebSettings.getDefaultUserAgent(wv.context) }
            else ->
                // 系统默认：用 WebView 默认 UA（带 Mobile 标识），站点据此返回手机版
                android.webkit.WebSettings.getDefaultUserAgent(wv.context)
        }
        runCatching { wv.settings.userAgentString = ua }
            .onFailure { FileLogger.w(TAG, "设置 UA 失败: ${it.message}") }
    }

    /**
     * 切换浏览器标识并**重载所有标签当前页**。
     *
     * 为什么必须重载：UA 只在页面加载时随请求发出，已加载的页面不会因为改了 UA 就变版式。
     *
     * @param mode 见 [com.wy.openx.tools.BrowserSettingsStore] 的 `UA_*` 常量
     * @param custom mode = custom 时写入的自定义 UA 字符串（null 表示不改）
     */
    suspend fun setUaMode(mode: String, custom: String? = null) = withContext(Dispatchers.Main) {
        browserSettings.uaMode = mode
        if (custom != null) browserSettings.customUa = custom
        tabs.forEach { tab ->
            applyUserAgent(tab.webView)
            // file:// 本地页没有"版式"概念，重载它没意义还可能弹"是否重发"，故跳过。
            val u = tab.url
            if (u.startsWith("http://") || u.startsWith("https://")) {
                runCatching { tab.webView.reload() }
            }
        }
        FileLogger.i(TAG, "浏览器标识切换为 $mode，已重载 ${tabs.size} 个标签")
        publishState()
    }

    /**
     * 兼容旧 API：切换 UA 桌面/移动端。
     *
     * @param desktop true=桌面端（desktop_pc），false=系统默认（default）
     */
    suspend fun setDesktopMode(desktop: Boolean) =
        setUaMode(
            if (desktop) com.wy.openx.tools.BrowserSettingsStore.UA_DESKTOP_PC
            else com.wy.openx.tools.BrowserSettingsStore.UA_DEFAULT
        )

    /** 切换夜间模式并即时作用于所有已打开标签。 */
    suspend fun setNightMode(on: Boolean) = withContext(Dispatchers.Main) {
        browserSettings.nightMode = on
        tabs.forEach { applyNightMode(it.webView) }
        FileLogger.i(TAG, "夜间模式已${if (on) "开启" else "关闭"}")
        publishState()
    }

    /** 切换开发者工具并即时作用于所有已打开标签（Eruda + WebView 内容调试）。 */
    suspend fun setDevTools(on: Boolean) = withContext(Dispatchers.Main) {
        browserSettings.devTools = on
        runCatching { WebView.setWebContentsDebuggingEnabled(on) }
        tabs.forEach { applyDevTools(it.webView) }
        FileLogger.i(TAG, "开发者工具已${if (on) "开启" else "关闭"}")
        publishState()
    }

    /**
     * 夜间模式：向页面声明深色偏好（`prefers-color-scheme: dark` + `color-scheme: dark`），
     * 并叠加一层半透明暗色蒙版（`pointer-events:none`，只压暗不改色、不挡点击）。
     * 关闭时移除两者。
     */
    private fun applyNightMode(wv: WebView) {
        val on = browserSettings.nightMode
        val script = if (on) NIGHT_MODE_ON_JS else NIGHT_MODE_OFF_JS
        runCatching { wv.evaluateJavascript(script, null) }
            .onFailure { FileLogger.w(TAG, "应用夜间模式失败: ${it.message}") }
    }

    /** 开发者工具：开启时注入 Eruda 控制台，关闭时销毁它。 */
    private fun applyDevTools(wv: WebView) {
        if (browserSettings.devTools) {
            runCatching {
                val js = erudaJs ?: appContext.assets.open(ERUDA_ASSET)
                    .bufferedReader().use { it.readText() }.also { erudaJs = it }
                wv.evaluateJavascript(js, null)
                wv.evaluateJavascript(ERUDA_SHOW_JS, null)
            }.onFailure { FileLogger.w(TAG, "注入 Eruda 失败: ${it.message}") }
        } else {
            runCatching { wv.evaluateJavascript(ERUDA_DESTROY_JS, null) }
        }
    }

    private var containerView: FrameLayout? = null
    private var hiddenHost: ViewGroup? = null

    private val _state = MutableStateFlow(BrowserState(favorites = loadFavorites()))
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /**
     * 容器路径解析后端：把 `/root/workspace/a.html` 这类 **PRoot 视图路径**映射到宿主真实文件。
     *
     * WebView 跑在宿主进程，看不到容器视图，裸容器路径会 404；故 [resolveLocalFileUrl]
     * 需要它做映射。由 MainViewModel 注入（它持有构造 [com.wy.openx.container.BrowseFileSystem]
     * 所需的 installer / settings 依赖）。**未注入时**（如极早的 headless 调用）退化为只认
     * 宿主绝对路径，不抛异常。
     */
    @Volatile
    var browseFileSystem: com.wy.openx.container.BrowseFileSystem? = null

    /** 选择器辅助函数，prepend 到所有需要选择器的 JS 中。支持 ref= / text= / text*= / role= / xpath= / CSS。 */
    private val selectorHelper = """
        function __resolveSelector(selector){
            if(selector.startsWith('ref=')){
                var id=selector.substring(4);
                var reg=window.__bicodeRefs||{};
                var el=reg[id];
                return (el && el.isConnected) ? el : null;
            }
            if(selector.startsWith('text=')){
                var t=selector.substring(5);
                var els=document.querySelectorAll('*');
                for(var i=els.length-1;i>=0;i--){
                    var own=els[i].childNodes;
                    for(var j=0;j<own.length;j++){
                        if(own[j].nodeType===3&&own[j].textContent.trim()===t)return els[i];
                    }
                }
                for(var i=0;i<els.length;i++){
                    if(els[i].textContent.trim()===t)return els[i];
                }
                return null;
            }
            if(selector.startsWith('text*=')){
                var t=selector.substring(6);
                var els=document.querySelectorAll('*');
                var best=null,bestLen=Infinity;
                for(var i=0;i<els.length;i++){
                    var tag=els[i].tagName;
                    if(tag==='HTML'||tag==='BODY'||tag==='HEAD'||tag==='SCRIPT'||tag==='STYLE')continue;
                    var text=els[i].textContent||'';
                    if(text.includes(t)&&text.length<bestLen){
                        best=els[i];bestLen=text.length;
                    }
                }
                return best;
            }
            if(selector.startsWith('role=')){
                var m=selector.match(/^role=(\w+)(?:\[name="(.+)"\])?$/);
                if(!m)return null;
                var role=m[1],name=m[2];
                var els=document.querySelectorAll('[role="'+role+'"]');
                if(name){
                    for(var i=0;i<els.length;i++){
                        if(els[i].textContent.includes(name))return els[i];
                    }
                    return null;
                }
                return els[0]||null;
            }
            if(selector.startsWith('xpath=')){
                var x=selector.substring(6);
                return document.evaluate(x,document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue;
            }
            return document.querySelector(selector);
        }
    """.trimIndent()

    /**
     * 注入到**每个**页面的 JS 桥。
     *
     * 安全约束（改动前务必先读）：本对象只能有「完成一个由本页自己发起的 evaluate 调用」
     * 这类纯数据回调，**绝不能**加入文件读写、shell 执行、Intent 跳转、读取 App 私有数据
     * 等能力——`addJavascriptInterface` 注入的��象对**页面上任何脚本**可见，
     * AI 让 WebView 加载的第三方站点同样能调用它。一旦桥接面扩大，就是远程代码执行。
     *
     * 当前仅两个方法，都只操作 [TabHolder.pendingJsCalls] 这个 Map，风险可控。
     * 页面重定向与关闭时会 removeJavascriptInterface 清理。
     */
    inner class BrowserJsBridge(private val tabId: String) {
        @JavascriptInterface
        fun resolve(callId: String, result: String) {
            val tab = findTab(tabId) ?: return
            tab.pendingJsCalls[callId]?.complete(result)
            tab.pendingJsCalls.remove(callId)
        }

        @JavascriptInterface
        fun reject(callId: String, error: String) {
            val tab = findTab(tabId) ?: return
            tab.pendingJsCalls[callId]?.completeExceptionally(RuntimeException(error))
            tab.pendingJsCalls.remove(callId)
        }
    }

    private fun findTab(tabId: String): TabHolder? = tabs.firstOrNull { it.id == tabId }

    private fun ensureActiveTab(): TabHolder {
        if (tabs.isEmpty()) {
            return createTabInternal("tab-${tabSeq++}", null)
        }
        return findTab(activeTabId) ?: tabs.first().also { activeTabId = it.id }
    }

    private fun resolveTab(tabId: String?): TabHolder {
        if (tabId.isNullOrBlank()) {
            return ensureActiveTab()
        }
        return findTab(tabId) ?: throw IllegalArgumentException("标签页不存在: $tabId")
    }

    private fun configureWebView(wv: WebView, tabId: String) {
        val tabHolderRef = { findTab(tabId) }

        wv.webViewClient = object : WebViewClient() {
            /**
             * 拦截非 http(s) scheme：目标站点常尝试唤起自家 App（如百度 `baiduboxapp://`、
             * 微博 `sinaweibo://`），WebView 无法处理会报 `net::ERR_UNKNOWN_URL_SCHEME` 并让
             * 主框架加载失败（表现为页面加载不出来、后退无历史）。这里对自定义 scheme 一律
             * 拦截（不交给 WebView），仅放行 http/https 在主框架内导航。
             */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString().orEmpty()
                return when {
                    u.startsWith("http://") || u.startsWith("https://") -> false // 放行，WebView 自行加载
                    u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:") -> false
                    u.isBlank() -> false
                    else -> {
                        FileLogger.i(TAG, "拦截非 http(s) scheme: $u")
                        true // 拦截，不让 WebView 处理，避免 ERR_UNKNOWN_URL_SCHEME
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val tab = tabHolderRef() ?: return
                tab.loading = true
                tab.url = url.orEmpty()
                tab.title = ""
                tab.error = null
                publishState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabHolderRef() ?: return
                tab.loading = false
                tab.url = url.orEmpty()
                val finish = {
                    tab.loadDeferred?.complete(Result.success(url.orEmpty()))
                    tab.loadDeferred = null
                    publishState()
                }
                if (view == null) {
                    finish()
                } else {
                    // 页面每次加载完成后重新应用夜间模式与开发者工具（新文档不保留旧注入）。
                    if (browserSettings.nightMode) applyNightMode(view)
                    if (browserSettings.devTools) applyDevTools(view)
                    view.evaluateJavascript("(function(){return document.title})()") { result ->
                        val title = result?.trim()?.trim('"') ?: ""
                        tab.title = title
                        finish()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                val tab = tabHolderRef() ?: return
                if (request?.isForMainFrame == true) {
                    val msg = error?.description?.toString() ?: "未知错误"
                    FileLogger.w(TAG, "主框架加载失败: ${request.url} -> $msg")
                    tab.loading = false
                    tab.error = msg
                    tab.loadDeferred?.complete(Result.failure(RuntimeException(msg)))
                    tab.loadDeferred = null
                    publishState()
                }
            }

            /** 历史栈变化（前进/后退/新导航）时刷新状态，使底部按钮的可用态实时准确。 */
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                publishState()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val tab = tabHolderRef() ?: return true
                val level = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "error"
                    ConsoleMessage.MessageLevel.WARNING -> "warning"
                    ConsoleMessage.MessageLevel.DEBUG -> "debug"
                    else -> "log"
                }
                if (tab.consoleLogs.size >= MAX_CONSOLE_LOGS) tab.consoleLogs.removeFirst()
                tab.consoleLogs.addLast(BrowserConsoleEntry(level, msg.message(), msg.lineNumber(), msg.sourceId()))
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                tab.pendingDialog = BrowserDialog("alert", message.orEmpty(), null)
                tab.pendingDialogResult = null
                result.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                holdDialog(tab, "confirm", message.orEmpty(), null, result)
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                holdDialog(tab, "prompt", message.orEmpty(), defaultValue, result)
                return true
            }
        }

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // UA 模式：桌面/移动。站点普遍按 UA 返回不同版式；WebView 默认 UA 带 "Mobile"
        // 标识，所有站点都给手机版。这里按设置统一覆盖（切换后重建标签才会生效，见 setDesktopMode）。
        applyUserAgent(wv)
        // 开发者工具开启时确保 WebView 内容调试可用（USB chrome://inspect）。
        if (browserSettings.devTools) runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        // 允许加载 file:// 本地页面（预览工作区里的 HTML/报告等）。
        // 该默认值随 targetSdk 变化（N 起默认 false），显式开启避免被静默关闭。
        // ⚠ 只开文件读取，**不开 allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs**
        // ——那两个会让 file:// 页面能读取任意本地文件并发往网络，是典型的越权面。
        // 本项目靠 targetSdk 28 的宽松策略本可开，但没必要，保持最小权限。
        wv.settings.allowFileAccess = true
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.addJavascriptInterface(BrowserJsBridge(tabId), "__browserBridge__")

        // 设置 Headless 默认尺寸，保证后台与离屏测试可用
        val density = appContext.resources.displayMetrics.density
        val width = (HEADLESS_WIDTH_DP * density).toInt()
        val height = (HEADLESS_HEIGHT_DP * density).toInt()
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, width, height)
    }

    private fun holdDialog(tab: TabHolder, type: String, message: String, defaultValue: String?, result: JsResult) {
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.pendingDialog = BrowserDialog(type, message, defaultValue)
        tab.pendingDialogResult = result
        val timeout = Runnable {
            if (tab.pendingDialogResult === result) {
                tab.pendingDialogResult = null
                tab.pendingDialog = null
                result.cancel()
            }
        }
        tab.dialogTimeout = timeout
        mainHandler.postDelayed(timeout, DIALOG_TIMEOUT_MS)
    }

    private fun createTabInternal(id: String, initialUrl: String?, ownerConversationId: Long = 0L): TabHolder {
        val wv = WebView(appContext)
        configureWebView(wv, id)
        val tab = TabHolder(id = id, webView = wv, ownerConversationId = ownerConversationId)
        tabs.add(tab)
        activeTabId = id
        // 归属会话的活动标签也指到新标签：AI 紧接着的「省略 tabId」操作应作用于它刚建的页。
        rememberActive(ownerConversationId, id)

        // 若当前前端面板未打开，自动挂到 hiddenHost 激活 Chromium 渲染管线
        if (containerView == null && hiddenHost != null) {
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            hiddenHost?.addView(wv, ViewGroup.LayoutParams(width, height))
        }

        publishState()
        updateContainerView()
        FileLogger.i(TAG, "Tab created: $id (initialUrl=$initialUrl)")
        return tab
    }

    private fun publishState() {
        _state.update {
            it.copy(
                tabs = tabs.map { t -> t.toState() },
                activeTabId = activeTabId,
                attached = containerView != null
            )
        }
    }

    private fun updateContainerView() {
        val container = containerView ?: return
        val active = findTab(activeTabId) ?: return
        if (active.webView.parent === container) return

        container.removeAllViews()
        (active.webView.parent as? ViewGroup)?.removeView(active.webView)
        container.addView(
            active.webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 把其他非激活 Tab 放回 hiddenHost，保持渲染管线处于就绪状态
        hiddenHost?.let { host ->
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            tabs.forEach { t ->
                if (t.id != activeTabId && t.webView.parent !== host) {
                    (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                    host.addView(t.webView, ViewGroup.LayoutParams(width, height))
                }
            }
        }
    }

    /**
     * 注册 Activity 级的隐藏宿主容器，让离屏 WebView 在后台也能触发 onAttachedToWindow 激活光栅化渲染。
     *
     * 会把**已脱离窗口**的 WebView 重新挂到新宿主（不止 `parent == null`）——Activity 重建（旋转/主题切换）
     * 时旧宿主随窗口销毁，但 BrowserManager 是进程级单例、WebView 仍持旧宿主引用；若不重挂，
     * 之后的后台截图会因 WebView 不在窗口上而恒为白图。
     */
    fun attachHiddenHost(host: ViewGroup) {
        hiddenHost = host
        val density = appContext.resources.displayMetrics.density
        val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
        val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
        tabs.forEach { tab ->
            val wv = tab.webView
            val parent = wv.parent
            if (parent === host) return@forEach
            // 面板打开时，激活 Tab 由 Compose 容器持有，不要抢过来
            if (containerView != null && parent === containerView) return@forEach
            (parent as? ViewGroup)?.removeView(wv)
            host.addView(wv, ViewGroup.LayoutParams(width, height))
        }
    }

    fun detachHiddenHost() {
        hiddenHost?.removeAllViews()
        hiddenHost = null
    }

    /**
     * 新建一个 Compose 挂载容器，并把当前激活 Tab 的 WebView 挂进去。
     *
     * 容器**每次新建、绝不复用**：同一个 View 被塞进两棵视图树时，
     * AndroidViewHolder 构造里的 addView 会抛
     * "The specified child already has a parent"（真机 2.9.5 崩溃：Scaffold 内容槽
     * 重新 subcompose → composeInitial → ViewFactoryHolder.<init>）。
     * 旧容器留在原处但已空，WebView 只属于最新的那个容器。
     */
    fun attachContainerView(context: Context): FrameLayout {
        val container = FrameLayout(context)
        containerView?.removeAllViews()
        containerView = container
        ensureActiveTab()
        findTab(activeTabId)?.let { active ->
            (active.webView.parent as? ViewGroup)?.removeView(active.webView)
            container.addView(
                active.webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        _state.update { it.copy(attached = true) }
        return container
    }

    /**
     * 组合释放：只回收**自己那一个**容器。
     *
     * 旧组合槽的 dispose 可能晚于新容器接手（同一帧建新节点、再拆旧节点），
     * 不过滤就会把新容器清空 —— 表现是浏览器页白屏。
     */
    fun detachViewHierarchy(container: View) {
        if (containerView !== container) return
        container.removeAllViews()
        containerView = null
        _state.update { it.copy(attached = false) }

        // 前端面板关闭后，把当前激活 Tab 移回 hiddenHost 保持渲染管线激活
        hiddenHost?.let { host ->
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            findTab(activeTabId)?.let { active ->
                if (active.webView.parent !== host) {
                    (active.webView.parent as? ViewGroup)?.removeView(active.webView)
                    host.addView(active.webView, ViewGroup.LayoutParams(width, height))
                }
            }
        }
    }

    fun destroy() {
        tabs.forEach { tab ->
            tab.webView.apply {
                stopLoading()
                removeJavascriptInterface("__browserBridge__")
                destroy()
            }
            tab.loadDeferred?.cancel()
            tab.pendingJsCalls.values.forEach { it.cancel() }
            tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
            tab.pendingDialogResult?.cancel()
            tab.consoleLogs.clear()
        }
        tabs.clear()
        containerView?.removeAllViews()
        containerView = null
        publishState()
    }

    fun isVisible(tabId: String? = null): Boolean {
        val tab = findTab(tabId ?: activeTabId) ?: return false
        val wv = tab.webView
        return wv.parent != null && wv.width > 0 && wv.height > 0
    }

    // ================= 多标签页管理 API =================

    /**
     * 新建标签页。
     *
     * @param ownerConversationId 发起方 AI 会话 id（0 = 无主，用户在浏览器面板手动新建）。
     *   多会话并行时，只有本会话的标签会被本会话的操作选中，见 [resolveTabIdFor]。
     */
    suspend fun newTab(url: String? = null, ownerConversationId: Long = 0L): String = withContext(Dispatchers.Main) {
        if (tabs.size >= MAX_TABS) {
            throw IllegalStateException("已达到最大标签页数量限制 ($MAX_TABS)")
        }
        val id = "tab-${tabSeq++}"
        createTabInternal(id, url, ownerConversationId)
        if (!url.isNullOrBlank()) {
            navigate(url, id)
        }
        id
    }

    suspend fun closeTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        val tab = findTab(tabId) ?: return@withContext false
        val index = tabs.indexOf(tab)
        tab.webView.apply {
            stopLoading()
            removeJavascriptInterface("__browserBridge__")
            destroy()
        }
        tab.loadDeferred?.cancel()
        tab.pendingJsCalls.values.forEach { it.cancel() }
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.pendingDialogResult?.cancel()
        tab.consoleLogs.clear()
        tabs.remove(tab)
        // 清掉指向该标签的「会话活动标签」记录，否则 activeTabIdFor 会拿到一个已关闭的 id
        // （虽有 contains 兜底，但留着陈旧键没意义，且会话多了会累积）。
        perConversationActiveTab.entries.removeAll { it.value == tabId }

        if (tabs.isEmpty()) {
            // 所有标签都被关闭，自动重置为一个新的空白标签页
            createTabInternal("tab-${tabSeq++}", null)
        } else if (activeTabId == tabId) {
            val newIndex = (index - 1).coerceAtLeast(0)
            activeTabId = tabs[newIndex].id
        }

        updateContainerView()
        publishState()
        true
    }

    suspend fun selectTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        val tab = findTab(tabId) ?: return@withContext false
        activeTabId = tabId
        // 同步记录到该标签归属会话的"活动标签"：AI 会话省略 tabId 时能落到这里选中的它；
        // 无主标签（0）不记录——那是用户在 UI 里切，与任何会话无关。
        rememberActive(tab.ownerConversationId, tabId)
        updateContainerView()
        publishState()
        true
    }

    fun listTabs(): List<BrowserTabState> = _state.value.tabs

    /**
     * 取当前活动标签 id；**无标签时会隐式新建一个**。
     *
     * ⚠ **只能在主线程调用**：新建标签会构造 WebView，而 WebView 必须在有 Looper 的线程创建。
     * 工具层（跑在 IO 线程）一律用 [peekActiveTabId]。为防误用，这里加主线程兜底：
     * 非主线程时直接返回 [peekActiveTabId]（不建标签），把「崩溃」降级为「拿不到 id」。
     */
    fun getActiveTabId(): String {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            FileLogger.w(TAG, "getActiveTabId 在非主线程被调用，已降级为只读 peek（避免 WebView 跨线程创建崩溃）")
            return peekActiveTabId()
        }
        ensureActiveTab()
        return activeTabId
    }

    /**
     * **只读**地取当前活动标签 id，**不会创建标签**，可在任意线程调用。
     *
     * ⚠ 与 [getActiveTabId] 的区别是本条的关键：[getActiveTabId] 会经 [ensureActiveTab] 在
     * 「还没有任何标签」时**隐式新建一个标签**——而新建 WebView **必须在有 Looper 的线程（主线程）**。
     * 工具的响应封装（`BrowserTool.envelope`/`errorResult`）跑在 IO 线程，若调 [getActiveTabId]，
     * 首次操作（如 `listTabs`，此时还没有标签）就会在 IO 线程建 WebView →
     * `WebView cannot be initialized on a thread that has no Looper` 崩溃。
     *
     * 故响应封装一律用本方法：读 `_state` 快照（线程安全）、无标签时返回空串、绝不创建。
     */
    fun peekActiveTabId(): String {
        val snap = _state.value
        return snap.activeTabId.ifBlank { snap.tabs.firstOrNull()?.id ?: "" }
    }

    // ================= 会话归属（多会话并行隔离） =================
    //
    // ⚠ 下面几个查询会被工具层在 **IO 线程**调用，而 `tabs` 由主线程增删。
    // 故一律读 `_state`（不可变快照），不直接遍历 `tabs`，避免竞态。

    /** 某标签归属的 AI 会话 id（0 = 无主：用户手动开的标签 / 标签不存在）。 */
    fun tabOwner(tabId: String): Long =
        _state.value.tabs.firstOrNull { it.id == tabId }?.ownerConversationId ?: 0L

    /**
     * 列出**某会话可见**的标签：本会话自己的 + 无主（用户手动开的）。
     *
     * 与终端工具同一规则：别的会话的标签既不出现在列表里，也不能被操作。
     */
    fun listTabsFor(conversationId: Long): List<BrowserTabState> =
        BrowserTabOwnership.visibleTo(_state.value.tabs, { it.ownerConversationId }, conversationId)

    /** 该会话当前的活动标签 id；找不到返回 null。 */
    fun activeTabIdFor(conversationId: Long): String? {
        val visible = listTabsFor(conversationId)
        if (visible.isEmpty()) return null
        // 「优先自己的标签，其次无主标签」：多会话下 AI 应作用于自己建的页面，
        // 只在没有自己的标签时才接手用户打开的页面（接手后会被认领，见 resolveTabIdFor）。
        val preferred = visible.filter { it.ownerConversationId == conversationId }
        val pool = (preferred.ifEmpty { visible }).map { it.id }
        return BrowserTabOwnership.pickActive(pool, perConversationActiveTab[conversationId])
    }

    /**
     * 把「可能省略的 tabId + 当前会话」解析成**确定的目标标签 id**。
     *
     * 无主标签可用，但**一旦被本会话使用就认领**（见 [BrowserTabOwnership.shouldClaim]），
     * 避免两个会话抢同一个页面互相导航。认领要在主线程改 `TabHolder`，故本函数是 suspend。
     *
     * @return 目标 id；`null` 表示本会话没有任何可用标签（调用方应提示先 newTab）。
     * @throws IllegalArgumentException 显式指定的 tabId 属于别的会话时抛。
     */
    suspend fun resolveTabIdFor(tabId: String?, conversationId: Long): String? {
        val resolved: String? = if (!tabId.isNullOrBlank()) {
            if (!BrowserTabOwnership.isVisibleTo(tabOwner(tabId), conversationId)) {
                throw IllegalArgumentException("标签「$tabId」属于另一个会话，本会话不能操作它")
            }
            tabId
        } else {
            activeTabIdFor(conversationId)
        }
        if (resolved != null) claimIfUnowned(resolved, conversationId)
        return resolved
    }

    /** 无主标签被某会话使用时改归它所有；用户（会话 0）不认领。 */
    private suspend fun claimIfUnowned(tabId: String, conversationId: Long) {
        if (!BrowserTabOwnership.shouldClaim(tabOwner(tabId), conversationId)) return
        withContext(Dispatchers.Main) {
            findTab(tabId)?.let { tab ->
                if (tab.ownerConversationId == 0L) {
                    tab.ownerConversationId = conversationId
                    rememberActive(conversationId, tabId)
                    publishState()
                    FileLogger.i(TAG, "标签 $tabId 被会话 $conversationId 认领（原为无主）")
                }
            }
        }
    }

    /** 记录某会话的活动标签（供 [activeTabIdFor] 下次解析）。 */
    private fun rememberActive(conversationId: Long, tabId: String) {
        if (conversationId != 0L) perConversationActiveTab[conversationId] = tabId
    }

    // ================= 浏览器操作 API =================

    suspend fun navigate(url: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val target = url.trim()
        val isLocal = isLocalPathInput(target)
        val localUrl = if (isLocal) {
            // 本地文件（file:// 或裸容器路径）：映射为宿主真实路径，再交给 WebView。
            // 解析在 IO 线程做（可能触发容器路径落盘/拷贝），故先 withContext 出去再回来。
            // null = 文件确实不存在（宿主直连与容器映射都没命中）。
            withContext(Dispatchers.IO) { resolveLocalFileUrl(target) }
        } else {
            null
        }
        when {
            target.startsWith("http://") || target.startsWith("https://") -> loadInto(tab, target)
            // 本地路径必须在此分支收敛：若解析失败就掉进下面的「裸域名」分支，
            // 会把 `/root/a.html` 当成域名去请求 `https:///root/a.html`，纯属胡闹。
            isLocal -> {
                if (localUrl == null) {
                    val msg = "本地文件不存在: $target"
                    FileLogger.w(TAG, msg)
                    // 显式写入 error，供 BrowserTool 判失败（不依赖 WebView 回调）。
                    tab.loading = false
                    tab.error = msg
                    publishState()
                    ""
                } else {
                    // 音视频不能直接 loadUrl：WebView 没有 Chrome 的「媒体文档」页，
                    // 顶层打开 mp3/mp4 只会白屏。这里生成一个带 <audio>/<video> 的
                    // 本地包装页（与媒体同盘 → 子资源 file:// 可加载），交给播放器控件。
                    val mediaPage = withContext(Dispatchers.IO) { writeMediaPreviewPage(localUrl) }
                    if (mediaPage != null) loadInto(tab, mediaPage) else loadInto(tab, localUrl)
                }
            }
            else -> {
                // 无协议头：优先 https，SSL/连接失败再回退 http（部分站点未配置 SSL）。
                // loadInto 失败时返回空串且不抛（避免从 WebView 主线程回调逃逸崩溃），故按返回值判断回退。
                val httpsResult = loadInto(tab, "https://$target")
                if (httpsResult.isNotBlank()) httpsResult else loadInto(tab, "http://$target")
            }
        }
    }

    /**
     * 输入是否为本地文件路径：`file://`，或以 `/`、`~`、`./`、`../` 开头的裸路径。
     *
     * 注意与「裸域名」区分：域名不会是单个 `/` 开头，所以这里的判定不会误吞网址。
     */
    private fun isLocalPathInput(input: String): Boolean =
        input.startsWith("file://") ||
            input.startsWith("/") ||
            input.startsWith("~") ||
            input.startsWith("./") ||
            input.startsWith("../")

    /**
     * 把本地文件输入解析为 WebView 可加载的 `file://` URL。
     *
     * **为什么需要映射**：WebView 跑在 App 宿主进程，看到的是设备真实文件系统，
     * **看不到容器的 PRoot 视图**。而 AI / 用户给的多是容器路径
     *（`/root/workspace/a.html`、`~/.openx/x.html`），直接用会 404。
     * 这里经 [com.wy.openx.container.BrowseFileSystem.hostFile] 换成宿主真实文件，
     * 于是**页面本身与其相对子资源（css/js/图片）都能正常加载**。
     *
     * 顺序：先按宿主绝对路径直接试（用户可能直接粘了 `/sdcard/...`），
     * 命中即用；否则走容器路径映射。
     *
     * @return 可加载的 `file://` URL；**文件确实不存在时返回 null**（由 [navigate] 显式报错，
     *   不再回退成指向不存在文件的 `file://`——那样 WebView 只会给个笼统的
     *   `ERR_FILE_NOT_FOUND`，且与「空 URL 成功」难以区分）。
     */
    private fun resolveLocalFileUrl(input: String): String? {
        val rawPath = if (input.startsWith("file://")) {
            // 去掉协议后还要 Uri 解码：路径里的空格、中文等会被编码成 %XX。
            runCatching { android.net.Uri.decode(input.removePrefix("file://")) }.getOrDefault(input.removePrefix("file://"))
        } else {
            input
        }
        if (rawPath.isBlank()) return null
        // `~` 展开遵循容器约定：`~` = `/root`（与 BrowseFileSystem 的 OPENX_ROOT/工作区别名一致）。
        val expanded = when {
            rawPath == "~" -> "/root"
            rawPath.startsWith("~/") -> "/root/" + rawPath.removePrefix("~/")
            else -> rawPath
        }
        // 1) 宿主绝对路径直接命中（file:// 指向设备真实文件时的常见情形）。
        if (expanded.startsWith("/")) {
            val direct = File(expanded)
            if (direct.exists()) return android.net.Uri.fromFile(direct).toString()
        }
        // 2) 走容器路径映射；映射后仍不存在则该文件确实找不到。
        val fs = browseFileSystem ?: return null
        return runCatching {
            val host = fs.hostFile(expanded)
            if (host.exists()) android.net.Uri.fromFile(host).toString() else null
        }.getOrNull()
    }

    /**
     * 音视频文件 → 生成本地盘上的包装页（含 `<audio>` / `<video>` 控件），返回其 `file://` URL。
     *
     * **为什么不能直接 `loadUrl(文件URL)`**：Chrome 有专门的「媒体文档」页，顶层打开
     * mp3/mp4 会得到播放器；Android WebView **没有**这个页面（`AwContents` 不实现
     * MediaDocument），顶层加载音视频只会白屏或触发下载。所以这里必须自己包一层 HTML。
     *
     * 包装页落在 `cacheDir/media-preview/`，与媒体同为真实文件系统上的 `file://`：
     * 在 `allowFileAccess = true`（见 `configureWebView`）下子资源可以正常加载。
     * 页面**不自动播放**（`autoplay` 在无手势时会被 Chromium 拦，反而不稳定）。
     *
     * @return 非音视频 / 写盘失败返回 null（调用方回退直接 loadUrl）。
     */
    private fun writeMediaPreviewPage(localUrl: String): String? {
        val kind = BrowserPreviewFormats.mediaKind(localUrl) ?: return null
        val rawName = runCatching { android.net.Uri.parse(localUrl).lastPathSegment }.getOrNull()
            ?.let { runCatching { android.net.Uri.decode(it) }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
            ?: localUrl.substringAfterLast('/')
        val safeName = rawName
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val src = localUrl.replace("\"", "%22")
        val player = when (kind) {
            BrowserPreviewFormats.MediaKind.AUDIO ->
                """<audio controls preload="metadata" src="$src"></audio>"""
            BrowserPreviewFormats.MediaKind.VIDEO ->
                """<video controls playsinline preload="metadata" src="$src"></video>"""
        }
        val html = """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$safeName</title>
            <style>
              html,body{margin:0;height:100%;background:#0f1115;color:#e8e8e8;
                font:13px/1.6 system-ui,-apple-system,sans-serif}
              body{display:flex;flex-direction:column;align-items:center;justify-content:center;
                gap:16px;padding:16px;box-sizing:border-box}
              video{width:100%;max-height:calc(100vh - 90px);background:#000;border-radius:10px}
              audio{width:100%;max-width:560px}
              .name{opacity:.7;word-break:break-all;text-align:center;font-size:12px}
            </style>
            </head><body>
            $player
            <div class="name">$safeName</div>
            </body></html>
        """.trimIndent()

        val dir = File(appContext.cacheDir, MEDIA_PREVIEW_DIR).apply { mkdirs() }
        val fileName = "media-" + Integer.toHexString(localUrl.hashCode()) + ".html"
        // 顺手清掉别的（非本次）预览页：缓存目录本就会被系统回收，这里只防长期堆积。
        runCatching {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (f.name != fileName && now - f.lastModified() > MEDIA_PREVIEW_TTL_MS) f.delete()
            }
        }
        val out = File(dir, fileName)
        return runCatching {
            out.writeText(html, Charsets.UTF_8)
            android.net.Uri.fromFile(out).toString()
        }.getOrElse {
            FileLogger.w(TAG, "写媒体预览页失败: ${it.message}")
            null
        }
    }

    private suspend fun loadInto(tab: TabHolder, finalUrl: String): String {
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        tab.webView.loadUrl(finalUrl)
        // 加载失败（含 net::ERR_UNKNOWN_URL_SCHEME 等）时 onReceivedError 会把 Result.failure 塞回来。
        // 这里**不能 getOrThrow**：该异常会从 WebView 主线程回调逃逸导致进程崩溃；错误信息已由
        // onReceivedError 写入 tab.error 供 UI 展示，故失败时返回空串并清除错误标记即可。
        return try {
            withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrDefault("")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // withTimeout 自身的超时（非调用方取消）：返回空串，不抛。
            FileLogger.w(TAG, "loadInto 超时: $finalUrl")
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "loadInto 失败: $finalUrl -> ${e.message}")
            ""
        }
    }

    suspend fun evaluateJavaScript(script: String, tabId: String? = null): String? = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        tab.pendingJsCalls[callId] = deferred

        // 彻底废除 eval，改用 async IIFE 包装并直接 await。
        // 单表达式自动补 return（支持 Promise 自动展开等待，且不受 CSP unsafe-eval 限制）；
        // 语句体（顶层含 ; / 换行或以语句关键字开头）原样放入函数体，值由脚本自行 return。
        val trimmed = script.trim()
        val body = if (isExpressionScript(trimmed)) {
            val expr = trimmed.trimEnd().removeSuffix(";").trimEnd()
            "return ($expr);"
        } else {
            trimmed
        }

        val wrapped = """
            (function(){
                var callId = ${jsStringLiteral(callId)};
                (async function(){
                    $body
                })().then(function(r){
                    var json;
                    try { json = JSON.stringify(r !== undefined ? r : null); } catch(e) { json = JSON.stringify(String(r)); }
                    __browserBridge__.resolve(callId, json);
                }).catch(function(e){
                    __browserBridge__.reject(callId, (e && e.message) ? e.message : String(e));
                });
            })();
        """.trimIndent()

        tab.webView.evaluateJavascript(wrapped) { }
        try {
            withTimeout(EVAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            tab.pendingJsCalls.remove(callId)
            throw e
        }
    }

    suspend fun clickElement(selector: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 步骤 1：先在 JS 层面解析元素、平滑滚动到视口中心并获取视口几何坐标
        val prepareScript = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                try {
                    el.scrollIntoView({behavior:'instant',block:'center',inline:'center'});
                } catch(e){}
                var rect = el.getBoundingClientRect();
                var before = location.href;
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    text: (el.textContent || '').trim().substring(0, 100),
                    href: el.href || null,
                    x: rect.left + rect.width / 2,
                    y: rect.top + rect.height / 2,
                    w: rect.width,
                    h: rect.height,
                    beforeUrl: before
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()

        val prepJson = wv.evaluateJavascriptSync(prepareScript) ?: "{}"
        val prep = parseEvalResult(prepJson) as? JsonObject
        val matched = (prep?.get("matched") as? JsonPrimitive)?.asString != "false"
        if (!matched) return@withContext """{"matched":false}"""

        val x = (prep?.get("x") as? JsonPrimitive)?.asString?.toFloatOrNull() ?: 0f
        val y = (prep?.get("y") as? JsonPrimitive)?.asString?.toFloatOrNull() ?: 0f
        val w = (prep?.get("w") as? JsonPrimitive)?.asString?.toFloatOrNull() ?: 0f
        val h = (prep?.get("h") as? JsonPrimitive)?.asString?.toFloatOrNull() ?: 0f
        val beforeUrl = (prep?.get("beforeUrl") as? JsonPrimitive)?.asString.orEmpty()
        val density = appContext.resources.displayMetrics.density

        // 步骤 2：若元素具有可见尺寸，使用 Android 原生 MotionEvent 模拟真实物理触摸（isTrusted: true）
        val nativeClicked = if (w > 0f && h > 0f) {
            val clickX = x * density
            val clickY = y * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, clickX, clickY, 0)
            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, clickX, clickY, 0)
            val downHandled = wv.dispatchTouchEvent(down)
            delay(50)
            val upHandled = wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            downHandled || upHandled
        } else false

        // 步骤 3：若物理点击未生效或元素尺寸为 0，回退到 JS dispatchEvent
        if (!nativeClicked) {
            val fallbackScript = """
                $selectorHelper
                (function(sel){
                    var el = __resolveSelector(sel);
                    if(!el) return;
                    var events = ['mouseover','mousedown','mouseup','click'];
                    for(var i=0;i<events.length;i++){
                        el.dispatchEvent(new MouseEvent(events[i],{
                            view: window, bubbles: true, cancelable: true, buttons: 1
                        }));
                    }
                })(${jsStringLiteral(selector)})
            """.trimIndent()
            wv.evaluateJavascriptSync(fallbackScript)
        }

        // 等待 300ms 检测是否有 SPA 路由切换
        delay(300)
        val afterUrl = wv.url.orEmpty()
        val navigatedTo = if (afterUrl.isNotEmpty() && afterUrl != beforeUrl) afterUrl else null

        val tag = (prep?.get("tag") as? JsonPrimitive)?.asString
        val text = (prep?.get("text") as? JsonPrimitive)?.asString
        val href = (prep?.get("href") as? JsonPrimitive)?.asString

        JsonObject().apply {
            add("matched", JsonPrimitive(true))
            add("tag", tag?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("text", text?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("href", href?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("navigatedTo", navigatedTo?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        }.toString()
    }

    suspend fun fillElement(selector: String, value: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                if(el.tagName === 'SELECT'){
                    var opt = null;
                    for(var i=0;i<el.options.length;i++){
                        var o = el.options[i];
                        if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                    }
                    if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                    el.value = opt.value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return JSON.stringify({matched:true, tag:'select', value:el.value});
                }
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set || (Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set);
                if(setter){ setter.call(el, val); }
                else { el.value = val; }
                if(el._valueTracker){ el._valueTracker.setValue(''); }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    type: el.type || null,
                    value: el.value
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        tab.webView.evaluateJavascriptSync(js) ?: "{}"
    }

    suspend fun selectOption(selector: String, value: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false, reason:'not-found'});
                if(el.tagName !== 'SELECT') return JSON.stringify({matched:false, reason:'not-select'});
                var opt = null;
                for(var i=0;i<el.options.length;i++){
                    var o = el.options[i];
                    if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                }
                if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                el.value = opt.value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: 'select',
                    value: el.value,
                    text: (opt.textContent||'').trim()
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        tab.webView.evaluateJavascriptSync(js) ?: "{}"
    }

    /**
     * 取挂起的对话框；**只在主线程可查**（`pendingDialog` 是 WebView 回调写入的运行时对象）。
     * 工具层从 IO 线程调用时返回 null——对话框提示属于「有则附上」的加分项，缺了不影响主流程，
     * 总好过为它跨线程读竞态对象。
     */
    fun pendingDialogInfo(tabId: String? = null): BrowserDialog? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null
        return findTab(tabId ?: activeTabId)?.pendingDialog
    }

    suspend fun resolveDialog(accept: Boolean, text: String?, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = findTab(tabId ?: activeTabId)
        val result = tab?.pendingDialogResult
        val dialog = tab?.pendingDialog
        if (result == null || dialog == null) {
            return@withContext """{"handled":false,"reason":"no-dialog"}"""
        }
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.dialogTimeout = null
        tab.pendingDialogResult = null
        tab.pendingDialog = null
        try {
            if (accept) {
                if (dialog.type == "prompt" && result is JsPromptResult) {
                    result.confirm(text ?: dialog.defaultValue ?: "")
                } else {
                    result.confirm()
                }
            } else {
                result.cancel()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "resolveDialog failed", e)
        }
        """{"handled":true,"type":"${dialog.type}","accepted":$accept}"""
    }

    suspend fun getConsoleLogs(level: String?, clear: Boolean, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val filtered = if (level.isNullOrBlank()) tab.consoleLogs.toList()
        else tab.consoleLogs.filter { it.level.equals(level, ignoreCase = true) }
        val logs = filtered.joinToString(",") { e ->
            JsonObject().apply {
                add("level", JsonPrimitive(e.level))
                add("message", JsonPrimitive(e.message))
                add("line", JsonPrimitive(e.line))
                add("source", JsonPrimitive(e.source))
            }.toString()
        }
        if (clear) tab.consoleLogs.clear()
        """{"count":${filtered.size},"logs":[$logs]}"""
    }

    suspend fun hoverElement(selector: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                var events = ['mouseenter','mouseover','mousemove'];
                for(var i=0;i<events.length;i++){
                    el.dispatchEvent(new MouseEvent(events[i],{
                        view: window, bubbles: true, cancelable: true
                    }));
                }
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    text: (el.textContent || '').trim().substring(0, 100)
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()
        tab.webView.evaluateJavascriptSync(js) ?: "{}"
    }

    suspend fun pressKey(key: String, tabId: String? = null): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            (function(key){
                var keyMap = {
                    'Enter': {key:'Enter',code:'Enter',keyCode:13},
                    'Escape': {key:'Escape',code:'Escape',keyCode:27},
                    'Tab': {key:'Tab',code:'Tab',keyCode:9},
                    'ArrowUp': {key:'ArrowUp',code:'ArrowUp',keyCode:38},
                    'ArrowDown': {key:'ArrowDown',code:'ArrowDown',keyCode:40},
                    'ArrowLeft': {key:'ArrowLeft',code:'ArrowLeft',keyCode:37},
                    'ArrowRight': {key:'ArrowRight',code:'ArrowRight',keyCode:39},
                    'Backspace': {key:'Backspace',code:'Backspace',keyCode:8},
                    'Delete': {key:'Delete',code:'Delete',keyCode:46},
                    ' ': {key:' ',code:'Space',keyCode:32}
                };
                var k = keyMap[key] || {key:key,code:key,keyCode:key.charCodeAt(0)};
                var target = document.activeElement || document.body;
                ['keydown','keypress','keyup'].forEach(function(type){
                    target.dispatchEvent(new KeyboardEvent(type, {
                        key: k.key, code: k.code, keyCode: k.keyCode,
                        bubbles: true, cancelable: true
                    }));
                });
                return true;
            })(${jsStringLiteral(key)})
        """.trimIndent()
        val result = tab.webView.evaluateJavascriptSync(js)
        result == "true"
    }

    suspend fun getText(selector: String? = null, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 异步渲染沉淀等待：若页面仍处于加载态，等待最多 2000ms
        if (tab.loading) {
            val deadline = System.currentTimeMillis() + 2_000L
            while (tab.loading && System.currentTimeMillis() < deadline) {
                delay(100)
            }
        }

        val js = if (selector != null) {
            """
            $selectorHelper
            (function(){
                var el=__resolveSelector(${jsStringLiteral(selector)});
                if(!el) return '';
                var t=el.innerText;
                if(t&&t.trim())return t;
                var clone=el.cloneNode(true);
                var junk=clone.querySelectorAll('script,style,noscript,template');
                for(var i=0;i<junk.length;i++){junk[i].parentNode.removeChild(junk[i]);}
                return clone.textContent||'';
            })()
            """.trimIndent()
        } else {
            "(function(){return document.body?document.body.innerText:''})()"
        }

        var result = wv.evaluateJavascriptSync(js)
        var text = unescapeJsString(result).trim()

        // 若正文为空且页面可能在异步挂载中，等待最多 1000ms 沉淀
        if (text.isEmpty() && selector == null) {
            val deadline = System.currentTimeMillis() + 1_000L
            while (text.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(150)
                result = wv.evaluateJavascriptSync(js)
                text = unescapeJsString(result).trim()
            }
        }

        text
    }

    suspend fun getHtml(selector: String? = null, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 异步渲染沉淀等待：若页面仍处于加载态，等待最多 2000ms
        if (tab.loading) {
            val deadline = System.currentTimeMillis() + 2_000L
            while (tab.loading && System.currentTimeMillis() < deadline) {
                delay(100)
            }
        }

        val js = if (selector != null) {
            "$selectorHelper\n(function(){var el=__resolveSelector(${jsStringLiteral(selector)});return el?el.outerHTML:''})()"
        } else {
            "(function(){return document.documentElement?document.documentElement.outerHTML:''})()"
        }
        val result = wv.evaluateJavascriptSync(js)
        val raw = unescapeJsString(result)
        if (raw.length > MAX_CONTENT_CHARS) raw.take(MAX_CONTENT_CHARS) + "\n\n[网页内容超长，已截断...]" else raw
    }

    suspend fun getBackbone(maxDepth: Int = 15, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            (function(maxDepth, maxNodes){
                var SKIP = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, LINK:1, META:1, HEAD:1};
                var ROLE_BY_TAG = {
                    A:'link', BUTTON:'button', TEXTAREA:'textbox', SELECT:'combobox', OPTION:'option',
                    IMG:'img', H1:'heading', H2:'heading', H3:'heading', H4:'heading', H5:'heading', H6:'heading',
                    UL:'list', OL:'list', LI:'listitem', DL:'list', DT:'term', DD:'definition',
                    TABLE:'table', TR:'row', TD:'cell', TH:'columnheader',
                    THEAD:'rowgroup', TBODY:'rowgroup', TFOOT:'rowgroup',
                    NAV:'navigation', HEADER:'banner', FOOTER:'contentinfo', ASIDE:'complementary',
                    MAIN:'main', FORM:'form', ARTICLE:'article', SECTION:'generic', DIALOG:'dialog',
                    SUMMARY:'button', DETAILS:'group', FIGURE:'figure', FIGCAPTION:'caption',
                    PROGRESS:'progressbar', P:'paragraph', BLOCKQUOTE:'blockquote',
                    PRE:'code', CODE:'code', HR:'separator', IFRAME:'iframe',
                    VIDEO:'video', AUDIO:'audio', OUTPUT:'status', TIME:'time'
                };
                var INPUT_ROLE = {
                    text:'textbox', search:'searchbox', email:'textbox', tel:'textbox', url:'textbox',
                    password:'textbox', number:'spinbutton', checkbox:'checkbox', radio:'radio',
                    range:'slider', button:'button', submit:'button', reset:'button', file:'button', color:'button'
                };
                var CONTENT_NAME = {
                    link:1, button:1, heading:1, cell:1, columnheader:1, rowheader:1, listitem:1,
                    paragraph:1, menuitem:1, tab:1, option:1, term:1, definition:1, caption:1
                };
                var INTERACTIVE = {
                    link:1, button:1, textbox:1, searchbox:1, checkbox:1, radio:1, combobox:1,
                    slider:1, spinbutton:1, menuitem:1, tab:1, option:1, switch:1
                };
                var refs = {};
                var refCount = 0;
                var count = 0;
                var budgetHit = false;

                function norm(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
                function roleOf(el){
                    var explicit = el.getAttribute('role');
                    if (explicit) { var r = explicit.trim().split(/\s+/)[0]; if (r) return r; }
                    var tag = el.tagName;
                    if (tag === 'INPUT') return INPUT_ROLE[(el.type || 'text').toLowerCase()] || 'textbox';
                    return ROLE_BY_TAG[tag] || 'generic';
                }
                function isVisible(el){
                    if (el.hidden) return false;
                    if (el.getAttribute('aria-hidden') === 'true') return false;
                    if (el.style && (el.style.display === 'none' || el.style.visibility === 'hidden')) return false;
                    if (typeof el.checkVisibility === 'function') {
                        try { return el.checkVisibility({ checkVisibilityCSS: true, checkOpacity: false }); } catch (e) {}
                    }
                    var cs = getComputedStyle(el);
                    return cs.display !== 'none' && cs.visibility !== 'hidden';
                }
                function accessibleName(el, role){
                    var lb = el.getAttribute('aria-labelledby');
                    if (lb) {
                        var parts = [];
                        lb.trim().split(/\s+/).forEach(function(id){
                            var r = document.getElementById(id);
                            if (r) parts.push(r.textContent || '');
                        });
                        var byLabel = norm(parts.join(' '));
                        if (byLabel) return byLabel;
                    }
                    var al = el.getAttribute('aria-label');
                    if (al && norm(al)) return norm(al);
                    var tag = el.tagName;
                    if (tag === 'IMG') { var alt = el.getAttribute('alt'); if (alt && norm(alt)) return norm(alt); }
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                        var ph = el.getAttribute('placeholder');
                        if (ph && norm(ph)) return norm(ph);
                        if (el.id) {
                            var lab = document.querySelector('label[for="' + el.id + '"]');
                            if (lab && norm(lab.textContent)) return norm(lab.textContent);
                        }
                        if (el.value != null && norm(String(el.value))) return norm(String(el.value));
                    }
                    if (CONTENT_NAME[role]) { var t = norm(el.textContent); if (t) return t; }
                    var ti = el.getAttribute('title');
                    if (ti && norm(ti)) return norm(ti);
                    if (el.childElementCount === 0) { var own = norm(el.textContent); if (own) return own; }
                    return '';
                }
                function walk(el, depth){
                    if (!el || !el.tagName) return [];
                    var tag = el.tagName;
                    if (SKIP[tag]) return [];
                    if (!isVisible(el)) return [];
                    var role = roleOf(el);
                    if (depth > maxDepth) {
                        return [{ role: role, truncated: true, childCount: el.children.length }];
                    }
                    if (count >= maxNodes) { budgetHit = true; return []; }
                    var name = accessibleName(el, role);
                    var kids = [];
                    for (var i = 0; i < el.children.length; i++) {
                        var sub = walk(el.children[i], depth + 1);
                        for (var k = 0; k < sub.length; k++) kids.push(sub[k]);
                    }
                    if (role === 'generic' && !name) return kids;
                    count++;
                    var node = { role: role };
                    if (name) node.name = name.substring(0, 200);
                    if (role === 'generic' && el.childElementCount === 0) node.role = 'text';
                    if (role === 'heading') { var lv = parseInt(tag.charAt(1), 10); if (lv) node.level = lv; }
                    if (INTERACTIVE[role]) {
                        refCount++;
                        var ref = 'e' + refCount;
                        refs[ref] = el;
                        node.ref = ref;
                    }
                    if (el.getAttribute) {
                        var href = el.getAttribute('href');
                        if (href && (role === 'link' || role === 'menuitem' || role === 'tab')) node.url = href;
                    }
                    if ((tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')
                        && el.value != null && String(el.value)) node.value = String(el.value).substring(0, 200);
                    if (tag === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) node.checked = !!el.checked;
                    else if (el.getAttribute('aria-checked')) node.checked = el.getAttribute('aria-checked') === 'true';
                    if (el.getAttribute('aria-expanded')) node.expanded = el.getAttribute('aria-expanded') === 'true';
                    if (el.disabled || el.getAttribute('aria-disabled') === 'true') node.disabled = true;
                    if (kids.length) node.children = kids;
                    return [node];
                }
                window.__bicodeRefs = refs;
                var out = { role: 'document' };
                var top = walk(document.body, 0);
                if (top.length) out.children = top;
                if (budgetHit) out.budgetExceeded = true;
                return JSON.stringify(out);
            })($maxDepth, $MAX_BACKBONE_NODES)
        """.trimIndent()
        val result = tab.webView.evaluateJavascriptSync(js)
        unescapeJsString(result)
    }

    suspend fun wait(condition: String, timeoutMs: Long = 10_000, tabId: String? = null): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        when {
            condition == "domStable" -> waitForDomStable(tab, timeoutMs)
            condition.startsWith("text=") -> {
                val text = condition.substring(5)
                waitForCondition(tab, "var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){if(els[i].textContent.trim()===${jsStringLiteral(text)})return true;}return false;", timeoutMs)
            }
            condition.startsWith("text*=") -> {
                val text = condition.substring(6)
                waitForCondition(tab, "return document.body&&document.body.innerText.includes(${jsStringLiteral(text)});", timeoutMs)
            }
            condition.startsWith("selector=") -> {
                val sel = condition.substring(9)
                waitForCondition(tab, "$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(sel)});", timeoutMs)
            }
            else -> {
                waitForCondition(tab, "$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(condition)});", timeoutMs)
            }
        }
    }

    private suspend fun waitForCondition(tab: TabHolder, checkJs: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val wrapped = "(function(){try{$checkJs}catch(e){return false}})()"
        while (System.currentTimeMillis() < deadline) {
            val result = tab.webView.evaluateJavascriptSync(wrapped)
            if (result == "true") return true
            delay(WAIT_POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun waitForDomStable(tab: TabHolder, timeoutMs: Long = 5_000): Boolean {
        val sigJs = "(function(){var b=document.body||document.documentElement;" +
            "return b?b.getElementsByTagName('*').length+':'+b.innerHTML.length:'0:0'})()"
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val sig = tab.webView.evaluateJavascriptSync(sigJs)?.trim('"') ?: ""
            val now = System.currentTimeMillis()
            if (sig.isNotEmpty() && sig == last) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= DOM_STABLE_QUIET_MS) return true
            } else {
                last = sig
                stableSince = 0L
            }
            delay(DOM_STABLE_POLL_MS)
        }
        return false
    }

    suspend fun scroll(selector: String? = null, x: Int? = null, y: Int? = null, tabId: String? = null): String =
        withContext(Dispatchers.Main) {
            val tab = resolveTab(tabId)
            val beforeJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0})})()"
            val beforeResult = tab.webView.evaluateJavascriptSync(beforeJs)
            val before = unescapeJsString(beforeResult)

            val actionJs = when {
                selector != null -> """
                    $selectorHelper
                    (function(){var el=__resolveSelector(${jsStringLiteral(selector)});
                    if(!el) return false;
                    el.scrollIntoView({behavior:'smooth',block:'center'});
                    return true;})()
                """.trimIndent()
                x != null || y != null -> "(function(){window.scrollBy(${x ?: 0}, ${y ?: 0});return true;})()"
                else -> "(function(){window.scrollTo(0,document.body?document.body.scrollHeight:0);return true;})()"
            }
            tab.webView.evaluateJavascriptSync(actionJs)

            delay(300)

            val afterJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0,atTop:(window.scrollY||0)===0,atBottom:(window.innerHeight+(window.scrollY||0))>=(document.body?document.body.scrollHeight:0)})})()"
            val afterResult = tab.webView.evaluateJavascriptSync(afterJs)
            val after = unescapeJsString(afterResult)

            """{"from":$before,"to":$after}"""
        }

    suspend fun screenshot(tabId: String? = null): BrowserImage? = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 离屏兜底尺寸：若未被系统布局过（w/h <= 0），手动测量布局
        if (wv.width <= 0 || wv.height <= 0) {
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, width, height)
        }
        if (wv.width <= 0 || wv.height <= 0) return@withContext null

        // 等待视觉状态就绪（API 23+ 原生机制，保证光栅化合成完毕）
        waitForVisualState(wv)

        val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 关键：WebView 处于硬件层时，software Canvas 回读不到 GPU 帧缓冲——离屏未挂载、或挂载到窗口（打开过浏览器面板）都会白屏；
        // 因此截图期间一律临时切为 LAYER_TYPE_SOFTWARE 遍历 display list 真实绘制到 Bitmap，画完还原。
        val oldLayerType = wv.layerType
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        try {
            wv.draw(canvas)
        } finally {
            wv.setLayerType(oldLayerType, null)
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, baos)
        bitmap.recycle()
        BrowserImage(mimeType = "image/jpeg", base64Data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
    }

    private suspend fun waitForVisualState(wv: WebView, timeoutMs: Long = 500L) {
        val deferred = CompletableDeferred<Unit>()
        wv.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                deferred.complete(Unit)
            }
        })
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            // 超时兜底，直接继续绘制
        }
    }

    suspend fun screenshotIfVisible(tabId: String? = null): BrowserImage? =
        if (isVisible(tabId)) screenshot(tabId) else null

    suspend fun getViewportInfo(tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = "(function(){return JSON.stringify({w:window.innerWidth,h:window.innerHeight,dpr:window.devicePixelRatio||1,scrollY:window.scrollY||0,scrollHeight:document.body?document.body.scrollHeight:0})})()"
        unescapeJsString(tab.webView.evaluateJavascriptSync(js))
    }

    suspend fun goBack(tabId: String? = null): Boolean =
        navigateHistory(tabId, { it.canGoBack() }) { it.goBack() }

    suspend fun goForward(tabId: String? = null): Boolean =
        navigateHistory(tabId, { it.canGoForward() }) { it.goForward() }

    private suspend fun navigateHistory(
        tabId: String?,
        canGo: (WebView) -> Boolean,
        action: (WebView) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView
        if (!canGo(wv)) return@withContext false
        val before = wv.url.orEmpty()
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        action(wv)

        val urlDeadline = System.currentTimeMillis() + GO_URL_TIMEOUT_MS
        var after = before
        while (after == before && System.currentTimeMillis() < urlDeadline) {
            delay(GO_POLL_MS)
            after = wv.url.orEmpty()
        }
        if (after == before) {
            val href = currentHref(tab)
            if (href.isNotEmpty() && href != before) after = href
        }
        if (after == before) {
            if (tab.loadDeferred === deferred) tab.loadDeferred = null
            return@withContext false
        }
        if (tab.loading) {
            try {
                withTimeout(GO_LOAD_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                // 同文档导航不会触发 onPageFinished，超时后直接继续
            }
        }
        if (tab.loadDeferred === deferred) tab.loadDeferred = null
        syncTitle(tab)
        true
    }

    private suspend fun currentHref(tab: TabHolder): String {
        return unescapeJsString(tab.webView.evaluateJavascriptSync("(function(){return location.href})()"))
    }

    private suspend fun syncTitle(tab: TabHolder) {
        val raw = tab.webView.evaluateJavascriptSync("(function(){return document.title})()")
        val title = raw?.trim()?.trim('"') ?: ""
        tab.title = title
        publishState()
    }

    suspend fun reload(tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        tab.webView.reload()
        // 同 loadInto：失败/超时返回空串，不抛异常（错误已写入 tab.error）。
        try {
            withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrDefault("")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            FileLogger.w(TAG, "reload 超时")
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "reload 失败: ${e.message}")
            ""
        }
    }

    /**
     * 取 url。**读 `_state` 快照而非 `tabs`**：本方法会被工具层在 IO 线程调用（见 `BrowserTool.envelope`），
     * 而 `tabs` 由主线程增删，直接遍历是数据竞争。快照是 `MutableStateFlow`，跨线程读安全。
     */
    fun getUrl(tabId: String? = null): String {
        val snap = _state.value
        val id = tabId?.takeIf { it.isNotBlank() } ?: peekActiveTabId()
        return snap.tabs.firstOrNull { it.id == id }?.url ?: snap.url
    }

    /** 取标题；线程安全口径同 [getUrl]。 */
    fun getTitle(tabId: String? = null): String {
        val snap = _state.value
        val id = tabId?.takeIf { it.isNotBlank() } ?: peekActiveTabId()
        return snap.tabs.firstOrNull { it.id == id }?.title ?: snap.title
    }

    /**
     * 取该标签最近一次主框架加载失败的原因；无错返回 null。
     *
     * 线程安全口径同 [getUrl]（读 `_state` 快照，不在 IO 线程建标签）。
     * 供 [com.wy.openx.tools.BrowserTool] 在 `navigate` 后判断真实加载结果——
     * 由于 `loadInto` 失败时**只能返回空串**（抛异常会从 WebView 主线程回调逃逸崩溃），
     * 单看返回的 URL 无法区分「空 URL」与「加载失败」，故需这个旁路信号。
     */
    fun getError(tabId: String? = null): String? {
        val snap = _state.value
        val id = tabId?.takeIf { it.isNotBlank() } ?: peekActiveTabId()
        return snap.tabs.firstOrNull { it.id == id }?.error
    }

    fun parseEvalResult(raw: String?): JsonElement {
        if (raw.isNullOrBlank()) return JsonNull.INSTANCE
        return try {
            val element = JsonParser.parseString(raw)
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                try { JsonParser.parseString(element.asString) } catch (e: Exception) { element }
            } else {
                element
            }
        } catch (e: Exception) {
            JsonPrimitive(raw)
        }
    }

    private suspend fun WebView.evaluateJavascriptSync(script: String): String? {
        val deferred = CompletableDeferred<String?>()
        evaluateJavascript(script) { result -> deferred.complete(result) }
        return deferred.await()
    }

    /**
     * 判断脚本是否为单个表达式（可直接 `return (script)` 取值）。
     * 仅看顶层（括号/花括号深度 0）是否出现 `;` 或换行——IIFE 内部语句的 `;` 不受影响。
     */
    private fun isExpressionScript(script: String): Boolean {
        val s = script.trimEnd().removeSuffix(";").trimEnd()
        if (s.isEmpty()) return false
        val statementStarts = listOf(
            "return", "var", "let", "const", "if", "for", "while", "function",
            "switch", "throw", "try", "do", "class", "import", "export"
        )
        if (statementStarts.any { s == it || s.startsWith("$it ") || s.startsWith("$it(") || s.startsWith("$it{") }) return false
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> {
                    if (c == '\\') i++ else if (c == quote) quote = null
                }
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ';' && depth == 0 -> return false
                c == '\n' && depth == 0 -> return false
            }
            i++
        }
        return true
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    private fun unescapeJsString(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var result = s.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result
            .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")
    }
}
