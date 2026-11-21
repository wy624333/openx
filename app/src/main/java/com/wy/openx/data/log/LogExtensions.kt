package com.wy.openx.data.log

/**
 * 迁移期兼容层：让历史调用形态 `uiState.logs + "[工具] 正在调用: xxx"` 继续可用，
 * 但产出的条目是结构化的（时间/级别/tag 分离），并统一写入 [AppLogStore] 落盘。
 *
 * 不逐个改写 39 处业务埋点，是为把「日志改造」的风险限制在日志系统内部。
 * 返回 [AppLogStore] 当前快照，保证与 [AppLogStore.entries] 流式推送的内容完全一致
 * （避免「同步赋值一份、异步再推一份」的两份数据不一致）。
 *
 * 新代码请直接调用 [AppLogStore.info] / [AppLogStore.warn] / [AppLogStore.error]。
 */
internal operator fun List<LogEntry>.plus(raw: String): List<LogEntry> {
    AppLogStore.pushLegacy(raw)
    return AppLogStore.snapshot()
}
