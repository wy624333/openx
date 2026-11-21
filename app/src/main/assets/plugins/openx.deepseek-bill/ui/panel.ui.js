/**
 * DeepSeek 余额 — 面板（ui/panel.ui.js）。
 *
 * 写法约定（对齐 free-model-gateway 面板，均真机验证过）：
 *  - 布局属性直接写组件 props 第一层：{ fillMaxWidth: true, padding: 16 }
 *  - Row 内首个子元素禁用 fillMaxWidth（会挤掉后续兄弟节点）
 *  - ctx.http / ctx.copy 返回 Promise（同步实现），必须 .then() 取结果
 *  - ctx.useState 的 setter 触发宿主自动重渲染；ctx.store.set 不会
 *  - 异步回调后宿主会自动重调 Screen()（renderLocked 补渲染循环），首次自动拉取可用
 *  - toast API 名是 ctx.showToast
 */
function Screen(ctx) {
    var saved = ctx.store.get('dsb_saved', {}) || {};
    var apiKey = saved.key || '';
    var keyMasked = apiKey ? apiKey.slice(0, 5) + '…' + apiKey.slice(-4) : '';

    var balancePair = ctx.useState('dsb_balance', null);
    var balance = balancePair[0];
    var setBalance = balancePair[1];

    var busyPair = ctx.useState('dsb_busy', false);
    var busy = busyPair[0];
    var setBusy = busyPair[1];

    var errState = ctx.useState('dsb_err', '');
    var err = errState[0];
    var setErr = errState[1];

    var inputPair = ctx.useState('dsb_input', '');
    var inputKey = inputPair[0];
    var setInputKey = inputPair[1];

    // 首次打开：有 Key 且无缓存 → 自动拉一次
    if (apiKey && !balance && !busy) {
        fetchBalance(apiKey);
    }

    /** 调 DeepSeek /user/balance，成功后写缓存。 */
    function fetchBalance(key) {
        setBusy(true);
        setErr('');
        ctx.http({
            url: 'https://api.deepseek.com/user/balance',
            method: 'GET',
            headers: { 'Authorization': 'Bearer ' + key },
            timeout: 30000
        }).then(function (res) {
            if (!res || res.ok !== true) {
                setErr('请求失败：' + ((res && res.error) || ('HTTP ' + (res && res.status))) +
                    '。请检查 Key 是否正确、网络是否可达。');
                setBalance(null);
            } else {
                var data = null;
                try { data = JSON.parse(res.body); } catch (e) { /* 保持 null */ }
                if (!data || !data.balance_infos) {
                    setErr('响应格式异常：' + String(res.body).slice(0, 120));
                    setBalance(null);
                } else {
                    var cny = null;
                    for (var i = 0; i < data.balance_infos.length; i++) {
                        if (data.balance_infos[i].currency === 'CNY') { cny = data.balance_infos[i]; break; }
                    }
                    var b = cny || data.balance_infos[0] || {};
                    var rec = {
                        fetchedAt: Date.now(),
                        isAvailable: data.is_available === true,
                        total: b.total_balance || '0',
                        granted: b.granted_balance || '0',
                        toppedUp: b.topped_up_balance || '0',
                        currency: b.currency || 'CNY'
                    };
                    ctx.store.set('dsb_last', rec);
                    setBalance(rec);
                }
            }
            setBusy(false);
        });
    }

    /** 保存 Key（校验格式后落盘）。 */
    function saveKey() {
        var k = String(inputKey || '').trim();
        if (!k) { setErr('请先输入 API Key'); return; }
        if (k.indexOf('sk-') !== 0) { setErr('Key 应以 sk- 开头'); return; }
        if (k.length < 24) { setErr('Key 太短（应不少于 24 位）'); return; }
        ctx.store.set('dsb_saved', { key: k, savedAt: Date.now() });
        ctx.showToast('Key 已保存');
        setBalance(null);
        fetchBalance(k);
    }

    /** 清除 Key 与缓存。 */
    function clearAll() {
        ctx.store.set('dsb_saved', {});
        ctx.store.set('dsb_last', null);
        setBalance(null);
        setInputKey('');
        setErr('');
        ctx.showToast('已清除 Key 与缓存');
    }

    /** 手动刷新。 */
    function refresh() {
        if (!apiKey) { setErr('请先保存 API Key'); return; }
        if (busy) return;
        fetchBalance(apiKey);
    }

    function fmtTime(ts) {
        if (!ts) return '—';
        var d = new Date(ts);
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }

    // ---------------- 视图 ----------------

    var header = ctx.UI.Card(
        { fillMaxWidth: true, padding: 18, cornerRadius: 16 },
        [
            ctx.UI.Row(
                { verticalAlignment: 'centerVertically' },
                [
                    ctx.UI.Text({ text: 'DeepSeek 余额', fontSize: 19, bold: true }, null),
                    ctx.UI.Text({ text: '   ', fontSize: 12 }, null),
                    busy
                        ? ctx.UI.Text({ text: '刷新中…', fontSize: 12, color: '#888888' }, null)
                        : ctx.UI.TextButton({ text: '刷新', fontSize: 12, onClick: refresh }, null)
                ]
            ),
            ctx.UI.Spacer({ height: 10 }, null),
            balance === null
                ? ctx.UI.Text(
                    { text: busy ? '正在查询…' : '暂无数据，保存 Key 后自动查询', fontSize: 13, color: '#888888' },
                    null)
                : ctx.UI.Column({}, [
                    ctx.UI.Text(
                        {
                            text: (balance.isAvailable ? '✓ 余额充足' : '⚠ 余额不足'),
                            fontSize: 13, bold: true,
                            color: balance.isAvailable ? '#4CAF50' : '#FF6B6B'
                        }, null),
                    ctx.UI.Spacer({ height: 6 }, null),
                    ctx.UI.Row(
                        { verticalAlignment: 'bottom' },
                        [
                            ctx.UI.Text(
                                { text: balance.currency + ' ', fontSize: 14, color: '#888888' },
                                null),
                            ctx.UI.Text(
                                { text: balance.total, fontSize: 34, bold: true, color: '#64B5F6' },
                                null)
                        ]),
                    ctx.UI.Text(
                        { text: '更新于 ' + fmtTime(balance.fetchedAt), fontSize: 11, color: '#888888' },
                        null)
                ])
        ]
    );

    var detailCard = balance
        ? ctx.UI.Card(
            { fillMaxWidth: true, padding: 16, cornerRadius: 16 },
            [
                ctx.UI.Text({ text: '明细', fontSize: 15, bold: true }, null),
                ctx.UI.Spacer({ height: 8 }, null),
                ctx.UI.Row(
                    { fillMaxWidth: true, horizontalArrangement: 'spaceBetween' },
                    [
                        ctx.UI.Text({ text: '赠送余额', fontSize: 13, color: '#888888' }, null),
                        ctx.UI.Text({ text: balance.currency + ' ' + balance.granted, fontSize: 13, bold: true }, null)
                    ]),
                ctx.UI.Spacer({ height: 6 }, null),
                ctx.UI.Row(
                    { fillMaxWidth: true, horizontalArrangement: 'spaceBetween' },
                    [
                        ctx.UI.Text({ text: '充值余额', fontSize: 13, color: '#888888' }, null),
                        ctx.UI.Text({ text: balance.currency + ' ' + balance.toppedUp, fontSize: 13, bold: true }, null)
                    ])
            ])
        : null;

    var errCard = err || errState[0]
        ? ctx.UI.Card(
            { fillMaxWidth: true, padding: 12, cornerRadius: 12, background: '#2AFF6B6B' },
            [
                ctx.UI.Text(
                    { text: err || errState[0], fontSize: 12, color: '#FF8A8A', fillMaxWidth: true },
                    null)
            ])
        : null;

    var keyCard = ctx.UI.Card(
        { fillMaxWidth: true, padding: 16, cornerRadius: 16 },
        [
            ctx.UI.Text({ text: 'API Key', fontSize: 15, bold: true }, null),
            ctx.UI.Spacer({ height: 4 }, null),
            ctx.UI.Text(
                {
                    text: apiKey ? ('已保存：' + keyMasked) : '未配置。Key 在 DeepSeek 平台 → API Keys 页生成。',
                    fontSize: 11, color: '#888888'
                }, null),
            ctx.UI.Spacer({ height: 8 }, null),
            ctx.UI.TextField(
                {
                    value: inputKey,
                    onValueChange: setInputKey,
                    placeholder: 'sk-…'
                }, null),
            ctx.UI.Spacer({ height: 10 }, null),
            ctx.UI.Row(
                { verticalAlignment: 'centerVertically' },
                [
                    ctx.UI.Button({ text: '保存并查询', onClick: saveKey }, null),
                    ctx.UI.Text({ text: '  ', fontSize: 12 }, null),
                    apiKey ? ctx.UI.TextButton({ text: '清除', color: 'error', onClick: clearAll }, null) : null
                ])
        ]
    );

    var hintCard = ctx.UI.Card(
        { fillMaxWidth: true, padding: 16, cornerRadius: 16 },
        [
            ctx.UI.Text({ text: '说明', fontSize: 15, bold: true }, null),
            ctx.UI.Spacer({ height: 8 }, null),
            ctx.UI.Text(
                { text: '· 数据来自官方接口 GET api.deepseek.com/user/balance', fontSize: 12, color: '#BBBBBB' }, null),
            ctx.UI.Text(
                { text: '· Key 只存在本机插件存储，不会上传到任何第三方', fontSize: 12, color: '#BBBBBB' }, null),
            ctx.UI.Text(
                { text: '· 打开面板自动查询一次，平时点「刷新」手动更新', fontSize: 12, color: '#BBBBBB' }, null)
        ]
    );

    return ctx.UI.Column(
        { padding: 12, fillMaxWidth: true, verticalArrangement: 'spacedBy' },
        [
            header,
            detailCard,
            errCard,
            keyCard,
            hintCard
        ]
    );
}

module.exports = Screen;