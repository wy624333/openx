package com.wy.openx.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN isCode INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN isSteps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN steps TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN imageUri TEXT")
    }

    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN tokens INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN timeMs INTEGER")
    }

    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN toolArguments TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN toolResult TEXT")
    }

    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN inputTokens INTEGER")
    }

    private val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN cachedInputTokens INTEGER")
    }

    private val MIGRATION_6_7 = Migration(6, 7) { db ->
        db.execSQL("ALTER TABLE conversations ADD COLUMN requestCount INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_7_8 = Migration(7, 8) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `llm_call_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT, " +
                    "`providerId` TEXT, " +
                    "`model` TEXT, " +
                    "`reasoningEffort` TEXT, " +
                    "`kind` TEXT NOT NULL, " +
                    "`inputTokens` INTEGER NOT NULL, " +
                    "`outputTokens` INTEGER NOT NULL, " +
                    "`cachedInputTokens` INTEGER NOT NULL, " +
                    "`cacheCreationTokens` INTEGER NOT NULL, " +
                    "`ttfbMillis` INTEGER, " +
                    "`durationMillis` INTEGER, " +
                    "`status` TEXT NOT NULL, " +
                    "`errorMessage` TEXT, " +
                    "`stopReason` TEXT, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_call_records_provider_model` ON `llm_call_records` (`providerId`, `model`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_call_records_createdAt` ON `llm_call_records` (`createdAt`)")
    }

    private val MIGRATION_8_9 = Migration(8, 9) { db ->
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastInputTokens INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_9_10 = Migration(9, 10) { db ->
        // 手动置顶 + 最近活跃时间（用于列表排序：置顶优先 → 最近聊天上浮）。
        // 旧会话 lastActiveAt 置 0，排序退化为「置顶优先 + id DESC」，与升级前观感一致。
        db.execSQL("ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastActiveAt INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_10_11 = Migration(10, 11) { db ->
        // 会话归档：从侧边栏主列表收进「历史」页，数据保留。
        // 旧会话默认 0（未归档）——升级后观感不变，不会突然有会话从列表里消失。
        db.execSQL("ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_11_12 = Migration(11, 12) { db ->
        // 提供商独立管理（多 Key 轮换 / 模型列表 / 代理 /启用开关）。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_providers` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`apiKey` TEXT NOT NULL, " +
                    "`multiKeyEnabled` INTEGER NOT NULL, " +
                    "`apiKeys` TEXT NOT NULL, " +
                    "`keyRotationStrategy` TEXT NOT NULL, " +
                    "`keyFailoverThreshold` INTEGER NOT NULL, " +
                    "`keyCooldownMinutes` INTEGER NOT NULL, " +
                    "`baseUrl` TEXT NOT NULL, " +
                    "`defaultModel` TEXT NOT NULL, " +
                    "`models` TEXT NOT NULL, " +
                    "`selectedModel` TEXT NOT NULL, " +
                    "`isEnabled` INTEGER NOT NULL, " +
                    "`useFullUrl` INTEGER NOT NULL, " +
                    "`useResponseApi` INTEGER NOT NULL, " +
                    "`openaiChatCacheKey` INTEGER NOT NULL, " +
                    "`balanceScriptPath` TEXT NOT NULL, " +
                    "`balanceRefreshInterval` INTEGER NOT NULL, " +
                    "`userAgent` TEXT NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, " +
                    "`proxyEnabled` INTEGER NOT NULL, " +
                    "`proxyType` TEXT NOT NULL, " +
                    "`proxyHost` TEXT NOT NULL, " +
                    "`proxyPort` INTEGER NOT NULL, " +
                    "`proxyUsername` TEXT NOT NULL, " +
                    "`proxyPassword` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`)" +
                    ")"
        )
    }

    private val MIGRATION_12_13 = Migration(12, 13) { db ->
        // 已发送/已生成消息的附件元数据（气泡下方附件行）：文件名/容器路径/本地路径/MIME/大小/是否图片。
        // 历史消息该列为 NULL，渲染时由 imageUri 兜底重建单条附件。
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
    }

    private val MIGRATION_13_14 = Migration(13, 14) { db ->
        // 会话级模型选择（「模型选择模式」开关开启时才有值）。
        // 两列都 nullable：旧数据为 NULL = 未选择过 → 跟随全局当前提供商，行为与升级前一致。
        db.execSQL("ALTER TABLE conversations ADD COLUMN providerId TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN model TEXT")
    }

    private val MIGRATION_14_15 = Migration(14, 15) { db ->
        // 压缩不再删库，改为给消息打「已折叠」标记（保留可翻阅的历史）。
        db.execSQL("ALTER TABLE messages ADD COLUMN folded INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_15_16 = Migration(15, 16) { db ->
        // 排队中的用户消息：AI 生成期间发出的消息立即落库并显示在聊天页，
        // 靠 queued 标记区分「尚未发给模型」，消费队列时置 false。
        db.execSQL("ALTER TABLE messages ADD COLUMN queued INTEGER NOT NULL DEFAULT 0")
    }

    private val MIGRATION_16_17 = Migration(16, 17) { db ->
        // 任务面板持久化（原先是纯内存，重启清空）。
        // 联合主键 (conversationId, taskId)：任务面板是会话级的，同一条任务在不同会话
        // 里各存一行；同一会话内 taskId 唯一。
        // 列名 taskOrder 而非 order —— `order` 是 SQL 关键字，避免到处加反引号。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tasks` (" +
                "`conversationId` INTEGER NOT NULL, " +
                "`taskId` INTEGER NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`taskOrder` INTEGER NOT NULL, " +
                "PRIMARY KEY(`conversationId`, `taskId`)" +
                ")"
        )
    }

    private val MIGRATION_17_18 = Migration(17, 18) { db ->
        // 标题定稿标志（AI 生成成功或用户手动改名后置 1；true 则不再自动生成标题）。
        //
        // 默认给 **1**（而不是 0）：升级时已存在的会话都已有一个用户看过的标题。
        // 若默认 0，升级后在任意旧会话里发一条消息，AI 就会重新生成并**覆盖**那个标题——
        // 正是本次要修的症状（原先标志只在内存、重启即丢）。旧会话保持现状最不惊扰。
        // 只有升级后**新建**的会话（insert 走 Entity 默认值 false）才会走 AI 生成一次。
        db.execSQL("ALTER TABLE conversations ADD COLUMN titleFinalized INTEGER NOT NULL DEFAULT 1")
    }

    /**
     * 新增 `messages.seq`：会话内单调递增的**显示序号**，与 id 解耦。
     *
     * 为什么必须加这一列：消息 id 有两套来源——库自增（小整数）与内存
     * `nextMessageId = 毫秒×1000`（≈1.79e18）。混在一列里 `ORDER BY id` 并不等于显示顺序：
     * 压缩时新插入的 marker/summary 拿到一个很小的自增 id，而排在它前面的折叠历史里混着
     * 内存大 id → 压缩卡被排到折叠历史**之前**，用户看到「卡片跑到最上面、历史不见了」。
     *
     * 回填：按会话、以 id（= rowid）升序编号 1,2,3…，等价于升级前的 `ORDER BY id` 观感，
     * 升级不改变任何会话的消息排列。
     *
     * ⚠ 回填子查询**只能引用 `m2.id`**，不能引用 `messages.seq`：SQLite 的 `UPDATE` 是逐行
     * 推进的，子查询会读到**已经被改过的** `seq`，于是每行算出来都是 1（实测），显示序直接失效。
     */
    private val MIGRATION_18_19 = Migration(18, 19) { db ->
        db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND m2.id <= messages.id
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversation_seq` ON `messages` (`conversationId`, `seq`)")
    }

    /**
     * 修正 18→19 可能留下的坏 `seq`（那次回填 SQL 引用了正在更新的列，结果全部算成 1），
     * 并补齐索引。
     *
     * 为什么需要这一条：18→19 已经发过一版**会崩**的包，用户设备上的库可能停在两种状态之一——
     * 迁移回滚了（仍是 18）或提交了但 `seq` 全为 1（19）。单加一条 `19→20` 才能保证
     * **两种状态都能正确打开并修好**，而不是赌用户处于哪一种。
     *
     * 重算 `seq` 是幂等的：无论 19 上是「全 1」还是已被其它路径改过，都按 id 重新编号。
     * 这也顺带修掉一个更隐蔽的问题：旧的坏回填让「同会话所有消息 seq 相同」，
     * UI 排序会退化成按 id（即回到这个字段要解决的原始 bug）。
     */
    private val MIGRATION_19_20 = Migration(19, 20) { db ->
        db.execSQL(
            """
            UPDATE messages SET seq = (
                SELECT COUNT(*) FROM messages AS m2
                WHERE m2.conversationId = messages.conversationId
                  AND m2.id <= messages.id
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversation_seq` ON `messages` (`conversationId`, `seq`)")
    }

    /**
     * 20 → 21：供应商表加来源标记。
     *
     * `source` / `pluginId` 用于区分「用户手填」与「插件在清单里声明」的供应商。
     * 后者由宿主随插件启停自动建/删，并在列表里打「插件」标签。
     * 已有行默认 `user`：用户此前手填的条目本来就都是自己加的。
     */
    private val MIGRATION_20_21 = Migration(20, 21) { db ->
        db.execSQL("ALTER TABLE ai_providers ADD COLUMN source TEXT NOT NULL DEFAULT 'user'")
        db.execSQL("ALTER TABLE ai_providers ADD COLUMN pluginId TEXT NOT NULL DEFAULT ''")
    }


    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "agent_console.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                // 不再使用 fallbackToDestructiveMigration：
                // 缺失迁移时应显式报错而非静默清空用户全部聊天记录。
                // 新增 schema 时必须补写 Migration，否则升级将崩溃（可感知、可修复）。
                .build()
                .also { instance = it }
        }
    }
}
