package com.wy.openx.tools

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.DisplayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DeviceTool : AgentTool {

    override val name = "device_info"
    override val description = "获取设备硬件和系统信息：CPU、内存、存储、电池、Android 版本、屏幕等"
    override val parameters = buildToolParameters(
        ToolParameter("category", ParameterType.STRING, "信息类别，默认 all", required = false, enumValues = listOf("all", "system", "storage", "memory", "battery", "display"))
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val category = (args["category"] as? String) ?: "all"
            val sb = StringBuilder()
            when (category) {
                "all" -> {
                    getSystemInfo(sb)
                    getMemoryInfo(sb)
                    getStorageInfo(sb)
                    getBatteryInfo(sb)
                    getDisplayInfo(sb)
                }
                "system" -> getSystemInfo(sb)
                "memory" -> getMemoryInfo(sb)
                "storage" -> getStorageInfo(sb)
                "battery" -> getBatteryInfo(sb)
                "display" -> getDisplayInfo(sb)
            }
            sb.toString().trimEnd()
        } catch (e: Exception) { "获取设备信息失败: ${e.message}" }
    }

    private fun getSystemInfo(sb: StringBuilder) {
        sb.appendLine("=== 系统信息 ===")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("品牌: ${android.os.Build.BRAND}")
        sb.appendLine("产品: ${android.os.Build.PRODUCT}")
        try {
            val abiList = android.os.Build.SUPPORTED_ABIS
            sb.appendLine("架构: ${abiList.joinToString(", ")}")
        } catch (_: Exception) {}

        try {
            val info = File("/proc/cpuinfo").readText()
            val lines = info.lines().filter { it.contains(":" ) }.distinct().take(10)
            if (lines.isNotEmpty()) {
                sb.appendLine("CPU:")
                lines.forEach { sb.appendLine("  $it") }
            }
        } catch (_: Exception) {}
    }

    private fun getMemoryInfo(sb: StringBuilder) {
        sb.appendLine("\n=== 内存信息 ===")
        try {
            val ctx = AppContextHolder.context ?: run { sb.appendLine("无法获取上下文"); return }
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am == null) { sb.appendLine("无法获取内存信息"); return }
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.appendLine("总内存: ${mi.totalMem / (1024 * 1024)} MB")
            sb.appendLine("可用内存: ${mi.availMem / (1024 * 1024)} MB")
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                sb.appendLine("低内存: ${mi.lowMemory}")
                sb.appendLine("阈值: ${mi.threshold / (1024 * 1024)} MB")
            }
        } catch (e: Exception) { sb.appendLine("读取失败: ${e.message}") }
    }

    private fun getStorageInfo(sb: StringBuilder) {
        sb.appendLine("\n=== 存储信息 ===")
        try {
            val dataPath = android.os.Environment.getDataDirectory()
            val dataStat = android.os.StatFs(dataPath.path)
            val dataTotal = dataStat.blockCountLong * dataStat.blockSizeLong
            val dataAvail = dataStat.availableBlocksLong * dataStat.blockSizeLong
            sb.appendLine("系统分区: 总 ${formatSize(dataTotal)} / 可用 ${formatSize(dataAvail)}")
        } catch (e: Exception) { sb.appendLine("系统分区: 读取失败") }

        try {
            val extPath = android.os.Environment.getExternalStorageDirectory()
            val extStat = android.os.StatFs(extPath.path)
            val extTotal = extStat.blockCountLong * extStat.blockSizeLong
            val extAvail = extStat.availableBlocksLong * extStat.blockSizeLong
            sb.appendLine("共享存储(/sdcard): 总 ${formatSize(extTotal)} / 可用 ${formatSize(extAvail)}")
        } catch (e: Exception) { sb.appendLine("共享存储: 读取失败") }
    }

    private fun getBatteryInfo(sb: StringBuilder) {
        sb.appendLine("\n=== 电池信息 ===")
        try {
            val ctx = AppContextHolder.context ?: run { sb.appendLine("无法获取上下文"); return }
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) { sb.appendLine("无法获取电池信息"); return }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (scale > 0) level * 100 / scale else -1
            sb.appendLine("电量: ${if (pct >= 0) "$pct%" else "未知"}")

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            sb.appendLine("状态: $statusText")

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val plugText = when {
                plugged == BatteryManager.BATTERY_PLUGGED_AC -> "交流电"
                plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
                else -> "未接通"
            }
            sb.appendLine("电源: $plugText")

            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val healthText = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
                BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
                else -> "正常"
            }
            sb.appendLine("健康: $healthText")

            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp >= 0) sb.appendLine("温度: ${temp / 10.0}°C")
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage >= 0) sb.appendLine("电压: ${voltage / 1000.0}V")
        } catch (e: Exception) { sb.appendLine("读取失败: ${e.message}") }
    }

    private fun getDisplayInfo(sb: StringBuilder) {
        sb.appendLine("\n=== 屏幕信息 ===")
        try {
            val ctx = AppContextHolder.context ?: run { sb.appendLine("无法获取上下文"); return }
            val display = ctx.getSystemService(android.content.Context.WINDOW_SERVICE)?.let {
                val metrics = DisplayMetrics()
                val wm = it as android.view.WindowManager
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics
            }
            if (display != null) {
                sb.appendLine("分辨率: ${display.widthPixels}×${display.heightPixels}")
                sb.appendLine("密度: ${display.densityDpi} dpi (${display.density}x)")
                sb.appendLine("物理尺寸(近似): ${"%.1f".format(display.widthPixels / display.xdpi)}×${"%.1f".format(display.heightPixels / display.ydpi)} 英寸")
            }
        } catch (e: Exception) { sb.appendLine("读取失败: ${e.message}") }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
