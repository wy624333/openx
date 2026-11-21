package com.wy.openx

import android.app.Application
import android.content.Intent
import android.os.Process
import com.wy.openx.container.FileLogger
import com.wy.openx.data.log.AILogger
import com.wy.openx.data.log.AppLogStore

/**
 * App 进程入口：早期初始化日志仓库，并安装全局崩溃处理器。
 *
 * [AppLogStore] 需要尽早可用，才能捕获启动阶段（容器/DB 初始化）的错误；
 * 放在 MainActivity 里会漏掉 Application 到 Activity 之间的问题。
 * [AILogger] 记录每次 AI 请求/响应的完整内容（每会话一文件），便于离线诊断模型交互。
 */
class OpenXApp : Application() {
    /** 当前界面路由（供崩溃报告显示「崩在哪一页」）。由 UI 层按需更新，可留空。 */
    @Volatile
    var currentRoute: String? = null

    override fun onCreate() {
        super.onCreate()
        AppLogStore.init(this)
        AILogger.init(this)
        // Shizuku 后端只持有 applicationContext；监听器注册失败（服务未安装等）内部已兜底。
        com.wy.openx.shizuku.ShizukuManager.init(this)
        // 语音引擎持有者：各引擎需要 applicationContext，统一在这里注入一次。
        com.wy.openx.voice.VoiceEngines.init(this)
        // 远程工作区：SFTP/FTP 客户端与配置存储需要 applicationContext。
        com.wy.openx.remote.RemoteEngines.init(this)
        logBuildIdentity()
        purgeLegacyUiStatePrefs()
        installCrashHandler()
        restorePluginOverlays()
    }

    /**
     * 恢复「上次退出时还开着的」插件浮窗（桌面宠物这类需要）。
     *
     * 浮窗活在宿主进程里，进程一死就没了；想在下次打开 App 时看到它，只能在这里
     * 主动拉一次。放后台线程 + 延迟：启动阶段主线程要干的事很多，而建浮窗要等
     * ComposeView 就绪（最多 5 秒），不能阻塞启动。
     *
     * 具体条件（自启开关、上次是否显示状态、权限）都在
     * `PluginOverlayWindow.restoreAutoStart` 里判。
     */
    private fun restorePluginOverlays() {
        Thread({
            runCatching { Thread.sleep(1500) }
            runCatching {
                com.wy.openx.plugin.ui.PluginOverlayWindow.restoreAutoStart(this) { msg ->
                    AppLogStore.info("PluginOverlay", msg)
                }
            }.onFailure { AppLogStore.error("App", it.message ?: "恢复插件浮窗失败") }
        }, "plugin-overlay-autostart").apply { isDaemon = true }.start()
    }

    /**
     * 每次进程启动把「本包身份」写进应用日志第一行。
     *
     * 为什么需要：排障时看日志无法确认**设备上跑的到底是哪个构建**。实测出现过
     * 「用户已覆盖安装新包，但日志行为仍是旧包」的争议，无从辨别是未生效还是没装成功。
     * 现以 [BuildConfig.VERSION_NAME]/[BuildConfig.VERSION_CODE]/[BuildConfig.BUILD_TIME]
     * 自证，且**每个进程一份日志文件**，故这条即「本次运行的构建指纹」。
     */
    private fun logBuildIdentity() {
        AppLogStore.info(
            "App",
            "启动版本: ${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) " +
                "buildTime=${BuildConfig.BUILD_TIME}"
        )
    }

    /**
     * 删掉旧版 `openx_ui_state` prefs 文件。
     *
     * 旧实现把**每条消息的思考折叠态**逐条写进这个文件（`thinking_fold_<msgId>`），
     * 既不清理也没有上限。SharedPreferences 有两个致命特性：
     *   1. 首次访问会把整个 XML 读成一个 HashMap **常驻内存**；
     *   2. 每次 `apply()` 都要把这张表**整体复制**一份新 HashMap。
     * 于是文件越大 → 每次落盘越吃内存，而滚动长会话时每渲染一条消息就触发一次落盘
     * （`LaunchedEffect(msgId, expanded, userToggled)` 连用户没动过的默认值也写），
     * 最终在长会话里 OOM：
     *   `OutOfMemoryError: HashMap.putMapEntries ← SharedPreferencesImpl$EditorImpl.apply`
     *
     * 用 `deleteSharedPreferences` 而不是「打开再删 key」：前者只是 unlink，
     * **不会**把那张巨大的表读进内存——这正是一上来就 OOM 的原因。
     * 代价是旧文件里记的展开/收起状态丢一次（纯 UI 状态，不是数据）。
     */
    private fun purgeLegacyUiStatePrefs() {
        runCatching { deleteSharedPreferences(LEGACY_UI_STATE_PREFS) }
    }

    /**
     * 捕获未处理异常：落盘后拉起全屏错误页（展示崩溃详情、支持复制），随即杀掉进程让系统
     * 重启并恢复错误页。
     *
     * 必须杀进程：主线程崩溃后其 Looper 已死，不杀进程则错误页窗口虽出现但 onCreate 永不执行
     * （白屏），最终被系统当 ANR 杀掉。杀掉后 AMS 检测到进程死在 Activity 创建途中，会自动重启
     * 进程并恢复 CrashActivity，新进程主线程健康，错误页才能正常渲染。
     *
     * 防递归：错误页自身崩溃时落盘标志仍在，直接交回系统默认处理器，避免无限重启。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃前先把异常写进应用日志（AppLogStore 每行 flush，落盘即时），便于离线诊断。
            runCatching { FileLogger.e("CRASH", "线程 ${thread.name} 未捕获异常", throwable) }
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_CRASH_UI_SHOWING, false)) {
                // 错误页自身崩溃：交回系统默认处理器，避免无限重启循环
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            prefs.edit().putBoolean(KEY_CRASH_UI_SHOWING, true).apply()
            try {
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
                        putExtra(CrashActivity.EXTRA_STACK, stackTraceOf(throwable))
                        putExtra(CrashActivity.EXTRA_SCREEN, currentRoute)
                    }
                )
            } catch (t: Throwable) {
                // 错误页启动失败（如系统限制）：交回默认处理器，不让崩溃被吞掉
                runCatching { FileLogger.e(TAG, "启动崩溃错误页失败", t) }
                prefs.edit().remove(KEY_CRASH_UI_SHOWING).apply()
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > MAX_CRASH_STACK_CHARS) {
            text.take(MAX_CRASH_STACK_CHARS) + "\n...[已截断]"
        } else {
            text
        }
    }

    companion object {
        private const val TAG = "OpenXApp"
        private const val CRASH_PREFS = "openx_crash"
        /** 膨胀的旧版思考折叠态 prefs，由 [purgeLegacyUiStatePrefs] 在启动时删除。 */
        private const val LEGACY_UI_STATE_PREFS = "openx_ui_state"
        private const val KEY_CRASH_UI_SHOWING = "crash_ui_showing"
        private const val MAX_CRASH_STACK_CHARS = 30_000

        /** CrashActivity 内容设置成功后调用：清除标志，允许下一次崩溃再次进入错误页。 */
        fun resetCrashUiFlag(context: android.content.Context) {
            context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().remove(KEY_CRASH_UI_SHOWING).apply()
        }
    }
}
