package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.PluginJsStdlib
import com.wy.openx.quickjs.QuickJsEngine
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JS 工具包管理器：在 QuickJS 里 eval 脚本，把脚本声明的 `$ptools` 转成 [AgentTool]。
 *
 * ## 包名
 * 包名以**第一个工具名第一个下划线之前的部分**为基名（不是调用方传进来的名字）；
 * 基名被占用时（两个插件首工具同前缀，如 `get_weather` / `get_time` 都推成 `get`）追加 `#2`、`#3`…，
 * 保证 [packages] 的 key 唯一。
 *
 * ⚠ 调用方**不要自己按工具名反推包名**：冲突时反推只能得到基名、拿不到 `#N` 后缀，
 * `unload` 就找不到条目 → 插件禁用后工具撤不下来。真实包名由 [load] 随
 * [LoadedPackage.pkgName] 返回，存好、原样传回 [unload]。
 *
 * ## 线程
 * QuickJS 是**单线程**运行时。本类把所有引擎访问与 [packages] 读写都压在 [engineLock] 上串行 ——
 * 否则 AI 正在执行插件工具时用户切插件开关（`reload`），两个线程会同时进引擎。
 * 锁是**每实例**的：插件界面用的是各自的 `QuickJsEngine`，不会与本实例互相等待。
 */
class JsPackageManager(private val hostBridgeScript: String? = null) : Closeable {

    private val engine = QuickJsEngine()
    private val gson = Gson()

    /** 单线程 QuickJS 的互斥：同一时刻只允许一个线程碰引擎 / 包表。 */
    private val engineLock = Any()

    private val packages = mutableMapOf<String, PkgInfo>()
    private var bridgeBound = false
    /**
     * 注入宿主桥：把 [bridges] 对象的方法暴露为 `NativeInterface.__call(...)`，
     * 再 eval [hostBridgeScript]（它把 `__call` 包成好用的 `__openx.xxx()`）。
     *
     * 幂等：重复调用只换绑 native 实例，不重复 eval 桥脚本。
     */
    fun bindHostBridge(bridge: Any, script: String? = hostBridgeScript) {
        synchronized(engineLock) {
            ensureInit()
            engine.bindNativeInterface(bridge)
            if (!bridgeBound && !script.isNullOrBlank()) {
                // 桥函数也得登记：VM 重建后它们是空的，所有 __openx.* 调用会变成 undefined。
                engine.installBootstrap(script, "host_bridge.js")
                bridgeBound = true
            }
        }
    }

    private data class PkgInfo(
        val name: String,
        val tools: List<ToolMeta>,
        val handlerNames: List<String>
    )

    data class ToolMeta(
        val name: String,
        val description: String,
        val parameters: Map<String, Any>
    )

    /** [load] 的结果：本次实际占用的包名 + 工具元数据。 */
    data class LoadedPackage(
        /** 真实包名（可能带 `#N` 后缀），卸载时原样传给 [unload]。 */
        val pkgName: String,
        val tools: List<ToolMeta>,
        /** 本插件实现了哪些钩子（七期）。 */
        val hooks: List<String> = emptyList(),
        /** operit 兼容包 registerNavigationEntry 收集到的侧边栏入口（仅 compat 轨道非空）。 */
        val navEntries: List<Map<String, Any>> = emptyList()
    )

