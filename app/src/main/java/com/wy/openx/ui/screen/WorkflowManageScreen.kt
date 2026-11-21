package com.wy.openx.ui.screen

import com.wy.openx.data.store.Workflow
import com.wy.openx.data.store.WorkflowStep
import com.wy.openx.data.store.parseWorkflowJson
import com.wy.openx.data.store.stringifyWorkflowSteps
import com.google.gson.Gson
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
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.rememberSlidableGroupState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schema
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 工作流管理页。
 *
 * 数据与执行能力本来就有（[com.wy.openx.data.store.WorkflowStore] 持久化、
 * [com.wy.openx.tools.WorkflowEngine] 按 DAG 执行），此前只能由 AI 通过 `workflow` 工具
 * 增删跑，用户没有入口——这页就是补上那个入口。
 *
 * 版式统一到 [ManageCommonUi]（顶栏 + 卡片列表 + 全屏详情/表单弹层），与技能/记忆/MCP 页一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowManageScreen(
    workflows: List<Workflow>,
    runningName: String?,
    onBack: () -> Unit,
    onSave: (Workflow, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (Workflow) -> Unit
) {
    var detail by remember { mutableStateOf<Workflow?>(null) }
    var editing by remember { mutableStateOf<Workflow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        ManageTopBar(title = "工作流管理", onBack = onBack, onGuide = { showGuide = true }) {
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建工作流", tint = Accent)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (workflows.isEmpty()) {
            ManageEmpty("暂无工作流。点右上角「+」新建，或让 AI 调用 workflow 工具创建。")
        } else {
            val slideGroup = rememberSlidableGroupState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(workflows, key = { it.name }) { wf ->
                    SlidableRow(
                        itemId = wf.name,
                        groupState = slideGroup,
                        actions = listOf(
                            SlideAction(icon = Icons.Outlined.Delete, tint = Color(0xFFFF6B6B)) {
                                deleteTarget = wf.name
                            }
                        ),
                        onFullSwipe = { deleteTarget = wf.name }
                    ) {
                        WorkflowCard(
                            workflow = wf,
                            running = runningName == wf.name,
                            disabled = runningName != null,
                            onClick = { detail = wf },
                            onRun = { onRun(wf) }
                        )
                    }
                }
            }
        }
    }

    detail?.let { wf ->
        WorkflowDetailDialog(
            workflow = wf,
            onDismiss = { detail = null },
            onEdit = { editing = wf; detail = null },
            onRun = { onRun(wf); detail = null }
        )
    }

    if (showGuide) {
        GuideDialog(
            title = "工作流管理说明",
            blocks = listOf(
                "工作流是什么" to "一串按依赖关系（DAG）自动执行的工具调用，可重复使用。",
                "怎么用" to "点卡片看步骤，点 ▶ 直接运行，结果输出到聊天页。",
                "步骤引用" to "步骤间可用 \${step_id} 引用前置步骤的输出；步骤可声明 dependsOn 控制执行顺序。",
                "怎么建" to "点右上角「+」新建，或让 AI 调用 workflow 工具创建；卡片上会标出有依赖的步骤数。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    if (creating || editing != null) {
        WorkflowFormDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { wf, existed ->
                onSave(wf, existed)
                creating = false
                editing = null
            }
        )
    }

    deleteTarget?.let { name ->
        ManageConfirmDialog(
            title = "删除工作流",
            message = "确定删除「$name」？此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = { onDelete(name); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** 工作流卡片：标题 + 描述 + 步骤数/工具数，尾部一个运行按钮。 */
@Composable
private fun WorkflowCard(
    workflow: Workflow,
    running: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
    onRun: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = PanelAlt,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Schema,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    workflow.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (workflow.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        workflow.description,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManageTag("${workflow.steps.size} 步")
                    Spacer(Modifier.width(6.dp))
                    Text(
                        workflow.steps.map { it.tool }.distinct().take(3).joinToString(" · "),
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 运行：已在跑就转圈；别的在跑则禁用，避免并发触发同一批工具
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Accent
                )
            } else {
                IconButton(onClick = onRun, enabled = !disabled) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "运行 ${workflow.name}",
                        tint = if (disabled) TextSecondary.copy(alpha = 0.4f) else Accent
                    )
                }
            }
        }
    }
}

