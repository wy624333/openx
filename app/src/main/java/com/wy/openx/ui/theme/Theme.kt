package com.wy.openx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6EA8FE),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF252E48),
    onPrimaryContainer = Color(0xFFC8D8F0),
    secondary = Color(0xFF6EA8FE),
    onSecondary = Color(0xFF0B1020),
    tertiary = Color(0xFF4ADE80),
    background = Color(0xFF1B1E2E),
    onBackground = Color(0xFFD1D5DB),
    surface = Color(0xFF232639),
    onSurface = Color(0xFFD1D5DB),
    surfaceVariant = Color(0xFF2D3047),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF383B52),
    error = Color(0xFFF87171),
    onError = Color(0xFF0B1020)
)

/** 暖白主题 — 柔和米白 */
private val WarmWhiteScheme = lightColorScheme(
    primary = Color(0xFFB88A6A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5EDE4),
    onPrimaryContainer = Color(0xFF4A3528),
    secondary = Color(0xFFA07A60),
    onSecondary = Color.White,
    tertiary = Color(0xFF8FA08A),
    background = Color(0xFFFEFBF7),
    onBackground = Color(0xFF2C221C),
    surface = Color.White,
    onSurface = Color(0xFF2C221C),
    surfaceVariant = Color(0xFFF3F0EB),
    onSurfaceVariant = Color(0xFF5E5349),
    outline = Color(0xFFE0D8D0),
    error = Color(0xFFD9534F),
    onError = Color.White
)

/**
 * 科技蓝浅色变体 — 与原「科技蓝」深色（[DarkScheme]）同色系，为「主题模式」补齐的对侧。
 * 原「科技蓝」只有深色一套，主题模式引入后必须补浅色，否则选浅色模式仍是深蓝底。
 */
private val TechBlueLightScheme = lightColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A5F),
    secondary = Color(0xFF4F8DF7),
    onSecondary = Color.White,
    tertiary = Color(0xFF16A34A),
    background = Color(0xFFF2F5FA),
    onBackground = Color(0xFF1F2937),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFE7EDF6),
    onSurfaceVariant = Color(0xFF5B6577),
    outline = Color(0xFFD4DCE8),
    error = Color(0xFFDC2626),
    onError = Color.White
)

/**
 * 浅米主题 — 新版 Claude 对话界面复刻配色
 * 页面背景 #F5F5F0、卡片 #FFFFFF、用户气泡 #F0E6DC、
 * 强调 #D4A574、分隔线 #E8E4DD、主文字 #1A1A1A、次要文字 #666666
 */
private val ClaudeLightScheme = lightColorScheme(
    primary = Color(0xFFD4A574),
    onPrimary = Color(0xFF2A2118),
    primaryContainer = Color(0xFFF0E6DC),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFFB88A6A),
    onSecondary = Color.White,
    tertiary = Color(0xFF2E8B5F),
    background = Color(0xFFF5F5F0),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFFAFAF5),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFE8E4DD),
    error = Color(0xFFD9534F),
    onError = Color.White
)

/**
 * 简约白主题 — 纯白底 + 中性灰点缀。
 *
 * 与其它配色的关键差别：按钮要呈「浅灰填充」，而 [primary] 还要承担 124 处
 * 文字/图标用色（[Accent]）与开关等控件，必须保持可读性。因此这里：
 * - [primary] 用可读的中性灰 #6B7280（文字/图标/控件）；
 * - 浅灰按钮交给语义色 [ButtonBg]/[ButtonSoftBg]（见下方），不占用 [primary]。
 * 面板/用户气泡用浅灰 #F0F1F3，分隔线 #E3E4E6，整体只保留黑白灰。
 */
private val MinimalWhiteLightScheme = lightColorScheme(
    primary = Color(0xFF6B7280),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F1F3),
    onPrimaryContainer = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFFB9BEC5),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEEF0),
    onSecondaryContainer = Color(0xFF2A2D31),
    tertiary = Color(0xFF4C9A6A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F2EB),
    onTertiaryContainer = Color(0xFF1E4D33),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = Color(0xFF6E7379),
    outline = Color(0xFFE3E4E6),
    outlineVariant = Color(0xFFEDEEF0),
    error = Color(0xFFD9534F),
    onError = Color.White,
    surfaceTint = Color(0xFF6B7280),
    inverseSurface = Color(0xFF2F3236),
    inverseOnSurface = Color(0xFFF2F3F4),
    scrim = Color.Black
)

