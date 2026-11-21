/**
 * 免费模型网关 — 服务脚本（service/gateway.js）
 *
 * 在宿主 PluginServiceManager 里常驻运行，对外提供 OpenAI 兼容 API：
 *   GET  /v1/models                      → 模型清单（合并两个上游）
 *   POST /v1/chat/completions            → 对话（流式 SSE 透传 / 非流式转发）
 *
 * 上游（均免注册免 Key，2026-10 实测可用）：
 *   1. OpenCode Zen 网关  https://opencode.ai/inference/v1/*  Bearer public
 *   2. Kilo 免费池        https://api.kilo.ai/api/gateway      免密
 *
 * 模型路由：kilo- 前缀 → Kilo；其余 → OpenCode。
 * 流式请求：返回 {stream:true, upstream:{...}}，宿主直接搬运上游 SSE 字节。
 */

'use strict';

var UPSTREAM_OPENCODE = 'https://opencode.ai/inference/v1';
var UPSTREAM_KILO = 'https://api.kilo.ai/api/gateway';

// 静态兜底清单（上游拉取失败时用；运行时优先实时拉取）
var FALLBACK_OPENCODE = [
    'deepseek-v4.1-flash-free', 'glm-5.3-flash-free', 'mimo-v2.6-flash-free',
    'nemotron-3.5-super-vl-free', 'nemotron-3.5-lightning-free',
    'longcat-2.5-preview-free', 'beam-free', 'jev-1.13-free',
    'big-pickle', 'space-bunny-free'
];
var FALLBACK_KILO = [
    'kilo-auto/free', 'nvidia/nemotron-3.5-super-vl:free', 'reflection/beam-free',
    'dots-studio/dots-3-note-preview:free', 'liquid/lfm-2.5-2.6b:free',
    'nvidia/nemotron-3.5-lightning:free', 'thinkingmachines/inkling-small:free',
    'poolside/laguna-s-2.1:free', 'poolside/laguna-xs-2.1:free',
    'cohere/north-mini-code:free', 'openrouter/free'
];

var state = {
    port: 0,
    startedAt: 0,
    requestCount: 0,
    modelsOpencode: [],
    modelsKilo: [],
    modelsFetchedAt: 0
};

// ---------------------------------------------------------------- 模型清单

/** 拉取上游模型清单（同步 ctx.http；失败返回 null）。 */
function fetchUpstreamModels(url, headers) {
    var resp = ctx.http({
        url: url,
        method: 'GET',
        headers: headers,
        timeout: 30000
    });
    var parsed = {};
    try { parsed = JSON.parse(resp); } catch (e) { return null; }
    if (!parsed || !parsed.ok) return null;
    var body = {};
    try { body = JSON.parse(parsed.body || '{}'); } catch (e) { return null; }
    var list = body && body.data;
    if (!list || !list.length) return null;
    return list.map(function (m) { return m.id; });
}

/** 刷新模型清单（缓存 10 分钟）。 */
function refreshModels(force) {
    var now = Date.now();
    if (!force && now - state.modelsFetchedAt < 10 * 60 * 1000) return;

    var oc = fetchUpstreamModels(UPSTREAM_OPENCODE + '/models', {
        'Authorization': 'Bearer public'
    });
    if (oc) state.modelsOpencode = oc;

    var kilo = fetchUpstreamModels(UPSTREAM_KILO + '/models', {});
    if (kilo) {
        // Kilo 清单很大（几百个），只保留免费池（isFree 无法从 id 判断——
        // /models 响应里有 isFree 字段，但 ctx.http 只回 body 字符串，这里重新解析）
        state.modelsKilo = kilo;
    }
    state.modelsFetchedAt = now;
}

/** Kilo 免费池：按 isFree 字段过滤（在 fetch 时做，这里只留 id）。 */
function fetchKiloFreeModels() {
    var resp = ctx.http({
        url: UPSTREAM_KILO + '/models',
        method: 'GET',
        headers: { 'Accept': 'application/json' },
        timeout: 30000
    });
    var parsed = {};
    try { parsed = JSON.parse(resp); } catch (e) { return null; }
    if (!parsed || !parsed.ok) return null;
    var body = {};
    try { body = JSON.parse(parsed.body || '{}'); } catch (e) { return null; }
    var list = body && body.data;
    if (!list || !list.length) return null;
    var free = list.filter(function (m) { return m.isFree === true; })
        .map(function (m) { return m.id; });
    return free.length ? free : null;
}

/** 全量模型清单（带来源标记）。 */
function allModels() {
    var out = [];
    state.modelsOpencode.forEach(function (id) {
        out.push({ id: id, source: 'opencode', upstream_id: id });
    });
    state.modelsKilo.forEach(function (id) {
        // 对外暴露加 kilo- 前缀的路由名 + 原始 id 两种
        out.push({ id: 'kilo-' + id, source: 'kilo', upstream_id: id });
    });
    return out;
}

// ---------------------------------------------------------------- 路由

/** 模型名 → 上游。kilo- 前缀走 Kilo，其余走 OpenCode。 */
function routeModel(model) {
    if (model && model.indexOf('kilo-') === 0) {
        return {
            source: 'kilo',
            upstreamId: model.substring(5),
            base: UPSTREAM_KILO,
            authHeader: null
        };
    }
    return {
        source: 'opencode',
        upstreamId: model,
        base: UPSTREAM_OPENCODE,
        authHeader: { 'Authorization': 'Bearer public' }
    };
}

/** CORS 头（本机调试工具可能带 Origin）。 */
function corsHeaders() {
    return {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type'
    };
}

