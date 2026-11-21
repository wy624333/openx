/**
 * 免费模型网关 — 控制面板（ui/panel.ui.js）
 *
 * 写法约定（对齐 sample-toolkit）：
 *  - 布局属性直接写在组件 props 第一层：{ fillMaxWidth: true, padding: 16 }
 *  - modifier 不支持 weight：等分布局用「外层 Column fillMaxWidth + Row」或固定宽度
 *  - ctx.http / ctx.copy 在 UI 引擎返回 Promise，必须 .then() 取结果
 *  - ctx.useState 的 setter 会触发宿主自动重渲染；ctx.store.set 不会
 */
function Screen(ctx) {
    var stats = ctx.store.get('gateway_stats', {}) || {};
    var port = stats.port || 18899;
    var requests = stats.requests || 0;

    // 服务真实状态：优先问宿主（PluginServiceManager 内存态），
    // 缓存 gateway_stats 只作展示参考——服务被杀/异常退出时缓存不会更新，会误报「运行中」。
    var statusPair = ctx.useState('panel_service_status', { checking: true, running: false, port: port });
    var status = statusPair[0];
    var setStatus = statusPair[1];
    var running = status.running === true;

    if (status.checking) {
        if (typeof ctx.serviceControl === 'function') {
            ctx.serviceControl({ cmd: 'status' }).then(function (res) {
                if (res && res.ok) {
                    setStatus({
                        checking: false,
                        running: res.running === true,
                        port: res.port || port
                    });
                } else {
                    probeAlive();
                }
            });
        } else {
            // 旧 APK 无此 API：退回 http 探活（只能查状态，不能启停）
            probeAlive();
        }
    }

    /** http 探活降级：GET 网关根路径，200 = 运行中。 */
    function probeAlive() {
        ctx.http({
            url: 'http://127.0.0.1:' + port + '/',
            method: 'GET',
            timeout: 5000
        }).then(function (probe) {
            var alive = probe && probe.ok && probe.status === 200;
            setStatus({ checking: false, running: alive, port: port });
        });
    }

    var modelsPair = ctx.useState('panel_models', null);
    var models = modelsPair[0];
    var setModels = modelsPair[1];

    if (!models) {
        fetchModels().then(function (list) { setModels(list); });
    }

    var modelList = models || { opencode: [], kilo: [] };
    var total = modelList.opencode.length + modelList.kilo.length;
    var livePort = status.port || port;

    /** 启动/停止服务（宿主桥；busy 防抖）。 */
    function toggleService() {
        if (status.busy) return;
        if (typeof ctx.serviceControl !== 'function') {
            ctx.showToast('当前 App 版本不支持面板启停，请更新 App 或到插件管理页操作');
            return;
        }
        setStatus({ checking: false, running: running, port: livePort, busy: true });
        ctx.serviceControl({ cmd: running ? 'stop' : 'start' }).then(function (res) {
            if (res && res.ok) {
                var nowRunning = running ? false : true;
                var newPort = (res.port || livePort);
                setStatus({ checking: false, running: nowRunning, port: newPort, busy: false });
                ctx.showToast(nowRunning
                    ? '服务已启动：127.0.0.1:' + newPort
                    : '服务已停止');
            } else {
                setStatus({ checking: false, running: running, port: livePort, busy: false });
                ctx.showToast('操作失败：' + ((res && res.error) || '未知错误'));
            }
        });
    }

    return ctx.UI.Column(
        { padding: 12 },
        [
            // ---------- 状态头卡片 ----------
            ctx.UI.Card(
                {
                    fillMaxWidth: true, padding: 18,
                    background: running ? '#0D2818' : '#1A1A1A',
                    cornerRadius: 16
                },
                [
                    ctx.UI.Row(
                        { verticalAlignment: 'center' },
                        [
                            ctx.UI.Icon(
                                { icon: 'cloud', size: 34, color: running ? '#4CAF50' : '#888888' },
                                null
                            ),
                            ctx.UI.Column(
                                { padding: 12 },
                                [
                                    ctx.UI.Text(
                                        {
                                            text: status.checking
                                                ? '正在检查服务状态…'
                                                : (running ? '服务运行中' : '服务未启动'),
                                            fontSize: 19, bold: true,
                                            color: running ? '#4CAF50' : '#888888'
                                        },
                                        null
                                    ),
                                    ctx.UI.Text(
                                        {
                                            text: status.checking
                                                ? ' '
                                                : (running
                                                    ? 'OpenAI 兼容接口已就绪'
                                                    : '点下方「启动服务」按钮'),
                                            fontSize: 12, color: '#AAAAAA'
                                        },
                                        null
                                    )
                                ]
                            ),
                            ctx.UI.Text(
                                {
                                    text: running ? '  ●' : '  ○',
                                    fontSize: 20,
                                    color: running ? '#4CAF50' : '#666666'
                                },
                                null
                            )
                        ]
                    ),
                    ctx.UI.Spacer({ height: 14 }, null),
                    ctx.UI.Row(
                        { verticalAlignment: 'center', horizontalArrangement: 'spaceBetween', fillMaxWidth: true },
                        [
                            ctx.UI.Column(
                                {},
                                [
                                    ctx.UI.Text({ text: '接口地址', fontSize: 11, color: '#888888' }, null),
                                    ctx.UI.Text(
                                        {
                                            text: 'http://127.0.0.1:' + livePort + '/v1',
                                            fontSize: 15, bold: true, color: '#64B5F6'
                                        },
                                        null
                                    )
                                ]
                            ),
                            ctx.UI.Button(
                                {
                                    text: '复制', fontSize: 12,
                                    height: 36,
                                    onClick: function () { copyBaseUrl(livePort); }
                                },
                                null
                            )
                        ]
                    ),
                    ctx.UI.Spacer({ height: 12 }, null),
                    ctx.UI.Row(
                        { fillMaxWidth: true, horizontalArrangement: 'spaceBetween', verticalAlignment: 'center' },
                        [
                            ctx.UI.Text(
                                {
                                    text: status.busy
                                        ? '正在' + (running ? '停止' : '启动') + '…'
                                        : (running ? '停止后 App 内对话将不可用' : '启动后可在App内使用免费模型'),
                                    fontSize: 11, color: '#888888'
                                },
                                null
                            ),
                            ctx.UI.Button(
                                {
                                    text: status.busy
                                        ? '处理中…'
                                        : (running ? '停止服务' : '启动服务'),
                                    fontSize: 13,
                                    height: 36,
                                    onClick: toggleService
                                },
                                null
                            )
                        ]
                    )
                ]
            ),

            ctx.UI.Spacer({ height: 12 }, null),

            // ---------- 统计卡片（三张并排，用 Column+Row 无 weight 的替代方案）----------
            statRow(ctx, total, requests),

            ctx.UI.Spacer({ height: 12 }, null),

            // ---------- 模型清单 ----------
            ctx.UI.Card(
                { fillMaxWidth: true, padding: 16, cornerRadius: 16 },
                [
                    ctx.UI.Row(
                        { verticalAlignment: 'center' },
                        [
                            ctx.UI.Text({ text: '模型清单', fontSize: 15, bold: true }, null),
                            ctx.UI.Text(
                                { text: '   ', fontSize: 12, color: '#888888' },
                                null
                            ),
                            ctx.UI.TextButton(
                                {
                                    text: '刷新', fontSize: 12,
                                    onClick: function () {
                                        setModels(null);
                                        fetchModels().then(function (list) { setModels(list); });
                                    }
                                },
                                null
                            )
                        ]
                    ),
                    ctx.UI.Divider(null, null),
                    ctx.UI.Spacer({ height: 8 }, null),
                    models === null
                        ? ctx.UI.Text({ text: '正在从上游拉取…', fontSize: 12, color: '#888888' }, null)
                        : modelSection(ctx, 'OpenCode 渠道', modelList.opencode.slice(0, 8), '#FFB74D'),
                    (models !== null && modelList.kilo.length > 0)
                        ? modelSection(ctx, 'Kilo 免费池（推荐，对话可用）', modelList.kilo, '#81C784')
                        : null
                ]
            ),

            ctx.UI.Spacer({ height: 12 }, null),

            // ---------- 使用指引 ----------
            ctx.UI.Card(
                { fillMaxWidth: true, padding: 16, cornerRadius: 16 },
                [
                    ctx.UI.Text({ text: '接入步骤', fontSize: 15, bold: true }, null),
                    ctx.UI.Spacer({ height: 10 }, null),
                    stepText(ctx, '1', '本面板点「启动服务」（或插件管理页 → 详情 → 启动服务）'),
                    stepText(ctx, '2', '设置 → 模型提供商 → 新建，Base URL 填上方地址'),
                    stepText(ctx, '3', 'API Key 随意填（如 sk-free），模型名选上面清单里的'),
                    stepText(ctx, '4', 'kilo- 前缀走 Kilo 免费池（免密可用），其余走 OpenCode'),
                    ctx.UI.Spacer({ height: 8 }, null),
                    ctx.UI.Text(
                        {
                            text: '注：OpenCode 渠道的对话接口被上游限制（仅官方客户端），对话请用 kilo- 前缀模型。',
                            fontSize: 11, color: '#888888'
                        },
                        null
                    )
                ]
            )
        ]
    );
}

