package com.wy.openx.audio

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.wy.openx.container.FileLogger
import com.wy.openx.data.provider.ProviderHttp
import com.wy.openx.service.AudioPlaybackService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 音频播放中枢：全进程唯一的播放器，供插件（`ctx.audio`）与 audiobar 组件共用。
 *
 * ## 为什么播放器必须活在主线程
 * ExoPlayer 只能在创建它的线程上访问。插件 JS 跑在 `Dispatchers.Default` 的后台线程，
 * 而且 action 派发期间持有插件 session 锁 —— **任何阻塞都会让整个插件面板僵死**
 * （插件发现器就踩过：ctx.http 同步阻塞把锁占满 40 秒）。
 *
 * 所以这里做两件事：
 *  1. **命令全部 post 到主线程**，调用方立即返回，不阻塞 JS 线程；
 *  2. **状态读快照**：主线程按事件 + 定时刷新 `snapshot`（@Volatile），JS 只读它，
 *     绝不直接触碰播放器。代价是进度最多滞后一个 tick（500ms），对进度条足够。
 */
object AudioPlaybackHub {

    private const val TAG = "AudioPlaybackHub"
    private const val TICK_MS = 500L
    private const val BUFFER_TIMEOUT_MS = 25_000L

    /** 播放队列里的一项。 */
    data class Track(
        val uri: String,
        val title: String = "",
        val artist: String = ""
    )

    /** 当前曲目。 */
    data class NowPlaying(
        val uri: String,
        val title: String,
        val artist: String,
        /** 直播流（电台）：时长无意义，进度条按"直播"处理。 */
        val isLive: Boolean
    )

    /** 对外状态快照。 */
    data class State(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val nowPlaying: NowPlaying? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val volume: Float = 1f,
        val error: String? = null,
        /** 循环模式，取值同 Player.REPEAT_MODE_*：0 不循环 / 1 单曲循环 / 2 列表循环。 */
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        /** 随机播放是否开启。 */
        val shuffle: Boolean = false
    )

    @Volatile
    private var snapshot = State()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var ticking = false

    /** 播放队列。单曲播放就是 size=1；插件可通过 playQueue 整单播放。 */
    private var queue: List<Track> = emptyList()
    private var queueIndex: Int = 0

    /** 读当前状态（任意线程安全）。 */
    fun state(): State = snapshot

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(snapshot)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    /** 当前 session（供通知栏 MediaStyle 用）。 */
    fun currentSession(): MediaSession? = session

    // ------------------------------------------------------------ 命令入口

    /**
     * 播放一个音频地址。
     *
     * @param uri 本地绝对路径（`/mnt/sdcard/...`）或 http(s) 地址（含 HLS 的 .m3u8）
     */
    fun play(context: Context, uri: String, title: String, artist: String) {
        playQueue(context, listOf(Track(uri, title, artist)), 0)
    }

    /**
     * 播放一个队列（整单播放）。
     *
     * **用 ExoPlayer 原生播放列表**（`setMediaItems`）而不是每次替换单个 item：
     * 只有播放器自己知道"有下一首/上一首"，系统媒体卡片才会显示「上一曲 / 下一曲」按钮。
     * 单曲播放时队列长度是 1，按钮会按不可用处理或重播当前曲。
     */
    fun playQueue(context: Context, tracks: List<Track>, startIndex: Int) {
        appContext = context.applicationContext
        val valid = tracks.filter { it.uri.isNotBlank() }
        if (valid.isEmpty()) {
            setError("播放地址为空")
            return
        }
        val idx = if (startIndex < 0) 0 else if (startIndex > valid.lastIndex) valid.lastIndex else startIndex
        queue = valid
        queueIndex = idx
        AudioPlaybackService.start(context)
        mainHandler.post { playQueueLocked() }
    }

