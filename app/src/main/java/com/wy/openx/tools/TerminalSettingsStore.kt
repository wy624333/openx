package com.wy.openx.tools

import android.content.Context

/**
 * 终端页设置的轻量持久化（SharedPreferences，独立于全局聊天设置）。
 * 数据读写在设置面板与 [TerminalEngine] 之间共享同一份 preferences 文件。
 */
class TerminalSettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("openx_terminal_settings", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = sp.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(value) {
            sp.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)).apply()
        }

    var themeId: String
        get() = sp.getString(KEY_THEME_ID, TerminalThemePreset.TERMIUS_DARK.id)!!
        set(value) {
            sp.edit().putString(KEY_THEME_ID, value).apply()
        }

    /** TerminalEmulator 光标样式：0=块、1=下划线、2=竖线（bar）。 */
    var cursorStyle: Int
        get() = sp.getInt(KEY_CURSOR_STYLE, CURSOR_BLOCK).coerceIn(0, 2)
        set(value) {
            sp.edit().putInt(KEY_CURSOR_STYLE, value.coerceIn(0, 2)).apply()
        }

    var remoteFontSize: Int
        get() = sp.getInt(KEY_REMOTE_FONT_SIZE, DEFAULT_REMOTE_FONT_SIZE).coerceIn(MIN_REMOTE_FONT_SIZE, MAX_REMOTE_FONT_SIZE)
        set(value) {
            sp.edit().putInt(KEY_REMOTE_FONT_SIZE, value.coerceIn(MIN_REMOTE_FONT_SIZE, MAX_REMOTE_FONT_SIZE)).apply()
        }

    var remoteForeground: Int
        get() = sp.getInt(KEY_REMOTE_FOREGROUND, DEFAULT_REMOTE_FOREGROUND)
        set(value) {
            sp.edit().putInt(KEY_REMOTE_FOREGROUND, value).apply()
        }

    var remoteBackground: Int
        get() = sp.getInt(KEY_REMOTE_BACKGROUND, DEFAULT_REMOTE_BACKGROUND)
        set(value) {
            sp.edit().putInt(KEY_REMOTE_BACKGROUND, value).apply()
        }

    companion object {
        const val DEFAULT_FONT_SIZE = 28
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 36

        const val DEFAULT_REMOTE_FONT_SIZE = 24
        const val MIN_REMOTE_FONT_SIZE = 12
        const val MAX_REMOTE_FONT_SIZE = 48
        const val DEFAULT_REMOTE_FOREGROUND = 0xFFE8E8E8.toInt()
        const val DEFAULT_REMOTE_BACKGROUND = 0xFF000000.toInt()
        const val REMOTE_FOREGROUND_BLACK = 0xFF000000.toInt()
        const val REMOTE_BACKGROUND_WHITE = 0xFFFFFFFF.toInt()

        const val CURSOR_BLOCK = 0
        const val CURSOR_UNDERLINE = 1
        const val CURSOR_BAR = 2

        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_CURSOR_STYLE = "cursor_style"
        private const val KEY_REMOTE_FONT_SIZE = "remote_font_size"
        private const val KEY_REMOTE_FOREGROUND = "remote_foreground"
        private const val KEY_REMOTE_BACKGROUND = "remote_background"
    }
}