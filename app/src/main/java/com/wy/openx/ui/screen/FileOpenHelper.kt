package com.wy.openx.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用外部应用打开一个宿主文件（APK 走系统安装器，其余走各自关联的应用）。
 *
 * ## 为什么需要
 * 侧边栏文件树点文件时，文本/代码进内置编辑器、图片进内置查看器——
 * 但 **APK 这类「在本应用内没有意义」的文件必须有出口**：用户下载了安装包，
 * 唯一想做的是装它。原先一律塞进内置编辑器，APK 会显示成乱码。
 *
 * ## 为什么不能直接用 `file://`
 * Android 7(N) 起，`file://` URI 跨应用传递会抛 `FileUriExposedException`。
 * 必须经 `FileProvider` 转成 `content://` 并显式授予读权限。
 *
 * ## 无法直接授权的文件
 * `FileProvider` 只覆盖 `file_paths.xml` 声明的目录树。落在其它位置
 * （如 SD 卡上的外部工作区）时，先拷进 `cacheDir` 再授权——
 * 大文件会慢，故只在必要时这么做。
 */
object FileOpenHelper {

    /** 可交给系统安装器的安装包扩展名。`apks`/`xapk` 是拆分/封装包，同样由安装器处理。 */
    private val INSTALLABLE_EXTS = setOf("apk", "apks", "xapk")

    private const val MIME_APK = "application/vnd.android.package-archive"

    /** 该文件名是否为安装包（按扩展名判定）。 */
    fun isInstallablePackage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in INSTALLABLE_EXTS

    /**
     * 打开（或安装）[file]。
     *
     * @param mimeType MIME 类型；安装包传 `application/vnd.android.package-archive` 即可。
     *   留空时用通配类型（由系统按扩展名/内容自行选择应用——不如显式类型可靠，但不至于打不开）。
     * @return 是否成功发起 Intent（失败会弹 Toast 说明原因，调用方无需再处理）。
     */
    fun openFile(context: Context, file: File, mimeType: String?): Boolean {
        if (!file.isFile) {
            Toast.makeText(context, "文件不存在：${file.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        val type = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"

        // 安装包：Android 8(O) 起「安装未知应用」是**按应用**授权的。
        // 未授权时直接 ACTION_VIEW 只会被系统静默拒绝（用户什么也看不到），
        // 所以先检查并引导到该应用的授权页。
        if (isInstallablePackage(file.name) && !canInstallPackages(context)) {
            Toast.makeText(context, "需要先允许本应用安装未知应用", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(context, "无法打开授权页面，请在系统设置中手动开启", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val uri = shareableUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从非 Activity 上下文（或无 Activity 栈）发起时必须有 NEW_TASK。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    context,
                    if (isInstallablePackage(file.name)) "没有可用的安装器" else "没有能打开该文件的应用",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .isSuccess
    }

    /**
     * 取得可对外授权的 `content://` URI。
     *
     * 先尝试直接授权；`FileProvider` 覆盖不到该路径时（如某些外部存储卷），
     * 退化为「拷进 cacheDir 再授权」。返回 null 表示两条路都失败（已弹 Toast）。
     */
    private fun shareableUri(context: Context, file: File): Uri? {
        directUri(context, file)?.let { return it }
        val cached = copyToShareCache(context, file)
        if (cached == null) {
            Toast.makeText(context, "该文件位置无法共享给其它应用", Toast.LENGTH_SHORT).show()
            return null
        }
        return directUri(context, cached) ?: run {
            Toast.makeText(context, "无法生成文件授权链接", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun directUri(context: Context, file: File): Uri? =
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()

    /**
     * 把文件复制到 `cacheDir/shared/` 再授权。
     *
     * 用**原始文件名**（而非随机名）：安装器/外部应用会拿文件名做展示与判断
     * （如根据 `.apk` 后缀决定是否走安装流程），改名会导致行为异常。
     * 同名文件按「大小 + 修改时间」判重，避免每次点击都重拷大文件。
     */
    private fun copyToShareCache(context: Context, file: File): File? = runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(dir, file.name)
        if (target.isFile && target.length() == file.length() && target.lastModified() == file.lastModified()) {
            return@runCatching target
        }
        file.copyTo(target, overwrite = true)
        target.setLastModified(file.lastModified())
        target
    }.getOrNull()

    /** 是否已获「安装未知应用」授权。API 26 以下无需该授权（直接受「未知来源」总开关控制）。 */
    private fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
}
