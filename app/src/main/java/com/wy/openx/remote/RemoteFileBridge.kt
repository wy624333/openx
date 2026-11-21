package com.wy.openx.remote

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 远程侧的文件访问：入口一律是**容器路径**，内部翻译成远端路径并落到 SFTP。
 *
 * ## 为什么以容器路径为入口
 * AI 的工具和 UI 用的都是容器口径（`/root/workspace/projects/xingde/...`），
 * 让它们各自去算「远端对应哪个文件」会把这个换算散到十几处、每处都可能算错。
 * 这里收口：调用方只管传它一直传的那个路径，越界与否由 [RemoteWorkspaceRegistry] 判定。
 *
 * ## 连接从哪来
 * [RemoteEngines.clientFor] 按连接 id 缓存，每条连接一条独立 SSH 通道。
 * 不借 `RemoteManager` 那条——那是用户的浏览会话，会被顶掉。
 */
class RemoteFileBridge {

    private fun route(containerPath: String): RemoteRoute =
        RemoteWorkspaceRegistry.routeFor(containerPath)
            ?: throw IOException("路径不在任何「服务器为准」的工作区内：$containerPath")

    private suspend fun clientOf(route: RemoteRoute): RemoteSshClient {
        val client = RemoteEngines.clientFor(route.connection.id)
        if (!client.isConnected) client.connect(route.connection)
        return client
    }

    suspend fun list(containerPath: String): List<RemoteEntry> {
        val route = route(containerPath)
        return clientOf(route).list(route.connection, route.remotePath)
    }

    suspend fun stat(containerPath: String): RemoteStat? {
        val route = route(containerPath)
        return clientOf(route).stat(route.remotePath)
    }

    suspend fun readText(containerPath: String, limitBytes: Long): String {
        val route = route(containerPath)
        return clientOf(route).readText(route.connection, route.remotePath, limitBytes)
    }

    suspend fun writeText(containerPath: String, content: String, overwrite: Boolean) {
        val route = route(containerPath)
        val client = clientOf(route)
        // 不覆盖时先查存在性：SFTP 写入没有「仅在不存在时创建」的原语，
        // 只能先 stat 再写，中间那点竞态窗口对单用户场景无所谓。
        if (!overwrite && client.exists(route.remotePath)) {
            throw IOException("目标已存在：$containerPath")
        }
        val parent = route.remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) runCatching { client.mkdir(parent) }
        client.writeText(route.remotePath, content)
    }

    suspend fun mkdirs(containerPath: String) {
        val route = route(containerPath)
        clientOf(route).mkdir(route.remotePath)
    }

    /** 删除（文件或整个目录树）。目标是目录时按递归删处理。 */
    suspend fun delete(containerPath: String) {
        val route = route(containerPath)
        val client = clientOf(route)
        val stat = client.stat(route.remotePath) ?: return
        if (stat.isDirectory) client.deleteRecursively(route.remotePath)
        else client.delete(route.remotePath, isDirectory = false)
    }

    suspend fun rename(fromContainerPath: String, toContainerPath: String) {
        val from = route(fromContainerPath)
        val to = route(toContainerPath)
        // 跨服务器搬文件不是 rename 能表达的：远端路径不同机器，得走本地中转。
        // 当前不做，直接拒绝——比静默在错的地方创建文件好。
        if (from.connection.id != to.connection.id) {
            throw IOException("不支持跨服务器重命名")
        }
        clientOf(from).rename(from.remotePath, to.remotePath)
    }

    suspend fun copy(fromContainerPath: String, toContainerPath: String) {
        val from = route(fromContainerPath)
        val to = route(toContainerPath)
        if (from.connection.id != to.connection.id) {
            throw IOException("不支持跨服务器复制")
        }
        clientOf(from).copyOnServer(from.remotePath, to.remotePath, false)
    }

    /**
     * 把远端文件取到本地缓存，返回那个本地文件。
     *
     * 给「必须要有本地 File」的场景用：用外部应用打开、图片解码、压缩包内浏览。
     * 这些能力都建立在 `java.io.File` 上，改造成本远高于拉一份副本。
     *
     * 缓存按「远端路径的 SHA-256 前 8 字节」分目录，保留原文件名——
     * 扩展名对预览器判断类型是必需的，路径本身则用哈希避免层级和非法字符。
     */
    suspend fun materialize(containerPath: String): File {
        val route = route(containerPath)
        val client = clientOf(route)
        val stat = client.stat(route.remotePath) ?: throw IOException("远端文件不存在：$containerPath")
        val target = previewFileOf(containerPath)
        // 把本地副本的 mtime 设成远端 mtime，于是「是否过期」变成一次 mtime 比较，
        // 不用另存一份元数据。
        if (target.isFile && target.length() == stat.size && target.lastModified() == stat.lastModified) {
            return target
        }
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "${target.name}.part")
        client.downloadFile(route.remotePath, staging.absolutePath)
        if (target.exists()) target.delete()
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        target.setLastModified(stat.lastModified)
        return target
    }

    /**
     * 递归列出目录下所有**文件**的容器路径。
     *
     * 只列文件不列目录：调用方（搜索/扫描）关心的是「有哪些文件」，目录由路径前缀体现。
     * 跳过隐藏文件与超过 [maxFileSize] 的文件，与本地搜索工具的口径保持一致。
     */
    suspend fun walkFiles(
        containerPath: String,
        maxDepth: Int = MAX_WALK_DEPTH,
        maxFileSize: Long = MAX_WALK_FILE_SIZE,
    ): List<String> {
        val root = route(containerPath)
        val client = clientOf(root)
        val out = mutableListOf<String>()

        suspend fun walk(path: String, depth: Int) {
            if (depth > maxDepth) return
            val entries = runCatching { client.list(root.connection, path) }.getOrNull() ?: return
            for (entry in entries) {
                if (entry.name.startsWith(".")) continue
                if (entry.isDirectory) walk(entry.path, depth + 1)
                else if (entry.size <= maxFileSize) out += root.containerPathOf(entry.path)
            }
        }

        walk(root.remotePath, 0)
        return out
    }

    private fun previewFileOf(containerPath: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(containerPath.toByteArray())
        val key = digest.take(8).joinToString("") { "%02x".format(it) }
        val name = containerPath.substringAfterLast('/').ifBlank { "file" }
        return File(File(RemoteEngines.cacheDir, "remote-preview/$key"), name)
    }

    private companion object {
        /** 递归扫描的深度上限：防远端有循环软链。 */
        const val MAX_WALK_DEPTH = 24

        /** 扫描时跳过的超大文件（搜索没必要把几百 MB 的日志拉下来）。 */
        const val MAX_WALK_FILE_SIZE = 5L * 1024 * 1024
    }
}
