package com.wy.openx.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 按属性查找节点的条件。所有非 null 字段是 **AND** 关系。
 *
 * `text` / `textContains` 会同时匹配 `text` 与 `contentDescription`：
 * 界面上"看到的字"经常挂在描述上（图标按钮尤其如此），只认 text 会让调用方
 * 反复找不到明明在屏幕上的控件。
 */
data class UiSelector(
    val text: String? = null,
    val textContains: String? = null,
    val className: String? = null,
    val classNameContains: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val id: String? = null,
    val idContains: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    /** 按应用包名限定（跨窗口或确认"目标 App 真的起来了"时用）。 */
    val pkgName: String? = null,
    val pkgContains: String? = null,
    /** 跳过前 N 个匹配项，用于"同名按钮点第二个"。 */
    val index: Int = 0
) {
    val hasCondition: Boolean
        get() = text != null || textContains != null || className != null ||
            classNameContains != null || desc != null || descContains != null ||
            id != null || idContains != null || clickable != null || editable != null ||
            scrollable != null || enabled != null || checked != null || focused != null ||
            pkgName != null || pkgContains != null

    companion object {
        fun parse(json: String?): UiSelector {
            val o = runCatching { JsonParser.parseString(json.orEmpty()).asJsonObject }
                .getOrElse { JsonObject() }

            fun str(vararg keys: String): String? {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (e.isJsonNull || !e.isJsonPrimitive) continue
                    val v = e.asString
                    if (v.isNotBlank()) return v
                }
                return null
            }

            fun bool(vararg keys: String): Boolean? {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) continue
                    return e.asBoolean
                }
                return null
            }

            val idx = o.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt ?: 0

            return UiSelector(
                text = str("text"),
                textContains = str("textContains", "text_contains"),
                className = str("className", "class_name", "cls"),
                classNameContains = str("classNameContains", "class_name_contains"),
                desc = str("desc", "contentDesc", "content_desc"),
                descContains = str("descContains", "contentDescContains"),
                id = str("id", "resourceId", "resource_id"),
                idContains = str("idContains", "resourceIdContains"),
                clickable = bool("clickable"),
                editable = bool("editable"),
                scrollable = bool("scrollable"),
                enabled = bool("enabled"),
                checked = bool("checked"),
                focused = bool("focused"),
                pkgName = str("package", "packageName", "pkg"),
                pkgContains = str("packageContains", "pkgContains"),
                index = idx.coerceAtLeast(0)
            )
        }
    }
}

/**
 * 无障碍节点树的查询与序列化。
 *
 * 为什么扁平化成数组：插件侧拿到的是 JSON，嵌套结构在 QuickJS 里递归展开更慢，
 * 而且带 parent 索引后插件可以自由重建树、只渲染关心的分支，不必整棵走完。
 *
 * 字段名用短名（`cls`/`b`/`clk`），因为一份真实应用的界面树常有几百个节点，
 * 字段名长度会直接放大 JSON 体积。
 */
object UiTreeQuery {

    private const val MAX_TEXT = 200

    /** 节点数上限，防止超大界面把 JSON 撑爆（聊天页轻松上千节点）。 */
    const val DEFAULT_MAX_NODES = 400
    const val MAX_NODES_LIMIT = 2000
    const val DEFAULT_MAX_DEPTH = 40

    fun snapshotJson(root: AccessibilityNodeInfo?, maxDepth: Int, maxNodes: Int): String {
        if (root == null) {
            return "{\"ok\":false,\"error\":\"无法获取界面根节点（可能没有活动窗口）\"}"
        }
        val sb = StringBuilder(4096)
        sb.append("{\"ok\":true,\"nodes\":[")
        val writer = Writer(sb, maxDepth.coerceIn(1, 200), maxNodes.coerceIn(1, MAX_NODES_LIMIT))
        try {
            writer.write(root, -1, 0, root.packageName?.toString().orEmpty())
        } finally {
            root.recycle()
        }
        sb.append("],\"count\":").append(writer.count)
        sb.append(",\"truncated\":").append(writer.truncated).append('}')
        return sb.toString()
    }

