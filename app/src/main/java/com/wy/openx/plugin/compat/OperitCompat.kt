package com.wy.openx.plugin.compat

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.PluginManifest
import java.io.File
import java.util.Locale

/**
 * Operit ToolPkg → OpenX 插件 的兼容适配。
 *
 * ## 思路
 * **不改动对方一个字节**，也不动 OpenX 的装载链路：导入时按 `manifest.json` 识别出
 * operit 包，映射成一份 `plugin.json`（带 `"compat": "operit"` 标记），原样铺开它的
 * 目录结构；装载时由 [buildEntrySource] 现场生成入口脚本 —— 注入
 * [OperitCompatRuntime.SCRIPT]，再把子包里 METADATA 声明的工具逐个注册进 `$ptools`。
 *
 * 入口脚本选择**装载时生成**而不是导入时落盘：兼容层的改进能立刻作用于已安装的插件，
 * 不必让用户重新导入一遍。
 *
 * ## operit 的两套声明
 *  - `manifest.json`：包级元信息 + 子包列表（`subpackages[].entry`）
 *  - 子包文件头的 METADATA 注释块：该子包对外暴露的工具清单
 *
 * 所以「哪些工具存在」只能靠扫文件头，不能靠 manifest。
 */
object OperitCompat {

    const val MANIFEST_FILE = "manifest.json"
    const val COMPAT_TAG = "operit"

    /** 匹配子包文件头的 METADATA 注释块。 */
    private val METADATA_REGEX = Regex("""/\*\s*METADATA\s*(\{[\s\S]*?\})\s*\*/""")

    /** METADATA 里兼容层已识别的键；其余只进诊断报告，不影响装载。 */
    private val METADATA_KNOWN_KEYS = setOf(
        "name", "display_name", "description", "enabled_by_default", "enabledByDefault", "tools"
    )

    private val gson = Gson()

    private val TRUE_TOKENS = setOf("true", "1", "yes", "on")
    private val FALSE_TOKENS = setOf("false", "0", "no", "off")

    /**
     * 子包元信息。名字与描述的**权威来源是子包文件头的 METADATA**（官方 manifest 的
     * 子包项根本不消费这些键），[enabledByDefault] 为 null 表示两处都没声明。
     */
    data class OperitSubpackage(
        val id: String,
        val entry: String,
        val displayName: String = "",
        val description: String = "",
        val enabledByDefault: Boolean? = null
    )

    /** 子包文件头 METADATA 的包级部分（工具列表另由 [parseMetadata] 给出）。 */
    data class OperitSubpackageMeta(
        val displayName: String = "",
        val description: String = "",
        val enabledByDefault: Boolean? = null,
        /** METADATA 里出现但兼容层不认的键，仅供诊断报告列举。 */
        val unknownKeys: List<String> = emptyList()
    )

    data class OperitTool(
        val name: String,
        val description: String,
        /** 已转换成 OpenX 期望的 JSON Schema。 */
        val schema: JsonObject
    )

    data class OperitManifest(
        val toolpkgId: String,
        val version: String,
        val apiVersion: String,
        val main: String,
        val displayName: String,
        val description: String,
        val subpackages: List<OperitSubpackage>,
        /** 包级默认启用态；缺失按官方语义取 true。 */
        val enabledByDefault: Boolean = true,
        /** `resources` 声明项（key → 相对路径），用于校验与诊断。 */
        val resources: List<OperitResource> = emptyList(),
        val workflowTemplates: Int = 0,
        val workspaceTemplates: Int = 0
    )

    data class OperitResource(
        val key: String,
        val path: String,
        val internal: Boolean = false
    )

    /** 一条兼容性诊断。[level] 取 `warn`（功能会丢）或 `info`（仅提示）。 */
    data class CompatIssue(
        val level: String,
        val item: String,
        val detail: String
    )

