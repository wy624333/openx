package com.wy.openx.ui.screen

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.log.LogEntry
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.model.ApiProviderConfigs
import com.wy.openx.data.model.ApiProviderType
import com.wy.openx.data.model.AppSettings
import com.wy.openx.data.model.ModelListFetcher
import com.wy.openx.data.model.ModelMetaFetcher
import com.wy.openx.data.model.ModelOption
import com.wy.openx.ui.theme.*
import com.wy.openx.ui.viewmodel.PresetTestState
import com.wy.openx.tools.SubAgentTimeoutInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class QuickModel(val provider: String, val models: List<String>)
private val QUICK_MODELS = listOf(
    QuickModel("FreeModel", listOf("gpt-4o", "gpt-4o-mini", "claude-3.5-sonnet", "deepseek-chat", "gemini-pro")),
    QuickModel("阿里云百炼", listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwq-32b", "qwen3-max", "qwen3-plus", "deepseek-r1", "deepseek-v3")),
    QuickModel("通义千问 (Qwen)", listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwq-32b")),
    QuickModel("DeepSeek", listOf("deepseek-chat", "deepseek-reasoner")),
    QuickModel("智谱 GLM", listOf("glm-4-flash", "glm-4-plus", "glm-4-air", "glm-4v-flash")),
    QuickModel("SiliconFlow", listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "deepseek-ai/deepseek-v4-flash", "Qwen/Qwen3.5-4B", "Qwen/Qwen2.5-72B-Instruct")),
    QuickModel("月之暗面 Kimi", listOf("moonshot-v1-auto", "moonshot-v1-32k", "moonshot-v1-128k")),
    QuickModel("OpenAI", listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "o3-mini")),
    QuickModel("OpenRouter", listOf("openai/gpt-4o", "anthropic/claude-sonnet-4", "deepseek/deepseek-chat")),
    QuickModel("Anthropic", listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514")),
    QuickModel("Google Gemini", listOf("gemini-2.5-pro", "gemini-2.5-flash")),
    QuickModel("Ollama (本地)", listOf("llama3", "qwen2.5", "mistral")),
)
/** 语言可选值。「跟随系统」= 跟随设备语言（当前资源只有简体中文，效果等同，但语义正确）。 */
private val LANGUAGES = listOf("跟随系统", "简体中文")

/** 「最大输出 tokens」空输入时的兜底值，须与 AppSettings.maxTokens 默认一致。 */
private const val DEFAULT_MAX_TOKENS = "16384"

/** 「压缩摘要输出上限」空输入时的兜底值，须与 AppSettings.compactionMaxTokens 默认一致。 */
private const val DEFAULT_COMPACTION_MAX_TOKENS = "4096"

/** 界面分区的「外观设置」入口副标题：主题 + 背景图状态一目了然，不用点进去看。 */
private fun appearanceSummary(themeName: String, bgEnabled: Boolean, bgSourceType: String): String {
    val bg = when {
        !bgEnabled -> "背景图关"
        bgSourceType == "local" -> "背景图·本地"
        bgSourceType == "remote" -> "背景图·直链"
        else -> "背景图未选图源"
    }
    return "$themeName · $bg"
}


/**
 * 把模型 ID 列表按「供应商前缀」分组：聚合网关（SiliconFlow/OpenRouter 等）返回的 ID
 * 形如 `deepseek-ai/DeepSeek-V3`、`Qwen/Qwen3.5-4B`，`/` 前段即供应商命名空间。
 * 无前缀者（`gpt-4o`、`claude-sonnet-4`）无法从 ID 推断供应商，统一归入「其他」。
 *
 * @param pinned 已知的「当前服务商」名：当列表里存在带前缀的模型时，用它给无前缀模型命名
 *   （如 OpenAI 提供商下的 `gpt-4o`），比笼统的「其他」更有信息量。
 */
private fun groupModelsByVendor(models: List<String>, pinned: String): List<Pair<String, List<String>>> {
    val prefixed = LinkedHashMap<String, MutableList<String>>()
    val unprefixed = mutableListOf<String>()
    models.forEach { m ->
        val slash = m.indexOf('/')
        if (slash > 0 && slash < m.length - 1) {
            prefixed.getOrPut(m.substring(0, slash)) { mutableListOf() }.add(m)
        } else {
            unprefixed.add(m)
        }
    }
    val groups = prefixed.entries.map { it.key to it.value.toList() }.toMutableList()
    if (unprefixed.isNotEmpty()) {
        // 有前缀分组时，无前缀项归入「当前服务商」；否则整表都无前缀，不值得加分组标题
        val label = if (prefixed.isEmpty()) "" else pinned.ifBlank { "其他" }
        if (label.isEmpty()) return listOf("" to unprefixed.toList())
        groups.add(label to unprefixed.toList())
    }
    return groups
}

/**
 * 提供商的「品牌」分组键：优先按名称识别品牌，未命中再按协议类型兜底；都不认则归「其他模型」。
 * （名称优先 → 类型兜底）。
 */
private fun providerBrandKey(provider: com.wy.openx.data.model.AIProviderConfig): String {
    val name = provider.name.lowercase()
    val byName = when {
        name.contains("doubao") || name.contains("豆包") -> "豆包"
        name.contains("minimax") -> "MiniMax"
        name.contains("moonshot") || name.contains("kimi") || name.contains("月之暗面") -> "月之暗面 Kimi"
        name.contains("zhipu") || name.contains("智谱") || name.contains("glm") || name.contains("bigmodel") -> "智谱 GLM"
        name.contains("qwen") || name.contains("通义") || name.contains("阿里") -> "通义千问"
        name.contains("deepseek") -> "DeepSeek"
        name.contains("grok") || name.contains("xai") -> "Grok"
        name.contains("groq") -> "Groq"
        name.contains("claude") || name.contains("anthropic") -> "Anthropic"
        name.contains("gemini") || name.contains("google") -> "Google Gemini"
        name.contains("hunyuan") || name.contains("混元") || name.contains("tencent") -> "腾讯混元"
        name.contains("openrouter") -> "OpenRouter"
        name.contains("perplexity") -> "Perplexity"
        name.contains("siliconflow") || name.contains("硅基") -> "SiliconFlow"
        name.contains("ollama") -> "Ollama"
        name.contains("mistral") -> "Mistral"
        name.contains("openai") || name.contains("gpt") -> "OpenAI"
        else -> null
    }
    if (byName != null) return byName
    // 名称未命中：按协议类型兜底（用于「我的接口」这类自定义命名）
    return when (provider.type) {
        com.wy.openx.data.model.ProviderType.OPENAI -> "OpenAI"
        com.wy.openx.data.model.ProviderType.ANTHROPIC -> "Anthropic"
        com.wy.openx.data.model.ProviderType.GEMINI -> "Google Gemini"
        com.wy.openx.data.model.ProviderType.DEEPSEEK -> "DeepSeek"
        com.wy.openx.data.model.ProviderType.MOONSHOT -> "月之暗面 Kimi"
        com.wy.openx.data.model.ProviderType.ZHIPU -> "智谱 GLM"
        com.wy.openx.data.model.ProviderType.QWEN -> "通义千问"
        com.wy.openx.data.model.ProviderType.DOUBAO -> "豆包"
        com.wy.openx.data.model.ProviderType.HUNYUAN -> "腾讯混元"
        com.wy.openx.data.model.ProviderType.MINIMAX -> "MiniMax"
        com.wy.openx.data.model.ProviderType.SILICONFLOW -> "SiliconFlow"
        com.wy.openx.data.model.ProviderType.OPENROUTER -> "OpenRouter"
        com.wy.openx.data.model.ProviderType.GROQ -> "Groq"
        com.wy.openx.data.model.ProviderType.OLLAMA -> "Ollama"
        com.wy.openx.data.model.ProviderType.PERPLEXITY -> "Perplexity"
        com.wy.openx.data.model.ProviderType.MISTRAL -> "Mistral"
        com.wy.openx.data.model.ProviderType.GROK -> "Grok"
        else -> "其他模型"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 口令弹窗的来由。备份是「设口令」（留空即明文），恢复是「解口令」（必填）。 */
private enum class PwdPurpose { EXPORT, RESTORE }

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onTest: (AppSettings, (String) -> Unit) -> Unit,
    onExitApp: () -> Unit = {},
    totalTokens: Long = 0L,
    totalInputTokens: Long = 0L,
    tokenDetails: Map<String, Long> = emptyMap(),
    cacheHitTokens: Long = 0L,
    cacheHitDetails: Map<String, Long> = emptyMap(),
    apiPresets: List<ApiPreset> = emptyList(),
    presetTestStates: Map<String, PresetTestState> = emptyMap(),
    onTestPreset: (ApiPreset) -> Unit = {},
    onSavePreset: (name: String, provider: String, baseUrl: String, model: String, apiKey: String) -> Unit = { _, _, _, _, _ -> },
    onDeletePreset: (String) -> Unit = {},
    onUpdatePresetMeta: (name: String, model: String, contextTokens: Long?, outputTokens: Long?) -> Unit = { _, _, _, _ -> },
    onResetTokenStats: () -> Unit = {},
    llmCallRecordDao: com.wy.openx.data.local.LlmCallRecordDao? = null,
    backupBusy: Boolean = false,
    /** 「恢复数据」进行中：恢复按钮据此禁用并显示「处理中…」。与 backupBusy 分离，
     *  恢复时「备份数据」不再误显「处理中…」（二者由 VM 各自的 Busy 维护）。 */
    restoreBusy: Boolean = false,
    /** 打开「偏好设置」子页。 */
    onOpenPreferences: () -> Unit = {},
    /** 打开「Token 统计」子页。 */
    onOpenTokenStats: () -> Unit = {},
    /** 打开「运行日志」子页。 */
    onOpenLogs: () -> Unit = {},
    /** 打开「应用权限授权」子页。 */
    onOpenPermissions: () -> Unit = {},
    /** 打开「存储占用」子页。 */
    onOpenStorageUsage: () -> Unit = {},
    /** 打开「提示词」子页（内置 AI 行为规则：查看 / 编辑 / 启停）。 */
    onOpenPrompts: () -> Unit = {},
    /** 打开「关于」子页（版本 / 构建时间 / 仓库等）。 */
    onOpenAbout: () -> Unit = {},
    /** 设置「外部替换」版本号：恢复备份后递增，触发下方本地状态重新播种。 */
    settingsRevision: Int = 0,
    /** [includeSections] = null 表示全部段；[password] 非空则加密。 */
    onExportBackup: (java.io.OutputStream, Set<String>?, String?, (com.wy.openx.data.backup.BackupOutcome) -> Unit) -> Unit = { _, _, _, _ -> },
    onInspectBackup: (java.io.InputStream, String?, (com.wy.openx.data.backup.InspectOutcome) -> Unit) -> Unit = { _, _, cb -> cb(com.wy.openx.data.backup.InspectOutcome()) },
    onRestoreBackup: (java.io.InputStream, com.wy.openx.data.backup.RestoreMode, Set<String>?, String?, (com.wy.openx.data.backup.BackupOutcome) -> Unit) -> Unit = { _, _, _, _, _ -> },
    containerInstalled: Boolean = false,
    containerMirrorId: String = com.wy.openx.container.ApkMirrors.OFFICIAL_ID,
    containerResetBusy: Boolean = false,
    containerProfiles: List<com.wy.openx.container.ContainerProfile> = emptyList(),
    activeContainerId: String = com.wy.openx.container.ContainerProfile.BUILTIN_ID,
    onApplyContainerMirror: (String) -> Unit = {},
    /** 运行环境（容器内 node/pnpm/opencode/dsh 等插件依赖）检测、安装与卸载。 */
    runtimeTools: List<com.wy.openx.ui.viewmodel.MainViewModel.RuntimeToolState> = emptyList(),
    onDetectRuntimeTools: () -> Unit = {},
    onInstallRuntimeTool: (String) -> Unit = {},
    onUninstallRuntimeTool: (String) -> Unit = {},
    /** 当前 npm 源 id 与切换回调（pnpm/opencode 安装用）。 */
    npmRegistryId: String = com.wy.openx.container.NpmRegistries.NPMMIRROR_ID,
    onSetNpmRegistry: (String) -> Unit = {},
    onResetContainer: ((Boolean) -> Unit) -> Unit = {},
    onApplyContainerProfile: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onSaveContainerProfile: (com.wy.openx.container.ContainerProfile, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onDeleteContainerProfile: (com.wy.openx.container.ContainerProfile) -> Unit = {},
    onRestoreBuiltinAlpine: () -> Unit = {},
    /** 当前已配置的 Gradle 堆上限（MB）；null = 未配置，走 gradlew 的自动值。 */
    gradleHeapMb: Int? = null,
    /** 写入 Gradle 堆上限（MB）。 */
    onGradleHeapChange: (Int) -> Unit = {},
    containerCatalog: List<com.wy.openx.container.ContainerImageEntry> = emptyList(),
    containerImageSources: List<Pair<String, String>> = emptyList(),
    downloadedContainerImages: Map<String, com.wy.openx.container.DownloadedImageRecord> = emptyMap(),
    containerDownloadState: com.wy.openx.ui.viewmodel.ContainerDownloadUiState =
        com.wy.openx.ui.viewmodel.ContainerDownloadUiState.Idle,
    onDownloadContainerImage: (com.wy.openx.container.ContainerImageEntry, String) -> Unit = { _, _ -> },
    onCancelContainerDownload: () -> Unit = {},
    onImportDownloadedImage: (String, String) -> Unit = { _, _ -> },
    onDeleteDownloadedImage: (String) -> Unit = {},
    logs: List<LogEntry> = emptyList(),
    onExportLogs: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    // ===== 提供商独立管理（阶段 4） =====
    providers: List<com.wy.openx.data.model.AIProviderConfig> = emptyList(),
    onSaveProvider: (com.wy.openx.data.model.AIProviderConfig) -> Unit = {},
    onDeleteProvider: (String) -> Unit = {},
    onSetProviderEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onSelectProvider: (String) -> Unit = {},
    providerFetchLoading: Boolean = false,
    providerFetchModels: List<String> = emptyList(),
    providerFetchError: String? = null,
    providerModelMetadata: Map<String, com.wy.openx.data.model.ModelMetadata> = emptyMap(),
    providerTestResults: Map<String, com.wy.openx.data.provider.ModelTestResult> = emptyMap(),
    providerTesting: Set<String> = emptySet(),
    onFetchProviderModels: (com.wy.openx.data.model.AIProviderConfig) -> Unit = {},
    onTestProviderModel: (com.wy.openx.data.model.AIProviderConfig, String) -> Unit = { _, _ -> },
    onTestAllProviderModels: (com.wy.openx.data.model.AIProviderConfig, List<String>) -> Unit = { _, _ -> },
    onStopTestAllProviderModels: () -> Unit = {},
    onStopTestProviderModel: (String) -> Unit = {},
    providerTestAllProgress: com.wy.openx.ui.viewmodel.TestAllProgress? = null,
    onResolveProviderMetadata: (String, com.wy.openx.data.model.ProviderType, List<String>) -> Unit = { _, _, _ -> },
    onSaveCustomModelMetadata: (String, String, com.wy.openx.data.model.ModelMetadata) -> Unit = { _, _, _ -> },
    onRemoveCustomModelMetadata: (String, String) -> Unit = { _, _ -> },
    onLoadCustomModelMetadata: () -> Unit = {},
    /** 进页时直接打开的分区（首次启动引导的「去配置」用），消费后回调 [onInitialSectionConsumed]。 */
    initialSection: String? = null,
    onInitialSectionConsumed: () -> Unit = {},
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var enableStream by remember { mutableStateOf(settings.enableStream) }
    var autoToolCall by remember { mutableStateOf(settings.autoToolCall) }
    var parallelToolCalls by remember { mutableStateOf(settings.parallelToolCalls) }
    // 「全局设置模式」开关：开着时聊天页切档改全局并统一所有会话；关着时只改当前会话
    var toolPermissionGlobalMode by remember { mutableStateOf(settings.toolPermissionGlobalMode) }
    var reasoningEffortGlobalMode by remember { mutableStateOf(settings.reasoningEffortGlobalMode) }
    var modelGlobalMode by remember { mutableStateOf(settings.modelGlobalMode) }
    var showLogs by remember { mutableStateOf(settings.showLogs) }
    var compactUseDefault by remember { mutableStateOf(settings.compactUseDefault) }
    var language by remember { mutableStateOf(settings.language) }
    var rules by remember { mutableStateOf(settings.rules) }
    var selectedPreset by remember { mutableStateOf(settings.provider) }
    // 当前激活的 API 预设名（与 provider 不同：预设名是用户在「保存的模型」里起的名字）。
    // 提升为本地状态是为了让「供应商切换 / 应用已存预设」也能走统一的自动保存路径。
    var selectedApiPresetName by remember { mutableStateOf(settings.selectedApiPreset) }
    var fontSize by remember { mutableStateOf(settings.fontSize) }
    var fontSizeCustomSp by remember { mutableStateOf(settings.fontSizeCustomSp) }
    var uiScalePreset by remember { mutableStateOf(settings.uiScalePreset) }
    var uiScaleCustom by remember { mutableStateOf(settings.uiScaleCustom) }

    // ===== 外观设置子页（主题模式 / 语言 / 字号 / 背景图 / 效果调整）=====
    // 与 themeName/fontSize 一样提升到本页本地状态，走下面统一的防抖自动保存路径。
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var chatTextColorMode by remember { mutableStateOf(settings.chatTextColorMode) }
    var chatTextHexColor by remember { mutableStateOf(settings.chatTextHexColor) }
    var bgEnabled by remember { mutableStateOf(settings.bgEnabled) }
    var bgSourceType by remember { mutableStateOf(settings.bgSourceType) }
    var bgLocalPath by remember { mutableStateOf(settings.bgLocalPath) }
    var bgRemoteUrl by remember { mutableStateOf(settings.bgRemoteUrl) }
    var bgBlurSigma by remember { mutableStateOf(settings.bgBlurSigma) }
    var bgOverlayAlpha by remember { mutableStateOf(settings.bgOverlayAlpha) }
    var bgBrightness by remember { mutableStateOf(settings.bgBrightness) }
    var bgFocalX by remember { mutableStateOf(settings.bgFocalX) }
    var bgFocalY by remember { mutableStateOf(settings.bgFocalY) }
    var bgImageScale by remember { mutableStateOf(settings.bgImageScale) }
    var showTimestamp by remember { mutableStateOf(settings.showTimestamp) }
    var hapticFeedback by remember { mutableStateOf(settings.hapticFeedback) }
    var notifyOnComplete by remember { mutableStateOf(settings.notifyOnComplete) }
    var vibrateOnComplete by remember { mutableStateOf(settings.vibrateOnComplete) }
    var notificationSound by remember { mutableStateOf(settings.notificationSound) }
    var autoScroll by remember { mutableStateOf(settings.autoScroll) }
    var pluginQuickBarExpanded by remember { mutableStateOf(settings.pluginQuickBarExpanded) }
    var thinkingAlwaysExpanded by remember { mutableStateOf(settings.thinkingAlwaysExpanded) }
    var contextRatio by remember { mutableStateOf(settings.contextRatio) }
    var maxTokensInput by remember { mutableStateOf(settings.maxTokens) }
    var compactionMaxTokensInput by remember { mutableStateOf(settings.compactionMaxTokens) }
    var toolAllowList by remember { mutableStateOf(settings.toolAllowList) }
    var showToolAllowDialog by remember { mutableStateOf(false) }

    // ===== 语音（离线识别模型 + 自动朗读）=====
    var voiceAutoSpeak by remember { mutableStateOf(settings.voiceAutoSpeak) }
    var maskOutbound by remember { mutableStateOf(settings.maskOutbound) }

    // ===== 默认模型（识图 / 压缩 / 起标题 专用模型；两字段空串 = 跟随聊天模型；生图模型不跟随聊天） =====
    var visionProviderId by remember { mutableStateOf(settings.visionProviderId) }
    var visionModel by remember { mutableStateOf(settings.visionModel) }
    var compactionProviderId by remember { mutableStateOf(settings.compactionProviderId) }
    var compactionModel by remember { mutableStateOf(settings.compactionModel) }
    var titleProviderId by remember { mutableStateOf(settings.titleProviderId) }
    var titleModel by remember { mutableStateOf(settings.titleModel) }
    var knowledgeProviderId by remember { mutableStateOf(settings.knowledgeProviderId) }
    var knowledgeModel by remember { mutableStateOf(settings.knowledgeModel) }
    var knowledgeExtractEnabled by remember { mutableStateOf(settings.knowledgeExtractEnabled) }
    var knowledgeExtractEveryN by remember { mutableStateOf(settings.knowledgeExtractEveryN) }
    var subagentProviderId by remember { mutableStateOf(settings.subagentProviderId) }
    var subagentModel by remember { mutableStateOf(settings.subagentModel) }
    var subagentConcurrency by remember { mutableStateOf(settings.subagentConcurrency) }
    var subagentTimeoutSec by remember { mutableStateOf(settings.subagentTimeoutSec) }
    var subagentMaxRounds by remember { mutableStateOf(settings.subagentMaxRounds) }
    var showSubAgentHelp by remember { mutableStateOf(false) }
    var showToolsHelp by remember { mutableStateOf(false) }
    var showRuntimeEnvHelp by remember { mutableStateOf(false) }
    var subagentReadOnly by remember { mutableStateOf(settings.subagentReadOnly) }
    var showSubagentModelSheet by remember { mutableStateOf(false) }
    var imageGenProviderId by remember { mutableStateOf(settings.imageGenProviderId) }
    var imageGenModel by remember { mutableStateOf(settings.imageGenModel) }
    var showVisionModelSheet by remember { mutableStateOf(false) }
    var showCompactionModelSheet by remember { mutableStateOf(false) }
    var showTitleModelSheet by remember { mutableStateOf(false) }
    var showKnowledgeModelSheet by remember { mutableStateOf(false) }
    var showImageGenModelSheet by remember { mutableStateOf(false) }

    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var themeName by remember { mutableStateOf(settings.themeName) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf("") }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var showDeletePresetConfirm by remember { mutableStateOf<String?>(null) }
    var showPresetMetaSheet by remember { mutableStateOf<ApiPreset?>(null) }
    
    // 供应商选择相关状态
    var selectedProviderType by remember { mutableStateOf(ApiProviderType.fromProviderTypeId(settings.provider) ?: ApiProviderType.SILICONFLOW) }
    var endpointOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showEndpointMenu by remember { mutableStateOf(false) }
    
    // 备份范围勾选（落在 AppSettings，随设置一起防抖落盘）
    var backupSectionIds by remember { mutableStateOf(settings.backupSectionIds) }

    // 模型列表相关状态
    var isLoadingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var showFetchedModelsMenu by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()

    // ===== 自动保存 =====
    // 所有设置项改动即存，无需再点「保存设置」。统一 400ms 防抖：
    // 文本字段（API Key / 系统提示词 / 项目规则）逐字符触发，防抖把连续输入合并成一次写入；
    // 开关与选择器本身是离散操作，多等 400ms 无感知，换来一套逻辑而非两套。
    val latestSave by rememberUpdatedState(onSave)
    val latestSettings by rememberUpdatedState(settings)
    // 把「界面上的全部设置」组装出来。以 settings.copy 为底，界面上未暴露的字段
    // （temperature / maxTokens / externalWorkspaces / toolPermissionMode / reasoningEffort 等）
    // 原样保留——若从零构造 AppSettings，这些字段会被默认值悄悄覆盖。
    val composeCurrent: () -> AppSettings = {
        latestSettings.copy(
            // 注意：**不要**再写 provider/baseUrl/apiKey/model/selectedApiPreset。
            // 这几个扁平字段现已由「提供商独立管理」体系（MainViewModel.resolveActiveProvider）
            // 派生并落盘；本页不再有编辑它们的 UI。若在此用进场时播种的旧副本回写，
            // 离开设置页的 DisposableEffect 冲刷会把刚派生的当前模型/提供商覆盖回旧值
            // （表现为「设置页点了使用、切了模型，回聊天页没变」）。
            systemPrompt = systemPrompt,
            enableStream = enableStream,
            autoToolCall = autoToolCall,
            parallelToolCalls = parallelToolCalls,
            toolPermissionGlobalMode = toolPermissionGlobalMode,
            reasoningEffortGlobalMode = reasoningEffortGlobalMode,
            modelGlobalMode = modelGlobalMode,
            showLogs = showLogs,
            compactUseDefault = compactUseDefault,
            themeName = themeName, language = language, rules = rules,
            fontSize = fontSize, fontSizeCustomSp = fontSizeCustomSp,
            uiScalePreset = uiScalePreset, uiScaleCustom = uiScaleCustom,
            showTimestamp = showTimestamp, hapticFeedback = hapticFeedback,
            themeMode = themeMode,
            chatTextColorMode = chatTextColorMode,
            chatTextHexColor = chatTextHexColor,
            bgEnabled = bgEnabled,
            bgSourceType = bgSourceType,
            bgLocalPath = bgLocalPath,
            bgRemoteUrl = bgRemoteUrl,
            bgBlurSigma = bgBlurSigma,
            bgOverlayAlpha = bgOverlayAlpha,
            bgBrightness = bgBrightness,
            bgFocalX = bgFocalX,
            bgFocalY = bgFocalY,
            bgImageScale = bgImageScale,
            notifyOnComplete = notifyOnComplete, vibrateOnComplete = vibrateOnComplete,
            notificationSound = notificationSound,
            autoScroll = autoScroll,
            pluginQuickBarExpanded = pluginQuickBarExpanded,
            thinkingAlwaysExpanded = thinkingAlwaysExpanded,
            voiceAutoSpeak = voiceAutoSpeak,
            maskOutbound = maskOutbound,
            contextRatio = contextRatio,
            maxTokens = maxTokensInput.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: DEFAULT_MAX_TOKENS,
            compactionMaxTokens = compactionMaxTokensInput.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: DEFAULT_COMPACTION_MAX_TOKENS,
            toolAllowList = toolAllowList,
            visionProviderId = visionProviderId,
            visionModel = visionModel,
            compactionProviderId = compactionProviderId,
            compactionModel = compactionModel,
            titleProviderId = titleProviderId,
            titleModel = titleModel,
            knowledgeProviderId = knowledgeProviderId,
            knowledgeModel = knowledgeModel,
            knowledgeExtractEnabled = knowledgeExtractEnabled,
            knowledgeExtractEveryN = knowledgeExtractEveryN,
            subagentProviderId = subagentProviderId,
            subagentModel = subagentModel,
            subagentConcurrency = subagentConcurrency,
            subagentTimeoutSec = subagentTimeoutSec,
            subagentMaxRounds = subagentMaxRounds,
            subagentReadOnly = subagentReadOnly,
            imageGenProviderId = imageGenProviderId,
            imageGenModel = imageGenModel,
            backupSectionIds = backupSectionIds,
        )
    }
    // 首帧不保存：进场时各本地状态刚由 settings 播种，此时写盘是无意义的一次往返。
    // settingsRevision 重新播种后需要重新武装，否则恢复备份会立刻被这次的旧值覆盖。
    var autoSaveArmed by remember { mutableStateOf(false) }
    LaunchedEffect(settingsRevision) { autoSaveArmed = false }
    LaunchedEffect(
        apiKey, baseUrl, model, systemPrompt, rules,
        enableStream, autoToolCall, parallelToolCalls, showLogs,
        toolPermissionGlobalMode, reasoningEffortGlobalMode, modelGlobalMode,
        language, themeName, fontSize, fontSizeCustomSp, uiScalePreset, uiScaleCustom, showTimestamp, hapticFeedback, notifyOnComplete,
        themeMode, chatTextColorMode, chatTextHexColor,
        bgEnabled, bgSourceType, bgLocalPath, bgRemoteUrl, bgBlurSigma, bgOverlayAlpha,
        bgBrightness, bgFocalX, bgFocalY, bgImageScale,
        vibrateOnComplete, notificationSound, autoScroll, pluginQuickBarExpanded, thinkingAlwaysExpanded, voiceAutoSpeak, maskOutbound,
        compactUseDefault, contextRatio, maxTokensInput,
        compactionMaxTokensInput, toolAllowList,
        selectedPreset, selectedProviderType, selectedApiPresetName,
        visionProviderId, visionModel, compactionProviderId, compactionModel, titleProviderId, titleModel,
        knowledgeProviderId, knowledgeModel, knowledgeExtractEnabled, knowledgeExtractEveryN,
        subagentProviderId, subagentModel,
        subagentConcurrency, subagentTimeoutSec, subagentMaxRounds, subagentReadOnly,
        imageGenProviderId, imageGenModel, backupSectionIds
    ) {
        if (!autoSaveArmed) {
            autoSaveArmed = true
            return@LaunchedEffect
        }
        delay(400)
        latestSave(composeCurrent())
    }
    // 离开页面时冲刷未到期的防抖保存。
    // 没有这一步，用户在文本框里敲完最后一个字、400ms 内就返回，那次修改会随协程取消一起丢掉——
    // 而自动保存已是唯一的保存路径，丢了就真没了。
    DisposableEffect(Unit) {
        onDispose {
            if (autoSaveArmed) latestSave(composeCurrent())
        }
    }

    // 恢复备份后 settings 被整体替换，但上面 20+ 个本地状态仍是旧副本——
    // 用户随手点「保存设置」会把旧值写回去、静默撤销恢复。这里按 revision 重新播种。
    // 用 revision 而非 settings 本身作键：正常编辑时 settings 每保存一次都变，
    // 若监听它会把用户正在输入的内容冲掉。
    LaunchedEffect(settingsRevision) {
        if (settingsRevision == 0) return@LaunchedEffect
        baseUrl = settings.baseUrl
        apiKey = settings.apiKey
        model = settings.model
        systemPrompt = settings.systemPrompt
        enableStream = settings.enableStream
        autoToolCall = settings.autoToolCall
        parallelToolCalls = settings.parallelToolCalls
        toolPermissionGlobalMode = settings.toolPermissionGlobalMode
        reasoningEffortGlobalMode = settings.reasoningEffortGlobalMode
        modelGlobalMode = settings.modelGlobalMode
        showLogs = settings.showLogs
        compactUseDefault = settings.compactUseDefault
        maxTokensInput = settings.maxTokens
        compactionMaxTokensInput = settings.compactionMaxTokens
        language = settings.language
        rules = settings.rules
        selectedPreset = settings.provider
        fontSize = settings.fontSize
        fontSizeCustomSp = settings.fontSizeCustomSp
        uiScalePreset = settings.uiScalePreset
        uiScaleCustom = settings.uiScaleCustom
        themeMode = settings.themeMode
        chatTextColorMode = settings.chatTextColorMode
        chatTextHexColor = settings.chatTextHexColor
        bgEnabled = settings.bgEnabled
        bgSourceType = settings.bgSourceType
        bgLocalPath = settings.bgLocalPath
        bgRemoteUrl = settings.bgRemoteUrl
        bgBlurSigma = settings.bgBlurSigma
        bgOverlayAlpha = settings.bgOverlayAlpha
        bgBrightness = settings.bgBrightness
        bgFocalX = settings.bgFocalX
        bgFocalY = settings.bgFocalY
        bgImageScale = settings.bgImageScale
        showTimestamp = settings.showTimestamp
        hapticFeedback = settings.hapticFeedback
        notifyOnComplete = settings.notifyOnComplete
        vibrateOnComplete = settings.vibrateOnComplete
        notificationSound = settings.notificationSound
        autoScroll = settings.autoScroll
        pluginQuickBarExpanded = settings.pluginQuickBarExpanded
        thinkingAlwaysExpanded = settings.thinkingAlwaysExpanded
        contextRatio = settings.contextRatio
        toolAllowList = settings.toolAllowList
        themeName = settings.themeName
        selectedApiPresetName = settings.selectedApiPreset
        selectedProviderType = ApiProviderType.fromProviderTypeId(settings.provider) ?: ApiProviderType.SILICONFLOW
        endpointOptions = ApiProviderConfigs.getConfig(selectedProviderType).endpointOptions.map { it.endpoint to it.label }
        visionProviderId = settings.visionProviderId
        visionModel = settings.visionModel
        compactionProviderId = settings.compactionProviderId
        compactionModel = settings.compactionModel
        titleProviderId = settings.titleProviderId
        titleModel = settings.titleModel
        knowledgeProviderId = settings.knowledgeProviderId
        knowledgeModel = settings.knowledgeModel
        knowledgeExtractEnabled = settings.knowledgeExtractEnabled
        knowledgeExtractEveryN = settings.knowledgeExtractEveryN
        subagentProviderId = settings.subagentProviderId
        subagentModel = settings.subagentModel
        subagentConcurrency = settings.subagentConcurrency
        subagentTimeoutSec = settings.subagentTimeoutSec
        subagentMaxRounds = settings.subagentMaxRounds
        subagentReadOnly = settings.subagentReadOnly
        imageGenProviderId = settings.imageGenProviderId
        imageGenModel = settings.imageGenModel
    }

    // 初始化端点选项
    LaunchedEffect(Unit) {
        val config = ApiProviderConfigs.getConfig(selectedProviderType)
        endpointOptions = config.endpointOptions.map { it.endpoint to it.label }
    }
    
    // 获取模型列表
    val fetchModels = {
        scope.launch {
            isLoadingModels = true
            modelLoadError = null
            val result = ModelListFetcher.getModelsList(
                apiKey = apiKey,
                apiEndpoint = baseUrl,
                providerType = selectedProviderType
            )
            result.onSuccess { models ->
                fetchedModels = models
                showFetchedModelsMenu = true
            }.onFailure { e ->
                modelLoadError = e.message
            }
            isLoadingModels = false
        }
    }
    
    val quickModels = remember(selectedPreset) {
        QUICK_MODELS.find { it.provider == selectedPreset }?.models ?: emptyList()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // ===== 数据备份 / 恢复 =====
    /**
     * 备份/恢复的结果弹窗模型。
     *
     * 从「按钮下面一行小字 + 靠 `contains("失败")` 上色」改成弹窗 + 结构化字段：
     * 明细能逐段展开（用户要确认工作区到底进没进去），跳过原因能高亮，
     * 而且成败判断不再依赖文案，改字不会失效。
     */
    data class ResultDialog(
        val title: String,
        val ok: Boolean,
        val summary: String,
        /** 需要用户注意的内容：跳过的段、口令被丢弃等。 */
        val notices: List<String> = emptyList(),
        /** 逐段明细：展示名 → 条数文案（可为「已清空」或空串）。 */
        val details: List<Pair<String, String>> = emptyList()
    )

    var resultDialog by remember { mutableStateOf<ResultDialog?>(null) }
    /** 仅用于「一项都没勾」这类点按即出的表单校验，不走弹窗。 */
    var backupNotice by remember { mutableStateOf<String?>(null) }

    /**
     * 把 [com.wy.openx.data.backup.BackupOutcome] 转成弹窗模型。[verb] 为「备份」或「恢复」。
     *
     * 备份和恢复**取数来源不同**，不能共用 manifest：
     * 恢复可以只勾其中几段，manifest 记的是「这份备份里有什么」，
     * 拿它汇报会列全 15 行，看着齐全其实大部分没写进去。恢复一律用 [com.wy.openx.data.backup.BackupOutcome.restored]。
     */
    fun resultDialogOf(verb: String, outcome: com.wy.openx.data.backup.BackupOutcome): ResultDialog {
        val isRestore = verb == "恢复"
        val sections = outcome.manifest?.sections.orEmpty()
        return ResultDialog(
            title = if (outcome.ok) "$verb 完成" else "$verb 失败",
            ok = outcome.ok,
            summary = when {
                !outcome.ok -> outcome.message
                isRestore -> "已恢复 ${outcome.restored.size} 段"
                else -> "已备份 ${sections.size} 段，共 ${sections.sumOf { it.count }} 项"
            },
            notices = buildList {
                addAll(outcome.skipped)
                // 口令被丢弃时 VM 会在 message 尾部追加说明，这里也提示一次，避免只在长文案里埋一句
                if (outcome.message.contains("未加密")) add("本次未加密（含工作区文件）")
            },
            details = if (isRestore) {
                outcome.restored.map { rs ->
                    rs.label to when {
                        rs.cleared -> "已清空"
                        rs.count != null && rs.unit.isNotEmpty() -> "${rs.count} ${rs.unit}"
                        rs.count != null -> "${rs.count}"
                        else -> ""
                    }
                }
            } else {
                sections.map { it.label to "${it.count}" }
            }
        )
    }

    @Composable
    fun SettingsResultDialog() {
        val r = resultDialog ?: return
        val accent = if (r.ok) Color(0xFF35C58B) else Color(0xFFFF6B6B)
        AlertDialog(
            onDismissRequest = { resultDialog = null },
            title = {
                Text(r.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(r.summary, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    r.notices.forEach { n ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFF6B6B).copy(alpha = 0.10f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("已跳过：$n", color = Color(0xFFFF6B6B), fontSize = 11.sp,
                                lineHeight = 16.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (r.details.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(6.dp))
                        r.details.forEach { (label, count) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(count, color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { resultDialog = null }) { Text("完成", color = Accent) }
            },
            containerColor = Panel
        )
    }

    // 待恢复的备份：先读清单给用户看内容，再让他选覆盖还是合并
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingManifest by remember { mutableStateOf<com.wy.openx.data.backup.BackupManifest?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    // 口令只在本次操作内存活，**不落 AppSettings**（设置里存明文口令等于把备份密钥公开）
    /** SAF 选择器是异步的：点「备份」后要先把口令存下来，等 uri 回来再传给 VM。 */
    var pendingExportPassword by remember { mutableStateOf("") }
    /** 口令弹窗的来由：备份时是「设口令」（允许留空），恢复时是「解口令」（必填）。 */
    var passwordPurpose by remember { mutableStateOf<PwdPurpose?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    /** 预检已用该口令解开过，恢复时直接复用（流不能回退，没法再解一次给你看）。 */
    var restorePassword by remember { mutableStateOf<String?>(null) }

    /**
     * 用 [uri] 重新开流做预检。SAF 的流只能顺序读一遍，口令输错也必须重开一次。
     * @param password 已知的口令；null 表示先无口令探一次。
     */
    fun inspectRestore(uri: android.net.Uri, password: String?, fromPasswordPrompt: Boolean) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                resultDialog = ResultDialog("无法读取", false, "所选文件读不出来，换一个再试")
                pendingRestoreUri = null
                return
            }
            onInspectBackup(input, password) { outcome ->
                when {
                    outcome.manifest != null -> {
                        restorePassword = password
                        pendingManifest = outcome.manifest
                        showRestoreDialog = true
                        passwordError = null
                    }
                    outcome.passwordRequired -> {
                        if (fromPasswordPrompt && !password.isNullOrEmpty()) {
                            // 口令给过一次还不行 = 口令错或文件损坏，别再弹框让人无限试
                            resultDialog = ResultDialog("恢复失败", false, outcome.error ?: "口令错误或备份文件已损坏")
                            passwordError = "口令错误或备份文件已损坏"
                            pendingRestoreUri = null
                        } else {
                            passwordPurpose = PwdPurpose.RESTORE
                            passwordInput = ""
                            passwordError = null
                        }
                    }
                    else -> {
                        resultDialog = ResultDialog("无法恢复", false, outcome.error ?: "这不是有效的备份文件（缺少清单）")
                        pendingRestoreUri = null
                    }
                }
            }
        } catch (e: Exception) {
            resultDialog = ResultDialog("无法读取", false, "读取失败：${e.message ?: "未知错误"}")
            pendingRestoreUri = null
        }
    }

    val importSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restorePassword = null
            pendingRestoreUri = uri
            inspectRestore(uri, null, fromPasswordPrompt = false)
        }
    }
    val exportSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            try {
                val out = context.contentResolver.openOutputStream(uri)
                if (out == null) {
                    resultDialog = ResultDialog("备份失败", false, "所选位置写不进去，换一个再试")
                } else {
                    val pwd = pendingExportPassword
                    val sections = settings.backupSectionIds
                    onExportBackup(out, sections, pwd) { result -> resultDialog = resultDialogOf("备份", result) }
                    // 口令用完即弃，不留在界面状态里
                    pendingExportPassword = ""
                }
            } catch (e: Exception) {
                resultDialog = ResultDialog("备份失败", false, "备份失败：${e.message ?: "未知错误"}")
            }
        }
    }
    // 分区导航栈。栈顶 = 当前打开的分区（空栈 = 主页入口列表）。
    //
    // 为什么不做成独立 AppScreen：本页有 20+ 个本地编辑状态 + 一整套自动保存机制
    // （防抖、离开冲刷、settingsRevision 重新播种），把各分区拆成独立页面就得把这些
    // 整体搬一遍，回归风险极高。用页内导航切换，状态与回调原封不动。
    //
    // 为什么是**栈**而不是单个 String：「镜像下载」(imagerepo) 是唯一嵌在「容器」(container)
    // 分区内部的入口，深度为 2。单个 String 时 `openSection = "imagerepo"` 会直接覆盖销毁
    // "container"，而返回键只能写死 null —— 于是从镜像下载页按返回会掉到**设置主页**而不是
    // 容器页（用户实际报的就是这个）。其余 10 个分区深度都是 1，写死的 null 碰巧正确，
    // 掩盖了这个设计假设的漏洞。栈顶派生 openSection，所以下面 11 处
    // `if (openSection == "x")` 守卫一行都不用改。
    // 用不可变 List（而非 mutableStateListOf）承载栈，好处是导航逻辑能整体抽到
    // 纯函数 SectionNav 里——于是单测跑的就是这里真正执行的代码，不是它的副本。
    var sectionStack by remember {
        mutableStateOf(if (initialSection.isNullOrBlank()) emptyList() else listOf(initialSection))
    }
    val openSection = SectionNav.current(sectionStack)

    // 初始分区只能生效一次：不清掉的话，用户退出分区再进设置页又会跳回去。
    LaunchedEffect(initialSection) {
        if (!initialSection.isNullOrBlank()) onInitialSectionConsumed()
    }

    // 进入分区，一律压栈：主页入口那 10 个是空栈→1 层，与原来的赋值等价；
    // 嵌套入口（镜像下载）则正确记住上一级。
    fun enterSection(id: String) { sectionStack = SectionNav.enter(sectionStack, id) }
    // 退出当前分区（退一级）。空栈时不动，交给外层 BackHandler 退出整个设置页。
    fun exitSection() { sectionStack = SectionNav.exit(sectionStack) }

    // 分区内返回键：退一级回到上一级；未在分区内时不拦截，交给外层（退出设置页）。
    // 该 BackHandler 比 MainActivity 的更内层、后注册，系统优先派给它；
    // 否则手机返回键在子分区会直接跳过设置主页一路退回聊天页。
    BackHandler(enabled = openSection != null) {
        exitSection()
    }
    // 正在编辑的提供商（进入 ProviderEditorScreen）
    var editingProvider by remember { mutableStateOf<com.wy.openx.data.model.AIProviderConfig?>(null) }
    var showProviderEditor by remember { mutableStateOf(false) }
    // 提供商列表左滑删除的组级协调状态（同组互斥）；pendingDeleteProvider 为待确认删除项。
    val providerSwipeGroup = com.wy.openx.ui.component.rememberSlidableGroupState()
    var pendingDeleteProvider by remember { mutableStateOf<com.wy.openx.data.model.AIProviderConfig?>(null) }

    if (showProviderEditor) {
        ProviderEditorScreen(
            initialProvider = editingProvider,
            onNavigateBack = { showProviderEditor = false; editingProvider = null },
            onSave = onSaveProvider,
            fetchLoading = providerFetchLoading,
            fetchModels = providerFetchModels,
            fetchError = providerFetchError,
            modelMetadata = providerModelMetadata,
            testResults = providerTestResults,
            testing = providerTesting,
            onFetchModels = onFetchProviderModels,
            onTestModel = onTestProviderModel,
            onTestAllModels = onTestAllProviderModels,
            onStopTestAll = onStopTestAllProviderModels,
            onStopTestModel = onStopTestProviderModel,
            testAllProgress = providerTestAllProgress,
            onResolveMetadata = onResolveProviderMetadata,
            onSaveCustomMetadata = onSaveCustomModelMetadata,
            onRemoveCustomMetadata = onRemoveCustomModelMetadata,
            onLoadCustomMetadata = onLoadCustomModelMetadata
        )
        return
    }

    // 「连接与同步」是独立全屏页（三 tab + 底栏），不放在页内分区里：
    // 它自带顶栏与底部 tab 栏，塞进本页的滚动容器会被外层 padding 和手势干扰。
    if (openSection == "remote") {
        Box(modifier = Modifier.fillMaxSize()) {
            RemoteServerScreen(
                onBack = { exitSection() },
                onFeedback = { msg -> resultDialog = ResultDialog("连接与同步", true, msg) },
            )
            SettingsResultDialog()
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        // 顶栏标题跟随当前分区：进入分区后直接显示分区名（与独立页面 PreferencesScreen 一致），
        // 不在下面再挂一条单独的分区返回条——否则「设置」+ 分区名两行并存，观感与偏好设置页不一致。
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 返回按钮尺寸与其余页面顶栏统一（40dp 按钮 + 20dp 图标）：
            // 原来用默认 48dp IconButton，使设置页顶栏比别的页高 8dp（用户反馈高度不一致）。
            IconButton(
                onClick = {
                    // 在分区内时退一级（回上一级分区），不直接退出设置
                    if (sectionStack.isNotEmpty()) exitSection() else onBack()
                },
                modifier = Modifier.size(40.dp)
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary, modifier = Modifier.size(20.dp)) }
            Text(
                when (openSection) {
                    "api" -> "API 配置"
                    "model" -> "上下文配置"
                    "ui" -> "界面"
                    "appearance" -> "外观设置"
                    "notify" -> "通知"
                    "subagent" -> "子代理设置"
                    "tools" -> "工具"
                    "advanced" -> "高级"
                    "backup" -> "备份与恢复"
                    "container" -> "容器"
                    "remote" -> "连接与同步"
                    "imagerepo" -> "镜像下载"
                    "runtimeenv" -> "运行环境"
                    "defaultModels" -> "默认模型"
                    else -> "设置"
                },
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            // 各分区的说明收进顶栏 ⓘ：不再在列表底部占一整行，
            // 尺寸与返回按钮对齐（40dp 按钮 + 20dp 图标）。
            val helpOnClick: (() -> Unit)? = when (openSection) {
                "subagent" -> { { showSubAgentHelp = true } }
                "tools" -> { { showToolsHelp = true } }
                "runtimeenv" -> { { showRuntimeEnvHelp = true } }
                else -> null
            }
            if (helpOnClick != null) {
                IconButton(
                    onClick = helpOnClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "本页使用说明",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (testResult.isNotEmpty()) {
            Surface(color = Panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val tc = if (testResult.contains("失败") || testResult.contains("错误") || testResult.contains("error", ignoreCase = true)) Color(0xFFFF6B6B) else Color(0xFF35C58B)
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("测试结果", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(testResult, color = tc, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
        // 测试结果提示 5 秒后自动关闭，避免一直占着界面
        LaunchedEffect(testResult) {
            if (testResult.isNotEmpty()) {
                delay(5000)
                testResult = ""
            }
        }
        // 选中的已保存模型未拉取到上下文等提示（saveFeedback）同样 5 秒后自动关闭，与测试结果提示口径一致
        LaunchedEffect(saveFeedback) {
            if (saveFeedback.isNotEmpty()) {
                delay(5000)
                saveFeedback = ""
            }
        }
        if (saveFeedback.isNotEmpty() && testResult.isEmpty()) {
            Surface(color = Panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(saveFeedback, color = Color(0xFF35C58B), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }

        // 左右统一 16dp（与「偏好设置」页一致）：卡片底板已移除，内容直接铺在背景上，
        // 边距由这里一次性给，行组件内部不再自带横向 padding。
        Column(modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            var showPresetsSheet by remember { mutableStateOf(false) }

            // ===== 分区导航 =====
            // 主页只放「[图标] 标题 > 副标题」入口；点进去后同一页内切换为分区内容。
            if (openSection == null) {
                SettingsCard {
                    SettingsNavRow("偏好设置", "自动归档、会话行为等", Icons.Outlined.ToggleOn) {
                        onOpenPreferences()
                    }
                    SettingsCardDivider()
                    SettingsNavRow("API 配置", "服务商、密钥、模型与预设", Icons.Outlined.Api) {
                        enterSection("api")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("上下文配置", "自动压缩、上下文阈值", Icons.Outlined.Tune) {
                        enterSection("model")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("默认模型", "识图、压缩与起标题的专用模型", Icons.Outlined.AutoAwesome) {
                        enterSection("defaultModels")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("界面", "主题、字号、语言、显示", Icons.Outlined.Palette) {
                        enterSection("ui")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("通知", "完成提醒、振动与提示音", Icons.Outlined.Notifications) {
                        enterSection("notify")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("子代理设置", "并发、超时与强制只读", Icons.Outlined.Psychology) {
                        enterSection("subagent")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("工具", "工具权限与白名单", Icons.Outlined.Build) {
                        enterSection("tools")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("高级", "系统提示词与项目规则", Icons.Outlined.MoreHoriz) {
                        enterSection("advanced")
                    }
                    SettingsCardDivider()
                    // 内置提示词独立子页（不在「高级」里塞表单）：要列表 + 详情预览 + 编辑弹层，
                    // 与「偏好设置」「存储占用」同类，走独立页面而非同页分区。
                    SettingsNavRow("提示词", "内置 AI 行为规则：查看、编辑与启停", Icons.Outlined.Article) {
                        onOpenPrompts()
                    }
                    SettingsCardDivider()
                    SettingsNavRow("容器", "Linux 容器与镜像", Icons.Outlined.Dns) {
                        enterSection("container")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("连接与同步", "远程服务器 SFTP / FTP，直接读写远端文件", Icons.Outlined.Sync) {
                        enterSection("remote")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("应用权限授权", "无障碍、后台运行、存储等系统授权", Icons.Outlined.VerifiedUser) {
                        onOpenPermissions()
                    }
                    SettingsCardDivider()
                    SettingsNavRow("备份与恢复", "选择备份内容、可选加密口令", Icons.Outlined.SaveAlt) {
                        enterSection("backup")
                    }
                    SettingsCardDivider()
                    SettingsNavRow("存储占用", "查看各类数据占用并清理", Icons.Filled.SdStorage) {
                        onOpenStorageUsage()
                    }
                    SettingsCardDivider()
                }
            } else {
                // 分区内容页不再挂单独的返回条：顶栏已显示分区名且返回键会先回主页，
                // 再加一条就是「设置 + 分区名」两行并存，与偏好设置页观感不一致。
            }

            // ===== API 配置（提供商独立管理） =====
            if (openSection == "api") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { editingProvider = null; showProviderEditor = true },
                    shape = RoundedCornerShape(8.dp), color = Accent.copy(alpha = 0.15f)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = "添加提供商", tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加提供商", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (providers.isEmpty()) {
                    SettingsCard {
                        Text(
                            "暂无提供商配置。点击上方「添加提供商」创建第一个（服务商 / Key / 模型列表 / 代理 / 多 Key 轮换）。\n\n旧版的单一「服务商 + Key + 模型」配置会在首次进入时自动迁移为一条提供商记录。",
                            color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp
                        )
                    }
                } else {
                    // 按品牌分组：同一品牌的提供商归一组（如 DeepSeek / 智谱 GLM），无法识别的归「其他模型」。
                    // 组内每组一张卡片；「其他模型」永远排最后，其余按品牌名排序。
                    val grouped = providers
                        .groupBy { providerBrandKey(it) }
                        .toSortedMap(
                            compareBy<String> { it == "其他模型" }.thenBy { it }
                        )
                    grouped.forEach { (brand, list) ->
                        ProviderBrandHeader(brand)
                        SettingsCard {
                            list.forEachIndexed { index, provider ->
                                if (index > 0) SettingsCardDivider()
                                val isActive = provider.id == settings.selectedProviderId
                                com.wy.openx.ui.component.SlidableRow(
                                    itemId = provider.id,
                                    groupState = providerSwipeGroup,
                                    actions = listOf(
                                        com.wy.openx.ui.component.SlideAction(
                                            icon = Icons.Default.Delete,
                                            tint = Color(0xFFFF6B6B),
                                            onClick = { pendingDeleteProvider = provider }
                                        )
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 去掉自画的 Panel 底：与本次「去卡片底板」一致，
                                            // 否则这一行仍是不透明面板，选背景图时盖出两块色块。
                                            // 注意必须放在 SlidableRow 内层（左滑删除的背景由外层负责）。
                                            .clickable { editingProvider = provider; showProviderEditor = true }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isActive) {
                                                    Icon(Icons.Default.Check, contentDescription = "当前", tint = Accent, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(provider.name, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium, color = TextPrimary)
                                                if (provider.source == com.wy.openx.data.model.PROVIDER_SOURCE_PLUGIN) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    // 仅标识来源，不可点——点整行才是编辑。
                                                    Text(
                                                        "插件",
                                                        fontSize = 9.sp,
                                                        color = Accent,
                                                        modifier = Modifier
                                                            .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                                if (!provider.isEnabled) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("已停用", fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.7f))
                                                }
                                            }
                                            Text(
                                                "${provider.type.displayName} · ${provider.effectiveModel.ifBlank { "未设模型" }} · ${provider.models.size} 个模型",
                                                fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!isActive) {
                                            Text("使用", fontSize = 12.sp, color = Accent, modifier = Modifier.clickable { onSelectProvider(provider.id) }.padding(horizontal = 8.dp, vertical = 4.dp))
                                        } else {
                                            Text("当前", fontSize = 11.sp, color = Accent, modifier = Modifier.padding(horizontal = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 删除提供商确认（左滑触发）：整条配置（Key/模型/代理等）会一并删除，不可撤销
                pendingDeleteProvider?.let { target ->
                    AlertDialog(
                        onDismissRequest = { pendingDeleteProvider = null },
                        title = { Text("删除提供商", fontWeight = FontWeight.Bold, color = TextPrimary) },
                        text = { Text("确定要删除「${target.name}」吗？其 API Key、模型列表与代理配置将一并删除，此操作不可撤销。", color = TextSecondary) },
                        confirmButton = {
                            TextButton(onClick = {
                                onDeleteProvider(target.id)
                                pendingDeleteProvider = null
                            }) { Text("删除", color = Color(0xFFFF6B6B)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteProvider = null }) { Text("取消", color = TextSecondary) }
                        }
                    )
                }
            }

            // ===== 上下文配置（可折叠） =====
            if (openSection == "model") {
                SettingsCard {
                    Text("上下文窗口基于每个模型的设置（模型右侧「设置」按钮），自动压缩时按当前模型的上下文窗口计算阈值。", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    // 跟随默认开关 = 80% 阈值（与 ChatRepository 工具循环、ViewModel 事后自动压缩同口径）；
                    // 关闭后才露出手动滑块 50%~95%
                    SettingsToggleRow("使用默认阈值", "按上下文窗口 80% 触发压缩", compactUseDefault) { compactUseDefault = it }
                    if (!compactUseDefault) {
                        CompactionThresholdSlider(contextRatio) { contextRatio = it }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("最大输出 tokens", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "单次回复最多生成的 token 数（含思维链与工具调用参数）。当前：${maxTokensInput.ifBlank { "默认 $DEFAULT_MAX_TOKENS" }}。写大文件易被截断，建议不小于 $DEFAULT_MAX_TOKENS。",
                        color = TextSecondary, fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxTokensInput,
                        onValueChange = { maxTokensInput = it.filter { c -> c.isDigit() }.take(7) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("最大输出 tokens", color = TextSecondary) },
                        placeholder = { Text("默认 $DEFAULT_MAX_TOKENS", color = TextSecondary.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        colors = tfColors(), shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("压缩摘要输出上限", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "压缩上下文时生成摘要的单次输出上限，与上面的「最大输出」相互独立。当前：${compactionMaxTokensInput.ifBlank { "默认 $DEFAULT_COMPACTION_MAX_TOKENS" }}。摘要较短，一般 $DEFAULT_COMPACTION_MAX_TOKENS 足够。",
                        color = TextSecondary, fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = compactionMaxTokensInput,
                        onValueChange = { compactionMaxTokensInput = it.filter { c -> c.isDigit() }.take(7) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("压缩摘要输出上限", color = TextSecondary) },
                        placeholder = { Text("默认 $DEFAULT_COMPACTION_MAX_TOKENS", color = TextSecondary.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        colors = tfColors(), shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ===== 默认模型（识图 / 压缩 / 起标题 专用模型） =====
            if (openSection == "defaultModels") {
                SectionHeader("专用模型", expanded = null)
                SettingsCard {
                    Text(
                        "为特定用途指定专用模型；识图/压缩/起标题未指定时跟随当前聊天模型，生图模型需要支持图像输出的模型且必须单独指定。",
                        color = TextSecondary, fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "识图模型",
                        value = visionModel.takeIf { visionProviderId.isNotBlank() && it.isNotBlank() } ?: "跟随聊天模型",
                        onPick = { showVisionModelSheet = true },
                        onClear = {
                            visionProviderId = ""; visionModel = ""
                        }
                    )
                    SettingsCardDivider()
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.Compress,
                        label = "压缩模型",
                        value = compactionModel.takeIf { compactionProviderId.isNotBlank() && it.isNotBlank() } ?: "跟随聊天模型",
                        onPick = { showCompactionModelSheet = true },
                        onClear = {
                            compactionProviderId = ""; compactionModel = ""
                        }
                    )
                    SettingsCardDivider()
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.Title,
                        label = "起标题模型",
                        value = titleModel.takeIf { titleProviderId.isNotBlank() && it.isNotBlank() } ?: "跟随聊天模型",
                        onPick = { showTitleModelSheet = true },
                        onClear = {
                            titleProviderId = ""; titleModel = ""
                        }
                    )
                    SettingsCardDivider()
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.Psychology,
                        label = "子代理模型",
                        value = subagentModel.takeIf { subagentProviderId.isNotBlank() && it.isNotBlank() } ?: "跟随聊天模型",
                        onPick = { showSubagentModelSheet = true },
                        onClear = {
                            subagentProviderId = ""; subagentModel = ""
                        }
                    )
                    SettingsCardDivider()
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.Image,
                        label = "生图模型",
                        value = imageGenModel.takeIf { imageGenProviderId.isNotBlank() && it.isNotBlank() } ?: "未配置",
                        onPick = { showImageGenModelSheet = true },
                        onClear = {
                            imageGenProviderId = ""; imageGenModel = ""
                        }
                    )
                    SettingsCardDivider()
                    DefaultModelSettingRow(
                        icon = Icons.Outlined.AutoAwesome,
                        label = "知识提炼模型",
                        value = knowledgeModel.takeIf { knowledgeProviderId.isNotBlank() && it.isNotBlank() } ?: "跟随聊天模型",
                        onPick = { showKnowledgeModelSheet = true },
                        onClear = {
                            knowledgeProviderId = ""; knowledgeModel = ""
                        }
                    )
                    SettingsCardDivider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("知识提炼", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text("每轮对话结束后由提炼模型抽取经验，存进知识库供以后查询", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(checked = knowledgeExtractEnabled, onCheckedChange = { knowledgeExtractEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                    }
                    if (knowledgeExtractEnabled) {
                        SettingsCardDivider()
                        IntSettingSlider(
                            label = "提炼间隔",
                            value = knowledgeExtractEveryN,
                            range = 1..20,
                            display = { if (it <= 1) "每轮" else "每 $it 轮" }
                        ) { knowledgeExtractEveryN = it }
                    }
                    SettingsCardDivider()
                    VoiceSettingsSection(
                        voiceAutoSpeak = voiceAutoSpeak,
                        onVoiceAutoSpeakChange = { voiceAutoSpeak = it },
                    )
                    SettingsCardDivider()
                    PrivacySettingsSection(
                        maskOutbound = maskOutbound,
                        onMaskOutboundChange = { maskOutbound = it },
                    )
                }
                if (showVisionModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = visionProviderId,
                        currentModel = visionModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            visionProviderId = pid
                            visionModel = m
                            showVisionModelSheet = false
                        },
                        onClear = {
                            visionProviderId = ""; visionModel = ""
                            showVisionModelSheet = false
                        },
                        onDismiss = { showVisionModelSheet = false }
                    )
                }
                if (showCompactionModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = compactionProviderId,
                        currentModel = compactionModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            compactionProviderId = pid
                            compactionModel = m
                            showCompactionModelSheet = false
                        },
                        onClear = {
                            compactionProviderId = ""; compactionModel = ""
                            showCompactionModelSheet = false
                        },
                        onDismiss = { showCompactionModelSheet = false }
                    )
                }
                if (showTitleModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = titleProviderId,
                        currentModel = titleModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            titleProviderId = pid
                            titleModel = m
                            showTitleModelSheet = false
                        },
                        onClear = {
                            titleProviderId = ""; titleModel = ""
                            showTitleModelSheet = false
                        },
                        onDismiss = { showTitleModelSheet = false }
                    )
                }
                if (showKnowledgeModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = knowledgeProviderId,
                        currentModel = knowledgeModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            knowledgeProviderId = pid
                            knowledgeModel = m
                            showKnowledgeModelSheet = false
                        },
                        onClear = {
                            knowledgeProviderId = ""; knowledgeModel = ""
                            showKnowledgeModelSheet = false
                        },
                        onDismiss = { showKnowledgeModelSheet = false }
                    )
                }
                if (showSubagentModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = subagentProviderId,
                        currentModel = subagentModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            subagentProviderId = pid
                            subagentModel = m
                            showSubagentModelSheet = false
                        },
                        onClear = {
                            subagentProviderId = ""; subagentModel = ""
                            showSubagentModelSheet = false
                        },
                        onDismiss = { showSubagentModelSheet = false }
                    )
                }
                if (showImageGenModelSheet) {
                    ModelSelectionSheet(
                        providers = providers,
                        currentProviderId = imageGenProviderId,
                        currentModel = imageGenModel,
                        modelMetadata = providerModelMetadata,
                        onSelect = { pid, m ->
                            imageGenProviderId = pid
                            imageGenModel = m
                            showImageGenModelSheet = false
                        },
                        onClear = {
                            imageGenProviderId = ""; imageGenModel = ""
                            showImageGenModelSheet = false
                        },
                        onDismiss = { showImageGenModelSheet = false }
                    )
                }
            }

            // ===== 界面（可折叠，默认折叠） =====
            if (openSection == "ui") {
            // 主题/语言/字号/背景图等全部收进「外观设置」子页（点本行进入）。
            // 原来这些控件全内联在本分区里，一屏塞不下、且背景图那种带预览的控件
            // 放内联会非常拥挤。拆成子页后本分区只留入口与轻量开关。
            SettingsCard {
                SettingsNavRow(
                    title = "外观设置",
                    subtitle = appearanceSummary(themeName, bgEnabled, bgSourceType),
                    icon = Icons.Outlined.Palette
                ) { enterSection("appearance") }
            }
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示消息时间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("每条消息下显示发送时间", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = showTimestamp, onCheckedChange = { showTimestamp = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("触感反馈", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("发送消息时震动", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = hapticFeedback, onCheckedChange = { hapticFeedback = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动滚动", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("新消息到达时自动滚到底部", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = autoScroll, onCheckedChange = { autoScroll = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("展开插件快捷区", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("侧边栏的插件快捷区默认展开；关闭则默认折叠", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = pluginQuickBarExpanded, onCheckedChange = { pluginQuickBarExpanded = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("展开思考过程", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("AI 思考中自动展开；关闭则思考结束后自动折叠", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = thinkingAlwaysExpanded, onCheckedChange = { thinkingAlwaysExpanded = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                }
            }
            }

            // ===== 外观设置子页（主题模式 / 语言 / 字号 / 背景图 / 效果调整 / 自定义色号） =====
            // 「外观设置」整页，按 Compose 重写并去掉不适用项（宠物/工作区预览）。
            if (openSection == "appearance") {
                AppearanceSection(
                    state = AppearanceState(
                        themeName = themeName, themeMode = themeMode, language = language,
                        fontSize = fontSize, fontSizeCustomSp = fontSizeCustomSp,
                        uiScalePreset = uiScalePreset, uiScaleCustom = uiScaleCustom,
                        chatTextColorMode = chatTextColorMode, chatTextHexColor = chatTextHexColor,
                        bgEnabled = bgEnabled, bgSourceType = bgSourceType,
                        bgLocalPath = bgLocalPath, bgRemoteUrl = bgRemoteUrl,
                        bgBlurSigma = bgBlurSigma, bgOverlayAlpha = bgOverlayAlpha,
                        bgBrightness = bgBrightness, bgFocalX = bgFocalX,
                        bgFocalY = bgFocalY, bgImageScale = bgImageScale
                    ),
                    onChange = { s ->
                        themeName = s.themeName; themeMode = s.themeMode; language = s.language
                        fontSize = s.fontSize; fontSizeCustomSp = s.fontSizeCustomSp
                        uiScalePreset = s.uiScalePreset; uiScaleCustom = s.uiScaleCustom
                        chatTextColorMode = s.chatTextColorMode; chatTextHexColor = s.chatTextHexColor
                        bgEnabled = s.bgEnabled; bgSourceType = s.bgSourceType
                        bgLocalPath = s.bgLocalPath; bgRemoteUrl = s.bgRemoteUrl
                        bgBlurSigma = s.bgBlurSigma; bgOverlayAlpha = s.bgOverlayAlpha
                        bgBrightness = s.bgBrightness; bgFocalX = s.bgFocalX
                        bgFocalY = s.bgFocalY; bgImageScale = s.bgImageScale
                    }
                )
            }

            // ===== 通知（可折叠，默认折叠） =====
            if (openSection == "notify") {
                    SettingsCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI 完成提示音", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text("AI 回复完成时播放提示音", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(checked = notifyOnComplete, onCheckedChange = { notifyOnComplete = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                        }
                        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI 完成震动", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text("AI 回复完成时震动", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(checked = vibrateOnComplete, onCheckedChange = { vibrateOnComplete = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
                        }
                    }
                    if (notifyOnComplete) {
                        SectionHeader("提示音选择")
                    SettingsCard {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("提示音", fontSize = 14.sp, color = TextPrimary)
                                    Text(if (notificationSound == "default") "系统默认" else if (notificationSound == "none") "无" else notificationSound, fontSize = 11.sp, color = TextSecondary)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("default" to "默认", "none" to "静音").forEach { (v, l) ->
                                        Surface(onClick = { notificationSound = v }, shape = RoundedCornerShape(8.dp),
                                            color = if (notificationSound == v) Accent.copy(alpha = 0.2f) else PanelAlt,
                                            border = if (notificationSound == v) BorderStroke(1.dp, Accent) else null) {
                                            Text(l, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                                color = if (notificationSound == v) Accent else TextPrimary, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            // ===== 子代理设置 =====
            if (openSection == "subagent") {
                SettingsCard {
                    IntSettingSlider(
                        label = "并发上限",
                        value = subagentConcurrency,
                        range = 1..5,
                        display = { "$it 个" }
                    ) { subagentConcurrency = it }
                    SettingsCardDivider()
                    IntSettingSlider(
                        label = "单任务超时",
                        value = subagentTimeoutSec,
                        range = 30..7200,
                        display = { v ->
                            when {
                                v < 60 -> "$v 秒"
                                v % 60 == 0 -> "${v / 60} 分钟"
                                else -> "${v / 60} 分${v % 60} 秒"
                            }
                        },
                        manual = ManualNumberInput(
                            parse = { text, current -> SubAgentTimeoutInput.parse(text, current) },
                            quickSec = listOf(60, 300, 600, 1800, 3600, 7200),
                            quickText = { "${it / 60} 分钟" }
                        )
                    ) { subagentTimeoutSec = it }
                    SettingsCardDivider()
                    IntSettingSlider(
                        label = "工具轮次上限",
                        value = subagentMaxRounds,
                        range = 1..200,
                        display = { "$it 轮" }
                    ) { subagentMaxRounds = it }
                    SettingsCardDivider()
                    SettingsToggleRow(
                        "强制只读",
                        "子代理一律不得写文件、执行命令或做网页交互（默认关闭；记忆库保存不受影响）",
                        subagentReadOnly
                    ) { subagentReadOnly = it }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showSubAgentHelp) {
                SubAgentHelpDialog(onDismiss = { showSubAgentHelp = false })
            }
            if (showToolsHelp) {
                GuideDialog(
                    title = "工具页面说明",
                    blocks = listOf(
                        "并行执行批内工具" to "同一批已批准的工具同时跑，快但更吃限流；频繁报 429 就关掉改成串行。",
                        "自动执行已批准工具" to "批准后直接跑，不用再逐条点「执行」。",
                        "流式输出" to "回复逐字显示；关掉则整段一次性出现。",
                        "三个「模式」开关" to "工具权限 / 思考过程 / 模型选择：开 = 聊天页切档时改全局并统一所有会话；关 = 只改当前会话。",
                        "工具白名单" to "放行的工具调用时不再弹窗确认，适合常用且无风险的工具。"
                    ),
                    onDismiss = { showToolsHelp = false }
                )
            }
            if (showRuntimeEnvHelp) {
                GuideDialog(
                    title = "运行环境说明",
                    blocks = listOf(
                        "这一页干什么" to "检测并安装容器里插件依赖的命令行工具，缺失会导致插件安装脚本失败。",
                        "两条安装通道" to "apk 系工具（Git / Unzip / JDK）走 Alpine 官方源；pnpm / OpenCode / DeepSeek Harness 走 npm 全局包。",
                        "npm 源" to "只影响 pnpm（corepack）与 OpenCode；apk 系工具走「apk 镜像源」，两者独立。",
                        "Node.js / npm / Corepack" to "由官方二进制释放到 /usr/bin，不提供卸载——删了会连带弄坏上面三个入口，重建容器才彻底。",
                        "可更新提示" to "点「重装」升到最新版；卸载不可撤销，依赖它的插件会失效。"
                    ),
                    onDismiss = { showRuntimeEnvHelp = false }
                )
            }

            // ===== 工具（可折叠） =====
            if (openSection == "tools") {
                // 分组：与本页其余分区写法统一（SettingsCard 现已是无底板的纯容器）
                SettingsCard {
                    SettingsToggleRow("并行执行批内工具", "同批已批准工具并发执行（默认开启；频繁撞 429 会提示切回串行）", parallelToolCalls) { parallelToolCalls = it }
                    SettingsToggleRow("自动执行已批准工具", "每批工具经确认批准后自动执行，无需手动点执行（默认开启）", autoToolCall) { autoToolCall = it }
                    SettingsToggleRow("流式输出", "逐字流式显示回复", enableStream) { enableStream = it }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCard {
                    SettingsCardDivider()
                    // ===== 全局 / 会话 档位作用范围开关 =====
                    // 聊天页那两个下拉的**作用范围**由这两个开关决定：
                    //   关（默认）= 只改当前会话（会话级 override，互不影响）；
                    //   开        = 改全局默认档，并清空所有会话的 override → 全部统一。
                    // 有了「开」这一档，就不必在设置页重复一套档位选择器，也不必
                    // 「清除所有会话覆盖」按钮 —— 开着开关点一下档位即等价于清除+统一。
                    SettingsToggleRow(
                        "工具权限模式",
                        "开：聊天页切档改全局并统一所有会话；关（默认）：只改当前会话",
                        toolPermissionGlobalMode
                    ) { toolPermissionGlobalMode = it }
                    SettingsCardDivider()
                    SettingsToggleRow(
                        "思考过程模式",
                        "开：聊天页切强度改全局并统一所有会话；关（默认）：只改当前会话",
                        reasoningEffortGlobalMode
                    ) { reasoningEffortGlobalMode = it }
                    SettingsCardDivider()
                    // 与上面两个开关**方向一致**（开=全局、关=会话级），默认开。
                    // 历史沿革：本开关原为「模型选择模式」（开=会话级、默认关），
                    // 与另两个开关方向相反，用户反馈反直觉，已翻转语义并改名 modelGlobalMode。
                    SettingsToggleRow(
                        "模型选择模式",
                        "开：聊天页切模型改全局并统一所有会话；关（默认）：聊天页切模型只改当前会话",
                        modelGlobalMode
                    ) { modelGlobalMode = it }
                    SettingsCardDivider()
                    // 与上面几个开关同一层（本分组内），横向边距由容器统一给
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { showToolAllowDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("工具白名单", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (toolAllowList.isEmpty()) "「弹窗确认」档下调用写/执行类工具前先弹窗确认" else "已放行 ${toolAllowList.size} 个工具，调用时不再确认",
                                fontSize = 11.sp, color = TextSecondary
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // ===== 高级（可折叠，默认折叠，含系统提示词） =====
            if (openSection == "advanced") {
            SettingsCard {
                Text("系统提示词", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = systemPrompt, onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                    placeholder = { Text("设定 AI 的行为和角色...", color = TextSecondary.copy(alpha = 0.5f)) },
                    colors = tfColors(), shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 「通用助手」是默认值（用户 2026-10-05 要求：默认改通用助手），
                // 且必须与 AppSettings.systemPrompt / SettingsStore 的兜底值逐字一致，
                // 否则「首次默认值」与「点一下这个模板」会得到不同的提示词。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "通用助手" to "你是一个聪明、有用的AI助手。",
                        "编程专家" to "你是一个资深软件工程师，擅长各种编程语言和架构设计，请用代码示例解答问题。",
                        "翻译官" to "你是一个专业翻译，请准确翻译用户提供的内容，保持原意和语气。",
                        "股票专家" to "你是一个专业的股票分析师，精通技术分析（K线、均线、MACD、RSI、布林带）和基本面分析（PE/PB、财报、行业趋势），请给出专业的投资建议和风险提示。"
                    ).forEach { (label, prompt) ->
                        val isDefault = label == "通用助手"
                        Surface(onClick = { systemPrompt = prompt }, shape = RoundedCornerShape(8.dp),
                            color = Accent.copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.3f))) {
                            Text(
                                if (isDefault) "$label（默认）" else label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Accent, fontSize = 12.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = rules, onValueChange = { rules = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text("项目规则", color = TextSecondary) },
                    placeholder = { Text("设定项目开发规范和偏好...", color = TextSecondary.copy(alpha = 0.4f)) },
                    colors = tfColors(), shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("快速模板", fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Android Compose" to "这是一个 Jetpack Compose 项目。遵循：\n1. 使用 Material3 组件\n2. UI 层用 @Composable + State\n3. 业务逻辑在 ViewModel\n4. 使用 Hilt 依赖注入\n5. 遵循 MVVM 架构",
                        "Spring Boot" to "这是一个 Spring Boot 项目。遵循：\n1. Controller-Service-Repository 分层\n2. 使用 JPA + MySQL\n3. RESTful API 设计\n4. 统一异常处理\n5. 编写单元测试",
                        "Python 项目" to "这是一个 Python 项目。遵循：\n1. 使用 type hints\n2. 函数和类有 docstring\n3. 配置用环境变量\n4. 依赖列在 requirements.txt\n5. 遵循 PEP 8"
                    ).forEach { (label, template) ->
                        Surface(onClick = { rules = template },
                            shape = RoundedCornerShape(6.dp), color = Accent.copy(alpha = 0.1f)) {
                            Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            }

        // ===== 备份与恢复（独立子页，导航位于「应用权限授权」与「存储占用」之间） =====
        if (openSection == "backup") {
            val catalog = com.wy.openx.data.backup.BackupSectionCatalog.entries
            val allIds = remember(catalog) { catalog.map { it.id }.toSet() }
            val allOn = backupSectionIds.isNotEmpty() && backupSectionIds.size == allIds.size
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("备份与恢复", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "勾选这次要备份的内容，恢复时仍可逐段再选。工作区超过 2GB 会自动跳过。\n" +
                        "不包含容器系统（rootfs/镜像，可重新下载）与日志。\n" +
                        "勾了「工作区文件」则本次导出为明文（不加密）。",
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        backupSectionIds = if (allOn) emptySet() else allIds
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = allOn, onCheckedChange = { v -> backupSectionIds = if (v) allIds else emptySet() })
                    Text("全选 / 全不选", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.weight(1f))
                    Text("已选 ${backupSectionIds.size}/${catalog.size}", color = TextSecondary, fontSize = 11.sp)
                }
                catalog.forEach { e ->
                    val on = e.id in backupSectionIds
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { backupNotice = null; backupSectionIds = if (on) backupSectionIds - e.id else backupSectionIds + e.id }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { v -> backupSectionIds = if (v) backupSectionIds + e.id else backupSectionIds - e.id }
                        )
                        Text(e.label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (!e.defaultOn) {
                            Text("默认不选", color = TextSecondary.copy(alpha = 0.55f), fontSize = 10.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = {
                            if (backupBusy) return@Surface
                            if (backupSectionIds.isEmpty()) {
                                backupNotice = "请至少勾选一项备份内容"
                                return@Surface
                            }
                            backupNotice = null
                            // 口令改在弹窗里问（可留空 = 明文 zip），不在页面上平铺一个输入框
                            passwordInput = ""
                            passwordError = null
                            passwordPurpose = PwdPurpose.EXPORT
                        },
                        enabled = !backupBusy,
                        shape = RoundedCornerShape(10.dp), color = Accent.copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (backupBusy) "处理中…" else "备份数据",
                            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                            color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Surface(
                        onClick = {
                            if (restoreBusy) return@Surface
                            backupNotice = null
                            importSettingsLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*")
                            )
                        },
                        enabled = !restoreBusy,
                        shape = RoundedCornerShape(10.dp), color = Color(0xFF35C58B).copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (restoreBusy) "处理中…" else "恢复数据",
                            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                            color = Color(0xFF35C58B), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                if (backupNotice != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        backupNotice!!,
                        fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFFFF6B6B)
                    )
                }
            }
        }

        SettingsResultDialog()

        // ===== 加密备份：输入口令 =====
        if (passwordPurpose != null) {
            val forExport = passwordPurpose == PwdPurpose.EXPORT
            // 勾了工作区就必须明文（整包一个 zip），所以此时不提供口令框
            val exportHasWorkspace = com.wy.openx.data.backup.BackupSections.WORKSPACE in backupSectionIds
            val showPasswordField = !forExport || !exportHasWorkspace
            fun closeDialog() {
                passwordPurpose = null
                passwordInput = ""
                passwordError = null
                if (!forExport) pendingRestoreUri = null
            }
            AlertDialog(
                onDismissRequest = { closeDialog() },
                title = {
                    Text(
                        when {
                            !forExport -> "备份已加密"
                            exportHasWorkspace -> "备份（不加密）"
                            else -> "备份口令（可选）"
                        },
                        color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column {
                        Text(
                            when {
                                !forExport -> "这份备份加了口令，请输入口令继续。"
                                exportHasWorkspace ->
                                    "本次包含工作区文件，将导出为明文 zip，不加密。\n" +
                                        "整包是一个压缩文件，工作区在内就没法只加密其余部分；" +
                                        "取消勾选「工作区文件」即可使用口令加密。"
                                else -> "留空则导出明文 zip；填写后整包用 AES-128-GCM 加密，恢复时需要同一口令。"
                            },
                            color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp
                        )
                        if (showPasswordField) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it; passwordError = null },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (forExport) "口令（可留空）" else "口令", color = TextSecondary) },
                            isError = passwordError != null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                                focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        }
                        if (passwordError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(passwordError!!, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val purpose = passwordPurpose
                        val pwd = passwordInput
                        passwordPurpose = null
                        passwordInput = ""
                        passwordError = null
                        when (purpose) {
                            PwdPurpose.EXPORT -> {
                                // SAF 选择器是异步的：口令先存下来，等 uri 回来再传给 VM
                                pendingExportPassword = pwd
                                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
                                    .format(java.util.Date())
                                exportSettingsLauncher.launch("OpenX_backup_$stamp.zip")
                            }
                            PwdPurpose.RESTORE -> {
                                val uri = pendingRestoreUri
                                if (uri != null && pwd.isNotEmpty()) {
                                    // 必须重开流：上一次预检已经把字节读光了
                                    inspectRestore(uri, pwd, fromPasswordPrompt = true)
                                }
                            }
                            null -> Unit
                        }
                    }) { Text(if (forExport) "选择保存位置" else "确定", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { closeDialog() }) { Text("取消", color = TextSecondary) }
                },
                containerColor = Panel
            )
        }

        // ===== 恢复前确认：展示备份内容 + 选择恢复模式 + 勾选想要恢复的段 =====
        if (showRestoreDialog) {
            val manifest = pendingManifest
            // 勾选状态：默认全选；以备份 uri 为 key，换一份备份重播。
            val sectionIds = manifest?.sections?.map { it.id } ?: emptyList()
            var checkedIds by remember(pendingRestoreUri, sectionIds) { mutableStateOf(sectionIds.toSet()) }
            val allChecked = sectionIds.isNotEmpty() && checkedIds.size == sectionIds.size
            AlertDialog(
                onDismissRequest = { showRestoreDialog = false; pendingRestoreUri = null },
                title = { Text("恢复数据", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("这份备份包含：", color = TextSecondary, fontSize = 12.sp)
                        if (sectionIds.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { checkedIds = if (allChecked) emptySet() else sectionIds.toSet() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = allChecked, onCheckedChange = { checkedIds = if (it) sectionIds.toSet() else emptySet() })
                                Text("全选 / 全不选", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        manifest?.sections?.forEach { section ->
                            val checked = section.id in checkedIds
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { checkedIds = if (checked) checkedIds - section.id else checkedIds + section.id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { v -> checkedIds = if (v) checkedIds + section.id else checkedIds - section.id }
                                )
                                Text(section.label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("${section.count}", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                        if (manifest != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(manifest.createdAt))
                            Text("备份时间：$time（版本 ${manifest.appVersion}）", color = TextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("恢复方式", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "覆盖：先清空选中数据再还原，结果与备份时刻一致。\n" +
                                "合并：现有数据全部保留，备份内容追加进来（会话不覆盖，只新增）。",
                            color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = pendingRestoreUri
                        showRestoreDialog = false
                        if (uri != null) {
                            try {
                                val input = context.contentResolver.openInputStream(uri)
                                if (input == null) {
                                    resultDialog = ResultDialog("无法读取", false, "所选文件读不出来，换一个再试")
                                } else {
                                    onRestoreBackup(input, com.wy.openx.data.backup.RestoreMode.OVERWRITE, checkedIds, restorePassword) { r ->
                                        resultDialog = resultDialogOf("恢复", r)
                                    }
                                }
                            } catch (e: Exception) {
                                resultDialog = ResultDialog("恢复失败", false, "恢复失败：${e.message ?: "未知错误"}")
                            }
                        }
                        pendingRestoreUri = null
                        restorePassword = null
                    }) { Text("覆盖恢复", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            val uri = pendingRestoreUri
                            showRestoreDialog = false
                            if (uri != null) {
                                try {
                                    val input = context.contentResolver.openInputStream(uri)
                                    if (input == null) {
                                        resultDialog = ResultDialog("无法读取", false, "所选文件读不出来，换一个再试")
                                    } else {
                                        onRestoreBackup(input, com.wy.openx.data.backup.RestoreMode.MERGE, checkedIds, restorePassword) { r ->
                                            resultDialog = resultDialogOf("恢复", r)
                                        }
                                    }
                                } catch (e: Exception) {
                                    resultDialog = ResultDialog("恢复失败", false, "恢复失败：${e.message ?: "未知错误"}")
                                }
                            }
                            pendingRestoreUri = null
                            restorePassword = null
                        }) { Text("合并恢复", color = Accent) }
                        TextButton(onClick = { showRestoreDialog = false; pendingRestoreUri = null; restorePassword = null }) {
                            Text("取消", color = TextSecondary)
                        }
                    }
                },
                containerColor = Panel
            )
        }

        // ===== 容器（可折叠，默认折叠，含镜像下载） =====
            var downloadSourceId by remember { mutableStateOf<String?>(null) }
            var showResetConfirm by remember { mutableStateOf(false) }
            var containerFeedback by remember { mutableStateOf<String?>(null) }
            var showContainerEditor by remember { mutableStateOf(false) }
            var containerEditorProfile by remember { mutableStateOf<com.wy.openx.container.ContainerProfile?>(null) }
            var deletingContainerProfile by remember { mutableStateOf<com.wy.openx.container.ContainerProfile?>(null) }
            var switchContainerTarget by remember { mutableStateOf<com.wy.openx.container.ContainerProfile?>(null) }
            if (openSection == "container") {
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Linux 容器（Alpine / PRoot）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        val currentName = containerProfiles.firstOrNull { it.id == activeContainerId }?.name
                        Text(
                            if (containerInstalled) "已就绪 · 当前：${currentName ?: "内置 Alpine"}"
                            else "未安装 · 打开「终端」页将自动初始化",
                            fontSize = 11.sp,
                            color = if (containerInstalled) Success else TextSecondary
                        )
                    }
                    Surface(
                        onClick = { containerEditorProfile = null; showContainerEditor = true },
                        shape = RoundedCornerShape(8.dp), color = Accent.copy(alpha = 0.15f)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                            Text("添加镜像", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Text("容器列表（点击切换，铅笔编辑）", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                if (containerProfiles.isEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无容器配置", fontSize = 13.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(onClick = { onRestoreBuiltinAlpine() }, shape = RoundedCornerShape(8.dp), color = PanelAlt) {
                            Text("恢复内置 Alpine", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    containerProfiles.forEachIndexed { index, p ->
                        if (index > 0) {
                            HorizontalDivider(color = Border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        val isActive = p.id == activeContainerId
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { if (!isActive) switchContainerTarget = p }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(PanelAlt),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SdStorage, contentDescription = null, tint = if (isActive) Accent else TextSecondary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (p.isBuiltin) "内置" else "自定义",
                                        fontSize = 10.sp,
                                        color = if (p.isBuiltin) Accent else TextSecondary,
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                            .background(if (p.isBuiltin) Accent.copy(alpha = 0.12f) else PanelAlt)
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                    if (isActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("当前", fontSize = 10.sp, color = Success, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text(containerShellSubtitle(p), fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { containerEditorProfile = p; showContainerEditor = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑镜像", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { deletingContainerProfile = p }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "删除容器", tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Text("apk 镜像源", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Text("切换 Alpine 包下载源，立即生效；重置容器后保留此选择。", fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.wy.openx.container.ApkMirrors.ALL.forEach { m ->
                        val selected = containerMirrorId == m.id
                        Surface(
                            onClick = { onApplyContainerMirror(m.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Success.copy(alpha = 0.15f) else PanelAlt,
                            border = if (selected) BorderStroke(1.dp, Success) else null
                        ) {
                            Text(m.label,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                color = if (selected) Success else TextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                        }
                    }
                }
                containerFeedback?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = if (it.contains("成功")) Success else Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("重置容器", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("删除当前容器 rootfs，重新安装（停掉全部终端会话）", fontSize = 11.sp, color = TextSecondary)
                    }
                    Button(
                        onClick = { if (!containerResetBusy) showResetConfirm = true },
                        enabled = !containerResetBusy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B6B).copy(alpha = if (containerResetBusy) 0.4f else 1f),
                            contentColor = TextPrimary,
                            disabledContainerColor = Color(0xFFFF6B6B).copy(alpha = 0.4f),
                            disabledContentColor = TextSecondary
                        )
                    ) {
                        if (containerResetBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(if (containerResetBusy) "重置中…" else "重置", fontSize = 13.sp)
                    }
                }
            }
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { enterSection("imagerepo") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SdStorage, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("镜像下载", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text(
                                "下载常见发行版 rootfs，完成后可导入为自定义容器（含 --link2symlink）",
                                fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text("›", color = Accent, fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                // 运行环境：与镜像下载同为容器分区的子入口（深度 2）。
                // 放最后：它管的是容器内软件（node/pnpm/opencode…），跟上面的镜像列表、重置是两个层面。
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { enterSection("runtimeenv") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Dns, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("运行环境", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text(
                                "检测/安装容器内 Node.js、pnpm、OpenCode 等插件依赖（Alpine 官方源）",
                                fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text("›", color = Accent, fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                // Gradle 堆内存：放容器页最末，属于容器内构建环境的调节项，
                // 与上面的镜像列表、重置、子入口不同层面。
                GradleHeapSection(
                    configuredMb = gradleHeapMb,
                    onCommit = onGradleHeapChange,
                )
            }
            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("重置容器", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
                    text = { Text("将删除当前 Linux 容器并全部终端会话，重新打开「终端」页会重新安装（约需 30~60 秒）。镜像源选择与用户脚本会保留。继续？", color = TextSecondary, fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            showResetConfirm = false
                            containerFeedback = null
                            onResetContainer { ok ->
                                containerFeedback = if (ok) "重置成功，打开终端页将重新安装" else "重置失败，请稍后重试"
                            }
                        }) { Text("重置", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) { Text("取消", color = TextSecondary) }
                    }
                )
            }
            if (showContainerEditor) {
                ContainerProfileEditorSheet(
                    initial = containerEditorProfile,
                    // 开关的初始状态必须来自真实的当前容器 id（见 ContainerActiveSwitch）
                    activeProfileId = activeContainerId,
                    onDismiss = { showContainerEditor = false },
                    onConfirm = { profile, setActive ->
                        val existingId = containerEditorProfile?.id
                        val finalProfile = profile.copy(
                            id = existingId ?: profile.id.ifEmpty { "custom-${System.currentTimeMillis()}" }
                        )
                        onSaveContainerProfile(finalProfile, setActive, existingId == null)
                        showContainerEditor = false
                    }
                )
            }
            deletingContainerProfile?.let { target ->
                AlertDialog(
                    onDismissRequest = { deletingContainerProfile = null },
                    title = { Text("删除容器", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
                    text = { Text("删除「${target.name}」的配置与本地镜像（内置可随时恢复）。继续？", color = TextSecondary, fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            onDeleteContainerProfile(target)
                            deletingContainerProfile = null
                        }) { Text("删除", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletingContainerProfile = null }) { Text("取消", color = TextSecondary) }
                    }
                )
            }
            switchContainerTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { switchContainerTarget = null },
                    title = { Text("切换容器", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
                    text = { Text("切换到「${target.name}」？将停止当前终端会话，下次打开终端页使用新容器。", color = TextSecondary, fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            containerFeedback = null
                            onApplyContainerProfile(target.id) { ok ->
                                containerFeedback = if (ok) "已切换：${target.name}（打开终端页生效）" else "切换失败，请稍后重试"
                            }
                            switchContainerTarget = null
                        }) { Text("切换", color = Accent, fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { switchContainerTarget = null }) { Text("取消", color = TextSecondary) }
                    }
                )
            }

        // ===== 镜像下载（子页面，原为底部 sheet） =====
        // 与容器分区同级：容器 → 点「镜像下载」→ 本页；顶栏返回回容器分区。
            val activeDownloadSource = downloadSourceId ?: containerImageSources.firstOrNull()?.first
            // ===== 运行环境子页（容器内插件依赖：node/pnpm/opencode…） =====
            if (openSection == "runtimeenv") {
                RuntimeEnvSection(
                    runtimeTools = runtimeTools,
                    onDetectRuntimeTools = onDetectRuntimeTools,
                    onInstallRuntimeTool = onInstallRuntimeTool,
                    onUninstallRuntimeTool = onUninstallRuntimeTool,
                    npmRegistryId = npmRegistryId,
                    onSetNpmRegistry = onSetNpmRegistry
                )
            }

            if (openSection == "imagerepo") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("镜像仓库", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("下载常见发行版 rootfs，完成后可导入为自定义容器（含 --link2symlink）", fontSize = 11.sp, color = TextSecondary)
                            }
                            Text("架构：${com.wy.openx.container.ContainerImageCatalog.CURRENT_ABI}", fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.6f))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (containerImageSources.size > 1) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                containerImageSources.forEach { (id, label) ->
                                    val sel = id == activeDownloadSource
                                    Surface(
                                        onClick = { downloadSourceId = id },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (sel) Accent.copy(alpha = 0.15f) else PanelAlt,
                                        border = if (sel) BorderStroke(1.dp, Accent) else null
                                    ) {
                                        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                            color = if (sel) Accent else TextSecondary,
                                            fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (containerCatalog.isEmpty()) {
                            Text("镜像目录加载中…", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                containerCatalog.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        HorizontalDivider(color = Border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${entry.name} ${entry.version}", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                                    color = TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                val rec = downloadedContainerImages[entry.id]
                                                if (rec != null && rec.installed) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("已导入", fontSize = 10.sp, color = Success, modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                                        .background(Success.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
                                                }
                                            }
                                            if (entry.description.isNotBlank()) {
                                                Text(entry.description, fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            }
                                            Text(formatDownloadSize(entry.sizeBytes), fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.6f))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        DownloadEntryAction(
                                            entry = entry,
                                            record = downloadedContainerImages[entry.id],
                                            state = containerDownloadState,
                                            sourceId = activeDownloadSource,
                                            onDownload = { onDownloadContainerImage(entry, it) },
                                            onCancel = onCancelContainerDownload,
                                            onImport = { onImportDownloadedImage(entry.id, it) },
                                            onDelete = { onDeleteDownloadedImage(entry.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

        // ===== 以下为「主页专属」区块：用量统计 / 日志 / 版本 =====
        // 必须只在主页（openSection == null）显示。
        if (openSection == null) {
            var dbSummary by remember { mutableStateOf<com.wy.openx.data.local.CallSummary?>(null) }
            LaunchedEffect(llmCallRecordDao) {
                val d = llmCallRecordDao ?: return@LaunchedEffect
                d.getSummary(0L).collect { dbSummary = it }
            }
            val cached = dbSummary?.cachedInputTokens ?: 0L
            val calls = dbSummary?.calls ?: 0
            val totalIn = dbSummary?.inputTokens ?: 0L
            val totalOut = dbSummary?.outputTokens ?: 0L

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTokenStats() }
                        .padding(top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("用量统计", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(Icons.Default.Cached, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                                Text(formatTokens(cached), fontSize = 11.sp, color = TextSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(Icons.Default.CallReceived, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                                Text("调用 $calls 次", fontSize = 11.sp, color = TextSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(Icons.Default.CallMade, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                                Text("↑${formatTokens(totalIn)} ↓${formatTokens(totalOut)}", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLogs() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("运行日志", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("查看应用运行日志", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAbout() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("版本", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(com.wy.openx.BuildConfig.VERSION_NAME, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
            // ===== 底部：仅保留退出（设置改动即时保存，已无「保存设置」按钮） =====
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onExitApp,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("退出软件", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("保存 API 配置", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("为当前配置命名：", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetNameInput, onValueChange = { presetNameInput = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("例如：SiliconFlow-Qwen", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            onSavePreset(presetNameInput.trim(), selectedPreset, baseUrl, model, apiKey)
                            showSavePresetDialog = false
                        }
                    },
                    enabled = presetNameInput.isNotBlank()
                ) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    showDeletePresetConfirm?.let { presetName ->
        AlertDialog(
            onDismissRequest = { showDeletePresetConfirm = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("确定要删除 API 配置「${presetName}」吗？此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showDeletePresetConfirm = null; onDeletePreset(presetName) }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePresetConfirm = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    showPresetMetaSheet?.let { preset ->
        PresetMetaSheet(
            preset = preset,
            onSave = { model, contextTokens, outputTokens ->
                showPresetMetaSheet = null
                onUpdatePresetMeta(preset.name, model, contextTokens, outputTokens)
            },
            onDismiss = { showPresetMetaSheet = null }
        )
    }

    if (showToolAllowDialog) {
        AlertDialog(
            onDismissRequest = { showToolAllowDialog = false },
            title = { Text("工具白名单", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("已「始终允许」的工具在规划模式下调用时不再弹窗确认。", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (toolAllowList.isEmpty()) {
                        Text("（暂无白名单工具）", color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
                    } else {
                        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            toolAllowList.forEach { name ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { toolAllowList = toolAllowList - name }) {
                                        Icon(Icons.Filled.Close, contentDescription = "移除", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = { toolAllowList = emptyList() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清空全部", color = Color(0xFFFF6B6B), fontSize = 13.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToolAllowDialog = false }) { Text("关闭", color = Accent) }
            },
            containerColor = Panel
        )
    }
}

/**
 * 模型设置弹窗：编辑模型名 / 输入输出窗口，支持从 models.dev 自动获取上下文。
 *
 * 设为 `internal` 而非 `private`：输入栏长按上下文圆环也复用这个抽屉
 * （改当前模型的上下文窗口），避免为同一件事维护两套 UI。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetMetaSheet(
    preset: ApiPreset,
    onSave: (model: String, contextTokens: Long?, outputTokens: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var modelName by remember { mutableStateOf(preset.model) }
    var inputTokens by remember { mutableStateOf(preset.contextTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var outputTokens by remember { mutableStateOf(preset.outputTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf("") }

    val doFetch: (Boolean) -> Unit = { force ->
        if (!fetching) {
            scope.launch {
                fetching = true
                fetchNote = ""
                val info = ModelMetaFetcher.fetch(preset.provider, modelName.trim())
                // force=手动按钮：始终回填；force=false 自动打开：只在为空时回填，避免覆盖手动保存的值
                if (info.inputTokens != null && (force || inputTokens.isBlank())) inputTokens = info.inputTokens.toString()
                if (info.outputTokens != null && (force || outputTokens.isBlank())) outputTokens = info.outputTokens.toString()
                fetchNote = if (info.inputTokens != null)
                    "已获取：输入 ${info.inputTokens}，输出 ${info.outputTokens ?: "未知"} Tokens"
                else
                    "未能获取，请检查模型名称或网络后手动填写"
                fetching = false
            }
        }
    }

    // 已选择过的模型（已保存上下文）打开弹窗时自动获取一次并显示（不覆盖手动保存的值）
    LaunchedEffect(preset.name, preset.model) {
        if ((preset.contextTokens ?: 0) > 0) {
            doFetch(false)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("模型设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
            Text(preset.name, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(18.dp))

            Text("模型名称", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = modelName, onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text("输入窗口 (Token)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = inputTokens, onValueChange = { inputTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("例如 128000，留空则自动压缩用默认回退", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text("输出窗口 (Token)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = outputTokens, onValueChange = { outputTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("例如 8192（可选）", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { doFetch(true) },
                enabled = !fetching && modelName.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSoftBg, contentColor = ButtonSoftFg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取中...", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    Text("自动获取上下文", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (fetchNote.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(fetchNote, color = if (fetchNote.contains("未")) Color(0xFFFF6B6B) else Color(0xFF35C58B), fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = {
                    val ctx = inputTokens.trim().toLongOrNull()
                    val out = outputTokens.trim().toLongOrNull()
                    onSave(modelName.trim(), ctx, out)
                },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 「当前模型窗口」编辑弹窗（聊天页圆环长按触发）。
 *
 * 与 [PresetMetaSheet] 的区别：编辑对象是「当前提供商 + 当前模型」（provider 体系），
 * 而非旧的 ApiPreset。模型名只读展示（改模型走「选择模型」列表），只让用户改输入/输出窗口，
 * 保存写入 provider 的自定义模型元数据，与设置页「编辑模型」同一份数据、立即生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CurrentModelMetaSheet(
    modelName: String,
    initial: com.wy.openx.data.model.ModelMetadata?,
    onSave: (contextTokens: Long?, outputTokens: Long?) -> Unit,
    onAutoFetch: ((Long?, Long?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inputTokens by remember {
        mutableStateOf(
            (initial?.inputTokens ?: initial?.contextTokens)?.takeIf { it > 0 }?.toString() ?: ""
        )
    }
    var outputTokens by remember { mutableStateOf(initial?.outputTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf("") }

    val doFetch: (Boolean) -> Unit = { force ->
        if (!fetching) {
            fetching = true
            fetchNote = ""
            onAutoFetch { inTok, outTok ->
                // force=手动：始终回填；自动：只在为空时回填，避免覆盖手动保存的值
                if (inTok != null && (force || inputTokens.isBlank())) inputTokens = inTok.toString()
                if (outTok != null && (force || outputTokens.isBlank())) outputTokens = outTok.toString()
                fetchNote = if (inTok != null) "已获取：输入 $inTok，输出 ${outTok ?: "未知"} Tokens"
                else "未能获取，请检查模型名称或网络后手动填写"
                fetching = false
            }
        }
    }

    // 打开时若有已保存窗口则自动获取一次（不覆盖手动值）
    LaunchedEffect(modelName) {
        if ((initial?.inputTokens ?: initial?.contextTokens ?: 0) > 0) doFetch(false)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("模型设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
            Text(modelName, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(18.dp))

            Text("输入窗口 (Token)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = inputTokens, onValueChange = { inputTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("例如 128000，留空则用自动获取/默认值", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text("输出窗口 (Token)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = outputTokens, onValueChange = { outputTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("例如 8192（可选）", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { doFetch(true) },
                enabled = !fetching,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSoftBg, contentColor = ButtonSoftFg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取中...", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    Text("自动获取上下文", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (fetchNote.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(fetchNote, color = if (fetchNote.contains("未")) Color(0xFFFF6B6B) else Color(0xFF35C58B), fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = {
                    onSave(inputTokens.trim().toLongOrNull(), outputTokens.trim().toLongOrNull())
                },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier, expanded: Boolean? = null, onToggle: (() -> Unit)? = null) {
    Row(modifier = modifier
        .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
        .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
        if (expanded != null) {
            Text(if (expanded) "▲" else "▼", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    // 不再画卡片底板：与「偏好设置」页统一，各行直接铺在页面背景上，
    // 选了背景图时能直接透出来（原来 Panel 底 + 圆角会盖住背景）。
    //
    // 左右边距由外层滚动容器统一给 16dp（与 PreferencesScreen 一致），
    // 这里只留组间纵向间距。**行组件自身不要再加横向内边距**，否则会叠成 32dp。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) { content() }
}

/**
 * 提供商列表的品牌分组标题：直接用品牌名（不做 uppercase，避免 "DEEPSEEK" 这种中文/品牌名观感差）。
 *
 * 横向缩进由外层容器统一给（16dp），这里不再自带 start 边距 —— 否则会叠成 36dp、
 * 比下方的提供商行多缩进一截。
 */
@Composable
private fun ProviderBrandHeader(brand: String) {
    Text(
        brand,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)
    )
}

/** 已下载镜像的文件是否仍在（文件被删后回到可重新下载态）。 */
private fun downloadedFileExists(uri: String): Boolean =
    runCatching {
        val path = android.net.Uri.parse(uri).path ?: return@runCatching false
        java.io.File(path).isFile
    }.getOrDefault(false)

@Composable
private fun DownloadEntryAction(
    entry: com.wy.openx.container.ContainerImageEntry,
    record: com.wy.openx.container.DownloadedImageRecord?,
    state: com.wy.openx.ui.viewmodel.ContainerDownloadUiState,
    sourceId: String?,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onImport: (String) -> Unit,
    onDelete: () -> Unit
) {
    val downloading = state as? com.wy.openx.ui.viewmodel.ContainerDownloadUiState.Downloading
    val isDownloadingThis = downloading != null && downloading.entryId == entry.id
    val error = state as? com.wy.openx.ui.viewmodel.ContainerDownloadUiState.Error
    val isErrorThis = error != null && error.entryId == entry.id

    when {
        isDownloadingThis -> {
            Column(horizontalAlignment = Alignment.End) {
                val total = downloading?.totalBytes ?: 0L
                val read = downloading?.bytesRead ?: 0L
                val fraction = if (total <= 0) 0f
                else (read.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.width(92.dp).height(4.dp),
                    color = Accent,
                    trackColor = Border.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (total <= 0) "${read / 1024}KB"
                    else "${read / 1024}KB / ${total / 1024}KB",
                    fontSize = 9.sp, color = TextSecondary
                )
                Text("取消", fontSize = 11.sp, color = Accent,
                    modifier = Modifier.clickable { onCancel() }.padding(top = 1.dp))
            }
        }
        isErrorThis && record == null -> {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(120.dp)) {
                Text(error?.message ?: "下载失败", fontSize = 9.sp, color = Color(0xFFFF6B6B),
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("重试", fontSize = 11.sp, color = Accent,
                    modifier = Modifier.clickable { sourceId?.let(onDownload) }.padding(top = 1.dp))
            }
        }
        record != null && downloadedFileExists(record.fileUri) -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!record.installed) {
                    Surface(
                        onClick = { onImport(record.fileUri) },
                        shape = RoundedCornerShape(8.dp), color = Accent.copy(alpha = 0.15f)
                    ) {
                        Text("导入", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text("已下载", fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.6f))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除下载", tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                }
            }
        }
        else -> {
            Surface(
                onClick = { sourceId?.let(onDownload) },
                shape = RoundedCornerShape(8.dp), color = PanelAlt
            ) {
                Text(if (isErrorThis) "重试" else "下载",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = if (isErrorThis) Accent else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatDownloadSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
}

@Composable
private fun SettingsToggleRow(label: String, subtitle: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    // 不再自画底色：与偏好设置页统一，直接铺在页面背景上（背景图可透出）。
    // 横向内边距由外层容器统一给 16dp，这里只留纵向间距。
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheck, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border))
    }
    // 本行**没有图标**，文字从行首开始，故分隔线不缩进（原来缩进 16 是因为行内自带 16 内边距）。
    HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
}

/**
 * 上下文压缩触发阈值滑块（50%~95%）
 * [contextRatio] 为 0..1 的比例；手动模式下滑动即时生效。
 */
@Composable
private fun CompactionThresholdSlider(contextRatio: Float, onChange: (Float) -> Unit) {
    val percent = (contextRatio * 100f).roundToInt().coerceIn(50, 95)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("上下文总结阀值", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$percent%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it / 100f) },
            valueRange = 50f..95f,
            steps = 44,
            colors = SliderDefaults.colors(
                activeTrackColor = Accent,
                thumbColor = Accent,
                inactiveTrackColor = Border
            )
        )
        Text("当 Token 使用超过该比例时触发压缩。", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
    }
}

/** 数值滑块的手动输入能力；为 null 时该滑块只支持拖动。 */
private class ManualNumberInput(
    /** 解析用户输入，失败返回 null（不改值） */
    val parse: (String, Int) -> Int?,
    /** 快捷选项（单位：秒） */
    val quickSec: List<Int>,
    val quickText: (Int) -> String
)

/**
 * 整数滑块。[manual] 非 null 时，右侧数值标签可点开弹窗手输。
 *
 * ## 为什么用弹窗而不是行内输入框
 * 第一版做的是行内 `BasicTextField`（点标签就地变成输入框），实测**点了输入不了**。
 * 两个原因叠加：
 * 1. 设置页在 `LazyColumn` 里，软键盘弹出会引起列表重排/复用，行内状态用 `remember`
 *    存不住，编辑框会直接消失。改 `rememberSaveable` 能扛住，但仍然脆弱。
 * 2. 行内框靠 `onFocusChanged` 判失焦提交，任何原因导致的失焦（重组、列表滚动、
 *    窗口变化）都会**静默丢弃**这次编辑，用户白敲一遍。
 *
 * 弹窗有独立窗口，焦点和软键盘行为可靠，还自带确定/取消与「键盘完成」提交，
 * 解析失败能当场看到原因。滑块保留做粗调，定值走弹窗。
 */
/** 整数滑杆（复用 [CompactionThresholdSlider] 的样式，供子代理设置这类离散档位使用）。 */
@Composable
private fun IntSettingSlider(
    label: String,
    value: Int,
    range: IntRange,
    display: (Int) -> String,
    /** 行内说明。为空则不渲染——设置页的小字收进底部「使用说明」弹窗，避免一屏灰字。 */
    hint: String = "",
    manual: ManualNumberInput? = null,
    onChange: (Int) -> Unit
) {
    val v = value.coerceIn(range.first, range.last)
    // rememberSaveable：弹窗可见性与草稿必须扛得住 LazyColumn 复用与软键盘引起的重组
    var showManual by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (manual == null) {
                Text(display(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            } else {
                // 1770 步的滑块拖到「7 分钟」不现实，所以数值本身可点
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent.copy(alpha = 0.10f))
                        .clickable {
                            draft = SubAgentTimeoutInput.draftFor(v)
                            showManual = true
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(display(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        Icons.Default.Edit, contentDescription = "手动输入",
                        tint = Accent.copy(alpha = 0.7f), modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Slider(
            value = v.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                activeTrackColor = Accent,
                thumbColor = Accent,
                inactiveTrackColor = Border
            )
        )
        if (hint.isNotBlank()) {
            Text(hint, color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }

    if (showManual && manual != null) {
        ManualNumberDialog(
            title = label,
            draft = draft,
            onDraftChange = { draft = it },
            quickSec = manual.quickSec,
            quickText = manual.quickText,
            parse = manual.parse,
            currentSec = v,
            display = display,
            onDismiss = { showManual = false },
            onConfirm = { parsed ->
                onChange(parsed.coerceIn(range.first, range.last))
                showManual = false
            }
        )
    }
}

/**
 * 子代理设置的使用说明。
 *
 * 这些说明原先是每个设置项下面的常驻小字，一屏叠五六行 10sp 灰字，正文设置项被淹没（用户反馈
 * 观感差）。但内容不能删——「轮次上限与超时是两种不同的熔断」这类信息删掉等于把踩坑点藏起来。
 * 故收进底部「使用说明」一行，点开看详情。
 *
 * ⚠ 里面的每个数字都必须与实现对齐（更新时逐项核对过源码）：
 *  - 并发上限 1..5、默认 3 → `SubAgentLimits.concurrencyCap()`；
 *  - 工具轮次上限 1..200、默认 50 → `SubAgentLimits.maxRounds()`；
 *  - 单任务超时 30..7200 秒 = 30 秒 ~ 120 分钟、默认 1800 秒（30 分钟） → `SubAgentLimits.timeoutMs()`；
 *  - 熔断的两种失败方式 → `ChatRepository.executeAndFollowUp` 的轮次触顶收尾分支
 *    与 `withTimeoutOrNull(req.timeoutMs)`。
 * 改动这三处预算时，记得同步回来。
 */
@Composable
private fun SubAgentHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("子代理设置说明", color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HelpBlock(
                    "子代理是什么",
                    "AI 遇到独立的活儿时可以派子代理去干，自己继续主线。派多个子任务时「并发上限」决定同时跑几个，" +
                        "其余排队。\n\n" +
                        "可用角色：通用子代理（继承全部工具）、代码探索者（只读探索）、记忆管理员（整理记忆）、" +
                        "规划器（不调工具、只出计划）。角色由模型在分派时指定。\n\n" +
                        "子代理不能再派子代理（会无限套娃烧额度），所以最外层的活只能由主 AI 决定。"
                )
                HelpBlock(
                    "并发上限（1 ~ 5，默认 3）",
                    "同时运行的子代理数量。模型每次分派的个数若超过此上限，会被压到上限跑（不是报错）。\n\n" +
                        "terminal / browser 这类有状态工具并发会互相干扰；中转站限流或按量计费时建议压到 1。"
                )
                HelpBlock(
                    "工具轮次上限（1 ~ 200，默认 50）",
                    "单个子代理最多能发起多少轮工具调用。一轮 = 一次模型请求带上工具调用 + 拿到工具结果。\n\n" +
                        "触顶后不会直接掐断：系统会再发一次「不带工具」的请求，让模型基于已获得的信息给出结论，" +
                        "必要时由它自己写明还缺什么。所以触顶不等于失败，但结论可能不完整。\n\n" +
                        "扫大仓库、改大工程时模型容易空转烧额度，可以适当调大。"
                )
                HelpBlock(
                    "单任务超时（30 秒 ~ 120 分钟，默认 30 分钟）",
                    "单个子代理的最长运行时间。超时是「硬截断」：之前已完成的搜索结果会全部丢掉，" +
                        "一个字结论都拿不到。所以改代码、扫大仓库时容易慢，超时太短会白跑。\n\n" +
                        "点数值可手动输入：纯数字按当前单位算（显示分钟时填 7 就是 7 分钟），" +
                        "也可带单位，如 90s / 30m / 1.5h / 2h。"
                )
                HelpBlock(
                    "调大轮次要同时放宽超时",
                    "轮次上限和超时是两个独立的熔断，而且「失败方式不同」：\n" +
                        "· 轮次触顶 → 仍会做一次收尾，你能拿到总结（可能不完整）\n" +
                        "· 超时触顶 → 直接判超时，累积上下文全丢\n\n" +
                        "只调大轮次而不放宽超时，很可能只是把熔断原因从「轮次触顶」换成更糟的「超时截断」。"
                )
                HelpBlock(
                    "强制只读（默认关闭）",
                    "开启后所有角色的权限收敛到探索类的只读集合（文件 / 代码 / 网页 / 记忆读取）。" +
                        "通用子代理也不例外——它需要改文件时会被挡下并自己改路；规划器本来就没有工具。\n\n" +
                        "两个例外：记忆管理员仍可写记忆库（那是它的职责本身）；" +
                        "file_manager 的 write/delete、browser 的点击/填表/执行脚本这类动作也会被挡下，" +
                        "不只是不显示整个工具。\n\n" +
                        "记忆库保存不受影响。"
                )
                HelpBlock(
                    "子代理模型",
                    "在「默认模型」里配置（子代理模型那项），本页只管并发、轮次上限、超时与只读策略。\n\n" +
                        "留空则跟随当前聊天模型；建议给探索类子代理配个便宜的小模型，主线用强模型。"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = Accent) }
        },
        containerColor = Panel
    )
}

/** 使用说明弹窗里的一段：标题 + 正文。 */
@Composable
private fun HelpBlock(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * 手动输入数值弹窗。带实时预览（输入合法就显示换算结果）与解析失败原因，
 * 免得用户点了「确定」才发现没生效、也不知道差在哪。
 */
@Composable
private fun ManualNumberDialog(
    title: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    quickSec: List<Int>,
    quickText: (Int) -> String,
    parse: (String, Int) -> Int?,
    currentSec: Int,
    display: (Int) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val parsed = parse(draft, currentSec)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title（手动输入）", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    placeholder = { Text("如 7 / 7m / 90s", color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp) },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) ?: onDismiss() }),
                    colors = tfColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    when {
                        draft.isBlank() -> "直接填数字按当前单位算；也可带单位 s / m / h。"
                        parsed != null -> "将设为：${display(parsed)}"
                        else -> "看不懂这个值。填纯数字，或带单位（30s / 10m / 0.5h）。"
                    },
                    color = if (parsed != null) Accent else TextSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                if (quickSec.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quickSec.forEach { s ->
                            val text = quickText(s)
                            val on = parse(draft, currentSec) == s
                            Surface(
                                // 必须带显式单位 m：裸数字是按**当前值**的单位解释的，
                                // 当前若是秒档（<60），填 "1" 会被当成 1 秒再夹到 30 秒下限，
                                // 点「1 分钟」反而设成 30 秒。写死 "1m" 才不受当前单位影响。
                                onClick = { onDraftChange("${s / 60}m") },
                                shape = RoundedCornerShape(8.dp),
                                color = if (on) Accent.copy(alpha = 0.2f) else PanelAlt,
                                border = if (on) BorderStroke(1.dp, Accent) else null
                            ) {
                                Text(
                                    text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    color = if (on) Accent else TextPrimary, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "确定",
                color = if (parsed != null) Accent else TextSecondary.copy(alpha = 0.4f),
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = parsed != null) { parsed?.let(onConfirm) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        },
        dismissButton = {
            Text(
                "取消", color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    )
}

@Composable
internal fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary
)

internal val MODEL_PRICES = mapOf(
    "gpt-4o" to 2.50, "gpt-4o-" to 2.50, "gpt-4-turbo" to 10.0,
    "gpt-4-" to 30.0, "gpt-3.5" to 0.50,
    "claude-3-5-sonnet" to 3.0, "claude-3-opus" to 15.0, "claude-3-haiku" to 0.25, "claude-3-" to 3.0,
    "claude-sonnet-4" to 3.0,
    "deepseek" to 0.27, "deepseek-reasoner" to 0.55,
    "qwen" to 0.15, "qwen2.5-7b" to 0.10, "qwen3.5-4b" to 0.10, "qwen2.5-32b" to 0.20, "qwen2.5-72b" to 0.40,
    "gemini-2.0-flash" to 0.10, "gemini-2.0-pro" to 2.0, "gemini-" to 0.10,
    "glm" to 0.50, "yi-" to 0.50, "moonshot" to 1.0,
    "llama" to 0.25, "mistral" to 0.25,
)

internal fun estimateCost(model: String, tokens: Long): Double {
    val price = MODEL_PRICES.entries.firstOrNull { model.contains(it.key, ignoreCase = true) }?.value ?: return 0.0
    return (tokens.toDouble() / 1_000_000.0) * price
}

internal fun formatCost(cost: Double): String {
    if (cost <= 0) return ""
    return when {
        cost < 0.01 -> String.format(java.util.Locale.US, "%.4f", cost)
        cost < 1.0 -> String.format(java.util.Locale.US, "%.3f", cost)
        else -> String.format(java.util.Locale.US, "%.2f", cost)
    }
}

internal fun formatTokens(tokens: Long): String = when {
    tokens < 1000 -> "$tokens"
    tokens < 1_000_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1000.0)
    else -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
}

/**
 * 分区导航行：`[图标] 标题 / 副标题 >`
 *
 * 图标 18、标题 14/w500、副标题 11。
 */
@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle, color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 导航行之间的分隔线。
 *
 * 用 `start = 28.dp` 缩进（图标 18 + 间距 10），让线从**文字左侧**开始而不是横贯图标，
 * 这是实现 `_buildSettingsSection` 的做法，比通栏分割线更精致。
 */
@Composable
private fun SettingsCardDivider() {
    HorizontalDivider(
        color = Border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 28.dp)
    )
}

/**
 * 「默认模型」分区行：`[图标] 标题 ｜ 当前值(跟随聊天模型) `跟随` 按钮`。
 * 点击主体弹出模型选择，右侧「跟随」一键清除专用配置（回退跟随聊天模型）。
 */
@Composable
private fun DefaultModelSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            value, color = if (value == "跟随聊天模型") TextSecondary else Accent,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "跟随", color = Accent, fontSize = 12.sp,
            modifier = Modifier
                .clickable { onClear() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * 外观设置子页的**全部可变状态**。
 *
 * 用一个数据类承载而不是 20 多个 `onXxx` 回调：状态项太多时逐个透传
 * 既容易漏、又会在增删字段时到处改签名。子页只持有它 + 一个 `onChange`，
 * 改哪项就 `onChange(state.copy(...))`。
 */
private data class AppearanceState(
    val themeName: String,
    val themeMode: String,
    val language: String,
    val fontSize: String,
    val fontSizeCustomSp: Int,
    val uiScalePreset: String,
    val uiScaleCustom: Float,
    val chatTextColorMode: String,
    val chatTextHexColor: String,
    val bgEnabled: Boolean,
    val bgSourceType: String,
    val bgLocalPath: String,
    val bgRemoteUrl: String,
    val bgBlurSigma: Float,
    val bgOverlayAlpha: Float,
    val bgBrightness: Float,
    val bgFocalX: Float,
    val bgFocalY: Float,
    val bgImageScale: Float
)

/**
 * 主题可选配色（已精简：删除与「系统默认」/科技蓝重叠的亮色、极简亮、极简暗）。
 *
 * ⚠ 这里的字符串**既是显示名也是持久化值**（`SettingsStore.themeName`），所以改名不是纯文案改动：
 * 老用户库里存的是「跟随系统」，改名后必须由 SettingsStore 读取时归一化，否则主题列表
 * 会匹配不到任何一项、显示「无选中」，且 `resolveTheme` 走 else 分支的浅米配色虽然碰巧
 * 一样，但那是巧合不是保证。见 SettingsStore 里 themeName 的归一化集合。
 */
private val THEME_PALETTES = listOf("系统默认", "科技蓝", "暖白", "玻璃色", "简约白")

/** 明暗模式三档：值 → 显示名。值是稳定的英文 key，与显示名解耦，改名不影响老数据。 */
private val THEME_MODES = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

/** 界面字号档位。前三项是预设（sp 映射见 AgentConsoleScreen），末项改用绝对 sp 值。 */
private val FONT_SIZE_PRESETS = listOf(
    "small" to "小", "medium" to "中", "large" to "大", "custom" to "自定义"
)

/** 自定义字号范围（sp）。下限取 10：再小正文就不可读了。 */
private const val FONT_SIZE_MIN_SP = 10
private const val FONT_SIZE_MAX_SP = 24

/**
 * 界面缩放档位。预设值以「标准」为 1.0 对称取值。
 * 倍率见 [com.wy.openx.data.model.Settings.effectiveUiScale]。
 */
private val UI_SCALE_PRESETS = listOf(
    "small" to "小", "standard" to "标准", "large" to "大", "custom" to "自定义"
)

/** 自定义缩放范围。下限 0.7：再小图标细到点不中；上限 1.5：再大布局就装不下了。 */
private const val UI_SCALE_MIN = 0.7f
private const val UI_SCALE_MAX = 1.5f

/** Gradle 堆上限可选范围（MB），与 gradlew 的自动钳制保持一致。 */
private const val HEAP_MIN_MB = 1024
private const val HEAP_MAX_MB = 8192

/** 堆上限滑块的步长（MB）：1024~8192 正好 28 个刻度。 */
private const val HEAP_STEP_MB = 256

/** 聊天文字颜色预设。 */
private val CHAT_TEXT_COLOR_PRESETS = listOf(
    "#FFFFFF" to "白",
    "#333333" to "深灰",
    "#2563EB" to "浅蓝",
    "#1E3A5F" to "藏蓝",
    "#0E7490" to "青绿",
    "#B45309" to "暖黄"
)

/** 解析 `#RRGGBB` / `#AARRGGBB` 为 Compose Color；非法返回 null。 */
private fun parseHexColor(value: String): Color? {
    val s = value.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(0xFF000000L or s.toLong(16))
            8 -> Color(s.toLong(16))
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/** 把本地选中的图片复制进应用私有目录，返回落盘路径（失败返回 null）。 */
private fun copyPickedImageToFiles(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        val dir = java.io.File(context.filesDir, "background").apply { mkdirs() }
        // 固定名 + 覆盖：换图时旧文件自然被替换，不留垃圾
        val target = java.io.File(dir, "bg_image")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}

/**
 * 外观设置子页。
 *
 * `BackgroundSettingPage`（Flutter），按 Compose 重写。保留：
 * 主题模式、语言、字号、背景来源、效果预览、效果调整、自定义色号。
 * 去掉其「宠物」等本项目不适用的部分；「效果预览」的场景由源项目的
 * 「聊天 / 工作区」改为「聊天 / 设置」（本项目无工作区页面，见下）。
 */
@Composable
private fun AppearanceSection(
    state: AppearanceState,
    onChange: (AppearanceState) -> Unit
) {
    val context = LocalContext.current
    var remoteUrlError by remember { mutableStateOf<String?>(null) }
    var hexError by remember { mutableStateOf<String?>(null) }
    var previewKind by remember { mutableStateOf("chat") }
    var pickError by remember { mutableStateOf<String?>(null) }

    // 本地图片选择器。
    //
    // 用 PickVisualMedia（系统**相册**选择器）而不是 OpenDocument（**文件管理器**）：
    // 后者会把用户丢进文件浏览界面去找图，与「选一张背景图」的预期不符（用户反馈）。
    // PickVisualMedia 是 Android 13+ 的照片选择器，低版本由 Activity 库回退到
    // 系统文档选择器并按 image/* 过滤，行为可接受。
    val imagePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = copyPickedImageToFiles(context, uri)
            if (path != null) {
                pickError = null
                onChange(state.copy(bgLocalPath = path, bgSourceType = "local"))
            } else {
                pickError = "图片保存失败，请重试"
            }
        }
    }

    // ===== 主题模式 =====
    SettingsCard {
        SectionLabel("主题模式", "选择浅色、深色或跟随系统")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THEME_MODES.forEach { (v, label) ->
                ChoiceChipLike(label, state.themeMode == v) { onChange(state.copy(themeMode = v)) }
            }
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
        // ===== 主题配色 =====
        SectionLabel("主题", null)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THEME_PALETTES.forEach { t ->
                ChoiceChipLike(t, state.themeName == t) { onChange(state.copy(themeName = t)) }
            }
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
        // ===== 语言 =====
        SectionLabel("语言", "跟随系统 = 与设备语言一致（当前仅提供简体中文）")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LANGUAGES.forEach { lang ->
                ChoiceChipLike(lang, state.language == lang) { onChange(state.copy(language = lang)) }
            }
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
        // ===== 界面大小 =====
        // 与字号是两回事：字号只改文字，这里按比例缩放全部 dp（间距、图标、控件高度），
        // 用来抹平不同分辨率/DPI 设备上的观感差异。
        Text("界面大小", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Text("整体缩放间距、图标与控件（不影响字号档位）", fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            UI_SCALE_PRESETS.forEach { (v, l) ->
                ChoiceChipLike(l, state.uiScalePreset == v, compact = true) { onChange(state.copy(uiScalePreset = v)) }
            }
        }
        if (state.uiScalePreset == "custom") {
            Spacer(Modifier.height(10.dp))
            // 拖动期间只更新本地状态、松手才提交：uiScale 一提交，整个界面的 density 都变，
            // 这根滑杆自身也跟着缩放，thumb 会越拖越偏离手指。
            var draggingScale by remember { mutableStateOf<Float?>(null) }
            val shownScale = draggingScale ?: state.uiScaleCustom
            Text("缩放  ${(shownScale * 100).roundToInt()}%", color = TextSecondary, fontSize = 12.sp)
            Slider(
                value = shownScale,
                onValueChange = {
                    // 取整到 1%：拖拽时不会因浮点噪声频繁重组
                    draggingScale = ((it * 100).roundToInt() / 100f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
                },
                onValueChangeFinished = {
                    draggingScale?.let { onChange(state.copy(uiScaleCustom = it)) }
                    draggingScale = null
                },
                valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                steps = 79,
                colors = SliderDefaults.colors(
                    activeTrackColor = Accent,
                    thumbColor = Accent,
                    inactiveTrackColor = Border
                )
            )
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
        // ===== 字体大小 =====
        // 标题与档位分行：四项（含「自定义」）与标题挤在同一行时，窄屏会把标题压成一列字。
        Text("字体大小", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Text("界面与消息的整体字号档位", fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        // 横向可滚动：档位再多也不会被压缩变形，滑一下就能看到全部
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FONT_SIZE_PRESETS.forEach { (v, l) ->
                ChoiceChipLike(l, state.fontSize == v, compact = true) { onChange(state.copy(fontSize = v)) }
            }
        }
        // 只在选中「自定义」时才展开滑杆，否则白占一块地方
        if (state.fontSize == "custom") {
            Spacer(Modifier.height(10.dp))
            Text("字号  ${state.fontSizeCustomSp} sp", color = TextSecondary, fontSize = 12.sp)
            Slider(
                value = state.fontSizeCustomSp.toFloat(),
                onValueChange = {
                    onChange(
                        state.copy(
                            fontSizeCustomSp = it.roundToInt().coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP)
                        )
                    )
                },
                valueRange = FONT_SIZE_MIN_SP.toFloat()..FONT_SIZE_MAX_SP.toFloat(),
                steps = FONT_SIZE_MAX_SP - FONT_SIZE_MIN_SP - 1,
                colors = SliderDefaults.colors(
                    activeTrackColor = Accent,
                    thumbColor = Accent,
                    inactiveTrackColor = Border
                )
            )
        }
    }

    // ===== 背景来源 =====
    SettingsCard {
        SectionTitle("背景来源", "同时作用于所有页面，更改会自动保存")
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用背景图", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("关闭时下面的设置保留但不渲染", fontSize = 11.sp, color = TextSecondary)
            }
            Switch(
                checked = state.bgEnabled,
                onCheckedChange = { onChange(state.copy(bgEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = Border
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChipLike("图片直链", state.bgSourceType == "remote") { onChange(state.copy(bgSourceType = "remote")) }
            ChoiceChipLike("本地图片", state.bgSourceType == "local") { onChange(state.copy(bgSourceType = "local")) }
        }
        if (state.bgSourceType == "local") {
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.bgLocalPath.isBlank()) "尚未选择本地图片" else state.bgLocalPath,
                fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (pickError != null) {
                Text(pickError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                imagePicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.bgLocalPath.isBlank()) "选择图片" else "重新选择")
            }
        }
        if (state.bgSourceType == "remote") {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.bgRemoteUrl,
                onValueChange = { url ->
                    val trimmed = url.trim()
                    remoteUrlError = if (trimmed.isNotEmpty() && !(trimmed.startsWith("http://") || trimmed.startsWith("https://")))
                        "请输入有效的 http(s) 图片直链" else null
                    onChange(state.copy(bgRemoteUrl = url))
                },
                label = { Text("图片直链") },
                placeholder = { Text("https://example.com/background.jpg") },
                isError = remoteUrlError != null,
                supportingText = remoteUrlError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // ===== 效果预览 =====
    SettingsCard {
        SectionTitle("效果预览", "拖动移动焦点、双指缩放；预览尽量贴近实际效果")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChipLike("聊天", previewKind == "chat") { previewKind = "chat" }
            // 「设置」：本项目无工作区页面，预览设置页更有参考价值（原「工作区」是死项）
            ChoiceChipLike("设置", previewKind == "settings") { previewKind = "settings" }
        }
        Spacer(Modifier.height(12.dp))
        AppearancePreview(
            state = state,
            kind = previewKind,
            onChange = onChange
        )
    }

    // ===== 效果调整 =====
    SettingsCard {
        // 标题右侧「恢复默认」：与标题**同行垂直居中**（用户要求对齐）。
        // 只重置本区可控项（三个滑条 + 聊天文本颜色），不动背景图来源/启用等其它设置 ——
        // 用户想救的是"调糊了的效果参数"，不该顺手把他的背景图也清掉。
        // 默认值取 AppSettings() 而非硬编码字面量：以后改默认值不会漏改这里。
        val appearanceDefaults = remember { AppSettings() }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) { SectionTitle("效果调整", null) }
            Text(
                "恢复默认",
                color = Accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable {
                        hexError = null
                        onChange(
                            state.copy(
                                bgBlurSigma = appearanceDefaults.bgBlurSigma,
                                bgOverlayAlpha = appearanceDefaults.bgOverlayAlpha,
                                bgBrightness = appearanceDefaults.bgBrightness,
                                chatTextColorMode = appearanceDefaults.chatTextColorMode,
                                chatTextHexColor = appearanceDefaults.chatTextHexColor
                            )
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        AppearanceSlider(
            label = "背景柔化", subtitle = "调节图片上方蒙版的柔化程度",
            value = state.bgBlurSigma, min = 0f, max = 24f,
            formatter = { "${it.toInt()}" }
        ) { onChange(state.copy(bgBlurSigma = it)) }
        AppearanceSlider(
            label = "蒙版强度", subtitle = "增强统一蒙版，让页面元素更干净",
            value = state.bgOverlayAlpha, min = 0f, max = 0.55f,
            formatter = { String.format(java.util.Locale.US, "%.2f", it) }
        ) { onChange(state.copy(bgOverlayAlpha = it)) }
        AppearanceSlider(
            label = "蒙版明暗", subtitle = "提亮或压暗蒙版，不会直接修改原图",
            value = state.bgBrightness, min = 0.5f, max = 1.5f,
            formatter = { String.format(java.util.Locale.US, "%.2f", it) }
        ) { onChange(state.copy(bgBrightness = it)) }
        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))

        // ===== 自定义色号 =====
        Text("聊天文本颜色", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Text("默认自动跟随背景明暗，也可以改成固定颜色", fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        // 「自动」+ 6 个色点总宽超出屏幕，必须能横向滑动，否则会被挤成一团。
        // 色点用 size 固定宽，Row 内靠 spacedBy 留空隙，滑动手感与上面的主题/配色选择一致。
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChoiceChipLike("自动", state.chatTextColorMode == "auto") {
                hexError = null
                onChange(state.copy(chatTextColorMode = "auto", chatTextHexColor = ""))
            }
            CHAT_TEXT_COLOR_PRESETS.forEach { (hex, _) ->
                val selected = state.chatTextColorMode == "custom" &&
                    state.chatTextHexColor.equals(hex, ignoreCase = true)
                val color = parseHexColor(hex) ?: Color.Gray
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(3.dp, Accent, androidx.compose.foundation.shape.CircleShape)
                            else Modifier.border(1.5.dp, Border, androidx.compose.foundation.shape.CircleShape)
                        )
                        .clickable {
                            hexError = null
                            onChange(state.copy(chatTextColorMode = "custom", chatTextHexColor = hex))
                        }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.chatTextHexColor,
            onValueChange = { raw ->
                val trimmed = raw.trim()
                hexError = if (trimmed.isNotEmpty() && parseHexColor(trimmed) == null)
                    "请输入 #RRGGBB 或 #AARRGGBB" else null
                onChange(state.copy(chatTextColorMode = "custom", chatTextHexColor = raw))
            },
            label = { Text("自定义色号") },
            placeholder = { Text("#FFFFFF 或 #FF112233") },
            isError = hexError != null,
            supportingText = hexError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(24.dp))
}

/** 子页区块标题（左大标题 + 右灰色说明）。 */
@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

/** 区块内的小标签（无大标题观感，仅一行加粗 + 说明）。 */
@Composable
private fun SectionLabel(title: String, subtitle: String?) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

/** 选中态按钮：与设置页主题选择器同一视觉语言（淡底 + 高对比文字 + 描边）。 */
@Composable
private fun ChoiceChipLike(
    label: String,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
        color = if (selected) Accent.copy(alpha = 0.10f) else PanelAlt,
        border = if (selected) BorderStroke(1.dp, Accent) else null
    ) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (compact) 14.dp else 18.dp,
                vertical = if (compact) 6.dp else 10.dp
            ),
            color = TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

/** 带数值显示的滑条行。 */
@Composable
private fun AppearanceSlider(
    label: String,
    subtitle: String,
    value: Float,
    min: Float,
    max: Float,
    formatter: (Float) -> String,
    onValue: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(formatter(value), fontSize = 12.sp, color = TextSecondary)
        }
        Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        Slider(value = value, onValueChange = onValue, valueRange = min..max)
    }
}

/**
 * 效果预览：一个 0.68 宽高比的小窗，内部真实渲染背景层 + 示意内容，
 * 支持拖动改焦点、双指缩放改倍数。
 */
@Composable
private fun AppearancePreview(
    state: AppearanceState,
    kind: String,
    onChange: (AppearanceState) -> Unit
) {
    // detectTransformGestures 的 lambda 会被 pointerInput 长期持有，直接捕获 state
    // 会读到组合时的旧值（拖一下就跳回原位）。用 rememberUpdatedState 保证每次读最新。
    val latestState by rememberUpdatedState(state)
    val latestOnChange by rememberUpdatedState(onChange)

    val spec = com.wy.openx.ui.theme.BackgroundSpec(
        enabled = state.bgEnabled,
        sourceType = state.bgSourceType,
        localPath = state.bgLocalPath,
        remoteUrl = state.bgRemoteUrl,
        blurSigma = state.bgBlurSigma,
        overlayAlpha = state.bgOverlayAlpha,
        brightness = state.bgBrightness,
        focalX = state.bgFocalX,
        focalY = state.bgFocalY,
        imageScale = state.bgImageScale
    )
    val hasImage = spec.hasSource

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(20.dp))
            .background(BgPrimary)
            .pointerInput(hasImage) {
                if (!hasImage) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = latestState
                    val newScale = (s.bgImageScale * zoom).coerceIn(1f, 3f)
                    // 焦点：把本帧平移量按预览尺寸归一化到 -1..1
                    val dx = (s.bgFocalX + pan.x / size.width.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                    val dy = (s.bgFocalY + pan.y / size.height.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                    latestOnChange(s.copy(bgImageScale = newScale, bgFocalX = dx, bgFocalY = dy))
                }
            }
    ) {
        // 只有启用且选了图源才渲染背景；否则给个提示，避免预览一片空白让人以为坏了
        if (hasImage) {
            com.wy.openx.ui.theme.AppBackgroundLayer(
                spec = spec,
                fallbackColor = BgPrimary,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (!state.bgEnabled) "背景图未启用" else "请先选择图源",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        }
        // 示意内容：聊天气泡 / 设置行，用来看蒙版下的可读性。
        // 「设置」而非「工作区」：本项目**没有工作区页面**
        // 而设置页是背景图的主要落地场景之一
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            if (kind == "chat") {
                PreviewBubble(text = "这是一条用户消息", alignEnd = true, bg = UserBubbleBg, fg = UserBubbleFg)
                Spacer(Modifier.height(8.dp))
                PreviewBubble(text = "AI 的回复会显示在这里，用于查看文字在背景上的可读性。", alignEnd = false, bg = Panel, fg = TextPrimary)
            } else {
                // 模拟设置页的「裸行」样式（图标 + 标题/副标题 + 尾部箭头），
                // 与去卡片底板后的真实设置页一致：行直接铺在背景上，无面板底。
                PreviewSettingRow("主题", "浅米 · 系统默认", Icons.Outlined.Palette)
                Box(Modifier.fillMaxWidth().padding(start = 28.dp).height(0.5.dp).background(Border.copy(alpha = 0.5f)))
                PreviewSettingRow("模型选择模式", "关（默认）：只改当前会话", Icons.Outlined.Tune)
            }
        }
        if (hasImage) {
            Surface(
                color = Color.Black.copy(alpha = 0.54f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            ) {
                Text("拖动与双指缩放图片", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun PreviewBubble(
    text: String,
    alignEnd: Boolean,
    bg: Color,
    fg: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.8f)) {
            Text(text, color = fg, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

/** 预览用的「设置页裸行」：图标 + 标题/副标题 + 尾部箭头，无卡片底（与真实设置页一致）。 */
@Composable
private fun PreviewSettingRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 设置页分区导航栈的纯逻辑。
 *
 * 抽出来是为了能单测。原实现是单个 `openSection: String?`，返回键写死置 null。
 * 「镜像下载」是唯一嵌在「容器」内部的入口（深度 2），`openSection = "imagerepo"`
 * 会直接覆盖销毁 "container"，于是从镜像下载页按返回掉到**设置主页**而不是容器页。
 * 其余 10 个分区深度都是 1，写死的 null 碰巧正确，把这个漏洞掩盖了很久——
 * 同一功能块此前已经出过两次同类导航事故，修的都是表象。
 */
internal object SectionNav {    /** 进入分区：压栈，天然记住上一级。 */
    fun enter(stack: List<String>, id: String): List<String> = stack + id

    /** 退一级。空栈时原样返回——此时应交给外层 BackHandler 退出整个设置页。 */
    fun exit(stack: List<String>): List<String> =
        if (stack.isEmpty()) stack else stack.dropLast(1)

    /** 栈顶即当前分区，空栈 = 设置主页。 */
    fun current(stack: List<String>): String? = stack.lastOrNull()
}

/**
 * 「运行环境」子页（容器分区内）：容器内插件依赖的命令行工具（node/pnpm/opencode…）
 * 的状态检测与一键安装，附 npm 源切换。
 *
 * 独立成函数的原因：原先是 SettingsScreen 主函数里的一段，而该函数已长到 JVM 方法
 * 上限（`Method too large: SettingsScreenKt.SettingsScreen ... >64KB`，编译期直接报
 * internal error）。SettingsScreen 的参数有 70+ 个，继续往里塞任何 UI 都会再次触发，
 * 后续新增子页也应照此拆出。
 */
@Composable
private fun RuntimeEnvSection(
    runtimeTools: List<com.wy.openx.ui.viewmodel.MainViewModel.RuntimeToolState>,
    onDetectRuntimeTools: () -> Unit,
    onInstallRuntimeTool: (String) -> Unit,
    onUninstallRuntimeTool: (String) -> Unit,
    npmRegistryId: String,
    onSetNpmRegistry: (String) -> Unit
) {
    // 首次进入自动检测一次；结果为空（从未检测）才触发，避免每次重组都跑 7 条命令
    LaunchedEffect(Unit) {
        if (runtimeTools.isEmpty()) onDetectRuntimeTools()
    }
    // 卸载是不可撤销的破坏性操作（包卸了要重新下载），必须先弹窗确认，避免误触。
    var pendingUninstall by remember { mutableStateOf<com.wy.openx.ui.viewmodel.MainViewModel.RuntimeToolState?>(null) }
    pendingUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载 ${target.label}", color = TextPrimary) },
            text = {
                Text(
                    "确定要从容器里卸载 ${target.label}${target.version?.let { "（$it）" } ?: ""} 吗？\n\n" +
                        "卸载后依赖它的插件会无法使用，需要重新下载安装。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUninstall = null
                    onUninstallRuntimeTool(target.key)
                }) { Text("卸载", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("运行环境", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("容器内插件依赖的命令行工具，缺失会导致插件安装脚本失败", fontSize = 11.sp, color = TextSecondary)
            }
            Surface(
                onClick = onDetectRuntimeTools,
                shape = RoundedCornerShape(8.dp), color = Accent.copy(alpha = 0.15f)
            ) {
                Text("重新检测", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (runtimeTools.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("尚未检测", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(onClick = onDetectRuntimeTools, shape = RoundedCornerShape(8.dp), color = PanelAlt) {
                    Text("开始检测", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            runtimeTools.forEachIndexed { index, t ->
                if (index > 0) HorizontalDivider(color = Border.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        val status = when {
                            t.installing -> "安装中…"
                            t.uninstalling -> "卸载中…"
                            t.message != null -> t.message
                            t.version != null -> t.version
                            else -> "未安装"
                        }
                        Text(
                            status,
                            fontSize = 11.sp,
                            color = when {
                                t.installing || t.uninstalling -> TextSecondary
                                t.message != null && !t.lastOk -> Color(0xFFFF6B6B)
                                t.version != null -> Success
                                else -> TextSecondary
                            }
                        )
                        // 上游有新版才提示。重装拉的就是 latest，所以点重装即可升上去。
                        t.updateAvailable?.let { upstream ->
                            Text(
                                "可更新 → $upstream（点重装）",
                                fontSize = 11.sp, color = Accent,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (t.installing || t.uninstalling) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextPrimary)
                    } else {
                        // 已安装时并排给「卸载」与「重装」：原先只有重装，想清掉只能重建容器。
                        // node/npm/corepack 是官方二进制手 cp 进 /usr/bin 的，删了会连带弄坏
                        // pnpm/opencode/dsh 的入口，所以它们不显示卸载（uninstallable = false）。
                        if (t.version != null && t.uninstallable) {
                            Surface(
                                onClick = { pendingUninstall = t },
                                shape = RoundedCornerShape(8.dp),
                                color = PanelAlt
                            ) {
                                Text(
                                    "卸载",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Surface(
                            onClick = { onInstallRuntimeTool(t.key) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (t.version == null) Accent.copy(alpha = 0.15f) else PanelAlt
                        ) {
                            Text(
                                if (t.version == null) "安装" else "重装",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (t.version == null) Accent else TextSecondary,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // npm 源切换：pnpm/opencode 安装走这里，与 apk 镜像源无关。
        // 默认 npmmirror：npm 官方源在国内下载只有十几 KB/s，基本不可用。
        Column {
            Text("npm 源", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.wy.openx.container.NpmRegistries.ALL.forEach { r ->
                    val sel = r.id == npmRegistryId
                    Surface(
                        onClick = { onSetNpmRegistry(r.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) Accent.copy(alpha = 0.15f) else PanelAlt,
                        border = if (sel) BorderStroke(1.dp, Accent) else null
                    ) {
                        Text(r.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (sel) Accent else TextSecondary,
                            fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/**
 * 「默认模型」页里的语音区：离线识别模型下载 + 自动朗读开关。
 *
 * 独立成函数而不是内联进 [SettingsScreen]：后者的函数体已逼近 JVM 单方法 64KB 上限，
 * 任何新增都必须挪到外面（见项目记忆「Compose 超长函数体」）。
 * 模型状态与下载协程都收在本函数内，主函数只透传需要落盘的「自动朗读」开关。
 */
@Composable
private fun VoiceSettingsSection(
    voiceAutoSpeak: Boolean,
    onVoiceAutoSpeakChange: (Boolean) -> Unit,
) {
    var modelReady by remember {
        mutableStateOf(
            com.wy.openx.voice.VoiceEngines.modelManager.isReady(com.wy.openx.voice.VoiceModels.ASR_ZH)
        )
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                busy = true
                failed = false
                scope.launch {
                    runCatching {
                        com.wy.openx.voice.VoiceEngines.modelManager.ensureModel(
                            com.wy.openx.voice.VoiceModels.ASR_ZH
                        ) { state ->
                            progress = (state as? com.wy.openx.voice.VoiceModelState.Downloading)?.progress
                        }
                    }.onFailure { failed = true }
                    modelReady = com.wy.openx.voice.VoiceEngines.modelManager
                        .isReady(com.wy.openx.voice.VoiceModels.ASR_ZH)
                    busy = false
                    progress = null
                }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Mic, contentDescription = null,
            tint = TextPrimary, modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("离线语音模型", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                "中文流式识别，约 167MB，不随安装包内置；点此行下载",
                fontSize = 11.sp, color = TextSecondary
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                busy -> progress?.let { "下载中 ${(it * 100).toInt()}%" } ?: "下载中…"
                modelReady -> "已就绪"
                failed -> "下载失败"
                else -> "未下载"
            },
            color = if (modelReady) Accent else TextSecondary,
            fontSize = 12.sp
        )
    }
    SettingsCardDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("自动朗读", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                "AI 每条回复落地后自动念出来；用语音提问的那一轮始终朗读",
                fontSize = 11.sp, color = TextSecondary
            )
        }
        Switch(
            checked = voiceAutoSpeak, onCheckedChange = onVoiceAutoSpeakChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Border
            )
        )
    }
}

/**
 * 隐私区：出站脱敏开关。
 *
 * 独立成函数而不是内联进 [SettingsScreen]：后者函数体已逼近 JVM 单方法 64KB 上限，
 * 任何新增都必须挪到外面（见项目记忆「Compose 超长函数体」）。
 */
@Composable
private fun PrivacySettingsSection(
    maskOutbound: Boolean,
    onMaskOutboundChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("出站脱敏", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                "发给模型前把密钥、手机号、身份证、银行卡换成占位符，回复再还原成原文",
                fontSize = 11.sp, color = TextSecondary
            )
        }
        Switch(
            checked = maskOutbound, onCheckedChange = onMaskOutboundChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Border
            )
        )
    }
}

/**
 * Gradle 堆内存上限。
 *
 * 独立成函数：SettingsScreen 的函数体已逼近 JVM 单方法 64KB 上限（见项目记忆
 * 「Compose 超长函数体」）。
 *
 * 写入容器内用户级 `~/.gradle/gradle.properties`。项目级配置会覆盖用户级，
 * 所以对自带 `gradle.properties` 的工程（包括 openx 自己）不生效——文案里必须说清，
 * 否则用户改了这里却发现某个工程还是 OOM，会当成功能坏了。
 *
 * 默认值与 `gradlew` 的自动算法一致（设备内存 50%，钳制 1024~8192MB），
 * 滑块只在这段范围内移动，避免用户滑到「小到必 OOM」或「大到把系统挤死」的两端。
 */
@Composable
private fun GradleHeapSection(
    configuredMb: Int?,
    onCommit: (Int) -> Unit,
) {
    val context = LocalContext.current
    val deviceMemMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024L * 1024L)).toInt()
    }
    // 推荐值沿用 gradlew 的口径：设备内存 50%，钳制 1024~8192MB
    val recommendedMb = (deviceMemMb / 2).coerceIn(HEAP_MIN_MB, HEAP_MAX_MB)
    // 初始值也要落在滑块范围内：用户手改过配置文件、写进一个超范围的值时，
    // Slider 的 value 超出 valueRange 会显示与数字不一致。
    var valueMb by remember(configuredMb) {
        mutableStateOf((configuredMb ?: recommendedMb).coerceIn(HEAP_MIN_MB, HEAP_MAX_MB))
    }
    // 拖动时只更新本地状态，松手才写盘（每次拖动都写文件会不断触碰容器内文件系统）
    var dragging by remember { mutableStateOf(false) }
    // 默认折叠：这一项对绝大多数用户是「不用管」的——推荐值已自动适配设备，
    // 只想知道当前是多少的人看标题行的数字就够了。展开才露出滑块与说明。
    var expanded by remember { mutableStateOf(false) }

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Gradle 堆内存上限", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text(
                    "设备 ${deviceMemMb / 1024}GB · 推荐 ${recommendedMb}MB" +
                        if (configuredMb == null) "（未配置，用推荐值）" else "",
                    fontSize = 11.sp, color = TextSecondary
                )
            }
            // 折叠时这里就是「当前值」，不必展开也知道是多少
            Text("${valueMb}MB", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▲" else "▼", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
        }
        if (expanded) {
            Slider(
                value = valueMb.toFloat(),
                onValueChange = {
                    dragging = true
                    valueMb = ((it / HEAP_STEP_MB).toInt() * HEAP_STEP_MB).coerceIn(HEAP_MIN_MB, HEAP_MAX_MB)
                },
                onValueChangeFinished = {
                    dragging = false
                    onCommit(valueMb)
                },
                valueRange = HEAP_MIN_MB.toFloat()..HEAP_MAX_MB.toFloat(),
                // 固定 27：range 1024~8192 被均分为 28 段，每段正好 256MB，与下面的 256 吸附对齐。
                // 不能用 (recommendedMb-1024)/256-1 动态算：2GB 设备推荐值钳到 1024 时会得到 -1，
                // 而 Compose 的 Slider 要求 steps >= 0，传负数直接抛异常。
                steps = (HEAP_MAX_MB - HEAP_MIN_MB) / HEAP_STEP_MB - 1,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "写入容器内 ~/.gradle/gradle.properties，下次构建生效（正在运行的 Gradle 守护进程" +
                    "会沿用旧参数，需先 ./gradlew --stop）。只影响没有自带 gradle.properties 的工程。",
                fontSize = 11.sp, color = TextSecondary
            )
            if (dragging) {
                Text("松手后保存", fontSize = 11.sp, color = Accent)
            }
        }
    }
}
