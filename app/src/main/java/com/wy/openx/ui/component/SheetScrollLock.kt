package com.wy.openx.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * 让底部弹窗（`ModalBottomSheet`）里的滚动列表不再和弹窗的「下滑关闭」抢手势。
 *
 * ## 背景（material3 1.3.2 的实现）
 * `ModalBottomSheet` 把 `draggable` 挂在**整个面板**上，并注册了一个嵌套滚动连接
 * `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection`：
 * - `onPostScroll`：把列表滚到边界后的**剩余滑动量** `dispatchRawDelta` 成弹窗位移；
 * - `onPostFling`：把剩余**惯性**直接转成 `settleToDismiss`，也就是「甩一下就关」。
 *
 * 表现就是「滑列表把弹窗也拖着走」，甚至滑几下就关了。
 *
 * ## 做法
 * 挂在列表自己的 `Modifier.nestedScroll(...)` 上。嵌套滚动的分发顺序（见 `NestedScrollNode`）：
 * - `onPostScroll` / `onPostFling`：**子级先于父级** → 这里先跑，吃掉全部剩余量后，
 *   面板那层连接只会收到 `0`，弹窗纹丝不动。
 * - `onPreScroll` / `onPreFling`：**父级先于子级** → 拦不住，且见下。
 *
 * ## 为什么绝不实现 onPreScroll
 * `onPreScroll` 发生在列表**真正滚动之前**，是给祖先「先挑」的机会。
 * 在这里把量吃掉，列表自己就滚不动了（子级拿到的是「减去已消费量」的剩余）。
 * 所以只拦「列表滚完之后剩下的那一部分」。
 *
 * ## 结果
 * 列表怎么滑都不会关闭弹窗；关闭仍由**标题栏**触发——标题不在滚动容器内，
 * 不产生嵌套滚动，直接由面板自身的 `draggable` 接管，照常可以下滑关闭。
 */
object SheetScrollLock : NestedScrollConnection {

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
