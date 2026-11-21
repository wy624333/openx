package com.wy.openx.tools

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.wy.openx.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoneOpTool : AgentTool {

    override val name = "phone_operator"
    override val description = "执行手机操作：获取屏幕内容（dump_ui）、模拟点击/滑动/输入文字/按键等。需要先在系统设置中开启辅助功能权限。"
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型（截图请用独立的 screenshot 工具，获得更清晰的结果）", enumValues = listOf("screenshot", "dump_ui", "tap", "swipe", "input_text", "keyevent", "battery", "foreground_app", "wake_screen", "open_app")),
        ToolParameter("x", ParameterType.INTEGER, "tap/swipe 的 X 坐标", required = false),
        ToolParameter("y", ParameterType.INTEGER, "tap/swipe 的 Y 坐标", required = false),
        ToolParameter("x2", ParameterType.INTEGER, "swipe 终点 X", required = false),
        ToolParameter("y2", ParameterType.INTEGER, "swipe 终点 Y", required = false),
        ToolParameter("text", ParameterType.STRING, "input_text 要输入的文字（调用前建议先 tap 点击输入框，再等待约 1 秒让 UI 刷新）", required = false),
        ToolParameter("keycode", ParameterType.STRING, "keyevent 的按键代码(如 KEYCODE_BACK, KEYCODE_HOME, KEYCODE_RECENT)", required = false),
        ToolParameter("package_name", ParameterType.STRING, "open_app 要打开的安卓应用包名，如 com.tencent.mm", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String ?: return@withContext "错误：未指定操作"
            val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"

            val service = AgentAccessibilityService.instance
            if (service == null) {
                return@withContext "辅助功能服务未运行，请在 系统设置 > 辅助功能 > OpenX 中开启后重试"
            }

            when (action) {
                "screenshot" -> {
                    ScreenshotTool().execute(mapOf("save_to" to ""))
                }
                "dump_ui" -> {
                    val result = service.dumpUi()
                    if (result.length > 8000) {
                        result.take(8000) + "\n\n---\n（UI 树过长，仅显示前 8000 字符。完整内容共 ${result.length} 字符）"
                    } else result
                }
                "tap" -> {
                    val x = (args["x"] as? Double)?.toInt() ?: return@withContext "错误：请提供 x 坐标"
                    val y = (args["y"] as? Double)?.toInt() ?: return@withContext "错误：请提供 y 坐标"
                    service.tap(x, y)
                }
                "swipe" -> {
                    val x1 = (args["x"] as? Double)?.toInt() ?: return@withContext "错误：请提供起点 x"
                    val y1 = (args["y"] as? Double)?.toInt() ?: return@withContext "错误：请提供起点 y"
                    val x2 = (args["x2"] as? Double)?.toInt() ?: return@withContext "错误：请提供终点 x2"
                    val y2 = (args["y2"] as? Double)?.toInt() ?: return@withContext "错误：请提供终点 y2"
                    service.swipe(x1, y1, x2, y2)
                }
                "input_text" -> {
                    val text = args["text"] as? String ?: return@withContext "错误：请提供输入文本"
                    if (service.setFocusedText(text)) "已输入文本 (${text.length} 字符): $text"
                    else "输入失败：未找到可输入的文本框"
                }
                "keyevent" -> {
                    val keycode = args["keycode"] as? String ?: return@withContext "错误：请提供按键代码"
                    val actionId = when (keycode.uppercase()) {
                        "KEYCODE_BACK", "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                        "KEYCODE_HOME", "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                        "KEYCODE_RECENT", "RECENT", "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        "KEYCODE_NOTIFICATIONS", "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        "KEYCODE_QUICK_SETTINGS", "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        "KEYCODE_POWER", "POWER" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        "KEYCODE_LOCK", "LOCK" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        else -> null
                    }
                    if (actionId != null) {
                        val ok = service.performGlobal(actionId)
                        if (ok) "已执行按键: $keycode"
                        else "按键 $keycode 执行失败"
                    } else {
                        "不支持的按键: $keycode（支持: BACK, HOME, RECENT, NOTIFICATIONS, QUICK_SETTINGS, POWER, LOCK）"
                    }
                }
                "battery" -> service.getBatteryInfo()
                "foreground_app" -> service.getForegroundApp()
                "wake_screen" -> {
                    if (service.wakeScreen()) "已唤醒屏幕" else "唤醒屏幕失败"
                }
                "open_app" -> {
                    val pkg = args["package_name"] as? String ?: return@withContext "错误：请提供 package_name"
                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent == null) "错误：未找到包名为 '$pkg' 的应用"
                    else {
                        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "已打开应用: $pkg"
                    }
                }
                else -> "错误：不支持的操作 '$action'"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "操作出错: ${e.message}" }
    }
}
