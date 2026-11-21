package com.wy.openx.plugin

/**
 * 插件 JS 运行时的**标准库补丁**（注入到所有插件引擎，UI 侧与工具侧共用）。
 *
 * ## 为什么需要
 *
 * 宿主用的 QuickJS（Bellard ingot 版 2026-06-04）**没有编译 ECMA-402**：
 * 整份 `quickjs.c` 里 `CONFIG_INTL` 出现 0 次，`Intl.DateTimeFormat` / `js_intl`
 * 的实现痕迹也是 0，可用开关只有 `ALL_UNICODE` / `ATOMICS` / `PRINTF_RNDN` /
 * `STACK_CHECK`。所以插件里写 `new Intl.DateTimeFormat(...)` 会直接抛
 * `Intl is not defined`，且**无法通过打开编译开关修复**——实现不在源码里。
 *
 * 与其让插件作者踩坑后逐个返工，不如在这里补一层够用的实现。
 *
 * ## 边界（重要，别过度承诺）
 *
 * 这是**按需裁剪的替代实现，不是 ECMA-402 规范实现**：
 *  - 只覆盖 `DateTimeFormat`，且只支持 `dateStyle`/`timeStyle`/`hour12`/`timeZone`
 *    之外最常用的 `year`/`month`/`day`/`hour`/`minute`/`second` 选项；
 *  - `timeZone` **只认 `UTC` 和设备本地时区**，不做 IANA 时区数据库解析
 *    （宿主的 proot 容器里没有 tzdata）；
 *  - `NumberFormat` 只做小数位与千分位，**不做货币符号/紧凑记数法**；
 *  - `Collator` / `PluralRules` / `RelativeTimeFormat` / `ListFormat` / `Segmenter`
 *    **不提供**，用到会拿到明确的 `TypeError` 而不是 `undefined` 的误导。
 *
 * 宁可显式报错，也不要给一个行为与规范不一致的实现——那比没有更危险。
 *
 * ## 幂等
 *
 * [SCRIPT] 开头会检查 `globalThis.Intl`，宿主若将来换成带 ECMA-402 的引擎，
 * 原生实现优先，本脚本不覆盖。
 */
object PluginJsStdlib {

