package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置页分区导航栈的回归测试。
 *
 * 用户报的现象：从「容器」进「镜像下载」，按返回退到**设置主页**而不是容器页。
 * 根因是 `openSection` 是单个 String，`openSection = "imagerepo"` 覆盖销毁了 "container"，
 * 而返回键写死 `openSection = null`。其余 10 个分区深度都是 1，写死的 null 碰巧正确，
 * 所以一直没暴露——`imagerepo` 是全仓唯一深度为 2 的入口。
 */
class SettingsSectionStackTest {

    private val empty = emptyList<String>()

    @Test
    fun `空栈表示设置主页`() {
        assertNull(SectionNav.current(empty))
    }

    @Test
    fun `从主页进分区`() {
        val s = SectionNav.enter(empty, "container")
        assertEquals("container", SectionNav.current(s))
    }

    /** 这条就是用户报的 bug：嵌套入口退一级必须回到上一级，而不是主页。 */
    @Test
    fun `从容器进镜像下载后返回回到容器`() {
        val container = SectionNav.enter(empty, "container")
        val imageRepo = SectionNav.enter(container, "imagerepo")
        assertEquals("imagerepo", SectionNav.current(imageRepo))
        val back = SectionNav.exit(imageRepo)
        assertEquals("container", SectionNav.current(back))
    }

    @Test
    fun `主页进分区后返回回到主页`() {
        val s = SectionNav.enter(empty, "api")
        assertNull(SectionNav.current(SectionNav.exit(s)))
    }

    /** 退出设置页：空栈时必须原样返回，不能抛异常——否则会一路退回聊天页。 */
    @Test
    fun `空栈退出是安全的空操作`() {
        assertEquals(empty, SectionNav.exit(empty))
    }

    @Test
    fun `多层嵌套逐级返回`() {
        var s = SectionNav.enter(empty, "container")
        s = SectionNav.enter(s, "imagerepo")
        s = SectionNav.enter(s, "container")
        assertEquals(listOf("container", "imagerepo", "container"), s)
        assertEquals(listOf("container", "imagerepo"), SectionNav.exit(s))
        assertEquals(listOf("container"), SectionNav.exit(SectionNav.exit(s)))
        assertEquals(empty, SectionNav.exit(SectionNav.exit(SectionNav.exit(s))))
    }

    /** 进入是压栈而非替换：连续进两个分区后栈深为 2，否则「返回回上一级」根本不成立。 */
    @Test
    fun `进入是压栈不是替换`() {
        val s = SectionNav.enter(SectionNav.enter(empty, "container"), "imagerepo")
        assertEquals(2, s.size)
    }
}
