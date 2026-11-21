package com.wy.openx.tools

/**
 * 终端预设配色主题（筛选 3 套：默认 Termius Dark + Dracula + One Dark）。
 *
 * @param id 唯一标识符，持久化于 [TerminalSettingsStore]
 * @param name 中文名称
 * @param background 背景颜色 ARGB
 * @param foreground 前景默认文字颜色 ARGB
 * @param cursor 光标颜色 ARGB
 * @param ansiColors 16 色标准 ANSI 调色板（0~15：黑、红、绿、黄、蓝、洋红、青、白 及对应亮色）
 */
data class TerminalThemePreset(
    val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansiColors: IntArray = IntArray(16)
) {
    companion object {
        /** 默认主题：经典 Termius 深邃蓝黑底 + 亮绿光标。 */
        val TERMIUS_DARK = TerminalThemePreset(
            id = "termius_dark",
            name = "Termius Dark",
            background = 0xFF141923.toInt(),
            foreground = 0xFFD8DEE9.toInt(),
            cursor = 0xFF35E28B.toInt(),
            ansiColors = intArrayOf(
                0xFF1B222D.toInt(), 0xFFE05252.toInt(), 0xFF35E28B.toInt(), 0xFFF0AF39.toInt(),
                0xFF4390F7.toInt(), 0xFFB362FF.toInt(), 0xFF2ED1D8.toInt(), 0xFFD8DEE9.toInt(),
                0xFF4C566A.toInt(), 0xFFFF6E6E.toInt(), 0xFF5DFFA9.toInt(), 0xFFFFC766.toInt(),
                0xFF6CA8FF.toInt(), 0xFFCA8BFF.toInt(), 0xFF5DF2F9.toInt(), 0xFFECEFF4.toInt()
            )
        )

        /** Dracula：官方标准极客暗黑主题（基于 draculatheme.com 规范）。 */
        val DRACULA = TerminalThemePreset(
            id = "dracula",
            name = "Dracula",
            background = 0xFF282A36.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFF50FA7B.toInt(),
            ansiColors = intArrayOf(
                0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
                0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()
            )
        )

        /** One Dark：Atom / VSCode 经典暗色。 */
        val ONE_DARK = TerminalThemePreset(
            id = "one_dark",
            name = "One Dark",
            background = 0xFF282C34.toInt(),
            foreground = 0xFFABB2BF.toInt(),
            cursor = 0xFF528BFF.toInt(),
            ansiColors = intArrayOf(
                0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
                0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt()
            )
        )

        val ALL_PRESETS = listOf(TERMIUS_DARK, DRACULA, ONE_DARK)

        fun findById(id: String): TerminalThemePreset =
            ALL_PRESETS.firstOrNull { it.id == id } ?: TERMIUS_DARK
    }
}