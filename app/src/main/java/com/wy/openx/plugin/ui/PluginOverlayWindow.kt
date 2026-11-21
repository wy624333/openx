package com.wy.openx.plugin.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.google.gson.Gson
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.str
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.InstalledPlugin
import com.wy.openx.plugin.PluginStore
import com.wy.openx.tools.ToolRegistry
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 悬浮窗的尺寸与焦点选项。
 *
 * 尺寸参数统一用「比例或 dp」两种解释：
 * - `0 < v <= 1` → 当作屏宽/屏高比例（`0.6` = 屏幕的 60%）
 * - `v > 1`      → 当作 dp 固定值（`320` = 320dp）
 *
 * 这样插件写 `{ width: 0.6 }` 能适配不同机型，写 `{ width: 320 }` 则是固定像素观感。
 */
data class OverlayOptions(
    val focusable: Boolean = false,
    /** 宽度。默认屏宽 82%。 */
    val width: Float = 0.82f,
    /** 最大高度。默认屏高 60%。 */
    val maxHeight: Float = 0.60f,
    /** 最小宽度下限（dp），防止拖成一条线。 */
    val minWidthDp: Float = 160f,
    /** 最小高度下限（dp）。 */
    val minHeightDp: Float = 80f,
    /**
     * 固定高度。默认（<= 0）按内容自适应。
     *
     * 桌面宠物这类「窗口大小就是形象大小」的场景需要它：自适应高度会随内容
     * （气泡文字多一行）忽高忽低，宠物会跟着上下跳。
     */
    val height: Float = 0f,
    /**
     * 是否画宿主外壳：标题栏（拖此条移动）、关闭按钮、右下角缩放手柄。
     *
     * 默认 true（普通工具面板需要一个明确的拖动条与关闭入口）。桌面宠物要 false ——
     * 宠物身上顶一条「插件浮窗」标题栏，就完全不是宠物了。
     */
    val chrome: Boolean = true,
    /**
     * 窗口背景是否透明。
     *
     * 默认 false：铺一层不透明面板色，与 App 内的卡片观感一致。
     * true 时不画底色与圆角，只剩插件自己画的内容（宠物形象、贴图都带透明边缘）。
     */
    val transparent: Boolean = false,
    /**
     * 整个窗口是否可拖动（而不只是标题栏）。chrome=false 时默认 true，
     * 否则无外壳的窗口没有拖动入口、用户拖不动。
     */
    val dragAnywhere: Boolean = false,
    /** 是否记忆并恢复上次的位置与大小（按插件维度存）。 */
    val rememberPosition: Boolean = true
)

/**
 * 插件悬浮窗（十期）。
 *
 * 让插件的界面树脱离 App 显示，浮在其它应用之上。渲染直接复用 [PluginUiSession]
 * 与 [RenderUiNode]，所以插件写一份 `ui/panel.ui.js`，面板和悬浮窗是同一种界面。
 *
 * ## 为什么需要 [OverlayHost]
 * 往 `WindowManager` 挂 `ComposeView` 和挂在 Activity 里不一样：没有 Activity 就没有
 * 现成的 Lifecycle / SavedStateRegistry / ViewModelStore。缺任何一个，Compose 内部的
 * `rememberSaveable`、`LaunchedEffect` 会直接抛异常（或整块白屏）。
 * 所以要自己造一个最小的宿主，把三样都补上。
 *
 * ## 焦点
 * 默认 `FLAG_NOT_FOCUSABLE`：不抢输入焦点，否则会把当前应用的软键盘顶掉，
 * 用户在其他 App 里打字时突然弹键盘会非常莫名其妙。
 * 代价是悬浮窗里的输入框收不到键盘 —— 需要输入的插件应传 `focusable: true`
 * （此时会抢焦点，慎用）。
 */
