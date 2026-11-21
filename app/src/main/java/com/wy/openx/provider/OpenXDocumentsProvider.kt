package com.wy.openx.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 OpenX 的私有目录通过 SAF 暴露给文件管理器（MT 管理器等）。
 *
 * 没有这个 provider，第三方文件管理器在「添加本地存储」里翻不到 OpenX 的任何东西：
 * 工作区、插件、记忆全在 `/data/user/0/<包名>/files/` 下，Android 11+ 的分区存储
 * 不允许别的 App 直接读，MT 只能看到共享存储 `/storage/emulated/0`。
 *
 * 一个 provider 暴露多个根（见 [roots]），比每个根单开一个 authority 好管理：
 * 文件管理器里只显示一项「OpenX」，点进去再选工作区 / 配置 / 容器根。
 */
class OpenXDocumentsProvider : DocumentsProvider() {

    companion object {
        /** 根 id 与相对 filesDir 的目录名。 */
        private const val ROOT_FILES = "files"
        private const val ROOT_CONTAINER = "container"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )

        /**
         * documentId = `根id:相对路径`。
         *
         * 路径不编码直接拼，读取时按第一个 `:` 切开即可 —— 根 id 里不含冒号，
         * 而 Linux 路径里出现冒号虽然可能（文件名允许），但首个冒号一定是分隔符。
         */
        private fun makeDocId(rootId: String, relPath: String): String =
            if (relPath.isEmpty()) "$rootId:" else "$rootId:$relPath"
    }

    private val roots = LinkedHashMap<String, Root>()

    private class Root(val id: String, val title: String, val summary: String, val dir: File)

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val filesDir = ctx.filesDir

        // 两个入口，已实测能在 MT / 系统「打开文档」里列出：
        //   1. OpenX        → files/ 整树（工作区、插件/记忆、日志…）
        //   2. OpenX 容器根 → files/rootfs，即容器 / 的系统目录（bin/etc/root/tmp…）
        //
        // 之前拆成 6 个根时一个都不显示，真因是 root flags 多加了
        // FLAG_SUPPORTS_RECENTS / FLAG_LOCAL_ONLY（见 queryRoots 里的注释）。
        roots[ROOT_FILES] = Root(
            ROOT_FILES,
            "OpenX",
            filesDir.absolutePath,
            filesDir
        )
        roots[ROOT_CONTAINER] = Root(
            ROOT_CONTAINER,
            "OpenX 容器根",
            File(filesDir, "rootfs").absolutePath,
            File(filesDir, "rootfs")
        )

        roots.values.forEach { runCatching { it.dir.mkdirs() } }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val result = MatrixCursor(cols, roots.size)
        roots.values.forEach { root ->
            val row = result.newRow()
            // ⚠️ MatrixCursor.RowBuilder.add(列名, 值) 在列名不在 projection 里时会抛
            // IllegalArgumentException；一旦 queryRoots 挂掉，系统就把整个 provider 从
            // "打开文档"列表里剔除（外面完全看不到，也不报错）。所以必须逐列确认。
            putRoot(row, cols, DocumentsContract.Root.COLUMN_ROOT_ID, root.id)
            putRoot(row, cols, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            putRoot(
                row, cols, DocumentsContract.Root.COLUMN_FLAGS,
                // 跟系统内置的 Operit provider 对齐：只用这两个。
                // 原来多设了 FLAG_SUPPORTS_RECENTS，在部分文件管理器的「打开文档」里
                // 会被当成需要额外能力而整条跳过。
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            putRoot(row, cols, DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_manage)
            putRoot(row, cols, DocumentsContract.Root.COLUMN_TITLE, root.title)
            putRoot(row, cols, DocumentsContract.Root.COLUMN_SUMMARY, root.summary)
            putRoot(row, cols, DocumentsContract.Root.COLUMN_DOCUMENT_ID, makeDocId(root.id, ""))
            putRoot(
                row, cols, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                runCatching { root.dir.usableSpace }.getOrDefault(0L)
            )
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, resolve(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val result = MatrixCursor(cols)
        val parent = resolve(parentDocumentId)
        val rootId = parentDocumentId.substringBefore(':')
        val children = parent.listFiles() ?: return result
        children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ).forEach { child ->
            val rel = child.absolutePath.removePrefix(rootOf(rootId).dir.absolutePath)
                .trimStart(File.separatorChar)
            includeFile(result, makeDocId(rootId, rel), child)
        }
        return result
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val result = MatrixCursor(cols)
        val root = roots[rootId] ?: return result
        runCatching {
            root.dir.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(50)
                .forEach { f ->
                    val rel = f.absolutePath.removePrefix(root.dir.absolutePath)
                        .trimStart(File.separatorChar)
                    includeFile(result, makeDocId(rootId, rel), f)
                }
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        if (file.isDirectory) throw FileNotFoundException("是目录，不能当文件打开：$documentId")
        val access = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("不支持的打开模式：$mode")
        }
        return ParcelFileDescriptor.open(file, access)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = resolve(parentDocumentId)
        val target = File(parent, displayName)
        if (!isInside(target, rootOf(parentDocumentId.substringBefore(':')).dir)) {
            throw FileNotFoundException("越界路径：$displayName")
        }
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.mkdirs() && !target.isDirectory) throw FileNotFoundException("建目录失败")
            if (!target.exists()) throw FileNotFoundException("目录已存在或创建失败：$displayName")
        } else {
            if (!target.createNewFile() && !target.isFile) throw FileNotFoundException("建文件失败")
        }
        val rootId = parentDocumentId.substringBefore(':')
        val rel = target.absolutePath.removePrefix(rootOf(rootId).dir.absolutePath)
            .trimStart(File.separatorChar)
        return makeDocId(rootId, rel)
    }

    override fun deleteDocument(documentId: String): Unit {
        val file = resolve(documentId)
        if (file == rootOf(documentId.substringBefore(':')).dir) {
            throw FileNotFoundException("不能删除根目录")
        }
        // 目录按整棵子树删：文件管理器点“删除”就是对整个目录的意图，
        // 只删空目录会让非空目录反复报错
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!ok) throw FileNotFoundException("删除失败：$documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val file = resolve(documentId)
        val rootId = documentId.substringBefore(':')
        val target = File(file.parentFile, displayName)
        if (!isInside(target, rootOf(rootId).dir)) throw FileNotFoundException("越界路径：$displayName")
        if (!file.renameTo(target)) throw FileNotFoundException("重命名失败：$documentId")
        val rel = target.absolutePath.removePrefix(rootOf(rootId).dir.absolutePath)
            .trimStart(File.separatorChar)
        return makeDocId(rootId, rel)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { resolve(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { resolve(documentId) }.getOrNull() ?: return false
        return isInside(child, parent)
    }

    override fun getDocumentType(documentId: String): String =
        if (resolve(documentId).isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            mimeOf(resolve(documentId))
        }

    /** 把一行文件信息塞进 cursor；`docId` 由调用方给，避免重复拼。 */
    private fun includeFile(result: MatrixCursor, docId: String, file: File) {
        val cols = result.columnNames
        val row = result.newRow()
        putDoc(row, cols, DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        putDoc(
            row, cols, DocumentsContract.Document.COLUMN_MIME_TYPE,
            if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(file)
        )
        putDoc(row, cols, DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { "/" })
        putDoc(row, cols, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        var flags = DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        putDoc(row, cols, DocumentsContract.Document.COLUMN_FLAGS, flags)
        putDoc(
            row, cols, DocumentsContract.Document.COLUMN_SIZE,
            if (file.isDirectory) null else file.length()
        )
    }

    private fun putRoot(
        row: MatrixCursor.RowBuilder,
        cols: Array<out String>,
        name: String,
        value: Any?
    ) {
        if (cols.contains(name)) row.add(name, value)
    }

    private fun putDoc(
        row: MatrixCursor.RowBuilder,
        cols: Array<String>,
        name: String,
        value: Any?
    ) {
        if (cols.contains(name)) row.add(name, value)
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun rootOf(rootId: String): Root =
        roots[rootId] ?: throw FileNotFoundException("未知的根：$rootId")
    /** documentId → 真实文件。路径必须落在该根目录内，防 `..` 越界。 */
    private fun resolve(documentId: String): File {
        val rootId = documentId.substringBefore(':')
        val rel = documentId.substringAfter(':', "")
        val root = rootOf(rootId)
        if (rel.isEmpty()) return root.dir
        val file = File(root.dir, rel)
        if (!isInside(file, root.dir)) throw FileNotFoundException("越界路径：$documentId")
        return file
    }

    private fun isInside(file: File, dir: File): Boolean = runCatching {
        val dirPath = dir.canonicalPath
        val filePath = file.canonicalPath
        filePath == dirPath || filePath.startsWith(dirPath + File.separator)
    }.getOrDefault(false)
}
