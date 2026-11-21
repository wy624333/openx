package com.wy.openx.data.model

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/** 已发送/已生成消息附带的附件：渲染气泡下方附件行 + 点击打开。 */
data class MessageAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
)

data class ChatMessageModel(
    val id: Long,
    val role: MessageRole,
    val author: String,
    val time: String,
    val content: String,
    val isCode: Boolean = false,
    val isSteps: Boolean = false,
    val steps: List<String> = emptyList(),
    val imageUri: String? = null,
    val attachments: List<MessageAttachment> = emptyList(),
    val tokens: Int? = null,
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val timeMs: Long? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolResult: String? = null,
    /**
     * 工具执行中的实时进度（子代理并行分派时逐行累积；其他工具为 null）。
     * 不落库：只用于当轮 UI 展示，工具完成后由 toolResult 取代。
     */
    val toolProgress: String? = null,
    /**
     * 已被压缩摘要取代、但仍保留在库里的历史消息（只用于 UI 翻阅，不进请求）。
     *
     * ⚠ **只影响「进不进模型请求」，不影响渲染**：被压缩的消息在聊天页就是普通气泡，
     * 与压缩前长得一模一样。
     * 压缩的唯一可见产物是那条「上下文已压缩」卡片，消息结构本身不变。
     */
    val folded: Boolean = false,
    /**
     * 会话内单调递增的**显示序号**（库统一分配，见 `MessageEntity.seq`）。
     *
     * 必须穿过模型层：压缩时新插入的 marker/摘要要按「压缩发生的时刻」排序，
     * 而它俩此刻还没有真实的库 id；若 seq 在模型层丢失（落库时取默认 0），
     * 排序就会把它们顶到所有历史之前 —— 卡片跑到最上面、历史看着全乱。
     */
    val seq: Long = 0,
    /**
     * 排队中：AI 正在生成时用户发的消息，已在聊天页显示且已落库，但尚未发给模型。
     * 落库是刻意的——否则排队期间进程被杀就彻底丢失，且聊天页看不到这条消息。
     */
    val queued: Boolean = false
)

data class ConversationModel(
    val id: Long,
    val title: String,
    val meta: String,
    val active: Boolean = false,
    val requestCount: Int = 0,
    val lastInputTokens: Int = 0,
    /** 手动置顶：常驻列表最前。 */
    val isPinned: Boolean = false,
    /** 最近活跃时间（epoch millis）：发消息时刷新，用于「最近聊天上浮」。 */
    val lastActiveAt: Long = 0,
    /** 已归档：收进「历史」页，数据保留（非删除）。 */
    val isArchived: Boolean = false,
    /**
     * 本会话显式选定的提供商 id（「模型选择模式」开关开启时写入）。
     * null = 未选择过 → 跟随全局当前提供商。
     */
    val providerId: String? = null,
    /** 本会话显式选定的模型名。null = 跟随该提供商的当前选中模型。 */
    val model: String? = null,
    /**
     * 标题是否已定稿：AI 生成成功或用户手动改名。true 后不再自动生成标题。
     * 持久化以扛住 App 重启（原先只在内存，重启即重生成并覆盖）。
     */
    val titleFinalized: Boolean = false
)

data class AgentModel(
    val id: Long,
    val name: String,
    val desc: String,
    val status: String,
    val selected: Boolean = false
)

data class ToolCallModel(
    val id: Long,
    val name: String,
    val detail: String,
    val state: String
)

data class StatModel(val label: String, val value: String)

data class ApiPreset(
    val name: String, val provider: String,
    val baseUrl: String, val model: String,
    val apiKey: String = "",
    val note: String = "", val isCustom: Boolean = false,
    val contextTokens: Long? = null,
    val outputTokens: Long? = null
)

data class SkillPreset(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val instructions: String = "",
    /**
     * 技能作用域（与记忆一致）：`"global"` = 全局技能（跨项目共享）；`"project"` = 项目技能
     * （按当前项目隔离，存在于 skills/projects/<项目>/）。字段由**存储位置**决定，不落 SKILL.md。
     */
    val scope: String = com.wy.openx.data.store.SCOPE_GLOBAL
)

data class TaskItem(
    val id: Long,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    /**
     * 显示顺序（升序）。**快照式 todo 工具的数组下标**即此值，
     * 面板按它排序显示，与执行时序无关。
     *
     * UI 手动新增的任务取「当前最大值 + 1」排到末尾。
     */
    val order: Int = 0
)

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }

