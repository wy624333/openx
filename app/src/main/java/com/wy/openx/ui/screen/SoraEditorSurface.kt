package com.wy.openx.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import com.wy.openx.tools.TextMateSetup
import kotlinx.coroutines.delay

/**
 * Sora 代码编辑器句柄：供上层（撤销/重做/全选/复制按钮）驱动编辑器实例。
 * 只读态与编辑态共用同一个实例，切换时保留文本、光标与滚动位置。
 */
class SoraEditorHandle {
    internal var editor: CodeEditor? = null

    /** setText 回写期间抑制 onTextChange，避免把「程序性回写」当成用户编辑。 */
    internal var suppressChange = false

    /**
     * 待落定的目标行（1-based，0 = 无请求）。
     *
     * 软换行布局是**异步**算的：布局就绪前 `ensurePositionVisible` 只能按
     * 「逻辑行号 × 行高」估算，遇到大量软换行的文件会严重落偏——实测一个 9327 行、
     * 平均每逻辑行约占 2.4 倍行高的文件，跳 1755 行会落在 728 行附近，**而且不报错**，
     * 调用方完全看不出失败。
     * 所以 [gotoLine] 先把目标行记在这里，按 [LOCATE_RETRY_DELAYS_MS] 补位重定位，
     * 布局算完（Sora 派发「布局空闲」事件）时再补一次，直到落定或用户自己动了光标。
     */
    internal var pendingLocateLine = 0

    /**
     * 最近一次已知与编辑器内容一致（同一实例）的文本。
     *
     * Compose 每次重组都会跑 `AndroidView.update`；用实例身份对比可以区分
     * 「用户刚输入的文本回流」与「外部真的换了内容」，避免每按一个键就整篇比较/回写。
     */
    internal var syncedText: String? = null

    fun undo() {
        val ed = editor ?: return
        if (ed.canUndo()) ed.undo()
    }

    fun redo() {
        val ed = editor ?: return
        if (ed.canRedo()) ed.redo()
    }

    fun selectAll() {
        editor?.selectAll()
    }

    /**
     * 把光标移到指定行（1-based）并滚动到可见。
     *
     * 用于「聊天里点 `文件:行号` 直接跳到该行」。Sora 没有 `setSelectionCompat`，
     * 定位走 `cursor.set(line, 0)` + `ensurePositionVisible`。
     *
     * @param line 1-based 行号；`<= 0` 时不动（调用方多半是"无行号"或过期链接）。
     */
    fun gotoLine(line: Int) {
        if (line <= 0) return
        // 内容可能还没下发（行数暂时对不上），别在这里提前放弃：只记目标行，落点全部交给补位。
        pendingLocateLine = line
        scheduleLocate(line)
    }

    /** 编辑器实例就绪（[SoraEditorSurface] 的 factory 里 setText 之后）时调用：把挂起的定位补上。 */
    internal fun onEditorReady() {
        val line = pendingLocateLine
        if (line > 0) scheduleLocate(line)
    }

    /** 按当前 [pendingLocateLine] 定位一次；行数还没对上就先不动，等下一次补位。 */
    internal fun applyLocate() {
        val ed = editor ?: return
        val line = pendingLocateLine
        if (line <= 0 || line > ed.lineCount) return
        ed.cursor.set(line - 1, 0)
        ed.ensurePositionVisible(line - 1, 0)
    }

    /** 立即定位一次，并按 [LOCATE_RETRY_DELAYS_MS] 补位；过 [LOCATE_FIX_WINDOW_MS] 放弃。 */
    private fun scheduleLocate(line: Int) {
        val ed = editor ?: return
        applyLocate()
        LOCATE_RETRY_DELAYS_MS.forEach { delay ->
            ed.postDelayed({ if (pendingLocateLine == line) applyLocate() }, delay)
        }
        ed.postDelayed({ if (pendingLocateLine == line) pendingLocateLine = 0 }, LOCATE_FIX_WINDOW_MS)
    }

    /**
     * 用户自己动了光标（点击 / 输入 / 拖选择手柄）：放弃补位，别再把他拽回目标行。
     *
     * 补位落点固定是目标行第 1 列，所以只有「行号相同且在 1 列」才认成是补位自己刚落的点。
     */
    internal fun onUserSelection(line: Int, column: Int) {
        if (pendingLocateLine > 0 && (line != pendingLocateLine || column != 1)) pendingLocateLine = 0
    }