/** 详情：按执行顺序列出每步（id / 工具 / 依赖 / 参数）。 */
@Composable
private fun WorkflowDetailDialog(
    workflow: Workflow,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit
) {
    val gson = remember { Gson() }
    ManageDetailDialog(
        title = workflow.name,
        onDismiss = onDismiss,
        onEdit = onEdit,
        tags = {
            ManageTag("${workflow.steps.size} 步")
            Spacer(Modifier.width(6.dp))
            ManageTag(workflow.steps.count { it.dependsOn.isNotEmpty() }.let { if (it > 0) "$it 步有依赖" else "无依赖" }, TextSecondary)
        },
        footer = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "▶ 运行",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onRun).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    ) {
        // 不要自己再套 verticalScroll：ManageDetailDialog 已把 content 放进
        // 带 weight(1f) 的定高滚动容器里，再套一层会让内层拿到无限高约束而崩溃。
        // 其它三个管理页（技能/记忆/MCP）的 content 也都是不套滚动的。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (workflow.description.isNotBlank()) {
                Text(workflow.description, fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
            }
            workflow.steps.forEachIndexed { index, step ->
                WorkflowStepRow(index + 1, step, gson)
                if (index != workflow.steps.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun WorkflowStepRow(no: Int, step: WorkflowStep, gson: Gson) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Accent.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "$no",
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(step.id, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(Modifier.width(8.dp))
                ManageTag(step.tool)
            }
            if (step.dependsOn.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "依赖：${step.dependsOn.joinToString("、")}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            if (step.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(step.description, fontSize = 11.sp, color = TextSecondary)
            }
            if (step.args.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // args 是 Map<String, Any>，整体 JSON 化最省事；单行且横向可滚，避免长参数撑破布局
                Surface(color = BgPrimary, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        gson.toJson(step.args),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        maxLines = 4,
                        modifier = Modifier
                            .heightIn(max = 96.dp)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 新建/编辑表单。
 *
 * 步骤用 **JSON 直接编辑**而不是做图形化 DAG 编辑器：workflow 工具创建的既有工作流
 * 就是 JSON 形态，这里沿用同一份表示，AI 建的和用户建的能互相看见、互相改。
 * 解析与校验抽成纯函数 [parseWorkflowJson]，与 AI 工具共用同一套规则。
 */
@Composable
private fun WorkflowFormDialog(
    initial: Workflow?,
    onDismiss: () -> Unit,
    onSave: (Workflow, Boolean) -> Unit
) {
    val existed = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var stepsText by remember { mutableStateOf(initial?.let { stringifyWorkflowSteps(it.steps) } ?: DEFAULT_STEPS_JSON) }
    var touched by remember { mutableStateOf(false) }

    val parsed = parseWorkflowJson(name, desc, stepsText)
    val nameError = name.isBlank()
    val stepError = parsed.error

    ManageFormDialog(
        title = if (existed) "编辑工作流" else "新建工作流",
        subtitle = if (existed) "保存后同名覆盖原工作流" else "步骤按 JSON 数组填写，args 里可用 ${'$'}{step_id} 引用前置输出",
        onDismiss = onDismiss,
        canSave = !nameError && parsed.workflow != null,
        onSave = { parsed.workflow?.let { onSave(it, existed) } },
        form = {
            Column {
                ManageTextField(name, { name = it; touched = true }, "名称", singleLine = true)
                Spacer(Modifier.height(10.dp))
                ManageTextField(desc, { desc = it }, "描述（可选）", singleLine = true)
                Spacer(Modifier.height(10.dp))
                ManageTextField(stepsText, { stepsText = it; touched = true }, "步骤 JSON", singleLine = false, minLines = 8)
                if (touched && stepError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(stepError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "格式：[{\"id\":\"s1\",\"tool\":\"工具名\",\"args\":{},\"dependsOn\":[]}]",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "产物（文档/报告）建议写到当前工作区的 workflows/ 子目录下",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    )
}

private const val DEFAULT_STEPS_JSON =
    """[
  {
    "id": "s1",
    "tool": "list_files",
    "args": { "path": "." },
    "dependsOn": []
  }
]"""
