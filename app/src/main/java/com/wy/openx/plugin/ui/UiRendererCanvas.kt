package com.wy.openx.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.ui.UiNodeParser.actionId
import com.wy.openx.plugin.ui.UiNodeParser.num
import com.wy.openx.plugin.ui.UiNodeParser.numOrNull
import com.wy.openx.plugin.ui.UiNodeParser.str
import com.wy.openx.ui.theme.TextPrimary

/** 插件没写尺寸时的画布边长（dp）。 */
private const val DEFAULT_CANVAS_SIZE_DP = 120f

/**
 * 绘制指令名。
 *
 * 抽成常量而不是直接写字面量分支：UiRendererComponentUniquenessTest 把所有
 * `UiRenderer*.kt` 里的 `"名字" ->` 分支当作「组件名」收集，而 `text` / `center` / `right`
 * 在这里是画布的绘制指令与文字对齐值，不是组件 —— 写成字面量分支会被自检误报成
 * 「组件名冲突」（实测确实报了）。
 */
private object CanvasOp {
    const val RECT = "rect"
    const val CIRCLE = "circle"
    const val ARC = "arc"
    const val LINE = "line"
    const val PATH = "path"
    const val POLYLINE = "polyline"
    const val POLYGON = "polygon"
    const val TEXT = "text"
}

/** 文字横向对齐取值。同样为了避开组件名自检。 */
private object CanvasAlign {
    const val CENTER = "center"
    const val RIGHT = "right"
}

/**
 * 画布：插件用**声明式绘制指令**画任意图形。
 *
 * ## 为什么给这个组件
 * 原有组件全是「盒子 + 背景色」，画不出圆、弧、折线这类东西。插件想画进度环、
 * 折线图、像素形象，只能贴一张提前做好的位图 —— 既占体积，也没法在运行时改尺寸、
 * 改配色、按状态换表情。
 *
 * ## 为什么不是让插件注入绘制代码
 * 传一段 JS 回调给宿主在 DrawScope 里执行，等于把插件的代码塞进宿主的绘制流程。
 * 这里传的是**数据**（一串绘制指令），宿主逐条翻译成 Compose 绘制调用：插件始终
 * 只是数据来源，与其它组件同一套安全模型。
 *
 * ## 用法
 * ```js
 * UI.Canvas({ width: 120, height: 120, ops: [
 *   { op: "circle", cx: 60, cy: 60, r: 46, fill: "#FFCC80" },
 *   { op: "rect", x: 10, y: 10, w: 40, h: 12, radius: 6, fill: "#fff", stroke: "#8D6E63", strokeWidth: 2 },
 *   { op: "path", points: [[10, 80], [30, 60], [50, 90]], stroke: "#4CAF50", strokeWidth: 2 },
 *   { op: "text", x: 60, y: 40, text: "喵", size: 18, align: "center" }
 * ] })
 * ```
 *
 * 坐标一律按 **dp** 解释（与 `width`/`height` 的单位一致），颜色吃 `#RRGGBB` /
 * `#AARRGGBB` / `transparent` / 主题 token。认不出的 `op` 静默跳过 —— 一个画不出来的
 * 图形不该让整页空白。
 */
@Composable
fun RenderUiNodeCanvas(node: UiNode, ctx: UiRenderContext, m: Modifier): Boolean {
    if (!node.type.equals("canvas", ignoreCase = true)) return false

    val ops = (node.props["ops"] as? List<*>)
        ?.mapNotNull { it as? Map<*, *> }
        .orEmpty()
    val textMeasurer = rememberTextMeasurer()
    val w = node.numOrNull("width") ?: DEFAULT_CANVAS_SIZE_DP
    val h = node.numOrNull("height") ?: w

    Canvas(
        modifier = if (node.actionId("onClick") != null) {
            // 容器类组件不自带点击，而「点宠物 / 点图表某块」是最基本的交互，
            // 所以画布自己处理 onClick（长按已由公共 modifier 链处理）。
            val id = node.actionId("onClick")!!
            m.clickable { ctx.onAction(id, emptyMap(), false) }
        } else {
            m
        }.size(w.dp, h.dp)
    ) {
        ops.forEach { drawCanvasOp(it, textMeasurer) }
    }
    return true
}

