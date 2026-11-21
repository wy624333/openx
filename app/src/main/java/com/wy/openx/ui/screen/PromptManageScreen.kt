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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.store.AgentRule
import com.wy.openx.data.store.AgentRuleSource
import com.wy.openx.data.store.AgentRules
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 设置 → 「提示词」子页：
 * 顶部**总开关** + 下面按编号列出的内置 AI 行为规则。
 *
 * ## 与存储的对应
 * - 列表来自合并后的两层视图（[AgentRule]）：内置层 `memory/agent/NN-*.md` +
 *   自定义覆盖层 `memory/agent-custom/NN-*.md`，同编号自定义胜出 → 徽章显示「已自定义」。
 * - 总开关 [onToggleEnabled] / 单条开关 [onToggleRule] 只决定**是否注入系统提示词**，
 *   列表永远展示全部（否则用户把某条关掉后就找不到开关再打开）。
 * - 「恢复内置默认」删自定义覆盖文件（[onResetRule]），该条回落到随 APK 发布的原文。
 *
 * 行样式复用 [ManageCard]（与记忆 / MCP / 技能页同源），开关样式对齐 MCP 页的行内开关。
 */
@Composable
fun PromptManageScreen(
    rules: List<AgentRule>,
    enabled: Boolean,
    disabledKeys: Set<String>,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onSaveRule: (String, String, Boolean) -> Unit,
    onResetRule: (String, AgentRuleSource) -> Unit,
    /** 右上角「+」新建片段：编号 + 名称（清洗后）+ 正文 + 是否只对当前项目生效。 */
    onCreateRule: (Int, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    /** 是否已选择项目（决定「仅当前项目」开关是否可用）。 */
    hasProject: Boolean = false,
    /** 当前项目名（展示用）。 */
    projectName: String = ""
) {
    var detailRule by remember { mutableStateOf<AgentRule?>(null) }
    var editRule by remember { mutableStateOf<AgentRule?>(null) }
    var editBody by remember { mutableStateOf("") }
    var resetTarget by remember { mutableStateOf<AgentRule?>(null) }
    // 自建片段（无内置原文）的删除：文案与破坏性都和「恢复默认」不同，单独一个状态
    var deleteTarget by remember { mutableStateOf<AgentRule?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newNumber by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    // 保存目标：false = 全局自定义层，true = 当前项目层（优先级更高）
    var editToProject by remember { mutableStateOf(false) }
    var createToProject by remember { mutableStateOf(false) }

    val enabledCount = rules.count { it.key !in disabledKeys }
    val customCount = rules.count { it.source != AgentRuleSource.BUILTIN }

    Column(modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        ManageTopBar(title = "提示词", onBack = onBack) {
            IconButton(onClick = {
                // 默认编号 = 现有最大编号 + 10（落到下一个整十档），留出插入空隙
                val next = (rules.mapNotNull { it.number }.maxOrNull()?.plus(10) ?: 10).coerceIn(0, 99)
                newNumber = next.toString().padStart(2, '0')
                newName = ""
                newBody = ""
                createToProject = false
                showCreate = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加提示词", tint = Accent)
            }
        }
        ManageHint("内置 AI 行为规则随 App 版本更新；改动存为自定义层，升级不会被覆盖。")
        Spacer(Modifier.height(8.dp))

        // ===== 总开关 =====
        Surface(
            color = PanelAlt,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "启用内置提示词",
                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "关闭后以下规则全部不再注入系统提示词（文件保留，随时可开回）。" +
                            "正在生成的这条消息不受影响，从下一条开始生效。",
                        color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Accent,
                        uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "已启用 $enabledCount/${rules.size} 条" +
                (if (customCount > 0) " · 自定义 $customCount 条" else "") +
                (if (!enabled) " · 总开关已关闭" else ""),
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(8.dp))

        // ===== 规则列表 =====
        if (rules.isEmpty()) {
            ManageEmpty("暂无内置提示词。若刚升级完 App，回到设置页重进本页即可。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rules, key = { it.key }) { rule ->
                    val off = rule.key in disabledKeys
                    ManageCard(
                        icon = Icons.Outlined.Description,
                        title = ruleDisplayTitle(rule),
                        summary = rule.summary.ifBlank {
                            rule.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60)
                        },
                        onClick = { detailRule = rule },
                        titleBadges = {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (rule.source) {
                                    AgentRuleSource.PROJECT -> ManageTag("项目", Accent)
                                    AgentRuleSource.CUSTOM ->
                                        ManageTag(if (rule.hasBuiltin) "已自定义" else "自建", Accent)
                                    AgentRuleSource.BUILTIN -> ManageTag("内置", TextSecondary)
                                }
                                if (off) {
                                    Spacer(Modifier.width(4.dp))
                                    ManageTag("已停用", Color(0xFFFF6B6B))
                                }
                                if (rule.source != AgentRuleSource.BUILTIN && rule.number >= 0) {
                                    Spacer(Modifier.width(4.dp))
                                    ManageTag("覆盖 ${rule.number}", Accent)
                                }
                            }
                        },
                        trailing = {
                            Switch(
                                checked = !off,
                                enabled = enabled,
                                onCheckedChange = { onToggleRule(rule.key, it) },
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

    // ===== 详情（只读预览 + 编辑入口 + 恢复默认） =====
    detailRule?.let { rule ->
        ManageDetailDialog(
            title = ruleDisplayTitle(rule),
            onDismiss = { detailRule = null },
            onEdit = {
                editRule = rule
                editBody = rule.body
                editToProject = rule.source == AgentRuleSource.PROJECT
                detailRule = null
            },
            tags = {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rule.source == AgentRuleSource.PROJECT) {
                        ManageTag(if (rule.hasBuiltin) "已自定义" else "自建")
                        Spacer(Modifier.width(4.dp))
                        ManageTag("项目层")
                    } else if (rule.source == AgentRuleSource.CUSTOM) {
                        ManageTag(if (rule.hasBuiltin) "已自定义" else "自建")
                        Spacer(Modifier.width(4.dp))
                        ManageTag("全局层")
                    } else {
                        ManageTag("内置", TextSecondary)
                        Spacer(Modifier.width(4.dp))
                        ManageTag("内置层", TextSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    if (rule.key in disabledKeys) ManageTag("已停用", Color(0xFFFF6B6B))
                    else ManageTag("已启用", Accent)
                }
            },
            footer = {
                if (rule.source != AgentRuleSource.BUILTIN) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (rule.hasBuiltin && rule.source == AgentRuleSource.PROJECT) {
                            TextButton(onClick = {
                                resetTarget = rule
                                detailRule = null
                            }) { Text("删除项目层", color = Accent) }
                        } else if (rule.hasBuiltin) {
                            TextButton(onClick = {
                                resetTarget = rule
                                detailRule = null
                            }) { Text("恢复内置默认", color = Accent) }
                        } else {
                            TextButton(onClick = {
                                deleteTarget = rule
                                detailRule = null
                            }) { Text("删除此片段", color = Color(0xFFFF6B6B)) }
                        }
                    }
                }
            }
        ) {
            MarkdownContent(
                text = bodyForPreview(rule.body),
                baseFontSize = 14.sp,
                textColor = TextPrimary
            )
        }
    }

    // ===== 编辑（写自定义覆盖层） =====
    editRule?.let { rule ->
        ManageFormDialog(
            title = "编辑提示词",
            subtitle = "改动写入自定义层，App 升级不会覆盖；「恢复」可随时丢弃。",
            onDismiss = { editRule = null },
            canSave = editBody.isNotBlank(),
            onSave = {
                onSaveRule(rule.key, editBody, editToProject)
                editRule = null
            },
            form = {
                ManageTextField(
                    value = editBody,
                    onValueChange = { editBody = it },
                    label = "规则正文（Markdown）",
                    singleLine = false,
                    minLines = 10
                )
                ProjectLayerSwitch(
                    checked = editToProject,
                    onCheckedChange = { editToProject = it },
                    hasProject = hasProject,
                    projectName = projectName
                )
            }
        )
    }

    // ===== 恢复默认确认（覆盖内置的自定义改动） =====
    resetTarget?.let { rule ->
        ManageConfirmDialog(
            title = if (rule.source == AgentRuleSource.PROJECT) "删除项目层" else "恢复内置默认",
            message = if (rule.source == AgentRuleSource.PROJECT)
                "删除「${ruleDisplayTitle(rule)}」的项目层改动，回落到全局/内置版本。"
            else
                "删除「${ruleDisplayTitle(rule)}」的自定义改动，回落到随 App 发布的内置原文。",
            confirmLabel = if (rule.source == AgentRuleSource.PROJECT) "删除" else "恢复",
            onConfirm = {
                resetTarget = null
                onResetRule(rule.key, rule.source)
            },
            onDismiss = { resetTarget = null }
        )
    }

    // ===== 自建片段删除确认（没有内置原文可回，删了就没了） =====
    deleteTarget?.let { rule ->
        ManageConfirmDialog(
            title = "删除片段",
            message = "「${ruleDisplayTitle(rule)}」是你自建的片段，没有更低层可回落，删除后不再注入提示词。",
            confirmLabel = "删除",
            onConfirm = {
                deleteTarget = null
                onResetRule(rule.key, rule.source)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // ===== 新建片段（写自定义层） =====
    if (showCreate) {
        val typedNumber = newNumber.trim().toIntOrNull()
        val numberOk = typedNumber != null && typedNumber in 0..99
        val occupied = typedNumber?.let { n -> rules.firstOrNull { it.number == n } }
        val cleanName = AgentRules.sanitizeName(newName)
        val canCreate = numberOk && occupied == null && cleanName.isNotBlank() && newBody.isNotBlank()

        ManageFormDialog(
            title = "添加提示词",
            subtitle = "新建片段写入自定义层，App 升级不受影响；编号决定它在系统提示词里的位置（00 最前）。",
            onDismiss = { showCreate = false },
            canSave = canCreate,
            saveLabel = "创建",
            onSave = {
                onCreateRule(typedNumber ?: 0, cleanName, newBody, createToProject)
                showCreate = false
            },
            form = {
                ManageTextField(
                    value = newNumber,
                    onValueChange = { v -> newNumber = v.filter { it.isDigit() }.take(2) },
                    label = "编号（00–99）",
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                ManageTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "名称（用作文件名后缀，如 comments）",
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                ManageTextField(
                    value = newBody,
                    onValueChange = { newBody = it },
                    label = "正文（Markdown，首行可用 <!-- 标题：摘要 --> 做标题）",
                    singleLine = false,
                    minLines = 8
                )
                ProjectLayerSwitch(
                    checked = createToProject,
                    onCheckedChange = { createToProject = it },
                    hasProject = hasProject,
                    projectName = projectName
                )
                if (occupied != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "编号 %02d 已被「%s」占用，请换一个编号。".format(typedNumber, occupied.title),
                        color = Color(0xFFFF6B6B),
                        fontSize = 11.sp
                    )
                }
            }
        )
    }
}

/** 列表与详情的统一标题：有编号时 `30 · 注释`。 */
private fun ruleDisplayTitle(rule: AgentRule): String =
    if (rule.number >= 0) "%02d · %s".format(rule.number, rule.title) else rule.title

/**
 * 详情预览用正文：把首行 `<!-- 标题：摘要 -->` 去掉——它已经在标题/摘要里展示过了，
 * 再渲染一遍要么显示成注释、要么显示成一串没意义的符号。
 */
private fun bodyForPreview(body: String): String {
    val first = body.lineSequence().firstOrNull()?.trim().orEmpty()
    if (!first.startsWith("<!--") || !first.endsWith("-->")) return body
    return body.lineSequence().drop(1).joinToString("\n").trimStart('\n')
}

/**
 * 「仅当前项目生效」开关：项目层优先级高于全局自定义层，切换项目后自动失效。
 * 没选项目时置灰并说明原因——否则用户以为存成了项目规则，实际写进了全局。
 */
@Composable
private fun ProjectLayerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hasProject: Boolean,
    projectName: String
) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "仅当前项目生效",
                color = if (hasProject) TextPrimary else TextSecondary,
                fontSize = 13.sp
            )
            Text(
                if (hasProject) "写入项目层，优先级高于全局；切换到别的项目后不生效。"
                else "未选择项目，只能保存为全局提示词。",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked && hasProject,
            enabled = hasProject,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border
            )
        )
    }
    if (hasProject && projectName.isNotBlank()) {
        Text("当前项目：$projectName", color = TextSecondary, fontSize = 11.sp)
    }
}
