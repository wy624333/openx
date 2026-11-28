package com.wy.openx.plugin

/**
 * 服务型插件的 **JS 侧运行时**（与 [com.wy.openx.plugin.ui.PluginUiRuntime] 同构，但面向服务场景精简）。
 *
 * 在 service 脚本 eval 之前注入，提供：
 *  - `ctx`：与 UI 插件一致的 API 面（getEnv/setEnv/store/http/fetch/log），权限经宿主桥强制
 *  - CommonJS 兼容层（module.exports / exports），service 脚本写 `exports.onStart = ...`
 *  - 宿主回调入口：`__openxServiceOnStart` / `__openxServiceOnStop` / `__openxServiceHandleRequest`
 *    —— Kotlin 侧 [PluginServiceManager] 通过 engine.callFunction 调这三个全局函数
 *
 * 与 UI 运行时的差异：
 *  - 没有 UI.Proxy / useState / actionRegistry（服务无界面）
 *  - 没有 rerender 语义
 *  - http 是**同步阻塞**的（宿主桥 runBlocking 实现）——服务脚本在 QuickJS 单线程里
 *    一次只处理一个请求，同步模型最简单可靠。流式场景不走 ctx.http，
 *    由宿主直接搬运字节（见 PluginServiceManager.handleStreamProxy）。
 */
object PluginServiceJsRuntime {

