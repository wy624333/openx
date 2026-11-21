package com.wy.openx.ui.navigation

sealed class AppScreen {
    data object Console : AppScreen()
    data object Settings : AppScreen()
    data object Terminal : AppScreen()
    data object MemoryManage : AppScreen()
    data object SkillManage : AppScreen()
    data object PluginManage : AppScreen()
    // [PLUGIN-MOD] 插件自定义界面（Compose DSL）
    data object PluginUi : AppScreen()
    data object QuantTrade : AppScreen()
    data object McpManage : AppScreen()
    data object Browser : AppScreen()
    /** 自动化工作流管理（聊天页顶栏「浏览器」与「量化交易」之间的图标入口）。 */
    data object Workflow : AppScreen()
    data object Git : AppScreen()
    /** Git 凭据与提交署名（Git 页顶栏钥匙入口）。 */
    data object GitCredentials : AppScreen()
    /** 历史：已归档会话列表。 */
    data object History : AppScreen()
    /** 偏好设置：自动归档等行为偏好。 */
    data object Preferences : AppScreen()
    /** Token 用量统计（设置页「用量统计」入口）。 */
    data object TokenStats : AppScreen()
    /** 运行日志（设置页「关于 → 运行日志」入口）。 */
    data object Logs : AppScreen()
    /** 应用权限授权（设置页入口）。 */
    data object Permissions : AppScreen()
    /** 存储占用（设置页入口）。 */
    data object StorageUsage : AppScreen()
    /** 设置 → 提示词：内置 AI 行为规则的查看 / 编辑 / 启停。 */
    data object Prompts : AppScreen()
    /** 设置 → 关于 → 版本：关于页（原版本弹窗改为子页）。 */
    data object About : AppScreen()
}
