package com.wy.openx.tools

import com.wy.openx.container.BoundedOutput
import com.wy.openx.container.FileLogger
import com.wy.openx.shizuku.ShizukuManager
import com.wy.openx.shizuku.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 Shizuku 以 adb shell（uid 2000）身份在宿主 Android 系统上执行命令。
 *
 * 与 [TerminalTool]（Alpine/proot 容器）不同，本工具直接作用于 Android 系统本身：
 * 可执行 `pm` / `am` / `cmd` / `settings` 等系统命令，适合辅助调试宿主。
 * 需用户已安装 Shizuku 并授予本应用权限，否则返回明确的状态提示。
 *
 * UserService 的 AIDL 调用是同步、一次性的（无逐行回传），故不实现流式输出。
 * 
 * （工具名加入 [ToolPermissionPolicyEngine] 的 shell 工具集合）。
 */
class ShizukuTool : AgentTool {

    private companion object {
        const val TAG = "ShizukuTool"
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "shizuku"

    override val description = """通过 Shizuku 在宿主 Android 系统上执行 shell 命令（adb shell / uid 2000）。
用于系统级操作：pm（安装/禁用应用）、am（启动 Activity/发送广播）、cmd、settings、dumpsys 等。
注意：
- 需用户已安装 Shizuku 并在「设置 → 应用权限授权 → Shizuku」中授权；未就绪时返回错误提示。
- 与 terminal 工具不同：terminal 在 Alpine 容器内执行，本工具直接作用于 Android 系统。
- 仅支持一次性执行：命令结束才返回输出，不提供流式/后台会话。
- 耗时命令请调大 timeout（默认 120 秒，上限 1800 秒）。"""

    override val parameters = buildToolParameters(
        ToolParameter(
            "command", ParameterType.STRING,
            "要执行的 shell 命令，如 pm list packages、settings get global airplane_mode_on",
            required = true
        ),
        ToolParameter(
            "timeout", ParameterType.INTEGER,
            "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS，上限 $MAX_TIMEOUT_SECONDS。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val command = (args["command"] as? String)?.trim()
        if (command.isNullOrEmpty()) return@withContext "错误：缺少必需参数 command"

        val state = ShizukuManager.state.value
        if (state != ShizukuState.READY) {
            return@withContext "错误：Shizuku 未就绪：${stateHint(state)}"
        }

        val timeoutSeconds = ((args["timeout"] as? Double)?.toLong()
            ?: (args["timeout"] as? Number)?.toLong()
            ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1L, MAX_TIMEOUT_SECONDS)

        try {
            FileLogger.d(TAG, "Shizuku exec (timeout=${timeoutSeconds}s): $command")
            val result = ShizukuManager.runCommand(command, timeoutSeconds * 1000L)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Shizuku exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            if (result.exitCode == 0) {
                output.ifEmpty { "(命令执行成功，无输出)" }
            } else {
                val codeNote = if (result.exitCode < 0) {
                    "执行异常/超时（退出码 ${result.exitCode}）"
                } else {
                    "退出码 ${result.exitCode}"
                }
                if (output.isEmpty()) codeNote else "$output\n[$codeNote]"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku exec 失败: $command", e)
            "执行 Shizuku 命令失败：${e.message}"
        }
    }

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku，请先安装并启动 Shizuku 服务"
        ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行，请在 Shizuku 应用中启动服务"
        ShizukuState.PERMISSION_DENIED -> "本应用尚未获得 Shizuku 授权，请在设置中授予"
        ShizukuState.READY -> ""
    }
}
