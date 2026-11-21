package com.wy.openx.ui.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.TypedValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.BuildConfig
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.update.UpdateChecker
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 「关于 OpenX」子页（设置 → 关于 → 版本）。
 *
 * 点版本行向 GitHub Releases 查询最新版本，有新版本时再次点击即在应用内
 * 下载 APK 并拉起系统安装器（下载走镜像回退，见 [UpdateChecker]）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember { loadAppIconBitmap(context) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var failed by remember { mutableStateOf(false) }
    var openDoc by remember { mutableStateOf<LegalDoc?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val hasUpdate = release?.isNewer == true
    val versionValue = when {
        checking -> "检查中…"
        downloading -> if (progress > 0f) "下载中 ${(progress * 100).toInt()}%" else "下载中…"
        hasUpdate -> "v${release?.versionName} 可更新"
        release != null -> "已是最新"
        failed -> "检查失败，重试"
        else -> "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    fun startDownload(target: UpdateChecker.Release) {
        if (downloading) return
        if (!UpdateChecker.canInstall(context)) {
            android.widget.Toast.makeText(context, "请先允许本应用安装未知应用", android.widget.Toast.LENGTH_LONG).show()
            UpdateChecker.requestInstallPermission(context)
            return
        }
        downloading = true
        progress = 0f
        scope.launch {
            val apk = UpdateChecker.download(context, target) { done, total ->
                progress = if (total > 0) done.toFloat() / total else 0f
            }
            downloading = false
            progress = 0f
            if (apk == null) {
                android.widget.Toast.makeText(context, "下载失败，请检查网络后重试", android.widget.Toast.LENGTH_LONG).show()
            } else {
                UpdateChecker.install(context, apk)
            }
        }
    }

    fun checkUpdate() {
        if (checking || downloading) return
        checking = true
        failed = false
        scope.launch {
            val result = UpdateChecker.fetchLatest()
            checking = false
            if (result == null) {
                failed = true
                android.widget.Toast.makeText(context, "检查更新失败，请检查网络", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                release = result
                failed = false
                if (result.isNewer) {
                    android.widget.Toast.makeText(
                        context,
                        "发现新版本 v${result.versionName}，再点一次开始下载",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val doc = openDoc
    if (doc != null) {
        DocScreen(title = doc.title, assetName = doc.asset, onBack = { openDoc = null })
        return
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
            Text("关于 OpenX", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp)
                    )
                } else {
                    // 图标加载失败时保留占位，避免文字贴边跳动
                    Spacer(Modifier.size(56.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("OpenX", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "中文AI智能体客户端。功能：对话，AI驱动代码编辑，内置终端，git，股票分析等",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            GroupLabel("版本信息")
            AboutRow(
                icon = Icons.Outlined.Info,
                title = "版本",
                value = versionValue,
                onClick = {
                    val target = release
                    if (target != null && target.isNewer) startDownload(target) else checkUpdate()
                }
            )

            Spacer(Modifier.height(18.dp))
            GroupLabel("链接")
            AboutRow(
                icon = Icons.Outlined.Code,
                title = "GitHub 仓库",
                value = "打开",
                onClick = { openUrl(context, GITHUB_REPO_URL) }
            )
            RowDivider()
            AboutRow(
                icon = Icons.Outlined.Feedback,
                title = "意见反馈",
                value = "GitHub Issues",
                onClick = { openUrl(context, FEEDBACK_URL) }
            )

            Spacer(Modifier.height(18.dp))
            GroupLabel("法律信息")
            AboutRow(
                icon = Icons.Outlined.Description,
                title = "用户协议",
                value = "查看",
                onClick = { openDoc = LegalDoc("用户协议", "用户协议.md") }
            )
            RowDivider()
            AboutRow(
                icon = Icons.Outlined.Lock,
                title = "隐私政策",
                value = "查看",
                onClick = { openDoc = LegalDoc("隐私政策", "隐私政策.md") }
            )
            RowDivider()
            AboutRow(
                icon = Icons.Outlined.Code,
                title = "开源许可",
                value = "查看",
                onClick = { openDoc = LegalDoc("开源许可", "开源许可.md") }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 内置法律文档：标题 + assets/legal 下的文件名。 */
private data class LegalDoc(val title: String, val asset: String)

private const val GITHUB_REPO_URL = "https://github.com/wy624333/openx"
private const val FEEDBACK_URL = "https://github.com/wy624333/openx/issues"

private const val ICON_SIZE_DP = 56

@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = if (onClick != null) Accent else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        if (onClick != null) {
            Text(" ›", color = Border, fontSize = 14.sp)
        }
    }
}

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

/** 用 PackageManager 加载自适应图标，避免 adaptive-icon XML 无法直接用于 painterResource。 */
private fun loadAppIconBitmap(context: Context): ImageBitmap? = runCatching {
    val pm = context.packageManager
    val drawable: Drawable = pm.getApplicationInfo(context.packageName, 0).loadIcon(pm)
    val sizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        ICON_SIZE_DP.toFloat(),
        context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
