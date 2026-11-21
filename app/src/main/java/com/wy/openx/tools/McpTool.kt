package com.wy.openx.tools

import com.wy.openx.data.store.McpServerConfig
import com.wy.openx.data.store.McpServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpTool : AgentTool {

    override val definition = ToolDef(
        function = FunctionDef(
            name = "mcp",
            description = """调用 MCP (Model Context Protocol) 工具。MCP 是一种让 AI 通过标准化协议调用外部工具的框架。
支持的操作：
- list_servers: 列出已配置的 MCP Server
- list_tools: 列出指定 Server 可用的工具
- call_tool: 调用指定 Server 的某个工具
- add_server: 添加新的 MCP Server 配置（远程 HTTP 用 url，本地进程用 command）
- remove_server: 删除 MCP Server 配置

使用前需先配置 MCP Server（名称 + 远程 url 或本地启动命令）。""",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf(
                        "type" to "string",
                        "enum" to listOf("list_servers", "list_tools", "call_tool", "add_server", "remove_server"),
                        "description" to "操作类型"
                    ),
                    "server" to mapOf("type" to "string", "description" to "MCP Server 名称"),
                    "tool" to mapOf("type" to "string", "description" to "要调用的工具名称（call_tool 时需要）"),
                    "args" to mapOf(
                        "type" to "object",
                        "description" to "调用工具的参数（call_tool 时需要）",
                        "default" to emptyMap<String, Any>()
                    ),
                    "url" to mapOf("type" to "string", "description" to "远程 MCP Server 的 HTTP 端点（add_server 时与 command 二选一）"),
                    "headers" to mapOf(
                        "type" to "object",
                        "description" to "HTTP 请求头（add_server 远程时可选，如鉴权头）",
                        "default" to emptyMap<String, Any>()
                    ),
                    "command" to mapOf("type" to "string", "description" to "Server 启动命令（add_server 时与 url 二选一）"),
                    "description" to mapOf("type" to "string", "description" to "该 Server 的功能说明（add_server 建议填写，便于用户在管理页识别用途）"),
                    "serverArgs" to mapOf(
                        "type" to "string",
                        "description" to "Server 启动参数（空格分隔，add_server 可选）",
                        "default" to ""
                    ),
                    "timeout" to mapOf("type" to "integer", "description" to "调用超时秒数（默认 60）", "default" to 60)
                ),
                "required" to listOf("action")
            )
        )
    )

    private val mcpClient = QuickJsMcpClient.shared

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"
        val store = McpServerStore(ctx)
        mcpClient.initBuiltins(ctx)
        val action = args["action"] as? String ?: return@withContext "错误：未指定操作"

        when (action) {
            "list_servers" -> listServers(store)
            "list_tools" -> {
                val serverName = args["server"] as? String ?: return@withContext "错误：未指定 server"
                val config = store.load().find { it.name == serverName && it.enabled }
                    ?: return@withContext "错误：未找到已启用的 Server「$serverName」"
                mcpClient.listTools(config)
            }
            "call_tool" -> {
                val serverName = args["server"] as? String ?: return@withContext "错误：未指定 server"
                val toolName = args["tool"] as? String ?: return@withContext "错误：未指定 tool"
                @Suppress("UNCHECKED_CAST")
                val toolArgs = args["args"] as? Map<String, Any> ?: emptyMap()
                val timeout = ((args["timeout"] as? Double)?.toInt())?.coerceIn(1, 300) ?: 60
                val config = store.load().find { it.name == serverName && it.enabled }
                    ?: return@withContext "错误：未找到已启用的 Server「$serverName」"
                mcpClient.callTool(config, toolName, toolArgs, timeout)
            }
            "add_server" -> {
                val name = (args["server"] as? String)?.trim() ?: return@withContext "错误：未提供 server 名称"
                val url = (args["url"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                @Suppress("UNCHECKED_CAST")
                val headers = (args["headers"] as? Map<String, Any>)?.entries
                    ?.associate { it.key to it.value.toString() } ?: emptyMap()
                val command = (args["command"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val serverArgs = ((args["serverArgs"] as? String)?.trim() ?: "").split("\\s+".toRegex()).filter { it.isNotBlank() }
                // 未显式提供说明时，用已知预设介绍兜底
                val description = (args["description"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    ?: McpServerConfig.knownDescription(name)

                val config = when {
                    url != null -> McpServerConfig(name = name, description = description, url = url, headers = headers)
                    command != null -> McpServerConfig(name = name, description = description, command = command, args = serverArgs)
                    else -> return@withContext "错误：需提供 url（远程）或 command（本地进程）"
                }
                store.add(config, com.wy.openx.data.store.McpScope.PROJECT)
                if (url != null) "✅ 已添加 MCP Server「$name」: $url"
                else "✅ 已添加 MCP Server「$name」: $command ${serverArgs.joinToString(" ")}"
            }
            "remove_server" -> {
                val name = (args["server"] as? String)?.trim() ?: return@withContext "错误：未提供 server 名称"
                // 优先按 AI 自己可写的作用域删：项目级存在则删项目，否则删全局
                val scope = if (store.getProjectServers().any { it.name == name }) {
                    com.wy.openx.data.store.McpScope.PROJECT
                } else {
                    com.wy.openx.data.store.McpScope.GLOBAL
                }
                store.delete(name, scope)
                mcpClient.cleanup(name)
                "✅ 已删除 MCP Server「$name」"
            }
            else -> "错误：不支持的操作「$action」"
        }
    }

    private fun listServers(store: McpServerStore): String {
        val servers = store.load()
        if (servers.isEmpty()) return "未配置 MCP Server。可使用 add_server 添加。"
        return servers.joinToString("\n") { s ->
            val status = if (s.enabled) "🟢" else "🔴"
            val alive = if (s.enabled && mcpClient.isAlive(s.name)) " (已连接)" else ""
            val info = when {
                s.isBuiltin -> "内置服务器（无需外部依赖）"
                s.isHttp -> "HTTP: ${s.url}"
                else -> {
                    val where = mcpClient.runtimeOf(s.name)?.let { " · $it" } ?: ""
                    "stdio: ${s.command} ${s.args.joinToString(" ")}$where"
                }
            }
            // 曾经启动失败过就带上真实原因（例如宿主无 python3/npx），避免 AI 反复无谓重试
            val hint = mcpClient.lastStartHint(s.name)?.let { "\n    ⚠ $it" } ?: ""
            val desc = s.description.takeIf { it.isNotBlank() }?.let { "\n    $it" } ?: ""
            "$status ${s.name}: $info$alive$desc$hint"
        }
    }
}
