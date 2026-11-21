package com.wy.openx.plugin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.ui.UiNodeParser.actionId
import com.wy.openx.plugin.ui.UiNodeParser.color
import com.wy.openx.plugin.ui.UiNodeParser.strOrNull
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 浮层类组件（Dialog / ModalBottomSheet）。
 *
 * ## 为什么单独一个文件
 * 这类组件的渲染方式与普通组件不同：它们**不占父布局位置**，而是浮在整棵树上，
 * 并且内部持有一个"是否展开"的 UI 状态。放在 [UiRendererExt] 里会打断那串
 * "占位型组件"的阅读节奏，也容易让读者误以为它们和 Button 一样在流式布局里。
 *
 * ## 展开状态怎么管
 * 优先读 JS 侧 state（`visible` / `stateKey` 指定的 useState），
 * 这样"点确定后关闭"由插件的 state 决定；插件没给 state 时，
 * 退回组件自己的本地状态，至少不会卡住。
 */
@Composable
fun RenderUiNodeOverlay(node: UiNode, ctx: UiRenderContext): Boolean {
    when (node.type.lowercase()) {
        "dialog", "alertdialog", "alert_dialog" -> RenderDialog(node, ctx)
        "modalbottomsheet", "bottomsheet", "sheet" -> RenderBottomSheet(node, ctx)
        else -> return false
    }
    return true
}

/** 对话框。props：`visible`/`stateKey`、`title`、`text`、`confirmText`、`dismissText`、`onConfirm`、`onDismiss`。 */
@Composable
private fun RenderDialog(node: UiNode, ctx: UiRenderContext) {
    val confirmAction = node.actionId("onConfirm") ?: node.actionId("onOk")
    val dismissAction = node.actionId("onDismiss") ?: node.actionId("onCancel")
    val hasState = node.props.containsKey("visible") || node.props.containsKey("stateKey")

    // 本地兜底状态：插件没绑 state 时，靠它控制显隐
    var localVisible by remember { mutableStateOf(true) }
    val visible = if (hasState) node.boolOrState(ctx, "visible") else localVisible
    if (!visible) return

    fun close(after: (() -> Unit)?) {
        if (!hasState) localVisible = false
        after?.invoke()
    }

    val titleText = node.strOrNull("title")
    val bodyText = node.strOrNull("text") ?: node.strOrNull("message")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            // 点遮罩/返回：有 onDismiss 就回调，否则直接关（本地态）
            if (dismissAction != null) close { ctx.onAction(dismissAction, emptyMap(), false) }
            else close(null)
        },
        title = if (titleText != null) {
            { Text(titleText, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        } else null,
        text = {
            Column {
                if (bodyText != null) {
                    Text(bodyText, color = TextSecondary, fontSize = 13.sp)
                }
                if (node.children.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    node.children.forEach { RenderUiNode(it, ctx) }
                }
            }
        },
        confirmButton = {
            val label = node.strOrNull("confirmText") ?: "确定"
            TextButton(onClick = {
                confirmAction?.let { a ->
                    close { ctx.onAction(a, emptyMap(), false) }
                } ?: close(null)
            }) {
                Text(label, color = UiTheme.colorOr(node.color("confirmColor"), Accent))
            }
        },
        dismissButton = node.strOrNull("dismissText")?.let { label ->
            {
                TextButton(onClick = {
                    dismissAction?.let { a -> close { ctx.onAction(a, emptyMap(), false) } } ?: close(null)
                }) {
                    Text(label, color = TextSecondary)
                }
            }
        },        containerColor = Panel,
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * 底部抽屉。props：`visible`/`stateKey`、`title`、`onDismiss`。
 *
 * ⚠ `ModalBottomSheet` 只要被组合就必然弹出，所以**必须在不可见时不组合它**（下面的 early return）。
 * 这点和 Dialog 不同 —— Dialog 有 `visible` 参数，底部抽屉没有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderBottomSheet(node: UiNode, ctx: UiRenderContext) {
    val dismissAction = node.actionId("onDismiss")
    val hasState = node.props.containsKey("visible") || node.props.containsKey("stateKey")
    var localVisible by remember { mutableStateOf(true) }
    val visible = if (hasState) node.boolOrState(ctx, "visible") else localVisible
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (!hasState) localVisible = false
            dismissAction?.let { ctx.onAction(it, emptyMap(), false) }
        },
        sheetState = sheetState,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)
        ) {
            node.strOrNull("title")?.let {
                Text(
                    it,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            node.children.forEach { RenderUiNode(it, ctx) }
        }
    }
}

/** 未使用但保留：提示颜色常量引用，避免 import 抖动。 */
@Suppress("unused")
private val overlayHintColor: Color = Color(0xFFFFA726)
