package com.wy.openx.data.backup

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的口令加密（AES-128-GCM + PBKDF2）。
 *
 * ## 为什么口令是「可选」的
 * 备份里含 API Key 和聊天记录，放到用户自己选的位置（U 盘 / 网盘）时加密有价值；
 * 但强制口令会让「随手备份一下」变麻烦，也会把不会设口令的用户挡在门外。
 * 所以默认不加密，勾了才加密。
 *
 * ## 容器格式
 * ```
 * magic(6)      = "OXBK1\n"
 * version(1)    = 1
 * iterations(4) = PBKDF2 迭代数，大端
 * salt(16)      = 随机盐
 * iv(12)        = 随机 GCM IV
 * payload       = AES-128-GCM 密文（末尾 16 字节是 GCM tag）
 * ```
 * 盐和 IV 每次都新生成——固定的话同一份明文两次加密结果完全相同，等于没加密。
 * iterations 写进头部，以后想调高强度时旧文件仍能按它自己的参数解开。
 *
 * **选 AES-128 而不是 AES-256**：早期 Android 对 JCE 有「无限强度」限制，
 * `SecretKeySpec` 传 32 字节可能被降级或直接抛异常。128 位对本机备份够用，
 * 且在 minSdk 24 上行为确定。
 *
 * **选 PBKDF2WithHmacSHA1 而不是 SHA256**：后者要 API 26，本项目 minSdk 24。
 * SHA1 版 PBKDF2 迭代两万次，在「防随手翻看」这个威胁模型下够用。
 *
 * ## 为什么不用 CipherInputStream
 * GCM 的完整性校验在最后一块才发生，而 `CipherInputStream` 读结束时
 * 是否校验 tag 在各版本上行为不一致（有些版本不抛异常，等于没校验）。
 * 这里手动分块 `update()` + 收尾 `doFinal()`，口令错时**必定**抛
 * `AEADBadTagException`，不会悄悄产出半个明文。
 */
object BackupCrypto {

    private val MAGIC = "OXBK1\n".toByteArray(Charsets.US_ASCII)
    const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 128
    const val DEFAULT_ITERATIONS = 20_000

    private const val MAGIC_LEN = 6

    init {
        // MAGIC_LEN 是字面量，MAGIC 是运行时数组；两者不一致会让所有头部偏移全错。
        require(MAGIC.size == MAGIC_LEN) { "MAGIC 长度 ${MAGIC.size} != MAGIC_LEN $MAGIC_LEN" }
    }

    /** 头部总长 39 字节：magic(6) + version(1) + iterations(4) + salt(16) + iv(12)。 */
    val HEADER_LEN: Int = MAGIC_LEN + 1 + 4 + SALT_LEN + IV_LEN
    private const val ITER_OFF = MAGIC_LEN + 1
    private const val SALT_OFF = ITER_OFF + 4

