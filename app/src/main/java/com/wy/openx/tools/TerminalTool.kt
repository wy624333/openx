package com.wy.openx.tools


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** `terminal` 的 action 枚举，与工具 schema 保持一致（供报错文案回显）。 */
private val TERMINAL_ACTIONS = listOf(
    "execute", "start_background", "send_input", "write_bytes",
    "get_output", "list_tabs", "close_tab"
)

class TerminalTool : AgentTool {

    override val name = "terminal"

    private companion object {
        /** 慢命令（下载/安装/构建/解压等）最小超时（秒）：AI 若传更小值会被强制抬高，避免半途被强杀。 */
        const val SLOW_COMMAND_MIN_TIMEOUT = 600
        /** 慢命令特征（命令名或典型操作）。命中即套用最小超时。 */
        val SLOW_COMMAND_REGEX = Regex(
            """(^|[\s;&|(])(wget|curl|pip|pip3|npm|yarn|pnpm|apt|apt-get|apk|gem|go|cargo|mvn|gradle|\./gradlew|make|cmake|git\s+clone|unzip|tar|javac|kotlinc|dpkg|dnf|yum|sdkmanager|avdmanager|aapt2|ndk-build|\./configure)\b"""
        )
        /** 命令是否属于「耗时类」（下载/安装/构建/解压）。 */
        fun isSlowCommand(cmd: String): Boolean = SLOW_COMMAND_REGEX.containsMatchIn(cmd)
    }
    override val description = """在 Android 设备的 Linux 容器（Alpine/proot）中执行 shell 命令，或管理持久终端会话。
动作（action，默认 execute）：
- execute：在容器中执行一条命令并返回输出（一次性进程，命令结束即返回；不在前台终端显示、无需打开终端页）。
   耗时命令（构建、安装依赖等）请把 timeout 参数调大（默认 120s，最大 1800s）；下载/安装/构建类命令
   （wget/curl/apt/npm/gradle 等）会自动套用至少 600s 的下限，避免被小 timeout 强杀在半途。
   **禁止用 nohup/& 把长驻服务（http.server、npm run dev 等）挂后台**——后台进程会占住输出管道，
   让本次调用排队直到超时被杀。长驻服务一律用 start_background(notify=false)。
  注意：**不保持跨命令状态**——cd/export/alias 等只在本条命令内有效；如需连续操作请用 `cd x && ...` 串在同一条命令，
  或给每条命令传 workdir 参数指定工作目录。
- start_background：把长任务（如 npm run dev、python server）挂到独立后台会话，返回 term-N 标签 id，不阻塞本次调用。
  该会话是**持久** PTY，可在其中 send_input 连续操作（此会话内 cd/export 状态保持）。
- send_input：向指定后台标签发送命令/输入并回车（用 Ctrl+C 打断优先 write_bytes 传 3）。
- write_bytes：向指定后台标签发送原始字节（如 Ctrl-C=3、Ctrl-D=4）。
- get_output：读取指定后台标签的累计输出（不传 id 则取**本会话**最近的后台标签；跨会话标签会被拒绝）。
- list_tabs：列出本会话的后台标签与用户手动开的标签（id/是否后台/运行状态/命令）。
- close_tab：关闭指定后台标签。
后台任务适合：dev server、长构建、安装依赖等会长时间运行的命令。"""
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作动作", enumValues = listOf("execute", "start_background", "send_input", "write_bytes", "get_output", "list_tabs", "close_tab")),
        ToolParameter("command", ParameterType.STRING, "execute/start_background 的 shell 命令，如 ls -la、cd /sdcard && pwd、npm run dev", required = false),
        ToolParameter("id", ParameterType.STRING, "会话标签 id（send_input/write_bytes/get_output/close_tab 用，形如 term-1）", required = false),
        ToolParameter("title", ParameterType.STRING, "start_background 的自定义标题（可选）", required = false),
        ToolParameter("notify", ParameterType.BOOLEAN, "start_background：命令是否自动触发完成通知（默认 true 会返回一次完成；false 表示保活后台会话可继续输入）", required = false),
        ToolParameter("input", ParameterType.STRING, "send_input 的输入文本", required = false),
        ToolParameter("enter", ParameterType.BOOLEAN, "send_input 是否自动追加回车（默认 true）", required = false),
        ToolParameter("bytes", ParameterType.ARRAY, "write_bytes 的字节列表（十进制，如 [3] 表示 Ctrl-C）", required = false, itemsSchema = mapOf("type" to "integer")),
        ToolParameter("workdir", ParameterType.STRING, "工作目录（可选，execute 用）", required = false),
        ToolParameter("timeout", ParameterType.INTEGER, "execute 超时时间（秒，默认 120，最大 1800；构建/安装依赖等耗时命令请调大）", required = false),
        ToolParameter("maxOutput", ParameterType.INTEGER, "execute 最大输出字符数（默认 10000）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "execute"

        // 代码在服务器上时，本地容器里跑命令看到的是旧副本（或根本没有那份代码）。
        // 不拦的话模型会以为在构建服务器的代码，实际跑在容器里——结果看着还挺像回事。
        // 只对显式传了 workdir 的情况拦：没传时本工具无从得知项目根，交给模型看提示词里的环境说明。
        (args["workdir"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { wd ->
            if (com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(wd)) {
                return@withContext "错误：目录「$wd」在「服务器为准」的工作区内，代码在服务器上。" +
                    "本地容器里执行命令看不到那份代码，请改用 remote_ssh 工具" +
                    "（参数 connection=<服务器名>, command=<命令>）。"
            }
        }

        val e = AppContextHolder.terminalEngine
        if (e == null) return@withContext "错误：终端引擎不可用"
        // 【P4-3】本轮所属 AI 会话：优先协程上下文（发起时冻结，并发可靠），退回全局前台。
        val cid = coroutineContext[AppContextHolder.ConversationId]?.id
            ?: AppContextHolder.currentConversationId ?: 0L
        // 标签归属校验：无主标签（cid=0，用户手动开的）任何会话可用；
        // 属于别的会话的标签直接拦下 —— 否则 AI 会读到/操控另一个任务的终端，不报错但结果错。
        val ownedByThisSession: (String) -> Boolean = { id ->
            cid <= 0L || e.tabOwner(id).let { it == 0L || it == cid }
        }

        try {
            when (action) {
                "start_background" -> {
                    val command = (args["command"] as? String)?.trim()
                        ?: return@withContext "错误：未提供 command"
                    guardCommand(command)?.let { return@withContext it }
                    val title = args["title"] as? String
                    val notify = (args["notify"] as? Boolean) ?: true
                    val source = e.activeTabId.value
                    val id = e.startBackgroundCommand(command, title, notify, source, cid)
                    buildString {
                        appendLine("已启动后台会话 $id（notify=$notify）：$command")
                        if (!title.isNullOrBlank()) {
                            // 标题只是展示用的别名，句柄始终是 id —— 说清楚，避免后续拿标题当 id 传。
                            appendLine("标题：「$title」（仅展示用；操作时请用 id：$id）")
                        }
                        appendLine("可用 send_input / get_output / close_tab 操作该会话，句柄用 $id")
                    }
                }

                "send_input" -> {
                    val id = args["id"] as? String ?: return@withContext "错误：未提供 id"
                    val input = args["input"] as? String ?: return@withContext "错误：未提供 input"
                    val enter = (args["enter"] as? Boolean) ?: true
                    guardCommand(input)?.let { return@withContext it }
                    if (!ownedByThisSession(id)) return@withContext "错误：标签 $id 属于其他会话，不能跨会话输入"
                    if (e.sendInput(id, input, enter)) {
                        "已发送到 $id：${if (enter) "$input（回车）" else input}"
                    } else {
                        "错误：标签 $id 不存在或已结束"
                    }
                }

                "write_bytes" -> {
                    val id = args["id"] as? String ?: return@withContext "错误：未提供 id"
                    val bytes = (args["bytes"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                        ?: return@withContext "错误：未提供 bytes"
                    if (!ownedByThisSession(id)) return@withContext "错误：标签 $id 属于其他会话，不能跨会话写入"
                    if (e.writeBytesToTab(id, *bytes.toIntArray())) {
                        "已向 $id 写入字节：${bytes.joinToString(", ")}"
                    } else {
                        "错误：标签 $id 不存在或已结束"
                    }
                }

                "get_output" -> {
                    // 不传 id 时优先取**本会话**最近的后台标签，而不是全局活动标签：
                    // 后者并发时属于另一个会话，会把别人的终端输出当成本任务结果回传给模型。
                    val id = (args["id"] as? String)
                        ?: e.lastBackgroundTabIdFor(cid)
                        ?: e.activeTabId.value
                        ?: return@withContext "错误：无活动标签，请先打开终端页或启动后台任务"
                    if (!ownedByThisSession(id)) return@withContext "错误：标签 $id 属于其他会话，请用 list_tabs 看本会话自己的标签 id"
                    val output = e.getTabOutput(id)
                        ?: return@withContext "错误：标签 $id 不存在或者已关闭"
                    if (output.isBlank()) "标签 $id 暂无输出"
                    else output
                }

                "list_tabs" -> {
                    // 只列本会话的 + 无主的（用户手动开的交互标签）：避免 AI 看到别的会话的 id 去误读。
                    val tabs = e.listTabs().filter { cid <= 0L || it.ownerConversationId == 0L || it.ownerConversationId == cid }
                    if (tabs.isEmpty()) {
                        "没有会话标签，请先打开终端页或 start_background 启动后台任务"
                    } else {
                        buildString {
                            appendLine("会话标签：")
                            tabs.forEach { t ->
                                // 句柄一律用 **id** 展示：send_input / get_output / close_tab 要传的就是 id。
                                // 以前这里只显示 title，于是「列表里看到的名字」与「实际要传的句柄」不是同一个串，
                                // 照抄标题去查就会报「标签不存在」（真机踩过）。title 降级为附注，与 id 相同时省略。
                                val titleNote = t.title
                                    .takeIf { it.isNotBlank() && it != t.id }
                                    ?.let { "  「$it」" }.orEmpty()
                                appendLine(
                                    "  ${t.id}$titleNote  ${if (t.isBackground) "[后台]" else "[前台]"} " +
                                        "${if (t.running) "运行中" else "已结束"}" +
                                        "${if (t.command != null) "  cmd: ${t.command}" else ""}"
                                )
                            }
                        }
                    }
                }

                "close_tab" -> {
                    val id = args["id"] as? String ?: return@withContext "错误：未提供 id"
                    if (!ownedByThisSession(id)) return@withContext "错误：标签 $id 属于其他会话，不能跨会话关闭"
                    if (e.closeTab(id)) "已关闭标签 $id" else "错误：标签 $id 不存在"
                }

                else -> executeCommand(e, args)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (ce: Exception) {
            "❌ 执行失败: ${ce.message ?: "未知错误"}"
        }
    }

    /** 安全守卫：返回 null 表示放行，返回非 null 表示拦截文案（阻止执行）。 */
    private suspend fun guardCommand(command: String): String? {
        when (val v = TerminalCommandGuard.analyze(command)) {
            is TerminalCommandGuard.Verdict.Allow -> return null
            is TerminalCommandGuard.Verdict.Block ->
                return "⛔ 已拦截危险命令（${v.reason}）。命令未执行。"
            is TerminalCommandGuard.Verdict.Confirm -> {
                val requester = AppContextHolder.terminalConfirmRequester
                if (requester == null) {
                    return "⛔ 危险命令需人工确认，但确认入口不可用，已拒绝执行。\n命令: $command"
                }
                val approved = requester(command, v.reason)
                return if (approved) null
                else "🛑 危险命令已被用户拒绝，未执行。\n命令: $command"
            }
        }
    }

    private suspend fun executeCommand(e: TerminalEngine, args: Map<String, Any>): String {
        // 与 file_manager 同类问题：模型漏参数时，原报错只有「未提供命令」五个字，
        // 没有任何可据此纠正的信息，模型只能反复发同样错的调用。把该传什么、可用
        // action 有哪些一并回显，让它下一轮就能改对。
        val command = (args["command"] as? String)?.trim()
            ?: return "错误：未提供 command 参数（要执行的 shell 命令）。" +
                "action 可选值：${TERMINAL_ACTIONS.joinToString(" / ")}；" +
                "后台长任务请用 action=start_background 并传 command。"
        val rawTimeout = ((args["timeout"] as? Double)?.toInt())?.coerceIn(1, 1800) ?: 120
        // 慢命令下限保护：下载/安装/构建类命令 inherently 耗时，AI 若传了小 timeout（如 30）会被
        // 强杀在半途（用户反馈：wget 下载被 30s 超时终止）。对这类命令强制抬高到至少 SLOW_MIN。
        val timeout = if (isSlowCommand(command)) rawTimeout.coerceAtLeast(SLOW_COMMAND_MIN_TIMEOUT) else rawTimeout
        val maxOutput = ((args["maxOutput"] as? Double)?.toInt())?.coerceIn(100, 50000) ?: 10000
        val workdir = (args["workdir"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        guardCommand(command)?.let { return it }

        val envInfo = "[Shell 环境]"

        return try {
            val output = e.executeAi(command, timeout * 1000L, workdir)

            buildString {
                appendLine(envInfo)
                appendLine("```")
                append("$ "); appendLine(command)
                if (output.isNotBlank()) {
                    // 留尾部不留头部：命令回显、编译日志的结论（报错栈、失败原因、"成功"那行）
                    // 都在最后。旧实现是 output.take(maxOutput) —— 恰好把结论丢掉，
                    // 用户在详情弹窗里看到的就是「日志开头 + 没有结尾」。规则见 ToolOutputFold.keepTail。
                    val tailed = com.wy.openx.data.util.ToolOutputFold.keepTail(output, maxOutput)
                    if (tailed != null) {
                        appendLine("[输出过长：共 ${output.length} 字符，此处显示最后 $maxOutput 字符（完整内容见长按复制）]")
                        appendLine(tailed)
                    } else {
                        appendLine(output.trimEnd())
                    }
                }
                append("```")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (e: Exception) {
            "❌ 执行失败: ${e.message ?: "未知错误"}\n命令: $command"
        }
    }
}