/** 简约白深色变体 — 中性黑灰底，为「主题模式」补齐的对侧。 */
private val MinimalWhiteDarkScheme = darkColorScheme(
    primary = Color(0xFFB4B9C0),
    onPrimary = Color(0xFF202327),
    primaryContainer = Color(0xFF2B2E33),
    onPrimaryContainer = Color(0xFFE8EAEC),
    inversePrimary = Color(0xFF5F6670),
    secondary = Color(0xFF8E949B),
    onSecondary = Color(0xFF202327),
    secondaryContainer = Color(0xFF31353A),
    onSecondaryContainer = Color(0xFFE3E5E8),
    tertiary = Color(0xFF6FBF92),
    onTertiary = Color(0xFF102A1B),
    tertiaryContainer = Color(0xFF1C3A29),
    onTertiaryContainer = Color(0xFFC6EBD6),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE6E7E9),
    surface = Color(0xFF181A1D),
    onSurface = Color(0xFFE6E7E9),
    surfaceVariant = Color(0xFF232629),
    onSurfaceVariant = Color(0xFFA3A8AE),
    outline = Color(0xFF373B40),
    outlineVariant = Color(0xFF2A2D31),
    error = Color(0xFFF08A85),
    onError = Color(0xFF2A1513),
    surfaceTint = Color(0xFFB4B9C0),
    inverseSurface = Color(0xFFE6E7E9),
    inverseOnSurface = Color(0xFF232629),
    scrim = Color.Black
)

/** 浅米主题深色变体 — 暖黑底色，随系统深色模式切换 */
private val ClaudeDarkScheme = darkColorScheme(
    primary = Color(0xFFD4A574),
    onPrimary = Color(0xFF2A2118),
    primaryContainer = Color(0xFF3A3228),
    onPrimaryContainer = Color(0xFFF0E6DC),
    secondary = Color(0xFFB88A6A),
    onSecondary = Color(0xFF2A2118),
    tertiary = Color(0xFF4FC3A1),
    background = Color(0xFF1E1E1A),
    onBackground = Color(0xFFE8E4DD),
    surface = Color(0xFF262620),
    onSurface = Color(0xFFE8E4DD),
    surfaceVariant = Color(0xFF2B2B25),
    onSurfaceVariant = Color(0xFF9A968D),
    outline = Color(0xFF383830),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A2118)
)

/**
 * 暖白深色变体 — 暖黑底 + 暖棕点缀，与原暖白浅色同族。
 * 「主题模式」引入后每个配色都需要浅/深两套，此为暖白补齐的对侧。
 */
private val WarmWhiteDarkScheme = darkColorScheme(
    primary = Color(0xFFC99B79),
    onPrimary = Color(0xFF2A211A),
    primaryContainer = Color(0xFF3A3128),
    onPrimaryContainer = Color(0xFFF0E4D8),
    secondary = Color(0xFFA98B72),
    onSecondary = Color(0xFF2A211A),
    tertiary = Color(0xFF93A88D),
    background = Color(0xFF211C18),
    onBackground = Color(0xFFEDE4DA),
    surface = Color(0xFF2A2420),
    onSurface = Color(0xFFEDE4DA),
    surfaceVariant = Color(0xFF332C27),
    onSurfaceVariant = Color(0xFFB3A79A),
    outline = Color(0xFF453C35),
    error = Color(0xFFF08A85),
    onError = Color(0xFF3A211E)
)

var currentThemeColors by mutableStateOf(DarkScheme)

/**
 * 按钮专用语义色。为 null 时回落到主题原有取值（[Accent]/[onPrimary]），
 * 即除「简约白」外其它配色的按钮外观与之前完全一致。
 *
 * 为什么需要单独一层：简约白要求「按钮浅灰」，但 [Accent] 同时是大量文字/图标
 * 与开关控件的颜色，直接把它改浅会让文字失去可读性。所以浅灰只作用于按钮填充。
 */
internal data class ButtonPalette(
    val bg: Color,
    val fg: Color,
    val softBg: Color,
    val softFg: Color
)

