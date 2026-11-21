package com.wy.openx.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.color
import com.wy.openx.plugin.ui.UiNodeParser.num
import com.wy.openx.plugin.ui.UiNodeParser.numOrNull
import com.wy.openx.plugin.ui.UiNodeParser.str
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 插件 UI 的主题 token 与修饰符解析。
 *
 * 插件里可以写 `color: 'primary'` 而不是硬编码色值 —— 这样插件界面会自动跟随
 * OpenX 的明暗主题，不会出现"深色模式下插件界面白得刺眼"。
 *
 * 同时接受 `#RRGGBB` / `#AARRGGBB` 硬编码色，给需要精确配色的插件用。
 */
object UiTheme {

    /** token 名 → 颜色。未知 token 回退为 null，让调用方用默认色。 */
    fun colorOf(token: String?): Color? {
        if (token.isNullOrBlank()) return null
        var t = token.trim()
        // Operit 兼容：`token@0.96` 的 alpha 后缀。`ctx.MaterialTheme.colorScheme
        // .surface.copy({ alpha: 0.96 })` 序列化后就是这种形态，而 Operit 把它当颜色
        // 一等公民到处用（background / tint / color），所以必须在**唯一收口点**这里认。
        var alpha: Float? = null
        val at = t.lastIndexOf('@')
        if (at > 0) {
            val a = t.substring(at + 1).toFloatOrNull()
            if (a != null && a in 0f..1f) {
                alpha = a
                t = t.substring(0, at)
            }
        }
        // 硬编码色值
        val base = if (t.startsWith("#")) {
            parseHex(t)
        } else {
            when (t.lowercase()) {
                "primary", "accent", "tertiary" -> Accent
                "onprimary", "on_primary", "ontertiary" -> Color.White
                "background", "surface", "surfacevariant", "surface_variant" -> Panel
                "surfacecontainer", "surfacecontainerhigh", "surfacecontainerlow",
                "surfacecontainerhighest", "surfacecontainerlowest", "inversesurface" -> Panel
                "onbackground", "onsurface", "text", "textprimary" -> TextPrimary
                "textsecondary", "secondary", "onsurfacevariant", "on_surface_variant" -> TextSecondary
                "border", "outline", "outlinevariant", "outline_variant" -> Border
                "error" -> Color(0xFFFF6B6B)
                "onerror" -> Color.White
                "success" -> Color(0xFF4CAF50)
                "warning" -> Color(0xFFFFA726)
                "transparent" -> Color.Transparent
                "scrim" -> Color.Black
                else -> null
            }
        }
        return if (base != null && alpha != null) base.copy(alpha = alpha) else base
    }

    /** `#RGB` / `#RRGGBB` / `#AARRGGBB`。非法返回 null。 */
    private fun parseHex(raw: String): Color? {
        val hex = raw.removePrefix("#")
        return runCatching {
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color(red = r, green = g, blue = b)
                }
                6 -> Color(hex.toLong(16) or 0xFF000000L)
                8 -> Color(hex.toLong(16))
                else -> null
            }
        }.getOrNull()
    }

    /** 取色，带默认值。 */
    fun colorOr(token: String?, def: Color): Color = colorOf(token) ?: def
}

/**
 * 归一化上下界后再夹取。
 *
 * 直接用 `coerceIn(a, b)` 时若 a > b 会抛 IllegalArgumentException（空区间），而这里的
 * 上下界大多来自**插件传入的数据**（尺寸、min/max、屏幕像素）—— 插件写错一个数不应该
 * 让宿主崩掉。曾因 `coerceIn(0, frameBitmaps.size - 1)` 在空序列上抛异常，
 * 导致所有 `Image` 组件崩溃（已修，同样加了这个保护）。
 */
internal fun safeCoerce(value: Int, a: Int, b: Int): Int =
    value.coerceIn(minOf(a, b), maxOf(a, b))

internal fun safeCoerce(value: Float, a: Float, b: Float): Float =
    value.coerceIn(minOf(a, b), maxOf(a, b))

/**
 * 把节点的公共 props 应用到 Modifier。
 *
 * 支持的属性（对齐 Operit 的 `applyCommonModifier` 里我们用到的那部分）：
 *  `fillMaxWidth` / `fillMaxHeight` / `fillMaxSize`
 *  `width` / `height` / `size`（数字 = dp，或 "fill"）
 *  `padding` / `paddingHorizontal` / `paddingVertical`
 *  `background`（token 或 #hex）/ `cornerRadius` / `clip`
 *  `alpha` / `rotate` / `scale` / `scaleX` / `scaleY` / `offsetX` / `offsetY`（绘制变换）
 *
 * 变换类属性走 `graphicsLayer`，也就是**只影响绘制、不参与布局测量**：
 * 呼吸、倾斜、悬浮这类动画不会把周围控件挤动，也不会因动画触发整页重排。
 * 代价是超出自身边界的内容仍会画出来（不会被裁），需要裁切就再配 `clip`。
 *
 * @param applyPadding 是否在此施加 padding。Card/Surface 传 false —— 它们的内衬由
 *   自身的内层容器统一施加（见 [containerPaddingModifier]），否则会叠成两倍。
 */
