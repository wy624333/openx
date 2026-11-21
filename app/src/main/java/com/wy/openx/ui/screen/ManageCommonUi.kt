package com.wy.openx.ui.screen

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFgOnFill
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 管理类页面的共用 UI 组件（记忆 / MCP / 技能 三页统一样式）。
 *
 * 抽出原因：三页原本各写一套顶栏、卡片、弹层，观感不一致。此处集中定义后，
 * 三页只需组装，样式改一处即全生效。视觉基准取自「记忆管理」页。
 */

/** 管理页顶栏：返回 + 标题 + 右侧任意操作（图标按钮或文字按钮）。 */
@Composable
fun ManageTopBar(
    title: String,
    onBack: () -> Unit,
    /** 非空时在最右侧渲染 ⓘ，点开看本页说明（说明不再铺在页面里）。 */
    onGuide: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 返回按钮尺寸与其余页面顶栏统一（40dp 按钮 + 20dp 图标）：
        // 原来用默认 48dp，各页顶栏高度对不齐。
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // ⓘ 排在操作按钮组的第一位（靠左），不挤到最右端
        if (onGuide != null) {
            IconButton(onClick = onGuide, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "本页使用说明",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        actions()
    }
}

/** 顶部说明文字（统一字号与色）。 */
@Composable
fun ManageHint(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/**
 * 管理页通用卡片：34dp 圆角图标容器 + 标题（可带右侧小标记）+ 摘要 1 行 + 尾部箭头。
 * 点击回调由调用方决定（弹详情 / 展开）。
 *
 * 摘要只占一行、内外间距收紧：列表里卡片堆得很高时一屏只能看三四条，
 * 标题/摘要/时间各占一行就够认出是哪条了，内容细节点进去看。
 */
@Composable
fun ManageCard(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    meta: String = "",
    /** meta 行颜色；null 用默认次级色。MCP 页用它区分「连接成功（绿）/ 连接失败（红）」。 */
    metaColor: Color? = null,
    titleSuffix: @Composable () -> Unit = {},
    /**
     * 标题下方**独立一行**的徽标区。
     *
     * 与 [titleSuffix] 的区别：titleSuffix 跟在标题**同一行**，标题一长就会把它挤掉；
     * 本槽位另起一行，不管插件名多长都不会被挤压。插件名普遍偏长（如“侧边栏测试A（有界面）”）
     * 这类场景应该用本槽位。
     */
    titleBadges: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {}
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // weight(fill=false)：标题按内容取宽，但剩余宽度不够时**先截断自己**，
                        // 把空间让给同行后缀。不加的话标题会吃掉整行宽度，后缀徽标被挤没。
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    titleSuffix()
                }
                titleBadges()
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary.replace("\n", " ").trim(),
                        color = TextSecondary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meta,
                        color = metaColor ?: TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            trailing()
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 列表空态提示（统一样式）。 */
@Composable
fun ManageEmpty(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text, color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)
        )
    }
}

/** 小标签 pill（作用域/类型/分类等）。 */
@Composable
fun ManageTag(text: String, color: Color = Accent) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            color = color, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 全屏详情/编辑弹层外壳：顶栏（标题 + 可选操作）+ 可滚动内容 + 可选底部操作行。
 * 记忆详情、MCP 详情、技能详情共用此骨架，保证三页弹层观感一致。
 */
@Composable
fun ManageDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    tags: @Composable () -> Unit = {},
    /** 标题与内容之间的自定义区（如 tab 条）。在滚动区**之外**，不会跟着内容滚走。 */
    subHeader: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            color = BgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (onEdit != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = Accent)
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B))
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = TextPrimary)
                    }
                }
                tags()
                subHeader()
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        content()
                    }
                }
                footer()
            }
        }
    }
}

/** 全屏表单弹层外壳（添加/编辑）：顶栏 + 副标题 + 表单区 + 底部取消/保存。 */
@Composable
fun ManageFormDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    saveLabel: String = "保存",
    form: @Composable () -> Unit
) {
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
                        title,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = TextPrimary)
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {
                    form()
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
                        onClick = onSave,
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFgOnFill),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(saveLabel, color = ButtonFgOnFill) }
                }
            }
        }
    }
}

/** 统一确认弹窗（删除等破坏性操作）。 */
@Composable
fun ManageConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = { Text(message, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Color(0xFFFF6B6B)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        },
        containerColor = Panel
    )
}

/** 表单输入框统一样式。 */
@Composable
fun manageFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Border,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary
)

/** 表单里的多行文本域（统一 min 高度）。 */
@Composable
fun ManageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().then(
            if (minLines > 1) Modifier.heightIn(min = 100.dp) else Modifier
        ),
        colors = manageFieldColors()
    )
}

/**
 * 设置类页面顶栏 ⓘ 打开的说明弹窗：若干「小标题 + 正文」段落。
 *
 * 说明文字统一收进这里而不是铺在页面底部，页面正文才不被一屏小字淹没。
 * 文案要求简洁——每条一两句，只讲「这一项到底影响什么」。
 */
@Composable
fun GuideDialog(
    title: String,
    blocks: List<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                blocks.forEach { (head, body) ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text(head, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            body,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = Accent) }
        },
        containerColor = Panel
    )
}