    /** 标准库补丁脚本。注入时机：**必须早于插件脚本 eval**。 */
    val SCRIPT: String = """
(function (g) {
    'use strict';
    if (g.Intl && typeof g.Intl.DateTimeFormat === 'function') return;

    var MONTHS_SHORT = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    var MONTHS_LONG  = ['January','February','March','April','May','June','July',
                        'August','September','October','November','December'];
    var DAYS_SHORT   = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
    var DAYS_LONG    = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];

    /** 只接受 UTC：其它 IANA 名需要 tzdata，宿主不保证有，直接报错比给错时区好。 */
    function resolveZone(timeZone) {
        if (timeZone === undefined || timeZone === null || timeZone === '') return null;
        var tz = String(timeZone);
        if (tz === 'UTC' || tz === 'Etc/UTC' || tz === 'Z') return 0;
        if (tz === 'local' || tz === 'Local') return null;
        throw new RangeError(
            'Intl polyfill: unsupported timeZone "' + tz +
            '" (only UTC and the device local zone are available)'
        );
    }

    /** 按偏移量重排 Date 的 UTC 字段，得到目标时区的年月日时分秒。 */
    function shiftToZone(date, offsetMinutes) {
        if (offsetMinutes === null) {
            return {
                year: date.getFullYear(), month: date.getMonth(), day: date.getDate(),
                hour: date.getHours(), minute: date.getMinutes(),
                second: date.getSeconds(), weekday: date.getDay(), off: null
            };
        }
        var shifted = new Date(date.getTime() + offsetMinutes * 60000);
        return {
            year: shifted.getUTCFullYear(), month: shifted.getUTCMonth(), day: shifted.getUTCDate(),
            hour: shifted.getUTCHours(), minute: shifted.getUTCMinutes(),
            second: shifted.getUTCSeconds(), weekday: shifted.getUTCDay(), off: offsetMinutes
        };
    }

    function pad(n, width) {
        var s = String(Math.abs(n));
        while (s.length < width) s = '0' + s;
        return (n < 0 ? '-' : '') + s;
    }

    /** dateStyle/timeStyle → 展开成具体字段集合。 */
    var STYLE_FIELDS = {
        full:    ['weekday', 'year', 'month', 'day', 'hour', 'minute', 'second'],
        long:    ['year', 'month', 'day', 'hour', 'minute', 'second'],
        medium:  ['year', 'month', 'day', 'hour', 'minute', 'second'],
        short:   ['year', 'month', 'day', 'hour', 'minute']
    };
    /** 每个样式在没有显式指定时的字段格式（short 的规范默认是2 位年）。 */
    var STYLE_DEFAULTS = {
        full:   { weekday: 'long',  year: 'numeric', month: 'long',  day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' },
        long:   { year: 'numeric', month: 'long',  day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' },
        medium: { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' },
        short:  { year: '2-digit', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit' }
    };

    function DateTimeFormat(locales, options) {
        if (!(this instanceof DateTimeFormat)) {
            throw new TypeError("Constructor Intl.DateTimeFormat requires 'new'");
        }
        var o = options || {};

        // 只取第一个 locale 的语言部分做月份/星期名选择；够用即可，不做完整协商。
        var lang = 'en';
        if (typeof locales === 'string' && locales) lang = locales.toLowerCase();
        else if (Array.isArray(locales) && locales.length && typeof locales[0] === 'string') {
            lang = String(locales[0]).toLowerCase();
        }
        var zh = lang.indexOf('zh') === 0;
        var monthsShort = zh ? ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月']
                             : MONTHS_SHORT;
        var monthsLong = zh ? ['一月','二月','三月','四月','五月','六月',
                                '七月','八月','九月','十月','十一月','十二月']
                            : MONTHS_LONG;
        var daysShort = zh ? ['周日','周一','周二','周三','周四','周五','周六'] : DAYS_SHORT;

        var zone = resolveZone(o.timeZone);
        var hour12 = !!o.hour12;
        var fields = null;
        var defaults = null;
        if (o.dateStyle || o.timeStyle) {
            fields = {};
            defaults = {};
            var dStyle = o.dateStyle, tStyle = o.timeStyle;
            if (dStyle && STYLE_FIELDS[dStyle]) {
                STYLE_FIELDS[dStyle].forEach(function (f) { fields[f] = true; });
                var dd = STYLE_DEFAULTS[dStyle] || {};
                for (var dk in dd) if (dd[dk] !== undefined) defaults[dk] = dd[dk];
            }
            if (tStyle && STYLE_FIELDS[tStyle]) {
                STYLE_FIELDS[tStyle].forEach(function (f) { fields[f] = true; });
                var td = STYLE_DEFAULTS[tStyle] || {};
                for (var tk in td) if (td[tk] !== undefined && defaults[tk] === undefined) defaults[tk] = td[tk];
            }
        }

        function wants(name) {
            if (fields) return !!fields[name];
            return !!o[name];
        }

        /** 字段展示格式：显式 options 优先于 dateStyle/timeStyle 的样式默认值。 */
        function opt(name) {
            if (o[name] !== undefined) return o[name];
            return defaults ? defaults[name] : undefined;
        }

        function fmtZone(p) {
            if (p.off === null) return '';
            var sign = p.off < 0 ? '-' : '+';
            var abs = Math.abs(p.off);
            return ' GMT' + sign + pad((abs / 60) | 0, 2) + pad(abs % 60, 2);
        }

        // locale 只分「英文式」与「中文式」两种排布：真实 CLDR 规则太多，
        // 假装全面支持反而会让插件作者写出与预期不符的格式。
        var zh = lang.indexOf('zh') === 0;

        function pad2(n) { return pad(n, 2); }

        function hourStr(h) { return hour12 ? String(h) : pad2(h); }

        var p0; // format() 内部赋值，供上面的取字段闭包使用

        this.format = function (value) {
            var d = value instanceof Date ? value : new Date(value);
            if (isNaN(d.getTime())) throw new RangeError('Invalid time value');
            p0 = shiftToZone(d, zone === null ? null : zone);

            var h = p0.hour;
            var ampm = '';
            if (wants('hour') && hour12) {
                ampm = h < 12 ? ' AM' : ' PM';
                h = h % 12;
                if (h === 0) h = 12;
            }

            var datePart, timePart;
            if (zh) {
                datePart = [];
                if (wants('weekday')) datePart.push(opt('weekday') === 'long' ? DAYS_LONG[p0.weekday] : daysShort[p0.weekday]);
                if (wants('year')) datePart.push(p0.year + '年');
                if (wants('month')) datePart.push(opt('month') === 'long' ? monthsLong[p0.month]
                                                  : opt('month') === 'short' ? monthsShort[p0.month]
                                                  : (p0.month + 1) + '月');
                if (wants('day')) datePart.push(p0.day + '日');
                datePart = datePart.join('');

                timePart = '';
                if (wants('hour') || wants('minute') || wants('second')) {
                    timePart = (wants('hour') ? hourStr(h) : '') +
                               (wants('minute') ? ':' + pad2(p0.minute) : '') +
                               (wants('second') ? ':' + pad2(p0.second) : '') + ampm;
                }
            } else {
                // 英文式：月/日/年（month:'long'/'short' 用名字，其余用数字）
                datePart = '';
                if (wants('weekday')) datePart += (opt('weekday') === 'long' ? DAYS_LONG[p0.weekday] : daysShort[p0.weekday]) + ' ';
                var mn = opt('month') || 'numeric';
                var yearPart = (opt('year') === '2-digit') ? pad2(p0.year % 100) : String(p0.year);
                var monthIsName = (mn === 'long' || mn === 'short');
                if (monthIsName) {
                    // “October 11, 2026”
                    var name = (mn === 'long') ? MONTHS_LONG[p0.month] : MONTHS_SHORT[p0.month];
                    var dpart = wants('day') ? String(p0.day) : '';
                    var tail = (wants('year') && wants('month') && wants('day')) ? yearPart : null;
                    datePart = datePart + (tail ? (dpart ? name + ' ' + dpart + ', ' + tail : name + ' ' + tail)
                                               : name);
                } else {
                    var segs = [];
                    if (wants('month')) segs.push(mn === '2-digit' ? pad2(p0.month + 1) : String(p0.month + 1));
                    if (wants('day')) segs.push(opt('day') === '2-digit' ? pad2(p0.day) : String(p0.day));
                    if (wants('year')) segs.push(yearPart);
                    datePart = (datePart + segs.join('/')).trim();
                }

                timePart = '';
                if (wants('hour') || wants('minute') || wants('second')) {
                    timePart = (wants('hour') ? hourStr(h) : '') +
                               (wants('minute') ? ':' + pad2(p0.minute) : '') +
                               (wants('second') ? ':' + pad2(p0.second) : '') + ampm;
                }
            }

            var body;
            if (datePart && timePart) body = datePart + ' ' + timePart;
            else body = datePart || timePart;

            // 时区后缀：**只**在显式要求 timeZoneName 时才带，不跟着 timeZone 一起出。
            if (o.timeZoneName) body += fmtZone(p0);
            return body;
        };

        this.resolvedOptions = function () {
            var r = { locale: lang, timeZone: o.timeZone || 'local', hour12: hour12 };
            if (fields) {
                r.dateStyle = o.dateStyle || null;
                r.timeStyle = o.timeStyle || null;
            } else {
                ['weekday','year','month','day','hour','minute','second'].forEach(function (f) {
                    if (o[f]) r[f] = o[f];
                });
            }
            return r;
        };
    }
    DateTimeFormat.prototype.formatToParts = function (value) {
        var text = this.format(value);
        return [{ type: 'literal', value: text }];
    };

    function NumberFormat(locales, options) {
        if (!(this instanceof NumberFormat)) {
            throw new TypeError("Constructor Intl.NumberFormat requires 'new'");
        }
        var o = options || {};
        var minFrac = o.minimumFractionDigits, maxFrac = o.maximumFractionDigits;

        function decimals(n) {
            var hasMin = typeof minFrac === 'number';
            var hasMax = typeof maxFrac === 'number';
            // 只给 minimumFractionDigits 时固定小数位；给 maximum 时至少留 minimum 位。
            if (hasMin && hasMax) return Math.min(Math.max(minFrac, 0), Math.max(maxFrac, 0));
            if (hasMin) return Math.max(minFrac, 0);
            if (hasMax) return Math.max(maxFrac, 0);
            var s = String(Math.abs(n));
            var dot = s.indexOf('.');
            return dot < 0 ? 0 : Math.min(s.length - dot - 1, 3);
        }

        this.format = function (value) {
            var n = typeof value === 'number' ? value : Number(value);
            if (!isFinite(n)) return String(n);
            var d = decimals(n);
            if (d < 0) d = 0;
            var fixed = Math.abs(n).toFixed(Math.min(d, 100));
            var parts = fixed.split('.');
            parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
            var sign = n < 0 ? '-' : '';
            return sign + parts.join('.');
        };

        this.resolvedOptions = function () {
            return { locale: 'en', useGrouping: o.useGrouping !== false,
                     minimumFractionDigits: 0, maximumFractionDigits: 3 };
        };
    }
    NumberFormat.prototype.formatToParts = function (value) {
        return [{ type: 'literal', value: this.format(value) }];
    };

    // 未实现的规范对象：显式抛错，避免插件拿到 undefined 后写出更隐蔽的 bug
    function unsupported(name) {
        return function () {
            throw new TypeError(
                'Intl.' + name + ' is not implemented in the OpenX plugin runtime'
            );
        };
    }

    g.Intl = {
        DateTimeFormat: DateTimeFormat,
        NumberFormat: NumberFormat,
        Collator: unsupported('Collator'),
        PluralRules: unsupported('PluralRules'),
        RelativeTimeFormat: unsupported('RelativeTimeFormat'),
        ListFormat: unsupported('ListFormat'),
        Segmenter: unsupported('Segmenter'),
        getCanonicalLocales: function (locales) {
            return Array.isArray(locales) ? locales.slice() : [String(locales || 'en')];
        }
    };
})(globalThis);
""".trimIndent()
}