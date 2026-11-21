package com.wy.openx.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.compat.OperitCompat
import java.io.File
import java.util.Locale

/**
 * 插件仓库：清单与脚本落盘在 `<filesDir>/openx/plugins/<id>/`，
 * 启停名单在 `<filesDir>/openx/plugins.json`（**只存禁用名单**，与 SkillStore 一致 ——
 * 这样手动往目录里丢插件默认是启用的，不需要先"注册"一次）。
 *
 * 选用 filesDir 而非外部存储：插件是**可执行 JS**，放外部存储等于任何 App 都能改，
 * 等于给了第三方代码注入点。与 Skill（仅文本指令）不同，这里必须是内部目录。
 */
class PluginStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    companion object {
        private const val TAG = "PluginStore"
        private const val DIR_NAME = "plugins"
        private const val CONFIG_FILE = "plugins.json"
        private const val MANIFEST_FILE = "plugin.json"
        /** 兼容包导入时落盘的诊断报告（人读文本，不进 PluginManifest）。 */
        private const val COMPAT_REPORT_FILE = "compat_report.txt"

        private val PLUGINS_ROOT: (Context) -> File = { ctx ->
            File(File(ctx.filesDir, "openx"), DIR_NAME)
        }
    }

    /** 插件根目录：filesDir/openx/plugins */
    val pluginsRoot: File
        get() = PLUGINS_ROOT(appContext)

    /** 启停配置：filesDir/openx/plugins.json */
    private val configFile: File
        get() = File(File(appContext.filesDir, "openx"), CONFIG_FILE)

    init {
        runCatching { pluginsRoot.mkdirs() }
        extractBuiltinPlugins()
    }

    // ---------------------------------------------------------------- 内置插件

    /**
     * 把 assets 里的内置插件释放到插件目录。
     *
     * 为什么要释放而不是直接从 assets 读：插件是 JS，加载链路（UI 脚本、
     * 沙箱文件目录、热重载）全都以磁盘目录为前提。释放成普通目录后，
     * 内置插件和用户装的插件走完全相同的代码路径，无需维护两套。
     *
     * 已存在的**不覆盖** —— 否则用户在界面上改过的配置/生成的文件会被 App 启动冲掉。
     */
    private fun extractBuiltinPlugins() {
        val root = "builtin-plugins"
        val names = runCatching {
            appContext.assets.list(root).orEmpty()
        }.getOrDefault(emptyArray()).toList()
        for (name in names) {
            val manifestAsset = "$root/$name/$MANIFEST_FILE"
            val manifestText = runCatching {
                appContext.assets.open(manifestAsset).bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val manifest = runCatching { parseManifest(manifestText) }.getOrNull() ?: continue
            val target = File(pluginsRoot, manifest.safeDirName)
            if (target.exists()) continue
            runCatching {
                copyAssetTree("$root/$name", target)
                FileLogger.i(TAG, "释放内置插件 ${manifest.id} → ${target.absolutePath}")
            }.onFailure {
                FileLogger.e(TAG, "释放内置插件 ${manifest.id} 失败: ${it.message}")
            }
        }
    }

    private fun copyAssetTree(assetDir: String, target: File) {
        target.mkdirs()
        for (child in appContext.assets.list(assetDir).orEmpty()) {
            val childAsset = "$assetDir/$child"
            val childTarget = File(target, child)
            // assets 不区分目录和文件：能当流打开就是文件，打不开就是目录
            val isFile = runCatching {
                appContext.assets.open(childAsset).use { it.available() >= 0 }
                true
            }.getOrDefault(false)
            if (isFile) {
                childTarget.parentFile?.mkdirs()
                appContext.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetTree(childAsset, childTarget)
            }
        }
    }

    // ---------------------------------------------------------------- 读

    /** 扫描全部已安装插件（含清单解析失败项，用于在管理页提示用户）。 */
    fun load(): List<InstalledPlugin> {
        val disabled = disabledIds()
        val dirs = pluginsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifestFile = File(dir, MANIFEST_FILE)
            if (!manifestFile.isFile) return@mapNotNull null
            val manifest = runCatching { parseManifest(manifestFile.readText()) }.getOrNull()
            if (manifest == null || manifest.id.isBlank()) {
                // 清单坏了也要显示出来，否则用户只会看到"插件莫名消失"
                InstalledPlugin(
                    manifest = PluginManifest(id = dir.name, name = dir.name),
                    dirPath = dir.absolutePath,
                    enabled = false,
                    error = "plugin.json 解析失败"
                )
            } else {
                InstalledPlugin(
                    manifest = withDerivedUi(manifest, dir),
                    dirPath = dir.absolutePath,
                    enabled = manifest.id.lowercase(Locale.ROOT) !in disabled
                )
            }
        }.sortedBy { it.manifest.name.lowercase(Locale.ROOT) }
    }

    /**
     * 给 operit 兼容包补上 `ui` 入口（仅当它没声明时）。
     *
     * 单一收口点：管理页的「有界面」判定、快捷栏、悬浮窗、UI session 都读这个字段，
     * 在这里补齐就不用四处特判 compat。早期版本转换出的 plugin.json 里 ui 是空的，
     * 不回填的话已装插件永远打不开面板，而兼容层的改进本就不该要求重新导入。
     *
     * 只补内存对象，不回写文件：避免每次启动都改用户目录下的文件。
     */
    private fun withDerivedUi(manifest: PluginManifest, dir: File): PluginManifest {
        if (manifest.ui.isNotBlank()) return manifest
        if (manifest.compat != OperitCompat.COMPAT_TAG) return manifest
        val derived = runCatching { OperitCompat.findUiEntry(dir) }.getOrNull()
        return if (derived.isNullOrBlank()) manifest else manifest.copy(ui = derived)
    }

    /** 已启用的插件（供运行时加载）。 */
    fun loadEnabled(): List<InstalledPlugin> = load().filter { it.enabled && it.error == null }

    /** 入口脚本文件；不存在返回 null。 */
    fun entryFile(plugin: InstalledPlugin): File? {
        val f = File(plugin.dirPath, plugin.manifest.main)
        // 防上跳：入口必须落在插件目录内
        return if (f.isFile && f.canonicalPath.startsWith(File(plugin.dirPath).canonicalPath)) f else null
    }

    /** 读取入口脚本源码。 */
    fun readEntrySource(plugin: InstalledPlugin): String? =
        entryFile(plugin)?.let { runCatching { it.readText() }.getOrNull() }

    /** 读取 service 入口脚本源码（服务型插件）；不存在返回 null。 */
    fun readServiceSource(plugin: InstalledPlugin): String? {
        if (plugin.manifest.service.isBlank()) return null
        val f = File(plugin.dirPath, plugin.manifest.service)
        return if (f.isFile && f.canonicalPath.startsWith(File(plugin.dirPath).canonicalPath)) {
            runCatching { f.readText() }.getOrNull()
        } else null
    }

    // ---------------------------------------------------------------- 写

    /** 启用 / 禁用（只改名单，不动文件）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        val set = disabledIds().toMutableSet()
        if (enabled) set.remove(id.lowercase(Locale.ROOT)) else set.add(id.lowercase(Locale.ROOT))
        writeDisabled(set)
    }

    /** 卸载：删掉整个插件目录 + 清掉名单记录。 */
    fun uninstall(id: String): Boolean {
        // 先退订总线：插件没了，它的订阅不应继续占着 topic
        runCatching { PluginBus.unsubscribeAll(id) }
        val dir = File(pluginsRoot, id)
        val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        val set = disabledIds().toMutableSet()
        set.remove(id.lowercase(Locale.ROOT))
        writeDisabled(set)
        runCatching { File(File(appContext.filesDir, "openx"), "plugins/$id").deleteRecursively() }
        FileLogger.i(TAG, "卸载插件 $id → $ok")
        return ok
    }

    /**
     * 安装：把 [manifest] + [entrySource] 写入插件目录。
     *
     * 已存在同 ID 时**覆盖安装**（保留目录内其它资源文件不清空，便于插件带 resources）。
     * @return 安装后的插件目录
     */
    fun install(manifest: PluginManifest, entrySource: String): File {
        val dir = File(pluginsRoot, manifest.safeDirName)
        dir.mkdirs()
        File(dir, MANIFEST_FILE).writeText(gson.toJson(manifest))
        val entry = File(dir, manifest.main)
        entry.parentFile?.mkdirs()
        entry.writeText(entrySource)
        // 覆盖安装视为启用：用户刚装完却因为历史禁用名单而"装了没反应"会很困惑
        setEnabled(manifest.id, true)
        FileLogger.i(TAG, "安装插件 ${manifest.id} → ${dir.absolutePath}")
        return dir
    }

    /** 仅写清单（市场安装时脚本可能单独下载）。 */
    fun writeManifest(dir: File, manifest: PluginManifest) {
        dir.mkdirs()
        File(dir, MANIFEST_FILE).writeText(gson.toJson(manifest))
    }

    /**
     * 生成并落盘兼容包的诊断报告，同时把报告返回给调用方。
     *
     * 诊断失败不影响安装：宁可没报告，也不能因为报告写不出来而装不上插件。
     */
    private fun writeCompatReport(
        destDir: File,
        om: OperitCompat.OperitManifest
    ): OperitCompat.CompatReport? = runCatching {
        val report = OperitCompat.diagnose(destDir, om)
        File(destDir, COMPAT_REPORT_FILE).writeText(report.renderText())
        FileLogger.i(TAG, "兼容性诊断：${om.toolpkgId} → ${report.renderText().replace("\n", " | ")}")
        report
    }.getOrNull()

    /**
     * 读兼容包的诊断报告；老版本导入的包没落过报告，这里按需补。
     *
     * 不是兼容包、或补不出来都返回 null（管理页据此决定要不要显示诊断区块）。
     */
    fun readCompatReport(plugin: InstalledPlugin): String? {
        if (plugin.manifest.compat != OperitCompat.COMPAT_TAG) return null
        val dir = File(plugin.dirPath)
        val existing = runCatching { File(dir, COMPAT_REPORT_FILE).readText().ifBlank { null } }.getOrNull()
        if (existing != null) return existing
        val om = runCatching { OperitCompat.resolveManifest(dir) }.getOrNull() ?: return null
        writeCompatReport(dir, om)
        return runCatching { File(dir, COMPAT_REPORT_FILE).readText().ifBlank { null } }.getOrNull()
    }

    // ------------------------------------------------------------ 压缩包导入

    /**
     * 从压缩包安装插件（对齐技能页的 `installFromArchive`）。
     *
     * 支持三种包内布局：
     *  1. 包根就是插件：`plugin.json` + `main.js`
     *  2. 单层子目录：`my-plugin/plugin.json`
     *  3. 多层嵌套：能找到多个 `plugin.json`，**每个都装**（一个包带多插件）
     *
     * 用 `plugin.json` 作为识别的锚点（而不是目录名或入口脚本名）——
     * 因为清单里的 `id` 才是插件的真实标识，目录名会变（GitHub 导出包常带 `xxx-main/`）。
     *
     * @return 结构化的导入结果：成功装了哪些（带兼容警告）、哪些跳过及原因。
     *         `entries` 为空且 `failures` 也为空 = 包里没找到任何有效插件。
     */
    fun installFromArchive(archive: File, archiveName: String): PluginInstallResult {
        val tmp = File(appContext.cacheDir, "plugin_import_${System.currentTimeMillis()}")
        val entries = mutableListOf<PluginInstallResult.Entry>()
        val failures = mutableListOf<PluginInstallResult.Failure>()
        return try {
            tmp.mkdirs()
            com.wy.openx.container.ArchiveExtractor.extract(archive, archiveName, tmp)

            // 找所有含 plugin.json 的目录。**只取最外层**：
            // walkTopDown 会把父目录和子目录都吐出来，若父目录已经有 plugin.json，
            // 子目录的就不该再单独装（一个插件一份清单）。
            val manifestDirs = tmp.walkTopDown()
                .filter { it.isDirectory && (File(it, MANIFEST_FILE).isFile || File(it, OperitCompat.MANIFEST_FILE).isFile) }
                .filter { dir ->
                    // 往上找，若任一祖先目录也含清单，则本目录不是最外层
                    var p = dir.parentFile
                    while (p != null && p.path.startsWith(tmp.path)) {
                        if (File(p, MANIFEST_FILE).isFile || File(p, OperitCompat.MANIFEST_FILE).isFile) return@filter false
                        p = p.parentFile
                    }
                    true
                }
                .toList()

            for (srcDir in manifestDirs) {
                // 优先原生 plugin.json；没有再试 operit 的 manifest.json
                val nativeManifest = runCatching {
                    parseManifest(File(srcDir, MANIFEST_FILE).readText())
                }.getOrNull()
                val adaptManifest = if (nativeManifest == null) {
                    runCatching {
                        OperitCompat.resolveManifest(srcDir)?.let { OperitCompat.adaptManifest(srcDir, it) }
                    }.getOrNull()
                } else {
                    null
                }
                val manifest = nativeManifest ?: adaptManifest

                if (manifest == null || manifest.id.isBlank()) {
                    FileLogger.e(TAG, "跳过 ${srcDir.name}：清单无效或缺少 id")
                    failures += PluginInstallResult.Failure(srcDir.name, "清单无效或缺少 id")
                    continue
                }

                val destDir = File(pluginsRoot, manifest.safeDirName)
                // 覆盖安装：先清掉旧目录，避免残留上一版本已删除的文件
                runCatching { destDir.deleteRecursively() }
                destDir.parentFile?.mkdirs()

                val compatOm = if (adaptManifest != null) {
                    runCatching { OperitCompat.resolveManifest(srcDir) }.getOrNull()
                } else null
                var report: OperitCompat.CompatReport? = null

                if (srcDir.copyRecursively(destDir, overwrite = true)) {
                    // operit 包自身没有 plugin.json，得落一份作为后续扫描的锚点；
                    // 里面带 compat 标记，装载时据此走兼容轨道。
                    if (adaptManifest != null) {
                        writeManifest(destDir, adaptManifest)
                        // 诊断报告直接决定弹窗里要不要提示「部分不兼容」，所以留下来用
                        report = compatOm?.let { writeCompatReport(destDir, it) }
                        FileLogger.i(TAG, "Operit 兼容包已转换：${manifest.id}（权限 ${adaptManifest.permissions}）")
                    }
                    // 覆盖安装视为启用（同 install()）；但尊重包声明的 `enabled_by_default`：
                    // 官方那个字段只决定「首次安装时的默认开关」，用户后续手动开关优先。
                    val defaultOn = compatOm?.enabledByDefault ?: true
                    setEnabled(manifest.id, defaultOn)
                    if (!defaultOn) FileLogger.i(TAG, "${manifest.id} 声明 enabled_by_default=false，已按禁用入库")
                    // 记一个版本戳：表明这是我们释放/安装的，便于后续区分
                    File(destDir, ".installed").writeText(manifest.version)
                    entries += PluginInstallResult.Entry(
                        id = manifest.id,
                        name = manifest.name.ifBlank { manifest.id },
                        // 只取 warn：info 是「按官方语义降级处理了」，不该吓用户
                        warnings = report?.issues
                            ?.filter { it.level == "warn" }
                            ?.map { "${it.item}：${it.detail}" }
                            .orEmpty()
                    )
                    FileLogger.i(TAG, "从压缩包安装插件 ${manifest.id} → ${destDir.absolutePath}")
                }
            }

            // 特殊情形：包根散装 plugin.json，但上面的 walk 已覆盖（tmp 自身也算含
            // manifest.json 的目录），所以不用额外分支。
            PluginInstallResult(entries, failures)
        } catch (e: Throwable) {
            FileLogger.e(TAG, "压缩包导入失败: ${e.message}")
            PluginInstallResult(entries, failures + PluginInstallResult.Failure(archiveName, e.message ?: "解压失败"))
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------ 名单读写

    private fun disabledIds(): Set<String> {
        if (!configFile.isFile) return emptySet()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String> = gson.fromJson(configFile.readText(), type) ?: emptyList()
            list.map { it.lowercase(Locale.ROOT) }.toSet()
        }.getOrElse {
            FileLogger.e(TAG, "plugins.json 读取失败: ${it.message}")
            emptySet()
        }
    }

    private fun writeDisabled(ids: Set<String>) {
        runCatching {
            configFile.parentFile?.mkdirs()
            val json = gson.toJson(ids.toList())
            // 先写临时文件再改名，避免写一半断电留下坏 JSON（照 McpServerStore 的做法）
            val tmp = File(configFile.parentFile, "${configFile.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(configFile)) configFile.writeText(json)
        }.onFailure { FileLogger.e(TAG, "plugins.json 写入失败: ${it.message}") }
    }

    // ------------------------------------------------------------ 清单解析加固

    /**
     * 解析 plugin.json。
     *
     * **为什么要先补默认值**：Gson 用 Unsafe 建实例、**不走 Kotlin 构造函数**，
     * 因此模型类上写的 `= ""` / `= false` 这类默认值对 JSON 里缺失的字段**不生效**——
     * 字段会保持 null。之后只要有人访问它、或调用 data class 的 `copy()`，就会抛
     * `NullPointerException: Parameter specified as non-null is null`（真机上崩过）。
     *
     * 所以在交给 Gson 之前，先把 JSON 里被省略的字段补成显式默认值。
     * 默认值集中在本函数，与插件文档一一对应：**新增清单字段时记得同步这里**，
     * 否则又会出现 null 字段。
     */
    private fun parseManifest(text: String): PluginManifest? {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        normalizeManifest(root)
        return gson.fromJson(root, PluginManifest::class.java)
    }

    private fun normalizeManifest(o: JsonObject) {
        o.putIfAbsentString("id", "")
        o.putIfAbsentString("name", "")
        o.putIfAbsentString("version", "")
        o.putIfAbsentString("description", "")
        o.putIfAbsentString("author", "")
        o.putIfAbsentString("main", "main.js")
        o.putIfAbsentString("apiVersion", "1.0.0")
        o.putIfAbsentString("ui", "")
        o.putIfAbsentString("service", "")
        o.putIfAbsentString("port", "0")
        o.putIfAbsentString("compat", "")

        val qa = o.get("quickActions")
        if (qa == null || !qa.isJsonArray) {
            o.add("quickActions", JsonArray())
        } else {
            qa.asJsonArray.forEach { el -> if (el.isJsonObject) normalizeQuickAction(el.asJsonObject) }
        }

        val perms = o.get("permissions")
        if (perms == null || !perms.isJsonArray) o.add("permissions", JsonArray())

        // 定时任务：旧版本装的插件没有这个字段，必须补成空数组，
        // 否则 manifest.copy() 会因为参数为 null 而 NPE。
        val sch = o.get("schedule")
        if (sch == null || !sch.isJsonArray) o.add("schedule", JsonArray())
    }

    private fun normalizeQuickAction(o: JsonObject) {
        o.putIfAbsentString("label", "")
        o.putIfAbsentString("prompt", "")
        o.putIfAbsentString("icon", "")
        o.putIfAbsentString("action", "prompt")
        if (o.get("autoSend") == null || o.get("autoSend").isJsonNull) {
            o.addProperty("autoSend", false)
        }
    }

    private fun JsonObject.putIfAbsentString(key: String, default: String) {
        if (get(key) == null || get(key).isJsonNull) addProperty(key, default)
    }
}

/**
 * 一次插件导入的结果，供管理页弹窗展示。
 *
 * 分成「装上的」和「没装的」两桶：装上的还要带兼容警告，因为 Operit 兼容包
 * 常常是**能装但部分 API 不支持**，直接报"成功"会让用户以为全好了。
 */
data class PluginInstallResult(
    val entries: List<Entry> = emptyList(),
    val failures: List<Failure> = emptyList()
) {
    /** 单个插件的结果。[warnings] 非空表示能装但有兼容问题。 */
    data class Entry(val id: String, val name: String, val warnings: List<String> = emptyList())

    /** 没装成的条目：[source] 是包内目录名或压缩包名，[reason] 给人看。 */
    data class Failure(val source: String, val reason: String)

    val hasAny: Boolean get() = entries.isNotEmpty() || failures.isNotEmpty()
    val hasWarnings: Boolean get() = entries.any { it.warnings.isNotEmpty() }
}
