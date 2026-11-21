/**
 * 插件发现器 —— 界面。
 *
 * 三个 Tab：
 *  · 发现：跨 DSH / Operit / OpenX 三个生态搜索插件，展示能力、许可、移植难度
 *  · 下载：把选中的插件拉到工作区，并生成 OpenX 移植指引
 *  · 模式：可复用的插件设计思路（来自真实头部插件的结构拆解）
 *
 * 数据源：
 *  · DSH   —— cccakeee/awesome-dsh-plugins 的分类清单（markdown 表格）
 *  · Operit—— AAswordman/Operit2 的插件目录树（GitHub API）
 *  · OpenX —— GitHub 搜索（无公开索引，只能按关键词搜）
 */
function Screen(ctx) {
    // ---------------- 状态 ----------------
    const [tab, setTab] = ctx.useState('tab', 0);
    const [keyword, setKeyword] = ctx.useState('keyword', '');
    const [eco, setEco] = ctx.useState('eco', 0);
    const [category, setCategory] = ctx.useState('category', 0);
    const [items, setItems] = ctx.useState('items', []);
    const [loading, setLoading] = ctx.useState('loading', false);
    const [err, setErr] = ctx.useState('err', '');
    const [detail, setDetail] = ctx.useState('detail', null);
    const [dlLog, setDlLog] = ctx.useState('dlLog', '');
    const [dlBusy, setDlBusy] = ctx.useState('dlBusy', false);
    const [targetDir, setTargetDir] = ctx.useState('pfDownloadDir', '/root/workspace/downloads');

    const ECOS = ['DSH', 'Operit', 'OpenX'];

    // DSH 的 20 个分类（与上游 awesome-dsh-plugins 的 docs/categories 一一对应）
    const DSH_CATS = [
        'Agent、自动化与工作流', '界面与体验', 'Web 界面与前端', '知识与研究',
        '聊天与 IM', '设计、媒体与视觉', '工具', '桌面与应用',
        '插件工具', '安全与鉴权', '网页与浏览器', 'MCP 与协议',
        '用量与计费', '纯属好玩', '生态与资源', '会话与消息',
        '语音', '主题与皮肤', '命令行与终端', '集成与分享'
    ];

    // 分类 → 上游文件名
    const CAT_FILE = {
        'Agent、自动化与工作流': 'agents-workflows', '界面与体验': 'ui-experience',
        'Web 界面与前端': 'web-ui', '知识与研究': 'knowledge-research',
        '聊天与 IM': 'chat-im', '设计、媒体与视觉': 'media-vision',
        '工具': 'utilities', '桌面与应用': 'desktop',
        '插件工具': 'plugin-tools', '安全与鉴权': 'security',
        '网页与浏览器': 'web-browser', 'MCP 与协议': 'mcp',
        '用量与计费': 'billing', '纯属好玩': 'fun',
        '生态与资源': 'ecosystem-resources', '会话与消息': 'sessions-messages',
        '语音': 'voice', '主题与皮肤': 'theme',
        '命令行与终端': 'cli', '集成与分享': 'integrations-sharing'
    };

    const DSH_RAW = 'https://raw.githubusercontent.com/cccakeee/awesome-dsh-plugins/main/docs/categories/';
    const DSH_MIRROR = 'https://gh-proxy.com/https://raw.githubusercontent.com/cccakeee/awesome-dsh-plugins/main/docs/categories/';
    const API = 'https://gh-proxy.com/https://api.github.com/';

    // ---------------- 网络小工具 ----------------

    /** 依次尝试直连与镜像，任一成功即可。 */
    /**
     * 取文本。**镜像优先**。
     *
     * 宿主 ctx.http 超时是全局 60s 且不可调，国内直连 raw.githubusercontent.com
     * 经常超时——直连放前面会让每次请求先卡满 60s。镜像在国内通常几百毫秒。
     */
    async function fetchText(urls) {
        const list = Array.isArray(urls) ? urls : [urls];
        let lastErr = '';
        for (let i = 0; i < list.length; i++) {
            try {
                const res = await ctx.http({ url: list[i], method: 'GET' });
                if (res && res.ok === true && res.body) {
                    if (res.truncated) {
                        throw new Error('响应超过宿主 2MB 上限被截断');
                    }
                    return String(res.body);
                }
                lastErr = (res && res.error) || ('HTTP ' + (res && res.status));
            } catch (e) {
                lastErr = (e && e.message) ? e.message : String(e);
            }
        }
        throw new Error(lastErr || '请求失败');
    }

    async function fetchJson(urls) {
        const txt = await fetchText(urls);
        try {
            return JSON.parse(txt);
        } catch (e) {
            throw new Error('返回不是合法 JSON');
        }
    }

    /**
     * 解析 DSH 分类清单的 markdown 表格。
     * 行格式：| [owner/name](url) | 能力 | 安装方式 | 许可/风险 |
     */
    function parseDshTable(md) {
        const out = [];
        const lines = String(md || '').split('\n');
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            if (line.charAt(0) !== '|') continue;
            if (line.indexOf('github.com') < 0) continue;
            const cells = line.split('|').slice(1, -1);
            if (cells.length < 2) continue;

            const m = cells[0].match(/\[([^\]]+)\]\((https:\/\/github\.com\/[^)]+)\)/);
            if (!m) continue;

            const repo = m[1].trim();
            if (repo.indexOf('/') < 0) continue;

            out.push({
                eco: 'dsh',
                repo: repo,
                url: m[2],
                name: repo.split('/')[1],
                desc: cleanCell(cells[1]),
                install: cleanCell(cells[2] || ''),
                license: cleanCell(cells[3] || ''),
                stars: 0
            });
        }
        return out;
    }

    function cleanCell(s) {
        return String(s || '')
            .replace(/\*\*/g, '')
            .replace(/`/g, '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    /** 取描述首句，避免长描述撑爆列表。 */
    function firstSentence(s) {
        return String(s || '').replace(/\s+/g, ' ').trim();
    }

    // ---------------- 移植难度判定（与工具侧同规则） ----------------

    function assessPortability(desc) {
        const t = String(desc || '').toLowerCase();
        if (!t) return { level: 'unknown', label: '未知', hint: '没有描述', form: '工具型' };

        const hard = [
            ['ssh', '绑定 SSH 远程执行'], ['desktop', '绑定桌面端'],
            ['browser extension', '绑定浏览器扩展'], ['inject', '绑定页面注入'],
            ['gpu', '绑定 GPU 调度'], ['tray', '绑定系统托盘'],
            ['electron', '绑定 Electron 桌面端']
        ];
        for (let i = 0; i < hard.length; i++) {
            if (t.indexOf(hard[i][0]) >= 0) {
                return { level: 'hard', label: '不可直移', hint: hard[i][1] + '，只能借鉴思路', form: '工具型（重写）' };
            }
        }
        const uiHeavy = ['theme', 'skin', 'animation', 'pet', 'ui ', 'interface', 'panel', 'widget', 'avatar'];
        for (let j = 0; j < uiHeavy.length; j++) {
            if (t.indexOf(uiHeavy[j]) >= 0) {
                return { level: 'mid', label: '中', hint: '界面为主，逻辑可参考，UI 按 OpenX 组件重写', form: '面板型' };
            }
        }
        const mobile = ['android', 'phone', 'sms', 'notification', 'clipboard', 'location', 'camera', 'wechat', 'qq'];
        for (let k = 0; k < mobile.length; k++) {
            if (t.indexOf(mobile[k]) >= 0) {
                return { level: 'low', label: '低', hint: '平台能力类，OpenX 已有对应工具，移植后往往更好用', form: '工具型 + 服务型' };
            }
        }
        const logic = ['cost', 'price', 'billing', 'parse', 'convert', 'format', 'api', 'provider', 'model', 'token', 'audit', 'chart', 'report'];
        for (let m = 0; m < logic.length; m++) {
            if (t.indexOf(logic[m]) >= 0) {
                return { level: 'low', label: '低', hint: '纯逻辑类，AI 改写成本最低，优先移植这类', form: '工具型' };
            }
        }
        return { level: 'mid', label: '中', hint: '能力与形态未知，建议先读源码', form: '工具型' };
    }

    function levelColor(level) {
        if (level === 'low') return 'success';
        if (level === 'mid') return 'warning';
        if (level === 'hard') return 'error';
        return 'textsecondary';
    }

    // ---------------- 数据加载 ----------------

    /** 加载 DSH 某个分类的清单。 */
    async function loadDsh(catName) {
        const file = CAT_FILE[catName];
        if (!file) return [];
        const md = await fetchText([DSH_MIRROR + file + '.md', DSH_RAW + file + '.md']);
        return parseDshTable(md);
    }

    /** 加载 Operit 插件目录树（buildin + external 两层）。 */
    async function loadOperit() {
        const out = [];
        const dirs = [
            'plugins/packages/buildin',
            'plugins/packages/external',
            'plugins/packages/examples'
        ];
        for (let i = 0; i < dirs.length; i++) {
            let list;
            try {
                list = await fetchJson(API + 'repos/AAswordman/Operit2/contents/' + dirs[i]);
            } catch (e) {
                continue;
            }
            if (!Array.isArray(list)) continue;
            for (let j = 0; j < list.length; j++) {
                const it = list[j];
                // dist / node_modules 等是构建产物，不是插件
                if (it.name === 'dist' || it.name === 'node_modules' || it.name === 'scripts' || it.name === 'tools') continue;
                if (it.type === 'dir') {
                    out.push({
                        eco: 'operit',
                        repo: 'AAswordman/Operit2',
                        url: 'https://github.com/AAswordman/Operit2/tree/main/' + it.path,
                        name: it.name,
                        desc: 'Operit 官方插件包 · ' + it.path,
                        install: '仓库路径 ' + it.path,
                        license: '见仓库 LICENSE',
                        stars: 0
                    });
                } else if (it.type === 'file' && /\.(ts|js)$/.test(it.name)) {
                    out.push({
                        eco: 'operit',
                        repo: 'AAswordman/Operit2',
                        url: 'https://github.com/AAswordman/Operit2/blob/main/' + it.path,
                        name: it.name.replace(/\.(ts|js)$/, ''),
                        desc: 'Operit 内置工具 · ' + it.path,
                        install: '仓库路径 ' + it.path,
                        license: '见仓库 LICENSE',
                        stars: 0
                    });
                }
            }
        }
        return out;
    }

    /**
     * OpenX 生态：扫本机的插件（已装的 + 工作区里的源码）。
     *
     * 不用 GitHub 搜索 —— "openx" 在 GitHub 上被同名项目占满（OpenXcom 游戏、
     * OpenXR 引擎等），搜出来的全是噪音，反而不如扫本地准确。
     */
    async function loadOpenx() {
        const roots = [
            '/root/.openx/plugins',
            '/root/workspace/projects'
        ];
        const found = [];
        for (let i = 0; i < roots.length; i++) {
            const res = await ctx.callTool('file_manager', {
                action: 'search',
                path: roots[i],
                pattern: 'plugin.json',
                maxDepth: 4,
                maxResults: 60
            });
            const text = String((res && res.result) ? res.result : (res || ''));
            const lines = text.split('\n');
            for (let j = 0; j < lines.length; j++) {
                const line = lines[j];
                if (line.indexOf('plugin.json') < 0) continue;
                // search 返回的是**相对**起始目录的路径，这里还原成绝对路径
                const rel = (line.match(/(\S*plugin\.json)/) || [])[1];
                if (!rel) continue;
                const p = rel.charAt(0) === '/' ? rel : (roots[i].replace(/\/$/, '') + '/' + rel);
                const dir = p.replace(/\/plugin\.json$/, '');
                if (found.some(function (x) { return x.path === dir; })) continue;

                // 读清单拿名字与描述
                let name = dir.split('/').pop();
                let desc = '';
                try {
                    const r = await ctx.callTool('file_manager', { action: 'read', path: p });
                    const t = String((r && r.result) ? r.result : (r || ''));
                    const m = t.match(/"name"\s*:\s*"([^"]*)"/);
                    const d = t.match(/"description"\s*:\s*"([^"]*)"/);
                    if (m) name = m[1];
                    if (d) desc = d[1];
                } catch (e) { /* 读不到就退回目录名 */ }

                found.push({
                    eco: 'openx',
                    repo: name,
                    url: dir,
                    path: dir,
                    name: name,
                    desc: desc || '本地 OpenX 插件（' + dir + '）',
                    install: '已是 OpenX 插件，可直接使用',
                    license: '本地',
                    stars: 0
                });
            }
        }
        return found;
    }

    /** 拉取仓库 star 数（DSH/Operit 清单里没有这个字段）。 */
    async function fetchStars(repo) {
        try {
            const d = await fetchJson(API + 'repos/' + repo);
            return d && d.stargazers_count ? d.stargazers_count : 0;
        } catch (e) {
            return 0;
        }
    }

    /**
     * 搜索入口。
     *
     * ⚠ 关键：宿主 `ctx.http` 是 **runBlocking 同步**实现，而 action 派发期间宿主持有
     * session 锁（PluginUiSession.dispatchAction 是 synchronized）。在 onClick 里直接
     * await 网络请求，会把锁占住整个 HTTP 时长（宿主超时 60s）——表现为「点了没反应」，
     * 整块面板僵死几十秒（真机踩过：logcat 里是 "Long monitor contention ... wait 43s"）。
     *
     * 所以这里只 setTimeout(0) 把真正的网络调用挪到锁外，dispatch 立刻返回。
     */
    function doSearch() {
        if (loading) return;
        setLoading(true);
        setErr('');
        setDetail(null);
        setTimeout(function () { runSearch(); }, 0);
    }

    async function runSearch() {
        try {
            let list = [];
            const which = ECOS[eco] || 'DSH';

            if (which === 'DSH') {
                const catName = DSH_CATS[category] || DSH_CATS[0];
                list = await loadDsh(catName);
            } else if (which === 'Operit') {
                list = await loadOperit();
            } else {
                list = await loadOpenx();
            }

            // 关键词过滤（三个生态都在本地过滤）
            const kw = String(keyword || '').trim().toLowerCase();
            if (kw) {
                list = list.filter(function (it) {
                    return (it.name + ' ' + it.desc + ' ' + it.repo).toLowerCase().indexOf(kw) >= 0;
                });
            }

            setItems(list);
            if (!list.length) {
                setErr('没有匹配结果。换个关键词或换个分类试试。');
            }
        } catch (e) {
            setErr('加载失败：' + ((e && e.message) ? e.message : String(e)));
            setItems([]);
        } finally {
            setLoading(false);
        }
    }

    /**
     * 打开详情。补拉 star 需要联网，同样不能占着 dispatch 的锁。
     */
    function openDetail(it) {
        setDetail(it);
        if (it.stars > 0 || it.eco === 'operit') return;
        setTimeout(function () { fetchStarsThen(it); }, 0);
    }

    async function fetchStarsThen(it) {
        const s = await fetchStars(it.repo);
        if (s > 0) {
            setItems((items || []).map(function (x) {
                return x.repo === it.repo ? Object.assign({}, x, { stars: s }) : x;
            }));
            if (detail && detail.repo === it.repo) {
                setDetail(Object.assign({}, detail, { stars: s }));
            }
        }
    }

    // ---------------- 下载 ----------------

    /** 通过宿主 terminal 工具执行 shell 命令（插件桥不经过权限审批，但受命令守卫约束）。 */
    async function runShell(cmd) {
        const res = await ctx.callTool('terminal', { action: 'execute', command: cmd, timeout: 180 });
        return String((res && res.result) ? res.result : (res || ''));
    }

    /** 生成 OpenX 移植指引。 */
    function buildPortingGuide(it) {
        const p = assessPortability(it.desc);
        const ecoRuntime = {
            dsh: 'Cordis + npm（TypeScript 源码，需 pnpm build）',
            operit: 'Rust SDK 插件包（ToolPkg 源码）',
            openx: 'OpenX 原生插件（plugin.json + main.js + ui/）'
        };
        const ecoNote = {
            dsh: 'DSH 插件依赖 Node/npm 与桌面环境，OpenX 没有对应运行时。**逻辑可移植，UI 与宿主集成必须重写。**',
            operit: 'Operit 同为安卓 AI 助手，运行时最接近 OpenX，移植性价比最高。',
            openx: '原生 OpenX 插件，可直接安装使用。'
        };

        return [
            '# 移植指引：' + it.repo,
            '',
            '## 来源',
            '- 仓库：' + it.url,
            '- 所属生态：' + (ECOS[eco] || 'DSH') + '（运行时：' + (ecoRuntime[it.eco] || '未知') + '）',
            '- 原始能力：' + (it.desc || '(无描述)'),
            '- 许可：' + (it.license || '未知，移植前请自行确认授权'),
            '',
            '## 重要提醒',
            ecoNote[it.eco] || '',
            '本目录是**参考素材**，不是可安装的 OpenX 插件。安装需在 OpenX 设置 → 插件页导入 zip。',
            '',
            '## 移植难度',
            '- 难度：**' + p.label + '**',
            '- 原因：' + p.hint,
            '- 建议形态：' + p.form,
            '',
            '## 建议步骤',
            '1. 先读源码，确认核心逻辑与宿主耦合点（哪些依赖 DSH/Operit 的 API，哪些是纯逻辑）。',
            '2. 抽出纯逻辑部分，按 OpenX 工具侧约定改写：',
            '   - 入口 `main.js` 用 `var $ptools = $ptools || [];` 声明工具',
            '   - 每个工具给 name / description / parameters / handler',
            '   - 工具侧**没有 HTTP 能力**，联网逻辑要放到 UI 面板的 `ctx.http` 或服务型插件',
            '3. 需要界面就加 `ui/panel.ui.js`，导出 `function Screen(ctx)`，返回 `ctx.UI.*` 组件树。',
            '4. 写 `plugin.json`：id / name / version / description / main / ui / permissions。',
            '   权限只给真正用到的：toast / env / http / call_tool / file / clipboard / notify / server。',
            '5. 打包成 zip（含 plugin.json），在设置页导入。',
            '',
            '## OpenX 插件骨架',
            '',
            '```json',
            '{',
            '  "id": "openx.your-plugin",',
            '  "name": "你的插件名",',
            '  "version": "1.0.0",',
            '  "description": "一句话说明它做什么",',
            '  "author": "wy",',
            '  "main": "main.js",',
            '  "ui": "ui/panel.ui.js",',
            '  "apiVersion": "1.0.0",',
            '  "permissions": ["toast", "http"],',
            '  "quickActions": [',
            '    { "label": "打开", "action": "openUi", "icon": "search" }',
            '  ]',
            '}',
            '```',
            '',
            '## 改造一句话指令',
            '',
            '> 把 `' + (targetDir || '/root/workspace/downloads') + '/' + it.name + '` 目录里的参考实现，',
            '> 改写成符合 OpenX 插件规范的实现：产出 `plugin.json` + `main.js`（+ `ui/panel.ui.js`），',
            '> 打包成 zip 放在同级目录。只移植核心逻辑，UI 按 OpenX 三期组件重写，不要试图翻译原框架代码。'
        ].join('\n');
    }

    /**
     * 下载。走 terminal 工具也要异步，否则会占着 dispatch 锁跑完整个下载。
     */
    function doDownload(it) {
        if (dlBusy) return;
        setDlBusy(true);
        setDlLog('开始下载 ' + it.repo + ' …');
        // 切到「下载记录」Tab，让用户能看到进度和结果
        setTab(1);
        setTimeout(function () { runDownload(it); }, 0);
    }

    async function runDownload(it) {
        try {
            const dir = String(targetDir || '').trim();

            // OpenX 本地插件已经在机器上，不用下载，直接给指引
            if (it.eco === 'openx') {
                setDlLog('本地 OpenX 插件，无需下载。\n路径：' + it.path +
                    '\n\n这是本机已装或工作区里的原生插件，可直接使用。' +
                    '\n若要改造它，AI 可直接读写该目录。');
                ctx.showToast('本地插件，无需下载');
                return;
            }

            const name = it.repo.split('/')[1].replace(/\.git$/, '');
            const full = dir ? dir + '/' + name : name;

            // GitHub 的 zip 解压出来是 <repo>-main/ 子目录，这里把内容整体上提一层，
            // 让源码和移植指引平级，AI 改造时不用多绕一层。
            // 注意：mv "$d"* 只能上提顶层文件，子目录会留在原地变成两半，
            // 所以先把目录改成临时名再整体搬。
            const cmd = 'mkdir -p "' + full + '" && cd "' + full + '" && ' +
                'curl -4 -sL --max-time 180 -o repo.zip ' +
                '"https://gh-proxy.com/https://github.com/' + it.repo + '/archive/refs/heads/main.zip" && ' +
                'unzip -q -o repo.zip && rm -f repo.zip && ' +
                'd=$(ls -d */ 2>/dev/null | head -1) && ' +
                'if [ -n "$d" ]; then mv "$d" .tmpdir 2>/dev/null && mv .tmpdir/* . 2>/dev/null && rm -rf .tmpdir; fi; ' +
                'ls -1 | head -20';

            const out = await runShell(cmd);
            setDlLog('下载完成 → ' + full + '\n' + out);

            // 写移植指引（与源码同目录）
            const guide = buildPortingGuide(it);
            const guideCmd = 'cat > "' + full + '/README-移植指引.md" <<\'__EOF__\'\n' +
                guide + '\n__EOF__\nls -la "' + full + '" | head -12';
            const out2 = await runShell(guideCmd);
            setDlLog('下载完成 → ' + full + '\n' + out + '\n--- 移植指引已写入 ---\n' + out2);
            ctx.showToast('已下载到 ' + full);
        } catch (e) {
            setDlLog('下载失败：' + ((e && e.message) ? e.message : String(e)));
            ctx.showToast('下载失败');
        } finally {
            setDlBusy(false);
        }
    }

    // ---------------- 设计模式库 ----------------

    const PATTERNS = [
        {
            name: '单一状态源 + 轮询',
            from: 'dsh-pet（DSH 最热娱乐插件，3036 star）',
            body: '所有状态位收敛到一个接口，界面只读这一个源。dsh-pet 用 GET /state，带 ?since=&timeout= 支持长轮询。',
            apply: 'OpenX 对应：服务型插件把状态存 ctx.store，UI 面板用 useState 读；或用定时器轮询服务端口。避免多处各存一份导致状态打架。'
        },
        {
            name: '占用锁：资源独占与强制接管',
            from: 'dsh-pet 的 lock / lock.release 接口',
            body: '演出/长任务期间独占资源，别的调用方静音。持锁者用 lockId 续期，force 参数允许用户强行接管。',
            apply: 'OpenX 对应：多开桌宠、多实例服务时的互斥。ctx.store 里存一个锁记录，带过期时间。'
        },
        {
            name: '双端同源，一份实现',
            from: 'dsh-pet 同时提供 HTTP 路由与 Cordis 服务',
            body: '桌面端走 HTTP，网页端走进程内服务，但读的是同一份实现，不复制逻辑。',
            apply: 'OpenX 对应：UI 面板与服务型插件共享同一份 ctx.store / env。宿主本身就这么设计（UI 插件与服务插件读写同一份持久化文件），顺着做即可。'
        },
        {
            name: '契约先行：三层文档',
            from: 'dsh-pet 的 API.md + openapi.yaml + CORDIS.md',
            body: '功能预览（给用户看）、完整契约（给集成方看）、服务契约（给扩展方看），三层分开写。',
            apply: 'OpenX 对应：plugin.json 字段语义写清楚、每个工具的入参出参写清楚、再补一段"怎么把别的生态插件接进来"的说明。'
        },
        {
            name: '素材生成链：让插件可自我复制',
            from: 'dsh-pet 的三件套结构（提示词配方 → 素材管线 → 插件成品）',
            body: '不只交付一个插件，还交付"怎么造出这类插件"的方法。任何人 clone 后能换个角色、换套动作生成自己的版本。',
            apply: 'OpenX 对应：宿主已有 generate_image 与 view_image，这条链天然通。可以做"AI 生成素材 → 打包成插件资产"的流水线型插件。'
        },
        {
            name: '许可与风险随插件一起走',
            from: 'awesome-dsh-plugins 清单的第四列',
            body: '每个插件都标注许可与已知风险（"仅快照、不长期维护"、"README 说桌面渲染未验证"）。',
            apply: 'OpenX 对应：移植指引里必须写清原插件许可与风险，不能让用户把不清楚授权的代码直接装进宿主。'
        }
    ];

    // ---------------- 视图辅助 ----------------

    function card(kids) {
        return ctx.UI.Card({ cornerRadius: 12, padding: 12 }, kids);
    }

    function itemRow(it) {
        const p = assessPortability(it.desc);
        const selected = detail && detail.repo === it.repo;
        return ctx.UI.Card({
            cornerRadius: 10,
            padding: 8,
            background: selected ? 'surface' : 'transparent'
        }, [
            // 整行可点 → 打开详情
            ctx.UI.ListItem({
                title: it.name,
                trailingText: it.stars ? ('★ ' + it.stars) : '',
                onClick: function () { openDetail(it); }
            }),
            // 描述用独立 Text，完整展示不截断（ListItem 的 subtitle 只给 2 行）
            ctx.UI.Text({
                text: firstSentence(it.desc),
                fontSize: 12,
                color: 'textsecondary',
                fillMaxWidth: true
            }),
            ctx.UI.Spacer({ height: 6 }),
            // 标签行：难度 + 生态 + 许可
            ctx.UI.Row({ verticalAlignment: 'centerVertically', fillMaxWidth: true }, [
                ctx.UI.Text({
                    text: p.label,
                    fontSize: 11,
                    color: levelColor(p.level),
                    bold: true
                }),
                ctx.UI.Spacer({ width: 6 }),
                ctx.UI.Text({
                    text: (it.eco === 'dsh' ? 'DSH' : (it.eco === 'operit' ? 'Operit' : 'OpenX')) +
                        (it.license ? (' · ' + it.license) : ''),
                    fontSize: 11,
                    color: 'textsecondary'
                })
            ]),
            ctx.UI.Spacer({ height: 4 }),
            // 仓库名 + 下载按钮（单独一行，避免按钮被挤到屏幕外）
            ctx.UI.Row({ verticalAlignment: 'centerVertically', fillMaxWidth: true }, [
                ctx.UI.Text({
                    text: it.repo,
                    fontSize: 11,
                    color: 'textsecondary',
                    fillMaxWidth: true
                }),
                ctx.UI.TextButton({
                    text: '下载',
                    onClick: function () { doDownload(it); }
                })
            ])
        ]);
    }

    function detailCard(it) {
        if (!it) return null;
        const p = assessPortability(it.desc);
        // DSH 描述与安装命令可能很长，全量铺开会把下载按钮顶到屏幕外
        // （真机实测：按钮 bounds 被裁成 [2363,2400]，只露上半截）。
        // 所以：下载按钮放标题行（永远在第一屏），描述放下面，长了由用户滚。
        // 安装命令只留 60 字符速览，完整版在移植指引文件里。
        const install = it.install
            ? (it.install.length > 60 ? (it.install.slice(0, 60) + '…') : it.install)
            : '';
        return card([
            ctx.UI.Row({ verticalAlignment: 'centerVertically', fillMaxWidth: true }, [
                ctx.UI.Text({ text: it.name, fontSize: 14, bold: true, fillMaxWidth: true }),
                ctx.UI.Text({
                    text: p.label,
                    fontSize: 11,
                    color: levelColor(p.level),
                    bold: true
                }),
                ctx.UI.Spacer({ width: 6 }),
                ctx.UI.TextButton({
                    text: '关闭',
                    onClick: function () { setDetail(null); }
                })
            ]),
            ctx.UI.Spacer({ height: 8 }),
            // 下载按钮紧跟标题，永远在第一屏可见
            ctx.UI.Button({
                text: dlBusy ? '处理中…' : '下载到工作区并生成移植指引',
                disabled: dlBusy,
                onClick: function () { doDownload(it); }
            }),
            ctx.UI.Spacer({ height: 10 }),
            ctx.UI.Text({ text: it.repo, fontSize: 11, color: 'textsecondary', fillMaxWidth: true }),
            ctx.UI.Spacer({ height: 6 }),
            // 完整描述，不截断 —— 用户要知道这个插件到底干什么
            ctx.UI.Text({ text: it.desc, fontSize: 12, fillMaxWidth: true }),
            ctx.UI.Spacer({ height: 6 }),
            ctx.UI.Text({
                text: (install ? ('原生态安装：' + install + '　') : '') +
                    '许可/风险：' + (it.license || '未知'),
                fontSize: 11,
                color: 'textsecondary',
                fillMaxWidth: true
            }),
            ctx.UI.Spacer({ height: 4 }),
            ctx.UI.Text({ text: p.hint + '　建议形态：' + p.form, fontSize: 11, color: 'textsecondary', fillMaxWidth: true })
        ]);
    }

    // ---------------- 主视图 ----------------

    const header = ctx.UI.Row({ verticalAlignment: 'centerVertically', fillMaxWidth: true }, [
        ctx.UI.Text({ text: '插件发现器', fontSize: 18, bold: true, fillMaxWidth: true }),
        loading ? ctx.UI.CircularProgressIndicator({ size: 20 }) : null
    ]);

    const errCard = err
        ? card([
            ctx.UI.Text({ text: err, fontSize: 12, color: 'error', fillMaxWidth: true })
        ])
        : null;

    // --- Tab 1：发现 ---
    const discoverTab = ctx.UI.Column({ width: 'fill' }, [
        ctx.UI.Row({ fillMaxWidth: true }, [
            ctx.UI.Dropdown({
                items: ECOS,
                selectedIndex: eco,
                onSelect: function (p) { setEco(p.index || 0); }
            }),
            ctx.UI.Spacer({ width: 8 }),
            ctx.UI.TextField({
                value: keyword,
                onValueChange: setKeyword,
                placeholder: '关键词',
                fillMaxWidth: true
            })
        ]),
        ctx.UI.Spacer({ height: 8 }),
        (ECOS[eco] === 'DSH')
            ? ctx.UI.Dropdown({
                items: DSH_CATS,
                selectedIndex: category,
                onSelect: function (p) { setCategory(p.index || 0); }
            })
            : null,
        (ECOS[eco] === 'DSH') ? ctx.UI.Spacer({ height: 8 }) : null,
        ctx.UI.Button({
            text: loading ? '搜索中…' : '搜索',
            disabled: loading,
            onClick: doSearch
        }),
        ctx.UI.Spacer({ height: 10 }),
        ctx.UI.Text({
            text: (items || []).length
                ? ('共 ' + (items || []).length + ' 条 · 生态：' + (ECOS[eco] || '') + ' · 点卡片看详情')
                : '选好生态与分类后点「搜索」',
            fontSize: 12,
            color: 'textsecondary',
            fillMaxWidth: true
        }),
        ctx.UI.Spacer({ height: 8 }),
        // 详情放在列表**上方**：点完卡片立刻能看到，不用往下滚
        detailCard(detail),
        detail ? ctx.UI.Spacer({ height: 10 }) : null,
        (items || []).length
            ? ctx.UI.LazyColumn({ height: 320 }, (items || []).map(itemRow))
            : null,
        dlLog ? ctx.UI.Spacer({ height: 12 }) : null,
        dlLog ? card([
            ctx.UI.Text({ text: '执行记录', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.Spacer({ height: 6 }),
            ctx.UI.Text({ text: dlLog, fontSize: 11, fillMaxWidth: true })
        ]) : null
    ]);

    // --- Tab 2：下载记录 ---
    const downloadTab = ctx.UI.Column({ width: 'fill' }, [
        ctx.UI.Text({
            text: '下载记录：每次下载的结果会记在这里。下载物落在容器工作区，AI 的 file_manager / readFile / editFile 可直接读写。',
            fontSize: 12,
            color: 'textsecondary',
            fillMaxWidth: true
        }),
        ctx.UI.Spacer({ height: 8 }),
        ctx.UI.Text({ text: '目标目录（容器路径）', fontSize: 12, color: 'textsecondary' }),
        ctx.UI.Spacer({ height: 4 }),
        ctx.UI.TextField({
            value: targetDir,
            onValueChange: setTargetDir,
            placeholder: '/root/workspace/downloads'
        }),
        ctx.UI.Spacer({ height: 4 }),
        ctx.UI.Text({
            text: '当前生效：' + targetDir,
            fontSize: 11,
            color: 'textsecondary',
            fillMaxWidth: true
        }),
        ctx.UI.Spacer({ height: 12 }),
        dlLog
            ? card([
                ctx.UI.Text({ text: '执行记录', fontSize: 12, color: 'textsecondary' }),
                ctx.UI.Spacer({ height: 6 }),
                ctx.UI.Text({ text: dlLog, fontSize: 11, fillMaxWidth: true })
            ])
            : ctx.UI.Text({
                text: '在「发现」里点某个插件的「下载」即可，结果会出现在这里。',
                fontSize: 12,
                color: 'textsecondary',
                fillMaxWidth: true
            })
    ]);

    // --- Tab 3：设计模式 ---
    const patternTab = ctx.UI.Column({ width: 'fill' }, [
        ctx.UI.Text({
            text: '从真实头部插件里拆出来的可复用做法，每条都标了出处和 OpenX 落地方式。',
            fontSize: 12,
            color: 'textsecondary',
            fillMaxWidth: true
        }),
        ctx.UI.Spacer({ height: 10 }),
        ctx.UI.LazyColumn({ height: 460 }, PATTERNS.map(function (pt) {
            return card([
                ctx.UI.Text({ text: pt.name, fontSize: 14, bold: true, fillMaxWidth: true }),
                ctx.UI.Spacer({ height: 4 }),
                ctx.UI.Text({ text: '出处：' + pt.from, fontSize: 11, color: 'textsecondary', fillMaxWidth: true }),
                ctx.UI.Spacer({ height: 6 }),
                ctx.UI.Text({ text: pt.body, fontSize: 12, fillMaxWidth: true }),
                ctx.UI.Spacer({ height: 6 }),
                ctx.UI.Text({ text: 'OpenX 落地：' + pt.apply, fontSize: 12, color: 'success', fillMaxWidth: true })
            ]);
        }))
    ]);

    return ctx.UI.Column({
        padding: 14,
        fillMaxWidth: true,
        verticalArrangement: 'spacedBy'
    }, [
        header,
        ctx.UI.Tabs({
            tabs: ['发现', '下载', '设计模式'],
            selectedIndex: tab,
            onSelect: function (p) { setTab(p.index || 0); }
        }),
        errCard,
        tab === 0 ? discoverTab : (tab === 1 ? downloadTab : patternTab),
        ctx.UI.Spacer({ height: 20 })
    ]);
}

module.exports = Screen;
