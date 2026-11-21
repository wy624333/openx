/**
 * 插件运行时集成测试。
 *
 * 与 _precheck.js 的区别：
 *   · _precheck.js 用 **mock ctx** 检查插件脚本自身（语法、权限、组件名）
 *   · 本文件用**真实运行时脚本**（从 PluginUiRuntime.kt 抽出）+ mock NativeInterface，
 *     真跑一遍 render → dispatch，能抓到 mock ctx 抓不到的问题：
 *
 *     例如：ctx.copy 存在，但运行时忘了定义 __openxClipboardCopy 转发函数
 *     → 点击时 ReferenceError → 被插件 try/catch 吞掉 → 用户只看到"点了没反应"
 *     （这个 bug 真机踩过，就是本文件要防的）
 *
 * 用法：node _integration.js
 */
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const ROOT = __dirname;
const PLUGIN = path.join(ROOT, 'sample-toolkit');
const RUNTIME_KT = path.join(
    ROOT, '..', 'app/src/main/java/com/wy/openx/plugin/ui/PluginUiRuntime.kt'
);

// ---------------------------------------------------------------- 抽取运行时 JS
function extractRuntimeScript() {
    const kt = fs.readFileSync(RUNTIME_KT, 'utf8');
    const start = kt.indexOf('val SCRIPT: String = """');
    if (start < 0) throw new Error('未找到 SCRIPT 定义');
    const bodyStart = kt.indexOf('"""', start) + 3;
    const bodyEnd = kt.indexOf('"""', bodyStart);
    if (bodyEnd < 0) throw new Error('未找到 SCRIPT 结束标记');
    return kt.slice(bodyStart, bodyEnd);
}

// ---------------------------------------------------------------- mock 宿主
// 与 PluginUiHostBridge 的方法名一一对应。缺一个就说明转发漏了。
const HOST_METHODS = [
    'openxShowToast', 'openxPersistEnv', 'openxRequestRerender',
    'openxCallToolAsync', 'openxHttpAsync',
    'openxClipboardCopy', 'openxClipboardPaste', 'openxNotifyAsync',
    'openxReadFileAsync', 'openxWriteFileAsync', 'openxListFilesAsync',
    'openxPickFileAsync', 'openxSaveFileAsync', 'openxPickImageAsync'
];

function makeContext() {
    const calls = [];
    const pendingIssued = [];
    function dispatch(method, argsJson) {
        calls.push(method);
        if (!HOST_METHODS.includes(method)) {
            throw new Error('宿主没有方法 ' + method);
        }
        switch (method) {
            case 'openxHttpAsync': return JSON.stringify({ ok: true, status: 200, body: '{}' });
            case 'openxCallToolAsync': return JSON.stringify({ ok: true, result: '{}' });
            case 'openxClipboardPaste': return JSON.stringify({ ok: true, text: 'pasted' });
            case 'openxReadFileAsync': return JSON.stringify({ ok: true, content: 'file-content', size: 12 });
            case 'openxWriteFileAsync': return JSON.stringify({ ok: true, size: 5 });
            case 'openxListFilesAsync': return JSON.stringify({ ok: true, files: [] });
            // 选择器三类要走 pending（真实宿主就是挂起等用户，不阻塞 JS 线程）
            case 'openxPickFileAsync': pendingIssued.push('file'); return 'pending:test_file';
            case 'openxSaveFileAsync': pendingIssued.push('save'); return 'pending:test_save';
            case 'openxPickImageAsync': pendingIssued.push('image'); return 'pending:test_image';
            default: return JSON.stringify({ ok: true });
        }
    }

    const sb = {
        console,
        JSON, Date, Math, String, Number, Boolean, Object, Array, RegExp, Error,
        TypeError, Promise, Symbol, Set, Map, isNaN, parseInt, parseFloat,
        Proxy
    };
    sb.globalThis = sb;

    // 与 QuickJsNativeCompatScriptBuilder 一致：NativeInterface 是 Proxy，
    // 任意属性访问都变成 callHost(属性名, args)。
    // 若这里用普通对象，测试会误报（真机能跑、测试报 not a function）。
    const native = new Proxy({}, {
        get: function (_t, prop) {
            if (prop === '__call') {
                return function (method, argsJson) { return dispatch(String(method), argsJson); };
            }
            return function () {
                return dispatch(String(prop), JSON.stringify(Array.prototype.slice.call(arguments)));
            };
        }
    });
    sb.NativeInterface = native;
    sb.exports = {};
    sb.module = { exports: sb.exports };
    vm.createContext(sb);
    return { sb, calls, pendingIssued };
}

