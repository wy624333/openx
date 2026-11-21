package com.wy.openx.plugin

/**
 * 插件权限声明。
 *
 * ## 为什么必须有
 * 插件是**可执行任意 JS** 的第三方代码。没有权限声明，一个插件可以：
 *   · 用 `ctx.getEnv('OPENAI_API_KEY')` 偷走你的密钥，再用 `ctx.http` 发到自己的服务器
 * ——因为它既能读到秘密，又能联网。这两件事单独看都不致命，合起来就是数据外泄通道。
 *
 * ## 设计原则
 *  1. **显式声明**：`plugin.json` 里写 `permissions: ["http"]`，不写就不给
 *  2. **安装时告知**：管理页显示"此插件可联网"，用户点开详情能看到完整清单
 *  3. **运行时强制**：ctx API 里真正检查权限，不是只做提示
 *
 * 权限只在 `plugin.json` 声明、**不可运行时提升** —— 避免插件先骗过安装检查再偷偷要权限。
 */
enum class PluginPermission(
    /** plugin.json 里声明的字符串。 */
    val wireName: String,
    /** 展示给用户的中文说明（安装/详情页用）。 */
    val label: String,
    /** 风险等级：高危权限在安装确认时用红字强调。 */
    val dangerous: Boolean
) {
    /** 发起 HTTP/HTTPS 请求。 */
    HTTP(
        wireName = "http",
        label = "联网（可发起网络请求，数据可能被发送到第三方服务器）",
        dangerous = true
    ),

    /** 读写插件自己的环境变量（`ctx.getEnv` / `ctx.setEnv`）。 */
    ENV(
        wireName = "env",
        label = "读写插件配置项（环境变量）",
        dangerous = false
    ),

    /** 调用宿主或其它插件注册的工具。 */
    CALL_TOOL(
        wireName = "call_tool",
        label = "调用 AI 工具",
        dangerous = false
    ),

    /** 弹系统提示（toast）。 */
    TOAST(
        wireName = "toast",
        label = "显示提示消息",
        dangerous = false
    ),

    /**
     * 文件读写：选择文件、保存文件、读写插件沙箱目录。
     *
     * 标为危险：文件系统是**跨插件的共享命名空间**，一个插件完全可以把用户
     * 别处的敏感文件读出来再传给另一个插件（或他自己）。
     * 实际实现里做了沙箱（只允许 app 外部私有目录 + 用户主动选择的文件），
     * 但权限提示仍按危险处理，让用户在安装时就看到。
     */
    FILE(
        wireName = "file",
        label = "读写文件（可选择 / 保存文件，访问插件目录）",
        dangerous = true
    ),

    /**
     * 真文件系统：绕过插件沙箱，按容器语义读写 root 下任意路径。
     *
     * 仅为**兼容 Operit ToolPkg** 而存在：那类插件的 `Tools.Files.*` 直接操作
     * `/root/.config/...` 这类真实路径，收进沙箱就没法用。
     * 标为危险并单独枚举（而不是放宽 [FILE]），是为了让用户一眼看出
     * 「这个插件要碰整个文件系统」，而不是与普通沙箱读写混在一起。
     * 实际实现里仍做黑名单：应用自身私有目录（`/data/data/<pkg>`）一律拒绝。
     */
    FILESYSTEM(
        wireName = "filesystem",
        label = "读写整个文件系统（可读取容器内任意文件、修改配置）",
        dangerous = true
    ),

    /** 剪贴板读写。 */
    CLIPBOARD(
        wireName = "clipboard",
        label = "剪贴板（可读取与写入剪贴板内容）",
        dangerous = true
    ),

    /** 发系统通知。 */
    NOTIFY(
        wireName = "notify",
        label = "发送系统通知",
        dangerous = false
    ),

    /**
     * 监听本地端口（服务型插件）。
     *
     * 标为危险：开端口意味着**任何本机 App / 局域网设备**都能连进来发请求，
     * 插件 JS 决定怎么响应。默认只允许回环地址（127.0.0.1），绑定 0.0.0.0
     * 需要插件在启动参数里显式声明。
     */
    SERVER(
        wireName = "server",
        label = "监听本地端口（服务型插件，外部请求可进入插件 JS）",
        dangerous = true
    ),

    /**
     * 定时任务：让插件按声明的时间间隔或每天固定时刻被叫醒。
     *
     * 不标危险：它不读用户数据，也不能后台偷偷联网（要联网得另外申请 http）。
     * 但它是「App 没开界面时也会自己跑」的能力，所以安装时仍然明示。
     */
    SCHEDULE(
        wireName = "schedule",
        label = "定时任务（可声明定时点，到点自动执行插件脚本）",
        dangerous = false
    ),

    /**
     * 插件间消息总线：与其他插件收发消息。
     *
     * 不标危险：只能和同样声明了 bus 的插件通信，拿不到宿主或用户数据。
     */
    BUS(
        wireName = "bus",
        label = "与其他插件收发消息（插件间总线）",
        dangerous = false
    ),

    /**
     * 发起 AI 任务（`ctx.chat.runTask`）：让插件开启一轮 AI 对话。
     *
     * 与只读的 [CHAT] 分开：读会话列表风险低，而发起任务会**消耗 Token、跑工具**，
     * 是真正的写操作，值得单独让用户知情。
     */
    CHAT_TASK(
        wireName = "chat_task",
        label = "发起 AI 任务（可让 AI 按指令干活，会消耗 Token）",
        dangerous = true
    ),

    /**
     * 音频播放：控制宿主播放器（本地音频与网络电台流）。
     *
     * 不标危险：放声不是数据风险（拿不到用户数据、不联网）。
     * 但插件能在**后台持续出声**，所以安装时仍会明确告知，让用户有预期。
     */
    AUDIO(
        wireName = "audio",
        label = "播放音频（可播放本地音乐与网络电台，会在后台持续出声）",
        dangerous = false
    ),

    /**
     * 无障碍 UI 自动化：读屏、点击、输入、滑动。
     *
     * 标为危险：能读到**其它应用的界面内容**（聊天记录、短信验证码等全部可见文字），
     * 也能代替用户操作任意应用。这是插件能拿到的权限里影响力最大的一个，
     * 必须让用户在安装时就看到。
     */
    UI_AUTOMATION(
        wireName = "ui",
        label = "读写其它应用界面（读取屏幕内容并模拟点击/输入，需开启无障碍服务）",
        dangerous = true
    ),

    /**
     * 系统信息：设备型号、屏幕、内存存储、已安装应用列表。
     *
     * 不标危险：只读，且不含账号与内容数据。应用列表有一定指纹价值，所以文案写明。
     */
    SYSTEM(
        wireName = "system",
        label = "读取设备信息与已安装应用列表",
        dangerous = false
    ),

    /**
     * 聊天流程钩子（七期）。
     *
     * 标为危险：`onBeforeSend` 能看到并**改写**你发出的每一条消息，
     * `onAfterReply` / `onAfterTool` 能看到 AI 的完整回复与工具输出（可能含隐私内容）。
     */
    HOOK(
        wireName = "hook",
        label = "接入聊天流程（可改写你发出的消息，查看 AI 回复与工具结果）",
        dangerous = true
    ),

    /**
     * 插件悬浮窗（十期）：把插件界面浮在其它应用上层。
     *
     * 标为危险：悬浮窗能**看到你在别的应用里做什么**（虽然读不到对方的数据，
     * 但界面一直存在），也容易被做成挡屏、伪装界面。必须让用户明确知晓。
     */
    OVERLAY(
        wireName = "overlay",
        label = "显示悬浮窗（让插件界面浮在其它应用之上）",
        dangerous = true
    ),

    /**
     * 读取 AI 会话的**聚合状态**（忙闲、正在跑的工具名、上下文用量、待确认类型）。
     *
     * 只给状态不给内容：拿不到对话正文、思考正文、工具参数。
     * 桌面宠物这类插件靠它「跟着 AI 干活变表情」。
     */
    SESSION(
        wireName = "session",
        label = "读取 AI 会话状态（忙闲、正在用的工具、上下文用量，不含对话内容）",
        dangerous = false
    ),

    /**
     * 读写记忆库（十二期）。
     *
     * 标为危险：记忆里是用户长期积累的偏好、项目事实与待办，属于隐私内容。
     */
    MEMORY(
        wireName = "memory",
        label = "读写 AI 记忆（能查看与修改你保存的长期记忆和项目记忆）",
        dangerous = true
    ),

    /**
     * 读聊天记录（十二期）。
     *
     * 标为危险：能看到你所有会话的标题与最近消息内容。
     * 目前只开放**只读**：插件能读上下文，但不能代替你发消息。
     */
    CHAT(
        wireName = "chat",
        label = "读取聊天记录（能查看会话列表与最近的消息内容）",
        dangerous = true
    );

    companion object {
        private val byWire = entries.associateBy { it.wireName }

        /** 解析声明；未知权限名忽略（向前兼容，不因新权限名而拒绝加载）。 */
        fun parse(names: List<String>?): Set<PluginPermission> =
            names.orEmpty().mapNotNull { byWire[it.trim().lowercase()] }.toSet()

        /**
         * 默认权限：**什么都不给**。
         * 插件必须显式声明才能拿到能力 —— 宁可在开发时提示"缺权限"，
         * 也不要让一个没声明权限的插件悄悄联网。
         */
        val NONE: Set<PluginPermission> = emptySet()
    }
}

/** 权限集合的便捷判断。 */
fun Set<PluginPermission>.has(p: PluginPermission): Boolean = p in this

/** 按危险度排序，让安装确认页把高危项排在最前。 */
fun Set<PluginPermission>.sortedByRisk(): List<PluginPermission> =
    sortedWith(compareByDescending<PluginPermission> { it.dangerous }.thenBy { it.wireName })
