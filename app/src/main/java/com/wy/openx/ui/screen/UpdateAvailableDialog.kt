package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.update.UpdateChecker
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 每日检查更新后发现的版本提醒。
 *
 * 「跳过此版本」记下 versionCode，之后只对更高的版本再提醒——否则同一个版本
 * 每天启动都弹一次，用户只能学会无视弹窗。
 */
@Composable
fun UpdateAvailableDialog(
    release: UpdateChecker.Release,
    onUpdateNow: (UpdateChecker.Release) -> Unit,
    onSkipVersion: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Text(
                "发现新版本 v${release.versionName}",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes.trim(),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                } else {
                    Text("有新版本可用。", color = TextSecondary, fontSize = 12.sp)
                }
                if (release.assetUrl.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "该版本未提供安装包，请到 GitHub 仓库手动下载。",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            if (release.assetUrl.isNotBlank()) {
                TextButton(onClick = { onUpdateNow(release) }) {
                    Text("立即更新", color = Accent, fontWeight = FontWeight.Medium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onSkipVersion(release.versionCode) }) {
                Text("跳过此版本", color = TextSecondary)
            }
        }
    )
}
