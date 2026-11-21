package com.wy.openx.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "agent_console_settings")

class SettingsStore(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            provider = prefs[Keys.provider] ?: "SiliconFlow",
            model = prefs[Keys.model] ?: "Qwen/Qwen3.5-4B",
            baseUrl = prefs[Keys.baseUrl] ?: "https://api.siliconflow.cn/v1",
            apiKey = prefs[Keys.apiKey] ?: "",
            // 兜底值与 AppSettings.systemPrompt 的默认值保持一致（用户要求默认「通用助手」）
            systemPrompt = prefs[Keys.systemPrompt] ?: "你是一个聪明、有用的AI助手。",
            temperature = prefs[Keys.temperature] ?: 0.7f,
            maxTokens = prefs[Keys.maxTokens] ?: "16384",
            compactionMaxTokens = prefs[Keys.compactionMaxTokens] ?: "4096",
            enableStream = prefs[Keys.enableStream] ?: true,
            autoToolCall = prefs[Keys.autoToolCall] ?: true,
            parallelToolCalls = prefs[Keys.parallelToolCalls] ?: true,
            showLogs = prefs[Keys.showLogs] ?: true,
            compactUseDefault = prefs[Keys.compactUseDefault] ?: true,
            selectedApiPreset = prefs[Keys.selectedApiPreset] ?: "SiliconFlow",
            selectedProviderId = prefs[Keys.selectedProviderId] ?: "",
            customApiName = prefs[Keys.customApiName] ?: "我的自定义接口",
            customApiUrl = prefs[Keys.customApiUrl] ?: "",
            customApiModel = prefs[Keys.customApiModel] ?: "",
            language = prefs[Keys.language] ?: "跟随系统",
            // 历史兼容：已删除 / 已改名的主题项归一化为「系统默认」，否则设置页主题列表
            // 会因为匹配不到任何一项而显示「无选中」。
            //   - 「浅米」：其配色已并入「系统默认」，重复项；
            //   - 「亮色」「极简暗」：历次精简主题时删除（与系统默认/科技蓝重叠）；
            //   - 「极简亮」：同上；
            //   - 「跟随系统」：2.5.1 起「主题配色」这一档改名「系统默认」——
            //     与同页「主题模式」的 system 档（仍叫「跟随系统」，管明暗）容易混，
            //     故配色档换个说法。注意 themeName 存的就是显示名，改名必须在此迁移。
            //     resolveTheme 里「系统默认」与「跟随系统」都落到 else → claude 浅米，
            //     但那是巧合；这里仍显式迁移，保证设置页能选中。
            themeName = (prefs[Keys.themeName] ?: "系统默认").let {
                if (it in setOf("浅米", "亮色", "极简亮", "极简暗", "跟随系统")) "系统默认" else it
            },
            themeMode = prefs[Keys.themeMode] ?: "system",
            rules = prefs[Keys.rules] ?: "",
            fontSize = prefs[Keys.fontSize] ?: "medium",
            fontSizeCustomSp = prefs[Keys.fontSizeCustomSp] ?: 15,
            uiScalePreset = prefs[Keys.uiScalePreset] ?: "standard",
            uiScaleCustom = prefs[Keys.uiScaleCustom] ?: 1.0f,
            chatTextColorMode = prefs[Keys.chatTextColorMode] ?: "auto",
            chatTextHexColor = prefs[Keys.chatTextHexColor] ?: "",
            bgEnabled = prefs[Keys.bgEnabled] ?: false,
            bgSourceType = prefs[Keys.bgSourceType] ?: "none",
            bgLocalPath = prefs[Keys.bgLocalPath] ?: "",
            bgRemoteUrl = prefs[Keys.bgRemoteUrl] ?: "",
            bgBlurSigma = (prefs[Keys.bgBlurSigma] ?: 8f).coerceIn(0f, 24f),
            bgOverlayAlpha = (prefs[Keys.bgOverlayAlpha] ?: 0.18f).coerceIn(0f, 0.55f),
            bgBrightness = (prefs[Keys.bgBrightness] ?: 1f).coerceIn(0.5f, 1.5f),
            bgFocalX = (prefs[Keys.bgFocalX] ?: 0f).coerceIn(-1f, 1f),
            bgFocalY = (prefs[Keys.bgFocalY] ?: 0f).coerceIn(-1f, 1f),
            bgImageScale = (prefs[Keys.bgImageScale] ?: 1f).coerceIn(1f, 3f),
            showTimestamp = prefs[Keys.showTimestamp] ?: false,
            hapticFeedback = prefs[Keys.hapticFeedback] ?: true,
            notifyOnComplete = prefs[Keys.notifyOnComplete] ?: false,
            vibrateOnComplete = prefs[Keys.vibrateOnComplete] ?: false,
            notificationSound = prefs[Keys.notificationSound] ?: "default",
            contextWindow = prefs[Keys.contextWindow] ?: "",
            autoScroll = prefs[Keys.autoScroll] ?: true,
            pluginQuickBarExpanded = prefs[Keys.pluginQuickBarExpanded] ?: false,
            contextRatio = prefs[Keys.contextRatio] ?: 0.8f,
            thinkingAutoFoldSeconds = prefs[Keys.thinkingAutoFoldSeconds] ?: 0,
            thinkingAlwaysExpanded = prefs[Keys.thinkingAlwaysExpanded] ?: false,
            currentProject = prefs[Keys.currentProject] ?: "",
            externalWorkspaces = Keys.parseExternal(prefs[Keys.externalWorkspaces]),
            toolAllowList = Keys.parseAllowList(prefs[Keys.toolAllowList]),
            toolPermissionMode = prefs[Keys.toolPermissionMode] ?: "allow",
            reasoningEffort = prefs[Keys.reasoningEffort] ?: "default",
            toolPermissionGlobalMode = prefs[Keys.toolPermissionGlobalMode] ?: false,
            reasoningEffortGlobalMode = prefs[Keys.reasoningEffortGlobalMode] ?: false,
            // 语义翻转迁移：本字段原为 model_session_mode（默认 false=全局）。
            // 新键没有时读旧键并**取反**，这样所有老用户的实际行为都不变：
            //   旧 true（会话级）→ 新 false（会话级）；旧 false（全局）→ 新 true（全局）。
            // 两者都没有（全新安装）→ 默认 true（全局，与旧默认行为一致）。
            modelGlobalMode = prefs[Keys.modelGlobalMode]
                ?: prefs[Keys.modelSessionMode]?.let { !it }
                ?: true,
            autoArchiveDays = prefs[Keys.autoArchiveDays] ?: 0,
            restoreLastConversation = prefs[Keys.restoreLastConversation] ?: true,
            separateSendButton = prefs[Keys.separateSendButton] ?: false,
            keepScreenOnWhileRunning = prefs[Keys.keepScreenOnWhileRunning] ?: false,
            backgroundKeepAlive = prefs[Keys.backgroundKeepAlive] ?: false,
            floatingOverlay = prefs[Keys.floatingOverlay] ?: false,
            voiceAutoSpeak = prefs[Keys.voiceAutoSpeak] ?: false,
            maskOutbound = prefs[Keys.maskOutbound] ?: false,
            visionProviderId = prefs[Keys.visionProviderId] ?: "",
            visionModel = prefs[Keys.visionModel] ?: "",
            compactionProviderId = prefs[Keys.compactionProviderId] ?: "",
            compactionModel = prefs[Keys.compactionModel] ?: "",
            titleProviderId = prefs[Keys.titleProviderId] ?: "",
            titleModel = prefs[Keys.titleModel] ?: "",
            knowledgeExtractEnabled = prefs[Keys.knowledgeExtractEnabled] ?: false,
            knowledgeExtractEveryN = (prefs[Keys.knowledgeExtractEveryN] ?: 3).coerceIn(1, 50),
            knowledgeProviderId = prefs[Keys.knowledgeProviderId] ?: "",
            knowledgeModel = prefs[Keys.knowledgeModel] ?: "",
            subagentProviderId = prefs[Keys.subagentProviderId] ?: "",
            subagentModel = prefs[Keys.subagentModel] ?: "",
            subagentConcurrency = (prefs[Keys.subagentConcurrency] ?: 3).coerceIn(1, 5),
            subagentTimeoutSec = (prefs[Keys.subagentTimeoutSec] ?: 1800).coerceIn(30, 7200),
            subagentMaxRounds = (prefs[Keys.subagentMaxRounds] ?: 50).coerceIn(1, 200),
            subagentReadOnly = prefs[Keys.subagentReadOnly] ?: false,
            imageGenProviderId = prefs[Keys.imageGenProviderId] ?: "",
            imageGenModel = prefs[Keys.imageGenModel] ?: "",
            // 内置提示词（设置 → 提示词）：总开关默认开；单条停用集 JSON 数组
            agentRulesEnabled = prefs[Keys.agentRulesEnabled] ?: true,
            agentRuleDisabled = Keys.parseKeySet(prefs[Keys.agentRuleDisabled]),
            backupSectionIds = com.wy.openx.data.backup.resolveBackupSectionSelection(
                rawSaved = prefs[Keys.backupSectionIds],
                rawAck = prefs[Keys.backupSectionsAck],
                known = com.wy.openx.data.backup.BackupSectionCatalog.entries.map { it.id }.toSet(),
                defaults = com.wy.openx.data.backup.BackupSectionCatalog.defaultSelection
            )
        )
    }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.provider] = settings.provider
            prefs[Keys.model] = settings.model
            prefs[Keys.baseUrl] = settings.baseUrl
            prefs[Keys.apiKey] = settings.apiKey
            prefs[Keys.systemPrompt] = settings.systemPrompt
            prefs[Keys.temperature] = settings.temperature
            prefs[Keys.maxTokens] = settings.maxTokens
            prefs[Keys.compactionMaxTokens] = settings.compactionMaxTokens
            prefs[Keys.enableStream] = settings.enableStream
            prefs[Keys.autoToolCall] = settings.autoToolCall
            prefs[Keys.parallelToolCalls] = settings.parallelToolCalls
            prefs[Keys.showLogs] = settings.showLogs
            prefs[Keys.compactUseDefault] = settings.compactUseDefault
            prefs[Keys.selectedApiPreset] = settings.selectedApiPreset
            prefs[Keys.selectedProviderId] = settings.selectedProviderId
            prefs[Keys.customApiName] = settings.customApiName
            prefs[Keys.customApiUrl] = settings.customApiUrl
            prefs[Keys.customApiModel] = settings.customApiModel
            prefs[Keys.language] = settings.language
            prefs[Keys.themeName] = settings.themeName
            prefs[Keys.themeMode] = settings.themeMode
            prefs[Keys.rules] = settings.rules
            prefs[Keys.fontSize] = settings.fontSize
            prefs[Keys.fontSizeCustomSp] = settings.fontSizeCustomSp
            prefs[Keys.uiScalePreset] = settings.uiScalePreset
            prefs[Keys.uiScaleCustom] = settings.uiScaleCustom
            prefs[Keys.chatTextColorMode] = settings.chatTextColorMode
            prefs[Keys.chatTextHexColor] = settings.chatTextHexColor
            prefs[Keys.bgEnabled] = settings.bgEnabled
            prefs[Keys.bgSourceType] = settings.bgSourceType
            prefs[Keys.bgLocalPath] = settings.bgLocalPath
            prefs[Keys.bgRemoteUrl] = settings.bgRemoteUrl
            prefs[Keys.bgBlurSigma] = settings.bgBlurSigma.coerceIn(0f, 24f)
            prefs[Keys.bgOverlayAlpha] = settings.bgOverlayAlpha.coerceIn(0f, 0.55f)
            prefs[Keys.bgBrightness] = settings.bgBrightness.coerceIn(0.5f, 1.5f)
            prefs[Keys.bgFocalX] = settings.bgFocalX.coerceIn(-1f, 1f)
            prefs[Keys.bgFocalY] = settings.bgFocalY.coerceIn(-1f, 1f)
            prefs[Keys.bgImageScale] = settings.bgImageScale.coerceIn(1f, 3f)
            prefs[Keys.showTimestamp] = settings.showTimestamp
            prefs[Keys.hapticFeedback] = settings.hapticFeedback
            prefs[Keys.notifyOnComplete] = settings.notifyOnComplete
            prefs[Keys.vibrateOnComplete] = settings.vibrateOnComplete
            prefs[Keys.notificationSound] = settings.notificationSound
            prefs[Keys.contextWindow] = settings.contextWindow
            prefs[Keys.autoScroll] = settings.autoScroll
            prefs[Keys.pluginQuickBarExpanded] = settings.pluginQuickBarExpanded
            prefs[Keys.contextRatio] = settings.contextRatio
            prefs[Keys.thinkingAutoFoldSeconds] = settings.thinkingAutoFoldSeconds
            prefs[Keys.thinkingAlwaysExpanded] = settings.thinkingAlwaysExpanded
            prefs[Keys.currentProject] = settings.currentProject
            prefs[Keys.externalWorkspaces] = Gson().toJson(settings.externalWorkspaces)
            prefs[Keys.toolAllowList] = Gson().toJson(settings.toolAllowList)
            prefs[Keys.toolPermissionMode] = settings.toolPermissionMode
            prefs[Keys.reasoningEffort] = settings.reasoningEffort
            prefs[Keys.toolPermissionGlobalMode] = settings.toolPermissionGlobalMode
            prefs[Keys.reasoningEffortGlobalMode] = settings.reasoningEffortGlobalMode
            prefs[Keys.modelGlobalMode] = settings.modelGlobalMode
            prefs[Keys.autoArchiveDays] = settings.autoArchiveDays
            prefs[Keys.restoreLastConversation] = settings.restoreLastConversation
            prefs[Keys.separateSendButton] = settings.separateSendButton
            prefs[Keys.keepScreenOnWhileRunning] = settings.keepScreenOnWhileRunning
            prefs[Keys.backgroundKeepAlive] = settings.backgroundKeepAlive
            prefs[Keys.floatingOverlay] = settings.floatingOverlay
            prefs[Keys.voiceAutoSpeak] = settings.voiceAutoSpeak
            prefs[Keys.maskOutbound] = settings.maskOutbound
            prefs[Keys.visionProviderId] = settings.visionProviderId
            prefs[Keys.visionModel] = settings.visionModel
            prefs[Keys.compactionProviderId] = settings.compactionProviderId
            prefs[Keys.compactionModel] = settings.compactionModel
            prefs[Keys.titleProviderId] = settings.titleProviderId
            prefs[Keys.titleModel] = settings.titleModel
            prefs[Keys.knowledgeExtractEnabled] = settings.knowledgeExtractEnabled
            prefs[Keys.knowledgeExtractEveryN] = settings.knowledgeExtractEveryN.coerceIn(1, 50)
            prefs[Keys.knowledgeProviderId] = settings.knowledgeProviderId
            prefs[Keys.knowledgeModel] = settings.knowledgeModel
            prefs[Keys.subagentProviderId] = settings.subagentProviderId
            prefs[Keys.subagentModel] = settings.subagentModel
            prefs[Keys.subagentConcurrency] = settings.subagentConcurrency.coerceIn(1, 5)
            prefs[Keys.subagentTimeoutSec] = settings.subagentTimeoutSec.coerceIn(30, 7200)
            prefs[Keys.subagentMaxRounds] = settings.subagentMaxRounds.coerceIn(1, 200)
            prefs[Keys.subagentReadOnly] = settings.subagentReadOnly
            prefs[Keys.imageGenProviderId] = settings.imageGenProviderId
            prefs[Keys.imageGenModel] = settings.imageGenModel
            prefs[Keys.agentRulesEnabled] = settings.agentRulesEnabled
            prefs[Keys.agentRuleDisabled] = Gson().toJson(settings.agentRuleDisabled.toList())
            // 存成 JSON 数组，顺序无关；读取侧会补齐默认段、剔除已不存在的段
            prefs[Keys.backupSectionIds] = Gson().toJson(settings.backupSectionIds.toList())
            // 记下「这些段用户已经见过」。读取时升级新增的段会按默认值并入一次，
            // 而这次并入的结果会跟着上面这行一起落盘 —— 于是 ack 在同一份保存里补齐，
            // 自动并入只发生一次，之后用户手动取消勾选就能一直保持取消。
            val ackType = object : TypeToken<List<String>>() {}.type
            val ack = runCatching { Gson().fromJson<List<String>>(prefs[Keys.backupSectionsAck], ackType) }
                .getOrNull() ?: emptyList()
            val known = com.wy.openx.data.backup.BackupSectionCatalog.entries.map { it.id }
            prefs[Keys.backupSectionsAck] = Gson().toJson((ack + known).distinct())

        }
    }

    val apiPresetsFlow: Flow<List<ApiPreset>> = context.settingsDataStore.data.map { prefs ->
        val json = prefs[Keys.apiPresets] ?: "[]"
        val type = object : TypeToken<List<ApiPreset>>() {}.type
        Gson().fromJson(json, type) ?: emptyList()
    }

    suspend fun saveApiPresets(presets: List<ApiPreset>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.apiPresets] = Gson().toJson(presets)
        }
    }

    private object Keys {
        val apiPresets = stringPreferencesKey("api_presets")
        val provider = stringPreferencesKey("provider")
        val model = stringPreferencesKey("model")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val temperature = floatPreferencesKey("temperature")
        val maxTokens = stringPreferencesKey("max_tokens")
        val compactionMaxTokens = stringPreferencesKey("compaction_max_tokens")
        val enableStream = booleanPreferencesKey("enable_stream")
        val autoToolCall = booleanPreferencesKey("auto_tool_call")
        val parallelToolCalls = booleanPreferencesKey("parallel_tool_calls")
        val showLogs = booleanPreferencesKey("show_logs")
        val compactUseDefault = booleanPreferencesKey("compact_use_default")
        val selectedApiPreset = stringPreferencesKey("selected_api_preset")
        val selectedProviderId = stringPreferencesKey("selected_provider_id")
        val customApiName = stringPreferencesKey("custom_api_name")
        val customApiUrl = stringPreferencesKey("custom_api_url")
        val customApiModel = stringPreferencesKey("custom_api_model")
        val language = stringPreferencesKey("language")
        val themeName = stringPreferencesKey("theme_name")
        val themeMode = stringPreferencesKey("theme_mode")
        val rules = stringPreferencesKey("rules")
        val fontSize = stringPreferencesKey("font_size")
        val fontSizeCustomSp = intPreferencesKey("font_size_custom_sp")
        val uiScalePreset = stringPreferencesKey("ui_scale_preset")
        val uiScaleCustom = floatPreferencesKey("ui_scale_custom")
        val chatTextColorMode = stringPreferencesKey("chat_text_color_mode")
        val chatTextHexColor = stringPreferencesKey("chat_text_hex_color")
        val bgEnabled = booleanPreferencesKey("bg_enabled")
        val bgSourceType = stringPreferencesKey("bg_source_type")
        val bgLocalPath = stringPreferencesKey("bg_local_path")
        val bgRemoteUrl = stringPreferencesKey("bg_remote_url")
        val bgBlurSigma = floatPreferencesKey("bg_blur_sigma")
        val bgOverlayAlpha = floatPreferencesKey("bg_overlay_alpha")
        val bgBrightness = floatPreferencesKey("bg_brightness")
        val bgFocalX = floatPreferencesKey("bg_focal_x")
        val bgFocalY = floatPreferencesKey("bg_focal_y")
        val bgImageScale = floatPreferencesKey("bg_image_scale")
        val showTimestamp = booleanPreferencesKey("show_timestamp")
        val hapticFeedback = booleanPreferencesKey("haptic_feedback")
        val notifyOnComplete = booleanPreferencesKey("notify_on_complete")
        val vibrateOnComplete = booleanPreferencesKey("vibrate_on_complete")
        val notificationSound = stringPreferencesKey("notification_sound")
        val contextWindow = stringPreferencesKey("context_window")
        val autoScroll = booleanPreferencesKey("auto_scroll")
        val pluginQuickBarExpanded = booleanPreferencesKey("plugin_quick_bar_expanded")
        val contextRatio = floatPreferencesKey("context_ratio")
        val thinkingAutoFoldSeconds = intPreferencesKey("thinking_auto_fold_seconds")
        val thinkingAlwaysExpanded = booleanPreferencesKey("thinking_always_expanded")
        val currentProject = stringPreferencesKey("current_project")
        val externalWorkspaces = stringPreferencesKey("external_workspaces")
        val toolAllowList = stringPreferencesKey("tool_allow_list")
        val toolPermissionMode = stringPreferencesKey("tool_permission_mode")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        // 档位「全局设置模式」开关：决定聊天页那个下拉是改全局还是只改当前会话
        val toolPermissionGlobalMode = booleanPreferencesKey("tool_permission_global_mode")
        val reasoningEffortGlobalMode = booleanPreferencesKey("reasoning_effort_global_mode")
        // 模型选择的「全局模式」开关（默认开=全局）。旧键 model_session_mode 仅用于读取迁移，不再写入。
        val modelGlobalMode = booleanPreferencesKey("model_global_mode")
        val modelSessionMode = booleanPreferencesKey("model_session_mode")
        val autoArchiveDays = intPreferencesKey("auto_archive_days")
        val restoreLastConversation = booleanPreferencesKey("restore_last_conversation")
        val separateSendButton = booleanPreferencesKey("separate_send_button")
        val keepScreenOnWhileRunning = booleanPreferencesKey("keep_screen_on_while_running")
        val backgroundKeepAlive = booleanPreferencesKey("background_keep_alive")
        val floatingOverlay = booleanPreferencesKey("floating_overlay")
        val voiceAutoSpeak = booleanPreferencesKey("voice_auto_speak")

        /** 出站脱敏；缺省 true（老用户升级后默认开启）。 */
        val maskOutbound = booleanPreferencesKey("mask_outbound")
        val visionProviderId = stringPreferencesKey("vision_provider_id")
        val visionModel = stringPreferencesKey("vision_model")
        val compactionProviderId = stringPreferencesKey("compaction_provider_id")
        val compactionModel = stringPreferencesKey("compaction_model")
        val titleProviderId = stringPreferencesKey("title_provider_id")
        val titleModel = stringPreferencesKey("title_model")
        val knowledgeExtractEnabled = booleanPreferencesKey("knowledge_extract_enabled")
        val knowledgeExtractEveryN = intPreferencesKey("knowledge_extract_every_n")
        val knowledgeProviderId = stringPreferencesKey("knowledge_provider_id")
        val knowledgeModel = stringPreferencesKey("knowledge_model")
        val subagentProviderId = stringPreferencesKey("subagent_provider_id")
        val subagentModel = stringPreferencesKey("subagent_model")
        val subagentConcurrency = intPreferencesKey("subagent_concurrency")
        val subagentTimeoutSec = intPreferencesKey("subagent_timeout_sec")
        val subagentMaxRounds = intPreferencesKey("subagent_max_rounds")
        val subagentReadOnly = booleanPreferencesKey("subagent_read_only")
        val imageGenProviderId = stringPreferencesKey("image_gen_provider_id")
        val imageGenModel = stringPreferencesKey("image_gen_model")
        /** 设置 → 提示词：内置 AI 行为规则总开关（默认开）。 */
        val agentRulesEnabled = booleanPreferencesKey("agent_rules_enabled")
        /** 设置 → 提示词：单独停用的规则 key 集合（JSON 数组）。 */
        val agentRuleDisabled = stringPreferencesKey("agent_rule_disabled")
        val backupSectionIds = stringPreferencesKey("backup_section_ids")
        /** 已经展示给用户、并有机会手动改过的段 id。用于把「升级新增的段按默认并入一次」
         *  与「用户主动取消过」区分开，详见 resolveBackupSectionSelection。 */
        val backupSectionsAck = stringPreferencesKey("backup_sections_ack")
        val fileTreeExpanded = stringPreferencesKey("file_tree_expanded")
        val fileTreeScrollIndex = intPreferencesKey("file_tree_scroll_index")
        val fileTreeScrollOffset = intPreferencesKey("file_tree_scroll_offset")

        fun parseExternal(json: String?): List<com.wy.openx.data.model.ExternalWorkspace> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<com.wy.openx.data.model.ExternalWorkspace>>() {}.type
                Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseAllowList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** 内置提示词「单条停用」集合：坏数据一律回退为空集（= 全部启用），不让设置页崩。 */
        fun parseKeySet(json: String?): Set<String> {
            if (json.isNullOrBlank()) return emptySet()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                (Gson().fromJson<List<String>>(json, type) ?: emptyList()).toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    /** 侧边栏文件树界面状态（展开目录 + 滚动位置），磁盘持久化以跨 Tab/抽屉开关恢复。 */
    suspend fun loadFileTreeState(): FileTreeUiState {
        val prefs = context.settingsDataStore.data.first()
        val json = prefs[Keys.fileTreeExpanded]
        val expanded = if (json.isNullOrBlank()) {
            emptySet()
        } else {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                (Gson().fromJson<List<String>>(json, type) ?: emptyList()).toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
        return FileTreeUiState(
            expandedPaths = expanded,
            firstVisibleItemIndex = prefs[Keys.fileTreeScrollIndex] ?: 0,
            firstVisibleItemScrollOffset = prefs[Keys.fileTreeScrollOffset] ?: 0
        )
    }

    suspend fun saveFileTreeState(state: FileTreeUiState) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.fileTreeExpanded] = Gson().toJson(state.expandedPaths.toList())
            prefs[Keys.fileTreeScrollIndex] = state.firstVisibleItemIndex
            prefs[Keys.fileTreeScrollOffset] = state.firstVisibleItemScrollOffset
        }
    }
}

data class FileTreeUiState(
    val expandedPaths: Set<String> = emptySet(),
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)
