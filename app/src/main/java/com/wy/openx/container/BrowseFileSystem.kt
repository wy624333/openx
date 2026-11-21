package com.wy.openx.container

import android.content.Context
import com.wy.openx.remote.RemoteEntry
import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

/** 目录条目信息，供侧边栏「文件」Tab 列表展示。 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录可为 0。 */
    val size: Long,
    /** 最后修改时间（epoch 毫秒）。 */
    val lastModified: Long,
    /** 可读/可写/可执行权限位（rwx 字符串，如 "rwx"）。 */
    val permissions: String = "---",
    /** 点击后应进入的目录/文件路径（容器路径，如 `/.openx` 快捷入口对应 `/root/.openx`）。
     *  为 null 时由 UI 按「当前目录 + name」拼接。 */
    val overridePath: String? = null
)

/** 文件浏览排序：目录在前，同类按名称不区分大小写（对齐 RC7 BROWSE_ORDER）。 */
private val FILE_ENTRY_ORDER: Comparator<FileEntry> =
    compareByDescending<FileEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** 文件浏览剪切板：一次只能持有一项，复制或剪切后待粘贴到其它目录。 */
data class BrowseClipboard(
    /** 源条目容器路径（如 `/root/workspace/foo`）。 */
    val sourcePath: String,
    /** 源条目显示名（UI 提示用）。 */
    val sourceName: String,
    /** 是否剪切（粘贴成功后删除源）。 */
    val isCut: Boolean
)

/** 侧边栏「文件」Tab 的加载状态。 */
sealed interface FileBrowseState {
    data object Loading : FileBrowseState
    data class Success(val entries: List<FileEntry>) : FileBrowseState
    data class Error(val detail: String?) : FileBrowseState
}

/**
 * 侧边栏「文件」Tab 的浏览后端：浏览根为「容器根目录」（`/`）。
 *
 * 浏览路径是容器绝对路径（`/`、`/etc`、`/root/workspace/…`、`/root/.openx/…` 等）。PRoot 容器
 * 里的目录是「rootfs + 若干绑定」的并集（proot `-b`），openx 没有 OpenX 的 WorkspacePathMapper，
 * 这里复刻同一「虚拟合并视图」：把 rootfs 与各绑定（`/dev /proc /sys /system`、`/root/workspace`
 * 工作区、`/root/.openx` 配置目录、当前 profile 额外绑定）合并成目录列表——
 * - 绑定目标 == 当前目录：绑定源的子项并入本层（同名覆盖 rootfs，与 proot `-b` 后者优先一致）；
 * - 绑定目标的父 == 当前目录：注入一个合成目录项（如 `/root` 下出现 `workspace/.openx`）。
 *
 * 写操作（新建/重命名/删除）同样解析到正确的宿主目标；固定绑定伪文件系统（`/proc` 等）只读，
 * 写操作直接拒绝。
 *
 * openx 只有本地 PRoot 模式，无 OpenX 的远程 SFTP 分支。
 */