    /** 整个包的兼容性诊断结果，导入时落盘并在管理页展示。 */
    data class CompatReport(
        val toolpkgId: String,
        val issues: List<CompatIssue>,
        val summary: String
    ) {
        fun renderText(): String {
            val body = issues.filter { it.level != "sub" }
            val subs = issues.filter { it.level == "sub" }
            val sb = StringBuilder()
            sb.append("包：").append(toolpkgId).append('\n').append(summary).append('\n')
            body.forEach {
                sb.append(if (it.level == "warn") "⚠ " else "· ")
                    .append(it.item).append("：").append(it.detail).append('\n')
            }
            if (subs.isNotEmpty()) {
                sb.append("子包：\n")
                subs.forEach { sb.append(it.detail).append('\n') }
            }
            return sb.toString().trimEnd()
        }
    }

    // ---------------------------------------------------------------- 识别与解析

    /** 目录是否是 operit ToolPkg（有 manifest.json 且带 toolpkg_id）。 */
    fun isOperitPackage(dir: File): Boolean {
        val f = File(dir, MANIFEST_FILE)
        if (!f.isFile) return false
        return runCatching { f.readText().contains("toolpkg_id") }.getOrDefault(false)
    }

    fun readManifest(dir: File): OperitManifest? {
        val text = runCatching { File(dir, MANIFEST_FILE).readText() }.getOrNull() ?: return null
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return null
        val id = root.stringOrNull("toolpkg_id")?.trim().orEmpty()
        if (id.isEmpty()) return null

        val subs = mutableListOf<OperitSubpackage>()
        (root.get("subpackages") as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val subId = o.stringOrNull("id")?.trim().orEmpty()
            val entry = o.stringOrNull("entry")?.trim().orEmpty()
            if (subId.isNotEmpty() && entry.isNotEmpty()) {
                subs += OperitSubpackage(
                    id = subId,
                    entry = entry,
                    displayName = localized(o.get("display_name")),
                    description = localized(o.get("description")),
                    enabledByDefault = boolOrNull(o.get("enabled_by_default")) ?: boolOrNull(o.get("enabledByDefault"))
                )
            }
        }

        val resources = mutableListOf<OperitResource>()
        (root.get("resources") as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val key = o.stringOrNull("key")?.trim().orEmpty()
            val path = o.stringOrNull("path")?.trim().orEmpty()
            if (key.isNotEmpty() && path.isNotEmpty()) {
                resources += OperitResource(key, path, isTrue(o.get("internal")))
            }
        }

        return OperitManifest(
            toolpkgId = id,
            version = root.stringOrNull("version").orEmpty(),
            apiVersion = root.stringOrNull("api_version").orEmpty(),
            main = root.stringOrNull("main")?.trim().orEmpty().ifBlank { "dist/main.js" },
            displayName = localized(root.get("display_name")).ifBlank { id },
            description = localized(root.get("description")),
            subpackages = subs,
            enabledByDefault = boolOrNull(root.get("enabled_by_default")) ?: boolOrNull(root.get("enabledByDefault")) ?: true,
            resources = resources,
            workflowTemplates = (root.get("workflow_templates") as? JsonArray)?.size() ?: 0,
            workspaceTemplates = (root.get("workspace_templates") as? JsonArray)?.size() ?: 0
        )
    }

    /** 扫子包文件头的 METADATA，得到它声明的工具。 */
    fun parseMetadata(entryFile: File): List<OperitTool> = readMetadata(entryFile).first