    /**
     * 查找匹配节点，返回的节点是 `obtain` 出来的副本，调用方负责 recycle。
     *
     * 只返回 `isVisibleToUser` 的节点：屏幕外的节点点了没有意义，
     * 而且滚动列表里常有几十个离屏的重复控件，混进结果只会误导调用方。
     */
    fun find(root: AccessibilityNodeInfo?, sel: UiSelector, limit: Int): List<AccessibilityNodeInfo> {
        if (root == null || !sel.hasCondition) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val budget = limit.coerceIn(1, 200)
        val visitLimit = MAX_NODES_LIMIT * 4
        var skipped = 0
        var visited = 0

        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= budget || visited > visitLimit) return
            visited++
            if (node.isVisibleToUser && matches(node, sel)) {
                if (skipped < sel.index) skipped++ else out.add(AccessibilityNodeInfo.obtain(node))
            }
            if (out.size >= budget) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child)
                } finally {
                    child.recycle()
                }
                if (out.size >= budget) return
            }
        }

        walk(root)
        return out
    }

    /** 把一批节点序列化成与 snapshot 相同格式的 JSON。 */
    fun nodesJson(nodes: List<AccessibilityNodeInfo>, emptyHint: String?): String {
        val sb = StringBuilder(1024)
        sb.append("{\"ok\":true,\"nodes\":[")
        nodes.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            appendNode(sb, n, i, -1, 0, n.packageName?.toString().orEmpty())
        }
        sb.append("],\"count\":").append(nodes.size)
        if (nodes.isEmpty() && emptyHint != null) {
            sb.append(",\"hint\":\"").append(esc(emptyHint)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    fun oneNodeJson(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder(256)
        appendNode(sb, node, 0, -1, 0, node.packageName?.toString().orEmpty())
        return sb.toString()
    }

    private fun matches(n: AccessibilityNodeInfo, s: UiSelector): Boolean {
        val text = n.text?.toString().orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        val cls = n.className?.toString().orEmpty()
        val vid = n.viewIdResourceName.orEmpty()

        s.text?.let { if (text != it && desc != it) return false }
        s.textContains?.let { if (!text.contains(it) && !desc.contains(it)) return false }
        s.className?.let { if (cls != it && !cls.endsWith(".$it")) return false }
        s.classNameContains?.let { if (!cls.contains(it)) return false }
        s.desc?.let { if (desc != it && text != it) return false }
        s.descContains?.let { if (!desc.contains(it) && !text.contains(it)) return false }
        s.id?.let {
            if (vid != it && !vid.endsWith("/$it") && !vid.endsWith(":id/$it")) return false
        }
        s.idContains?.let { if (!vid.contains(it)) return false }
        s.clickable?.let { if (n.isClickable != it) return false }
        s.editable?.let { if (n.isEditable != it) return false }
        s.scrollable?.let { if (n.isScrollable != it) return false }
        s.enabled?.let { if (n.isEnabled != it) return false }
        s.checked?.let { if (n.isChecked != it) return false }
        s.focused?.let { if (n.isFocused != it) return false }
        val pkg = n.packageName?.toString().orEmpty()
        s.pkgName?.let { if (pkg != it && !pkg.endsWith(".$it")) return false }
        s.pkgContains?.let { if (!pkg.contains(it)) return false }
        return true
    }

    private class Writer(
        private val sb: StringBuilder,
        private val maxDepth: Int,
        private val maxNodes: Int
    ) {
        var count = 0
        var truncated = false

        fun write(node: AccessibilityNodeInfo, parent: Int, depth: Int, rootPkg: String) {
            if (truncated) return
            if (count >= maxNodes) {
                truncated = true
                return
            }
            val idx = count++
            if (idx > 0) sb.append(',')
            appendNode(sb, node, idx, parent, depth, rootPkg)
            if (depth >= maxDepth) {
                if (node.childCount > 0) truncated = true
                return
            }
            for (i in 0 until node.childCount) {
                if (truncated) return
                val child = node.getChild(i) ?: continue
                try {
                    write(child, idx, depth + 1, rootPkg)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun appendNode(
        sb: StringBuilder,
        n: AccessibilityNodeInfo,
        index: Int,
        parent: Int,
        depth: Int,
        rootPkg: String
    ) {
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        sb.append("{\"i\":").append(index)
        sb.append(",\"p\":").append(parent)
        sb.append(",\"d\":").append(depth)
        sb.append(",\"cls\":\"").append(esc(n.className?.toString()?.substringAfterLast('.') ?: "")).append('"')
        sb.append(",\"b\":[").append(bounds.left).append(',').append(bounds.top)
            .append(',').append(bounds.right).append(',').append(bounds.bottom).append(']')

        val text = n.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            sb.append(",\"text\":\"").append(esc(text.take(MAX_TEXT))).append('"')
        }
        val desc = n.contentDescription?.toString().orEmpty()
        if (desc.isNotEmpty()) {
            sb.append(",\"desc\":\"").append(esc(desc.take(MAX_TEXT))).append('"')
        }
        val vid = n.viewIdResourceName.orEmpty()
        if (vid.isNotEmpty()) {
            sb.append(",\"id\":\"").append(esc(vid)).append('"')
        }

        if (n.isClickable) sb.append(",\"clk\":true")
        if (n.isEditable) sb.append(",\"ed\":true")
        if (n.isScrollable) sb.append(",\"scr\":true")
        if (n.isCheckable) sb.append(",\"chk\":").append(n.isChecked)
        if (!n.isEnabled) sb.append(",\"dis\":true")
        if (n.isFocused) sb.append(",\"foc\":true")
        if (n.isSelected) sb.append(",\"sel\":true")

        val pkg = n.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != rootPkg) {
            sb.append(",\"pkg\":\"").append(esc(pkg)).append('"')
        }
        sb.append('}')
    }

    private fun esc(s: String): String {
        var needEscape = false
        for (c in s) {
            if (c == '"' || c == '\\' || c < ' ') {
                needEscape = true
                break
            }
        }
        if (!needEscape) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
