package com.wy.openx.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用浏览器打开」准入清单 + 媒体种类判定。
 *
 * 这层是纯函数，回归价值在于**清单与菜单/播放器包装页保持一致**：
 * 若有人把 mkv 之类 Chromium 不支持的容器加进来，菜单会给入口但打开是黑屏。
 */
class BrowserPreviewFormatsTest {

    @Test
    fun `常见音频与视频都算可预览`() {
        listOf("a.mp3", "b.m4a", "c.flac", "d.wav", "e.ogg", "f.opus", "g.aac")
            .forEach { assertTrue(it, BrowserPreviewFormats.isPreviewable(it)) }
        listOf("a.mp4", "b.webm", "c.m4v", "d.ogv", "e.3gp", "f.mov")
            .forEach { assertTrue(it, BrowserPreviewFormats.isPreviewable(it)) }
    }

    @Test
    fun `文本图片与网页仍然可预览`() {
        listOf("a.html", "b.svg", "c.xml", "d.txt", "e.json", "f.css", "g.png", "h.webp")
            .forEach { assertTrue(it, BrowserPreviewFormats.isPreviewable(it)) }
    }

    @Test
    fun `扩展名大小写不敏感且取最后一段`() {
        assertTrue(BrowserPreviewFormats.isPreviewable("SONG.MP3"))
        assertTrue(BrowserPreviewFormats.isPreviewable("/mnt/sdcard/a.b/CLIP.MP4"))
        assertEquals("mp4", BrowserPreviewFormats.extOf("x.TAR.MP4"))
    }

    @Test
    fun `Chromium 不支持的容器不进入口`() {
        listOf("a.mkv", "b.avi", "c.pdf", "d.docx", "e.zip", "f.ts", "g.rmvb")
            .forEach { assertFalse(it, BrowserPreviewFormats.isPreviewable(it)) }
    }

    @Test
    fun `无扩展名与空串不进入口`() {
        assertFalse(BrowserPreviewFormats.isPreviewable("README"))
        assertFalse(BrowserPreviewFormats.isPreviewable(""))
        assertFalse(BrowserPreviewFormats.isPreviewable("/root/workspace/dir/"))
    }

    @Test
    fun `媒体种类按扩展名区分`() {
        assertEquals(BrowserPreviewFormats.MediaKind.AUDIO, BrowserPreviewFormats.mediaKind("x.mp3"))
        assertEquals(BrowserPreviewFormats.MediaKind.AUDIO, BrowserPreviewFormats.mediaKind("x.M4A"))
        assertEquals(BrowserPreviewFormats.MediaKind.VIDEO, BrowserPreviewFormats.mediaKind("x.mp4"))
        assertEquals(BrowserPreviewFormats.MediaKind.VIDEO, BrowserPreviewFormats.mediaKind("x.webm"))
        assertNull(BrowserPreviewFormats.mediaKind("x.html"))
        assertNull(BrowserPreviewFormats.mediaKind("x.png"))
        assertNull(BrowserPreviewFormats.mediaKind("x.mkv"))
    }
}