private var currentButtonPalette by mutableStateOf<ButtonPalette?>(null)

/** 实心按钮填充色。 */
val ButtonBg: Color get() = currentButtonPalette?.bg ?: Accent

/** 实心按钮前景色（默认 contentColor 场景）。 */
val ButtonFg: Color get() = currentButtonPalette?.fg ?: currentThemeColors.onPrimary

/**
 * 实心按钮上**显式**文字/图标的前景色。
 *
 * 与 [ButtonFg] 分开是因为旧代码里有若干按钮把内容色写死成 [Color.White]
 * （如「保存」），这些按钮在其它主题下必须保持白色，不能被 onPrimary 改写。
 */
val ButtonFgOnFill: Color get() = currentButtonPalette?.fg ?: Color.White

/** 弱化（浅底）按钮填充色，对应旧的 Accent.copy(alpha = 0.15f)。 */
val ButtonSoftBg: Color get() = currentButtonPalette?.softBg ?: Accent.copy(alpha = 0.15f)

/** 弱化（浅底）按钮前景色。 */
val ButtonSoftFg: Color get() = currentButtonPalette?.softFg ?: Accent

/** 玻璃色主题标记：仅「玻璃色」为 true，供界面层做玻璃渐变背景/元素修饰。 */
var isGlassTheme by mutableStateOf(false)

/**
 * 背景图是否激活（启用 + 有有效图源）。
 *
 * 与 [isGlassTheme] 同一套路：各页面原先铺不透明的 [BgPrimary] 全屏底色，
 * 背景图会被盖住。激活时页面底色改用 [PageBg]（透明或半透明），透出根部的背景层。
 * 由 [AppBackgroundLayer] 的调用方（MainActivity）在渲染后写回。
 */
var isBackgroundImageActive by mutableStateOf(false)

/**
 * 页面底色：背景图激活或玻璃色主题时为透明（透出根背景层），否则用主题背景色。
 *
 * 这是给**全屏页面根容器**用的。卡片/面板等仍用 [Panel]/[PanelAlt]——
 * 它们本就有半透明叠加的观感需求，且若也透明会与背景图糊在一起。
 *
 * 为什么不直接改 [BgPrimary]：它同时被大量非根容器引用（卡片、条形背景等），
 * 一律透明会让内容失去层次；只有根容器透出根背景才是想要的效果。
 */
val PageBg: Color
    get() = if (isBackgroundImageActive || isGlassTheme) Color.Transparent
            else currentThemeColors.background

/**
 * 玻璃色主题 — 浅色玻璃质感：背景/卡片/按钮呈「中间透明玻璃、两边渐变浅色」，
 * 文字一律深色。主色取清透青蓝，渐变浅色往同色系走，保证可读性。
 */
private val GlassScheme = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color(0xFF0B242B),
    primaryContainer = Color(0xFFD8EAEF),
    onPrimaryContainer = Color(0xFF14333C),
    secondary = Color(0xFF3B87A0),
    onSecondary = Color(0xFF0B242B),
    tertiary = Color(0xFF2F8F66),
    background = Color(0xFFE8F2F5),
    onBackground = Color(0xFF14333C),
    surface = Color(0xFFF4F9FA),
    onSurface = Color(0xFF14333C),
    surfaceVariant = Color(0xFFEAF2F4),
    onSurfaceVariant = Color(0xFF5A7680),
    outline = Color(0xFFC4D8DD),
    error = Color(0xFFC74343),
    onError = Color(0xFFFFFFFF)
)

/** 玻璃色深色变体 — 深青玻璃底，为「主题模式」补齐的对侧。 */
private val GlassDarkScheme = darkColorScheme(
    primary = Color(0xFF4FB3CC),
    onPrimary = Color(0xFF062028),
    primaryContainer = Color(0xFF1A3038),
    onPrimaryContainer = Color(0xFFCDE7EE),
    secondary = Color(0xFF5AA0B8),
    onSecondary = Color(0xFF062028),
    tertiary = Color(0xFF4FBF92),
    background = Color(0xFF0F191C),
    onBackground = Color(0xFFD4E5EA),
    surface = Color(0xFF152329),
    onSurface = Color(0xFFD4E5EA),
    surfaceVariant = Color(0xFF1B2B32),
    onSurfaceVariant = Color(0xFF8FAAB2),
    outline = Color(0xFF2B3E46),
    error = Color(0xFFEE716C),
    onError = Color(0xFF062028)
)

