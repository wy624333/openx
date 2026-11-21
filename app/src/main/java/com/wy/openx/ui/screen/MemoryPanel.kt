package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.store.MemoryItem
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextSecondary

@Composable
fun MemoryPanel(items: List<MemoryItem>, maxItems: Int = 6) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text("暂无记忆", color = TextSecondary, fontSize = 13.sp)
        } else {
            items.take(maxItems).forEach { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelAlt),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.key, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.value, color = TextSecondary, fontSize = 12.sp)
                        Text(item.type, color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
