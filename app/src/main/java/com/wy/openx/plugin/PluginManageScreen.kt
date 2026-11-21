package com.wy.openx.plugin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.wy.openx.plugin.ui.PluginOverlayWindow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import com.wy.openx.plugin.PluginPermission
import com.wy.openx.plugin.PluginSetting
import com.wy.openx.plugin.compat.OperitCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.rememberSlidableGroupState
import com.wy.openx.ui.screen.ManageCard
import com.wy.openx.ui.screen.ManageConfirmDialog
import com.wy.openx.ui.screen.ManageDetailDialog
import com.wy.openx.ui.screen.ManageEmpty
import com.wy.openx.ui.screen.GuideDialog
import com.wy.openx.ui.screen.ManageTag
import com.wy.openx.ui.screen.ManageTextField
import com.wy.openx.ui.screen.ManageTopBar
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 插件管理页。
 *
 * 卡片结构、左滑删除、详情弹层、「+」从底部弹出的导入抽屉，全部**刻意对齐
 * [com.wy.openx.ui.screen.SkillManageScreen]**：同一形态的列表页在 OpenX 里已有四个
 * （记忆 / MCP / 技能 / 工作流），插件是第五个，观感要一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManageScreen(
    plugins: List<InstalledPlugin>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onImportJsText: (fileName: String, source: String) -> Unit = { _, _ -> },
    /** 从压缩包（.zip / .tar / .tar.gz / .tgz）安装，包内需含 plugin.json。 */
    onImportArchive: (uri: Uri, fileName: String) -> Unit = { _, _ -> },
    /** 打开插件的自定义界面（仅声明了 ui 的插件可点）。 */
    onOpenPluginUi: (InstalledPlugin) -> Unit = {},
    /** 点击详情页里的快捷入口（打开界面 / 填入提示词 / 用浏览器打开网址）。 */
    onQuickAction: (PluginQuickAction) -> Unit = {},
    /** 服务运行状态：pluginId → 监听端口（不在表 = 未运行）。 */
    servicePorts: Map<String, Int> = emptyMap(),
    serviceStarting: Set<String> = emptySet(),
    serviceErrors: Map<String, String> = emptyMap(),
    /** 启动服务型插件（声明了 service 的插件）。 */
    onStartService: (String) -> Unit = {},
    /** 停止服务型插件。 */
    onStopService: (String) -> Unit = {},
    /** 重新探测服务状态（容器里的进程可能是从插件面板里起的，宿主没有它的记录）。 */
    onRefreshServiceStatus: (String) -> Unit = {},
    /** 插件 env 快照：pluginId → (key → value)。清单声明 settings 后由宿主渲染，值存在这里。 */
    pluginEnv: Map<String, Map<String, String>> = emptyMap(),
    /** 改一项配置：pluginId, key, value。 */
    onUpdatePluginSetting: (String, String, String) -> Unit = { _, _, _ -> },
    /** 最近一次导入的结果；非 null 时弹结果窗。 */
    installResult: PluginInstallResult? = null,
    onDismissInstallResult: () -> Unit = {},
    errorOf: (String) -> String? = { null }
) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<InstalledPlugin?>(null) }
    var uninstallTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    /** 从 content uri 取文件名（两个 picker 共用）。 */
    fun displayNameOf(uri: Uri, fallback: String): String =
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: fallback

    // 导入单文件 .js：读成文本后交给上层落盘
    val jsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = displayNameOf(uri, "plugin.js")
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            onImportJsText(name, text)
        }
    }

    // 压缩包导入：交给 ViewModel 落临时文件 + ArchiveExtractor 解压
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImportArchive(uri, displayNameOf(uri, "plugin.zip"))
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        ManageTopBar(title = "插件管理", onBack = onBack, onGuide = { showGuide = true }) {
            IconButton(onClick = { showImportSheet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "导入插件", tint = Accent)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (plugins.isEmpty()) {
            ManageEmpty("暂无插件。点右上角「+」导入 .js 或压缩包，或将插件目录放入 .openx/plugins。")
        } else {
            // 同组互斥：滑开新卡片时旧卡片自动收起（与技能/会话列表一致）
            val slideGroup = rememberSlidableGroupState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(plugins, key = { it.manifest.id }) { plugin ->
                    val err = plugin.error ?: errorOf(plugin.manifest.id)
                    SlidableRow(
                        itemId = plugin.manifest.id,
                        groupState = slideGroup,
                        actions = listOf(
                            SlideAction(
                                icon = Icons.Outlined.Delete,
                                tint = Color(0xFFFF6B6B)
                            ) { uninstallTarget = plugin }
                        ),
                        onFullSwipe = { uninstallTarget = plugin }
                    ) {
                        ManageCard(
                            icon = Icons.Outlined.Extension,
                            title = plugin.manifest.name,
                            summary = plugin.manifest.description.ifBlank { plugin.manifest.id },
                            // 启用状态只放徽标行（对齐 MCP 卡片）；底部 meta 留给运行态
                            // （加载失败 / 服务端口 / 工具数），不再重复「已启用/已停用」。
                            meta = servicePorts[plugin.manifest.id].let { port ->
                                when {
                                    err != null -> "加载失败：$err"
                                    // port == 0：后台脚本没让宿主开端口（只负责管容器
                                    // 里的常驻进程），不能写成 127.0.0.1:0。
                                    port != null ->
                                        if (port > 0) "服务运行中 · 127.0.0.1:$port" else "后台运行中"
                                    plugin.toolNames.isNotEmpty() -> "${plugin.toolNames.size} 个工具"
                                    else -> ""
                                }
                            },
                            metaColor = if (err != null) Color(0xFFFF6B6B) else null,
                            onClick = { detail = plugin },
                            // 徽标改放标题**下方独立一行**：插件名普遍偏长，
                            // 跟在标题同行时会被长名字挤掉（真机看到“界面/启用”消失）。
                            titleBadges = {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 有界面的插件：标出来，提示可点开
                                    if (plugin.manifest.ui.isNotBlank()) {
                                        ManageTag("界面", Accent)
                                    }
                                    // 有后台的插件（服务或定时任务）：运行中绿标，未运行灰标
                                    if (plugin.manifest.hasBackground) {
                                        val port = servicePorts[plugin.manifest.id]
                                        if (port != null) {
                                            ManageTag(
                                                if (port > 0) "服务:$port" else "后台运行中",
                                                Color(0xFF66BB6A)
                                            )
                                        } else {
                                            ManageTag("服务未启动", TextSecondary)
                                        }
                                    }
                                    ManageTag(
                                        if (plugin.enabled) "已启用" else "已停用",
                                        if (plugin.enabled) Accent else TextSecondary
                                    )
                                }
                            },
                            trailing = {
                                Switch(
                                    checked = plugin.enabled,
                                    onCheckedChange = { onToggle(plugin.manifest.id, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White, checkedTrackColor = Accent,
                                        uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
                                    )
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        )
                    }
                }
            }
        }
    }

    // 详情弹层（内容不要自带 verticalScroll —— ManageDetailDialog 内部已滚动，
    // 再套一层会触发 "infinity maximum height constraints" 崩溃）
    detail?.let { plugin ->
        // 详情分两页：管理（只读信息）/ 配置（能动手的操作）。
        // remember 带插件 id：换一个插件重开时回到第一页，同一插件重开保持上次的页。
        var detailTab by remember(plugin.manifest.id) { mutableStateOf(0) }
        ManageDetailDialog(
            title = plugin.manifest.name,
            onDismiss = { detail = null },
            // 不传 onDelete：删除统一走卡片左滑
            // tab 条走 subHeader 而不是 content：content 在 verticalScroll 里，
            // 让切换条跟着内容滚走会很难受。
            subHeader = {
                PluginDetailTabBar(selected = detailTab, onSelect = { detailTab = it })
            }
        ) {
            if (detailTab == 1) {
                PluginConfigTab(
                    plugin = plugin,
                    port = servicePorts[plugin.manifest.id],
                    starting = plugin.manifest.id in serviceStarting,
                    startError = serviceErrors[plugin.manifest.id],
                    env = pluginEnv[plugin.manifest.id].orEmpty(),
                    onStartService = { onStartService(plugin.manifest.id) },
                    onStopService = { onStopService(plugin.manifest.id) },
                    onRefreshStatus = { onRefreshServiceStatus(plugin.manifest.id) },
                    onOpenUi = { onOpenPluginUi(plugin); detail = null },
                    onUpdateSetting = { k, v -> onUpdatePluginSetting(plugin.manifest.id, k, v) }
                )
            } else {
                PluginInfoTab(
                    plugin = plugin,
                    errorOf = errorOf,
                    onOpenUi = { onOpenPluginUi(plugin); detail = null },
                    onQuickAction = onQuickAction
                )
            }
        }
    }

    uninstallTarget?.let { plugin ->
        ManageConfirmDialog(
            title = "卸载插件",
            message = "确定卸载「${plugin.manifest.name}」？插件目录及脚本将被删除，此操作不可撤销。",
            confirmLabel = "卸载",
            onConfirm = { onUninstall(plugin.manifest.id); uninstallTarget = null },
            onDismiss = { uninstallTarget = null }
        )
    }

    installResult?.let { result ->
        PluginInstallResultDialog(result = result, onDismiss = onDismissInstallResult)
    }

    // 从下往上弹出的导入抽屉（与技能页「添加技能」同款）
    if (showGuide) {
        GuideDialog(
            title = "插件管理说明",
            blocks = listOf(
                "插件是什么" to "本地 JS 脚本扩展，可向 AI 注册新工具、提供面板界面或后台服务。",
                "存放位置" to ".openx/plugins/<id>/，清单为 plugin.json；启停名单在 plugins.json（只存禁用项）。",
                "怎么装" to "点右上角「+」导入 .js 单文件或含 plugin.json 的压缩包；左滑卡片可删除。",
                "权限" to "需要的能力（overlay / system / server 等）要在 plugin.json 的 permissions 里声明，否则调用会被拒。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)
            ) {
                Text(
                    "添加插件",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PluginImportActionRow(
                    icon = Icons.Outlined.Code,
                    label = "添加 .js 插件",
                    subtitle = "从手机目录选择单个 JS 文件导入",
                    onClick = {
                        showImportSheet = false
                        jsPicker.launch(
                            arrayOf(
                                "text/javascript", "application/javascript",
                                "text/plain", "application/octet-stream"
                            )
                        )
                    }
                )
                PluginImportActionRow(
                    icon = Icons.Outlined.Archive,
                    label = "添加压缩包插件",
                    subtitle = "选择 zip / tar.gz 压缩包，自动解压安装到 .openx/plugins",
                    onClick = {
                        showImportSheet = false
                        archivePicker.launch(
                            arrayOf(
                                "application/zip", "application/gzip", "application/x-gzip",
                                "application/x-tar", "application/octet-stream"
                            )
                        )
                    }
                )
            }
        }
    }
}

/** 抽屉里的一行导入动作（样式对齐技能页的 SkillImportActionRow）。 */
@Composable
private fun PluginImportActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TextPrimary, fontSize = 12.sp)
    }
}

