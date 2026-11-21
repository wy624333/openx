package com.wy.openx.voice

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音引擎的进程级持有者。
 *
 * 本模块不接依赖注入框架，各引擎都持有 `applicationContext`，统一在这里 `by lazy` 建一次：
 * 识别器与模型管理器都带较重状态（native recognizer 一次加载 160MB），多处各建一份
 * 会把内存和设备资源吃穿。
 *
 * 必须在 `Application.onCreate` 里先 [init]，否则取用会立刻抛错——早于 Application 的调用
 * 本身就是 bug，静默兜底只会把问题推后到更难查的地方。
 */
internal object VoiceEngines {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val modelManager: VoiceModelManager by lazy { VoiceModelManager(requireContext()) }
    val recorder: VoiceRecorder by lazy { VoiceRecorder(requireContext()) }
    val audioSession: VoiceAudioSession by lazy { VoiceAudioSession(requireContext()) }
    val tts: SystemTtsPlayer by lazy { SystemTtsPlayer(requireContext()) }

    /** 流式识别引擎（自身即单例，无需在此持有）。 */
    val asr: StreamingAsrEngine get() = StreamingAsrEngine.get()

    /** 模型已就绪时返回其目录，否则 null。 */
    fun readyModelDir(): File? {
        val spec = VoiceModels.ASR_ZH
        val dir = modelManager.modelDir(spec)
        return dir.takeIf { modelManager.isReady(spec) }
    }

    /** 识别器是否已预热（避免重复加载）。 */
    fun isAsrWarm(): Boolean = readyModelDir()?.let { asr.isLoaded(it) } ?: false

    /**
     * 本轮对话是否由语音输入发起。
     *
     * 用作「语音问就语音答」的触发条件：只有语音输入的那一轮才朗读回复，
     * 文字输入时不该突然发出声音打扰周围人。
     *
     * 带时效：语音输入后用户可能改成文字再发（甚至不发），隔太久才出现的回复
     * 不该再被当作语音回合。
     */
    @Volatile
    private var voiceTurnAtMs = 0L

    fun markVoiceTurn() {
        voiceTurnAtMs = System.currentTimeMillis()
    }

    /** 取用并清除标记：一次语音输入只朗读一轮回复。 */
    fun consumeVoiceTurn(): Boolean {
        val fresh = System.currentTimeMillis() - voiceTurnAtMs < VOICE_TURN_TTL_MS
        voiceTurnAtMs = 0L
        return fresh
    }

    /**
     * 上次看到的「是否正在生成」。
     *
     * 放在进程级而不是 Composable 里：自动朗读只能在**生成刚结束**那一刻触发，
     * 而 `remember` 会在离开聊天页时被清掉、回来时又按当前值初始化——
     * 那样「切回聊天页」会被误判成「回复刚完成」，把上一条旧回复重念一遍。
     */
    @Volatile
    private var lastSendingState = false

    /** 检测「生成刚结束」这个下降沿，并把状态推进到当前值。 */
    fun consumeReplyFinished(isSending: Boolean): Boolean {
        val wasSending = lastSendingState
        lastSendingState = isSending
        return wasSending && !isSending
    }

    /** 当前正在朗读的消息 id（-1 = 无）。消息底部的喇叭按钮据此点亮。 */
    private val _speakingMessageId = MutableStateFlow(-1L)
    val speakingMessageId: StateFlow<Long> = _speakingMessageId.asStateFlow()

    fun setSpeakingMessage(messageId: Long) {
        _speakingMessageId.value = messageId
    }

    fun clearSpeakingMessage() {
        _speakingMessageId.value = -1L
    }

    private fun requireContext(): Context =
        appContext ?: error("VoiceEngines.init() 未在 Application.onCreate 中调用")

    /** 语音回合标记的有效期：超过它就不再算作语音发起的对话。 */
    private const val VOICE_TURN_TTL_MS = 120_000L
}
