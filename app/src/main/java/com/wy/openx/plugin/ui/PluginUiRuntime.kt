package com.wy.openx.plugin.ui

/**
 * 插件 UI 的 **JS 侧运行时**。
 *
 * 写成 Kotlin 字符串而非 assets 文件，是为了跟 [PluginRuntime] 一起打包、
 * 且注入时机可控（必须在插件脚本 eval 之前）。
 *
 * ## 设计（参照 Operit 的 JsComposeDslBridge，但精简）
 *
 * 1. **`ctx.UI.任意名字(...)` 用 Proxy 生成节点**
 *    JS 侧**不需要登记组件白名单** —— 写 `ctx.UI.ScaffoldTopBar()` 也能生成节点，
 *    是否支持由宿主渲染器决定。这样加组件只需改 Kotlin，不用同步改 JS。
 *
 * 2. **`useState` 是唯一状态源**
 *    每次 render 结束把整个 stateStore 回传给宿主；宿主触发 action 时把
 *    新的 state 传回，再重新 render。避免 Kotlin/JS 两边各存一份状态导致不同步。
 *
 * 3. **`onClick: fn` 自动换成 action id**
 *    `createNode` 遍历 props，遇到函数就登记进 `actionRegistry` 并替换成
 *    `{"__actionId":"..."}`。宿主渲染 Button 时取出这个 id，点击时回调。
 */
object PluginUiRuntime {

