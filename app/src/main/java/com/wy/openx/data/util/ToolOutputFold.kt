package com.wy.openx.data.util

/**
 * 工具卡「指令 / 结果」节的行数折叠规则。抽成纯函数是为了能在 JVM 单测里钉住
 * （Compose 层测不了，而这条规则直接决定刷屏型输出会不会顶掉整屏）。
 *
 * 折叠时**留尾部**（留头是给 diff 用的；命令回显、编译日志的结论总在最后，
 * 留头等于把结论藏起来）。
 *
 * 阈值 10 行（用户指定，20 → 15 → 10）。参照一般做法。
 * 工具输出（命令回显、文件列表）通常十行上下就过去了，阈值定高了一点就等于没折叠。
 */
object ToolOutputFold {

    const val LINE_LIMIT = 10

    data class Folded(
        /** 实际要渲染的文本（已按折叠规则截取）。 */
        val visible: String,
        /** 被折叠掉的行数；0 表示无需折叠。 */
        val hiddenCount: Int,
        /** 是否需要显示「展开剩余 N 行 / 收起」切换条。 */
        val collapsible: Boolean
    )

    /**
     * @param text 原始文本；空串按「无内容」处理，返回空结果且不可折叠。
     * @param expanded 用户是否已手动展开。
     * @param limit 行数上限，默认 [LINE_LIMIT]。
     */
    fun fold(text: String, expanded: Boolean, limit: Int = LINE_LIMIT): Folded {
        if (text.isEmpty()) return Folded("", 0, false)
        val lines = text.split("\n")
        // 单行超长（无换行的 JSON 串等）不算「多行」，不触发折叠——否则会误导用户去展开
        if (limit <= 0 || lines.size <= limit) return Folded(text, 0, false)
        val visible = if (expanded) lines else lines.takeLast(limit)
        return Folded(visible = visible.joinToString("\n"), hiddenCount = lines.size - limit, collapsible = true)
    }

    /**
     * 超长输出按**字符**截断时留尾部，返回 null 表示无需截断。
     *
     * 口径与 [fold] 一致：命令回显、编译日志的结论都在最后。
     * 旧实现用 `output.take(maxOutput)`（留头丢尾），把报错栈和「成功」那行扔了——
     * 用户在详情弹窗里看到的就是「一堆日志开头 + 没有结尾」。
     */
    fun keepTail(text: String, limit: Int): String? =
        if (limit <= 0 || text.length <= limit) null else text.takeLast(limit)
}
