package com.wy.openx.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** 波形保留的采样块数：按每块约 0.13 秒算，约 15 秒的可视历史。 */
private const val WAVE_SAMPLES = 120

/** 波形高度放大系数：语音峰值通常在 0.1~0.5，不放大几乎看不出起伏。 */
private const val WAVE_GAIN = 3f

/**
 * 语音输入弹窗：实时波形 + 识别文字 + 「说完了」。
 *
 * 做成居中弹窗而不是全屏页：录音是个打断式的短动作，弹窗能明确表达「说完就回去」，
 * 也不会让用户以为进入了另一个页面。
 *
 * @param levels 最近的音量采样（0~1，旧→新），由录音回调持续灌入
 * @param liveText 当前累计的识别文本；为空表示还没识别出内容
 * @param paused 是否处于暂停收音状态
 * @param onTogglePause 切换暂停/继续
 * @param onFinish 点「说完了」：结束录音并把结果交给上层
 * @param onCancel 点 ✕ 或返回键：关闭弹窗并放弃本次录音
 */
@Composable
internal fun VoiceInputSheet(
    levels: List<Float>,
    liveText: String,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                VoiceInputHeader(
                    paused = paused,
                    onTogglePause = onTogglePause,
                    onCancel = onCancel,
                    onSurface = onSurface,
                )

                Spacer(Modifier.height(24.dp))

                VoiceWaveform(
                    levels = levels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when {
                        paused -> "已暂停，点左上角继续"
                        liveText.isNotBlank() -> liveText
                        else -> "请提高说话音量…"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = onSurface.copy(alpha = 0.55f),
                )

                Spacer(Modifier.height(20.dp))

                VoiceFinishButton(onClick = onFinish)
            }
        }
    }
}

/** 弹窗顶部：左侧暂停/继续、中间语言、右侧关闭。 */
@Composable
private fun VoiceInputHeader(
    paused: Boolean,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onSurface: androidx.compose.ui.graphics.Color,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onTogglePause,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(30.dp)
        ) {
            Icon(
                imageVector = if (paused) Icons.Default.PlayArrow else Icons.Outlined.Pause,
                contentDescription = if (paused) "继续录音" else "暂停录音",
                tint = onSurface.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("普通话", fontSize = 15.sp, color = onSurface.copy(alpha = 0.8f))
            Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                tint = onSurface.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(28.dp)
        ) {
            Icon(
                Icons.Default.Close, contentDescription = "取消",
                tint = onSurface.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 「说完了」：结束录音并回填识别结果。 */
@Composable
private fun VoiceFinishButton(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "说完了",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 横向波形：每个采样画一根竖条，安静时退化成小圆点。
 *
 * 条宽与间距固定、整体居中——不按采样数去分屏宽。按采样数分宽会在只有一两个采样时
 * 算出比画布还宽的柱子，进而让 `coerceIn(barWidth, size.height)` 收到「下界大于上界」
 * 的空区间，直接抛 IllegalArgumentException 崩掉（真机上点开录音即闪退）。
 */
@Composable
private fun VoiceWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (levels.isEmpty()) return@Canvas
        val barWidth = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val slot = barWidth + gap
        val capacity = (size.width / slot).toInt().coerceAtLeast(1)
        val visible = if (levels.size > capacity) levels.takeLast(capacity) else levels
        val startX = (size.width - visible.size * slot) / 2f
        val centerY = size.height / 2f
        // 下界额外夹一次：任何极端尺寸下都保证 min <= max
        val minHeight = barWidth.coerceAtMost(size.height)
        visible.forEachIndexed { index, level ->
            val barHeight = (level * size.height * WAVE_GAIN).coerceIn(minHeight, size.height)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(startX + index * slot, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** 供录音回调裁剪波形历史用。 */
internal fun MutableList<Float>.trimWaveSamples() {
    while (size > WAVE_SAMPLES) removeAt(0)
}
