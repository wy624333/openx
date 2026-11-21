package com.wy.openx.container

/**
 * HOME 路径解析器：缓存容器内 `$HOME` 的实际值。
 *
 * [LinuxContainerEngine] 在容器就绪后查询 `echo $HOME` 并写入此处，
 * 供工具层展开 `~` 等相对路径使用（AI 工具接线子项目扩展）。
 * 首期为最小实现：仅缓存容器内 HOME 值。
 */
interface PathHomeResolver {
    /** 当前活动容器内 `$HOME`（首帧可能为 null，由引擎就绪后填充）。 */
    var containerHome: String?
}

/** 首期内存实现。 */
class SimplePathHomeResolver : PathHomeResolver {
    @Volatile
    override var containerHome: String? = null
}