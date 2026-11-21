package com.wy.openx.tools

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 工具授权规则的**持久化**存储。
 *
 * ## 为什么放宿主而不是容器
 * 授权规则是安全边界本身，绝不能落进 `~/.openx`（容器内可见/可写）。
 * 放在宿主 `filesDir` 下（与设置同源），**容器内的 AI 无法读写本表给自己扩权**。
 * 内置安全白名单 [BuiltInSafeCommands] 同理——它是代码常量、刻意不落盘。
 *
 * 存储用 SharedPreferences（存 JSON 字符串），随设置一起被备份/还原覆盖。
 */
class ToolPermissionRuleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val listType = object : TypeToken<List<PermissionRule>>() {}.type

    /** 读取全部规则；损坏时返回空列表（宁可全弹窗，也不因解析失败而误放行）。 */
    fun load(): List<PermissionRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<PermissionRule>>(raw, listType) }
            .getOrNull()
            ?.filter { it.toolName.isNotBlank() && it.pattern.isNotBlank() }
            ?: emptyList()
    }

    /** 覆盖写入（去重：同工具+同模式+同决策只留一条）。 */
    fun save(rules: List<PermissionRule>) {
        val deduped = rules.distinctBy { Triple(it.toolName, it.pattern, it.decision) }
        prefs.edit().putString(KEY_RULES, gson.toJson(deduped, listType)).apply()
    }

    /** 追加规则（用于「始终允许」），已存在则不重复写。 */
    fun remember(newRules: List<PermissionRule>) {
        if (newRules.isEmpty()) return
        val merged = (load() + newRules).distinctBy { Triple(it.toolName, it.pattern, it.decision) }
        save(merged)
    }

    /** 移除某条规则（设置页「撤销授权」用）。 */
    fun remove(toolName: String, pattern: String) {
        save(load().filterNot { it.toolName == toolName && it.pattern == pattern })
    }

    fun clear() {
        prefs.edit().remove(KEY_RULES).apply()
    }

    private companion object {
        const val PREFS_NAME = "openx_tool_permission"
        const val KEY_RULES = "tool_permission_rules"
    }
}