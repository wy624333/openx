package com.wy.openx.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.wy.openx.data.log.AppLogStore
import com.wy.openx.ui.theme.currentThemeColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮窗里**一个会话**的展示数据。
 *
 * 多会话并行时面板用一行标签列出所有活动会话，点标签切换下面的正文，
 * 因此每个会话都要带齐「标题 / 状态 / 正文 / 计时起点」。
 *
 * @property conversationId 会话 id（标签切换用的身份，也是默认排序键）。
 * @property title 会话标题（标签上显示；过长由标签自己省略）。
 * @property state 该会话当前处于哪个阶段，决定标签上的小标记与颜色。
 * @property statusText 正文上方的状态文案（「AI 正在思考」/「正在使用 xxx」等）。
 * @property body 要展示的正文（工具轨迹 + 流式正文），已由 VM 拼好。
 * @property startedAt 本轮开始的绝对时间戳（epoch millis），用于标签与状态行的「N秒」计时；
 *   0 表示不计时（例如「等你处理」阶段没有持续时长可算）。
 */
data class OverlaySession(
    val conversationId: Long,
    val title: String,
    val state: OverlaySessionState,
    val statusText: String,
    val body: String,
    val startedAt: Long
)

/**
 * 单个会话在悬浮窗里的阶段。
 *
 * **声明顺序即标签排序优先级**（VM 用 `ordinal` 排序），与 `SessionSlot.badge()` 一致：
 * 等待你 > 运行中 > 有新结果。「等待你」排最前是因为它需要用户动作，
 * 压着不显示会让人误以为还在跑、白等；「有新结果」最后（已经结束了，不急）。
 *
 * 注意「等待你」与「运行中」可同时成立（AI 停下等你确认的那一瞬），
 * 此时取「等待你」——与侧边栏 🟡 的口径保持一致。
 */
enum class OverlaySessionState {
    /** 在等你处理（工具确认 / 终端确认 / 提问），对应侧边栏 🟡。 */
    WAITING,

    /** 正在生成。 */
    RUNNING,

    /** 后台跑完、用户还没切回来看（对应侧边栏 🔵）。 */
    DONE
}

/**
 * AI 运行进度悬浮窗控制器（偏好设置「悬浮窗」开关）。
 *
 * ## 职责
 * 当 **AI 正在生成** 且 **openx 处于后台** 时，用系统悬浮窗（`TYPE_APPLICATION_OVERLAY`）
 * 显示实时回复流与工具执行状态，方便用户切到别的应用时瞄一眼进度。回到 openx 或生成结束即隐去。
 *
 * ## 为什么用原生 View 而不是 Compose
 * 往 `WindowManager` 挂 ComposeView 必须手动补齐 `ViewTreeLifecycleOwner` /
 * `SavedStateRegistryOwner` / `ViewModelStoreOwner` 三个 Owner，缺一个就崩；而这个面板是
 * 「纯展示 + 定时刷新」，用原生 `TextView` + `ScrollView` 更简单也更稳。样式从
 * [currentThemeColors] 取色，与 app 主题保持一致。
 *
 * ## 生命周期
 * 进程级单例（与 `BrowserManager` / `TerminalEngine` 同一套路，见 `getOrCreate`）。
 * 不持有 Activity 引用——只用 `applicationContext`，避免泄漏。
 * **进程被杀悬浮窗就没了**，这是平台特性；要活久需配合前台服务（见 `KeepAliveService`）。
 *
 * ## 前后台判定
 * openx 是单 Activity 应用（Manifest 里只有 `MainActivity`），因此 `MainActivity.onStart/onStop`
 * 即等价于进程前后台，无需引入 `lifecycle-process` 依赖。
 */
