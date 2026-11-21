package com.wy.openx.remote

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程工作区的进程级持有者。
 *
 * 与 VoiceEngines 同一模式：不引依赖注入框架，用 applicationContext 建一次。
 * 必须由 `Application.onCreate` 先 [init]，早于它调用是 bug，直接报错而不是静默兜底。
 */
object RemoteEngines {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 缓存目录（远程预览副本等）。 */
    val cacheDir: File
        get() = (appContext ?: error("RemoteEngines.init() 未在 Application.onCreate 中调用")).cacheDir

    val manager: RemoteManager by lazy {
        RemoteManager(appContext ?: error("RemoteEngines.init() 未在 Application.onCreate 中调用"))
    }

    /** 内置 FTP 服务器（单独实例：它不跟远程客户端连接共享任何状态）。 */
    val ftp: BuiltinFtpServer by lazy {
        BuiltinFtpServer(appContext ?: error("RemoteEngines.init() 未在 Application.onCreate 中调用"))
    }

    private val clients = ConcurrentHashMap<String, RemoteSshClient>()

    /**
     * 取某条连接专用的 SSH 客户端（进程级缓存，每条连接一条）。
     *
     * 不复用 [RemoteManager] 那条：那条代表「用户当前正在浏览的连接」，
     * 而文件路由是按路径随时切服务器的——借它会把用户的浏览会话顶掉。
     */
    internal fun clientFor(connectionId: String): RemoteSshClient = clients.getOrPut(connectionId) {
        val ctx = appContext ?: error("RemoteEngines.init() 未在 Application.onCreate 中调用")
        RemoteSshClient(ctx, RemoteKeyStore(ctx))
    }

    /** 连接被删除时释放它那条客户端，别把 SSH 通道留着占资源。 */
    internal fun forgetClient(connectionId: String) {
        val client = clients.remove(connectionId) ?: return
        // 断开走网络，不能在主线程同步做（删连接是在 UI 线程触发的）
        Thread({ runCatching { client.disconnectBlocking() } }, "RemoteClientClose").start()
    }
}
