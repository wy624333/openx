package com.wy.openx.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.model.SkillPreset
import com.wy.openx.data.store.SCOPE_GLOBAL
import com.wy.openx.data.store.SCOPE_PROJECT
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.rememberSlidableGroupState
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 技能管理：展示已安装技能（全局 .openx/skills/<name>/SKILL.md；项目 .openx/skills/projects/<项目>/）。
 * 与记忆管理同构：胶囊 Tab（全局技能 / 项目技能）→ 卡片列表 → 详情弹层 → 删除确认；
 * 启停切换、删除、导入都带入当前 Tab 的作用域。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManageScreen(
    skills: List<SkillPreset>,
    projectSkills: List<SkillPreset> = emptyList(),
    currentProject: String = "",
    onBack: () -> Unit,
    onDelete: (String, String) -> Unit = { _, _ -> },
    onToggle: (String, Boolean, String) -> Unit = { _, _, _ -> },
    onImportMarkdown: (android.net.Uri, String, String) -> Unit = { _, _, _ -> },
    onImportArchive: (android.net.Uri, String, String) -> Unit = { _, _, _ -> }
) {
    var tab by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detailSkill by remember { mutableStateOf<SkillPreset?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 0 = 全局技能，1 = 项目技能
    val list = if (tab == 0) skills else projectSkills
    val scope = if (tab == 0) SCOPE_GLOBAL else SCOPE_PROJECT

    // 读取所选 content uri 的显示名（找不到时按 uri 末段 / 兜底名）
    fun displayName(uri: android.net.Uri, fallback: String): String = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: fallback

    val mdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportMarkdown(uri, displayName(uri, "skill.md"), scope)
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportArchive(uri, displayName(uri, "skill.zip"), scope)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        ManageTopBar(title = "技能管理", onBack = onBack, onGuide = { showGuide = true }) {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加技能", tint = Accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        SkillTabSwitcher(
            selected = tab,
            onSelect = { tab = it },
            globalCount = skills.size,
            projectCount = projectSkills.size
        )
        Spacer(Modifier.height(12.dp))

        // 项目技能但未选项目：项目技能按项目隔离，没有项目就没有容器
        if (tab == 1 && currentProject.isBlank()) {
            ManageEmpty("尚未选择项目。项目技能按项目隔离，请先在输入栏的「工作区」里选择或创建一个项目。")
            return@Column
        }

        if (list.isEmpty()) {
            ManageEmpty(
                if (tab == 0) "暂无全局技能，点右上角「+」导入，或让 AI 调用 skill_add 工具创建。"
                else "当前项目暂无项目技能，点右上角「+」导入，或让 AI 调用 skill_add 工具创建（scope=project）。"
            )
        } else {
            val slideGroup = rememberSlidableGroupState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.scope + "|" + it.name }) { skill ->
                    SlidableRow(
                        itemId = skill.scope + "|" + skill.name,
                        groupState = slideGroup,
                        actions = listOf(
                            SlideAction(icon = Icons.Outlined.Delete, tint = Color(0xFFFF6B6B)) {
                                deleteTarget = skill.name to skill.scope
                            }
                        ),
                        onFullSwipe = { deleteTarget = skill.name to skill.scope }
                    ) {
                        ManageCard(
                            icon = if (skill.scope == SCOPE_PROJECT) Icons.Outlined.Folder else Icons.Outlined.Extension,
                            title = skill.name,
                            summary = skill.description.ifBlank { skill.instructions },
                            onClick = { detailSkill = skill },
                            // 徽标改放标题**下方独立一行**，避免长名字把徽标挤掉（与插件卡片一致）。
                            // 启用状态跟作用域徽标同一行（对齐 MCP 卡片）：底部 meta 行只留给
                            // 「连接状态」这类真正会变化的运行态，启用状态不该在卡片最下面再重复一遍。
                            titleBadges = {
                                // titleBadges 是 Column 槽位：多个徽标必须自己包 Row，否则会各占一行
                                // （真机看到「已启用」掉到「全局技能」下面）。对齐 MCP 卡片写法。
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ManageTag(
                                        if (skill.scope == SCOPE_PROJECT) "项目技能" else "全局技能",
                                        if (skill.scope == SCOPE_PROJECT) TextSecondary else Accent
                                    )
                                    ManageTag(
                                        if (skill.enabled) "已启用" else "已停用",
                                        if (skill.enabled) Accent else TextSecondary
                                    )
                                }
                            },
                            trailing = {
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { onToggle(skill.name, it, skill.scope) },
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

    detailSkill?.let { skill ->
        ManageDetailDialog(
            title = skill.name,
            onDismiss = { detailSkill = null },
            tags = {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManageTag(if (skill.scope == SCOPE_PROJECT) "项目技能" else "全局技能",
                        if (skill.scope == SCOPE_PROJECT) TextSecondary else Accent)
                    Spacer(Modifier.width(6.dp))
                    ManageTag(if (skill.enabled) "已启用" else "已停用",
                        if (skill.enabled) Accent else TextSecondary)
                    if (skill.description.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        ManageTag("技能")
                    }
                }
            }
        ) {
            if (skill.description.isNotBlank()) {
                Text("描述", color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(skill.description, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
            }
            Text("指令", color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                skill.instructions.ifBlank { "（无指令内容）" },
                color = TextPrimary, fontSize = 13.sp
            )
        }
    }

    if (showGuide) {
        GuideDialog(
            title = "技能管理说明",
            blocks = listOf(
                "技能是什么" to "一份按需加载的专项操作指令，把某类任务的标准流程沉淀下来，相关时才取用。",
                "全局 / 项目" to "全局技能跨项目共享（.openx/skills）；项目技能只在该项目生效（.openx/skills/projects/<项目>），同名时项目级优先。",
                "怎么创建" to "让 AI 调用 skill_add 创建，或点右上角「+」导入 .md / 压缩包；左滑卡片可删除。",
                "何时生效" to "系统提示里只列名称与用途，正文需 AI 主动 skill_load 加载；技能目录必须有带 name/description 的 SKILL.md。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    deleteTarget?.let { (skillName, targetScope) ->
        ManageConfirmDialog(
            title = "确认删除",
            message = "确定要删除${if (targetScope == SCOPE_PROJECT) "项目技能" else "全局技能"}「$skillName」吗？此操作不可撤销。",
            confirmLabel = "确认删除",
            onConfirm = { deleteTarget = null; onDelete(skillName, targetScope) },
            onDismiss = { deleteTarget = null }
        )
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)
            ) {
                Text(
                    "添加技能",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "导入到：" + if (scope == SCOPE_PROJECT) "项目技能（$currentProject）" else "全局技能",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                SkillImportActionRow(
                    icon = Icons.Outlined.Description,
                    label = "添加 .md 技能",
                    subtitle = "从手机目录选择 SKILL.md 文件导入",
                    onClick = {
                        showAddSheet = false
                        mdPicker.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"))
                    }
                )
                SkillImportActionRow(
                    icon = Icons.Outlined.Archive,
                    label = "添加压缩包技能",
                    subtitle = "选择 zip / tar.gz 压缩包，自动解压安装到 .openx/skills",
                    onClick = {
                        showAddSheet = false
                        archivePicker.launch(arrayOf("application/zip", "application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"))
                    }
                )
            }
        }
    }
}

/** 胶囊 Tab 切换器 */
@Composable
private fun SkillTabSwitcher(
    selected: Int,
    onSelect: (Int) -> Unit,
    globalCount: Int,
    projectCount: Int
) {
    val tabs = listOf("全局技能" to globalCount, "项目技能" to projectCount)
    Surface(
        color = Panel,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, (label, count) ->
                val active = selected == index
                val bg by animateColorAsState(
                    if (active) Accent.copy(alpha = 0.18f) else Color.Transparent,
                    label = "skill-tab-bg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$label（$count）",
                        color = if (active) Accent else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillImportActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
    }
}