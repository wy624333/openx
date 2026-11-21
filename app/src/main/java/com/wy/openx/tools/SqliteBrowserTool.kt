package com.wy.openx.tools

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SqliteBrowserTool : AgentTool {
    override val name = "sqlite_browser"
    override val description = "浏览和查询 SQLite 数据库文件。支持列出表、查看建表语句、执行 SELECT 查询。"
    override val parameters = buildToolParameters(
        ToolParameter("db", ParameterType.STRING, "数据库文件路径"),
        ToolParameter("query", ParameterType.STRING, "要执行的 SQL 查询（仅 SELECT 查询，不传则列出所有表）", required = false),
        ToolParameter("max_rows", ParameterType.INTEGER, "最多返回多少行（默认 30）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val dbPath = (args["db"] as? String)?.trim() ?: return "错误：未指定数据库文件路径"
        val query = (args["query"] as? String)?.trim() ?: ""
        val maxRows = (((args["max_rows"] as? Double)?.toInt()) ?: 30).coerceIn(1, 500)

        val dbFile = resolveDbFile(dbPath) ?: return "错误：数据库文件未找到或路径被安全策略拒绝「$dbPath」"
        if (!dbFile.exists()) return "错误：文件不存在「${dbFile.absolutePath}」"

        return try {
            withContext(Dispatchers.IO) {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    if (query.isBlank()) {
                        listTables(db)
                    } else {
                        runQuery(db, query, maxRows)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "数据库操作失败: ${e.message ?: "未知错误"}" }
    }

    private fun listTables(db: SQLiteDatabase): String {
        val tables = mutableListOf<String>()
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", null
        )
        cursor.use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        if (tables.isEmpty()) return "数据库中没有表"

        val sb = StringBuilder()
        sb.appendLine("📊 ${db.path} 中的表:")
        sb.appendLine()
        for (t in tables) {
            // 表名转义：内部双引号翻倍，杜绝通过畸形表名注入 SQL
            val safeName = t.replace("\"", "\"\"")
            val countCursor = db.rawQuery("SELECT COUNT(*) FROM \"$safeName\"", null)
            var count = 0
            countCursor.use { if (it.moveToFirst()) count = it.getInt(0) }

            sb.appendLine("📄 $t ($count 行)")

            val schemaCursor = db.rawQuery("SELECT sql FROM sqlite_master WHERE name=?", arrayOf(t))
            schemaCursor.use { c ->
                if (c.moveToFirst()) {
                    val sql = c.getString(0)
                    if (sql != null) {
                        sb.appendLine("  ```sql")
                        sb.appendLine("  $sql")
                        sb.appendLine("  ```")
                    }
                }
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun runQuery(db: SQLiteDatabase, query: String, maxRows: Int): String {
        val trimmed = query.trim()
        if (!trimmed.startsWith("SELECT", ignoreCase = true) &&
            !trimmed.startsWith("PRAGMA", ignoreCase = true) &&
            !trimmed.startsWith("EXPLAIN", ignoreCase = true)
        ) {
            return "错误：只允许 SELECT / PRAGMA / EXPLAIN 查询"
        }

        val startTime = System.currentTimeMillis()
        val cursor = db.rawQuery(trimmed, null)
        val cols = cursor.columnNames
        val colCount = cols.size
        val rows = mutableListOf<List<String>>()
        var rowCount = 0

        cursor.use { c ->
            while (c.moveToNext() && rowCount < maxRows) {
                val row = (0 until colCount).map { i ->
                    try { c.getString(i) ?: "NULL" } catch (_: Exception) { "NULL" }
                }
                rows.add(row)
                rowCount++
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        if (rows.isEmpty()) return "查询无结果 (${elapsed}ms)"

        val colWidths = cols.indices.map { i ->
            val dataWidths = rows.map { r -> r[i].length }
            maxOf(cols[i].length, dataWidths.maxOrNull() ?: 0)
        }

        fun formatRow(row: List<String>): String {
            return row.mapIndexed { i, v -> v.padEnd(colWidths[i]) }.joinToString(" | ")
        }

        val separator = colWidths.map { "-".repeat(it) }.joinToString("-+-")

        return buildString {
            appendLine("📊 查询结果 (${rows.size} 行, ${elapsed}ms)")
            appendLine("  ${trimmed.take(200)}${if (trimmed.length > 200) "..." else ""}")
            appendLine()
            appendLine(formatRow(cols.toList()))
            appendLine(separator)
            for (row in rows) appendLine(formatRow(row))
            if (rowCount >= maxRows) appendLine("... 仅显示前 $maxRows 行")
        }.trimEnd()
    }

    private fun resolveDbFile(path: String): File? {
        if (File(path).isAbsolute) return ToolPaths.sanitize(path)?.takeIf { it.exists() }
        val ctx = AppContextHolder.context ?: return null
        val base = ctx.getExternalFilesDir(null) ?: return null
        val candidate = File(base, path).takeIf { it.exists() }
            ?: File(base.parentFile, path).takeIf { it.exists() }
            ?: return null
        return ToolPaths.sanitize(candidate.absolutePath)
    }
}