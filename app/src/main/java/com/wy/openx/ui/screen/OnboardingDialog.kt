package com.wy.openx.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 首次启动引导。只在本机第一次进入软件时出现一次。
 *
 * 目的是把「装完就能用」和「实际要先配三样东西」之间的落差补上：
 * 没有 API 密钥发不出消息，没有容器环境插件跑不起来，
 * 这两件事不说清楚，用户会以为软件坏了。
 */
object OnboardingStore {
    private const val PREFS = "openx_onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

@Composable
fun OnboardingDialog(
    onConfigureApi: () -> Unit,
    onConfigureContainer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Column {
                Text(
                    "欢迎使用 OpenX",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "运行在 Android 上的 AI 编程助手。装好之后还需要三步准备，才能真正用起来。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OnboardingStep(
                    index = 1,
                    title = "配置 API",
                    body = "填入模型服务商的地址、密钥与模型名。不配这一步发不出任何消息。",
                    actionLabel = "去配置",
                    onAction = onConfigureApi
                )
                Spacer(Modifier.height(10.dp))
                OnboardingStep(
                    index = 2,
                    title = "配置容器环境",
                    body = "设置 → 容器，选一个镜像源并安装 Linux 容器。终端、命令执行、构建都依赖它。",
                    actionLabel = "去设置",
                    onAction = onConfigureContainer
                )
                Spacer(Modifier.height(10.dp))
                OnboardingStep(
                    index = 3,
                    title = "配置插件运行环境",
                    body = "设置 → 容器 → 运行环境，安装 Node.js、pnpm 等。装插件前必须先装好，否则插件脚本会失败。",
                    actionLabel = "去设置",
                    onAction = onConfigureContainer
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "提示：容器环境安装需要联网下载，约几十秒到几分钟；可以先把 API 配好开始聊天，容器稍后再装。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("开始使用", color = Accent, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun OnboardingStep(
    index: Int,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PageBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$index",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        actionLabel,
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onAction() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(body, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
