package com.wy.openx.quickjs

internal fun buildQuickJsCompatScript(): String = """
(function() {
    var root = typeof globalThis !== 'undefined' ? globalThis : this;
    if (typeof root.globalThis === 'undefined') root.globalThis = root;
    if (typeof root.window === 'undefined') root.window = root;
    if (typeof root.self === 'undefined') root.self = root;
    if (typeof root.global === 'undefined') root.global = root;

    var nativeBridge = root.NativeInterface;
    function callHost(method, args) {
        if (!nativeBridge || typeof nativeBridge.__call !== 'function') return null;
        return nativeBridge.__call(method, args == null ? null : JSON.stringify(args));
    }

    root.NativeInterface = new Proxy({}, {
        get: function(_, property) {
            if (property === '__call') return nativeBridge && nativeBridge.__call;
            return function() { return callHost(String(property), Array.prototype.slice.call(arguments)); };
        }
    });

    root.console = root.console || {};
    ['log','info','warn','error','debug'].forEach(function(level) {
        if (typeof root.console[level] === 'function') return;
        root.console[level] = function() {
            var args = Array.prototype.slice.call(arguments).map(function(v) {
                if (typeof v === 'string') return v;
                try { return JSON.stringify(v); } catch(_) { return String(v); }
            });
            callHost('console.' + level, args);
        };
    });

    if (typeof root.queueMicrotask !== 'function') {
        root.queueMicrotask = function(cb) { Promise.resolve().then(cb); };
    }

    function createStorage() {
        var store = {};
        return {
            get length() { return Object.keys(store).length; },
            key: function(i) { var keys = Object.keys(store); return i >= 0 && i < keys.length ? keys[i] : null; },
            getItem: function(k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
            setItem: function(k, v) { store[String(k)] = String(v); },
            removeItem: function(k) { delete store[String(k)]; },
            clear: function() { store = {}; }
        };
    }

    if (!root.localStorage) root.localStorage = createStorage();
    if (!root.sessionStorage) root.sessionStorage = createStorage();
    if (!root.performance) root.performance = {};
    if (typeof root.performance.now !== 'function') {
        var start = Date.now();
        root.performance.now = function() { return Date.now() - start; };
    }

    var timerState = root.__operitTimerState;
    if (!timerState || typeof timerState !== 'object') {
        timerState = { nextId: 1, entries: {} };
        root.__operitTimerState = timerState;
    }

    function normalizeTimerCallback(cb) {
        if (typeof cb === 'function') return cb;
        if (typeof cb === 'string') return function() { return eval(cb); };
        throw new Error('Timer callback must be a function or string');
    }

    function normalizeDelay(d) { var n = Number(d); return isFinite(n) ? Math.max(0, Math.floor(n)) : 0; }

    function clearTimer(timerId) {
        var id = Number(timerId) || 0;
        delete timerState.entries[id];
        if (root.NativeInterface && typeof root.NativeInterface.cancelTimer === 'function') root.NativeInterface.cancelTimer(String(id));
    }

    function scheduleTimer(callback, delay, repeat, args) {
        var timerId = timerState.nextId++;
        timerState.entries[timerId] = { callback: normalizeTimerCallback(callback), args: args || [], repeat: !!repeat };
        if (!root.NativeInterface || typeof root.NativeInterface.scheduleTimer !== 'function') throw new Error('NativeInterface.scheduleTimer unavailable');
        root.NativeInterface.scheduleTimer(String(timerId), normalizeDelay(delay), !!repeat);
        return timerId;
    }

    root.__operitDispatchTimer = function(timerId) {
        var id = Number(timerId) || 0;
        var entry = timerState.entries[id];
        if (!entry) return;
        if (!entry.repeat) delete timerState.entries[id];
        try { return entry.callback.apply(root, entry.args || []); } catch(e) {
            if (root.console && typeof root.console.error === 'function') root.console.error(String(e && e.message ? e.message : e));
        }
    };

    root.__operitClearAllTimers = function() { Object.keys(timerState.entries).forEach(clearTimer); };

    root.setTimeout = function(cb, d) { return scheduleTimer(cb, d, false, Array.prototype.slice.call(arguments, 2)); };
    root.clearTimeout = clearTimer;
    root.setInterval = function(cb, d) { return scheduleTimer(cb, d, true, Array.prototype.slice.call(arguments, 2)); };
    root.clearInterval = clearTimer;
})();
""".trimIndent()
