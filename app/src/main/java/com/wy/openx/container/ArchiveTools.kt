package com.wy.openx.container

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 压缩包内条目（扁平列表），UI 按路径前缀组织成树。 */
data class ArchiveEntryInfo(
    /** 包内路径：目录以 `/` 结尾（如 `a/`、`a/b.txt`）。 */
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

/** 文件名是否为可识别的压缩包。 */
fun isArchiveFileName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tar.gz") ||
        n.endsWith(".tgz") || n.endsWith(".gz")
}

/** 压缩包内条目扫描（仅读元数据，不解压内容）。 */
object ArchiveScanner {

    /** 返回压缩包内全部条目的扁平列表；无法识别时返回空列表。 */
    fun listEntries(archive: File, archiveName: String): List<ArchiveEntryInfo> {
        val n = archiveName.lowercase()
        return when {
            n.endsWith(".zip") -> runCatching { listZip(archive) }.getOrDefault(emptyList())
            n.endsWith(".tar") -> listTar(archive, gzipped = false)
            n.endsWith(".tgz") || n.endsWith(".tar.gz") || n.endsWith(".gz") ->
                runCatching { listTar(archive, gzipped = true) }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    private fun listZip(archive: File): List<ArchiveEntryInfo> {
        ZipFile(archive).use { zf ->
            val out = ArrayList<ArchiveEntryInfo>()
            val en = zf.entries
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                out.add(
                    ArchiveEntryInfo(
                        path = if (e.isDirectory) "${e.name.trimEnd('/')}/" else e.name,
                        isDirectory = e.isDirectory,
                        size = e.size
                    )
                )
            }
            return out
        }
    }

    private fun listTar(archive: File, gzipped: Boolean): List<ArchiveEntryInfo> {
        val base = BufferedInputStream(FileInputStream(archive))
        val tin = if (gzipped) TarArchiveInputStream(GZIPInputStream(base)) else TarArchiveInputStream(base)
        val out = ArrayList<ArchiveEntryInfo>()
        tin.use {
            while (true) {
                val e: TarArchiveEntry = tin.nextTarEntry ?: break
                if (e.isDirectory) {
                    out.add(ArchiveEntryInfo(path = e.name.trimEnd('/') + "/", isDirectory = true, size = 0))
                } else if (!e.isLink && !e.isSymbolicLink) {
                    out.add(ArchiveEntryInfo(path = e.name, isDirectory = false, size = e.size))
                }
            }
        }
        return out
    }
}

/** 压缩包解压：zip / tar / tar.gz / tgz。 */
object ArchiveExtractor {

    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000

    /** 解压到 [destDir]（宿主机目录），返回写入条目数；失败抛 [IOException]。 */
    fun extract(archive: File, archiveName: String, destDir: File): Int {
        val n = archiveName.lowercase()
        return when {
            n.endsWith(".zip") -> extractZip(archive, destDir)
            else -> extractTar(archive, destDir, gzipped = n.endsWith(".tgz") || n.endsWith(".tar.gz") || n.endsWith(".gz"))
        }
    }

    /** 清洗包内路径，防穿越（`..` / 绝对路径 / null 字节）；非法条目返回 null 跳过。 */
    private fun sanitizedTarget(raw: String): String? {
        val cleaned = raw.replace('\\', '/')
        if (cleaned.contains('\u0000')) return null
        if (cleaned.startsWith("/")) return null
        val segments = cleaned.split("/")
        if (segments.any { it == ".." }) return null
        return cleaned.trimStart('/')
    }

    private fun extractZip(archive: File, destDir: File): Int {
        destDir.mkdirs()
        ZipFile(archive).use { zf ->
            var count = 0
            var total = 0L
            val en = zf.entries
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (count >= MAX_ENTRIES) throw IOException("压缩包条目过多")
                val rel = sanitizedTarget(e.name) ?: continue
                if (e.isDirectory) {
                    File(destDir, rel).mkdirs()
                    continue
                }
                total += e.size
                if (total > MAX_TOTAL_BYTES) throw IOException("压缩包解压后过大")
                val out = File(destDir, rel)
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
                count++
            }
            return count
        }
    }

    private fun extractTar(archive: File, destDir: File, gzipped: Boolean): Int {
        destDir.mkdirs()
        val base = BufferedInputStream(FileInputStream(archive))
        val tin = if (gzipped) TarArchiveInputStream(GZIPInputStream(base)) else TarArchiveInputStream(base)
        tin.use {
            var count = 0
            var total = 0L
            while (true) {
                val e: TarArchiveEntry = tin.nextTarEntry ?: break
                if (count >= MAX_ENTRIES) throw IOException("压缩包条目过多")
                val rel = sanitizedTarget(e.name) ?: continue
                if (e.isDirectory) {
                    File(destDir, rel).mkdirs()
                    continue
                }
                if (e.isLink || e.isSymbolicLink) continue
                total += e.size
                if (total > MAX_TOTAL_BYTES) throw IOException("压缩包解压后过大")
                val out = File(destDir, rel)
                out.parentFile?.mkdirs()
                out.outputStream().use { dst -> tin.copyTo(dst) }
                count++
            }
            return count
        }
    }
}

/** 把文件或文件夹压缩为 zip。文件夹打包时保留相对目录结构。压缩级别取 [Deflater] 常量。 */
object ArchiveCompressor {

    fun compress(source: File, destZip: File, level: Int = Deflater.DEFAULT_COMPRESSION) {
        if (!source.exists()) throw IOException("来源不存在")
        destZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip))).use { zip ->
            zip.setLevel(level)
            collect(source, "", zip)
        }
    }

    private fun collect(source: File, prefix: String, zip: ZipOutputStream) {
        if (source.isDirectory) {
            val dirEntry = "$prefix${source.name}/"
            zip.putNextEntry(ZipEntry(dirEntry))
            zip.closeEntry()
            val files = source.listFiles() ?: return
            for (f in files) {
                collect(f, "$dirEntry", zip)
            }
        } else {
            zip.putNextEntry(ZipEntry("$prefix${source.name}"))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}