    /** 把整个队列交给播放器并从 queueIndex 播起。**必须在主线程调用**。 */
    private fun playQueueLocked() {
        try {
            if (queue.isEmpty()) return
            val p = ensurePlayer()
            val items = queue.map { t ->
                val src = t.uri
                val local = localFileFor(src)
                val mediaUri = if (local != null) Uri.fromFile(local) else Uri.parse(src)
                val isLive = isStreamUri(src)
                val meta = MediaMetadata.Builder()
                    .setTitle(t.title.ifBlank { fallbackTitle(src) })
                    .setArtist(t.artist.ifBlank { if (isLive) "网络电台" else "" })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
                MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMediaMetadata(meta)
                    .build()
            }
            p.setMediaItems(items, queueIndex, 0L)
            p.prepare()
            p.playWhenReady = true
            applyCurrentTrack()
            startTicking()
            emit()
        } catch (e: Exception) {
            setError(e.message ?: "播放失败")
        }
    }

    /**
     * 按播放器当前的 MediaItem 刷新 `snapshot.nowPlaying`。
     * 上下曲切换、自动续播都走这里。
     */
    private fun applyCurrentTrack() {
        val p = player ?: return
        val item = p.currentMediaItem
        val meta = item?.mediaMetadata
        val src = item?.localConfiguration?.uri?.toString()
            ?: item?.mediaId
            ?: ""
        val title = meta?.title?.toString().orEmpty().ifBlank { fallbackTitle(src) }
        val artist = meta?.artist?.toString().orEmpty()
        snapshot = snapshot.copy(
            nowPlaying = NowPlaying(
                uri = src,
                title = title,
                artist = artist,
                isLive = isStreamUri(src)
            ),
            error = null,
            positionMs = 0L,
            durationMs = 0L
        )
    }

