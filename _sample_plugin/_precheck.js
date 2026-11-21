/**
 * 示例插件预检：用 Node + mock ctx 真跑一遍 UI 脚本。
 *
 * 参照 /sdcard/Download/plugin-testkit 的 _harness.js 思路 —— 不开 App 就能
 * 查出语法错误、权限逻辑错误、以及"组件名写错导致渲染不出来"这类问题。
 *
 * 用法：node _precheck.js
 */
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const ROOT = __dirname;
const PLUGIN = path.join(ROOT, 'sample-toolkit');

function mockCtx(perms, initial) {
    const state = Object.assign({}, initial || {});
    const env = {};
    const store = {};
    const calls = [];
    const req = (n) => {
        if (!perms.includes(n)) throw new Error('缺少权限 "' + n + '"');
    };
    const UI = new Proxy({}, {
        get: (t, k) => (props, children) => ({ type: String(k), props: props || {}, children: children || [] })
    });
    const ctx = {
        UI,
        useState: (k, i) => {
            if (!(k in state)) state[k] = i;
            return [state[k], v => { state[k] = (typeof v === 'function') ? v(state[k]) : v; }];
        },
        useMemo: (k, f) => f(),
        useRef: (k, i) => ({ current: i }),
        getEnv: k => { req('env'); return env[k]; },
        setEnv: (k, v) => { req('env'); env[k] = v; return true; },
        setEnvs: m => { req('env'); Object.assign(env, m); return true; },
        store: {
            get: (k, d) => { req('env'); return k in store ? store[k] : d; },
            set: (k, v) => { req('env'); store[k] = v; return true; },
            remove: k => { req('env'); delete store[k]; return true; },
            keys: () => { req('env'); return Object.keys(store); },
            clear: () => { req('env'); return true; }
        },
        showToast: m => { req('toast'); calls.push(['toast', m]); return true; },
        reportError: () => {},
        callTool: () => { req('call_tool'); return '{}'; },
        http: () => { req('http'); return '{"ok":true}'; },
        fetch: () => { req('http'); return '{"ok":true}'; },
        copy: () => { req('clipboard'); calls.push(['copy']); return '{"ok":true}'; },
        paste: () => { req('clipboard'); return '{"ok":true,"text":"x"}'; },
        notify: () => { req('notify'); calls.push(['notify']); return '{"ok":true}'; },
        readFile: () => { req('file'); return '{"ok":true,"content":""}'; },
        writeFile: () => { req('file'); return '{"ok":true,"size":1}'; },
        listFiles: () => { req('file'); return '{"ok":true,"files":[]}'; },
        pickFile: () => { req('file'); return '{"ok":true,"cancelled":true}'; },
        saveFile: () => { req('file'); return '{"ok":true,"cancelled":true}'; },
        pickImage: () => { req('file'); return '{"ok":true,"cancelled":true}'; }
    };
    return { ctx, state };
}

// 宿主实际支持的组件（与 UiRenderer / UiRendererExt / UiRendererOverlay 对齐）
const SUPPORTED = new Set([
    'column', 'row', 'box', 'spacer', 'lazycolumn', 'lazyrow', 'card', 'surface',
    'text', 'textfield', 'button', 'textbutton', 'iconbutton', 'switch', 'checkbox',
    'icon', 'divider', 'horizontaldivider', 'verticaldivider',
    'linearprogressindicator', 'circularprogressindicator',
    'image', 'slider', 'radiobutton', 'dropdown', 'tabs', 'listitem', 'chip',
    'dialog', 'modalbottomsheet'
]);

function collectTypes(node, out) {
    if (!node || typeof node !== 'object') return;
    if (node.type) out.add(node.type.toLowerCase());
    (node.children || []).forEach(c => collectTypes(c, out));
}

let failed = 0;

function check(label, fn) {
    try {
        fn();
        console.log('✓ ' + label);
    } catch (e) {
        failed++;
        console.log('✗ ' + label + ' → ' + e.message);
    }
}

// ---------- 1. 清单 ----------
let manifest;
check('plugin.json 可解析', () => {
    manifest = JSON.parse(fs.readFileSync(path.join(PLUGIN, 'plugin.json'), 'utf8'));
    if (!manifest.id) throw new Error('缺 id');
    if (!manifest.ui) throw new Error('缺 ui');
});

// ---------- 2. 工具脚本 ----------
check('main.js 声明了工具', () => {
    const sb = { $ptools: [], console };
    vm.createContext(sb);
    vm.runInContext(fs.readFileSync(path.join(PLUGIN, 'main.js'), 'utf8'), sb, { filename: 'main.js' });
    if (!sb.$ptools.length) throw new Error('没有 push 任何工具');
    const prefixes = new Set(sb.$ptools.map(t => String(t.name).split('_')[0]));
    if (prefixes.size > 1) throw new Error('工具名前缀不一致：' + [...prefixes].join(', '));
    sb.$ptools.forEach(t => {
        if (!t.name || !t.description || typeof t.handler !== 'function') {
            throw new Error('工具定义不完整：' + t.name);
        }
        const r = t.handler({});
        if (typeof r !== 'string') throw new Error(t.name + ' 的 handler 未返回字符串');
    });
    console.log('  工具：' + sb.$ptools.map(t => t.name).join(', '));
});

// ---------- 3. UI 脚本 ----------
check('panel.ui.js 可加载并导出 Screen', () => {
    const m = { exports: {} };
    const sb = {
        module: m, exports: m.exports, console, JSON, Date, Math, String, Number,
        Object, Array, RegExp, Error, Promise
    };
    vm.createContext(sb);
    vm.runInContext(fs.readFileSync(path.join(PLUGIN, manifest.ui), 'utf8'), sb, { filename: manifest.ui });
    const Screen = m.exports.default || m.exports;
    if (typeof Screen !== 'function') throw new Error('未导出 Screen');
    global.__Screen = Screen;
});

check('Screen(ctx) 能渲染出节点树', () => {
    if (!global.__Screen) throw new Error('上一步未通过');
    const { ctx } = mockCtx(manifest.permissions);
    const tree = global.__Screen(ctx);
    if (!tree || !tree.type) throw new Error('未返回有效节点');
    global.__tree = tree;
});

check('所有用到的组件宿主都支持', () => {
    if (!global.__tree) throw new Error('上一步未通过');
    // 遍历每个 tab：单帧只能覆盖当前分支，而 Image/TextField 在另一个 tab 里
    const used = new Set();
    for (let tab = 0; tab < 2; tab++) {
        const { ctx } = mockCtx(manifest.permissions, { tab: tab });
        collectTypes(global.__Screen(ctx), used);
    }
    const unsupported = [...used].filter(t => !SUPPORTED.has(t));
    if (unsupported.length) throw new Error('不支持的组件：' + unsupported.join(', '));
    console.log('  用到组件：' + [...used].sort().join(', '));
});

check('每帧都调用过 mock ctx（无权限误用）', () => {
    if (!global.__Screen) throw new Error('上一步未通过');
    // 用"只给 env"的权限集跑一遍，若代码在渲染期调了 toast/clipboard 就会抛
    for (let tab = 0; tab < 2; tab++) {
        const { ctx } = mockCtx(['env'], { tab: tab });
        global.__Screen(ctx);
    }
});

console.log('');
console.log(failed === 0 ? '全部通过 ✓' : (failed + ' 项失败 ✗'));
process.exit(failed === 0 ? 0 : 1);
