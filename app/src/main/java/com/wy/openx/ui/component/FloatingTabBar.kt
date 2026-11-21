package com.wy.openx.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底部悬浮 tab 栏的单个 tab 项：图标 + 文案。 */
data class FloatingTabItem(
    val icon: ImageVector,
    val label: String
)

/**
 * 底部悬浮液态玻璃 Tab 栏：
 * - 选中态毛玻璃指示器平滑滑动，点击 Tab 项切换；
 * - 长按底栏左右拖拽可临时移动指示器，松手吸附回最近项；
 * - 颜色全部取自 OpenX `currentThemeColors` 派生色，浅/深主题跟随系统自动切换。
 */
@Composable
fun FloatingTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    items: List<FloatingTabItem>,
    maskColor: Color,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.35f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabbar-content-alpha"
    )

    val isLight = BgPrimary.luminance() > 0.5f
    val surfaceVariantColor = Panel
    val surfaceColor = BgSecondary
    val outlineVariantColor = Border
    val pageBackgroundColor = BgPrimary
    val primaryContainerColor = Panel
    val primaryColor = Accent

    val glassBackgroundBrush = remember(isLight, surfaceVariantColor, surfaceColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(surfaceColor.copy(alpha = 0.94f), surfaceColor.copy(alpha = 0.90f))
            )
        } else {
            Brush.verticalGradient(
                listOf(surfaceVariantColor.copy(alpha = 0.92f), surfaceColor.copy(alpha = 0.88f))
            )
        }
    }

    val glassBorderBrush = remember(isLight, outlineVariantColor, pageBackgroundColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.14f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    outlineVariantColor.copy(alpha = 0.55f),
                    outlineVariantColor.copy(alpha = 0.20f),
                    pageBackgroundColor.copy(alpha = 0.60f)
                )
            )
        }
    }

    val tabBounds = remember { mutableStateMapOf<Int, Rect>() }
    val currentSelected by rememberUpdatedState(selected)
    val density = LocalDensity.current

    var dragX by remember { mutableFloatStateOf(Float.NaN) }
    val isDragging = !dragX.isNaN()

    val indicatorTarget = if (isDragging) dragX else tabBounds[selected]?.left ?: 0f

    val indicatorX by animateFloatAsState(
        targetValue = indicatorTarget,
        animationSpec = if (isDragging) tween(0) else spring(
            dampingRatio = 0.76f,
            stiffness = 380f
        ),
        label = "indicator-x"
    )

    val stretchScale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        stretchScale.snapTo(1.10f)
        stretchScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(
                Brush.verticalGradient(
                    listOf(maskColor.copy(alpha = 0f), maskColor.copy(alpha = 0.98f))
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .graphicsLayer { alpha = contentAlpha }
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color(0xFF040A14).copy(alpha = 0.35f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color(0xFF040A14).copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(50))
                .background(glassBackgroundBrush)
                .border(1.dp, glassBorderBrush, RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .pointerInput(items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val w = tabBounds.values.firstOrNull()?.width ?: 0f
                            dragX = start.x - w / 2f
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = change.position.x
                            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f
                            if (tabWidth > 0f) {
                                val minX = tabBounds.values.minOfOrNull { it.left } ?: 0f
                                val maxX = (tabBounds.values.maxOfOrNull { it.right } ?: tabWidth) - tabWidth
                                dragX = (x - tabWidth / 2f).coerceIn(minX, maxX)
                            }
                            val target = items.indices.minByOrNull { abs((tabBounds[it]?.center?.x ?: x) - x) }
                            if (target != null && target != currentSelected) onSelect(target)
                        },
                        onDragEnd = { dragX = Float.NaN },
                        onDragCancel = { dragX = Float.NaN }
                    )
                }
        ) {
            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f

            if (tabWidth > 0f) {
                val indicatorWidthDp = with(density) { tabWidth.toDp() }

                val indicatorBrush = remember(isLight, primaryContainerColor) {
                    if (isLight) {
                        Brush.verticalGradient(
                            listOf(primaryColor.copy(alpha = 0.95f), primaryColor.copy(alpha = 0.85f))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(primaryColor.copy(alpha = 0.90f), primaryColor.copy(alpha = 0.72f))
                        )
                    }
                }

                val indicatorBorderBrush = remember(isLight, primaryColor) {
                    Brush.verticalGradient(
                        listOf(
                            if (isLight) Color.Black.copy(alpha = 0.08f) else primaryColor.copy(alpha = 0.35f),
                            if (isLight) Color.Black.copy(alpha = 0.02f) else primaryColor.copy(alpha = 0.08f)
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(indicatorX.roundToInt(), 0) }
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.05f else stretchScale.value
                            scaleY = if (isDragging) 0.98f else (2f - stretchScale.value).coerceIn(0.95f, 1f)
                        }
                        .width(indicatorWidthDp)
                        .height(44.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(50),
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(RoundedCornerShape(50))
                        .background(indicatorBrush)
                        .border(1.dp, indicatorBorderBrush, RoundedCornerShape(50))
                )
            }

            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val tabScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "tab-press-scale"
                    )

                    val fgColor by animateColorAsState(
                        // 选中态前景色必须用 onPrimary，**不能**用 Accent。
                        //
                        // 选中项的毛玻璃指示器底色就是 Accent（见上面 indicatorBrush），
                        // 前景再用 Accent = 同色叠同色，对比度 1.00:1 —— 文字图标完全看不见
                        //（用户反馈「配置/模型 两个字在按钮背景色下面」）。onPrimary 是
                        // 专为叠在 primary/Accent 上设计的对比色：浅米 7.1:1、科技蓝 7.8:1。
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else TextSecondary,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tab-fg-color"
                    )

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab-icon-scale"
                    )

                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { tabBounds[index] = it.boundsInParent() }
                            .widthIn(min = 84.dp)
                            .graphicsLayer {
                                scaleX = tabScale
                                scaleY = tabScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(index) }
                            )
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            tint = fgColor
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            style = androidx.compose.material3.LocalTextStyle.current.copy(
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = fgColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}