/** JSON 响应。 */
function jsonResponse(status, obj) {
    return {
        status: status,
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, corsHeaders()),
        body: JSON.stringify(obj)
    };
}

// ---------------------------------------------------------------- 请求处理

/**
 * 宿主每请求调用一次。
 * req = {method, path, query, headers, body}
 * 返回 {status, headers, body} 或 {stream:true, upstream:{url, method, headers, body}}
 */
function handleRequest(req) {
    state.requestCount++;
    persistStats(false);

    // CORS 预检
    if (req.method === 'OPTIONS') {
        return { status: 204, headers: corsHeaders(), body: '' };
    }

    var path = req.path || '/';

    // GET / → 状态页
    if (req.method === 'GET' && (path === '/' || path === '')) {
        return jsonResponse(200, {
            name: 'OpenX Free Model Gateway',
            version: '1.0.0',
            port: state.port,
            uptime_sec: Math.floor((Date.now() - state.startedAt) / 1000),
            request_count: state.requestCount,
            models: allModels().length,
            endpoints: ['/v1/models', '/v1/chat/completions'],
            upstreams: ['opencode', 'kilo']
        });
    }

    // GET /v1/models → 模型清单
    if (req.method === 'GET' && path === '/v1/models') {
        refreshModels(false);
        var models = allModels();
        var data = models.map(function (m) {
            return { id: m.id, object: 'model', created: 0, owned_by: 'free-gateway-' + m.source };
        });
        return jsonResponse(200, { object: 'list', data: data });
    }

    // POST /v1/chat/completions → 对话
    if (req.method === 'POST' && (path === '/v1/chat/completions' || path === '/chat/completions')) {
        return handleChat(req);
    }

    return jsonResponse(404, { error: { message: 'Not Found: ' + path, type: 'invalid_request_error' } });
}

/** 对话请求：解析 body → 路由模型 → 流式透传或非流式转发。 */
function handleChat(req) {
    var body = {};
    try { body = JSON.parse(req.body || '{}'); } catch (e) {
        return jsonResponse(400, { error: { message: 'Invalid JSON body', type: 'invalid_request_error' } });
    }

    var model = body.model || '';
    if (!model) {
        return jsonResponse(400, { error: { message: 'Missing model', type: 'invalid_request_error' } });
    }
    var route = routeModel(model);
    var upstreamBody = Object.assign({}, body, { model: route.upstreamId });
    var stream = body.stream === true;

    var upstreamHeaders = { 'Content-Type': 'application/json' };
    if (route.authHeader) {
        Object.assign(upstreamHeaders, route.authHeader);
    }
    if (stream) upstreamHeaders['Accept'] = 'text/event-stream';

    var upstreamUrl = route.base + '/chat/completions';

    if (stream) {
        // 流式：宿主直接搬运上游 SSE 字节，JS 不经手
        return {
            stream: true,
            status: 200,
            upstream: {
                url: upstreamUrl,
                method: 'POST',
                headers: upstreamHeaders,
                body: JSON.stringify(upstreamBody)
            }
        };
    }

    // 非流式：同步转发
    var resp = ctx.http({
        url: upstreamUrl,
        method: 'POST',
        headers: upstreamHeaders,
        body: JSON.stringify(upstreamBody),
        timeout: 120000
    });
    var parsed = {};
    try { parsed = JSON.parse(resp); } catch (e) {}
    if (!parsed || !parsed.ok) {
        return jsonResponse(502, {
            error: { message: 'Upstream failed: ' + ((parsed && parsed.error) || 'unknown'), type: 'api_error' }
        });
    }
    return {
        status: parsed.status || 200,
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, corsHeaders()),
        body: parsed.body || ''
    };
}

// ---------------------------------------------------------------- 统计（与 UI 面板共享，落盘 env）

/** 把运行状态写进 ctx.store，UI 面板渲染时读。节流：每 10 次请求写一次。 */
function persistStats(force) {
    if (!force && state.requestCount % 10 !== 0) return;
    try {
        ctx.store.set('gateway_stats', {
            running: true,
            port: state.port,
            models: state.modelsOpencode.length + state.modelsKilo.length,
            requests: state.requestCount,
            updated_at: Date.now()
        });
    } catch (e) { /* 统计失败不影响服务 */ }
}

// ---------------------------------------------------------------- 生命周期

exports.onStart = function (info) {
    state.port = info.port || 0;
    state.startedAt = Date.now();
    state.requestCount = 0;

    // 启动时拉一次模型清单（Kilo 用 isFree 过滤）
    var oc = fetchUpstreamModels(UPSTREAM_OPENCODE + '/models', {
        'Authorization': 'Bearer public'
    });
    state.modelsOpencode = oc || FALLBACK_OPENCODE;

    var kiloFree = fetchKiloFreeModels();
    state.modelsKilo = kiloFree || FALLBACK_KILO;

    state.modelsFetchedAt = Date.now();
    persistStats(true);
    ctx.log('免费模型网关已启动 : ' + state.port +
        '（opencode ' + state.modelsOpencode.length + ' + kilo ' + state.modelsKilo.length + ' 个模型）');
};

exports.onStop = function () {
    try {
        ctx.store.set('gateway_stats', {
            running: false,
            port: 0,
            models: 0,
            requests: state.requestCount,
            updated_at: Date.now()
        });
    } catch (e) {}
    ctx.log('免费模型网关已停止（累计 ' + state.requestCount + ' 次请求）');
};

exports.handleRequest = handleRequest;