    fun copyAll() {
        val ed = editor ?: return
        val cm = ed.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("code", ed.text.toString()))
    }

    fun canUndo(): Boolean = editor?.canUndo() == true

    fun canRedo(): Boolean = editor?.canRedo() == true

    fun commitText(s: String) {
        val ed = editor ?: return
        val cursor = ed.cursor
        if (cursor.isSelected) {
            ed.text.delete(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn)
        }
        val leftLine = cursor.leftLine
        val leftColumn = cursor.leftColumn
        ed.text.insert(leftLine, leftColumn, s)
        cursor.set(leftLine, leftColumn + s.length)
    }

    fun moveCursorLeft() {
        val ed = editor ?: return
        val cursor = ed.cursor
        if (cursor.isSelected) {
            cursor.set(cursor.leftLine, cursor.leftColumn)
            return
        }
        if (cursor.leftColumn > 0) {
            cursor.set(cursor.leftLine, cursor.leftColumn - 1)
        } else if (cursor.leftLine > 0) {
            val prevLine = cursor.leftLine - 1
            cursor.set(prevLine, ed.text.getColumnCount(prevLine))
        }
    }

    fun moveCursorRight() {
        val ed = editor ?: return
        val cursor = ed.cursor
        if (cursor.isSelected) {
            cursor.set(cursor.rightLine, cursor.rightColumn)
            return
        }
        val lineCount = ed.text.lineCount
        val colCount = ed.text.getColumnCount(cursor.leftLine)
        if (cursor.leftColumn < colCount) {
            cursor.set(cursor.leftLine, cursor.leftColumn + 1)
        } else if (cursor.leftLine < lineCount - 1) {
            cursor.set(cursor.leftLine + 1, 0)
        }
    }
}

/**
 * Sora Editor 封装层（Compose 互操作）。
 *
 * 为什么不用 Compose 原生文本组件：
 *  - `SelectionContainer` 放在 `LazyColumn` 里做跨行拖选，会因列表回收而崩溃；
 *  - `OutlinedTextField` 没有可靠的跨行选择手柄，横向滚动也不好控。
 * Sora 是原生 View，自带选择手柄 + 双向滚动 + 行号 + 原生撤销栈，
 * 通过 AndroidView 嵌入后可与现有 Compose 布局混排。
 *
 * @param text      当前文本。仅当与编辑器现有内容不一致时才回写，
 *                  避免重组打断正在进行的输入与选区。
 * @param editable  true 可编辑；false 只读（仍可长按跨行选择复制）。
 * @param wordWrap  自动换行。
 * @param fontSizeSp 正文字号（sp）。用 sp 而非 px，字号不随屏幕密度缩放。
 * @param showWrapArrow  绘制软换行处的箭头标记。
 * @param showWhitespace 绘制行首/行内/行尾空白与空行占位符。
 * @param onTextChange   文本变化回调（用于脏标记与保存）。
 * @param onCursorChange 光标/选区变化：1-based 的 (行, 列) 与选中字符数。
 * @param handle    对外句柄。
 */
