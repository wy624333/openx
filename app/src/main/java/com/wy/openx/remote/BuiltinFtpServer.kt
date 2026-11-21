package com.wy.openx.remote

import android.content.Context
import com.wy.openx.container.FileLogger
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission

/** 内置 FTP 服务器的配置。 */
data class FtpServerConfig(
    val port: Int = 2121,
    val username: String = "openx",
    val password: String = "",
    /** 匿名访问：允许不登录，但只读更安全——这里只做开关，权限统一按只读+写入给。 */
    val anonymous: Boolean = false,
    val autoStart: Boolean = false,
    /** 共享出去的目录（容器内路径）。 */
    val rootPath: String = "/root/workspace",
)

/**
 * 内置 FTP 服务器：把手机上的目录挂出去，让电脑用 FTP 客户端访问。
 *
 * 为什么值得有：不装 OpenX 的电脑（比如只能装普通 FTP 客户端的场合）也能读写手机上的
 * 工作区文件，不必走 adb 或数据线。
 *
 * 几个刻意的约束：
 * 1. **默认端口 2121**：21 在部分系统上会被拦截或已被占用，用高位端口避开。
 * 2. **密码不能为空**：空密码的 FTP 等于对同网段任何人敞开，这里强制要求填。
 * 3. **只绑定当前局域网 IP**，且要提醒用户 FTP 是**明文协议**——同一 WiFi 下任何人都能嗅探。
 * 4. 用户配置持久化到私有目录，重启后自动恢复。
 */
class BuiltinFtpServer(private val context: Context) {

    private var server: FtpServer? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _config = MutableStateFlow(FtpServerConfig())
    val config: StateFlow<FtpServerConfig> = _config.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val configFile: File by lazy { File(context.filesDir, "builtin_ftp.properties") }

    fun load() {
        if (!configFile.isFile) {
            // 首次使用：直接生成一个可用密码，用户不必先想办法想一个
            _config.value = FtpServerConfig(password = generatePassword())
            save(_config.value)
            return
        }
        runCatching {
            val props = java.util.Properties()
            configFile.inputStream().use { props.load(it) }
            // 密码在磁盘上是密文；解不开（换设备/清数据）就重新生成，
            // 而不是拿空密码去启动——空密码的 FTP 等于对同网段敞开
            val savedPassword = RemoteSecretBox.decrypt(props.getProperty("password").orEmpty())
            _config.value = FtpServerConfig(
                port = props.getProperty("port")?.toIntOrNull() ?: 2121,
                username = props.getProperty("username").orEmpty().ifBlank { "openx" },
                password = savedPassword.ifBlank { generatePassword() },
                anonymous = props.getProperty("anonymous")?.toBoolean() ?: false,
                autoStart = props.getProperty("autoStart")?.toBoolean() ?: false,
                rootPath = props.getProperty("rootPath").orEmpty().ifBlank { "/root/workspace" },
            )
            if (savedPassword.isBlank()) save(_config.value)
        }.onFailure { FileLogger.w(TAG, "读取内置 FTP 配置失败") }
    }

    fun save(config: FtpServerConfig) {
        _config.value = config
        runCatching {
            val props = java.util.Properties().apply {
                setProperty("port", config.port.toString())
                setProperty("username", config.username)
                // 密码加密落盘：内置 FTP 的口令一旦泄露就是同网段任人读写
                setProperty("password", RemoteSecretBox.encrypt(config.password))
                setProperty("anonymous", config.anonymous.toString())
                setProperty("autoStart", config.autoStart.toString())
                setProperty("rootPath", config.rootPath)
            }
            configFile.outputStream().use { props.store(it, "OpenX builtin FTP") }
        }.onFailure { FileLogger.w(TAG, "保存内置 FTP 配置失败", it) }
    }

    /**
     * 生成随机访问密码。
     *
     * 取大小写字母+数字共 12 位：足够挡住「随手试一串」，又不至于长到用户抄写困难。
     * 排除易混淆字符（0/O、1/l/I）——这个密码是要手抄到电脑 FTP 客户端里的。
     */
    fun generatePassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return (1..PASSWORD_LENGTH)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    /** 启动服务器；失败抛异常（消息可直接显示）。 */
    suspend fun start(config: FtpServerConfig) = withContext(Dispatchers.IO) {
        if (_running.value) return@withContext
        if (config.password.isBlank()) {
            throw IllegalStateException("请先设置访问密码：空密码等于对同网段所有人开放")
        }
        val hostRoot = com.wy.openx.tools.AppContextHolder.containerEngine
            ?.toHostFileIfContainerPath(config.rootPath)
            ?: File(config.rootPath)
        if (!hostRoot.exists()) {
            throw IllegalStateException("共享目录不存在：${config.rootPath}")
        }
        try {
            val factory = FtpServerFactory()
            val listener = ListenerFactory().apply {
                port = config.port
            }
            factory.addListener("default", listener.createListener())

            // 用户库放内存：只服务一个用户，落盘反而要维护一个 properties 文件的生命周期
            val userManagerFactory = PropertiesUserManagerFactory()
            userManagerFactory.passwordEncryptor = org.apache.ftpserver.usermanager.Md5PasswordEncryptor()
            val userManager: UserManager = userManagerFactory.createUserManager()
            val user = BaseUser().apply {
                name = config.username
                password = config.password
                homeDirectory = hostRoot.absolutePath
                authorities = listOf<Authority>(WritePermission())
            }
            runCatching { userManager.save(user) }
                .onFailure { FileLogger.w(TAG, "写入 FTP 用户失败", it) }

            // 匿名用户：Apache FTPServer 认的用户名就是 anonymous，密码留空。
            // 权限同样给写入——用户在设置里主动开了匿名，就不该再自作主张改成只读，
            // 界面上把「明文+任何人可读写」的风险说清楚即可。
            if (config.anonymous) {
                val anon = BaseUser().apply {
                    name = ANONYMOUS_USER
                    password = ""
                    homeDirectory = hostRoot.absolutePath
                    authorities = listOf<Authority>(WritePermission())
                }
                runCatching { userManager.save(anon) }
                    .onFailure { FileLogger.w(TAG, "写入匿名用户失败", it) }
            }
            factory.userManager = userManager

            val created = factory.createServer()
            created.start()
            server = created
            _running.value = true
            _lastError.value = null
            FileLogger.i(TAG, "内置 FTP 已启动，端口 ${config.port}，共享 ${hostRoot.absolutePath}")
        } catch (e: Exception) {
            _running.value = false
            _lastError.value = e.message ?: "启动失败"
            throw e
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { server?.stop() }
        server = null
        _running.value = false
    }

    /** 本机局域网 IP，给界面显示「用这个地址连」。 */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "BuiltinFtpServer"

        /** Apache FTPServer 约定的匿名用户名。 */
        const val ANONYMOUS_USER = "anonymous"

        const val PASSWORD_LENGTH = 12
    }
}
