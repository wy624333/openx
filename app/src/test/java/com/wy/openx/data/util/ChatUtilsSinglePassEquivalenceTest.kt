package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单遍扫描重写必须与旧的「findAll + 两次 replace」逐字符等价。
 *
 * [ChatUtils.extractThinkingContent] 为消除长思考链流式下的每帧三遍全文扫描改成了单遍实现，
 * 语义上属于「等价重写」——一旦某类标签形态判定不同，表现就是思考/正文串位或丢内容，
 * 且只在特定网关的标记形态下出现，很难靠肉眼发现。这里用旧实现作参照逐一对撞。
 */
class ChatUtilsSinglePassEquivalenceTest {

    private val openTag = "<think(?:ing)?(?::[A-Za-z0-9_\\-]*)?>"
    private val closeTag = "</think(?:ing)?(?::[A-Za-z0-9_\\-]*)?>"
    private val capture = "$openTag([\\s\\S]*?)($closeTag|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val loneClose = Regex(closeTag)

    /** 改动前的实现，作为等价性参照。 */
    private fun reference(content: String): Pair<String, String> {
        val thinking = capture.findAll(content).joinToString("\n") { it.groupValues[1].trim() }
        val body = content.replace(capture, "").replace(loneClose, "").trim()
        return Pair(body, thinking)
    }

    @Test
    fun `新旧实现在各类标签形态下结果一致`() {
        val samples = listOf(
            "",
            "没有标签的普通正文",
            "<thinking>只思考没正文</thinking>",
            "<thinking>思考一</thinking>正文一",
            "开头正文<thinking>思考</thinking>结尾正文",
            "<thinking>思考一</thinking>正文一<thinking>思考二</thinking>正文二",
            // 流式中尚未闭合：捕获到文本末尾
            "<thinking>思考写到一半",
            "正文<thinking>思考写到一半",
            // 混元等网关的带后缀形态
            "<think:6124c78e>思考内容</think:6124c78e>",
            "<thinking:abc>思考</thinking>",
            "<think>x</think:abc>",
            // 孤立闭合标签（某些网关只发闭标签）
            "正文</think:6124c78e>更多正文",
            "</thinking>",
            "<thinking>思考</thinking></thinking>正文",
            // search 标签不在本函数处理范围，确认不受影响
            "<search>检索</search>正文",
            // 空白与换行
            "<thinking>\n  多行\n  思考\n</thinking>\n\n  正文  ",
            "<thinking>   </thinking>正文",
            // 嵌套与畸形
            "<thinking><thinking>内层</thinking></thinking>",
            "<thinking>甲<thinking>乙",
            "a<thinking>b</thinking>c<thinking>d",
            // 长内容（跨 StringBuilder 预分配路径）
            "思考".repeat(2000) + "<thinking>" + "内容".repeat(3000) + "</thinking>正文"
        )
        for (s in samples) {
            assertEquals(
                "样本不一致：${s.take(60)}",
                reference(s),
                ChatUtils.extractThinkingContent(s)
            )
        }
    }

    @Test
    fun `流式逐字增长时每一帧都与旧实现一致`() {
        // 流式期间这条路径每 150ms 跑一次、且必然走不到缓存，逐帧比对才有意义
        val full = "先想一下。\n\n<thinking>步骤一\n步骤二\n步骤三</thinking>\n\n结论是：可以。"
        var i = 0
        while (i <= full.length) {
            val partial = full.substring(0, i)
            assertEquals("前 $i 字符不一致", reference(partial), ChatUtils.extractThinkingContent(partial))
            i += 1
        }
    }

    @Test
    fun `超长文本不缓存但结果仍正确`() {
        // 超过缓存上限的 content 走直通路径，结果不能变
        val big = "<thinking>" + "x".repeat(300_000) + "</thinking>正文"
        assertEquals(reference(big), ChatUtils.extractThinkingContent(big))
    }
}
