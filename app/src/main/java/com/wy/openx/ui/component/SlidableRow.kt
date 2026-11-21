package com.wy.openx.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滑动操作项
 *
 * 实现的差别：**不带色块底色**（用户要求）。实现在每项后面铺一块实心色
 * （删除红、归档蓝…），这里只显示描边图标本身，视觉更轻，也不至于一滑出一排色块很吵。
 */
data class SlideAction(
    val icon: ImageVector,
    /** 图标颜色；不传则用主题的次要文字色。 */
    val tint: Color = Color.Unspecified,
    val onClick: () -> Unit
)

/**
 * 列表级「同时只允许一行展开」的协调状态。
 *
 * 实现用 `flutter_slidable` 的 `groupTag` 保证同组内互斥；这里用
 * 「当前展开行 id」的共享状态达到同一效果：滑开新行时，旧行观察到 id 变了就自行收起。
 *
 * 不这样做的话，每行各自持有 `expanded`，滑开新行时旧行毫不知情、一直停在展开位
 * ——就是用户反馈的「滑过去的不会自己回来」。
 */
class SlidableGroupState {
    var openId by mutableStateOf<Any?>(null)
        internal set

    /** 请求展开 [id]；传 null 表示全部收起。 */
    fun requestOpen(id: Any?) {
        openId = id
    }
}

/** 记住一个列表级的滑动协调状态。 */
@Composable
fun rememberSlidableGroupState(): SlidableGroupState = remember { SlidableGroupState() }

/**
 * 会话行「左滑出操作」
 *
 * 交互实现：
 * - 每项占行宽的 24%（`actionExtentRatioPerAction = 0.24`），总量按项数叠加；
 * - 松手后**吸附**：滑过一半就完全展开，否则回弹归位；
 * - **同组互斥**：同一次只允许一行展开，滑开新行旧行自动收起（见 [SlidableGroupState]）；
 * - 展开状态下点内容区先收起，不直接触发点击（避免误触进会话）；
 * - 全滑（滑过 85%）触发**首项**动作——参考实现里首项是归档，这里同样。
 *
 * 用 `detectHorizontalDragGestures` + `Animatable` 手写，而非 `AnchoredDraggable`：
 * 后者在 foundation 1.9 里 API 仍标注实验且用法冗长，而这个组件的位移逻辑很直白
 * （单轴、吸附两点），手写更可控也更易读。
 *
 * **调用方必须给前景内容不透明底色**：操作图标画在内容层背后，内容若透明，
 * 图标会在未滑动时就透出来与文字重叠。
 */