/** 执行一条绘制指令。 */
private fun DrawScope.drawCanvasOp(op: Map<*, *>, textMeasurer: TextMeasurer) {
    fun num(key: String, def: Float = 0f): Float = when (val v = op[key]) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: def
        else -> def
    }

    fun str(key: String): String? = op[key]?.toString()?.takeIf { it.isNotBlank() }

    val d = density
    val kind = str("op")?.lowercase() ?: return

    val fill = UiTheme.colorOf(str("fill"))
    val strokeColor = UiTheme.colorOf(str("stroke"))
    val stroke = Stroke(width = num("strokeWidth", 1.5f) * d, cap = StrokeCap.Round)

    when (kind) {
        CanvasOp.RECT -> {
            val topLeft = Offset(num("x") * d, num("y") * d)
            val size = Size(num("w") * d, num("h") * d)
            val radius = CornerRadius(num("radius") * d)
            fill?.let { drawRoundRect(color = it, topLeft = topLeft, size = size, cornerRadius = radius) }
            strokeColor?.let {
                drawRoundRect(
                    color = it, topLeft = topLeft, size = size,
                    cornerRadius = radius, style = stroke
                )
            }
        }

        CanvasOp.CIRCLE -> {
            val center = Offset(num("cx") * d, num("cy") * d)
            val r = num("r") * d
            fill?.let { drawCircle(color = it, radius = r, center = center) }
            strokeColor?.let { drawCircle(color = it, radius = r, center = center, style = stroke) }
        }

        CanvasOp.LINE -> {
            val c = strokeColor ?: fill ?: return
            drawLine(
                color = c,
                start = Offset(num("x1") * d, num("y1") * d),
                end = Offset(num("x2") * d, num("y2") * d),
                strokeWidth = num("strokeWidth", 1.5f) * d,
                cap = StrokeCap.Round
            )
        }

        CanvasOp.PATH, CanvasOp.POLYLINE, CanvasOp.POLYGON -> {
            val pts = opPoints(op, d)
            if (pts.size < 2) return
            val closed = kind == CanvasOp.POLYGON || op["close"] == true || str("close") == "true"
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                if (closed) close()
            }
            // 闭合图形（或有 fill 的折线）先铺底再描边，否则描边会被填色盖掉一半
            fill?.let { drawPath(path, it) }
            strokeColor?.let { drawPath(path, it, style = stroke) }
        }

        CanvasOp.ARC -> {
            val topLeft = Offset(num("x") * d, num("y") * d)
            val size = Size(num("w") * d, num("h") * d)
            // 角度按度数写（与 Compose 一致），0 度在三点钟方向、顺时针为正
            val startAngle = num("start")
            val sweepAngle = num("sweep", 360f)
            val useCenter = op["useCenter"] == true || str("useCenter") == "true"
            fill?.let {
                drawArc(
                    color = it, startAngle = startAngle, sweepAngle = sweepAngle,
                    useCenter = useCenter, topLeft = topLeft, size = size
                )
            }
            strokeColor?.let {
                drawArc(
                    color = it, startAngle = startAngle, sweepAngle = sweepAngle,
                    useCenter = useCenter, topLeft = topLeft, size = size, style = stroke
                )
            }
        }

        CanvasOp.TEXT -> {
            val content = str("text") ?: return
            val style = TextStyle(
                color = UiTheme.colorOf(str("color")) ?: TextPrimary,
                fontSize = num("size", 14f).sp,
                fontWeight = if (op["bold"] == true || str("bold") == "true") {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                }
            )
            val layout = textMeasurer.measure(content, style)
            // y 按「文字左上角」定，与 rect/circle 的坐标习惯一致；
            // 想让文字以某点为中点就自己按 size 减一半，比宿主猜基线可靠。
            val align = str("align")?.lowercase()
            val x = when {
                align == CanvasAlign.CENTER -> num("x") * d - layout.size.width / 2f
                align == CanvasAlign.RIGHT -> num("x") * d - layout.size.width
                else -> num("x") * d
            }
            drawText(layout, topLeft = Offset(x, num("y") * d))
        }
    }
}

/** 解析 `points: [[x,y], ...]`。 */
private fun opPoints(op: Map<*, *>, d: Float): List<Offset> =
    (op["points"] as? List<*>)?.mapNotNull { raw ->
        val pair = raw as? List<*> ?: return@mapNotNull null
        if (pair.size < 2) return@mapNotNull null
        Offset(
            (pair[0] as? Number)?.toFloat()?.times(d) ?: 0f,
            (pair[1] as? Number)?.toFloat()?.times(d) ?: 0f
        )
    }.orEmpty()
