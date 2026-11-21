package com.wy.openx.data.util

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.wy.openx.data.remote.ChatMessageDto

/**
 * 工具输出预算投影：发请求前给**近期工具输出**单独记一份累计预算，超出的从最旧开始落盘 + 换成一句提示。
 *
 *
 *
 * ## 为什么必须有
 * 工具结果入历史后**每轮都原样重发**。openx 这边单次 `readFile` 可达 200KB（`ReadFileTool.MAX_BYTES`）、
 * 单次 `terminal` 5 万字符，而历史里不做任何截断（`MainViewModel` 直接 `toolResult = resultContent`）。
 * 几轮大读取就能把请求体顶到 1MB 以上——这正是「上下文跑到 250 多 K、换个模型也 HTTP 500、
 * 压缩一下就好」的成因：撞的是网关请求体上限，不是模型上下文窗口。
 *
 * ## 三条必须守住的不变量
 * 1. **只做请求投影，不改存储**：不改 `uiState.messages`、不落库。UI 仍能展开看全文，
 *    用户看到的工具卡片和导出备份都还是完整内容。
 * 2. **落盘失败必须抛错**，不能静默降级成一句「详见文件」而文件其实没写成——那会让模型
 *    拿着一个不存在的路径去编内容，比直接报错危险得多。
 * 3. **从最旧开始省**：越新的工具结果越可能是当前任务还在用的，优先留在上下文里。
 *
 * @param budgetTokens 近期工具输出的累计预算；50_000 按常用实践取值（512K 模型约占 10%）。
 * @param offload 落盘回调：(工具名, 原文) → 提示语里要用的绝对路径。抛异常即视为落盘失败，整体中止。
 * @param toolNameOf 取消息对应的工具名，仅用于落盘文件名。
 */
object ToolOutputBudget {

    /** 近期工具输出累计预算（token）。 */
    const val DEFAULT_BUDGET_TOKENS = 50_000

    /**
     * 落盘提示的起始标记。用于识别「已落盘」的消息，避免重复落盘。
     *
     * 必须与 [notice] 生成的文本一致。
     */
    const val OFFLOAD_NOTICE_PREFIX = "[工具输出过大，已完整保存到："

    /**
     * 落盘后替换提示语的 token 估算（供上下文估算侧模拟投影用）。
     *
     * [notice] 含宿主 + 容器两条路径的文字，约 100~140 token；取 130 作常量，
     * 让「估算实际会发出去的上下文」时能把被落盘的旧工具结果按提示长度计入，
     * 而不是把库里早已不发送的全文也算进去。
     */
    const val OFFLOADED_NOTICE_TOKENS = 130

    /**
     * 返回一份「可用于发请求」的消息列表；超预算的 tool 消息内容被替换为落盘提示。
     * 消息顺序、role、tool_call_id 一律不变——只换 tool 消息的 content。
     */
    fun project(
        history: List<ChatMessageDto>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        toolNameOf: (ChatMessageDto) -> String = { "tool" },
        offload: (toolName: String, content: String) -> String
    ): List<ChatMessageDto> {
        if (history.isEmpty() || budgetTokens <= 0) return history

        // 保留「最新的一段连续工具输出」：从最新往最旧累计，装不下的那条及其**更旧**的全部落盘。
        // （若从头往后累计，留下的反而是最旧的一段——越新的工具结果越可能是当前任务还在用的。）
        var retained = 0L
        var dropFrom = -1
        for (index in history.indices.reversed()) {
            val msg = history[index]
            if (!isToolResult(msg)) continue
            val size = TokenEstimator.bodyTokens(textOf(msg))
            if (retained + size > budgetTokens) {
                dropFrom = index + 1
                break
            }
            retained += size
        }
        if (dropFrom < 0) return history

        return history.mapIndexed { index, msg ->
            // dropFrom 之前是「装不进预算的更旧一段」——落盘；dropFrom 及之后是保留下来的最新一段
            if (index >= dropFrom || !isToolResult(msg)) return@mapIndexed msg
            val original = textOf(msg)
            if (original.isBlank()) return@mapIndexed msg
            // 已经是落盘提示：直接保留，不再落盘。
            //
            // 不加这个判断会造成「提示被当成新内容再落盘一次」：digest 基于提示文本，
            // 而提示里又嵌着上一次的落盘路径 → 同一条消息在相邻两次请求里内容不同 →
            // prompt cache 的前缀在该处断开，**它之后的所有内容全部无法命中**。
            // 实测现象：命中率卡在 system prompt 的占比（约 24%）、越聊越长反而越低。
            if (original.startsWith(OFFLOAD_NOTICE_PREFIX)) return@mapIndexed msg
            // 落盘失败向上抛，绝不返回一句指向不存在文件的提示
            val path = offload(toolNameOf(msg), original)
            msg.copy(content = JsonPrimitive(notice(path)))
        }
    }

    /** tool 结果消息：role=tool 且带 tool_call_id。 */
    private fun isToolResult(msg: ChatMessageDto): Boolean =
        msg.role == "tool" && msg.toolCallId != null

    private fun textOf(msg: ChatMessageDto): String {
        val c: JsonElement? = msg.content
        return when {
            c == null || c.isJsonNull -> ""
            c.isJsonPrimitive -> c.asString
            else -> c.toString()
        }
    }

    /**
     * 替换提示语。同时给出宿主绝对路径与容器内路径：openx 的 terminal 默认跑在容器里
     * （`Workspace.root` → 绑定为 `/root/workspace`），只给宿主路径模型用 terminal 读不回来。
     */
    fun notice(path: String): String = buildString {
        append("[工具输出过大，已完整保存到：").append(path)
        append("。需要细节时用 readFile 读取该路径；若在终端里操作，容器内对应路径为 ")
        append(ToolOffloadPaths.containerPathOf(path))
        append("。本次分析请先用上面的摘要，需要原文时再去读。]")
    }
}
