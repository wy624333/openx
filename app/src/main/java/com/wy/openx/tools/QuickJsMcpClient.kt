package com.wy.openx.tools

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wy.openx.data.store.McpServerConfig
import com.wy.openx.data.store.McpTestResult
import com.wy.openx.data.store.McpToolInfo
import com.wy.openx.quickjs.QuickJsEngine

class QuickJsMcpClient {

    private val engine = QuickJsEngine()
    val bridge = McpBridgeManager()
    val http = McpHttpClient()
    private var initialized = false
    private var builtinInitialized = false
    private val gson = Gson()

    private val mcpJs = """
function mcpBuildReq(method,paramsJson,id) {
    var p=paramsJson?JSON.parse(paramsJson):{};
    return JSON.stringify({jsonrpc:"2.0",id:id||1,method:method,params:p});
}
function mcpParseResp(rawJson) {
    try {
        var obj=JSON.parse(rawJson);
        if (obj.error) return JSON.stringify({e:(obj.error.message||JSON.stringify(obj.error))});
        return JSON.stringify({r:obj.result});
    } catch(e) {
        return JSON.stringify({e:"ParseError: "+String(rawJson).substring(0,500)});
    }
}
function mcpFormatList(name,rawJson) {
    try {
        var p=JSON.parse(rawJson), r=p.result;
        if (p.e) return "Error: "+p.e;
        if (!r||!r.tools||!r.tools.length) return "Server "+name+" has no tools";
        var o="";
        for (var i=0;i<r.tools.length;i++)
            o+="  "+r.tools[i].name+"\n"+(r.tools[i].description?"    "+r.tools[i].description+"\n":"");
        return o;
    } catch(e) { return "ParseError: "+e.message; }
}
function mcpFormatCall(rawJson) {
    try {
        var p=JSON.parse(rawJson), r=p.result;
        if (p.e) return "Error: "+p.e;
        if (r&&r.content) {
            var out=[];
            for (var i=0;i<r.content.length;i++) {
                var c=r.content[i]; out.push(c.text||c.data||JSON.stringify(c));
            }
            return out.join("\n");
        }
        return JSON.stringify(r||rawJson);
    } catch(e) { return "ParseError: "+e.message; }
}
""".trimIndent()

