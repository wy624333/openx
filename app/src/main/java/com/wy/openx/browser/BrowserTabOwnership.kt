package com.wy.openx.browser

/**
 * 多会话标签归属的**纯逻辑**：给一组「标签 → 归属会话」，判定某会话能看见/能操作哪些标签。
 *
 * 抽出来是为了可单测 —— [BrowserManager] 本体依赖 WebView/主线程，无法在 JVM 单测里构造。
 *
 * ## 规则（方案 A′：无主标签可用，但一用就"认领"）
 * - **无主标签（owner=0）**：UI 建的 / 用户手动开的，**任何会话都能看见、都能用**。
 *   这让 AI 能直接操作用户正打开的页面（不必强制先 newTab）。
 * - **有主标签**：只有归属会话能用；别的会话既看不到、也不能显式指定。
 *
 * ## 关键补充：认领（见 [shouldClaim]）
 * 无主标签若被两个会话都用，仍会互相导航（实测复现：A、B 都落到无主 tab-5）。
 * 故**某会话首次使用无主标签时把它认领**（owner 改成该会话），之后别的会话就看不到它了
 * ——「先到先得」。这样单会话时 AI 能接手用户打开的页面，多会话时又不会抢同一个。
 */
object BrowserTabOwnership {

    /** 该标签对该会话是否可见/可用。 */
    fun isVisibleTo(ownerConversationId: Long, viewerConversationId: Long): Boolean =
        ownerConversationId == 0L || ownerConversationId == viewerConversationId

    /**
     * 某会话使用一个标签后，是否应把它「认领」为自己所有。
     *
     * 只认领**当前无主**的；已属于自己的无需再改；别人的标签本就不该走到这里（会被拒）。
     * 会话 0（用户/UI）不认领——用户不该"拥有"标签，否则 AI 就再也用不了用户打开的页面。
     */
    fun shouldClaim(ownerConversationId: Long, viewerConversationId: Long): Boolean =
        viewerConversationId != 0L && ownerConversationId == 0L

    /** 该会话可见的标签；保持输入顺序。 */
    fun <T> visibleTo(
        items: List<T>,
        ownerOf: (T) -> Long,
        viewerConversationId: Long
    ): List<T> = items.filter { isVisibleTo(ownerOf(it), viewerConversationId) }

    /**
     * 「省略 tabId」时选哪个作为活动标签。
     *
     * @param visibleIds 该会话可见的标签 id（按创建顺序）
     * @param remembered 该会话上次记录的活动标签（可能已被关闭/不可见）
     * @return 目标 id；无可选标签时 null
     */
    fun pickActive(visibleIds: List<String>, remembered: String?): String? {
        if (visibleIds.isEmpty()) return null
        if (remembered != null && visibleIds.contains(remembered)) return remembered
        // 兜底取最后一个：与「新建标签即成为活动标签、且新建的总是追加在末尾」一致。
        return visibleIds.last()
    }

    /**
     * 解析显式指定的 `tabId`。
     *
     * @return 目标 id；**null 表示不可用（属于别的会话 / AI 想用无主标签），应拒绝**。
     */
    fun resolveExplicit(tabId: String, ownerConversationId: Long, viewerConversationId: Long): String? =
        if (isVisibleTo(ownerConversationId, viewerConversationId)) tabId else null
}