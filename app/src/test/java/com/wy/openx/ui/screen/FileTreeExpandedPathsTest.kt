package com.wy.openx.ui.screen

import com.wy.openx.container.BrowseClipboard
import com.wy.openx.container.FileBrowseState
import com.wy.openx.container.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧边栏文件树「展开路径」与真实目录脱节的回归测试。
 *
 * 背景：expandedPaths 只增不减，refresh() 又只清 dirCache，于是——
 * 1. 重命名一个**已展开**的目录后，新目录不会自动展开，旧路径留在集合里
 *    每次刷新都被 loadDir 白试一次（还会被持久化到 DataStore，越攒越多）；
 * 2. 删除一个**已展开**的目录后，它的整条路径永久残留，且不会自己消失。
 * 两者根因相同：没有任何地方把 expandedPaths 和真实目录列表对账。
 */
class FileTreeExpandedPathsTest {

    private fun dir(vararg names: String) = FileBrowseState.Success(
        names.map { FileEntry(name = it, isDirectory = true, size = 0, lastModified = 0) }
    )

    private fun file(vararg names: String) = FileBrowseState.Success(
        names.map { FileEntry(name = it, isDirectory = false, size = 1, lastModified = 0) }
    )

    // ===== 重命名：前缀改写 =====

    @Test
    fun `重命名已展开目录时整棵子树跟着改写`() {
        val before = setOf("/", "/a/b", "/a/b/c", "/a/b/c/d", "/other")
        val after = remapExpandedPaths(before, old = "/a/b", new = "/x/y")
        assertEquals(setOf("/", "/x/y", "/x/y/c", "/x/y/c/d", "/other"), after)
    }

    @Test
    fun `重命名不会误伤同前缀的兄弟目录`() {
        // /a/bc 与 /a/b 只差一个字符，用 startsWith(old) 就会连它一起改掉
        val before = setOf("/", "/a/b", "/a/bc", "/a/bcd")
        val after = remapExpandedPaths(before, old = "/a/b", new = "/n")
        assertEquals(setOf("/", "/n", "/a/bc", "/a/bcd"), after)
    }

    @Test
    fun `重命名到自身等价于不变`() {
        val before = setOf("/", "/a")
        assertEquals(before, remapExpandedPaths(before, old = "/a", new = "/a"))
    }

    @Test
    fun `重命名未被展开的目录不影响其它条目`() {
        val before = setOf("/", "/a", "/b")
        assertEquals(before, remapExpandedPaths(before, old = "/c", new = "/d"))
    }

    @Test
    fun `重命名父目录后子目录的前缀不再带旧名`() {
        val after = remapExpandedPaths(setOf("/", "/a", "/a/b"), old = "/a", new = "/z")
        assertEquals(setOf("/", "/z", "/z/b"), after)
    }

    // ===== 对账：剪掉已删除的 =====

    @Test
    fun `删掉已展开目录后残留路径被剪掉`() {
        val cache = mapOf("/" to dir("a"), "/a" to dir("keep"))
        // /a/deleted 已不在 /a 的列表里
        val after = pruneMissingExpandedPaths(setOf("/", "/a", "/a/keep", "/a/deleted"), cache)
        assertEquals(setOf("/", "/a", "/a/keep"), after)
    }

    @Test
    fun `递归删除父目录时其所有后代一并剪掉`() {
        // /a 整个没了，/a/b、/a/b/c 都不该留下
        val cache = mapOf("/" to dir("other"))
        val after = pruneMissingExpandedPaths(setOf("/", "/a", "/a/b", "/a/b/c"), cache)
        assertEquals(setOf("/"), after)
    }

    @Test
    fun `根路径永远不会被剪掉`() {
        // 即使根目录自己的列表里没有名为 "" 的条目
        val cache = mapOf("/" to file("readme.md"))
        val after = pruneMissingExpandedPaths(setOf("/"), cache)
        assertEquals(setOf("/"), after)
    }

    @Test
    fun `空集合直接返回`() {
        assertEquals(emptySet<String>(), pruneMissingExpandedPaths(emptySet(), emptyMap()))
    }

    // ===== 关键：加载时序不能误剪 =====

    @Test
    fun `父目录还没加载出来时一律保留不误剪`() {
        // refresh() 清空 dirCache 后目录逐个异步加载，此时 /a 还是 Loading。
        // 若此时剪 /a 的子目录，就会把「还没加载完」误判成「已删除」。
        val cache = mapOf("/" to dir("a"), "/a" to FileBrowseState.Loading)
        val before = setOf("/", "/a", "/a/b")
        assertEquals(before, pruneMissingExpandedPaths(before, cache))
    }

    @Test
    fun `父目录不在缓存里时保留`() {
        assertEquals(
            setOf("/", "/a", "/a/b"),
            pruneMissingExpandedPaths(setOf("/", "/a", "/a/b"), mapOf("/" to dir("a")))
        )
    }

