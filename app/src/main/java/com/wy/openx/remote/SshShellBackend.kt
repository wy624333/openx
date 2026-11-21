package com.wy.openx.remote

import com.termux.terminal.SessionBackend
import com.wy.openx.container.FileLogger
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * [SessionBackend] 的远程实现：包一条 sshj 交互式 shell 通道（[Session.Shell]）。
 *
 * 这样远端 shell 就能像本地 pty 一样驱动 Termux 的终端仿真器：
 * shell 输出 → 仿真器，用户输入 → shell stdin，resize → 远端 PTY 尺寸。
 * 传入的 shell 必须已经分配了 PTY 并已启动（见 [RemoteSshClient.openShell]）。
 *
 * 远端 shell 没有有意义的退出码，[waitForExit] 一律返回 0；
 * 会话结束时由 `TerminalSession` 自己在屏幕上补 "[Process completed]" 提示。
 */
class SshShellBackend(
    private val shell: Session.Shell,
) : SessionBackend {

    /**
     * resize 与 close 都走网络 I/O，而它们会被 UI 主线程直接调用
     *（[com.termux.view.TerminalView] 在布局变化时同步调 resize）。
     * 主线程上做网络 I/O 会直接抛 NetworkOnMainThreadException，故统一丢到单线程执行器。
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SshShellIO") }

    @Volatile
    private var closed = false

    override fun getInputStream(): InputStream = shell.inputStream

    override fun getOutputStream(): OutputStream = shell.outputStream

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // 远端 PTY 只认行列数；像素尺寸是本地渲染用的，远端无从使用，忽略。
        if (closed) return
        runCatching {
            ioExecutor.execute {
                if (closed) return@execute
                runCatching { shell.changeWindowDimensions(columns, rows, cellWidthPixels, cellHeightPixels) }
                    .onFailure { FileLogger.w(TAG, "远端 PTY resize 失败 ${columns}x${rows}", it) }
            }
        }
    }

    override fun waitForExit(): Int = runCatching {
        shell.join()
        0
    }.getOrElse {
        FileLogger.w(TAG, "shell.join 异常", it)
        0
    }

    override fun close() {
        if (closed) return
        closed = true
        ioExecutor.shutdown()
        // 关闭同样走网络，不能在主线程同步做；用独立线程，避免排在已 shutdown 的执行器上
        Thread({
            runCatching { shell.close() }.onFailure { FileLogger.w(TAG, "关闭远端 shell 异常", it) }
        }, "SshShellClose").start()
    }

    private companion object {
        const val TAG = "SshShellBackend"
    }
}
