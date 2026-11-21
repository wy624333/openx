/**
 * 插件发现器 —— 工具侧。
 *
 * 工具侧 QuickJS 运行时没有 HTTP 能力（hostBridge 未接入），所以这里只做两件纯 JS 能真正实现的事：
 *  1. 把「下载某个插件」翻译成一条可直接执行的 shell 命令；
 *  2. 根据插件描述判定移植到 OpenX 的难度与建议形态。
 */
var $ptools = $ptools || [];

/** 三个生态的运行时形态，决定了移植路线。 */
var ECOSYSTEMS = {
    dsh: {
        label: 'DSH',
        runtime: 'Cordis + npm 包（TypeScript）',
        note: 'DSH 插件跑在 Cordis 之上，依赖 Node/npm 与桌面环境。逻辑可参考，UI 与宿主集成需重写。'
    },
    operit: {
        label: 'Operit',
        runtime: 'Rust SDK + TypeScript 声明',
        note: 'Operit 插件是 Android AI 助手生态的插件，运行时最接近 OpenX，移植价值最高。'
    },
    openx: {
        label: 'OpenX',
        runtime: 'QuickJS 插件（plugin.json + main.js + ui/）',
        note: '原生 OpenX 插件，可直接安装使用。'
    }
};

/**
 * 判定移植难度：看描述里有没有强平台绑定词。
 * 纯逻辑 → 低；界面为主 → 中；绑定系统能力 → 高/不可行。
 */
function assessPortability(desc) {
    var t = String(desc || '').toLowerCase();
    if (!t) return { level: 'unknown', label: '未知', hint: '没有描述，无法判断', form: '工具型' };

    // 强绑定：桌面窗口、SSH、浏览器注入、GPU 调度等，OpenX 做不到
    var hard = [
        ['ssh', '绑定 SSH 远程执行'],
        ['desktop', '绑定桌面端'],
        ['browser extension', '绑定浏览器扩展'],
        ['inject', '绑定页面注入'],
        ['gpu', '绑定 GPU 调度'],
        ['tray', '绑定系统托盘'],
        ['electron', '绑定 Electron 桌面端']
    ];
    for (var i = 0; i < hard.length; i++) {
        if (t.indexOf(hard[i][0]) >= 0) {
            return {
                level: 'hard',
                label: '不可直移',
                hint: hard[i][1] + '，OpenX 没有对应能力，只能借鉴思路',
                form: '纯工具型（重新实现）'
            };
        }
    }

    // 界面密集
    var uiHeavy = ['theme', 'skin', 'animation', 'pet', 'ui ', 'interface', 'panel', 'widget', 'avatar'];
    for (var j = 0; j < uiHeavy.length; j++) {
        if (t.indexOf(uiHeavy[j]) >= 0) {
            return {
                level: 'mid',
                label: '中',
                hint: '界面为主，逻辑可参考，UI 需按 OpenX 三期组件重写',
                form: '面板型'
            };
        }
    }

    // 平台能力类（手机/系统交互，OpenX 有对应工具）
    var mobile = ['android', 'phone', 'sms', 'notification', 'clipboard', 'location', 'camera', 'wechat', 'qq'];
    for (var k = 0; k < mobile.length; k++) {
        if (t.indexOf(mobile[k]) >= 0) {
            return {
                level: 'low',
                label: '低',
                hint: '平台能力类，OpenX 已有对应工具（phone_operator / notify 等），移植后往往更好用',
                form: '工具型 + 服务型'
            };
        }
    }

    // 纯逻辑：计算、解析、协议、计费
    var logic = ['cost', 'price', 'billing', 'parse', 'parser', 'convert', 'format', 'api', 'provider', 'model', 'token', 'encrypt', 'audit', 'chart', 'report'];
    for (var m = 0; m < logic.length; m++) {
        if (t.indexOf(logic[m]) >= 0) {
            return {
                level: 'low',
                label: '低',
                hint: '纯逻辑类，AI 改写成本最低，优先移植这类',
                form: '工具型'
            };
        }
    }

    return {
        level: 'mid',
        label: '中',
        hint: '能力与形态未知，建议先读源码再判断',
        form: '工具型'
    };
}

/** 生成下载命令：把 GitHub 仓库拉到工作区目标目录。 */
function buildDownloadCommand(repo, targetDir) {
    var r = String(repo || '').trim();
    var dir = String(targetDir || '').trim();
    if (!r) return '';
    if (!/^[\w.-]+\/[\w.-]+$/.test(r)) return '';
    // 目录名去掉斜杠，避免拼出多层路径
    var name = r.split('/')[1].replace(/\.git$/, '');
    var full = dir ? dir + '/' + name : name;
    return 'mkdir -p "' + full + '" && cd "' + full + '" && ' +
        'curl -4 -sL --max-time 120 -o repo.zip ' +
        '"https://gh-proxy.com/https://github.com/' + r + '/archive/refs/heads/main.zip" && ' +
        'unzip -q -o repo.zip && rm -f repo.zip && ls -1';
}

$ptools.push({
    name: 'plugin_finder_download_command',
    description: '把一个 GitHub 仓库转成「下载到工作区」的 shell 命令。用于插件发现器的下载落地。',
    parameters: {
        type: 'object',
        properties: {
            repo: { type: 'string', description: 'GitHub 仓库，形如 owner/name' },
            targetDir: { type: 'string', description: '容器内目标目录（如 /root/workspace/projects/xingde/downloads），可空' }
        },
        required: ['repo']
    },
    handler: function (args) {
        var a = args || {};
        var cmd = buildDownloadCommand(a.repo, a.targetDir);
        if (!cmd) {
            return JSON.stringify({ ok: false, error: 'repo 必须是 owner/name 形式' });
        }
        return JSON.stringify({ ok: true, command: cmd });
    }
});

$ptools.push({
    name: 'plugin_finder_portability',
    description: '根据插件能力描述，判定它移植到 OpenX 的难度、原因与建议的插件形态（工具型/面板型/服务型）。',
    parameters: {
        type: 'object',
        properties: {
            description: { type: 'string', description: '插件的能力描述文本' },
            ecosystem: { type: 'string', description: '所属生态：dsh / operit / openx' }
        },
        required: ['description']
    },
    handler: function (args) {
        var a = args || {};
        var eco = String(a.ecosystem || 'dsh').toLowerCase();
        var info = ECOSYSTEMS[eco] || ECOSYSTEMS.dsh;
        var p = assessPortability(a.description);
        return JSON.stringify({
            ok: true,
            ecosystem: info.label,
            runtime: info.runtime,
            note: info.note,
            level: p.level,
            levelLabel: p.label,
            hint: p.hint,
            suggestedForm: p.form
        });
    }
});
