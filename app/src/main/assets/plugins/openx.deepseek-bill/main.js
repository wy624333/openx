/**
 * DeepSeek 余额 — 工具侧（main.js）。
 *
 * 工具侧 QuickJS 只有 $ptools/$phandlers（无 ctx、无 HTTP、无 store），
 * 真实余额由 ui/panel.ui.js 通过 ctx.http 拉取并缓存到 ctx.store（__store__ 命名空间）。
 * 这里只做纯 JS 能实现的事：Key 格式校验 + 金额格式化，供 AI 在对话里调用。
 */
var $ptools = $ptools || [];

/** 校验 DeepSeek API Key 格式：sk- 前缀 + 24 位以上字母数字（-_）。 */
function $dsb_checkKey(key) {
    var s = String(key || '').trim();
    if (!s) return { ok: false, error: 'Key 为空' };
    if (s.indexOf('sk-') !== 0) return { ok: false, error: '应以 sk- 开头' };
    if (s.length < 24) return { ok: false, error: '太短，应不少于 24 位' };
    if (!/^[A-Za-z0-9_-]+$/.test(s)) return { ok: false, error: '含非法字符' };
    return { ok: true, masked: s.slice(0, 5) + '…' + s.slice(-4) };
}

$ptools.push({
    name: 'deepseekbill_check_key',
    description: '校验 DeepSeek API Key 的格式是否合法（sk- 前缀、24 位以上、字符集），不联网。',
    parameters: {
        type: 'object',
        properties: {
            key: { type: 'string', description: '待校验的 API Key' }
        },
        required: ['key']
    },
    handler: function (args) {
        var r = $dsb_checkKey((args || {}).key);
        return JSON.stringify(r);
    }
});

$ptools.push({
    name: 'deepseekbill_format_balance',
    description: '把 DeepSeek /user/balance 接口返回的 JSON 格式化成中文摘要文本（总额/赠额/充值额/是否够用）。',
    parameters: {
        type: 'object',
        properties: {
            json: { type: 'string', description: '/user/balance 接口的原始响应 JSON 文本' }
        },
        required: ['json']
    },
    handler: function (args) {
        var text = String((args || {}).json || '').trim();
        if (!text) return JSON.stringify({ ok: false, error: 'json 为空' });
        var data;
        try { data = JSON.parse(text); } catch (e) {
            return JSON.stringify({ ok: false, error: '不是合法 JSON：' + (e && e.message ? e.message : String(e)) });
        }
        var lines = [];
        lines.push(data.is_available === true ? '余额充足，可正常调用' : '余额不足，调用会被拒绝');
        var infos = data.balance_infos || [];
        for (var i = 0; i < infos.length; i++) {
            var b = infos[i];
            lines.push(
                '[' + (b.currency || '?') + '] 总额 ' + (b.total_balance || '0') +
                '（赠额 ' + (b.granted_balance || '0') +
                ' + 充值 ' + (b.topped_up_balance || '0') + '）'
            );
        }
        if (!infos.length) lines.push('（无币种明细）');
        return JSON.stringify({ ok: true, summary: lines.join('\n') });
    }
});