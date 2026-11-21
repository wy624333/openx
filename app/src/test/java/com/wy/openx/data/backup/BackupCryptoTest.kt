package com.wy.openx.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

/**
 * 备份口令加密的回归测试。备份模块此前零单测，加密这段更要测——
 * 加密写错的后果是**用户的备份永久解不开**。
 */
class BackupCryptoTest {

    private val plain = "OpenX 备份内容：apiKey=sk-test-1234567890".toByteArray()

    @Test
    fun `加解密往返一致`() {
        val blob = BackupCrypto.encrypt(plain, "hunter2")
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "hunter2"))
    }

    @Test
    fun `能识别出加密包`() {
        assertTrue(BackupCrypto.isEncrypted(BackupCrypto.encrypt(plain, "pw")))
    }

    @Test
    fun `明文 zip 不被误判成加密包`() {
        // PK\x03\x04 是 zip 的魔数，必须原样交给 ZipInputStream
        val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertFalse(BackupCrypto.isEncrypted(zipHead))
    }

    @Test
    fun `头部太短不误判`() {
        assertFalse(BackupCrypto.isEncrypted(byteArrayOf(0x4F, 0x58)))
    }

    @Test
    fun `口令错误必定抛异常而不是产出明文`() {
        val blob = BackupCrypto.encrypt(plain, "right")
        val failed = try {
            BackupCrypto.decrypt(blob, "wrong")
            false
        } catch (e: Exception) {
            assertTrue("应抛 AEADBadTagException，实际 ${e::class.java.name}", e is AEADBadTagException)
            true
        }
        assertTrue("口令错了却没报错，等于没校验", failed)
    }

    @Test
    fun `密文被篡改会被检出`() {
        val blob = BackupCrypto.encrypt(plain, "pw")
        blob[blob.size - 5] = (blob[blob.size - 5] + 1).toByte()
        val failed = try {
            BackupCrypto.decrypt(blob, "pw")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("GCM 应检出篡改", failed)
    }

    @Test
    fun `同一明文两次加密结果不同`() {
        // 盐/IV 固定的话同一份备份两次结果相同，等于没加密
        val a = BackupCrypto.encrypt(plain, "pw")
        val b = BackupCrypto.encrypt(plain, "pw")
        assertFalse("两次加密结果相同，说明盐或 IV 被固定了", a.contentEquals(b))
    }

    @Test
    fun `空内容也能往返`() {
        val empty = ByteArray(0)
        assertArrayEquals(empty, BackupCrypto.decrypt(BackupCrypto.encrypt(empty, "pw"), "pw"))
    }

    @Test
    fun `跨分块边界的大内容能完整还原`() {
        // 故意造大于 64KB 的内容，压 pump() 的分块与收尾逻辑
        val big = ByteArray(300_000) { (it % 251).toByte() }
        assertArrayEquals(big, BackupCrypto.decrypt(BackupCrypto.encrypt(big, "pw"), "pw"))
    }

    @Test
    fun `恰好等于分块大小的内容能完整还原`() {
        val exact = ByteArray(64 * 1024) { (it % 97).toByte() }
        assertArrayEquals(exact, BackupCrypto.decrypt(BackupCrypto.encrypt(exact, "pw"), "pw"))
    }

    @Test
    fun `流式加密与内存加密结果都能被解回`() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(plain), out, "pw")
        assertArrayEquals(plain, BackupCrypto.decrypt(out.toByteArray(), "pw"))
    }

    @Test
    fun `头部写入位置正确`() {
        val blob = BackupCrypto.encrypt(plain, "pw")
        // 头部之后紧跟密文，长度应正好是 明文 + GCM tag(16)
        assertEquals(BackupCrypto.HEADER_LEN + plain.size + 16, blob.size)
        // 魔数逐字节校验（isEncrypted 已覆盖，这里确认落盘位置没偏）
        assertEquals("OXBK1\n", String(blob, 0, 6, Charsets.US_ASCII))
        assertEquals(1, blob[6].toInt())
        // iterations 是 4 字节大端，落在偏移 7..10（20000 = 0x00004E20）
        val iters = ((blob[7].toInt() and 0xFF) shl 24) or ((blob[8].toInt() and 0xFF) shl 16) or
            ((blob[9].toInt() and 0xFF) shl 8) or (blob[10].toInt() and 0xFF)
        assertEquals(20_000, iters)
    }

    @Test
    fun `截断的包被拒绝`() {
        val blob = BackupCrypto.encrypt(plain, "pw")
        val truncated = blob.copyOf(10)
        val failed = try {
            BackupCrypto.decrypt(truncated, "pw")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("截断包应被拒绝", failed)
    }

    @Test
    fun `明文包交给解密应明确报不是加密包`() {
        val failed = try {
            BackupCrypto.decrypt("PK\u0003\u0004不是加密包".toByteArray(), "pw")
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("应提示不是加密备份文件", failed)
    }

    @Test
    fun `encryptingStream 产出可被 decrypt 还原`() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, "pw").use { it.write(plain) }
        val blob = out.toByteArray()

        assertEquals(BackupCrypto.HEADER_LEN + plain.size + 16, blob.size)
        assertTrue(BackupCrypto.isEncrypted(blob.copyOf(BackupCrypto.HEADER_LEN)))
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "pw"))
    }

    @Test
    fun `encryptingStream 分多次 write 仍能连续解开`() {
        // GCM 是流式 CTR：ZipOutputStream 会分多次写底层流，每次 write 之间必须保持连续
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, "pw").use { st ->
            var i = 0
            while (i < plain.size) {
                val n = minOf(5, plain.size - i)
                st.write(plain, i, n)
                i += n
            }
        }
        assertArrayEquals(plain, BackupCrypto.decrypt(out.toByteArray(), "pw"))
    }

    @Test
    fun `encryptingStream 空内容也能解`() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, "pw").use { }
        val blob = out.toByteArray()
        assertEquals(BackupCrypto.HEADER_LEN + 16, blob.size)
        assertEquals(0, BackupCrypto.decrypt(blob, "pw").size)
    }

    @Test
    fun `encryptingStream 不 close 则残包解不开`() {
        // 守住「tag 只在 close 时写出」这个前提：谁以后把 close 去掉，这条会红
        val out = ByteArrayOutputStream()
        val st = BackupCrypto.encryptingStream(out, "pw")
        st.write(plain)
        st.flush()
        var failed = false
        try {
            BackupCrypto.decrypt(out.toByteArray(), "pw")
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("未 close 的残包应当解不开", failed)
    }
}