    /** 下一曲。已在队尾时重播当前曲（而不是点了毫无反应）。 */
    fun next() {
        mainHandler.post {
            val p = player ?: return@post
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.playWhenReady = true
                applyCurrentTrack()
                startTicking()
                emit()
            } else {
                p.seekTo(0)
                refreshSnapshot()
                emit()
            }
        }
    }

    /** 上一曲。已在队首时重播当前曲。 */
    fun previous() {
        mainHandler.post {
            val p = player ?: return@post
            if (p.hasPreviousMediaItem()) {
                p.seekToPreviousMediaItem()
                p.playWhenReady = true
                applyCurrentTrack()
                startTicking()
                emit()
            } else {
                p.seekTo(0)
                refreshSnapshot()
                emit()
            }
        }
    }

    fun setPlayWhenReady(playing: Boolean) {
        mainHandler.post {
            val p = player ?: return@post
            p.playWhenReady = playing
            if (playing) {
                AudioPlaybackService.start(appContext ?: return@post)
                startTicking()
            }
        }
    }

    fun pause() = setPlayWhenReady(false)

    fun resume() = setPlayWhenReady(true)

    fun toggle() {
        mainHandler.post {
            val p = player ?: return@post
            p.playWhenReady = !p.playWhenReady
            if (p.playWhenReady) {
                appContext?.let { AudioPlaybackService.start(it) }
                startTicking()
            }
        }
    }

    /** 循环模式轮转：不循环 → 列表循环 → 单曲循环 → 不循环。 */
    fun cycleRepeatMode() {
        mainHandler.post {
            val p = player ?: return@post
            p.repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            refreshSnapshot()
            emit()
        }
    }

    fun setRepeatMode(mode: Int) {
        mainHandler.post {
            val p = player ?: return@post
            p.repeatMode = mode
            refreshSnapshot()
            emit()
        }
    }

    /** 随机播放开关。 */
    fun toggleShuffle() {
        mainHandler.post {
            val p = player ?: return@post
            p.shuffleModeEnabled = !p.shuffleModeEnabled
            refreshSnapshot()
            emit()
        }
    }

    /** 停止并清空当前曲目；同时撤掉前台服务（不响就不该占通知栏）。 */
    fun stop() {
        val ctx = appContext
        mainHandler.post {
            try {
                player?.stop()
                player?.clearMediaItems()
            } catch (e: Exception) {
                FileLogger.e(TAG, "停止失败: ${e.message}")
            }
            queue = emptyList()
            queueIndex = 0
            // 清空的是曲目，不该顺带把用户的循环/随机偏好也重置掉
            val keepRepeat = snapshot.repeatMode
            val keepShuffle = snapshot.shuffle
            snapshot = State(repeatMode = keepRepeat, shuffle = keepShuffle)
            emit()
        }
        if (ctx != null) {
            AudioPlaybackService.stop(ctx)
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = if (positionMs < 0) 0L else positionMs
        // 同 setVolume：先更新快照，没播放时也不丢进度
        snapshot = snapshot.copy(positionMs = pos)
        emit()
        mainHandler.post {
            val p = player ?: return@post
            p.seekTo(pos)
            refreshSnapshot()
            emit()
        }
    }

    fun setVolume(v: Float) {
        val clamped = if (v < 0f) 0f else if (v > 1f) 1f else v
        // 先记快照：**没播放过时 player 还没创建**（ensurePlayer 在 play() 里才调），
        // 如果只改播放器，用户的音量意图就丢了，滑块会被下一次轮询重置回原值，
        // 表现成“滑不动”。快照先行，播放器建好时再应用（见 ensurePlayer）。
        snapshot = snapshot.copy(volume = clamped)
        emit()
        mainHandler.post {
            val p = player ?: return@post
            p.volume = clamped
        }
    }

    /** 插件被禁用/卸载时调用：停止播放并释放播放器，避免"切走了还在响"。 */
    fun releaseForPlugin() {
        stop()
    }

    // ------------------------------------------------------------ 内部

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val ctx = appContext ?: throw IllegalStateException("上下文未初始化")
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    // OkHttp 只认 http/https，直接拿它当数据源会让所有 file:// 统统失败
                    // （OkHttp 抛 "Expected URL scheme 'http' or 'https' but was 'file'"，
                    // media3 再包成笼统的 Source error）。DefaultDataSource 会按 scheme 分派：
                    // file:// → FileDataSource，http(s):// → 下面这个 OkHttp（跟随 301/302、
                    // 直播流用更长的读超时，默认 8s 读完就断会被误判为失败）。
                    DefaultDataSource.Factory(
                        ctx,
                        OkHttpDataSource.Factory(
                            ProviderHttp.directClient.newBuilder()
                                .connectTimeout(20, TimeUnit.SECONDS)
                                .readTimeout(60, TimeUnit.SECONDS)
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        )
                    ),
                    // media3 1.5.1 只在构造函数上暴露 ExtractorsFactory（没有 setExtractorsFactory）
                    mediaExtractorsFactory
                )
            )
            // 缓冲参数：直播流卡在 BUFFERING 时不会自己报错，用户只看到“缓冲中…”一直转。
            // （1.5.1 里 setLoadControl 在 Builder 上；Player 上的 setBufferingSettings 是 1.6+）
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 50_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 5_000
                    )
                    .build()
            )
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        // 网络流后台播放时别让 CPU 睡：电台能连播靠它，WAKE_LOCK 权限已声明。
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        // 播放器建好时把之前设好的音量应用上（用户可能先拖滑块再点播放）
        p.volume = snapshot.volume
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // 时长就绪后立刻刷新，媒体卡片的进度条/时间依赖 duration
                    refreshSnapshot()
                    emit()
                }
                if (playbackState == Player.STATE_ENDED) {
                    // 本地音乐放完就停；直播流一般不会 ENDED
                    setPlayWhenReady(false)
                }
                if (playbackState == Player.STATE_BUFFERING) {
                    // 缓冲超时兜底：直播流卡住时给个明确错误，而不是一直转圈
                    mainHandler.removeCallbacks(bufferTimeoutRunnable)
                    mainHandler.postDelayed(bufferTimeoutRunnable, BUFFER_TIMEOUT_MS)
                } else {
                    mainHandler.removeCallbacks(bufferTimeoutRunnable)
                }
                refreshSnapshot()
                emit()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 自动续播 / 上下曲切换：同步当前曲目信息
                applyCurrentTrack()
                refreshSnapshot()
                emit()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshSnapshot()
                emit()
                if (isPlaying) startTicking()
            }

            override fun onPlayerError(error: PlaybackException) {
                // "Source error" 只是 ExoPlaybackException 的类型名，真实原因在 cause 链里，
                // 不把链路写出来的话，日志里永远只有这一句，无法定位。
                val chain = generateSequence<Throwable>(error) { it.cause }
                    .take(6)
                    .joinToString(" ← ") { "${it.javaClass.simpleName}(${it.message.orEmpty()})" }
                FileLogger.e(TAG, "播放异常 code=${error.errorCode} name=${error.errorCodeName} 链路=$chain")
                val local = queue.getOrNull(queueIndex)?.let { localFileFor(it.uri) }
                val detail = when {
                    local == null -> error.errorCodeName
                    !local.exists() -> "文件不存在或无访问权限：${local.absolutePath}"
                    else -> "解析失败（${local.name}）：${error.errorCodeName}"
                }
                setError(detail)
            }

            override fun onVolumeChanged(volume: Float) {
                snapshot = snapshot.copy(volume = volume)
                emit()
            }
        })
        player = p
        // MediaSession 提供耳机按键 / 蓝牙控制，也是媒体通知的 token 来源。
        session = runCatching {
            MediaSession.Builder(ctx, p).build()
        }.onFailure {
            FileLogger.e(TAG, "MediaSession 创建失败（不影响播放）: ${it.message}")
        }.getOrNull()
        return p
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        mainHandler.postDelayed(ticker, TICK_MS)
    }

    /** 缓冲超过这个时长就报错（直播流卡死时给用户一个交代）。 */
    private val bufferTimeoutRunnable = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            setError("缓冲超时，这个流可能已失效，换一个电台试试")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || (!p.isPlaying && p.playbackState != Player.STATE_BUFFERING)) {
                ticking = false
                refreshSnapshot()
                emit()
                return
            }
            refreshSnapshot()
            emit()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private fun refreshSnapshot() {
        val p = player ?: return
        val dur = p.duration
        snapshot = snapshot.copy(
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = if (p.currentPosition < 0) 0L else p.currentPosition,
            durationMs = if (dur == C.TIME_UNSET || dur < 0) 0L else dur,
            volume = p.volume,
            repeatMode = p.repeatMode,
            shuffle = p.shuffleModeEnabled
        )
    }

    private fun setError(msg: String) {
        snapshot = snapshot.copy(error = msg, buffering = false, playing = false)
        emit()
        FileLogger.e(TAG, "播放错误: $msg")
    }

    private fun emit() {
        val s = snapshot
        for (l in listeners) {
            runCatching { l(s) }
        }
    }

    private fun fallbackTitle(src: String): String {
        val name = src.substringAfterLast('/')
        return name.ifBlank { src }
    }

    /**
     * 把插件传来的路径解析成宿主进程真能打开的文件，非本地地址返回 null。
     *
     * 插件跑在容器里，存储路径写作 `/mnt/sdcard/...` 或 `/sdcard/...`，那是沙盒内的挂载点；
     * 宿主进程的真实路径是 `/storage/emulated/0/...`。直接 File(容器路径) 必然
     * FileNotFoundException，ExoPlayer 只会抛一个笼统的 Source error。
     */
    private fun localFileFor(src: String): File? {
        if (!src.startsWith("/")) return null
        val rel = when {
            src == "/mnt/sdcard" || src == "/sdcard" -> ""
            src.startsWith("/mnt/sdcard/") -> src.removePrefix("/mnt/sdcard/")
            src.startsWith("/sdcard/") -> src.removePrefix("/sdcard/")
            else -> return File(src)
        }
        val mapped = File(Environment.getExternalStorageDirectory(), rel)
        return if (mapped.exists()) mapped else File(src).takeIf { it.exists() } ?: mapped
    }

    /**
     * 按文件头判断真实容器格式。
     *
     * 扩展名不可信：曲库里存在扩展名写 `.mp3`、实际是 M4A 的文件。此时 Mp3Extractor 的
     * 嗅探会在前几 KB 里碰到 0xFFEx 之类的字节而误判成功，接着解析失败，最终只剩一句
     * Source error。显式给出 mimeType 才能让 media3 直接选对解析器。
     */
    private fun sniffMimeType(file: File): String? = runCatching {
        file.inputStream().use { ins ->
            val b = ByteArray(12)
            if (ins.read(b) < 8) return@use null
            when {
                b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                    b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> "audio/mp4"
                b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() &&
                    b[2] == '3'.code.toByte() -> "audio/mpeg"
                b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
                b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
                    b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte() -> "audio/flac"
                b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
                    b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte() -> "audio/ogg"
                b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
                    b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() -> "audio/wav"
                else -> null
            }
        }
    }.getOrNull()

    /**
     * 按真实文件头挑解析器，绕开 media3 的扩展名推断。
     *
     * `DefaultExtractorsFactory` 是按 **URI 扩展名**给 extractor 排序的：`.mp3` 会把
     * Mp3Extractor 放第一位。而 Mp3Extractor 的 sniff 会在前几 KB 逐字节找帧同步 `0xFFEx`，
     * M4A 的音频数据里这种字节大量存在 —— 于是嗅探误判成功、真正解析时失败，
     * 最终只抛一句 Source error。曲库里扩展名写 `.mp3` 实为 M4A 的文件很常见，必须绕开。
     *
     * ⚠ **每次都必须新建 extractor 数组**。Extractor 是有状态对象（内部持有待解析缓冲），
     * 官方契约要求 `createExtractors` 返回新实例。曾经把结果缓存成一个 fallback 反复返回，
     * 导致所有 HTTP 直链电台（.mp3 这类 Progressive 流）抛
     * `NullPointerException: Object.getClass() on null object reference` 并被包成 Source error；
     * 而 HLS（.m3u8）走 HlsMediaSource 不经 extractor，所以症状表现为「HLS 能播、直链全挂」。
     */
    private val mediaExtractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            DefaultExtractorsFactory().createExtractors()

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>
        ): Array<Extractor> {
            val all = DefaultExtractorsFactory().createExtractors()
            if (uri.scheme != "file") return all
            val path = uri.path ?: return all
            val sniffed = sniffMimeType(File(path))
            FileLogger.d(TAG, "选解析器 file=$path sniff=$sniffed")
            // 命中类型的 extractor 提到最前，其余保持原顺序兜底。
            // 复用工厂创建的实例、不自己 new：Mp4Extractor() 等无参构造在 1.5.1 已弃用。
            return when (sniffed) {
                "audio/mp4" -> prioritize(all, Mp4Extractor::class.java)
                "audio/mpeg" -> prioritize(all, Mp3Extractor::class.java)
                "audio/flac" -> prioritize(all, FlacExtractor::class.java)
                "audio/ogg" -> prioritize(all, OggExtractor::class.java)
                "audio/wav" -> prioritize(all, WavExtractor::class.java)
                else -> all
            }
        }
    }

    /** 把指定类型的 extractor 提到最前，其余保持原顺序。 */
    private fun prioritize(all: Array<Extractor>, clazz: Class<*>): Array<Extractor> {
        val picked = ArrayList<Extractor>(all.size)
        val rest = ArrayList<Extractor>(all.size)
        for (e in all) {
            if (clazz.isInstance(e)) picked.add(e) else rest.add(e)
        }
        picked.addAll(rest)
        return picked.toTypedArray()
    }

    /** 判断是不是直播流：电台常见 m3u8/无扩展名端点，本地文件不算。 */
    private fun isStreamUri(src: String): Boolean {
        if (!src.startsWith("http://") && !src.startsWith("https://")) return false
        val lower = src.lowercase()
        return lower.contains(".m3u8") || lower.contains("/live") || lower.contains("stream") ||
            lower.endsWith(".mp3") || lower.endsWith(".aac") || lower.endsWith(".m3u")
    }
}