    /** 运行时脚本。会先于插件脚本 eval。 */
    val SCRIPT: String = """
(function () {
    'use strict';

    // 每次 render 重建，避免残留上一次的执行上下文
    var actionRegistry = {};
    var actionSeq = 0;
    var stateStore = {};
    var memoStore = {};
    var envStore = {};
    var permissions = [];
    var toastSink = null;
    // 当前渲染容器的名字：'panel'（插件面板）/ 'overlay'（桌面浮窗）。
    // 宿主每次渲染都会传进来；插件靠它决定要不要画标题栏/按钮这类只属于面板的东西。
    var containerName = 'panel';

    /** 组件节点 → JSON。函数属性（onClick 等）取出登记。 */
    function normalizePropValue(value) {
        if (typeof value === 'function') {
            var id = 'action_' + (++actionSeq);
            actionRegistry[id] = value;
            return { __actionId: id };
        }
        // 带 toJSON 的包装对象（如 createColorToken 的 String 包装）原样交给
        // JSON.stringify：不能走下面的 for-in 分支，否则 String 包装会被拆成
        // {0:'#',1:'F',...,copy:{},toJSON:{}} 这种垃圾。
        if (value && typeof value === 'object' && typeof value.toJSON === 'function') {
            return value;
        }
        if (Array.isArray(value)) {
            return value.map(normalizePropValue);
        }
        if (value && typeof value === 'object') {
            var out = {};
            for (var k in value) {
                if (Object.prototype.hasOwnProperty.call(value, k)) {
                    out[k] = normalizePropValue(value[k]);
                }
            }
            return out;
        }
        return value;
    }

    /** children 归一化：单节点 / 数组 / null 都接受。 */
    function normalizeChildren(children) {
        if (children === null || children === undefined || children === false) return [];
        if (Array.isArray(children)) {
            var out = [];
            for (var i = 0; i < children.length; i++) {
                var c = children[i];
                if (c === null || c === undefined || c === false) continue;
                if (Array.isArray(c)) {
                    out = out.concat(normalizeChildren(c));
                } else if (c && c.__composeNode) {
                    out.push(c);
                }
            }
            return out;
        }
        if (children && children.__composeNode) return [children];
        return [];
    }

    /**
     * `ctx.MaterialTheme.colorScheme.<token>` —— Operit 侧的主题色入口。
     *
     * 返回 **String 包装对象**而不是普通字符串：它带 `copy({alpha})` 方法（Operit
     * 插件会写 `colors.surface.copy({ alpha: 0.96 })`），同时 `toJSON`/`toString`
     * 让它序列化回字符串，于是宿主 [UiTheme.colorOf] 拿到的仍是它本来就认识的形态
     * —— 不需要改 Kotlin 侧那 20 多处取色分支。
     *
     * alpha 编进字符串（`token@0.96`）而不是丢掉：宿主主题色是 Compose Color 对象，
     * JS 拿不到它的 hex、算不出 `#AARRGGBB`；而 `colorOf` 是所有颜色的唯一收口点，
     * 让它认这个后缀就能真正生效，比降级成不透明好。
     */
    function createColorToken(name, alpha) {
        var token = String(name || '');
        var hasAlpha = typeof alpha === 'number' && alpha >= 0 && alpha <= 1;
        var text = hasAlpha ? (token + '@' + alpha) : token;
        var wrapper = new String(text);
        wrapper.copy = function (options) {
            var a = options && typeof options.alpha === 'number' ? options.alpha : (hasAlpha ? alpha : null);
            return createColorToken(token, a);
        };
        wrapper.toJSON = function () { return text; };
        return wrapper;
    }

    var materialTheme = {
        colorScheme: new Proxy({}, {
            get: function (_t, key) {
                if (typeof key !== 'string') return undefined;
                return createColorToken(key, null);
            }
        })
    };

    /** 造节点。children[0] 若是 {children:[...]} 形式则取出。 */
    function createNode(type, props, children) {
        var rawProps = (props && typeof props === 'object') ? props : {};
        var normalizedProps = {};
        for (var key in rawProps) {
            if (Object.prototype.hasOwnProperty.call(rawProps, key)) {
                normalizedProps[key] = normalizePropValue(rawProps[key]);
            }
        }
        return {
            __composeNode: true,
            type: String(type || ''),
            props: normalizedProps,
            children: normalizeChildren(children)
        };
    }

    /** ctx.UI 的 Proxy：任意属性名都成为一个节点工厂。 */
    var uiProxy = new Proxy({}, {
        get: function (_t, key) {
            if (typeof key !== 'string') return undefined;
            return function (props, children) {
                return createNode(key, props, children);
            };
        }
    });

    /** useState：以 key 为索引，render 之间保持。 */
    function useState(key, initialValue) {
        var k = String(key || '').trim();
        if (!k) throw new Error('useState: key 不能为空');
        if (!Object.prototype.hasOwnProperty.call(stateStore, k)) {
            stateStore[k] = initialValue;
        }
        var setter = function (next) {
            var prev = stateStore[k];
            var value = (typeof next === 'function') ? next(prev) : next;
            if (value === prev) return;
            stateStore[k] = value;
            __openxRerender();
        };
        return [stateStore[k], setter];
    }

    function useMemo(key, factory, deps) {
        var k = String(key || '').trim();
        if (!k) throw new Error('useMemo: key 不能为空');
        var depKey = JSON.stringify(deps || []);
        var slot = memoStore[k];
        if (!slot || slot.depKey !== depKey) {
            memoStore[k] = { depKey: depKey, value: factory() };
        }
        return memoStore[k].value;
    }

    function useRef(key, initialValue) {
        var k = '__ref_' + String(key || '').trim();
        if (!k || k === '__ref_') throw new Error('useRef: key 不能为空');
        if (!Object.prototype.hasOwnProperty.call(stateStore, k)) {
            stateStore[k] = initialValue;
        }
        return {
            get current() { return stateStore[k]; },
            set current(v) { stateStore[k] = v; }
        };
    }

    /** 权限检查：没有权限直接抛，让插件作者立刻发现问题。 */
    function requirePermission(name) {
        if (permissions.indexOf(name) < 0) {
            throw new Error('缺少权限 "' + name + '"，请在 plugin.json 的 permissions 里声明');
        }
    }

    /** 系统导航全局动作。三个不同名字走同一条原生通道，只需要转个字符串。 */
    function __uiGlobal(name) {
        requirePermission('ui');
        return __async(function () { return __openxUiGlobal(name); });
    }

    /**
     * 插件面板内的多页路由。
     *
     * 栈存在 `stateStore['__route']` 里，不另开一套状态：这样页内跳转与普通
     * `useState` 一样会触发重渲染，宿主也不用多一个状态通道。
     *
     * 返回键：宿主把返回动作转成 `__openxRouterBack()`。栈里还有页就回退一层，
     * 栈空了才关面板——所以插件不需要自己处理系统返回。
     */
    var router = {
        stack: function () {
            var s = stateStore['__route'];
            return (Array.isArray(s) && s.length) ? s.slice() : [{ name: 'root', params: {} }];
        },
        current: function () {
            var s = router.stack();
            return s[s.length - 1];
        },
        canGoBack: function () {
            return router.stack().length > 1;
        },
        push: function (name, params) {
            var s = router.stack();
            s.push({ name: String(name || 'page'), params: params || {} });
            stateStore['__route'] = s;
            __openxRerender();
            return s[s.length - 1];
        },
        replace: function (name, params) {
            var s = router.stack();
            s[s.length - 1] = { name: String(name || 'page'), params: params || {} };
            stateStore['__route'] = s;
            __openxRerender();
            return s[s.length - 1];
        },
        pop: function () {
            return __openxRouterBack() === '1';
        },
        reset: function () {
            stateStore['__route'] = [{ name: 'root', params: {} }];
            __openxRerender();
        }
    };

    /** 宿主返回键的入口：回退成功返回 '1'，栈已空返回 '0'（交给宿主关面板）。 */
    function __openxRouterBack() {
        try {
            var s = stateStore['__route'];
            if (!Array.isArray(s) || s.length <= 1) return '0';
            s.pop();
            stateStore['__route'] = s.slice();
            __openxRerender();
            return '1';
        } catch (e) {
            return '0';
        }
    }

    /**
     * WebView 控制器：插件用它控制内嵌网页（控制加载、执行 JS、注册网页→原生回调）。
     *
     * 所有命令都**同步**走宿主 NativeInterface，因为宿主那边操作的是真 WebView
     * （必须在主线程；且 shouldInterceptRequest 这类回调要求同步返回）。
     * 返回体约定：{result: ...} 或 {error: "..."}。
     */
    function __openxWebViewCommand(command, key, payload) {
        var ni = root.NativeInterface;
        if (!ni || typeof ni.openxWebViewControllerCommand !== 'function') {
            throw new Error('宿主未提供 WebView 控制器能力');
        }
        var raw = ni.openxWebViewControllerCommand(JSON.stringify({
            command: String(command || ''),
            key: String(key || ''),
            payload: payload || {}
        }));
        var res = null;
        try { res = JSON.parse(raw || 'null'); } catch (e) { res = null; }
        if (res && res.error) throw new Error(String(res.error));
        return res ? res.result : null;
    }

    function createWebViewController(key) {
        var k = String(key || '').trim();
        if (!k) throw new Error('webview controller key is required');
        function cmd(name, payload) { return __openxWebViewCommand(name, k, payload); }
        return {
            // 这两个字段是给宿主认的：controller 被当作 WebView 节点 prop 序列化时
            // 变成 {__composeWebViewController:true, key}，宿主据此找到对应 WebView。
            // 下面那些方法都是函数，JSON.stringify 时天然被忽略。
            __composeWebViewController: true,
            key: k,
            loadUrl: function (url, headers) { cmd('loadUrl', { url: String(url || ''), headers: headers || {} }); },
            loadHtml: function (html, options) { cmd('loadHtml', { html: String(html || ''), options: options || {} }); },
            reload: function () { cmd('reload', {}); },
            stopLoading: function () { cmd('stopLoading', {}); },
            goBack: function () { cmd('goBack', {}); },
            goForward: function () { cmd('goForward', {}); },
            clearHistory: function () { cmd('clearHistory', {}); },
            getState: function () { return cmd('getState', {}); },
            evaluateJavascript: function (script) {
                // 官方签名返回 Promise；宿主求值结果已同步拿到，包一层即可
                return Promise.resolve(cmd('evaluateJavascript', { script: String(script || '') }));
            },
            addJavascriptInterface: function (name, obj) {
                // obj 的方法要转成 {__actionId}：网页调用时宿主靠这个 id **同步回调**
                // 插件函数。必须过 normalizePropValue 才能登记进 actionRegistry。
                cmd('addJavascriptInterface', { name: String(name || ''), object: normalizePropValue(obj || {}) });
            },
            removeJavascriptInterface: function (name) { cmd('removeJavascriptInterface', { name: String(name || '') }); }
        };
    }

    var ctx = {
        UI: uiProxy,
        MaterialTheme: materialTheme,
        useState: useState,
        useMemo: useMemo,
        useRef: useRef,
        router: router,

        // 当前容器：'panel' / 'overlay'。同一份 ui 文件会被两个容器渲染（面板与浮窗），
        // 靠它区分「我现在在哪儿」——不能拿 overlay.isShowing() 猜，
        // 浮窗开着时面板也会读到 true。
        container: 'panel',

        // ---- WebView 控制器（控制内嵌网页 / 执行 JS / 网页→原生回调）----
        createWebViewController: createWebViewController,

        // ---- 环境变量（需 env 权限）----
        getEnv: function (key) {
            requirePermission('env');
            var k = String(key || '');
            return Object.prototype.hasOwnProperty.call(envStore, k) ? envStore[k] : undefined;
        },
        setEnv: function (key, value) {
            requirePermission('env');
            envStore[String(key || '')] = value;
            __openxPersistEnv(JSON.stringify(envStore));
            return true;
        },
        setEnvs: function (map) {
            requirePermission('env');
            if (map && typeof map === 'object') {
                for (var k in map) {
                    if (Object.prototype.hasOwnProperty.call(map, k)) envStore[k] = map[k];
                }
            }
            __openxPersistEnv(JSON.stringify(envStore));
            return true;
        },

        // ---- 工具调用（需 call_tool 权限）----
        callTool: function (toolName, params) {
            requirePermission('call_tool');
            return __async(function () { return __openxCallToolAsync(String(toolName || ''), JSON.stringify(params || {})); });
        },

        // ---- 插件间总线（需 bus 权限）----
        //
        // 拉取式：publish 投递，订阅方在自己渲染/工具调用/定时任务里 poll。
        // 宿主不会主动调你的回调（每个插件是独立 QuickJS 实例，跨引擎重入会卡死）。
        bus: {
            /** 发一条消息，返回序号。 */
            publish: function (topic, payload) {
                requirePermission('bus');
                return __async(function () {
                    return __openxBusPublish(
                        String(topic || ''),
                        typeof payload === 'string' ? payload : JSON.stringify(payload === undefined ? null : payload)
                    );
                });
            },
            /** 登记订阅，返回当前最大序号（拿它作为起始游标最方便）。 */
            subscribe: function (topic) {
                requirePermission('bus');
                return __async(function () { return __openxBusSubscribe(String(topic || '')); });
            },
            unsubscribe: function (topic) {
                requirePermission('bus');
                return __async(function () { return __openxBusUnsubscribe(String(topic || '')); });
            },
            /** 拉序号 > sinceSeq 的消息。未订阅该 topic 时返回空。 */
            poll: function (topic, sinceSeq, limit) {
                requirePermission('bus');
                return __async(function () {
                    return __openxBusPoll(String(topic || ''), Number(sinceSeq) || 0, Number(limit) || 50);
                });
            },
            /** 当前最大序号。 */
            latestSeq: function (topic) {
                requirePermission('bus');
                return __async(function () { return __openxBusLatestSeq(String(topic || '')); });
            }
        },

        // ---- HTTP（需 http 权限）----
        http: function (options) {
            requirePermission('http');
            var opt = (options && typeof options === 'object') ? options : {};
            return __async(function () { return __openxHttpAsync(JSON.stringify(opt)); });
        },

        // ---- 服务启停（需 server 权限；仅服务型插件有意义）----
        serviceControl: function (options) {
            requirePermission('server');
            var opt = (options && typeof options === 'object') ? options : {};
            return __async(function () { return __openxServiceControlAsync(JSON.stringify(opt)); });
        },
        fetch: function (url, options) {
            requirePermission('http');
            var opt = (options && typeof options === 'object') ? options : {};
            opt.url = String(url || '');
            return __async(function () { return __openxHttpAsync(JSON.stringify(opt)); });
        },

        // ---- 剪贴板（需 clipboard 权限）----
        copy: function (text) {
            requirePermission('clipboard');
            return __async(function () { return __openxClipboardCopy(String(text == null ? '' : text)); });
        },
        paste: function () {
            requirePermission('clipboard');
            return __async(function () { return __openxClipboardPaste(); });
        },

        // ---- 系统通知（需 notify 权限）----
        notify: function (title, text) {
            requirePermission('notify');
            return __async(function () { return __openxNotifyAsync(JSON.stringify({ title: String(title || ''), text: String(text || '') })); });
        },

        // ---- 音频播放（需 audio 权限）----
        // 全部同步返回：宿主侧只把命令 post 到主线程就立即返回，不阻塞 JS。
        // 播放器只在主线程访问，所以这里的 state() 读的是宿主维护的状态快照。
        audio: {
            play: function (uri, meta) {
                requirePermission('audio');
                var m = (meta && typeof meta === 'object') ? meta : {};
                __openxAudioPlay(
                    String(uri == null ? '' : uri),
                    String(m.title == null ? '' : m.title),
                    String(m.artist == null ? '' : m.artist)
                );
                return true;
            },
            /**
             * 整单播放：tracks = [{ uri, title, artist }, ...]
             * 传队列后系统播放器才知道"有下一首"，通知栏/媒体卡片的上下曲按钮才可用。
             */
            playQueue: function (tracks, startIndex) {
                requirePermission('audio');
                var arr = Array.isArray(tracks) ? tracks : [];
                var norm = [];
                for (var i = 0; i < arr.length; i++) {
                    var t = arr[i] || {};
                    norm.push({
                        uri: String(t.uri == null ? '' : t.uri),
                        title: String(t.title == null ? '' : t.title),
                        artist: String(t.artist == null ? '' : t.artist)
                    });
                }
                __openxAudioPlayQueue(JSON.stringify(norm), Number(startIndex) || 0);
                return true;
            },
            next: function () { requirePermission('audio'); __openxAudioNext(); return true; },
            previous: function () { requirePermission('audio'); __openxAudioPrevious(); return true; },
            pause: function () { requirePermission('audio'); __openxAudioPause(); return true; },
            resume: function () { requirePermission('audio'); __openxAudioResume(); return true; },
            toggle: function () { requirePermission('audio'); __openxAudioToggle(); return true; },
            stop: function () { requirePermission('audio'); __openxAudioStop(); return true; },
            seekTo: function (ms) {
                requirePermission('audio');
                __openxAudioSeek(Number(ms) || 0);
                return true;
            },
            setVolume: function (v) {
                requirePermission('audio');
                __openxAudioSetVolume(Number(v));
                return true;
            },
            state: function () {
                requirePermission('audio');
                var raw = __openxAudioState();
                try { return JSON.parse(String(raw == null ? '{}' : raw)); } catch (e) { return {}; }
            }
        },

        // ---- 文件（需 file 权限）----
        // readFile/writeFile/listFiles 限插件沙箱目录；pickFile/saveFile 走系统选择器
        readFile: function (path) {
            requirePermission('file');
            return __async(function () { return __openxReadFileAsync(String(path || '')); });
        },
        writeFile: function (path, content) {
            requirePermission('file');
            return __async(function () { return __openxWriteFileAsync(JSON.stringify({ path: String(path || ''), content: String(content == null ? '' : content) })); });
        },
        listFiles: function (dir) {
            requirePermission('file');
            return __async(function () { return __openxListFilesAsync(String(dir || '')); });
        },
        pickFile: function (options) {
            requirePermission('file');
            return __async(function () { return __openxPickFileAsync(JSON.stringify(options || {})); });
        },
        saveFile: function (name, content) {
            requirePermission('file');
            return __async(function () { return __openxSaveFileAsync(JSON.stringify({ name: String(name || ''), content: String(content == null ? '' : content) })); });
        },
        pickImage: function () {
            requirePermission('file');
            return __async(function () { return __openxPickImageAsync(); });
        },
        // 以下六个同样限沙箱目录：路径越界（.. 或绝对路径）会被宿主拒绕
        moveFile: function (from, to) {
            requirePermission('file');
            return __async(function () {
                return __openxMoveFileAsync(JSON.stringify({ from: String(from || ''), to: String(to || '') }));
            });
        },
        copyFile: function (from, to) {
            requirePermission('file');
            return __async(function () {
                return __openxCopyFileAsync(JSON.stringify({ from: String(from || ''), to: String(to || '') }));
            });
        },
        searchFiles: function (options) {
            requirePermission('file');
            return __async(function () { return __openxSearchFilesAsync(JSON.stringify(options || {})); });
        },
        zip: function (from, to) {
            requirePermission('file');
            return __async(function () {
                return __openxZipAsync(JSON.stringify({ from: String(from || ''), to: String(to || '') }));
            });
        },
        unzip: function (zipPath, to) {
            requirePermission('file');
            return __async(function () {
                return __openxUnzipAsync(JSON.stringify({ zip: String(zipPath || ''), to: String(to || '') }));
            });
        },
        diff: function (a, b, context) {
            requirePermission('file');
            return __async(function () {
                return __openxDiffAsync(JSON.stringify({
                    a: String(a || ''),
                    b: String(b || ''),
                    context: context == null ? 3 : Number(context)
                }));
            });
        },

        // ---- 结构化存储（复用 env 权限）----
        // env 是平铺的字符串表，适合存配置；store 提供嵌套 JSON 语义，
        // 适合存列表/对象。两者共用同一份持久化文件，只是命名空间不同。
        store: (function () {
            var NS = '__store__';
            function raw() {
                requirePermission('env');
                var s = envStore[NS];
                if (!s || typeof s !== 'object' || Array.isArray(s)) {
                    s = {};
                    envStore[NS] = s;
                }
                return s;
            }
            function persist() {
                __openxPersistEnv(JSON.stringify(envStore));
            }
            return {
                get: function (key, def) {
                    var s = raw();
                    var k = String(key || '');
                    return Object.prototype.hasOwnProperty.call(s, k) ? s[k] : def;
                },
                set: function (key, value) {
                    raw()[String(key || '')] = value;
                    persist();
                    return true;
                },
                remove: function (key) {
                    delete raw()[String(key || '')];
                    persist();
                    return true;
                },
                keys: function () {
                    return Object.keys(raw());
                },
                clear: function () {
                    envStore[NS] = {};
                    persist();
                    return true;
                }
            };
        })(),

        // ---- 提示（需 toast 权限）----
        showToast: function (message) {
            requirePermission('toast');
            if (toastSink) {
                toastSink(String(message || ''));
                return;
            }
            __openxShowToast(String(message || ''));
        },

        // ---- 无障碍 UI 自动化（需 ui 权限）----
        //
        // 查询类同步返回（就在当前调用栈里拿结果），动作类返回 Promise——
        // 点击要等手势回调、waitFor 要等界面变化，都可能占用几百毫秒以上。
        //
        // 选择器统一用对象：{ text, textContains, id, className, desc, clickable,
        // editable, scrollable, enabled, checked, focused, index }
        // text/textContains 会同时匹配 text 与 contentDescription。
        ui: {
            ready: function () {
                requirePermission('ui');
                return __hostResult(__openxUiReady());
            },
            snapshot: function (options) {
                requirePermission('ui');
                return __hostResult(__openxUiSnapshot(JSON.stringify(options || {})));
            },
            find: function (selector, limit) {
                requirePermission('ui');
                return __hostResult(__openxUiFind(JSON.stringify(selector || {}), Number(limit) || 20));
            },
            foreground: function () {
                requirePermission('ui');
                return __hostResult(__openxUiForeground());
            },
            tap: function (selector) {
                requirePermission('ui');
                return __async(function () { return __openxUiClick(JSON.stringify(selector || {})); });
            },
            tapAt: function (x, y) {
                requirePermission('ui');
                return __async(function () { return __openxUiTapAt(Number(x) || 0, Number(y) || 0); });
            },
            setText: function (selector, text) {
                requirePermission('ui');
                return __async(function () {
                    return __openxUiSetText(JSON.stringify(selector || {}), String(text == null ? '' : text));
                });
            },
            scroll: function (selector, direction) {
                requirePermission('ui');
                return __async(function () {
                    return __openxUiScroll(JSON.stringify(selector || {}), String(direction || 'down'));
                });
            },
            swipe: function (x1, y1, x2, y2) {
                requirePermission('ui');
                return __async(function () {
                    return __openxUiSwipe(Number(x1) || 0, Number(y1) || 0, Number(x2) || 0, Number(y2) || 0);
                });
            },
            waitFor: function (selector, timeoutMs) {
                requirePermission('ui');
                return __async(function () {
                    return __openxUiWaitFor(JSON.stringify(selector || {}), Number(timeoutMs) || 5000);
                });
            },
            back: function () { return __uiGlobal('back'); },
            home: function () { return __uiGlobal('home'); },
            recents: function () { return __uiGlobal('recents'); },
            notifications: function () { return __uiGlobal('notifications'); },
            quickSettings: function () { return __uiGlobal('quick_settings'); },
            power: function () { return __uiGlobal('power'); },
            lock: function () { return __uiGlobal('lock'); }
        },

        // ---- 设备与应用（需 system 权限）----
        device: {
            info: function () {
                requirePermission('system');
                return __hostResult(__openxDeviceInfo());
            },
            battery: function () {
                requirePermission('system');
                return __hostResult(__openxBatteryInfo());
            },
            stats: function () {
                requirePermission('system');
                return __hostResult(__openxDeviceStats());
            }
        },
        app: {
            list: function (max) {
                requirePermission('system');
                return __async(function () { return __openxAppList(Number(max) || 0); });
            },
            search: function (keyword, max) {
                requirePermission('system');
                return __async(function () {
                    return __openxAppSearch(String(keyword || ''), Number(max) || 0);
                });
            },
            launch: function (packageName) {
                requirePermission('system');
                return __async(function () { return __openxAppLaunch(String(packageName || '')); });
            },
            // 前台应用靠无障碍读窗口，所以这一项要 ui 权限而不是 system
            foreground: function () {
                requirePermission('ui');
                return __hostResult(__openxUiForeground());
            }
        },

        // ---- 摘要与编码（纯本地计算，不涉数据外发，不设权限）----
        crypto: {
            hash: function (algorithm, text) {
                return __hostResult(__openxCryptoHash(
                    String(algorithm || 'sha256'), String(text == null ? '' : text)));
            },
            base64: function (mode, text) {
                return __hostResult(__openxCryptoBase64(
                    String(mode || 'encode'), String(text == null ? '' : text)));
            }
        },

        // ---- 插件悬浮窗（需 overlay 权限）----
        //
        // 悬浮窗直接渲染插件自己的 ui/panel.ui.js，与面板是同一份界面代码。
        // 默认不抢输入焦点（否则会把其它 App 的软键盘顶掉），代价是窗内输入框收不到
        // 键盘 —— 确实需要输入时传 { focusable: true }。
        overlay: {
            canShow: function () {
                requirePermission('overlay');
                return __hostResult(__openxOverlayCanShow());
            },
            show: function (options) {
                requirePermission('overlay');
                var opt = (options && typeof options === 'object') ? options : {};
                return __async(function () { return __openxOverlayShow(JSON.stringify(opt)); });
            },
            hide: function () {
                requirePermission('overlay');
                return __async(function () { return __openxOverlayHide(); });
            },
            isShowing: function () {
                requirePermission('overlay');
                return __hostResult(__openxOverlayIsShowing());
            },
            // 走动/贴边三件套。插件自己做动画：setInterval 里算坐标 -> moveTo，
            // 不重渲染（重渲染是重建整棵 UI 树，30fps 跑不起）。
            moveTo: function (x, y) {
                requirePermission('overlay');
                return __hostResult(__openxOverlayMoveTo(Math.round(Number(x) || 0), Math.round(Number(y) || 0)));
            },
            geometry: function () {
                requirePermission('overlay');
                return __hostResult(__openxOverlayGeometry());
            },
            resize: function (widthDp, heightDp) {
                requirePermission('overlay');
                return __hostResult(__openxOverlayResize(
                    Math.round(Number(widthDp) || 0), Math.round(Number(heightDp) || 0)));
            },
            setPassThrough: function (on) {
                requirePermission('overlay');
                return __hostResult(__openxOverlaySetPassThrough(!!on));
            },
            // 在宠物头顶冒一句话（独立窗口，不接收触摸）：浮窗本体往往只有几十
            // 甚至几 dp，摆不下字，所以气泡单独开一个不吃触摸的窗口。
            say: function (text, ms) {
                requirePermission('overlay');
                var t = (text == null) ? '' : String(text);
                return __hostResult(__openxOverlaySay(t, Math.round(Number(ms) || 4200)));
            }
        },

        // 宿主 AI 会话的实时状态（需 session 权限）。
        // 只给状态不给内容：拿不到对话正文、思考正文、工具参数。
        // 返回 { ok, busy, thinking, tool, pending, contextTokens, reconnecting }。
        host: {
            session: function () {
                requirePermission('session');
                return __hostResult(__openxHostSession());
            }
        },

        // ---- 记忆库（需 memory 权限）----
        //
        // 列表只给前 200 字摘要，全文用 memory.read；需要完整内容时不要循环调 list。
        memory: {
            list: function (keyword, scope, limit) {
                requirePermission('memory');
                return __hostResult(__openxMemoryList(
                    String(keyword || ''), String(scope || 'global'), Number(limit) || 50));
            },
            read: function (key, scope) {
                requirePermission('memory');
                return __hostResult(__openxMemoryRead(String(key || ''), String(scope || 'global')));
            },
            save: function (key, value, category, scope) {
                requirePermission('memory');
                return __hostResult(__openxMemorySave(
                    String(key || ''), String(value == null ? '' : value),
                    String(category || ''), String(scope || 'global')));
            },
            remove: function (key, scope) {
                requirePermission('memory');
                return __hostResult(__openxMemoryDelete(String(key || ''), String(scope || 'global')));
            }
        },

        // ---- 聊天记录（需 chat 权限）----
        //
        // 目前**只读**：能看会话列表与最近消息，不能替用户发消息（需要串起整条 AI
        // 请求链，风险远大于收益）。插件想影响对话应该用钩子。
        chat: {
            list: function (limit) {
                requirePermission('chat');
                return __hostResult(__openxChatList(Number(limit) || 20));
            },
            current: function () {
                requirePermission('chat');
                return __hostResult(__openxChatCurrent());
            },
            messages: function (limit) {
                requirePermission('chat');
                return __hostResult(__openxChatMessages(Number(limit) || 20));
            },
            /**
             * 发起一轮 AI 任务（需 chat_task 权限）。
             *
             * `newConversation` 默认 true：任务跑在**独立的后台会话**里，不抢当前对话。
             * 只发起、不等结果——AI 回复要几十秒，等会卡住插件。
             *
             * ```js
             * await ctx.chat.runTask('总结今天的新闻', { newConversation: true });
             * ```
             */
            runTask: function (prompt, options) {
                requirePermission('chat_task');
                var o = options || {};
                var nc = (o.newConversation === undefined) ? true : !!o.newConversation;
                return __async(function () {
                    return __openxChatRunTask(
                        String(prompt || ''),
                        nc,
                        o.workspace === undefined ? null : String(o.workspace)
                    );
                });
            }
        },

        // ---- 其它 ----
        reportError: function (e) {
            var msg = (e && e.message) ? e.message : String(e || '');
            try { __openxShowToast('插件出错：' + msg); } catch (_) {}
        }
    };

    // ---------------------------------------------------------------- 渲染入口

    /**
     * 找界面入口函数。依次尝试三种写法：
     *   1. CommonJS：module.exports = Screen
     *   2. CommonJS：exports.default = Screen
     *   3. 全局函数：function Screen(ctx) {}  /  globalThis.Screen = ...
     *
     * 前两种依赖本运行时在末尾定义的 exports/module 全局量（见下方「CommonJS 兼容层」）。
     */
    function resolveEntry() {
        try {
            if (typeof module !== 'undefined' && module && typeof module.exports === 'function') {
                return module.exports;
            }
            if (typeof exports !== 'undefined' && exports && typeof exports.default === 'function') {
                return exports.default;
            }
            if (typeof globalThis !== 'undefined' && typeof globalThis.Screen === 'function') {
                return globalThis.Screen;
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    var entryFn = null;
    var lastTree = null;

    /** 执行一次渲染，返回 {tree, state, memo} 的 JSON 字符串。 */
    /** 宿主 NativeInterface 一律返回 JSON 字符串；这里统一解成对象给插件用。 */
    /**
     * **pending 请求表**：id -> {resolve, reject}。
     *
     * 宿主先把异步动作挂起（返回 id），等外部真的回来（选图完成、HTTP 有响应），
     * 再调 __openxResolvePending(id, json) 结算。之前是同步阻塞等，QuickJS 单次执行
     * 60 秒就超时——用户慢慢挑一张图都会失败（真机踩过）。
     */
    /**
     * 调宿主原生方法并把结果包成 Promise。
     *
     * 宿主返回两种形态：
     *  - 字符串，且以 `pending:` 开头 → 后面由 __openxResolvePending 结算
     *  - 其它字符串 → 已经同步拿到结果（JSON），直接 resolve
     * 这样同步快路径（剪贴板读写这种 IO 很快的）不用绕一圈 pending，
     * 而选图/选文件这类要等用户的走 pending，不会再撞 QuickJS 60 秒超时。
     */
    function __async(nativeCall) {
        return new Promise(function (resolve, reject) {
            var raw = nativeCall();
            if (typeof raw === 'string' && raw.indexOf('pending:') === 0) {
                var pid = raw.substring(8);
                __pendingSeq += 1;
                __pending[pid] = { resolve: resolve, reject: reject };
            } else {
                resolve(__hostResult(raw));
            }
        });
    }

    var __pending = {};
    var __pendingSeq = 0;

    function __openxResolvePending(id, resultJson) {
        var p = __pending[id];
        if (!p) return false;
        delete __pending[id];
        var r = __hostResult(resultJson);
        if (r && r.__reject) { p.reject(new Error(r.__reject)); return true; }
        p.resolve(r);
        return true;
    }

    function __openxPendingCount() { return __pendingSeq; }

    function __hostResult(raw) {
        if (typeof raw !== 'string') return raw;
        try {
            var v = JSON.parse(raw);
            return (v && typeof v === 'object') ? v : { ok: true, value: v };
        } catch (e) {
            return { ok: false, error: String(raw) };
        }
    }

    function __openxRenderUi(runtimeOptionsJson) {
        var opts = {};
        try { opts = JSON.parse(runtimeOptionsJson || '{}'); } catch (e) { opts = {}; }
        permissions = Array.isArray(opts.permissions) ? opts.permissions : [];
        if (opts.env && typeof opts.env === 'object') envStore = opts.env;
        if (opts.container) containerName = String(opts.container);
        ctx.container = containerName;
        // 宿主回传的状态（rerender 时用）。
        // ⚠ 只补缺失的键，不覆盖已有键：异步回调（http/copy 的 .then）在两次渲染
        // 之间改了 stateStore，宿主手里的快照是旧的，覆盖会把新值冲掉
        // （表现为面板首次自动拉取永远停在“加载中”，点刷新才出来——真机踩过）。
        if (opts.state && typeof opts.state === 'object') {
            for (var k in opts.state) {
                if (Object.prototype.hasOwnProperty.call(opts.state, k) &&
                    !Object.prototype.hasOwnProperty.call(stateStore, k)) {
                    stateStore[k] = opts.state[k];
                }
            }
        }

        if (!entryFn) entryFn = resolveEntry();
        if (typeof entryFn !== 'function') {
            throw new Error('未找到界面入口：需要在插件脚本里 exports.default = Screen，或定义全局 Screen 函数');
        }
        // operit 的 UI 脚本大量直接用全局 UI.Text(...) 而不是 ctx.UI.*
        // （官方 DSL 把 UI 挂成了全局）。不补这个全局对象，脚本在**用到 UI.xxx 的
        // 那条分支**上会抛 ReferenceError，表现成「渲染结果解析失败」——
        // 且只在特定状态下触发（如服务就绪后才渲染的那块），极难定位。
        // 每次渲染前重设：ctx 是本次渲染新造的，不能抓旧快照。
        root.UI = ctx.UI;
        var tree = entryFn(ctx);
        lastTree = tree;
        return JSON.stringify({
            tree: tree,
            state: stateStore,
            memo: memoStore
        });
    }

    /**
     * 请求重渲染。
     *
     * ⚠ 这里**只记账，不通知宿主**。
     *
     * 为什么：setter 是在宿主调用 __openxDispatchAction 的**执行中途**被触发的，
     * 而那次调用此刻正持有宿主 session 的锁。此时若直接回调宿主让 Compose 立刻重组，
     * 重组会发生在一次未完成的派发中间，可能读到半更新状态，表现为界面卡在旧树、
     * 点什么都像没反应（真机踩过：整个面板所有交互都失效）。
     *
     * 改为只置标志位，由宿主在 dispatchAction **返回之后**查询并统一触发重组。
     * 时序干净，还省掉一次多余的 render 调用。
     */
    var rerenderRequested = false;
    function __openxRerender() {
        rerenderRequested = true;
    }

    /** 宿主在一次派发后询问：JS 侧是否请求了重渲染（问完自动清除）。 */
    function __openxConsumeRerenderRequest() {
        var v = rerenderRequested;
        rerenderRequested = false;
        return v ? 'true' : 'false';
    }

    /** 宿主回调：某个 action 被触发。返回新的渲染结果 JSON。 */
    function __openxDispatchAction(actionId, payloadJson, wantRaw) {
        var fn = actionRegistry[actionId];
        if (typeof fn !== 'function') {
            return JSON.stringify({ error: '未知动作：' + actionId });
        }
        var payload = {};
        try { payload = JSON.parse(payloadJson || '{}'); } catch (e) { payload = {}; }
        // onValueChange / onCheckedChange 的处理器就是 useState 的 setter，它期望**原始值**，
        // 而渲染器传的是 {value: v} 包装。不拆开的话 state 会被写成 {value:...} 对象：
        // 下一帧 TextField 显示成 "[object Object]"、Switch 恒为关。
        //
        // 但**只对宿主显式声明要裸值**的动作拆包。Tabs/Dropdown 的 onSelect 同样带
        // value 字段（那是"选中项的值"），拆了就等于把 index 丢掉 → 点标签切不动。
        // 靠猜动作名不可靠（id 只是序号），所以由宿主传 raw 标记。
        var arg = payload;
        if (wantRaw && payload && typeof payload === 'object' &&
            Object.prototype.hasOwnProperty.call(payload, 'value')) {
            arg = payload.value;
        }
        fn(arg);
        return __openxRenderUi(JSON.stringify({ permissions: permissions, env: envStore, state: stateStore }));
    }

    /** 宿主注入环境变量快照。 */
    function __openxSetEnvSnapshot(envJson) {
        try { envStore = JSON.parse(envJson || '{}'); } catch (e) { envStore = {}; }
    }

    // 暴露给宿主
    var root = (typeof globalThis !== 'undefined') ? globalThis : this;
    /**
     * 宿主回调：**同步**调用某个 action 并把它的返回值带回去。
     *
     * WebView 的 shouldInterceptRequest / shouldOverrideUrlLoading 必须在调用现场
     * 同步给出结果（Android 的签名如此，等不了 Promise），所以单开一条同步通道。
     */
    function __openxInvokeActionSync(actionId, payloadJson) {
        var target = actionRegistry[actionId];
        if (typeof target !== 'function') {
            return JSON.stringify({ ok: false, error: '未知动作：' + actionId });
        }
        var arg = null;
        try { arg = JSON.parse(payloadJson || 'null'); } catch (e) { arg = null; }
        try {
            var r = Array.isArray(arg) ? target.apply(null, arg) : target(arg);
            if (r && typeof r.then === 'function') {
                return JSON.stringify({ ok: false, error: '同步回调不支持 async 函数（返回了 Promise）' });
            }
            return JSON.stringify({ ok: true, value: (r === undefined ? null : r) });
        } catch (e) {
            return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
        }
    }

    root.__openxRenderUi = __openxRenderUi;
    root.__openxDispatchAction = __openxDispatchAction;
    root.__openxInvokeActionSync = __openxInvokeActionSync;
    root.__openxSetEnvSnapshot = __openxSetEnvSnapshot;
    root.__openxConsumeRerenderRequest = __openxConsumeRerenderRequest;
    root.ctx = ctx;

    // ---------------------------------------------------------------- 宿主桥
    //
    // 把 JS 侧的 __openx* 调用转发到宿主的 NativeInterface 方法。
    //
    // 这一层是必须的，之前漏了：宿主桥方法名是 openxShowToast / openxPersistEnv / …
    // （无下划线前缀），只能经 NativeInterface 代理反射调用；而运行时内部（ctx.setEnv、
    // ctx.showToast、__openxRerender 等）用的是 __openx 前缀的全局函数。两者没接上，
    // 每次调用都抛 ReferenceError，又被外层 try/catch 吞掉 ——
    // 表现就是「点保存/拨开关都没反应」（真机踩过）。
    root.__openxShowToast = function (message) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxShowToast(message == null ? '' : String(message));
    };
    root.__openxPersistEnv = function (json) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxPersistEnv(json == null ? '' : String(json));
    };
    root.__openxRequestRerender = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxRequestRerender();
    };
    root.__openxCallToolAsync = function (name, paramsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxCallToolAsync(String(name || ''), String(paramsJson || '{}'));
    };
    root.__openxBusPublish = function (topic, payload) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBusPublish(String(topic || ''), String(payload || ''));
    };
    root.__openxBusSubscribe = function (topic) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBusSubscribe(String(topic || ''));
    };
    root.__openxBusUnsubscribe = function (topic) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBusUnsubscribe(String(topic || ''));
    };
    root.__openxBusPoll = function (topic, sinceSeq, limit) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBusPoll(String(topic || ''), Number(sinceSeq) || 0, Number(limit) || 50);
    };
    root.__openxBusLatestSeq = function (topic) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBusLatestSeq(String(topic || ''));
    };
    root.__openxHttpAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxHttpAsync(String(optionsJson || '{}'));
    };
    root.__openxServiceControlAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxServiceControlAsync(String(optionsJson || '{}'));
    };

    // ---- 二期新增能力（剪贴板 / 通知 / 文件）的转发 ----
    // 每个 ctx 方法都要在这里登记一次，否则调用时是 ReferenceError。
    // 已被 try/catch 吞掉的 ReferenceError 表现为"点了没反应"，极难定位，
    // 所以新增 ctx API 时**必须同步补这里**。
    root.__openxClipboardCopy = function (text) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxClipboardCopy(text == null ? '' : String(text));
    };
    root.__openxClipboardPaste = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxClipboardPaste();
    };
    root.__openxNotifyAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxNotifyAsync(String(optionsJson || '{}'));
    };
    root.__openxReadFileAsync = function (relPath) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxReadFileAsync(String(relPath || ''));
    };
    root.__openxWriteFileAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxWriteFileAsync(String(optionsJson || '{}'));
    };
    root.__openxListFilesAsync = function (dir) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxListFilesAsync(String(dir || ''));
    };
    root.__openxPickFileAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxPickFileAsync(String(optionsJson || '{}'));
    };
    root.__openxSaveFileAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxSaveFileAsync(String(optionsJson || '{}'));
    };
    root.__openxPickImageAsync = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxPickImageAsync();
    };
    root.__openxMoveFileAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxMoveFileAsync(String(optionsJson || '{}'));
    };
    root.__openxCopyFileAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxCopyFileAsync(String(optionsJson || '{}'));
    };
    root.__openxSearchFilesAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxSearchFilesAsync(String(optionsJson || '{}'));
    };
    root.__openxZipAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxZipAsync(String(optionsJson || '{}'));
    };
    root.__openxUnzipAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUnzipAsync(String(optionsJson || '{}'));
    };
    root.__openxDiffAsync = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxDiffAsync(String(optionsJson || '{}'));
    };

    // ---- 音频播放（ctx.audio）的转发 ----
    root.__openxAudioPlay = function (uri, title, artist) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioPlay(
            String(uri || ''), String(title || ''), String(artist || ''));
    };
    root.__openxAudioPlayQueue = function (tracksJson, startIndex) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioPlayQueue(
            String(tracksJson || '[]'), Number(startIndex) || 0);
    };
    root.__openxAudioNext = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioNext();
    };
    root.__openxAudioPrevious = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioPrevious();
    };
    root.__openxAudioPause = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioPause();
    };
    root.__openxAudioResume = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioResume();
    };
    root.__openxAudioToggle = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioToggle();
    };
    root.__openxAudioStop = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioStop();
    };
    root.__openxAudioSeek = function (positionMs) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioSeek(Number(positionMs) || 0);
    };
    root.__openxAudioSetVolume = function (volume) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioSetVolume(Number(volume));
    };
    root.__openxAudioState = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAudioState();
    };

    /**
     * 启动自检：把"应该存在但缺失"的桥函数列出来。
     *
     * 为什么需要它：缺一个转发只会在**用户点到那个按钮时**才暴露，而且异常容易被
     * 插件自己的 try/catch 吞掉，表现成"点击没反应"这种无从下手的信息。
     * 启动时主动扫一遍，缺什么一上来就知道。
     */
    // ---- 五期 / 六期：原生能力桥的转发 ----
    //
    // 每个 ctx 方法都要在这里登记一次，否则调用时是 ReferenceError（被 try/catch
    // 吞掉后表现为"点了没反应"）。新增 API 时记得同步补下面的 selfCheck 清单。
    root.__openxUiReady = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiReady();
    };
    root.__openxUiSnapshot = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiSnapshot(String(optionsJson || '{}'));
    };
    root.__openxUiFind = function (selectorJson, limit) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiFind(String(selectorJson || '{}'), Number(limit) || 20);
    };
    root.__openxUiClick = function (selectorJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiClick(String(selectorJson || '{}'));
    };
    root.__openxUiTapAt = function (x, y) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiTapAt(Number(x) || 0, Number(y) || 0);
    };
    root.__openxUiSetText = function (selectorJson, text) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiSetText(String(selectorJson || '{}'), String(text == null ? '' : text));
    };
    root.__openxUiScroll = function (selectorJson, direction) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiScroll(String(selectorJson || '{}'), String(direction || 'down'));
    };
    root.__openxUiSwipe = function (x1, y1, x2, y2) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiSwipe(
            Number(x1) || 0, Number(y1) || 0, Number(x2) || 0, Number(y2) || 0);
    };
    root.__openxUiGlobal = function (action) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiGlobal(String(action || ''));
    };
    root.__openxUiWaitFor = function (selectorJson, timeoutMs) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiWaitFor(String(selectorJson || '{}'), Number(timeoutMs) || 5000);
    };
    root.__openxUiForeground = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxUiForeground();
    };
    root.__openxBatteryInfo = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxBatteryInfo();
    };
    root.__openxDeviceStats = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxDeviceStats();
    };
    root.__openxMemoryList = function (keyword, scope, limit) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxMemoryList(
            String(keyword || ''), String(scope || 'global'), Number(limit) || 50);
    };
    root.__openxMemoryRead = function (key, scope) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxMemoryRead(String(key || ''), String(scope || 'global'));
    };
    root.__openxMemorySave = function (key, value, category, scope) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxMemorySave(
            String(key || ''), String(value == null ? '' : value),
            String(category || ''), String(scope || 'global'));
    };
    root.__openxMemoryDelete = function (key, scope) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxMemoryDelete(String(key || ''), String(scope || 'global'));
    };
    root.__openxChatList = function (limit) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxChatList(Number(limit) || 20);
    };
    root.__openxChatCurrent = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxChatCurrent();
    };
    root.__openxChatMessages = function (limit) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxChatMessages(Number(limit) || 20);
    };
    root.__openxChatRunTask = function (prompt, newConversation, workspacePath) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxChatRunTask(
            String(prompt || ''),
            !!newConversation,
            workspacePath == null ? null : String(workspacePath)
        );
    };
    root.__openxDeviceInfo = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxDeviceInfo();
    };
    root.__openxAppList = function (max) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAppList(Number(max) || 0);
    };
    root.__openxAppSearch = function (keyword, max) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAppSearch(String(keyword || ''), Number(max) || 0);
    };
    root.__openxAppLaunch = function (packageName) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxAppLaunch(String(packageName || ''));
    };
    root.__openxCryptoHash = function (algorithm, text) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxCryptoHash(String(algorithm || ''), String(text == null ? '' : text));
    };
    root.__openxCryptoBase64 = function (mode, text) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxCryptoBase64(String(mode || ''), String(text == null ? '' : text));
    };
    root.__openxOverlayCanShow = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayCanShow();
    };
    root.__openxOverlayShow = function (optionsJson) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayShow(String(optionsJson || '{}'));
    };
    root.__openxOverlayHide = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayHide();
    };
    root.__openxOverlayIsShowing = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayIsShowing();
    };
    root.__openxOverlayMoveTo = function (x, y) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayMoveTo(x, y);
    };
    root.__openxOverlayGeometry = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayGeometry();
    };
    root.__openxOverlayResize = function (widthDp, heightDp) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlayResize(widthDp, heightDp);
    };
    root.__openxOverlaySetPassThrough = function (on) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlaySetPassThrough(!!on);
    };
    root.__openxOverlaySay = function (text, ms) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxOverlaySay(text, ms);
    };
    root.__openxHostSession = function () {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxHostSession();
    };

    root.__openxBridgeSelfCheck = function () {
        var required = [
            '__openxShowToast', '__openxPersistEnv', '__openxRequestRerender',
            '__openxCallToolAsync', '__openxHttpAsync', '__openxServiceControlAsync',
            '__openxClipboardCopy', '__openxClipboardPaste', '__openxNotifyAsync',
            '__openxReadFileAsync', '__openxWriteFileAsync', '__openxListFilesAsync',
            '__openxPickFileAsync', '__openxSaveFileAsync', '__openxPickImageAsync',
            '__openxMoveFileAsync', '__openxCopyFileAsync', '__openxSearchFilesAsync',
            '__openxZipAsync', '__openxUnzipAsync', '__openxDiffAsync',
            '__openxBusPublish', '__openxBusSubscribe', '__openxBusUnsubscribe',
            '__openxBusPoll', '__openxBusLatestSeq',
            '__openxChatRunTask',
            '__openxAudioPlay', '__openxAudioPlayQueue', '__openxAudioNext',
            '__openxAudioPrevious', '__openxAudioPause', '__openxAudioResume',
            '__openxAudioToggle', '__openxAudioStop', '__openxAudioSeek',
            '__openxAudioSetVolume', '__openxAudioState',
            '__openxUiReady', '__openxUiSnapshot', '__openxUiFind', '__openxUiClick',
            '__openxUiTapAt', '__openxUiSetText', '__openxUiScroll', '__openxUiSwipe',
            '__openxUiGlobal', '__openxUiWaitFor', '__openxUiForeground',
            '__openxBatteryInfo', '__openxDeviceInfo', '__openxDeviceStats', '__openxAppList',
            '__openxMemoryList', '__openxMemoryRead', '__openxMemorySave', '__openxMemoryDelete',
            '__openxChatList', '__openxChatCurrent', '__openxChatMessages',
            '__openxAppSearch', '__openxAppLaunch', '__openxCryptoHash',
            '__openxCryptoBase64',
            '__openxOverlayCanShow', '__openxOverlayShow', '__openxOverlayHide',
            '__openxOverlayIsShowing', '__openxOverlayMoveTo', '__openxOverlayGeometry',
            '__openxOverlayResize', '__openxOverlaySetPassThrough', '__openxOverlaySay', '__openxHostSession'
        ];
        var missing = [];
        for (var i = 0; i < required.length; i++) {
            if (typeof root[required[i]] !== 'function') missing.push(required[i]);
        }
        return JSON.stringify({ ok: missing.length === 0, missing: missing });
    };

    // 宿主结算挂起请求时调用（见 PluginUiSession.settlePending）。
    // 必须挂到 root：宿主是在脚本外用 engine.evaluate 执行的，
    // 顶层 function 声明虽然可见，但显式挂 root 更稳（也不依赖 eval 的作用域）。
    root.__openxResolvePending = __openxResolvePending;
    root.__openxPendingCount = __openxPendingCount;

    // 定时器驱动重渲染。
    //
    // 宿主只在两个时刻调Screen()：打开面板、action 派发后。所以 setInterval 里
    // 改状态后画面不动——回调执行了，但没人要求重组。
    //
    // 这里包一层而不是新增 ctx API：定时器语义本来就该触发重渲染，让插件作者
    // 不用学新写法。引擎侧的 setInterval 由 QuickJsNativeCompatScriptBuilder 注入，
    // 有完整生命周期（clearAllTimers + close 时 shutdown），这里只补重渲染那一环。
    function __openxWrapTimer(schedule) {
        return function (cb, delay) {
            if (typeof cb !== 'function') return schedule.apply(root, arguments);
            var args = Array.prototype.slice.call(arguments, 2);
            var wrapped = function () {
                var out;
                try {
                    out = cb.apply(this, args);
                } catch (e) {
                    // 不往外抛：宿主侧已把定时器回调包在 runCatching 里，抛出去只会
                    // 打成一行 stderr，插件作者在界面上看不到。转成 console.error。
                    if (root.console && typeof root.console.error === 'function') {
                        root.console.error(String(e && e.message ? e.message : e));
                    }
                }
                // 必须用 __openxRequestRerender（真正通知宿主），不能用 __openxRerender
                // （只置标志位）。标志位仅供 dispatchAction 路径消费，而定时器不走那条路，
                // 置了也没人读 —— 结果就是回调执行了、画面照样不动。
                // 放在异常分支之后（而非 return 之前）：回调抛异常时同样要重组，
                // 否则界面会永久停在旧树、看起来像插件卡死。
                root.__openxRequestRerender();
                return out;
            };
            return schedule.call(root, wrapped, delay);
        };
    }
    if (typeof root.setInterval === 'function') {
        var __openxRawSetInterval = root.setInterval;
        root.setInterval = __openxWrapTimer(__openxRawSetInterval);
    }
    if (typeof root.setTimeout === 'function') {
        var __openxRawSetTimeout = root.setTimeout;
        root.setTimeout = __openxWrapTimer(__openxRawSetTimeout);
    }

    // CommonJS 兼容层：让插件 UI 脚本能写 exports.default = Screen（或 module.exports = Screen）。
    //
    // 本引擎的 eval 是全局模式，compat 层只提供了 window/global/console/定时器等全局量，
    // 并未提供 exports/module。于是插件脚本一旦写 exports.default = Screen 就会
    // ReferenceError: "'exports' is not defined"——而 resolveEntry 又承诺支持这种写法，
    // 两边对不上（真机踩过：首帧报错、点「重试」反而能进页面。原因是函数声明 Screen 已在
    // 失败的首次求值里挂到了全局，回退到 globalThis.Screen 恰好救回来，非常容易误诊）。
    if (typeof root.exports === 'undefined' || root.exports === null) root.exports = {};
    if (typeof root.module === 'undefined' || root.module === null) {
        root.module = { exports: root.exports };
    }
}).call(typeof globalThis !== 'undefined' ? globalThis : this);
""".trimIndent()
}