    /**
     * 读子包文件头的 METADATA，一次返回工具清单与包级元信息。
     *
     * 子包名/描述/默认启用态的**权威来源是这里**，不是 manifest：官方
     * `ToolPkgManifestSubpackage` 只认 `id` 与 `entry`，子包项里写的其余键会被
     * `ignoreUnknownKeys` 静默丢弃。
     */
    fun readMetadata(entryFile: File): Pair<List<OperitTool>, OperitSubpackageMeta> {
        val empty = emptyList<OperitTool>() to OperitSubpackageMeta()
        val text = runCatching { entryFile.readText() }.getOrNull() ?: return empty
        val raw = METADATA_REGEX.find(text)?.groupValues?.getOrNull(1) ?: return empty
        val obj = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull() ?: return empty

        val tools = mutableListOf<OperitTool>()
        (obj.get("tools") as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.stringOrNull("name")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            tools += OperitTool(
                name = name,
                description = localized(o.get("description")),
                schema = paramsToSchema(o.get("parameters") as? JsonArray)
            )
        }

        return tools to OperitSubpackageMeta(
            displayName = localized(obj.get("display_name")),
            description = localized(obj.get("description")),
            enabledByDefault = boolOrNull(obj.get("enabled_by_default")) ?: boolOrNull(obj.get("enabledByDefault")),
            unknownKeys = obj.entrySet().map { it.key }.filter { it !in METADATA_KNOWN_KEYS }.sorted()
        )
    }

    // ---------------------------------------------------------------- 映射

    /**
     * operit 清单 → OpenX 清单。
     *
     * 权限靠扫源码推导（`Tools.Net` → http），并**始终**加 `filesystem`：
     * 那类插件的 `Tools.Files.*` 直接操作 `/root/.config/...` 这类真实路径，
     * 收进沙箱就没法用了。这是危险权限，安装页会红字提示。
     */
    /**
     * 读清单并把子包元信息补齐：子包名/描述/默认启用态以**子包文件头的 METADATA**
     * 为准（官方只认那里），manifest 里的同名字段只当备选。
     */
    fun resolveManifest(dir: File): OperitManifest? {
        val om = readManifest(dir) ?: return null
        val merged = om.subpackages.map { sub ->
            val f = File(dir, sub.entry)
            if (!f.isFile) return@map sub
            val meta = readMetadata(f).second
            sub.copy(
                displayName = meta.displayName.ifBlank { sub.displayName },
                description = meta.description.ifBlank { sub.description },
                enabledByDefault = meta.enabledByDefault ?: sub.enabledByDefault
            )
        }
        return om.copy(subpackages = merged)
    }

    // ---------------------------------------------------------------- 兼容性诊断

