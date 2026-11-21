package com.wy.openx.plugin.compat

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Operit 包的权限推断。
 *
 * `hook` 是这里最敏感的权限（能读写每一条对话），所以不能无条件授予 ——
 * 只有源码里真调了钩子注册 API 的包才给。这组测试把这条边界钉死。
 */
class OperitCompatPermissionTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("operit-perm").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manifest(main: String = "main.js"): String = """
        {
          "schema_version": 1,
          "toolpkg_id": "com.test.sample",
          "version": "1.0.0",
          "main": "$main",
          "display_name": { "zh": "测试包", "en": "Test" },
          "subpackages": []
        }
    """.trimIndent()

    private fun write(rel: String, text: String) {
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    /** 走真实路径：resolveManifest + adaptManifest，拿转换后的权限表。 */
    private fun permissionsOf(js: String): List<String> {
        write("manifest.json", manifest())
        write("main.js", js)
        val om = OperitCompat.resolveManifest(root)
        assertNotNull("清单解析失败", om)
        return OperitCompat.adaptManifest(root, om!!).permissions
    }

    @Test
    fun `基线只有 filesystem`() {
        val perms = permissionsOf("function registerToolPkg(){ return true; }")
        assertTrue(perms.contains("filesystem"))
        assertFalse("没调钩子 API 不该给 hook", perms.contains("hook"))
        assertFalse("没用到网络不该给 http", perms.contains("http"))
    }

    @Test
    fun `调用 registerAppLifecycleHook 才授予 hook`() {
        val perms = permissionsOf(
            """
            function registerToolPkg() {
                ToolPkg.registerAppLifecycleHook({ id: 'x', event: 'application_on_create', function: function(){} });
                return true;
            }
            """.trimIndent()
        )
        assertTrue("调了钩子 API 应给 hook", perms.contains("hook"))
    }

    @Test
    fun `调用工具生命周期钩子也授予 hook`() {
        val perms = permissionsOf(
            "ToolPkg.registerToolLifecycleHook({ id: 't', function: function(){} });"
        )
        assertTrue(perms.contains("hook"))
    }

    @Test
    fun `调用提示词钩子也授予 hook`() {
        assertTrue(permissionsOf("ToolPkg.registerPromptInputHook({id:'p',function:function(){}});").contains("hook"))
        assertTrue(permissionsOf("ToolPkg.registerSystemPromptComposeHook({id:'p',function:function(){}});").contains("hook"))
        assertTrue(permissionsOf("ToolPkg.registerPromptFinalizeHook({id:'p',function:function(){}});").contains("hook"))
    }

    @Test
    fun `用到网络时额外授予 http`() {
        val perms = permissionsOf("Tools.Net.httpGet('https://example.com');")
        assertTrue(perms.contains("http"))
        assertFalse(perms.contains("hook"))
    }

    @Test
    fun `同时用网络和钩子时两个都给`() {
        val perms = permissionsOf(
            "Tools.Net.httpGet('https://example.com');\n" +
                "ToolPkg.registerAppLifecycleHook({id:'a',event:'application_on_create',function:function(){}});"
        )
        assertTrue(perms.contains("http"))
        assertTrue(perms.contains("hook"))
    }

    @Test
    fun `钩子权限也能从子目录的 js 文件里识别`() {
        write("manifest.json", manifest())
        write("main.js", "function registerToolPkg(){ return true; }")
        write("packages/sub.js", "ToolPkg.registerAppLifecycleHook({id:'a',event:'application_on_create',function:function(){}});")

        val om = OperitCompat.resolveManifest(root)
        assertNotNull(om)
        val perms = OperitCompat.adaptManifest(root, om!!).permissions
        assertTrue("子包里的钩子调用也要算", perms.contains("hook"))
    }
}
