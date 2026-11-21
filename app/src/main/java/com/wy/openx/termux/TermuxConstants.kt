package com.wy.openx.termux

import com.wy.openx.tools.AppContextHolder
import java.io.File

/**
 * 终端环境路径。不再硬编码包名：优先从 AppContextHolder 取运行时 dataDir，
 * 兼容 debug 后缀包名与未来 applicationId 变更；拿不到时才回退常量。
 */
object TermuxConstants {

    // 仅作最终兜底的旧值
    private const val FALLBACK_FILES_DIR = "/data/data/com.wy.openx/files"

    val APP_FILES_DIR: String by lazy {
        val ctx = AppContextHolder.context
        val dir = ctx?.applicationInfo?.dataDir
        if (!dir.isNullOrBlank()) dir else FALLBACK_FILES_DIR
    }

    val PREFIX_DIR_PATH: String get() = "$APP_FILES_DIR/usr"
    val PREFIX_DIR: File get() = File(PREFIX_DIR_PATH)

    val HOME_DIR_PATH: String get() = "$APP_FILES_DIR/home"
    val HOME_DIR: File get() = File(HOME_DIR_PATH)

    val BIN_DIR_PATH: String get() = "$PREFIX_DIR_PATH/bin"
}