    /**
     * 汇总这个 operit 包在 OpenX 兼容层下的能力差异。
     *
     * 目的不是拦截安装，而是让「装了但某些功能没反应」能事前看见：
     * 兼容层只跑工具与 UI，其余注册类 API 是空实现，不报错也不生效。
     */
    fun diagnose(dir: File, om: OperitManifest): CompatReport {
        val issues = mutableListOf<CompatIssue>()

        if (!om.enabledByDefault) {
            issues += CompatIssue(
                "info", "enabled_by_default=false",
                "导入时已按禁用入库（与官方一致：该字段只决定首次安装的默认开关，用户手动启用后优先）"
            )
        }

        // 子包默认禁用：OpenX 没有子包级开关 UI，默认值无处落地。
        val disabledSubs = om.subpackages.filter { it.enabledByDefault == false }
        if (disabledSubs.isNotEmpty()) {
            issues += CompatIssue(
                "info", "子包默认禁用 ${disabledSubs.size} 个",
                "无子包级开关，已按启用处理：" + disabledSubs.joinToString("、") { it.displayName.ifBlank { it.id } }
            )
        }

        // resources：声明了就要能取到。官方允许 path 指向文件或目录，两者都算存在。
        for (r in om.resources) {
            val f = File(dir, r.path)
            if (!f.exists()) {
                issues += CompatIssue(
                    "warn", "资源缺失 ${r.key}",
                    "声明路径 ${r.path} 在包内不存在，readResource(\"${r.key}\") 会返回空"
                )
            } else if (f.isDirectory) {
                issues += CompatIssue(
                    "info", "资源 ${r.key} 是目录",
                    "官方支持整目录资源，兼容层只能按单文件取（readResource 会返回空）"
                )
            }
        }

        // 工作流/工作区模板：OpenX 没有对应概念
        if (om.workflowTemplates > 0) {
            issues += CompatIssue(
                "warn", "workflow_templates ×${om.workflowTemplates}",
                "OpenX 无工具包工作流模板概念，这部分功能不会呈现"
            )
        }
        if (om.workspaceTemplates > 0) {
            issues += CompatIssue(
                "warn", "workspace_templates ×${om.workspaceTemplates}",
                "OpenX 无工作区模板概念，这部分功能不会呈现"
            )
        }

        // 扫源码：调用了兼容层没实现的 API 就告知
        val apiUsage = scanUnsupportedApis(dir)
        for ((api, count) in apiUsage) {
            issues += CompatIssue(
                "warn", "$api 不可用",
                "源码调用 $count 处，OpenX 无对应概念（调用不报错但不生效）"
            )
        }

        // 部分支持：主路径能跑，但有事件会被忽略，属于 info 而不是 warn
        val partialUsage = scanPartialApis(dir)
        for ((api, hit) in partialUsage) {
            issues += CompatIssue("info", "$api 部分支持", hit.second)
        }

        // METADATA 声明却没导出的工具：装载时自动筛除
        var declaredCount = 0
        var droppedCount = 0
        val missing = mutableListOf<String>()
        val unknownKeys = mutableListOf<String>()
        for (sub in om.subpackages) {
            val f = File(dir, sub.entry)
            if (!f.isFile) {
                missing += "${sub.id}（入口 ${sub.entry} 不存在）"
                continue
            }
            val (tools, meta) = readMetadata(f)
            unknownKeys += meta.unknownKeys.map { "${sub.id}.${it}" }
            val body = runCatching { f.readText() }.getOrDefault("")
            for (t in tools) {
                declaredCount++
                val exported = Regex("exports\\.${Regex.escape(t.name)}\\s*=").containsMatchIn(body) ||
                    Regex("""["']${Regex.escape(t.name)}["']\s*:\s*(function|async)""").containsMatchIn(body)
                if (!exported) { missing += "${sub.id}.${t.name}"; droppedCount++ }
            }
        }
        if (missing.isNotEmpty()) {
            issues += CompatIssue(
                "info", "装载时筛除 $droppedCount 个工具",
                "METADATA 声明了但没导出，注册进去只会给 AI 一个必失败的工具：" +
                    missing.distinct().joinToString("、").take(400)
            )
        }
        if (unknownKeys.isNotEmpty()) {
            issues += CompatIssue(
                "info", "METADATA 未识别键 ${unknownKeys.size} 个",
                "兼容层不读这些键（不影响工具与界面）：" + unknownKeys.distinct().joinToString("、").take(300)
            )
        }

        // 子包清单：OpenX 没有子包级开关 UI，子包名只能作为工具归属标注出现在报告里
        val subLines = om.subpackages.map { sub ->
            val f = File(dir, sub.entry)
            val n = if (f.isFile) readMetadata(f).first.size else 0
            val name = sub.displayName.ifBlank { sub.id }
            val flag = when (sub.enabledByDefault) { null -> "未声明默认态"; true -> "默认启用"; false -> "默认禁用" }
            "· $name（${sub.id}）$n 个工具，$flag"
        }

        val warns = issues.count { it.level == "warn" }
        val summary = if (issues.isEmpty()) {
            "未发现兼容性问题（$declaredCount 个工具全部可用）"
        } else {
            "共 ${issues.size} 条：$warns 条会丢功能，${issues.size - warns} 条仅提示（可用工具 ${declaredCount - droppedCount} 个）"
        }
        return CompatReport(om.toolpkgId, issues + subLines.map { CompatIssue("sub", "", it) }, summary)
    }