/** 统计区：三张卡片。无 weight，改用三行式紧凑布局。 */
function statRow(ctx, total, requests) {
    return ctx.UI.Card(
        { fillMaxWidth: true, padding: 14, cornerRadius: 16, background: '#1E1E1E' },
        [
            ctx.UI.Row(
                {
                    fillMaxWidth: true,
                    horizontalArrangement: 'spaceBetween'
                },
                [
                    statItem(ctx, String(total), '可用模型', '#64B5F6'),
                    statItem(ctx, String(requests), '累计请求', '#81C784'),
                    statItem(ctx, '2', '上游渠道', '#FFB74D')
                ]
            )
        ]
    );
}

/** 单个统计项（横向排布时自然分列）。 */
function statItem(ctx, value, label, color) {
    return ctx.UI.Column(
        { horizontalAlignment: 'center' },
        [
            ctx.UI.Text({ text: value, fontSize: 22, bold: true, color: color }, null),
            ctx.UI.Text({ text: label, fontSize: 11, color: '#888888' }, null)
        ]
    );
}

/** 模型分组区块。 */
function modelSection(ctx, title, ids, color) {
    if (!ids || ids.length === 0) return null;
    return ctx.UI.Column(
        { padding: 6 },
        [
            ctx.UI.Text({ text: title, fontSize: 12, bold: true, color: color }, null),
            ctx.UI.Spacer({ height: 4 }, null)
        ].concat(ids.map(function (id) {
            return ctx.UI.Text(
                { text: '· ' + id, fontSize: 12, color: '#CCCCCC' },
                null
            );
        }))
    );
}