let failed = 0;
// fn 可以返回 Promise（ctx API 现在是真异步的），那就等它 settle 再判成败。
const pendingChecks = [];
function check(label, fn) {
    let r;
    try { r = fn(); }
    catch (e) { failed++; console.log('✗ ' + label + ' → ' + e.message); return; }
    if (r && typeof r.then === 'function') {
        pendingChecks.push(
            r.then(function () { console.log('✓ ' + label); },
                  function (e) { failed++; console.log('✗ ' + label + ' → ' + e.message); })
        );
        return;
    }
    console.log('✓ ' + label);
}

function finish() {
    return Promise.all(pendingChecks).then(function () {
        if (failed === 0) {
            console.log('\n全部通过 ✓');
        } else {
            console.log('\n' + failed + ' 项失败 ✗');
            process.exitCode = 1;
        }
    });
}

const { sb, calls, pendingIssued } = makeContext();

check('运行时脚本可注入', () => {
    const script = extractRuntimeScript();
    vm.runInContext(script, sb, { filename: 'runtime.js' });
});

check('桥函数自检通过（所有 __openx* 转发都已定义）', () => {
    const raw = vm.runInContext('__openxBridgeSelfCheck()', sb);
    const r = JSON.parse(raw);
    if (!r.ok) throw new Error('缺转发：' + r.missing.join(', '));
});

const manifest = JSON.parse(fs.readFileSync(path.join(PLUGIN, 'plugin.json'), 'utf8'));

check('插件 UI 脚本可加载', () => {
    const src = fs.readFileSync(path.join(PLUGIN, manifest.ui), 'utf8');
    vm.runInContext(src, sb, { filename: manifest.ui });
});

check('首次渲染产出节点树', () => {
    const opts = JSON.stringify({
        permissions: manifest.permissions,
        env: {}
    });
    const raw = vm.runInContext('__openxRenderUi(' + JSON.stringify(opts) + ')', sb);
    const r = JSON.parse(raw);
    if (!r.tree || !r.tree.type) throw new Error('未产出节点树');
    sb.__last = r;
});

check('能遍历出可点击动作并逐个派发（不抛异常）', () => {
    const r = sb.__last;
    const actions = new Set();
    (function walk(n) {
        if (!n || typeof n !== 'object') return;
        const p = n.props || {};
        Object.keys(p).forEach(k => {
            const v = p[k];
            if (v && typeof v === 'object' && v.__actionId) actions.add(v.__actionId);
        });
        (n.children || []).forEach(walk);
    })(r.tree);

    if (actions.size === 0) throw new Error('没找到任何动作（onClick 未注册？）');
    console.log('  发现 ' + actions.size + ' 个动作');

    let dispatched = 0;
    actions.forEach(id => {
        const raw = vm.runInContext(
            '__openxDispatchAction(' + JSON.stringify(id) + ', "{}")', sb
        );
        if (typeof raw !== 'string') throw new Error(id + ' 派发未返回字符串');
        const out = JSON.parse(raw);
        if (out.error) throw new Error(id + ' 派发失败：' + out.error);
        dispatched++;
    });
    console.log('  成功派发 ' + dispatched + ' 个');
});

check('派发过程中真实调用到了宿主桥', () => {
    // 至少应看到 requestRerender（useState setter）或其它桥调用
    const relevant = calls.filter(c => c.startsWith('openx'));
    if (relevant.length === 0) throw new Error('没有任何宿主桥调用，说明事件没接上');
    console.log('  宿主调用：' + [...new Set(relevant)].join(', '));
});

/**
 * 回归：setter 不在派发中途回调宿主。
 *
 * 之前 useState 的 setter 直接调 openxRequestRerender，而 setter 是在
 * __openxDispatchAction 执行**中途**被触发的（那一刻宿主 session 锁还被持有）。
 * 于是 Compose 重组与派发抢同一份状态 → 界面停在旧树、所有交互看着都失效。
 * 现在改为：JS 只置标志位，宿主在派发**结束后**用 __openxConsumeRerenderRequest 询问。
 *
 * 复用上面那个 context（runtime 只注入一次，与真实宿主一致；
 * 新建 context 会因 IIFE 作用域差异误报，不忠实于真实场景）。
 */
check('setter 只记账、由宿主在派发后统一询问（不中途回调）', () => {
    // 1) 派发过程中不得出现 openxRequestRerender
    const during = calls.filter(c => c === 'openxRequestRerender');
    if (during.length > 0) {
        throw new Error('派发过程中仍回调了 openxRequestRerender（会导致交互失效）');
    }
    // 2) 标志位机制存在且可消费
    const flag = vm.runInContext('__openxConsumeRerenderRequest()', sb);
    if (flag !== 'true' && flag !== 'false') {
        throw new Error('__openxConsumeRerenderRequest 返回异常：' + flag);
    }
    console.log('  0 次中途回调；标志位可消费');
});

