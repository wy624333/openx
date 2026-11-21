package com.wy.openx.container

/**
 * 一个可切换的容器配置：镜像来源 + shell 路径 + 额外 proot 绑定/参数。
 *
 * 内置 [BUILTIN_ALPINE] 描述现有 Alpine rootfs（来自 assets）。
 * 首期仅支持内置 Alpine；自定义镜像（导入 tar.gz）与远程 SSH 在后续子项目中扩展
 * （届时恢复 RootfsSource 的自定义分支与执行模式字段）。
 */
data class ContainerProfile(
    val id: String,
    val name: String,
    val rootfsSource: RootfsSource,
    /** 自定义镜像用的 shell（如 /bin/sh 或 /bin/bash）；未指定时按 provision 状态自动选择。 */
    val shellPath: String?,
    /** 额外 -b 绑定，如 ["/sdcard:/mnt/sdcard"]，逐项作为 `-b <binding>` 拼进 proot argv。 */
    val extraBindings: List<String> = emptyList(),
    /** 额外 proot 参数，原样追加到基础 argv（如 ["-k","..."]）。 */
    val extraArgs: List<String> = emptyList(),
    /** 自定义环境变量，注入容器内进程（覆盖同名默认值）。 */
    val env: Map<String, String> = emptyMap(),
    val isBuiltin: Boolean,
    /** 添加时间（毫秒时间戳），容器列表按此降序排列；内置默认 0 排最后。 */
    val createdAt: Long = 0
) {
    companion object {
        const val BUILTIN_ID = "builtin-alpine"

        /** 内置 Alpine profile：镜像来自 assets，复用现有安装/provision 全流程。 */
        val BUILTIN_ALPINE = ContainerProfile(
            id = BUILTIN_ID,
            name = "内置 Alpine",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            isBuiltin = true
        )
    }
}

/** rootfs 镜像来源。 */
sealed interface RootfsSource {
    /** 内置：assets 里的 rootfs 文件（[ContainerProfile.BUILTIN_ALPINE] 用）。 */
    data class Asset(val path: String) : RootfsSource

    /** 用户导入的 tar.gz/tar.xz，经 content uri 引用（App 私有副本由 rootfs_images/ 保存）。 */
    data class LocalFile(val uri: String) : RootfsSource
}