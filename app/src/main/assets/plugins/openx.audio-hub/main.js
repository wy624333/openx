/**
 * 音频工作台 —— 工具侧。
 *
 * 移植自 DSH 音乐播放器（kendu76/dsh-music-player，MIT）。
 *
 * 工具侧 QuickJS 没有 HTTP / 文件系统能力，所以这里只放**纯逻辑**：
 * 文件名→元数据推断、m3u 清单生成、LRC 歌词解析。
 * 联网（电台目录）与落盘（写 m3u）都在 UI 面板里做。
 */
var $ptools = $ptools || [];

/** 扩展名 → 音质档位（按容器格式判断，读不到真实码率就不谎报）。 */
var LOSSLESS = ['flac', 'wav', 'aiff', 'aif', 'ape', 'alac', 'dsf', 'dff', 'tak', 'wv'];
var LOSSY_HI = ['m4a', 'aac', 'opus', 'ogg', 'oga', 'webm', 'mp4'];
var AUDIO_EXT = LOSSLESS.concat(LOSSY_HI).concat(['mp3', 'm4b', 'mka']);

function extOf(name) {
    var m = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
    return m ? m[1] : '';
}

function baseName(p) {
    var s = String(p || '');
    var i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return i >= 0 ? s.slice(i + 1) : s;
}

/** 从文件名里找码率线索，如 "320K"、"192kbps"。找不到返回 0。 */
function bitrateHint(stem) {
    var m = stem.match(/(\d{2,3})\s*k(?:bps)?/i);
    if (!m) return 0;
    var v = parseInt(m[1], 10);
    return (v >= 32 && v <= 640) ? v : 0;
}

/** 推断音质档位文案。 */
function qualityOf(ext, stem) {
    if (LOSSLESS.indexOf(ext) >= 0) return '无损';
    var br = bitrateHint(stem);
    if (ext === 'mp3') {
        if (br >= 320) return '高音质 ' + br + 'k';
        if (br >= 192) return '标准 ' + br + 'k';
        return br > 0 ? (br + 'k') : '标准';
    }
    if (LOSSY_HI.indexOf(ext) >= 0) return br > 0 ? ('有损 ' + br + 'k') : '有损';
    return '未知';
}

/**
 * 把文件名拆成 艺术家 / 标题。
 *
 * 处理常见的几种命名：
 *   01. 周杰伦 - 七里香.mp3
 *   周杰伦 - 七里香 [320K].flac
 *   Artist - Title (Live).m4a
 *   只是标题.mp3
 */
function parseName(name) {
    var stem = String(name || '').replace(/\.[a-z0-9]+$/i, '');
    // 去掉开头的曲序（01 / 01. / 01- / 01_）
    stem = stem.replace(/^\s*\d{1,3}\s*[._\-\u3001)]\s*/, '');
    // 去掉成对括号里的附加信息（【】[]()）
    stem = stem.replace(/[（(\[【][^）)\]】]*[）)\]】]/g, ' ').replace(/\s+/g, ' ').trim();

    var parts = stem.split(/\s+-\s+/);
    var artist = '';
    var title = stem;
    if (parts.length >= 2) {
        artist = parts[0].trim();
        title = parts.slice(1).join(' - ').trim();
    } else {
        // 没有分隔符时，试试中文全角破折号或下划线
        var alt = stem.split(/\s*[—–－_]\s*/);
        if (alt.length >= 2) {
            artist = alt[0].trim();
            title = alt.slice(1).join(' ').trim();
        }
    }
    return { artist: artist, title: title || stem };
}

function shellQuote(s) {
    return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'";
}

$ptools.push({
    name: 'audio_guess_meta',
    description: '把一串音频文件名（或完整路径）解析成结构化元数据：艺术家、标题、格式、音质档位，并标出是否是可识别的音频扩展名。用于扫描音乐库后整理曲目。',
    parameters: {
        type: 'object',
        properties: {
            files: {
                type: 'array',
                items: { type: 'string' },
                description: '文件名或路径数组'
            }
        },
        required: ['files']
    },
    handler: function (args) {
        var a = args || {};
        var files = Array.isArray(a.files) ? a.files : (a.files ? [a.files] : []);
        if (!files.length) return JSON.stringify({ ok: false, error: 'files 不能为空' });

        var out = [];
        for (var i = 0; i < files.length; i++) {
            var path = String(files[i] || '');
            var name = baseName(path);
            var ext = extOf(name);
            var stem = name.replace(/\.[a-z0-9]+$/i, '');
            var meta = parseName(name);
            out.push({
                path: path,
                name: name,
                artist: meta.artist,
                title: meta.title,
                format: ext.toUpperCase(),
                quality: qualityOf(ext, stem),
                audio: AUDIO_EXT.indexOf(ext) >= 0
            });
        }
        return JSON.stringify({ ok: true, count: out.length, tracks: out });
    }
});

