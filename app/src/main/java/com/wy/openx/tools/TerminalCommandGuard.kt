package com.wy.openx.tools

/**
 * AI 终端命令安全守卫。
 * 仅作用于 AI 工具入口（TerminalTool），真人手输命令不受影响。
 *
 * 判定结果：
 * - [Verdict.Allow]：放行
 * - [Verdict.Block]：致命操作，直接拦截并把原因返回给 LLM
 * - [Verdict.Confirm]：危险操作，弹确认框由用户决定后再执行
 */
object TerminalCommandGuard {

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val reason: String) : Verdict
        data class Confirm(val reason: String) : Verdict
    }

    // 容器 rootfs 关键目录（整体删除/写入视为致命）
    private val systemRoots = listOf(
        "/bin", "/sbin", "/usr", "/lib", "/lib64", "/etc", "/boot",
        "/dev", "/proc", "/sys", "/root", "/srv", "/opt", "/run",
        "/media", "/mnt", "/var", "/workspace"
    )

    // 写操作可能改系统配置的目录（rm /etc 下具体文件、向 /etc 重定向等）
    private val writeGuardDirs = listOf(
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64",
        "/boot", "/dev", "/proc", "/sys", "/var"
    )

    // 无害特殊文件（重定向过去不拦截）
    private val harmlessDev = setOf(
        "/dev/null", "/dev/tty", "/dev/zero", "/dev/random", "/dev/urandom"
    )

    private val fatalPatterns = listOf(
        Regex("""^\s*mkfs(\.[a-z0-9]+)?(\s|$)""") to "禁止格式化磁盘（mkfs）",
        Regex("""^\s*(fdisk|gdisk|parted|mkswap|cfdisk|sfdisk)(\s|$)""") to "禁止磁盘分区/交换分区操作",
        Regex("""^\s*:\(\)\s*\{""") to "疑似 fork 炸弹，已被拦截",
        Regex("""^\s*(reboot|shutdown|halt|poweroff)(\s|$)""") to "禁止重启/关机类命令",
        Regex("""\bdd\s+.*\bof=/dev/(?!null\b|tty\b|zero\b|random\b|urandom\b)[-\w./]+""") to "禁止用 dd 写入块设备",
    )

    /**
     * 是否启用「弹窗确认」这一层。默认 **false**：用户反馈构建类任务（大量 rm -rf 构建产物、
     * 覆盖 SDK 目录等）频繁弹窗打断，要求「只要不把系统根/关键目录删掉，其余一律放行」。
     * 关闭后 [Confirm] 永不产生，仅保留致命操作的硬拦截（[Block]，见 [blockVerdict]）。
     * 如需恢复弹窗确认，把此常量改回 true 即可（[confirmVerdict] 逻辑完整保留）。
     */
    private const val ENABLE_CONFIRM_POPUPS = false

    /** 入口：对完整命令（可含 ; && || | & 与换行）进行分析。 */
    fun analyze(fullCommand: String): Verdict {
        val segments = splitSegments(fullCommand)
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) continue
            blockVerdict(trimmed)?.let { return it }
        }
        // 仅在启用弹窗确认时才做危险判定；默认关闭以免打断构建类任务。
        if (ENABLE_CONFIRM_POPUPS) {
            for (seg in segments) {
                val trimmed = seg.trim()
                if (trimmed.isEmpty()) continue
                confirmVerdict(trimmed)?.let { return it }
            }
        }
        return Verdict.Allow
    }

    // ---------------- 分段（忽略引号内的 ; | & 换行） ----------------
    private fun splitSegments(command: String): List<String> {
        val segments = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        val len = command.length
        while (i < len) {
            val c = command[i]
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; sb.append(c) }
                c == '"' && !inDouble -> { inDouble = !inDouble; sb.append(c) }
                c == '\\' && i + 1 < len -> { sb.append(c).append(command[i + 1]); i++ }
                !inSingle && !inDouble && (c == ';' || c == '|' || c == '&' || c == '\n') -> {
                    segments.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        segments.add(sb.toString())
        return segments
    }

    private fun words(s: String): List<String> =
        s.split(Regex("""\s+""")).filter { it.isNotBlank() }

    /** 剥掉 command/sudo/nohup/env 等前缀，得到真正要执行的命令 token。 */
    private fun bareWords(s: String): List<String> {
        var list = words(s)
        while (true) {
            if (list.isNotEmpty() && list[0] in setOf("command", "sudo", "nohup")) {
                list = list.drop(1)
                continue
            }
            if (list.isNotEmpty() && list[0] == "env") {
                var j = 1
                while (j < list.size && list[j].contains('=')) j++
                list = list.drop(j)
                continue
            }
            break
        }
        return list
    }

    /** 解析 rm/rmdir：返回 (递归删除?, 目标列表)。 */
    private fun parseDeleteArgs(s: String): Pair<Boolean, List<String>> {
        val w = bareWords(s)
        val targets = mutableListOf<String>()
        var recursive = false
        var endOpts = false
        var i = 1
        while (i < w.size) {
            val t = w[i]
            when {
                !endOpts && t == "--" -> endOpts = true
                !endOpts && t.startsWith("--") -> {
                    if (t == "--recursive" || t == "--dir" || t == "--all") recursive = true
                }
                !endOpts && t.length > 1 && t.startsWith("-") -> {
                    if (t.any { it == 'r' || it == 'R' }) recursive = true
                }
                else -> targets.add(t)
            }
            i++
        }
        return recursive to targets
    }

    /** 取第一个非选项参数（mv/cp 等的源路径）。 */
    private fun firstNonOpt(w: List<String>): String? {
        var i = 1
        while (i < w.size && w[i].startsWith("-") && w[i] != "--") i++
        if (i >= w.size) return null
        if (w[i] == "--") i++
        return w.getOrNull(i)
    }

    /** 取命令最后一个非选项参数（mv/cp/sed 等的目标路径）。 */
    private fun lastNonOpt(w: List<String>): String? {
        var i = 1
        while (i < w.size && w[i].startsWith("-") && w[i] != "--") i++
        if (i >= w.size) return null
        if (w[i] == "--") i++
        // i 指向第一个非选项参数（源），目标在其后一个位置
        return w.getOrNull(i + 1)
    }

    /** 解析 chmod/chown 的目标路径（跳过选项与权限模式）。 */
    private fun parseChmodTarget(s: String): String? {
        val w = bareWords(s)
        var i = 1
        while (i < w.size && w[i].startsWith("-") && w[i] != "--") i++
        if (i >= w.size) return null
        if (w[i] == "--") i++
        var j = i
        val modeish = Regex("""^[0-7]{1,4}$|^[ugoa]*[+-=][rwxXstugo,]+$|^[0-7]+[rwxXugo]*$""")
        val ownerish = Regex("""^[\w.]+:[\w.-]*$""")
        if (j < w.size && (modeish.matches(w[j]) || ownerish.matches(w[j]))) j++
        return if (j < w.size) w[j] else null
    }

    /** 提取重定向（> / >> / 2> / 1>）的目标路径，返回 null 表示无重定向。 */
    private fun redirTarget(s: String): String? {
        val m = Regex("""(?<![<\w-])\d?>+\s*([^\s;&|]+)""").find(s) ?: return null
        val target = m.groupValues[1]
        // 排除 2>&1 这类 fd 重定向
        return target.takeUnless { it.startsWith("&") }
    }

    private fun normalizePath(p: String): String {
        var n = p.trim()
        if (n == "~") return "/root"
        if (n.startsWith("~/")) n = "/root/" + n.substring(2)
        if (n.startsWith("\$HOME")) n = "/root" + n.removePrefix("\$HOME")
        return n
    }

    // 容器内临时/缓存目录：删除、写入这些目录一律放行（用户反馈「删除临时文件不要拦截」）。
    private val tempDirs = listOf("/tmp", "/var/tmp", "/dev/shm", "/root/.cache", "/root/tmp")

    /** 目标是否落在临时/缓存区域（这些区域的操作无风险，直接放行）。 */
    private fun isTempPath(p: String): Boolean {
        val n = normalizePath(p)
        return tempDirs.any { n == it || n.startsWith("$it/") }
    }

    /** 致命目标：根目录整体或关键系统目录整体。 */
    private fun isFatalTarget(p: String): Boolean {
        val n = normalizePath(p)
        return n == "/" || n == "/*" || n == "/.*" || systemRoots.any { n == it }
    }

    /** 系统关键区域内部（含子路径），整体删除会波及系统。 */
    private fun isDangerPath(p: String): Boolean {
        val n = normalizePath(p)
        return n == "/" || n == "/*" || n == "/.*" ||
            systemRoots.any { n == it || n.startsWith("$it/") }
    }

    /** 写保护区域（改系统配置）：/etc /usr /bin /sbin /lib /boot /var /dev /proc /sys。 */
    private fun isWriteGuarded(p: String): Boolean {
        val n = normalizePath(p)
        return writeGuardDirs.any { n == it || n.startsWith("$it/") }
    }

    /** 块设备/内存设备路径（写它们=破坏性）。 */
    private fun isDevBlockTarget(p: String): Boolean {
        val n = normalizePath(p)
        if (!n.startsWith("/dev/")) return false
        if (n in harmlessDev) return false
        return Regex("""/dev/(sd|xvd|vd|mmcblk|nvme|loop|dm-)[-\w./]+""").matches(n) || n == "/dev/mem"
    }

    // ---------------- 致命判定（硬拦截） ----------------
    private fun blockVerdict(s: String): Verdict? {
        for ((pattern, msg) in fatalPatterns) {
            if (pattern.containsMatchIn(s)) return Verdict.Block(msg)
        }

        val bare = bareWords(s)
        if (bare.isEmpty()) return null
        val cmd = bare[0]

        when (cmd) {
            "rm", "rmdir" -> {
                val (recursive, targets) = parseDeleteArgs(s)
                if (recursive) {
                    for (t in targets) {
                        if (isFatalTarget(t)) return Verdict.Block("禁止递归删除关键目录：$t")
                    }
                }
            }
            "find" -> {
                if ("-delete" in bare && !bare.contains("-exec")) {
                    val paths = bare.drop(1).takeWhile { !it.startsWith("-") }
                    for (p in paths) {
                        if (isFatalTarget(p)) return Verdict.Block("禁止 find -delete 清空关键目录：$p")
                    }
                }
                if ("-exec" in bare && Regex("""\brm\b""").containsMatchIn(s)) {
                    return Verdict.Block("禁止 find -exec rm 批量删除")
                }
            }
            "chmod", "chown" -> {
                val target = parseChmodTarget(s)
                if (target != null && isFatalTarget(target)) {
                    return Verdict.Block("禁止对关键目录执行权限修改：$target")
                }
            }
        }

        // 重定向写入块设备/内存设备
        redirTarget(s)?.let {
            if (isDevBlockTarget(it)) return Verdict.Block("禁止写入块设备：$it")
        }

        return null
    }

    // ---------------- 危险判定（弹确认框） ----------------
    private fun confirmVerdict(s: String): Verdict? {
        val bare = bareWords(s)
        if (bare.isEmpty()) return null
        val cmd = bare[0]

        when (cmd) {
            "rm", "rmdir" -> {
                val (recursive, targets) = parseDeleteArgs(s)
                // 目标全部落在临时/缓存目录：删除无风险，直接放行（用户反馈「删除临时文件不要拦截」）。
                if (targets.isNotEmpty() && targets.all { isTempPath(it) }) return null
                if (recursive && targets.isEmpty()) {
                    return Verdict.Confirm("递归删除（未指明完整目标），请确认是否执行")
                }
                if (recursive) {
                    return Verdict.Confirm("递归删除，请确认：${targets.joinToString(" ")}")
                }
                for (t in targets) {
                    if (isWriteGuarded(t)) return Verdict.Confirm("删除系统区域内的文件：$t")
                }
                return null
            }
            // 复制/移动不再拦截（用户反馈：cp/mv 属常规操作，不需要确认）。
            // 仅保留对「系统关键目录整体」的硬拦截（见 blockVerdict 的 fatalPatterns/rm 部分，
            // 以及 mv/cp 目标为系统关键目录整体时仍走 fatal 拦截）。
            "mv", "cp", "install" -> {
                val src = firstNonOpt(bare)
                val target = lastNonOpt(bare)
                if (src != null && isFatalTarget(src)) {
                    return Verdict.Confirm("移动/复制关键系统目录：$src")
                }
                if (target != null && isFatalTarget(target)) {
                    return Verdict.Confirm("目标路径属于系统关键区域：$target")
                }
                return null
            }
            "tar" -> {
                val ci = bare.indexOf("-C")
                if (ci >= 0 && ci + 1 < bare.size) {
                    val dir = bare[ci + 1]
                    if (isFatalTarget(dir)) return Verdict.Confirm("解压目标为系统关键目录：$dir")
                    if (isWriteGuarded(dir)) return Verdict.Confirm("解压内容写入系统区域：$dir")
                }
                return null
            }
            "chmod", "chown" -> {
                val target = parseChmodTarget(s)
                if (target != null && isWriteGuarded(target)) {
                    return Verdict.Confirm("对系统区域执行权限修改：$target")
                }
                return null
            }
            "sed" -> {
                if (bare.any { it.contains("-i") }) {
                    val file = bare.lastOrNull()
                    if (file != null && isWriteGuarded(file)) return Verdict.Confirm("sed -i 将改写系统文件：$file")
                }
                return null
            }
            "perl" -> {
                if (bare.any { it.contains("-pi") }) {
                    val file = bare.lastOrNull()
                    if (file != null && isWriteGuarded(file)) return Verdict.Confirm("perl -pi 将改写系统文件：$file")
                }
                return null
            }
            "find" -> {
                if ("-delete" in bare && !bare.contains("-exec")) {
                    val paths = bare.drop(1).takeWhile { !it.startsWith("-") }
                    for (p in paths) {
                        if (isWriteGuarded(p)) return Verdict.Confirm("find -delete 将递归删除：$p")
                    }
                    return Verdict.Confirm("find -delete 将清空当前目录树，请确认")
                }
                return null
            }
        }

        redirTarget(s)?.let { t ->
            if (t !in harmlessDev && isWriteGuarded(t)) {
                return Verdict.Confirm("向系统目录写入：$t")
            }
        }

        return null
    }
}