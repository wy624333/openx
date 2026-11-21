package com.wy.openx.plugin

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

/**
 * OpenX 插件（Plugin）模型。
 *
 * 「JS 工具 + 快捷入口」两件事，
 * 未做 Compose DSL、未做运行时 Hook、未做 WASM —— 那几样要么性价比低，要么会与
 * 「每版同步作者源码」的流程长期冲突（详见调研结论）。
 *
 * 存储布局（与 SkillStore 同源，便于文件浏览器/容器内直接读写）：
 * ```
 * filesDir/openx/plugins/<pluginId>/plugin.json   # 清单
 * filesDir/openx/plugins/<pluginId>/main.js       # 入口脚本
 * filesDir/openx/plugins/<pluginId>/...           # 插件自带资源
 * filesDir/openx/plugins.json                     # 启停名单（仅存禁用名单）
 * ```
 */
data class PluginManifest(
    /** 插件唯一 ID，建议反向域名；同时作为目录名（会做安全过滤）。 */
    @SerializedName("id") val id: String,
    /** 显示名。 */
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("author") val author: String = "",
    /** 入口脚本相对路径，默认 main.js。 */
    @SerializedName("main") val main: String = "main.js",
    /**
     * 本插件提供的快捷入口。**这是侧边栏快捷区那块的数据来源** ——
     * 插件声明几条，侧边栏就渲染几个；加插件不需要动 UI 代码。
     */
    @SerializedName("quickActions") val quickActions: List<PluginQuickAction> = emptyList(),
    /** 插件要求的宿主 API 版本，暂时只做展示与将来兼容判断。 */
    @SerializedName("apiVersion") val apiVersion: String = "1.0.0",

    /**
     * 兼容轨道标记。空 = 原生 OpenX 插件；`"operit"` = 从 Operit ToolPkg 适配而来。
     *
     * 标了 `operit` 的插件**入口脚本由宿主动态生成**（`OperitCompat.buildEntrySource`），
     * 不读 [main] 指向的文件 —— 这样兼容层的改进能直接作用于已安装的插件，
     * 不用让用户重新导入。
     */
    @SerializedName("compat") val compat: String = "",

    /**
     * 权限声明。不写 = 不给任何权限（只能算纯 JS 工具）。
     * 见 [PluginPermission] 的说明：这些权限在 ctx API 里**真正强制检查**。
     */
    @SerializedName("permissions") val permissions: List<String> = emptyList(),

    /**
     * UI 入口脚本（Compose DSL）。声明后，插件管理页会出现「打开界面」入口。
     * 典型值 `"ui/panel.ui.js"`。为空表示这个插件没有界面，只有工具。
     */
    @SerializedName("ui") val ui: String = "",

    /**
     * 服务入口脚本（服务型插件）。声明后，插件管理页会出现「启动服务」入口，
     * 启动后宿主在后台跑一个独立 QuickJS 引擎执行该脚本，并接管 HTTP 端口监听。
     * 典型值 `"service/gateway.js"`。为空表示这个插件没有后台服务。
     * 需要 `server` 权限。
     */
    @SerializedName("service") val service: String = "",

    /**
     * 服务默认监听端口（服务型插件）。启动时端口被占用则自动顺延 +1，
     * 最多尝试 10 个。实际端口由宿主启动后回传给插件 JS（onStart 参数）。
     * 为 0 且未声明 [service] 时不绑端口（纯定时任务插件）。
     */
    @SerializedName("port") val port: Int = 0,

    /**
     * 定时任务声明。每项一条，需要 `schedule` 权限。
     *
     * ```json
     * "schedule": [
     *   { "id": "poll", "every": "30m" },
     *   { "id": "morning", "at": "08:00" }
     * ]
     * ```
     * - `every`：固定间隔。支持 `30s` / `10m` / `2h` / 纯数字（毫秒）。
     * - `at`：每天固定时刻 `HH:mm`（本地时区）。
     * - 两者写一个；都写时以 `every` 优先。
     *
     * 回调是脚本导出的 `onSchedule(taskId, info)`。注意：调度器**跑在 App 进程里**，
     * App 被系统杀掉后不会自动拉起（Android 后台限制），下次打开 App 会重新按点跑。
     *
     * **为何声明为可空**：Gson 用 Unsafe 绕过构造函数创建对象，JSON 里没这个字段时
     * 字段会是 null 而不是 `emptyList()`。旧版本装的插件都没有 schedule 字段，
     * 声明成非空会让 `manifest.copy(...)` 直接 NPE。统一用 [scheduleTasks] 读。
     */
    @SerializedName("schedule") val schedule: List<PluginSchedule>? = null,

    /**
     * 是否在 App 启动时自动恢复浮窗（桌面宠物这类需要）。
     *
     * 浮窗活在宿主进程里，进程一死就没了。声明 `true` 后，下次打开 App 会按
     * 上次显示的尺寸/样式重新拉起 —— **前提是上次退出时它还开着**；
     * 用户点过「收回」就不会再自动弹。
     *
     * 可空是硬要求：Gson 不套用 Kotlin 默认值，没这个字段的旧插件会得到 null。
     */
    @SerializedName("overlayAutoStart") val overlayAutoStart: Boolean? = null,

    /**
     * 插件自带配色（十期主题定制）。key → `#hex`，可用 key：
     * `primary` / `surface`（或 `background`）/ `text` / `textSecondary` /
     * `border` / `error` / `success` / `warning`。
     *
     * 声明后：写了 `color: 'primary'` 这类 token 的组件会取这里的值；
     * 连 color 都没写的组件也会按语义跟着变（见 `UiNode.color`）。
     */
    @SerializedName("theme") val theme: Map<String, String>? = null,

    /**
     * 本插件对外提供的 AI 供应商（OpenX 扩展，Operit 无此字段）。
     *
     * 声明后宿主在插件**启用时**把它登记进「API 配置」的供应商列表（打上「插件」标记），
     * 禁用/卸载时连带移除，用户不用自己填 baseUrl。适合「插件里跑了个本机
     * OpenAI 兼容服务」这类形态。
     *
     * **为何声明为可空**：同 [schedule]，Gson 不套用 Kotlin 默认值，JSON 里没这个字段时
     * 字段会是 null；声明成非空会让 `manifest.copy(…)` 直接 NPE，而 `load()` 要遍历
     * 所有已安装插件，一个缺字段的旧插件能带倒整个插件列表。
     */
    @SerializedName("aiProvider") val aiProvider: PluginAiProvider? = null,

    /**
     * 插件声明的配置项（OpenX 扩展）。宿主在「插件配置」页把它们渲染成原生控件，
     * 值写进插件 env，插件侧 `ctx.getEnv(key)` 读。
     *
     * 可空理由同 [schedule]：Gson 不套用 Kotlin 默认值，旧插件没这个字段就是 null，
     * 声明成非空会让整个插件列表崩。读的时候走 [settingItems]。
     */
    @SerializedName("settings") val settings: List<PluginSetting>? = null
) {
    /** 解析后的权限集。 */
    val grantedPermissions: Set<PluginPermission>
        get() = PluginPermission.parse(permissions)
    /** 目录名安全过滤：去掉路径分隔与上跳，防止插件用 id 越权写别处。 */
    val safeDirName: String
        get() = id.trim()
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .trim('.')
            .ifBlank { "plugin" }

    /**
     * 是否有「后台部分」—— 声明了 service 脚本，或有可调度的定时任务。
     *
     * 管理页用它决定要不要显示「启动/停止」按钮。注意：**不再要求 `server` 权限**——
     * 有些插件（如容器里的 JVM / Node 网关）的 service 脚本只是用 `ctx.exec` 管理
     * 容器进程，根本不需要宿主开 HTTP 端口；这类插件以前不会被当成后台插件，
     * 「插件配置」页里就显得什么也没有。
     */
    val hasBackground: Boolean
        get() = service.isNotBlank() || scheduleTasks.any { it.isValid() }

    /** 非空的配置项列表（容忍 Gson 反序列化出的 null）。 */
    val settingItems: List<PluginSetting>
        get() = settings.orEmpty()

    /** 非空的定时任务列表（容忍 Gson 反序列化出的 null）。 */
    val scheduleTasks: List<PluginSchedule>
        get() = schedule.orEmpty()

    /** 是否要在 App 启动时自动恢复浮窗（容忍 null）。 */
    val autoStartOverlay: Boolean
        get() = overlayAutoStart == true
}

