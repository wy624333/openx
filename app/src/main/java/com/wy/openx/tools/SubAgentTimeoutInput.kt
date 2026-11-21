package com.wy.openx.tools

/**
 * 「单任务超时」手动输入的解析规则。
 *
 * ## 为什么需要
 * 该项原本只有一条 `IntSettingSlider`（范围 30..1800 秒），要精确到比如 7 分钟得靠拖——
 * 1770 步的滑块拖到「7 分钟」几乎不可能。拖动适合粗调，不适合定值。
 *
 * 所以把数值标签做成可点开手输。但显示文案有三种形态（「45 秒」/「5 分钟」/「5 分 30 秒」），
 * 光标标着「5 分钟」时用户敲个 `10` 到底是 10 秒还是 10 分钟？这里定死规则，
 * 免得用户猜、也免得实现里散落着 if。
 *
 * ## 规则
 * 1. **裸数字** → 按**当前显示的单位**解释。显示「分钟」时 `10` = 10 分钟；显示「秒」时 `10` = 10 秒。
 *    这是最常见的输入方式，用户看着标签敲，语义最自然。
 * 2. **带单位后缀** → 后缀说了算，与当前显示无关：
 *    - 秒：`s` `sec` `secs` `second` `seconds` `秒`
 *    - 分钟：`m` `min` `mins` `minute` `minutes` `分` `分钟`
 *    - 小时：`h` `hr` `hrs` `hour` `hours` `小时`（超范围时会被夹到上限）
 * 3. 允许中间空格与全角空格（手机输入法常带），`10 分` 与 `10分` 等价。
 * 4. 超出 [minSec]..[maxSec] 一律**夹到边界**而不是报错——用户手滑多打一位不该换来一句失败。
 * 5. 解析不出来（小数、多个单位、纯符号）→ 返回 null，由调用方还原显示、不改值。
 */
object SubAgentTimeoutInput {

    /** 设置页滑块的范围，与 [SubAgentDispatcher.timeoutMs] 的夹取区间保持一致。 */
    const val MIN_SEC = 30
    const val MAX_SEC = 7200

    enum class Unit { SECONDS, MINUTES }

    /**
     * 当前值对应「裸数字」输入时该按什么单位解释。
     * 跟 [SettingsScreen] 里那条 display 文案的判定保持一致：
     * 不到 1 分钟显示秒、整分显示分钟、其余显示「X 分 Y 秒」——最后一种按分钟解释
     * （用户看到分钟数，多半想改的就是它）。
     */
    fun unitFor(currentSec: Int): Unit =
        if (currentSec < 60) Unit.SECONDS else Unit.MINUTES

    /** 给编辑框用的初值：按当前单位显示裸数字，并预先全选。 */
    fun draftFor(currentSec: Int): String = when (val u = unitFor(currentSec)) {
        Unit.SECONDS -> currentSec.toString()
        Unit.MINUTES -> (currentSec / 60.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
    }

    /**
     * @param text 用户输入
     * @param currentSec 当前值（秒），决定裸数字的单位
     * @return 落秒数；解析失败返回 null
     */
    fun parse(text: String, currentSec: Int): Int? {
        val t = text.trim().replace('　', ' ')
        if (t.isEmpty()) return null
        // 只允许数字 / 一个小数点 / 空格 / 字母 / 中文单位词
        if (!t.matches(Regex("""[0-9.]+[ ]*[A-Za-z一-龥]*|[0-9.]+"""))) return null
        // 小数点超过一个 → 非法
        if (t.count { it == '.' } > 1) return null

        val m = Regex("""^([0-9]+(?:\.[0-9]+)?)[ ]*([A-Za-z一-龥]*)$""").find(t) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        if (num.isNaN() || num.isInfinite()) return null
        val suffix = m.groupValues[2].trim().lowercase()

        val seconds = when {
            suffix.isEmpty() -> num * if (unitFor(currentSec) == Unit.MINUTES) 60.0 else 1.0
            suffix in SECOND_UNITS -> num
            suffix in MINUTE_UNITS -> num * 60.0
            suffix in HOUR_UNITS -> num * 3600.0
            else -> return null
        }
        // 0 视为无效：超时 0 秒没有意义，也会被 dispatcher 夹回下限，用户会以为没生效
        if (seconds <= 0.0) return null
        return seconds.roundToInt().coerceIn(MIN_SEC, MAX_SEC)
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()

    private val SECOND_UNITS = setOf("s", "sec", "secs", "second", "seconds", "秒")
    private val MINUTE_UNITS = setOf("m", "min", "mins", "minute", "minutes", "分", "分钟")
    private val HOUR_UNITS = setOf("h", "hr", "hrs", "hour", "hours", "小时")
}
