package com.wy.openx.data.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 回归：项目技能的启停必须写入 `skills.json` 的 `projects.<项目>` 结构。
 * 曾因 `projects` 为星投影类型导致 `apply { set(...) }` 误作用到外层 map，
 * 项目技能开关无法关闭（全局正常）。
 */
class SkillStoreScopeTest {
    private fun fakeContext(root: File): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getString" -> null
                    "getBoolean" -> false
                    "getInt" -> 0
                    "getLong" -> 0L
                    "getFloat" -> 0f
                    "getStringSet" -> emptySet<String>()
                    "edit" -> Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)
                    ) { _, m, _ ->
                        when (m.name) {
                            "putString", "putBoolean", "putInt", "putLong", "putFloat",
                            "putStringSet", "remove", "clear" -> null
                            "commit" -> true
                            else -> null
                        }
                    }
                    else -> null
                }
            } as SharedPreferences
    }

    private fun writeSkill(root: File, scope: String, project: String, name: String) {
        val dir = if (scope == SCOPE_PROJECT)
            File(File(File(File(root, "openx"), "skills"), "projects"), project)
        else File(File(root, "openx"), "skills")
        val skillDir = File(dir, name)
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(
            "---\nname: \"$name\"\ndescription: \"desc\"\n---\n\nbody\n"
        )
    }

    private fun newRoot(): File = File.createTempFile("skstore", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun projectToggleOffPersists() {
        val tmp = newRoot()
        try {
            val store = SkillStore(fakeContext(tmp))
            val project = "MyProj"
            writeSkill(tmp, SCOPE_PROJECT, project, "Skill-A")
            writeSkill(tmp, SCOPE_PROJECT, project, "Skill-B")

            assertEquals(
                mapOf("Skill-A" to true, "Skill-B" to true),
                store.loadProject(project).associate { it.name to it.enabled }
            )

            store.setEnabled("Skill-A", false, SCOPE_PROJECT, project)

            val after = store.loadProject(project).associate { it.name to it.enabled }
            assertFalse("Skill-A 应已停用", after["Skill-A"]!!)
            assertTrue("Skill-B 应仍启用", after["Skill-B"]!!)

            val json = File(File(tmp, "openx"), "skills.json").readText()
            assertTrue("应写入 projects 结构: $json", json.contains("\"projects\""))
            assertTrue("项目名应嵌套在 projects 内: $json", json.contains("\"MyProj\""))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun globalAndProjectScopesAreIndependent() {
        val tmp = newRoot()
        try {
            val store = SkillStore(fakeContext(tmp))
            val project = "MyProj"
            writeSkill(tmp, SCOPE_GLOBAL, "", "GSkill")
            writeSkill(tmp, SCOPE_PROJECT, project, "PSkill")

            store.setEnabled("GSkill", false, SCOPE_GLOBAL, "")
            store.setEnabled("PSkill", false, SCOPE_PROJECT, project)

            assertFalse(store.load().first { it.name == "GSkill" }.enabled)
            assertFalse(store.loadProject(project).first { it.name == "PSkill" }.enabled)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun legacyTopLevelProjectKeyStillReadable() {
        val tmp = newRoot()
        try {
            val project = "MyProj"
            writeSkill(tmp, SCOPE_PROJECT, project, "Skill-A")
            File(tmp, "openx").mkdirs()
            File(File(tmp, "openx"), "skills.json").writeText("""{"MyProj":["skill-a"]}""")

            val store = SkillStore(fakeContext(tmp))
            assertFalse(store.loadProject(project).first { it.name == "Skill-A" }.enabled)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