class PluginOverlayWindow(
    private val context: Context,
    private val plugin: InstalledPlugin,
    private val store: PluginStore,
    private val toolRegistry: ToolRegistry?,
    private val onToast: (String) -> Unit
) {

    companion object {
        private const val TAG = "PluginOverlay"

        /** 是否有悬浮窗权限（走系统的「显示在其他应用上层」开关）。 */
        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

        private const val DEFAULT_WIDTH_RATIO = 0.82f
        private const val DEFAULT_MAX_HEIGHT_RATIO = 0.6f

        /** 窗口位置/大小的记忆存储。按插件维度存，放在宿主私有目录。 */
        private const val PREFS = "plugin_overlay_geometry"

        /** 上次退出时浮窗是否处于显示状态。 */
        private const val KEY_VISIBLE = "visible_"

        /** 上次 show 用的选项（JSON），自启恢复时原样回放。 */
        private const val KEY_OPTIONS = "options_"

        /** 自启开关的用户设置（覆盖清单默认值）。 */
        private const val KEY_AUTOSTART = "autostart_"

        /**
         * 插件声明的自启默认值（清单 `overlayAutoStart`）。
         *
         * 注意这是**宿主级**开关（存宿主 prefs），不走插件的 env ——
         * env 是插件自己的配置，而“要不要在 App 启动时把它拉起来”是用户对宿主的偏好。
         */
        fun isAutoStartEnabled(context: Context, plugin: InstalledPlugin): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = KEY_AUTOSTART + plugin.manifest.id
            return if (prefs.contains(key)) {
                prefs.getBoolean(key, false)
            } else {
                plugin.manifest.autoStartOverlay
            }
        }

        fun setAutoStartEnabled(context: Context, plugin: InstalledPlugin, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTOSTART + plugin.manifest.id, on)
                .apply()
        }

        /**
         * 恢复「上次退出时还开着的」插件浮窗（App 启动时调）。
         *
         * 浮窗活在宿主进程里，进程一死就没了。满足以下条件的插件会被拉回来：
         *   1. 自启开关是开的（用户没关过，或清单里声明了 `overlayAutoStart`）
         *   2. 上次退出时它是**显示状态**（用户点过「收回」就不再自动弹）
         *   3. 系统悬浮窗权限仍在（没有就安静跳过，不弹系统设置页 —— 启动时弹很突兵）
         *
         * 尺寸/样式沿用上次 show 的选项。
         */
        fun restoreAutoStart(context: Context, onToast: (String) -> Unit) {
            if (!canDrawOverlays(context)) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store = runCatching { PluginStore(context) }.getOrNull() ?: return
            val plugins = runCatching { store.load() }.getOrDefault(emptyList())
            plugins.forEach { plugin ->
                val id = plugin.manifest.id
                if (!isAutoStartEnabled(context, plugin)) return@forEach
                if (!prefs.getBoolean(KEY_VISIBLE + id, false)) return@forEach
                val json = prefs.getString(KEY_OPTIONS + id, null) ?: return@forEach
                runCatching {
                    val win = obtain(
                        context = context,
                        plugin = plugin,
                        store = store,
                        toolRegistry = null,
                        onToast = onToast
                    )
                    win.show(options(json))
                    FileLogger.i(TAG, "已恢复插件浮窗：$id")
                }.onFailure { FileLogger.e(TAG, "恢复浮窗失败 $id：${it.message}") }
            }
        }

        /**
         * 已创建的浮窗，按插件 id 共享。
         *
         * UI 面板轨道与后台脚本/定时任务轨道是两套桥，如果各建各的窗口，就会出现：
         * 后台任务查 isShowing 看到 false（它自己那个实例没显示过），于是又拉一个 ——
         * 屏幕上出现两只宠物。同一个插件只应该有一个浮窗实例。
         */
        private val windows = mutableMapOf<String, PluginOverlayWindow>()

        /** 取（必要时创建）某插件的浮窗实例。 */
        fun obtain(
            context: Context,
            plugin: InstalledPlugin,
            store: PluginStore,
            toolRegistry: ToolRegistry?,
            onToast: (String) -> Unit
        ): PluginOverlayWindow = synchronized(windows) {
            windows[plugin.manifest.id] ?: PluginOverlayWindow(
                context = context,
                plugin = plugin,
                store = store,
                toolRegistry = toolRegistry,
                onToast = onToast
            ).also { windows[plugin.manifest.id] = it }
        }

        /** 已经存在的实例（没有则 null）。查状态用，不创建窗口。 */
        fun peek(pluginId: String): PluginOverlayWindow? =
            synchronized(windows) { windows[pluginId] }

        /**
         * 解析 `ctx.overlay.show(options)` 的选项。
         *
         * 尺寸字段的语义见 [OverlayOptions]：0 < n <= 1 当屏幕比例，> 1 当 dp。
         * 字段缺失/类型不对一律回退默认值，不报错 —— 悬浮窗尺寸不值得让调用方写对每个字段。
         *
         * 放在这里而不是某个桥里：**UI 轨道与服务轨道要用同一份解析**
         * （两个桥都各自有一份的话，新加字段必然漏一处）。
         */
        fun options(json: String?): OverlayOptions {
            val o = runCatching { JsonParser.parseString(json ?: "{}").asJsonObject }.getOrNull()
                ?: return OverlayOptions()

            fun num(key: String, def: Float): Float {
                val e = o.get(key) ?: return def
                return if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asFloat else def
            }

            fun bool(key: String, def: Boolean): Boolean {
                val e = o.get(key) ?: return def
                return if (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) e.asBoolean else def
            }

            return OverlayOptions(
                focusable = bool("focusable", false),
                width = num("width", 0.82f),
                maxHeight = num("maxHeight", 0.60f),
                minWidthDp = num("minWidth", 160f),
                minHeightDp = num("minHeight", 80f),
                height = num("height", 0f),
                chrome = bool("chrome", true),
                transparent = bool("transparent", false),
                dragAnywhere = bool("dragAnywhere", false),
                rememberPosition = bool("rememberPosition", true)
            )
        }

        /**
         * 内容等比缩放的**下限**：再小文字就没法看了。
         * 到这一步就不再继续缩，剩余空间不足交给内容自己滚动。
         */
        private const val MIN_CONTENT_SCALE = 0.4f
    }
    var isShowing: Boolean = false
        private set

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var composeView: ComposeView? = null
    private var host: OverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * 拖拽缩放用的边界，单位 px。
     */
    private var minWidthPx = 0
    private var minHeightPx = 0
    private var screenWidthPx = 0
    private var screenHeightPx = 0

    /**
     * 标题栏（拖动条）实测高度，px。
     *
     * 窗口高度 = 标题栏 + 内容区，所以缩放时算内容区高度必须减掉它，
     * 否则内容区比窗口高出一个标题栏，底部那一截永远被窗口裁掉。
     */
    private var headerHeightPx = 0

    /**
     * 设计尺寸（px）：首次显示时的窗口宽 / 内容区高。
     * 缩放以它为基准 —— 窗口相对它缩了多少，内容就等比缩多少。
     */
    private var designWidthPx = 0
    private var designHeightPx = 0

    /** 内容等比缩放系数（1f = 设计尺寸，只缩不放）。 */
    private val contentScaleState = mutableStateOf(1f)

    /** 窗口是否抢焦点（穿透 flags 要跟它一起重算）。 */
    private var focusable = false

    /** 触摸穿透：窗口完全不吃触摸，底下的 App 正常操作。 */
    private var passThrough = false

    /**
     * 用户是不是正在拖这个窗口。
     *
     * 插件侧得知道这件事：它在自己做走动动画，每帧都改窗口坐标；
     * 如果不同时让位，就会跟手指抢方向盘（表现为拖不动、发抖）。
     */
    private var draggingNow = false

    /** 说话的气泡（独立窗口，不接收触摸）。 */
    private var bubbleView: android.widget.TextView? = null

    /** 气泡的窗口参数与实测尺寸：宠物移动时要拿它们重算坐标。 */
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleW = 0
    private var bubbleH = 0

    /** 气泡到期时间；用来让旧的定时器不要关掉新气泡。 */
    private var bubbleHideAt = 0L

    /** 会话在首次显示时创建，关闭时释放 —— QuickJS 的 native 资源不会自动回收。 */
    private var session: PluginUiSession? = null
    private var scope: CoroutineScope? = null

    /** 渲染结果（Compose 状态源，会话回调里更新）。 */
    private val treeState = mutableStateOf<UiNode?>(null)
    private val stateMap = mutableStateOf<Map<String, Any?>>(emptyMap())
    private val errorState = mutableStateOf<String?>(null)

    /**
     * 内容区最大高度（px）。
     *
     * 之所以做成可变状态而不是常量：用户拖右下角改窗口大小时，内容区得跟着变，
     * 否则窗口变大了里面还是旧尺寸，下半截空白。
     */
    private val maxHeightPxState = mutableStateOf(0)

    /**
     * 防重复创建。
     *
     * [isShowing] 要等 addView 成功才置 true，而 show() 是异步生效的（骨架先出、
     * 脚本后加载）。在这段窗口期里如果用户连点，每一次都会新建一个
     * PluginUiSession（即一个 QuickJS 引擎）再叠一个窗口 —— 内存会成倍涨。
     * 所以用一把锁把整个 show 串行化，后来的调用直接看到 isShowing 返回。
     */
    @Synchronized
    fun show(options: OverlayOptions = OverlayOptions()): Boolean {
        if (isShowing) return true
        if (!canDrawOverlays(context)) {
            FileLogger.w(TAG, "没有悬浮窗权限，无法显示插件 ${plugin.manifest.id}")
            return false
        }

        val uiRel = (plugin.manifest.ui ?: "").trim()
        if (uiRel.isEmpty()) {
            FileLogger.w(TAG, "插件 ${plugin.manifest.id} 没有界面文件，无法悬浮")
            return false
        }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newSession = PluginUiSession(
            context = context,
            plugin = plugin,
            store = store,
            toolRegistry = toolRegistry,
            onToast = onToast,
            onRerenderRequest = {
                newScope.launch(Dispatchers.Main) { rerenderTick.value++ }
            },
            hostScope = newScope,
            // 告诉插件「你在浮窗里」：同一份 ui 文件在面板里要画按钮，在这里只要立绘
            container = "overlay"
        )
        session = newSession
        scope = newScope

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val pluginId = plugin.manifest.id
        // 记住焦点设置：切触摸穿透时要用它重算整组 flags
        focusable = options.focusable

        // 尺寸参数：0<n<=1 当比例，>1 当 dp。
        // 一律走 safeCoerce：下界来自插件的 minWidth/minHeight，上界是屏幕像素 ——
        // 高密度窄屏下“最小尺寸 > 屏幕宽”交接时就是一个空区间，直接 coerceIn 会抛异常
        fun len(v: Float, base: Int): Int =
            if (v in 0f..1f) (base * v).toInt() else (v * density).toInt()

        val minW = (options.minWidthDp * density).toInt()
        val minH = (options.minHeightDp * density).toInt()

        var width = safeCoerce(len(options.width, screenW), minW, screenW)
        val maxHeight = safeCoerce(
            len(options.maxHeight, screenH), minH, (screenH * 0.95f).toInt()
        )
        var fixedHeight = if (options.height > 0f) {
            safeCoerce(len(options.height, screenH), minH, maxHeight)
        } else 0

        // 上次的位置：用户把宠物拖到屏幕角落后，下次让它还在那里
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var posX = Int.MIN_VALUE
        var posY = Int.MIN_VALUE
        if (options.rememberPosition && prefs.getBoolean("has_" + pluginId, false)) {
            // **只恢复位置，不恢复宽高**。
            //
            // 宽高是插件根据它自己的配置（体型/尺寸）算好传进来的；若拿历史值覆盖，
            // 用户在设置里把体型改小后，立绘缩了、窗口不缩 —— 宠物四周会多出一圈
            // 透明区域（既挡触摸，看着又像“没生效”），同时窗口比屏幕还宽时
            // `screenWidth - width` 算不出有效边界，贴边也跟着失效。
            prefs.getInt("x_" + pluginId, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?.let { posX = it }
            prefs.getInt("y_" + pluginId, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?.let { posY = it }
        }

        maxHeightPxState.value = maxHeight
        designWidthPx = width
        designHeightPx = if (fixedHeight > 0) fixedHeight else maxHeight
        contentScaleState.value = 1f
        minWidthPx = minW
        minHeightPx = minH
        screenWidthPx = screenW
        screenHeightPx = screenH

        val lp = WindowManager.LayoutParams(
            width,
            if (fixedHeight > 0) fixedHeight else WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            windowFlags(options.focusable, passThrough),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 还原的位置也要夹到屏幕内：换分辨率/旋转屏后旧坐标可能已越界。
            // 下界取 0（不是 -width/2）：上一次可能是「贴边收进去」（负坐标），
            // 重启后必须能看到完整宠物，否则用户会以为它丢了。
            x = if (posX != Int.MIN_VALUE) {
                posX.coerceIn(0, (screenW - width).coerceAtLeast(0))
            } else {
                ((screenW - width) / 2).coerceAtLeast(0)
            }
            y = if (posY != Int.MIN_VALUE) {
                posY.coerceIn(0, (screenH - minHeightPx).coerceAtLeast(0))
            } else {
                (screenH * 0.15f).toInt()
            }
        }
        params = lp

        // 记下「这次是显示状态」与用的选项：下次 App 启动时若开着自启开关，
        // 就按这份选项原样恢复（尺寸/透明度/是否无外壳都跟上次一致）。
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_VISIBLE + pluginId, true)
                .putString(KEY_OPTIONS + pluginId, Gson().toJson(options))
                .apply()
        }

        // 三件事必须在主线程：
        //   1. OverlayHost 内部的 LifecycleRegistry/performRestore（实测后台线程会报
        //      "Method addObserver must be called on the main thread"）
        //   2. ComposeView 的构造与 setContent
        //   3. windowManager.addView
        // 插件脚本的加载（newSession.start()）反而要放到**后台**：它是纯 CPU 的
        // QuickJS 初始化，放主线程会卡 UI；而且此刻面板自己的引擎正在后台跑，
        // 在那个线程重入建新引擎会直接死锁。
        var view: ComposeView? = null
        val shown = runOnMain {
            val owner = OverlayHost()
            host = owner
            val cv = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                // 显式透明：窗口是 TRANSLUCENT，但 View 自己若有背景就会把整个窗口矩
                // （包括立绘四周）填成一块底色，看着就像“黑/灰窗”。
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setContent {
                    OverlayContent(
                        tree = treeState.value,
                        error = errorState.value,
                        tick = rerenderTick.value,
                        maxHeightPx = maxHeightPxState.value,
                        contentScale = contentScaleState.value,
                        stateOf = { key -> stateMap.value[key] },
                        permissions = plugin.manifest.grantedPermissions,
                        onAction = { a, p, r -> dispatchAction(a, p, r) },
                        onRefreshNeeded = { refresh() },
                        onClose = { hide() },
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        // 拖动开始/结束告诉窗口：插件自己做走动动画时要给手指让位
                        onDragStateChange = { dragging -> draggingNow = dragging },
                        onResize = { dw, dh -> resizeBy(dw, dh) },
                        onHeaderHeight = { headerHeightPx = it },
                        chrome = options.chrome,
                        // 无外壳的窗口默认就是透的：没人会在去掉了标题栏的窗口上
                        // 再铺一块灰板（插件漏传 transparent 时也不该变成灰卡片）
                        transparent = options.transparent || !options.chrome,
                        dragAnywhere = options.dragAnywhere || !options.chrome,
                        contentFillWindow = !options.chrome && fixedHeight > 0,
                        onGeometryChanged = { saveGeometry() },
                        pluginDir = plugin.dirPath
                    )
                }
            }
            view = cv
            composeView = cv
            val added = runCatching { windowManager.addView(cv, lp) }
            if (added.isFailure) {
                FileLogger.e(TAG, "悬浮窗挂载失败：${added.exceptionOrNull()?.message}")
                false
            } else {
                true
            }
        }

        if (!shown) {
            releaseResources()
            return false
        }
        isShowing = true

        // 骨架先出来，插件脚本随后加载。加载完成前窗内显示"界面为空"。
        newScope.launch {
            val first = runCatching { newSession.start() }.getOrNull()
            val err = if (first == null) newSession.lastError else null
            withContext(Dispatchers.Main) {
                if (first != null) {
                    treeState.value = first.tree
                    stateMap.value = first.state
                    errorState.value = null
                } else {
                    errorState.value = err ?: "界面脚本加载失败"
                }
            }
        }
        return true
    }

    /** 把一段必须在主线程跑的 UI 操作同步执行，并把成功/失败传回调用线程。 */
    private fun runOnMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrDefault(false)
        }
        val latch = CountDownLatch(1)
        var result = false
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block).getOrDefault(false)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    @Synchronized
    fun hide() {
        if (!isShowing) return
        isShowing = false
        hideBubble()
        // 记下“现在是隐藏状态”：自启恢复时看到它就跳过（用户主动收回的，不要又弹出来）
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_VISIBLE + plugin.manifest.id, false)
                .apply()
        }
        // 同样必须在主线程
        runOnMain {
            composeView?.let { v -> runCatching { windowManager.removeView(v) } }
            true
        }
        releaseResources()
    }

    /** 宿主侧发起的界面动作（插件在悬浮窗里点了按钮）。 */
    fun dispatchAction(actionId: String, payload: Map<String, Any?>, rawValue: Boolean) {
        val s = session ?: return
        val sc = scope ?: return
        sc.launch {
            val outcome = runCatching {
                withContext(Dispatchers.Default) { s.dispatchAction(actionId, payload, rawValue) }
            }.getOrNull()
            val err = outcome?.let { s.lastError }
            val tree = outcome?.result?.tree
            val st = outcome?.result?.state
            withContext(Dispatchers.Main) {
                if (tree != null) treeState.value = tree
                if (st != null) stateMap.value = st
                errorState.value = err
            }
        }
    }

    /** JS useState setter 触发的重渲染：重新求值插件树并刷新 Compose 状态。 */
    private fun refresh() {
        val s = session ?: return
        val sc = scope ?: return
        sc.launch {
            val r = runCatching { withContext(Dispatchers.Default) { s.render() } }.getOrNull()
            withContext(Dispatchers.Main) {
                if (r != null) {
                    treeState.value = r.tree
                    stateMap.value = r.state
                    errorState.value = null
                } else {
                    errorState.value = s.lastError
                }
            }
        }
    }

    /**
     * 窗口 flag。
     *
     * 穿透与焦点是同一组 flags 的两个位，必须**一次性重算**：只改一位会把另一位冲掉，
     * 表现为「开过一次穿透之后宠物再也点不动」。
     */
    private fun windowFlags(focusableNow: Boolean, passThroughNow: Boolean): Int {
        var f = if (focusableNow) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (passThroughNow) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // 允许窗口一部分伸出屏幕：贴边「收进去」靠的就是负坐标，
        // 没有这个 flag 系统会把窗口夹回屏内（表现为「开了收进去也没反应」）。
        f = f or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return f
    }

    /**
     * 把窗口挪到屏幕坐标 (x, y)，单位 px。
     *
     * **允许负值**：贴边「收进去」就是让窗口移出屏幕一部分。
     *
     * 越界有下限：最多 80% 移出屏幕（至少留 20% 可见），否则窗口就找不回来了。
     *
     * 刻意不等待主线程（不用 [runOnMain]）：插件做走动动画会 30fps 调它，
     * 每次同步等一趟主线程会把帧率拖死。坐标变更晚一两帧不影响观感。
     */
    fun moveTo(x: Int, y: Int) {
        val lp = params ?: return
        val view = composeView ?: return
        val dm = context.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: lp.width.takeIf { it > 0 } ?: 0
        val h = view.height.takeIf { it > 0 } ?: lp.height.takeIf { it > 0 } ?: 0
        val nx = if (w > 0) x.coerceIn(-(w * 4 / 5), dm.widthPixels - w / 5) else x
        val ny = if (h > 0) y.coerceIn(-(h * 4 / 5), dm.heightPixels - h / 5) else y
        val task = Runnable {
            lp.x = nx
            lp.y = ny
            runCatching { windowManager.updateViewLayout(view, lp) }
            followBubble()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
    }

    /**
     * 当前窗口几何信息（JSON 字符串）。
     *
     * 插件靠它做边界判断：走到屏幕边就掉头、贴边就收进去。宽高取**实测值**
     * （params.width 可能是 WRAP_CONTENT 这种负数常量，插件拿去算会错）。
     */
    fun geometryJson(): String {
        val lp = params
        val m = context.resources.displayMetrics
        val w = composeView?.width?.takeIf { it > 0 } ?: (lp?.width?.takeIf { it > 0 } ?: 0)
        val h = composeView?.height?.takeIf { it > 0 } ?: (lp?.height?.takeIf { it > 0 } ?: 0)
        return buildString {
            append("{\"ok\":true")
            append(",\"x\":").append(lp?.x ?: 0)
            append(",\"y\":").append(lp?.y ?: 0)
            append(",\"width\":").append(w)
            append(",\"height\":").append(h)
            append(",\"screenWidth\":").append(m.widthPixels)
            append(",\"screenHeight\":").append(m.heightPixels)
            append(",\"density\":").append(m.density)
            append(",\"dragging\":").append(draggingNow)
            append(",\"passThrough\":").append(passThrough)
            append(",\"showing\":").append(isShowing)
            append('}')
        }
    }

    /**
     * 切换触摸穿透。
     *
     * 打开后窗口完全不吃触摸（点它会传到下面的 App）。代价是**插件自己也就点不到了**，
     * 所以入口要留在插件面板里（面板是另一个容器，不受影响）。
     */
    fun setPassThrough(on: Boolean) {
        passThrough = on
        val lp = params ?: return
        val view = composeView ?: return
        val task = Runnable {
            lp.flags = windowFlags(focusable, passThrough)
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val view = composeView ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        // 拖动回调本已在主线程，无需再切
        runCatching { windowManager.updateViewLayout(view, lp) }
        followBubble()
    }

    /**
     * 拖右下角改窗口大小。
     *
     * 高度初始是 `WRAP_CONTENT`（一个负数常量），不能直接在它上面加增量。
     * 所以第一次缩放先把当前实际高度固定下来，之后才按像素增减。
     *
     * 同时更新内容区的 maxHeight：窗口变大而内容区还卡在旧上限的话，
     * 下半截会白白空着。注意要减掉标题栏高度 —— 内容区只是窗口的**下半部分**，
     * 不减就会高出一个标题栏，底部（包括最后一行的下半截）被窗口裁掉。
     *
     * 并同步更新等比缩放系数：窗口缩小时，插件界面跟着一起缩（而不是只被裁掉）。
     * 取宽、高两个比例的**较小者**，保证两个方向都装得下。
     */
    /** 插件按配置更新窗口尺寸，保留当前坐标；与手柄拖拽缩放不同，不缩放内容。 */
    fun resizeTo(widthDp: Int, heightDp: Int) {
        val lp = params ?: return
        val view = composeView ?: return
        val density = context.resources.displayMetrics.density
        val newW = (widthDp * density).toInt().coerceIn(1, screenWidthPx)
        val newH = (heightDp * density).toInt().coerceIn(1, (screenHeightPx * 0.95f).toInt())
        val task = Runnable {
            if (!isShowing || params !== lp || composeView !== view) return@Runnable
            if (lp.width == newW && lp.height == newH) return@Runnable
            lp.width = newW
            lp.height = newH
            designWidthPx = newW
            designHeightPx = newH
            minWidthPx = newW
            minHeightPx = newH
            maxHeightPxState.value = newH
            contentScaleState.value = 1f
            runCatching { windowManager.updateViewLayout(view, lp) }
            followBubble()
            saveGeometry()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
    }

    private fun resizeBy(dw: Float, dh: Float) {
        val lp = params ?: return
        val view = composeView ?: return
        if (lp.height == WindowManager.LayoutParams.WRAP_CONTENT) {
            lp.height = view.height.coerceAtLeast(minHeightPx)
        }
        val newW = safeCoerce(lp.width + dw.toInt(), minWidthPx, screenWidthPx)
        val newH = safeCoerce(
            lp.height + dh.toInt(), minHeightPx, (screenHeightPx * 0.92f).toInt()
        )
        if (newW == lp.width && newH == lp.height) return
        lp.width = newW
        lp.height = newH
        val contentH = (newH - headerHeightPx).coerceAtLeast(1)
        maxHeightPxState.value = contentH
        // 只缩不放：窗口比设计尺寸大时保持 1f，多出来的空间给内容重排
        contentScaleState.value = minOf(
            newW.toFloat() / designWidthPx,
            contentH.toFloat() / designHeightPx
        ).coerceIn(MIN_CONTENT_SCALE, 1f)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    /**
     * 记住窗口位置，下次 [show] 时还原。
     *
     * 只存位置（不存宽高）：尺寸由插件每次传入的配置决定，存下来会在用户改了
     * 体型后仍然用旧尺寸（立绘缩了窗口没缩）。
     */
    private fun saveGeometry() {
        val lp = params ?: return
        val id = plugin.manifest.id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("has_" + id, true)
            .putInt("x_" + id, lp.x)
            .putInt("y_" + id, lp.y)
            .apply()
    }

    /**
     * 在宠物头顶说一句话（独立窗口，**不接收触摸**）。
     *
     * 为什么不用主窗口里的 Text 组件：主窗口紧贴着立绘（`size + 8dp`），体型小时
     * 根本没地方摆字；把主窗口改大又会多挡一圈触摸。所以气泡单独开一个
     * `FLAG_NOT_TOUCHABLE` 的窗口：能画得比宠物宽、而且完全不挡操作。
     *
     * 定位：横向与宠物居中，纵向在宠物上方 4dp；贴边/超出屏幕时自动夹回屏内。
     */
    fun showBubble(text: String, durationMs: Long) {
        if (text.isBlank()) {
            hideBubble()
            return
        }
        val task = Runnable {
            val lp = params ?: return@Runnable
            val dm = context.resources.displayMetrics
            val maxW = (dm.widthPixels * 0.86f).toInt()
            val tv = bubbleView ?: android.widget.TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xCC1B2130.toInt())
                }
            }
            tv.text = text

            // 先量一遍才能知道气泡多宽：要按它把窗口居中到宠物上方
            tv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val bw = tv.measuredWidth.coerceAtLeast(dp(24))
            val bh = tv.measuredHeight.coerceAtLeast(dp(20))

            val blp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // 不抢焦点 + 不吃触摸：气泡只是装饰，不能拦住下面的 App
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            // 尺寸记下来：宠物走动时按同一份尺寸重算位置，不能每帧重新 measure
            bubbleW = bw
            bubbleH = bh
            bubbleParams = blp
            positionBubble(blp, lp)

            val added = runCatching {
                if (bubbleView == null) {
                    windowManager.addView(tv, blp)
                    bubbleView = tv
                } else {
                    windowManager.updateViewLayout(tv, blp)
                }
            }
            if (added.isFailure) {
                FileLogger.w(TAG, "气泡窗口失败：${added.exceptionOrNull()?.message}")
                bubbleView = null
                bubbleParams = null
                return@Runnable
            }

            // 连续说话时只让最后一次的定时器生效，否则前一句会把新气泡提前关掉
            bubbleHideAt = System.currentTimeMillis() + durationMs
            Handler(Looper.getMainLooper()).postDelayed({
                if (System.currentTimeMillis() >= bubbleHideAt) hideBubble()
            }, durationMs.coerceAtLeast(500L))
        }
        val run = {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run()
            else Handler(Looper.getMainLooper()).post(task)
        }
        run()
    }

    fun hideBubble() {
        bubbleHideAt = 0
        val tv = bubbleView ?: return
        bubbleView = null
        bubbleParams = null
        val task = Runnable { runCatching { windowManager.removeView(tv) } }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
    }

    /**
     * 把气泡摆到宠物窗口**当前**位置的正上方（横向居中，纵向相距 4dp）。
     *
     * 调用方负责在主线程；只改坐标不碰其它字段。
     */
    private fun positionBubble(blp: WindowManager.LayoutParams, lp: WindowManager.LayoutParams) {
        if (bubbleW <= 0 || bubbleH <= 0) return
        val dm = context.resources.displayMetrics
        val winW = lp.width.takeIf { it > 0 } ?: bubbleW
        blp.x = (lp.x + (winW - bubbleW) / 2)
            .coerceIn(0, (dm.widthPixels - bubbleW).coerceAtLeast(0))
        blp.y = (lp.y - bubbleH - dp(4))
            .coerceIn(dp(2), (dm.heightPixels - bubbleH).coerceAtLeast(0))
    }

    /**
     * 窗口动了：气泡跟着走。
     *
     * 气泡是独立窗口，坐标不会自己跟着主窗口变。不跟的话，宠物边走边说的时候
     * 那句话会留在原地（最远能差出半屏）。
     */
    private fun followBubble() {
        val tv = bubbleView ?: return
        val blp = bubbleParams ?: return
        val lp = params ?: return
        positionBubble(blp, lp)
        runCatching { windowManager.updateViewLayout(tv, blp) }
    }

    /** dp → px 小工具（气泡用）。 */
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun releaseResources() {
        hideBubble()
        composeView = null
        params = null
        runCatching { session?.close() }
        session = null
        runCatching { scope?.cancel() }
        scope = null
        host?.destroy()
        host = null
    }

    /** JS useState setter 触发的重渲染。 */
    private val rerenderTick = mutableStateOf(0)
}

