package com.wy.openx.data.util

import java.io.File

/**
 * 工具输出落盘目录的路径约定。
 *
 * 位置：`filesDir/workspace/.openx/offloads/<会话id>/<工具名>-<hash>.txt`
 *
 * 为什么落在 workspace 里面（而不是新建一个顶层目录）：
 * openx 的 `Workspace.root()` 就是 `filesDir/workspace`，它被 proot 绑定为容器内
 * `/root/workspace` 且是容器命令的默认工作目录。terminal 平时跑在容器里，
 * 落盘放在 workspace 外的话，模型拿到提示后用 terminal/cat 读不回来。
 * `.openx` 这个保留子目录名 app 里已经用过（`/root/workspace/.openx/uploads/`）。
 *
 * 落在应用私有目录还有个好处：不需要任何存储权限，且 `ToolPaths` 已放行 `filesDir`，
 * `readFile` 用绝对路径直接能读。
 */
object ToolOffloadPaths {

    /** 容器内工作区路径（与 [com.wy.openx.container.Workspace.CONTAINER_PATH] 一致）。 */
    const val CONTAINER_WORKSPACE = "/root/workspace"

    /** 注意开头的斜杠：拼在 "workspace" 之后，少了它会变成 workspace.openx（无斜杠的错误目录）。 */
    private const val OFFLOAD_SUFFIX = "/.openx/offloads"

    /** 宿主侧落盘根目录：`<filesDir>/workspace/.openx/offloads`。 */
    fun root(filesDir: File): File = File(filesDir, "workspace$OFFLOAD_SUFFIX")

    /** 某次会话的落盘目录。 */
    fun conversationDir(filesDir: File, conversationId: Long): File =
        File(root(filesDir), conversationId.toString())

    /**
     * 宿主绝对路径 → 容器内路径。落盘目录在 workspace 下，故可直接换算。
     * 不在 workspace 下时原样返回（避免拼出错误路径误导模型）。
     */
    fun containerPathOf(hostPath: String): String {
        // 宿主绝对路径形如 <filesDir>/workspace/.openx/offloads/<id>/<file>，
        // 容器内 workspace 固定挂在 /root/workspace，故按最后一个 "/workspace/" 切分换算。
        val marker = "/workspace/"
        val at = hostPath.lastIndexOf(marker)
        return if (at >= 0) CONTAINER_WORKSPACE + hostPath.substring(at + marker.length - 1)
        else hostPath
    }

    /** 工具名 → 安全的文件名词干（去掉路径分隔符与其它会惹事的字符）。 */
    fun safeToolName(toolName: String): String {
        val cleaned = toolName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return cleaned.take(40).ifBlank { "tool" }
    }
}