@Composable
fun SoraEditorSurface(
    text: String,
    editable: Boolean,
    wordWrap: Boolean,
    fontSizeSp: Int,
    showWrapArrow: Boolean,
    showWhitespace: Boolean,
    filePath: String? = null,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit = {},
    onCursorChange: (line: Int, column: Int, selected: Int) -> Unit = { _, _, _ -> },
    handle: SoraEditorHandle = remember { SoraEditorHandle() }
) {
    val density = LocalContext.current.resources.displayMetrics.density

    // 已经下发到原生编辑器的参数（每次 factory 重建时重置）。
    //
    // 为什么必须记住：`AndroidView.update` 在**每次重组**时都会跑，而输入时每敲一个字上层都会
    // 更新 `content` 状态 → 本函数重组 → update 再跑一遍。Sora 的几个 setter 都不是幂等的
    // 廉价操作，其中两个还会直接造成视觉闪烁：
    //  - `setTextSizePx` 无条件 `createLayout()`：重建全部行的布局，重绘前会空一帧；
    //  - `setColorScheme` 先 detach 再 attach，触发 `onColorFullUpdate()`：重建画笔、全量重绘；
    //  - `setCursorBlinkPeriod` 会重置闪烁相位。
    // 每按一个键都下发一遍，画面就是「输入一个字闪一下」。所以这里只在值真的变化时才调。
    val applied = remember { AppliedEditorStyle() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val ed = CodeEditor(context)
            handle.editor = ed

            ed.setTextSize(fontSizeSp.toFloat())
            ed.setTypefaceText(Typeface.MONOSPACE)
            ed.setTypefaceLineNumber(Typeface.MONOSPACE)
            ed.setLineNumberEnabled(true)
            ed.setPinLineNumber(true)
            val ctx = context
            runCatching {
                TextMateSetup.ensureInitialized(ctx.applicationContext)
                val dark = (BgPrimary.toArgb() ushr 24 and 0xFF).let { ColorUtils.calculateLuminance(BgPrimary.toArgb()) } < 0.5
                TextMateSetup.applyTheme(dark)
            }
            val scope = filePath?.let { TextMateSetup.scopeNameFor(it) }
            runCatching {
                ed.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                if (scope != null) {
                    val lang = TextMateLanguage.create(scope, true)
                    ed.setEditorLanguage(lang)
                }
            }
            ed.setDividerMargin(0f)
            ed.setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            // 必须可获取焦点，否则 onCreateInputConnection 不会被调用（键盘永远不出现）。
            // Sora 构造时未置 focusableInTouchMode，这里显式打开（触控模式下点击才能拿到焦点）。
            ed.isFocusable = true
            ed.isFocusableInTouchMode = true
            ed.isEditable = editable
            // 选择手柄是跨行复制的前提，任何模式都不关闭。
            ed.isStickyTextSelection = true

            ed.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                if (handle.suppressChange) return@subscribeEvent
                val newText = event.editor.text.toString()
                // 记下「这次文本来自编辑器自己」：update 里靠实例身份即可跳过回写，
                // 不必每按一个键都把整篇内容再 toString/比较一遍（大文件上是明显的卡顿源）。
                handle.syncedText = newText
                onTextChange(newText)
            }
            ed.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                val right = event.right ?: return@subscribeEvent
                val range = event.editor.cursorRange
                val selected = (range.endIndex - range.startIndex).coerceAtLeast(0)
                handle.onUserSelection(right.line + 1, right.column + 1)
                onCursorChange(right.line + 1, right.column + 1, selected)
            }
            // 「布局空闲」时补一次定位：软换行布局是异步算的，[SoraEditorHandle.gotoLine]
            // 第一次调用很可能早于布局就绪，落点按估算行高算出来会偏得离谱。这里用真实行高重算。
            ed.subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
                if (event.isLayoutBusy) return@subscribeEvent
                handle.applyLocate()
            }

            ed.setText(text)
            handle.syncedText = text
            // 挂起的定位要等实例建好、内容下发后才能落：早于这里的调用只能先把行号记下。
            handle.onEditorReady()
            ed
        },
        update = { ed ->
            handle.editor = ed
            // 只有外部内容真正变化（打开新文件/保存后重置）才回写。
            val synced = handle.syncedText
            if (text !== synced && text != synced) {
                handle.suppressChange = true
                ed.setText(text)
                handle.suppressChange = false
                handle.syncedText = text
            }

            // 主题要排在 editable 之前判断：配色与光标闪烁都由 (颜色, 可编辑) 共同决定。
            val colors = SoraColors.current()
            if (applied.colors != colors || applied.editable != editable) {
                applySoraTheme(ed, editable, colors)
                applied.colors = colors
            }
            if (applied.editable != editable) {
                ed.isEditable = editable
                applied.editable = editable
            }
            if (applied.wordWrap != wordWrap) {
                ed.setWordwrap(wordWrap)
                applied.wordWrap = wordWrap
            }
            if (applied.fontSizeSp != fontSizeSp) {
                ed.setTextSize(fontSizeSp.toFloat())
                applied.fontSizeSp = fontSizeSp
            }
            val flags = nonPrintableFlags(showWrapArrow, showWhitespace)
            if (applied.nonPrintable != flags) {
                ed.setNonPrintablePaintingFlags(flags)
                applied.nonPrintable = flags
            }
        },
        onRelease = { handle.editor = null }
    )

    // 进入编辑态时主动把焦点和输入法抢回来。
    //
    // 根因：Sora 的 `setEditable(true)` **只写一个 boolean 字段**，既不 requestFocus 也不
    // 重建输入连接；而 `setEditable(false)` 会调 `hideSoftInput()`。从只读切到编辑后，
    // `onCreateInputConnection` 虽然不再返回 null，但视图没有焦点 → 系统不建立
    // InputConnection → 键盘不出现、按键进不来，表现就是「点了编辑按钮没反应」，
    // 必须点别的控件触发一次重组才恢复。
    LaunchedEffect(editable) {
        if (!editable) return@LaunchedEffect
        val ed = handle.editor ?: return@LaunchedEffect
        if (!ed.isEditable) ed.isEditable = true
        // 文本尚未真正布局完成时立即 showSoftInput 会被系统忽略，稍作延迟更稳。
        ed.requestFocus()
        if (!ed.isFocused) ed.post { ed.requestFocus() }
        delay(80)
        ed.showSoftInput()
    }
}

