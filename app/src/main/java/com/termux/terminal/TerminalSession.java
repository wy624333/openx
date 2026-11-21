package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The actual I/O transport is abstracted behind a {@link SessionBackend}: a local pty subprocess
 * (JNI fork, the PRoot container) or a remote byte stream (SSH shell channel). When the size is made
 * known by a call to {@link #updateSize(int, int, int, int)} terminal emulation begins and threads
 * are spawned to pump bytes between the backend and the emulator.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the backend's output stream.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Optional hook for raw output from process - called before emulator processing. */
    public volatile OnRawOutput onRawOutput;

    public interface OnRawOutput {
        void onBytes(byte[] data, int count);
    }

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started, -1 if finished, -2 when backed by a remote stream. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    /**
     * 字节管道后端。本地是 pty 子进程（{@link SubprocessBackend}），
     * 远程是 SSH shell 通道——仿真器与线程不关心具体是哪一种。
     */
    private final SessionBackend mBackend;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this(transcriptRows, client, new LocalBackendHolder(shellPath, cwd, args, env));
    }

    /**
     * 用调用方提供的 {@link SessionBackend} 构造会话（如 SSH shell 通道）。
     * 后端必须已经打开；仿真在 {@link #updateSize} 被调用时才开始。
     */
    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, SessionBackend backend) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mBackend = backend;
        this.mShellPid = 0;
    }

    /**
     * 把 {@link SubprocessBackend} 的创建推迟到 {@link #initializeEmulator}。
     *
     * 子进程必须在知道尺寸后才能 fork（pty 窗口大小在 createSubprocess 时定下），
     * 而尺寸是 updateSize 才知道的。
     */
    private static final class LocalBackendHolder implements SessionBackend {
        private final String mShellPath;
        private final String mCwd;
        private final String[] mArgs;
        private final String[] mEnv;
        private SubprocessBackend mDelegate;

        LocalBackendHolder(String shellPath, String cwd, String[] args, String[] env) {
            this.mShellPath = shellPath;
            this.mCwd = cwd;
            this.mArgs = args;
            this.mEnv = env;
        }

        @Override
        public InputStream getInputStream() {
            return mDelegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return mDelegate.getOutputStream();
        }

        @Override
        public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
            mDelegate.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }

        @Override
        public int waitForExit() {
            return mDelegate.waitForExit();
        }

        @Override
        public void close() {
            if (mDelegate != null) mDelegate.close();
        }

        SubprocessBackend open(int columns, int rows, int cellWidthPixels, int cellHeightPixels,
                               TerminalSessionClient client) {
            mDelegate = new SubprocessBackend(mShellPath, mCwd, mArgs, mEnv,
                    rows, columns, cellWidthPixels, cellHeightPixels, client);
            return mDelegate;
        }
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached backend of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mBackend.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        if (mBackend instanceof LocalBackendHolder) {
            SubprocessBackend sub = ((LocalBackendHolder) mBackend)
                    .open(columns, rows, cellWidthPixels, cellHeightPixels, mClient);
            mShellPid = sub.getPid();
            mClient.setTerminalShellPid(this, mShellPid);
            startPumpThreads("pid=" + mShellPid);
        } else {
            // 远程流后端：没有 pid。-2 与「未开始(0)/已结束(-1)」区分开，
            // 使 isRunning() 在远端会话期间返回 true，而 getCwd() 因 <1 直接返回 null。
            mShellPid = -2;
            mClient.setTerminalShellPid(this, mShellPid);
            startPumpThreads("remote");
        }

        new Thread("TermSessionWaiter[" + (mShellPid > 0 ? "pid=" + mShellPid : "remote") + "]") {
            @Override
            public void run() {
                int processExitCode = mBackend.waitForExit();
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();
    }

    /**
     * 启动读写两个泵线程。
     *
     * 读线程把后端输出写进 {@link #mProcessToTerminalIOQueue} 并通知主线程，
     * 写线程从 {@link #mTerminalToProcessIOQueue} 取用户输入写给后端。
     * 两者都只依赖后端的流，因此本地 pty 与远端 shell 走完全相同的路径。
     */
    private void startPumpThreads(String label) {
        final InputStream termIn = mBackend.getInputStream();
        final OutputStream termOut = mBackend.getOutputStream();

        new Thread("TermSessionInputReader[" + label + "]") {
            @Override
            public void run() {
                try {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + label + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                        termOut.flush();
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        // 不能用 >0 判活：远端会话没有 pid（-2），那样会把所有用户输入默默丢掉。
        if (mShellPid != -1) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the backend (SIGKILL for a local shell). */
    public void finishIfRunning() {
        if (isRunning()) {
            mBackend.close();
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mBackend.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                OnRawOutput hook = onRawOutput;
                if (hook != null) hook.onBytes(mReceiveBuffer, bytesRead);
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
