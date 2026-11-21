package com.wy.openx.tools

import java.io.File

/**
 * AI 文件工具统一路径守卫：
 * - 容器路径（AI 在 terminal 里看到的 `/root/workspace/...`、`~/...` 等）先翻译成宿主真实文件，
 *   使文件工具与终端作用于同一批文件；
 * - 一律先 canonicalize，杜绝 ../ 与 symlink 绕过
 * - 系统分区(/system /proc /sys /dev /vendor ...)一律拒绝
 * - /data 下仅放行应用自身私有目录，其余拒绝
 * - 外部存储(/sdcard)与应用自有目录放行（应用声明了 MANAGE_EXTERNAL_STORAGE）
 */
object ToolPaths {

    private val FORBIDDEN_ROOTS = listOf(
        "/system", "/proc", "/sys", "/dev", "/vendor",
        "/efs", "/cust", "/product", "/odm", "/apex", "/sbin", "/xbin"
    )

    fun sanitize(path: String): File? {
        val ctx = AppContextHolder.context ?: return null
        if (path.isBlank()) return null
        // 容器路径优先翻译：AI 常用 `/root/workspace/...`（与其 terminal 视角一致）调文件工具；
        // 非容器路径（含 /sdcard、/data/user/0/... 宿主路径）返回 null 时按原样处理。
        val mapped = AppContextHolder.containerEngine?.toHostFileIfContainerPath(path)
        val target = mapped ?: File(path)
        val canonical = try {
            target.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val abs = canonical.absolutePath

        // 应用自身目录始终放行
        if (abs.startsWith(ctx.filesDir.canonicalPath)) return canonical
        if (abs.startsWith(ctx.cacheDir.canonicalPath)) return canonical
        ctx.getExternalFilesDir(null)?.let {
            if (abs.startsWith(it.canonicalPath)) return canonical
        }

        for (root in FORBIDDEN_ROOTS) {
            if (abs == root || abs.startsWith("$root/")) return null
        }
        if (abs == "/data" || abs.startsWith("/data/")) {
            val pkg = ctx.packageName
            val ownPrivate = abs.startsWith("/data/data/$pkg") ||
                abs.startsWith("/data/user/0/$pkg") ||
                abs.startsWith("/data/media") || // /sdcard 的真实挂载点
                abs.startsWith("/storage/emulated")
            if (!ownPrivate) return null
        }
        return canonical
    }
}
