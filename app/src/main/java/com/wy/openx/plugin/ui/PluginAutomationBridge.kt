package com.wy.openx.plugin.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.wy.openx.data.local.ConversationEntity
import com.wy.openx.data.local.MessageEntity
import com.wy.openx.data.store.MemoryItem
import com.wy.openx.plugin.PluginPermission
import com.wy.openx.service.AgentAccessibilityService
import com.wy.openx.service.UiTreeQuery
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.roundToInt
import android.util.Base64

/**
 * 五期 / 六期的插件原生能力桥：无障碍 UI 自动化、设备与应用信息、摘要编码。
 *
 * 为什么单独一个类而不是继续堆进 [PluginUiHostBridge]：那边已经有音频、文件、
 * 网络、服务控制四组能力，再加一组体量就过千行了。这里按「宿主系统能力」聚合，
 * 转发方法留在 [PluginUiHostBridge]（QuickJS 只能绑定一个实例）。
 *
 * 所有方法返回 **JSON 字符串**，因为 QuickJS 的 NativeInterface 调用只回传
 * `toString()` 结果，结构化数据必须在宿主侧就序列化好。
 */
class PluginAutomationBridge(
    private val context: Context,
    private val pluginId: String,
    private val permissions: Set<PluginPermission>
) {

    private fun err(msg: String): String =
        "{\"ok\":false,\"error\":\"" + esc(msg) + "\"}"

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun requireUi(): AgentAccessibilityService? {
        if (PluginPermission.UI_AUTOMATION !in permissions) return null
        return AgentAccessibilityService.instance
    }

    private val uiDenied: String
        get() = if (PluginPermission.UI_AUTOMATION !in permissions) {
            err("缺少 ui 权限，请在 plugin.json 的 permissions 里声明 \"ui\"")
        } else {
            err("无障碍服务未运行，请在「设置 > 应用权限授权 > 无障碍」里开启 OpenX 后重试")
        }

    // ---------------------------------------------------------- 五期：ctx.ui

    fun openxUiReady(): String {
        if (PluginPermission.UI_AUTOMATION !in permissions) return uiDenied
        val running = AgentAccessibilityService.isRunning()
        return "{\"ok\":true,\"running\":$running}"
    }

    fun openxUiSnapshot(optionsJson: String?): String {
        val service = requireUi() ?: return uiDenied
        val opt = parseOpts(optionsJson)
        val maxNodes = num(opt["maxNodes"] ?: opt["max_nodes"], UiTreeQuery.DEFAULT_MAX_NODES.toDouble()).toInt()
        val maxDepth = num(opt["maxDepth"] ?: opt["max_depth"], UiTreeQuery.DEFAULT_MAX_DEPTH.toDouble()).toInt()
        return runCatching { service.uiSnapshot(maxNodes, maxDepth) }
            .getOrElse { err("读取界面失败：${it.message}") }
    }

    fun openxUiFind(selectorJson: String?, limit: Double): String {
        val service = requireUi() ?: return uiDenied
        return runCatching { service.uiFind(selectorJson, limit.toInt()) }
            .getOrElse { err("查找节点失败：${it.message}") }
    }

    /**
     * 点击目标节点。
     *
     * 这里用 `runBlocking` 等手势回调完成，前提是**调用方不在主线程**——
     * 插件动作派发走 `Dispatchers.Default`（见 PluginUiScreen），所以安全。
     * 如果将来把派发线程改回主线程，这里会直接死锁（dispatchGesture 的回调
     * 需要主线程 Looper 才能跑），改之前先确认这一点。
     */
    fun openxUiClick(selectorJson: String?): String {
        val service = requireUi() ?: return uiDenied
        return runCatching {
            runBlocking { withContext(Dispatchers.IO) { service.uiClick(selectorJson) } }
        }.getOrElse { err("点击失败：${it.message}") }
    }

    fun openxUiSetText(selectorJson: String?, text: String?): String {
        val service = requireUi() ?: return uiDenied
        val ok = runCatching { service.uiSetText(selectorJson, text.orEmpty()) }.getOrDefault(false)
        return if (ok) {
            "{\"ok\":true}"
        } else {
            err("没有找到可输入的输入框，或该输入框不接受程序设置文本")
        }
    }

    fun openxUiScroll(selectorJson: String?, direction: String?): String {
        val service = requireUi() ?: return uiDenied
        val ok = runCatching { service.uiScroll(selectorJson, direction.orEmpty()) }.getOrDefault(false)
        return if (ok) "{\"ok\":true}" else err("没有找到可滚动区域，或已经滚到尽头")
    }

    fun openxUiSwipe(x1: Double, y1: Double, x2: Double, y2: Double): String {
        val service = requireUi() ?: return uiDenied
        return runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    service.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
                }
            }
            "{\"ok\":true}"
        }.getOrElse { err("滑动失败：${it.message}") }
    }

    fun openxUiGlobal(action: String?): String {
        val service = requireUi() ?: return uiDenied
        val id = when (action?.trim()?.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent", "app_switch" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        } ?: return err("不支持的全局动作：$action（可用 back / home / recents / notifications / quick_settings / power / lock）")

        val ok = runCatching { service.performGlobal(id) }.getOrDefault(false)
        return if (ok) "{\"ok\":true}" else err("全局动作 $action 执行失败")
    }

    fun openxUiWaitFor(selectorJson: String?, timeoutMs: Double): String {
        val service = requireUi() ?: return uiDenied
        return runCatching { service.uiWaitFor(selectorJson, timeoutMs.toLong()) }
            .getOrElse { err("等待失败：${it.message}") }
    }

    fun openxUiForeground(): String {
        val service = requireUi() ?: return uiDenied
        return runCatching { service.uiForeground() }.getOrElse { err("获取前台应用失败：${it.message}") }
    }

    /** 坐标点击（语义定位不到时的退路）。 */
    fun openxUiTapAt(x: Double, y: Double): String {
        val service = requireUi() ?: return uiDenied
        return runCatching {
            runBlocking {
                withContext(Dispatchers.IO) { service.tap(x.toInt(), y.toInt()) }
            }
            "{\"ok\":true}"
        }.getOrElse { err("点击失败：${it.message}") }
    }

    // ---------------------------------------------------------- 六期：电池

    /**
     * 电池状态。
     *
     * 走 system 权限而不是 ui：读电池不需要无障碍，挂在 ui 下会让本来
     * 只想看电量的插件被迫申请读屏权限。
     */
    fun openxBatteryInfo(): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        return runCatching {
            val ctx = AppContextHolder.context ?: context
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return "{\"ok\":false,\"error\":\"无法读取电池广播\"}"
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val pct = if (scale > 0) level * 100 / scale else level
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val statusText = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            "{\"ok\":true,\"percent\":$pct,\"temperature\":${temp / 10.0}," +
                "\"charging\":$charging,\"status\":\"" + esc(statusText) + "\"}"
        }.getOrElse { err("获取电池信息失败：${it.message}") }
    }

    // ---------------------------------------------------------- 六期：设备

    fun openxDeviceInfo(): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        return runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val runtime = Runtime.getRuntime()
            val stat = StatFs(Environment.getDataDirectory().path)

            val sb = StringBuilder(512)
            sb.append("{\"ok\":true")
            sb.append(",\"brand\":\"").append(esc(Build.BRAND.orEmpty())).append('"')
            sb.append(",\"manufacturer\":\"").append(esc(Build.MANUFACTURER.orEmpty())).append('"')
            sb.append(",\"model\":\"").append(esc(Build.MODEL.orEmpty())).append('"')
            sb.append(",\"device\":\"").append(esc(Build.DEVICE.orEmpty())).append('"')
            sb.append(",\"android\":\"").append(esc(Build.VERSION.RELEASE.orEmpty())).append('"')
            sb.append(",\"sdk\":").append(Build.VERSION.SDK_INT)
            sb.append(",\"abis\":[")
            Build.SUPPORTED_ABIS.forEachIndexed { i, abi ->
                if (i > 0) sb.append(',')
                sb.append('"').append(esc(abi)).append('"')
            }
            sb.append(']')
            sb.append(",\"screen\":{\"width\":").append(metrics.widthPixels)
            sb.append(",\"height\":").append(metrics.heightPixels)
            sb.append(",\"densityDpi\":").append(metrics.densityDpi)
            sb.append(",\"density\":").append(metrics.density).append('}')
            sb.append(",\"memory\":{\"totalBytes\":").append(runtime.totalMemory())
            sb.append(",\"freeBytes\":").append(runtime.freeMemory())
            sb.append(",\"maxBytes\":").append(runtime.maxMemory()).append('}')
            sb.append(",\"storage\":{\"totalBytes\":").append(stat.totalBytes)
            sb.append(",\"freeBytes\":").append(stat.availableBytes).append('}')
            sb.append('}')
            sb.toString()
        }.getOrElse { err("读取设备信息失败：${it.message}") }
    }

    // ---------------------------------------------------------- 六期：实时状态

    // CPU 使用率需要两次采样求差，所以存上一轮的 ticks。
    // 散在类上而不是每次局部重算：插件通常 1~2 秒轮询一次，靠这个能做到无阻塞。
    @Volatile
    private var lastCpuIdle = -1L
    @Volatile
    private var lastCpuTotal = -1L

    /**
     * 设备实时状态：CPU 使用率、真实内存（不是 JVM 堆）、存储、运行时长、电池。
     *
     * 为什么不用 `Runtime.totalMemory()`：那是 **JVM 堆**，跟整台设备的内存占用毫无关系
     * （`ctx.device.info` 里的 memory 字段就是这个问题，历史遗留，新代码一律走这里）。
     */
    fun openxDeviceStats(): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        return runCatching {
            val appCtx = AppContextHolder.context ?: context
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // ---- 内存（系统级）
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalMem = mem.totalMem
            val availMem = mem.availMem
            val usedMem = (totalMem - availMem).coerceAtLeast(0L)
            val memPercent = if (totalMem > 0) usedMem * 100.0 / totalMem else 0.0

            // ---- 本进程内存（PSS，相对准确）
            val appPss = runCatching {
                val infos = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                infos?.firstOrNull()?.totalPss?.toLong()?.times(1024L) ?: 0L
            }.getOrDefault(0L)

            // ---- 存储
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalSt = stat.totalBytes
            val freeSt = stat.availableBytes
            val usedSt = (totalSt - freeSt).coerceAtLeast(0L)

            // ---- CPU
            val usage = cpuUsage()
            val cores = Runtime.getRuntime().availableProcessors()
            val freqs = readCpuFreqs()
            val freqMax = readCpuMaxFreq()

            // ---- 电池
            val battery = readBattery(appCtx)

            val sb = StringBuilder(512)
            sb.append("{\"ok\":true")
            sb.append(",\"cpu\":{\"usage\":").append(usage)
            sb.append(",\"cores\":").append(cores)
            sb.append(",\"freqMax\":").append(freqMax)
            sb.append(",\"freqs\":[")
            freqs.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                sb.append(f)
            }
            sb.append("]}")
            sb.append(",\"memory\":{\"totalBytes\":").append(totalMem)
            sb.append(",\"availBytes\":").append(availMem)
            sb.append(",\"usedBytes\":").append(usedMem)
            sb.append(",\"usedPercent\":").append((memPercent * 10).roundToInt() / 10.0)
            sb.append(",\"thresholdBytes\":").append(mem.threshold)
            sb.append(",\"lowMemory\":").append(mem.lowMemory)
            sb.append(",\"appPssBytes\":").append(appPss).append('}')
            sb.append(",\"storage\":{\"totalBytes\":").append(totalSt)
            sb.append(",\"freeBytes\":").append(freeSt)
            sb.append(",\"usedBytes\":").append(usedSt)
            sb.append(",\"usedPercent\":")
                .append(if (totalSt > 0) (usedSt * 1000.0 / totalSt).roundToInt() / 10.0 else 0.0)
            sb.append('}')
            sb.append(",\"uptimeMs\":").append(SystemClock.elapsedRealtime())
            sb.append(",\"battery\":").append(battery)
            sb.append('}')
            sb.toString()
        }.getOrElse { err("读取实时状态失败：${it.message}") }
    }

    /**
     * 全系统 CPU 使用率（0~100，-1 表示读不到 /proc/stat）。
     *
     * 首次调用没有上一轮样本，短暂休眠 80ms 再采一次。这个阻塞在插件动作线程上
     * （Dispatchers.Default），不会卡 UI；换来的好处是插件第一帧就能显示真实数值，
     * 而不是先闪一下 -1。
     */
    private fun cpuUsage(): Double {
        val ticks = readCpuTicks() ?: return -1.0
        val idle = ticks[0]
        val total = ticks[1]
        val prevIdle = lastCpuIdle
        val prevTotal = lastCpuTotal
        lastCpuIdle = idle
        lastCpuTotal = total
        if (prevTotal < 0L) {
            runCatching { Thread.sleep(80) }
            return cpuUsage()
        }
        val dTotal = total - prevTotal
        val dIdle = idle - prevIdle
        if (dTotal <= 0L) return 0.0
        val usage = (dTotal - dIdle) * 100.0 / dTotal
        return (usage * 10).roundToInt() / 10.0
    }

    /** `/proc/stat` 首行：[idle, total]，单位是内核 jiffies。 */
    private fun readCpuTicks(): LongArray? {
        return try {
            val line = java.io.File("/proc/stat").bufferedReader().use { r ->
                var hit: String? = null
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.startsWith("cpu ")) {
                        hit = l
                        break
                    }
                }
                hit
            } ?: return null
            val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return null
            // idle(3) + iowait(4)
            val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
            longArrayOf(idle, nums.sum())
        } catch (e: Exception) {
            null
        }
    }

    /** 各 CPU 核心当前频率（kHz）。读不到的设备返回空数组。 */
    private fun readCpuFreqs(): List<Long> {
        val out = mutableListOf<Long>()
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val f = runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    .readText().trim().toLong()
            }.getOrNull() ?: continue
            out += f
        }
        return out
    }

    /** 各核心当前频率上限（kHz），取所有核心中的最大值；读不到返回 0。 */
    private fun readCpuMaxFreq(): Long {
        var best = 0L
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val f = runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                    .readText().trim().toLong()
            }.getOrNull() ?: runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull() ?: continue
            if (f > best) best = f
        }
        return best
    }

    private fun readBattery(appCtx: Context): String {
        return runCatching {
            val intent = appCtx.registerReceiver(
                null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return "{}"
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val pct = if (scale > 0) level * 100 / scale else level
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            "{\"percent\":$pct,\"temperature\":${temp / 10.0},\"charging\":$charging}"
        }.getOrElse { "{}" }
    }

    // ---------------------------------------------------------- 六期：应用

    fun openxAppList(max: Double): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        return runCatching {
            val limit = max.toInt().let { if (it <= 0) 200 else it.coerceAtMost(500) }
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val sb = StringBuilder(4096)
            sb.append("{\"ok\":true,\"apps\":[")
            var count = 0
            var truncated = false
            for (p in packages) {
                if (count >= limit) {
                    truncated = true
                    break
                }
                val app = p.applicationInfo ?: continue
                val hasLauncher = pm.getLaunchIntentForPackage(p.packageName) != null
                if (!hasLauncher) continue
                if (count > 0) sb.append(',')
                sb.append("{\"package\":\"").append(esc(p.packageName)).append('"')
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
                sb.append(",\"label\":\"").append(esc(label)).append('"')
                sb.append(",\"version\":\"").append(esc(p.versionName.orEmpty())).append('"')
                sb.append(",\"system\":").append((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                sb.append('}')
                count++
            }
            sb.append("],\"count\":").append(count)
            sb.append(",\"truncated\":").append(truncated).append('}')
            sb.toString()
        }.getOrElse { err("读取应用列表失败：${it.message}") }
    }

    fun openxAppSearch(keyword: String?, max: Double): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        val kw = keyword.orEmpty().trim()
        if (kw.isEmpty()) return err("缺少搜索关键词")

        val all = openxAppList(max)
        if (!all.startsWith("{\"ok\":true")) return all

        return runCatching {
            val root = com.google.gson.JsonParser.parseString(all).asJsonObject
            val arr = root.getAsJsonArray("apps")
            val kept = com.google.gson.JsonArray()
            for (el in arr) {
                val o = el.asJsonObject
                val pkg = o.get("package")?.asString.orEmpty()
                val label = o.get("label")?.asString.orEmpty()
                if (pkg.contains(kw, true) || label.contains(kw, true)) kept.add(o)
            }
            val sb = StringBuilder(512)
            sb.append("{\"ok\":true,\"apps\":").append(kept.toString())
            sb.append(",\"count\":").append(kept.size()).append('}')
            sb.toString()
        }.getOrElse { err("搜索应用失败：${it.message}") }
    }

    fun openxAppLaunch(packageName: String?): String {
        if (PluginPermission.SYSTEM !in permissions) {
            return err("缺少 system 权限，请在 plugin.json 的 permissions 里声明 \"system\"")
        }
        val pkg = packageName.orEmpty().trim()
        if (pkg.isEmpty()) return err("缺少 packageName")
        return runCatching {
            val ctx = AppContextHolder.context ?: context
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "{\"ok\":false,\"error\":\"没有找到可启动的应用：$pkg\"}"
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "{\"ok\":true}"
        }.getOrElse { err("启动应用失败：${it.message}") }
    }

    // ---------------------------------------------------------- 十二期：记忆

    // 记忆库里的东西含用户偏好与项目内情，所以单独用一个 `memory` 危险权限，
    // 不和 `system`（设备信息）混在一起——授权粒度得对得上用户在确认页看到的文案。

    private fun memoryStore(): com.wy.openx.data.store.MemoryStore =
        com.wy.openx.data.store.MemoryStore(AppContextHolder.context ?: context)

    /** 解析作用域，返回 (scope, project)。project 作用域要带当前项目名。 */
    private fun memoryScope(scope: String?): Pair<String, String> {
        val sc = if (scope?.trim()?.lowercase() == "project") {
            com.wy.openx.data.store.SCOPE_PROJECT
        } else {
            com.wy.openx.data.store.SCOPE_GLOBAL
        }
        val proj = if (sc == com.wy.openx.data.store.SCOPE_PROJECT) {
            AppContextHolder.currentSettings?.currentProject?.trim().orEmpty()
        } else ""
        return sc to proj
    }

    private val memoryDenied: String
        get() = err("缺少 memory 权限，请在 plugin.json 的 permissions 里声明 \"memory\"")

    /** 列出/搜索记忆。value 只给前 200 字摘要，全文用 ctx.memory.read。 */
    fun openxMemoryList(keyword: String?, scope: String?, limit: Double): String {
        if (PluginPermission.MEMORY !in permissions) return memoryDenied
        return runCatching {
            val store = memoryStore()
            val (sc, proj) = memoryScope(scope)
            val max = limit.toInt().let { if (it <= 0) 50 else it.coerceAtMost(200) }
            val kw = keyword?.trim().orEmpty()
            val items: List<MemoryItem> = store.load(sc, proj)
                .filter { item -> kw.isEmpty() || item.key.contains(kw, true) || item.value.contains(kw, true) }
                .take(max)

            val sb = StringBuilder(1024)
            sb.append("{\"ok\":true,\"scope\":\"").append(sc).append('"')
            if (proj.isNotEmpty()) sb.append(",\"project\":\"").append(esc(proj)).append('"')
            sb.append(",\"count\":").append(items.size).append(",\"items\":[")
            items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                sb.append("{\"key\":\"").append(esc(item.key)).append('"')
                sb.append(",\"type\":\"").append(esc(item.type)).append('"')
                sb.append(",\"category\":\"").append(esc(item.category)).append('"')
                sb.append(",\"length\":").append(item.value.length)
                sb.append(",\"preview\":\"").append(esc(item.value.take(200))).append('"')
                sb.append('}')
            }
            sb.append("]}")
            sb.toString()
        }.getOrElse { err("读取记忆失败：${it.message}") }
    }

    /** 读某条记忆的全文。 */
    fun openxMemoryRead(key: String?, scope: String?): String {
        if (PluginPermission.MEMORY !in permissions) return memoryDenied
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return err("缺少 key")
        return runCatching {
            val store = memoryStore()
            val (sc, proj) = memoryScope(scope)
            val all: List<MemoryItem> = store.load(sc, proj)
            val hit: MemoryItem? = all.firstOrNull { it.key == k }
            if (hit == null) return "{\"ok\":false,\"error\":\"没有找到 key=$k 的记忆\"}"
            val sb = StringBuilder(256)
            sb.append("{\"ok\":true,\"key\":\"").append(esc(hit.key))
            sb.append("\",\"type\":\"").append(esc(hit.type))
            sb.append("\",\"category\":\"").append(esc(hit.category))
            sb.append("\",\"value\":\"").append(esc(hit.value)).append("\"}")
            sb.toString()
        }.getOrElse { err("读取记忆失败：${it.message}") }
    }

    /** 写入记忆（type 固定为 ai，便于与用户手写的记忆区分）。 */
    fun openxMemorySave(key: String?, value: String?, category: String?, scope: String?): String {
        if (PluginPermission.MEMORY !in permissions) return memoryDenied
        val k = key?.trim().orEmpty()
        val v = value.orEmpty()
        if (k.isEmpty() || v.isBlank()) return err("key 和 value 都不能为空")
        return runCatching {
            val store = memoryStore()
            val (sc, proj) = memoryScope(scope)
            val cat = category?.trim().takeUnless { it.isNullOrEmpty() } ?: "general"
            store.add(key = k, value = v, type = "ai", category = cat, scope = sc, project = proj)
            "{\"ok\":true}"
        }.getOrElse { err("保存记忆失败：${it.message}") }
    }

    /** 删除记忆（key 匹配的就删，不用调用方知道 type）。 */
    fun openxMemoryDelete(key: String?, scope: String?): String {
        if (PluginPermission.MEMORY !in permissions) return memoryDenied
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return err("缺少 key")
        return runCatching {
            val store = memoryStore()
            val (sc, proj) = memoryScope(scope)
            val hits = store.load(sc, proj).filter { it.key == k }
            if (hits.isEmpty()) return "{\"ok\":false,\"error\":\"没有找到 key=$k 的记忆\"}"
            hits.forEach { store.delete(it.key, it.type, sc, proj) }
            "{\"ok\":true,\"deleted\":" + hits.size + "}"
        }.getOrElse { err("删除记忆失败：${it.message}") }
    }

    // ---------------------------------------------------------- 十二期：会话（只读）

    // 只开放读。让插件“替用户发消息”需要串起整条 AI 请求链（模型选择、工具循环、
    // 流式渲染、中断），风险远高于收益——插件可以通过钩子/工具间接达到同样目的。

    private val chatDenied: String
        get() = err("缺少 chat 权限，请在 plugin.json 的 permissions 里声明 \"chat\"")

    private fun chatDb() = com.wy.openx.data.local.DatabaseProvider.get(
        AppContextHolder.context ?: context.applicationContext
    )

    /** 会话列表（按最近活跃倒序）。 */
    fun openxChatList(limit: Double): String {
        if (PluginPermission.CHAT !in permissions) return chatDenied
        return runCatching {
            val max = limit.toInt().let { if (it <= 0) 20 else it.coerceAtMost(100) }
            val all: List<ConversationEntity> = kotlinx.coroutines.runBlocking {
                chatDb().conversationDao().getAll()
            }
            val active = AppContextHolder.currentConversationId
            val list: List<ConversationEntity> = all.sortedByDescending { it.lastActiveAt }.take(max)

            val sb = StringBuilder(1024)
            sb.append("{\"ok\":true,\"count\":").append(list.size).append(",\"items\":[")
            list.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(c.id)
                sb.append(",\"title\":\"").append(esc(c.title)).append('"')
                sb.append(",\"active\":").append(c.id == active)
                sb.append(",\"archived\":").append(c.isArchived)
                sb.append(",\"requestCount\":").append(c.requestCount)
                sb.append(",\"lastActiveAt\":").append(c.lastActiveAt)
                sb.append('}')
            }
            sb.append("]}")
            sb.toString()
        }.getOrElse { err("读取会话列表失败：${it.message}") }
    }

    /** 当前会话信息。 */
    fun openxChatCurrent(): String {
        if (PluginPermission.CHAT !in permissions) return chatDenied
        val id = AppContextHolder.currentConversationId
            ?: return "{\"ok\":true,\"conversationId\":0,\"note\":\"当前没有打开的会话\"}"
        return runCatching {
            val all: List<ConversationEntity> = kotlinx.coroutines.runBlocking {
                chatDb().conversationDao().getAll()
            }
            val c: ConversationEntity = all.firstOrNull { it.id == id }
                ?: return "{\"ok\":false,\"error\":\"当前会话 $id 不存在\"}"
            val sb = StringBuilder(256)
            sb.append("{\"ok\":true,\"conversationId\":").append(c.id)
            sb.append(",\"title\":\"").append(esc(c.title)).append('"')
            sb.append(",\"requestCount\":").append(c.requestCount).append('}')
            sb.toString()
        }.getOrElse { err("读取当前会话失败：${it.message}") }
    }

    /** 当前会话最近 N 条消息（按时间正序返回）。content 过长会截断。 */
    fun openxChatMessages(limit: Double): String {
        if (PluginPermission.CHAT !in permissions) return chatDenied
        val id = AppContextHolder.currentConversationId
            ?: return err("当前没有打开的会话")
        return runCatching {
            val max = limit.toInt().let { if (it <= 0) 20 else it.coerceAtMost(100) }
            // DAO 是 DESC 取尾部，这里反转回时间正序（插件侧直接按顺序展示即可）
            val rows: List<MessageEntity> = kotlinx.coroutines.runBlocking {
                chatDb().messageDao().getRecentMessages(id, max)
            }.reversed()

            val sb = StringBuilder(2048)
            sb.append("{\"ok\":true,\"conversationId\":").append(id)
            sb.append(",\"count\":").append(rows.size).append(",\"messages\":[")
            rows.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append("{\"role\":\"").append(esc(m.role)).append('"')
                sb.append(",\"time\":\"").append(esc(m.time)).append('"')
                sb.append(",\"length\":").append(m.content.length)
                // 单条截断 2000 字：插件读上下文，不是做导出
                sb.append(",\"content\":\"").append(esc(m.content.take(2000))).append('"')
                sb.append('}')
            }
            sb.append("]}")
            sb.toString()
        }.getOrElse { err("读取会话消息失败：${it.message}") }
    }

    // ---------------------------------------------------------- 六期：摘要与编码

    fun openxCryptoHash(algorithm: String?, text: String?): String {
        val algo = when (algorithm.orEmpty().trim().uppercase().replace("_", "-")) {
            "", "SHA-256", "SHA256" -> "SHA-256"
            "SHA-1", "SHA1" -> "SHA-1"
            "SHA-512", "SHA512" -> "SHA-512"
            "MD5" -> "MD5"
            else -> return err("不支持的算法：$algorithm（可用 MD5 / SHA-1 / SHA-256 / SHA-512）")
        }
        return runCatching {
            val digest = MessageDigest.getInstance(algo)
            val bytes = digest.digest(text.orEmpty().toByteArray(Charsets.UTF_8))
            val hex = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
            "{\"ok\":true,\"algorithm\":\"$algo\",\"hex\":\"$hex\"}"
        }.getOrElse { err("计算摘要失败：${it.message}") }
    }

    fun openxCryptoBase64(mode: String?, text: String?): String {
        val raw = text.orEmpty()
        return runCatching {
            when (mode.orEmpty().trim().lowercase()) {
                "", "encode", "enc" -> {
                    val out = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    "{\"ok\":true,\"text\":\"" + esc(out) + "\"}"
                }
                "decode", "dec" -> {
                    val bytes = Base64.decode(raw, Base64.NO_WRAP)
                    "{\"ok\":true,\"text\":\"" + esc(String(bytes, Charsets.UTF_8)) + "\"}"
                }
                else -> err("不支持的模式：$mode（可用 encode / decode）")
            }
        }.getOrElse { err("Base64 处理失败：${it.message}") }
    }

    // ---------------------------------------------------------- 工具

    private fun parseOpts(json: String?): Map<String, Any?> = runCatching {
        com.google.gson.JsonParser.parseString(json.orEmpty()).asJsonObject
            .entrySet()
            .associate { (k, v) ->
                k to (if (v.isJsonPrimitive) {
                    val p = v.asJsonPrimitive
                    when {
                        p.isNumber -> p.asNumber
                        p.isBoolean -> p.asBoolean
                        else -> p.asString
                    }
                } else {
                    v.toString()
                })
            }
    }.getOrElse { emptyMap() }

    private fun num(v: Any?, fallback: Double): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    /**
     * 发起一轮 AI 任务（`ctx.chat.runTask`）。
     *
     * 需要 `chat_task` 权限——它会真的消耗 Token 并让 AI 跑工具。
     * `newConversation = true` 时新建一个后台会话（不切前台），否则向当前会话发。
     *
     * **只发起、不等结果**：AI 回复可能要几十秒，定时回调里同步等会卡住 QuickJS。
     * 结果在目标会话里，用户进去就能看到。
     */
    fun openxChatRunTask(prompt: String?, newConversation: Boolean, workspacePath: String?): String {
        if (PluginPermission.CHAT_TASK !in permissions) {
            return err("缺少 chat_task 权限，请在 plugin.json 的 permissions 里声明 \"chat_task\"")
        }
        val text = prompt?.trim().orEmpty()
        if (text.isEmpty()) return err("prompt 不能为空")
        val runner = AppContextHolder.pluginChatRunner
            ?: return err("宿主未就绪（pluginChatRunner 未注册），请重启应用")
        return runCatching {
            val (cid, started) = kotlinx.coroutines.runBlocking { runner(text, newConversation, workspacePath) }
            if (started) {
                "{\"ok\":true,\"conversationId\":$cid,\"started\":true}"
            } else {
                "{\"ok\":false,\"conversationId\":$cid,\"error\":\"未能启动一轮（可能未选模型、并发已满或当前有请求在跑）\"}"
            }
        }.getOrElse { err("发起任务失败：${it.message}") }
    }
}
