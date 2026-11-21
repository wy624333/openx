package com.termux.terminal;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link TerminalSession} 的 I/O 后端抽象：本地 pty 子进程，或远程字节流（SSH shell 通道）。
 *
 * <p>后端自己管传输，对外只暴露：双向字节流、尺寸变更、等待结束、释放资源。
 * {@link TerminalSession} 的读/写/等待三个线程照旧驱动这些流，
 * 所以终端仿真行为与后端无关——本地 pty 与远端 shell 共用同一套渲染。
 *
 * <p>{@link #resize} 带像素尺寸：本地 pty 需要它（决定字体下真实的行列像素），
 * 远端 PTY 只用行列数，忽略像素部分即可。
 */
public interface SessionBackend {

    /** 从后端流向仿真器的字节（子进程 stdout / 远端 shell 输出）。 */
    InputStream getInputStream();

    /** 从仿真器流向后端的字节（用户输入 → 子进程 stdin / 远端 shell 输入）。 */
    OutputStream getOutputStream();

    /** 把终端尺寸变化传给后端（pty 窗口大小 / 远端 PTY 大小）。 */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /**
     * 阻塞直到后端的进程/流结束。
     *
     * @return 退出状态；无有意义退出码时返回 0（远端流关闭即属此类）。
     */
    int waitForExit();

    /** 释放后端资源（关闭 pty fd / 远端 shell 通道）。可重复调用。 */
    void close();
}
