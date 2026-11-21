package com.wy.openx.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 迁移 18→19 / 19→20 的 `seq` 回填回归。
 *
 * ## 为什么用 JDBC 而不是 Room 的 MigrationTestHelper
 *
 * 本次事故有**两个**独立的坑，其中一个只在真机才崩：
 *
 * 1. 迁移里 `CREATE INDEX` 却没在 `@Entity` 上声明 `indices` —— Room 打开库时比对 schema
 *    发现多一个索引，直接抛异常。**表现为「一开 APP 就崩、会话列表空白」**，用户看到的是
 *    「历史全没了」，其实数据一条没少，只是库打不开。这一条只能在 Room 层发现，
 *    但**根因是「实体声明与迁移 SQL 不同步」**，用文本层面的检查同样能挡住（见下）。
 * 2. 回填子查询引用了**正在被 UPDATE 修改**的 `seq` 列 —— SQLite 逐行推进，子查询读到
 *    已改过的值，结果每行都算成 1，显示序彻底失效（不会崩，但排序静默退化回原始 bug）。
 *    这一条是纯 SQL 语义问题，用 SQLite 直接验证最直接。
 *
 * 所以这里覆盖第 2 类（真实的 SQL 语义），第 1 类由 [实体索引声明与迁移一致] 这条
 * 文本一致性测试兜住 —— 它读的是真源码，一旦有人改了迁移里的索引名/列却忘了改注解，就会红。
 */
class SeqMigrationTest {

    private fun newDb(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    /** 18 版 schema（有 folded/queued，没有 seq），外加几条跨会话、id 乱序的数据。 */
    private fun seedV18(c: Connection) {
        c.createStatement().execute(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                role TEXT NOT NULL,
                author TEXT NOT NULL,
                time TEXT NOT NULL,
                content TEXT NOT NULL,
                folded INTEGER NOT NULL DEFAULT 0,
                queued INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // 故意制造「id 顺序 == 显示顺序」的历史库，且混入一条内存大 id（模拟 nextMessageId）
        c.createStatement().execute(
            """
            INSERT INTO messages (id, conversationId, role, author, time, content) VALUES
              (1, 1, 'user', '我', '10:00', '会话1-第1条'),
              (2, 1, 'assistant', 'AI', '10:01', '会话1-第2条'),
              (3, 1, 'user', '我', '10:02', '会话1-第3条'),
              (1791169000000, 1, 'assistant', 'AI', '10:03', '会话1-第4条(内存大id)'),
              (4, 2, 'user', '我', '11:00', '会话2-第1条'),
              (5, 2, 'assistant', 'AI', '11:01', '会话2-第2条')
            """.trimIndent()
        )
    }

    private fun seqOf(c: Connection, id: Long): Long =
        c.prepareStatement("SELECT seq FROM messages WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> assertTrue("id=$id 应存在", rs.next()); rs.getLong(1) }
        }

    private fun conversation(c: Connection, id: Long): Unit =
        c.prepareStatement("SELECT conversationId FROM messages WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); Unit }
        }

    @Test
    fun `18到19回填后同会话 seq 从1连续递增且跨会话各自独立`() {
        val c = newDb()
        seedV18(c)
        // ↓ 迁移体：与 DatabaseProvider.MIGRATION_18_19 保持一致
        c.createStatement().execute("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        c.createStatement().execute(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND m2.id <= messages.id
            )
            """.trimIndent()
        )

        // 会话 1：id 升序 = 显示序，故 seq 应为 1,2,3,4
        assertEquals(1L, seqOf(c, 1L))
        assertEquals(2L, seqOf(c, 2L))
        assertEquals(3L, seqOf(c, 3L))
        assertEquals("内存大 id 也必须拿到连续序号", 4L, seqOf(c, 1_791_169_000_000L))
        // 会话 2 独立编号
        assertEquals(1L, seqOf(c, 4L))
        assertEquals(2L, seqOf(c, 5L))
        c.close()
    }

    @Test
    fun `坏回填写法会让 seq 全部变成 1——锁住这个反例`() {
        val c = newDb()
        seedV18(c)
        c.createStatement().execute("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        // 事故现场的写法：子查询引用了正在被更新、且会被逐行改写的 seq
        c.createStatement().execute(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND (m2.seq < messages.seq OR (m2.seq = messages.seq AND m2.id <= messages.id))
            )
            """.trimIndent()
        )
        val all = c.createStatement().executeQuery("SELECT seq FROM messages").use { rs ->
            buildList { while (rs.next()) add(rs.getLong(1)) }
        }
        // 若哪天有人把回填改回引用 seq，这条会红，提醒你为什么不能那么写。
        assertEquals("坏写法会让所有 seq 归 1", listOf(1L, 1L, 1L, 1L, 1L, 1L), all)
        c.close()
    }

    @Test
    fun `19到20重算是幂等的`() {
        val c = newDb()
        seedV18(c)
        c.createStatement().execute("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        // 模拟「18→19 已提交但 seq 全是 1」的坏状态
        c.createStatement().execute("UPDATE messages SET seq = 1")
        // 19→20：重算
        c.createStatement().execute(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND m2.id <= messages.id
            )
            """.trimIndent()
        )
        assertEquals(4L, seqOf(c, 1_791_169_000_000L))
        assertEquals(2L, seqOf(c, 5L))
        // 再跑一次仍是同样的结果（幂等），迁移重放/重复执行安全
        c.createStatement().execute(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND m2.id <= messages.id
            )
            """.trimIndent()
        )
        assertEquals(4L, seqOf(c, 1_791_169_000_000L))
        c.close()
    }

    @Test
    fun `回填不丢失任何行也不改内容`() {
        val c = newDb()
        seedV18(c)
        val before = c.createStatement().executeQuery("SELECT id, content FROM messages ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
        }
        c.createStatement().execute("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        c.createStatement().execute(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId AND m2.id <= messages.id
            )
            """.trimIndent()
        )
        val after = c.createStatement().executeQuery("SELECT id, content FROM messages ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
        }
        assertEquals("迁移绝不能删行或改内容（事故时用户看到的『历史没了』其实只是库打不开）", before, after)
        c.close()
    }

    /**
     * 文本一致性：`@Entity(indices=…)` 的索引名/列必须与迁移里 `CREATE INDEX` 完全一致。
     *
     * 这是本次崩溃的根因防线 —— Room 只比对「名字 + 列」，名字不一致就会判定 schema 不匹配
     * 并抛异常（一开 APP 就崩）。用源码文本比对，能在编译期之前就发现问题。
     */
    @Test
    fun `实体索引声明与迁移一致`() {
        val root = System.getProperty("openx.projectDir") ?: guessProjectDir()
        val entity = java.io.File(root, "app/src/main/java/com/wy/openx/data/local/MessageEntity.kt").readText()
        val provider = java.io.File(root, "app/src/main/java/com/wy/openx/data/local/DatabaseProvider.kt").readText()

        val declared = Regex("""Index\(name\s*=\s*"([^"]+)"\s*,\s*value\s*=\s*\[([^\]]*)]""")
            .findAll(entity)
            .map { it.groupValues[1] to it.groupValues[2].replace("\"", "").split(",").map(String::trim).filter(String::isNotEmpty) }
            .toList()

