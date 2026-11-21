package com.wy.openx.remote

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一条已保存的登录私钥。
 *
 * [content] 是私钥正文（PEM/OpenSSH 格式），[passphrase] 是加密私钥的口令。
 * 两者都只在内存里是明文，落盘一律走 [RemoteSecretBox] 加密——
 * 私钥泄露等于把整台服务器交出去。
 */
data class SavedSshKey(
    val id: String,
    val name: String,
    /** 私钥正文；内存里是明文。 */
    val content: String = "",
    /** 口令（可选）；内存里是明文。 */
    val passphrase: String = "",
    val createdAt: Long = 0,
)

/**
 * 已保存登录密钥的持久化。
 *
 * 与连接配置分开存：密钥是**可复用资产**（一把私钥可能连多台机器），
 * 混在 `remote_servers.json` 里删连接时容易连带删掉别人还在用的密钥。
 */
class RemoteKeyStore(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, "remote_keys.json") }
    private val mutex = Mutex()
    private val gson = Gson()

    private val _keys = MutableStateFlow<List<SavedSshKey>>(emptyList())
    val keys: StateFlow<List<SavedSshKey>> = _keys.asStateFlow()

    suspend fun load(): List<SavedSshKey> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = readFromDisk()
            _keys.value = list
            list
        }
    }

    suspend fun save(key: SavedSshKey) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _keys.value
            val next = if (current.any { it.id == key.id }) {
                current.map { if (it.id == key.id) key else it }
            } else {
                current + key
            }
            writeToDisk(next)
            _keys.value = next
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = _keys.value.filterNot { it.id == id }
            writeToDisk(next)
            _keys.value = next
        }
    }

    fun find(id: String): SavedSshKey? = _keys.value.firstOrNull { it.id == id }

    private fun readFromDisk(): List<SavedSshKey> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.getAsJsonArray("keys")?.mapNotNull { element ->
                val obj = element.asJsonObject
                SavedSshKey(
                    id = obj.get("id")?.asString ?: return@mapNotNull null,
                    name = obj.get("name")?.asString.orEmpty(),
                    content = RemoteSecretBox.decrypt(obj.get("content")?.asString.orEmpty()),
                    passphrase = RemoteSecretBox.decrypt(obj.get("passphrase")?.asString.orEmpty()),
                    createdAt = obj.get("createdAt")?.asLong ?: 0L,
                )
            }.orEmpty()
        }.onFailure {
            FileLogger.e("RemoteKeyStore", "读取登录密钥失败，按空列表处理", it)
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(list: List<SavedSshKey>) {
        runCatching {
            val root = JsonObject()
            val array = com.google.gson.JsonArray()
            list.forEach { k ->
                array.add(JsonObject().apply {
                    addProperty("id", k.id)
                    addProperty("name", k.name)
                    addProperty("content", RemoteSecretBox.encrypt(k.content))
                    addProperty("passphrase", RemoteSecretBox.encrypt(k.passphrase))
                    addProperty("createdAt", k.createdAt)
                })
            }
            root.add("keys", array)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(root))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) error("登录密钥写入失败")
            }
        }.onFailure { FileLogger.e("RemoteKeyStore", "写入登录密钥失败", it) }
    }
}