    private val bootstrapJs: String by lazy {
        "var \$ptools = [];\n" +
        "var \$phandlers = {};\n" +
        "var \$phooks = [];\n" +
        "var \$pendingHooks = {};\n" +
        "var \$hookHandlers = {};\n" +
        "function \$loadPkg(src) {\n" +
        "    eval(src);\n" +
        "    var list = typeof \$ptools !== 'undefined' && Array.isArray(\$ptools) ? \$ptools : [];\n" +
        "    var out = [];\n" +
        "    for (var i = 0; i < list.length; i++) {\n" +
        "        var t = list[i];\n" +
        "        if (typeof t.name !== 'string' || typeof t.handler !== 'function') continue;\n" +
        "        \$phandlers[t.name] = t.handler;\n" +
        "        out.push({\n" +
        "            name: t.name,\n" +
        "            desc: t.description || '',\n" +
        "            params: t.parameters || {type:'object',properties:{}\n" +
        "        }});\n" +
        "    }\n" +
        "    \$ptools = [];\n" +
        "    \$pendingHooks = {};\n" +
        "    var hookNames = [];\n" +
        "    var hlist = typeof \$phooks !== 'undefined' && Array.isArray(\$phooks) ? \$phooks : [];\n" +
        "    for (var j = 0; j < hlist.length; j++) {\n" +
        "        var h = hlist[j];\n" +
        "        if (!h || typeof h.name !== 'string' || typeof h.handler !== 'function') continue;\n" +
        "        \$pendingHooks[h.name] = h.handler;\n" +
        "        hookNames.push(h.name);\n" +
        "    }\n" +
        "    \$phooks = [];\n" +
        "    // operit 兼容入口把 registerNavigationEntry 收集到这个全局；\n" +
        "    // 原生插件不碰它，恒为空数组。\n" +
        "    var nav = [];\n" +
        "    try {\n" +
        "        var nv = typeof globalThis.__openxNavEntries !== 'undefined' ? globalThis.__openxNavEntries : null;\n" +
        "        if (nv && Array.isArray(nv)) {\n" +
        "            for (var k = 0; k < nv.length; k++) {\n" +
        "                var e = nv[k];\n" +
        "                if (e && typeof e === 'object') nav.push(e);\n" +
        "            }\n" +
        "        }\n" +
        "    } catch (ignore) {}\n" +
        "    globalThis.__openxNavEntries = [];\n" +
        "    return JSON.stringify({tools: out, hooks: hookNames, nav: nav});\n" +
        "}\n" +
        "function \$bindHooks(pkg) {\n" +
        "    \$hookHandlers[pkg] = \$pendingHooks;\n" +
        "    \$pendingHooks = {};\n" +
        "    return 'ok';\n" +
        "}\n" +
        "function \$callHook(pkg, hook, argsJson) {\n" +
        "    try {\n" +
        "        var set = \$hookHandlers[pkg];\n" +
        "        if (!set) return '';\n" +
        "        var fn = set[hook];\n" +
        "        if (typeof fn !== 'function') return '';\n" +
        "        var r = fn(JSON.parse(argsJson || '{}'));\n" +
        "        if (r === undefined || r === null) return '';\n" +
        "        return typeof r === 'string' ? r : JSON.stringify(r);\n" +
        "    } catch(e) {\n" +
        "        return JSON.stringify({__hookError: String(e && e.message ? e.message : e)});\n" +
        "    }\n" +
        "}\n" +
        "function \$unloadHooks(pkg) {\n" +
        "    delete \$hookHandlers[pkg];\n" +
        "    return 'ok';\n" +
        "}\n" +
        "function \$callTool(name, argsJson) {\n" +
        "    try {\n" +
        "        var fn = \$phandlers[name];\n" +
        "        if (typeof fn !== 'function') return JSON.stringify({e:'handler not found: '+name});\n" +
        "        var args = JSON.parse(argsJson || '{}');\n" +
        "        var r = fn(args);\n" +
        "        return typeof r === 'string' ? r : JSON.stringify(r);\n" +
        "    } catch(e) {\n" +
        "        return JSON.stringify({e:e.message});\n" +
        "    }\n" +
        "}\n" +
        "function \$unloadByIds(idsJson) {\n" +
        "    var ids = JSON.parse(idsJson || '[]');\n" +
        "    for (var i = 0; i < ids.length; i++) delete \$phandlers[ids[i]];\n" +
        "    return 'ok';\n" +
        "}\n"
    }

    private var initialized = false

    private fun ensureInit() {
        synchronized(engineLock) {
            if (initialized) return
            // 标准库补丁先于bootstrap：插件脚本会直接用 Intl，bootstrap 自身不依赖它。
            // 用 installBootstrap 而不是 evaluate：这两段脚本要活到 VM 结束，
            // VM 因中断污染被重建时必须跟着重放，否则所有插件一起失效。
            engine.installBootstrap(PluginJsStdlib.SCRIPT, "openx_stdlib.js")
            engine.installBootstrap(bootstrapJs, "bootstrap.js")
            initialized = true
        }
    }

