package com.wy.openx.data.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.wy.openx.BuildConfig
import com.wy.openx.container.FileLogger
import com.wy.openx.data.local.AIProviderEntity
import com.wy.openx.data.local.ChunkedReader
import com.wy.openx.data.local.ConversationEntity
import com.wy.openx.data.local.DatabaseProvider
import com.wy.openx.data.local.LlmCallRecordEntity
import com.wy.openx.data.local.MessageEntity
import com.wy.openx.data.model.AppSettings
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.store.MemoryStore
import com.wy.openx.data.store.SettingsStore
import com.wy.openx.data.store.WorkflowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份格式版本：新增/变更段结构时递增，恢复时拒绝高于自身的版本。 */
const val BACKUP_FORMAT_VERSION = 1

/** 单个备份段的描述（写入 manifest，恢复前供 UI 展示）。 */
data class BackupSectionInfo(
    /** 段 id，见 [BackupSections]。 */
    val id: String,
    /** 展示名。 */
    val label: String,
    /** 条目数（会话数 / 记忆条数 / 文件数…）。 */
    val count: Int
)

/** 备份清单：恢复前先读它，用于校验版本 + 向用户展示「这份备份里有什么」。 */
data class BackupManifest(
    val format: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    val sections: List<BackupSectionInfo> = emptyList()
)

/** 恢复模式。 */
enum class RestoreMode {
    /** 覆盖：先清空对应数据再还原，结果与备份时刻一致（回滚语义）。 */
    OVERWRITE,

    /** 合并：现有数据一律保留，备份内容追加/同名更新（绝不删除）。 */
    MERGE
}

/** 备份/恢复结果。 */
/**
 * 恢复时某一段的**实际落地结果**。
 *
 * 和 [BackupManifest] 的区别很关键：manifest 记的是「这份备份里有什么」，
 * 而恢复可以只选其中几段，所以 manifest 不能用来汇报恢复结果——
 * 否则只恢复 3 段也会列出全部 15 行，看着齐全其实大部分没写进去。
 * 这里记的是「真正写进去了什么」，并区分 [cleared]（覆盖模式下被清空）与 [count] 条。
 */
data class RestoredSection(
    val label: String,
    /** 不按条目计的段（如整块生效的设置）为 null。 */
    val count: Int? = null,
    val unit: String = "",
    /** 覆盖模式下该段被清空而不是写入。 */
    val cleared: Boolean = false
) {
    fun describe(): String = when {
        cleared -> "$label（已清空）"
        count != null && unit.isNotEmpty() -> "$label（$count $unit）"
        count != null -> "$label（$count）"
        else -> label
    }
}

data class BackupOutcome(
    val ok: Boolean,
    val message: String,
    val manifest: BackupManifest? = null,
    /** 加密包缺口令或口令错误：UI 收到 true 应弹口令框，重开 SAF 流后带口令重试。 */
    val passwordRequired: Boolean = false,
    /**
     * 被跳过的内容（工作区超上限、符号链接过多…）。
     *
     * 单独列出来而不是只塞进 [message]：UI 需要把这些**高亮**——备份「成功」但工作区
     * 整段没进去是最容易被忽略的失败方式，用户必须一眼看到。
     */
    val skipped: List<String> = emptyList(),
    /** 恢复时实际生效的段（按 [RestoreMode] 落地后的真实结果，不是备份内容）。 */
    val restored: List<RestoredSection> = emptyList()
)

/**
 * 备份预检结果。单独建类型而不是复用 [BackupOutcome]：
 * 预检失败**不该**和「恢复失败」共用一个布尔——前者要提示输口令，后者要提示选段落。
 */
data class InspectOutcome(
    val manifest: BackupManifest? = null,
    val passwordRequired: Boolean = false,
    val error: String? = null
)

/** 备份段 id 常量。 */
object BackupSections {
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val MODEL_METADATA = "model_metadata"
    const val CONVERSATIONS = "conversations"
    const val MEMORY = "memory"
    const val KNOWLEDGE = "knowledge"
    const val MCP = "mcp"
    const val SKILLS = "skills"
    const val WORKFLOWS = "workflows"
    const val BUILTIN_MCP = "builtin_mcp"
    const val TERMINAL = "terminal"
    const val CONTAINER = "container"
    const val WORKSPACE = "workspace"
    const val REMOTE = "remote"
}
/**
 * 备份段目录：**备份范围选择 UI 的唯一数据源**。
 *
 * ## 为什么要显式列出来，而不是从 [BackupSections] 常量或 [fileSections] 反推
 * 文件段是用 [FileSection.id] 登记的，而技能目录段刻意**跳过**登记（它并入
 * [BackupSections.SKILLS] 同一个条目）。另外量化相关的三段真实 id 是
 * `portfolio` / `stock_memory` / `signal_tracker`——原来的 `BackupSections.QUANT`
 * 常量从来没被用过（0 引用），照着它写 UI 会勾到一个不存在的段。
 * 列成一份常量并由单测锁住，才能保证「勾选框里显示的」和「实际打包的」是同一批。
 */
object BackupSectionCatalog {
    data class Entry(val id: String, val label: String, val defaultOn: Boolean = true)

    val entries: List<Entry> = listOf(
        Entry(BackupSections.SETTINGS, "设置与 API 预设"),
        Entry(BackupSections.PROVIDERS, "提供商配置"),
        Entry(BackupSections.MODEL_METADATA, "模型能力与窗口配置"),
        Entry(BackupSections.CONVERSATIONS, "会话与聊天记录"),
        Entry(BackupSections.MEMORY, "记忆（长期 + 项目）"),
        Entry(BackupSections.KNOWLEDGE, "知识库（对话提炼的经验）"),
        Entry(BackupSections.MCP, "MCP 配置"),
        Entry(BackupSections.BUILTIN_MCP, "内置 MCP 数据"),
        Entry("mcp_workspace", "内置 MCP 数据文件"),
        Entry(BackupSections.SKILLS, "技能"),
        Entry(BackupSections.WORKFLOWS, "工作流"),
        Entry("portfolio", "量化持仓与交易记录"),
        Entry("stock_memory", "自选股"),
        Entry("signal_tracker", "信号跟踪"),
        Entry(BackupSections.TERMINAL, "终端外观设置"),
        // 连接与同步：服务器列表、挂载、登录密钥、主机指纹、同步设置。
        // 凭据即使备份出来也**换机后解不开**（Keystore 密钥不可导出），到新设备需重填；
        // 同设备上恢复备份则仍然可用。
        Entry(BackupSections.REMOTE, "连接与同步"),
        // 容器：只搬「选了哪个 profile / 哪个 apk 镜像源 / 自定义 profile 定义」，
        // rootfs 与已下载镜像不搬（换机后文件不存在，镜像可重新下载）
        Entry(BackupSections.CONTAINER, "容器配置"),
        // 工作区默认不勾：内容常有 GB 级，勾上容易白等（上限已放宽到 2GB，但仍可能超）
        Entry(BackupSections.WORKSPACE, "工作区文件", defaultOn = false)
    )

    /** 默认勾选集（工作区除外）。 */
    val defaultSelection: Set<String> = entries.filter { it.defaultOn }.map { it.id }.toSet()

    fun isValid(id: String): Boolean = entries.any { it.id == id }

    fun labelOf(id: String): String = entries.firstOrNull { it.id == id }?.label ?: id
}


/**
 * 数据备份 / 恢复。
 *
 * **备份内容**（打包为 zip）：设置与 API 预设、会话与消息与调用记录、记忆（长期+项目）、
 * MCP 配置、技能、工作流、内置 MCP 数据、量化数据、终端外观、工作区文件。
 *
 * **明确排除**：容器 rootfs / proot / 镜像（GB 级，可由镜像源重新下载）、日志（有独立导出）、
 * token 统计与界面折叠状态（无迁移价值）。
 *
 * 容器配置（profile 列表、激活容器、镜像下载记录）也排除：其中的 rootfs 文件 URI 指向本机
 * 私有目录，跨设备还原后必然失效，还原一个坏配置反而添乱。
 */
