package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 法律文档查看页（用户协议 / 隐私政策 / 开源许可）。
 *
 * 文档打包在 assets/legal 下，离线可读，不依赖网络。
 * 刻意不放 assets/docs：那是 AI 记忆参考文档目录，会被 seedBuiltinDocs 释放给模型检索。
 */
@Composable
fun DocScreen(title: String, assetName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var content by remember(assetName) { mutableStateOf<String?>(null) }
    var loaded by remember(assetName) { mutableStateOf(false) }

    LaunchedEffect(assetName) {
        content = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("legal/$assetName").bufferedReader().use { it.readText() }
            }.getOrNull()
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
        }

        val body = content
        when {
            body != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                MarkdownContent(text = body, baseFontSize = 14.sp, textColor = TextPrimary)
                Spacer(Modifier.height(32.dp))
            }

            loaded -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("文档加载失败", color = TextSecondary, fontSize = 13.sp)
            }

            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}
