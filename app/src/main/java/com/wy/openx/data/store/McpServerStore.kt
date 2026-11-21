package com.wy.openx.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import com.wy.openx.container.Workspace
import java.io.File

/**
 * MCP Server 配置：远程 HTTP（url）或本地进程（command），见 [isStdio]/
 * [isHttp] 推断。兼容 aicode-main 的 McpServerConfig 字段。
 *
 * @param description 功能说明（安装时从预设/对话框带入，供管理页展示「这个 Server 是干嘛的」）
 * @param url 远程 Streamable HTTP 端点（HTTP 传输用）
 * @param headers 附加请求头（HTTP 传输用）
 * @param command 本地进程启动命令（stdio 传输用；"builtin" 为内置 QuickJS 服务）
 * @param disabledTools 被禁用的工具名集合
 */
data class McpServerConfig(
    val name: String,
    val description: String = "",
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet()
) {
    val isBuiltin: Boolean get() = command == "builtin"
    val isHttp: Boolean get() = !url.isNullOrBlank()
    val isStdio: Boolean get() = !command.isNullOrBlank() && !isBuiltin

    /** 已知 Server 的功能说明（内置 + 常见社区预设），供历史数据补齐介绍。 */
    companion object {
        private val KNOWN_DESCRIPTIONS: Map<String, String> = mapOf(
            "mcp-filesystem" to "文件系统操作：在 App 工作区内读写/删除文件、遍历目录、查看文件信息",
            "mcp-fetch" to "网页抓取：发起 HTTP 请求（GET/POST/PUT/DELETE），获取网页或接口内容",
            "mcp-memory" to "持久化记忆：基于文件的 key-value 存储，跨会话保存与读取数据",
            "mcp-puppeteer" to "浏览器自动化：截图、点击、表单填写、JS 执行",
            "mcp-playwright" to "Playwright 浏览器自动化：截图、点击、表单、JS 执行",
            "mcp-sqlite" to "SQLite 数据库：查询、创建表、插入数据",
            "mcp-postgres" to "PostgreSQL 数据库：查询、建表、数据 CRUD",
            "mcp-redis" to "Redis 缓存：键值读写、过期管理、发布订阅",
            "mcp-github" to "GitHub API：Issue/PR/代码搜索/仓库管理",
            "mcp-notion" to "Notion API：页面创建/更新、数据库查询、块操作",
            "mcp-jira" to "Jira 项目管理：Issue 操作、Sprint 管理、搜索",
            "mcp-slack" to "Slack 集成：发送消息、频道管理、用户查询",
            "mcp-google-maps" to "Google Maps API：地理编码、地点搜索、路线规划",
            "mcp-brave-search" to "Brave 搜索引擎 API 调用",
            "mcp-sequential-thinking" to "思维链推理：复杂问题的逐步逻辑推导和决策分析",
            "mcp-time" to "时间和时区工具：获取当前时间、时区转换、日期计算",
            "mcp-docker" to "Docker 容器管理：列出/启动/停止容器、镜像管理",
            "mcp-kubernetes" to "Kubernetes 集群管理：Pod/Service/Deployment 操作",
            "mcp-aws-s3" to "AWS S3 存储：上传/下载/列出/删除 S3 对象",
            "mcp-sentry" to "Sentry 错误监控：Issue 查询、事件详情、性能追踪",
            "mcp-cloudflare" to "Cloudflare API：DNS 管理、Worker 部署、KV 存储",
            "mcp-everything" to "Everything 测试服务器：MCP 协议全功能测试/示例",
            "mcp-python-exec" to "Python 代码执行沙箱（需安装 mcp 包）"
        )

        /** 按名字取已知说明；未知名字返回空串。 */
        fun knownDescription(name: String): String = KNOWN_DESCRIPTIONS[name.lowercase()] ?: ""
    }
}

/** MCP Server 内可调用的单个工具（测试连接时由 tools/list 得到）。 */
data class McpToolInfo(val name: String, val description: String)

/** MCP Server 连接测试结果，供管理页展示「连没连上、有哪些工具」。 */
sealed interface McpTestResult {
    /** 连接成功。[runtime] 为实际运行位置（"容器"/"宿主"/"内置"/"HTTP"）。 */
    data class Ok(val tools: List<McpToolInfo>, val runtime: String) : McpTestResult
    /** 连接失败，[message] 为可展示的原因。 */
    data class Fail(val message: String) : McpTestResult
}