/**
 * 页面背景玻璃渐变（水平）：两侧渐变浅色 → 中间透明玻璃高光。
 * 用于玻璃色主题的根背景；其他主题不引用。
 */
val GlassPageBrush: Brush
    get() = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Color(0xFF9CC9D6).copy(alpha = 0.20f),
            0.16f to Color(0xFF9CC9D6).copy(alpha = 0.12f),
            0.50f to Color.White.copy(alpha = 0.05f),
            0.84f to Color(0xFF9CC9D6).copy(alpha = 0.12f),
            1f to Color(0xFF9CC9D6).copy(alpha = 0.20f)
        )
    )

/**
 * 按钮/卡片底色玻璃渐变（水平）：两侧浅色渐变 → 中间透明玻璃高光。
 * 中间靠高光提亮、两侧靠浅色彩，文字用深色在玻璃上清晰可读。
 */
val GlassElementBrush: Brush
    get() = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Color(0xFF9CC9D6).copy(alpha = 0.35f),
            0.50f to Color.White.copy(alpha = 0.90f),
            1f to Color(0xFF9CC9D6).copy(alpha = 0.35f)
        )
    )

/**
 * 把 [themeName]（配色）+ [themeMode]（明暗）解析成「实际配色族 + 是否深色」。
 *
 * 抽成纯函数是为了能单测：配色矩阵的边界很容易改错且难用肉眼验证。
 *
 * 两个维度正交：themeName 选配色族，themeMode 选浅/深。[themeMode] 语义：
 * - `"light"`：强制浅色变体；
 * - `"dark"`：强制深色变体；
 * - `"system"`：**跟随系统深浅**。
 *
 * 为什么 system 下所有配色都跟随系统、而不是各自"固有明暗"：
 * 当前 5 个配色（系统默认/科技蓝/暖白/玻璃色/简约白）各自都有浅/深两套，
 * 配色名只描述风格、不用于锁定明暗（「简约白」的深色变体也是可用的）。
 * 没有理由让某个配色无视用户的系统深浅设置。早期版本曾给「科技蓝」定成固有深色
 *（当时列表里还有「亮色」「极简暗」这类名字自带明暗的项），那批项已删除，
 * 该规则失去适用对象，反而会让用户选了「系统默认」却得到深色（用户反馈。
 * 详见轮次一百四十四）。
 *
 * @return 配色族 id（用于映射到具体 ColorScheme）+ 是否深色。
 */
internal data class ResolvedTheme(val family: String, val isDark: Boolean)

internal fun resolveTheme(themeName: String, themeMode: String, systemDark: Boolean): ResolvedTheme {
    // CrashActivity 传 "暗色"（不在设置列表里）。崩溃页**恒深色**，且不受 themeMode 影响：
    // 白屏在崩溃场景既刺眼又掩盖信息，这个页面不允许被任何设置改成浅色。
    // 这是唯一"固有明暗"的配色，故单独短路返回。
    if (themeName == "暗色") return ResolvedTheme("dark", isDark = true)

    val family = when (themeName) {
        "科技蓝" -> "techblue"
        "暖白" -> "warmwhite"
        "玻璃色" -> "glass"
        "简约白" -> "minimalwhite"
        // 「系统默认」= 浅米配色。
        // 已删除的「浅米」「亮色」「极简亮」「极简暗」也落在这里（老数据残留值）；
        // 「跟随系统」是 2.5.1 之前的旧配色档名，同样落这里。
        // 都渲染为浅米配色而非崩溃；SettingsStore 读取时会把这些值统一归一化为「系统默认」。
        else -> "claude"
    }
    return when (themeMode) {
        "light" -> ResolvedTheme(family, isDark = false)
        "dark" -> ResolvedTheme(family, isDark = true)
        else -> ResolvedTheme(family, isDark = systemDark)
    }
}

