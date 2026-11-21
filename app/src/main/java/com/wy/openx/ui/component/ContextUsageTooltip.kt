package com.wy.openx.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.TextPrimary

/**
 * 圆环 + 短按浮层 + 长按回调。
 *
 * - **短按**：弹出小窗，**再次短按可关闭**（toggle），3 秒后自动消失；
 * - **长按**：触发 [onLongClick]，并先收起浮层避免两个浮层叠一起。
 *
 * 浮层用非聚焦 [Popup]，**不抢输入框焦点**——参考实现专门为此绕开了 Navigator push，
 * 否则软键盘塌陷、输入栏下沉，而浮层锚点还停在键盘弹起时的高位置，看起来就是浮层飘着、
 * 输入栏掉下去。这里同理 `focusable = false`，键盘不受影响。
 */
@Composable
fun ContextUsageRingButton(
    ratio: Float,
    tooltipMessage: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var showTooltip by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }
    ) {
        ContextUsageRing(
            ratio = ratio,
            onClick = { if (tooltipMessage.isNotBlank()) showTooltip = !showTooltip },
            onLongClick = {
                showTooltip = false
                onLongClick?.invoke()
            }
        )
    }

    if (showTooltip) {
        ContextUsageTooltip(
            message = tooltipMessage,
            anchor = anchorBounds,
            onDismiss = { showTooltip = false }
        )
    }
}

/** 阴影外溢预留：与参考实现 `_UnfoldClipper._shadowExtent` 同值，让阴影随展开一起渗出。 */
private val SHADOW_EXTENT = 64.dp

/**
 * 上下文用量浮层——
 *
 * ## 三处照搬参考实现的关键点（前两版都栽在这上面）
 *
 * **1. 裁切，而不是重新布局**（参考 `_GlassPopupAnimatedFrame` + `_UnfoldClipper`）。
 * child **始终以完整尺寸布局**，只有裁切矩形在动。用 `AnimatedVisibility` 那种会改变布局尺寸的做法，
 * 会让 `Popup` 的定位每帧漂移，表现为「先弹到右下、再跳回上方」。
 *
 * **2. 裁切必须作用在面板自己的局部坐标系**（参考实现文档里专门强调过）。
 * 裁切矩形起点是 `(-extent, -extent)`、终点是 `(size + extent)`，即**从面板外扩一圈开始生长**；
 * 若把裁切包在整个页面/浮层容器上，就等于「裁切还没扩到面板时什么都看不见、一旦覆盖就整块蹦出来」，
 * 完全没有生长感。
 *
 * **3. 0.5px 下限**（参考实现注释：避免 0 面积矩形进入 ClipRect 的零面积 fast-path，
 * 那会直接跳过绘制，导致首帧「什么都没有 → 一瞬间冒一整块」的跳变）。
 *
 * ## 动画曲线（照搬）
 * - 宽度 `Interval(0, 0.35)`：前 35% 内把宽度拉到满，模拟原生 PopupMenu「先撑宽再垂下」；
 * - 高度 `Interval(0, 1.0)`：贯穿全程，像帘子垂下；
 * - 透明度 `Interval(0, 0.18)`：只在前 18% 淡入，藏掉首帧硬边；
 * - 展开 380ms / 收回 220ms，出现比消失慢。
 *
 * 展开锚点（从哪个角长）由定位实测的「在上方还是下方」+ 水平相对位置决定，
 * 保证内容始终从贴着圆环的那一侧长出来（对应参考实现 `onAlignmentResolved`）。
 */