    /** 源码里出现即视为“调用了兼容层没实现的 API”。 */
    private val UNSUPPORTED_APIS = listOf(
        "ToolPkg.registerChatViewHook" to "registerChatViewHook",
        "ToolPkg.registerAiProvider" to "registerAiProvider",
        "ToolPkg.registerMessageProcessingPlugin" to "registerMessageProcessingPlugin",
        "ToolPkg.registerXmlRenderPlugin" to "registerXmlRenderPlugin",
        "ToolPkg.registerInputMenuTogglePlugin" to "registerInputMenuTogglePlugin",
        "ToolPkg.registerToolboxUiModule" to "registerToolboxUiModule",
        "ToolPkg.registerDesktopWidget" to "registerDesktopWidget",
        "ToolPkg.registerPromptHistoryHook" to "registerPromptHistoryHook",
        "ToolPkg.registerChatMessageMenuItem" to "registerChatMessageMenuItem",
        "ToolPkg.registerChatInputHook" to "registerChatInputHook",
        "ToolPkg.registerChatMessageHook" to "registerChatMessageHook",
        "ToolPkg.registerSummaryGenerateHook" to "registerSummaryGenerateHook",
        "Tools.Chat." to "Tools.Chat",
        "Tools.SoftwareSettings." to "Tools.SoftwareSettings",
        "ToolPkg.wasm" to "ToolPkg.wasm",
        "ToolPkg.ipc.call" to "ToolPkg.ipc"
    )

    /**
     * 已映射到 OpenX 钩子、但**只覆盖部分事件**的 API。
     *
     * 与 [UNSUPPORTED_APIS] 分开：把它们报成「不可用」会让用户白担心（主路径其实是通的），
     * 而完全不提又会让人以为所有事件都能收到。所以列明哪些事件支持、哪些会被忽略。
     */
    private val PARTIAL_APIS = listOf(
        Triple(
            "ToolPkg.registerAppLifecycleHook", "registerAppLifecycleHook",
            "仅 application_on_create 生效（映射到 onAppStart）；前台/后台/低内存事件会被忽略"
        ),
        Triple(
            "ToolPkg.registerSystemPromptComposeHook", "registerSystemPromptComposeHook",
            "仅 after_compose_system_prompt 生效（映射到 onPromptInject，返回值按「追加内容」处理）"
        ),
        Triple(
            "ToolPkg.registerPromptFinalizeHook", "registerPromptFinalizeHook",
            "仅 before_send_to_model 生效（映射到 onPromptInject，返回值按「追加内容」处理）"
        ),
        Triple(
            "ToolPkg.registerPromptInputHook", "registerPromptInputHook",
            "仅 before_process 生效（映射到 onBeforeSend，可改写发出去的文字）"
        ),
        Triple(
            "ToolPkg.registerToolLifecycleHook", "registerToolLifecycleHook",
            "tool_call_requested / tool_execution_result 已映射；但只观察不干预（不能否决调用或替换结果）"
        )
    )

