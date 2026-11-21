package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.rememberSlidableGroupState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wy.openx.data.store.McpScope
import com.wy.openx.data.store.McpServerConfig
import com.wy.openx.data.store.McpServerEntry
import com.wy.openx.data.store.McpTestResult
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFgOnFill
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

data class McpPreset(
    val name: String,
    val command: String,
    val args: String,
    val description: String
)

private val communityPresets = listOf(
    McpPreset("mcp-filesystem", "python3", "-m mcp_filesystem", "文件系统操作：读/写/编辑文件，遍历目录"),
    McpPreset("mcp-puppeteer", "npx", "-y @anthropic/mcp-puppeteer", "浏览器自动化：截图、点击、表单填写、JS 执行"),
    McpPreset("mcp-sqlite", "uvx", "mcp-server-sqlite --db /sdcard/data.db", "SQLite 数据库：查询、创建表、插入数据"),
    McpPreset("mcp-github", "uvx", "mcp-server-github", "GitHub API：Issue/PR/代码搜索/仓库管理"),
    McpPreset("mcp-fetch", "uvx", "mcp-server-fetch", "网页抓取：获取网页内容，处理重定向"),
    McpPreset("mcp-memory", "uvx", "mcp-server-memory", "持久化记忆：基于文件的 key-value 存储"),
    McpPreset("mcp-python-exec", "python3", "-c \"import sys,json,subprocess; from mcp.server import Server\"", "Python 代码执行沙箱（需安装 mcp 包）"),
    McpPreset("mcp-brave-search", "uvx", "mcp-server-brave-search", "Brave 搜索引擎 API 调用"),
    McpPreset("mcp-sequential-thinking", "npx", "-y @anthropic/sequential-thinking", "思维链推理：复杂问题的逐步逻辑推导和决策分析"),
    McpPreset("mcp-playwright", "npx", "-y @anthropic/mcp-playwright", "Playwright 浏览器自动化：截图、点击、表单、JS 执行"),
    McpPreset("mcp-postgres", "npx", "-y @anthropic/mcp-postgres", "PostgreSQL 数据库：查询、建表、数据 CRUD"),
    McpPreset("mcp-slack", "npx", "-y @anthropic/mcp-slack", "Slack 集成：发送消息、频道管理、用户查询"),
    McpPreset("mcp-google-maps", "npx", "-y @anthropic/mcp-google-maps", "Google Maps API：地理编码、地点搜索、路线规划"),
    McpPreset("mcp-time", "npx", "-y @anthropic/mcp-time", "时间和时区工具：获取当前时间、时区转换、日期计算"),
    McpPreset("mcp-docker", "npx", "-y @anthropic/mcp-docker", "Docker 容器管理：列出/启动/停止容器、镜像管理"),
    McpPreset("mcp-kubernetes", "npx", "-y @anthropic/mcp-kubernetes", "Kubernetes 集群管理：Pod/Service/Deployment 操作"),
    McpPreset("mcp-everything", "npx", "-y @anthropic/mcp-everything", "Everything 测试服务器：MCP 协议全功能测试/示例"),
    McpPreset("mcp-aws-s3", "npx", "-y @anthropic/mcp-aws-s3", "AWS S3 存储：上传/下载/列出/删除 S3 对象"),
    McpPreset("mcp-notion", "npx", "-y @anthropic/mcp-notion", "Notion API：页面创建/更新、数据库查询、块操作"),
    McpPreset("mcp-jira", "npx", "-y @anthropic/mcp-jira", "Jira 项目管理：Issue 操作、Sprint 管理、搜索"),
    McpPreset("mcp-redis", "uvx", "mcp-server-redis", "Redis 缓存：键值读写、过期管理、发布订阅"),
    McpPreset("mcp-sentry", "uvx", "mcp-server-sentry", "Sentry 错误监控：Issue 查询、事件详情、性能追踪"),
    McpPreset("mcp-cloudflare", "npx", "-y @anthropic/mcp-cloudflare", "Cloudflare API：DNS 管理、Worker 部署、KV 存储")
)