/**
 * 快捷入口：点击后向聊天页**填入一段提示词**（可选自动发送）。
 *
 * 之所以不做「点击直接执行 JS」：快捷入口的用户预期是「帮我起个头」，
 * 灌提示词让模型带着工具去调用，比插件自己闷头跑更有用，也不需要
 * 在侧边栏上下文里持有 JS 运行时。
 * 注意：所有字段声明为可空（带 `orEmpty()` 兑底）。
 * Gson 反序列化 Kotlin data class 时**不应用字段默认值**，清单里缺哪个字段
 * 解出来就是 null。之前 url 写成非空 String，旧清单（没有这个字段）一解析
 * 就得到 null，而 `PluginQuickBar.collectQuickGroups` 里的 `it.copy()` 会做
 * Kotlin 非空校验，直接抛 NPE 把整个插件列表加载带崩。
 * 宁可在使用处多写几个 `orEmpty()`，也不能让一个可选字段把全局拖挂。
 */
data class PluginQuickAction(
    @SerializedName("label") val label: String? = null,
    /**
     * 点击后填进输入框的提示词。
     *
     * `action = "openUi"` 时可省略；其余情况必填。
     */
    @SerializedName("prompt") val prompt: String? = null,
    /** 可选的材料图标名（映射见 PluginIcons），为空则用默认图标。 */
    @SerializedName("icon") val icon: String? = null,
    /** true = 填入后立刻发送；false（默认）= 只填入，等用户确认。 */
    @SerializedName("autoSend") val autoSend: Boolean = false,
    /**
     * `action = "openUrl"` 时要打开的地址。
     *
     * 典型场景：插件在容器里起了本地网页服务，需要拉起到手机浏览器里打开
     * （WebView 里有些页面用不了，比如需要系统下载/分享/长按选择时）。
     */
    @SerializedName("url") val url: String? = null,
    /**
     * 动作类型：
     *   - `"prompt"`（默认）：把 [prompt] 填入聊天输入框，可选自动发送。
     *   - `"openUi"`：直接打开本插件的 Compose DSL 界面。
     *   - `"openUrl"`：用系统浏览器打开 [url]。
     *
     * 之前只有「填提示词」一种行为，导致「打开面板」这类入口名不副实，
     * 所以补上动作类型让快捷区能真正触发界面。
     */
    @SerializedName("action") val action: String? = null
) {
    /** 动作类型（空值视为默认的 prompt）。 */
    val actionOrPrompt: String get() = action?.takeIf { it.isNotBlank() } ?: "prompt"

    /** 是否打开插件界面。 */
    val opensUi: Boolean get() = actionOrPrompt.equals("openUi", ignoreCase = true)

    /** 是否用系统浏览器打开外部地址。 */
    val opensUrl: Boolean get() = actionOrPrompt.equals("openUrl", ignoreCase = true)

    /**
     * 来源插件 id。**不写在 plugin.json 里**，由 [collectQuickGroups] 在组装时填入，
     * 这样点击回调（只拿到 action）才知道该开哪个插件的界面。
     */
    @Transient
    var ownerPluginId: String = ""
}

