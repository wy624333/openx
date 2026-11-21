package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Danger
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.Success
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.theme.Warning

@Composable
fun InfoPanel(title: String, value: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = PanelAlt)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(value, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun StatusBadge(text: String) {
    val color = when (text) {
        "在线", "成功", "完成" -> Success
        "运行中", "执行中", "规划中", "思考中", "生成中" -> Warning
        "失败", "未授权", "已停止" -> Danger
        else -> TextSecondary
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold)
    }
}


