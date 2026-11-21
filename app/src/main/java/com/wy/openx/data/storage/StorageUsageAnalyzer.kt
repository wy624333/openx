package com.wy.openx.data.storage

import android.content.Context
import com.wy.openx.container.FileLogger
import java.io.File

/**
 * 存储占用分析按 openx 的实际目录裁剪）。
 *
 * 
 * 
 *
 * - **容器系统单独成类**：openx 的 rootfs 是 GB 级、占绝对大头（用户曾遇到「工作区超 2 个 G」），
 *   与其他项并列会被淡化，用户看不出空间到底去哪了。
 */
data class StorageCategory(
    val id: String,
    val name: String,
    val description: String,
    val bytes: Long,
    /** 是否可清理。 */
    val cleanable: Boolean,
    /** 风险级别：safe / caution / dangerous / info。 */
    val risk: String,
    /** 清理后果提示；不可清理时为 null。 */
    val cleanupHint: String? = null
)

data class StorageUsageSummary(
    val totalBytes: Long,
    val cleanableBytes: Long,
    val categories: List<StorageCategory>
) {
    /** 按占用倒序，便于展示与「空间去哪了」的直观判断。 */
    val sortedCategories: List<StorageCategory> get() = categories.sortedByDescending { it.bytes }
}

/** 清理策略：一组可一起执行的动作。 */
data class CleanupStrategy(
    val id: String,
    val name: String,
    val description: String,
    val risk: String,
    val categoryIds: List<String>
)

data class StorageItem(
    val path: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)

object StorageUsageAnalyzer {

    private const val TAG = "StorageUsage"

    /** 可清理的分类 id（供策略引用）。 */
    const val CATEGORY_CONTAINER = "container"
    const val CATEGORY_CACHE = "cache"
    const val CATEGORY_BROWSER = "browser"
    const val CATEGORY_TOOL_DATA = "tool_data"
    const val CATEGORY_WORKSPACE = "workspace"
    const val CATEGORY_HISTORY = "history"
    const val CATEGORY_MEMORY = "memory"
    const val CATEGORY_KNOWLEDGE = "knowledge"
    const val CATEGORY_OTHER = "other"

    const val CATEGORY_CONTAINER_IMAGES = "container_images"

