package com.wy.openx.ui.screen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** markdown 图片「保存到工作区 uploads」的文件名推导与防覆盖。 */
class MarkdownImageSaveTest {

    @Test
    fun `CDN 链接丢掉 query 后取末段`() {
        // 签名参数常比文件名还长，不剥掉会得到 xxx.png?sign=... 这种非法名
        assertEquals(
            "abc.png",
            deriveImageFileName("https://p3-flow-image.cdn/xxx/abc.png?sign=deadbeef&t=1", "image/png")
        )
    }

    @Test
    fun `无扩展名时按 MIME 补`() {
        assertEquals(
            "img.jpg",
            deriveImageFileName("https://a.b.c/v1/img", "image/jpeg")
        )
    }

    @Test
    fun `URL 里的非图片扩展名按 MIME 纠正`() {
        assertEquals(
            "x.png",
            deriveImageFileName("https://a/b/x?file=1#frag", "image/png")
        )
    }

    @Test
    fun `URL 有合法图片扩展名时以 URL 为准`() {
        // 某些图床用 .gif 链接实际返回 webp 也不纠——保持链接原样，浏览/工具侧照样能看
        assertEquals("k.gif", deriveImageFileName("https://a/b/k.GIF", "image/webp"))
    }

    @Test
    fun `路径非法字符被替换且首尾点被裁掉`() {
        val n = deriveImageFileName("https://a/b/..we:ird*name..png", "image/png")
        assertEquals("we_ird_name.png", n)
        assertTrue("不应含路径分隔符: $n", !n.contains('/') && !n.contains('\\'))
        assertTrue("不应以点开头: $n", !n.startsWith('.'))
    }

    @Test
    fun `带尾斜杠的域名不会被当成文件名`() {
        // 防回归：曾先 trimEnd('/')，导致 https://a.b.c/ 产出 a.b.png（把主机名当文件名）
        assertEquals("image.png", deriveImageFileName("https://a.b.c/", "image/png"))
        // 尾斜杠的目录 URL 同样回落到 image：不为省一个通用名去猜路径段——
        // 去掉尾斜杠再取末段，正是上面那个把主机名当文件名的 bug 的成因。
        assertEquals("image.png", deriveImageFileName("https://a.b.c/dir/x/", "image/png"))
    }

    @Test
    fun `末段为空时回落到 image`() {
        assertEquals("image.png", deriveImageFileName("https://a.b.c/", "image/png"))
        assertEquals("image.jpg", deriveImageFileName("", "image/jpeg"))
    }

    @Test
    fun `data URI 不把整段 base64 当文件名`() {
        val n = deriveImageFileName("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB", "image/png")
        assertEquals("image.png", n)
    }

    @Test
    fun `同名不覆盖 追加序号`() {
        val dir = createTempDir()
        val a = uniqueFileIn(dir, "p.png")
        assertTrue(a.createNewFile())
        val b = uniqueFileIn(dir, "p.png")
        assertTrue(b.createNewFile())
        assertEquals("p.png", a.name)
        assertEquals("p_1.png", b.name)
        val c = uniqueFileIn(dir, "p.png")
        assertTrue(c.createNewFile())
        assertEquals("p_2.png", c.name)
    }

    @Test
    fun `无扩展名的同名文件也追加序号`() {
        val dir = createTempDir()
        assertTrue(File(dir, "x").createNewFile())
        assertEquals("x_1", uniqueFileIn(dir, "x").name)
    }
}
