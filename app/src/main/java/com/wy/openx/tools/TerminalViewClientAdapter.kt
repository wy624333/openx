package com.wy.openx.tools

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

class TerminalViewClientAdapter(
    private val engine: TerminalEngine
) : TerminalViewClient {

    var terminalView: TerminalView? = null
    private var textSize = 28

    override fun onScale(scale: Float): Float {
        textSize = (textSize * scale).roundToInt().coerceIn(8, 36)
        terminalView?.setTextSize(textSize)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val tv = terminalView ?: return
        tv.requestFocus()
        val imm = tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = engine.readControlKey()
    override fun readAltKey(): Boolean = engine.readAltKey()
    override fun readShiftKey(): Boolean = engine.readShiftKey()
    override fun readFnKey(): Boolean = engine.readFnKey()
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}
