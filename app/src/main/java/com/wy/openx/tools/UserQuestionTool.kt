package com.wy.openx.tools

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeout

class UserQuestionTool : AgentTool {
    override val name = "ask_user"
    override val description = "向用户提问并等待回答。适合需要用户决策、选择、确认或输入信息的场景，" +
        "例如设计选择、配置确认、方向决策等。多个不相关的问题请一次用 questions 数组问完（最多 4 个）。"
    override val parameters = buildToolParameters(
        ToolParameter("question", ParameterType.STRING, "向用户提出的单个问题（与 questions 二选一）", required = false),
        ToolParameter("options", ParameterType.ARRAY, "可选项列表（可选），用户可直接选择，例如 [\"方案A\", \"方案B\", \"方案C\"]", required = false, itemsSchema = mapOf("type" to "string")),
        ToolParameter("allow_custom", ParameterType.BOOLEAN, "是否允许用户输入自定义答案（默认 true）", required = false),
        ToolParameter("multi_select", ParameterType.BOOLEAN, "是否允许多选（默认 false，单选）。为 true 时用户可勾选多个选项，返回以顿号、分隔", required = false),
        ToolParameter(
            "questions", ParameterType.ARRAY,
            "一次问 1–4 个问题（可选，优先于顶层 question）。每项：{question, header, options, allow_custom, multiSelect}。" +
                "options 支持字符串数组，或 [{label, description}] 带说明的选项数组。",
            required = false,
            itemsSchema = mapOf("type" to "object")
        )
    )

    private companion object {
        const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L
    }

    override suspend fun execute(args: Map<String, Any>): String {
        val questions = args["questions"] as? List<*>
        if (questions != null && questions.isNotEmpty() && questions.size > 4) {
            return "错误：一次最多问 4 个问题，请拆分后再调用"
        }
        val question = (args["question"] as? String)?.trim()
        if (question.isNullOrEmpty() && questions.isNullOrEmpty()) return "错误：未提供问题"
        // 本轮所属会话：优先协程上下文（发起时冻结，并发可靠），退回全局前台（单前台旧语义）。
        val cid = coroutineContext[AppContextHolder.ConversationId]?.id
            ?: AppContextHolder.currentConversationId ?: 0L
        // 同一会话已有未回答提问时拒绝新的（不同会话各自独立、互不阻塞）。
        if (AppContextHolder.hasPendingQuestion(cid)) {
            return "错误：本会话已有一个待回答的提问，请等待用户响应后再发起新提问"
        }
        val deferred = CompletableDeferred<String>()
        AppContextHolder.setPendingQuestion(cid, deferred)
        return try {
            val answer = withTimeout(QUESTION_TIMEOUT_MS) { deferred.await() }
            if (answer.isBlank()) "用户没有给出回答" else "用户回答: $answer"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "用户超过5分钟未回答，本次提问已超时。请继续执行或自行假设合理默认值。"
        } finally {
            // 只清理仍属于自己的槽位，避免误清新发起的提问
            AppContextHolder.clearPendingQuestionIfOwned(cid, deferred)
        }
    }
}