/**
 * MCP 管理：配置 AI 可调用的外部工具服务（stdio 本地进程 / HTTP 远程 / 内置 QuickJS）。
 *
 * 支持两级作用域（全局 / 当前工作区「项目」），列表条目标注作用域；点击卡片进入
 * 就地编辑弹窗（基础设置 + 单工具开关两个 Tab），右上角「+」添加、
 * 「🧪」社区预设一键添加，左滑删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManageScreen(
    entries: List<McpServerEntry>,
    onBack: () -> Unit,
    onAdd: (McpServerConfig, McpScope) -> Unit,
    onUpdate: (oldName: String, oldScope: McpScope, config: McpServerConfig, newScope: McpScope) -> Unit,
    onDelete: (name: String, scope: McpScope) -> Unit,
    onToggle: (name: String, scope: McpScope, enabled: Boolean) -> Unit,
    onTest: (String) -> Unit = {},
    testResults: Map<String, McpTestResult> = emptyMap(),
    testing: Set<String> = emptySet(),
    onLeave: () -> Unit = {}
) {
    // 离开页面时释放会话占用，让后台空闲回收重新接管
    DisposableEffect(Unit) { onDispose { onLeave() } }

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServerEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<McpServerEntry?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        ManageTopBar(title = "MCP 管理", onBack = onBack, onGuide = { showGuide = true }) {
            IconButton(onClick = { showPresets = true }) {
                Icon(Icons.Outlined.Science, contentDescription = "社区预设", tint = Accent)
            }
            IconButton(onClick = {
                editing = null
                showEditor = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加 Server", tint = Accent)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            ManageEmpty("暂无 MCP Server。点右上角「+」手动添加，或点「🧪」展开社区预设一键添加。")
        } else {
            val slideGroup = rememberSlidableGroupState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.config.name }) { entry ->
                    val server = entry.config
                    SlidableRow(
                        itemId = server.name,
                        groupState = slideGroup,
                        actions = listOf(
                            SlideAction(icon = Icons.Outlined.Delete, tint = Color(0xFFFF6B6B)) {
                                deleteTarget = entry
                            }
                        ),
                        onFullSwipe = { deleteTarget = entry }
                    ) {
                        ManageCard(
                            icon = Icons.Outlined.Hub,
                            title = server.name,
                            summary = server.description.ifBlank {
                                when {
                                    server.isBuiltin -> "内置服务器（QuickJS，无需外部依赖）"
                                    server.isHttp -> "远程 HTTP：${server.url}"
                                    else -> "本地进程：${server.command} ${server.args.joinToString(" ")}"
                                }
                            },
                            onClick = {
                                editing = entry
                                showEditor = true
                            },
                            // 连接状态放标题同一行（不再占独立一行）：卡片少一行、更窄。
                            // 用紧凑文案（完整失败原因看详情页），避免与开关挤在一起。
                            titleSuffix = {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    mcpStatusText(server.name, testing, testResults, short = true),
                                    color = mcpStatusColor(server.name, testing, testResults),
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            },
                            // 徽标放标题**下方独立一行**，避免长名字把徽标挤掉（与插件卡片一致）。
                            titleBadges = {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ManageTag(
                                        if (server.enabled) "已启用" else "已停用",
                                        if (server.enabled) Accent else TextSecondary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    ManageTag(
                                        mcpScopeText(entry.scope),
                                        if (entry.scope == McpScope.GLOBAL) TextSecondary else Accent.copy(alpha = 0.85f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    ManageTag(mcpTypeText(server), TextSecondary)
                                }
                            },
                            trailing = {
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { onToggle(server.name, entry.scope, it) },
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

    if (showGuide) {
        GuideDialog(
            title = "MCP 管理说明",
            blocks = listOf(
                "MCP 是什么" to "让 AI 通过标准协议调用外部工具的接口。",
                "三种类型" to "内置（宿主自带）、HTTP（远程端点）、stdio（容器内本地进程）。",
                "全局 / 项目" to "可设为全局或仅当前工作区生效；同名时项目级优先于全局。",
                "怎么加" to "点「+」手动填写，或点🧪展开社区预设一键添加；左滑卡片可删除。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    deleteTarget?.let { entry ->
        ManageConfirmDialog(
            title = "确认删除",
            message = "确定要删除 ${mcpScopeText(entry.scope)} MCP Server「${entry.config.name}」吗？",
            confirmLabel = "确认删除",
            onConfirm = {
                deleteTarget = null
                onDelete(entry.config.name, entry.scope)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (showPresets) {
        ModalBottomSheet(
            onDismissRequest = { showPresets = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                Text(
                    "社区预设",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "一键添加常用 MCP Server（添加到当前工作区，可编辑时改作用域）。部分需要容器内已安装对应运行时（Node.js / Python uvx）。",
                    color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    items(communityPresets, key = { it.name }) { preset ->
                        val alreadyAdded = entries.any { it.config.name == preset.name }
                        Surface(
                            onClick = {
                                if (!alreadyAdded) {
                                    val argsList = preset.args.split("\\s+".toRegex()).filter { it.isNotBlank() }
                                    onAdd(
                                        McpServerConfig(name = preset.name, description = preset.description, command = preset.command, args = argsList),
                                        McpScope.PROJECT
                                    )
                                }
                            },
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                                    Text(preset.description, color = TextSecondary, fontSize = 11.sp)
                                    Text("${preset.command} ${preset.args}", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
                                }
                                if (alreadyAdded) {
                                    Text("已添加", color = Accent.copy(alpha = 0.6f), fontSize = 11.sp)
                                } else {
                                    Icon(Icons.Outlined.Add, contentDescription = "添加", tint = Accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val editingEntry = editing
        McpEditorDialog(
            initial = editingEntry,
            testResult = editingEntry?.let { testResults[it.config.name] },
            testing = editingEntry != null && editingEntry.config.name in testing,
            onRefreshTools = { editingEntry?.let { onTest(it.config.name) } },
            onDismiss = { showEditor = false },
            onSave = { config, scope ->
                if (editingEntry == null) {
                    onAdd(config, scope)
                } else {
                    onUpdate(editingEntry.config.name, editingEntry.scope, config, scope)
                }
                showEditor = false
            }
        )
    }
}

/**
 * MCP Server 添加 / 就地编辑弹窗（点开已装 Server 直接编辑）。
 *
 * Tab「基础设置」：作用域（全局/项目）、启用开关、名称、传输类型与各自字段；
 * Tab「工具」：拉取该 Server 的实时工具列表，逐工具开关写入 disabledTools（编辑态）。
 */