/**
 * 已下发的样式参数快照，用来把 `update` 里的调用变成「仅变化时执行」。
 *
 * 初值全为 null = 「一项都没下发」，因此新编辑器实例的首次 update 会把所有项应用一遍；
 * 之后只有参数真的变化才会再次下发（避免每按键一次就重建行布局/全量重绘 → 闪屏）。
 */
private class AppliedEditorStyle {
    var editable: Boolean? = null
    var wordWrap: Boolean? = null
    var fontSizeSp: Int? = null
    var nonPrintable: Int? = null
    var colors: SoraColors? = null
}

/** 编辑器用到的主题色快照；相等即代表无需重新下发配色。 */
private data class SoraColors(
    val bg: Int,
    val fg: Int,
    val gutter: Int,
    val lineNo: Int,
    val accent: Int
) {
    companion object {
        fun current() = SoraColors(
            bg = BgPrimary.toArgb(),
            fg = TextPrimary.toArgb(),
            gutter = BgSecondary.toArgb(),
            lineNo = TextSecondary.toArgb(),
            accent = Accent.toArgb()
        )
    }
}

/**
 * 把两个开关合成 sora 的非打印字符绘制标志。
 *
 * 两者互不相关，需按位或合并；换行箭头只在开启换行时才可见。
 */
private fun nonPrintableFlags(showWrapArrow: Boolean, showWhitespace: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}

/** 编辑器配色跟随 App 主题，避免与整体 UI 割裂。 */
private fun applySoraTheme(editor: CodeEditor, editable: Boolean, colors: SoraColors) {
    val bg = colors.bg
    val fg = colors.fg
    val gutter = colors.gutter
    val lineNo = colors.lineNo
    val accent = colors.accent

    val scheme = editor.colorScheme
    if (scheme is TextMateColorScheme) {
        editor.setBackgroundColor(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
        editor.setCursorBlinkPeriod(if (editable) 500 else 0)
        return
    }
    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, gutter)
    scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, gutter)
    scheme.setColor(EditorColorScheme.LINE_NUMBER, lineNo)
    scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, accent)
    scheme.setColor(
        EditorColorScheme.SELECTED_TEXT_BACKGROUND,
        ColorUtils.blendARGB(bg, accent, 0.30f)
    )
    val luminance = ColorUtils.calculateLuminance(fg)
    scheme.setColor(
        EditorColorScheme.TEXT_SELECTED,
        if (luminance > 0.5) ColorUtils.blendARGB(0xFF000000.toInt(), fg, 0.25f)
        else ColorUtils.blendARGB(0xFFFFFFFF.toInt(), fg, 0.25f)
    )
    editor.colorScheme = scheme
    editor.setBackgroundColor(bg)
    editor.setCursorBlinkPeriod(if (editable) 500 else 0)
}

/**
 * [SoraEditorHandle.gotoLine] 的补位节奏（毫秒，相对定位请求时刻）。
 *
 * 软换行的行高由后台任务算（Sora 的 WordwrapAnalyzeTask），算完前 `ensurePositionVisible`
 * 只有「行号 × 估算行高」可用——实测 3000 行的文件会落到 1000 行出头。Sora 只在布局
 * 「由忙转闲」那一刻发一次事件，可能早于本次请求，故按时间表补几次。
 */
private val LOCATE_RETRY_DELAYS_MS = longArrayOf(120L, 320L, 700L, 1_400L, 2_500L, 4_000L)

/**
 * 上述补位的总窗口（毫秒）。
 *
 * 到点放弃：万一布局长时间没算完，过晚的修正反而会在用户已经开始滚动/编辑之后
 * 把光标拽回目标行，比不修正更烦人。
 */
private const val LOCATE_FIX_WINDOW_MS = 4_500L