/**
 * 插件导入结果弹窗。
 *
 * 三档结论刻意分开：装上了但兼容层诊断出警告的包，如果只说「安装成功」，
 * 用户会以为功能都齐了，等用到不支持的 API 才发现。所以「能装但有坑」单独一档。
 */
@Composable
private fun PluginInstallResultDialog(
    result: PluginInstallResult,
    onDismiss: () -> Unit
) {
    val title = when {
        result.entries.isEmpty() && result.failures.isEmpty() -> "未找到插件"
        result.entries.isEmpty() -> "安装失败"
        result.hasWarnings || result.failures.isNotEmpty() -> "安装完成（部分不兼容）"
        else -> "安装成功"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (result.entries.isEmpty() && result.failures.isEmpty()) {
                    Text("压缩包内没有找到插件（需含 plugin.json 或 operit 的 manifest.json）。", color = TextSecondary)
                }
                result.entries.forEach { entry ->
                    Row {
                        Text("✓ ", color = Accent, fontWeight = FontWeight.Bold)
                        Text(entry.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    entry.warnings.forEach { w ->
                        Text("⚠ $w", color = Color(0xFFE0A030), fontSize = 12.sp)
                    }
                }
                result.failures.forEach { f ->
                    Row {
                        Text("✗ ", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                        Text(f.source, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Text(f.reason, color = TextSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = Accent) }
        },
        containerColor = Panel
    )
}

/**
 * 详情页「插件管理」：只读信息（清单、权限、工具、快捷入口、兼容诊断）。
 *
 * 从 [PluginManageScreen] 里拆出来有两个原因：一是详情页要分两页显示，
 * 二是那个函数已经很长，继续往里塞会重演 SettingsScreen 的 64KB 方法上限。
 */
@Composable
private fun PluginInfoTab(
    plugin: InstalledPlugin,
    errorOf: (String) -> String?,
    onOpenUi: () -> Unit,
    onQuickAction: (PluginQuickAction) -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val hasUi = plugin.manifest.ui.isNotBlank()
        // 界面入口放最上面：详情里最常用的动作，原来压在权限清单后面，要滚一屏才点到
        if (hasUi) {
            androidx.compose.material3.TextButton(
                onClick = onOpenUi,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开界面", color = Accent)
            }
        }
        DetailRow("ID", plugin.manifest.id)
        if (plugin.manifest.version.isNotBlank()) DetailRow("版本", plugin.manifest.version)
        if (plugin.manifest.author.isNotBlank()) DetailRow("作者", plugin.manifest.author)
        DetailRow("目录", plugin.dirPath)
        DetailRow("入口", plugin.manifest.main)
        // 界面入口：声明了 ui 才能点
        DetailRow(
            "界面",
            if (hasUi) plugin.manifest.ui else "（无界面）"
        )
        // 权限清单：危险项标红，让用户一眼看到联网/读配置的能力
        val perms = plugin.manifest.grantedPermissions.sortedByRisk()
        if (perms.isNotEmpty()) {
            Column {
                Text(
                    "权限",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                perms.forEach { p ->
                    Text(
                        "• ${p.label}",
                        color = if (p.dangerous) Color(0xFFFFA726) else TextPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            DetailRow("权限", "无（不能联网/读写配置）")
        }
        if (plugin.toolNames.isNotEmpty()) {
            DetailRow("注册工具", plugin.toolNames.joinToString("、"))
        }
        plugin.manifest.quickActions.takeIf { it.isNotEmpty() }?.let { qa ->
            Column {
                Text(
                    "快捷入口",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                qa.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(Panel, RoundedCornerShape(8.dp))
                            .clickable {
                                // ownerPluginId 由清单组装时填入，但详情页里
                                // 手边就有 plugin，直接补上更保险
                                item.ownerPluginId = plugin.manifest.id
                                onQuickAction(item)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.label.orEmpty(),
                            color = Accent,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                item.opensUi -> "打开界面"
                                item.opensUrl -> item.url.orEmpty()
                                else -> "填入提示词"
                            },
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        (plugin.error ?: errorOf(plugin.manifest.id))?.let {
            Text("加载错误：$it", color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }
        if (plugin.manifest.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(plugin.manifest.description, color = TextSecondary, fontSize = 12.sp)
        }
        // Operit 兼容包：列出在 OpenX 下不生效的能力，避免「装了但某些功能没反应」时无处可查。
        if (plugin.manifest.compat == OperitCompat.COMPAT_TAG) {
            val store = remember { PluginStore(context) }
            var report by remember(plugin.manifest.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(plugin.manifest.id) {
                // 首次可能要扫全目录生成报告，不占 UI 线程
                report = withContext(Dispatchers.IO) {
                    runCatching { store.readCompatReport(plugin) }.getOrNull()
                }
            }
            report?.let { text ->
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text("兼容性诊断", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    text.lineSequence().filter { it.isNotBlank() }.forEach { raw ->
                        val line = raw.trimStart()
                        Text(
                            line,
                            color = when {
                                line.startsWith("⚠") -> Color(0xFFFFA726)
                                line.startsWith("·") -> TextSecondary
                                else -> TextPrimary
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 详情页的两页切换条（插件管理 / 插件配置）。
 *
 * 没有用 Material3 的 TabRow：它自带指示器与 48dp 最小高度，塞进
 * ManageDetailDialog 的 subHeader 里会把标题和正文挤开。这里用等宽胶囊分段控件，
 * 高度可控，观感也和页内其它 ManageTag 一致。
 */
@Composable
private fun PluginDetailTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(Panel, RoundedCornerShape(9.dp))
            .padding(3.dp)
    ) {
        listOf("插件管理", "插件配置").forEachIndexed { index, label ->
            val active = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Accent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(7.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) Accent else TextSecondary
                )
            }
        }
    }
}

/**
 * 详情页「插件配置」：能动手的操作都收在这里。
 *
 * 四块内容，按「先看状态、再调设置、最后是附属信息」排：
 *  1. 服务状态 + 启停 + 刷新状态（插件有后台脚本时）
 *  2. 清单声明的 `settings` —— 宿主渲染成原生控件，值写进插件 env
 *  3. 「打开配置界面」—— 插件自己的界面里往往还有更复杂的配置
 *  4. 定时任务清单
 */
@Composable
private fun PluginConfigTab(
    plugin: InstalledPlugin,
    port: Int?,
    starting: Boolean,
    startError: String?,
    env: Map<String, String>,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRefreshStatus: () -> Unit,
    onOpenUi: () -> Unit,
    onUpdateSetting: (String, String) -> Unit
) {
    val running = port != null
    // 自启开关只给有浮窗能力的插件看：没声明 overlay 权限的插件根本不会开浮窗
    val ctx = LocalContext.current
    val canOverlay = plugin.manifest.grantedPermissions.contains(PluginPermission.OVERLAY)
    var autoStart by remember(plugin.manifest.id) {
        mutableStateOf(PluginOverlayWindow.isAutoStartEnabled(ctx, plugin))
    }
    // 有没有真正的 HTTP 服务：没声明 service、或没给 SERVER 权限的是「脚本自管进程」型，
    // 这类插件 port 恒为 0，不能说「运行中 127.0.0.1:0」，也不能提「默认端口」。
    val hasService = plugin.manifest.service.isNotBlank()
    val runsServer = hasService &&
        plugin.manifest.grantedPermissions.contains(PluginPermission.SERVER)
    // key 为空的设置项存不了值，直接不渲染
    val settings = plugin.manifest.settingItems.filter { it.key.orEmpty().isNotBlank() }
    val schedules = plugin.manifest.scheduleTasks
    val hasUi = plugin.manifest.ui.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (plugin.manifest.hasBackground) {
            // 状态块放按钮上方：进这页第一眼要知道现在是不是跑着
            Column(
                Modifier.fillMaxWidth()
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    when {
                        starting -> "正在启动…"
                        running -> "运行中"
                        startError != null -> "启动失败"
                        else -> "未启动"
                    },
                    color = when {
                        startError != null -> Color(0xFFFF6B6B)
                        running -> Color(0xFF66BB6A)
                        else -> TextSecondary
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        port != null && port > 0 -> "监听 127.0.0.1:" + port
                        port != null && hasService -> "已启动（后台运行）"
                        port != null -> "已启动（未监听端口）"
                        runsServer -> "启动后监听 127.0.0.1:" +
                            (if (plugin.manifest.port > 0) plugin.manifest.port else 18899)
                        hasService -> "启动后在后台运行"
                        else -> scheduleSummary(plugin).ifBlank { "未监听端口" }
                    },
                    color = TextSecondary, fontSize = 11.sp
                )
            }

            if (!starting && startError != null) {
                Text(startError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }

            androidx.compose.material3.Button(
                onClick = { if (running) onStopService() else onStartService() },
                enabled = !starting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFFF6B6B) else Accent,
                    contentColor = Color.White
                )
            ) {
                Text(
                    when {
                        starting -> "正在启动…"
                        // 对用户而言都是「一个一直在后台跑的东西」，不必区分
                        // 宿主开端口还是脚本自管容器进程——那是实现细节。
                        !running && hasService -> "启动服务"
                        !running -> "启动定时任务"
                        hasService -> "停止服务"
                        else -> "停止定时任务"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 刷新状态：容器的常驻进程可能是从插件面板里起的，宿主没有它的运行记录，
            // 光看启动那一刻的状态会一直显示「未启动」。
            TextButton(
                onClick = onRefreshStatus,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("刷新状态", color = Accent, fontSize = 13.sp)
            }
        }

        if (settings.isNotEmpty()) {
            PluginSettingsSection(settings, env, onUpdateSetting)
        }

        if (hasUi) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "这个插件还有自己的界面，账号、端口这类更细的配置在里面。",
                    color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp
                )
                OutlinedButton(
                    onClick = onOpenUi,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("打开配置界面", color = Accent, fontSize = 13.sp)
                }
            }
        }

        if (canOverlay) {
            // 浮窗活在宿主进程里，进程一死就没了。这个开关决定「下次打开 App 时
            // 要不要把它拉回来」——只在上次退出时它还开着的情况下生效。
            Column(
                Modifier.fillMaxWidth()
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("打开 App 时自动显示", color = TextPrimary, fontSize = 13.sp)
                        Text(
                            "上次退出时浮窗还开着、这次也没被收回，才会自动拉回来。" +
                                "App 被系统杀掉后不会自己重启。",
                            color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            PluginOverlayWindow.setAutoStartEnabled(ctx, plugin, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = Accent,
                            uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
                        )
                    )
                }
            }
        }

        if (schedules.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("定时任务", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                schedules.forEach { t ->
                    Text(
                        "· " + listOfNotNull(
                            t.id.takeIf { it.isNotBlank() },
                            t.every.takeIf { it.isNotBlank() }?.let { "每 " + it },
                            t.at.takeIf { it.isNotBlank() }?.let { "每天 " + it }
                        ).joinToString(" · "),
                        color = TextPrimary, fontSize = 12.sp
                    )
                }
            }
        }

        if (!plugin.manifest.hasBackground && settings.isEmpty() && !hasUi) {
            Text(
                "这个插件没有可配置的项（清单里没有 service / schedule / settings，也没有界面）。",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp
            )
        }
    }
}

/**
 * 清单声明的配置项，全部由**宿主**渲染成原生控件。
 *
 * 插件脚本不参与渲染：宿主不必执行任何插件代码就能画出这页，安全模型和别处一致；
 * 值写进插件 env，插件侧 `ctx.getEnv(key)` 直接读。
 */
@Composable
private fun PluginSettingsSection(
    settings: List<PluginSetting>,
    env: Map<String, String>,
    onUpdate: (String, String) -> Unit
) {
    // 相邻同 group 的归到一个小节；LinkedHashMap 保留清单里的书写顺序
    val groups = LinkedHashMap<String, MutableList<PluginSetting>>()
    settings.forEach { groups.getOrPut(it.group.orEmpty().trim()) { mutableListOf() }.add(it) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        groups.forEach { (groupTitle, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (groupTitle.isNotEmpty()) {
                    Text(
                        groupTitle,
                        color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                items.forEach { item ->
                    PluginSettingRow(item, env[item.key.orEmpty()].orEmpty(), onUpdate)
                }
            }
        }
    }
}

/** 单个配置项。认不出的 type 按文本框渲染，不会让整页画不出来。 */
@Composable
private fun PluginSettingRow(
    item: PluginSetting,
    value: String,
    onUpdate: (String, String) -> Unit
) {
    val key = item.key.orEmpty()
    val title = item.title.orEmpty()
    val desc = item.desc.orEmpty()
    val isNumber = item.type.orEmpty().equals("number", ignoreCase = true)

    when (item.type.orEmpty().lowercase(Locale.ROOT)) {
        "switch" -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (title.isNotEmpty()) Text(title, color = TextPrimary, fontSize = 13.sp)
                if (desc.isNotEmpty()) {
                    Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onUpdate(key, it.toString()) }
            )
        }

        "segment" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title.isNotEmpty()) Text(title, color = TextPrimary, fontSize = 13.sp)
            if (desc.isNotEmpty()) {
                Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.options.orEmpty().forEach { opt ->
                    val optionValue = opt.value.orEmpty()
                    val selected = optionValue == value
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) Accent.copy(alpha = 0.18f) else Panel,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onUpdate(key, optionValue) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opt.label.orEmpty().ifBlank { optionValue },
                            fontSize = 12.sp,
                            color = if (selected) Accent else TextSecondary,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        "slider" -> {
            val lower = (item.min ?: 0.0).toFloat()
            val upper = (item.max ?: 100.0).toFloat().coerceAtLeast(lower)
            val committed = (value.toFloatOrNull() ?: lower).coerceIn(lower, upper)
            // 拖动期间只动本地草稿：每帧 onUpdate 等于每帧落盘 + 整页重组，手指会拖不动。
            var draft by remember(key, committed) { mutableStateOf(committed) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        color = TextPrimary, fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(draft.roundToInt().toString(), color = TextSecondary, fontSize = 12.sp)
                }
                if (desc.isNotEmpty()) {
                    Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onUpdate(key, draft.roundToInt().toString()) },
                    valueRange = lower..upper
                )
            }
        }

        else -> {
            // text / number / 认不出的类型都走这里。
            // 输入过程不逐字写盘：先存本地，停手 500ms 再落 env。
            // key 带上 value：默认值是进页后异步补写的，补上后要把草稿回填（否则显示空）。
            var draft by remember(key, value) { mutableStateOf(value) }
            LaunchedEffect(draft) {
                if (draft != value) {
                    delay(500)
                    onUpdate(key, draft)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (desc.isNotEmpty()) {
                    Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                ManageTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = title.ifBlank { item.placeholder.orEmpty().ifBlank { key } },
                    keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text
                )
            }
        }
    }
}

/** 把定时任务压成一行摘要，如「每 10m」；没有可读描述时返回空串。 */
private fun scheduleSummary(plugin: InstalledPlugin): String =
    plugin.manifest.scheduleTasks
        .mapNotNull { t ->
            when {
                t.every.isNotBlank() -> "每 " + t.every
                t.at.isNotBlank() -> "每天 " + t.at
                else -> t.id.takeIf { it.isNotBlank() }
            }
        }
        .distinct()
        .joinToString("、")