    @Test
    fun `父目录列不出来时保守保留`() {
        val cache = mapOf("/" to dir("a"), "/a" to FileBrowseState.Error("权限不足"))
        val before = setOf("/", "/a", "/a/b")
        assertEquals(before, pruneMissingExpandedPaths(before, cache))
    }

    @Test
    fun `多轮加载后最终收敛到正确结果`() {
        // 模拟真实时序：refresh 清空 → 根加载 → /a 加载 → /a/b 加载。
        // 每轮都调用一次对账，最终结果必须和「全部加载完再对账」一致。
        val target = setOf("/", "/a", "/a/keep", "/a/gone")
        val finalCache = mapOf("/" to dir("a"), "/a" to dir("keep", "gone2"))
        val expected = setOf("/", "/a", "/a/keep")

        var cur = target
        cur = pruneMissingExpandedPaths(cur, emptyMap())                    // 1 全空
        cur = pruneMissingExpandedPaths(cur, mapOf("/" to FileBrowseState.Loading)) // 2 根加载中
        cur = pruneMissingExpandedPaths(cur, mapOf("/" to dir("a")))         // 3 根好了
        cur = pruneMissingExpandedPaths(cur, finalCache)                     // 4 全好了
        assertEquals(expected, cur)
    }

    // ===== 别名目录（overridePath）=====

    @Test
    fun `父目录条目用 overridePath 时按别名对账`() {
        val entries = listOf(
            FileEntry(name = "工作区", isDirectory = true, size = 0, lastModified = 0, overridePath = "/workspace")
        )
        val cache = mapOf("/" to FileBrowseState.Success(entries))
        // /workspace 存在（别名），/nope 不存在
        val after = pruneMissingExpandedPaths(setOf("/", "/workspace", "/nope"), cache)
        assertEquals(setOf("/", "/workspace"), after)
    }
}

/**
 * 粘贴后「该刷新哪些目录」的回归测试。
 *
 * 背景（2026-10-05 用户报障：侧边栏文件管理「剪切怎么和复制一样，怎么还留下文件」）：
 * 文件确实被 `fs.move` 移走了，但 UI 上源目录里那个条目还在 —— 因为 `dirCache` 是按目录
 * 缓存的，粘贴时只 `reloadDir(dstParent)` 刷了目标目录，源目录那份缓存没重拉。
 *
 * 复制只需刷目标（源没变），**剪切是源与目标都变了**。这个区别以前没体现在代码里。
 */
class FileTreePasteReloadTest {

    private fun clip(src: String, isCut: Boolean) =
        BrowseClipboard(sourcePath = src, sourceName = src.substringAfterLast('/'), isCut = isCut)

    // ===== 核心回归：剪切必须刷源 =====

    @Test
    fun `剪切跨目录时源与目标都要刷新`() {
        val dirs = dirsToReloadAfterPaste(clip("/root/a/f.txt", isCut = true), dstParent = "/root/b")
        assertEquals(setOf("/root/a", "/root/b"), dirs)
    }

    @Test
    fun `跨目录复制只刷目标`() {
        // 复制不删源，源目录列表不会变，刷它是白费 IO
        val dirs = dirsToReloadAfterPaste(clip("/root/a/f.txt", isCut = false), dstParent = "/root/b")
        assertEquals(setOf("/root/b"), dirs)
    }

    @Test
    fun `同级移动源与目标同目录时只刷一次`() {
        // /root/a/f.txt 移到 /root/a/g.txt：dstParent 与 srcParent 都是 /root/a，
        // 去重后只该发一次 reloadDir，否则同级移动会变成两倍 IO
        val dirs = dirsToReloadAfterPaste(clip("/root/a/f.txt", isCut = true), dstParent = "/root/a")
        assertEquals(setOf("/root/a"), dirs)
    }

    @Test
    fun `从根目录剪切时源父目录归一化成根`() {
        // parentOf("/f.txt") 会得到 ""，必须归一化成 "/" —— 否则 dirCache[""] 恒为 null，
        // 源目录永远刷不到（这正是本 bug 在根目录层级的表现）
        val dirs = dirsToReloadAfterPaste(clip("/f.txt", isCut = true), dstParent = "/sub")
        assertEquals(setOf("/", "/sub"), dirs)
    }

    @Test
    fun `从根目录复制到子目录只刷子目录`() {
        val dirs = dirsToReloadAfterPaste(clip("/f.txt", isCut = false), dstParent = "/sub")
        assertEquals(setOf("/sub"), dirs)
    }

    @Test
    fun `移动目录本身时刷的是它的父目录`() {
        // 剪的是目录 /root/a/sub，源目录是 /root/a（不是 /root/a/sub）
        val dirs = dirsToReloadAfterPaste(clip("/root/a/sub", isCut = true), dstParent = "/root/b")
        assertEquals(setOf("/root/a", "/root/b"), dirs)
    }

    @Test
    fun `别名目录作为源时按容器路径取父目录`() {
        val dirs = dirsToReloadAfterPaste(clip("/root/.openx/cfg.json", isCut = true), dstParent = "/root/workspace")
        assertEquals(setOf("/root/.openx", "/root/workspace"), dirs)
    }
}
