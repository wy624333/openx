/**
 * 表达式求值器 —— 递归下降，不依赖 eval（宿主 QuickJS 对 eval 不可靠，且 eval 有注入风险）。
 *
 * 语法：
 *   expr   := term (('+' | '-') term)*
 *   term   := factor (('*' | '/' | '%') factor)*
 *   factor := ('-' | '+')? (number | '(' expr ')' | func '(' args ')')
 *
 * 支持：+ - * / % 、括号、一元正负、科学计数、小数
 *       sqrt/sin/cos/tan/log/ln/abs/exp/pow
 *       常量 pi e
 * 不支持：变量、逗号分隔的多参数（pow 用 ^ 代替）
 */
function CalcEngine() {
    var self = this;

    var CONST = { pi: Math.PI, PI: Math.PI, e: Math.E, E: Math.E };

    var FUNCS = {
        sqrt: function (a) { return Math.sqrt(a); },
        abs: function (a) { return Math.abs(a); },
        sin: function (a) { return Math.sin(a); },
        cos: function (a) { return Math.cos(a); },
        tan: function (a) { return Math.tan(a); },
        asin: function (a) { return Math.asin(a); },
        acos: function (a) { return Math.acos(a); },
        atan: function (a) { return Math.atan(a); },
        ln: function (a) { return Math.log(a); },
        log: function (a) { return Math.log10(a); },
        exp: function (a) { return Math.exp(a); },
        round: function (a) { return Math.round(a); },
        floor: function (a) { return Math.floor(a); },
        ceil: function (a) { return Math.ceil(a); }
    };

    // pow 用 ^ 运算符实现，不走函数表，避免逗号参数解析

    function tokenize(src) {
        var tokens = [];
        var i = 0;
        var s = String(src);

        while (i < s.length) {
            var ch = s.charAt(i);

            if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
                i++;
                continue;
            }

            if (ch >= '0' && ch <= '9' || ch === '.') {
                var start = i;
                var seenDot = false;
                while (i < s.length) {
                    var c = s.charAt(i);
                    if (c >= '0' && c <= '9') { i++; continue; }
                    if (c === '.' && !seenDot) { seenDot = true; i++; continue; }
                    // e/E 后紧跟数字或±数字 → 是本数字的指数（2e3 / 2e-3 / 1.5e2）
                    if (c === 'e' || c === 'E') {
                        var nx = s.charAt(i + 1);
                        if (nx >= '0' && nx <= '9') { i += 2; continue; }
                        if ((nx === '+' || nx === '-') && s.charAt(i + 2) >= '0' && s.charAt(i + 2) <= '9') {
                            i += 3; continue;
                        }
                    }
                    break;
                }
                var text = s.substring(start, i);
                var num = Number(text);
                if (isNaN(num)) throw new Error('无效数字：' + text);
                tokens.push({ t: 'num', v: num });
                continue;
            }

            if (ch === '+' || ch === '-' || ch === '*' || ch === '/' || ch === '%' ||
                ch === '^' || ch === '(' || ch === ')') {
                tokens.push({ t: 'op', v: ch });
                i++;
                continue;
            }

            // 标识符：函数名或常量
            if (/[a-zA-Z_]/.test(ch)) {
                var idStart = i;
                while (i < s.length && /[a-zA-Z0-9_]/.test(s.charAt(i))) i++;
                var id = s.substring(idStart, i);
                tokens.push({ t: 'id', v: id });
                continue;
            }

            throw new Error('无法识别的字符：' + ch);
        }

        return tokens;
    }

    function Parser(tokens) {
        this.k = 0;
        this.tk = tokens;
    }

    Parser.prototype.peek = function () {
        return this.k < this.tk.length ? this.tk[this.k] : null;
    };

    Parser.prototype.next = function () {
        return this.k < this.tk.length ? this.tk[this.k++] : null;
    };

    Parser.prototype.expectOp = function (op) {
        var tok = this.next();
        if (!tok || tok.t !== 'op' || tok.v !== op) {
            throw new Error('缺少 ' + op);
        }
    };

    Parser.prototype.parseExpr = function () {
        var left = this.parseTerm();
        while (true) {
            var tok = this.peek();
            if (!tok || tok.t !== 'op' || (tok.v !== '+' && tok.v !== '-')) break;
            this.next();
            var right = this.parseTerm();
            left = (tok.v === '+') ? (left + right) : (left - right);
        }
        return left;
    };

    Parser.prototype.parseTerm = function () {
        var left = this.parsePower();
        while (true) {
            var tok = this.peek();
            if (!tok || tok.t !== 'op' || (tok.v !== '*' && tok.v !== '/' && tok.v !== '%')) break;
            this.next();
            var right = this.parsePower();
            if (tok.v === '*') left = left * right;
            else if (tok.v === '/') {
                if (right === 0) throw new Error('除数为 0');
                left = left / right;
            } else {
                if (right === 0) throw new Error('取模的除数为 0');
                left = left % right;
            }
        }
        return left;
    };

    // 幂运算右结合，优先级高于 */；隐式乘法（2pi、2(3+4)）也在此处理，
    // 因为隐式乘法的左边可能是一个完整因子，右边只可能是数字/括号/标识符。
    Parser.prototype.parsePower = function () {
        var left = this.parseFactor();
        while (true) {
            var tok = this.peek();
            if (tok && tok.t === 'op' && tok.v === '^') {
                this.next();
                var e = this.parsePower();
                left = Math.pow(left, e);
                if (isNaN(left)) throw new Error('幂运算结果无效');
                continue;
            }
            // 隐式乘法的左值已经算出来了，这里接着乘右因子
            if (tok && (tok.t === 'num' || tok.t === 'id' ||
                (tok.t === 'op' && tok.v === '('))) {
                left = left * this.parseFactor();
                continue;
            }
            break;
        }
        return left;
    };

    Parser.prototype.parseFactor = function () {
        var tok = this.peek();

        if (tok && tok.t === 'op' && (tok.v === '-' || tok.v === '+')) {
            this.next();
            var v = this.parseFactor();
            return tok.v === '-' ? -v : v;
        }

        tok = this.next();
        if (!tok) throw new Error('表达式不完整');

        if (tok.t === 'num') return tok.v;

        if (tok.t === 'op' && tok.v === '(') {
            var v2 = this.parseExpr();
            this.expectOp(')');
            return v2;
        }

        if (tok.t === 'id') {
            var name = tok.v;
            var lower = name.toLowerCase();
            var nextTok = this.peek();

            // 函数调用：名字后跟 (
            if (nextTok && nextTok.t === 'op' && nextTok.v === '(') {
                var fn = FUNCS[lower];
                if (!fn) throw new Error('未知函数：' + name);
                this.next(); // 吃掉 (
                var arg = this.parseExpr();
                this.expectOp(')');
                var out = fn(arg);
                if (typeof out !== 'number' || isNaN(out)) {
                    throw new Error(name + ' 定义域错误');
                }
                return out;
            }

            // 常量
            if (Object.prototype.hasOwnProperty.call(CONST, name)) return CONST[name];
            var lowConst = CONST[lower];
            if (lowConst !== undefined) return lowConst;

            // 标识符只能作为隐式乘法的右因子被上面消费掉；能走到这里说明它前面没数字
            throw new Error('无法识别的名称：' + name);
        }

        throw new Error('意外的符号：' + (tok.v || tok.t));
    };

    /**
     * 求值入口。返回 { ok, value, error }。
     * 非有限结果（NaN / Infinity）视为错误，避免显示 Infinity。
     */
    self.evaluate = function (source) {
        var src = String(source == null ? '' : source).trim();
        if (!src) return { ok: false, error: '表达式为空' };

        try {
            var tokens = tokenize(src);
            if (!tokens.length) return { ok: false, error: '表达式为空' };

            var p = new Parser(tokens);
            var value = p.parseExpr();

            if (p.k < p.tk.length) {
                var left = p.tk[p.k];
                throw new Error('多余的内容：' + left.v);
            }

            if (typeof value !== 'number' || isNaN(value)) {
                return { ok: false, error: '结果不是有效数字' };
            }
            if (!isFinite(value)) {
                return { ok: false, error: '结果超出数值范围' };
            }

            return { ok: true, value: value };
        } catch (e) {
            return { ok: false, error: (e && e.message) ? e.message : String(e) };
        }
    };

    /**
     * 格式化：整数直接显示，小数最多 10 位有效数字，去掉多余的 0。
     * 1e21 以上转科学计数（JS 超过 21 位会自动转 exponential，字符串化会变 "1e+21"）。
     */
    self.format = function (value) {
        if (typeof value !== 'number' || !isFinite(value)) return String(value);
        if (Number.isInteger(value) && Math.abs(value) < 1e21) return String(value);

        var abs = Math.abs(value);
        if (abs !== 0 && (abs >= 1e15 || abs < 1e-6)) {
            return value.toExponential(6).replace(/\.?0+e/, 'e');
        }

        // 用 toPrecision 再去掉尾随 0
        var rounded = Number(value.toPrecision(12));
        return String(rounded);
    };

    /** 供 AI/测试调用：求值并返回 JSON 字符串。 */
    self.evaluateToJson = function (source) {
        var r = self.evaluate(source);
        if (!r.ok) return JSON.stringify({ ok: false, error: r.error });
        return JSON.stringify({
            ok: true,
            value: r.value,
            result: self.format(r.value)
        });
    };

    return self;
}

