package com.wy.openx.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wy.openx.container.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 麦克风按钮的界面状态。 */
private enum class MicUiState { Idle, Listening, Busy }

/**
 * 语音输入入口：点一下弹出全屏录制面板，面板里看波形说完再收。
 *
 * 自包含——权限申请、模型下载引导、录音与流式识别都在内部完成，外部只需要接住文本：
 * 这是为了不改动调用方（输入区在 9000 行的界面文件里）的函数签名链。
 *
 * [onPartial] 在识别过程中持续回调累计文本（实时填进输入框，用户看得见识别在动），
 * [onResult] 在结束时回调最终文本（已 trim，空则不回调）。
 */
@Composable
internal fun VoiceMicButton(
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    activeTint: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf(MicUiState.Idle) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<VoiceModelState>(VoiceModelState.Idle) }
    var downloading by remember { mutableStateOf(false) }

    // 录制面板：波形采样与实时识别文字都在这里
    var showSheet by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }
    val levels = remember { mutableStateListOf<Float>() }

    // 录音线程与识别协程之间用通道传 PCM：录音回调在录音线程上同步执行，直接 feed 会拖慢采集。
    val pcmChannel = remember { Channel<FloatArray>(Channel.UNLIMITED) }
    var session by remember { mutableStateOf<StreamingAsrEngine.AsrSession?>(null) }

    // 消费 PCM：一段拿去驱动波形，一段喂识别引擎。
    LaunchedEffect(Unit) {
        for (chunk in pcmChannel) {
            val s = session ?: continue
            var peak = 0f
            for (v in chunk) {
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
            }
            val text = withContext(Dispatchers.Default) { s.feed(chunk) }
            levels.add(peak)
            levels.trimWaveSamples()
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                liveText = trimmed
                onPartial(trimmed)
            }
        }
    }

    suspend fun beginListening() {
        val modelDir = VoiceEngines.readyModelDir()
        if (modelDir == null) {
            showSheet = false
            showDownloadDialog = true
            return
        }
        uiState = MicUiState.Busy
        val asr = VoiceEngines.asr
        // 先预热：识别器首次加载要 4 秒多，等录起来再加载会把这期间说的话整段丢掉。
        asr.warmUp(modelDir)
        val newSession = asr.startSession(modelDir)
        if (newSession == null) {
            uiState = MicUiState.Idle
            showSheet = false
            errorText = "识别引擎启动失败"
            return
        }
        session = newSession
        when (VoiceEngines.recorder.start(onChunk = { pcmChannel.trySend(it) })) {
            RecordStartResult.Started -> {
                uiState = MicUiState.Listening
                onPartial("")
            }
            RecordStartResult.NoPermission -> {
                newSession.release()
                session = null
                uiState = MicUiState.Idle
                showSheet = false
                errorText = "没有麦克风权限，无法录音"
            }
            RecordStartResult.Failed -> {
                newSession.release()
                session = null
                uiState = MicUiState.Idle
                showSheet = false
                errorText = "录音启动失败，可能被其他应用占用"
            }
        }
    }

    fun endListening() {
        showSheet = false
        paused = false
        uiState = MicUiState.Busy
        // onChunk 非空时录音器不累积整段，这里只负责停采集，结果以流式引擎为准。
        VoiceEngines.recorder.cancel()
        val current = session
        session = null
        scope.launch {
            val text = withContext(Dispatchers.Default) { current?.finish().orEmpty() }
            current?.release()
            uiState = MicUiState.Idle
            liveText = ""
            levels.clear()
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                // 标记「本轮由语音发起」：回复结束时据此决定是否播报（文字输入不该出声）。
                VoiceEngines.markVoiceTurn()
                onResult(trimmed)
            } else {
                errorText = "没有听清，请再说一次"
            }
        }
    }

    /** 关闭弹窗：放弃本次录音，不产出文本。 */
    fun cancelListening() {
        showSheet = false
        paused = false
        VoiceEngines.recorder.cancel()
        val current = session
        session = null
        current?.release()
        uiState = MicUiState.Idle
        liveText = ""
        levels.clear()
    }

    suspend fun resumeRecording() {
        when (VoiceEngines.recorder.start(onChunk = { pcmChannel.trySend(it) })) {
            RecordStartResult.Started -> paused = false
            RecordStartResult.NoPermission -> errorText = "没有麦克风权限，无法继续录音"
            RecordStartResult.Failed -> errorText = "继续录音失败，可能被其他应用占用"
        }
    }

    /**
     * 暂停/继续收音。
     *
     * 只停采集，不释放识别会话：继续时接着往同一个会话里喂 PCM，暂停前说过的话不会丢。
     */
    fun togglePause() {
        if (paused) {
            scope.launch { resumeRecording() }
        } else {
            VoiceEngines.recorder.cancel()
            paused = true
        }
    }

    /** 开面板：模型没下过就先引导下载，不静默失败。 */
    fun startVoice() {
        if (VoiceEngines.readyModelDir() == null) {
            showDownloadDialog = true
            return
        }
        liveText = ""
        levels.clear()
        paused = false
        showSheet = true
        scope.launch { beginListening() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoice() else errorText = "需要麦克风权限才能语音输入"
    }

    Surface(
        onClick = {
            when (uiState) {
                MicUiState.Idle -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startVoice()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                // 面板已铺满屏幕，正常不会走到这里；留作兜底。
                MicUiState.Listening -> endListening()
                MicUiState.Busy -> Unit
            }
        },
        modifier = modifier,
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = if (uiState == MicUiState.Listening) Icons.Outlined.Stop else Icons.Outlined.Mic,
            contentDescription = when (uiState) {
                MicUiState.Listening -> "结束语音输入"
                MicUiState.Busy -> "正在准备"
                MicUiState.Idle -> "语音输入"
            },
            tint = if (uiState == MicUiState.Listening) activeTint else tint,
            modifier = Modifier
                .size(28.dp)
                .padding(4.dp),
        )
    }

    if (showSheet) {
        VoiceInputSheet(
            levels = levels,
            liveText = liveText,
            paused = paused,
            onTogglePause = { togglePause() },
            onFinish = { endListening() },
            onCancel = { cancelListening() },
        )
    }

    if (showDownloadDialog) {
        VoiceModelDownloadDialog(
            state = downloadState,
            downloading = downloading,
            onDownload = {
                if (downloading) return@VoiceModelDownloadDialog
                downloading = true
                downloadState = VoiceModelState.Downloading(0f)
                scope.launch {
                    runCatching {
                        VoiceEngines.modelManager.ensureModel(VoiceModels.ASR_ZH) { downloadState = it }
                    }.onFailure {
                        FileLogger.e("VoiceMicButton", "语音模型下载失败", it)
                        downloadState = VoiceModelState.Failed(it.message ?: "下载失败")
                    }
                    downloading = false
                }
            },
            onDismiss = { if (!downloading) showDownloadDialog = false },
        )
    }

    errorText?.let { message ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("语音输入") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) { Text("知道了") }
            },
        )
    }
}

