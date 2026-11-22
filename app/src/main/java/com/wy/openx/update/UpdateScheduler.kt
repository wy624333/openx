package com.wy.openx.update

import android.content.Context
import com.wy.openx.BuildConfig

/**
 * 更新检查的调度与「用户已忽略的版本」记录。
 *
 * 每天最多自动查一次：次数限制靠时间戳，不是定时任务——本应用没有常驻后台进程，
 * 用户不开软件就没有任何代码在跑，只能等他下次启动时补查。
 *
 * 弹窗提醒后用户可以选择「跳过此版本」，该 versionCode 记在本地，
 * 之后只对更高的版本再提醒，避免同一个版本天天弹。
 */
object UpdateScheduler {

    private const val PREFS = "openx_update"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_SKIPPED_CODE = "skipped_version_code"

    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 距上次检查是否已满 24 小时（从未检查过也算到期）。 */
    fun isDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK_AT, 0L)
        return System.currentTimeMillis() - last >= INTERVAL_MS
    }

    /** 记录本次检查时间。失败也算查过——否则断网时会每次启动都重试，白等一次超时。 */
    fun markChecked(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    /** 用户主动跳过的版本，不再提醒；返回 0 表示没有跳过记录。 */
    fun skippedVersionCode(context: Context): Int =
        prefs(context).getInt(KEY_SKIPPED_CODE, 0)

    fun skipVersion(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_SKIPPED_CODE, versionCode).apply()
    }

    /**
     * 到期才真正去查，返回值得提醒的新版本。
     *
     * @return 有新版本且未被用户跳过时返回它；未到期、无新版、查询失败或被跳过都返回 null。
     */
    suspend fun checkIfDue(context: Context): UpdateChecker.Release? {
        if (!isDue(context)) return null
        val release = UpdateChecker.fetchLatest()
        markChecked(context)
        if (release == null || !release.isNewer) return null
        if (release.versionCode <= skippedVersionCode(context)) return null
        return release
    }

    /** 当前安装版本，弹窗文案里用。 */
    fun currentVersion(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
