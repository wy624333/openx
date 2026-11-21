package com.wy.openx.remote

/**
 * 「服务器为准」工作区的路由表：把容器路径翻译成远端路径。
 *
 * ## 为什么需要它
 * AI 的工具、文件树、编辑器用的都是**容器口径**的路径（`/root/workspace/projects/xingde/...`），
 * 它们不该关心文件实际在哪。这一层负责回答「这个路径属于哪个远程工作区、对应远端哪个文件」，
 * 答不上来就返回 null，走本地——这样本地与远程可以并存，不必像「执行模式」那样全局二选一。
 *
 * ## 数据来源
 * 直接读 [RemoteManager] 的挂载表：挂载天然表达了「本地某个工作区 ↔ 远端某个目录」，
 * 再套一层用户开关（[RemoteMount.sourceOnServer]）就够了，不需要第二份映射配置。
 */
object RemoteWorkspaceRegistry {

    /**
     * 处于「服务器为准」模式的挂载 + 它对应的连接。
     *
     * 按挂载路径**从长到短**排序：嵌套挂载时取更具体的那个
     *（如同时挂了 `/root/workspace` 与 `/root/workspace/projects/a`，后者该优先）。
     */
    fun activeSources(): List<RemoteSource> {
        val manager = RemoteEngines.manager
        // 没进过设置页时列表还是空的，先补一次加载；否则路由会全部判成「本地」
        manager.ensureLoaded()
        val connections = manager.connections.value.associateBy { it.id }
        return manager.mounts.value
            .filter { it.sourceOnServer }
            .mapNotNull { mount -> connections[mount.connectionId]?.let { RemoteSource(mount, it) } }
            .sortedByDescending { normalize(it.mount.localMountPath).length }
    }

    /** 按容器路径找远程路由；不在任何远程工作区内 → null（调用方走本地）。 */
    fun routeFor(containerPath: String): RemoteRoute? {
        val path = normalize(containerPath)
        for (source in activeSources()) {
            val root = normalize(source.mount.localMountPath)
            if (path == root) return RemoteRoute(source.connection, source.mount, "")
            // 带 `/` 判断边界：否则 `/a/xingde2` 会误match `/a/xingde`
            if (path.startsWith("$root/")) {
                return RemoteRoute(source.connection, source.mount, path.removePrefix("$root/"))
            }
        }
        return null
    }

    /** 这个容器路径是否落在「服务器为准」的工作区里。 */
    fun isRemote(containerPath: String): Boolean = routeFor(containerPath) != null

    /** 某台服务器当前承载了哪些「服务器为准」的工作区（UI 显示用）。 */
    fun mountNamesOf(connectionId: String): List<String> {
        RemoteEngines.manager.ensureLoaded()
        return RemoteEngines.manager.mounts.value
            .filter { it.connectionId == connectionId && it.sourceOnServer }
            .map { it.localMountPath.substringAfterLast('/') }
    }

    /**
     * 容器路径别名归一化：`~` / `/workspace` / `/.openx` → 真实容器路径。
     *
     * 与 [com.wy.openx.container.LinuxContainerEngine.toHostFileIfContainerPath] 同一套规则，
     * 区别只是这里不落地成宿主 `File`——路由只做字符串比对。
     */
    fun normalize(raw: String): String {
        val expanded = when {
            raw == "~" -> "/root"
            raw.startsWith("~/") -> "/root/" + raw.removePrefix("~/")
            else -> raw
        }
        val aliased = when {
            expanded == "/workspace" || expanded.startsWith("/workspace/") ->
                "/root/workspace" + expanded.removePrefix("/workspace")
            expanded == "/.openx" || expanded.startsWith("/.openx/") ->
                "/root/.openx" + expanded.removePrefix("/.openx")
            else -> expanded
        }
        return aliased.trimEnd('/').ifEmpty { "/" }
    }
}

/** 一个「服务器为准」的工作区：挂载 + 它绑定的连接。 */
data class RemoteSource(val mount: RemoteMount, val connection: RemoteConnection)

/** 一次文件操作该落到哪：哪台服务器、哪个远端路径。 */
data class RemoteRoute(
    val connection: RemoteConnection,
    val mount: RemoteMount,
    /** 相对挂载根的路径（`/` 分隔）；就是挂载根时为空串。 */
    val relative: String,
) {
    val remotePath: String
        get() = if (relative.isEmpty()) mount.remotePath.trimEnd('/')
        else "${mount.remotePath.trimEnd('/')}/$relative"

    val containerPath: String
        get() = if (relative.isEmpty()) mount.localMountPath.trimEnd('/')
        else "${mount.localMountPath.trimEnd('/')}/$relative"

    /** 把远端路径反查回容器路径（远端目录浏览器里要显示容器口径的路径）。 */
    fun containerPathOf(remotePath: String): String {
        val root = mount.remotePath.trimEnd('/')
        val tail = remotePath.removePrefix(root)
        return "${mount.localMountPath.trimEnd('/')}$tail"
    }
}
