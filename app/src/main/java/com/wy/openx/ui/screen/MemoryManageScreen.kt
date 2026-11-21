package com.wy.openx.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.rememberSlidableGroupState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.wy.openx.data.store.KnowledgeEntry
import com.wy.openx.data.store.MemoryItem
import com.wy.openx.data.store.SCOPE_GLOBAL
import com.wy.openx.data.store.SCOPE_PROJECT
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆与知识管理页。
 *
 * 结构：胶囊 Tab（长期记忆 / 项目记忆 / 全局知识）→ 卡片列表 → 点卡片弹详情。
 * - 长期记忆：全局，跨项目共享；
 * - 项目记忆：按当前项目隔离（未选项目时该 Tab 提示先选项目）；
 * - 全局知识：从历史对话里提炼出来的经验（`openx/knowledge/`），AI 用 `knowledge_search` 查；
 *   这里只做浏览与删除——新增靠「知识提炼」自动沉淀或 AI 手动 `knowledge_save`。
 *
 * 保留「添加记忆」（右上按钮），按用户要求**去掉「清空所有记忆」**。
 */
@Composable
fun MemoryManageScreen(
    memoryItems: List<MemoryItem>,
    projectMemoryItems: List<MemoryItem> = emptyList(),
    knowledgeItems: List<KnowledgeEntry> = emptyList(),
    currentProject: String = "",
    onBack: () -> Unit,
    onDeleteMemory: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveMemory: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onDeleteKnowledge: (String) -> Unit = {},
    onSaveKnowledge: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onImportKnowledge: (Uri, String) -> Unit = { _, _ -> }
) {
    // 0 = 长期记忆，1 = 项目记忆，2 = 全局知识
    var tab by remember { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var editType by remember { mutableStateOf("user") }
    var editScope by remember { mutableStateOf(SCOPE_GLOBAL) }
    var detailItem by remember { mutableStateOf<MemoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryItem?>(null) }
    var knowledgeDetail by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var knowledgeDeleteTarget by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var showKnowledgeDialog by remember { mutableStateOf(false) }
    var kTitle by remember { mutableStateOf("") }
    var kBody by remember { mutableStateOf("") }
    var kTags by remember { mutableStateOf("") }
    /** 非空 = 正在编辑这条（保存时按 id 覆盖）；空 = 新增。 */
    var editingKnowledgeId by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(false) }

    if (showGuide) {
        GuideDialog(
            title = "记忆管理说明",
            blocks = listOf(
                "长期记忆" to "跨项目通用的偏好与约定，全局生效，所有项目都看得到。",
                "项目记忆" to "只对当前项目生效（按项目目录隔离）；未选项目时该页签不可用。",
                "全局知识" to "从历史对话里提炼的经验与踩坑，AI 用 knowledge_search 查；可在「默认模型」里开启自动提炼。",
                "怎么加" to "点右上角「+」手动新建，或导入文档；卡片左滑可删，点卡片看详情。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    // 从手机存储导入文档（md / txt / json / 代码等纯文本），内容由 VM 读并落库。
    // 带上 editingKnowledgeId：编辑态导入＝用文件内容替换这条的正文，而不是新建一条。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showKnowledgeDialog = false
            onImportKnowledge(uri, editingKnowledgeId)
        }
    }

    val list = if (tab == 0) memoryItems else projectMemoryItems
    val scope = if (tab == 0) SCOPE_GLOBAL else SCOPE_PROJECT

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        ManageTopBar(title = "记忆管理", onBack = onBack, onGuide = { showGuide = true }) {
            // 知识 Tab 的「+」加知识（标题/正文/标签）；记忆 Tab 的「+」仍是加记忆。
            if (tab == 2) {
                IconButton(onClick = {
                    editingKnowledgeId = ""
                    kTitle = ""; kBody = ""; kTags = ""
                    showKnowledgeDialog = true
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加知识", tint = Accent)
                }
            } else {
                IconButton(onClick = {
                    editKey = ""; editValue = ""; editType = "user"
                    editScope = scope
                    showDialog = true
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加记忆", tint = Accent)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        MemoryTabSwitcher(
            selected = tab,
            onSelect = { tab = it },
            globalCount = memoryItems.size,
            projectCount = projectMemoryItems.size,
            knowledgeCount = knowledgeItems.size
        )
        Spacer(Modifier.height(12.dp))

        // 项目记忆但未选项目：项目记忆按项目隔离，没有项目就没有容器
        if (tab == 1 && currentProject.isBlank()) {
            ManageEmpty("尚未选择项目。项目记忆按项目隔离，请先在输入栏的「工作区」里选择或创建一个项目。")
            return@Column
        }

        if (tab == 2) {
            // 知识库：由「知识提炼模型」从历史对话里沉淀，AI 用 knowledge_search 查。
            if (knowledgeItems.isEmpty()) {
                ManageEmpty("知识库是空的。点右上角「+」手动添加，或在「设置 → 默认模型」里开启「知识提炼」让它自动沉淀。")
            } else {
                val knowledgeSlideGroup = rememberSlidableGroupState()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(knowledgeItems, key = { it.id }) { item ->
                        SlidableRow(
                            itemId = item.id,
                            groupState = knowledgeSlideGroup,
                            actions = listOf(
                                SlideAction(icon = Icons.Outlined.Delete, tint = Color(0xFFFF6B6B)) {
                                    knowledgeDeleteTarget = item
                                }
                            ),
                            onFullSwipe = { knowledgeDeleteTarget = item }
                        ) {
                            ManageCard(
                                icon = Icons.Outlined.AutoAwesome,
                                title = item.title,
                                summary = item.body,
                                meta = buildString {
                                    if (item.tags.isNotEmpty()) append(item.tags.joinToString("、")).append(" · ")
                                    append(formatMemoryTime(item.updated))
                                    if (item.source.isNotBlank()) append(" · ").append(item.source)
                                },
                                onClick = { knowledgeDetail = item }
                            )
                        }
                    }
                }
            }
            return@Column
        }

        if (list.isEmpty()) {
            ManageEmpty(
                if (tab == 0) "暂无长期记忆，点右上角「+」添加"
                else "当前项目暂无项目记忆，点右上角「+」添加"
            )
        } else {
            val slideGroup = rememberSlidableGroupState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.scope + "|" + it.key + "|" + it.type }) { item ->
                    SlidableRow(
                        itemId = item.scope + "|" + item.key + "|" + item.type,
                        groupState = slideGroup,
                        actions = listOf(
                            SlideAction(icon = Icons.Outlined.Delete, tint = Color(0xFFFF6B6B)) {
                                deleteTarget = item
                            }
                        ),
                        onFullSwipe = { deleteTarget = item }
                    ) {
                        ManageCard(
                            icon = if (item.scope == SCOPE_PROJECT) Icons.Outlined.Folder else Icons.Outlined.AutoAwesome,
                            title = item.key,
                            summary = item.value,
                            meta = formatMemoryTime(item.timestamp) + " · " + item.type,
                            onClick = { detailItem = item }
                        )
                    }
                }
            }
        }
    }

    detailItem?.let { item ->
        ManageDetailDialog(
            title = item.key,
            onDismiss = { detailItem = null },
            onEdit = {
                editKey = item.key; editValue = item.value
                editType = item.type; editScope = item.scope
                detailItem = null; showDialog = true
            },
            tags = {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManageTag(if (item.scope == SCOPE_PROJECT) "项目记忆" else "长期记忆")
                    Spacer(Modifier.padding(3.dp))
                    ManageTag(item.type)
                    Spacer(Modifier.padding(3.dp))
                    ManageTag(item.category)
                    Spacer(Modifier.padding(3.dp))
                    Text(formatMemoryTime(item.timestamp), color = TextSecondary, fontSize = 11.sp)
                }
            }
        ) {
            Text(item.value, color = com.wy.openx.ui.theme.TextPrimary, fontSize = 14.sp)
        }
    }

    deleteTarget?.let { item ->
        ManageConfirmDialog(
            title = "确认删除",
            message = "确定要删除记忆「${item.key}」吗？",
            confirmLabel = "删除",
            onConfirm = {
                deleteTarget = null
                onDeleteMemory(item.key, item.type, item.scope)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    knowledgeDetail?.let { item ->
        ManageDetailDialog(
            title = item.title,
            onDismiss = { knowledgeDetail = null },
            onEdit = {
                editingKnowledgeId = item.id
                kTitle = item.title; kBody = item.body; kTags = item.tags.joinToString(",")
                knowledgeDetail = null
                showKnowledgeDialog = true
            },
            tags = {
                Spacer(Modifier.height(4.dp))
                // 标签多时左右滑：不加滚动的话 Row 会把每个胶囊挤到压缩宽度，
                // 「Android」这类词会被拆成两行。
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ManageTag("全局知识")
                    item.tags.forEach { tag ->
                        Spacer(Modifier.padding(3.dp))
                        ManageTag(tag)
                    }
                    Spacer(Modifier.padding(3.dp))
                    Text(formatMemoryTime(item.updated), color = TextSecondary, fontSize = 11.sp)
                }
            }
        ) {
            Text(item.body, color = com.wy.openx.ui.theme.TextPrimary, fontSize = 14.sp)
            if (item.source.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("来源：${item.source}", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }

    knowledgeDeleteTarget?.let { item ->
        ManageConfirmDialog(
            title = "确认删除",
            message = "确定要从知识库删除「${item.title}」吗？",
            confirmLabel = "删除",
            onConfirm = {
                knowledgeDeleteTarget = null
                onDeleteKnowledge(item.id)
            },
            onDismiss = { knowledgeDeleteTarget = null }
        )
    }

    if (showKnowledgeDialog) {
        ManageFormDialog(
            title = if (editingKnowledgeId.isNotBlank()) "编辑知识" else "添加知识",
            subtitle = if (editingKnowledgeId.isNotBlank()) "修改后覆盖原条目（id 与来源不变）"
            else "保存到：全局知识（对话提炼与手动添加都进这里）",
            onDismiss = { showKnowledgeDialog = false },
            canSave = kTitle.isNotBlank() && kBody.isNotBlank(),
            onSave = {
                onSaveKnowledge(editingKnowledgeId, kTitle.trim(), kBody.trim(), kTags.trim())
                showKnowledgeDialog = false
            },
            form = {
                ManageTextField(kTitle, { kTitle = it }, "知识标题")
                Spacer(Modifier.height(8.dp))
                ManageTextField(kBody, { kBody = it }, "正文：现象 / 根因 / 做法", singleLine = false, minLines = 6)
                Spacer(Modifier.height(8.dp))
                ManageTextField(kTags, { kTags = it }, "标签，逗号分隔（可选）")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从手机文件导入（md / txt 等纯文本）")
                }
            }
        )
    }

    if (showDialog) {
        ManageFormDialog(
            title = if (editKey.isNotEmpty()) "编辑记忆" else "添加记忆",
            subtitle = "保存到：" + if (editScope == SCOPE_PROJECT) "项目记忆" else "长期记忆",
            onDismiss = { showDialog = false },
            canSave = editKey.isNotBlank() && editValue.isNotBlank(),
            onSave = {
                onSaveMemory(editKey.trim(), editValue.trim(), editType.trim().ifBlank { "user" }, editScope)
                showDialog = false
            },
            form = {
                ManageTextField(editKey, { editKey = it }, "记忆名称")
                Spacer(Modifier.height(8.dp))
                ManageTextField(editValue, { editValue = it }, "记忆内容", singleLine = false, minLines = 4)
                Spacer(Modifier.height(8.dp))
                ManageTextField(editType, { editType = it }, "类型（user / ai / project）")
            }
        )
    }
}

/** 胶囊 Tab 切换器。 */
@Composable
private fun MemoryTabSwitcher(
    selected: Int,
    onSelect: (Int) -> Unit,
    globalCount: Int,
    projectCount: Int,
    knowledgeCount: Int
) {
    val tabs = listOf("长期记忆" to globalCount, "项目记忆" to projectCount, "全局知识" to knowledgeCount)
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
                    label = "mem-tab-bg"
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

/** 记忆时间显示：今天只显示 HH:mm，否则显示 MM-dd HH:mm。 */
private fun formatMemoryTime(ts: Long): String {
    if (ts <= 0L) return "未知时间"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val today = SimpleDateFormat("MM-dd", Locale.getDefault())
    val text = fmt.format(Date(ts))
    return if (text.startsWith(today.format(Date()))) text.substringAfter(" ") else text
}