/**
 * 语音模型下载对话框。
 *
 * 模型一百多 MB，不在安装包里，首次使用必须先下载——这一步放在这里而不是设置页，
 * 是为了让「点麦克风 → 引导下载 → 下完立刻能用」成为一条闭环，不必让用户自己去找设置项。
 */
@Composable
private fun VoiceModelDownloadDialog(
    state: VoiceModelState,
    downloading: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = VoiceModels.ASR_ZH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要先下载语音模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${spec.displayName}，约 167MB。下载一次后长期可用，之后不再联网。",
                    fontSize = 13.sp,
                )
                when (state) {
                    is VoiceModelState.Downloading -> {
                        val progress = state.progress
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (progress > 0f) "下载中 ${(progress * 100).toInt()}%" else "下载中…",
                            fontSize = 12.sp,
                        )
                    }
                    VoiceModelState.Extracting -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("解压中…", fontSize = 12.sp)
                    }
                    VoiceModelState.Ready -> Text("已就绪，可以开始语音输入了。", fontSize = 12.sp)
                    is VoiceModelState.Failed -> Text(
                        "下载失败：${state.message}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    VoiceModelState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                VoiceModelState.Ready -> TextButton(onClick = onDismiss) { Text("开始使用") }
                is VoiceModelState.Failed -> TextButton(onClick = onDownload) { Text("重试") }
                else -> TextButton(onClick = onDownload, enabled = !downloading) {
                    Text(if (downloading) "下载中…" else "下载")
                }
            }
        },
        dismissButton = {
            if (state != VoiceModelState.Ready) {
                TextButton(onClick = onDismiss, enabled = !downloading) { Text("取消") }
            }
        },
    )
}
