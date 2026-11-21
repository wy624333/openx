package com.wy.openx.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AppRegistration
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.shizuku.ShizukuManager
import com.wy.openx.shizuku.ShizukuState
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 「应用权限授权」页。
 *
 * 独立成页而不是塞进「偏好设置」：偏好设置是**行为开关**，这里是**系统授权**，
 * 性质不同，混在一起既乱又难找。
 *
 * 页面结构照参考实现：分组（小字标签 + 分隔线）+ `[图标] 标题/说明 [状态/去开启]`。
 * 分组也沿用其划分：通知与提醒 / 核心权限 / 扩展能力。
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 进页面读一次状态；从系统设置页返回后重读（权限变化只可能发生在离开本页期间）
    var refreshTick by remember { mutableStateOf(0) }

    val notifications = remember(refreshTick) { notificationsEnabled(context) }
    val accessibility = remember(refreshTick) { isAccessibilityEnabled(context) }
    val battery = remember(refreshTick) { isIgnoringBatteryOptimizations(context) }
    val overlay = remember(refreshTick) { canDrawOverlays(context) }
    val installUnknown = remember(refreshTick) { canInstallUnknownApps(context) }
    val allFiles = remember(refreshTick) { hasAllFilesAccess() }

    // Shizuku 状态由 binder/授权监听驱动；额外在进入页面与回到前台时重算一次
    // （从 Shizuku 应用安装/启动服务返回后，保证状态不滞后）。
    val shizukuState by ShizukuManager.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        ShizukuManager.refreshState()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuManager.refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            Text("应用权限授权", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            // ===== 通知与提醒 =====
            GroupLabel("通知与提醒")
            PermissionRow(
                icon = Icons.Outlined.Notifications,
                title = "接收通知",
                subtitle = "任务完成提醒、后台保活通知等都需要它",
                granted = notifications,
                onClick = {
                    openSafely(
                        context,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        fallback = appDetailsIntent(context)
                    )
                    refreshTick++
                }
            )

            Spacer(Modifier.height(22.dp))

            // ===== 核心权限 =====
            GroupLabel("核心权限")
            PermissionRow(
                icon = Icons.Outlined.Accessibility,
                title = "无障碍权限",
                subtitle = "用于「手机操作」工具：读取界面并执行点击、滑动、输入、截图",
                granted = accessibility,
                onClick = {
                    openSafely(
                        context,
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        fallback = appDetailsIntent(context)
                    )
                    refreshTick++
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.Bolt,
                title = "后台运行权限",
                subtitle = "加入电池优化白名单，减少系统回收，让任务在后台稳定继续",
                granted = battery,
                onClick = {
                    openSafely(context, batteryOptimizationIntent(context))
                    refreshTick++
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.Layers,
                title = "悬浮窗权限",
                subtitle = "允许在其他应用上层显示内容（「偏好设置 → 悬浮窗」依赖此项）",
                granted = overlay,
                onClick = {
                    openSafely(context, overlayIntent(context), fallback = appDetailsIntent(context))
                    refreshTick++
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.AppRegistration,
                title = "安装未知应用",
                subtitle = "允许在本应用内安装 APK（容器环境、镜像导入等）",
                granted = installUnknown,
                onClick = {
                    openSafely(context, unknownSourcesIntent(context), fallback = appDetailsIntent(context))
                    refreshTick++
                }
            )

            Spacer(Modifier.height(22.dp))

            // ===== 扩展能力 =====
            GroupLabel("扩展能力")
            PermissionRow(
                icon = Icons.Outlined.FolderOpen,
                title = "所有文件访问权限",
                subtitle = "访问设备公共存储中的文件，用于文件读取、整理与下载",
                granted = allFiles,
                onClick = {
                    openSafely(context, allFilesAccessIntent(context), fallback = appDetailsIntent(context))
                    refreshTick++
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.Layers,
                title = "应用列表读取",
                subtitle = "用于识别设备已安装应用，提供应用上下文",
                granted = installedAppsReadable(context),
                onClick = {
                    openSafely(context, appDetailsIntent(context))
                    refreshTick++
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.Terminal,
                title = "Shizuku",
                subtitle = when (shizukuState) {
                    ShizukuState.NOT_INSTALLED -> "未安装 Shizuku，点击前往下载安装"
                    ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行，点击打开并启动服务"
                    ShizukuState.PERMISSION_DENIED -> "点击申请授权，以 adb shell 身份执行系统命令"
                    ShizukuState.READY -> "AI 可通过 adb shell 执行 pm / am / cmd 等系统命令"
                },
                granted = shizukuState == ShizukuState.READY,
                statusText = when (shizukuState) {
                    ShizukuState.NOT_INSTALLED -> "未安装"
                    ShizukuState.NOT_RUNNING -> "未运行"
                    ShizukuState.PERMISSION_DENIED -> "未授权"
                    ShizukuState.READY -> "已授权"
                },
                onClick = {
                    when (shizukuState) {
                        ShizukuState.NOT_INSTALLED,
                        ShizukuState.NOT_RUNNING -> ShizukuManager.openShizukuApp()
                        ShizukuState.PERMISSION_DENIED -> ShizukuManager.requestPermission()
                        ShizukuState.READY -> ShizukuManager.refreshState()
                    }
                }
            )
            RowDivider()
            PermissionRow(
                icon = Icons.Outlined.Shield,
                title = "自启动管理",
                // 自启动没有标准 API，各家 ROM 入口不同、状态也读不到，故不显示已开启/未开启
                granted = null,
                subtitle = "部分系统会限制后台自启，可在系统设置中放开",
                onClick = { openSafely(context, autoStartIntent(context)) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 分区标题：小字标签 + 右侧分隔线。 */
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

/** 项间分隔线：左缩进 28（图标 18 + 间距 10）。 */
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
 * 权限项：`[图标] 标题 / 说明` + 右侧状态或「去开启 ›」。
 *
 * [granted] 为 null 表示该系统项**无法读取状态**（如自启动），此时只显示说明与跳转入口。
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean?,
    statusText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            statusText ?: when (granted) {
                true -> "已开启"
                false -> "去开启"
                null -> "去设置"
            },
            color = if (granted == true) TextSecondary else Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(" ›", color = if (granted == true) Border else Accent, fontSize = 14.sp)
    }
}

// ═══════════════════════════════════════════════════════════
//  状态读取
// ═══════════════════════════════════════════════════════════

/** 通知是否已对本应用开启（API 24+ 用 areNotificationsEnabled）。 */
private fun notificationsEnabled(context: Context): Boolean =
    runCatching {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

/** 本应用的无障碍服务是否已在系统里启用。 */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, com.wy.openx.service.AgentAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    // 系统存的是扁平的 "pkg/cls:pkg/cls" 串，需逐个比对（还要兼容 cls 前缀省略的写法）
    return enabled.split(':').any { entry ->
        val comp = ComponentName.unflattenFromString(entry) ?: return@any false
        comp == expected
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

private fun canDrawOverlays(context: Context): Boolean =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

private fun canInstallUnknownApps(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    } else true

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

/**
 * 能否枚举设备上已安装的应用。
 *
 * 实现同口径（`getInstalledApplications(0).size > 1`）——**这是个启发式判断**：
 * Android 11+ 若应用未声明 `QUERY_ALL_PACKAGES` 或未被授予该权限，`getInstalledApplications`
 * 只会返回本应用与极少数包，数量就可能 ≤1。
 *
 * 注意：openx 已声明 `QUERY_ALL_PACKAGES`（见 AndroidManifest），所以这项**本来就是通的**，
 * 不需要用户额外授权。此项保留，并让用户能自查。
 */
private fun installedAppsReadable(context: Context): Boolean =
    runCatching { context.packageManager.getInstalledApplications(0).size > 1 }.getOrDefault(false)

// ═══════════════════════════════════════════════════════════
//  跳转 Intent
// ═══════════════════════════════════════════════════════════

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun unknownSourcesIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else appDetailsIntent(context)

private fun batteryOptimizationIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun overlayIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun allFilesAccessIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else appDetailsIntent(context)

/**
 * 自启动管理：**没有标准 API**，各家 ROM 的 Activity 名称不同，只能逐个尝试常见入口，
 * 全部失败则退到应用详情页（用户可从中找到厂商的权限入口）。
 * 这是 Android 生态的既有缺陷，不是实现取巧。
 */
private fun autoStartIntent(context: Context): Intent {
    val candidates = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
    )
    for ((pkg, cls) in candidates) {
        val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) != null) return intent
    }
    return appDetailsIntent(context)
}

/**
 * 安全跳转：目标系统页可能不存在（部分 ROM 阉割了某个 Activity），
 * 直接 startActivity 会抛 ActivityNotFoundException 让 App 崩溃。
 * 故按 [intent] → [fallback] → 应用详情页 逐级降级。
 */
private fun openSafely(context: Context, intent: Intent, fallback: Intent? = null) {
    val chain = listOfNotNull(intent, fallback, appDetailsIntent(context))
    for (i in chain) {
        if (runCatching { context.startActivity(i) }.isSuccess) return
    }
}
