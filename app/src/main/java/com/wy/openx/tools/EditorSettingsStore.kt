package com.wy.openx.tools

import android.content.Context

/**
 * 文件查看器（Sora 编辑器）设置的轻量持久化。
 *
 * 与 [TerminalSettingsStore] 同构：独立 SharedPreferences 文件，改动即时生效并保留到下次打开文件。
 * 只作用于文件查看器的 Sora 编辑器，不影响终端、聊天等其它页面。
 */
class EditorSettingsStore(context: Context) {

    private val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 正文字号（sp）。代码阅读场景，取 8~24。 */
    var fontSizeSp: Int
        get() = sp.getInt(KEY_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        set(value) {
            sp.edit().putInt(KEY_FONT_SIZE_SP, value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)).apply()
        }

    /** 绘制软换行处的箭头标记（编辑器已开启换行时才可见）。 */
    var showWrapArrow: Boolean
        get() = sp.getBoolean(KEY_SHOW_WRAP_ARROW, DEFAULT_SHOW_WRAP_ARROW)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_WRAP_ARROW, value).apply()
        }

    /** 绘制行首/行内/行尾空白与空行占位符。 */
    var showWhitespace: Boolean
        get() = sp.getBoolean(KEY_SHOW_WHITESPACE, DEFAULT_SHOW_WHITESPACE)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_WHITESPACE, value).apply()
        }

    /** 显示光标行列及选中字数（编辑器底部状态栏）。 */
    var showEditorPosition: Boolean
        get() = sp.getBoolean(KEY_SHOW_EDITOR_POSITION, true)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_EDITOR_POSITION, value).apply()
        }

    var showEditorStatusBar: Boolean
        get() = sp.getBoolean(KEY_SHOW_EDITOR_STATUS_BAR, true)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_EDITOR_STATUS_BAR, value).apply()
        }

    companion object {
        private const val PREF_NAME = "openx_editor_settings"

        const val DEFAULT_FONT_SIZE_SP = 12
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 24

        const val DEFAULT_SHOW_WRAP_ARROW = false
        const val DEFAULT_SHOW_WHITESPACE = false
        const val DEFAULT_SHOW_EDITOR_POSITION = true

        private const val KEY_FONT_SIZE_SP = "font_size_sp"
        private const val KEY_SHOW_WRAP_ARROW = "show_wrap_arrow"
        private const val KEY_SHOW_WHITESPACE = "show_whitespace"
        private const val KEY_SHOW_EDITOR_POSITION = "show_editor_position"
        private const val KEY_SHOW_EDITOR_STATUS_BAR = "show_editor_status_bar"
    }
}