data class QuestionInfo(
    val question: String,
    val options: List<String> = emptyList(),
    val allowCustom: Boolean = true,
    val multiSelect: Boolean = false,
    /**
     * 多问题模式（`ask_user` 的 `questions` 参数，1–4 问）：非空时 UI 走多问面板，
     * [question]/[options] 只是第一问的副本，便于旧代码兜底。
     */
    val items: List<QuestionItem> = emptyList()
)

/** 一个带说明的选项。 */
data class QuestionOption(
    val label: String,
    val description: String = ""
)

/** 多问题模式下的单个问题。 */
data class QuestionItem(
    val question: String,
    /** 短标题（如「方案」「范围」），UI 用作小标签。 */
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val allowCustom: Boolean = true,
    val multiSelect: Boolean = false
)

data class PendingToolCallInfo(
    val toolName: String,
    val toolCallId: String,
    val arguments: String,
    val description: String,
    /**
     * 人类可读的动作摘要，如「写入 ~/workspace/notes.md」。
     *
     * 工具参数是 JSON，原样摊给用户看很难读（file_manager 的 write 还带整篇转义过的正文），
     * 所以按工具抽关键信息。为空表示没生成出摘要（未知工具），UI 会退回显示原始参数——
     * 宁可难看也别丢信息。
     */
    val actionLine: String = "",
    /** 动作附带的元信息（字符数 / 行数 / 写入模式…），UI 逐行列出。 */
    val extras: List<String> = emptyList(),
    /**
     * 「始终允许」到底会记住什么（按会话当前工具生成）。
     *
     * shell 类工具由权限策略引擎给出**指令前缀**（避免点一次就把 `rm -rf /` 也放行），
     * 其它工具记的是整个工具；引擎判定不可记忆时这里写原因。
     */
    val alwaysAllowHint: String = ""
)

/**
 * 待确认工具调用的策略评估结果（shell 类工具）。
 * 「始终允许」据此记忆**指令前缀**；为空则不可记忆，只能单次放行。
 */
data class PendingPermissionEval(
    val toolName: String,
    val rememberablePatterns: List<String>,
    val rememberDisabledReason: String?
)

