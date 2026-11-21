package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * 子代理角色权限
 *
 * OpenX 的 [ToolRegistry] 没有这层元数据，
 * 只有工具名，所以这里改成**按工具名白名单 + 参数级动作白名单**。
 *
 * 双保险是必须的，不能只靠「不下发」：
 * 父 Agent 的 [ToolRegistry] 是全量的，子代理循环执行时拿到的是同一个 registry。
 * 一旦模型幻觉/越界调了未授权工具，[SubAgentToolPolicy.validate] 会在**执行前**挡下来并回一条错误，
 * 让子代理自己改路，而不是真的写文件/跑命令。
 */
object SubAgentToolPolicy {

    private val gson = Gson()

    /** 子代理绝不能递归调用子代理（否则可以无限套娃烧额度）。 */
    // 注意 "todo" 也在禁止之列：子代理并发跑，若各自替换父级会话的任务清单，
    // 后提交的会整体覆盖先提交的，面板内容不可预期。
    private val NEVER = setOf("subagent_dispatch", "call_sub_agent", "talk_to_agent", "todo", "workflow")

    /** 只读观察类：文件、代码、网页、记忆读取、设备信息。 */
    private val READ_ONLY: Set<String> = setOf(
        "readFile", "file_manager", "file_analyzer", "explore", "search_code",
        "view_image", "screenshot",
        "web_search", "web_fetch", "browser",
        "memory_read", "skills_list", "device_info"
    )

    /** 记忆管理员额外可写。 */
    private val MEMORY_WRITE: Set<String> = setOf("memory_save", "memory_delete")

    /** file_manager 的只读动作（write/delete 必须挡住）。 */
    private val FILE_MANAGER_READ_ACTIONS = setOf("list", "read", "info", "search", "analyze")

    /** browser 的只读动作（click/fill/evaluate/dialog 等会改远端状态或执行 JS，必须挡住）。 */
    private val BROWSER_READ_ACTIONS = setOf(
        "navigate", "getText", "getHtml", "getBackbone", "screenshot", "console",
        "wait", "scroll", "back", "forward", "reload", "listTabs", "selectTab"
    )

    /** 该角色是否**可以调用**这个工具（不下发层面的判定）。 */
    fun allows(profileId: String, toolName: String): Boolean {
        val name = toolName.removePrefix("functions.").trim()
        if (name in NEVER) return false
        // 强制只读（设置 → 子代理设置）：所有角色的权限面收敛到只读集合。
        // 放在这里而不是 toolsFor 里过滤，是为了让**执行前**的 validate 复用同一条判定，
        // 不会出现「schema 没下发但执行校验放行」这种两套口径。
        if (readOnlyLocked()) {
            return when (profileId) {
                "planner" -> false
                // 记忆写入保留：那是 memory-curator 的职责本身，范围可控、可单条删除
                "memory-curator" -> name in READ_ONLY || name in MEMORY_WRITE
                else -> name in READ_ONLY
            }
        }
        return when (profileId) {
            // 规划器没有工具：显式空集，绝不回落成「全给」
            "planner" -> false
            "explore" -> name in READ_ONLY
            "memory-curator" -> name in READ_ONLY || name in MEMORY_WRITE
            // general 继承父级能力：只扣掉 NEVER 里那些不该复制的
            else -> true
        }
    }

    /**
     * 参数级校验：返回非 null 表示**拒绝**并给出原因。
     * 只对「同一工具内部读写混合」的场景有意义（file_manager / browser / memory_*）。
     */
    fun validate(profileId: String, toolName: String, argsJson: String): String? {
        val name = toolName.removePrefix("functions.").trim()
        if (!allows(profileId, name)) {
            return "工具「$name」超出 ${SubAgentProfiles.get(profileId).displayName} 的权限范围，不允许调用。"
        }
        if (name != "file_manager" && name != "browser") return null
        val action = try {
            JsonParser.parseString(argsJson.ifBlank { "{}" })
                .asJsonObject.get("action")?.asString?.trim()?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return null
        val allowed = when (name) {
            "file_manager" -> FILE_MANAGER_READ_ACTIONS
            "browser" -> BROWSER_READ_ACTIONS
            else -> emptySet()
        }
        // general / memory-curator 不受动作级限制；强制只读时例外——
        // 否则 general 在只读模式下仍能用 file_manager 的 write 动作绕开工具层。
        if (profileId == "general" && !readOnlyLocked()) return null
        if (action in allowed) return null
        val reason = if (readOnlyLocked()) {
            "「${SubAgentProfiles.get(profileId).displayName}」当前处于强制只读模式"
        } else {
            "${SubAgentProfiles.get(profileId).displayName}只允许 ${allowed.joinToString("/")}"
        }
        return "「$name」的动作「$action」会修改状态，$reason。"
    }

    /**
     * 按角色裁剪父级工具列表。
     * planner 返回空列表——注意调用方需把空列表转成 null（不下发 tools 字段），
     * 有些网关对 `"tools": []` 会直接报错。
     */
    fun toolsFor(profileId: String, all: List<ToolDef>): List<ToolDef> =
        all.filter { allows(profileId, it.function.name) }

    /**
     * 某「读写混合」工具的只读动作集合（file_manager / browser）。
     *
     * 主链路审批（`MainViewModel.buildToolApprover`）与子代理策略共用这一份，
     * 免得出现「子代理按动作拦、主链路只看工具名就放行」的口径漂移。
     */
    fun readOnlyActionsOf(toolName: String): Set<String> = when (toolName.removePrefix("functions.").trim()) {
        "file_manager" -> FILE_MANAGER_READ_ACTIONS
        "browser" -> BROWSER_READ_ACTIONS
        else -> emptySet()
    }

    /** 单个工具默认是否启用（子代理默认不下发 mcp/自定义工具，避免 schema 膨胀）。 */
    fun includeByDefault(profileId: String, toolName: String): Boolean {
        val name = toolName.removePrefix("functions.").trim()
        if (name == "mcp") return false
        return allows(profileId, name)
    }

    /**
     * 「强制只读」开关。读 AppContextHolder 的当前设置而不是走构造注入：
     * 权限判定必须对「下发 schema」和「执行前校验」两处同时生效，
     * 从同一个实时来源取值才不会两边口径漂移（也避免漏改某个调用点）。
     */
    private fun readOnlyLocked(): Boolean =
        AppContextHolder.currentSettings?.subagentReadOnly == true

    internal fun prettyArgs(argsJson: String): String = try {
        gson.toJson(JsonParser.parseString(argsJson.ifBlank { "{}" }))
    } catch (_: Exception) {
        argsJson.take(200)
    }
}
