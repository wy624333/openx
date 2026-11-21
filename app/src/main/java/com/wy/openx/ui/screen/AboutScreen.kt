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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
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
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「关于 OpenX」子页（设置 → 关于 → 版本）。
 *
 * 取代原先的版本弹窗：弹窗里既有版本/构建时间，又塞了 13 条功能列表，纵向过长且
 * 关掉就看不到。改成独立子页后与「偏好设置 / 应用权限」保持同一套下钻交互。
 *
 * 点版本号向 GitHub 查询远端 version.json，比较 versionCode 判断是否有新版；
 * 有新版本时再点一下跳仓库主页手动下载。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember { loadAppIconBitmap(context) }
    val scope = rememberCoroutineScope()
    val buildDate = remember {
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(BuildConfig.BUILD_TIME))
        }.getOrDefault("未知")
    }

    var checking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<RemoteVersion?>(null) }
    var failed by remember { mutableStateOf(false) }

    val hasUpdate = latest?.let { it.versionCode > BuildConfig.VERSION_CODE } == true
    val versionValue = when {
        checking -> "检查中…"
        hasUpdate -> "v${latest?.versionName} 可更新"
        latest != null -> "已是最新"
        failed -> "检查失败，重试"
        else -> "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    fun checkUpdate() {
        if (checking) return
        checking = true
        failed = false
        scope.launch {
            val result = fetchRemoteVersion()
            checking = false
            if (result == null) {
                failed = true
                android.widget.Toast.makeText(context, "检查更新失败，请检查网络", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                latest = result
                failed = false
                if (result.versionCode > BuildConfig.VERSION_CODE) {
                    android.widget.Toast.makeText(
                        context,
                        "发现新版本 v${result.versionName}，点「打开」去下载",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    if (hasUpdate) openUrl(context, GITHUB_REPO_URL) else checkUpdate()
                }
            )
            RowDivider()
            AboutRow(
                icon = Icons.Outlined.Schedule,
                title = "构建时间",
                value = buildDate
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
                icon = Icons.Outlined.Description,
                title = "开源许可证",
                value = "GPL-3.0",
                onClick = { openUrl(context, LICENSE_URL) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 远端版本信息。version.json 由仓库同步脚本生成，放在仓库根目录。
 */
private data class RemoteVersion(val versionCode: Int, val versionName: String)

private const val GITHUB_REPO_URL = "https://github.com/wy624333/openx"
private const val LICENSE_URL = "https://github.com/wy624333/openx/blob/main/LICENSE"

/** 主源用 raw，回退 jsDelivr CDN（国内直连 raw 常被墙）。 */
private val VERSION_URLS = listOf(
    "https://raw.githubusercontent.com/wy624333/openx/main/version.json",
    "https://cdn.jsdelivr.net/gh/wy624333/openx@main/version.json"
)

private val updateClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

/** 依次尝试各个源，任一成功即返回；全失败返回 null。 */
private suspend fun fetchRemoteVersion(): RemoteVersion? = withContext(Dispatchers.IO) {
    for (url in VERSION_URLS) {
        val parsed = runCatching {
            val request = Request.Builder().url(url).build()
            updateClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else parseVersionJson(response.body?.string())
            }
        }.getOrNull()
        if (parsed != null) return@withContext parsed
    }
    null
}

private fun parseVersionJson(raw: String?): RemoteVersion? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val json = org.json.JSONObject(raw)
        val code = json.optInt("versionCode", 0)
        val name = json.optString("versionName", "")
        if (code <= 0 || name.isBlank()) null else RemoteVersion(code, name)
    }.getOrNull()
}

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
