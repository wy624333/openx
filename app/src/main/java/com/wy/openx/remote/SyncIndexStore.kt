package com.wy.openx.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wy.openx.container.FileLogger
import java.io.File
import java.security.MessageDigest

/**
 * 单个文件上次成功同步后的指纹。
 *
 * 远端 SFTP/FTP 拿不到内容哈希，所以「这个文件要不要重传」只能靠本地记录的指纹比对
 * （rsync 的 quick-check 思路）：size + mtime 一致就跳过，不一致再比内容哈希。
 */
data class FileFingerprint(
    val size: Long,
    val mtimeMs: Long,
    /** 内容 SHA-256；为空表示尚未计算（只有「同尺寸改写」时才补算，避免每次上传都全量读盘）。 */
    val sha256: String? = null,
)

/**
 * 索引所属的远端身份。
 *
 * 同一挂载换了远端目录或换了服务器后，旧指纹不再代表当前远端内容；
 * 只按本地相对路径做 key 会让新远端被误判「未变」而漏传，所以索引必须绑定远端身份。
 */
data class SyncIndexIdentity(
    val connectionId: String,
    val host: String,
    val remotePath: String,
)

/**
 * 单文件是否需要上传的纯判定逻辑（与网络、磁盘副作用解耦）。
 *
 * **保守原则：任何不确定都判 UPLOAD**，绝不误跳过导致远端缺文件。
 */
object SyncDecision {

    enum class Action { UPLOAD, SKIP_UNCHANGED }

    /**
     * @param stored 上次成功同步的指纹；null 表示无记录
     * @param size 当前本地文件大小
     * @param mtime 当前本地文件修改时间
     * @param currentSha 惰性计算当前文件 SHA-256 的函数；只在「尺寸相同但 mtime 不同」时才调用
     */
    fun decide(
        stored: FileFingerprint?,
        size: Long,
        mtime: Long,
        currentSha: () -> String?,
    ): Action {
        if (stored == null) return Action.UPLOAD
        if (stored.size != size) return Action.UPLOAD
        // size + mtime 都一致：quick-check 命中，跳过（不读文件内容）
        if (stored.mtimeMs == mtime) return Action.SKIP_UNCHANGED
        // 尺寸相同、mtime 不同：可能是「改了又改回来」，用内容哈希兜底
        val oldSha = stored.sha256 ?: return Action.UPLOAD
        val newSha = currentSha() ?: return Action.UPLOAD
        return if (oldSha == newSha) Action.SKIP_UNCHANGED else Action.UPLOAD
    }
}

/**
 * 同步指纹索引的持久化：每个挂载一个 JSON 文件。
 *
 * 用相对挂载根的路径作 key（本地路径改了索引仍可复用）；写入走「临时文件 + 原子改名」，
 * 避免写一半崩溃把索引弄坏；读取失败按空索引处理，退化为全量上传而不是漏传。
 */
class SyncIndexStore(private val dir: File) {

    private val gson = Gson()

    private fun fileOf(mountId: String): File = File(dir, "$mountId.json")

    /**
     * 读取索引；文件不存在、损坏或 [identity] 与记录不符时返回空（视为全部需要上传，保守）。
     * 身份不符也一律作废，而不是复用可能指向别处的指纹。
     */
    fun load(mountId: String, identity: SyncIndexIdentity): Map<String, FileFingerprint> {
        val file = fileOf(mountId)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = gson.fromJson(file.readText(), JsonObject::class.java)
            val savedIdentity = root.getAsJsonObject("identity")?.let {
                SyncIndexIdentity(
                    connectionId = it.get("connectionId")?.asString.orEmpty(),
                    host = it.get("host")?.asString.orEmpty(),
                    remotePath = it.get("remotePath")?.asString.orEmpty(),
                )
            }
            if (savedIdentity != identity) return emptyMap()
            val map = root.getAsJsonObject("fingerprints") ?: return emptyMap()
            map.entrySet().mapNotNull { (key, value) ->
                val obj = value.asJsonObject
                val size = obj.get("size")?.asLong ?: return@mapNotNull null
                val mtime = obj.get("mtimeMs")?.asLong ?: return@mapNotNull null
                key to FileFingerprint(size, mtime, obj.get("sha256")?.asString)
            }.toMap()
        }.onFailure {
            FileLogger.w(TAG, "读取同步索引失败，按空索引处理（将全量上传）: ${file.name}")
        }.getOrDefault(emptyMap())
    }

    fun save(mountId: String, identity: SyncIndexIdentity, fingerprints: Map<String, FileFingerprint>) {
        runCatching {
            dir.mkdirs()
            val root = JsonObject().apply {
                add("identity", JsonObject().apply {
                    addProperty("connectionId", identity.connectionId)
                    addProperty("host", identity.host)
                    addProperty("remotePath", identity.remotePath)
                })
                add("fingerprints", JsonObject().apply {
                    fingerprints.forEach { (key, fp) ->
                        add(key, JsonObject().apply {
                            addProperty("size", fp.size)
                            addProperty("mtimeMs", fp.mtimeMs)
                            fp.sha256?.let { addProperty("sha256", it) }
                        })
                    }
                })
            }
            val payload = gson.toJson(root)
            // 临时名唯一化：并发写同一挂载时互不覆盖，rename 才原子生效
            val tmp = File.createTempFile("$mountId-", ".json.tmp", dir)
            tmp.writeText(payload)
            val target = fileOf(mountId)
            if (!tmp.renameTo(target)) {
                target.writeText(payload)
                tmp.delete()
            }
        }.onFailure { FileLogger.w(TAG, "保存同步索引失败: $mountId") }
    }

    fun clear(mountId: String) {
        runCatching { fileOf(mountId).delete() }
    }

    private companion object {
        const val TAG = "SyncIndexStore"
    }
}

/** 计算文件内容 SHA-256（十六进制小写）；读盘失败返回 null（调用方据此保守上传）。 */
internal fun sha256Of(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) digest.update(buf, 0, n)
        }
    }
    val bytes = digest.digest()
    val hex = StringBuilder(64)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    hex.toString()
}.getOrNull()

private const val HEX = "0123456789abcdef"