    /**
     * 容器运行时（PRoot + 各 profile 的 rootfs）。「重置容器」删的就是这些。
     *
     * **不含 `rootfs_images`**：那是用户下载的镜像归档，`ContainerInstaller.resetContainer`
     * 并不删它。若把两者并成一类，重置后展示的释放量会对不上，误导用户。
     */
    private fun containerRuntimePaths(context: Context): List<File> = listOf(
        File(context.filesDir, "rootfs"),
        File(context.filesDir, "container")
    ).plus(
        // 自定义 profile 的 rootfs：rootfs_<id>
        context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("rootfs_") }?.toList() ?: emptyList()
    )

    /** 已下载的镜像归档（与运行时分开，因为重置容器不会删它们）。 */
    private fun containerImagePaths(context: Context): List<File> =
        listOf(File(context.filesDir, "rootfs_images"))

    /** 分析全部占用。耗时与文件数成正比，调用方应在 IO 线程执行。 */
    fun analyze(context: Context): StorageUsageSummary {
        val cacheDir = context.cacheDir
        val filesDir = context.filesDir
        // 用 getDatabasePath 而非 getDatabaseDir：后者是受限 API，
        // 前者公开且稳定，直接给出库文件路径。
        val dbFile = context.getDatabasePath("agent_console.db")

        val containerBytes = containerRuntimePaths(context).sumOf { dirSize(it) }
        val containerImageBytes = containerImagePaths(context).sumOf { dirSize(it) }
        val cacheBytes = dirSize(cacheDir)
        // 浏览器产物：① AI browser 工具的截图落在工作区 browser-screenshots 下（见 BrowserTool.SCREENSHOT_SUBDIR）；
        // ② 旧版本/参考实现落在 cacheDir 下的目录，一并统计以防遗留旧文件。
        val browserScreenshotDir = File(filesDir, "workspace/browser-screenshots")
        val browserBytes = listOf(
            browserScreenshotDir,
            File(cacheDir, "browser"),
            File(cacheDir, "browser_screenshots"),
            File(cacheDir, "downloads")
        ).sumOf { dirSize(it) }
        val toolDataBytes = dirSize(File(filesDir, "mcp_workspace"))
        // 工作区整体：**排除**浏览器截图子目录，避免与「浏览器产物」重复计数
        val workspaceBytes = dirSize(File(filesDir, "workspace")) - dirSize(browserScreenshotDir)
        val memoryBytes = dirSize(File(File(filesDir, "openx"), "memory"))
        val knowledgeBytes = dirSize(File(File(filesDir, "openx"), "knowledge"))
        // 会话历史：数据库文件（messages/conversations/llm_call_records 三表都在这个库里）
        val historyBytes = listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm")
        ).sumOf { if (it.isFile) it.length() else 0L }
        // 日志/崩溃等
        val extRoot = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
        val logsBytes = listOf(
            File(filesDir, "logs"),
            File(filesDir, "ai-logs"),
            File(filesDir, "crash"),
            File(File(filesDir, "openx"), "logs"),
            File(File(filesDir, "openx"), "ai-logs"),
            extRoot?.let { File(it, "openx") },
            extRoot?.let { File(it, "openx/logs") },
            extRoot?.let { File(it, "openx/ai-logs") }
        ).filterNotNull().sumOf { dirSize(it) }
        // 其他数据：量化、技能、设置等
        val quantBytes = dirSize(File(filesDir, "quant"))
        val skillsBytes = dirSize(File(filesDir, "skills"))
        val presetsBytes = dirSize(File(filesDir, "api_presets"))
        val providersBytes = dirSize(File(filesDir, "providers"))
        val otherExtra = logsBytes + quantBytes + skillsBytes + presetsBytes + providersBytes + dirSize(File(filesDir, "downloads")) + dirSize(File(filesDir, "browser"))

        val categories = listOf(
            StorageCategory(
                id = CATEGORY_CONTAINER,
                name = "容器系统",
                description = "PRoot 与 rootfs 运行时",
                bytes = containerBytes,
                cleanable = true,
                risk = "dangerous",
                cleanupHint = "会删除整个 Linux 容器，下次打开终端需重新安装"
            ),
            StorageCategory(
                id = CATEGORY_CONTAINER_IMAGES,
                name = "镜像归档",
                description = "已下载的发行版镜像文件",
                bytes = containerImageBytes,
                cleanable = true,
                risk = "caution",
                cleanupHint = "会删除已下载的镜像，需要时得重新下载"
            ),
            StorageCategory(
                id = CATEGORY_CACHE,
                name = "缓存",
                description = "临时文件与图片缓存",
                bytes = cacheBytes,
                cleanable = true,
                risk = "safe",
                cleanupHint = "清理后会在使用中自动重新生成"
            ),
            StorageCategory(
                id = CATEGORY_HISTORY,
                name = "会话历史",
                description = "对话、消息与调用记录（数据库）",
                bytes = historyBytes,
                cleanable = true,
                risk = "dangerous",
                cleanupHint = "会删除全部会话与消息记录，不可恢复"
            ),
            StorageCategory(
                id = CATEGORY_WORKSPACE,
                name = "工作区",
                description = "容器内 /root/workspace 对应的宿主目录（含上传附件）",
                bytes = workspaceBytes,
                cleanable = true,
                risk = "caution",
                cleanupHint = "会删除工作区内的文件与上传附件"
            ),
            StorageCategory(
                id = CATEGORY_TOOL_DATA,
                name = "工具数据",
                description = "内置 MCP 的文件读写目录",
                bytes = toolDataBytes,
                cleanable = true,
                risk = "caution",
                cleanupHint = "会删除工具读写产生的文件"
            ),
            StorageCategory(
                id = CATEGORY_MEMORY,
                name = "记忆",
                description = "长期记忆与项目记忆",
                bytes = memoryBytes,
                cleanable = true,
                risk = "caution",
                cleanupHint = "可展开查看并清理记忆文件"
            ),
            StorageCategory(
                id = CATEGORY_KNOWLEDGE,
                name = "知识库",
                description = "从对话里提炼出来的经验（entries/ 下的 Markdown）",
                bytes = knowledgeBytes,
                cleanable = true,
                risk = "caution",
                cleanupHint = "可展开查看并清理知识条目；清掉后 AI 就查不到了"
            ),
            StorageCategory(
                id = CATEGORY_BROWSER,
                name = "浏览器产物",
                description = "浏览器截图与下载的中间文件",
                bytes = browserBytes,
                cleanable = true,
                risk = "safe",
                cleanupHint = "会删除浏览器相关的中间文件"
            ),
            StorageCategory(
                id = CATEGORY_OTHER,
                name = "其他",
                description = "量化数据、日志、技能、设置等",
                bytes = otherBytes(context, listOf(
                    containerBytes, containerImageBytes, cacheBytes, historyBytes, workspaceBytes,
                    toolDataBytes, memoryBytes, knowledgeBytes, browserBytes
                )) + otherExtra,
                cleanable = true,
                risk = "safe",
                cleanupHint = "可展开查看并清理日志、崩溃、技能缓存等"
            )
        )

        val total = categories.sumOf { it.bytes }
        return StorageUsageSummary(
            totalBytes = total,
            cleanableBytes = categories.filter { it.cleanable }.sumOf { it.bytes },
            categories = categories
        )
    }

    /**
     * 「其他」= 应用数据总大小 - 已单列的分类之和。
     *
     * 用减法而非再逐个枚举：新增一个存储目录时不会漏统计，也不会重复统计。
     * 下限 0：各分类的统计时刻略有先后，理论上相减可能得到微小负值。
     */
    private fun otherBytes(context: Context, counted: List<Long>): Long {
        val dataTotal = dirSize(context.filesDir) + dirSize(context.cacheDir)
        val base = (dataTotal - counted.sum()).coerceAtLeast(0L)
        return base
    }

    /** 清理建议（对齐参考实现的三个策略）。 */
    fun strategies(): List<CleanupStrategy> = listOf(
        CleanupStrategy(
            id = "safe_quick",
            name = "安全快速清理",
            description = "清理缓存与浏览器中间产物，不影响任何数据",
            risk = "safe",
            categoryIds = listOf(CATEGORY_CACHE, CATEGORY_BROWSER)
        ),
        CleanupStrategy(
            id = "balance",
            name = "平衡清理",
            description = "在安全清理基础上，再清工作区与工具数据（会删其中的文件）",
            risk = "caution",
            categoryIds = listOf(CATEGORY_CACHE, CATEGORY_BROWSER, CATEGORY_WORKSPACE, CATEGORY_TOOL_DATA)
        ),
        CleanupStrategy(
            id = "reset_container",
            name = "重置容器",
            description = "删除 PRoot 与 rootfs 运行时，释放最多空间（需重新安装容器）",
            risk = "dangerous",
            // 只含运行时：镜像归档不在此列，因为 resetContainer 不会删它
            categoryIds = listOf(CATEGORY_CONTAINER)
        ),
        CleanupStrategy(
            id = "clear_images",
            name = "清理镜像归档",
            description = "删除已下载的发行版镜像文件（已导入的容器不受影响）",
            risk = "caution",
            categoryIds = listOf(CATEGORY_CONTAINER_IMAGES)
        )
    )

    /**
     * 清理指定分类，返回实际释放的字节数（清理前 - 清理后）。
     *
     * 只删分类**内容**、保留目录本身：部分目录（如 workspace）被容器绑定挂载，
     * 目录消失会导致挂载点缺失。
     */
    fun listItems(context: Context, categoryId: String): List<StorageItem> {
        val targets: List<File> = when (categoryId) {
            CATEGORY_CACHE -> listOf(context.cacheDir)
            CATEGORY_BROWSER -> listOf(
                File(context.filesDir, "workspace/browser-screenshots"),
                File(context.cacheDir, "browser"),
                File(context.cacheDir, "browser_screenshots"),
                File(context.cacheDir, "downloads")
            )
            CATEGORY_TOOL_DATA -> listOf(File(context.filesDir, "mcp_workspace"))
            CATEGORY_WORKSPACE -> listOf(File(context.filesDir, "workspace"))
            CATEGORY_HISTORY -> listOf(context.getDatabasePath("agent_console.db").parentFile)
            CATEGORY_MEMORY -> listOf(File(File(context.filesDir, "openx"), "memory"))
            CATEGORY_KNOWLEDGE -> listOf(File(File(context.filesDir, "openx"), "knowledge"))
            CATEGORY_CONTAINER -> containerRuntimePaths(context)
            CATEGORY_CONTAINER_IMAGES -> containerImagePaths(context)
            else -> {
                val extRoot = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
                listOfNotNull(
                    File(context.filesDir, "logs"),
                    File(context.filesDir, "ai-logs"),
                    File(context.filesDir, "crash"),
                    File(File(context.filesDir, "openx"), "logs"),
                    extRoot?.let { File(it, "openx") },
                    extRoot?.let { File(it, "openx/logs") },
                    extRoot?.let { File(it, "openx/ai-logs") }
                )
            }
        }
        return targets.filter { it.exists() }.flatMap { dir ->
            if (dir.isFile) {
                listOf(StorageItem(dir.absolutePath, dir.name, dir.length(), false))
            } else {
                (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@mapNotNull null
                    StorageItem(f.absolutePath, f.name, if (f.isDirectory) dirSize(f) else f.length(), f.isDirectory)
                }.sortedByDescending { it.size }
            }
        }.filter { it.size > 0 }.sortedByDescending { it.size }
    }

    fun clearCategory(context: Context, categoryId: String): Long {
        val targets: List<File> = when (categoryId) {
            CATEGORY_CACHE -> listOf(context.cacheDir)
            CATEGORY_BROWSER -> listOf(
                File(context.filesDir, "workspace/browser-screenshots"),
                File(context.cacheDir, "browser"),
                File(context.cacheDir, "browser_screenshots"),
                File(context.cacheDir, "downloads")
            )
            CATEGORY_TOOL_DATA -> listOf(File(context.filesDir, "mcp_workspace"))
            CATEGORY_KNOWLEDGE -> listOf(File(File(context.filesDir, "openx"), "knowledge"))
            CATEGORY_WORKSPACE -> listOf(File(context.filesDir, "workspace"))
            CATEGORY_CONTAINER -> containerRuntimePaths(context)
            CATEGORY_CONTAINER_IMAGES -> containerImagePaths(context)
            CATEGORY_HISTORY -> listOf(
                context.getDatabasePath("agent_console.db"),
                File(context.getDatabasePath("agent_console.db").path + "-wal"),
                File(context.getDatabasePath("agent_console.db").path + "-shm")
            )
            else -> emptyList()
        }
        if (targets.isEmpty()) return 0L

        val before = targets.sumOf { dirSize(it) }
        targets.forEach { dir ->
            if (dir.isFile) {
                runCatching { dir.delete() }
            } else {
                // 清内容、留目录：删掉目录本身会让容器的绑定挂载点缺失
                dir.listFiles()?.forEach { child ->
                    runCatching { child.deleteRecursively() }
                }
            }
        }
        val after = targets.sumOf { dirSize(it) }
        val freed = (before - after).coerceAtLeast(0L)
        FileLogger.i(TAG, "清理分类 $categoryId，释放 $freed 字节")
        return freed
    }

    /**
     * 目录大小（递归）。
     *
     * **不跟随符号链接**：与备份模块同样的坑——工作区里可能有指向手机存储的软链，
     * 跟随会把整张卡的体积算进来（用户此前遇到过「工作区超 2 个 G」的虚高数字）。
     */
    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (f in children) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) stack.addLast(f)
                else if (f.isFile) total += f.length()
            }
        }
        return total
    }
}