class BackupManager(
    private val context: Context,
    /**
     * 容器配置仓库。**必须传与界面同一个实例**（MainViewModel 里那个）：
     * 它的三个 flow 只在构造时从 prefs 播种一次，之后只由仓库自己的 setter 推进。
     * 若恢复时另起一个实例（或绕过仓库直写 prefs），界面会停留在旧值直到进程重启。
     */
    private val containerSettings: com.wy.openx.container.ContainerSettingsRepository? = null
) {

    private val gson = Gson()
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val memoryStore = MemoryStore(appContext)
    private val workflowStore = WorkflowStore(appContext)
    private val db = DatabaseProvider.get(appContext)

    companion object {
        private const val TAG = "BackupManager"
        private const val MANIFEST = "manifest.json"
        private const val DATA_PREFIX = "data/"
        private const val FILES_PREFIX = "files/"
        private const val FILE_SETTINGS = "settings.json"
        private const val FILE_PROVIDERS = "providers.json"
        private const val FILE_CONVERSATIONS = "conversations.json"
        private const val FILE_MCP = "mcp.json"
        private const val FILE_MODEL_METADATA = "custom-model-metadata.json"
        private const val FILE_SKILLS = "skills.json"
        private const val FILE_WORKFLOWS = "workflows.json"
        private const val FILE_BUILTIN_MCP = "builtin_mcp.json"
        private const val FILE_TERMINAL = "terminal_settings.json"
        private const val FILE_CONTAINER = "container.json"
        private const val FILE_REMOTE = "remote.json"

        /** 远程模块在应用私有目录下的两个文件，文件名与 RemoteStore / RemoteKeyStore 一致。 */
        private const val FILE_REMOTE_SERVERS = "remote_servers.json"
        private const val FILE_REMOTE_KEYS = "remote_keys.json"

        private const val SP_BUILTIN_MCP = "builtin_mcp_store"
        private const val SP_TERMINAL = "openx_terminal_settings"
        private const val SP_REMOTE_HOST_KEYS = "remote_host_keys"
        private const val SP_REMOTE_SYNC_SETTINGS = "remote_sync_settings"

        private val fileSections = listOf(
            FileSection(BackupSections.MEMORY, "记忆（长期+项目）", "${FILES_PREFIX}openx/memory/", "openx/memory"),
            FileSection(BackupSections.KNOWLEDGE, "知识库", "${FILES_PREFIX}openx/knowledge/", "openx/knowledge"),
            FileSection("skills_dir", "技能", "${FILES_PREFIX}openx/skills/", "openx/skills", BackupSections.SKILLS),
            FileSection("mcp_workspace", "内置 MCP 数据文件", "${FILES_PREFIX}mcp_workspace/", "mcp_workspace"),
            FileSection("portfolio", "量化持仓与交易记录", "${FILES_PREFIX}portfolio/", "portfolio"),
            FileSection("stock_memory", "自选股", "${FILES_PREFIX}stock_memory/", "stock_memory"),
            FileSection("signal_tracker", "信号跟踪", "${FILES_PREFIX}signal_tracker/", "signal_tracker"),
            FileSection(BackupSections.WORKSPACE, "工作区文件", "${FILES_PREFIX}workspace/", "workspace")
        )

        /** [export] 里显式登记进清单的段 id（不含 [fileSections] 那批）。 */
        private val registeredSectionIds = setOf(
            BackupSections.SETTINGS, BackupSections.PROVIDERS, BackupSections.CONVERSATIONS,
            BackupSections.MCP, BackupSections.MODEL_METADATA, BackupSections.SKILLS,
            BackupSections.WORKFLOWS, BackupSections.BUILTIN_MCP, BackupSections.TERMINAL,
            BackupSections.CONTAINER, BackupSections.REMOTE
        )

        /**
         * 本实现能写进清单的**全部**段 id。与 [BackupSectionCatalog.entries] 必须完全一致，
         * 由 `BackupSectionCatalogTest` 锁住——新增备份段却忘了加进选择目录时，测试会失败。
         */
        @JvmStatic
        val exportableSectionIds: Set<String> = registeredSectionIds + fileSections.map { it.manifestId }

        /**
         * 工作区大小上限：超过则整段跳过并在结果里提示。
         *
         * 历史上是 512MB（用户看到的是「500M」）。曾讨论过「常规 500M + 单独备份工作区 2GB」的双入口方案，
         * 最终定为**统一 2GB 单入口**，理由：
         * 1. 越限是**静默跳过**而非报错——用户主动勾了工作区却没拿到数据、还要自己发现那行提示，
         *    这是最差的失败方式，500M 这道坎制造的问题比它防住的更多；
         * 2. 工作区默认不勾（见 [BackupSectionCatalog]），所以放宽对日常备份的轻量性没有影响，
         *    这道坎只在用户真想要工作区时才触发；
         * 3. 含工作区的备份本来就必然是明文（整包一个 zip），所以不需要第二个入口来隔离加密。
         *
         * 这是**软保护**不是配额：全程流式写盘（加密走 [BackupCrypto.encryptingStream]，不落临时文件），
         * 真正的约束是目标位置的可用空间，写满时由 IOException 报错，不会静默截断。
         */
        internal const val MAX_WORKSPACE_BYTES = 2L * 1024 * 1024 * 1024
    }

    /** 需要整目录搬运的文件段：zip 内前缀 → 宿主目录（相对 filesDir）。 */
    private data class FileSection(
        val id: String,
        val label: String,
        /** zip 内路径前缀（以 / 结尾）。 */
        val zipPrefix: String,
        /** 宿主目录，相对 filesDir。 */
        val hostPath: String,
        /**
         * 该段在清单里登记的 id。多数段与 [id] 相同；技能目录段例外——
         * 它与「技能启停名单」同属一个 [BackupSections.SKILLS] 条目，避免清单里出现两个技能段。
         */
        val manifestId: String = id
    )


    // ═══════════════════════════════════════════════════════
    //  导出
    // ═══════════════════════════════════════════════════════

    /**
     * 把选中的数据打包写入 [out]。调用方负责流的生命周期（SAF 的 openOutputStream）。
     *
     * @param includeSections 要备份的段 id，见 [BackupSectionCatalog]。**传 null = 全部**（保持旧行为）。
     *   文件段按 `manifestId` 匹配，所以勾「技能」会同时带上技能目录，不会出现两个技能段。
     */
    suspend fun export(
        out: OutputStream,
        includeSections: Set<String>? = null,
        password: String? = null
    ): BackupOutcome =
        withContext(Dispatchers.IO) {
        val encrypted = !password.isNullOrBlank()
        try {
            val sections = mutableListOf<BackupSectionInfo>()
            val skipped = mutableListOf<String>()
            // null = 全量；空集 = 一个段都不备份，直接报错而不是产出一个空壳 zip
            fun want(id: String): Boolean = includeSections == null || id in includeSections
            if (includeSections != null && includeSections.isEmpty()) {
                return@withContext BackupOutcome(false, "没有勾选任何备份内容")
            }

            // 加密走「边写边加密」：zip 的字节流经 CipherOutputStream 直接落到目标位置。
            // 旧实现是「先在 cacheDir 落一份完整临时 zip、再整体加密」，上限放宽到 2GB 后
            // 那条路走不通——cacheDir 放不下 2GB，且 IO 放大成 写2G+读2G+写2G。
            val zipSink: OutputStream =
                if (encrypted) BackupCrypto.encryptingStream(out, password!!) else out
            val outcome = ZipOutputStream(BufferedOutputStream(zipSink)).use { zip ->
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.SETTINGS)) {
                    // ── 设置 + API 预设 ──
                    val settings = settingsStore.settingsFlow.first()
                    val presets = settingsStore.apiPresetsFlow.first()
                    val settingsObj = JsonObject().apply {
                        add("settings", gson.toJsonTree(settings))
                        add("apiPresets", gson.toJsonTree(presets))
                    }
                    putText(zip, DATA_PREFIX + FILE_SETTINGS, gson.toJson(settingsObj))
                    sections += BackupSectionInfo(BackupSections.SETTINGS, "设置与 API 预设", presets.size)
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.PROVIDERS)) {
                    // ── 提供商（独立管理的多提供商配置：名称/类型/Key/多Key/代理/模型列表/选中模型…）──
                    val providers = db.aiProviderDao().getAllProvidersOnce()
                    putText(
                        zip, DATA_PREFIX + FILE_PROVIDERS,
                        gson.toJson(JsonObject().apply { add("providers", gson.toJsonTree(providers)) })
                    )
                    sections += BackupSectionInfo(BackupSections.PROVIDERS, "提供商配置", providers.size)
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.CONVERSATIONS)) {
                    // ── 会话 / 消息 / 调用记录 ──
                    // 三张表都是「分块读 → 逐块流式写」：既避开 CursorWindow 的 2MB 窗口，
                    // 也不把全表堆在内存里（消息表可到几万条长文本，攒起来再序列化必 OOM）。
                    val convIds = db.conversationDao().getAllIds()
                    val msgIds = db.messageDao().getAllIds()
                    val callIds = db.llmCallRecordDao().getAllIds()
                    val convAdapter = gson.getAdapter(ConversationEntity::class.java)
                    val msgAdapter = gson.getAdapter(MessageEntity::class.java)
                    val callAdapter = gson.getAdapter(LlmCallRecordEntity::class.java)
                    putJson(zip, DATA_PREFIX + FILE_CONVERSATIONS) { w ->
                        w.beginObject()
                        w.name("conversations")
                        writeJsonArray(w, convIds, convAdapter, "备份导出") { a, b -> db.conversationDao().getAllRange(a, b) }
                        w.name("messages")
                        writeJsonArray(w, msgIds, msgAdapter, "备份导出") { a, b -> db.messageDao().getAllRange(a, b) }
                        w.name("llmCallRecords")
                        writeJsonArray(w, callIds, callAdapter, "备份导出") { a, b -> db.llmCallRecordDao().getAllRange(a, b) }
                        w.endObject()
                    }
                    sections += BackupSectionInfo(
                        BackupSections.CONVERSATIONS, "会话与聊天记录",
                        convIds.size
                    )
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.MCP)) {
                    // ── MCP 配置（原样搬 mcp.json） ──
                    val mcpFile = File(File(appContext.filesDir, "openx"), FILE_MCP)
                    if (mcpFile.isFile) {
                        putText(zip, DATA_PREFIX + FILE_MCP, mcpFile.readText())
                    }
                    sections += BackupSectionInfo(
                        BackupSections.MCP, "MCP 配置",
                        runCatching { parseMcpNames(mcpFile.readText()).size }.getOrDefault(0)
                    )
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.MODEL_METADATA)) {
                    // ── 自定义模型元数据（能力/上下文窗口，用户为每个模型手配）──
                    val modelMetaFile = File(File(appContext.filesDir, com.wy.openx.data.provider.CustomModelMetadataStore.SUBDIR), com.wy.openx.data.provider.CustomModelMetadataStore.FILE_NAME)
                    if (modelMetaFile.isFile) {
                        putText(zip, DATA_PREFIX + FILE_MODEL_METADATA, modelMetaFile.readText())
                    }
                    sections += BackupSectionInfo(
                        BackupSections.MODEL_METADATA, "模型能力与窗口配置",
                        runCatching { JsonParser.parseString(modelMetaFile.readText()).asJsonObject.size() }.getOrDefault(0)
                    )
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.SKILLS)) {
                    // ── 技能启停名单 ──
                    val skillsJson = File(File(appContext.filesDir, "openx"), FILE_SKILLS)
                    if (skillsJson.isFile) {
                        putText(zip, DATA_PREFIX + FILE_SKILLS, skillsJson.readText())
                    }
                    // 技能段同时在文件段里搬运（skills_dir），此处 count 用文件数合并统计
                    val skillFileCount = countFiles(File(appContext.filesDir, "openx/skills"))
                    sections += BackupSectionInfo(BackupSections.SKILLS, "技能", skillFileCount)
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.WORKFLOWS)) {
                    // ── 工作流 ──
                    val workflows = workflowStore.load()
                    putText(
                        zip, DATA_PREFIX + FILE_WORKFLOWS,
                        gson.toJson(JsonObject().apply { add("workflows", gson.toJsonTree(workflows)) })
                    )
                    sections += BackupSectionInfo(BackupSections.WORKFLOWS, "工作流", workflows.size)
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.BUILTIN_MCP)) {
                    // ── 内置 MCP 数据（KV + 文件目录） ──
                    val builtinKv = dumpPrefs(appContext.getSharedPreferences(SP_BUILTIN_MCP, Context.MODE_PRIVATE))
                    putText(zip, DATA_PREFIX + FILE_BUILTIN_MCP, builtinKv)
                    sections += BackupSectionInfo(
                        BackupSections.BUILTIN_MCP, "内置 MCP 数据",
                        runCatching { JsonParser.parseString(builtinKv).asJsonObject.size() }.getOrDefault(0)
                    )
                }
                // 未勾选的段整块跳过：不读数据、不写 zip、不进清单
                if (want(BackupSections.TERMINAL)) {
                    // ── 终端外观 ──
                    putText(
                        zip, DATA_PREFIX + FILE_TERMINAL,
                        dumpPrefs(appContext.getSharedPreferences(SP_TERMINAL, Context.MODE_PRIVATE))
                    )
                    sections += BackupSectionInfo(BackupSections.TERMINAL, "终端外观设置", 1)
                }
                // ── 连接与同步 ──
                if (want(BackupSections.REMOTE)) {
                    val serversText = readPrivateText(FILE_REMOTE_SERVERS)
                    val keysText = readPrivateText(FILE_REMOTE_KEYS)
                    if (serversText == null && keysText == null) {
                        // 从没用过远程功能：不是错误，不占一段
                        skipped += "连接与同步（未使用）"
                    } else {
                        val remoteObj = JsonObject()
                        serversText?.let { text ->
                            runCatching { JsonParser.parseString(text) }.getOrNull()
                                ?.let { remoteObj.add("servers", it) }
                        }
                        keysText?.let { text ->
                            runCatching { JsonParser.parseString(text) }.getOrNull()
                                ?.let { remoteObj.add("keys", it) }
                        }
                        remoteObj.addProperty(
                            "hostKeys",
                            dumpPrefs(appContext.getSharedPreferences(SP_REMOTE_HOST_KEYS, Context.MODE_PRIVATE))
                        )
                        remoteObj.addProperty(
                            "syncSettings",
                            dumpPrefs(appContext.getSharedPreferences(SP_REMOTE_SYNC_SETTINGS, Context.MODE_PRIVATE))
                        )
                        putText(zip, DATA_PREFIX + FILE_REMOTE, gson.toJson(remoteObj))
                        val connCount = remoteObj.getAsJsonObject("servers")
                            ?.getAsJsonArray("connections")?.size() ?: 0
                        sections += BackupSectionInfo(BackupSections.REMOTE, "连接与同步", connCount)
                    }
                }
                // ── 容器配置 ──
                if (want(BackupSections.CONTAINER)) {
                    val snap = containerSettings?.exportSnapshot()
                    if (snap == null || snap.isEmpty) {
                        // 没接入仓库，或用户从没改过任何容器设置（三个 key 都没写过）——不是错误
                        skipped += "容器配置（未设置）"
                    } else {
                        val obj = JsonObject()
                        snap.activeProfileId?.let { obj.addProperty("activeProfile", it) }
                        snap.apkMirrorId?.let { obj.addProperty("apkMirror", it) }
                        // 原始 JSON 字符串整体塞进一个字段，导入时原样写回，避免往返丢字段
                        snap.profilesJson?.let { obj.addProperty("profilesJson", it) }
                        putText(zip, DATA_PREFIX + FILE_CONTAINER, gson.toJson(obj))
                        sections += BackupSectionInfo(BackupSections.CONTAINER, "容器配置", 1)
                    }
                }
                // ── 文件段（记忆/技能/量化/工作区…） ──
                for (section in fileSections) {
                    // 按 manifestId 过滤：技能目录段与「技能启停名单」共用一个条目，勾一个就两个都带
                    if (!want(section.manifestId)) continue
                    val dir = File(appContext.filesDir, section.hostPath)
                    if (!dir.isDirectory) continue
                    // 工作区里常有符号链接，必须排除（不跟随）：
                    // · 指向手机存储（/storage/emulated/0）→ 把整张存储卡打进包（用户实测报「超 2 个 G」）；
                    // · 指向 `..` 的链接 → 直接逃出工作区，把 filesDir 下全部内容（含容器 rootfs，GB 级）拖进来。
                    // 其余段（记忆/技能/量化）也一并跳过链接——它们是纯数据目录，出现链接同样不该跟。
                    val size = if (section.id == BackupSections.WORKSPACE) dirSizeNoFollow(dir).first else 0L
                    if (section.id == BackupSections.WORKSPACE && size > MAX_WORKSPACE_BYTES) {
                        skipped += "工作区文件（${formatSize(size)}，超过上限）"
                        continue
                    }
                    val result = putDir(zip, dir, section.zipPrefix)
                    // 清单 id 与段 id 不同的段（技能目录）已由上面的独立条目登记，跳过避免重复
                    if (section.manifestId != section.id) continue
                    sections += BackupSectionInfo(section.id, section.label, result.files)
                    if (result.skippedLinks > 0) {
                        skipped += "${section.label}中 ${result.skippedLinks} 个符号链接（指向手机存储等外部位置，未打包）"
                    }
                }
                // ── 清单（最后写，此时段信息已齐） ──
                val manifest = BackupManifest(
                    format = BACKUP_FORMAT_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    createdAt = System.currentTimeMillis(),
                    sections = sections
                )
                putText(zip, MANIFEST, gson.toJson(manifest))

                FileLogger.i(TAG, "备份完成: ${sections.joinToString { "${it.id}=${it.count}" }}")
                val msg = buildString {
                    append("已备份 ")
                    append(sections.sumOf { it.count })
                        append(" 项数据")
                    if (skipped.isNotEmpty()) {
                        append("\n已跳过：")
                        append(skipped.joinToString("、"))
                    }
                }
                BackupOutcome(true, msg, manifest, skipped = skipped)
            }
            // CipherOutputStream 的 tag 由 ZipOutputStream.close() 顺着关闭链写出，无需额外收尾
            return@withContext if (encrypted) {
                outcome.copy(message = outcome.message + "\n已用口令加密")
            } else outcome
        } catch (e: Exception) {
            FileLogger.w(TAG, "备份失败: ${e.message}", e)
            BackupOutcome(false, "备份失败：${e.message ?: "未知错误"}")
        }
    }

    // ═══════════════════════════════════════════════════════
    //  读取清单（恢复前预览）
    // ═══════════════════════════════════════════════════════

    /**
     * 恢复前预检：只读清单，**不**解压、不写任何真实数据。
     *
     * 加密包不给口令时返回 [InspectOutcome.passwordRequired]，由 UI 弹口令框，
     * 重新打开 SAF 流后再带口令调一次——流只能顺序读一遍，不能回退。
     */
    suspend fun inspect(input: InputStream, password: String? = null): InspectOutcome =
        withContext(Dispatchers.IO) {
        val tmp = File(appContext.cacheDir, "backup_inspect_${System.currentTimeMillis()}")
        try {
            when (val probe = BackupCrypto.probe(input)) {
                is BackupCrypto.Probe.Plain -> probe.stream.manifestOrError()
                is BackupCrypto.Probe.Encrypted -> {
                    if (password.isNullOrBlank()) return@withContext InspectOutcome(passwordRequired = true)
                    try {
                        BackupCrypto.decryptTo(probe.stream, tmp, password)
                    } catch (e: Exception) {
                        return@withContext InspectOutcome(
                            passwordRequired = true, error = "口令错误或备份文件已损坏"
                        )
                    }
                    tmp.inputStream().buffered().manifestOrError()
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** 把 [readManifest] 的 null 翻译成带原因的 [InspectOutcome]。 */
    private suspend fun InputStream.manifestOrError(): InspectOutcome {
        val m = readManifest(this)
        return if (m == null) InspectOutcome(error = "这不是有效的备份文件（缺少清单）")
        else InspectOutcome(manifest = m)
    }

    /** 只读清单，不解压。文件非法时返回 null。 */
    suspend fun readManifest(input: InputStream): BackupManifest? = withContext(Dispatchers.IO) {
        try {
            ZipInputStream(BufferedInputStream(input)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.name == MANIFEST) {
                        val text = zin.readBytes().toString(Charsets.UTF_8)
                        return@withContext runCatching {
                            gson.fromJson(text, BackupManifest::class.java)
                        }.getOrNull()
                    }
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取备份清单失败: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    //  恢复
    // ═══════════════════════════════════════════════════════

    /**
     * 从 [input] 恢复数据。
     *
     * @param mode 决定现有数据是清空（[RestoreMode.OVERWRITE]）还是保留追加（[RestoreMode.MERGE]）。
     * @param includeSections 想要恢复的段 id 白名单；为 null 表示恢复全部段。
     *   调用方（恢复弹窗）让用户勾选「自定义恢复」后传入，未勾选段一律跳过。
     */
    suspend fun restore(
        input: InputStream,
        mode: RestoreMode,
        includeSections: Set<String>? = null,
        password: String? = null
    ): BackupOutcome = withContext(Dispatchers.IO) {
        val tmp = File(appContext.cacheDir, "backup_restore_${System.currentTimeMillis()}")
        try {
            tmp.mkdirs()
            val probe = BackupCrypto.probe(input)
            val payload: InputStream = when (probe) {
                is BackupCrypto.Probe.Plain -> probe.stream
                is BackupCrypto.Probe.Encrypted -> {
                    if (password.isNullOrBlank()) {
                        return@withContext BackupOutcome(
                            false, "这份备份已加密，请先输入口令", passwordRequired = true
                        )
                    }
                    // GCM 校验失败在这里抛出：口令错或文件被改过
                    val plain = File(tmp, "payload.zip")
                    try {
                        BackupCrypto.decryptTo(probe.stream, plain, password)
                    } catch (e: Exception) {
                        return@withContext BackupOutcome(
                            false, "口令错误或备份文件已损坏", passwordRequired = true
                        )
                    }
                    plain.inputStream().buffered()
                }
            }
            // ── 解包到临时目录（先校验，避免半途污染真实数据） ──
            ZipInputStream(BufferedInputStream(payload)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val rel = sanitizeEntryName(entry.name) ?: continue
                    val target = File(tmp, rel)
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                }
            }

            val manifestFile = File(tmp, MANIFEST)
            if (!manifestFile.isFile) {
                return@withContext BackupOutcome(false, "这不是有效的备份文件（缺少清单）")
            }
            val manifest = runCatching {
                gson.fromJson(manifestFile.readText(), BackupManifest::class.java)
            }.getOrNull() ?: return@withContext BackupOutcome(false, "备份清单解析失败")

            if (manifest.format > BACKUP_FORMAT_VERSION) {
                return@withContext BackupOutcome(
                    false,
                    "备份来自更高版本（格式 v${manifest.format}），当前仅支持 v$BACKUP_FORMAT_VERSION"
                )
            }

            val allPresent = manifest.sections.map { it.id }.toSet()
            val present = if (includeSections == null) allPresent
                else allPresent.filterTo(mutableSetOf()) { it in includeSections }
            if (includeSections != null && present.isEmpty()) {
                return@withContext BackupOutcome(
                    ok = false,
                    message = "未选择任何恢复内容",
                    manifest = manifest
                )
            }
            val restored = mutableListOf<RestoredSection>()
            val overwrite = mode == RestoreMode.OVERWRITE

            // ── 设置 + API 预设 ──
            if (BackupSections.SETTINGS in present) {
                val f = File(tmp, DATA_PREFIX + FILE_SETTINGS)
                if (f.isFile) {
                    val obj = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
                    if (obj != null) {
                        // 逐字段兜底合并：以当前已存设置为底，用备份里有值的字段覆盖——
                        // 旧备份缺失的新字段保留现值（不再回默认）；备份里明确记有的字段用备份值。
                        // （base 取 settingsFlow 现值而非内存 uiState，避免与恢复过程中的内存态竞态。）
                        val currentSettings = settingsStore.settingsFlow.first()
                        val settings = obj.get("settings")
                            ?.let { parseSettingsSafely(gson, it.toString(), currentSettings) }
                        if (settings != null) {
                            if (settings != currentSettings) settingsStore.save(settings)
                            restored += RestoredSection("设置与 API 预设")
                        }
                        val presetsJson = obj.get("apiPresets")?.toString()
                        if (presetsJson != null) {
                            val type = object : com.google.gson.reflect.TypeToken<List<ApiPreset>>() {}.type
                            val presets: List<ApiPreset>? = runCatching {
                                gson.fromJson<List<ApiPreset>>(presetsJson, type)
                            }.getOrNull()
                            if (!presets.isNullOrEmpty()) {
                                settingsStore.saveApiPresets(
                                    if (overwrite) presets else mergePresets(settingsStore.apiPresetsFlow.first(), presets)
                                )
                            }
                        }
                    }
                }
            }

            // ── 提供商配置 ──
            if (BackupSections.PROVIDERS in present) {
                val f = File(tmp, DATA_PREFIX + FILE_PROVIDERS)
                if (f.isFile) {
                    val obj = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
                    val type = object : com.google.gson.reflect.TypeToken<List<AIProviderEntity>>() {}.type
                    val incoming: List<AIProviderEntity> =
                        obj?.get("providers")?.let {
                            runCatching { gson.fromJson<List<AIProviderEntity>>(it.toString(), type) }.getOrNull()
                        } ?: emptyList()
                    if (incoming.isNotEmpty()) {
                        if (overwrite) {
                            db.aiProviderDao().clearAll()
                        }
                        // 合并语义：同 id 覆盖、不同 id 追加（REPLACE 天然幂等，不产生重复）。
                        db.aiProviderDao().insertAllProviders(incoming)
                        restored += RestoredSection("提供商配置", incoming.size, "个")
                    }
                }
            }

            // ── 会话 / 消息 / 调用记录 ──
            if (BackupSections.CONVERSATIONS in present) {
                val f = File(tmp, DATA_PREFIX + FILE_CONVERSATIONS)
                if (f.isFile) {
                    val convAdapter = gson.getAdapter(ConversationEntity::class.java)
                    val msgAdapter = gson.getAdapter(MessageEntity::class.java)
                    val callAdapter = gson.getAdapter(LlmCallRecordEntity::class.java)
                    val convs = mutableListOf<ConversationEntity>()
                    val msgs = mutableListOf<MessageEntity>()
                    val calls = mutableListOf<LlmCallRecordEntity>()
                    // 流式逐条解析，不再 readText() + parseString 整棵树 + toString() 往返：
                    // 旧写法这一段要同时驻留「完整 JSON 串 + 完整 JsonObject 树 + 再序列化出的数组串」，
                    // 几万条长文本的会话备份直接 OOM（真机崩溃点）。
                    f.reader(Charsets.UTF_8).use { reader ->
                        val jr = JsonReader(reader)
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "conversations" -> {
                                    jr.beginArray()
                                    while (jr.hasNext()) {
                                        // 需按字段是否存在判定 titleFinalized，故逐条解析成小对象；单条量级。
                                        val o = JsonParser.parseReader(jr).asJsonObject
                                        // 备份可能来自尚无 titleFinalized 列的旧版本。Gson 反序列化缺失
                                        // 字段得到 false（Kotlin 默认值不会被 Gson 填充），恢复后标题会被
                                        // 重新生成并覆盖。故按字段是否存在判定：缺字段 = 旧备份 → 视为已
                                        // 定稿（与 Room 迁移 17→18 对旧会话默认值 1 一致）；有字段 = 新备份
                                        // → 尊重其真实值（未定稿的仍可让 AI 生成一次）。
                                        runCatching {
                                            val c = gson.fromJson(o, ConversationEntity::class.java)
                                            if (o.has("titleFinalized")) c else c.copy(titleFinalized = true)
                                        }.getOrNull()?.let { convs += it }
                                    }
                                    jr.endArray()
                                }
                                "messages" -> {
                                    jr.beginArray()
                                    val safe = msgAdapter.nullSafe()
                                    while (jr.hasNext()) safe.read(jr)?.let { msgs += it }
                                    jr.endArray()
                                }
                                "llmCallRecords" -> {
                                    jr.beginArray()
                                    val safe = callAdapter.nullSafe()
                                    while (jr.hasNext()) safe.read(jr)?.let { calls += it }
                                    jr.endArray()
                                }
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                    }

                    if (overwrite) {
                        // 覆盖：单事务内「清空 + 写入」，中途失败会整体回滚，不会留下半截数据
                        db.restoreConversations(convs, msgs, calls, clearFirst = true)
                    } else {
                        // 合并：按原 id 做幂等 upsert（REPLACE）——同 id 覆盖、不同 id 追加，
                        // 绝不给会话分配新 id。旧实现给每个会话 ++next 新 id 并追加，
                        // 同一份备份恢复两次就翻倍（10→20）。现语义与 providers 合并一致：
                        // 幂等（重复恢复不产生重复），且新 id 会话（现有表没有的）自然插入。
                        // active 一律置 false：合并后不抢占当前会话，由调用方后续重解析。
                        val mergedConvs = convs.map { it.copy(active = false) }
                        // 消息保留原 id + 原 conversationId：外键无需重映射，REPLACE 幂等。
                        // 调用记录同样保留原 id（REPLACE 天然幂等，不重复计数）。
                        db.restoreConversations(mergedConvs, msgs, calls, clearFirst = false)
                    }
                    restored += RestoredSection("会话与聊天记录", convs.size, "个会话")
                }
            }

            // ── MCP 配置 ──
            if (BackupSections.MCP in present) {
                val f = File(tmp, DATA_PREFIX + FILE_MCP)
                if (f.isFile) {
                    val target = File(File(appContext.filesDir, "openx"), FILE_MCP)
                    val incoming = f.readText()
                    val text = if (overwrite) incoming else mergeMcpJson(
                        target.takeIf { it.isFile }?.readText() ?: "{}", incoming
                    )
                    target.parentFile?.mkdirs()
                    target.writeText(text)
                    restored += RestoredSection("MCP 配置")
                }
            }

            // ── 自定义模型元数据 ──
            if (BackupSections.MODEL_METADATA in present) {
                val f = File(tmp, DATA_PREFIX + FILE_MODEL_METADATA)
                if (f.isFile) {
                    val target = File(
                        File(appContext.filesDir, com.wy.openx.data.provider.CustomModelMetadataStore.SUBDIR),
                        com.wy.openx.data.provider.CustomModelMetadataStore.FILE_NAME
                    )
                    val incoming = f.readText()
                    // 合并：以「提供商ID:模型名」为键，备份里的同名项覆盖现有，其余保留。
                    val text = if (overwrite) incoming else mergeJsonObject(
                        target.takeIf { it.isFile }?.readText() ?: "{}", incoming
                    )
                    target.parentFile?.mkdirs()
                    target.writeText(text)
                    restored += RestoredSection("模型能力与窗口配置")
                }
            }

            // ── 技能启停名单 ──
            if (BackupSections.SKILLS in present) {
                val f = File(tmp, DATA_PREFIX + FILE_SKILLS)
                if (f.isFile) {
                    val target = File(File(appContext.filesDir, "openx"), FILE_SKILLS)
                    val incoming = f.readText()
                    val text = if (overwrite) incoming else mergeSkillsJson(
                        target.takeIf { it.isFile }?.readText() ?: "{}", incoming
                    )
                    target.parentFile?.mkdirs()
                    target.writeText(text)
                }
            }

            // ── 工作流 ──
            if (BackupSections.WORKFLOWS in present) {
                val f = File(tmp, DATA_PREFIX + FILE_WORKFLOWS)
                if (f.isFile) {
                    val obj = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
                    val type = object : com.google.gson.reflect.TypeToken<List<com.wy.openx.data.store.Workflow>>() {}.type
                    val workflows: List<com.wy.openx.data.store.Workflow> =
                        obj?.get("workflows")?.let { runCatching { gson.fromJson<List<com.wy.openx.data.store.Workflow>>(it.toString(), type) }.getOrNull() } ?: emptyList()
                    if (workflows.isNotEmpty()) {
                        // 合并语义：按 name 去重，备份里的同名单项覆盖现有（WorkflowStore.add 即此语义）
                        if (overwrite) {
                            workflowStore.save(workflows)
                        } else {
                            workflows.forEach { workflowStore.add(it) }
                        }
                        restored += RestoredSection("工作流", workflows.size, "个")
                    }
                }
            }

            // ── 内置 MCP 数据 ──
            if (BackupSections.BUILTIN_MCP in present) {
                val f = File(tmp, DATA_PREFIX + FILE_BUILTIN_MCP)
                if (f.isFile) {
                    restorePrefs(
                        appContext.getSharedPreferences(SP_BUILTIN_MCP, Context.MODE_PRIVATE),
                        f.readText(), overwrite
                    )
                    restored += RestoredSection("内置 MCP 数据")
                }
            }

            // ── 终端外观 ──
            if (BackupSections.TERMINAL in present) {
                val f = File(tmp, DATA_PREFIX + FILE_TERMINAL)
                if (f.isFile) {
                    restorePrefs(
                        appContext.getSharedPreferences(SP_TERMINAL, Context.MODE_PRIVATE),
                        f.readText(), overwrite
                    )
                    restored += RestoredSection("终端外观设置")
                }
            }

            // ── 连接与同步 ──
            if (BackupSections.REMOTE in present) {
                val f = File(tmp, DATA_PREFIX + FILE_REMOTE)
                if (f.isFile) {
                    val root = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
                    if (root == null) {
                        FileLogger.w(TAG, "恢复跳过连接与同步：备份内不是合法 JSON")
                    } else {
                        // 文件段一律**按 id 合并**，不看 overwrite：
                        // 连接/挂载/密钥都用 id 标识同一台服务器，整份覆盖会把用户刚加的
                        // 那台机器冲掉；而用备份里的旧条目覆盖现有同名条目，也等于把
                        // 用户改过的端口/路径退回去。两边都不合适，所以只补缺。
                        root.getAsJsonObject("servers")?.let { incoming ->
                            val current = readPrivateJson(FILE_REMOTE_SERVERS)
                            val merged = JsonObject().apply {
                                add("connections", mergeArraysById(current, incoming, "connections"))
                                add("mounts", mergeArraysById(current, incoming, "mounts"))
                            }
                            writePrivateText(FILE_REMOTE_SERVERS, gson.toJson(merged))
                        }
                        root.getAsJsonObject("keys")?.let { incoming ->
                            val merged = JsonObject().apply {
                                add("keys", mergeArraysById(readPrivateJson(FILE_REMOTE_KEYS), incoming, "keys"))
                            }
                            writePrivateText(FILE_REMOTE_KEYS, gson.toJson(merged))
                        }
                        root.get("hostKeys")?.asString?.let {
                            restorePrefs(
                                appContext.getSharedPreferences(SP_REMOTE_HOST_KEYS, Context.MODE_PRIVATE),
                                it, overwrite
                            )
                        }
                        root.get("syncSettings")?.asString?.let {
                            restorePrefs(
                                appContext.getSharedPreferences(SP_REMOTE_SYNC_SETTINGS, Context.MODE_PRIVATE),
                                it, overwrite
                            )
                        }
                        restored += RestoredSection("连接与同步")
                    }
                }
            }

            // ── 容器配置 ──
            if (BackupSections.CONTAINER in present) {
                val f = File(tmp, DATA_PREFIX + FILE_CONTAINER)
                val repo = containerSettings
                if (repo == null) {
                    FileLogger.w(TAG, "恢复跳过容器配置：未接入 ContainerSettingsRepository")
                } else if (f.isFile) {
                    val snap = runCatching {
                        val o = JsonParser.parseString(f.readText()).asJsonObject
                        // 用 Gson 取值：这里的 JsonObject 没有 org.json 的 optString
                        fun str(k: String): String? =
                            o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        com.wy.openx.container.ContainerSettingsSnapshot(
                            activeProfileId = str("activeProfile"),
                            apkMirrorId = str("apkMirror"),
                            profilesJson = str("profilesJson")
                        )
                    }.getOrNull()
                    if (snap == null || snap.isEmpty) {
                        FileLogger.w(TAG, "恢复跳过容器配置：内容为空或无法解析")
                    } else {
                        repo.importSnapshot(snap)
                        restored += RestoredSection("容器配置")
                    }
                }
            }

            // ── 文件段 ──
            for (section in fileSections) {
                // 用清单 id 匹配：技能目录段与「技能启停名单」共用同一个清单条目
                if (section.manifestId !in present) continue
                val src = File(tmp, section.zipPrefix.removeSuffix("/"))
                val dest = File(appContext.filesDir, section.hostPath)
                // 必须用不跟随符号链接的删除：`deleteRecursively()` 会跟随链接逃出目标目录
                // （实测 `up -> ..` / 指向 /storage/emulated/0 的链接曾被跟随，把外部目录内容删光）。
                if (overwrite) deleteRecursivelyNoFollow(dest)
                if (src.isDirectory) {
                    src.copyRecursively(dest, overwrite = true)
                    val n = countFiles(dest)
                    if (n > 0) restored += RestoredSection(section.label, n, "个文件")
                } else if (overwrite) {
                    // 备份里该段为空：覆盖模式下目标已清空，正是期望结果
                    restored += RestoredSection(section.label, cleared = true)
                }
            }

            FileLogger.i(TAG, "恢复完成（${if (overwrite) "覆盖" else "合并"}）: $restored")
            BackupOutcome(
                true,
                if (restored.isEmpty()) "备份中没有可恢复的数据"
                else "已恢复：${restored.joinToString("、") { it.describe() }}",
                manifest,
                restored = restored
            )
        } catch (e: Exception) {
            FileLogger.w(TAG, "恢复失败: ${e.message}", e)
            BackupOutcome(false, "恢复失败：${e.message ?: "未知错误"}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════════

    /**
     * 宽松解析设置：以「当前已存设置」为底，用备份里有值的字段覆盖。
     *
     * 底必须用当前 DataStore 里的设置而非 AppSettings() 默认实例：
     * 恢复新版备份时没问题，但恢复**旧格式备份**时，备份里缺失的新字段（如
     * separateSendButton / reasoningEffort / 通知分组等）若不保留现值、纯回落默认，
     * 一次旧备份恢复就会把用户后续新增的非默认设置全部抹成默认（「恢复后偏好/通知/工具/高级回默认」）。
     * 以现值为底后：备份里有的字段用备份值（备份更可能是用户想恢复的版本），
     * 备份缺失的字段原样保留当前值——只影响备份真正记录过的内容。
     *
     * 不能直接 `gson.fromJson(json, AppSettings::class.java)`：Gson 走 Unsafe 构造，不受
     * Kotlin 空安全约束，旧备份里缺失的字段会变成 null 灌进非空属性，直到被使用才崩。
     */
    /**
     * 合并「连接 / 挂载 / 密钥」这类按 id 索引的数组：取并集，**同名保留已有的**。
     *
     * 与 mcp.json 同一口径——合并的语义是「补上备份里有的」，
     * 不是把用户现有配置回退成备份里的旧版本。
     */
    private fun mergeArraysById(
        existing: JsonObject?,
        incoming: JsonObject?,
        arrayKey: String,
    ): JsonArray {
        val byId = LinkedHashMap<String, JsonElement>()
        existing?.getAsJsonArray(arrayKey)?.forEach { el ->
            el.asJsonObject.get("id")?.asString?.let { byId[it] = el }
        }
        incoming?.getAsJsonArray(arrayKey)?.forEach { el ->
            el.asJsonObject.get("id")?.asString?.let { id ->
                if (!byId.containsKey(id)) byId[id] = el
            }
        }
        return JsonArray().apply { byId.values.forEach { add(it) } }
    }

    /** 读应用私有目录下的一个 JSON 文件（不存在或不是合法 JSON 时返回 null）。 */
    private fun readPrivateJson(name: String): JsonObject? =
        readPrivateText(name)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }

    /** 读应用私有目录下的一个文本文件（不存在或读失败返回 null）。 */
    private fun readPrivateText(name: String): String? =
        File(appContext.filesDir, name).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * 写回应用私有目录下的文本文件。
     *
     * 先写临时文件再改名：直接覆盖时若中途被杀，用户会丢掉**整份**连接配置，
     * 而这不是他能预料到的恢复结果。
     */
    private fun writePrivateText(name: String, text: String) {
        val target = File(appContext.filesDir, name)
        val staging = File(appContext.filesDir, "$name.tmp")
        runCatching {
            staging.writeText(text)
            if (!staging.renameTo(target)) {
                target.writeText(text)
                staging.delete()
            }
        }.onFailure { FileLogger.w(TAG, "写入 $name 失败", it) }
    }

    /** API 预设合并：现有一律保留，备份里的同名项覆盖（备份更可能是用户想恢复的版本）。 */
    private fun mergePresets(existing: List<ApiPreset>, incoming: List<ApiPreset>): List<ApiPreset> {
        val incomingNames = incoming.map { it.name }.toSet()
        return existing.filterNot { it.name in incomingNames } + incoming
    }

    /** 通用 JSON 对象合并：现有键全部保留，备份里的同键覆盖（备份更可能是用户想恢复的版本）。 */
    private fun mergeJsonObject(existing: String, incoming: String): String {
        val ex = runCatching { JsonParser.parseString(existing).asJsonObject }.getOrNull() ?: JsonObject()
        val inc = runCatching { JsonParser.parseString(incoming).asJsonObject }.getOrNull() ?: return existing
        inc.entrySet().forEach { (k, v) -> ex.add(k, v) }
        return gson.toJson(ex)
    }

    /** mcp.json 合并：现有 Server 全部保留，只补充备份里现有没有的名字。 */
    private fun mergeMcpJson(existing: String, incoming: String): String {
        val ex = runCatching { JsonParser.parseString(existing).asJsonObject }.getOrNull() ?: JsonObject()
        val inc = runCatching { JsonParser.parseString(incoming).asJsonObject }.getOrNull() ?: return existing
        val exServers = ex.getAsJsonObject("mcpServers") ?: JsonObject()
        val incServers = inc.getAsJsonObject("mcpServers") ?: JsonObject()
        incServers.entrySet().forEach { (name, cfg) -> if (!exServers.has(name)) exServers.add(name, cfg) }
        ex.add("mcpServers", exServers)
        return gson.toJson(ex)
    }

    /** skills.json（禁用名单）合并：取并集——根 `disabled` 与每个项目 `projects.<键>` 都取并集。 */
    private fun mergeSkillsJson(existing: String, incoming: String): String {
        val ex = runCatching { JsonParser.parseString(existing).asJsonObject }.getOrNull() ?: JsonObject()
        val inc = runCatching { JsonParser.parseString(incoming).asJsonObject }.getOrNull() ?: return existing
        fun unionDisabled(sets: List<JsonArray?>): List<String> {
            val names = linkedSetOf<String>()
            sets.filterNotNull().forEach { arr -> arr.forEach { if (it.isJsonPrimitive) names += it.asString } }
            return names.sorted()
        }
        val disabled = unionDisabled(
            listOf(ex.getAsJsonArray("disabled"), inc.getAsJsonArray("disabled"))
        )
        // 项目级禁用名单：按项目键合并（同名键取并集），两边都保留
        val projectKeys = (ex.getAsJsonObject("projects")?.keySet() ?: emptySet()) union
            (inc.getAsJsonObject("projects")?.keySet() ?: emptySet())
        val projects = JsonObject()
        projectKeys.sorted().forEach { key ->
            val merged = unionDisabled(
                listOf(ex.getAsJsonObject("projects")?.getAsJsonArray(key), inc.getAsJsonObject("projects")?.getAsJsonArray(key))
            )
            val arr = JsonArray()
            merged.forEach { arr.add(it) }
            projects.add(key, arr)
        }
        val out = JsonObject()
        val arr = JsonArray()
        disabled.forEach { arr.add(it) }
        out.add("disabled", arr)
        out.add("projects", projects)
        return gson.toJson(out)
    }

    /** 解析 mcp.json 里的 Server 名字（仅用于统计条目数）。 */
    private fun parseMcpNames(json: String): List<String> {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        return obj.getAsJsonObject("mcpServers")?.keySet()?.toList() ?: emptyList()
    }

    /**
     * SharedPreferences 全量导出为 JSON，**带显式类型标签** `{"key":{"t":"i","v":28}}`。
     *
     * 不能只存裸值：SharedPreferences 的 Int 与 Long 在 XML 里是不同类型，用 `getInt` 读一个
     * 以 Long 写入的值会抛 ClassCastException（如 TerminalSettingsStore 用 getInt 读字号）。
     * JSON 数字本身不区分 Int/Long（Gson 一律解析为 Long），故必须自己记录原类型。
     */
    private fun dumpPrefs(prefs: SharedPreferences): String {
        val obj = JsonObject()
        prefs.all.forEach { (k, v) ->
            val entry = JsonObject()
            when (v) {
                is Boolean -> { entry.addProperty("t", "b"); entry.addProperty("v", v) }
                is Int -> { entry.addProperty("t", "i"); entry.addProperty("v", v) }
                is Long -> { entry.addProperty("t", "l"); entry.addProperty("v", v) }
                is Float -> { entry.addProperty("t", "f"); entry.addProperty("v", v) }
                is String -> { entry.addProperty("t", "s"); entry.addProperty("v", v) }
                is Set<*> -> {
                    entry.addProperty("t", "ss")
                    val arr = JsonArray()
                    v.forEach { arr.add(it?.toString() ?: "") }
                    entry.add("v", arr)
                }
                else -> return@forEach
            }
            obj.add(k, entry)
        }
        return gson.toJson(obj)
    }

    /** 按类型标签写回 SharedPreferences；[overwrite] 为 true 时先清空。 */
    private fun restorePrefs(prefs: SharedPreferences, json: String, overwrite: Boolean) {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val editor = prefs.edit()
        if (overwrite) editor.clear()
        obj.entrySet().forEach { (k, v) ->
            val entry = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val type = entry.get("t")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val value = entry.get("v") ?: return@forEach
            runCatching {
                when (type) {
                    "b" -> editor.putBoolean(k, value.asBoolean)
                    "i" -> editor.putInt(k, value.asInt)
                    "l" -> editor.putLong(k, value.asLong)
                    "f" -> editor.putFloat(k, value.asFloat)
                    "s" -> editor.putString(k, value.asString)
                    "ss" -> editor.putStringSet(k, value.asJsonArray.mapNotNull { it.asString }.toSet())
                }
            }
        }
        editor.apply()
    }

    /** 写入文本条目（UTF-8）。 */
    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * 流式写一个 JSON 段：交给 [write] 往 [JsonWriter] 里写，边写边落到 zip。
     *
     * 不用 `gson.toJson(obj)` 配 [putText]——那要先构造整个 JSON 字符串，会话/消息段可达几十 MB，
     * 叠加同量级的 JsonElement 树与已读出的实体列表，堆直接被撑爆（真机 OOM 就崩在这一步）。
     * 这里内存占用与段大小无关。
     */
    private suspend fun putJson(
        zip: ZipOutputStream,
        name: String,
        write: suspend (JsonWriter) -> Unit,
    ) {
        zip.putNextEntry(ZipEntry(name))
        // 刻意不 close：close 会连带关掉 zip 流。把 JsonWriter（连同其 BufferedWriter）刷出去即可。
        val writer = JsonWriter(BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 32 * 1024))
        try {
            write(writer)
            writer.flush()
        } finally {
            zip.closeEntry()
        }
    }

    /** 分块读一张表，逐条写进 [w] 当前的 JSON 数组里（只占单块内存，不攒全表）。 */
    private suspend fun <E> writeJsonArray(
        w: JsonWriter,
        ids: List<Long>,
        adapter: TypeAdapter<E>,
        label: String,
        fetch: suspend (afterId: Long, beforeId: Long) -> List<E>,
    ) {
        // Gson 没有 toJson(Any, JsonWriter) 重载；TypeAdapter.write 才是逐条流式写（不建树不拼串）。
        val safe = adapter.nullSafe()
        w.beginArray()
        ChunkedReader.forEachChunk(ids, label, fetch) { rows ->
            rows.forEach { safe.write(w, it) }
        }
        w.endArray()
    }

    /** [putDir] 的结果：写入文件数 + 因是符号链接而跳过的条目数。 */
    private data class DirWriteResult(val files: Int, val skippedLinks: Int)

    /**
     * 递归写入目录，返回写入文件数与跳过的链接数。
     *
     * **必须用 [Files.isSymbolicLink] 判断而非 `File.isDirectory`**：后者会跟随符号链接。
     * 实测后果（用真实文件系统验证过）：`up -> ..` 这类链接会让遍历**逃出工作区**，
     * 把 filesDir 下全部内容（含容器 rootfs，GB 级）反复拖进包——这正是「工作区超 2 个 G」的成因。
     * 故一律不跟随链接，链接本身也不写入（还原后目标路径未必存在，写个悬空链接只会添乱）。
     */
    private fun putDir(zip: ZipOutputStream, dir: File, prefix: String): DirWriteResult {
        var files = 0
        var links = 0
        dir.listFiles()?.forEach { f ->
            val path = f.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                links++
                return@forEach
            }
            if (f.isDirectory) {
                val sub = putDir(zip, f, "$prefix${f.name}/")
                files += sub.files
                links += sub.skippedLinks
            } else if (f.isFile) {
                zip.putNextEntry(ZipEntry("$prefix${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
            }
        }
        return DirWriteResult(files, links)
    }

    /** zip 条目名防穿越：拒绝绝对路径与 `..`，其余原样（保留子目录结构）。 */
    private fun sanitizeEntryName(raw: String): String? {
        val cleaned = raw.replace('\\', '/')
        if (cleaned.contains('\u0000')) return null
        if (cleaned.startsWith("/")) return null
        if (cleaned.split("/").any { it == ".." }) return null
        return cleaned.trimStart('/').takeIf { it.isNotEmpty() }
    }

    /** 统计文件数（不跟随符号链接，与 [putDir] 口径一致）。 */
    private fun countFiles(dir: File): Int = dirSizeNoFollow(dir).second

    /**
     * 递归删除目录，**不跟随符号链接**。
     *
     * `File.deleteRecursively()` / `walkTopDown()` 会跟随符号链接：实测 `up -> ..` 或指向
     * `/storage/emulated/0` 的链接会被一路跟随，把目标目录之外的内容（用户外部存储的备份/
     * 源码）一并删掉。这里遇到链接只删链接本身，绝不进入其指向的目录。
     */
    private fun deleteRecursivelyNoFollow(dir: File) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        val roots = ArrayList<File>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            cur.listFiles()?.forEach { f ->
                val path = f.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    // 链接只删自身，不跟随；目标是目录时 delete() 仅移除链接项，不动其内容
                    f.delete()
                    return@forEach
                }
                if (f.isDirectory) stack.addLast(f)
                else f.delete()
            }
            roots.add(cur)
        }
        // 自底向上删除目录本身（跳过已在遍历中被连带清空的）
        roots.asReversed().forEach {
            if (it.isDirectory) it.delete()
        }
    }

    /**
     * 统计目录大小与文件数，**不跟随符号链接**。
     *
     * 不能用 `walkTopDown()`：它跟随符号链接。实测 `up -> ..` 这类链接会让遍历逃出目标目录，
     * 于是「工作区大小」算出来的是整个 filesDir（含容器 rootfs）的体积，而非工作区自身——
     * 用户看到的「超 2 个 G」正是这么来的。
     */
    private fun dirSizeNoFollow(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var files = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            cur.listFiles()?.forEach { f ->
                val path = f.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) return@forEach
                if (f.isDirectory) stack.addLast(f)
                else if (f.isFile) { bytes += f.length(); files++ }
            }
        }
        return bytes to files
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

/**
 * 逐字段兜底合并：以 [base] 为底，用备份 JSON 里有值的字段覆盖。
 * 旧备份缺的新字段保留现值（不再回默认）；备份里明确记有的字段用备份值。
 *
 * 提成顶层 internal 是为了让 `BackupSettingsMergeTest` 能跑**真实**合并逻辑——
 * 之前它是 private 成员，测试只能自己抄一份，抄错也不会有人发现。
 */
internal fun parseSettingsSafely(gson: Gson, json: String, base: AppSettings): AppSettings? = runCatching {
    val merged = gson.toJsonTree(base).asJsonObject
    val incoming = JsonParser.parseString(json).asJsonObject
    incoming.entrySet().forEach { (k, v) ->
        if (v != null && !v.isJsonNull) merged.add(k, v)
    }
    gson.fromJson(merged, AppSettings::class.java)
}.getOrNull()

/**
 * 算出「备份范围」勾选框最终该勾哪些。
 *
 * ## 为什么要「已见过」这个概念
 * 默认勾选集 [defaults] 只在**从没保存过选择**时整体生效。但用户一旦保存过一次，
 * 后续版本新增的段就不在这个已保存的列表里了——若原样沿用，那些段会**静默保持未勾**：
 * 用户勾着一堆段做备份、以为覆盖完整，实际漏掉的正是他从没见过的段。
 * （和「工作区超上限静默跳过」是同一类失败：看起来成功、实际没覆盖。）
 *
 * 但也不能每次读都并入 [defaults]——那用户手动取消勾选的段会在下次启动被勾回来，
 * 选择器退化成只读。所以用 [rawAck] 区分两种情况：
 * - 段在 [rawAck] 里 = 用户已经见过并有机会改过 → 之后完全听用户的，不再自动干预；
 * - 段不在 [rawAck] 里 = 用户还没见过（升级新增）→ 按默认值并入**一次**。
 *
 * 并入后的结果会随下一次保存一起落盘，之后 [rawAck] 也把它记上，于是只发生一次。
 *
 * @param rawSaved 已保存的勾选 JSON（空白 = 从没保存过）
 * @param rawAck 已展示给用户过的段 id JSON
 * @param known 当前目录里的全部段 id（用于剔除旧版本遗留的未知段）
 * @param defaults 默认勾选集
 */
internal fun resolveBackupSectionSelection(
    rawSaved: String?,
    rawAck: String?,
    known: Set<String>,
    defaults: Set<String>
): Set<String> {
    fun parse(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            Gson().fromJson<List<String>>(
                raw, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            )
        }.getOrNull()
    }
    // 从没保存过 → 整体用默认；否则沿用已保存的，但剔掉旧版本遗留的未知段，
    // 否则会出现「勾了但打不进包」的幽灵勾选。
    val saved = parse(rawSaved)?.toSet()?.filter { it in known }?.toSet() ?: defaults
    val ack = parse(rawAck)?.toSet() ?: emptySet()
    return saved + (defaults - ack)
}