fun Modifier.applyNodeModifier(node: UiNode, applyPadding: Boolean = true): Modifier {
    var m = this

    // fillMax*
    if (node.bool("fillMaxSize")) m = m.fillMaxSize()
    if (node.bool("fillMaxWidth")) m = m.fillMaxWidth()
    // 曾经误为 fillMaxSize：高度方向想撑满时把宽度也撑满了，组件横向溢出屏幕
    if (node.bool("fillMaxHeight")) m = m.fillMaxHeight()

    // 尺寸
    node.numOrNull("width")?.let { m = m.width(it.dp) }
    node.numOrNull("height")?.let { m = m.height(it.dp) }
    if (node.str("width") == "fill") m = m.fillMaxWidth()
    if (node.str("height") == "fill") m = m.fillMaxHeight()
    node.numOrNull("size")?.let { m = m.size(it.dp) }

    // 内边距（容器类会跳过，见 applyPadding 参数）
    if (applyPadding) {
        node.numOrNull("padding")?.let { m = m.padding(it.dp) }
        val hPad = node.numOrNull("paddingHorizontal")
        val vPad = node.numOrNull("paddingVertical")
        if (hPad != null || vPad != null) {
            m = m.padding(horizontal = (hPad ?: 0f).dp, vertical = (vPad ?: 0f).dp)
        }
    }

    // 圆角 + 背景：**clip 必须在前**。
    // Compose 的修饰符按链序生效：若先 background 再 clip，背景已经画完了，
    // clip 只影响其后的内容 —— 结果是「圆角失效、背景是方的」。
    val clipShape = node.numOrNull("cornerRadius")?.let { RoundedCornerShape(it.dp) }
        ?: if (node.bool("clip")) RoundedCornerShape(8.dp) else null
    if (clipShape != null) m = m.clip(clipShape)

    val bg = UiTheme.colorOf(node.color("background"))
    if (bg != null) m = m.background(bg)

    // 绘制变换。单独没有这个能力以前，插件想做「呼吸」只能改 width/height，
    // 那会真的改变布局尺寸（大呼吸把旁边控件顶开），且只有缩放一种动作；
    // 加上位移与旋转才能做出弹跳/倾斜/视差。
    val alpha = node.numOrNull("alpha")
    val rotate = node.numOrNull("rotate")
    val scale = node.numOrNull("scale")
    val scX = node.numOrNull("scaleX")
    val scY = node.numOrNull("scaleY")
    val offsetX = node.numOrNull("offsetX")
    val offsetY = node.numOrNull("offsetY")
    val hasTransform = alpha != null || rotate != null || scale != null ||
        scX != null || scY != null || offsetX != null || offsetY != null
    if (hasTransform) {
        val pivotX = node.num("pivotX", 0.5f)
        val pivotY = node.num("pivotY", 0.5f)
        m = m.graphicsLayer {
            alpha?.let { this.alpha = it.coerceIn(0f, 1f) }
            rotate?.let { rotationZ = it }
            (scX ?: scale)?.let { this.scaleX = it }
            (scY ?: scale)?.let { this.scaleY = it }
            // offsetX/offsetY 单位是 dp（与 width/padding 一致）；
            // GraphicsLayerScope 本身就是 Density，直接换算即可
            offsetX?.let { translationX = it.dp.toPx() }
            offsetY?.let { translationY = it.dp.toPx() }
            transformOrigin = TransformOrigin(pivotX, pivotY)
        }
    }

    return m
}

/**
 * Card / Surface 的内衬。
 *
 * 整块 padding 已在 [applyNodeModifier] 里被跳过（applyPadding=false），
 * 这里统一施加一次 —— 否则「外层 12 + 内层 8」会叠成 20，插件写多少都偏大。
 */
fun UiNode.containerPaddingModifier(defaultDp: Float): Modifier {
    val all = numOrNull("padding")
    val h = numOrNull("paddingHorizontal")
    val v = numOrNull("paddingVertical")
    if (all == null && h == null && v == null) return Modifier.padding(defaultDp.dp)
    return Modifier.padding(
        horizontal = (h ?: all ?: 0f).dp,
        vertical = (v ?: all ?: 0f).dp
    )
}

/** 取内边距（给 Row/Column 的 content padding 用）。 */
fun UiNode.paddingValues(): PaddingValues {
    val all = numOrNull("padding") ?: 0f
    val h = numOrNull("paddingHorizontal") ?: all
    val v = numOrNull("paddingVertical") ?: all
    return PaddingValues(horizontal = h.dp, vertical = v.dp)
}

/**
 * dp 解析：数字直接用；`"fill"` 之类语义值返回 null（由调用方处理）。
 */
fun UiNode.dpOrNull(key: String): Dp? = numOrNull(key)?.dp

/** 字号：数字按 sp 处理。 */
fun UiNode.fontSizeSp(def: Float): Float = num("fontSize", def)

/** 尺寸对（给 size(...) 用）。 */
fun UiNode.sizeOrNull(): DpSize? {
    val w = numOrNull("width") ?: return null
    val h = numOrNull("height") ?: return null
    return DpSize(w.dp, h.dp)
}

/** 便捷：把 Float 当成 sp。 */
val Float.spValue: Float get() = this

/** 便捷：把 Int 当字号 sp。 */
fun Int.asSp() = this.sp
