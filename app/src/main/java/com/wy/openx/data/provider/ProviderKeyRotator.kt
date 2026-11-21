package com.wy.openx.data.provider

import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.KeyRotationStrategy

/** 一次 Key 切换的结果，供 UI 提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newKey: String,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int
)

/**
 * 多 Key 轮换器：决定某个 provider 在某条会话上该用哪个 API Key，并按失败次数做切换。
 *
 * 状态全部是运行时内存态、不落库：进程重启后回到第一个 Key、冷却记录清空。
 *
 * **会话粘性**是核心约束：服务端 prompt 缓存按 API Key 隔离，同一会话逐请求换 Key 会让
 * 每轮都落到没有缓存的 Key 上。因此两种策略都在会话内粘住同一个 Key，只有该 Key
 * 连续失败达阈值时才切换并重绑。
 */
class ProviderKeyRotator {

    private data class KeySetup(
        val keys: List<String>,
        val strategy: KeyRotationStrategy,
        val failoverThreshold: Int,
        val cooldownMillis: Long
    )

    private val lock = Any()
    private val setups = mutableMapOf<String, KeySetup>()
    private val failureCounts = mutableMapOf<String, Int>()
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val roundRobinCursor = mutableMapOf<String, Int>()
    private val lastSelected = mutableMapOf<String, String>()

    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /** 取 [config] 在 [sessionId] 上当前该用的 Key；没有任何可用 Key 时返回 null。 */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                failoverThreshold = config.keyFailoverThreshold.coerceAtLeast(1),
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )
            if (keys.size == 1) return keys.first()

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[it] }
            if (bound != null && bound in keys && !isCoolingDown(config.id, bound)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, avoid = null)
            if (bindKey != null) sessionKeys[bindKey] = chosen
            lastSelected[config.id] = chosen
            return chosen
        }
    }

    /** 只读地取该 provider 当前活动的 Key：不建会话绑定、不推进轮询游标。 */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && last in keys && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it) } ?: keys.first()
        }
    }

    /**
     * 上报一次可归因于 [key] 的失败。连续失败达到阈值时把该 Key 打进冷却、切到下一个并重绑会话，
     * 返回切换结果；未达阈值或无可切换目标时返回 null。
     */
    fun reportFailure(providerId: String, sessionId: String?, key: String): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (setup.keys.size <= 1 || key !in setup.keys) return null

            val counterKey = stateKey(providerId, key)
            val count = (failureCounts[counterKey] ?: 0) + 1
            if (count < setup.failoverThreshold) {
                failureCounts[counterKey] = count
                return null
            }

            failureCounts[counterKey] = 0
            if (setup.cooldownMillis > 0) {
                cooldownUntil[counterKey] = System.currentTimeMillis() + setup.cooldownMillis
            }
            val next = pick(providerId, setup.keys, setup.strategy, avoid = key)
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            if (next == key) return null
            val index = setup.keys.indexOf(next) + 1
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /** 上报一次成功：清零该 Key 的连续失败计数。 */
    fun reportSuccess(providerId: String, key: String) {
        synchronized(lock) {
            failureCounts.remove(stateKey(providerId, key))
        }
    }

    private fun pick(
        providerId: String,
        keys: List<String>,
        strategy: KeyRotationStrategy,
        avoid: String?
    ): String {
        val ordered = when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> keys
            KeyRotationStrategy.ROUND_ROBIN -> {
                val start = (roundRobinCursor[providerId] ?: 0) % keys.size
                roundRobinCursor[providerId] = (start + 1) % keys.size
                keys.subList(start, keys.size) + keys.subList(0, start)
            }
        }
        val candidates = ordered.filter { it != avoid }.ifEmpty { ordered }
        return candidates.firstOrNull { !isCoolingDown(providerId, it) }
            ?: candidates.minByOrNull { cooldownUntil[stateKey(providerId, it)] ?: 0L }
            ?: keys.first()
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"
    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    private companion object {
        const val MAX_TRACKED_SESSIONS = 200
    }
}

/**
 * 判断一次 LLM 调用失败是否可归因于当前 API Key（鉴权/权限/余额）。
 *
 * **429 不算**：它是速率限流，与 Key 是否有效无关（同一个 Key 等一会就能用）。
 * 旧实现把 "http 429" 也算作 Key 故障，于是限流时不仅换了 Key 无效，
 * 还会把所有 Key 打进冷却，把「等一会就好」升级成「整轮对话彻底卡住」。
 */
fun Throwable.isApiKeyFailure(): Boolean {
    val text = (message ?: "").lowercase()
    if (text.isEmpty()) return false
    if (text.contains("429") || text.contains("rate limit") || text.contains("too many requests")) {
        return false
    }
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

private val KEY_FAILURE_MESSAGES = listOf(
    "http 401", "http 402", "http 403",
    "invalid api key", "invalid_api_key", "incorrect api key",
    "insufficient_quota", "insufficient balance", "quota exceeded",
    "authentication_error", "permission_denied"
)
