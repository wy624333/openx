package com.wy.openx.tools

data class StyledChar(
    val char: Char = ' ',
    val fg: Int = -1,
    val bg: Int = -1,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val blink: Boolean = false,
    val reverse: Boolean = false
)

data class BufferLine(val chars: List<StyledChar>)

class TerminalBuffer(
    val cols: Int = 80,
    val rows: Int = 24
) {
    private val _scrollback = mutableListOf<BufferLine>()
    private val _screen = MutableList(rows) { MutableList(cols) { StyledChar() } }
    private var cursorRow = 0
    private var cursorCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var fg: Int = -1
    private var bg: Int = -1
    private var bold = false
    private var italic = false
    private var underline = false
    private var strikethrough = false
    private var blink = false
    private var reverse = false
    private var invisible = false
    private var lineWrap = true

    private val inputBuf = StringBuilder()
    private var state = ParserState.TEXT
    private val csiParams = mutableListOf<Int>()
    private var oscString = StringBuilder()
    // OSC 以 ESC 预终止后，下一个 '\' 属于 ST 终止符，需吞掉而非当文本绘制
    private var expectStBackslash = false

    private data class CursorSnapshot(
        val row: Int, val col: Int, val fg: Int, val bg: Int,
        val bold: Boolean, val italic: Boolean, val underline: Boolean,
        val strikethrough: Boolean, val blink: Boolean, val reverse: Boolean,
        val invisible: Boolean
    )
    private val cursorSnapshots = mutableListOf<CursorSnapshot>()
    private var savedCursor: CursorSnapshot? = null

    val screen: List<BufferLine>
        get() = _screen.map { BufferLine(it.toList()) }

    val scrollback: List<BufferLine>
        get() = _scrollback.toList()

    val cursorPosition: Pair<Int, Int>
        get() = cursorRow to cursorCol

    fun feed(text: String) {
        for (ch in text) {
            feedChar(ch)
        }
    }

    fun feedByte(byte: Int) {
        feedChar(byte.toChar())
    }

    private fun feedChar(ch: Char) {
        when (state) {
            ParserState.TEXT -> handleText(ch)
            ParserState.ESC -> handleEsc(ch)
            ParserState.CSI -> handleCsi(ch)
            ParserState.CSI_PARAM -> handleCsiParam(ch)
            ParserState.OSC -> handleOsc(ch)
        }
    }

    private fun handleText(ch: Char) {
        // 吞掉 ST 终止序列的第二个字符 '\'
        if (expectStBackslash) {
            expectStBackslash = false
            if (ch == '\\') return
        }
        when {
            ch == '\u001B' -> { state = ParserState.ESC }
            ch == '\n' -> newLine()
            ch == '\r' -> cursorCol = 0
            ch == '\t' -> {
                val stop = ((cursorCol / 8) + 1) * 8
                cursorCol = minOf(stop, cols - 1)
            }
            ch == '\b' -> { if (cursorCol > 0) cursorCol-- }
            ch == '\u0007' -> { /* bell - ignore */ }
            ch.code < 0x20 -> { /* other control chars - ignore */ }
            else -> putChar(ch)
        }
    }

    private fun handleEsc(ch: Char) {
        state = ParserState.TEXT
        when (ch) {
            '[' -> {
                state = ParserState.CSI
                csiParams.clear()
                csiParams.add(0)
            }
            ']' -> {
                state = ParserState.OSC
                oscString = StringBuilder()
            }
            '7' -> {
                savedCursor = CursorSnapshot(
                    cursorRow, cursorCol, fg, bg, bold, italic, underline,
                    strikethrough, blink, reverse, invisible
                )
            }
            '8' -> {
                val s = savedCursor
                if (s != null) {
                    cursorRow = s.row.coerceIn(0, rows - 1)
                    cursorCol = s.col.coerceIn(0, cols - 1)
                    fg = s.fg; bg = s.bg; bold = s.bold; italic = s.italic
                    underline = s.underline; strikethrough = s.strikethrough
                    blink = s.blink; reverse = s.reverse; invisible = s.invisible
                }
            }
            'D' -> { // Index (scroll up)
                if (cursorRow == scrollBottom) scrollUp() else cursorRow++
            }
            'M' -> { // Reverse Index (scroll down)
                if (cursorRow == scrollTop) scrollDown() else cursorRow--
            }
            'c' -> reset()
            '=' -> {} // Application Keypad
            '>' -> {} // Normal Keypad
            '(' -> state = ParserState.TEXT // G0 character set - skip
            ')' -> state = ParserState.TEXT // G1 character set - skip
            '#' -> {} // Ignore next char
            '%' -> {} // Ignore next char
        }
    }

    private fun handleCsi(ch: Char) {
        when {
            ch >= '0' && ch <= '9' -> {
                state = ParserState.CSI_PARAM
                csiParams[csiParams.size - 1] = (ch - '0')
            }
            ch == ';' -> {
                state = ParserState.CSI_PARAM
                csiParams.add(0)
            }
            ch == '?' -> {} // DEC private mode marker
            ch == '>' || ch == '\'' || ch == '!' || ch == '"' || ch == '$' || ch == ':' -> {} // vendor-specific
            else -> {
                executeCsi(ch, csiParams)
                state = ParserState.TEXT
            }
        }
    }

    private fun handleCsiParam(ch: Char) {
        when {
            ch >= '0' && ch <= '9' -> {
                val idx = csiParams.size - 1
                csiParams[idx] = csiParams[idx] * 10 + (ch - '0')
            }
            ch == ';' -> csiParams.add(0)
            ch == '?' -> {} // DEC private
            ch == '>' || ch == '\'' || ch == '!' || ch == '"' || ch == '$' || ch == ':' -> {}
            else -> {
                executeCsi(ch, csiParams)
                state = ParserState.TEXT
            }
        }
    }

    private fun handleOsc(ch: Char) {
        when {
            ch == '\u0007' || ch == '\u001B' -> {
                val str = oscString.toString()
                handleOscString(str)
                state = ParserState.TEXT
                if (ch == '\u001B') {
                    // ST sequence: ESC \ —— 标记吞掉下一个 '\' 字符
                    expectStBackslash = true
                }
            }
            ch == '\\' && oscString.isNotEmpty() && oscString.last() == '\u001B' -> {
                // ESC \ terminated (ST)
                val str = oscString.dropLast(1).toString()
                handleOscString(str)
                state = ParserState.TEXT
            }
            else -> oscString.append(ch)
        }
    }

    private fun handleOscString(str: String) {
        when {
            str.startsWith("0;") -> {} // Set icon name + window title
            str.startsWith("1;") -> {} // Set icon name
            str.startsWith("2;") -> {} // Set window title
            str.startsWith("4;") -> {} // Set color palette
            str.startsWith("10;") -> {} // Set foreground
            str.startsWith("11;") -> {} // Set background
        }
    }

    private fun executeCsi(ch: Char, params: List<Int>) {
        val p0 = if (params[0] == 0) 1 else params[0]
        when (ch) {
            '@' -> insertChars(p0)
            'A' -> cursorUp(p0)
            'B' -> cursorDown(p0)
            'C' -> cursorForward(p0)
            'D' -> cursorBack(p0)
            'E' -> cursorNextLine(p0)
            'F' -> cursorPrevLine(p0)
            'G' -> cursorHorizontal(p0)
            'H', 'f' -> cursorPosition(params)
            'J' -> eraseDisplay(params)
            'K' -> eraseLine(params)
            'L' -> insertLines(p0)
            'M' -> deleteLines(p0)
            'P' -> deleteChars(p0)
            'S' -> scrollUp(p0)
            'T' -> scrollDown(p0)
            'X' -> eraseChars(p0)
            'd' -> cursorVertical(p0)
            'e' -> cursorDown(p0)
            'm' -> setGraphics(params)
            'h' -> setMode(params)
            'l' -> resetMode(params)
            'r' -> setScrollRegion(params)
            's' -> saveCursorPos()
            'u' -> restoreCursorPos()
            'c' -> {} // Device Attributes
            'n' -> {} // Device Status Report
            'g' -> {} // Tab clear
            'i' -> {} // Print/Media copy
        }
    }

    private fun cursorUp(n: Int) {
        cursorRow = (cursorRow - n).coerceIn(scrollTop, scrollBottom)
    }

    private fun cursorDown(n: Int) {
        cursorRow = (cursorRow + n).coerceIn(scrollTop, scrollBottom)
    }

    private fun cursorForward(n: Int) {
        cursorCol = minOf(cursorCol + n, cols - 1)
    }

    private fun cursorBack(n: Int) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
    }

    private fun cursorNextLine(n: Int) {
        cursorRow = (cursorRow + n).coerceIn(scrollTop, scrollBottom)
        cursorCol = 0
    }

    private fun cursorPrevLine(n: Int) {
        cursorRow = (cursorRow - n).coerceIn(scrollTop, scrollBottom)
        cursorCol = 0
    }

    private fun cursorHorizontal(n: Int) {
        cursorCol = (n - 1).coerceIn(0, cols - 1)
    }

    private fun cursorPosition(params: List<Int>) {
        val row = if (params.size >= 1) (params[0] - 1).coerceIn(0, rows - 1) else 0
        val col = if (params.size >= 2) (params[1] - 1).coerceIn(0, cols - 1) else 0
        cursorRow = row
        cursorCol = col
    }

    private fun cursorVertical(n: Int) {
        cursorRow = (n - 1).coerceIn(0, rows - 1)
    }

    private fun eraseDisplay(params: List<Int>) {
        val mode = params.getOrElse(0) { 0 }
        when (mode) {
            0 -> {
                // Clear from cursor to end of screen
                clearFrom(cursorRow, cursorCol, rows - 1, cols - 1)
            }
            1 -> {
                // Clear from start to cursor
                clearFrom(0, 0, cursorRow, cursorCol)
            }
            2, 3 -> {
                // Clear entire screen
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        _screen[r][c] = StyledChar()
                    }
                }
                cursorRow = 0; cursorCol = 0
            }
        }
    }

    private fun eraseLine(params: List<Int>) {
        val mode = params.getOrElse(0) { 0 }
        val row = cursorRow
        when (mode) {
            0 -> {
                for (c in cursorCol until cols) _screen[row][c] = StyledChar()
            }
            1 -> {
                for (c in 0..cursorCol) _screen[row][c] = StyledChar()
            }
            2 -> {
                for (c in 0 until cols) _screen[row][c] = StyledChar()
                cursorCol = 0
            }
        }
    }

    private fun insertLines(n: Int) {
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (i in scrollBottom downTo cursorRow + count) {
            _screen[i] = _screen[i - count].toMutableList()
        }
        for (i in cursorRow until cursorRow + count) {
            _screen[i] = MutableList(cols) { StyledChar() }
        }
    }

    private fun deleteLines(n: Int) {
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (i in cursorRow..scrollBottom - count) {
            _screen[i] = _screen[i + count].toMutableList()
        }
        for (i in (scrollBottom - count + 1)..scrollBottom) {
            _screen[i] = MutableList(cols) { StyledChar() }
        }
    }

    private fun deleteChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val row = _screen[cursorRow]
        for (i in cursorCol until cols - count) {
            row[i] = row[i + count]
        }
        for (i in (cols - count) until cols) {
            row[i] = StyledChar()
        }
    }

    private fun insertChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val row = _screen[cursorRow]
        for (i in (cols - count - 1) downTo cursorCol) {
            row[i + count] = row[i]
        }
        for (i in cursorCol until cursorCol + count) {
            row[i] = StyledChar()
        }
    }

    private fun eraseChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        for (i in cursorCol until cursorCol + count) {
            _screen[cursorRow][i] = StyledChar()
        }
    }

    private fun scrollUp(n: Int = 1) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (i in scrollTop..scrollBottom - count) {
            _screen[i] = _screen[i + count].toMutableList()
        }
        for (i in (scrollBottom - count + 1)..scrollBottom) {
            _screen[i] = MutableList(cols) { StyledChar() }
        }
    }

    private fun scrollDown(n: Int = 1) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (i in scrollBottom downTo scrollTop + count) {
            _screen[i] = _screen[i - count].toMutableList()
        }
        for (i in scrollTop until scrollTop + count) {
            _screen[i] = MutableList(cols) { StyledChar() }
        }
    }

    private fun putChar(ch: Char) {
        if (cursorRow >= rows) {
            _scrollback.add(BufferLine(_screen[0].toList()))
            for (r in 0 until rows - 1) {
                _screen[r] = _screen[r + 1].toMutableList()
            }
            _screen[rows - 1] = MutableList(cols) { StyledChar() }
            cursorRow = rows - 1
        }
        val actualFg = if (reverse) { if (bg != -1) bg else 15 } else fg
        val actualBg = if (reverse) { if (fg != -1) fg else 0 } else bg
        _screen[cursorRow][cursorCol] = StyledChar(
            char = ch,
            fg = if (invisible) bg else actualFg,
            bg = actualBg,
            bold = bold,
            italic = italic,
            underline = underline,
            strikethrough = strikethrough,
            blink = blink,
            reverse = false
        )
        cursorCol++
        if (cursorCol >= cols) {
            if (lineWrap) {
                cursorCol = 0
                cursorRow++
                if (cursorRow > scrollBottom) {
                    scrollUp(1)
                    cursorRow = scrollBottom
                }
            } else {
                cursorCol = cols - 1
            }
        }
    }

    private fun newLine() {
        cursorCol = 0
        cursorRow++
        if (cursorRow > scrollBottom) {
            scrollUp(1)
            cursorRow = scrollBottom
        }
    }

    private fun setGraphics(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> {
                    fg = -1; bg = -1; bold = false; italic = false
                    underline = false; strikethrough = false; blink = false
                    reverse = false; invisible = false
                }
                p == 1 -> bold = true
                p == 2 -> bold = false // dim
                p == 3 -> italic = true
                p == 4 -> underline = true
                p == 5 || p == 6 -> blink = true
                p == 7 -> reverse = true
                p == 8 -> invisible = true
                p == 9 -> strikethrough = true
                p == 22 -> bold = false
                p == 23 -> italic = false
                p == 24 -> underline = false
                p == 25 || p == 26 -> blink = false
                p == 27 -> reverse = false
                p == 28 -> invisible = false
                p == 29 -> strikethrough = false
                p in 30..37 -> fg = p - 30
                p == 38 -> {
                    // Extended foreground color
                    val (newFg, consumed) = parseExtendedColor(params, i + 1)
                    if (newFg != null) fg = newFg
                    i += consumed
                }
                p == 39 -> fg = -1
                p in 40..47 -> bg = p - 40
                p == 48 -> {
                    val (newBg, consumed) = parseExtendedColor(params, i + 1)
                    if (newBg != null) bg = newBg
                    i += consumed
                }
                p == 49 -> bg = -1
                p in 90..97 -> fg = p - 90 + 8
                p in 100..107 -> bg = p - 100 + 8
            }
            i++
        }
    }

    private fun parseExtendedColor(params: List<Int>, start: Int): Pair<Int?, Int> {
        if (start >= params.size) return null to 0
        return when (params[start]) {
            5 -> {
                // 256 color
                if (start + 1 < params.size) params[start + 1] to 2
                else null to 1
            }
            2 -> {
                // True color
                if (start + 3 < params.size) {
                    val r = params[start + 1].coerceIn(0, 255)
                    val g = params[start + 2].coerceIn(0, 255)
                    val b = params[start + 3].coerceIn(0, 255)
                    val color = (r shl 16) or (g shl 8) or b
                    // Encode as negative to distinguish from palette indices
                    (-color - 1) to 4
                } else null to 1
            }
            else -> null to 0
        }
    }

    private fun clearFrom(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        for (r in startRow..endRow) {
            val cStart = if (r == startRow) startCol else 0
            val cEnd = if (r == endRow) endCol else cols - 1
            for (c in cStart..cEnd) {
                _screen[r][c] = StyledChar()
            }
        }
    }

    private fun setScrollRegion(params: List<Int>) {
        val top = (params.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
        val bottom = (params.getOrElse(1) { rows } - 1).coerceIn(0, rows - 1)
        scrollTop = minOf(top, bottom)
        scrollBottom = maxOf(top, bottom)
        cursorRow = 0
        cursorCol = 0
    }

    private fun setMode(params: List<Int>) {
        for (p in params) {
            when (p) {
                4 -> {} // Insert mode
                20 -> lineWrap = true // Automatic newline
            }
        }
    }

    private fun resetMode(params: List<Int>) {
        for (p in params) {
            when (p) {
                4 -> {} // Replace mode
                20 -> lineWrap = false
            }
        }
    }

    private fun saveCursorPos() {
        cursorSnapshots.add(CursorSnapshot(
            cursorRow, cursorCol, fg, bg, bold, italic, underline,
            strikethrough, blink, reverse, invisible
        ))
    }

    private fun restoreCursorPos() {
        val s = cursorSnapshots.removeLastOrNull() ?: return
        cursorRow = s.row.coerceIn(0, rows - 1)
        cursorCol = s.col.coerceIn(0, cols - 1)
        fg = s.fg; bg = s.bg; bold = s.bold; italic = s.italic
        underline = s.underline; strikethrough = s.strikethrough
        blink = s.blink; reverse = s.reverse; invisible = s.invisible
    }

    private fun reset() {
        for (r in 0 until rows) {
            _screen[r] = MutableList(cols) { StyledChar() }
        }
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        savedCursor = null
        fg = -1; bg = -1; bold = false; italic = false
        underline = false; strikethrough = false; blink = false
        reverse = false; invisible = false; lineWrap = true
        inputBuf.clear()
        state = ParserState.TEXT
        csiParams.clear()
    }

    fun clearHistory() {
        _scrollback.clear()
    }

    private enum class ParserState {
        TEXT, ESC, CSI, CSI_PARAM, OSC
    }

    companion object {
        private val colorMap = mapOf(
            0 to 0x000000, 1 to 0xAA0000, 2 to 0x00AA00, 3 to 0xAA5500,
            4 to 0x0000AA, 5 to 0xAA00AA, 6 to 0x00AAAA, 7 to 0xAAAAAA,
            8 to 0x555555, 9 to 0xFF5555, 10 to 0x55FF55, 11 to 0xFFFF55,
            12 to 0x5555FF, 13 to 0xFF55FF, 14 to 0x55FFFF, 15 to 0xFFFFFF
        )

        fun resolveColor(index: Int): Int {
            if (index < 0) return 0xFFFFFF // default fg
            if (index in 0..15) return colorMap[index] ?: 0xFFFFFF
            if (index in 16..231) {
                val i = index - 16
                val r = (i / 36) * 40 + 55
                val g = ((i % 36) / 6) * 40 + 55
                val b = (i % 6) * 40 + 55
                return (r shl 16) or (g shl 8) or b
            }
            if (index in 232..255) {
                val v = (index - 232) * 10 + 8
                return (v shl 16) or (v shl 8) or v
            }
            // True color encoded as negative
            return -(index + 1) and 0xFFFFFF
        }

        val defaultBg = 0xFF1E1E1E.toInt()
        val defaultFg = 0xFFD4D4D4.toInt()
    }
}