data class AppSettings(
    val provider: String = "SiliconFlow",
    val model: String = "Qwen/Qwen3.5-4B",
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "",
    /**
     * 系统提示词默认值 = 高级设置里「通用助手」模板的原文（用户要求：默认改通用助手）。
     *
     * 为什么默认给通用助手而不是那套很长的「资深全栈开发工程师」规范：
     * openx 是**通用**客户端（聊天 / 翻译 / 分析 / 写代码都用它），预设一个强编程人设会让
     * 非编程场景的回答莫名其妙地夹带代码块规范。原先默认提示词里还写着
     * 「所有代码用 ``` 代码块包裹」这类约束，对翻译、写作类任务是纯粹的干扰。
     *
     * 需要那套工程规范的用户，在「高级 → 系统提示词」里点一下「编程专家」模板即可。
     *
     * ⚠ 与 `SettingsStore` 里 `prefs[...] ?: ...` 的兜底值必须保持一致，
     * 否则新装（没写进 DataStore）与首帧默认值会不一致。
     */
    val systemPrompt: String = "你是一个聪明、有用的AI助手。",
    val temperature: Float = 0.7f,
    /**
     * 单次回复的最大输出 token。
     *
     * 默认 16384（而非 4096）：写代码/大文件时，内容作为**模型输出**吐出来，
     * 4096 会在长文件写到一半被硬截断 → 工具参数是残缺 JSON → 写出半截文件。
     * 16384 能覆盖大多数单文件；更长的文件仍建议用 writeFile + appendFile 分块。
     */
    val maxTokens: String = "16384",
    /**
     * 压缩摘要请求的最大输出 token（与 [maxTokens] 相互独立）。
     *
     * 摘要本身不长，且输出里常含思维链 token，4096 足够；单独成项是为了不让它跟着「最大输出」
     * 一起被调大/调小——写大文件需要大 maxTokens，但摘要没必要跟着放大。
     */
    val compactionMaxTokens: String = "4096",
    val enableStream: Boolean = true,
    val autoToolCall: Boolean = true,
    val currentProject: String = "",
    /**
     * 工具调用方式：true=并行（同批已批准工具并发执行），false=串行（逐个执行）。
     *
     * 默认并行（原为串行）：聊天页已能在频繁撞 429 时弹窗提示并一键切回串行，
     * 有了这个兜底，先拿并行的速度、撞限流再降级，比一律默认慢更划算。
     */
    val parallelToolCalls: Boolean = true,
    val showLogs: Boolean = true,
    /**
     * 上下文压缩阈值是否跟随系统默认（90%）。移植自 rc7「上下文压缩」设置：
     * - true（默认）：始终按 90% 触发（等价 rc7 的「使用默认」开关开启）；
     * - false：使用 [contextRatio] 手动的百分比（滑块 50%~95%）。
     */
    val compactUseDefault: Boolean = true,
    val selectedApiPreset: String = "SiliconFlow",
    /**
     * 当前激活的提供商 id（ai_providers 表主键）。空串表示未绑定，
     * 请求层回退到扁平字段（provider/baseUrl/apiKey/model）——兼容旧数据。
     */
    val selectedProviderId: String = "",
    val customApiName: String = "我的自定义接口",
    val customApiUrl: String = "",
    val customApiModel: String = "",
    val language: String = "跟随系统",
    val themeName: String = "系统默认",
    /**
     * 主题模式（浅色/深色/跟随系统），与 [themeName] 正交：
     * [themeName] 决定**配色**，本字段决定**明暗**。每个配色都有浅/深两套。
     *
     * - `"system"`（默认）：跟随系统深浅；
     * - `"light"`：强制浅色；
     * - `"dark"`：强制深色。
     *
     * 与 [themeName] = "系统默认" 的区别：后者是「选哪套配色（默认是浅米配色）」，
     * 本字段是「明暗切换」。两者叠加：themeName 选配色、themeMode 选明暗。
     * 注意同名陷阱：[themeMode] = "system" 的档位显示名仍是「跟随系统」，
     * 而 [themeName] 那一档已在 2.5.1 改名为「系统默认」——两者指的是不同维度。
     */
    val themeMode: String = "system",
    val rules: String = "",
    /**
     * 界面字号档位：`"small"` / `"medium"` / `"large"` / `"custom"`。
     * 前三个是预设档（横屏还会再降一档）；`"custom"` 表示改用 [fontSizeCustomSp] 的绝对 sp 值。
     */
    val fontSize: String = "medium",

    /** [fontSize] == "custom" 时的字号（sp），由设置页外观区的滑杆调节。 */
    val fontSizeCustomSp: Int = 15,

    /**
     * 界面整体缩放档位：`"small"` / `"standard"` / `"large"` / `"custom"`。
     *
     * 与 [fontSize] 是两回事：字号只改文字大小，本项按比例缩放**全部 dp 与 sp**
     * （间距、图标、控件高度一并变），用来抹平不同分辨率设备上的观感差异。
     * `"custom"` 时用 [uiScaleCustom]。
     */
    val uiScalePreset: String = "standard",

    /** [uiScalePreset] == "custom" 时的倍率（1.0 = 原始尺寸）。 */
    val uiScaleCustom: Float = 1.0f,

    // ===== 外观设置子页面：聊天文本 =====
    /** 聊天文本颜色模式：`"auto"` 自动跟随背景明暗 / `"custom"` 用 [chatTextHexColor]。 */
    val chatTextColorMode: String = "auto",
    /** 自定义聊天文本色号，`#RRGGBB` 或 `#AARRGGBB`；仅 [chatTextColorMode] = custom 时生效。 */
    val chatTextHexColor: String = "",

    // ===== 外观设置子页面：背景图 =====
    /** 是否启用背景图（全应用）。关闭时下列字段保留但不渲染。 */
    val bgEnabled: Boolean = false,
    /** 背景来源：`"none"` / `"local"`（本地图片）/ `"remote"`（图片直链）。 */
    val bgSourceType: String = "none",
    /** 本地图片的宿主绝对路径。 */
    val bgLocalPath: String = "",
    /** 图片直链（http/https）。 */
    val bgRemoteUrl: String = "",
    /** 背景柔化强度（0..24），作用于图片上方的蒙版模糊。 */
    val bgBlurSigma: Float = 8f,
    /** 蒙版强度（0..0.55），越高页面元素越干净、背景越淡。 */
    val bgOverlayAlpha: Float = 0.18f,
    /** 蒙版明暗（0.5..1.5），提亮或压暗蒙版，不改原图。 */
    val bgBrightness: Float = 1f,
    /** 图片焦点 X（-1..1），与 [bgFocalY] 一起决定缩放/裁剪的锚点。 */
    val bgFocalX: Float = 0f,
    /** 图片焦点 Y（-1..1）。 */
    val bgFocalY: Float = 0f,
    /** 图片缩放（1..3），预览里双指缩放的持久化值。 */
    val bgImageScale: Float = 1f,
    val showTimestamp: Boolean = false,
    val hapticFeedback: Boolean = true,
    val notifyOnComplete: Boolean = false,
    val vibrateOnComplete: Boolean = false,
    val notificationSound: String = "default",
    val contextWindow: String = "",
    val autoScroll: Boolean = true,
    /**
     * 侧边栏「插件快捷区」是否展开。
     *
     * 默认 **折叠**（false）：插件多了会把侧边栏撑满、压掉下方会话列表。
     * 用户在设置页的偏好设置里切换，或在侧边栏点标题行/箭头就地切换（就地切换同样写回这里，
     * 保证下次启动保持一致）。
     */
    val pluginQuickBarExpanded: Boolean = false,
    val contextRatio: Float = 0.8f,
    val thinkingAutoFoldSeconds: Int = 0,
    /**
     * 聊天页思考块是否始终展开。
     *
     * false（默认）：思考进行中自动展开，这段思考一结束就自动折叠；
     * 用户手动点过的块不再被自动改。
     */
    val thinkingAlwaysExpanded: Boolean = false,
    // 用户通过「添加本地目录」注册的设备本地目录（宿主绝对路径，容器内映射到 /root/local/<name>）
    val externalWorkspaces: List<ExternalWorkspace> = emptyList(),
    // 规划模式下免除工具确认的「始终允许」工具白名单
    val toolAllowList: List<String> = emptyList(),

    /**
     * 工具权限档位（全局，对 构建/股票/规划 模式生效；聊天模式恒为只读、不参与）：
     * - `"allow"`：完全允许（默认），工具直接执行，不弹确认；
     * - `"workspace"`：仅工作区修改，工作区内的写/执行类工具直接放行（不弹窗）；
     *   越界时退回「弹窗确认」由用户定夺（`/root/workspace`、`/root/local` 为工作区）；
     * - `"confirm"`：弹窗确认，每次写/执行类工具调用前弹窗由用户决定；
     * - `"readonly"`：只读，**不下发**写/执行类工具给模型（模型无从调用）。
     */
    val toolPermissionMode: String = "allow",

    /**
     * 思考强度（推理强度），对应 OpenAI 兼容接口的 `reasoning_effort` 参数。
     * - `"default"`：模型默认（默认值）——**不发送**该参数，行为与改造前一致，避免不支持的网关报错；
     * - 其余取值（`none`/`minimal`/`low`/`medium`/`high`/`xhigh`/`max`）会写入请求体。
     */
    val reasoningEffort: String = "default",

    /**
     * 工具权限档的「全局设置模式」开关（默认关）。
     * 关：聊天页切档只改**当前会话**（会话级 override）。
     * 开：聊天页切档改**全局默认**，并把所有会话的 override 清空 → 全部统一跟随。
     */
    val toolPermissionGlobalMode: Boolean = false,

    /** 思考强度的「全局设置模式」开关，语义同 [toolPermissionGlobalMode]。 */
    val reasoningEffortGlobalMode: Boolean = false,

    /**
     * 模型选择的「全局设置模式」开关（**默认开**）。与上面两个开关**方向一致**：
     * **开（默认）= 全局模式**，聊天页切模型改全局当前提供商；
     * **关 = 会话级模式**，聊天页切模型只改当前会话（写入会话的 providerId/model 并持久化），
     * 不影响其他会话；新会话默认「未选择模型」，需用户自己选。
     *
     * 历史沿革：本字段原为 `modelSessionMode`（默认 `false`，且方向与另两个开关**相反**）。
     * 用户反馈「开=全局、关=会话级」才符合直觉（与 toolPermissionGlobalMode /
     * reasoningEffortGlobalMode 一致），故翻转语义并改名为 …GlobalMode。
     * 旧 DataStore 里的 `model_session_mode` 键在读取时按**取反**迁移（见 SettingsStore）。
     */
    val modelGlobalMode: Boolean = true,

    /**
     * 自动归档天数（偏好设置）：**0 = 关闭**（默认）。
     * 大于 0 时，启动会话列表后把「超过该天数未活跃」的会话批量归档进「历史」页。
     * 与加密传输等并列，属于「偏好设置」区。
     */
    val autoArchiveDays: Int = 0,

    /**
     * 启动时是否恢复上次关闭前的对话。
     * - `true`（默认）：恢复上次的当前会话，行为与本设置引入前一致；
     * - `false`：每次启动都新建一个会话。
     */
    val restoreLastConversation: Boolean = true,

    /**
     * 是否使用独立的发送按钮。
     * - `false`（默认）：键盘回车 = 直接发送；
     * - `true`：回车 = 换行，必须点发送按钮才发出。
     */
    val separateSendButton: Boolean = false,

    /** 任务运行期间保持屏幕常亮（等效 FLAG_KEEP_SCREEN_ON）。 */
    val keepScreenOnWhileRunning: Boolean = false,

    /** 后台保活：开一个前台服务常驻通知栏，降低被系统回收的概率。 */
    val backgroundKeepAlive: Boolean = false,

    /**
     * 悬浮窗（AI 运行进度小窗）。
     *
     * 开启后，当 AI 正在生成**且 openx 处于后台**时，用系统悬浮窗显示实时回复流与工具执行状态，
     * 方便切到别的应用时瞄一眼进度。回到 openx 或生成结束即自动隐去。
     *
     * 依赖 `SYSTEM_ALERT_WINDOW` 权限（「应用权限授权」页可跳转授予）；未授予时开关打开也不显示。
     */
    val floatingOverlay: Boolean = false,
    /**
     * AI 每条回复落地后自动朗读（系统 TTS）。
     *
     * 默认关：朗读会出声，不该在新装时就让用户措手不及。
     * 语音输入发起的那一轮**不受此开关影响**，始终朗读——对着手机说话本就期待被回话。
     */
    /**
     * 出站脱敏（默认开）：发给模型的正文/思考/工具参数里的密钥、手机号、身份证、
     * 银行卡等换成 `{{SECRET_xxx}}` 占位符，回复再回填成原文。
     *
     * 默认开：它不会改变用户看到的内容（双向可逆），但能防住供应商侧的泄漏。
     */
    /** 出站脱敏开关（默认**关**）。开着时发给供应商的正文/思考/工具参数里的凭据会被换成占位符。 */
    val maskOutbound: Boolean = false,
    val voiceAutoSpeak: Boolean = false,

    /**
     * 识图专用模型（providerId + model，来自「默认模型」设置；两值同时设为空即「跟随当前聊天模型」）。
     *「默认模型」分区：独立识图（view_image）调用使用该模型。
     */
    val visionProviderId: String = "",
    val visionModel: String = "",

    /**
     * 压缩专用模型（providerId + model，来自「默认模型」设置；两值同时设为空即「跟随当前聊天模型」）。
     * 上下文压缩（工具循环就地压缩 / 手动压缩）的摘要请求使用该模型，避免用主模型做大摘要耗额度。
     */
    val compactionProviderId: String = "",
    val compactionModel: String = "",

    /**
     * 起标题专用模型（providerId + model，来自「默认模型」设置；两值同时设为空即「跟随当前聊天模型」）。
     * 新建会话时的 AI 标题生成使用该模型。
     */
    val titleProviderId: String = "",
    val titleModel: String = "",

    /**
     * 知识提炼：每轮对话结束后交给一个独立模型读，抽出可复用的经验/踩坑/约定写进知识库
     * （`filesDir/openx/knowledge/`，容器内 `/root/.openx/knowledge/`）。
     *
     * 默认**关闭**：每轮都跑一次模型是有成本的，而且多数轮次没有值得沉淀的东西。
     * [knowledgeExtractEveryN] 控制攒几轮提炼一次（1 = 每轮，默认 3）——攒批同时省调用与噪声，
     * 攒下的几轮会合并成一次请求。
     */
    val knowledgeExtractEnabled: Boolean = false,
    val knowledgeExtractEveryN: Int = 3,

    /**
     * 知识提炼专用模型（providerId + model，来自「默认模型」设置；两值同时设为空即「跟随当前聊天模型」）。
     * 提炼是「读长文本 → 产出结构化 JSON」，用便宜快的小模型即可，没必要占主模型的额度。
     */
    val knowledgeProviderId: String = "",
    val knowledgeModel: String = "",

    /**
     * 子代理专用模型（providerId + model，来自「默认模型」设置；两值同时设为空即「跟随当前聊天模型」）。
     * subagent_dispatch 分派出去的子代理使用该模型：探索类任务通常可以塞便宜快的模型，
     * 通用/规划类留在主模型上更稳。未配置时子代理沿用当前聊天模型。
     */
    val subagentProviderId: String = "",
    val subagentModel: String = "",

    /**
     * 子代理并发上限（设置 → 子代理设置，1..5，默认 3）。
     * 只做**上限**：模型在 subagent_dispatch 里传的 concurrency 仍可更低。
     * 有状态工具（terminal / browser）并发会互相干扰，故默认压到 3；中转站限流或按量计费时可再压到 1。
     */
    val subagentConcurrency: Int = 3,

    /** 单个子代理超时秒数（30..7200，默认 1800）。改代码/大仓库探索容易慢，超时太短会半途截断。 */
    val subagentTimeoutSec: Int = 1800,

    /**
     * 单个子代理允许的工具调用轮次上限（1..200，默认 50）。
     *
     * 与 [subagentTimeoutSec] 是两个独立的熔断，**失败方式不同**，所以要一起看：
     * - 轮次触顶：发一次不带工具的收尾请求，模型基于已有信息给结论（拿不到就用兜底摘要）；
     * - 超时触顶：直接判 timeout，之前累积的上下文全丢。
     * 因此调大轮次时通常也要同步放宽超时，否则只是把熔断原因从「轮次」换成更糟的「超时」。
     */
    val subagentMaxRounds: Int = 50,

    /**
     * 子代理强制只读（设置 → 子代理设置，默认关闭）。
     * 开启后所有角色的权限面收敛到 explore 的只读集合：不得写文件、不得执行命令、
     * 不得做 browser 的 click/evaluate 等改状态动作。记忆库写入保留——那是 memory-curator 的职责本身，
     * 且范围可控、可单条删除。
     */
    val subagentReadOnly: Boolean = false,

    /**
     * 生图专用模型（providerId + model，来自「默认模型」设置）。
     * 与识图/压缩/起标题不同：生图**不跟随聊天模型**（聊天模型通常是纯文本模型，
     * 没有图像输出能力），配置后 generate_image 工具使用该模型调用 images/generations；
     * 未配置时生图工具直接报错并指引去设置。
     */
    val imageGenProviderId: String = "",
    val imageGenModel: String = "",

    /**
     * 设置 → 提示词：内置 AI 行为规则**总开关**（默认开）。
     *
     * 关 = `memory/agent/` 那套内置规则整段不再注入系统提示词（文件保留，随时可开回来）。
     * 单条的停用见 [agentRuleDisabled]；两者都只影响注入，不影响设置页展示。
     */
    val agentRulesEnabled: Boolean = true,

    /**
     * 设置 → 提示词：被**单独停用**的规则 key（文件名去 `.md`，如 `30-comments`）。
     *
     * 存 Set 而非 List：增删单条开关是高频动作，语义上无序；序列化为 JSON 数组落 DataStore。
     * 总开关 [agentRulesEnabled] 关闭时整表不注入，这里的单条状态保留不动。
     */
    val agentRuleDisabled: Set<String> = emptySet(),

    /**
     * 备份时要包含的数据段 id 集合（设置 → 备份与恢复，默认见
     * `BackupSectionCatalog.defaultSelection`）。
     *
     * 用 String 而不是 Set 存进 DataStore：跨版本增删段时，未知 id 会被 [BackupSectionCatalog]
     * 侧忽略、缺失的段会按默认补齐，所以这里只做「用户意图」而非强 schema。
     */
    val backupSectionIds: Set<String> = emptySet()
)

/**
 * 界面缩放的实际倍率（预设档位 → 倍率；custom 用 [AppSettings.uiScaleCustom]）。
 *
 * 放在类外做成扩展属性：带 getter 的属性不能出现在 data class 的构造参数列表里
 * （构造参数只允许 `val name: Type = default` 形式）。
 */
val AppSettings.effectiveUiScale: Float
    get() = when (uiScalePreset) {
        "small" -> 0.85f
        "large" -> 1.15f
        "custom" -> uiScaleCustom
        else -> 1.0f
    }

/** 外部本地目录工作区：name 为显示名/选择标识，path 为宿主绝对路径。 */
data class ExternalWorkspace(
    val name: String = "",
    val path: String = ""
)