/** MCP server 的配置作用域：全局（跨项目共享）或项目级（仅当前工作区生效）。 */
enum class McpScope { GLOBAL, PROJECT }

/** 一个生效的 MCP 条目：配置 + 其来源作用域，供管理页标注「全局/项目」。 */
data class McpServerEntry(val config: McpServerConfig, val scope: McpScope)

/**
 * MCP Server 仓库：两级配置，均为 aicode 同款 `{"mcpServers":{name:{...}}}` 格式
 * （可由文件浏览查看/编辑、容器内 AI 读写）：
 * - 全局：`<filesDir>/openx/mcp.json`（跨项目、跨升级保留）；
 * - 项目级：`<filesDir>/workspace/.aicode/mcp.json`（随工作区走，容器内对应
 *   `/root/workspace/.aicode/mcp.json`，AI 可直接读写）。
 *
 * 生效配置 = 全局 + 项目合并，**项目级优先**，同名时项目项覆盖全局项；条目带来源
 * 作用域供 UI 标注。内置服务（builtin）只属于全局，不落盘，读取时自动合并（仅改
 * 启停状态时写回覆盖）；旧 SharedPreferences（mcp_servers）数据迁移到全局文件。
 */
class McpServerStore(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    companion object {
        private const val TAG = "McpServerStore"
        private const val CONFIG_FILE = "mcp.json"
        private const val AICODE_DIR = ".aicode"
        private const val LEGACY_SP_NAME = "mcp_servers"

        val BUILTIN_SERVERS = listOf(
            McpServerConfig("mcp-filesystem", description = McpServerConfig.knownDescription("mcp-filesystem"), command = "builtin", enabled = true),
            McpServerConfig("mcp-fetch", description = McpServerConfig.knownDescription("mcp-fetch"), command = "builtin", enabled = true),
            McpServerConfig("mcp-memory", description = McpServerConfig.knownDescription("mcp-memory"), command = "builtin", enabled = true)
        )
    }

    /** 全局配置文件：`filesDir/openx/mcp.json`。 */
    private val globalFile: File
        get() = File(File(appContext.filesDir, "openx"), CONFIG_FILE)

    /** 项目级配置文件：`filesDir/workspace/.aicode/mcp.json`（容器内 `/root/workspace/.aicode/mcp.json`）。 */
    private val projectFile: File
        get() = File(File(Workspace.root(appContext), AICODE_DIR), CONFIG_FILE)

    private val legacySp = appContext.getSharedPreferences(LEGACY_SP_NAME, Context.MODE_PRIVATE)

    /**
     * 生效条目 = 全局（含内置预设、描述补齐）+ 项目合并，项目优先覆盖同名；
     * 顺序 = 全局序 + 项目新增项。供管理页列表与作用域标注使用。
     */
    fun loadEntries(): List<McpServerEntry> {
        migrateLegacyIfNeeded()
        val byName = LinkedHashMap<String, McpServerEntry>()
        loadGlobal().forEach { byName[it.name] = McpServerEntry(it, McpScope.GLOBAL) }
        loadProject().forEach { byName[it.name] = McpServerEntry(it, McpScope.PROJECT) }
        return byName.values.toList()
    }

    /** 生效配置（合并后，命中项目覆盖），供运行时（AI 调用 mcp 工具、测试连接）使用。 */
    fun load(): List<McpServerConfig> = loadEntries().map { it.config }

    /** 全局服务（含内置预设与描述补齐）。 */
    fun getGlobalServers(): List<McpServerConfig> = loadGlobal()

    /** 项目级服务（含描述补齐）。 */
    fun getProjectServers(): List<McpServerConfig> = loadProject()

    /** 新增/覆盖服务到指定作用域并落盘（内置服务仅改启停状态时写回覆盖，其它情况不落盘）。 */
    fun add(config: McpServerConfig, scope: McpScope) {
        synchronized(this) {
            val file = fileFor(scope)
            val list = readFile(file).toMutableList()
            list.removeAll { it.name == config.name }
            list.add(config)
            writeFile(file, serialize(list))
        }
    }

    /** 从指定作用域删除服务（内置服务本就不落盘，删除为幂等空操作，下次加载仍会合并回来）。 */
    fun delete(name: String, scope: McpScope) {
        synchronized(this) {
            val file = fileFor(scope)
            writeFile(file, serialize(readFile(file).filterNot { it.name == name }))
        }
    }

    /** 切换指定作用域下某服务的启停状态并落盘。 */
    fun setEnabled(name: String, scope: McpScope, enabled: Boolean) {
        synchronized(this) {
            val file = fileFor(scope)
            writeFile(file, serialize(readFile(file).map { if (it.name == name) it.copy(enabled = enabled) else it }))
        }
    }

    /**
     * 编辑替换：把 (oldName, oldScope) 的旧条目更新为 [config]，支持改名 / 改作用域
     * （从旧作用域文件移除旧名，再把新条目写入新作用域文件；同名同作用域则就地覆盖）。
     */
    fun update(oldName: String, oldScope: McpScope, config: McpServerConfig, newScope: McpScope) {
        synchronized(this) {
            if (oldScope != newScope || oldName != config.name) {
                val oldFile = fileFor(oldScope)
                writeFile(oldFile, serialize(readFile(oldFile).filterNot { it.name == oldName }))
            }
            val newFile = fileFor(newScope)
            val list = readFile(newFile).toMutableList()
            list.removeAll { it.name == config.name }
            list.add(config)
            writeFile(newFile, serialize(list))
        }
    }

    // ── mcp.json 读写（aicode 同款格式） ──

    /** 全局服务 = 用户配置 + 内置预设（未覆盖名字时）+ 描述补齐。 */
    private fun loadGlobal(): List<McpServerConfig> {
        val stored = readFile(globalFile)
        val storedNames = stored.map { it.name }.toSet()
        val builtins = BUILTIN_SERVERS.filter { !storedNames.contains(it.name) }
        return backfillDescription(stored + builtins)
    }

    /** 项目级服务（含描述补齐，便于管理页展示）。 */
    private fun loadProject(): List<McpServerConfig> = backfillDescription(readFile(projectFile))

    /** 历史数据（新增 description 字段之前配置的）按名字补齐已知介绍，
     *  否则老用户看到的所有 Server 都没有功能说明。 */
    private fun backfillDescription(servers: List<McpServerConfig>): List<McpServerConfig> =
        servers.map { server ->
            if (server.description.isBlank()) {
                server.copy(description = McpServerConfig.knownDescription(server.name))
            } else server
        }

    private fun fileFor(scope: McpScope): File = when (scope) {
        McpScope.GLOBAL -> globalFile
        McpScope.PROJECT -> projectFile
    }

    private fun readFile(file: File): List<McpServerConfig> {
        if (!file.isFile) return emptyList()
        return parse(runCatching { file.readText() }.getOrElse {
            FileLogger.w(TAG, "读取 MCP 配置 ${file.name} 失败: ${it.message}")
            "{}"
        })
    }

    private fun writeFile(file: File, json: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        }.onFailure {
            FileLogger.w(TAG, "写入 MCP 配置 ${file.name} 失败: ${it.message}", it)
        }
    }

    /** 序列化为 {"mcpServers":{name:{...}}}：stdio 存 command/args/env，HTTP 存 url/headers。 */
    private fun serialize(servers: List<McpServerConfig>): String {
        val serversObj = JsonObject()
        servers.forEach { server ->
            val obj = JsonObject()
            if (server.description.isNotBlank()) obj.addProperty("description", server.description)
            if (server.isHttp) {
                obj.addProperty("url", server.url)
                if (server.headers.isNotEmpty()) {
                    val h = JsonObject()
                    server.headers.forEach { (k, v) -> h.addProperty(k, v) }
                    obj.add("headers", h)
                }
            } else {
                obj.addProperty("command", server.command ?: "")
                if (server.args.isNotEmpty()) {
                    val a = JsonArray()
                    server.args.forEach { a.add(it) }
                    obj.add("args", a)
                }
                if (server.env.isNotEmpty()) {
                    val e = JsonObject()
                    server.env.forEach { (k, v) -> e.addProperty(k, v) }
                    obj.add("env", e)
                }
            }
            obj.addProperty("enabled", server.enabled)
            if (server.disabledTools.isNotEmpty()) {
                val d = JsonArray()
                server.disabledTools.sorted().forEach { d.add(it) }
                obj.add("disabledTools", d)
            }
            serversObj.add(server.name, obj)
        }
        val root = JsonObject()
        root.add("mcpServers", serversObj)
        return gson.toJson(root)
    }

    private fun parse(raw: String): List<McpServerConfig> {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            FileLogger.w(TAG, "MCP 配置 JSON 解析失败: ${it.message}")
            return emptyList()
        }
        val servers = root.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()

        return servers.entrySet().mapNotNull { (name, element) ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            val command = obj.get("command")?.takeIf { it.isJsonPrimitive }?.asString
            val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            val description = obj.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val args = obj.get("args")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
            val env = obj.get("env")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }?.toMap()
                ?: emptyMap()
            val headers = obj.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }?.toMap()
                ?: emptyMap()
            val disabledTools = obj.get("disabledTools")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }?.toSet() ?: emptySet()

            when {
                !command.isNullOrBlank() -> McpServerConfig(
                    name = name, description = description, command = command, args = args, env = env,
                    enabled = enabled, disabledTools = disabledTools
                )
                !url.isNullOrBlank() -> McpServerConfig(
                    name = name, description = description, url = url, headers = headers,
                    enabled = enabled, disabledTools = disabledTools
                )
                else -> {
                    FileLogger.i(TAG, "忽略无法识别的 MCP server（缺 url/command）: $name")
                    null
                }
            }
        }
    }

    /** 首次使用把旧 agent_memory 数据迁移为记忆文件，成功后清掉旧表（幂等）。 */
    private fun migrateLegacyIfNeeded() {
        val raw = legacySp.getString("servers", "[]") ?: "[]"
        if (raw == "[]" || raw.isBlank()) {
            legacySp.edit().remove("servers").apply()
            return
        }
        runCatching {
            parseLegacyServers(raw)
                .filterNot { it.isBuiltin || it.name.isBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { writeFile(globalFile, serialize(it)) }
            // 必须无条件清除旧表：否则只要有一处解析/落盘抛错，就会在每次启动重跑并反复告警
            legacySp.edit().remove("servers").apply()
            FileLogger.i(TAG, "已迁移旧 SharedPreferences MCP 配置到 ${globalFile.absolutePath}")
        }.onFailure {
            FileLogger.w(TAG, "迁移旧 MCP 配置失败: ${it.message}", it)
        }
    }

    /**
     * 解析旧 SharedPreferences 里的 MCP 配置 JSON。
     *
     * 不能直接 `gson.fromJson(..., List<McpServerConfig>)`：Gson 不受 Kotlin 空安全约束，
     * 历史数据中缺失或为 null 的字段会变成 null 灌进非空属性，随后在
     * `it.name.isBlank()` 处抛 "Parameter specified as non-null is null" 使整个迁移失败。
     * 故逐字段做空值兜底。
     */
    private fun parseLegacyServers(raw: String): List<McpServerConfig> {
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.strField("name")
            if (name.isBlank()) return@mapNotNull null
            val url = obj.strField("url").takeIf { it.isNotBlank() }
            val command = obj.strField("command").takeIf { it.isNotBlank() }
            if (url == null && command == null) return@mapNotNull null
            McpServerConfig(
                name = name,
                description = obj.strField("description"),
                url = url,
                headers = obj.strMapField("headers"),
                command = command,
                args = obj.strListField("args"),
                env = obj.strMapField("env"),
                enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            )
        }
    }

    /** 读取字符串字段，缺失/为 null/非字符串时返回空串。 */
    private fun com.google.gson.JsonObject.strField(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    /** 读取字符串数组字段，非法项跳过。 */
    private fun com.google.gson.JsonObject.strListField(name: String): List<String> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()

    /** 读取字符串映射字段，非法项跳过。 */
    private fun com.google.gson.JsonObject.strMapField(name: String): Map<String, String> =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
            ?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }?.toMap()
            ?: emptyMap()
}