class FloatingOverlayController private constructor(
    private val appContext: Context
) {

    companion object {
        private const val TAG = "FloatingOverlay"

        /** 面板占屏宽比例（留出边缘，避免贴边难以拖动）。 */
        private const val WIDTH_RATIO = 0.72f

        /** 面板最大高度占屏高比例，超出则内部滚动。 */
        private const val MAX_HEIGHT_RATIO = 0.42f

        /** 面板宽高下限（像素的 dp 基数），避免拖成一个抓不住的小点。 */
        private const val MIN_WIDTH_DP = 150f
        private const val MIN_HEIGHT_DP = 90f

        /** 缩放手柄的边长（dp）与触碰热区外扩量（dp）——热区比视觉大，才好按。 */
        private const val RESIZE_HANDLE_DP = 20f
        private const val RESIZE_TOUCH_SLOP_DP = 8f

        /**
         * 每个会话正文最多保留字符数：悬浮窗只是「瞄一眼」，不是完整记录，避免超长文本拖慢渲染。
         * 公开给 `MainViewModel`——正文由 VM 拼装，截断放到拼装处才不必先造出巨大字符串再裁。
         */
        const val MAX_BODY_CHARS = 4000

        @Volatile
        private var sharedInstance: FloatingOverlayController? = null

        fun getOrCreate(context: Context): FloatingOverlayController {
            val existing = sharedInstance
            if (existing != null) return existing
            return synchronized(this) {
                sharedInstance ?: FloatingOverlayController(context.applicationContext)
                    .also { sharedInstance = it }
            }
        }

        fun getShared(): FloatingOverlayController? = sharedInstance

        /** 悬浮窗权限是否已授予（未授予时 addView 会抛异常）。 */
        fun canDrawOverlays(context: Context): Boolean =
            runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // ===== 输入条件 =====
    /** 偏好设置「悬浮窗」开关。 */
    private var enabled = false

    /** openx 是否在前台（前台时不显示，否则会挡住聊天界面）。 */
    private var appInForeground = true

    // ===== 多会话展示 =====
    /**
     * 当前需要展示的会话列表（由 `MainViewModel` 汇总**所有槽位**后推送）。
     *
     * 只含「运行中 / 后台刚跑完未读 / 正在等你处理」的会话，因此**非空即代表面板该显示**——
     * 不再像单会话版本那样另设一个 `running` 布尔，避免「一组会话里只反映前台那个」的老问题。
     */
    private var sessions: List<OverlaySession> = emptyList()

    /** app 当前前台会话 id：用户没手选时默认跟随它。 */
    private var foregroundConversationId: Long? = null

    /** 用户手动点选查看的会话 id；null = 跟随前台。会话从列表消失时自动落回 null。 */
    private var selectedId: Long? = null

    /**
     * 用户手动关掉悬浮窗后，本次显示周期不再弹出。
     * 「从没有活动会话 → 又有活动会话」时重置——否则关一次之后永远不显示了。
     */
    private var dismissedForRun = false

    /** 上一帧是否存在活动会话，用于捕捉「新一轮开始」的瞬间（见 [setSessions]）。 */
    private var hadSessions = false

    /**
     * 已释放（Activity `finish()`）：禁止再添加窗口。
     *
     * 必要性：退出时 `release()` 移除了窗口，但 `MainViewModel` 的 `viewModelScope` 要等到
     * `onCleared()` 才取消，其间若 `snapshotFlow` 再发一次（AI 仍在跑），`sync()` 会把窗口重新加上，
     * 而此后已无人再调用 `release()` —— 结果是一个**永远留在屏幕上、直到进程被杀才消失**的孤儿窗口。
     *
     * 下一次 `onStart` 会把它清掉（见 [setAppInForeground]），保证重新启动 app 后功能恢复正常。
     */
    private var released = false

    /** 计时刷新（1s 一跳），仅在窗口可见时运行，顺序刷新各标签与状态行。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (rootView == null) return
            renderTabs()
            renderHeader()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private var rootView: View? = null
    /** 标签行容器（多会话时容纳每个会话一个标签）。 */
    private var tabsRow: LinearLayout? = null
    /** 标签行外的横向滚动容器：会话多到一行放不下时横向滚动，而不是把标签压扁。 */
    private var tabsScroll: HorizontalScrollView? = null
    /**
     * 已建好的标签（key = 会话 id），按当前顺序复用。
     *
     * [renderContent] 在流式期间每 150ms 被调一次，若每次都重建 `TextView` 会不停分配、
     * 还会把横向滚动位置重置回起点（用户正在翻标签时尤其恼人）。
     * 故只在「会话集合或顺序变了」时重建，否则原地改文案与高亮。
     */
    private val tabViews = LinkedHashMap<Long, TextView>()

    /**
     * 标签底色的「样板」（选中 / 未选中）。`renderContent` 每 150ms 跑一次，
     * 每次给每个标签新建 [GradientDrawable] 是白白制造垃圾；这两个只依赖主题色，建一次即可。
     *
     * 注意**不能把同一个 Drawable 直接赋给多个 View**：Drawable 只有一个 callback，
     * 多个 View 共用会导致刷新行为异常。这里用 `constantState.newDrawable()` 克隆——
     * 克隆很便宜，且各自持有独立回调。
     */
    private var activeTabBgProto: GradientDrawable? = null
    private var inactiveTabBgProto: GradientDrawable? = null
    private var statusView: TextView? = null
    private var bodyView: TextView? = null
    private var scrollView: MaxHeightScrollView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * 用户手动拖角设定过的窗口尺寸（像素）；null = 尚未手动调过，按 [WIDTH_RATIO] /
     * `WRAP_CONTENT` 的默认行为显示（内容短时不留空白）。
     *
     * 一旦用户拖过就固定下来，同一次进程内隐藏/再弹出都沿用；进程重启才回到默认。
     * 存内存而非 SharedPreferences：这是「本次会话的临时排版偏好」，
     * 且控制器只持有 applicationContext，落盘要多引一个 store。用户若要跨重启保留再加。
     */
    private var customWidth: Int? = null
    private var customHeight: Int? = null

    /** 当前展示的会话：优先用户手选的，否则跟随前台，最后回落列表首个。 */
    private fun activeSession(): OverlaySession? {
        if (sessions.isEmpty()) return null
        val want = selectedId ?: foregroundConversationId
        return sessions.firstOrNull { it.conversationId == want } ?: sessions.first()
    }

    /** 用户是否停留在底部：只有贴在底部时才自动跟随滚动，用户上翻查看历史时不打扰。 */
    private var stickToBottom = true

    // ===================== 对外接口 =====================

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) dismissedForRun = false
        sync()
    }

    /**
     * 推送当前全部活动会话（由 `MainViewModel` 汇总所有槽位后调用）。
     *
     * **一次调用带齐整个列表**，而不是像旧版那样逐个 `update(status, body)`——
     * 后者只能表达「一个会话」的状态，多会话并行时后推的会覆盖先推的，
     * 表现就是「悬浮窗只显示当前前台会话」。
     *
     * @param sessions 活动会话列表，空 = 无活动会话（面板该隐藏）。
     *   计时起点由各会话自带（[OverlaySession.startedAt]），故这里不再重置全局计时器。
     * @param foregroundId app 当前正在看的会话 id，用户没手选时默认展示它。
     */
    fun setSessions(sessions: List<OverlaySession>, foregroundId: Long?) {
        val wasEmpty = this.sessions.isEmpty()
        this.sessions = sessions
        this.foregroundConversationId = foregroundId
        // 手选过但已从列表消失（跑完又被读掉）：落回「跟随前台」，
        // 否则标签会停在一个不存在的高亮项上。
        if (selectedId != null && sessions.none { it.conversationId == selectedId }) selectedId = null
        // 新一轮开始（从「无活动会话」变为「有」）：清掉上一轮的「手动关闭」。
        // 判据从「running 由 false 变 true」改成「列表由空变非空」，语义一致但适配多会话。
        if (wasEmpty && sessions.isNotEmpty()) dismissedForRun = false
        if (!wasEmpty && sessions.isEmpty()) selectedId = null
        // 面板可见性/内容可能变化；列表为空时 sync() 内部会走 removeOverlay()。
        sync()
    }

    fun setAppInForeground(value: Boolean) {
        // 回到前台即视为「本次会话重新开始」：清掉上次退出时的 released 标记，
        // 否则用户重开 app 后悬浮窗再也不显示（标记是进程级、不会自己复位）。
        if (value) released = false
        if (appInForeground == value) return
        appInForeground = value
        sync()
    }

    /** 真正退出 app（Activity `finish()`）时调用：移除窗口并禁止再添加。 */
    fun release() {
        released = true
        mainHandler.post { removeOverlay() }
    }

    // ===================== 显示判定 =====================

    /** 有活动会话且开关开着、app 在后台、本次未被手动关闭、未释放 → 显示。 */
    private fun shouldShow(): Boolean =
        enabled && sessions.isNotEmpty() && !appInForeground && !dismissedForRun && !released

    private fun sync() {
        mainHandler.post {
            if (shouldShow()) {
                if (rootView == null) addOverlay() else renderContent()
            } else {
                removeOverlay()
            }
        }
    }

    // ===================== 窗口增删 =====================

    private fun addOverlay() {
        if (!canDrawOverlays(appContext)) {
            // 开关开着但没授权：不弹窗骚扰，只记一条日志（权限页有引导）
            AppLogStore.warn(TAG, "悬浮窗未获授权，跳过显示")
            return
        }
        val ctx = appContext
        val density = ctx.resources.displayMetrics.density
        val colors = currentThemeColors
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        // 用户拖过角就用他的尺寸，否则用默认宽度（高度仍是 WRAP_CONTENT）。
        val width = customWidth ?: (screenW * WIDTH_RATIO).toInt()
        // 注意别把这个变量命名为 `height`：它会遮蔽 View.height，
        // 使下方 apply 块里的 `height - paddingTop` 解析到这个 Int? 上而编译失败。
        val fixedHeightPx = customHeight
        val maxHeight = (screenH * MAX_HEIGHT_RATIO).toInt()
        // 固定高度模式下正文要填满剩余空间，故不再限高（见 MaxHeightScrollView.capEnabled）。
        val fixedHeight = fixedHeightPx != null

        // 根用 FrameLayout：内容列与右下角缩放手柄要**叠放**在同一层，
        // 手柄浮在正文之上。若用 LinearLayout 竖直排列，手柄会另起一行、挤在正文下面。
        val panel = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(colors.surface.toArgb())
                setStroke((1f * density).toInt(), colors.outline.toArgb())
            }
            elevation = 8f * density
        }
        // 内容列：头 / 标签 / 正文，竖直排列并填满根（手柄盖在它上面）。
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // ----- 头部：状态点 + 状态文字 + 关闭按钮（拖动把手也在这）-----
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt())
                .apply { marginEnd = (8 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors.primary.toArgb())
            }
        }
        val status = TextView(ctx).apply {
            setTextColor(colors.primary.toArgb())
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(colors.onSurfaceVariant.toArgb())
            textSize = 15f
            setPadding((8 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                dismissedForRun = true
                sync()
            }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
            addView(dot)
            addView(status)
            addView(close)
        }

        // ----- 标签行：每个活动会话一个可点标签（仅多会话时占用高度）-----
        // 放在头部之下、正文之上。单会话时整行 gone，面板外观与旧版完全一致。
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabsScroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            // 标签行本身不接管拖动：用户在这里多半是想点标签，避免和拖动把手抢手势。
            addView(
                tabs,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val tabsScrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // ----- 正文：可滚动 -----
        val body = TextView(ctx).apply {
            setTextColor(colors.onBackground.toArgb())
            textSize = 12.5f
            setLineSpacing(2f * density, 1f)
        }
        // 用「限高的 ScrollView」而不是 weight(1f)：
        // 面板高度是 WRAP_CONTENT（内容短时不该留大片空白），而 WRAP_CONTENT 的 LinearLayout
        // 里 weight 子项拿不到「剩余空间」（剩余空间为 0），高度会算成 0，正文直接不可见。
        val scroll = MaxHeightScrollView(ctx, maxHeight).apply {
            capEnabled = !fixedHeight
            // 底部留出缩放手柄的高度：手柄浮在右下角，正文若贴到底会被它压住最后一行的右侧。
            setPadding(
                (12 * density).toInt(), 0,
                (12 * density).toInt(),
                ((RESIZE_HANDLE_DP + 6f) * density).toInt()
            )
            isVerticalScrollBarEnabled = false
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // 只在这里注册一次：renderContent() 每 150ms 会被调用，放那里会反复新建 lambda
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                val viewport = height - paddingTop - paddingBottom
                stickToBottom = child.height - viewport - scrollY <= 8
            }
        }
        // 点正文区回到 openx。
        // 注意：**不能用 setOnClickListener**——点击（performClick）在 View.onTouchEvent 里触发，
        // 而 ScrollView 重写了 onTouchEvent 且不调用 super，所以挂在 ScrollView 上的
        // OnClickListener 永远不会被触发。这里改用 OnTouchListener 自己判「短按未移动」，
        // 返回 false 把事件继续交给 ScrollView，滚动不受影响。
        attachTapToOpen(scroll)

        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(tabsScroller, tabsScrollParams)
        // 固定高度时正文用 weight(1f) 占满剩余空间；WRAP_CONTENT 时 weight 拿不到空间
        // （剩余空间为 0，高度会算成 0），必须用 WRAP_CONTENT + 限高 ScrollView。
        column.addView(
            scroll,
            if (fixedHeight)
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            else
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        panel.addView(
            column,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // ----- 右下角缩放手柄 -----
        // 视觉上是一组斜线，靠自定义 Drawable 画（不用字体图标：不同 ROM 字形差异大）。
        val handleSize = (RESIZE_HANDLE_DP * density).toInt()
        val grip = View(ctx).apply {
            background = GripsDrawable(2f * density, 3, colors.onSurfaceVariant.toArgb())
            // 热区比视觉大：手柄本身只有 20dp，手指按准不容易，
            // 外扩靠负 margin 做不到，改为把 View 做大、斜线只画右下角（见 GripsDrawable）。
            layoutParams = FrameLayout.LayoutParams(
                handleSize + (RESIZE_TOUCH_SLOP_DP * density).toInt(),
                handleSize + (RESIZE_TOUCH_SLOP_DP * density).toInt()
            ).apply { gravity = Gravity.BOTTOM or Gravity.END }
        }
        panel.addView(grip)

        statusView = status
        bodyView = body
        scrollView = scroll
        tabsRow = tabs
        tabsScroll = tabsScroller

        val params = WindowManager.LayoutParams(
            width,
            fixedHeightPx ?: WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE：不抢输入焦点（否则会顶掉当前应用的软键盘）。
            // 仍可接收触摸事件，因此滚动/拖动/点关闭都正常。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认停在右上角，避开多数应用的主操作区
            x = (screenW - width) - (12 * density).toInt()
            y = (screenH * 0.12f).toInt()
        }
        layoutParams = params

        attachDragHandler(header, panel, params, screenW, screenH)
        attachResizeHandler(grip, panel, params, scroll, screenW, screenH)

        rootView = panel
        val added = runCatching { windowManager.addView(panel, params) }.isSuccess
        if (!added) {
            AppLogStore.warn(TAG, "悬浮窗添加失败")
            rootView = null
            statusView = null
            bodyView = null
            scrollView = null
            tabsRow = null
            tabsScroll = null
            layoutParams = null
            return
        }
        renderContent()
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, 1000L)
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        mainHandler.removeCallbacks(ticker)
        runCatching { windowManager.removeView(view) }
        rootView = null
        statusView = null
        bodyView = null
        scrollView = null
        tabsRow = null
        tabsScroll = null
        // 标签视图随窗口一起作废：留着会在下次 addOverlay 时被 getOrPut 复用，
        // 而那些视图已经脱离了原父容器，复用容易出怪状态。
        tabViews.clear()
        layoutParams = null
    }

    /**
     * 限高的 [ScrollView]：内容不足时按内容高度（不留空白），超出则封顶到 [maxHeightPx] 并内部滚动。
     *
     * 面板本身高度是 `WRAP_CONTENT`，此时 LinearLayout 里 `weight` 子项算出来的高度是 0
     * （剩余空间为 0），正文会直接不可见——所以这里用 `onMeasure` 限高代替 `weight`。
     */
    private class MaxHeightScrollView(context: Context, internal var maxHeightPx: Int) : ScrollView(context) {
        /**
         * 用户拖角把面板拉成固定高度后，正文应**填满剩余空间**（`weight(1f)`）而不是被限高。
         * 限高只服务于 `WRAP_CONTENT` 的默认形态（内容短时不留空白）。
         * 故固定尺寸模式下把它设为 [Int.MAX_VALUE]，等同于不限高。
         */
        internal var capEnabled = true

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = if (capEnabled)
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    /**
     * 右下角缩放把手的图形：几条从左上到右下的斜线。
     *
     * 用自定义 [Drawable] 而不是字体图标（`⤡` 之类）：悬浮窗用原生 View，
     * 字体图标在不同 ROM 上的字形/基线差异大，画出来容易缺字或错位。
     *
     * 线只画在**右下角**：View 本身比视觉尺寸大（多出的部分作触碰热区，
     * 见 [RESIZE_TOUCH_SLOP_DP]），若铺满整个 View 会显得离边太远。
     */
    private class GripsDrawable(
        private val strokeWidth: Float,
        private val lineCount: Int,
        private val color: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.strokeWidth = strokeWidth
            this.color = color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            // 手柄图形只占 View 的右下角：View 多出来的部分是触碰热区，
            // 线若铺满整个 View 会显得离窗口边角太远。
            val visual = minOf(b.width(), b.height()) * 0.62f
            val step = visual / (lineCount + 1).toFloat()
            val right = b.right - strokeWidth
            val bottom = b.bottom - strokeWidth
            for (i in 1..lineCount) {
                // 每条线垂直于对角线：从底边往上、再折到右边，
                // 越靠里的线越短，形成经典的「阶梯」缩放图标。
                val len = i * step
                canvas.drawLine(right - len, bottom, right, bottom - len, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ===================== 点击回应用 =====================

    /**
     * 给 [target]（正文区 ScrollView）加「短按回应用」。
     *
     * 为什么不用 `setOnClickListener`：`performClick()` 是在 `View.onTouchEvent()` 里触发的，
     * 而 `ScrollView` 重写了 `onTouchEvent()` 且不调 `super`，所以挂在 ScrollView 上的
     * `OnClickListener` 永远不会被触发。
     *
     * 这里用 `OnTouchListener` 自己判定：`ACTION_DOWN` 记起点，`ACTION_UP` 时若位移
     * 在阈值内视为点击、回应用。**始终返回 false**，把事件继续交给 ScrollView 处理滚动。
     * （`setOnTouchListener` 在 `View.dispatchTouchEvent` 里、`onTouchEvent` 之前被调用，
     * 返回 false 不影响后续分发。）
     */
    private fun attachTapToOpen(target: View) {
        val slop = 10f * appContext.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) <= slop && abs(event.rawY - downY) <= slop) {
                        openApp()
                    }
                }
            }
            false
        }
    }

    private fun openApp() {
        // 从后台切回 openx。CLEAR_TOP | SINGLE_TOP 避免重复堆叠 MainActivity；
        // NEW_TASK 是必须的——从悬浮窗（非 Activity 上下文）启动 Activity 必须有它。
        val intent = Intent(appContext, com.wy.openx.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { appContext.startActivity(intent) }
            .onFailure { AppLogStore.warn(TAG, "点击回应用失败：${it.message}") }
    }

    // ===================== 拖动 =====================
    /**
     * 头部拖动：改窗口位置。
     *
     * 夹取范围按**窗口当前实际高度**算（不是默认的 [MAX_HEIGHT_RATIO] 上限）——
     * 用户拖角缩放后窗口可能比默认更高或更矮，仍用旧上限会导致拖不到底或提前卡住。
     * 高度取 `panel.height`（已测量值：WRAP_CONTENT 时是内容高，固定尺寸时是用户设的值）。
     */
    private fun attachDragHandler(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams,
        screenW: Int,
        screenH: Int
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 4f || abs(dy) > 4f) dragged = true
                    // 夹在屏幕范围内，避免拖出可视区后再也抓不到。
                    // 高度优先用测量值；未测量时（理论上发生不了，ACTION_DOWN 时已布局）
                    // 回落到 params.height 或 0，宁可暂时拖不动也不要算出负上限。
                    val winH = when {
                        panel.height > 0 -> panel.height
                        params.height > 0 -> params.height
                        else -> 0
                    }
                    params.x = min(max(0, startX + dx.toInt()), max(0, screenW - params.width))
                    params.y = min(max(0, startY + dy.toInt()), max(0, screenH - winH))
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragged
                else -> false
            }
        }
    }

    // ===================== 拖角缩放 =====================

    /**
     * 右下角手柄：拖动改窗口宽高。
     *
     * 两个必须做的转换：
     * 1. **先切成固定尺寸**。默认形态高度是 `WRAP_CONTENT`，它的 `params.height` 是 -2、
     *    `view.height` 是内容实测高。要缩放就得在 `ACTION_DOWN` 那一刻把当前实测宽高
     *    固化成像素值写回 `params`，否则加减一个 `WRAP_CONTENT` 的"高度"没有意义。
     *    宽度同理（默认是 `WIDTH_RATIO` 算出的像素，已是固定值，但仍统一固化一遍）。
     * 2. **正文布局随之切换**。固定高度后正文改用 `weight(1f)` 填满，且解掉
     *    `MaxHeightScrollView` 的限高（[MaxHeightScrollView.capEnabled] = false），
     *    否则用户拉高面板时正文不会跟着变高。
     *
     * 只挂一次（在 [addOverlay] 里），`WRAP_CONTENT → 固定` 的切换只在此函数首次触发时做。
     */
    private fun attachResizeHandler(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams,
        scroll: MaxHeightScrollView,
        screenW: Int,
        screenH: Int
    ) {
        val density = appContext.resources.displayMetrics.density
        val minW = (MIN_WIDTH_DP * density).toInt()
        val minH = (MIN_HEIGHT_DP * density).toInt()
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        /** 归一化为固定尺寸只需做一次：之后 params.height 已是具体像素。 */
        var normalized = false
        /** 本次手势是否真的拖动过（用于区分「想缩放」和「误点一下」）。 */
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 只记起点，**不在这里**切换布局。
                    // 若在 DOWN 就切成固定尺寸，一次「只点不拖」的误触会让
                    // WRAP_CONTENT 的父容器里出现 height=0 + weight=1 的正文，
                    // 面板瞬间塌成只剩头部。所以归一化推迟到确认拖动之后。
                    startW = if (params.width > 0) params.width else panel.width
                    startH = if (params.height > 0) params.height else panel.height
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && abs(dx) < 4 && abs(dy) < 4) return@setOnTouchListener true
                    moved = true
                    if (!normalized) {
                        // 首次确认拖动：把当前实测宽高固化成像素值，并把正文切成填满模式。
                        // 必须与下面的 updateViewLayout 在同一帧完成，否则中间态会闪一下。
                        params.width = startW
                        params.height = startH
                        scroll.capEnabled = false
                        (scroll.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.height = 0
                            lp.weight = 1f
                            scroll.layoutParams = lp
                        }
                        normalized = true
                    }
                    // 上限 = 从面板左上角到屏幕右/下边，保证拖大时不超出屏幕。
                    val maxW = (screenW - params.x).coerceAtLeast(minW)
                    val maxH = (screenH - params.y).coerceAtLeast(minH)
                    params.width = (startW + dx).coerceIn(minW, maxW)
                    params.height = (startH + dy).coerceIn(minH, maxH)
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 没真的拖过（误点）：什么都不改，保持原有 WRAP_CONTENT 形态。
                    if (!moved) return@setOnTouchListener true
                    // 记住用户设定的尺寸：本次进程内隐藏再弹出都沿用，不必每次重拖。
                    customWidth = params.width
                    customHeight = params.height
                    // 记进字段后，后续 addOverlay 会按固定尺寸重建（并给正文用 weight），
                    // 与此刻屏幕上这版一致；无需立即重建窗口。
                    true
                }
                else -> false
            }
        }
    }

    // ===================== 内容渲染 =====================
    private fun renderContent() {
        renderTabs()
        renderHeader()
        bodyView?.text = activeSession()?.body ?: ""
        // 贴在底部时才自动跟随（用户上翻查看历史时不打断）
        if (stickToBottom) {
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 标签行：每个活动会话一个标签，显示「标题 + 该会话自己的计时」。
     *
     * 单会话时整行 `GONE`——面板外观与旧版完全一致，不给单会话用户增加视觉噪音。
     * 之所以把计时放在标签上：并行跑多个会话时，用户最需要的信息是
     * 「哪个还在跑、跑了多久」，而不是只有当前这个的秒数。
     */
    private fun renderTabs() {
        val row = tabsRow ?: return
        val container = tabsScroll
        val list = sessions
        // 单会话不显示标签行（面板保持旧观感）。
        if (list.size <= 1) {
            if (container?.visibility != View.GONE) container?.visibility = View.GONE
            return
        }
        if (container != null && container.visibility != View.VISIBLE) container.visibility = View.VISIBLE

        val ctx = appContext
        val density = ctx.resources.displayMetrics.density
        val colors = currentThemeColors
        val activeId = activeSession()?.conversationId

        // 只在会话集合/顺序变化时重建标签视图；否则复用，避免每 150ms 重建。
        val orderChanged = tabViews.size != list.size ||
            list.withIndex().any { (i, s) -> row.getChildAt(i) !== tabViews[s.conversationId] }
        if (orderChanged) {
            row.removeAllViews()
            // 只保留当前还在的会话，防止已结束的会话把视图一直攥着。
            val liveIds = list.mapTo(HashSet()) { it.conversationId }
            tabViews.keys.retainAll(liveIds)
            list.forEachIndexed { index, s ->
                val label = tabViews.getOrPut(s.conversationId) {
                    TextView(ctx).apply {
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setPadding(
                            (10 * density).toInt(), (5 * density).toInt(),
                            (10 * density).toInt(), (5 * density).toInt()
                        )
                        setOnClickListener {
                            // 点标签 = 切到这个会话看它的正文；再点一次取消手选、跟随前台。
                            // 用 view.tag 取 id，避免闭包捕获旧列表。
                            val id = (it.tag as? Long) ?: return@setOnClickListener
                            selectedId = if (selectedId == id) null else id
                            stickToBottom = true
                            renderContent()
                        }
                    }
                }
                label.tag = s.conversationId
                // 每次都显式设一遍布局参数：复用的视图可能带着上一次的 margin
                // （例如原 index 1 的标签变成 index 0 后，左侧会多出一段间距）。
                label.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = (6 * density).toInt() }
                row.addView(label)
            }
        }

        // 复用路径也要刷新文案与高亮（计时每秒在变、状态随时在变）。
        val activeBg = activeTabBgProto ?: GradientDrawable().apply {
            cornerRadius = 9f * density
            setColor(colors.primaryContainer.toArgb())
        }.also { activeTabBgProto = it }
        val inactiveBg = inactiveTabBgProto ?: GradientDrawable().apply {
            cornerRadius = 9f * density
            setColor(colors.surfaceVariant.toArgb())
        }.also { inactiveTabBgProto = it }
        list.forEachIndexed { index, s ->
            val label = row.getChildAt(index) as? TextView ?: return@forEachIndexed
            label.text = tabLabel(s)
            val isActive = s.conversationId == activeId
            label.setTextColor((if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant).toArgb())
            // 克隆样板：每个 View 拿到独立 Drawable（见字段注释），且每帧只改引用不算脏。
            label.background = (if (isActive) activeBg else inactiveBg).constantState?.newDrawable()
        }

        // 选中项在横向滚动区外时滚进可视范围（会话多时否则看不到当前高亮）。
        // 仅在切换选中/增删标签后滚——每次渲染都滚会打断用户的手动横向滑动。
        val activeIndex = list.indexOfFirst { it.conversationId == activeId }
        if (activeIndex >= 0 && orderChanged) {
            val child = row.getChildAt(activeIndex)
            container?.post { container.smoothScrollTo(max(0, (child?.left ?: 0) - (12 * density).toInt()), 0) }
        }
    }

    /** 标签文案 = 阶段前缀 + 标题（+ 运行中/等待中的计时）。 */
    private fun tabLabel(s: OverlaySession): String {
        val prefix = when (s.state) {
            OverlaySessionState.RUNNING -> "● "
            OverlaySessionState.WAITING -> "? "
            OverlaySessionState.DONE -> "✓ "
        }
        // 已完成不再计时：否则「✓ 会话 137秒」会一直往上涨，看着像还在跑。
        val elapsed = if (s.state == OverlaySessionState.DONE) "" else elapsedSuffix(s.startedAt)
        val title = s.title.ifBlank { "会话 ${s.conversationId}" }
        return prefix + title + elapsed
    }

    /** 状态行 = 当前展示会话的状态文案 + 它自己的已耗时（与聊天页「AI 正在思考 12秒」一致）。 */
    private fun renderHeader() {
        val s = activeSession() ?: return
        // 同上：已完成不显示计时。
        val elapsed = if (s.state == OverlaySessionState.DONE) "" else elapsedSuffix(s.startedAt)
        statusView?.text = s.statusText + elapsed
    }

    /**
     * 把 [startedAt] 换算成「 12秒 / 1分5秒」后缀；不可计时（0）时返回空串。
     *
     * 计时**按会话各算各的**：并行跑时每个会话的起跑时刻不同，
     * 旧版用单一全局 `startedAt` 会把切过去那一刻当成起跑点，秒数完全不对。
     */
    private fun elapsedSuffix(startedAt: Long): String {
        if (startedAt <= 0L) return ""
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
        return when {
            elapsed <= 0 -> ""
            elapsed < 60 -> "  ${elapsed}秒"
            else -> "  ${elapsed / 60}分${elapsed % 60}秒"
        }
    }
}
