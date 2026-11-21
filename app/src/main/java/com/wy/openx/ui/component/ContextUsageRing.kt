package com.wy.openx.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import kotlin.math.min

/** 圆环外径（触摸区）。比参考实现的 22dp 略大，因为 openx 这行图标是 20dp、间距紧，太小会看不清。 */
val ContextRingSize = 26.dp

/**
 * 上下文用量圆环
 *
 * 视觉规格基于参考实现（外框 / 环本体 / 环粗 / 圆头端点 / 12 点方向顺时针 / 220ms 补间），
 * 但**为可读性做了两处加强**——参考实现那套是给 Flutter 端更宽松的输入栏用的，
 * 直接照搬到 openx 这行 20dp 图标里会「不明显」（用户反馈）：
 * - 环本体 18dp → **20dp**、环粗 1.8dp → **2.4dp**：线太细在深色底上几乎看不见；
 * - 轨道色由 `PanelAlt`（与背景太接近）改为**半透明描边色**，让"没被占用的部分"也能看出是个环。
 *
 * 三段告警语义保留：≥85% 琥珀、≥100% 红，这是这个控件最主要的信息量。
 */
@Composable
fun ContextUsageRing(
    ratio: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val safeRatio = if (ratio.isFinite()) ratio else 0f
    val target = safeRatio.coerceIn(0f, 1f)
    val color = when {
        safeRatio >= 1f -> Color(0xFFFF6B6B)
        safeRatio >= 0.85f -> Color(0xFFF6C760)
        else -> Accent
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 220),
        label = "contextRingProgress"
    )

    Box(
        modifier = modifier
            .size(ContextRingSize)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.pointerInput(onClick, onLongClick) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { onLongClick?.invoke() }
                        )
                    }
                } else Modifier
            )
            .semantics { contentDescription = "上下文用量" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val strokeWidth = 2.4.dp.toPx()
            val radius = (min(size.width, size.height) - strokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                // 轨道用半透明描边色而非 PanelAlt：后者与输入栏底色几乎同色，
                // 导致"环"看不出形状，只剩一小段进度弧孤零零挂着。
                color = Border.copy(alpha = 0.55f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
    }
}
