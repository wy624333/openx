package com.wy.openx.remote

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
 * 远程服务器配置的持久化。
 *
 * 用单个 JSON 文件而不是 Room：条目量级是个位数到几十，为它加一张表 + 迁移不值当，
 * 而 DataStore 存 JSON 串又要在读写时反复解析。文件写入走「临时文件 + 原子改名」，
 * 避免写到一半掉电留下半截 JSON 把整份配置读废。
 *
 * **凭据单独加密**：`authData` / `passphrase` 经 [RemoteSecretBox] 加密后再落盘，
 * 文件里出现的是密文。所以文件不能直接手改（改了对不上），要改走 UI。
 */
class RemoteStore(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, "remote_servers.json") }
    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _connections = MutableStateFlow<List<RemoteConnection>>(emptyList())
    val connections: StateFlow<List<RemoteConnection>> = _connections.asStateFlow()

    private val _mounts = MutableStateFlow<List<RemoteMount>>(emptyList())
    val mounts: StateFlow<List<RemoteMount>> = _mounts.asStateFlow()

    /** 从磁盘载入一次；重复调用无副作用。 */
    suspend fun load(): List<RemoteConnection> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (list, mountList) = readFromDisk()
            _connections.value = list
            _mounts.value = mountList
            list
        }
    }

    /** 新增或更新（按 id 匹配）。 */
    suspend fun upsert(connection: RemoteConnection) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _connections.value
            val next = if (current.any { it.id == connection.id }) {
                current.map { if (it.id == connection.id) connection else it }
            } else {
                current + connection.copy(sortOrder = connection.sortOrder.takeIf { it > 0 } ?: current.size)
            }
            writeToDisk(next)
            _connections.value = next
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = _connections.value.filterNot { it.id == id }
            // 删连接时连带删掉它的挂载：留着孤立挂载只会在列表里显示「连接已不存在」
            val nextMounts = _mounts.value.filterNot { it.connectionId == id }
            writeToDisk(next, nextMounts)
            _connections.value = next
            _mounts.value = nextMounts
        }
    }

    /** 新增或更新挂载。 */
    suspend fun upsertMount(mount: RemoteMount) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _mounts.value
            val next = if (current.any { it.id == mount.id }) {
                current.map { if (it.id == mount.id) mount else it }
            } else {
                current + mount.copy(sortOrder = mount.sortOrder.takeIf { it > 0 } ?: current.size)
            }
            writeToDisk(_connections.value, next)
            _mounts.value = next
        }
    }

    suspend fun deleteMount(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = _mounts.value.filterNot { it.id == id }
            writeToDisk(_connections.value, next)
            _mounts.value = next
        }
    }

    /** 持久化新的排序（拖拽后调用）。 */
    suspend fun reorderMounts(orderedIds: List<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val byId = _mounts.value.associateBy { it.id }
            val next = orderedIds.mapNotNull { id -> byId[id]?.copy(sortOrder = orderedIds.indexOf(id)) }
            if (next.isEmpty()) return@withContext
            writeToDisk(_connections.value, next)
            _mounts.value = next
        }
    }

    fun find(id: String): RemoteConnection? = _connections.value.firstOrNull { it.id == id }

    fun findMount(id: String): RemoteMount? = _mounts.value.firstOrNull { it.id == id }

    private fun readFromDisk(): Pair<List<RemoteConnection>, List<RemoteMount>> {
        if (!file.isFile) return emptyList<RemoteConnection>() to emptyList()
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val connections = root.getAsJsonArray("connections")?.mapNotNull { element ->
                val obj = element.asJsonObject
                val authType = runCatching {
                    RemoteAuthType.valueOf(obj.get("authType")?.asString ?: "PASSWORD")
                }.getOrDefault(RemoteAuthType.PASSWORD)
                val protocol = runCatching {
                    RemoteProtocol.valueOf(obj.get("protocol")?.asString ?: "SFTP")
                }.getOrDefault(RemoteProtocol.SFTP)
                RemoteConnection(
                    id = obj.get("id")?.asString ?: return@mapNotNull null,
                    name = obj.get("name")?.asString.orEmpty(),
                    protocol = protocol,
                    host = obj.get("host")?.asString.orEmpty(),
                    port = obj.get("port")?.asInt ?: RemoteConnection.defaultPort(protocol),
                    username = obj.get("username")?.asString.orEmpty(),
                    authType = authType,
                    // 存的是密文，读出来立刻解密；解不开（换了设备/清了数据）就当空凭据，UI 提示重填
                    authData = RemoteSecretBox.decrypt(obj.get("authData")?.asString.orEmpty()),
                    passphrase = RemoteSecretBox.decrypt(obj.get("passphrase")?.asString.orEmpty()),
                    remotePath = obj.get("remotePath")?.asString.orEmpty(),
                    // 不落盘的话，重启后连接就丢了「用哪把已保存密钥」，只剩 authData 里的旧路径，
                    // 用户得回编辑器重选一次——而界面上的选项看着已经是选中的样子
                    savedKeyId = obj.get("savedKeyId")?.asString,
                    allowAiExec = obj.get("allowAiExec")?.asBoolean ?: false,
                    sortOrder = obj.get("sortOrder")?.asInt ?: 0,
                )
            }.orEmpty()
            val mounts = root.getAsJsonArray("mounts")?.mapNotNull { element ->
                val obj = element.asJsonObject
                RemoteMount(
                    id = obj.get("id")?.asString ?: return@mapNotNull null,
                    connectionId = obj.get("connectionId")?.asString ?: return@mapNotNull null,
                    remotePath = obj.get("remotePath")?.asString.orEmpty(),
                    localMountPath = obj.get("localMountPath")?.asString.orEmpty(),
                    autoConnect = obj.get("autoConnect")?.asBoolean ?: true,
                    sourceOnServer = obj.get("sourceOnServer")?.asBoolean ?: false,
                    sortOrder = obj.get("sortOrder")?.asInt ?: 0,
                )
            }.orEmpty()
            connections to mounts
        }.onFailure {
            FileLogger.e("RemoteStore", "读取远程配置失败，按空列表处理", it)
        }.getOrDefault(emptyList<RemoteConnection>() to emptyList())
    }

    private fun writeToDisk(list: List<RemoteConnection>, mounts: List<RemoteMount> = _mounts.value) {
        runCatching {
            val root = JsonObject()
            val array = com.google.gson.JsonArray()
            list.sortedBy { it.sortOrder }.forEach { c ->
                array.add(JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                    addProperty("protocol", c.protocol.name)
                    addProperty("host", c.host)
                    addProperty("port", c.port)
                    addProperty("username", c.username)
                    addProperty("authType", c.authType.name)
                    addProperty("authData", RemoteSecretBox.encrypt(c.authData))
                    addProperty("passphrase", RemoteSecretBox.encrypt(c.passphrase))
                    addProperty("remotePath", c.remotePath)
                    c.savedKeyId?.let { addProperty("savedKeyId", it) }
                    addProperty("allowAiExec", c.allowAiExec)
                    addProperty("sortOrder", c.sortOrder)
                })
            }
            root.add("connections", array)
            val mountArray = com.google.gson.JsonArray()
            mounts.sortedBy { it.sortOrder }.forEach { m ->
                mountArray.add(JsonObject().apply {
                    addProperty("id", m.id)
                    addProperty("connectionId", m.connectionId)
                    addProperty("remotePath", m.remotePath)
                    addProperty("localMountPath", m.localMountPath)
                    addProperty("autoConnect", m.autoConnect)
                    addProperty("sourceOnServer", m.sourceOnServer)
                    addProperty("sortOrder", m.sortOrder)
                })
            }
            root.add("mounts", mountArray)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(root))
            if (!tmp.renameTo(file)) {
                // renameTo 在部分文件系统上不覆盖已有文件，先删再试一次
                file.delete()
                if (!tmp.renameTo(file)) error("远程配置写入失败")
            }
        }.onFailure { FileLogger.e("RemoteStore", "写入远程配置失败", it) }
    }
}
