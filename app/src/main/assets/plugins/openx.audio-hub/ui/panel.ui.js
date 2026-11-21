/**
 * 音频工作台 —— 界面。
 *
 * 移植自 DSH 音乐播放器（kendu76/dsh-music-player，MIT）。
 *
 * 宿主 2.7.4 起内置 ExoPlayer（ctx.audio + AudioBar 组件），所以这里可以直接播：
 * 本地文件走 file://，电台走 http(s) 直播流，播放状态由宿主统一维护，通知栏和耳机键
 * 也能控制。歌单仍可导出 m3u，方便交给外部播放器离线收听。
 *
 * 三个 Tab：
 *  · 电台：搜 radio-browser 开放目录（数万台、免登录），点一下直接听
 *  · 本地音乐：扫描手机音频目录，过滤、整单播放、随机播放
 *  · 歌单：管理曲目、整单/随机播放、导出 m3u
 *
 * 封面用「稳定色块 + 首字」代替网络台标：radio-browser 的 favicon 质量参差、加载慢，
 * 纯色块不依赖网络，列表滚动更稳，视觉也更统一。
 */
function Screen(ctx) {
    // ---------------- 状态 ----------------
    const [tab, setTab] = ctx.useState('ahTab', 0);

    // 电台
    const [radioKw, setRadioKw] = ctx.useState('ahRadioKw', '');
    const [radioCc, setRadioCc] = ctx.useState('ahRadioCc', 0);
    const [radioTag, setRadioTag] = ctx.useState('ahRadioTag', 0);
    const [stations, setStations] = ctx.useState('ahStations', []);
    const [radioBusy, setRadioBusy] = ctx.useState('ahRadioBusy', false);
    const [radioMsg, setRadioMsg] = ctx.useState('ahRadioMsg', '');

    // 本地音乐
    const [scanDir, setScanDir] = ctx.useState('ahScanDir', '/mnt/sdcard');
    const [tracks, setTracks] = ctx.useState('ahTracks', []);
    const [trackFilter, setTrackFilter] = ctx.useState('ahTrackFilter', '');
    const [scanBusy, setScanBusy] = ctx.useState('ahScanBusy', false);
    // 扫描完就把目录输入框收起来：省纵向空间，也少一个滑动时容易误触弹键盘的输入框
    const [dirOpen, setDirOpen] = ctx.useState('ahDirOpen', true);
    const [scanMsg, setScanMsg] = ctx.useState('ahScanMsg', '');

    // 歌单
    const [playlist, setPlaylist] = ctx.useState('ahPlaylist', null);
    const [listName, setListName] = ctx.useState('ahListName', 'OpenX歌单');
    const [outDir, setOutDir] = ctx.useState('ahOutDir', '/mnt/sdcard/Music');
    const [writeMsg, setWriteMsg] = ctx.useState('ahWriteMsg', '');

    // 歌单从持久化存储读一次（store 是嵌套 JSON 语义，适合放数组）
    if (playlist === null) {
        let saved = null;
        try {
            saved = ctx.store.get('playlist', null);
        } catch (e) {
            saved = null;
        }
        setPlaylist(Array.isArray(saved) ? saved : []);
    }
    const list = Array.isArray(playlist) ? playlist : [];

    const CC_NAMES = ['全部国家', '中国', '美国', '英国', '日本', '德国', '法国'];
    const CC_CODES = ['', 'CN', 'US', 'GB', 'JP', 'DE', 'FR'];
    const TAG_NAMES = ['不限类型', '新闻', '音乐', '古典', '爵士', '摇滚', '流行', '电子'];
    const TAG_VALS = ['', 'news', 'music', 'classical', 'jazz', 'rock', 'pop', 'electronic'];

    /** 曲库可能上千首，一次性全塞进 LazyColumn 会卡，列表只渲染前这么多行。 */
    const RENDER_LIMIT = 300;

    // radio-browser 的镜像（社区维护，首个不可用就换下一个）
    const RB_MIRRORS = [
        'https://de1.api.radio-browser.info',
        'https://si1.api.radio-browser.info',
        'https://fr1.api.radio-browser.info',
        'https://fi1.api.radio-browser.info'
    ];

    // ---------------- 小工具 ----------------

    /** 单引号包裹，供 shell 安全传参（内嵌单引号用 '\'' 转义）。 */
    function shq(s) {
        return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'";
    }

    function baseName(p) {
        const s = String(p || '');
        const i = s.lastIndexOf('/');
        return i >= 0 ? s.slice(i + 1) : s;
    }

    function fmtSize(bytes) {
        const n = Number(bytes) || 0;
        if (n >= 1073741824) return (n / 1073741824).toFixed(2) + ' GB';
        if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
        if (n >= 1024) return (n / 1024).toFixed(0) + ' KB';
        return n + ' B';
    }

    function extOf(name) {
        const m = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
        return m ? m[1].toUpperCase() : '';
    }

    function qualityOf(name) {
        const ext = extOf(name).toLowerCase();
        if (['flac', 'wav', 'aiff', 'aif', 'ape', 'alac'].indexOf(ext) >= 0) return '无损';
        if (['m4a', 'aac', 'opus', 'ogg', 'webm'].indexOf(ext) >= 0) return '有损';
        const m = String(name).match(/(\d{2,3})\s*k(?:bps)?/i);
        if (m) return m[1] + 'k';
        return '标准';
    }

    /**
     * 执行 shell。
     * 宿主 ctx.http / callTool 都是同步阻塞实现，而 action 派发期间宿主持有 session 锁，
     * 在 onClick 里直接 await 会把锁占满整个耗时（表现为面板僵死），所以调用方一律
     * 先 setXxx 再 setTimeout 把真正的工作挪到锁外。
     */
    async function runShell(cmd, timeoutSec, maxOutput) {
        const args = { action: 'execute', command: cmd, timeout: timeoutSec || 180 };
        // terminal 默认只回传尾部 10000 字符，扫库结果动辄上万行，必须显式抬高上限
        if (maxOutput) args.maxOutput = maxOutput;
        const res = await ctx.callTool('terminal', args);
        return String((res && res.result) ? res.result : (res || ''));
    }

    /** 从 terminal 的包装输出里挑出最后一行纯数字（`wc -l` 的结果）。 */
    function pickNumber(text) {
        const lines = String(text || '').split('\n');
        for (let i = lines.length - 1; i >= 0; i--) {
            const t = lines[i].trim();
            if (t && /^[0-9]+$/.test(t)) return parseInt(t, 10);
        }
        return 0;
    }

    /** 解析 `stat -c '%s|%n'` 的行，忽略命令回显、代码块标记等非数据行。 */
    function parseStatLines(text) {
        const out = [];
        const lines = String(text || '').split('\n');
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            const sep = line.indexOf('|');
            if (sep <= 0) continue;
            const fp = line.slice(sep + 1);
            if (!fp || fp.charAt(0) !== '/') continue;
            const size = parseInt(line.slice(0, sep), 10);
            if (isNaN(size)) continue;
            out.push({
                path: fp,
                name: baseName(fp),
                size: size,
                fmt: extOf(fp),
                quality: qualityOf(fp)
            });
        }
        return out;
    }

    /** radio-browser 请求，带镜像故障转移。 */
    async function rbGet(path) {
        let lastErr = '';
        for (let i = 0; i < RB_MIRRORS.length; i++) {
            try {
                const res = await ctx.http({ url: RB_MIRRORS[i] + path, method: 'GET' });
                if (res && res.ok === true && res.body) {
                    if (res.truncated) throw new Error('响应被宿主 2MB 上限截断');
                    return String(res.body);
                }
                lastErr = (res && res.error) || ('HTTP ' + (res && res.status));
            } catch (e) {
                lastErr = (e && e.message) ? e.message : String(e);
            }
        }
        throw new Error(lastErr || '所有镜像都不可用');
    }

    function savePlaylist(next) {
        setPlaylist(next);
        try {
            ctx.store.set('playlist', next);
        } catch (e) {
            ctx.showToast('歌单保存失败：' + ((e && e.message) ? e.message : String(e)));
        }
    }

    function inList(path) {
        for (let i = 0; i < list.length; i++) {
            if (list[i].path === path) return true;
        }
        return false;
    }

    // ---------------- 电台 ----------------

    function doRadioSearch() {
        if (radioBusy) return;
        setRadioBusy(true);
        setRadioMsg('搜索中…');
        setStations([]);
        setTimeout(function () { runRadioSearch(); }, 0);
    }

    async function runRadioSearch() {
        try {
            const params = ['limit=30', 'hidebroken=true', 'order=clickcount', 'reverse=true'];
            const kw = String(radioKw || '').trim();
            if (kw) params.push('name=' + encodeURIComponent(kw));
            const cc = CC_CODES[radioCc] || '';
            if (cc) params.push('countrycode=' + cc);
            const tag = TAG_VALS[radioTag] || '';
            if (tag) params.push('tag=' + tag);

            const body = await rbGet('/json/stations/search?' + params.join('&'));
            let arr;
            try {
                arr = JSON.parse(body);
            } catch (e) {
                throw new Error('返回不是合法 JSON');
            }
            if (!Array.isArray(arr)) throw new Error('返回结构异常');

            const out = [];
            for (let i = 0; i < arr.length; i++) {
                const s = arr[i] || {};
                const url = s.url_resolved || s.url || '';
                if (!url) continue;
                out.push({
                    name: s.name || '(未命名)',
                    url: url,
                    country: s.country || '',
                    cc: s.countrycode || '',
                    codec: s.codec || '',
                    bitrate: s.bitrate || 0,
                    tags: s.tags || ''
                });
            }
            setStations(out);
            setRadioMsg(out.length ? ('找到 ' + out.length + ' 个电台') : '没有匹配的电台，换个关键词或国家试试');
        } catch (e) {
            setRadioMsg('搜索失败：' + ((e && e.message) ? e.message : String(e)));
        } finally {
            setRadioBusy(false);
        }
    }

    function stationLine(s) {
        const bits = [];
        if (s.country) bits.push(s.country);
        if (s.codec && s.codec !== 'UNKNOWN') bits.push(s.codec);
        if (s.bitrate) bits.push(s.bitrate + 'k');
        const tg = String(s.tags || '').split(',').filter(function (x) { return x; }).slice(0, 3).join('/');
        if (tg) bits.push(tg);
        return bits.join(' · ');
    }

    // ---------------- 本地音乐 ----------------

    function doScan() {
        if (scanBusy) return;
        setScanBusy(true);
        setScanMsg('扫描中…全盘约 10 秒');
        setTracks([]);
        setTimeout(function () { runScan(); }, 0);
    }

    async function runScan() {
        const tmp = '/tmp/.openx_ah_scan.txt';
        try {
            const dir = String(scanDir || '').trim() || '/mnt/sdcard';
            const exts = ['mp3', 'flac', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'm4b', 'aiff'];
            const inames = exts.map(function (e) { return "-iname '*." + e + "'"; }).join(' -o ');

            // 结果先落盘再分页取：曲库常有几千首，一次回传会被 terminal 的 maxOutput 截成只剩尾部
            const scanCmd = 'find ' + shq(dir) + ' -type f \\( ' + inames + ' \\) ' +
                '-not -path "*/Android/*" -not -path "*/.thumbnails/*" -not -path "*/.*" ' +
                '-exec stat -c "%s|%n" {} + 2>/dev/null | head -4000 > ' + tmp +
                '; wc -l < ' + tmp;
            const total = pickNumber(await runShell(scanCmd, 180, 2000));

            if (!total) {
                setTracks([]);
                setScanMsg('这个目录下没找到音频文件' + (dir === '/mnt/sdcard'
                    ? '，换个目录试试'
                    : '，把目录改成 /mnt/sdcard 可以扫全盘'));
                return;
            }

            const PAGE = 500;
            const pages = Math.min(Math.ceil(total / PAGE), 8);
            const arr = [];
            for (let i = 0; i < pages; i++) {
                const from = i * PAGE + 1;
                const to = Math.min((i + 1) * PAGE, total);
                const chunk = await runShell('sed -n "' + from + ',' + to + 'p" ' + tmp, 120, 50000);
                const got = parseStatLines(chunk);
                for (let k = 0; k < got.length; k++) arr.push(got[k]);
                if (pages > 1) setScanMsg('读取中… ' + arr.length + ' / ' + total);
            }

            arr.sort(function (a, b) { return a.name < b.name ? -1 : (a.name > b.name ? 1 : 0); });
            setTracks(arr);
            setScanMsg('扫到 ' + arr.length + ' 个音频文件'
                + (total > arr.length ? ('（共 ' + total + '，列表只取前 ' + arr.length + '）') : ''));
            setDirOpen(false);
        } catch (e) {
            setScanMsg('扫描失败：' + ((e && e.message) ? e.message : String(e)));
        } finally {
            try { await runShell('rm -f ' + tmp, 30, 500); } catch (e2) { /* 清理失败不影响结果 */ }
            setScanBusy(false);
        }
    }

    function filteredTracks() {
        const kw = String(trackFilter || '').trim().toLowerCase();
        if (!kw) return tracks || [];
        return (tracks || []).filter(function (t) {
            return (t.name + ' ' + t.path).toLowerCase().indexOf(kw) >= 0;
        });
    }

    // ---------------- 歌单 ----------------

    function addTrack(t) {
        if (inList(t.path)) {
            ctx.showToast('已在歌单里');
            return;
        }
        savePlaylist(list.concat([{ path: t.path, name: t.name, kind: 'local' }]));
        ctx.showToast('已加入歌单');
    }

    function addStation(s) {
        if (inList(s.url)) {
            ctx.showToast('已在歌单里');
            return;
        }
        savePlaylist(list.concat([{ path: s.url, name: s.name, kind: 'radio' }]));
        ctx.showToast('电台已加入歌单');
    }

    function removeAt(i) {
        const next = list.slice();
        next.splice(i, 1);
        savePlaylist(next);
    }

    function clearList() {
        savePlaylist([]);
        setWriteMsg('');
    }

    function copyText(s) {
        ctx.copy(String(s || ''));
        ctx.showToast('已复制');
    }

    /**
     * 播放一个地址（本地路径或流地址）。
     * 缺 audio 权限时 requirePermission 会抛错，这里统一兜住并告知用户，
     * 而不是静默失败（"点了没反应"最难排查）。
     */
    function playUri(uri, title, artist) {
        try {
            ctx.audio.play(String(uri || ''), { title: String(title || ''), artist: String(artist || '') });
            ctx.showToast('正在播放：' + (title || uri));
        } catch (e) {
            ctx.showToast('播放失败：' + ((e && e.message) ? e.message : String(e)));
        }
    }

    /**
     * 整单播放。交给宿主一个队列后，通知栏/系统媒体卡片的「上一曲 / 下一曲」
     * 才能真正切歌（单曲播放时那两个按钮只会重播当前曲）。
     */
    function playTracks(tracks, startIndex, label) {
        if (!tracks || !tracks.length) {
            ctx.showToast('没有可播放的曲目');
            return;
        }
        try {
            ctx.audio.playQueue(tracks, startIndex || 0);
            ctx.showToast('开始播放：' + (label || (tracks.length + ' 首')));
        } catch (e) {
            ctx.showToast('播放失败：' + ((e && e.message) ? e.message : String(e)));
        }
    }

    /** 把歌单转成宿主队列格式。 */
    function listAsTracks() {
        return (list || []).map(function (it) {
            return {
                uri: String(it.path || ''),
                title: String(it.name || baseName(it.path || '')),
                artist: it.kind === 'radio' ? '网络电台' : ''
            };
        });
    }

    function doWriteM3u() {
        if (!list.length) {
            ctx.showToast('歌单是空的');
            return;
        }
        setWriteMsg('写入中…');
        setTimeout(function () { runWriteM3u(); }, 0);
    }

    async function runWriteM3u() {
        try {
            const name = String(listName || '').trim().replace(/[\/\\:*?"<>|]/g, '_') || 'OpenX歌单';
            const dir = String(outDir || '').trim().replace(/\/+$/, '') || '/mnt/sdcard/Music';
            const file = dir + '/' + name + '.m3u';

            const lines = ['#EXTM3U'];
            for (let i = 0; i < list.length; i++) lines.push(String(list[i].path || ''));

            await runShell('mkdir -p ' + shq(dir));

            // 分块写：单条命令太长会被终端工具截断，也难排查
            const CHUNK = 40;
            for (let off = 0; off < lines.length; off += CHUNK) {
                const chunk = lines.slice(off, off + CHUNK);
                const redir = (off === 0) ? '>' : '>>';
                const cmd = "printf '%s\\n' " + chunk.map(shq).join(' ') + ' ' + redir + ' ' + shq(file);
                const r = await runShell(cmd);
                if (r && /not found|Permission denied|No such/i.test(r)) {
                    throw new Error(r.split('\n')[0]);
                }
            }

            setWriteMsg('已写入 ' + file + '（' + list.length + ' 条）\n\n' +
                '用手机的文件管理器进入该目录，点这个 .m3u 文件，选择任意播放器即可播放。');
            ctx.showToast('已生成 ' + name + '.m3u');
        } catch (e) {
            setWriteMsg('写入失败：' + ((e && e.message) ? e.message : String(e)));
        }
    }


    // ---------------- 视图 ----------------

    /** 播放类操作用青绿，次强调用紫。 */
    const ACCENT_PLAY = '#00D9A3';
    const ACCENT_ALT = '#6C5CE7';

    /** 用名字生成稳定的封面底色，避免依赖网络台标。 */
    function accentOf(name) {
        const P = ['#6C5CE7', '#00B894', '#E17055', '#0984E3', '#D63031',
                   '#00CEC9', '#A29BFE', '#E84393', '#FDCB6E', '#55EFC4'];
        let h = 0;
        const s = String(name || '');
        for (let i = 0; i < s.length; i++) h = (h * 131 + s.charCodeAt(i)) & 0x7fffffff;
        return P[h % P.length];
    }

    function initialOf(name) {
        const s = String(name || '').trim();
        return s ? s.charAt(0).toUpperCase() : '♪';
    }

    /** 方形封面：纯色圆角块 + 首字。 */
    function cover(name, size, color) {
        return ctx.UI.Box({
            width: size,
            height: size,
            background: color || accentOf(name),
            cornerRadius: Math.round(size / 4),
            contentAlignment: 'center',
            clip: true
        }, [
            ctx.UI.Text({
                text: initialOf(name),
                fontSize: Math.round(size * 0.42),
                bold: true,
                color: '#FFFFFF'
            })
        ]);
    }

    /** 洗牌后从第一首开始播。 */
    function shufflePlay(ts, label) {
        if (!ts || !ts.length) {
            ctx.showToast('没有可播放的曲目');
            return;
        }
        const a = ts.slice();
        for (let i = a.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            const t = a[i]; a[i] = a[j]; a[j] = t;
        }
        playTracks(a, 0, '随机播放 · ' + (label || (a.length + ' 首')));
    }

    /** 列表条目卡：封面 + 标题 + 副标题 + 播放键 + 可选操作行。 */
    function trackCard(opts) {
        // 播放键单独占一行。插件 UI 没有 weight/grow，把 IconButton 和
        // fillMaxWidth 的文本列塞进同一个 Row 时，文本列会吃满宽度、把按钮直接挤出屏幕
        // （表现为只剩「加入歌单 / 复制」可见）。
        const actions = [
            ctx.UI.IconButton({
                icon: 'play',
                contentDescription: '播放',
                color: opts.color || ACCENT_PLAY,
                onClick: opts.onPlay
            })
        ];
        // 调用方传进来的是包好的 Row，这里把它子节点接到播放键后面，合成同一行
        const extra = (opts.actions && opts.actions.children) ? opts.actions.children : [];
        for (let i = 0; i < extra.length; i++) actions.push(extra[i]);

        return ctx.UI.Card({ cornerRadius: 16, padding: 12 }, [
            ctx.UI.Row({
                horizontalArrangement: 'spacedBy',
                verticalAlignment: 'centerVertically',
                fillMaxWidth: true
            }, [
                cover(opts.title, 46, opts.color),
                ctx.UI.Column({ verticalArrangement: 'spacedBy' }, [
                    ctx.UI.Text({ text: opts.title, fontSize: 14, bold: true, maxLines: 1 }),
                    ctx.UI.Text({
                        text: opts.subtitle || '',
                        fontSize: 11,
                        color: 'textsecondary',
                        maxLines: 1
                    })
                ])
            ]),
            ctx.UI.Row({
                horizontalArrangement: 'spacedBy',
                verticalAlignment: 'centerVertically',
                wrap: true
            }, actions)
        ]);
    }

    // --- Tab 0：电台 ---
    const radioTab = ctx.UI.Column({ width: 'fill', verticalArrangement: 'spacedBy' }, [
        ctx.UI.TextField({
            key: 'radioKw',
            value: radioKw,
            onValueChange: setRadioKw,
            placeholder: '搜台名（可空，如 经济之声）'
        }),
        ctx.UI.Row({ horizontalArrangement: 'spacedBy' }, [
            ctx.UI.Dropdown({
                items: CC_NAMES,
                selectedIndex: radioCc,
                onSelect: function (p) { setRadioCc(p.index || 0); }
            }),
            ctx.UI.Dropdown({
                items: TAG_NAMES,
                selectedIndex: radioTag,
                onSelect: function (p) { setRadioTag(p.index || 0); }
            })
        ]),
        ctx.UI.Row({ horizontalArrangement: 'spacedBy', verticalAlignment: 'centerVertically' }, [
            ctx.UI.Button({
                text: radioBusy ? '搜索中…' : '搜索电台',
                disabled: radioBusy,
                onClick: doRadioSearch
            }),
            ctx.UI.TextButton({
                text: '试播一个',
                onClick: function () {
                    playUri('https://ice1.somafm.com/groovesalad-128-mp3', 'Groove Salad', 'SomaFM');
                }
            })
        ]),
        radioMsg
            ? ctx.UI.Text({ text: radioMsg, fontSize: 11, color: 'textsecondary', fillMaxWidth: true })
            : null,
        (stations || []).length
            ? ctx.UI.LazyColumn({ height: 290, spacing: 10 }, (stations || []).map(function (s) {
                return trackCard({
                    title: s.name,
                    subtitle: stationLine(s) || '网络电台',
                    color: ACCENT_ALT,
                    onPlay: function () { playUri(s.url, s.name, s.country); },
                    actions: ctx.UI.Row({ horizontalArrangement: 'spacedBy' }, [
                        ctx.UI.AssistChip({
                            text: inList(s.url) ? '已在歌单' : '加入歌单',
                            icon: inList(s.url) ? 'check' : 'add',
                            onClick: function () { addStation(s); }
                        }),
                        ctx.UI.AssistChip({
                            text: '复制地址',
                            icon: 'copy',
                            onClick: function () { copyText(s.url); }
                        })
                    ])
                });
            }))
            : null
    ]);

    // --- Tab 1：本地音乐 ---
    const shownTracks = filteredTracks();
    const renderTracks = shownTracks.slice(0, RENDER_LIMIT);

    function localAsTracks(src) {
        return src.map(function (x) { return { uri: x.path, title: x.name, artist: '' }; });
    }

    const localTab = ctx.UI.Column({ width: 'fill', verticalArrangement: 'spacedBy' }, [
        (dirOpen || !(tracks || []).length)
            ? ctx.UI.TextField({
                key: 'scanDir',
                value: scanDir,
                onValueChange: setScanDir,
                placeholder: '扫描目录（默认扫整个存储）'
            })
            : null,
        ctx.UI.Row({ horizontalArrangement: 'spacedBy', verticalAlignment: 'centerVertically', wrap: true }, [
            ctx.UI.Button({
                text: scanBusy ? '扫描中…' : '扫描',
                disabled: scanBusy,
                onClick: doScan
            }),
            (dirOpen || !(tracks || []).length)
                ? null
                : ctx.UI.TextButton({
                    text: '改目录',
                    onClick: function () { setDirOpen(true); }
                }),
            ctx.UI.TextButton({
                text: '播放全部',
                onClick: function () {
                    var ts = localAsTracks(shownTracks);
                    playTracks(ts, 0, '本地音乐 ' + ts.length + ' 首');
                }
            }),
            ctx.UI.TextButton({
                text: '随机播放',
                onClick: function () {
                    shufflePlay(localAsTracks(renderTracks), '本地 ' + renderTracks.length + ' 首');
                }
            })
        ]),
        scanMsg
            ? ctx.UI.Text({ text: scanMsg, fontSize: 11, color: 'textsecondary', fillMaxWidth: true })
            : null,
        (tracks || []).length
            ? ctx.UI.TextField({
                key: 'trackFilter',
                value: trackFilter,
                onValueChange: setTrackFilter,
                placeholder: '按文件名过滤'
            })
            : null,
        (tracks || []).length
            ? ctx.UI.Text({
                text: '显示 ' + renderTracks.length + ' / ' + shownTracks.length + ' 首'
                    + (shownTracks.length > RENDER_LIMIT
                        ? ('（曲库共 ' + tracks.length + ' 首，列表上限 ' + RENDER_LIMIT + '，用过滤框缩小范围）')
                        : ''),
                fontSize: 11,
                color: 'textsecondary',
                fillMaxWidth: true
            })
            : null,
        (tracks || []).length
            ? ctx.UI.LazyColumn({ height: 320, spacing: 10 }, renderTracks.map(function (t, idx) {
                return trackCard({
                    title: t.name,
                    subtitle: t.fmt + ' · ' + t.quality + ' · ' + fmtSize(t.size),
                    // 从这一首起整单播，上下曲才能接着切
                    onPlay: function () { playTracks(localAsTracks(shownTracks), idx, t.name); },
                    actions: ctx.UI.Row({ horizontalArrangement: 'spacedBy' }, [
                        ctx.UI.AssistChip({
                            text: inList(t.path) ? '已在歌单' : '加入歌单',
                            icon: inList(t.path) ? 'check' : 'add',
                            onClick: function () { addTrack(t); }
                        }),
                        ctx.UI.AssistChip({
                            text: '复制路径',
                            icon: 'copy',
                            onClick: function () { copyText(t.path); }
                        })
                    ])
                });
            }))
            : null
    ]);

    // --- Tab 2：歌单 ---
    const listTab = ctx.UI.Column({ width: 'fill', verticalArrangement: 'spacedBy' }, [
        ctx.UI.TextField({
            key: 'listName',
            value: listName,
            onValueChange: setListName,
            placeholder: '歌单名'
        }),
        ctx.UI.TextField({
            key: 'outDir',
            value: outDir,
            onValueChange: setOutDir,
            placeholder: 'm3u 输出目录（容器路径，如 /mnt/sdcard/Music）'
        }),
        ctx.UI.Row({ horizontalArrangement: 'spacedBy', verticalAlignment: 'centerVertically', wrap: true }, [
            ctx.UI.Button({
                text: '整单播放',
                disabled: !list.length,
                onClick: function () { playTracks(listAsTracks(), 0, '歌单 ' + list.length + ' 首'); }
            }),
            ctx.UI.FilledTonalButton({
                text: '随机播放',
                enabled: list.length > 0,
                onClick: function () { shufflePlay(listAsTracks(), '歌单 ' + list.length + ' 首'); }
            }),
            ctx.UI.TextButton({
                text: '导出 m3u',
                onClick: doWriteM3u
            }),
            ctx.UI.TextButton({
                text: '清空',
                onClick: clearList
            })
        ]),
        ctx.UI.Text({
            text: '当前 ' + list.length + ' 条',
            fontSize: 11,
            color: 'textsecondary',
            fillMaxWidth: true
        }),
        writeMsg
            ? ctx.UI.Card({ cornerRadius: 12, padding: 12 }, [
                ctx.UI.Text({ text: writeMsg, fontSize: 11, fillMaxWidth: true })
            ])
            : null,
        list.length
            ? ctx.UI.LazyColumn({ height: 300, spacing: 10 }, list.map(function (it, idx) {
                const isRadio = it.kind === 'radio';
                const title = it.name || baseName(it.path);
                return trackCard({
                    title: title,
                    subtitle: (isRadio ? '电台 · ' : '本地 · ') + baseName(it.path),
                    color: isRadio ? '#E17055' : accentOf(title),
                    // 从第 idx 首起连播，后面按歌单顺序继续
                    onPlay: function () { playTracks(listAsTracks(), idx, title); },
                    actions: ctx.UI.Row({ horizontalArrangement: 'spacedBy' }, [
                        ctx.UI.AssistChip({
                            text: '复制',
                            icon: 'copy',
                            onClick: function () { copyText(it.path); }
                        }),
                        ctx.UI.AssistChip({
                            text: '移除',
                            icon: 'delete',
                            onClick: function () { removeAt(idx); }
                        })
                    ])
                });
            }))
            : ctx.UI.Text({
                text: '歌单还是空的。到「电台」或「本地音乐」里挑几条加进来。',
                fontSize: 12,
                color: 'textsecondary',
                fillMaxWidth: true
            })
    ]);

    // ---------------- 主布局 ----------------

    // 顶部只留播放条和页签，其余纵向空间全部给列表。
    //
    // 宿主机的外层本身是 verticalScroll + imeNestedScroll 的滚动容器，插件内容一旦
    // 超出屏幕高度就会变成「内层列表滚完、手势冒泡到外层」的双层滚动，表现是滑到底部
    // 继续上滑会触发输入法。所以这里刻意压到不超一屏：播放条用 compact，进度与音量
    // 交给通知栏和系统音量键。
    return ctx.UI.Column({
        padding: 14,
        fillMaxWidth: true,
        verticalArrangement: 'spacedBy'
    }, [
        ctx.UI.AudioBar({ showVolume: true, compact: false }),
        ctx.UI.Tabs({
            tabs: ['电台', '本地音乐', '歌单'],
            selectedIndex: tab,
            onSelect: function (p) { setTab(p.index || 0); }
        }),
        tab === 0 ? radioTab : (tab === 1 ? localTab : listTab)
    ]);
}

module.exports = Screen;