// ---- 真实点击路径：点 Tabs 第二个标签，内容必须真的换过去 ----
//
// 回归 bug：__openxDispatchAction 见到 payload 有 value 字段就拆包，把 Tabs 的
// {index:1,value:"文件与图片"} 拆成字符串传给 setter，index 丢失 → 点标签切不动。
// 所以这里必须**从 tab0 出发、模拟真实点击**，不能直接把 state 预置成 1 绕过去。
check('点 Tabs 第二个标签能真实切换内容', () => {
    const opts = JSON.stringify({
        permissions: manifest.permissions,
        env: {},
        state: { tab: 0, booted: true }
    });
    const rendered = vm.runInContext('__openxRenderUi(' + JSON.stringify(opts) + ')', sb);

    // 不能再渲一次：__openxRenderUi 每次都重建 actionRegistry，
    // 多渲一次拿到的 actionId 派发时已失效。所以从刚才那次返回值里取树。
    let tabsNode = null;
    const first = JSON.parse(rendered);
    (function find(n) {
        if (!n || typeof n !== 'object' || tabsNode) return;
        if (n.type === 'Tabs') { tabsNode = n; return; }
        (n.children || []).forEach(find);
    })(first.tree);
    if (!tabsNode) throw new Error('没找到 tabs 节点');
    const onSelect = tabsNode.props.onSelect;
    if (!onSelect || !onSelect.__actionId) throw new Error('tabs 没有 onSelect');

    // 模拟真实点击：宿主传 rawValue=false，payload 含 index/value/label
    const out = vm.runInContext('__openxDispatchAction(' + JSON.stringify(onSelect.__actionId) +
        ', \'{"index":1,"value":"\\u6587\\u4ef6\\u4e0e\\u56fe\\u7247","label":"\\u6587\\u4ef6\\u4e0e\\u56fe\\u7247"}\', false)', sb);
    const after = JSON.parse(out);
    if (after.error) throw new Error('派发失败：' + after.error);
    if (after.state.tab !== 1) throw new Error('点击后 tab 应为 1，实际 ' + JSON.stringify(after.state.tab));
    const txt = JSON.stringify(after.tree);
    // 第一个 tab 独有"交互组件"，第二个 tab 独有"文件与系统"，用它判内容真换了
    if (txt.includes('交互组件')) throw new Error('切换后仍是第一个 tab 的内容');
    if (!txt.includes('文件与系统')) throw new Error('切换后未见"文件与系统"分区');
    console.log('  tab0 --点击--> tab1，内容已换');
});

// ---- 裸值回调契约：Slider/TextField 传裸值，写成 p.value 会静默拿到 undefined ----
//
// 真机踩过：示例写 onValueChange: p => setVol(Math.round(p.value))，但宿主传的是裸值，
// p.value 是 undefined → Math.round(undefined) = NaN → 界面显示"音量 null"。
// 这类错误不抛异常、只表现为值变 NaN，所以必须用断言拦住。
check('裸值回调不会被当成 payload（Slider/TextField）', () => {
    const opts = JSON.stringify({
        permissions: manifest.permissions,
        env: {},
        state: { tab: 0, vol: 50, note: '', booted: true }
    });
    const r = JSON.parse(vm.runInContext('__openxRenderUi(' + JSON.stringify(opts) + ')', sb));
    let slider = null, textfield = null;
    (function find(n) {
        if (!n || typeof n !== 'object') return;
        if (n.type === 'Slider' && !slider) slider = n;
        if (n.type === 'TextField' && !textfield) textfield = n;
        (n.children || []).forEach(find);
    })(r.tree);
    if (!slider) throw new Error('没找到 Slider');

    // 宿主对 Slider 传 rawValue=true，payload 为 {value: 数字} → JS 应收到裸数字
    const out = JSON.parse(vm.runInContext('__openxDispatchAction(' +
        JSON.stringify(slider.props.onValueChange.__actionId) +
        ', \'{"value":73}\', true)', sb));
    const v = out.state.vol;
    if (typeof v !== 'number' || isNaN(v)) {
        throw new Error('Slider 回调后 vol 应是数字，实际 ' + JSON.stringify(v) +
            '（示例 JS 若写成 p.value 就会得到 NaN）');
    }
    if (v !== 73) throw new Error('Slider 值应为 73，实际 ' + v);
    console.log('  Slider 裸值 → vol=' + v);
});