    private val builtinServerJs = """
(function(){
var servers={};
function reg(name,tools){ servers[name]=tools; }
function handle(server,method,paramsJson){
    var s=servers[server];
    if(!s) return JSON.stringify({error:{message:"Built-in server not found: "+server}});
    try {
        if(method==="tools/list"){
            var list=[];
            for(var i=0;i<s.length;i++){
                list.push({name:s[i].n,description:s[i].d,inputSchema:s[i].s||{}});
            }
            return JSON.stringify({result:{tools:list}});
        }
        if(method==="tools/call"){
            var p=JSON.parse(paramsJson);
            var tool=null;
            for(var i=0;i<s.length;i++){ if(s[i].n===p.name){ tool=s[i]; break; } }
            if(!tool) return JSON.stringify({error:{message:"Tool not found: "+p.name}});
            var r=tool.h(p.arguments||{});
            if(r&&r.error) return JSON.stringify({error:{message:r.error}});
            return JSON.stringify({result:{content:[{type:"text",text:(typeof r==="string"?r:JSON.stringify(r))}]}});
        }
        return JSON.stringify({error:{message:"Method not found: "+method}});
    } catch(e){ return JSON.stringify({error:{message:e.message}}); }
}
function builtinList(){ var a=[]; for(var k in servers) a.push(k); return JSON.stringify(a); }
function builtinHandle(server,method,params){ return handle(server,method,params); }
function builtinHasServer(name){ return servers[name]?1:0; }

__mcpReg=reg; __mcpBuiltinList=builtinList; __mcpHandle=builtinHandle; __mcpHas=builtinHasServer;

__mcpReg("mcp-filesystem",[
    {n:"read_file",d:"Read file content from app workspace",s:{type:"object",properties:{path:{type:"string"}},required:["path"]},
        h:function(a){ var p=a.path||""; var r=NativeInterface.__call("mcpReadFile",JSON.stringify([p])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"write_file",d:"Write content to a file in app workspace",s:{type:"object",properties:{path:{type:"string"},content:{type:"string"}},required:["path","content"]},
        h:function(a){ var r=NativeInterface.__call("mcpWriteFile",JSON.stringify([a.path||"",a.content||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"list_directory",d:"List files and directories",s:{type:"object",properties:{path:{type:"string"}},required:[]},
        h:function(a){ var r=NativeInterface.__call("mcpListDir",JSON.stringify([a.path||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"file_info",d:"Get file or directory info",s:{type:"object",properties:{path:{type:"string"}},required:["path"]},
        h:function(a){ var r=NativeInterface.__call("mcpFileInfo",JSON.stringify([a.path||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"delete_file",d:"Delete a file or directory",s:{type:"object",properties:{path:{type:"string"}},required:["path"]},
        h:function(a){ var r=NativeInterface.__call("mcpDeleteFile",JSON.stringify([a.path||""])); return JSON.parse(r||'{"error":"Null"}'); }}
]);

__mcpReg("mcp-fetch",[
    {n:"fetch",d:"Make HTTP request (GET/POST/PUT/DELETE)",s:{type:"object",properties:{url:{type:"string"},method:{type:"string"},headers:{type:"object"},body:{type:"string"}},required:["url"]},
        h:function(a){
            var opts={};
            if(a.method) opts.method=a.method;
            if(a.headers) opts.headers=a.headers;
            if(a.body) opts.body=a.body;
            var r=NativeInterface.__call("mcpFetch",JSON.stringify([a.url||"",JSON.stringify(opts)]));
            return JSON.parse(r||'{"error":"Null"}');
        }}
]);

__mcpReg("mcp-memory",[
    {n:"get_value",d:"Get a stored value by key",s:{type:"object",properties:{key:{type:"string"}},required:["key"]},
        h:function(a){ var r=NativeInterface.__call("mcpGetValue",JSON.stringify([a.key||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"set_value",d:"Store a value by key",s:{type:"object",properties:{key:{type:"string"},value:{type:"string"}},required:["key","value"]},
        h:function(a){ var r=NativeInterface.__call("mcpSetValue",JSON.stringify([a.key||"",a.value||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"delete_value",d:"Delete a stored value",s:{type:"object",properties:{key:{type:"string"}},required:["key"]},
        h:function(a){ var r=NativeInterface.__call("mcpDeleteValue",JSON.stringify([a.key||""])); return JSON.parse(r||'{"error":"Null"}'); }},
    {n:"list_values",d:"List all stored keys and values",s:{type:"object",properties:{},required:[]},
        h:function(){ var r=NativeInterface.__call("mcpListValues",null); return JSON.parse(r||'{"error":"Null"}'); }}
]);
})();
""".trimIndent()

    companion object {
        val BUILTIN_SERVERS = listOf("mcp-filesystem", "mcp-fetch", "mcp-memory")

        /**
         * 全 App 共享实例。UI「测试连接」与 AI 的 mcp 工具必须共用同一个 client，
         * 否则二者各持一套进程会话：UI 测出的连接状态与 AI 实际使用的进程不是同一个，
         * 状态显示会失真。
         */
        val shared: QuickJsMcpClient by lazy { QuickJsMcpClient() }
    }

    fun init() {
        if (initialized) return
        // 登记而非单次执行：VM 因超时中断被重建时要跟着重放，否则 MCP 脚本整体消失。
        engine.installBootstrap(mcpJs, "mcp_client.js")
        initialized = true
    }

    fun initBuiltins(context: Context) {
        init()
        if (builtinInitialized) return
        engine.bindNativeInterface(BuiltinMcpHost(context))
        engine.installBootstrap(builtinServerJs, "builtin_servers.js")
        builtinInitialized = true
    }

    fun isBuiltin(name: String): Boolean = name in BUILTIN_SERVERS

    fun getBuiltinServers(): List<McpServerConfig> = BUILTIN_SERVERS.map { name ->
        McpServerConfig(name = name, command = "builtin", args = emptyList(), enabled = true)
    }

    fun getBuiltinToolList(): String {
        init()
        val raw = engine.callFunction<String>("__mcpBuiltinList", "[]", "mcp:builtinList")
            ?: return "[]"
        return raw
    }