/** 步骤条目。 */
function stepText(ctx, num, text) {
    return ctx.UI.Text(
        { text: num + '. ' + text, fontSize: 12, color: '#BBBBBB' },
        { padding: 3 }
    );
}

/** 复制 baseUrl（ctx.copy 是 Promise，这里不阻塞 UI）。 */
function copyBaseUrl(port) {
    ctx.copy('http://127.0.0.1:' + port + '/v1').then(function () {
        ctx.showToast('已复制接口地址');
    });
}

/** 异步拉上游模型清单，返回 Promise<{opencode:[], kilo:[]}>。 */
function fetchModels() {
    var opencodeIds = ctx.http({
        url: 'https://opencode.ai/inference/v1/models',
        method: 'GET',
        headers: { 'Authorization': 'Bearer public' },
        timeout: 30000
    }).then(function (parsed) {
        if (parsed && parsed.ok && parsed.body) {
            var body = JSON.parse(parsed.body);
            if (body && body.data) {
                return body.data.map(function (m) { return m.id; });
            }
        }
        return [];
    });

    var kiloIds = ctx.http({
        url: 'https://api.kilo.ai/api/gateway/models',
        method: 'GET',
        headers: {},
        timeout: 30000
    }).then(function (parsed) {
        if (parsed && parsed.ok && parsed.body) {
            var body = JSON.parse(parsed.body);
            if (body && body.data) {
                return body.data.filter(function (m) { return m.isFree === true; })
                    .map(function (m) { return 'kilo-' + m.id; });
            }
        }
        return [];
    });

    return Promise.all([opencodeIds, kiloIds]).then(function (lists) {
        return { opencode: lists[0] || [], kilo: lists[1] || [] };
    });
}

module.exports = Screen;
