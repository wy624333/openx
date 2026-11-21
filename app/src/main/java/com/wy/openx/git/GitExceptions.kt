package com.wy.openx.git

/**
 * git 命令以非零退出码结束（真实失败），区别于进程异常崩溃。携带 git 自身输出的文本（stderr 优先），
 * UI 据此把「成功」误报纠正为「失败 + 原因」。
 *
 * 移植自 AiCode-main `feature/git/domain/GitCommandFailureException.kt`。
 */
open class GitCommandFailureException(val output: String) : Exception(output)

/** git 输出超过引擎上限被截断。既有失败处理路径（toast）自动生效。 */
class GitOutputTooLargeException : GitCommandFailureException("输出过大，已截断，请缩小范围后重试")
