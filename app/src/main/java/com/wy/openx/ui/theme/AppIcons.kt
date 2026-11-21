package com.wy.openx.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义图标集合。
 *
 * 这里放 Material Icons 里没有、但需要
 * 用 `ImageVector` 手写路径，避免引入额外依赖（如 lucide 图标库）。
 */
object AppIcons {

    /**
     * lucide `brain`（描边风格的双脑叶大脑）——用于「思考强度」。
     *
     * 
     * `acp_config_button.dart` 与 `chat_input_agent_menus.dart`），而 Material 的
     * `Psychology` 是「实心大脑 + 侧脸」剪影，观感差别明显。
     *
     * 路径数据逐条对应 lucide brain 的 SVG（`viewBox="0 0 24 24"`、`stroke-width=2`、
     * round cap/join、`fill="none"`），按 Compose `PathBuilder` 逐段转换而来。
     */
    val Brain: ImageVector by lazy {
        ImageVector.Builder(
            name = "Brain",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // M12 18V5
                moveTo(12f, 18f)
                verticalLineTo(5f)
                // M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4
                moveTo(15f, 13f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, -4f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, 4f)
                // M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5
                moveTo(17.598f, 6.5f)
                arcTo(3f, 3f, 0f, true, false, 12f, 5f)
                arcToRelative(3f, 3f, 0f, true, false, -5.598f, 1.5f)
                // M17.997 5.125a4 4 0 0 1 2.526 5.77
                moveTo(17.997f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, true, 2.526f, 5.77f)
                // M18 18a4 4 0 0 0 2-7.464
                moveTo(18f, 18f)
                arcToRelative(4f, 4f, 0f, false, false, 2f, -7.464f)
                // M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517
                moveTo(19.967f, 17.483f)
                arcTo(4f, 4f, 0f, true, true, 12f, 18f)
                arcToRelative(4f, 4f, 0f, true, true, -7.967f, -0.517f)
                // M6 18a4 4 0 0 1-2-7.464
                moveTo(6f, 18f)
                arcToRelative(4f, 4f, 0f, false, true, -2f, -7.464f)
                // M6.003 5.125a4 4 0 0 0-2.526 5.77
                moveTo(6.003f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, false, -2.526f, 5.77f)
            }
        }.build()
    }
}