    /** 读文件头判断是否加密包（不解密）。 */
    fun isEncrypted(head: ByteArray): Boolean {
        if (head.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
        return true
    }

    /** 读够头部；读不满返回 null。 */
    fun readHeader(input: InputStream): ByteArray? {
        val buf = ByteArray(HEADER_LEN)
        var off = 0
        while (off < HEADER_LEN) {
            val n = input.read(buf, off, HEADER_LEN - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }

    /**
     * 判定包类型，并把**已经读走的头部接回**数据流。
     *
     * SAF 返回的 InputStream 不支持 mark/reset，没法「读头部再回退」，
     * 所以返回的是「已读前缀 + 剩余字节」的拼接流，调用方继续顺序读即可。
     */
    sealed class Probe {
        /** 未加密的明文 zip。 */
        class Plain(val stream: InputStream) : Probe()

        /** 加密包，[stream] 含完整头部。 */
        class Encrypted(val stream: InputStream, val iterations: Int) : Probe()
    }

    fun probe(input: InputStream): Probe {
        // 读不满头部 = 文件比 39 字节还短，不可能带加密头，当明文交给 zip 去报「缺少清单」
        val head = readHeader(input) ?: return Probe.Plain(input)
        val rest = SequenceInputStream(ByteArrayInputStream(head), input)
        return if (isEncrypted(head)) Probe.Encrypted(rest, bytesToInt(head, ITER_OFF)) else Probe.Plain(rest)
    }

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun newCipher(mode: Int, key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun bytesToInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** 解析头部并返回 (salt, iv)；不是加密包或版本不认识就抛。 */
    private fun parseHeader(header: ByteArray): Triple<ByteArray, ByteArray, Int> {
        if (!isEncrypted(header)) throw IllegalArgumentException("不是加密备份文件")
        if (header[MAGIC.size] != VERSION) {
            throw IllegalArgumentException("备份文件版本 ${header[MAGIC.size]} 暂不支持")
        }
        val iterations = bytesToInt(header, ITER_OFF)
        if (iterations <= 0) throw IllegalArgumentException("备份文件头部损坏")
        val salt = header.copyOfRange(SALT_OFF, SALT_OFF + SALT_LEN)
        val iv = header.copyOfRange(SALT_OFF + SALT_LEN, HEADER_LEN)
        return Triple(salt, iv, iterations)
    }

    /**
     * 分块搬运数据。对 GCM 来说，**必须**在输入耗尽后调 `doFinal()`：
     * tag 校验就在这一步，口令错或文件被改过会在这里抛异常。
     */
    private fun pump(cipher: Cipher, input: InputStream, out: OutputStream) {
        val inBuf = ByteArray(64 * 1024)
        val outBuf = ByteArray(64 * 1024 + 32)
        while (true) {
            val n = input.read(inBuf)
            if (n < 0) break
            if (n == 0) continue
            var off = 0
            while (off < n) {
                val produced = cipher.update(inBuf, off, n - off, outBuf, 0)
                if (produced > 0) out.write(outBuf, 0, produced)
                off += n - off
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) out.write(tail)
    }

    /** 流式加密：[input] 全部内容加密后写入 [out]，自动带头部。 */
    /**
     * 把 [out] 包装成「先写头、再边写边加密」的流。
     *
     * 用来替代「先落临时 zip 再整体加密」：工作区放宽到 2GB 后，临时文件方案会在
     * cacheDir 里塞一份 2GB 的 zip，IO 还放大成 写2G+读2G+写2G。
     *
     * GCM 的 tag 由 [javax.crypto.CipherOutputStream.close] 顺着关闭链写出，
     * 所以调用方只需正常 close 上层流（这里由 ZipOutputStream.close 级联触发）。
     * 加密方向 doFinal 不会失败；万一中途异常，留下的残包会在解密时因 tag 不匹配被拒。
     */
    fun encryptingStream(out: OutputStream, password: String): OutputStream {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(intToBytes(DEFAULT_ITERATIONS))
        out.write(salt)
        out.write(iv)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt, DEFAULT_ITERATIONS), iv)
        return CipherOutputStream(BufferedOutputStream(out), cipher)
    }

    fun encryptStream(input: InputStream, out: OutputStream, password: String) {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(intToBytes(DEFAULT_ITERATIONS))
        out.write(salt)
        out.write(iv)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt, DEFAULT_ITERATIONS), iv)
        pump(cipher, input, out)
        out.flush()
    }

    /**
     * 把加密流解到 [dest]（[input] 从第 0 字节读起，含完整头部）。
     * 大文件走这里——先落临时文件再交给 ZipInputStream，避免整个备份进内存。
     */
    fun decryptTo(input: InputStream, dest: File, password: String) {
        val header = readHeader(input) ?: throw IllegalArgumentException("备份文件太短，不是有效的加密备份")
        val (salt, iv, iterations) = parseHeader(header)
        val cipher = newCipher(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), iv)
        dest.outputStream().buffered().use { fos -> pump(cipher, input, fos) }
    }

    /** 内存版加密/解密，供小内容与单测使用。 */
    fun encrypt(plain: ByteArray, password: String): ByteArray =
        java.io.ByteArrayOutputStream().also { encryptStream(java.io.ByteArrayInputStream(plain), it, password) }
            .toByteArray()

    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val src = java.io.ByteArrayInputStream(blob)
        val header = readHeader(src) ?: throw IllegalArgumentException("备份文件太短")
        val (salt, iv, iterations) = parseHeader(header)
        val cipher = newCipher(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), iv)
        return java.io.ByteArrayOutputStream().also { pump(cipher, src, it) }.toByteArray()
    }
}