/** 把 [resolveTheme] 的结果映射到具体的 Material3 配色方案。 */
private fun ResolvedTheme.toColorScheme(): ColorScheme = when (family) {
    "techblue" -> if (isDark) DarkScheme else TechBlueLightScheme
    "warmwhite" -> if (isDark) WarmWhiteDarkScheme else WarmWhiteScheme
    "glass" -> if (isDark) GlassDarkScheme else GlassScheme
    "minimalwhite" -> if (isDark) MinimalWhiteDarkScheme else MinimalWhiteLightScheme
    "dark" -> DarkScheme
    else -> if (isDark) ClaudeDarkScheme else ClaudeLightScheme
}

/**
 * 浅灰按钮语义色：仅「简约白」有独立取值，其它配色返回 null（沿用 Accent 系）。
 * 深色变体也保持"按钮比背景略亮"的层次，而不是纯黑。
 */
private fun ResolvedTheme.buttonPaletteOrNull(): ButtonPalette? = when {
    family != "minimalwhite" -> null
    isDark -> ButtonPalette(
        bg = Color(0xFF34373C),
        fg = Color(0xFFEDEEEF),
        softBg = Color(0xFF25282C),
        softFg = Color(0xFFC6CACF)
    )
    else -> ButtonPalette(
        bg = Color(0xFFE6E7E9),
        fg = Color(0xFF1F2328),
        softBg = Color(0xFFF0F1F3),
        softFg = Color(0xFF3A3F45)
    )
}

@Composable
fun AgentConsoleTheme(
    themeName: String = "系统默认",
    themeMode: String = "system",
    /** 界面整体缩放倍率（外观设置的「界面大小」）。1.0 = 原始尺寸。 */
    uiScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val resolved = resolveTheme(themeName, themeMode, isSystemInDarkTheme())
    val scheme = resolved.toColorScheme()
    isGlassTheme = themeName == "玻璃色"
    currentThemeColors = scheme
    currentButtonPalette = resolved.buttonPaletteOrNull()

    // 界面缩放：只改 LocalDensity 的 density，不动 fontScale——
    //   dp  → px 用 density，sp → px 用 density * fontScale，所以乘 density 会让 dp 与 sp
    //   同比例放大，正是「整界面等比缩放」想要的；而保留系统 fontScale，无障碍字号设置仍然生效。
    //   也不去改 Configuration：screenWidthDp 保持系统值，布局会按真实屏幕重新分配，
    //   小屏上不会把图标和文字挤成一团。
    val base = LocalDensity.current
    val scaled = if (uiScale == 1.0f) base else Density(base.density * uiScale, base.fontScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content
        )
    }
}

// 兼容旧的全局变量引用
val BgPrimary get() = currentThemeColors.background
val BgSecondary get() = currentThemeColors.surface
val Panel get() = currentThemeColors.surfaceVariant
val PanelAlt get() = currentThemeColors.primaryContainer
val Accent get() = currentThemeColors.primary
val AccentStrong get() = currentThemeColors.secondary
val Success get() = currentThemeColors.tertiary
val Warning get() = Color(0xFFF59E0B)
val Danger get() = currentThemeColors.error
val TextPrimary get() = currentThemeColors.onBackground
val TextSecondary get() = currentThemeColors.onSurfaceVariant
val Border get() = currentThemeColors.outline

// ===== 新版对话界面语义色（各主题自适应）=====
/** 用户消息气泡背景（浅米主题为 #F0E6DC） */
val UserBubbleBg get() = currentThemeColors.primaryContainer
/** 用户消息气泡文字（浅米主题为 #1A1A1A） */
val UserBubbleFg get() = currentThemeColors.onPrimaryContainer
/** 代码/工具结果块背景（浅米主题 ≈ #F8F8F3） */
val CodeBg get() = currentThemeColors.surfaceVariant.copy(alpha = 0.6f)

/** Git 提交拓扑图泳道调色板（8 色循环，移植自 AiCode-main GitLanePalette）。 */
object GitLanePalette {
    val colors = listOf(
        Color(0xFF2563EB), // 经典蓝
        Color(0xFF16A34A), // 翠绿
        Color(0xFFF59E0B), // 琥珀
        Color(0xFF8B5CF6), // 优雅紫
        Color(0xFF06B6D4), // 青蓝
        Color(0xFFEF4444), // 珊瑚红
        Color(0xFFEC4899), // 亮粉
        Color(0xFFEAB308)  // 柠檬金
    )
}
