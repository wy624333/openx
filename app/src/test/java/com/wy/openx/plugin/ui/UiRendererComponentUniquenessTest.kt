package com.wy.openx.plugin.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器组件名唯一性自检。
 *
 * 六个渲染器（UiRenderer / Ext / Ext2 / Audio / Overlay / WebView）各自维护一份
 * `when (node.type.lowercase()) { "组件名" -> ... }` 分发表，而它们由**调用顺序**
 * 决定谁生效。于是同名组件出现在两个文件里时，排在后面的那份是死代码 ——
 * 改它不会有任何效果。
 *
 * 这个坑真踩过：`surface` 在 Ext2 里有一份更早执行的实现，主渲染器里那份怎么改都
 * 不起作用，白花了一整轮排查。所以用测试把"组件名不许重复"钉死。
 */
class UiRendererComponentUniquenessTest {

    /** 组件名 → 出现的文件列表（按出现顺序）。 */
    private fun collectDispatch(): Map<String, List<String>> {
        val dir = rendererDir()
        val out = LinkedHashMap<String, MutableList<String>>()
        dir.listFiles { f -> f.isFile && RENDERER.matches(f.name) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val names = BRANCH.findAll(file.readText())
                    .map { it.groupValues[1] }
                    .distinct()
                names.forEach { name ->
                    out.getOrPut(name) { mutableListOf() }.add(file.name)
                }
            }
        return out
    }

    @Test
    fun `组件名不允许在多个渲染器里重复实现`() {
        val dup = collectDispatch().filterValues { it.size > 1 }
        assertTrue(
            "以下组件名在多个渲染器里都有分发分支，排在后面的那份不会生效：" +
                dup.entries.joinToString("；") { (name, files) -> "\"$name\" 出现在 ${files.joinToString()}" },
            dup.isEmpty()
        )
    }

    @Test
    fun `自检本身有效 —— 至少扫到了已知组件`() {
        val all = collectDispatch()
        // 防止正则或路径失效导致测试永远通过（空集合时上面那条会假绿）
        assertTrue("没扫到任何组件分支，检查渲染器路径/正则是否失效", all.size > 30)
        assertTrue("已知组件 surface / filledtonalbutton 未扫到", all.keys.containsAll(listOf("surface", "filledtonalbutton")))
    }

    private fun rendererDir(): File {
        val candidates = listOf(
            File("src/main/java/com/wy/openx/plugin/ui"),
            File("app/src/main/java/com/wy/openx/plugin/ui")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到渲染器目录，测试工作目录是：${File(".").absolutePath}")
    }

    private companion object {
        val RENDERER = Regex("^UiRenderer.*\\.kt$")

        /** 匹配 `"组件名" ->` 这种分支标签（组件名只含小写字母、数字、下划线）。 */
        val BRANCH = Regex("\"([a-z][a-z0-9_]*)\"\\s*->")
    }
}
