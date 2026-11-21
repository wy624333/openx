package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.model.AppSettings
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 偏好设置页。
 *
 * 页面结构照实现：
 * - 顶栏：左返回 + 标题；
 * - 分区标题：小字标签 + 右侧一条分隔线（`_buildSettingsSection`）；
 * - 设置项：`[图标] 标题 / 副标题 [尾部控件]`；跳转型项的尾部是「去开启 ›」。
 */
@Composable
fun PreferencesScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdate: (AppSettings) -> Unit,
    onAutoArchiveDaysChanged: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不铺底色：透出页面底色/背景图，与 Git、量化页一致
                // （TopAppBar containerColor = Transparent）观感相同。
                // 原来用 BgSecondary（= surface，浅米下纯白）会形成一条白带，选背景图时更是挡图。
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
            Text("偏好设置", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            // ===== 启动与输入 =====
            GroupLabel("启动与输入")

            SettingsRow(
                icon = Icons.Outlined.PlayArrow,
                title = "启动时",
                subtitle = if (settings.restoreLastConversation)
                    "恢复上次关闭的对话"
                else
                    "新建会话",
                trailing = {
                    Switch(
                        checked = settings.restoreLastConversation,
                        onCheckedChange = { onUpdate(settings.copy(restoreLastConversation = it)) },
                        colors = switchColors()
                    )
                }
            )
            RowDivider()

            SettingsRow(
                icon = Icons.Outlined.Send,
                title = "使用独立的发送按钮",
                subtitle = if (settings.separateSendButton)
                    "回车为换行，点发送按钮才发出"
                else
                    "回车直接发送",
                trailing = {
                    Switch(
                        checked = settings.separateSendButton,
                        onCheckedChange = { onUpdate(settings.copy(separateSendButton = it)) },
                        colors = switchColors()
                    )
                }
            )

            Spacer(Modifier.height(22.dp))

            // ===== 运行 =====
            GroupLabel("运行")

            SettingsRow(
                icon = Icons.Outlined.Brightness6,
                title = "防止任务运行时屏幕休眠",
                subtitle = "任务运行期间保持屏幕常亮",
                trailing = {
                    Switch(
                        checked = settings.keepScreenOnWhileRunning,
                        onCheckedChange = { onUpdate(settings.copy(keepScreenOnWhileRunning = it)) },
                        colors = switchColors()
                    )
                }
            )
            RowDivider()

            SettingsRow(
                icon = Icons.Outlined.Power,
                title = "后台保活",
                subtitle = "前台服务常驻通知栏，降低被系统回收的概率",
                trailing = {
                    Switch(
                        checked = settings.backgroundKeepAlive,
                        onCheckedChange = { onUpdate(settings.copy(backgroundKeepAlive = it)) },
                        colors = switchColors()
                    )
                }
            )
            RowDivider()

            val overlayGranted = remember(settings.floatingOverlay) {
                com.wy.openx.service.FloatingOverlayController.canDrawOverlays(context)
            }
            SettingsRow(
                icon = Icons.Outlined.PictureInPictureAlt,
                title = "悬浮窗",
                subtitle = when {
                    !settings.floatingOverlay -> "切到其他应用时，用悬浮窗显示 AI 回复与工具状态"
                    !overlayGranted -> "尚未授予「悬浮窗权限」，请到「应用权限授权」中开启"
                    else -> "AI 运行中且 OpenX 在后台时显示"
                },
                trailing = {
                    Switch(
                        checked = settings.floatingOverlay,
                        onCheckedChange = { onUpdate(settings.copy(floatingOverlay = it)) },
                        colors = switchColors()
                    )
                }
            )

            Spacer(Modifier.height(22.dp))

            // ===== 会话 =====
            GroupLabel("会话")

            val archiveEnabled = settings.autoArchiveDays > 0
            SettingsRow(
                icon = Icons.Outlined.Archive,
                title = "自动归档",
                subtitle = if (archiveEnabled)
                    "超过 ${settings.autoArchiveDays} 天未活跃的会话自动收进「历史」"
                else
                    "关闭；会话仍可在侧边栏左滑手动归档",
                trailing = {
                    Switch(
                        checked = archiveEnabled,
                        onCheckedChange = { on ->
                            // 开关只负责开/关，具体天数用下方档位选。默认开启时给 7 天。
                            val days = if (on) (settings.autoArchiveDays.takeIf { it > 0 } ?: 7) else 0
                            onUpdate(settings.copy(autoArchiveDays = days))
                            if (days > 0) onAutoArchiveDaysChanged(days)
                        },
                        colors = switchColors()
                    )
                }
            )
            // 开关打开后才显示天数档位——关着时给档位没意义，只会让页面变噪
            if (archiveEnabled) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(7, 30, 90, 180).forEach { days ->
                        val selected = settings.autoArchiveDays == days
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Accent else PanelAlt)
                                .clickable {
                                    onUpdate(settings.copy(autoArchiveDays = days))
                                    onAutoArchiveDaysChanged(days)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${days}天",
                                color = if (selected) TextPrimary else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = Accent,
    uncheckedThumbColor = TextSecondary,
    uncheckedTrackColor = Border
)

/** 分区标题：小字标签 + 右侧分隔线（照搬参考实现 `_buildSettingsSection`）。 */
@Composable
private fun GroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Border.copy(alpha = 0.6f))
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** 项间分隔线：左缩进 28（图标 18 + 间距 10），让线从文字左侧开始而非横贯图标。 */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .height(0.5.dp)
            .background(Border.copy(alpha = 0.5f))
    )
}

/**
 * 单个设置项：[图标] 标题/副标题 [尾部控件]。
 * 排布与字号 `_buildSettingTile`（图标 18、标题 14/w500、副标题 11）。
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
