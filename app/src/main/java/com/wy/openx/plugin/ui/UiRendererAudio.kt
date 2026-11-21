package com.wy.openx.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.wy.openx.audio.AudioPlaybackHub
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.color
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 音频播放条组件（`audiobar`）—— 宿主渲染，插件一行就能用。
 *
 * 为什么由宿主渲染而不是让插件自己拼：进度要随时间刷新，插件侧得自己起定时器
 * 再触发重渲染，很容易写错（而且插件面板外层有滚动容器，交互容易打架）。
 * 这里直接用 Compose 的 Compose 状态轮询，插件只需声明一个节点：
 *
 * ```js
 * ctx.UI.AudioBar({})
 * ```
 *
 * props：
 *  · `showVolume`(bool)：是否显示音量滑杆，默认 false
 *  · `compact`(bool)：紧凑模式（不显示进度与时间），默认 false
 *
 * 它**不接任何 onClick**：播放/暂停/停止按钮直接操作 [AudioPlaybackHub]，
 * 状态从 Hub 快照读，所以任何插件页面的播放条都显示同一份真实状态。
 */
@Composable
fun RenderUiNodeAudio(node: UiNode, ctx: UiRenderContext, m: Modifier): Boolean {
    if (node.type.lowercase() !in AUDIO_BAR_TYPES) return false

    // 轮询快照即可：Hub 内部已经按事件 + 500ms 刷新，这里同步读不会阻塞（@Volatile）。
    var state by remember { mutableStateOf(AudioPlaybackHub.state()) }
    LaunchedEffect(Unit) {
        while (true) {
            state = AudioPlaybackHub.state()
            delay(500)
        }
    }

    // 拖动中的本地值。
    //
    // 为什么需要：滑块显示的值来自 500ms 轮询的快照，拖动时轮询会把滑块**重置回旧值**，
    // 看起来就是“滑不动”（手一松就弹回去）。所以拖动期间用本地值接管，
    // 松手（onValueChangeFinished）才把最终值交回 Hub。
    var draggingVolume by remember { mutableStateOf<Float?>(null) }
    var draggingSeek by remember { mutableStateOf<Float?>(null) }

    val showVolume = node.bool("showVolume")
    val compact = node.bool("compact")
    val np = state.nowPlaying
    val hasItem = np != null

    Surface(
        modifier = m.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = UiTheme.colorOr(node.color("background"), Panel)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ① 曲名与状态独占一行，放在按钮上方：按钮组不再和文字抢宽度
            Text(
                text = np?.title ?: "未在播放",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = when {
                state.error != null -> state.error
                state.buffering -> "缓冲中…"
                np == null -> "插件调用 ctx.audio.play() 后会显示在这里"
                np.artist.isNotBlank() -> np.artist
                np.isLive -> "网络电台"
                else -> "正在播放"
            }
            Text(
                text = sub.orEmpty(),
                color = if (state.error != null) {
                    UiTheme.colorOf("error") ?: TextSecondary
                } else {
                    TextSecondary
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            // ② 进度：放在按钮行上方（直播流没有有意义的时长，只对本地文件/点播显示）
            if (!compact && hasItem && !np.isLive && state.durationMs > 0) {
                val pos = state.positionMs.coerceIn(0L, state.durationMs)
                val shown = draggingSeek ?: pos.toFloat()
                // 时间放在进度条左右两端同一行：比另起一行省 14dp，也符合常见播放器习惯
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fmtTime(pos),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.width(40.dp)
                    )
                    Slider(
                        value = shown.coerceIn(0f, state.durationMs.toFloat()),
                        onValueChange = { v -> draggingSeek = v },
                        onValueChangeFinished = {
                            draggingSeek?.let { AudioPlaybackHub.seekTo(it.toLong()) }
                            draggingSeek = null
                        },
                        valueRange = 0f..state.durationMs.toFloat(),
                        // 压窄：Slider 默认自带上下各约 12dp 留白，本地文件进度条没必要那么高
                        modifier = Modifier.weight(1f).height(32.dp)
                    )
                    Text(
                        text = fmtTime(state.durationMs),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }

            // ③ 传输控制 + 播放模式：六个按钮同一行，居中对齐
            //    循环/随机跟在停止键后面，不额外占一行，纵向空间留给进度条
            val modeOn = UiTheme.colorOf("primary") ?: TextPrimary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { AudioPlaybackHub.previous() }, enabled = hasItem) {
                    Icon(
                        UiIcons.of("skipprevious"),
                        contentDescription = "上一曲",
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { AudioPlaybackHub.toggle() }, enabled = hasItem) {
                    Icon(
                        UiIcons.of(if (state.playing) "pause" else "play"),
                        contentDescription = if (state.playing) "暂停" else "播放",
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { AudioPlaybackHub.next() }, enabled = hasItem) {
                    Icon(
                        UiIcons.of("skipnext"),
                        contentDescription = "下一曲",
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { AudioPlaybackHub.stop() }, enabled = hasItem) {
                    Icon(
                        UiIcons.of("stop"),
                        contentDescription = "停止",
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                // 循环轮转：不循环 → 列表循环 → 单曲循环
                ModeIconButton(
                    on = state.repeatMode != Player.REPEAT_MODE_OFF,
                    enabled = hasItem,
                    onClick = { AudioPlaybackHub.cycleRepeatMode() },
                    icon = UiIcons.of(if (state.repeatMode == Player.REPEAT_MODE_ONE) "repeatone" else "repeat"),
                    contentDesc = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "单曲循环"
                        Player.REPEAT_MODE_ALL -> "列表循环"
                        else -> "不循环"
                    },
                    onColor = modeOn
                )
                ModeIconButton(
                    on = state.shuffle,
                    enabled = hasItem,
                    onClick = { AudioPlaybackHub.toggleShuffle() },
                    icon = UiIcons.of("shuffle"),
                    contentDesc = if (state.shuffle) "随机播放已开" else "随机播放已关",
                    onColor = modeOn
                )
            }

            if (showVolume) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        UiIcons.of("volumeup"),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = (draggingVolume ?: state.volume).coerceIn(0f, 1f),
                        onValueChange = { v -> draggingVolume = v },
                        onValueChangeFinished = {
                            draggingVolume?.let { AudioPlaybackHub.setVolume(it) }
                            draggingVolume = null
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(32.dp)
                    )
                }
            }
        }
    }
    return true
}

/**
 * AudioBar 的模式键（循环 / 随机）。
 *
 * 开启态不能只靠 tint 从 [TextSecondary] 换成主色：简约白主题的主色 #6B7280 与
 * onSurfaceVariant #6E7379 是同一档灰，换色等于没换 —— 用户看不出按没按上。
 * 所以状态另外用「有没有底色圆」表达，形状差异在任何主题下都成立。
 */
@Composable
private fun ModeIconButton(
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDesc: String,
    onColor: Color
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (on) onColor.copy(alpha = 0.18f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDesc,
                modifier = Modifier.size(18.dp),
                tint = if (on) onColor else TextSecondary
            )
        }
    }
}

private val AUDIO_BAR_TYPES = setOf("audiobar", "audio_bar", "audioplayer", "audio_player")

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    val min = total / 60
    val sec = total % 60
    return "$min:${if (sec < 10) "0" else ""}$sec"
}
