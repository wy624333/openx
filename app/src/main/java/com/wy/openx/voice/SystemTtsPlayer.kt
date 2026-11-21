package com.wy.openx.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.wy.openx.container.FileLogger
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 朗读：走系统 TextToSpeech，不引第三方 TTS。
 *
 * 选系统 TTS 的理由：零安装包体积、零模型下载、各厂商 ROM 自带中文音色。
 * 代价是音色随设备而异——对本项目而言可接受。
 *
 * [speakAndWait] 会挂到念完才返回，调用方据此串行排队多段文本；[stop] 要主动完成等待句柄，
 * 否则等待方会一直挂着。
 */
class SystemTtsPlayer(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    /** 当前这段念白的完成句柄；[stop] 要主动完成它，等待方才能醒过来。 */
    private var speaking: CompletableDeferred<Unit>? = null

    /** 是否正在朗读。 */
    val isSpeaking: Boolean get() = speaking != null

    /**
     * 念一段文字并等它念完。返回 false 表示没念（系统无引擎，或清洗后没内容可念）。
     */
    suspend fun speakAndWait(text: String): Boolean {
        val speech = sanitizeForSpeech(text)
        if (speech.isEmpty() || !ensureEngine()) return false
        val tts = engine ?: return false
        val done = CompletableDeferred<Unit>()
        speaking = done
        val utteranceId = UUID.randomUUID().toString()
        val accepted = withContext(Dispatchers.Main) {
            runCatching {
                tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
            }.getOrElse {
                FileLogger.w(TAG, "系统 TTS 播报失败：$speech", it)
                false
            }
        }
        if (!accepted) {
            speaking = null
            return false
        }
        done.await()
        if (speaking === done) speaking = null
        return true
    }

    /** 打断当前念白。 */
    fun stop() {
        speaking?.complete(Unit)
        speaking = null
        runCatching { engine?.stop() }
    }

    /** 释放引擎（退出前调用；之后 [speakAndWait] 会重新创建）。 */
    fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = null
    }

    private suspend fun ensureEngine(): Boolean {
        ready?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        withContext(Dispatchers.Main) {
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    created?.language = Locale.getDefault()
                    created?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            speaking?.complete(Unit)
                        }

                        @Deprecated("被 onError(String, Int) 取代，但抽象方法必须实现")
                        override fun onError(utteranceId: String?) {
                            speaking?.complete(Unit)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            FileLogger.w(TAG, "系统 TTS 报错 errorCode=$errorCode")
                            speaking?.complete(Unit)
                        }
                    })
                    engine = created
                    deferred.complete(true)
                } else {
                    FileLogger.w(TAG, "系统 TTS 引擎不可用 status=$status")
                    deferred.complete(false)
                }
            }
        }
        return deferred.await()
    }

    /**
     * 朗读前的文本清洗。
     *
     * 模型的推理块（`<thinking>…</thinking>`）是写给自己看的，直接念出来会让用户先听一段无关内容；
     * 有的模型还会只吐不闭合的开标签，也一并去掉。
     *
     * 只清这类已知标签，不做通用的 `<...>` 剥离：正文里的代码片段（`List<String>`、`a < b`）
     * 会被误伤。
     */
    private fun sanitizeForSpeech(raw: String): String {
        val withoutBlocks = REASONING_BLOCK.replace(raw, " ")
        val withoutTags = REASONING_TAG.replace(withoutBlocks, " ")
        return withoutTags.replace(WHITESPACE_RUN, " ").trim()
    }

    private companion object {
        const val TAG = "SystemTtsPlayer"

        /** 成对的推理块（含内容）。 */
        val REASONING_BLOCK = Regex("(?is)<(thinking|reasoning|analysis|think)\\b[^>]*>.*?</\\1\\s*>")

        /** 残留的推理标签，包括不闭合的开标签。 */
        val REASONING_TAG = Regex("(?i)</?(thinking|reasoning|analysis|think)\\b[^>]*>")

        /** 剥掉标签后可能留下连续空白，收成一个空格。 */
        val WHITESPACE_RUN = Regex("[ \\t]{2,}")
    }
}
