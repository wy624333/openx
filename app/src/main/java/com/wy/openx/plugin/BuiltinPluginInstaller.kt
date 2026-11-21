package com.wy.openx.plugin

import android.content.Context
import com.wy.openx.container.FileLogger
import java.io.File

/**
 * 内置插件的释放与清理。
 *
 * 行为：
 *   1. **释放**：把 APK 内 `assets/plugins/<id>/` 复制到 `filesDir/openx/plugins/<id>/`，
 *      仅在版本戳变化时执行（用户改过/删过的不被每次启动覆盖）。
 *
 * **不删任何东西**：内置插件被移除后，用户机器上的旧目录保留，由用户自行删除
 * （体验上更可控，也避免误删风险）。
 */
object BuiltinPluginInstaller {

    private const val TAG = "BuiltinPluginInstaller"
    private const val ASSET_ROOT = "plugins"
    private const val STAMP = ".installed"

    /**
     * 同步内置插件。
     * @return 本次实际安装/更新的插件目录名列表
     */
    fun install(context: Context, store: PluginStore): List<String> {
        val assets = runCatching { context.assets.list(ASSET_ROOT)?.toList().orEmpty() }
            .getOrElse { emptyList() }
            .filter { it != "_shared" } // _shared 是宿主脚本目录，不是插件

        val done = mutableListOf<String>()

        for (dirName in assets) {
            val manifestJson = runCatching {
                context.assets.open("$ASSET_ROOT/$dirName/plugin.json")
                    .bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            val version = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
                .find(manifestJson)?.groupValues?.getOrNull(1).orEmpty()

            val target = File(store.pluginsRoot, dirName)
            val stamp = File(target, STAMP)
            if (stamp.isFile && stamp.readText().trim() == version) continue

            target.mkdirs()
            copyAssetDir(context, "$ASSET_ROOT/$dirName", target)
            stamp.writeText(version)
            done += dirName
            FileLogger.i(TAG, "内置插件 $dirName@$version 已释放 → ${target.absolutePath}")
        }

        return done
    }

    /**
     * 递归复制 assets 目录到本地。
     *
     * `assets.list(path)` 只返回当前层的条目名：目录会返回名字、文件会返回文件名，
     * 但**不会返回深层文件**。所以必须自己递归下去，否则像 `ui/panel.ui.js` 这种
     * 子目录里的文件会被整个漏掉。
     */
    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = runCatching { context.assets.list(assetPath).orEmpty().toList() }
            .getOrDefault(emptyList())
        if (children.isEmpty()) {
            // 空列表 ⇒ 这是一个文件，不是目录
            copyAssetFile(context, assetPath, target)
            return
        }
        target.mkdirs()
        for (name in children) {
            copyAssetDir(context, "$assetPath/$name", File(target, name))
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { FileLogger.e(TAG, "释放 $assetPath 失败: ${it.message}") }
    }
}