/**
 * 计算器面板。
 *
 * 求值器直接内联在本文件里：宿主 UI 运行时没有 require，多文件共享代码不可行。
 * 逻辑与 lib/calc.js 一致（递归下降，不用 eval）。
 *
 * 交互：按钮拼表达式 → 表达式栏实时编辑 → 「=」求值 → 进历史。
 * 历史存 ctx.store，关掉面板再打开还在。
 */
var engine = new CalcEngine();

function Screen(ctx) {
    const exprState = ctx.useState('calc_expr', '');
    const resultState = ctx.useState('calc_result', '');
    const errState = ctx.useState('calc_error', '');
    const histState = ctx.useState('calc_hist', []);

    const expr = exprState[0];
    const setExpr = exprState[1];
    const result = resultState[0];
    const setResult = resultState[1];
    const errorMsg = errState[0];
    const setError = errState[1];
    const history = histState[0];
    const setHistory = histState[1];

    const loadedRef = ctx.useRef('calc_loaded', false);

    if (!loadedRef.current) {
        loadedRef.current = true;
        const saved = ctx.store.get('history', []);
        if (Array.isArray(saved)) setHistory(saved);
    }

    function saveHistory(list) {
        setHistory(list);
        ctx.store.set('history', list);
    }

    function run() {
        const source = String(expr || '').trim();
        if (!source) {
            setError('表达式为空');
            setResult('');
            return;
        }
        const r = engine.evaluate(source);
        if (!r.ok) {
            setError(r.error);
            setResult('');
            return;
        }
        const text = engine.format(r.value);
        setError('');
        setResult(text);
        saveHistory([{ e: source, r: text }].concat(history).slice(0, 50));
    }

    /** 按钮往表达式里追加内容。连续两次数字之间自动补运算符。 */
    function append(text) {
        setExpr(String(expr || '') + text);
    }

    function backspace() {
        const cur = String(expr || '');
        setExpr(cur.slice(0, -1));
    }

    function clearAll() {
        setExpr('');
        setResult('');
        setError('');
    }

    function reuse(item) {
        setExpr(item.e);
        setResult('');
        setError('');
    }

    function removeHistoryAt(index) {
        const list = history.slice();
        list.splice(index, 1);
        saveHistory(list);
    }

    function clearHistory() {
        saveHistory([]);
    }

    const KEYS = [
        ['C', 'clearAll'], ['(', 'k('], [')', 'k)'], ['⌫', 'backspace'],
        ['7', 'k7'], ['8', 'k8'], ['9', 'k9'], ['÷', 'k/'],
        ['4', 'k4'], ['5', 'k5'], ['6', 'k6'], ['×', 'k*'],
        ['1', 'k1'], ['2', 'k2'], ['3', 'k3'], ['−', 'k-'],
        ['.', 'k.'], ['0', 'k0'], ['%', 'k%'], ['+', 'k+']
    ];
    // Row 内不能用 fillMaxWidth：Compose 里第一个 fillMaxWidth 子元素会吃满整行宽度，
    // 后面的按钮被挤出屏幕。改成固定宽度 + spacedBy 排布。
    const COLS = 4;
    const KEY_W = 64;
    const KEY_H = 46;

    const TOKENS = {
        'k0': '0', 'k1': '1', 'k2': '2', 'k3': '3', 'k4': '4',
        'k5': '5', 'k6': '6', 'k7': '7', 'k8': '8', 'k9': '9',
        'k+': '+', 'k-': '-', 'k*': '*', 'k/': '/', 'k%': '%',
        'k.': '.', 'k(': '(', 'k)': ')'
    };

    function onKey(action) {
        if (action === 'clearAll') return clearAll();
        if (action === 'backspace') return backspace();
        if (action === 'run') return run();
        if (TOKENS[action]) return append(TOKENS[action]);
    }

    const keyRows = [];
    for (let i = 0; i < KEYS.length; i += COLS) {
        const cells = [];
        for (let c = 0; c < COLS; c++) {
            const item = KEYS[i + c];
            if (!item) {
                cells.push(ctx.UI.Spacer({ width: KEY_W, height: KEY_H }));
                continue;
            }
            const action = item[1];
            var isDigitKey = action.length === 2 && action.charAt(1) >= '0' && action.charAt(1) <= '9';
            var isEquals = item[2] === true;
            cells.push(ctx.UI.Button({
                text: item[0],
                width: KEY_W,
                height: isEquals ? KEY_H * 2 + 8 : KEY_H,
                fontSize: isEquals ? 26 : 18,
                background: isEquals ? 'primary' : (isDigitKey ? 'transparent' : '#242A32'),
                onClick: (function (a) { return function () { onKey(a); }; })(action)
            }));
        }
        keyRows.push(ctx.UI.Row({ fillMaxWidth: true, horizontalArrangement: 'spacedBy' }, cells));
    }

    // '=' 单独一行，靠右对齐（Column 里的 Row 用 spaceBetween 会把占位推到左边，故用固定宽度）
    const eqSpacer = ctx.UI.Spacer({ width: KEY_W * 3 + 8 * 3, height: 1 });
    const eqButton = ctx.UI.Button({
        text: '=',
        width: KEY_W,
        height: 56,
        fontSize: 26,
        background: 'primary',
        onClick: run
    });

    const head = [
        ctx.UI.TextField({
            value: expr,
            onValueChange: setExpr,
            placeholder: '也可以直接粘贴整串，如 2*(3+4)/5',
            fillMaxWidth: true
        })
    ];

    const display = [];
    if (errorMsg) {
        display.push(ctx.UI.Text({
            text: '⚠ ' + errorMsg,
            fontSize: 13,
            color: 'error',
            fillMaxWidth: true
        }));
    } else if (result) {
        display.push(ctx.UI.Text({
            text: '= ' + result,
            fontSize: 26,
            bold: true,
            color: 'primary',
            fillMaxWidth: true
        }));
    }

    const histNodes = [];
    if (history.length) {
        histNodes.push(ctx.UI.Spacer({ height: 12 }));
        histNodes.push(ctx.UI.Row({ fillMaxWidth: true, horizontalArrangement: 'spacedBy' }, [
            ctx.UI.Text({
                text: '历史（' + history.length + '）',
                fontSize: 12,
                color: 'textsecondary'
            }),
            ctx.UI.TextButton({
                text: '清空',
                onClick: clearHistory
            })
        ]));
        for (let i = 0; i < history.length && i < 8; i++) {
            (function (item, index) {
                histNodes.push(ctx.UI.Row({ fillMaxWidth: true, horizontalArrangement: 'spacedBy' }, [
                    ctx.UI.ListItem({
                        title: item.e,
                        subtitle: item.r,
                        onClick: function () { reuse(item); }
                    }),
                    ctx.UI.TextButton({
                        text: '删',
                        color: 'error',
                        onClick: function () { removeHistoryAt(index); }
                    })
                ]));
            })(history[i], i);
        }
    }

    const children = [].concat(head, display, [
        ctx.UI.Spacer({ height: 10 })
    ], keyRows, [
        ctx.UI.Spacer({ height: 8 }),
        ctx.UI.Row({ fillMaxWidth: true, horizontalArrangement: 'start' }, [eqSpacer, eqButton]),
        ctx.UI.Spacer({ height: 12 })
    ], histNodes);

    return ctx.UI.Column({ padding: 14, fillMaxWidth: true }, children);
}

module.exports = Screen;