    fun load(source: String): Result<LoadedPackage> {
        return try {
            val loaded = synchronized(engineLock) {
                ensureInit()
                val result = engine.callFunction<String>("\$loadPkg", gson.toJson(listOf(source)))
                    ?: return Result.failure(Exception("QuickJS returned null"))

                // 返回体现在是 {tools:[...], hooks:[...]}，出错时是 {e:"..."}。
                // 两者都以 { 开头，所以不能靠首字符判断，只能看有没有 e 键。
                val root = runCatching { JsonParser.parseString(result).asJsonObject }
                    .getOrElse { return Result.failure(Exception("无法解析 load 返回：${result.take(200)}")) }

                root.get("e")?.takeIf { !it.isJsonNull }?.let {
                    return Result.failure(Exception(it.asString))
                }

                val toolsArr = root.getAsJsonArray("tools")
                    ?: return Result.failure(Exception("load 返回缺少 tools 字段"))
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val raw: List<Map<String, Any>> = gson.fromJson(toolsArr, type)
                val tools = raw.map {
                    ToolMeta(
                        name = it["name"]?.toString() ?: "unknown",
                        description = it["desc"]?.toString() ?: "",
                        parameters = it["params"] as? Map<String, Any>
                            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                    )
                }
                val hooks = root.getAsJsonArray("hooks")
                    ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                    .orEmpty()

                val navEntries: List<Map<String, Any>> = root.getAsJsonArray("nav")
                    ?.mapNotNull { el ->
                        runCatching {
                            gson.fromJson<Map<String, Any>>(el, object : TypeToken<Map<String, Any>>() {}.type)
                        }.getOrNull()
                    }
                    .orEmpty()

                val base = tools.firstOrNull()?.name?.substringBefore("_") ?: "js"
                val pkgName = allocatePackageName(base, packages.keys)
                packages[pkgName] = PkgInfo(pkgName, tools, tools.map { it.name })
                // 钩子函数已在 JS 侧暂存到 $pendingHooks，这里认领到真实包名下。
                // 必须在 pkgName 定下来之后，否则 `base#2` 这种冲突包会绑错。
                engine.callFunction<String>("\$bindHooks", gson.toJson(listOf(pkgName)))
                // 登记重放脚本：VM 重建后靠它恢复工具与钩子。
                // 走 $loadPkg + $bindHooks 而不是只重放 source —— 钩子是要认领到具体包名下的，
                // 只 eval source 会填 $pendingHooks 但没人 bind，钩子照样不生效。
                engine.registerBootstrap(
                    "\$loadPkg(${gson.toJson(source)});\$bindHooks(${gson.toJson(pkgName)});",
                    "<replay:$pkgName>"
                )
                LoadedPackage(pkgName, tools, hooks, navEntries)
            }
            Result.success(loaded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调用插件注册的钩子（七期）。返回插件函数的原始返回值，空串表示"不干预"。
     *
     * 引擎是单线程的，这里的 engineLock 会让多个钩子"看起来"串行——这反而是对的：
     * QuickJS 本来就无法并发执行。
     */
    /**
     * 钩子专用执行线程。
     *
     * 不复用 engine 自带的 runtime 线程，是因为需要自己掌握超时：
     * `engine.callFunction` 内部的看门狗是 **60 秒**（给工具脚本的，它们可能跑得久），
     * 而钩子全在聊天主链路上——卡 60 秒和卡死没区别，用户只会觉得 App 卡了。
     */
    private val hookExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-hook").apply { isDaemon = true }
    }

    /** 钩子超时时长。参考 Operit 的默认值（10 秒）。 */
    val hookTimeoutMs: Long = 10_000L

    /**
     * 调用插件钩子，带超时。超时会被中断并返回 [HOOK_TIMEOUT]，由调用方忽略这个钩子。
     *
     * 为什么超时后必须 `engine.interrupt()`：钩子可能写在 `while(true){}` 里，
     * 光靠 Future.cancel 只能中断等待、停不下正在跑的 JS。QuickJS 自带的中断句柄
     * 能让脚本在下一个字节码边界退出。
     */
    fun callHook(pkgName: String, hook: String, argsJson: String): String? {
        val task = hookExecutor.submit<String?> {
            synchronized(engineLock) {
                runCatching {
                    engine.callFunction<String>(
                        "\$callHook",
                        gson.toJson(listOf(pkgName, hook, argsJson))
                    )
                }.getOrNull()
            }
        }
        return try {
            task.get(hookTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 先中断 JS（让它从死循环里退出），再放弃这个任务。
            // 中断会把引擎的 VM 标记为已污染：旧版 .so 的脚本入口不清中断标志，
            // 这个 VM 之后每个脚本都会被立刻中断，所以引擎会在下次执行前重建 VM 并重放插件脚本。
            val r = runCatching { engine.interrupt() }
            FileLogger.w(
                "PluginHook",
                "钩子 $pkgName/$hook 超时(${hookTimeoutMs}ms)，中断调用" +
                    if (r.isSuccess) "成功" else "失败：${r.exceptionOrNull()}"
            )
            task.cancel(true)
            HOOK_TIMEOUT
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** 钩子超时的哨兵返回值。插件不可能自己返回这个字符串，所以不会误判。 */
        const val HOOK_TIMEOUT = "__openx_hook_timeout__"
    }

    fun createTool(toolName: String): AgentTool? {
        return synchronized(engineLock) {
            val entry = packages.values.find { toolName in it.handlerNames } ?: return null
            val meta = entry.tools.find { it.name == toolName } ?: return null
            JsToolAgent(toolName, meta)
        }
    }

    fun unload(pkgName: String) {
        synchronized(engineLock) {
            val entry = packages.remove(pkgName) ?: return
            // 原实现把 pkgName 直接拼进 JS 源码（注入面），且用 indexOf 前缀匹配 ——
            // unload("foo") 会连带删掉 foobar / myfoo 的 handler（别的包的工具静默失效）。
            // 现在改为把 id 列表当**参数**传进去（callFunction 的 argsJson），源码是固定的，
            // 逐个精确删除。
            engine.callFunction<String>("\$unloadByIds", unloadHandlerArgsJson(entry.handlerNames))
            // 钩子表也要摘，否则重新加载同名插件时旧包的钩子还挂着。
            engine.callFunction<String>("\$unloadHooks", gson.toJson(listOf(pkgName)))
            // 重放登记也要摘，否则 VM 重建时已卸载的插件会“复活”。
            engine.unregisterBootstrap("<replay:$pkgName>")
        }
    }

    fun registerWith(registry: ToolRegistry) {
        synchronized(engineLock) {
            for ((_, pkg) in packages) {
                for (meta in pkg.tools) {
                    val tool = createTool(meta.name)
                    if (tool != null) registry.register(tool)
                }
            }
        }
    }

    override fun close() {
        // 先停钩子线程，避免它还在等一个已经关掉的引擎
        runCatching { hookExecutor.shutdownNow() }
        synchronized(engineLock) { engine.close() }
    }

    private inner class JsToolAgent(
        private val toolName: String,
        private val meta: ToolMeta
    ) : AgentTool {
        override val definition = ToolDef(
            function = FunctionDef(
                name = meta.name,
                description = meta.description,
                parameters = meta.parameters
            )
        )

        override suspend fun execute(args: Map<String, Any>): String {
            val argsJson = gson.toJson(args)
            // QuickJS 单线程：reload()/unload() 可能在另一线程并发进引擎，这里必须持锁。
            val result = synchronized(this@JsPackageManager.engineLock) {
                val raw = this@JsPackageManager.engine.callFunction<String>("\$callTool", gson.toJson(listOf(toolName, argsJson)))
                settleCompatAwait(raw)
            }
            if (result == null) return "Error: JS tool returned null"
            if (result.startsWith("{")) {
                try {
                    val obj = gson.fromJson(result, Map::class.java)
                    if (obj.containsKey("e")) return "Error: ${obj["e"]}"
                } catch (_: Exception) {}
            }
            return result
        }

        /**
         * Operit 兼容插件的工具是 `async function`，返回 Promise。
         *
         * 兼容层把未结算的 Promise 登记成句柄、返回 `{"__compatAwait": id}`；
         * `callFunction` 返回后引擎已经 `executePendingJobs()` 跑过微任务，所以这里
         * 再问一次就能拿到结果。兼容层的宿主能力全是同步的，一轮就够；留几轮余量
         * 给多层 Promise 链。
         */
        private fun settleCompatAwait(raw: String?): String? {
            var cur = raw ?: return null
            repeat(4) {
                val id = compatAwaitId(cur) ?: return cur
                cur = this@JsPackageManager.engine.callFunction<String>(
                    "__openxSettleCompatAwait",
                    gson.toJson(listOf(id))
                ) ?: return null
            }
            return cur
        }

        private fun compatAwaitId(raw: String): String? {
            if (!raw.startsWith("{") || !raw.contains("__compatAwait")) return null
            return runCatching {
                val obj = gson.fromJson(raw, Map::class.java)
                obj["__compatAwait"]?.toString()
            }.getOrNull()
        }
    }
}

/**
 * 给包名基名分配一个**未被占用**的包名（[load] 用，抽出来便于单测）。
 *
 * ⚠ 冲突时**不能直接覆盖** [packages] 里同名的条目 —— 那会把前一个包整个顶掉：
 * 前一个插件的工具因此全部注册不上（管理页却仍显示加载成功），
 * 而且它卸载时会按基名删掉**别人**那条。
 *
 * @param base 基名（第一个工具名第一个下划线之前的部分；无下划线则为原串）
 * @param taken 已被占用的包名
 * @return 未被占用的包名：无冲突时就是 [base]，冲突时依次试 `base#2`、`base#3`…
 */
internal fun allocatePackageName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var n = 2
    while ("$base#$n" in taken) n++
    return "$base#$n"
}

/**
 * 组装 `$unloadByIds` 的实参 JSON（[JsPackageManager.unload] 用，抽出来便于单测）。
 *
 * ⚠ `callFunction` 的 argsJson 是**实参列表**，不是要传给函数的那个值本身：
 * 传 `["a","b"]` 会被拆成两个位置实参 `a`、`b`，而 `$unloadByIds(idsJson)` 只收一个
 * 字符串参数 —— 于是它拿到 `"a"`，`JSON.parse("a")` 抛 `unexpected token: 'a'`，
 * JS 侧 handler 删不掉（日志 `[PluginRuntime] JS 侧卸载 xx 失败`）。
 *
 * 正确形状：先序列化成文本，再包一层 → `["[\"a\",\"b\"]"]`。
 */
internal fun unloadHandlerArgsJson(handlerNames: List<String>): String =
    Gson().toJson(listOf(Gson().toJson(handlerNames)))