@Composable
fun SlidableRow(
    actions: List<SlideAction>,
    modifier: Modifier = Modifier,
    /** 行标识：与 [groupState] 配合实现同组互斥。不传则不参与互斥。 */
    itemId: Any? = null,
    /** 列表级协调状态；同一列表传同一个实例。 */
    groupState: SlidableGroupState? = null,
    // 全滑触发：参考实现传给 DismissiblePane（首项动作）
    onFullSwipe: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (actions.isEmpty()) {
        content()
        return
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var rowWidthPx by remember { mutableFloatStateOf(0f) }

    // 每项 24% 行宽，总量封顶行宽
    val actionsTotalPx = rowWidthPx * (0.24f * actions.size).coerceAtMost(1f)
    val perActionPx = if (actions.isEmpty()) 0f else actionsTotalPx / actions.size

    val offsetX = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }

    val settle: (Boolean) -> Unit = { toExpand ->
        expanded = toExpand
        if (toExpand && groupState != null && itemId != null) {
            groupState.requestOpen(itemId)
        }
        scope.launch {
            offsetX.animateTo(
                if (toExpand) -actionsTotalPx else 0f,
                tween(durationMillis = 200)
            )
        }
    }

    // 同组互斥：别人展开时自己收起。
    // 用 LaunchedEffect（而非直接读 state 后 animateTo）避免在组合期触发动画。
    val isOpen = groupState != null && itemId != null && groupState.openId == itemId
    LaunchedEffect(isOpen) {
        if (!isOpen && expanded) {
            expanded = false
            offsetX.animateTo(0f, tween(durationMillis = 200))
        }
    }

    // 组内是否有任一行处于展开态。用于决定是否铺「先收起」拦截层——
    // **必须按整组判断，不能只看自己**：用户滑开 A 后点是**另一行 B**，
    // 若只在 A 上铺拦截层，这一下会落到 B 的内容上直接导航，A 也仍停在展开位，
    // 正是用户报的「滑开的没回来、点击直接进聊天页」。
    val anyOpenInGroup = groupState?.openId != null

    // 行宽变化（旋转/分屏）时把展开态重置，避免偏移量停在错误位置
    LaunchedEffect(actionsTotalPx) {
        if (expanded) offsetX.snapTo(-actionsTotalPx)
        else offsetX.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
    ) {
        // ── 背景：操作按钮（从右侧滑出）──
        // 只绘制「已滑出」的那部分宽度：用 drawWithContent 把绘制裁剪到右侧 revealed 像素。
        // 这样**不再要求调用方给前景铺不透明底色**——设置页为了让背景图透出来、
        // 故意把行底色去掉了（见 SettingsScreen 里该行的注释），若靠内容遮挡，
        // 删除图标就会在未滑动时从底下透出来、压到右侧的「使用」文字上。
        //
        // 用 drawWithContent + clipRect（读 offsetX.value）而不是在组合期按宽度包一层 Box：
        // 前者只触发**重绘**，后者会让每一帧拖动都触发**重组**。
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(with(density) { actionsTotalPx.toDp() })
                .drawWithContent {
                    // 已滑出的宽度；0 = 完全收起（不画），满 = 全部露出。
                    val revealed = (-offsetX.value).coerceIn(0f, size.width)
                    if (revealed <= 0f) return@drawWithContent
                    // 滑出方向是从右往左展开，所以裁掉左侧、保留最右 revealed 像素。
                    clipRect(left = size.width - revealed) {
                        this@drawWithContent.drawContent()
                    }
                },
            horizontalArrangement = Arrangement.End
        ) {
            actions.forEach { action ->
                Box(
                    modifier = Modifier
                        .width(with(density) { perActionPx.toDp() })
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            settle(false)
                            action.onClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        // Unspecified 时回退主题次要文字色：Data class 的默认参数不能读
                        // CompositionLocal（非 @Composable 上下文），只能在这里兜底。
                        tint = if (action.tint == Color.Unspecified) TextSecondary else action.tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── 前景：内容（可左滑）──
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .pointerInput(actionsTotalPx) {
                    if (actionsTotalPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount)
                                .coerceIn(-actionsTotalPx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            val threshold = actionsTotalPx / 2f
                            when {
                                // 全滑：滑过 85% 触发首项动作（参考实现走 DismissiblePane）
                                onFullSwipe != null && abs(offsetX.value) >= actionsTotalPx * 0.85f -> {
                                    settle(false)
                                    onFullSwipe()
                                }
                                abs(offsetX.value) >= threshold -> settle(true)
                                else -> settle(false)
                            }
                        },
                        onDragCancel = { settle(false) }
                    )
                }
        ) {
            content()

            // ── 组内有行展开时的「先收起」拦截层 ──
            // 必须**盖在内容之上**。第一版把 clickable 加在内容外层的 Box 上，永远不生效：
            // 内容自己是 combinedClickable，子节点先消费点击，外层根本收不到。
            // 于是「滑开后点其他会话」直接进聊天页，展开的那行也没收回。
            //
            // 且必须**整组**都铺（anyOpenInGroup），不能只铺展开的那行：
            // 用户点的往往是另一行，只铺展开行的话点击会落到目标行上直接导航。
            // 现在任一行展开时，所有行都被这层挡住：第一次点只收起，第二次点才落到内容上。
            // 这正是用户描述的期望行为（「先收回来，再点才进聊天页」）。
            if (anyOpenInGroup) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { groupState?.requestOpen(null) }
                )
            }
        }
    }
}
