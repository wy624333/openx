package com.wy.openx.data.provider

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wy.openx.data.model.ModelMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义模型元数据的本地持久化存储。key 为「提供商ID:模型名」复合键；优先级高于拉取/内置元数据。
 *
 * **必须存 filesDir，不能存 cacheDir**：cacheDir 会被系统在存储紧张时清理、部分 ROM 在应用被
 * 杀死/重启后也会清空缓存。此前存 cacheDir，导致用户配置的窗口/能力在退出应用后丢失
 * （表现为「配置了模型能力/上下文，退出再回来就没了」）。filesDir 属于应用私有数据，不会被
 * 系统自动清理，也会随「备份数据」一并保留。
 */
class CustomModelMetadataStore(private val context: Context) {

    suspend fun all(): Map<String, ModelMetadata> = withContext(Dispatchers.IO) { read() }

    suspend fun get(providerId: String, model: String): ModelMetadata? =
        all()[key(providerId, model)]

    suspend fun put(providerId: String, model: String, metadata: ModelMetadata) = withContext(Dispatchers.IO) {
        val map = read().toMutableMap()
        map[key(providerId, model)] = metadata
        write(map)
    }

    suspend fun remove(providerId: String, model: String) = withContext(Dispatchers.IO) {
        val map = read().toMutableMap()
        if (map.remove(key(providerId, model)) != null) {
            write(map)
        }
    }

    private fun key(providerId: String, model: String): String = "$providerId:$model"

    private fun read(): Map<String, ModelMetadata> {
        val f = file()
        if (!f.isFile) {
            // 旧版本存在 cacheDir，尝试迁移一次（迁移成功即写入 filesDir 并删旧文件）。
            val legacy = legacyFile()
            if (legacy.isFile) {
                val migrated = parse(legacy)
                if (migrated.isNotEmpty()) {
                    write(migrated)
                    runCatching { legacy.delete() }
                }
                return migrated
            }
            return emptyMap()
        }
        return parse(f)
    }

    private fun parse(f: File): Map<String, ModelMetadata> = runCatching {
        val type = object : TypeToken<Map<String, ModelMetadata>>() {}.type
        Gson().fromJson<Map<String, ModelMetadata>>(f.readText(Charsets.UTF_8), type) ?: emptyMap()
    }.getOrElse { emptyMap() }

    private fun write(map: Map<String, ModelMetadata>) {
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(Gson().toJson(map), Charsets.UTF_8)
        }
    }

    private fun file(): File = File(File(context.filesDir, SUBDIR), FILE_NAME)

    /** 旧版本所在的 cacheDir 路径（仅用于一次性迁移）。 */
    private fun legacyFile(): File = File(context.cacheDir, FILE_NAME)

    companion object {
        const val SUBDIR = "openx"
        const val FILE_NAME = "custom-model-metadata.json"
    }
}