/**
 * 已安装插件的运行时视图：清单 + 落盘位置 + 启用状态 + 加载结果。
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    /** 插件目录绝对路径。 */
    val dirPath: String,
    val enabled: Boolean,
    /** 脚本是否已成功加载进 QuickJS。 */
    val loaded: Boolean = false,
    /** 该插件注册出的工具名列表（加载成功后才有）。 */
    val toolNames: List<String> = emptyList(),
    /** 加载/启用过程中的错误信息，供管理页红字提示。 */
    val error: String? = null
)

/**
 * 侧边栏快捷区的**一行**：一个插件 + 它声明的快捷项。
 *
 * ## 为什么按插件分组
 * 第一版把所有插件的快捷项平铺成一行。插件一多，就分不清哪个胶囊属于哪个插件，
 * 也没地方点「打开这个插件的界面」。改成每插件一行后：
 *   · 行首是插件名 —— 点击直接开界面（插件没声明 ui 时置灰）
 *   · 行内横向滚动该插件的快捷项 —— 一行里既能选插件、又能选具体入口
 *
 * 纯运行时结构，不写进 plugin.json，也不参与序列化。
 */
data class PluginQuickGroup(
    val pluginId: String,
    /** 展示用插件名；为空时回退到 id。 */
    val pluginName: String,
    /** 该插件是否声明了界面（决定行首能不能点开）。 */
    val hasUi: Boolean,
    /** 本行要渲染的快捷项，按 plugin.json 声明顺序。 */
    val actions: List<PluginQuickAction>
)

/**
 * 一条定时任务声明（见 [PluginManifest.schedule]）。
 *
 * 只支持「固定间隔」和「每天某时刻」两种，不做完整 cron：
 * cron 表达式的解析和时区边界容易写错，而插件 99% 的需求就是这两类。
 */
