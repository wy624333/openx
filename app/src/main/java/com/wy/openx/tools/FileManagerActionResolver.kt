package com.wy.openx.tools

/**
 * `file_manager` 的 action 兜底推断。
 *
 * ## 为什么需要
 * 线上日志（`session-338.log`）里 47 次 `file_manager` 调用有 **28 次（60%）没带 `action`**，
 * 参数长这样：
 * ```json
 * {"path": "/root/workspace/projects/xingde/CHANGELOG.md"}
 * ```
 * 全部返回 `错误：未指定操作`。而 schema 里明明写了 `"required":["action","path"]`——
 * 是上游模型（`agnes-2.5-flash` 走 apihub）**不强制校验 required**，模型直接漏参数。
 *
 * 原实现在 `FileManagerTool.execute` 里 `args["action"] as? String ?: return error("未指定操作")`，
 * 硬报错、零兜底，而且这条错误**不含任何有用信息**（没说支持哪些 action，也没回显 path），
 * 模型拿不到新信息只能拿同样错的参数反复重试，于是表现为「file_manager 老是失败」。
 *
 * 这里改成：能猜就猜，猜不了就把「可用值 + 你给的参数」一起回显，让模型能自我纠正。
 */
object FileManagerActionResolver {

    /** 与 [FileManagerTool] 的 `action` 枚举保持一致。 */
    val ACTIONS = listOf("list", "read", "write", "delete", "info", "search", "analyze")

    sealed interface Result {
        /** [inferred] 为 true 表示 action 是猜出来的，调用方可据此在结果里告知模型。 */
        data class Use(val action: String, val inferred: Boolean) : Result

        /** 无法推断，交给调用方转成错误文案。 */
        data class Reject(val message: String) : Result
    }

    /**
     * @param rawAction 模型给的 action，可能为 null / 空 / 大小写不规范
     * @param path 模型给的 path
     * @param hasContent 是否带了 `content`（带了就几乎必然是想写文件）
     * @param pathExists 目标路径是否已存在（由调用方解析后传入，本类不碰文件系统）
     * @param pathIsDirectory 目标路径是否为目录
     */
    fun resolve(
        rawAction: String?,
        path: String?,
        hasContent: Boolean,
        pathExists: Boolean,
        pathIsDirectory: Boolean
    ): Result {
        val action = rawAction?.trim()?.lowercase()
        if (!action.isNullOrEmpty()) {
            if (action !in ACTIONS) {
                return Result.Reject(
                    "无效的 action='$rawAction'。可用值：${ACTIONS.joinToString(" / ")}"
                )
            }
            return Result.Use(action, inferred = false)
        }

        // ↓ action 缺失：按其余参数推断
        // 1) 带了 content 就是想写文件
        if (hasContent) return Result.Use("write", inferred = true)

        if (path.isNullOrBlank()) {
            return Result.Reject(
                "未指定 action，也没有 path / content 可供推断。" +
                    "可用值：${ACTIONS.joinToString(" / ")}"
            )
        }

        val p = path.trim()
        // 2) 路径以 / 结尾 = 明确指向目录
        if (p.endsWith("/")) return Result.Use("list", inferred = true)
        // 3) 已存在：目录看内容、文件读内容
        if (pathExists) return Result.Use(if (pathIsDirectory) "list" else "read", inferred = true)
        // 4) 不存在：按 read 处理，随后由 FileManagerTool 回「路径不存在」，
        //    报错里带上了真实路径，比「未指定操作」有用得多
        return Result.Use("read", inferred = true)
    }

    /** 推断成功时给结果加的前缀，让模型知道自己省了 action、下次最好显式写上。 */
    fun notice(action: String, inferred: Boolean, rawAction: String?): String? {
        if (!inferred) return null
        return "（未指定 action，已按 path 推断为 $action；建议后续显式传 action）"
    }
}
