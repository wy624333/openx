package com.termux.terminal;

import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/**
 * {@link SessionBackend} 的本地实现：经 {@link JNI} 用 fork 出带 pty 的子进程（上游 Termux 机制）。
 *
 * <p>本地 PRoot 容器走这条路径——交互式 shell 需要真 pty（readline、ANSI、全屏程序）。
 * 持有 pty master fd 与 pid：流是 fd 的包装，resize 调 JNI 设置窗口大小，
 * 结束时先 SIGKILL 再关 fd。
 */
final class SubprocessBackend implements SessionBackend {

    private final int mPtyFd;
    private final int mPid;
    private final FileDescriptor mWrappedFd;
    private final InputStream mInputStream;
    private final OutputStream mOutputStream;

    /** close() 会被 finishIfRunning 与 cleanupResources 各调一次，重复 JNI.close 会误关别的 fd。 */
    private volatile boolean mClosed;

    SubprocessBackend(String shellPath, String cwd, String[] args, String[] env,
                      int rows, int columns, int cellWidthPixels, int cellHeightPixels,
                      TerminalSessionClient client) {
        int[] processId = new int[1];
        mPtyFd = JNI.createSubprocess(shellPath, cwd, args, env, processId,
                rows, columns, cellWidthPixels, cellHeightPixels);
        mPid = processId[0];
        mWrappedFd = wrapFileDescriptor(mPtyFd, client);
        mInputStream = new FileInputStream(mWrappedFd);
        mOutputStream = new FileOutputStream(mWrappedFd);
    }

    int getPid() {
        return mPid;
    }

    @Override
    public InputStream getInputStream() {
        return mInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOutputStream;
    }

    @Override
    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        JNI.setPtyWindowSize(mPtyFd, rows, columns, cellWidthPixels, cellHeightPixels);
    }

    @Override
    public int waitForExit() {
        return JNI.waitFor(mPid);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
        }
        if (mPid > 0) {
            try {
                Os.kill(mPid, OsConstants.SIGKILL);
            } catch (Exception ignored) {
            }
        }
        JNI.close(mPtyFd);
    }

    /** 把一个裸 fd 包成 {@link FileDescriptor}，好让标准流类能用它。 */
    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // 桌面 JVM 上字段名不同
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, "TerminalSession",
                    "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }
}
