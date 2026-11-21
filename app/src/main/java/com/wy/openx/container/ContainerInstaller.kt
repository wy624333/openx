package com.wy.openx.container

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream

/**
 * 负责把打进 assets 的 Alpine rootfs 与 PRoot 二进制安装到 App 私有目录。
 *
 * 自动根据设备架构（ARM / x86）加载对应资源。targetSdk 锁定 28，数据目录文件才可执行（见 build.gradle.kts）。
 */
class ContainerInstaller(
    private val context: Context,
    private val containerOsDetector: ContainerOsDetector
) {
    companion object {
        private const val TAG = "ContainerInstaller"

        /**
         * 从 assets 提取容器初始化依赖安装脚本到 ~/.openx/provision.sh 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.openx/provision.sh，由
         * 首次进入终端的初始化菜单以 `sh` 执行——脚本按包管理器（apk/apt-get/dnf/yum/pacman）
         * 安装基础包，包清单维护在脚本内而非代码里。启动即提取、每次覆盖写（脚本随 App 版本更新）。
         * 提取失败仅告警不抛：缺脚本时初始化菜单不会出现，用户可手动安装基础工具。
         */
        fun extractProvisionScript(context: Context) {
            val dest = File(File(context.filesDir, "openx"), "provision.sh")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("openx/provision.sh").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 provision 脚本失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取自定义 git credential helper 到 `~/.openx/git-credential-openx` 并赋可执行位，
         * 并幂等写入 credential 配置。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 `/root/.openx/git-credential-openx`。
         *
         * 配置不交给 provision.sh 写：provision.sh 只在用户进终端初始化菜单时跑，已初始化的老容器升级
         * App 后不会重跑，旧版留下的 `store` + `git-credential-aicode`（脚本从未被提取，形同虚设）会一直生效。
         * 故这里每次启动都覆盖写 gitconfig.credential（该文件只由 App 管理），并在 .gitconfig 缺 includeIf
         * 时补上，不依赖 provisioning 时机。
         */
        fun extractCredentialHelper(context: Context) {
            val dir = File(context.filesDir, "openx").apply { mkdirs() }
            val dest = File(dir, "git-credential-openx")
            runCatching {
                context.assets.open("openx/git-credential-openx").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
                writeCredentialConfig(dir)
            }.onFailure {
                FileLogger.w(TAG, "提取 git credential helper 失败: ${it.message}", it)
            }
        }

        /** 容器内路径（与 -b 绑定一致），写进 gitconfig 必须是容器视角的路径而非宿主路径。 */
        private const val CONTAINER_OPENX_DIR = "/root/.openx"
        private const val CREDENTIAL_CONFIG = "gitconfig.credential"

        /** 写 gitconfig.credential，并在 .gitconfig 缺 includeIf 时补上（两步都幂等）。 */
        private fun writeCredentialConfig(dir: File) {
            File(dir, CREDENTIAL_CONFIG).writeText(
                "[credential]\n    helper = $CONTAINER_OPENX_DIR/git-credential-openx\n"
            )
            val gitconfig = File(dir, ".gitconfig")
            val marker = "path = $CONTAINER_OPENX_DIR/$CREDENTIAL_CONFIG"
            val existing = runCatching { gitconfig.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
            // 已指向本文件就不动：避免每次启动都重写用户可能手工扩展过的配置
            if (existing.contains(marker)) return
            val prefix = if (existing.isBlank() || existing.endsWith("\n")) existing else "$existing\n"
            gitconfig.writeText(prefix + "[includeIf \"gitdir:/root/workspace/\"]\n\t$marker\n")
        }

        /**
         * 
         * 幂等：目标目录已存在或源目录不存在时直接跳过；仅迁移 process.sh 之外的用户数据
         * （provision.sh 由 assets 每次覆盖写，无需保留旧版），并把 .aicode 软链改指向新目录以防旧引用悬空。
         */
        fun migrateLegacyAicode(context: Context) {
            val legacy = File(context.filesDir, "aicode")
            val target = File(context.filesDir, "openx")
            if (!legacy.isDirectory || target.isDirectory) return
            runCatching {
                legacy.copyRecursively(target, overwrite = false)
                File(legacy, "provision.sh").delete()
                if (target.exists()) {
                    FileLogger.i(TAG, "已迁移旧 .aicode 数据到 .openx: ${target.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "迁移 .aicode 数据失败: ${it.message}", it)
            }
        }

        /** 安装版本。换 rootfs / proot 或改安装逻辑时 +1，触发重新解压。
         *  与 assets 里实际放的 Alpine 版本保持一致以便排查。 */
        private const val INSTALL_VERSION = "alpine-3.21.3-v6"
    }

    /** assets 内的架构特定目录 */
    val ASSET_DIR: String
        get() {
            // 优先按设备 ABI 选对应镜像目录。openx 单 arm64-v8a 打包，assets 仅含 container/arm；
            // 保留 x86 探测逻辑，万一后续打包多架构资产可无缝扩展。
            val preferX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val first = if (preferX86) "container/x86" else "container/arm"
            val fallback = if (preferX86) "container/arm" else "container/x86"
            return if (assetExists(first)) first else fallback
        }

    /** 轻量探测某 asset 路径是否被打进当前 APK（用于 [ASSET_DIR] 的 fallback 判断） */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    val ASSET_PROOT: String get() = "$ASSET_DIR/proot"
    // Termux proot 的 loader 分离（靠 PROOT_LOADER 定位），且动态依赖 libtalloc / libandroid-shmem。
    val ASSET_LOADER: String get() = "$ASSET_DIR/loader"
    val ASSET_LOADER32: String get() = "$ASSET_DIR/loader32"
    val ASSET_LIBTALLOC: String get() = "$ASSET_DIR/libtalloc.so.2"
    val ASSET_LIBSHMEM: String get() = "$ASSET_DIR/libandroid-shmem.so"
    // 故意用中性的 .bin 后缀：AGP 的 asset 合并会把 .tar.gz/.tgz 当归档自动解压并改名，
    // 导致运行时 open("...tar.gz") 找不到文件。.bin 让它当普通二进制原样打包。
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** rootfs 解压根目录 */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * 容器配置数据根目录，固定在 app 私有 filesDir。
     *
     * 刻意**独立于 [rootfsDir]**：rootfs 在容器版本升级时会被整体删除重装（见 [installRootfsIfNeed]），
     * 而本目录承载用户数据（如 provision.sh / mcp.json / skills 等），必须跨升级保留。它由
     * [com.wy.openx.container.LinuxContainerEngine] 绑定到容器内 `/root/.openx`。
     */
    val openxDir: File
        get() = File(context.filesDir, "openx")

    /** PRoot 可执行文件路径（Termux 构建，含 statx，动态链接 libtalloc/libandroid-shmem） */
    val prootBin: File
        get() = File(context.filesDir, "container/bin/proot")

    /** PRoot 的 64/32 位 loader（Termux proot loader 分离，由 PROOT_LOADER/_32 指向）。 */
    val prootLoader: File
        get() = File(context.filesDir, "container/bin/loader")
    val prootLoader32: File
        get() = File(context.filesDir, "container/bin/loader32")

    /** proot 的动态依赖库目录（libtalloc.so.2 / libandroid-shmem.so），由 LD_LIBRARY_PATH 指向。 */
    val prootLibDir: File
        get() = File(context.filesDir, "container/lib")

    /**
     * PRoot 在 Android 上必须的临时目录（Android 没有 /tmp）。
     * 放在 rootfs 的 /tmp（宿主 filesDir/rootfs/tmp）：cache 目录会被系统清理（清缓存后 proot
     * 找不到临时目录报 can't canonicalize），files 目录稳定；rootfs 重装后自带 /tmp 会重建，无需额外处理。
     */
    val prootTmpDir: File
        get() = File(rootfsDir, "tmp")

    /** 标记文件，内容是已安装的版本号 */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** 检查 rootfs 与 proot 是否已按当前版本安装就绪 */
    fun isInstalled(): Boolean {
        if (!prootBin.exists() || !rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /**
     * 若未安装（或版本不匹配）则从 assets 解压安装。幂等，可在每次执行命令前调用。
     *
     * [onProgress] 在真正解压/部署的各阶段被回调以更新 [ContainerInitState]；已安装的快路径不会调用。
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext

        FileLogger.i(TAG, "开始安装容器 rootfs（版本 $INSTALL_VERSION）")

        // 版本不匹配时清掉旧的，保证干净安装
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        extractRootfs(onProgress)
        configureResolvConf()
        prootTmpDir.mkdirs()

        installedMarker.writeText(INSTALL_VERSION)
        FileLogger.i(TAG, "容器 rootfs 安装完成")
    }

    /**
     * 按 [profile] 返回 rootfs 目录：内置仍是 [rootfsDir]，自定义本地镜像用 filesDir/rootfs_<id>
     * （自定义镜像子项目扩展时生效）。目录隔离——内置与自定义互不共享、互不删除。
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        if (profile.isBuiltin) rootfsDir
        else File(context.filesDir, "rootfs_${profile.id}")

    /** 自定义镜像的已安装标记（独立于内置 .installed，避免混淆）。 */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** 按 [profile] 判断是否已安装就绪：内置走现有版本校验，自定义本地看目录与标记。 */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin -> isInstalled()
            else -> prootBin.exists() && rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * 按 [profile] 解压安装 rootfs。内置走 assets 全流程（proot/resolv/apk 源）。
     * 不自动装基础工具——由进入终端时的初始化菜单（assets/openx/provision.sh）引导用户选择安装。
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        // rootfs 将被重新解压，容器系统可能变化，清掉旧的识别缓存让下次运行重新检测。
        containerOsDetector.clear(profile.id)

        if (profile.isBuiltin) {
            installRootfsIfNeed(onProgress)
            return@withContext
        }

        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "安装自定义容器 rootfs：${profile.id} -> ${dest.absolutePath}")
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                extractRootfsTo(dest, it, CompressedFormat.GZIP, onProgress)
            }
            is RootfsSource.LocalFile -> extractUserImage(profile, src, dest, onProgress)
        }
        configureResolvConf(dest)
        prootTmpDir.mkdirs()
        repairRootfsCompatibility(dest)
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "自定义容器 rootfs 安装完成：${profile.id}")
    }

    /** 解压用户导入的自定义镜像（tar.gz / tar.xz）：按魔数探测压缩格式，避免依赖扩展名。 */
    private fun extractUserImage(
        profile: ContainerProfile,
        src: RootfsSource.LocalFile,
        dest: File,
        onProgress: (ContainerInitState) -> Unit
    ) {
        val uri = runCatching { android.net.Uri.parse(src.uri) }.getOrThrow()
        val raw = java.io.BufferedInputStream(
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开镜像文件：${src.uri}")
            , 64 * 1024
        )
        raw.use { input ->
            input.mark(8)
            val magic = ByteArray(6)
            var n = 0
            while (n < magic.size) {
                val r = input.read(magic, n, magic.size - n)
                if (r < 0) break
                n += r
            }
            input.reset()
            val format = when {
                n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> CompressedFormat.GZIP
                n >= 6 && magic[0] == 0xfd.toByte() && magic[1] == 0x37.toByte() &&
                    magic[2] == 0x7a.toByte() && magic[3] == 0x58.toByte() &&
                    magic[4] == 0x5a.toByte() && magic[5] == 0x00.toByte() -> CompressedFormat.XZ
                else -> CompressedFormat.GZIP
            }
            FileLogger.i(TAG, "导入镜像 ${profile.id}，压缩格式：$format")
            extractRootfsTo(dest, input, format, onProgress)
        }
    }

    /**
     * 删除指定 profile 的 rootfs、proot 二进制与识别缓存，使下次 [installRootfsIfNeed] 重新解压安装。
     * 刻意保留 [openxDir]（用户数据如 provision.sh 跨重置保留）。
     */
    fun resetContainer(profile: ContainerProfile) {
        val rootfs = rootfsDirFor(profile)
        FileLogger.i(TAG, "重置容器 ${profile.id}：删除 rootfs ${rootfs.absolutePath}")
        containerOsDetector.clear(profile.id)
        if (rootfs.exists()) {
            runCatching { rootfs.deleteRecursively() }
                .onFailure { FileLogger.w(TAG, "删除 rootfs 失败，下次安装前会重试清理", it) }
        }
        // proot 二进制与标记随 rootfs 一起清（下次安装会重新从 assets 解压）
        listOf(prootBin, prootLoader, prootLoader32).forEach { runCatching { it.delete() } }
    }

    /**
     * 从 rootfs 探测 Alpine 大版本分支（v3.21 / v3.22 / edge）：优先读现有
     * /etc/apk/repositories 里的 `alpine/<分支>/`，回退 /etc/os-release 的 VERSION_ID，最后兜底 v3.21。
     */
    fun alpineBranch(rootfs: File): String {
        val repo = File(rootfs, "etc/apk/repositories")
        if (repo.isFile) {
            runCatching {
                repo.readLines().forEach { line ->
                    val m = Regex("alpine/([^/]+)/").find(line)
                    if (m != null) return m.groupValues[1]
                }
            }
        }
        val osRelease = File(rootfs, "etc/os-release")
        if (osRelease.isFile) {
            runCatching {
                val id = osRelease.readLines()
                    .firstOrNull { it.startsWith("VERSION_ID=") }
                    ?.substringAfter("=")?.trim('"', '\'')
                if (!id.isNullOrBlank()) {
                    val parts = id.split('.')
                    if (parts.size >= 2) return "v${parts[0]}.${parts[1]}"
                }
            }
        }
        return "v3.21"
    }

    /** 将选定的 apk 镜像源写入 rootfs/etc/apk/repositories（main/community 各一行），立即对下次 apk 操作生效。 */
    fun applyApkMirrorToRootfs(rootfs: File, mirror: ApkMirror) {
        val branch = alpineBranch(rootfs)
        val etc = File(rootfs, "etc/apk")
        etc.mkdirs()
        val content = "${mirror.baseUrl}/alpine/$branch/main\n" +
            "${mirror.baseUrl}/alpine/$branch/community\n"
        File(etc, "repositories").writeText(content)
        FileLogger.i(TAG, "apk 源已切换：${mirror.baseUrl}/alpine/$branch（镜像：${mirror.label}）")
    }

    /**
     * rootfs 兼容性巡检（幂等，失败静默）：修复已知的 Ubuntu 25.10+ 布局问题。
     * 所有容器统一调用，作用面由内部存在性检测收敛——非命中容器零改动。
     */
    fun repairRootfsCompatibility(rootfs: File) {
        fixCoreutilsPermissions(rootfs)
        fixNodeGlobEntry(rootfs)
    }

    private fun fixCoreutilsPermissions(rootfs: File) {
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        if (!coreutilsDir.isDirectory) return
        val target = PosixFilePermissions.fromString("rwxr-xr-x")
        var fixed = 0
        fun fix(path: File) {
            runCatching {
                val p = path.toPath()
                if (Files.getPosixFilePermissions(p) != target) {
                    Files.setPosixFilePermissions(p, target)
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "修复 coreutils 权限失败: ${path.absolutePath}", it) }
        }
        fix(File(rootfs, "usr/lib/cargo"))
        fix(File(rootfs, "usr/lib/cargo/bin"))
        fix(coreutilsDir)
        coreutilsDir.listFiles()?.forEach { fix(it) }
        if (fixed > 0) {
            FileLogger.i(TAG, "已修复容器 coreutils 权限: $fixed 个文件（${coreutilsDir.absolutePath}）")
        }
    }

    /**
     * 修复 Ubuntu 26.04 仓库打包 bug：node-glob 10.3.6 用 `dist/cjs/src -> .` 自引用软链做打包
     * 拍平，node 22+ 的 realpath 判定 ELOOP，npm 启动即报 MODULE_NOT_FOUND（与 PRoot 无关，
     * 原生 Ubuntu 26.04 同样中招）。真机布局下的修复：把 package.json 入口从 dist/cjs/src/
     * 改指真实打平文件所在 dist/cjs/。幂等：入口已正常则不动。
     */
    private fun fixNodeGlobEntry(rootfs: File) {
        val candidates = listOf(
            File(rootfs, "usr/share/nodejs/glob/package.json"),
            File(rootfs, "usr/share/node_modules/glob/package.json")
        )
        for (f in candidates) {
            if (!f.isFile) continue
            runCatching {
                val text = f.readText()
                if (text.contains("dist/cjs/src/")) {
                    f.writeText(text.replace("dist/cjs/src/", "dist/cjs/"))
                    FileLogger.i(TAG, "已修复 node-glob 入口: ${f.absolutePath}")
                }
            }.onFailure { FileLogger.w(TAG, "修复 node-glob 入口失败: ${f.absolutePath}", it) }
        }
    }

    /** 删除自定义 profile 的 rootfs 目录（删 profile 时调用）。内置 rootfs 不可删。 */
    fun deleteCustomRootfs(profile: ContainerProfile) {
        containerOsDetector.clear(profile.id)
        if (profile.isBuiltin) return
        rootfsDirFor(profile).deleteRecursively()
    }

    /**
     * 重置内置 Alpine 容器：删除其 rootfs 目录（含 .installed / .provisioned 标记），
     * 下次 [ensureInstalled] 会重新解压；进入终端时由初始化菜单重新引导安装。供内置镜像「重置」按钮调用。
     */
    fun resetBuiltinRootfs() {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
    }

    /** 按 [profile] 统一重置 rootfs：内置走 [resetBuiltinRootfs]，自定义本地删其 rootfs 目录。 */
    fun resetRootfs(profile: ContainerProfile) {
        if (profile.isBuiltin) {
            resetBuiltinRootfs()
            return
        }
        deleteCustomRootfs(profile)
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            migrateLegacyAicode(context)
            extractProvisionScript(context)
            extractCredentialHelper(context)
        }
    }

    /** 从 assets 复制 proot 全套（二进制 + loader + 动态依赖库）到私有目录并赋权限 */
    private fun installProot() {
        // 二进制与 loader：需可执行
        copyAsset(ASSET_PROOT, prootBin, executable = true)
        copyAsset(ASSET_LOADER, prootLoader, executable = true)
        copyAsset(ASSET_LOADER32, prootLoader32, executable = true)
        // 动态依赖库：放到 lib 目录，由 LD_LIBRARY_PATH 指向；可读即可（给可执行位无害）
        copyAsset(ASSET_LIBTALLOC, File(prootLibDir, "libtalloc.so.2"), executable = true)
        copyAsset(ASSET_LIBSHMEM, File(prootLibDir, "libandroid-shmem.so"), executable = true)
    }

    /** 把单个 asset 复制到目标文件，按需赋「对所有用户」的可执行位（proot 进程以 App uid 运行）。
     *  幂等：内容与 assets 一致则跳过——proot 常被运行中的容器进程占用，直接覆写会 ETXTBSY；
     *  不一致（App 升级换了新二进制）先删后写：unlink 运行中的可执行文件是安全的，新文件写新 inode 不与旧进程冲突。 */
    private fun copyAsset(assetPath: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()
        if (assetContentEquals(assetPath, dest)) return
        dest.delete()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (executable && !dest.setExecutable(true, false)) {
            FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
        }
    }

    /** asset 与目标文件字节是否一致（读失败按不一致处理）。 */
    private fun assetContentEquals(assetPath: String, dest: File): Boolean {
        if (!dest.isFile) return false
        return try {
            context.assets.open(assetPath).use { input ->
                dest.inputStream().use { output -> input.readBytes().contentEquals(output.readBytes()) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 解压 alpine-minirootfs.tar.gz，正确处理目录/文件/符号链接/硬链接与权限位 */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit) {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            extractRootfsTo(rootfsDir, rawIn, CompressedFormat.GZIP, onProgress)
        }
    }

    /** 镜像压缩格式：内置 Alpine 用 gzip，用户导入的可能是 gzip 或 xz。 */
    enum class CompressedFormat { GZIP, XZ }

    /**
     * 把 tar.gz / tar.xz 流解压到 [destDir]，正确处理目录/文件/符号链接/硬链接与权限位。
     * 内置 Alpine（[extractRootfs] 传 assets 流）与用户自定义镜像都用它。
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat = CompressedFormat.GZIP,
        onProgress: (ContainerInitState) -> Unit = {}
    ) {
        var processed = 0
        val decompressed = when (format) {
            CompressedFormat.GZIP -> GZIPInputStream(input)
            CompressedFormat.XZ -> XZCompressorInputStream(input)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // 防 zip-slip：确保解压目标落在 destDir 内
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "跳过越界条目: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // symlink 的目标可能是相对/绝对路径，原样创建（在容器内由 proot 解析）
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink 失败 ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // 硬链接：linkName 指向 tar 内已解压的另一文件
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // 退化为复制，保证文件存在
                        FileLogger.w(TAG, "hardlink 失败 ${entry.name} -> ${entry.linkName}，改为复制: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "忽略不支持的条目类型: ${entry.name}")
        }
    }

    /** 按 tar entry 的 mode 设置可执行位（owner 有 x 位则对所有人开放执行） */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner 写位
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * 写入容器内 DNS，否则 apk/npm 等联网操作会因无法解析域名而失败。
     * 用阿里云公共 DNS：国内解析更快/更稳，8.8.8.8 在部分网络环境会被拦截。
     * 已是有效配置（普通文件且含 nameserver）则不动，尊重容器内已有设置；
     * 空文件或坏链（Ubuntu/Debian base 常把 resolv.conf 链到 systemd-resolved 的 stub，PRoot 下不可用）先删再写。
     */
    private fun configureResolvConf(rootfs: File = rootfsDir) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        if (conf.isFile) {
            runCatching {
                if (conf.readText().contains("nameserver")) return
            }
        }
        conf.delete()
        conf.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
    }
}