data class PluginSchedule(
    /** 任务标识，回调时原样回传，插件用它区分是哪个任务。 */
    @SerializedName("id") val id: String = "",
    /** 固定间隔，如 `30s` / `10m` / `2h` / `5000`（毫秒）。 */
    @SerializedName("every") val every: String = "",
    /** 每天固定时刻 `HH:mm`（本地时区）。 */
    @SerializedName("at") val at: String = ""
) {
    /** 解析出的间隔毫秒数；`every` 为空或非法时返回 null。 */
    fun intervalMs(): Long? {
        val raw = every.trim().lowercase()
        if (raw.isEmpty()) return null
        val m = Regex("^(\\d+)\\s*(ms|s|m|h)?$").find(raw) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = m.groupValues[2]
        val ms = when (unit) {
            "", "ms" -> n
            "s" -> n * 1000
            "m" -> n * 60_000
            "h" -> n * 3_600_000
            else -> return null
        }
        // 下限 10 秒：再快就是轮询刷屏，且 QuickJS 单线程扛不住
        return if (ms < 10_000L) 10_000L else ms
    }

    /** 解析出的「当天第几分钟」；`at` 为空或非法时返回 null。 */
    fun minuteOfDay(): Int? {
        val raw = at.trim()
        if (raw.isEmpty()) return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    /** 是否可调度（至少要有一个合法的时间表达式）。 */
    fun isValid(): Boolean = intervalMs() != null || minuteOfDay() != null
}
/**
 * 插件对外提供的 AI 供应商（清单里的 `aiProvider` 字段）。
 *
 * 只描述「怎么连」，不参与发消息逻辑：宿主按 baseUrl/apiKey 建一条普通的 OPENAI
 * 类型供应商记录，之后与用户手填的供应商走完全同一条链路。
 *
 * 字段全部可空：这个类同样由 Gson 反序列化，缺字段时是 null 而不是默认值
 * （见 [PluginManifest.aiProvider] 的说明），使用方一律 `orEmpty()`。
 */
data class PluginAiProvider(
    /** 供应商显示名，如「AI 网关（本机）」；留空时回退成插件名。 */
    @SerializedName("name") val name: String? = null,
    /** OpenAI 兼容根地址，如 `http://127.0.0.1:18791/v1`。 */
    @SerializedName("baseUrl") val baseUrl: String? = null,
    /** 服务端要求的 Key；本机服务不校验时留空。 */
    @SerializedName("apiKey") val apiKey: String? = null,
    /** 默认模型名；留空时用户在供应商里自己拉取或选择。 */
    @SerializedName("model") val model: String? = null
)

/**
 * 插件声明的一个配置项（清单里 `settings` 数组的元素）。
 *
 * 控件类型由 [type] 决定，**宿主负责渲染**；值以字符串存进插件 env，
 * 插件侧 `ctx.getEnv(key)` 拿到。这样宿主不必执行插件代码就能画出配置页。
 *
 * 支持的 [type]：
 * | 值 | 渲染成 | 说明 |
 * |---|---|---|
 * | `switch` | 开关 | 值存 `"true"` / `"false"` |
 * | `segment` | 分段选择（一行胶囊） | 必填 [options] |
 * | `slider` | 滑杆 | 用 [min] / [max]，值存整数字符串 |
 * | `text` | 单行输入框 | 用 [placeholder] |
 * | `number` | 数字输入框 | 数字键盘 |
 *
 * 认不出的类型按 `text` 处理，不会让整页渲染不出来。
 *
 * 字段全部可空：同 [PluginAiProvider]，Gson 不套用 Kotlin 默认值，缺字段就是 null。
 */
data class PluginSetting(
    /** 存进 env 的键名。留空则整项被忽略（没有键就没法存值）。 */
    @SerializedName("key") val key: String? = null,
    /** 控件类型，见类文档。留空按 `text` 处理。 */
    @SerializedName("type") val type: String? = null,
    /** 显示标题。留空则直接不显示标题（只有控件）。 */
    @SerializedName("title") val title: String? = null,
    /** 副说明，显示在标题下方的小字。 */
    @SerializedName("desc") val desc: String? = null,
    /** `segment` 的候选项。 */
    @SerializedName("options") val options: List<PluginSettingOption>? = null,
    /** 默认值：env 里还没有这个键时，宿主在插件加载时写进去。 */
    @SerializedName("default") val defaultValue: String? = null,
    /** `slider` 的下界，默认 0。 */
    @SerializedName("min") val min: Double? = null,
    /** `slider` 的上界，默认 100。 */
    @SerializedName("max") val max: Double? = null,
    /** `text` / `number` 的占位文字。 */
    @SerializedName("placeholder") val placeholder: String? = null,
    /** 分组标题：相邻同组的项会归到一个带标题的小节里；留空则归入无标题组。 */
    @SerializedName("group") val group: String? = null
)

/** [PluginSetting.options] 的一项。 */
data class PluginSettingOption(
    /** 显示文字。留空则回退成 [value]。 */
    @SerializedName("label") val label: String? = null,
    /** 存进 env 的值。 */
    @SerializedName("value") val value: String? = null
)
