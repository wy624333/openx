package com.wy.openx.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppTool(private val context: Context) : AgentTool {

    override val definition = ToolDef(
        function = FunctionDef(
            name = "app_launcher",
            description = "打开 Android 应用（通过包名），列出或搜索已安装的应用。注意：部分系统应用可能不显示。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf(
                        "type" to "string",
                        "enum" to listOf("open", "list", "search"),
                        "description" to "操作: open(打开应用), list(列出所有应用), search(搜索应用)"
                    ),
                    "package_name" to mapOf(
                        "type" to "string",
                        "description" to "open 模式: 要打开的应用包名(如 com.tencent.mm)"
                    ),
                    "keyword" to mapOf(
                        "type" to "string",
                        "description" to "search 模式: 搜索关键词(如 微信)"
                    ),
                    "max_results" to mapOf(
                        "type" to "integer",
                        "description" to "list/search 返回数量上限(默认20)",
                        "default" to 20
                    )
                ),
                "required" to listOf("action")
            )
        )
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String ?: return@withContext "错误：未指定操作"
            when (action) {
                "open" -> openApp(args["package_name"] as? String ?: "")
                "list" -> listApps((args["max_results"] as? Double)?.toInt() ?: 20)
                "search" -> searchApps(args["keyword"] as? String ?: "", (args["max_results"] as? Double)?.toInt() ?: 20)
                else -> "错误：不支持的操作 '$action'"
            }
        } catch (e: Exception) { "操作出错: ${e.message}" }
    }

    private fun openApp(packageName: String): String {
        if (packageName.isBlank()) return "错误：请提供包名"
        val pm = context.packageManager
        return try {
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                val appName = intent.resolveActivityInfo(pm, 0)?.loadLabel(pm)?.toString() ?: packageName
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "✅ 已打开应用: $appName ($packageName)"
            } else {
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))?.toString()
                } catch (_: Exception) { null }
                if (appName != null) "应用 $appName 已安装但无启动入口（可能为系统组件）"
                else "错误：未找到包名为 '$packageName' 的应用。常见原因：\n1. 未安装该应用\n2. Android 11+ 应用可见性限制"
            }
        } catch (e: Exception) { "打开失败: ${e.message}" }
    }

    private fun listApps(max: Int): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 预解析 label 一次，避免 filter/sort/输出三阶段重复调用 PackageManager
        val allApps = pm.queryIntentActivities(intent, 0)
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second }
        val apps = allApps.take(max)
        return buildString {
            appendLine("已安装应用 (共${allApps.size}个，仅显示有桌面图标的应用):")
            apps.forEachIndexed { i, (info, label) ->
                appendLine("${i + 1}. $label (${info.activityInfo.packageName})")
            }
            if (apps.size < allApps.size) appendLine("...(仅显示前${max}个，共${allApps.size}个)")
        }.trimEnd()
    }

    private fun searchApps(keyword: String, max: Int): String {
        if (keyword.isBlank()) return "错误：请提供搜索关键词"
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = pm.queryIntentActivities(intent, 0)
            .filter { info ->
                val name = info.loadLabel(pm).toString()
                name.contains(keyword, ignoreCase = true) || info.activityInfo.packageName.contains(keyword, ignoreCase = true)
            }.sortedBy { it.loadLabel(pm).toString() }.take(max)
        if (results.isEmpty()) {
            val allPkgs = try {
                pm.getInstalledApplications(PackageManager.MATCH_ALL).map { it.packageName }
            } catch (_: Exception) { emptyList() }
            val pkgMatches = allPkgs.filter { it.contains(keyword, ignoreCase = true) }
            return if (pkgMatches.isNotEmpty()) {
                buildString {
                    appendLine("未找到匹配 '$keyword' 的可启动应用，但发现以下包名匹配:")
                    pkgMatches.take(max).forEach { appendLine("  - $it") }
                }
            } else "未找到匹配 '$keyword' 的应用"
        }
        return buildString {
            appendLine("搜索 '$keyword' 结果 (${results.size}个):")
            results.forEachIndexed { i, info ->
                appendLine("${i + 1}. ${info.loadLabel(pm)} (${info.activityInfo.packageName})")
            }
        }.trimEnd()
    }
}