@Composable
fun ContextUsageTooltip(
    message: String,
    anchor: Rect?,
    onDismiss: () -> Unit,
    autoDismissMillis: Long = 3000L,
) {
    if (anchor == null) return

    // 展开锚点，取值 [-1,1]²：x 由水平相对位置决定，y 由上/下决定
    var unfoldX by remember { mutableStateOf(0f) }
    var unfoldY by remember { mutableStateOf(1f) }

    val unfold = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        unfold.animateTo(
            1f,
            tween(durationMillis = 380, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(autoDismissMillis)
        if (!closing) closing = true
    }
    LaunchedEffect(closing) {
        if (closing) {
            unfold.animateTo(0f, tween(durationMillis = 220))
            onDismiss()
        }
    }

    val t = unfold.value
    // 三段曲线：把 Interval(0,0.35) / (0,1) / (0,0.18) 换算成同一进度 t 上的取值
    val widthT = (t / 0.35f).coerceIn(0f, 1f)
    val heightT = t
    val fadeT = (t / 0.18f).coerceIn(0f, 1f)

    val extentPx = with(androidx.compose.ui.platform.LocalDensity.current) { SHADOW_EXTENT.toPx() }

    Popup(
        popupPositionProvider = remember(anchor) {
            ContextTooltipPositionProvider(anchor) { ax, ay ->
                unfoldX = ax
                unfoldY = ay
            }
        },
        onDismissRequest = { if (!closing) closing = true },
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
    ) {
        GlassPanel(
            modifier = Modifier
                // 宽度随内容自适应，仅设上限。不写死宽度：文案只有两行短句，写死会撑出又宽又空的小窗。
                .widthIn(max = 190.dp)
                .alpha(fadeT)
                // ── 关键：在面板自身的局部坐标系里裁切展开（外扩 extent 以放阴影渗出）──
                .drawWithContent {
                    val fullW = size.width + extentPx * 2
                    val fullH = size.height + extentPx * 2
                    // 0.5px 下限：避免 0 面积矩形走 ClipRect 的 fast-path 而整块跳过绘制
                    val w = (fullW * widthT).coerceIn(0.5f, fullW)
                    val h = (fullH * heightT).coerceIn(0.5f, fullH)
                    // 锚点 [-1,1] → [0,1]
                    val axq = (unfoldX + 1f) / 2f
                    val ayq = (unfoldY + 1f) / 2f
                    val left = -extentPx + (fullW - w) * axq
                    val top = -extentPx + (fullH - h) * ayq
                    clipRect(left, top, left + w, top + h) { this@drawWithContent.drawContent() }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (!closing) closing = true }
        ) {
            Text(
                message,
                color = TextPrimary,
                fontSize = 12.sp,
                lineHeight = 17.4.sp,
                fontWeight = FontWeight.Medium,
                // 两行文案长度不同（「N / M tokens」比「长按可调整阈值」长），居中后两行才对齐。
                // 不要加 fillMaxWidth()：那会把宽度重新撑到 widthIn 的上限，
                // Text 的自然宽度本身就是最长行宽度，短行在其中居中即可，窗口仍贴合文字。
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 玻璃面板。
 *
 * 与「纯色圆角块」的差别：**渐变 tint**（左上→右下）+ **1px 浅色描边**
 * + **顶部 1px 高光**（两侧透明、中间亮的横向渐变，即"光打在玻璃顶上"）+ 投影。
 *
 * **底色跟随系统主题**（用户要求）：用 openx 的主题 surface 作底，
 * 不再铺强调色——之前铺 Accent 导致整个小窗是蓝的，太抢眼。
 * 强调色只保留在「顶部高光」这一点点，用于维持玻璃质感。
 *
 * 实现还带 10px 背景模糊（`BackdropFilter`）。这里**没做**：Compose 的 `blur` 需 API 31+，
 * 且在自绘弹层上代价偏高；openx 输入栏背景本身是纯色，模糊前后几乎无差别，收益不抵成本。
 */
@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    // 跟随主题：以 surface 为底，用极轻的明暗渐变制造玻璃的「面」
    val topTint = Color.White.copy(alpha = 0.06f)
    val bottomTint = Color.Black.copy(alpha = 0.10f)

    Box(
        modifier = modifier
            // 投影：向下偏移，营造浮起。放在 clip 之前才不会被自己的圆角裁掉
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(BgSecondary)
            .background(Brush.linearGradient(listOf(topTint, bottomTint)))
            .border(1.dp, Border.copy(alpha = 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Box {
            content()
            // 顶部 1px 高光：左右透明、中间亮，叠在内容之上。
            //
            // 用 matchParentSize() 而不是 fillMaxWidth()：后者会按 widthIn 的**上限**撑满宽度，
            // 于是不管文字多短，小窗都固定停在最大宽度（这正是之前「小窗还有点宽」的原因）。
            // matchParentSize() 不参与父级尺寸计算，父级宽度仍由文字决定，高光再匹配上去。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val inset = 6.dp.toPx()
                        val lineHeight = 1.dp.toPx()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent)
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(inset, 0f),
                            size = androidx.compose.ui.geometry.Size(
                                (size.width - inset * 2).coerceAtLeast(0f),
                                lineHeight
                            )
                        )
                    }
            )
        }
    }
}

/**
 * 浮层定位。
 *
 * - 垂直：优先锚点上方 8dp，空间不足翻下方，并夹在窗口内；
 * - 水平：以锚点中心对齐；
 * - 回传展开锚点：`x` 由「锚点中心在浮层内的相对位置」算出，`y` 上=1 / 下=-1，
 *   保证从贴着圆环的那一侧长出来。
 */
private class ContextTooltipPositionProvider(
    private val anchor: Rect,
    private val onUnfoldAnchorResolved: (Float, Float) -> Unit
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val gap = 8
        val anchorTop = anchor.top.toInt()
        val anchorBottom = anchor.bottom.toInt()
        val anchorCenterX = (anchor.left + anchor.width / 2f).toInt()

        val placeAbove = anchorTop >= popupContentSize.height + gap
        var y = if (placeAbove) anchorTop - popupContentSize.height - gap else anchorBottom + gap
        y = y.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))

        var x = anchorCenterX - popupContentSize.width / 2
        x = x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))

        // 锚点：水平按「圆环中心落在浮层内的相对位置」，垂直按上/下
        val rel = if (popupContentSize.width <= 0) 0.5f
        else ((anchorCenterX - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f)
        onUnfoldAnchorResolved(rel * 2f - 1f, if (placeAbove) 1f else -1f)

        return IntOffset(x, y)
    }
}