    val SCRIPT: String = """
(function () {
    'use strict';

    var envStore = {};
    var permissions = [];
    var logSink = null;

    function requirePermission(name) {
        if (permissions.indexOf(name) < 0) {
            throw new Error('缺少权限 "' + name + '"，请在 plugin.json 的 permissions 里声明');
        }
    }

    var ctx = {
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

        http: function (options) {
            requirePermission('http');
            var opt = (options && typeof options === 'object') ? options : {};
            return __openxHttpSync(JSON.stringify(opt));
        },
        fetch: function (url, options) {
            requirePermission('http');
            var opt = (options && typeof options === 'object') ? options : {};
            opt.url = String(url || '');
            return __openxHttpSync(JSON.stringify(opt));
        },

        // 在容器里执行命令（需 filesystem 权限）。
        //
        // 用途：后台脚本管理容器里的常驻进程——比如把 JVM / Node 网关拉起来、停掉。
        // 这类插件的「服务」不在宿主进程里，而是容器里的独立进程；
        // 声明 service 指向本脚本后，「插件配置」页的启动按钮就能控制它。
        //
        // 同步返回 {ok, exitCode, output}，没有 Promise。命令自身阻塞到结束或超时，
        // 常驻进程请让命令自己脱离（setsid … </dev/null >/dev/null 2>&1 &）。
        exec: function (command, options) {
            requirePermission('filesystem');
            var opt = (options && typeof options === 'object') ? options : {};
            var out = __openxExec(String(command || ''), Number(opt.timeoutMs) || 0);
            try {
                return JSON.parse(out);
            } catch (e) {
                return { ok: false, error: '宿主返回无法解析：' + out };
            }
        },

        // 发起 AI 任务（需 chat_task 权限）。
        // 定时任务的核心用途就在这里：到点让 AI 按指令干活。
        // 同步调用（服务运行时没有 Promise 链），宿主只发起不等结果，不会卡住回调。
        chat: {
            runTask: function (prompt, options) {
                requirePermission('chat_task');
                var o = (options && typeof options === 'object') ? options : {};
                var nc = (o.newConversation === undefined) ? true : !!o.newConversation;
                return __openxChatRunTask(
                    String(prompt || ''),
                    nc,
                    o.workspace === undefined ? null : String(o.workspace)
                );
            }
        },

        // 系统通知（需 notify 权限）：定时任务到点提醒用户靠它
        notify: function (title, text) {
            requirePermission('notify');
            return __openxNotify(JSON.stringify({
                title: String(title || ''),
                text: String(text || '')
            }));
        },

        // 电池状态（需 system 权限）：定时任务的「仅充电时」「最低电量」条件靠它
        battery: function () {
            requirePermission('system');
            return __openxBattery();
        },

        // 插件间总线（拉取式，需 bus 权限）。服务型插件是它最大的受益者：
        // 可以在定时任务或请求处理里把别的插件发的消息排干。
        bus: {
            publish: function (topic, payload) {
                requirePermission('bus');
                return __openxBusPublish(
                    String(topic || ''),
                    typeof payload === 'string' ? payload : JSON.stringify(payload === undefined ? null : payload)
                );
            },
            subscribe: function (topic) {
                requirePermission('bus');
                return __openxBusSubscribe(String(topic || ''));
            },
            unsubscribe: function (topic) {
                requirePermission('bus');
                return __openxBusUnsubscribe(String(topic || ''));
            },
            poll: function (topic, sinceSeq, limit) {
                requirePermission('bus');
                return __openxBusPoll(String(topic || ''), Number(sinceSeq) || 0, Number(limit) || 50);
            },
            latestSeq: function (topic) {
                requirePermission('bus');
                return __openxBusLatestSeq(String(topic || ''));
            }
        },

        // 悬浮窗（需 overlay 权限）。
        //
        // 返回值是 **JSON 字符串**（与 bus 一致，服务轨道没有 Promise 封装），
        // 要取值先 JSON.parse。show 是异步生效的：返回 accepted 不代表窗口已经画出来，
        // 想确认得等一拍再 isShowing。
        overlay: {
            canShow: function () {
                requirePermission('overlay');
                return __openxOverlayCanShow();
            },
            show: function (options) {
                requirePermission('overlay');
                var opt = (options && typeof options === 'object') ? options : {};
                return __openxOverlayShow(JSON.stringify(opt));
            },
            hide: function () {
                requirePermission('overlay');
                return __openxOverlayHide();
            },
            isShowing: function () {
                requirePermission('overlay');
                return __openxOverlayIsShowing();
            }
        },

        log: function () {
            var parts = [];
            for (var i = 0; i < arguments.length; i++) {
                var a = arguments[i];
                parts.push(typeof a === 'object' ? JSON.stringify(a) : String(a));
            }
            if (logSink) { logSink(parts.join(' ')); return; }
            try { __openxShowToast(parts.join(' ')); } catch (_) {}
        },

        reportError: function (e) {
            var msg = (e && e.message) ? e.message : String(e || '');
            try { __openxShowToast('服务出错：' + msg); } catch (_) {}
        }
    };

    // ---------------------------------------------------------------- 宿主回调入口

    /** Kotlin start() 后调用：注入 env 快照 + 权限，再调 onStart。 */
    function __openxServiceOnStart(infoJson) {
        var info = {};
        try { info = JSON.parse(infoJson || '{}'); } catch (e) {}
        try {
            var envRaw = (typeof NativeInterface !== 'undefined' && NativeInterface.openxLoadEnv)
                ? NativeInterface.openxLoadEnv() : null;
            if (envRaw) {
                try { envStore = JSON.parse(envRaw); } catch (e) { envStore = {}; }
            }
        } catch (e) {}
        try {
            var permRaw = (typeof NativeInterface !== 'undefined' && NativeInterface.openxPermissions)
                ? NativeInterface.openxPermissions() : null;
            if (permRaw) {
                try { permissions = JSON.parse(permRaw); } catch (e) { permissions = []; }
            }
        } catch (e) {}
        var fn = (typeof module !== 'undefined' && module.exports && module.exports.onStart)
            ? module.exports.onStart
            : (typeof globalThis !== 'undefined' ? globalThis.onStart : null);
        if (typeof fn === 'function') {
            try { fn(info); } catch (e) {
                ctx.reportError(e);
                return JSON.stringify({ ok: false, error: (e && e.message) ? e.message : String(e) });
            }
        }
        return JSON.stringify({ ok: true });
    }

    /** Kotlin stop() 前调用。 */
    function __openxServiceOnStop() {
        var fn = (typeof module !== 'undefined' && module.exports && module.exports.onStop)
            ? module.exports.onStop
            : (typeof globalThis !== 'undefined' ? globalThis.onStop : null);
        if (typeof fn === 'function') {
            try { fn(); } catch (e) { ctx.reportError(e); }
        }
        return JSON.stringify({ ok: true });
    }

    /**
     * Kotlin 定时器到点时调用。
     *
     * 宿主传的实参是 **JSON 数组**（`callFunction` 的约定：参数对应 JS 函数的实参列表），
     * 形如 `[{taskId, firedAt, pluginId}]`。所以解析后要先取第一个元素，
     * 否则 `info.taskId` 永远是 undefined，任务永远不会触发。
     */
    function __openxServiceOnSchedule(infoJson) {
        // 宿主可能传字符串也可能已解析成对象（取决于 callFunction 的实现），
        // 两种都接受。下面把实际收到的形状一并返回，方便排查传参问题。
        var rawType = typeof infoJson;
        var info = {};
        try {
            var parsed = (typeof infoJson === 'string') ? JSON.parse(infoJson) : infoJson;
            info = Array.isArray(parsed) ? (parsed[0] || {}) : (parsed || {});
        } catch (e) {}
        // 每次定时任务开始前重读 env：面板（另一份 JS 运行时）会把请求写进
        // env 文件，而 envStore 只在 onStart 时读过一次。不重读的话服务
        // 永远看不到面板后来的写入（表现为「面板提交了任务，后台毫无反应」）。
        try {
            var envFresh = (typeof NativeInterface !== 'undefined' && NativeInterface.openxLoadEnv)
                ? NativeInterface.openxLoadEnv() : null;
            if (envFresh) {
                var parsedEnv = JSON.parse(envFresh);
                if (parsedEnv && typeof parsedEnv === 'object') envStore = parsedEnv;
            }
        } catch (e) {}
        var taskId = String(info.taskId || '');
        var fn = (typeof module !== 'undefined' && module.exports && module.exports.onSchedule)
            ? module.exports.onSchedule
            : (typeof globalThis !== 'undefined' ? globalThis.onSchedule : null);
        if (typeof fn !== 'function') {
            return JSON.stringify({ ok: false, skipped: '未导出 onSchedule' });
        }
        try {
            fn(taskId, info);
            return JSON.stringify({ ok: true, taskId: taskId, rawType: rawType, keys: Object.keys(info) });
        } catch (e) {
            ctx.reportError(e);
            return JSON.stringify({ ok: false, taskId: taskId, rawType: rawType, error: (e && e.message) ? e.message : String(e) });
        }
    }

    /**
     * Kotlin 每个请求调用一次。reqJson = {method, path, query, headers, body}。
     * 返回 {status, headers, body} 或 {stream:true, upstream:{url, method, headers, body}}。
     */
    function __openxServiceHandleRequest(reqJson) {
        var req = {};
        try { req = JSON.parse(reqJson || '{}'); } catch (e) {}
        var fn = (typeof module !== 'undefined' && module.exports && module.exports.handleRequest)
            ? module.exports.handleRequest
            : (typeof globalThis !== 'undefined' ? globalThis.handleRequest : null);
        if (typeof fn !== 'function') {
            return JSON.stringify({ status: 501, body: JSON.stringify({ error: 'service plugin has no handleRequest' }) });
        }
        try {
            var result = fn(req);
            if (result && typeof result === 'object') return JSON.stringify(result);
            return JSON.stringify({ status: 200, body: String(result == null ? '' : result) });
        } catch (e) {
            return JSON.stringify({
                status: 500,
                body: JSON.stringify({ error: (e && e.message) ? e.message : String(e) })
            });
        }
    }

    // ---------------------------------------------------------------- CommonJS 兼容层

    var module = { exports: {} };
    var exports = module.exports;

    // ---------------------------------------------------------------- 宿主桥转发

    var root = (typeof globalThis !== 'undefined') ? globalThis : this;
    root.__openxShowToast = function (message) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxShowToast(message == null ? '' : String(message));
    };
    root.__openxPersistEnv = function (json) {
        if (!root.NativeInterface) return null;
        return root.NativeInterface.openxPersistEnv(json == null ? '' : String(json));
    };
    root.__openxHttpSync = function (optJson) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxHttpSync(optJson == null ? '{}' : String(optJson));
    };
    root.__openxExec = function (command, timeoutMs) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxExec(
            command == null ? '' : String(command),
            Number(timeoutMs) || 0
        );
    };

    // 总线：服务插件与 UI 插件共用同一套宿主方法（同步调用，服务场景不需要 Promise）
    root.__openxBusPublish = function (topic, payload) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBusPublish(
            topic == null ? '' : String(topic),
            payload == null ? '' : String(payload)
        );
    };
    root.__openxBusSubscribe = function (topic) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBusSubscribe(topic == null ? '' : String(topic));
    };
    root.__openxBusUnsubscribe = function (topic) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBusUnsubscribe(topic == null ? '' : String(topic));
    };
    root.__openxBusPoll = function (topic, sinceSeq, limit) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBusPoll(
            topic == null ? '' : String(topic),
            Number(sinceSeq) || 0,
            Number(limit) || 50
        );
    };
    root.__openxBusLatestSeq = function (topic) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBusLatestSeq(topic == null ? '' : String(topic));
    };
    root.__openxChatRunTask = function (prompt, newConversation, workspacePath) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxChatRunTask(
            prompt == null ? '' : String(prompt),
            !!newConversation,
            workspacePath == null ? null : String(workspacePath)
        );
    };
    root.__openxNotify = function (optJson) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxNotify(optJson == null ? '{}' : String(optJson));
    };
    root.__openxBattery = function () {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxBattery();
    };
    root.__openxOverlayCanShow = function () {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxOverlayCanShow();
    };
    root.__openxOverlayShow = function (optJson) {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxOverlayShow(optJson == null ? '{}' : String(optJson));
    };
    root.__openxOverlayHide = function () {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxOverlayHide();
    };
    root.__openxOverlayIsShowing = function () {
        if (!root.NativeInterface) return JSON.stringify({ ok: false, error: 'no NativeInterface' });
        return root.NativeInterface.openxOverlayIsShowing();
    };

    root.__openxServiceOnStart = __openxServiceOnStart;
    root.__openxServiceOnStop = __openxServiceOnStop;
    root.__openxServiceOnSchedule = __openxServiceOnSchedule;
    root.__openxServiceHandleRequest = __openxServiceHandleRequest;
    root.ctx = ctx;
    root.module = module;
    root.exports = exports;
})();
"""
}
