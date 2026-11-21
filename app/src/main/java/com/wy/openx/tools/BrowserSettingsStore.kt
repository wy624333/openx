package com.wy.openx.tools

import android.content.Context

/**
 * 浏览器设置的轻量持久化。
 *
 * 承载三项（与内置浏览器底栏「更多设置」抽屉一一对应）：
 * - **浏览器标识（UA 模式）**：[uaMode]/[customUa]；
 * - **夜间模式**：[nightMode]；
 * - **开发者工具（Eruda）**：[devTools]。
 *
 * 与 [EditorSettingsStore] 同构：独立 SharedPreferences 文件，改动即时生效并保留到下次打开浏览器。
 */
class BrowserSettingsStore(context: Context) {

    private val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 浏览器标识模式：见 [UA_DEFAULT] 等常量。
     *
     * 首次读取时**迁移旧字段**：老版本只有 [desktopMode]（布尔），据此映射成
     * [UA_DESKTOP_PC] / [UA_DEFAULT]，保持老用户的原有效果不变。
     */
    var uaMode: String
        get() = sp.getString(KEY_UA_MODE, null)
            ?: if (sp.getBoolean(KEY_DESKTOP_MODE, DEFAULT_DESKTOP_MODE)) UA_DESKTOP_PC else UA_DEFAULT
        set(value) {
            sp.edit().putString(KEY_UA_MODE, value).apply()
        }

    /** 自定义 UA 字符串（[uaMode] = [UA_CUSTOM] 时使用）。 */
    var customUa: String
        get() = sp.getString(KEY_CUSTOM_UA, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CUSTOM_UA, value).apply()
        }

    /**
     * 兼容旧字段：是否桌面端 UA。
     *
     * 仍被 [com.wy.openx.tools.BrowserTool] 的 `desktopMode`/`mobileMode` 使用；
     * 映射为 [UA_DESKTOP_PC] / [UA_DEFAULT]。
     */
    var desktopMode: Boolean
        get() = uaMode == UA_DESKTOP_PC
        set(value) {
            uaMode = if (value) UA_DESKTOP_PC else UA_DEFAULT
        }

    /**
     * 夜间模式：开启后向页面声明深色偏好（`prefers-color-scheme: dark`）并叠加半透明暗色蒙版，
     * 亮底站点整体压暗、原生深色站点直接生效。默认关闭。
     */
    var nightMode: Boolean
        get() = sp.getBoolean(KEY_NIGHT_MODE, false)
        set(value) {
            sp.edit().putBoolean(KEY_NIGHT_MODE, value).apply()
        }

    /**
     * 开发者工具：开启后在页面注入 Eruda 移动端控制台，并启用 WebView 内容调试。
     * 默认关闭。
     */
    var devTools: Boolean
        get() = sp.getBoolean(KEY_DEVTOOLS, false)
        set(value) {
            sp.edit().putBoolean(KEY_DEVTOOLS, value).apply()
        }

    companion object {
        private const val PREF_NAME = "openx_browser_settings"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_UA_MODE = "ua_mode"
        private const val KEY_CUSTOM_UA = "custom_ua"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_DEVTOOLS = "devtools"

        /** 老字段默认值：桌面端（见原注释——本项目浏览器主要用于 AI 抓取与预览）。 */
        const val DEFAULT_DESKTOP_MODE = true

        const val UA_DEFAULT = "default"
        const val UA_ANDROID_PHONE = "android_phone"
        const val UA_ANDROID_TABLET = "android_tablet"
        const val UA_DESKTOP_PC = "desktop_pc"
        const val UA_IPHONE = "iphone"
        const val UA_IPAD = "ipad"
        const val UA_CUSTOM = "custom"
    }
}
