package com.wy.openx.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.KeyRotationStrategy
import com.wy.openx.data.model.ModelMetadata
import com.wy.openx.data.model.ProviderType
import com.wy.openx.data.model.ProxyType
import com.wy.openx.data.model.defaultProviderBaseUrl
import com.wy.openx.data.model.mergeModelMetadata
import com.wy.openx.data.model.sanitized
import com.wy.openx.ui.component.FloatingTabBar
import com.wy.openx.ui.component.FloatingTabItem
import com.wy.openx.ui.component.ModelLogoIcon
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 提供商编辑页：配置 + 模型 两个页签。
 *
 * openx 请求层只实现 OpenAI 兼容，因此：
 * - 类型切换保留全部 19 种（便于用户识别品牌、拉取对应列表），但聊天端点仍走 OpenAI 兼容；
 * - Anthropic/Gemini 的原生协议、余额脚本 AdaptiveCard 渲染未移植（余额脚本字段保留可填，暂不执行）。
 * - 多 Key 轮换、独立代理、useResponseApi、缓存键、UA 全部落地并生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorScreen(
    initialProvider: AIProviderConfig?,
    onNavigateBack: () -> Unit,
    onSave: (AIProviderConfig) -> Unit,
    // 模型拉取/测试/元数据（由 MainViewModel 提供）
    fetchLoading: Boolean = false,
    fetchModels: List<String> = emptyList(),
    fetchError: String? = null,
    modelMetadata: Map<String, ModelMetadata> = emptyMap(),
    testResults: Map<String, com.wy.openx.data.provider.ModelTestResult> = emptyMap(),
    testing: Set<String> = emptySet(),
    onFetchModels: (AIProviderConfig) -> Unit = {},
    onTestModel: (AIProviderConfig, String) -> Unit = { _, _ -> },
    /** 批量测试整张模型列表（串行，见 MainViewModel.testAllProviderModels）。 */
    onTestAllModels: (AIProviderConfig, List<String>) -> Unit = { _, _ -> },
    /** 中断批量测试。 */
    onStopTestAll: () -> Unit = {},
    /** 中断单个模型的测试（模型行上的「停止」）。 */
    onStopTestModel: (String) -> Unit = {},
    /** 批量测试进度；null = 没在跑。 */
    testAllProgress: com.wy.openx.ui.viewmodel.TestAllProgress? = null,
    onResolveMetadata: (String, ProviderType, List<String>) -> Unit = { _, _, _ -> },
    onSaveCustomMetadata: (String, String, ModelMetadata) -> Unit = { _, _, _ -> },
    onRemoveCustomMetadata: (String, String) -> Unit = { _, _ -> },
    /** 进场从磁盘加载已保存的自定义模型元数据（能力/窗口）。 */
    onLoadCustomMetadata: () -> Unit = {},
) {
    var name by remember { mutableStateOf(initialProvider?.name ?: "") }
    var apiKey by remember { mutableStateOf(initialProvider?.apiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var multiKeyEnabled by remember { mutableStateOf(initialProvider?.multiKeyEnabled ?: false) }
    val apiKeys = remember { mutableStateListOf<String>().apply { addAll(initialProvider?.apiKeys ?: emptyList()) } }
    var keyRotationStrategy by remember { mutableStateOf(initialProvider?.keyRotationStrategy ?: KeyRotationStrategy.SEQUENTIAL) }
    var keyFailoverThreshold by remember { mutableStateOf(initialProvider?.keyFailoverThreshold ?: 2) }
    var keyCooldownMinutes by remember { mutableStateOf(initialProvider?.keyCooldownMinutes ?: 5) }
    var baseUrl by remember { mutableStateOf(initialProvider?.baseUrl ?: "") }
    var useFullUrl by remember { mutableStateOf(initialProvider?.useFullUrl ?: false) }
    var useResponseApi by remember { mutableStateOf(initialProvider?.useResponseApi ?: false) }
    var openaiChatCacheKey by remember { mutableStateOf(initialProvider?.openaiChatCacheKey ?: false) }
    var userAgent by remember { mutableStateOf(initialProvider?.userAgent ?: "") }
    val customHeaders = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(initialProvider) {
        customHeaders.clear()
        initialProvider?.userAgent?.takeIf { it.isNotBlank() }?.let { if (customHeaders.none { h -> h.first.equals("User-Agent", true) }) customHeaders.add("User-Agent" to it) }
    }
    var showHeadersSheet by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(initialProvider?.isEnabled ?: true) }
    // 新增时的默认类型：用枚举第一项 OPENAI，使「类型列表首项 == 默认值」，避免
    // 列表第一个是 OpenAI、默认却选中中间的 SiliconFlow 造成困惑。
    var type by remember { mutableStateOf(initialProvider?.type ?: ProviderType.OPENAI) }
    var proxyEnabled by remember { mutableStateOf(initialProvider?.proxyEnabled ?: false) }
    var proxyType by remember { mutableStateOf(initialProvider?.proxyType ?: ProxyType.HTTP) }
    var proxyHost by remember { mutableStateOf(initialProvider?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(initialProvider?.proxyPort ?: 0) }
    var proxyUsername by remember { mutableStateOf(initialProvider?.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(initialProvider?.proxyPassword ?: "") }

    val providerId = remember { initialProvider?.id ?: System.currentTimeMillis().toString() }
    val models = remember { mutableStateListOf<String>().apply { addAll(initialProvider?.models ?: emptyList()) } }
    // 「全部测试」跑完后的汇总弹窗：列出失败项供勾选删除。
    // 以 testResults 为准而非本批的目标列表——中途点「停止」时只有部分模型有结果，
    // 用目标列表会凭空多出几十行「没测过」。
    var showTestAllSummary by remember { mutableStateOf(false) }
    var testAllWasRunning by remember { mutableStateOf(false) }
    /** 弹窗里勾中要删的失败模型。默认不勾：删除不可逆，让用户自己点。 */
    var testAllDeletePicked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 当前使用的模型（编辑页可改）。此前 currentConfig() 写死用 initialProvider.selectedModel，
    // 用户在编辑页改的选中模型会被静默丢弃，保存后聊天页自然不变。
    var selectedModel by remember {
        mutableStateOf(initialProvider?.selectedModel?.takeIf { it.isNotBlank() } ?: initialProvider?.defaultModel.orEmpty())
    }
    // 模型列表左滑删除的组级协调状态（同组互斥）
    val modelSwipeGroup = com.wy.openx.ui.component.rememberSlidableGroupState()
    val modelListScrollState = rememberScrollState()
    var draggingModel by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    val rowTops = remember { mutableMapOf<String, Float>() }
    val rowHeights = remember { mutableMapOf<String, Int>() }
    val currentDragPointerY = rememberUpdatedState(dragPointerY)
    val currentDraggingModel = rememberUpdatedState(draggingModel)
    val density = LocalDensity.current

    // 进场把已保存的自定义模型元数据（能力/窗口）从磁盘读回，并解析一次内置元数据；
    // 否则编辑弹窗与列表行回填不出上次保存的值（「离开再进来就没了」）。
    androidx.compose.runtime.LaunchedEffect(providerId, initialProvider?.models) {
        onLoadCustomMetadata()
        onResolveMetadata(providerId, type, models.toList())
    }

    var showTypeSheet by remember { mutableStateOf(false) }
    var showKeysPage by remember { mutableStateOf(false) }
    var showProxyPage by remember { mutableStateOf(false) }
    var showAddModelSheet by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var showFetchDialog by remember { mutableStateOf(false) }

    fun currentConfig(): AIProviderConfig = AIProviderConfig(
        id = providerId,
        name = name.ifBlank { "新提供商" },
        type = type,
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys.toList(),
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes,
        baseUrl = baseUrl.ifBlank { defaultProviderBaseUrl(type) },
        defaultModel = initialProvider?.defaultModel ?: "",
        models = models.toList(),
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        openaiChatCacheKey = openaiChatCacheKey,
        userAgent = customHeaders.firstOrNull { it.first.equals("User-Agent", true) }?.second ?: userAgent,
        sortOrder = initialProvider?.sortOrder ?: -1,
        proxyEnabled = proxyEnabled,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUsername = proxyUsername,
        proxyPassword = proxyPassword,
        // 必须带上：漏了会退回默认值 user，让插件声明的供应商被当成用户手填，
        // 下次插件刷新时认不出自己建的那条、把它当新条目重建，用户的模型列表就丢了。
        source = initialProvider?.source ?: com.wy.openx.data.model.PROVIDER_SOURCE_USER,
        pluginId = initialProvider?.pluginId.orEmpty()
    ).sanitized()

    fun hasSubstantiveInput(): Boolean =
        initialProvider != null || name.isNotBlank() || apiKey.isNotBlank() ||
            apiKeys.any { it.isNotBlank() } || baseUrl.isNotBlank() || models.isNotEmpty()

    fun saveAndNavigateBack() {
        if (!hasSubstantiveInput()) { onNavigateBack(); return }
        onSave(currentConfig())
        onNavigateBack()
    }

    fun modelIndexForPointerY(y: Float): Int {
        if (models.isEmpty()) return -1
        for (index in models.indices) {
            val model = models[index]
            val top = rowTops[model] ?: continue
            val height = rowHeights[model]?.toFloat() ?: 0f
            if (y < top + height / 2f) return index
        }
        return models.lastIndex
    }

    LaunchedEffect(Unit) {
        val edge = with(density) { 72.dp.toPx() }
        while (true) {
            if (currentDraggingModel.value != null) {
                val y = currentDragPointerY.value
                val delta = when {
                    y > viewportBottom - edge -> 14f
                    y < viewportTop + edge -> -14f
                    else -> 0f
                }
                if (delta != 0f) {
                    modelListScrollState.scrollBy(delta)
                    dragTargetIndex = modelIndexForPointerY(y)
                }
            }
            delay(16)
        }
    }

    if (showKeysPage) {
        BackHandler { showKeysPage = false }
    } else if (showProxyPage) {
        BackHandler { showProxyPage = false }
    } else {
        BackHandler { saveAndNavigateBack() }
    }

    // 内嵌子页：多 Key / 代理
    if (showKeysPage) {
        ProviderKeysPage(
            keys = apiKeys,
            strategy = keyRotationStrategy,
            failoverThreshold = keyFailoverThreshold,
            cooldownMinutes = keyCooldownMinutes,
            onBack = { showKeysPage = false },
            onSetStrategy = { keyRotationStrategy = it },
            onSetFailoverThreshold = { keyFailoverThreshold = it },
            onSetCooldownMinutes = { keyCooldownMinutes = it }
        )
        return
    }
    if (showProxyPage) {
        ProviderProxyPage(
            enabled = proxyEnabled,
            type = proxyType,
            host = proxyHost,
            port = proxyPort,
            username = proxyUsername,
            password = proxyPassword,
            onBack = { showProxyPage = false },
            onSetEnabled = { proxyEnabled = it },
            onSetType = { proxyType = it },
            onSetHost = { proxyHost = it },
            onSetPort = { proxyPort = it },
            onSetUsername = { proxyUsername = it },
            onSetPassword = { proxyPassword = it }
        )
        return
    }

    // 编辑页双 Tab：配置 | 模型。
    var editorTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(com.wy.openx.ui.theme.BgPrimary).statusBarsPadding()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().background(com.wy.openx.ui.theme.BgSecondary).padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { saveAndNavigateBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary)
            }
            Text(
                if (initialProvider == null) "添加提供商" else "编辑提供商",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddModelSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加模型", tint = Accent)
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
        // 批测进度从「非 null」变回 null 即为跑完（正常跑完或中途停止都会走到这）。
    // 用 wasRunning 兜住首次进入时 progress 本来就是 null 的情况，避免一进来就弹空窗。
    LaunchedEffect(testAllProgress) {
        if (testAllProgress != null) {
            testAllWasRunning = true
        } else if (testAllWasRunning) {
            testAllWasRunning = false
            testAllDeletePicked = emptySet()
            showTestAllSummary = true
        }
    }

    if (editorTab == 1) {
            // ── 模型列表子页 ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(modelListScrollState)
                    .onGloballyPositioned { coords ->
                        viewportTop = coords.positionInRoot().y
                        viewportBottom = viewportTop + coords.size.height
                    }
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 标题行：标题 + 「全部测试」+「拉取模型」并排，两个操作都收到段标题右侧。
                // 原先「拉取模型」挂在下面的操作提示行里，跟提示文字挤在一起，
                // 移到标题行后才是一眼看得到的段操作。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsGroupHeader("模型 (${models.size})", modifier = Modifier.weight(1f))
                    val prog = testAllProgress
                    if (prog != null) {
                        // 测试中复用同一个位置变成「停止」，免得再多一个按钮把标题行挤爆
                        TextButton(onClick = onStopTestAll, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("停止 ${prog.done}/${prog.total}", fontSize = 13.sp, color = com.wy.openx.ui.theme.Danger)
                        }
                    } else {
                        TextButton(
                            onClick = { onTestAllModels(currentConfig(), models) },
                            enabled = models.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("全部测试", color = Accent, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { showFetchDialog = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
                        Spacer(Modifier.width(4.dp))
                        Text("拉取模型", color = Accent, fontSize = 13.sp)
                    }
                }
                Text(
                    "点击圆点设为当前 · 长按拖动排序 · 左滑删除 · 点模型名编辑",
                    fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
                SettingsGroup {
                    if (models.isEmpty()) {
                        Text("暂无模型，点右上角 + 手动添加或点「拉取模型」", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        models.forEachIndexed { index, model ->
                            if (index > 0) SettingsDivider()
                            // 自定义元数据在 providerModelMetadata 里以「提供商ID:模型名」为键，
                            // 内置/拉取的元数据才以纯模型名为键。两者都要取，自定义优先。
                            val meta = mergeModelMetadata(model, modelMetadata[model], modelMetadata["$providerId:$model"])
                            val isDragging = draggingModel == model
                            val isTarget = draggingModel != null && dragTargetIndex == index && !isDragging
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        rowHeights[model] = coords.size.height
                                        if (!isDragging) rowTops[model] = coords.positionInRoot().y
                                    }
                                    .pointerInput(model) {
                                        var fromIndex = -1
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                fromIndex = models.indexOf(model)
                                                draggingModel = model
                                                dragTargetIndex = fromIndex
                                                dragOffsetY = 0f
                                                dragPointerY = (rowTops[model] ?: 0f) + offset.y
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                dragPointerY += dragAmount.y
                                                dragTargetIndex = modelIndexForPointerY(dragPointerY)
                                            },
                                            onDragEnd = {
                                                val to = dragTargetIndex
                                                if (fromIndex >= 0 && to in models.indices && fromIndex != to) {
                                                    models.removeAt(fromIndex)
                                                    models.add(to, model)
                                                }
                                                draggingModel = null
                                                dragOffsetY = 0f
                                                dragTargetIndex = -1
                                            },
                                            onDragCancel = {
                                                draggingModel = null
                                                dragOffsetY = 0f
                                                dragTargetIndex = -1
                                            }
                                        )
                                    }
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else 0f
                                        scaleX = if (isDragging) 1.02f else 1f
                                        scaleY = if (isDragging) 1.02f else 1f
                                        shadowElevation = if (isDragging) 12f else 0f
                                    }
                            ) {
                                ProviderModelRow(
                                    model = model,
                                    metadata = meta,
                                    selected = model == selectedModel,
                                    testing = model in testing,
                                    result = testResults[model],
                                    groupState = modelSwipeGroup,
                                    onSelect = { selectedModel = model },
                                    onTest = { onTestModel(currentConfig(), model) },
                                    onStopTest = { onStopTestModel(model) },
                                    onEdit = {
                                        editingModel = model
                                        showAddModelSheet = true
                                    },
                                    onSwipeDelete = {
                                        // 左滑删除：滑出即删，与参考实现一致（参考实现不弹二次确认）。
                                        val wasSelected = model == selectedModel
                                        models.remove(model)
                                        onRemoveCustomMetadata(providerId, model)
                                        if (wasSelected) {
                                            selectedModel = models.firstOrNull().orEmpty()
                                        }
                                    }
                                )
                                if (isTarget) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 全部测试的汇总弹窗 ──
            if (showTestAllSummary) {
                // 只统计当前列表里、且确实有结果的模型（单测中途手点的结果也会一并进来）
                val failedModels = models.filter { testResults[it]?.success == false }
                val okModels = models.filter { testResults[it]?.success == true }
                val untested = models.size - failedModels.size - okModels.size
                AlertDialog(
                    onDismissRequest = { showTestAllSummary = false },
                    title = {
                        Text(
                            if (failedModels.isEmpty()) "全部通过" else "测试完成 · ${failedModels.size} 个失败",
                            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                buildString {
                                    append("${okModels.size} 个可用")
                                    if (untested > 0) append(" · $untested 个未测")
                                    if (failedModels.isNotEmpty()) append(" · ${failedModels.size} 个失败")
                                },
                                color = TextSecondary, fontSize = 12.sp
                            )
                            if (failedModels.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "勾选要删除的（不可撤销）",
                                        color = TextSecondary, fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            testAllDeletePicked =
                                                if (testAllDeletePicked.size == failedModels.size) emptySet()
                                                else failedModels.toSet()
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) {
                                        Text(
                                            if (testAllDeletePicked.size == failedModels.size) "全不选" else "全选",
                                            fontSize = 12.sp, color = Accent
                                        )
                                    }
                                }
                                HorizontalDivider(color = com.wy.openx.ui.theme.Border, thickness = 0.5.dp)
                                failedModels.forEach { m ->
                                    val on = m in testAllDeletePicked
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                testAllDeletePicked = if (on) testAllDeletePicked - m else testAllDeletePicked + m
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = on,
                                            onCheckedChange = { checked ->
                                                testAllDeletePicked = if (checked) testAllDeletePicked + m else testAllDeletePicked - m
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(m, color = TextPrimary, fontSize = 12.sp)
                                            Text(
                                                testResults[m]?.message.orEmpty(),
                                                color = com.wy.openx.ui.theme.Danger, fontSize = 11.sp, lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        if (failedModels.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    val toDelete = testAllDeletePicked.filter { it in failedModels }
                                    // 与左滑删除同一条路径：连带清掉该模型的自定义元数据，
                                    // 否则会留下指向已删模型的孤儿条目。
                                    val hitSelected = selectedModel in toDelete
                                    toDelete.forEach {
                                        models.remove(it)
                                        onRemoveCustomMetadata(providerId, it)
                                    }
                                    if (hitSelected) selectedModel = models.firstOrNull().orEmpty()
                                    testAllDeletePicked = emptySet()
                                    showTestAllSummary = false
                                },
                                enabled = testAllDeletePicked.isNotEmpty()
                            ) {
                                Text(
                                    "删除选中的 ${testAllDeletePicked.size} 个",
                                    color = if (testAllDeletePicked.isNotEmpty()) com.wy.openx.ui.theme.Danger else TextSecondary
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTestAllSummary = false }) {
                            Text(if (failedModels.isEmpty()) "知道了" else "取消", color = TextSecondary)
                        }
                    },
                    containerColor = com.wy.openx.ui.theme.BgSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // ── 基本信息 ──
            SettingsGroupHeader("基本信息")
            SettingsGroup {
                ProviderTextFieldRow("名称", name, { name = it })
                if (!multiKeyEnabled) {
                    ProviderTextFieldRow(
                        "API Key", apiKey, { apiKey = it },
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            Text(
                                if (apiKeyVisible) "隐藏" else "显示",
                                color = Accent, fontSize = 12.sp,
                                modifier = Modifier.clickable { apiKeyVisible = !apiKeyVisible }.padding(horizontal = 6.dp)
                            )
                        }
                    )
                }
                ProviderTextFieldRow("Base URL", baseUrl, { baseUrl = it }, placeholder = "https://api.example.com/v1")
                initialProvider?.takeIf { it.source == com.wy.openx.data.model.PROVIDER_SOURCE_PLUGIN }?.let { p ->
                    Text(
                        "该供应商由插件「${p.pluginId}」在清单里声明，地址与类型会在插件重新加载时按清单覆盖——要改请改插件清单。",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "自定义请求头",
                    onClick = { showHeadersSheet = true },
                    trailing = { Text("›", color = Border, fontSize = 16.sp) }
                )
                SettingsDivider()
                SettingsRow(
                    title = "供应商类型",
                    onClick = { showTypeSheet = true },
                    subtitle = type.name,
                    trailing = { Text("›", color = Border, fontSize = 16.sp) }
                )
            }

            // ── 选项 ──
            SettingsGroupHeader("选项")
            SettingsGroup {
                ProviderSwitchRow("启用", isEnabled, { isEnabled = it })
                SettingsDivider()
                ProviderSwitchRow("使用完整 URL", useFullUrl, { useFullUrl = it }, "直接使用 Base URL 作为端点，不追加 /chat/completions")
                SettingsDivider()
                ProviderSwitchRow("使用 Responses API", useResponseApi, { useResponseApi = it }, "OpenAI 新版端点 v1/responses（模型支持时开启）")
                SettingsDivider()
                ProviderSwitchRow("缓存路由键", openaiChatCacheKey, { openaiChatCacheKey = it }, "Chat Completions 发送 prompt_cache_key（仅兼容服务支持）")
                SettingsDivider()
                ProviderSwitchRow("多 Key 轮换", multiKeyEnabled, { enabled ->
                    multiKeyEnabled = enabled
                    if (enabled && apiKeys.isEmpty() && apiKey.isNotBlank()) apiKeys.add(apiKey)
                }, "开启后按失败次数在多个 Key 间自动切换")
                if (multiKeyEnabled) {
                    SettingsDivider()
                    SettingsRow(
                        title = "管理多个 Key",
                        onClick = { showKeysPage = true },
                        trailing = {
                            Text(
                                "${apiKeys.count { it.isNotBlank() }} 个 · ${if (keyRotationStrategy == KeyRotationStrategy.SEQUENTIAL) "顺序" else "轮询"}",
                                fontSize = 13.sp, color = TextSecondary
                            )
                        }
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "代理",
                    onClick = { showProxyPage = true },
                    trailing = {
                        Text(
                            if (proxyEnabled) "${proxyType.name} ${proxyHost}:${proxyPort}" else "未开启",
                            fontSize = 13.sp, color = TextSecondary
                        )
                    }
                )
            }

            // ── 模型列表已移至「模型」页签 ──
            }
        }
        // 底部悬浮 tab 栏（配置 | 模型，移植 rc7，颜色随系统主题）
        FloatingTabBar(
            selected = editorTab,
            onSelect = { editorTab = it },
            items = listOf(
                FloatingTabItem(icon = Icons.Default.Settings, label = "配置"),
                FloatingTabItem(icon = Icons.Default.Refresh, label = "模型")
            ),
            maskColor = com.wy.openx.ui.theme.BgPrimary,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }

    // 自定义请求头
    if (showHeadersSheet) {
        CustomHeadersSheet(
            customHeaders = customHeaders,
            onDismiss = { showHeadersSheet = false }
        )
    }

    // 类型选择
    if (showTypeSheet) {
        ProviderTypeSelectionSheet(
            selected = type,
            onSelected = { newType ->
                val newDefault = defaultProviderBaseUrl(newType)
                val prevDefault = defaultProviderBaseUrl(type)
                if (baseUrl.isBlank() || baseUrl == prevDefault) baseUrl = newDefault
                type = newType
            },
            onDismiss = { showTypeSheet = false }
        )
    }

    // 添加/编辑模型
    if (showAddModelSheet) {
        val existing = models.toList()
        AddModelSheet(
            existingModels = existing,
            title = if (editingModel != null) "编辑模型" else "添加模型",
            confirmLabel = if (editingModel != null) "保存" else "添加",
            // 回填时同样要带上自定义元数据（复合键），否则编辑框显示的是内置/空值
            initial = editingModel?.let {
                mergeModelMetadata(it, modelMetadata[it], modelMetadata["$providerId:$it"])
            },
            onSave = { model, meta ->
                val editing = editingModel
                if (editing != null && model != editing) {
                    val idx = models.indexOf(editing)
                    if (idx >= 0) models[idx] = model else models.add(model)
                } else if (model !in models) {
                    models.add(model)
                }
                onSaveCustomMetadata(providerId, model, meta)
                onResolveMetadata(providerId, type, models.toList())
                editingModel = null
                showAddModelSheet = false
            },
            onDismiss = { editingModel = null; showAddModelSheet = false }
        )
    }

    // 拉取模型
    if (showFetchDialog) {
        FetchModelsDialog(
            fetchLoading = fetchLoading,
            fetchModels = fetchModels,
            fetchError = fetchError,
            modelMetadata = modelMetadata,
            existingModels = models.toList(),
            testing = testing,
            testResults = testResults,
            onTestModel = { m -> onTestModel(currentConfig(), m) },
            onStopTestModel = onStopTestModel,
            onFetchModels = { onFetchModels(currentConfig()) },
            onAddModel = { m -> if (m !in models) models.add(m) },
            onDismiss = { showFetchDialog = false }
        )
    }
}

sealed interface ProviderEditorFetchState {
    data object Idle : ProviderEditorFetchState
    data object Loading : ProviderEditorFetchState
    data class Success(val models: List<String>, val debugInfo: com.wy.openx.data.provider.ModelTestResult? = null) : ProviderEditorFetchState
    data class Error(val message: String, val debugInfo: com.wy.openx.data.provider.ModelTestResult? = null) : ProviderEditorFetchState
}

@Composable
private fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

@Composable
private fun ProviderTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true,
        label = { Text(label, color = TextSecondary) },
        placeholder = if (placeholder.isNotBlank()) { { Text(placeholder, color = TextSecondary.copy(alpha = 0.4f)) } } else null,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        colors = tfColors(),
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun ProviderSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border)
        )
    }
}

@Composable
private fun ProviderModelRow(
    model: String,
    metadata: ModelMetadata,
    selected: Boolean,
    testing: Boolean,
    result: com.wy.openx.data.provider.ModelTestResult?,
    groupState: com.wy.openx.ui.component.SlidableGroupState,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onStopTest: () -> Unit,
    onEdit: () -> Unit,
    onSwipeDelete: () -> Unit
) {
    // 测试结果可点：点开查看完整请求/响应调试详情。
    var showDetail by remember { mutableStateOf(false) }
    //  风格：左滑露出删除按钮（openx 复用自身 SlidableRow，同组互斥）
    SlidableRow(
        itemId = model,
        groupState = groupState,
        actions = listOf(
            SlideAction(
                icon = Icons.Default.Delete,
                tint = com.wy.openx.ui.theme.Danger,
                onClick = onSwipeDelete
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(Panel)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆点：点按设为「当前使用」模型
                IconButton(onClick = onSelect, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (selected) "当前使用" else "设为当前",
                        tint = if (selected) Accent else TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                ModelLogoIcon(modelName = model, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f).clickable { onEdit() }.padding(end = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (selected) Accent else TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Text("当前", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Medium)
                        }
                    }
                    ModelMetadataTags(metadata)
                }
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                    // 慢供应商可能卡很久（客户端 readTimeout 300s），得能就地停下
                    TextButton(onClick = onStopTest, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("停止", fontSize = 12.sp, color = com.wy.openx.ui.theme.Danger)
                    }
                } else {
                    TextButton(onClick = onTest, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("测试", fontSize = 12.sp, color = Accent)
                    }
                }
            }
            result?.let { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 36.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showDetail = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (r.success) "✓ ${r.message}" else "✗ ${r.message}",
                        fontSize = 11.sp,
                        color = if (r.success) com.wy.openx.ui.theme.Success else com.wy.openx.ui.theme.Danger,
                        // 失败时报错可能较长（含服务端返回的字段级原因），换行展示而不是截断，
                        // 便于用户直接看清 400 的具体字段；点这一行可看完整请求/响应。
                        maxLines = if (r.success) 1 else 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("详情 ›", fontSize = 11.sp, color = Accent)
                }
            }
        }
    }
    if (showDetail) {
        result?.let { r ->
            ModelTestDetailSheet(model = model, result = r, onDismiss = { showDetail = false })
        }
    }
}

/**
 * 模型测试调试详情弹窗：展示完整请求/响应，支持一键复制。
 * openx 的 [com.wy.openx.data.provider.ModelTestResult] ，故信息可逐项还原。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelTestDetailSheet(
    model: String,
    result: com.wy.openx.data.provider.ModelTestResult,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val okColor = com.wy.openx.ui.theme.Success
    val badColor = com.wy.openx.ui.theme.Danger

    val fullLog = remember(model, result) {
        buildString {
            appendLine("=== 模型测试调试信息 ===")
            appendLine("模型: $model")
            appendLine("结果: ${if (result.success) "成功" else "失败"}")
            appendLine("耗时: ${result.latencyMs}ms")
            if (result.responseCode > 0) appendLine("状态码: ${result.responseCode}")
            if (result.message.isNotBlank()) appendLine("消息: ${result.message}")
            appendLine()
            appendLine("--- 请求 ---")
            if (result.requestUrl.isNotBlank()) appendLine("URL: ${result.requestUrl}")
            if (result.requestHeaders.isNotEmpty()) {
                appendLine("Headers:")
                result.requestHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (result.requestBody.isNotBlank()) {
                appendLine("Body:")
                appendLine(result.requestBody)
            }
            appendLine()
            appendLine("--- 响应 ---")
            if (result.responseHeaders.isNotEmpty()) {
                appendLine("Headers:")
                result.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (result.responseBody.isNotBlank()) {
                appendLine("Body:")
                appendLine(result.responseBody)
            }
            if (result.errorDetail.isNotBlank() && result.errorDetail != result.responseBody) {
                appendLine()
                appendLine("--- 错误详情 ---")
                appendLine(result.errorDetail)
            }
        }.trimEnd()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("测试详情", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model, fontSize = 11.sp, color = TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = (if (result.success) okColor else badColor).copy(alpha = 0.15f)) {
                            Text(
                                if (result.success) "成功 · ${result.latencyMs}ms"
                                else "${if (result.responseCode > 0) "HTTP ${result.responseCode}" else "失败"} · ${result.latencyMs}ms",
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = if (result.success) okColor else badColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(fullLog)) }) {
                    Text("复制", fontSize = 12.sp, color = Accent)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (result.message.isNotBlank()) {
                    DebugSection("结果", result.message, if (result.success) null else badColor)
                }
                if (result.requestUrl.isNotBlank()) {
                    DebugSection("请求 URL", result.requestUrl)
                }
                if (result.requestHeaders.isNotEmpty()) {
                    DebugSection("请求头", result.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                }
                if (result.requestBody.isNotBlank()) {
                    DebugSection("请求体", result.requestBody)
                }
                if (result.responseHeaders.isNotEmpty()) {
                    DebugSection("响应头", result.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                }
                if (result.responseBody.isNotBlank()) {
                    DebugSection("响应体", result.responseBody)
                }
                if (result.errorDetail.isNotBlank() && result.errorDetail != result.responseBody) {
                    DebugSection("错误详情", result.errorDetail, badColor)
                }
            }
        }
    }
}

/** 调试详情里的一个分区：标题 + 等宽正文卡片。 */
@Composable
private fun DebugSection(title: String, body: String, titleColor: androidx.compose.ui.graphics.Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = titleColor ?: TextSecondary)
        Surface(shape = RoundedCornerShape(8.dp), color = PanelAlt, modifier = Modifier.fillMaxWidth()) {
            Text(
                body,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun ModelMetadataTags(metadata: ModelMetadata) {
    val tags = mutableListOf<String>()
    if (metadata.supportsVision) tags.add("Image")
    if (metadata.supportsTools) tags.add("Tools")
    val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
    if (input != null) tags.add("↑${formatTokenLimit(input)}")
    metadata.outputTokens?.takeIf { it > 0 }?.let { tags.add("↓${formatTokenLimit(it)}") }
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
        tags.take(4).forEach { t ->
            androidx.compose.material3.Surface(color = PanelAlt.copy(alpha = 0.6f), shape = RoundedCornerShape(50)) {
                Text(t, fontSize = 9.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

private fun formatTokenLimit(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
    else -> tokens.toString()
}

private fun String.trimDecimal(): String = replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")

// ===== 类型选择 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeSelectionSheet(
    selected: ProviderType,
    onSelected: (ProviderType) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxListHeight = (configuration.screenHeightDp * 0.6f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("选择类型", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                items(ProviderType.entries.toList()) { t ->
                    val isSelected = t == selected
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onSelected(t) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.displayName, fontSize = 14.sp, color = if (isSelected) Accent else TextPrimary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomHeadersSheet(
    customHeaders: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxListHeight = (configuration.screenHeightDp * 0.55f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自定义请求头",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { customHeaders.add("" to "") }) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = Accent)
                }
            }
            Text(
                "可添加任意请求头，同名头会覆盖默认值。常用：User-Agent",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                items(customHeaders.size) { index ->
                    val item = customHeaders[index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderTextFieldRow(
                            "Header 名称",
                            item.first,
                            { customHeaders[index] = it to item.second },
                            placeholder = if (index == 0) "User-Agent" else "",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderTextFieldRow(
                            "值",
                            item.second,
                            { customHeaders[index] = item.first to it },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { customHeaders.removeAt(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "删除", tint = TextSecondary)
                        }
                    }
                    if (index < customHeaders.size - 1) {
                        HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ===== 多 Key 管理 =====
@Composable
private fun ProviderKeysPage(
    keys: MutableList<String>,
    strategy: KeyRotationStrategy,
    failoverThreshold: Int,
    cooldownMinutes: Int,
    onBack: () -> Unit,
    onSetStrategy: (KeyRotationStrategy) -> Unit,
    onSetFailoverThreshold: (Int) -> Unit,
    onSetCooldownMinutes: (Int) -> Unit
) {
    var keysVisible by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(com.wy.openx.ui.theme.BgPrimary).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(com.wy.openx.ui.theme.BgSecondary).padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary) }
            Text("管理多个 Key", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { keysVisible = !keysVisible }) { Text(if (keysVisible) "隐藏" else "显示", color = Accent, fontSize = 12.sp) }
            IconButton(onClick = { keys.add("") }) { Icon(Icons.Default.Add, contentDescription = "添加", tint = Accent) }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsGroupHeader("Key 列表")
            SettingsGroup {
                if (keys.isEmpty()) {
                    Text("暂无 Key，点右上角 + 添加", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    keys.forEachIndexed { index, key ->
                        if (index > 0) SettingsDivider()
                        ProviderTextFieldRow(
                            "Key ${index + 1}", key,
                            { keys[index] = it },
                            visualTransformation = if (keysVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = {
                                Icon(
                                    Icons.Default.Close, "删除", tint = TextSecondary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp).clickable { keys.removeAt(index) }
                                )
                            }
                        )
                    }
                }
            }

            SettingsGroupHeader("取用策略")
            SettingsGroup {
                KeyRotationStrategy.entries.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        title = if (item == KeyRotationStrategy.SEQUENTIAL) "顺序" else "轮询",
                        subtitle = if (item == KeyRotationStrategy.SEQUENTIAL) "始终用第一个 Key，连续失败才切换" else "新会话轮流分配起始 Key",
                        onClick = { onSetStrategy(item) },
                        trailing = {
                            if (item == strategy) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }

            SettingsGroupHeader("失败切换")
            SettingsGroup {
                StepperRow("失败阈值", "连续失败多少次后切换", "$failoverThreshold", { onSetFailoverThreshold((failoverThreshold - 1).coerceAtLeast(1)) }, { onSetFailoverThreshold((failoverThreshold + 1).coerceAtMost(10)) })
                SettingsDivider()
                StepperRow("冷却时长", "被切走的 Key 冷却多少分钟", if (cooldownMinutes <= 0) "关闭" else "$cooldownMinutes 分钟", { onSetCooldownMinutes((cooldownMinutes - 1).coerceAtLeast(0)) }, { onSetCooldownMinutes((cooldownMinutes + 1).coerceAtMost(120)) })
            }
        }
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        TextButton(onClick = onDecrease, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("−", fontSize = 18.sp, color = TextSecondary) }
        Text(valueText, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        TextButton(onClick = onIncrease, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("+", fontSize = 18.sp, color = TextSecondary) }
    }
}

// ===== 代理页 =====
@Composable
private fun ProviderProxyPage(
    enabled: Boolean,
    type: ProxyType,
    host: String,
    port: Int,
    username: String,
    password: String,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetType: (ProxyType) -> Unit,
    onSetHost: (String) -> Unit,
    onSetPort: (Int) -> Unit,
    onSetUsername: (String) -> Unit,
    onSetPassword: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(com.wy.openx.ui.theme.BgPrimary).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(com.wy.openx.ui.theme.BgSecondary).padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary) }
            Text("代理", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Spacer(modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsGroup {
                ProviderSwitchRow("启用代理", enabled, { onSetEnabled(it) })
            }
            SettingsGroupHeader("代理类型")
            SettingsGroup {
                ProxyType.entries.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        title = item.name,
                        onClick = { onSetType(item) },
                        trailing = { if (item == type) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
            SettingsGroupHeader("代理服务器")
            SettingsGroup {
                ProviderTextFieldRow("主机", host, { onSetHost(it) }, placeholder = "127.0.0.1")
                ProviderTextFieldRow("端口", if (port == 0) "" else port.toString(), { onSetPort(it.toIntOrNull() ?: 0) }, placeholder = "1080")
                ProviderTextFieldRow("用户名", username, { onSetUsername(it) }, placeholder = "可选")
                ProviderTextFieldRow("密码", password, { onSetPassword(it) }, placeholder = "可选", visualTransformation = PasswordVisualTransformation())
            }
        }
    }
}

// ===== 添加/编辑模型弹窗 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelSheet(
    existingModels: List<String>,
    title: String,
    confirmLabel: String,
    initial: ModelMetadata?,
    onSave: (String, ModelMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    var modelName by remember { mutableStateOf(initial?.id ?: "") }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var supportsImageOutput by remember { mutableStateOf(initial?.supportsImageOutput ?: false) }
    var supportsTools by remember { mutableStateOf(initial?.supportsTools ?: false) }
    var supportsReasoning by remember { mutableStateOf(initial?.supportsReasoning ?: false) }
    var inputTokens by remember { mutableStateOf((initial?.inputTokens ?: initial?.contextTokens?.takeIf { it > 0 })?.toString() ?: "") }
    var outputTokens by remember { mutableStateOf(initial?.outputTokens?.toString() ?: "") }
    var inputPrice by remember { mutableStateOf(initial?.inputCostUsdPerM?.toString() ?: "") }
    var outputPrice by remember { mutableStateOf(initial?.outputCostUsdPerM?.toString() ?: "") }
    var cacheReadPrice by remember { mutableStateOf(initial?.cacheReadCostUsdPerM?.toString() ?: "") }
    var cacheWritePrice by remember { mutableStateOf(initial?.cacheWriteCostUsdPerM?.toString() ?: "") }
    val trimmed = modelName.trim()
    val duplicate = existingModels.any { it == trimmed && it != initial?.id }
    val canSave = trimmed.isNotEmpty() && !duplicate

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.85f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        // 整表可滚动：能力开关 + 保存按钮在窄屏/小屏上会超出弹窗高度，
        // 不可滚动时底部（含保存按钮）被裁掉、点不到，表现为「改了能力/窗口保存不了」。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            ProviderTextFieldRow("模型名", modelName, { modelName = it })
            if (duplicate) {
                Text("该模型已添加", fontSize = 11.sp, color = com.wy.openx.ui.theme.Danger)
            }
            ProviderTextFieldRow("输入 Token 上限", inputTokens, { inputTokens = it })
            ProviderTextFieldRow("输出 Token 上限", outputTokens, { outputTokens = it })

            SettingsGroupHeader("价格（USD / 1M Tokens）")
            SettingsGroup {
                ProviderTextFieldRow("输入价格", inputPrice, { inputPrice = it })
                SettingsDivider()
                ProviderTextFieldRow("输出价格", outputPrice, { outputPrice = it })
                SettingsDivider()
                ProviderTextFieldRow("缓存读取价格", cacheReadPrice, { cacheReadPrice = it })
                SettingsDivider()
                ProviderTextFieldRow("缓存写入价格", cacheWritePrice, { cacheWritePrice = it })
            }

            SettingsGroupHeader("能力")
            SettingsGroup {
                ProviderSwitchRow("支持图片输入", supportsVision, { supportsVision = it })
                SettingsDivider()
                ProviderSwitchRow("支持生图", supportsImageOutput, { supportsImageOutput = it })
                SettingsDivider()
                ProviderSwitchRow("支持工具调用", supportsTools, { supportsTools = it })
                SettingsDivider()
                ProviderSwitchRow("支持推理", supportsReasoning, { supportsReasoning = it })
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val input = inputTokens.trim().toIntOrNull()
                    val output = outputTokens.trim().toIntOrNull()
                    val meta = ModelMetadata(
                        id = trimmed, displayName = trimmed,
                        contextTokens = input ?: 0, inputTokens = input, outputTokens = output,
                        inputCostUsdPerM = inputPrice.trim().toDoubleOrNull(),
                        outputCostUsdPerM = outputPrice.trim().toDoubleOrNull(),
                        cacheReadCostUsdPerM = cacheReadPrice.trim().toDoubleOrNull(),
                        cacheWriteCostUsdPerM = cacheWritePrice.trim().toDoubleOrNull(),
                        supportsVision = supportsVision, supportsImageOutput = supportsImageOutput,
                        supportsTools = supportsTools, supportsReasoning = supportsReasoning
                    )
                    onSave(trimmed, meta)
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = com.wy.openx.ui.theme.TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ===== 拉取模型弹窗 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FetchModelsDialog(
    fetchLoading: Boolean,
    fetchModels: List<String>,
    fetchError: String?,
    modelMetadata: Map<String, ModelMetadata>,
    existingModels: List<String>,
    testing: Set<String>,
    testResults: Map<String, com.wy.openx.data.provider.ModelTestResult>,
    onTestModel: (String) -> Unit,
    onStopTestModel: (String) -> Unit,
    onFetchModels: () -> Unit,
    onAddModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var fired by remember { mutableStateOf(false) }

    if (!fired) {
        fired = true
        onFetchModels()
    }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("拉取模型", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("筛选模型...", fontSize = 13.sp) },
                singleLine = true,
                colors = tfColors(),
                shape = RoundedCornerShape(10.dp)
            )

            when {
                fetchLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
                fetchError != null -> {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(fetchError, fontSize = 13.sp, color = com.wy.openx.ui.theme.Danger)
                    }
                }
                else -> {
                    val newModels = fetchModels.filter { it !in existingModels && it.contains(searchQuery, ignoreCase = true) }
                    if (newModels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Text(if (fetchModels.isEmpty()) "尚未拉取到模型" else "没有可添加的模型", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else {
                        var showAddAll by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("共 ${newModels.size} 个可添加", fontSize = 12.sp, color = TextSecondary)
                            TextButton(onClick = { showAddAll = true }) {
                                Text("全部添加", color = Accent, fontSize = 13.sp)
                            }
                        }
                        if (showAddAll) {
                            AlertDialog(
                                onDismissRequest = { showAddAll = false },
                                title = { Text("添加全部模型", color = TextPrimary) },
                                text = { Text("确定将拉取到的 ${newModels.size} 个模型全部添加至当前提供商？", color = TextSecondary) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showAddAll = false
                                        newModels.forEach { onAddModel(it) }
                                        onDismiss()
                                    }) { Text("确定", color = Accent) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showAddAll = false }) { Text("取消", color = TextSecondary) }
                                },
                                containerColor = Panel
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp, max = 380.dp)
                        ) {
                            items(newModels) { m ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onAddModel(m) }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(m, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        ModelMetadataTags(modelMetadata[m] ?: ModelMetadata(id = m, contextTokens = 0))
                                    }
                                    // 先测通再添加：拉取回来的列表里常有一堆根本调不通的，
                                    // 盲加完还得回列表逐个删。
                                    val r = testResults[m]
                                    when {
                                        m in testing -> {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                                            TextButton(onClick = { onStopTestModel(m) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                                Text("停止", fontSize = 12.sp, color = com.wy.openx.ui.theme.Danger)
                                            }
                                        }
                                        r != null -> TextButton(onClick = { onTestModel(m) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                            Text(
                                                if (r.success) "✓ ${r.latencyMs}ms" else "✗ 失败",
                                                fontSize = 11.sp,
                                                color = if (r.success) com.wy.openx.ui.theme.Success else com.wy.openx.ui.theme.Danger
                                            )
                                        }
                                        else -> TextButton(onClick = { onTestModel(m) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                            Text("测试", fontSize = 12.sp, color = Accent)
                                        }
                                    }
                                    IconButton(onClick = { onAddModel(m) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Add, contentDescription = "添加", tint = Accent, modifier = Modifier.size(18.dp))
                                    }
                                }
                                HorizontalDivider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
