package com.wy.openx.plugin.compat

/**
 * Operit ToolPkg 兼容运行时（JS 侧）。
 *
 * 在 operit 插件装载时**先于**插件模块代码注入。它提供三样东西：
 *  1. CommonJS 模块系统 —— 那类包由 tsc 编译，全是 `require` / `exports` / `__esModule`
 *  2. `Tools.*` 宿主能力门面 —— 转发到 [CompatHostBridge] 的同步实现
 *  3. 异步结算机制 —— operit 的工具几乎都是 `async function`，返回 Promise；
 *     宿主单线程跑不了 await，所以先登记 slot、返回一个句柄，
 *     由宿主在 `executePendingJobs()` 之后回调 `__openxSettleCompatAwait(句柄)` 取结果。
 *
 * 写成 Kotlin 字符串而非 assets，是为了跟 [OperitCompat] 一起打包、注入时机可控。
 */
object OperitCompatRuntime {

    val SCRIPT: String = """
var __openxCompatRoot = '';
var __openxCompatPluginId = '';
var __openxCompatSubEntries = {};
var __openxCompatModules = {};

function __openxCompatInit(root, pluginId, subEntriesJson) {
    __openxCompatRoot = String(root || '');
    __openxCompatPluginId = String(pluginId || '');
    try { __openxCompatSubEntries = JSON.parse(subEntriesJson || '{}'); } catch (e) { __openxCompatSubEntries = {}; }
    __openxCompatModules = {};
}

// ------------------------------------------------------------------ 宿主调用

// 宿主方法统一收 (插件id, 操作, 参数JSON) 三个字符串参数：
// 桥对象是**按引擎全局绑定**的，不知道谁在调，所以身份必须由 JS 侧带过去，
// 否则没法按插件各自声明的权限做校验。
function __openxCompatHost(method, pluginId, op, argsJson) {
    if (typeof NativeInterface === 'undefined' || !NativeInterface || typeof NativeInterface[method] !== 'function') {
        throw new Error('宿主未提供 ' + method + '（兼容层未启用）');
    }
    var raw = NativeInterface[method](String(pluginId), String(op), String(argsJson));
    if (raw === null || raw === undefined || raw === '') return null;
    if (typeof raw !== 'string') return raw;
    try { return JSON.parse(raw); } catch (e) { return raw; }
}

function __openxCompatUnwrap(result) {
    if (result && typeof result === 'object' && result.ok === false) {
        throw new Error(String(result.error || '宿主调用失败'));
    }
    return result;
}

// ------------------------------------------------------------------ CommonJS

function __openxCompatJoin(dir, spec) {
    var parts = (dir + '/' + spec).split('/');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
        var p = parts[i];
        if (p === '' || p === '.') continue;
        if (p === '..') { out.pop(); continue; }
        out.push(p);
    }
    return '/' + out.join('/');
}

function __openxCompatFileExists(path) {
    var r = __openxCompatUnwrap(__openxCompatHost(
        'openxCompatModule', __openxCompatPluginId, 'exists', JSON.stringify({ path: path })
    ));
    return !!(r && r.exists);
}

function __openxCompatRequire(fromDir, spec) {
    var s = String(spec || '');
    if (s.charAt(0) !== '.' && s.charAt(0) !== '/') {
        throw new Error('暂不支持包名 require：' + s + '（只支持相对路径）');
    }
    var base = s.charAt(0) === '/' ? s : __openxCompatJoin(fromDir, s);
    var candidates = [base, base + '.js', base + '/index.js'];
    for (var i = 0; i < candidates.length; i++) {
        if (__openxCompatFileExists(candidates[i])) return __openxCompatLoad(candidates[i]);
    }
    throw new Error('模块不存在：' + s + '（from ' + fromDir + '）');
}

function __openxCompatLoad(absPath) {
    if (Object.prototype.hasOwnProperty.call(__openxCompatModules, absPath)) {
        return __openxCompatModules[absPath].exports;
    }
    var res = __openxCompatUnwrap(__openxCompatHost(
        'openxCompatModule', __openxCompatPluginId, 'read', JSON.stringify({ path: absPath })
    ));
    var src = res && typeof res.content === 'string' ? res.content : '';
    if (!src) throw new Error('模块源码读取失败或为空：' + absPath);

    var module = { exports: {} };
    __openxCompatModules[absPath] = module;
    var dir = absPath.substring(0, absPath.lastIndexOf('/')) || '/';

    var factory;
    try {
        // Tools/Env/ToolPkg 必须显式传参：new Function 造的工厂挂在**全局作用域**，
        // 而兼容脚本是 eval 进包装载函数的函数作用域的，自由变量引用不到。
        factory = new Function('exports', 'require', 'module', '__filename', '__dirname',
            'Tools', 'Env', 'ToolPkg', src);
    } catch (e) {
        delete __openxCompatModules[absPath];
        throw new Error('模块语法错误 ' + absPath + '：' + (e && e.message ? e.message : e));
    }
    try {
        factory.call(module.exports, module.exports,
            function (spec) { return __openxCompatRequire(dir, spec); },
            module, absPath, dir,
            Tools, Env, ToolPkg);
    } catch (e) {
        delete __openxCompatModules[absPath];
        throw e;
    }
    return module.exports;
}

// ------------------------------------------------------------------ 异步结算
//
// operit 的工具是 async function，返回 Promise。QuickJS 是单线程随宿主同步调用的，
// 没法真 await。这里登记 slot 后返回句柄字符串，宿主在 executePendingJobs() 之后再
// 调 __openxSettleCompatAwait 取值 —— 只要宿主能力是同步的，一次就够。
//
// slot 表和结算函数放在 **globalThis**：兼容脚本每个插件各自 eval 一份，但宿主只能
// 按全局函数名调 callFunction；句柄 id 全局递增，多插件共用一张表也不会串。

if (typeof globalThis.__openxCompatSlots === 'undefined') {
    globalThis.__openxCompatSlots = {};
    globalThis.__openxCompatAwaitSeq = 0;
}

function __openxCompatAwait(p) {
    if (!p || typeof p.then !== 'function') return p;
    var id = 'cw' + (++globalThis.__openxCompatAwaitSeq);
    var slot = { settled: false, value: undefined, error: null };
    globalThis.__openxCompatSlots[id] = slot;
    try {
        p.then(
            function (v) { slot.settled = true; slot.value = v; },
            function (e) { slot.settled = true; slot.error = e; }
        );
    } catch (e) {
        slot.settled = true;
        slot.error = e;
    }
    return JSON.stringify({ __compatAwait: id });
}

globalThis.__openxSettleCompatAwait = function (id) {
    var key = String(id || '');
    var slot = globalThis.__openxCompatSlots[key];
    if (!slot) return JSON.stringify({ e: '未知的异步句柄：' + key });
    delete globalThis.__openxCompatSlots[key];
    if (!slot.settled) {
        return JSON.stringify({ e: '异步工具未在同步阶段完成（兼容层要求宿主能力同步返回）' });
    }
    if (slot.error) {
        var m = slot.error && slot.error.message ? slot.error.message : String(slot.error);
        return JSON.stringify({ e: m });
    }
    // 工具用 complete() 交结果时，它优先于 return 值（return 通带是 undefined）。
    var fromComplete = slot.result && slot.result.used ? slot.result.value : undefined;
    var v = fromComplete !== undefined ? fromComplete : slot.value;
    if (v === undefined || v === null) return JSON.stringify(null);
    return typeof v === 'string' ? v : JSON.stringify(v);
};

// ------------------------------------------------------------------ Tools.*

function __openxCompatFsOp(op, params) {
    return __openxCompatUnwrap(__openxCompatHost(
        'openxCompatFs', __openxCompatPluginId, op, JSON.stringify(params || {})
    ));
}

function __openxCompatHttpData(method, params) {
    var r = __openxCompatUnwrap(__openxCompatHost(
        'openxCompatHttp', __openxCompatPluginId, method, JSON.stringify(params || {})
    )) || {};
    return {
        url: String(r.url || params.url || ''),
        statusCode: typeof r.statusCode === 'number' ? r.statusCode : 0,
        statusMessage: String(r.statusMessage || ''),
        headers: r.headers || {},
        contentType: String(r.contentType || ''),
        content: r.content !== undefined ? String(r.content) : '',
        size: typeof r.size === 'number' ? r.size : 0,
        toString: function () { return this.content; }
    };
}

// 长命令的 pending 表：宿主跑完后通过 __openxCompatResolvePending(id, json) 结算。
var __openxCompatPending = {};

function __openxCompatResolvePending(id, resultJson) {
    var fn = __openxCompatPending[id];
    if (typeof fn !== 'function') return false;
    delete __openxCompatPending[id];
    try { fn(resultJson); } catch (e) {}
    return true;
}

/**
 * 把宿主返回的终端结果折成 operit 的 TerminalCommandResultData 形状。
 *
 * 返回值是**对象**（`{command,output,exitCode,sessionId,timedOut}`），不是裸字符串：
 * 插件写的是 `result?.output`，返回裸值不会报错、只会永远拿到 undefined（静默错）。
 */
function __openxCompatTerminalResult(raw, sessionId, command) {
    // raw 是宿主返回的 **JSON 字符串**（如 '{"ok":true,"output":"..."}'），
    // 不是对象。必须先 parse —— 否则整个 JSON 串会被当成 output，
    // 插件 parseRuntimeEvents 拿到的就不是结构化数据了
    // （表现为「版本检查没有返回结构化数据」，但宿主日志里命令明明成功了）。
    if (typeof raw === 'string') {
        try { raw = JSON.parse(raw); } catch (e) { raw = { output: raw }; }
    }
    var r = __openxCompatUnwrap(raw) || {};
    if (typeof r === 'string') r = { output: r };
    var out = r.output !== undefined ? String(r.output) : '';
    return {
        command: String(command === undefined ? '' : command),
        output: out,
        exitCode: typeof r.exitCode === 'number' ? r.exitCode : 0,
        sessionId: String(sessionId === undefined ? '' : sessionId),
        timedOut: !!r.timedOut,
        attempts: r.attempts,
        error: r.error,
        toString: function () { return out; }
    };
}

var Tools = {
    // 返回值形状按 operit 真实契约：**全部是对象**（调用方写的是 `result?.content`、
    // `exists?.exists`）。返回裸值不会报错，只会永远拿到 undefined —— 静默错。多余的位置参数（如环境提示 "linux"）直接忽略。
    Files: {
        read: function (a) {
            var p = (a && typeof a === 'object') ? a.path : a;
            return __openxCompatFsOp('read', { path: String(p === undefined ? '' : p) });
        },
        write: function (path, content, append) {
            return __openxCompatFsOp('write', {
                path: String(path === undefined ? '' : path),
                content: content === undefined || content === null ? '' : String(content),
                append: !!append
            });
        },
        exists: function (path) {
            return __openxCompatFsOp('exists', { path: String(path === undefined ? '' : path) });
        },
        mkdir: function (path, recursive) {
            return __openxCompatFsOp('mkdir', {
                path: String(path === undefined ? '' : path),
                recursive: recursive === undefined ? true : !!recursive
            });
        },
        list: function (path) {
            return __openxCompatFsOp('list', { path: String(path === undefined ? '' : path) });
        },
        remove: function (path, recursive) {
            return __openxCompatFsOp('remove', {
                path: String(path === undefined ? '' : path),
                recursive: !!recursive
            });
        },
        stat: function (path) {
            return __openxCompatFsOp('stat', { path: String(path === undefined ? '' : path) });
        },
        // 以下方法官方返回布尔（extended_file_tools 里直接 `!!result`），
        // 多余的环境参数（"linux"）一律忽略。
        deleteFile: function (path, recursive, environment) {
            return __openxCompatFsOp('remove', {
                path: String(path === undefined ? '' : path),
                recursive: recursive === undefined ? true : !!recursive
            });
        },
        move: function (source, destination, environment) {
            return __openxCompatFsOp('move', {
                source: String(source === undefined ? '' : source),
                destination: String(destination === undefined ? '' : destination)
            });
        },
        copy: function (source, destination, recursive, sourceEnvironment, destEnvironment) {
            return __openxCompatFsOp('copy', {
                source: String(source === undefined ? '' : source),
                destination: String(destination === undefined ? '' : destination),
                recursive: recursive === undefined ? true : !!recursive
            });
        },
        info: function (path, environment) {
            return __openxCompatFsOp('info', { path: String(path === undefined ? '' : path) });
        },
        unzip: function (source, destination, environment) {
            return __openxCompatFsOp('unzip', {
                source: String(source === undefined ? '' : source),
                destination: String(destination === undefined ? '' : destination)
            });
        },
        download: function (url, destinationPath, environment) {
            return __openxCompatFsOp('download', {
                url: String(url === undefined ? '' : url),
                path: String(destinationPath === undefined ? '' : destinationPath)
            });
        },
        // 以下三个是 OpenX 在 operit 面之上额外补的，operit 原生没有同名 API。
        // 参数名同时接受 source/destination 与 path/destination，方便两边写法混用。
        zip: function (source, destination, environment) {
            return __openxCompatFsOp('zip', {
                path: String(source === undefined ? '' : source),
                destination: String(destination === undefined ? '' : destination)
            });
        },
        search: function (path, options, environment) {
            var o = options || {};
            return __openxCompatFsOp('search', {
                path: String(path === undefined ? '' : path),
                name: o.name === undefined ? undefined : String(o.name),
                query: o.query === undefined ? undefined : String(o.query),
                ext: o.ext === undefined ? undefined : o.ext,
                content: o.content === undefined ? undefined : String(o.content),
                limit: o.limit === undefined ? undefined : Number(o.limit)
            });
        },
        diff: function (a, b, options) {
            var o = options || {};
            return __openxCompatFsOp('diff', {
                path: String(a === undefined ? '' : a),
                other: String(b === undefined ? '' : b),
                context: o.context === undefined ? 3 : Number(o.context)
            });
        }
    },
    Net: {
        // 返回 **HttpResponseData 对象**（`{url,statusCode,statusMessage,headers,
        // contentType,content,size}`），不是裸字符串：插件写的是 `result?.statusCode` /
        // `result?.content`。返回裸值不报错，只会让健康检查永远判定“服务未就绪”。
        httpGet: function (url) {
            return __openxCompatHttpData('GET', { url: String(url || '') });
        },
        httpPost: function (url, body, headers) {
            return __openxCompatHttpData('POST', { url: String(url || ''), body: body, headers: headers || {} });
        },
        // 通用请求：参数对象 {url, method, headers, body, connect_timeout, read_timeout, validateStatus}。
        // 超时秒数与 validateStatus 由宿主桥统一处理，这里只透传。
        http: function (opt) {
            var o = (opt && typeof opt === 'object') ? opt : {};
            return __openxCompatHttpData(
                String(o.method || 'GET').toUpperCase(),
                {
                    url: String(o.url || ''),
                    body: o.body,
                    headers: o.headers || {},
                    connectTimeout: o.connect_timeout,
                    readTimeout: o.read_timeout
                }
            );
        }
    },
    System: {
        // operit 里出现频率最高的一个（UI 自动化脚本逐帧等待）。走宿主同步 sleep：
        // 它阻塞的是 QuickJS 线程，但上限卡住以免撞引擎看门狗。
        sleep: function (ms) {
            var n = Number(ms);
            if (!isFinite(n) || n < 0) n = 0;
            __openxCompatHost('openxCompatSleep', __openxCompatPluginId, String(Math.min(n, 10000)), '');
            return Promise.resolve();
        },
        toast: function (message) {
            return __openxCompatHost('openxCompatToast', __openxCompatPluginId, String(message == null ? '' : message), '');
        },
        terminal: {
            // 会话语义做了简化：exec 每次都按新命令同步跑完并返回输出，
            // 不保留 cd / 环境变量的跨命令状态。
            create: function (name) {
                return { sessionId: 'compat:' + String(name || 'default') };
            },
            exec: function (sessionId, command, timeoutMs) {
                var payload = JSON.stringify({ sessionId: sessionId, command: command, timeoutMs: timeoutMs });
                var raw = NativeInterface.openxCompatTerminal(
                    String(__openxCompatPluginId), 'exec', payload
                );

                // 长命令：宿主挂后台跑并返回 `pending:<id>`。
                // 这里**必须返回 Promise**，否则同步等下去会占住 QuickJS 线程，
                // 界面在下载/编译期间完全不刷新（用户看到的就是「点了没反应」）。
                if (typeof raw === 'string' && raw.indexOf('pending:') === 0) {
                    var pid = raw.substring(8);
                    return new Promise(function (resolve) {
                        __openxCompatPending[pid] = function (json) {
                            resolve(__openxCompatTerminalResult(json, sessionId, command));
                        };
                    });
                }
                return __openxCompatTerminalResult(raw, sessionId, command);
            },
            /**
             * 流式执行。宿主目前没有把逐行输出推过来（那要异步桥支持多次回调），
             * 所以这里只保证两件事：**真的等到命令结束**、**结束时报一次完成**。
             *
             * 之前的实现是同步返回 exec 的结果，而 exec 在长命令下返回 Promise ——
             * 于是 on_progress('完成') 在命令还没跑完时就报了，插件以为装好了。
             */
            execStreaming: function (sessionId, command, options) {
                var opt = options || {};
                var progress = function (msg, pct) {
                    if (typeof opt.on_progress === 'function') {
                        try { opt.on_progress({ message: msg, progress: pct }); } catch (e) {}
                    }
                };
                progress('执行中…', 10);
                return Promise.resolve(Tools.System.terminal.exec(sessionId, command, opt.timeoutMs))
                    .then(function (r) {
                        progress('完成', 100);
                        return r;
                    });
            },
            close: function () { return true; }
        }
    }
};

// operit 侧的工具脚本可能引用 Env；这里给一个落盘到插件沙箱的极简实现。
var Env = {
    __store: {},
    get: function (key, fallback) {
        var k = String(key || '');
        return Object.prototype.hasOwnProperty.call(Env.__store, k) ? Env.__store[k] : fallback;
    },
    set: function (key, value) {
        Env.__store[String(key || '')] = value;
        return true;
    }
};

// ------------------------------------------------------------------ ToolPkg 桩
//
// 注册类 API 全部对齐官方语义：返回 undefined、失败 throw、重复注册不去重。
// OpenX 只消费工具（METADATA）与导航入口（registerNavigationEntry），
// 其余注册只记录不生效 —— 调用不报错，但功能不会出现，诊断报告会列出来。
// getConfigDir / readResource 是真能力，走宿主桥。

// ---- Operit 钩子 → OpenX 钩子的映射 ----
// Operit 的钩子是「一个 function 处理该类的所有事件」，靠 event 字段区分；
// OpenX 的是「一个钩子名对应一个 handler」。所以把同一个 function 挂到多个
// OpenX 钩子上、每次传不同 event，行为与 operit 一致。
//
// **只映射两边语义真能对上的事件**，对不上的宁可不注册，也不假装生效。
function __openxCompatDeclareHook(hookName, handler) {
    if (typeof ${'$'}phooks === 'undefined' || !Array.isArray(${'$'}phooks)) return false;
    ${'$'}phooks.push({ name: hookName, handler: handler });
    return true;
}

function __openxCompatHookEvent(eventName, payload, hookId) {
    return {
        event: eventName,
        eventName: eventName,
        eventPayload: payload || {},
        toolPkgId: __openxCompatPluginId,
        hookId: hookId
    };
}

/** 把 operit 钩子函数的返回值折算成 OpenX 钩子要的形状。 */
function __openxCompatHookReturn(r, field) {
    if (r === undefined || r === null) return '';
    if (typeof r === 'string') return r;
    if (typeof r === 'object') {
        if (typeof r[field] === 'string') return r[field];
        if (typeof r.text === 'string') return r.text;
    }
    return '';
}

/**
 * operit 的 compose/finalize 钩子返回的是「改写后的完整提示词」，而 OpenX 的
 * onPromptInject 是「追加一段内容」。直接把完整提示词追加回去会整段重复，
 * 所以返回值里含原提示词时只取新增的那部分。
 */
function __openxCompatPromptDelta(out, base) {
    if (!out) return '';
    if (!base) return out;
    var i = out.indexOf(base);
    if (i < 0) return out;
    return out.slice(0, i) + out.slice(i + base.length);
}

var ToolPkg = {
    __uiRoutes: [],
    __navEntries: [],
    // registerNavigationEntry 的定义同时镜像到全局：装载器结束时从这里收走，
    // 供 Kotlin 侧生成侧边栏快捷入口（见 buildEntrySource 末尾的收集段）。
    registerUiRoute: function (def) { ToolPkg.__uiRoutes.push(def || {}); },
    registerNavigationEntry: function (def) {
        ToolPkg.__navEntries.push(def || {});
        try {
            if (!Array.isArray(globalThis.__openxNavEntries)) globalThis.__openxNavEntries = [];
            globalThis.__openxNavEntries.push(def || {});
        } catch (ignore) {}
    },
    registerToolboxUiModule: function (def) { ToolPkg.__uiRoutes.push(def || {}); },
    registerDesktopWidget: function (def) { ToolPkg.__uiRoutes.push(def || {}); },
    registerTool: function () {},
    // 生命周期：operit 的 application_on_create 对应 OpenX 的 onAppStart。
    // 前台/后台/低内存等事件 OpenX 没有对应接缝，不注册（返回 false 让插件方知道没生效）。
    registerAppLifecycleHook: function (def) {
        def = def || {};
        if (typeof def.function !== 'function') return false;
        if (def.event !== 'application_on_create') return false;
        return __openxCompatDeclareHook('onAppStart', function (payload) {
            try {
                def.function(__openxCompatHookEvent('application_on_create', payload, def.id));
            } catch (e) {}
            return '';
        });
    },
    // 系统提示词组装：after_compose_system_prompt 对应 onPromptInject
    registerSystemPromptComposeHook: function (def) {
        def = def || {};
        if (typeof def.function !== 'function') return false;
        return __openxCompatDeclareHook('onPromptInject', function (payload) {
            try {
                var base = String(payload.base || '');
                var r = def.function(__openxCompatHookEvent('after_compose_system_prompt', {
                    systemPrompt: base
                }, def.id));
                return __openxCompatPromptDelta(__openxCompatHookReturn(r, 'systemPrompt'), base);
            } catch (e) {}
            return '';
        });
    },
    registerToolPromptComposeHook: function () {},
    // 发送前最后一道：before_send_to_model 对应 onPromptInject
    registerPromptFinalizeHook: function (def) {
        def = def || {};
        if (typeof def.function !== 'function') return false;
        return __openxCompatDeclareHook('onPromptInject', function (payload) {
            try {
                var base = String(payload.base || '');
                var r = def.function(__openxCompatHookEvent('before_send_to_model', {
                    systemPrompt: base
                }, def.id));
                return __openxCompatPromptDelta(__openxCompatHookReturn(r, 'systemPrompt'), base);
            } catch (e) {}
            return '';
        });
    },
    registerPromptEstimateFinalizeHook: function () {},
    // 用户输入处理：before_process 对应 onBeforeSend（能改写发出去的文字）
    registerPromptInputHook: function (def) {
        def = def || {};
        if (typeof def.function !== 'function') return false;
        return __openxCompatDeclareHook('onBeforeSend', function (payload) {
            try {
                var raw = String(payload.text || '');
                var r = def.function(__openxCompatHookEvent('before_process', {
                    rawInput: raw,
                    processedInput: raw
                }, def.id));
                var t = __openxCompatHookReturn(r, 'processedInput');
                return t === '' ? '' : JSON.stringify({ text: t });
            } catch (e) {}
            return '';
        });
    },
    registerPromptHistoryHook: function () {},
    registerPromptEstimateHistoryHook: function () {},
    registerSummaryGenerateHook: function () {},
    registerChatViewHook: function () {},
    registerChatInputHook: function () {},
    registerChatMessageHook: function () {},
    registerChatRuntimeHook: function () {},
    registerChatMessageMenuItem: function () {},
    registerMessageProcessingPlugin: function () {},
    registerXmlRenderPlugin: function () {},
    registerInputMenuTogglePlugin: function () {},
    // 工具生命周期：tool_call_requested → onBeforeTool，tool_execution_result → onAfterTool。
    // 两个包装都**不改流程**：operit 的工具生命周期钩子是观察性质，不否决、不替换结果。
    registerToolLifecycleHook: function (def) {
        def = def || {};
        if (typeof def.function !== 'function') return false;
        var a = __openxCompatDeclareHook('onBeforeTool', function (payload) {
            try {
                def.function(__openxCompatHookEvent('tool_call_requested', {
                    toolName: payload.tool,
                    parameters: payload.args
                }, def.id));
            } catch (e) {}
            return '';
        });
        var b = __openxCompatDeclareHook('onAfterTool', function (payload) {
            try {
                def.function(__openxCompatHookEvent('tool_execution_result', {
                    toolName: payload.tool,
                    parameters: payload.args,
                    success: true,
                    resultText: payload.result
                }, def.id));
            } catch (e) {}
            return '';
        });
        return a || b;
    },
    registerAiProvider: function () {},
    ipc: {
        on: function () {},
        off: function () { return false; },
        call: function () { return Promise.reject(new Error('ToolPkg.ipc 在 OpenX 兼容层不可用')); }
    },
    getConfigDir: function (pluginId) {
        var r = __openxCompatUnwrap(__openxCompatHost(
            'openxCompatResource', __openxCompatPluginId, 'configDir',
            JSON.stringify({ pluginId: pluginId == null ? '' : String(pluginId) })
        ));
        return (r && r.path) || '';
    },
    readResource: function (key, outputFileName, internal) {
        return Promise.resolve(__openxCompatUnwrap(__openxCompatHost(
            'openxCompatResource', __openxCompatPluginId, 'readResource',
            JSON.stringify({ key: String(key == null ? '' : key), outputFileName: outputFileName == null ? '' : String(outputFileName), internal: !!internal })
        )).path || '');
    },
    ipc: {
        call: function () { return null; },
        on: function () { return true; },
        off: function () { return true; }
    }
};

// ------------------------------------------------------------------ 其余全局
//
// complete(result)：operit 的工具可以用它交结果（而不是 return）。
// Icons.X：图标名注册表，取不到实体，返回名字字符串就够 UI 节点用。
// getLang / getCallerCardId：会话上下文，兼容层没有聊天语境，给默认值。
// 这三个也放 globalThis 并做幂等，理由同上（多插件各 eval 一份会互相覆盖）。
if (typeof globalThis.Icons === 'undefined') {
    globalThis.Icons = new Proxy({}, {
        get: function (_obj, prop) { return String(prop); }
    });
}
if (typeof globalThis.getLang !== 'function') globalThis.getLang = function () { return 'zh-CN'; };
if (typeof globalThis.getCallerCardId !== 'function') globalThis.getCallerCardId = function () { return ''; };

// complete(result) 的状态放 **globalThis**：兼容脚本每个插件各 eval 一份，若
// result 对象是各自作用域的 var，`globalThis.complete` 会被后加载的插件覆盖成
// 指向它自己的对象 —— 先加载的插件调 complete() 就把结果写进了别人的槽里。
if (typeof globalThis.__openxCompatResult === 'undefined') {
    globalThis.__openxCompatResult = { used: false, value: undefined };
    globalThis.complete = function (result) {
        globalThis.__openxCompatResult.used = true;
        globalThis.__openxCompatResult.value = result;
    };
}

function __openxCompatResetResult() {
    var r = globalThis.__openxCompatResult;
    r.used = false;
    r.value = undefined;
}

// ------------------------------------------------------------------ 界面入口（UI 轨道）
//
// 侧边栏类 operit 插件的界面本体是一个 `Screen(ctx)` 函数，写在
// `ui/<route>/index.ui.js` 里以 `exports.default = Screen` 导出，并且同样
// `require` 共享模块（那些模块内部用 `Tools.*`）。所以 UI 需要：
//  1. 同一套 CommonJS loader + Tools 门面
//  2. 把 screen 函数交给宿主 —— 挂到 `globalThis.Screen`，正好命中 OpenX UI 运行时
//     已有的入口解析分支（exports.default / 全局 Screen），不用改那侧代码。
//
// UI 轨道不需要 `__openxCompatAwait` 结算：界面脚本跑在真异步环境里（有
// `__async`/Promise 微任务），`await` 能正常结算。

function __openxCompatLoadUi(uiAbsPath) {
    var mod = __openxCompatLoad(String(uiAbsPath || ''));
    if (!mod) throw new Error('UI 模块为空：' + uiAbsPath);
    var fn = typeof mod.default === 'function' ? mod.default : null;
    if (!fn) {
        // 兼容非 tsc 产物：找第一个大写开头的导出函数当界面入口。
        for (var k in mod) {
            if (Object.prototype.hasOwnProperty.call(mod, k) &&
                typeof mod[k] === 'function' && /^[A-Z]/.test(k)) {
                fn = mod[k];
                break;
            }
        }
    }
    if (typeof fn !== 'function') throw new Error('UI 模块没有导出界面函数：' + uiAbsPath);
    globalThis.Screen = fn;
    return 'ok';
}

// ------------------------------------------------------------------ 工具入口

/**
 * 该子包是否真的导出了这个工具。
 *
 * operit 包普遍存在**METADATA 声明了但模块没导出**的工具（实测 12 个包里有 4 个
 * `usage_advice` 如此）。注册它们等于给 AI 一个必失败的工具，所以装载阶段就筛掉。
 *
 * 但模块本身 require 失败时**仍然注册**：不这样会把整个插件筛成 0 工具，
 * 用户只看到「装了没反应」，而报错原因（路径越界、语法错误）本该在调用时暴露。
 */
function __openxCompatShouldRegister(subId, toolName) {
    var entry = __openxCompatSubEntries[String(subId)];
    if (!entry) return true;
    var mod;
    try { mod = __openxCompatLoad(entry); } catch (e) { return true; }
    return !!(mod && typeof mod[String(toolName)] === 'function');
}

function __openxCompatInvoke(subId, toolName, args) {
    var entry = __openxCompatSubEntries[String(subId)];
    if (!entry) throw new Error('子包未注册：' + subId);
    var mod = __openxCompatLoad(entry);
    var fn = mod ? mod[String(toolName)] : null;
    if (typeof fn !== 'function') {
        var avail = [];
        try { for (var k in mod) { if (typeof mod[k] === 'function') avail.push(k); } } catch (e) {}
        throw new Error('工具未导出：' + subId + '.' + toolName + '（可用：' + (avail.slice(0, 8).join(', ') || '无') + '）');
    }
    __openxCompatResetResult();
    var ret = fn(args || {});
    if (ret && typeof ret.then === 'function') {
        var id = 'cw' + (++globalThis.__openxCompatAwaitSeq);
        var slot = { settled: false, value: undefined, error: null, result: globalThis.__openxCompatResult };
        globalThis.__openxCompatSlots[id] = slot;
        try {
            ret.then(
                function (v) { slot.settled = true; slot.value = v; },
                function (e) { slot.settled = true; slot.error = e; }
            );
        } catch (e) {
            slot.settled = true;
            slot.error = e;
        }
        return JSON.stringify({ __compatAwait: id });
    }
    // 同步路径：调了 complete() 就用它的结果（序列化成字符串，宿主工具分发会直接透传）。
    var res = globalThis.__openxCompatResult;
    if (res.used) {
        var v = res.value;
        __openxCompatResetResult();
        return (v === undefined || v === null) ? 'null' : (typeof v === 'string' ? v : JSON.stringify(v));
    }
    return ret;
}

""".trimIndent()
}
