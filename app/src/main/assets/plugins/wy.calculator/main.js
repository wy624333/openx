/**
 * 计算器 —— 工具侧。
 *
 * 工具运行时只有纯 JS（无 require / 无 ctx），所以求值器内联在此，
 * 与 ui/panel.ui.js 里的那份一致。改一处记得同步另一处。
 */
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

var engine = new CalcEngine();

var $ptools = $ptools || [];

$ptools.push({
  name: 'wy_calc',
  description: '计算数学表达式。支持 + - * / %、括号、^幂运算、科学计数、隐式乘法(2pi)、函数(sqrt/sin/cos/tan/log/ln/abs/exp/round/floor/ceil)与常数(pi/e)。',
  parameters: {
    type: 'object',
    properties: {
      expression: {
        type: 'string',
        description: '要计算的表达式，如 2*(3+4)/5、sqrt(16)、2^10'
      }
    },
    required: ['expression']
  },
  handler: function (args) {
    var src = args && args.expression ? String(args.expression) : '';
    if (!src.trim()) {
      return JSON.stringify({ ok: false, error: '表达式为空' });
    }
    var r = engine.evaluate(src);
    if (!r.ok) {
      return JSON.stringify({ ok: false, error: r.error, expression: src });
    }
    return JSON.stringify({
      ok: true,
      expression: src,
      value: r.value,
      result: engine.format(r.value)
    });
  }
});