        val created = Regex("""CREATE INDEX IF NOT EXISTS `([^`]+)` ON `?(\w+)`? \(([^)]*)\)""")
            .findAll(provider)
            .map { it.groupValues[1] to it.groupValues[3].replace("`", "").split(",").map(String::trim) }
            .toList()

        for ((name, cols) in declared) {
            val match = created.firstOrNull { it.first == name }
            assertTrue(
                "实体声明了索引 `$name`，但迁移里没有同名 CREATE INDEX —— Room 打开库时会判定 " +
                    "schema 不匹配并抛异常（一开 APP 就崩，用户看到『历史全没了』）。",
                match != null
            )
            assertEquals("索引 `$name` 的列必须在实体与迁移里一致", cols, match!!.second)
        }
        // messages 表上除实体声明的之外，不允许迁移偷偷多建索引（同样会让 Room 校验失败）
        val messageIndexes = created.filter { it.first.contains("messages") }.map { it.first }.toSet()
        val declaredNames = declared.map { it.first }.toSet()
        assertTrue(
            "迁移在 messages 上建的索引 $messageIndexes 必须在实体里声明（否则 Room 校验失败→崩溃）",
            declaredNames.containsAll(messageIndexes)
        )
    }

    private fun guessProjectDir(): String {
        // 测试工作目录通常是 app/ 或项目根，向上找 app/build.gradle.kts
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            if (java.io.File(dir, "app/build.gradle.kts").exists()) return dir.absolutePath
            dir = dir.parentFile
        }
        error("找不到项目根目录（含 app/build.gradle.kts）")
    }
}