    private fun scanUnsupportedApis(dir: File): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        runCatching {
            dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                .forEach { f ->
                    val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    for ((needle, label) in UNSUPPORTED_APIS) {
                        var i = 0
                        var n = 0
                        while (true) {
                            i = t.indexOf(needle, i)
                            if (i < 0) break
                            n++; i += needle.length
                        }
                        if (n > 0) counts[label] = (counts[label] ?: 0) + n
                    }
                }
        }
        return counts
    }

    /** 扫描「部分支持」的 API；返回 label → (调用次数, 说明)。 */
    private fun scanPartialApis(dir: File): Map<String, Pair<Int, String>> {
        val hits = LinkedHashMap<String, Pair<Int, String>>()
        runCatching {
            dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                .forEach { f ->
                    val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    for ((needle, label, note) in PARTIAL_APIS) {
                        var i = 0
                        var n = 0
                        while (true) {
                            i = t.indexOf(needle, i)
                            if (i < 0) break
                            n++; i += needle.length
                        }
                        if (n > 0) {
                            val prev = hits[label]?.first ?: 0
                            hits[label] = (prev + n) to note
                        }
                    }
                }
        }
        return hits
    }

    fun adaptManifest(dir: File, om: OperitManifest): PluginManifest = PluginManifest(
        id = om.toolpkgId,
        name = om.displayName.ifBlank { om.toolpkgId },
        version = om.version,
        description = om.description,
        author = "Operit ToolPkg",
        main = om.main,
        apiVersion = om.apiVersion.ifBlank { "1.0.0" },
        permissions = inferPermissions(dir),
        // ui 写**真实存在**的文件相对路径（而不是生成物）：这样 canOpen()、管理页的
        // 「有界面」判定、悬浮窗的可用性检查都不用特判 compat 分支。真正装载时
        // PluginUiSession 会用 [buildUiEntrySource] 替换掉文件内容。
        ui = findUiEntry(dir).orEmpty(),
        compat = COMPAT_TAG
    )

    /**
     * 找出这个包的界面入口文件（相对插件根的路径），找不到返回 null。
     *
     * operit 没有「哪个文件是界面」的清单字段 —— 它靠 `ToolPkg.registerUiRoute` 在
     * 运行时注册，而注册要执行 main.js 才知道。所以这里按约定找 `.ui.js`：
     *
     * ⚠ 必须**递归**搜全目录，不能只看根下的 `ui/`：tsc 产物实际在 `dist/ui/<route>/
     * index.ui.js`（`dist` 是编译输出目录）。只看根目录会返回 null，导致面板根本打不开。
     */
    fun findUiEntry(dir: File): String? {
        val hit = runCatching {
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".ui.js") }
                .minByOrNull { it.absolutePath.length }
        }.getOrNull() ?: return null
        return runCatching { hit.relativeTo(dir).path }.getOrNull()
    }

    /**
     * 生成 UI 装载脚本：兼容运行时 + 初始化 + 加载界面模块。
     *
     * 产出以 `exports.default = Screen` 结尾，正好命中 OpenX UI 运行时的入口解析
     * 分支（见 PluginUiRuntime.resolveEntry），不需要改那侧代码。
     */
    fun buildUiEntrySource(dir: File, uiRelPath: String): String {
        val om = readManifest(dir) ?: return ""
        val abs = File(dir, uiRelPath).absolutePath
        val sb = StringBuilder()
        sb.append(OperitCompatRuntime.SCRIPT).append('\n')
        sb.append("__openxCompatInit(")
            .append(gson.toJson(dir.absolutePath)).append(", ")
            .append(gson.toJson(om.toolpkgId)).append(", ")
            // UI 轨道不需要子包表（工具注册用），但 loader 的模块路径校验依赖 root，
            // 所以仍要 init；传空表即可。
            .append("\"{}\");\n")
        sb.append("__openxCompatLoadUi(").append(gson.toJson(abs)).append(");\n")
        return sb.toString()
    }

    /**
     * 从源码推断需要什么权限。
     *
     * `hook` 不能无差别授予——它意味着能读写每一条对话，是这里最敏感的权限。
     * 所以只在源码里**真调了**钩子注册 API 时才给，与 operit 插件实际需要的能力对齐。
     */
    private fun inferPermissions(dir: File): List<String> {
        val perms = mutableListOf("filesystem")
        var usesHttp = false
        var usesHook = false
        runCatching {
            dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                .forEach { f ->
                    val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    if (t.contains("Tools.Net.") || t.contains("Tools.Http.")) usesHttp = true
                    if (HOOK_APIS.any { t.contains(it) }) usesHook = true
                }
        }
        if (usesHttp) perms += "http"
        if (usesHook) perms += "hook"
        return perms
    }

    /** 兼容层里已映射到 OpenX 钩子的那些注册 API。 */
    private val HOOK_APIS = listOf(
        "registerAppLifecycleHook",
        "registerToolLifecycleHook",
        "registerPromptInputHook",
        "registerSystemPromptComposeHook",
        "registerPromptFinalizeHook"
    )

    // ---------------------------------------------------------------- 入口脚本

    /**
     * 生成 OpenX 风格的入口脚本：兼容运行时 + 初始化 + 逐工具注册。
     *
     * 没有声明任何工具时返回空串（纯 UI 的 operit 包归后续阶段处理）。
     * 每个工具都包一层 `__openxCompatShouldRegister`，把声明了却没导出的条目筛掉。
     */
    fun buildEntrySource(dir: File, manifest: PluginManifest): String {
        val om = readManifest(dir) ?: return ""
        val subEntries = JsonObject()
        val exists = mutableListOf<OperitSubpackage>()
        for (sub in om.subpackages) {
            val f = File(dir, sub.entry)
            if (!f.isFile) {
                FileLogger.w("OperitCompat", "子包 ${sub.id} 入口不存在：${sub.entry}")
                continue
            }
            subEntries.addProperty(sub.id, f.absolutePath)
            exists += sub
        }

        // 注意：`exists` 为空 **不等于** 没事可做。纯钩子包（只调 register*Hook、
        // 声明 `subpackages: []`、不导出任何工具）在 operit 侧是合法的，它的注册全在
        // main.js 里。之前这里直接 return "" 导致这类包根本跑不到 main.js，钩子也就
        // 永远注不上。只要 main.js 存在就继续生成入口。
        val mainAbs = File(dir, om.main)
        if (exists.isEmpty() && !mainAbs.isFile) return ""

        val sb = StringBuilder()
        sb.append(OperitCompatRuntime.SCRIPT).append('\n')
        sb.append("__openxCompatInit(")
            .append(gson.toJson(dir.absolutePath)).append(", ")
            .append(gson.toJson(om.toolpkgId)).append(", ")
            .append(gson.toJson(subEntries.toString())).append(");\n")

        var declared = 0
        for (sub in exists) {
            for (tool in parseMetadata(File(dir, sub.entry))) {
                // 装载期先查模块是否真导出了这个工具：operit 包普遍有「METADATA 声明了
                // 但没导出」的条目，注册进去只会给 AI 一个必失败的工具。
                sb.append("if (__openxCompatShouldRegister(")
                    .append(gson.toJson(sub.id)).append(", ").append(gson.toJson(tool.name)).append(")) ")
                sb.append("\$ptools.push({\n")
                sb.append("  name: ").append(gson.toJson(tool.name)).append(",\n")
                sb.append("  description: ").append(gson.toJson(tool.description)).append(",\n")
                sb.append("  parameters: ").append(gson.toJson(tool.schema)).append(",\n")
                sb.append("  handler: function (args) { return __openxCompatInvoke(")
                    .append(gson.toJson(sub.id)).append(", ")
                    .append(gson.toJson(tool.name)).append(", args); }\n")
                sb.append("});\n")
                declared++
            }
        }
        // 注意：declared == 0 时不能直接返回空串。纯钩子插件（只挂 register*Hook、
        // 不声明任何工具）是合法的，operit 侧就有这种包；它需要跑 main.js 才能把钩子
        // 声明进 $phooks。只要 main.js 存在就继续生成入口，让钩子有机会注册。
        if (declared == 0 && !mainAbs.isFile) return ""

        // 执行 main.js：收集 registerNavigationEntry / registerUiRoute 的注册，供侧边栏入口用。
        // 官方语义（ToolPkgMainRegistrationScriptParser）：装载 main.js 后宿主要**主动调用
        // exports.registerToolPkg()**，注册都发生在这个函数体里 —— 只 eval 不调它，
        // 什么也收不到。有的包没有这个导出（顶层直接注册），所以找不到就跳过。
        // 同一段还负责把 ToolPkg.register*Hook 的声明推进 $phooks（见兼容运行时的映射）。
        if (mainAbs.isFile) {
            sb.append("try {\n")
            sb.append("    globalThis.__openxNavEntries = [];\n")
            sb.append("    var __navMod = __openxCompatLoad(").append(gson.toJson(mainAbs.absolutePath)).append(");\n")
            sb.append("    if (__navMod && typeof __navMod.registerToolPkg === 'function') __navMod.registerToolPkg();\n")
            sb.append("} catch (navErr) {\n")
            sb.append("    globalThis.__openxNavEntries = [];\n")
            sb.append("    if (typeof console !== 'undefined' && console.warn) console.warn('nav collect failed: ' + navErr);\n")
            sb.append("}\n")
        }

        FileLogger.i("OperitCompat", "${manifest.id} 兼容入口生成：${exists.size} 个子包 / 声明 $declared 个工具（未导出的装载时自动筛除）")
        return sb.toString()
    }

    // ---------------------------------------------------------------- JSON 辅助

    /**
     * operit 里大量文案是 `{zh, en, default}` 多语言对象。按**宿主语言**选优先项
     * （中文环境 zh 优先，其余 en 优先），再退 default、退另一语种、最后退任意非空值。
     */
    private fun localized(el: JsonElement?, preferZh: Boolean = prefersZh): String {
        if (el == null || el.isJsonNull) return ""
        if (el.isJsonPrimitive) return runCatching { el.asString }.getOrDefault("")
        if (!el.isJsonObject) return ""
        val o = el.asJsonObject
        val order = if (preferZh) listOf("zh", "default", "en") else listOf("en", "default", "zh")
        for (key in order) {
            val v = o.stringOrNull(key)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return o.entrySet().firstOrNull { it.value.isJsonPrimitive }
            ?.value?.let { runCatching { it.asString }.getOrNull() }
            .orEmpty()
    }

    /** 宿主系统语言是否中文（取不到时当中文，与历史行为一致）。 */
    private val prefersZh: Boolean
        get() = runCatching { Locale.getDefault().language.equals("zh", ignoreCase = true) }.getOrDefault(true)

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    /**
     * 宽松布尔解析：官方对 `enabled_by_default` 同时接受布尔与字符串
     * （`"true"/"1"/"yes"/"on"` 为真，`"false"/"0"/"no"/"off"` 为假），
     * 见官方 PackageManager 的别名归一与转换逻辑。
     */
    private fun isTrue(el: JsonElement?): Boolean {
        if (el == null || !el.isJsonPrimitive) return false
        return runCatching {
            val p = el.asJsonPrimitive
            if (p.isBoolean) p.asBoolean else p.asString.trim().lowercase() in TRUE_TOKENS
        }.getOrDefault(false)
    }

    /** 区分「没声明」与「声明为 false」，供默认值分层回退。 */
    private fun boolOrNull(el: JsonElement?): Boolean? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        return runCatching {
            val p = el.asJsonPrimitive
            if (p.isBoolean) return@runCatching p.asBoolean
            val s = p.asString.trim().lowercase()
            if (s in TRUE_TOKENS) true else if (s in FALSE_TOKENS) false else null
        }.getOrNull()
    }

    /** operit 的参数数组 → OpenX 要求的 JSON Schema 对象。 */
    private fun paramsToSchema(params: JsonArray?): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        val props = JsonObject()
        val required = JsonArray()
        params?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.stringOrNull("name")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val prop = JsonObject()
            prop.addProperty("type", o.stringOrNull("type")?.trim().orEmpty().ifBlank { "string" })
            val desc = localized(o.get("description"))
            if (desc.isNotBlank()) prop.addProperty("description", desc)
            props.add(name, prop)
            if (o.get("required")?.let { runCatching { it.asBoolean }.getOrDefault(false) } == true) {
                required.add(name)
            }
        }
        schema.add("properties", props)
        if (required.size() > 0) schema.add("required", required)
        return schema
    }
}
