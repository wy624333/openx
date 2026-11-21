package com.wy.openx.remote

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 远程工作区协议。 */
enum class RemoteProtocol { SFTP, FTP }

/** 认证方式。 */
enum class RemoteAuthType { PASSWORD, PRIVATE_KEY }

/**
 * 一条远程服务器配置。
 *
 * 凭据（密码 / 私钥路径 / 私钥口令）与配置存在一起，但**不落明文日志**：
 * 序列化时脱敏由 [RemoteStore] 负责，这里只做数据结构。
 */
data class RemoteConnection(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol = RemoteProtocol.SFTP,
    val host: String,
    val port: Int = 22,
    val username: String = "",
    val authType: RemoteAuthType = RemoteAuthType.PASSWORD,
    /** 密码（authType=PASSWORD）或私钥文件路径（authType=PRIVATE_KEY）。 */
    val authData: String = "",
    /** 私钥口令（加密私钥才需要）。 */
    val passphrase: String = "",
    /**
     * 已保存的登录密钥 id（见 [SavedSshKey]）。
     *
     * 非空时优先用它：密钥正文存在密钥库，连接配置里只留 id，
     * 这样一把私钥能被多条连接共用，改密钥不用逐条改连接。
     */
    val savedKeyId: String? = null,
    /** 初始进入的远程目录；空表示登录后的 home。 */
    val remotePath: String = "",
    /**
     * 允许 AI 在这台服务器上执行命令。
     *
     * 默认关闭，且必须**逐台**开：本地容器是可以随时重建的沙盒，远端是真机器，
     * 一条 `rm -rf` 的代价完全不同。默认给 AI 开这个能力，等于把「不可逆」当成默认值。
     */
    val allowAiExec: Boolean = false,
    /** 用户排序，越小越靠前。 */
    val sortOrder: Int = 0,
) {
    /** 列表副标题：`user@host:port`。 */
    val endpoint: String get() = "${username.ifBlank { "?" }}@$host:$port"

    /** 新建时的默认端口：SFTP 22，FTP 21。 */
    companion object {
        fun defaultPort(protocol: RemoteProtocol): Int = if (protocol == RemoteProtocol.SFTP) 22 else 21
    }
}

/** 连接当前状态，供列表与浏览页显示。 */
enum class RemoteConnState { IDLE, CONNECTING, CONNECTED, FAILED }

/** 当前连接的可观察状态，供聊天页顶栏与连接卡片显示。 */
data class RemoteActiveState(
    val connectionId: String? = null,
    val state: RemoteConnState = RemoteConnState.IDLE,
)

/** 远程目录项。[isDirectory] 决定点击是进入还是预览。 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    /** 最后修改时间（毫秒）；远端未提供时为 0。 */
    val lastModified: Long = 0,
    /** chmod 字符串（如 `-rw-r--r--`）；远端未提供时为空串。 */
    val permissions: String = "",
)

/** 单个远程路径的属性（[RemoteEntry] 的「只要这一项」版本）。 */
data class RemoteStat(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String,
)

/**
 * 一条挂载：把远端目录与本地工作区目录绑起来，双向同步。
 *
 * [localMountPath] 是容器内路径（如 `/root/workspace/projects/demo`），
 * [remotePath] 是远端绝对路径。两边都以「相对挂载根的路径」作为索引 key，
 * 这样本地路径改了索引仍可复用。
 */
data class RemoteMount(
    val id: String,
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    /** 建立连接后是否自动开始同步。 */
    val autoConnect: Boolean = true,
    /**
     * 服务器为准（远程源码模式）：本工作区的源码以远端为唯一真实副本。
     *
     * 开启后：
     * - 读写/搜索/编辑器/终端均直接作用于服务器，本地那份副本停用（只作历史参考）；
     * - **同步自动停止**——既然只有服务器一份，往哪个方向传都是错的。
     *
     * 默认关：这是个会改变本工作区行为语义的开关，不能让用户不知情地进进去。
     */
    val sourceOnServer: Boolean = false,
    val sortOrder: Int = 0,
)

object RemoteTrafficMonitor {
    data class Snapshot(val uploaded: Long = 0L, val downloaded: Long = 0L)

    private val uploadedBytes = AtomicLong()
    private val downloadedBytes = AtomicLong()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun addUploaded(bytes: Long) {
        if (bytes <= 0) return
        uploadedBytes.addAndGet(bytes)
        publish()
    }

    fun addDownloaded(bytes: Long) {
        if (bytes <= 0) return
        downloadedBytes.addAndGet(bytes)
        publish()
    }

    fun reset() {
        uploadedBytes.set(0)
        downloadedBytes.set(0)
        publish()
    }

    fun format(bytes: Long): String = when {
        bytes < 1024L -> "0kb"
        bytes < 1024L * 1024L -> "${bytes / 1024L}kb"
        else -> String.format(Locale.US, "%.1fmb", bytes / (1024.0 * 1024.0))
    }

    private fun publish() {
        _state.value = Snapshot(uploadedBytes.get(), downloadedBytes.get())
    }
}
