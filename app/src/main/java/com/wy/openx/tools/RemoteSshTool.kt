package com.wy.openx.tools

import com.wy.openx.container.CommandEngine
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteHostKeyPendingException
import com.wy.openx.remote.RemoteProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `remote_ssh`：在用户已授权的远端 SSH 服务器上执行命令。
 *
 * ## 为什么是独立工具，而不是让 `terminal` 改道
 * `terminal` 跑在本地容器里，`rm -rf /` 顶多废掉一个可重装的 Alpine rootfs；
 * 这里跑在**用户真实的服务器**上，同一串字符不可逆。若按「执行模式」静默改道，
 * 模型会以为自己在沙盒里——这种误判的代价太大，不值得为省一次参数交换去冒。
 * 独立工具让「在哪执行」变成每次调用都要显式说清的事。
 */
class RemoteSshTool : AgentTool {

    override val name = "remote_ssh"

    override val description = """在远端 SSH 服务器上执行 shell 命令（真实服务器，操作不可逆，谨慎使用）。

前提：该服务器已在「设置 → 连接与同步」中**逐台**开启「允许 AI 执行命令」。
未开启时本工具会直接拒绝，请让用户先去开启，不要换参数反复重试。

- connection：服务器名称（或连接 id），对应连接列表里那一项。
- command：要执行的 shell 命令。远端不保证有 bash/python，POSIX 写法更稳；
  工作目录是远端登录用户的默认目录，不继承手机的容器工作区，路径请自己写全。
- timeout：超时秒数，默认 120，最大 1800；构建/安装类命令请调大。

输出与本地 terminal 工具同样限幅（过长时只保留开头与结尾），末尾附退出码。
命令跑完后 SSH 通道会保留，下一次调用直接复用，不必重复建连。"""

    override val parameters = buildToolParameters(
        ToolParameter("connection", ParameterType.STRING, "服务器名称或连接 id（见连接列表）"),
        ToolParameter("command", ParameterType.STRING, "要执行的 shell 命令"),
        ToolParameter("timeout", ParameterType.INTEGER, "超时秒数，默认 120，最大 1800", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val wanted = (args["connection"] as? String)?.trim().orEmpty()
        val command = (args["command"] as? String)?.trim().orEmpty()
        if (wanted.isEmpty()) return@withContext "错误：必须指定 connection（服务器名称或连接 id）"
        if (command.isEmpty()) return@withContext "错误：command 不能为空"

        val manager = RemoteEngines.manager
        val connections = manager.connections.value
        if (connections.isEmpty()) {
            return@withContext "错误：还没有配置任何服务器。请让用户先在「设置 → 连接与同步」里添加。"
        }
        val config = connections.firstOrNull { it.id == wanted }
            ?: connections.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: return@withContext "错误：找不到服务器「$wanted」。当前可用：" +
            connections.joinToString("、") { it.name }

        if (!config.allowAiExec) {
            return@withContext "错误：服务器「${config.name}」未开启「允许 AI 执行命令」。" +
                "请让用户在「设置 → 连接与同步」里编辑该连接并打开此开关。"
        }
        if (config.protocol != RemoteProtocol.SFTP) {
            return@withContext "错误：服务器「${config.name}」是 FTP 连接，无法执行命令（需要 SFTP/SSH）。"
        }

        // 复用与本地 terminal 相同的硬拦截。远端要拦的正是它覆盖的那几类
        //（格式化磁盘 / 改分区 / fork 炸弹 / 关机）——在真机上误伤无法挽回。
        when (val verdict = TerminalCommandGuard.analyze(command)) {
            is TerminalCommandGuard.Verdict.Allow -> Unit
            is TerminalCommandGuard.Verdict.Block ->
                return@withContext "⛔ 已拦截（${verdict.reason}）。命令未在服务器上执行。"
            is TerminalCommandGuard.Verdict.Confirm ->
                return@withContext "⛔ 该命令需要用户确认，已跳过。请让用户自行在服务器上执行。"
        }

        val seconds = (args["timeout"] as? Number)?.toLong() ?: 120L
        val timeoutMs = (seconds * 1000).coerceIn(1_000L, CommandEngine.MAX_TIMEOUT_MS)

        val engine = manager.execEngineFor(config.id)
            ?: return@withContext "错误：无法为「${config.name}」创建执行后端"

        val result = runCatching {
            engine.runCommandSyncWithExit(command, projectPath = null, timeoutMs = timeoutMs)
        }.getOrElse { error ->
            if (error is RemoteHostKeyPendingException) {
                // 指纹确认必须由人来拍板，模型自己重试多少次都没用
                return@withContext "错误：${error.message}。" +
                    "请让用户在「连接与同步 → 连接配置」里连一次该服务器并确认指纹，然后再重试。"
            }
            return@withContext "错误：远端执行失败（${error.message ?: error::class.simpleName}）"
        }

        // 末尾补退出码：非零表示「命令跑完了但结果不成功」，必须让模型看见，
        // 否则它会把报错文本当成正常输出继续往下做。
        val body = result.output.ifBlank { "（无输出）" }
        val code = result.exitCode
        if (code == null) "$body\n\n[退出码未知：命令可能被超时终止]"
        else "$body\n\n[退出码 $code]"
    }
}
