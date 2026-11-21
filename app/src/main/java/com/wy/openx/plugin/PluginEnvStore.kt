package com.wy.openx.plugin

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 插件 env 的落盘位置与读写。
 *
 * 文件是 `filesDir/openx/pluginEnv/<pluginId>.json`，一个扁平的 `{key: value}` 对象。
 * 现在有三处读写它，**必须共用同一份文件**，否则会出现「面板里改的配置服务读不到」：
 *  - UI 面板桥（`ui/PluginUiHostBridge`）：面板里 `ctx.setEnv` 写的那份
 *  - 服务桥（`PluginHostActionsBridge`）：服务脚本 `ctx.getEnv` 读的同一份
 *  - 宿主「插件配置」页：渲染清单声明的 `settings`，值也写这里
 *
 * 所以路径收在这里一处，几个桥都来 delegate，别再各自拼一遍。
 */
object PluginEnvStore {

    private val gson = Gson()
    private val lock = Any()

    fun file(context: Context, pluginId: String): File =
        File(File(File(context.filesDir, "openx"), "pluginEnv"), "$pluginId.json")

    /**
     * 读成字符串映射。
     *
     * env 里的值按约定都是字符串，但插件可能塞进数字/布尔（JS 侧没做转换），
     * 这里统一 `toString()`，避免读出来是 `1.0` 这种带小数点的怪值之外还崩掉。
     * 文件不存在或内容坏掉都返回空表，不抛。
     */
    fun read(context: Context, pluginId: String): Map<String, String> = synchronized(lock) {
        runCatching {
            val f = file(context, pluginId)
            if (!f.isFile) {
                emptyMap()
            } else {
                val raw = gson.fromJson(f.readText(), Map::class.java)
                raw.entries.associate { (k, v) -> k.toString() to (v?.toString() ?: "") }
            }
        }.getOrDefault(emptyMap())
    }

    fun write(context: Context, pluginId: String, values: Map<String, String>) {
        synchronized(lock) {
            runCatching {
                val f = file(context, pluginId)
                f.parentFile?.mkdirs()
                f.writeText(gson.toJson(values))
            }
        }
    }
}
