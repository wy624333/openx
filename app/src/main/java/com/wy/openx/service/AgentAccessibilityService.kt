package com.wy.openx.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private var _instance: AgentAccessibilityService? = null
        val instance: AgentAccessibilityService? get() = _instance

        fun isRunning(): Boolean = _instance != null
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this
    }

    override fun onDestroy() {
        _instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    suspend fun tap(x: Int, y: Int): String = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { cont.resume("已点击 ($x, $y)") }
            override fun onCancelled(gd: GestureDescription?) { cont.resume("点击 ($x, $y) 被取消") }
        }, null)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { cont.resume("已从 ($x1,$y1) 滑动到 ($x2,$y2)") }
            override fun onCancelled(gd: GestureDescription?) { cont.resume("滑动被取消") }
        }, null)
    }

    fun dumpUi(): String {
        val root = rootInActiveWindow ?: return "无法获取界面信息（可能没有活动窗口）"
        val sb = StringBuilder()
        try {
            dumpNode(root, sb, 0)
        } finally {
            root.recycle()
        }
        return sb.toString().take(5000)
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val cn = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(100) ?: ""
        val desc = node.contentDescription?.toString()?.take(100) ?: ""
        val bounds = Rect().also { node.getBoundsInScreen(it) }

        val parts = mutableListOf<String>()
        if (text.isNotBlank()) parts.add("text=\"$text\"")
        if (desc.isNotBlank()) parts.add("desc=\"$desc\"")
        if (node.isClickable) parts.add("clickable")
        if (node.isCheckable) parts.add("checked=${node.isChecked}")
        if (node.isScrollable) parts.add("scrollable")
        if (node.isEditable) parts.add("editable")
        if (node.isPassword) parts.add("password")

        if (parts.isNotEmpty() || node.childCount == 0) {
            sb.appendLine("$indent[$cn] $bounds ${parts.joinToString(" ")}")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                dumpNode(child, sb, depth + 1)
            } finally {
                child?.recycle()
            }
        }
    }

    fun setFocusedText(text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
        repeat(5) {
            val root = rootInActiveWindow ?: return false
            var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null) target = findEditableNode(root)
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100)
                var result = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!result) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                target.recycle()
                root.recycle()
                if (result) return true
            } else {
                root.recycle()
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled && node.isVisibleToUser) {
            val copy = AccessibilityNodeInfo.obtain(node)
            return copy
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    fun wakeScreen(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return true
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "openx:wake"
        )
        try {
            wl.acquire(100)
        } finally {
            wl.release()
        }
        return pm.isInteractive
    }

    fun getBatteryInfo(): String {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) return "无法获取电池信息"
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val pct = if (scale > 0) level * 100 / scale else level
            val statusStr = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            "电量: ${pct}%\n温度: ${temp / 10.0}°C\n状态: $statusStr"
        } catch (e: Exception) {
            "获取电池信息失败: ${e.message}"
        }
    }

    fun getForegroundApp(): String {
        return try {
            val root = rootInActiveWindow
            if (root == null) return "无法获取前台应用（可能没有活动窗口）"
            val pkg = root.packageName ?: "未知"
            root.recycle()
            "前台应用包名: $pkg"
        } catch (e: Exception) {
            "获取前台应用失败: ${e.message}"
        }
    }

    // ============================================================
    // 五期：插件侧 UI 自动化语义层
    //
    // 上面的 tap/swipe/dumpUi 是给 AI 工具用的（返回人话），
    // 下面这组是给插件用的（返回 JSON），两者共用同一个无障碍实例。
    // ============================================================

    private fun jsonError(msg: String): String = "{\"ok\":false,\"error\":\"${jsonEsc(msg)}\"}"

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder(s.length + 8)
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

    /** 结构化快照：扁平节点数组，字段见 [UiTreeQuery]。 */
    fun uiSnapshot(maxNodes: Int, maxDepth: Int): String =
        UiTreeQuery.snapshotJson(rootInActiveWindow, maxDepth, maxNodes)

    /** 按条件查找节点，返回 JSON 数组（不含操作，只描述）。 */
    fun uiFind(selectorJson: String?, limit: Int): String {
        val sel = UiSelector.parse(selectorJson)
        if (!sel.hasCondition) return jsonError("缺少选择条件（至少要给 text / desc / id / className 之一）")
        val root = rootInActiveWindow ?: return jsonError("无法获取界面根节点（可能没有活动窗口）")
        val nodes = try {
            UiTreeQuery.find(root, sel, limit)
        } finally {
            root.recycle()
        }
        return try {
            if (nodes.isEmpty()) {
                "{\"ok\":true,\"nodes\":[],\"count\":0,\"hint\":\"没有匹配的节点，可先用 ctx.ui.snapshot() 看界面结构\"}"
            } else {
                UiTreeQuery.nodesJson(nodes, null)
            }
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * 点击：优先语义点击（自己 → 祖先的可点击节点），都失败才用坐标手势。
     *
     * 语义点击不依赖坐标，界面滚动或分辨率变化时比坐标点击稳得多，
     * 所以顺序不能反。
     */
    suspend fun uiClick(selectorJson: String?): String {
        val sel = UiSelector.parse(selectorJson)
        if (!sel.hasCondition) return jsonError("缺少选择条件")
        val root = rootInActiveWindow ?: return jsonError("无法获取界面根节点")
        val targets = try {
            UiTreeQuery.find(root, sel, 1)
        } finally {
            root.recycle()
        }
        if (targets.isEmpty()) return jsonError("没有匹配的节点")

        val node = targets[0]
        val ancestors = ArrayList<AccessibilityNodeInfo>()
        var cur: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        while (cur != null && ancestors.size < 6) {
            ancestors.add(cur)
            cur = runCatching { cur?.parent }.getOrNull()
        }
        try {
            var clicked = node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                for (a in ancestors) {
                    if (a.isClickable && a.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true
                        break
                    }
                }
            }
            if (clicked) {
                return "{\"ok\":true,\"via\":\"action\",\"node\":${UiTreeQuery.oneNodeJson(node)}}"
            }
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() <= 0 || r.height() <= 0) return jsonError("节点没有可见区域，无法点击")
            val cx = (r.left + r.right) / 2
            val cy = (r.top + r.bottom) / 2
            tap(cx, cy)
            return "{\"ok\":true,\"via\":\"gesture\",\"x\":$cx,\"y\":$cy," +
                "\"node\":${UiTreeQuery.oneNodeJson(node)}}"
        } finally {
            ancestors.forEach { runCatching { it.recycle() } }
            targets.forEach { runCatching { it.recycle() } }
        }
    }

    /** 给目标输入框设置文本（语义定位，不依赖焦点位置）。 */
    fun uiSetText(selectorJson: String?, text: String): Boolean {
        val parsed = UiSelector.parse(selectorJson)
        val sel = if (parsed.hasCondition) parsed else UiSelector(editable = true)
        val root = rootInActiveWindow ?: return false
        val nodes = try {
            UiTreeQuery.find(root, sel, 1)
        } finally {
            root.recycle()
        }
        if (nodes.isEmpty()) return false
        val node = nodes[0]
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    /** 滚动：找可滚动容器（目标自身或祖先），按方向发 forward/backward。 */
    fun uiScroll(selectorJson: String?, direction: String): Boolean {
        val sel = UiSelector.parse(selectorJson)
        val root = rootInActiveWindow ?: return false

        val container: AccessibilityNodeInfo?

        if (sel.hasCondition) {
            val nodes = try {
                UiTreeQuery.find(root, sel, 1)
            } finally {
                root.recycle()
            }
            if (nodes.isEmpty()) return false
            container = findScrollableSelfOrAncestor(nodes[0])
            nodes.forEach { if (it !== container) runCatching { it.recycle() } }
        } else {
            container = findFirstScrollable(root)
            root.recycle()
        }

        val target = container ?: return false
        return try {
            val action = when (direction.lowercase()) {
                "up", "backward", "back" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            target.performAction(action)
        } finally {
            runCatching { target.recycle() }
        }
    }

    private fun findScrollableSelfOrAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = start
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isScrollable) return cur
            val parent = runCatching { cur!!.parent }.getOrNull()
            if (cur !== start) runCatching { cur!!.recycle() }
            cur = parent
            hops++
        }
        return null
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = try {
                findFirstScrollable(child)
            } finally {
                runCatching { child.recycle() }
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 等某个节点出现，超时就失败。
     *
     * 每次轮询都重新取根节点：界面可能在两次查询之间整页重建，
     * 复用第一次拿到的根会一直查一棵已经失效的树。
     */
    fun uiWaitFor(selectorJson: String?, timeoutMs: Long): String {
        val sel = UiSelector.parse(selectorJson)
        if (!sel.hasCondition) return jsonError("缺少选择条件")
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs.coerceIn(0L, 30_000L)
        while (true) {
            val root = rootInActiveWindow
            if (root != null) {
                val nodes = try {
                    UiTreeQuery.find(root, sel, 1)
                } finally {
                    root.recycle()
                }
                if (nodes.isNotEmpty()) {
                    val json = UiTreeQuery.oneNodeJson(nodes[0])
                    nodes.forEach { runCatching { it.recycle() } }
                    return "{\"ok\":true,\"elapsedMs\":${System.currentTimeMillis() - start}," +
                        "\"node\":$json}"
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return jsonError("等待超时（${timeoutMs}ms）内没有匹配的节点")
            }
            Thread.sleep(200)
        }
    }

    fun uiForeground(): String {
        val root = rootInActiveWindow ?: return jsonError("无法获取前台应用")
        val pkg = root.packageName?.toString().orEmpty()
        root.recycle()
        if (pkg.isEmpty()) return jsonError("无法获取前台应用包名")
        return "{\"ok\":true,\"package\":\"${jsonEsc(pkg)}\"}"
    }
}