/**
 * 悬浮窗 ContentView 的宿主：补齐 Compose 需要的三个 owner。
 */
private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

/**
 * 悬浮窗内容。
 *
 * 与面板的区别只有外壳（拖动条 + 关闭按钮）和高度限制；中间那块仍然是
 * [RenderUiNode] 渲染的插件节点树，所以插件写一份界面就同时具备面板与悬浮两种形态。
 */
@Composable
private fun OverlayContent(
    tree: UiNode?,
    error: String?,
    tick: Int,
    maxHeightPx: Int,
    contentScale: Float,
    stateOf: (String) -> Any?,
    permissions: Set<com.wy.openx.plugin.PluginPermission>,
    onAction: (String, Map<String, Any?>, Boolean) -> Unit,
    onRefreshNeeded: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onHeaderHeight: (Int) -> Unit,
    /** 是否画宿主外壳（标题栏 + 关闭 + 缩放手柄）。false 时窗口就是插件内容本身。 */
    chrome: Boolean,
    /** 背景是否透明（不铺面板底色与圆角）。 */
    transparent: Boolean,
    /** 整个窗口可拖（而非只有标题栏）。 */
    dragAnywhere: Boolean,
    /** 内容区是否填满窗口（固定尺寸的无外壳窗口用，默认按内容高度定）。 */
    contentFillWindow: Boolean,
    /** 拖动/缩放结束：把几何存起来。 */
    onGeometryChanged: () -> Unit,
    /** 拖动开始/结束（插件自己在做走动动画时，得知道该给手指让位了）。 */
    onDragStateChange: (Boolean) -> Unit,
    /** 插件安装目录（`Image` 的 `asset://` 包内素材靠它解析）。 */
    pluginDir: String?
) {
    // JS 侧 setState 后重新求值一次插件树。
    // tick == 0 是首次组合，树已经在 show() 里取到了，不必再渲染一遍。
    LaunchedEffect(tick) {
        if (tick > 0) onRefreshNeeded()
    }

    val density = LocalDensity.current
    val maxHeightDp = with(density) { maxHeightPx.toDp() }
    // 等比缩放：窗口缩小时整块插件 UI 跟着缩。
    // 走 density 而不是 graphicsLayer —— dp/sp 换算成更少的像素，文字仍按最终字号
    // 光栅化（清晰）、点击热区随布局一起缩；graphicsLayer 是拉伸已画好的位图，会糊。
    val scaledDensity = remember(contentScale, density) {
        Density(density.density * contentScale, density.fontScale * contentScale)
    }
    val renderCtx = remember(tree, permissions, pluginDir, chrome) {
        UiRenderContext(
            onAction = onAction,
            stateOf = stateOf,
            showUnknown = false,
            permissions = permissions,
            pluginDir = pluginDir,
            // 无外壳（桌面宠物形态）里不画图片占位灰块
            hidePlaceholder = !chrome
        )
    }

    Box(
        (if (transparent) Modifier else Modifier.background(Panel, RoundedCornerShape(14.dp)))
            .fillMaxWidth()
            .then(
                // 无外壳时整窗可拖：窗口只有宠物本身那么大，没有标题栏可抓
                if (dragAnywhere) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStateChange(true) },
                            onDragEnd = {
                                onDragStateChange(false)
                                onGeometryChanged()
                            },
                            onDragCancel = {
                                onDragStateChange(false)
                                onGeometryChanged()
                            }
                        ) { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        }
                    }
                } else Modifier
            )
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (!chrome) {
                // 无外壳：连拖动条都不画，整个窗口就是插件内容
            } else Row(
                Modifier
                    .fillMaxWidth()
                    .background(Accent.copy(alpha = 0.14f))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        }
                    }
                    .onSizeChanged { onHeaderHeight(it.height) }
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "插件浮窗（拖此条移动，拖右下角改大小）",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) {
                    Text("关闭", fontSize = 12.sp, color = Accent)
                }
            }

            // 内容超出窗口高度时要能滚。但 `verticalScroll` 给子项的是「无限」高度约束，
            // WebView 会塔成 0 高、LazyColumn 会失去懒加载，所以按树里有没有这类
            // 节点分流：有 → 保持有界，滚动交给组件自己；没有 → 外层套 verticalScroll。
            // 只改 Box 自己的 modifier、不换容器：RenderUiTree 的 composition 位置恒定，
            // 根节点 onLoad 的 remember 不会因分支切换重置。
            Box(
                (
                    if (contentFillWindow) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().height(maxHeightDp)
                    )
                    .then(
                        when {
                            // WebView / LazyColumn 这类要求高度有界，滚动交给组件自己
                            tree?.needsBoundedHeight() == true -> Modifier
                            contentFillWindow -> Modifier
                            else -> Modifier.verticalScroll(rememberScrollState())
                        }
                    )
            ) {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    tree?.let { RenderUiTree(it, renderCtx, Modifier.fillMaxSize()) }
                }
                error?.let { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    )
                }
                if (tree == null && error == null) {
                    Text(
                        text = "界面为空",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    )
                }
            }
        }

        // 右下角的拖拽把手。
        // 半径做大一点（32dp 命中区）但视觉上只画一小块，否则会挡住内容里的控件。
        if (chrome) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onGeometryChanged() },
                            onDragCancel = { onGeometryChanged() }
                        ) { change, drag ->
                            change.consume()
                            onResize(drag.x, drag.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(TextSecondary.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
