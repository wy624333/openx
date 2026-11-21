package com.wy.openx.git

import android.util.Base64
import com.wy.openx.container.FileLogger
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Git 凭据的文件存储（真源 = `filesDir/openx/git-credentials`，容器内 `/root/.openx/git-credentials`）。
 *
 * 文件用 git-credential-store 标准格式：每行 `https://user:token@host`，每主机一条（同主机覆盖旧值），
 * 由容器内 credential.helper（[GitCredentialHelper]）读取后喂给 git。UI 与 git 共用同一份，
 * 用户在凭据页改完立刻对下一次 push/pull 生效。
 *
 * 落盘时整体 base64 再反转：不是可直接回显的明文，挡住随手 cat / grep 扫描 / AI 读进上下文。
 * 属混淆而非加密——helper 脚本本身就能还原，定位是提高门槛而非防住刻意攻击。
 */
object GitCredentialStore {

    private const val TAG = "GitCredentialStore"
    private const val CREDENTIALS_NAME = "git-credentials"

    private val mutex = Mutex()

    private val _credentials = MutableStateFlow<List<GitCredential>>(emptyList())
    val credentials: StateFlow<List<GitCredential>> = _credentials.asStateFlow()

    private val file: File?
        get() = AppContextHolder.context?.let { File(File(it.filesDir, "openx"), CREDENTIALS_NAME) }

    /** 从磁盘重读。幂等，进凭据页或需要最新值时调用。 */
    suspend fun load() = mutex.withLock {
        _credentials.value = parse(file)
    }

    /** 按 host 查凭据（大小写不敏感）。供注入链路与界面预填使用。 */
    fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return _credentials.value.firstOrNull { it.host == normalized }
    }

    /** 新增或覆盖同主机凭据。 */
    suspend fun save(credential: GitCredential) = mutex.withLock {
        val host = credential.host.trim().lowercase()
        if (host.isEmpty()) return@withLock
        val updated = _credentials.value.filterNot { it.host == host } +
            GitCredential(
                host = host,
                username = credential.username.trim(),
                token = credential.token
            )
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "保存凭据 host=$host user=${credential.username}")
    }

    suspend fun delete(id: String) = mutex.withLock {
        val updated = _credentials.value.filterNot { it.id == id }
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "删除凭据 id=$id")
    }

    /**
     * 把明文 KV（helper 弹窗回填的 username/password）落成凭据。
     *
     * username 为空时留空——部分自建服务只校验 token，username 任意值都行。
     */
    suspend fun saveFromPrompt(host: String, username: String, password: String) =
        save(GitCredential(host = host, username = username, token = password))

    private fun writeFile(credentials: List<GitCredential>) {
        val target = file ?: run {
            FileLogger.w(TAG, "无 Context，跳过写入")
            return
        }
        target.parentFile?.mkdirs()
        val encoded = encode(GitCredentialFormat.serialize(credentials))
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            tmp.writeText(encoded)
            if (!tmp.renameTo(target)) {
                target.writeText(encoded)
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "写入凭据文件失败: ${it.message}") }
    }

    /** 编码：base64 后整体反转。 */
    private fun encode(plain: String): String =
        Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).reversed()

    /**
     * 解码；不是本格式时返回 null。
     *
     * `android.util.Base64` 的 DEFAULT 解码很宽容：非法字符按 SKIP 忽略、不抛异常，明文也能被
     * 「解码」成乱码。故不能用「未抛异常」判格式，改走往返校验 `encode(decode(raw)) == raw`。
     */
    private fun tryDecode(text: String): String? = runCatching {
        val raw = text.trim()
        if (raw.isEmpty()) return@runCatching null
        val decoded = String(Base64.decode(raw.reversed(), Base64.DEFAULT), Charsets.UTF_8)
        if (encode(decoded) != raw) return@runCatching null
        decoded
    }.getOrNull()

    /** 解析凭据文件；解不出编码格式时按旧明文处理。 */
    private fun parse(target: File?): List<GitCredential> {
        if (target == null || !target.isFile) return emptyList()
        val raw = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        return GitCredentialFormat.parse(tryDecode(raw) ?: raw)
    }
}
