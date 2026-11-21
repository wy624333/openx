package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 请求侧顺序：压缩产物必须提到历史**最前**（对齐 AiCode-RC2 `orderHistoryEntities`）。
 *
 * ## 为什么展示与请求是两套顺序
 *
 * `commitCompaction` 把 marker/摘要排在**全部消息之后**（含保留尾），卡片才会紧贴
 * 「触发压缩的那个工具」。但模型必须**先读摘要、再读保留历史**——摘要描述的是保留尾
 * **之前**发生的事，留在末尾会被当成最后一条发言。
 *
 * AiCode 就是这么分的：`nextTimestamp()` 决定展示顺序（末尾），
 * `orderHistoryEntities` 决定请求顺序（compaction 在前）。
 */
class HoistCompactionArtifactsTest {

    private fun m(id: Long, content: String) =
        ChatMessageModel(id, MessageRole.USER, "我", "10:00", content)

    private fun ids(list: List<ChatMessageModel>) = list.map { it.id }

    @Test
    fun `marker 与其后的摘要被提到最前`() {
        val list = listOf(
            m(1, "系统提示"), m(2, "折叠原文"), m(3, "保留尾工具A"), m(4, "保留尾工具B"),
            m(900, COMPACTION_MARKER), m(901, "本轮摘要")
        )
        assertEquals(
            listOf(900L, 901L, 1L, 2L, 3L, 4L),
            ids(hoistCompactionArtifacts(list))
        )
    }

    @Test
    fun `没有压缩产物时原样返回`() {
        val list = listOf(m(1, "a"), m(2, "b"), m(3, "c"))
        assertEquals(list, hoistCompactionArtifacts(list))
        assertEquals(emptyList<ChatMessageModel>(), hoistCompactionArtifacts(emptyList()))
    }

    @Test
    fun `摘要丢失时只提前 marker 不越界`() {
        // 截断的历史：marker 是最后一条，后面没有摘要
        val list = listOf(m(1, "a"), m(900, COMPACTION_MARKER))
        assertEquals(
            listOf(900L, 1L),
            ids(hoistCompactionArtifacts(list))
        )
    }

    @Test
    fun `二次压缩的两组产物都按原相对顺序提到最前`() {
        val list = listOf(
            m(1, "旧保留尾"),
            m(900, COMPACTION_MARKER), m(901, "旧摘要"),
            m(2, "新工具A"), m(3, "新工具B"),
            m(902, COMPACTION_MARKER), m(903, "新摘要")
        )
        assertEquals(
            listOf(900L, 901L, 902L, 903L, 1L, 2L, 3L),
            ids(hoistCompactionArtifacts(list))
        )
    }

    @Test
    fun `连续两个 marker 不把下一条重复计入`() {
        // 上一次压缩摘要缺失：900 后面紧跟又是一个 marker，不能把 902 当成 900 的摘要提两次
        val list = listOf(
            m(1, "a"),
            m(900, COMPACTION_MARKER),
            m(902, COMPACTION_MARKER),
            m(903, "摘要")
        )
        val out = ids(hoistCompactionArtifacts(list))
        assertEquals("不得出现重复", out.size, out.toSet().size)
        assertEquals(listOf(900L, 902L, 903L, 1L), out)
    }

    @Test
    fun `id 全为零的未落库消息也按下标正确切分`() {
        // 未落库消息的常见形态：id 全为 0。若按 id 判定会把不相干的消息误当压缩产物。
        val dup = { c: String -> ChatMessageModel(0L, MessageRole.USER, "我", "10:00", c) }
        val list = listOf(dup("x"), dup(COMPACTION_MARKER), dup("摘要"), dup("y"))
        assertEquals(
            listOf(COMPACTION_MARKER, "摘要", "x", "y"),
            hoistCompactionArtifacts(list).map { it.content }
        )
    }
}