    fun listTools(config: McpServerConfig): String {
        init()
        val raw: String
        if (isBuiltin(config.name)) {
            if (!builtinInitialized) return "Error: Built-in MCP servers not initialized"
            raw = engine.callFunction<String>(
                "__mcpHandle", gson.toJson(listOf(config.name, "tools/list", emptyMap<String, Any>())),
                "mcp:builtin:listTools"
            ) ?: return "Error: Built-in MCP handler returned null"
        } else if (config.isHttp) {
            raw = http.listTools(config)
        } else {
            raw = bridge.listTools(config)
        }
        return callFormatList(config.name, raw)
    }

    fun callTool(config: McpServerConfig, tool: String, args: Map<String, Any>, timeout: Int): String {
        init()
        val raw: String
        if (isBuiltin(config.name)) {
            if (!builtinInitialized) return "Error: Built-in MCP servers not initialized"
            val paramsJson = gson.toJson(mapOf("name" to tool, "arguments" to args))
            raw = engine.callFunction<String>(
                "__mcpHandle", gson.toJson(listOf(config.name, "tools/call", paramsJson)),
                "mcp:builtin:callTool"
            ) ?: return "Error: Built-in MCP handler returned null"
        } else if (config.isHttp) {
            raw = http.callTool(config, tool, args)
        } else {
            raw = bridge.callTool(config, tool, args, timeout)
        }
        return callFormatCall(raw)
    }

    fun parseMcpResponse(rawJson: String): McpBridgeManager.McpResult {
        init()
        val argsJson = gson.toJson(listOf(rawJson))
        val resultJson = engine.callFunction<String>("mcpParseResp", argsJson)
        return if (resultJson != null) {
            try {
                val obj = JsonParser.parseString(resultJson).asJsonObject
                if (obj.has("e")) McpBridgeManager.McpResult.Error(obj.get("e").asString)
                else {
                    // "r" 字段可能缺失或为原始类型，做类型守卫避免抛异常
                    val r = obj.get("r")
                    when {
                        r == null || r.isJsonNull -> McpBridgeManager.McpResult.Error("MCP 响应缺少 result 字段")
                        r.isJsonObject -> McpBridgeManager.McpResult.Success(r.asJsonObject)
                        else -> McpBridgeManager.McpResult.Success(com.google.gson.JsonParser.parseString(
                            """{"content":[{"type":"text","text":${gson.toJson(r.toString())}}]}"""
                        ).asJsonObject)
                    }
                }
            } catch (e: Exception) {
                McpBridgeManager.McpResult.Error("MCP 响应解析失败: ${e.message ?: "unknown"} | ${resultJson.take(300)}")
            }
        } else {
            McpBridgeManager.McpResult.Error("QuickJS MCP client returned null")
        }
    }

    fun buildMcpRequest(method: String, params: Map<String, Any>, id: Int = 1): String {
        init()
        val paramsJson = gson.toJson(params)
        val argsJson = gson.toJson(listOf(method, paramsJson, id))
        return engine.callFunction<String>("mcpBuildReq", argsJson) ?: "{}"
    }

    private fun callFormatList(name: String, raw: String): String {
        return try {
            engine.callFunction<String>("mcpFormatList", gson.toJson(listOf(name, raw)))
                ?: formatMcpListFallback(name, raw)
        } catch (e: Exception) {
            formatMcpListFallback(name, raw)
        }
    }

    private fun callFormatCall(raw: String): String {
        return try {
            engine.callFunction<String>("mcpFormatCall", gson.toJson(listOf(raw)))
                ?: formatMcpCallFallback(raw)
        } catch (e: Exception) {
            formatMcpCallFallback(raw)
        }
    }

    fun cleanup(name: String) { bridge.cleanup(name); http.close(name) }

    /**
     * 标记 [name] 正在被界面占用（如管理页测试连接），空闲回收时跳过它。
     * 防止用户还在看连接状态时，后台回收把会话进程杀掉导致状态失真。
     */
    fun claim(name: String) { bridge.claim(name) }

    /** 解除 [name] 的界面占用，恢复常规空闲回收。 */
    fun release(name: String) { bridge.release(name) }