@Composable
private fun McpEditorDialog(
    initial: McpServerEntry?,
    testResult: McpTestResult?,
    testing: Boolean,
    onRefreshTools: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig, McpScope) -> Unit
) {
    val isEdit = initial != null
    var tab by remember { mutableIntStateOf(0) } // 0: 基础设置, 1: 工具

    var name by remember { mutableStateOf(initial?.config?.name ?: "") }
    var description by remember { mutableStateOf(initial?.config?.description ?: "") }
    var enabled by remember { mutableStateOf(initial?.config?.enabled ?: true) }
    // 作用域：新增默认当前工作区（项目），编辑保持原作用域。
    var scope by remember { mutableStateOf(initial?.scope ?: McpScope.PROJECT) }
    var isStdio by remember { mutableStateOf(initial?.config?.isStdio ?: false) }

    var url by remember { mutableStateOf(initial?.config?.url.orEmpty()) }
    var headers by remember {
        mutableStateOf(initial?.config?.headers?.entries?.joinToString(",") { "${it.key}=${it.value}" } ?: "")
    }
    var command by remember { mutableStateOf(initial?.config?.command.orEmpty()) }
    var args by remember { mutableStateOf(initial?.config?.args?.joinToString(" ") ?: "") }
    var env by remember {
        mutableStateOf(initial?.config?.env?.entries?.joinToString(",") { "${it.key}=${it.value}" } ?: "")
    }
    var disabledTools by remember { mutableStateOf(initial?.config?.disabledTools ?: emptySet()) }

    val canSave = name.isNotBlank() && (if (isStdio) command.isNotBlank() else url.isNotBlank())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            color = BgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isEdit) "编辑 MCP Server" else "添加 MCP Server",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isEdit) {
                        IconButton(onClick = onRefreshTools) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "刷新工具列表",
                                tint = if (testing) TextSecondary else Accent
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = TextPrimary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = { Text("基础设置", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent, selectedLabelColor = Color.White,
                            containerColor = Panel, labelColor = TextSecondary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("工具", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent, selectedLabelColor = Color.White,
                            containerColor = Panel, labelColor = TextSecondary
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {
                    if (tab == 0) {
                        // ── 作用域（全局 / 当前工作区） ──
                        Text("作用域", color = TextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = scope == McpScope.GLOBAL,
                                onClick = { scope = McpScope.GLOBAL },
                                label = { Text("全局", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent, selectedLabelColor = Color.White,
                                    containerColor = Panel, labelColor = TextSecondary
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = scope == McpScope.PROJECT,
                                onClick = { scope = McpScope.PROJECT },
                                label = { Text("当前工作区（项目）", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent, selectedLabelColor = Color.White,
                                    containerColor = Panel, labelColor = TextSecondary
                                )
                            )
                        }
                        if (scope == McpScope.PROJECT) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "项目级配置存于 workspace/.aicode/mcp.json，可容器内 AI 读写、随数据备份走。",
                                color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 是否启用 ──
                        Surface(
                            color = Panel,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "启用",
                                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { enabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White, checkedTrackColor = Accent,
                                        uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        ManageTextField(name, { name = it }, "Server 名称")
                        Spacer(Modifier.height(8.dp))
                        ManageTextField(description, { description = it }, "功能说明（可选，这个 Server 是干嘛的）")
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = isStdio,
                                onClick = { isStdio = true },
                                label = { Text("本地进程 (stdio)", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent, selectedLabelColor = Color.White,
                                    containerColor = Panel, labelColor = TextSecondary
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = !isStdio,
                                onClick = { isStdio = false },
                                label = { Text("远程 HTTP", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent, selectedLabelColor = Color.White,
                                    containerColor = Panel, labelColor = TextSecondary
                                )
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (isStdio) {
                            ManageTextField(command, { command = it }, "启动命令（如 python3 / npx / uvx）")
                            Spacer(Modifier.height(8.dp))
                            ManageTextField(args, { args = it }, "参数（空格分隔，如 -m mcp_filesystem）")
                            Spacer(Modifier.height(8.dp))
                            ManageTextField(env, { env = it }, "环境变量（K=V,K2=V2 可选，逗号分隔）")
                        } else {
                            ManageTextField(url, { url = it }, "HTTP 端点（如 https://host/mcp）")
                            Spacer(Modifier.height(8.dp))
                            ManageTextField(headers, { headers = it }, "请求头（k=v,k2=v2 可选，逗号分隔）")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "AI 调用格式：mcp(action=\"call_tool\", server=\"${
                                name.ifBlank { "服务名" }
                            }\", tool=\"工具名\", args={...})",
                            color = Accent.copy(alpha = 0.8f), fontSize = 11.sp
                        )
                    } else {
                        // ── 工具权限（disabledTools） ──
                        if (isEdit) {
                            if (testing) {
                                Text("正在连接并刷新工具列表…", color = TextSecondary, fontSize = 13.sp)
                            } else when (testResult) {
                                is McpTestResult.Ok -> {
                                    if (testResult.tools.isEmpty()) {
                                        Text("该 Server 没有可用工具。", color = TextSecondary, fontSize = 13.sp)
                                    } else {
                                        Text(
                                            "共 ${testResult.tools.size} 个工具 · 运行于 ${testResult.runtime}，关闭某个即对其禁用（AI 将无法调用）。",
                                            color = TextSecondary, fontSize = 12.sp
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        testResult.tools.forEach { tool ->
                                            val toolEnabled = tool.name !in disabledTools
                                            Surface(
                                                color = Panel,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            tool.name,
                                                            color = if (toolEnabled) TextPrimary else TextSecondary.copy(alpha = 0.6f),
                                                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                                        )
                                                        if (tool.description.isNotBlank()) {
                                                            Spacer(Modifier.height(2.dp))
                                                            Text(
                                                                tool.description,
                                                                color = TextSecondary, fontSize = 11.sp,
                                                                maxLines = 2
                                                            )
                                                        }
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                    Switch(
                                                        checked = toolEnabled,
                                                        onCheckedChange = { checked ->
                                                            disabledTools = if (checked) disabledTools - tool.name else disabledTools + tool.name
                                                        },
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = Color.White, checkedTrackColor = Accent,
                                                            uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
                                                        )
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
                                is McpTestResult.Fail -> {
                                    Text("连接失败：${testResult.message}", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                                }
                                null -> {
                                    Text("暂无连接结果。点右上角「刷新」测试连接以加载工具列表。", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        } else {
                            Text(
                                "保存并重新打开本 Server 后，可在这里逐个开关它的工具（禁用项 AI 无法调用）。",
                                color = TextSecondary, fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val config = if (isStdio) {
                                McpServerConfig(
                                    name = name.trim(),
                                    description = description.trim(),
                                    command = command.trim(),
                                    args = args.trim().split("\\s+".toRegex()).filter { it.isNotBlank() },
                                    env = parseKv(env),
                                    enabled = enabled,
                                    disabledTools = disabledTools
                                )
                            } else {
                                McpServerConfig(
                                    name = name.trim(),
                                    description = description.trim(),
                                    url = url.trim(),
                                    headers = parseKv(headers),
                                    enabled = enabled,
                                    disabledTools = disabledTools
                                )
                            }
                            onSave(config, scope)
                        },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFgOnFill),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(if (isEdit) "保存" else "添加", color = ButtonFgOnFill) }
                }
            }
        }
    }
}

/** 连接状态文本；[short] = 标题行内联用（紧凑、不带失败原因全文），默认给详情用。 */
private fun mcpStatusText(
    name: String,
    testing: Set<String>,
    results: Map<String, McpTestResult>,
    short: Boolean = false
): String = when {
    name in testing -> "正在连接…"
    results[name] is McpTestResult.Ok -> {
        val n = (results[name] as McpTestResult.Ok).tools.size
        if (short) "已连接·${n} 工具" else "连接成功 · ${n} 个工具"
    }
    // 带上失败原因（完整原因在详情页），便于一眼定位问题
    results[name] is McpTestResult.Fail ->
        if (short) "连接失败"
        else "连接失败 · " + (results[name] as McpTestResult.Fail).message
    else -> "未测试"
}

/** 连接状态颜色：成功绿 / 失败红 / 其余次级色。 */
private fun mcpStatusColor(
    name: String,
    testing: Set<String>,
    results: Map<String, McpTestResult>
): androidx.compose.ui.graphics.Color = when {
    name in testing -> TextSecondary
    results[name] is McpTestResult.Ok -> Color(0xFF4CAF50)
    results[name] is McpTestResult.Fail -> Color(0xFFFF6B6B)
    else -> TextSecondary
}

/** 作用域显示名：全局 / 项目。 */
private fun mcpScopeText(scope: McpScope): String =
    if (scope == McpScope.GLOBAL) "全局" else "项目"

/** 传输类型显示名：内置 / HTTP / stdio。 */
private fun mcpTypeText(config: McpServerConfig): String = when {
    config.isBuiltin -> "内置"
    config.isHttp -> "HTTP"
    else -> "stdio"
}

/** 解析 "K=V,K2=V2" 形式的键值对（Headers / 环境变量共用），忽略空项与无等号项。 */
private fun parseKv(text: String): Map<String, String> =
    text.split(",").mapNotNull { pair ->
        val kv = pair.trim().split("=", limit = 2)
        if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
    }.toMap()