class BrowseFileSystem(
    private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private val remoteBridge by lazy { RemoteFileBridge() }

    private fun isRemote(path: String) = RemoteWorkspaceRegistry.isRemote(path)

    /**
     * 把挂起的 SFTP 调用桥成同步结果。
     *
     * 本类的接口是同步的（调用方在 IO 线程上用它填列表），改全员改成 suspend 会波及文件树、
     * 编辑器、压缩包浏览等多个界面；用 runBlocking 在这里收口，代价是调用方**不得在主线程调**
     *（已有调用点都在 Dispatchers.IO 里）。
     */
    private fun <T> remoteAwait(block: suspend () -> T): T = runBlocking { block() }

    fun listFiles(path: String): List<FileEntry> =
        if (isRemote(path)) remoteAwait { remoteBridge.list(path) }.map { it.toFileEntry() }
        else mergedListing(path)

    /** 容器路径 → 宿主真实文件（压缩/解压/属性等基于宿主 File 的操作使用）。
     *  远程工作区下返回**本地缓存的副本**（可能是刚下载的，调用方不应假设它是最新的）。 */
    fun hostFile(containerPath: String): File =
        if (isRemote(containerPath)) remoteAwait { remoteBridge.materialize(containerPath) }
        else hostFileOf(containerPath)

    fun exists(path: String): Boolean =
        if (isRemote(path)) remoteAwait { remoteBridge.stat(path) != null }
        else hostFileOf(path).exists()

    fun isDirectory(path: String): Boolean =
        if (isRemote(path)) remoteAwait { remoteBridge.stat(path)?.isDirectory == true }
        else hostFileOf(path).isDirectory

    fun writeFile(path: String, content: String, overwrite: Boolean) {
        if (isRemote(path)) return remoteAwait { remoteBridge.writeText(path, content, overwrite) }
        ensureMutable(path)
        val file = hostFileOf(path)
        if (file.exists() && !overwrite) throw IOException("文件已存在: $path")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * 读取文本文件内容（UTF-8），最多读取 [limitBytes] 字节（超出部分截断，不报错）。
     * 二进制（前 [BINARY_SNIFF_BYTES] 字节内含 NUL）抛 [IOException]。
     * 调用方可通过 `hostFile(path).length() > 实际返回字节数` 判断是否被截断。
     */
    fun readFile(path: String, limitBytes: Long = MAX_TEXT_FILE_SIZE_BYTES.toLong()): String {
        // 远程读直接拿回文本；SFTP 侧不做二进制嗅探，读到二进制会返回乱码而非报错
        if (isRemote(path)) return remoteAwait { remoteBridge.readText(path, limitBytes) }
        ensureMutable(path)
        val file = hostFileOf(path)
        if (!file.isFile) throw IOException("文件不存在或不是文件: $path")
        val readLimit = minOf(file.length(), limitBytes)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val bytes = file.inputStream().use { ins ->
            val buf = ByteArray(readLimit)
            var off = 0
            while (off < readLimit) {
                val n = ins.read(buf, off, readLimit - off)
                if (n < 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        }
        val sample = minOf(bytes.size, BINARY_SNIFF_BYTES)
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) throw IOException("二进制文件，无法以文本方式查看")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 文件查看器可打开的体积上限（字节）：超过后只预览前若干字节。 */
    fun maxViewableFileSize(): Long = MAX_VIEW_FILE_SIZE_BYTES

    fun mkdirs(path: String) {
        if (isRemote(path)) return remoteAwait { remoteBridge.mkdirs(path) }
        ensureMutable(path)
        hostFileOf(path).mkdirs()
    }

    fun rename(path: String, newPath: String) {
        if (isRemote(path) || isRemote(newPath)) {
            return remoteAwait { remoteBridge.rename(path, newPath) }
        }
        ensureMutable(path)
        ensureMutable(newPath)
        val source = hostFileOf(path)
        val target = hostFileOf(newPath)
        if (!source.exists()) throw IOException("源不存在: $path")
        if (target.exists()) throw IOException("目标已存在: $newPath")
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) throw IOException("重命名失败: $path -> $newPath")
    }

    /** 复制文件或目录（含子内容）到 [newPath]。目标已存在或源不存在时抛 [IOException]，不覆盖。源可在只读目录（如系统目录），仅目标须可写。 */
    fun copy(path: String, newPath: String) {
        if (isRemote(path) || isRemote(newPath)) {
            return remoteAwait { remoteBridge.copy(path, newPath) }
        }
        val source = hostFileOf(path)
        val target = hostFileOf(newPath)
        if (!source.exists()) throw IOException("源不存在: $path")
        if (target.exists()) throw IOException("目标已存在: $newPath")
        if (!ensureMutableDir(newPath)) throw IOException("目标目录不可写: $newPath")
        target.parentFile?.mkdirs()
        if (source.isDirectory) source.copyRecursively(target, overwrite = false)
        else source.copyTo(target, overwrite = false)
    }

    /** 移动（剪切）[path] 到 [newPath]。优先 renameTo，跨文件系统失败时回退「复制 + 删除源」。 */
    fun move(path: String, newPath: String) {
        // 同一服务器内 move 就是 rename；跨服务器的搬移不支持（bridge 会拒绝）
        if (isRemote(path) || isRemote(newPath)) {
            return remoteAwait { remoteBridge.rename(path, newPath) }
        }
        ensureMutable(path)
        ensureMutable(newPath)
        val source = hostFileOf(path)
        val target = hostFileOf(newPath)
        if (!source.exists()) throw IOException("源不存在: $path")
        if (target.exists()) throw IOException("目标已存在: $newPath")
        target.parentFile?.mkdirs()
        val moved = try {
            source.renameTo(target)
        } catch (e: SecurityException) {
            false
        }
        if (moved) return
        if (source.isDirectory) source.copyRecursively(target, overwrite = false)
        else source.copyTo(target, overwrite = false)
        if (!source.deleteRecursively()) throw IOException("移动失败（删除源不成功）: $path")
    }

    fun deleteRecursively(path: String) {
        if (isRemote(path)) return remoteAwait { remoteBridge.delete(path) }
        ensureMutable(path)
        val file = hostFileOf(path)
        if (!file.exists()) return
        if (!file.deleteRecursively()) throw IOException("删除失败: $path")
    }

    /** 容器路径的父目录；根 `/` 返回 null（不能再上退）。 */
    fun parentPath(path: String): String? {
        val key = normalize(path)
        return if (key == "/") null else parentOf(key)
    }

    /** 本地合并列目录：主源 + 本层绑定合并 + 子树绑定合成入口。 */
    private fun mergedListing(containerPath: String): List<FileEntry> {
        val key = normalize(containerPath)
        val entries = LinkedHashMap<String, FileEntry>()

        // 顶层 `/` 只放「快捷入口」：workspace 与 .openx 以别名目录（/workspace、/.openx）
        // 平铺在根视图，点击进入即真实工作区 / AI 配置目录（经 overridePath 直达，上一级直接回根）。
        if (key == "/") {
            val shortcuts = mutableListOf(
                Triple("workspace", WORKSPACE_ALIAS, Workspace.root(context)),
                Triple(".openx", OPENX_ALIAS, containerInstaller.openxDir)
            )
            // 外部本地目录绑定在 /root/local/<name>：顶层给出「local」快捷入口直达。
            val anyExternalDir = externalWorkspaces().firstOrNull { java.io.File(it.path).isDirectory }
            if (anyExternalDir != null) {
                shortcuts += Triple("local", LOCAL_ROOT, java.io.File(anyExternalDir.path))
            }
            for ((name, alias, host) in shortcuts) {
                if (entries.containsKey(name)) continue
                entries[name] = FileEntry(
                    name = name,
                    isDirectory = !host.isFile,
                    size = if (host.isFile) host.length() else 0,
                    lastModified = host.lastModified(),
                    permissions = permsOf(host),
                    overridePath = alias
                )
            }
        }

        val primary = hostFileOf(key)
        if (primary.isDirectory) {
            primary.listFiles().orEmpty().forEach { child ->
                // 顶层 `/` 不展示 dev/proc/sys/system 这些系统绑定目录（用户用不到）。
                if (key == "/" && child.name in ROOT_SKIP_NAMES) return@forEach
                entries[child.name] = entryOf(child)
            }
        }

        // 绑定目标即当前目录：绑定源的直接子项并入本层（同名覆盖）。
        for (binding in bindings()) {
            if (binding.containerTarget == key && binding.hostSource.isDirectory) {
                binding.hostSource.listFiles().orEmpty().forEach { child ->
                    entries[child.name] = entryOf(child)
                }
            }
        }

        // 绑定目标在当前目录下：注入合成目录项（真实内容在绑定源宿主目录）。
        for (binding in bindings()) {
            val target = binding.containerTarget
            if (target == key) continue
            if (parentOf(target) != key) continue
            // 顶层 `/` 不再注入 dev/proc/sys/system 系统绑定入口。
            if (key == "/" && target in FIXED_BINDS) continue
            val name = target.substringAfterLast('/')
            if (entries.containsKey(name)) continue
            entries[name] = FileEntry(
                name = name,
                isDirectory = true,
                size = 0,
                lastModified = binding.hostSource.lastModified(),
                permissions = permsOf(binding.hostSource)
            )
        }

        return entries.values.sortedWith(FILE_ENTRY_ORDER)
    }

    /**
     * 远端条目 → 文件树条目。
     *
     * 权限取属主那 3 位：远端给的是 `-rw-r--r--` 这种完整串，而 [FileEntry.permissions] 的口径是
     * `rwx`；不换算的話列表会显示十位字符，与本地条目视觉不一致。
     */
    private fun RemoteEntry.toFileEntry() = FileEntry(
        name = name,
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
        permissions = permissions.drop(1).take(3).ifBlank { "---" },
    )

    /**
     * 容器路径 → 宿主真实文件。工作区 / AI 配置目录 / 额外绑定都是独立于 rootfs 的宿主目录
     * （升级重装 rootfs 不丢数据）；固定伪文件系统绑定（/dev /proc /sys /system）直接落到宿主同名目录；
     * 其余路径落到 rootfs 内（容器系统文件）。
     */
    private fun hostFileOf(containerPath: String): File {
        val key = normalize(containerPath)
        for (fixed in FIXED_BINDS) {
            if (key == fixed || key.startsWith("$fixed/")) return File(key)
        }

        val workspaceRoot = Workspace.root(context)
        val wsContainer = Workspace.CONTAINER_PATH // /root/workspace
        when {
            key == wsContainer || key == "$wsContainer/" -> return workspaceRoot
            key.startsWith("$wsContainer/") -> return File(workspaceRoot, key.removePrefix("$wsContainer/"))
            key == OPENX_ROOT || key == "$OPENX_ROOT/" -> return containerInstaller.openxDir
            key.startsWith("$OPENX_ROOT/") ->
                return File(containerInstaller.openxDir, key.removePrefix("$OPENX_ROOT/"))
            // 文件浏览的顶层别名：/.openx 与 /workspace 分别是 AI 配置目录与工作区的快捷入口。
            key == OPENX_ALIAS || key == "$OPENX_ALIAS/" -> return containerInstaller.openxDir
            key.startsWith("$OPENX_ALIAS/") ->
                return File(containerInstaller.openxDir, key.removePrefix("$OPENX_ALIAS/"))
            key == WORKSPACE_ALIAS || key == "$WORKSPACE_ALIAS/" -> return workspaceRoot
            key.startsWith("$WORKSPACE_ALIAS/") -> return File(workspaceRoot, key.removePrefix("$WORKSPACE_ALIAS/"))
        }

        for (ws in externalWorkspaces()) {
            val c = "$LOCAL_ROOT/${ws.name}"
            if (key == c || key == "$c/") return File(ws.path)
            if (key.startsWith("$c/")) return File(ws.path, key.removePrefix("$c/"))
        }

        mountedHostFile(key)?.let { return it }
        return if (key.startsWith("/")) File(rootfsRoot(), key.removePrefix("/")) else File(workspaceRoot, key)
    }

    /** 固定伪文件系统 / 系统只读绑定：写操作一律拒绝。 */
    private fun ensureMutable(containerPath: String) {
        val key = normalize(containerPath)
        if (key == "/") throw IOException("根目录不可修改")
        for (fixed in FIXED_BINDS) {
            if (key == fixed || key.startsWith("$fixed/")) {
                throw IOException("$key 系统目录只读")
            }
        }
    }

    /** 目标路径所在目录是否可写（复制目标不会自动创建不存在的父目录时使用）。 */
    private fun ensureMutableDir(containerPath: String): Boolean {
        val parent = containerPath.substringBeforeLast('/', "/")
        return try {
            ensureMutable(if (parent.isEmpty()) "/" else parent)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** 容器内所有绑定：固定系统绑定 + 工作区 + AI 配置目录 + 外部本地目录 + 当前 profile 额外绑定。 */
    private fun bindings(): List<Bind> {
        val list = mutableListOf<Bind>()
        for (p in FIXED_BINDS) list += Bind(p, File(p))
        list += Bind(Workspace.CONTAINER_PATH, Workspace.root(context))
        list += Bind(OPENX_ROOT, containerInstaller.openxDir)
        for (ws in externalWorkspaces()) {
            val host = java.io.File(ws.path)
            if (host.isDirectory) list += Bind("$LOCAL_ROOT/${ws.name}", host)
        }
        for ((src, dst) in parseExtraBindings(currentProfile)) {
            list += Bind(dst, File(src))
        }
        return list
    }

    /** 注册的外部本地目录（name + 宿主绝对路径）。 */
    private fun externalWorkspaces(): List<com.wy.openx.data.model.ExternalWorkspace> =
        com.wy.openx.tools.AppContextHolder.currentSettings?.externalWorkspaces.orEmpty()

    /** 当前 profile 的额外绑定解析为 (宿主源, 容器目标)，格式与 [ContainerProfile.extraBindings] 一致。 */
    private fun parseExtraBindings(profile: ContainerProfile): List<Pair<String, String>> =
        profile.extraBindings.mapNotNull { binding ->
            val idx = binding.indexOf(':')
            val src = (if (idx >= 0) binding.substring(0, idx) else binding).trim()
            val dstRaw = (if (idx >= 0) binding.substring(idx + 1) else binding).trim()
            val dst = expandHome(dstRaw).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else expandHome(src) to dst
        }

    /** [p]（已规范化的容器路径）落在某额外挂载目标下时，映射到宿主源真实文件；否则 null。 */
    private fun mountedHostFile(p: String): File? {
        for ((src, dst) in parseExtraBindings(currentProfile)) {
            when {
                p == dst || p == "$dst/" -> return File(src)
                p.startsWith("$dst/") -> return File(src, p.removePrefix("$dst/"))
            }
        }
        return null
    }

    /** 展开 `~`、去尾斜杠；空串与 `/` 统一为根 `/`。 */
    private fun normalize(containerPath: String): String {
        return expandHome(containerPath.trim()).removeSuffix("/").takeIf { it.isNotEmpty() } ?: "/"
    }

    /** `~` 展开为容器内 HOME（openx 固定 /root，Alpine 与自定义 rootfs 一致）。 */
    private fun expandHome(p: String): String {
        return if (p == "~") "/root"
        else if (p.startsWith("~/")) "/root/${p.removePrefix("~/")}"
        else p
    }

    /** 容器绝对路径的直接父目录；根及顶层（/dev 等）归一到 `/`。 */
    private fun parentOf(containerPath: String): String {
        val i = containerPath.lastIndexOf('/')
        return if (i <= 0) "/" else containerPath.substring(0, i)
    }

    private fun entryOf(file: File): FileEntry = FileEntry(
        name = file.name,
        isDirectory = file.isDirectory,
        size = file.length(),
        lastModified = file.lastModified(),
        permissions = permsOf(file)
    )

    private fun permsOf(file: File): String {
        val read = if (file.canRead()) "r" else "-"
        val write = if (file.canWrite()) "w" else "-"
        val execute = if (file.canExecute() || file.isDirectory) "x" else "-"
        return read + write + execute
    }

    private fun rootfsRoot(): File = containerInstaller.rootfsDirFor(currentProfile)

    /** 固定绑定（proot `-b` 的宿主来源）；宿主目录以宿主绝对路径直接列取。 */
    private data class Bind(
        val containerTarget: String,
        val hostSource: File
    )

    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    private companion object {
        /** 文本文件查看/编辑的最大字节数（3MB）。 */
        const val MAX_TEXT_FILE_SIZE_BYTES = 3L * 1024 * 1024

        /** 文件查看器可打开的最大体积（30MB）；超出部分截断预览，避免一次性读入导致 OOM。 */
        const val MAX_VIEW_FILE_SIZE_BYTES = 30L * 1024 * 1024

        /** 二进制嗅探采样字节数：只看开头一段（与 git 一致），避免整文件扫描。 */
        const val BINARY_SNIFF_BYTES = 8000

        /** [readBytes] 单次可读入的字节上限（64MB），防大文件整读 OOM。 */
        const val MAX_BINARY_READ_BYTES = 64L * 1024 * 1024

        val FIXED_BINDS = listOf("/dev", "/proc", "/sys", "/system")

        /** 顶层 `/` 不展示的系统绑定目录名。 */
        val ROOT_SKIP_NAMES = FIXED_BINDS.map { it.removePrefix("/") }.toSet()

        /** AI 配置目录在容器内的绑定目标。 */
        const val OPENX_ROOT = "/root/.openx"

        /** 顶层快捷入口别名：/workspace、/.openx。 */
        const val WORKSPACE_ALIAS = "/workspace"
        const val OPENX_ALIAS = "/.openx"

        /** 外部本地目录在容器内的绑定根（各目录位于 /root/local/<name>）。 */
        const val LOCAL_ROOT = "/root/local"
    }
}