$ptools.push({
    name: 'audio_make_m3u',
    description: '把一组音频文件路径（或电台流地址）生成 m3u 播放清单内容，并附一条可直接执行的写盘命令。OpenX 宿主不支持音频播放，播放需把清单交给手机上的播放器 App。',
    parameters: {
        type: 'object',
        properties: {
            name: { type: 'string', description: '清单名（不含扩展名），如 我的歌单' },
            tracks: {
                type: 'array',
                items: { type: 'string' },
                description: '音频文件绝对路径或 http(s) 流地址数组'
            },
            targetDir: { type: 'string', description: '输出目录，默认 /mnt/sdcard/Music' }
        },
        required: ['tracks']
    },
    handler: function (args) {
        var a = args || {};
        var tracks = Array.isArray(a.tracks) ? a.tracks : (a.tracks ? [a.tracks] : []);
        if (!tracks.length) return JSON.stringify({ ok: false, error: 'tracks 不能为空' });

        var rawName = String(a.name || 'OpenX歌单').replace(/[\\\/:*?"<>|]/g, '_').trim() || 'OpenX歌单';
        var dir = String(a.targetDir || '/mnt/sdcard/Music').replace(/\/+$/, '');
        var file = dir + '/' + rawName + '.m3u';

        var lines = ['#EXTM3U'];
        for (var i = 0; i < tracks.length; i++) {
            var t = String(tracks[i] || '').trim();
            if (!t) continue;
            lines.push(t);
        }
        var content = lines.join('\n') + '\n';

        // printf '%s\n' 逐个参数输出，天然不吃引号里的特殊字符
        var cmd = 'mkdir -p ' + shellQuote(dir) + ' && printf \'%s\\n\' ' +
            lines.map(shellQuote).join(' ') + ' > ' + shellQuote(file);

        return JSON.stringify({
            ok: true,
            file: file,
            count: lines.length - 1,
            content: content,
            command: cmd
        });
    }
});

$ptools.push({
    name: 'audio_parse_lrc',
    description: '解析 LRC 歌词文本为带时间戳的行数组（支持 [mm:ss.xx] 与一行多时间戳），并识别 [ti:][ar:][al:] 元信息。用于展示或改写歌词。',
    parameters: {
        type: 'object',
        properties: {
            text: { type: 'string', description: 'LRC 文件内容' }
        },
        required: ['text']
    },
    handler: function (args) {
        var a = args || {};
        var text = String(a.text || '');
        if (!text) return JSON.stringify({ ok: false, error: 'text 不能为空' });

        var meta = {};
        var lines = [];
        var raw = text.split(/\r?\n/);

        for (var i = 0; i < raw.length; i++) {
            var line = raw[i];
            // 元信息标签
            var mm = line.match(/^\s*\[(ti|ar|al|by|offset):([^\]]*)\]\s*$/i);
            if (mm) {
                meta[mm[1].toLowerCase()] = mm[2].trim();
                continue;
            }
            // 时间戳（可能一行多个）
            var stamps = line.match(/\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]/g);
            if (!stamps) continue;
            var body = line.replace(/\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]/g, '').trim();
            for (var j = 0; j < stamps.length; j++) {
                var s = stamps[j].slice(1, -1);
                var p = s.split(/[:.]/);
                var min = parseInt(p[0], 10);
                var sec = parseInt(p[1], 10);
                var frac = p.length > 2 ? parseInt(p[2], 10) : 0;
                var div = p.length > 2 && String(p[2]).length === 2 ? 100 : 1000;
                var ms = Math.round((min * 60 + sec) * 1000 + (frac * 1000 / div));
                lines.push({ ms: ms, time: min + ':' + (sec < 10 ? '0' : '') + sec, text: body });
            }
        }
        lines.sort(function (x, y) { return x.ms - y.ms; });

        return JSON.stringify({
            ok: true,
            meta: meta,
            count: lines.length,
            lines: lines
        });
    }
});
