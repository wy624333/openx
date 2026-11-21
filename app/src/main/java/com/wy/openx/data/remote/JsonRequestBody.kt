package com.wy.openx.data.remote

import com.google.gson.Gson
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.Writer

/**
 * 把 JSON 对象**流式**写进请求体，不在内存里拼出完整 JSON 字符串。
 *
 * 长会话（几百条消息）的请求体可达数 MB。`gson.toJson(obj)` 要先建一个 StringBuilder，
 * 增长期间旧数组与新数组同时在堆上（峰值约为最终长度的 2~3 倍），再叠加日志侧 append
 * 拷贝、字节数组拷贝，2MB 的请求体峰值能到 10MB 以上——真机上就是崩在
 * `AbstractStringBuilder.append` 的 `Arrays.copyOf` 上。
 *
 * 这里改成两趟遍历：一趟只计字节数（[contentLength]，OkHttp 要准确值，否则会改用
 * chunked 编码，部分网关不接受），一趟直接写进 Okio sink。内存占用与请求体大小无关。
 * 代价是序列化两遍，用 CPU 换内存，值得。
 */
class JsonRequestBody(
    private val gson: Gson,
    private val payload: Any,
    private val contentType: MediaType?,
) : RequestBody() {

    /** 计数结果缓存：OkHttp 可能多次询问 contentLength，不必重复遍历。 */
    @Volatile
    private var cachedLength: Long = -1

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long {
        val cached = cachedLength
        if (cached >= 0) return cached
        val counted = CountingWriter().also { w ->
            gson.toJson(payload, payload.javaClass, gson.newJsonWriter(w))
        }.bytes
        cachedLength = counted
        return counted
    }

    override fun writeTo(sink: BufferedSink) {
        // 不 close：close 会连带关掉 sink。JsonWriter.flush() 会把缓冲推到 OutputStreamWriter，
        // 再 flush 一次推到底层 sink。
        val writer = sink.outputStream().writer(Charsets.UTF_8)
        val jsonWriter = gson.newJsonWriter(writer)
        gson.toJson(payload, payload.javaClass, jsonWriter)
        jsonWriter.flush()
        writer.flush()
    }

    /** 只统计 UTF-8 字节数、不保存任何内容的 Writer。 */
    private class CountingWriter : Writer() {
        var bytes = 0L

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            for (i in off until off + len) bytes += utf8Length(cbuf[i])
        }

        override fun write(s: String, off: Int, len: Int) {
            for (i in off until off + len) bytes += utf8Length(s[i])
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        /**
         * 单个 UTF-16 字符编码成 UTF-8 后的字节数。
         *
         * 代理对（emoji 等）按「高低各 2 字节」计，合计 4 字节，与 UTF-8 实际编码一致——
         * 若按单个 char 独立计算会得到 3+3=6，导致 Content-Length 偏大、请求被截断。
         */
        private fun utf8Length(c: Char): Int = when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isSurrogate(c) -> 2
            else -> 3
        }
    }
}

/**
 * 生成用于日志的 JSON 预览：最多 [maxChars] 字符，超出即停止序列化。
 *
 * 日志不该为了留痕而把几 MB 的请求体整个拼出来（那正是 OOM 的来源之一）；
 * 而且会话日志本身就有 20MB 上限，完整记录超大 body 也只是挤掉其他有用信息。
 */
fun jsonPreview(gson: Gson, payload: Any, maxChars: Int = 32_000): String {
    val limited = LimitedWriter(maxChars)
    try {
        gson.toJson(payload, payload.javaClass, gson.newJsonWriter(limited))
    } catch (_: LimitedWriter.Overflow) {
        // 达到上限，用已累积的部分
    }
    val text = limited.text.toString()
    return if (limited.overflowed) "$text…（预览已截断，共 ${limited.text.length}+ 字符）" else text
}

/** 累积到上限就抛 [Overflow] 中断序列化的 Writer。 */
private class LimitedWriter(private val limit: Int) : Writer() {
    val text = StringBuilder(minOf(limit, 8192))
    var overflowed = false
        private set

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        val room = limit - text.length
        if (room <= 0) {
            overflowed = true
            throw Overflow()
        }
        val n = minOf(len, room)
        text.appendRange(cbuf, off, off + n)
        if (n < len) {
            overflowed = true
            throw Overflow()
        }
    }

    override fun write(s: String, off: Int, len: Int) {
        val room = limit - text.length
        if (room <= 0) {
            overflowed = true
            throw Overflow()
        }
        val n = minOf(len, room)
        text.append(s, off, off + n)
        if (n < len) {
            overflowed = true
            throw Overflow()
        }
    }

    override fun flush() = Unit

    override fun close() = Unit

    /** 达到上限的哨兵异常；不填栈（高频抛出，省开销）。 */
    class Overflow : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}
