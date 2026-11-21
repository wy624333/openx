package com.wy.openx.container

import android.content.Context
import java.io.File

/**
 * openx 工作区：容器命令与文件工具默认工作的宿主目录。
 *
 * 新建一个绑定根：宿主
 * `filesDir/workspace` 会被 proot 绑定为容器内 `/root/workspace` 并设为默认工作目录，
 * 使容器命令与文件操作落到同一可预期目录。
 */
object Workspace {
    /** 宿主侧工作区根目录（不存在则自动创建）。 */
    fun root(context: Context): File = File(context.filesDir, "workspace").apply { mkdirs() }

    /** 容器内对应的绑定路径。 */
    const val CONTAINER_PATH = "/root/workspace"

    /** 容器内项目目录的父目录（与「新建项目」落盘位置一致）。 */
    const val CONTAINER_PROJECTS_PATH = "$CONTAINER_PATH/projects"

    /** 外部本地目录在容器内的挂载点。 */
    const val CONTAINER_LOCAL_PATH = "/root/local"

    /** 工作流产物子目录名。 */
    const val WORKFLOWS_DIR = "workflows"

    /**
     * 工作流产物放在**当前工作区下的 workflows/ 子目录**，不直接用 `/root/`。
     *
     * 为什么不直接用 `/root/`：那里的文件落在**容器 rootfs overlay** 内（宿主
     * `filesDir/rootfs_<profileId>/root/…`），而「删除当前容器 rootfs,重新安装」会把整个
     * overlay 抹掉；工作区是独立 bind 挂载，跨 rootfs 重装存活，也更好找。
     *
     * 为什么跟项目走：项目工作区的约定就是「读写都发生在该项目目录内」。产物另开一个全局目录
     * 会把项目文档散到项目外，切项目时也容易分不清归属。
     *
     * @param project 选中的项目名；空 → 工作区根。与 [containerExternalPath] 的外部本地
     *   目录不同，外部目录是用户指定的真实路径，产物直接落在其下（见 [workflowsDir]）。
     */
    fun containerWorkflowsPath(project: String?): String =
        if (project.isNullOrBlank()) "$CONTAINER_PATH/$WORKFLOWS_DIR"
        else "$CONTAINER_PROJECTS_PATH/$project/$WORKFLOWS_DIR"

    /** 宿主侧对应目录（不存在则创建，让空目录也能在文件浏览里看到）。 */
    fun workflowsDir(context: Context, project: String?): File =
        File(root(context), containerWorkflowsPath(project).removePrefix("$CONTAINER_PATH/"))
            .apply { mkdirs() }
}
