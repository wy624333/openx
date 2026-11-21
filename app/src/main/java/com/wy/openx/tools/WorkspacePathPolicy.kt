package com.wy.openx.tools

/**
 * 「仅工作区修改」档的路径判定。
 *
 * ## 为什么只认「路径参数」，不扫所有字符串参数
 * 最初实现是 `args.values.filterIsInstance<String>()` 全扫，把**内容参数**也当成了路径：
 * `writeFile.content`（文件内容）、`editFile.edits[].old_text/new_text`（代码片段）、
 * `batch_replace.replace`（替换文本）里只要出现工作区外的绝对路径——写脚本时的
 * `/usr/bin/python3`、注释里的路径、接口的 `/api/v1`——就会被判成「工作区外」而弹窗。
 * 用户看到的「明明在改 /root/workspace 下的文件，却弹确认框」就是这么来的。
 *
 * 现在只取 [PATH_ARGS] 里命名的参数；嵌套结构（如 batch_edit 的 `edits`）只取其 `path` 字段。
 *
 * ## 局限
 * 这是尽力而为的护栏，不是安全沙箱：`..` 上跳、变量拼接、命令替换都绕得过去。
 * 好在判定的下游是「弹窗确认」而非「直接放行」，绕过只是多问一句，不会错放。
 */
object WorkspacePathPolicy {

    /** 工作区白名单（容器路径）。 */
    val ROOTS = listOf("/root/workspace", "/root/local")

    /**
     * **语义上就是路径**的参数名。
     *
     * 用白名单而非黑名单：以后新增工具参数若忘了登记，最坏结果是「该扫的没扫到」，
     * 而不是「内容被当成路径」——后者会在用户正常写代码时误弹窗，烦人得多。
     */
    val PATH_ARGS = setOf("path", "dir", "filePath", "targetPath", "workdir", "cwd")

    /**
     * 「仅工作区修改」档按路径判定的工具。
     * todo / memory_* / skill_* 虽属写类，但写的是面板与配置、不动工作区文件，故不列入。
     */
    val FILE_TOOLS = setOf(
        "writeFile", "appendFile", "editFile", "batch_edit", "batch_replace", "scaffold", "git"
    )

    /** 单个路径是否在工作区内（末尾斜杠不影响判定）。 */
    fun isWithin(path: String): Boolean {
        val p = path.trimEnd('/')
        return ROOTS.any { p == it || p.startsWith("$it/") }
    }

    /**
     * 逐个判定：绝对路径必须在白名单内；相对路径相对工作区解析，故放行；
     * `~` 是容器家目录（/root），在工作区外。
     */
    fun allWithin(paths: List<String>): Boolean = paths.all { p ->
        when {
            p.startsWith("~") -> false
            p.startsWith("/") -> isWithin(p)
            else -> true
        }
    }

    /**
     * 从工具参数里收集**路径**。
     *
     * 顶层分两种处理：字符串值只认 [PATH_ARGS] 命名的参数（content/old_text/replace 这类
     * 内容参数直接排除）；容器类参数（数组/对象）照常下钻，其内部的键同样要命中 [PATH_ARGS]——
     * 这样 `edits: [{path, old_text, new_text}]` 只取 path。
     *
     * 不能对顶层直接 `filterKeys { it in PATH_ARGS }`：`edits` 自己不是路径参数，
     * 但它的**内部**有——那样整份嵌套路径都会漏掉（单测覆盖了这个回归）。
     */
    fun collectPathArgs(args: Map<String, Any?>): List<String> =
        args.flatMap { (key, value) ->
            if (value is String) {
                if (key in PATH_ARGS) splitTokens(value) else emptyList()
            } else {
                collectPaths(value)
            }
        }

    /** 从 shell 命令里挑出路径 token（相对路径由 cwd 决定，由 [allWithin] 放行）。 */
    fun commandPaths(command: String): List<String> = splitTokens(command)

    private fun collectPaths(value: Any?): List<String> = when (value) {
        is String -> splitTokens(value)
        is Map<*, *> -> value.filterKeys { it.toString() in PATH_ARGS }.values.flatMap { collectPaths(it) }
        is List<*> -> value.flatMap { collectPaths(it) }
        else -> emptyList()
    }

    /** 按 shell 分隔符拆 token。 */
    private fun splitTokens(text: String): List<String> =
        if (text.isBlank()) emptyList()
        else text.split(Regex("""[\s'"`|&;()<>]+""")).filter { it.isNotBlank() }
}