    fun cleanupAll() { bridge.cleanupAll(); http.closeAll(); engine.close() }
    fun isAlive(name: String) = when {
        isBuiltin(name) -> true
        else -> bridge.isAlive(name)
    }

    /** stdio server 的实际运行位置（"容器"/"宿主"）；未连接时为 null。 */
    fun runtimeOf(name: String): String? = bridge.runtimeOf(name)

    /** stdio server 最近一次启动失败的诊断信息；无失败则为 null。 */
    fun lastStartHint(name: String): String? = bridge.lastStartHint(name)

    /**
     * 测试连接：调用 tools/list 验证能否真正握手，并返回工具清单供 UI 展示。
     * 与 AI 调用共用同一套 client/session，因此结果反映的就是真实连接状态。
     */
    fun testConnection(config: McpServerConfig): McpTestResult {
        return try {
            init()
            val raw = when {
                isBuiltin(config.name) -> {
                    if (!builtinInitialized) initBuiltins(AppContextHolder.context ?: return McpTestResult.Fail("应用上下文未初始化"))
                    engine.callFunction<String>(
                        "__mcpHandle", gson.toJson(listOf(config.name, "tools/list", emptyMap<String, Any>())),
                        "mcp:test:builtin"
                    ) ?: return McpTestResult.Fail("内置 Server 无响应")
                }
                config.isHttp -> http.listTools(config)
                else -> bridge.listTools(config)
            }
            val tools = parseToolList(raw)
            if (tools == null) {
                McpTestResult.Fail(extractErrorMessage(raw))
            } else {
                val runtime = when {
                    isBuiltin(config.name) -> "内置"
                    config.isHttp -> "HTTP"
                    else -> bridge.runtimeOf(config.name) ?: "宿主"
                }
                McpTestResult.Ok(tools, runtime)
            }
        } catch (e: Exception) {
            val hint = lastStartHint(config.name) ?: e.message ?: "未知错误"
            McpTestResult.Fail(hint)
        }
    }

    /** 解析 tools/list 原始响应为工具列表；响应含 error 或格式不符时返回 null。 */
    private fun parseToolList(raw: String): List<McpToolInfo>? {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val err = obj.get("error")
        if (err != null && !err.isJsonNull) return null
        val tools = obj.getAsJsonObject("result")?.getAsJsonArray("tools") ?: return null
        return tools.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val n = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val d = o.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            McpToolInfo(n, d)
        }
    }

    /** 从错误响应里抽出可展示的 message；抽不到就截断原文。 */
    private fun extractErrorMessage(raw: String): String {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        val err = obj?.get("error")
        if (err != null && !err.isJsonNull) {
            return when {
                err.isJsonObject -> err.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString ?: raw.take(300)
                err.isJsonPrimitive -> err.asString
                else -> raw.take(300)
            }
        }
        return raw.take(300).ifBlank { "对方未返回有效响应" }
    }

    private fun formatMcpListFallback(name: String, raw: String): String {
        return bridge.parseMcpResponse(raw).let {
            if (it is McpBridgeManager.McpResult.Error) "Error: ${it.message}"
            else {
                val result = (it as McpBridgeManager.McpResult.Success).json
                val tools = result.getAsJsonArray("tools")
                if (tools == null || tools.isEmpty()) "Server $name has no tools"
                else buildString {
                    tools.forEach { t ->
                        appendLine("  ${t.asJsonObject.get("name")?.asString ?: "?"}")
                        t.asJsonObject.get("description")?.asString?.let { d ->
                            if (d.isNotBlank()) appendLine("    $d")
                        }
                    }
                }
            }
        }
    }

    private fun formatMcpCallFallback(raw: String): String {
        return bridge.parseMcpResponse(raw).let {
            if (it is McpBridgeManager.McpResult.Error) "Error: ${it.message}"
            else {
                val content = (it as McpBridgeManager.McpResult.Success).json.getAsJsonArray("content")
                if (content != null) content.map { el ->
                    val c = el.asJsonObject
                    c.get("text")?.asString ?: c.get("data")?.asString ?: c.toString()
                }.joinToString("\n")
                else "OK"
            }
        }
    }
}