// ---- ctx API 必须是 Promise，且结果已解包成对象 ----
//
// 两个历史坑：
//  1. 宿主返回 JSON 字符串没解包 → res.ok 恒 undefined → "写文件失败：未知"，
//     但文件其实已写进沙箱。
//  2. 宿主用 runBlocking 同步等选图 → QuickJS 60 秒超时，用户挑图稍慢就整条动作失败。
// 现在 ctx API 返回 Promise；宿主同步能出结果的立即 resolve，要等用户的走 pending。
check('ctx API 返回 Promise，结果已解包成对象', () => {
    const r = JSON.parse(vm.runInContext('__openxRenderUi(' + JSON.stringify(JSON.stringify({
        permissions: manifest.permissions, env: {}, state: { tab: 1, booted: true }
    })) + ')', sb));
    if (!r.tree) throw new Error('渲染失败');

    const api = vm.runInContext('ctx', sb);
    let settled = null;

    // 同步快路径：宿主直接给 JSON → Promise 立刻 resolve 出对象
    settled = api.writeFile('a.txt', 'hi');
    if (typeof settled.then !== 'function') throw new Error('writeFile 没返回 Promise');

    // 挂起路径：宿主返回 pending:<id>，Promise 在 __openxResolvePending 之前不 settle
    let pickDone = false;
    const pendingRaw = api.pickImage().then(function (r) { pickDone = true; return r; });
    if (typeof pendingRaw.then !== 'function') throw new Error('pickImage 没返回 Promise');
    if (pickDone) throw new Error('宿主还没结算，pickImage 不该已经完成');
    if (!pendingIssued.includes('image')) throw new Error('宿主没收到选图请求');

    // 宿主结算后，await 的后续代码应该真的跑起来
    const settled2 = vm.runInContext(
        "__openxResolvePending('test_image', JSON.stringify({ok:true,cancelled:false,data:'data:image/png;base64,AAA'}))",
        sb);

    return pendingRaw.then(function (img) {
        if (!pickDone) throw new Error('结算后 pickImage 的 then 仍未执行');
        if (img.ok !== true || img.data !== 'data:image/png;base64,AAA') {
            throw new Error('选图结果不对：' + JSON.stringify(img));
        }
    }).then(function () { return settled; }).then(function (w) {
        if (!w || typeof w !== 'object') throw new Error('writeFile 结果不是对象：' + JSON.stringify(w));
        if (w.ok !== true) throw new Error('writeFile 应 ok，实际 ' + JSON.stringify(w));
        if (typeof w.size !== 'number') throw new Error('缺 size 字段，实际 ' + JSON.stringify(w));
        return api.paste();
    }).then(function (p) {
        if (p.ok !== true || typeof p.text !== 'string') throw new Error('paste 返回不对：' + JSON.stringify(p));
        return api.copy('x');
    }).then(function (c) {
        if (c.ok !== true) throw new Error('copy 返回不对：' + JSON.stringify(c));
        console.log('  writeFile/paste/copy 均 resolve 出对象；pickImage 挂起等结算');
    });
});

// ---- 覆盖第二个 tab：文件 / 图片 / 剪贴板 / 通知（用户报"点了没反应"的就是这批）----
check('第二个 tab（文件与图片）也能渲染与派发', () => {
    const opts = JSON.stringify({
        permissions: manifest.permissions,
        env: {},
        state: { tab: 1, booted: true }   // 直接把 tab 预置为 1
    });
    const raw = vm.runInContext('__openxRenderUi(' + JSON.stringify(opts) + ')', sb);
    const r = JSON.parse(raw);
    if (!r.tree) throw new Error('未产出节点树');

    const actions = new Set();
    (function walk(n) {
        if (!n || typeof n !== 'object') return;
        const p = n.props || {};
        Object.keys(p).forEach(k => {
            const v = p[k];
            if (v && typeof v === 'object' && v.__actionId) actions.add(v.__actionId);
        });
        (n.children || []).forEach(walk);
    })(r.tree);

    if (actions.size === 0) throw new Error('tab1 没找到动作');
    actions.forEach(id => {
        const out = JSON.parse(
            vm.runInContext('__openxDispatchAction(' + JSON.stringify(id) + ', "{}")', sb)
        );
        if (out.error) throw new Error(id + ' 派发失败：' + out.error);
    });
    console.log('  tab1 派发 ' + actions.size + ' 个动作');

    const used = new Set(calls.filter(c => c.startsWith('openx')));
    // 只断言**示例实际用到**的那几个（pickFile/saveFile/listFiles 示例里没按钮，
    // 它们的转发已由前面的 __openxBridgeSelfCheck 覆盖）
    const expected = ['openxClipboardCopy', 'openxClipboardPaste', 'openxNotifyAsync',
                      'openxReadFileAsync', 'openxWriteFileAsync', 'openxPickImageAsync'];
    const missing = expected.filter(m => !used.has(m));
    if (missing.length) throw new Error('这些二期桥未被调用：' + missing.join(', '));
    console.log('  二期桥已调用：' + expected.join(', '));
});

finish();
