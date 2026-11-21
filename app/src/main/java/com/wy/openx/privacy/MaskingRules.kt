package com.wy.openx.privacy

import com.wy.openx.privacy.MaskingKind.API_KEY
import com.wy.openx.privacy.MaskingKind.ASSIGNED_SECRET
import com.wy.openx.privacy.MaskingKind.BANK_CARD
import com.wy.openx.privacy.MaskingKind.BEARER_TOKEN
import com.wy.openx.privacy.MaskingKind.CHINA_ID
import com.wy.openx.privacy.MaskingKind.EMAIL
import com.wy.openx.privacy.MaskingKind.PHONE
import com.wy.openx.privacy.MaskingKind.PRIVATE_KEY

/**
 * 默认出站脱敏规则集：只匹配「凭据形态」与明确的个人标识。
 *
 * 刻意不匹配 IP、域名、绝对路径——那些是项目事实（`254` 那台机器、容器路径、包名），
 * 掩掉会把正常对话打残：问「254 那台机器怎么了」得到满屏占位符，等于把功能做坏。
 *
 * 规则顺序有意义：私钥块与前缀密钥先吃掉整块，赋值规则才不会只啃掉半截；
 * 身份证排在银行卡之前，否则一个校验和同时通过的 18 位号码会被当成卡号占位。
 */
object MaskingRules {

    fun defaultRules(): List<MaskingRule> = DEFAULT_RULES

    /** 中文关键词单列一条：`\b` 在汉字与全角冒号之间不成立，塞进半角那条会整条失效。 */
    private val ASSIGNED_VALUE_CN = Regex(
        "(访问令牌|访问密钥|密码|口令|令牌|密钥|秘钥|私钥)(\\s*[=:：＝]\\s*)([\"']?)([A-Za-z0-9._~+/=@#$%^&*-]{8,})\\3"
    )

    private val ASSIGNED_VALUE = Regex(
        "(?i)\\b(password|passwd|token|api[_-]?key|apikey|secret|access[_-]?key|access[_-]?token" +
            "|refresh[_-]?token|client[_-]?secret)\\b(\\s*[=:：＝]\\s*)([\"']?)([A-Za-z0-9._~+/=@#$%^&*-]{8,})\\3"
    )

    private val BEARER_VALUE = Regex("(?i)\\b(bearer)(\\s+)([A-Za-z0-9._~+/=-]{12,})")

    private val PREFIXED_KEY = Regex(
        "\\b(?:sk-(?:ant-|proj-)?[A-Za-z0-9_-]{12,}" +
            "|(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}" +
            "|github_pat_[A-Za-z0-9_]{20,}" +
            "|xox[abpr]-[A-Za-z0-9-]{10,}" +
            "|AKIA[0-9A-Z]{16}" +
            "|AIza[0-9A-Za-z_-]{35})"
    )

    /**
     * 头尾的 `[A-Z0-9 ]*` 必须限长：不封顶时，「-----BEGIN private…」这种没有 END 的长文本
     * 会让贪婪量词在 20 万字符上回溯几十万次，单次 mask 卡住几分钟（原实现实测 200KB 要 517 秒）。
     * 真实 PEM 头不会超过 40 字符（RSA / EC / OPENSSH / ENCRYPTED / PGP），封顶不损失识别能力。
     */
    private val PRIVATE_KEY_BLOCK = Regex(
        "-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----"
    )

    /**
     * 本地部分与域名标签都按 RFC 上限封顶，且前面加一条「必须是词首」的后顾断言：
     * 不封顶的 `[class]+@` 在长 base64 / 压缩代码这种没有 @ 的长串上是平方级回溯，
     * 而每个位置都尝试一次也一样费——有了后顾断言，引擎只在长串开头试一次。
     * 原实现实测 1MB 正文 374ms → 48ms，200KB 纯字母长串 393ms → 5ms。
     */
    private val EMAIL_ADDRESS = Regex(
        "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9-]{1,63}\\.){1,8}[A-Za-z]{2,24}"
    )

    private val ID_NUMBER = Regex(
        "\\b[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"
    )

    /** 支持连带分隔符的 4 位分组写法（`4111 1111 1111 1111`），校验和仍按纯数字算。 */
    private val CARD_NUMBER = Regex("\\b\\d{4}(?:[ -]?\\d{4}){3}\\b|\\b\\d{13,19}\\b")

    /**
     * 大陆手机号：11 位、以 1 开头、第二位 3~9。
     *
     * 前后靠 `\b` 划边界：13 位以上的订单号/时间戳不会被截断匹配，
     * 但形如手机号的 11 位串（如某些内部编号）会被掩——这是形状规则的固有代价。
     */
    private val PHONE_NUMBER = Regex("\\b1[3-9]\\d{9}\\b")

    private val DEFAULT_RULES: List<MaskingRule> = listOf(
        MaskingRule(PRIVATE_KEY, PRIVATE_KEY_BLOCK),
        MaskingRule(API_KEY, PREFIXED_KEY),
        MaskingRule(BEARER_TOKEN, BEARER_VALUE, valueGroup = 3),
        MaskingRule(ASSIGNED_SECRET, ASSIGNED_VALUE, valueGroup = 4),
        MaskingRule(ASSIGNED_SECRET, ASSIGNED_VALUE_CN, valueGroup = 4),
        MaskingRule(EMAIL, EMAIL_ADDRESS),
        MaskingRule(CHINA_ID, ID_NUMBER, validator = ::chinaIdChecksumValid),
        MaskingRule(PHONE, PHONE_NUMBER),
        MaskingRule(BANK_CARD, CARD_NUMBER, validator = ::luhnValid),
    )
}
