/**
 * 设备监控面板。
 *
 * 数据来自 ctx.device.stats()——它是宿主侧一次采样算出来的真实值：
 *   · CPU 使用率：读 /proc/stat 两次求差（不是进程 CPU，是全系统）
 *   · 内存：ActivityManager.MemoryInfo（整机可用/总量），不是 JVM 堆
 *   · 应用自身：PSS
 *   · 存储：StatFs
 *   · 电池/温度：电量广播
 *
 * 2 秒自动刷新靠 setInterval。宿主的定时器是包装过的：回调跑完会自动请求重渲染，
 * 所以这里只管 setState，不用手动刷新界面。
 *
 * 两个必须注意的点：
 *   1. 定时器只能建一次。渲染函数每帧都会跑，直接把 setInterval 写在函数体里会
 *      每帧新建一个，几秒后就有几十个定时器在抢着采样。所以用 useRef 兜住。
 *   2. 「暂停」状态要用 ref 同步一份。定时器闭包捕获的是首次渲染时的变量，
 *      只看 state 的话按了暂停也停不下来。
 */

function fmtBytes(b) {
    if (!b || b <= 0) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var v = b, i = 0;
    while (v >= 1024 && i < units.length - 1) { v = v / 1024; i++; }
    return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[i];
}

/** kHz → 可读频率：≥1000 MHz 用 GHz。 */
function fmtFreq(khz) {
    if (!khz || khz <= 0) return '—';
    var mhz = khz / 1000;
    if (mhz >= 1000) return (mhz / 1000).toFixed(2) + ' GHz';
    return Math.round(mhz) + ' MHz';
}

function fmtUptime(ms) {
    if (!ms || ms <= 0) return '—';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400);
    var h = Math.floor((s % 86400) / 3600);
    var m = Math.floor((s % 3600) / 60);
    if (d > 0) return d + ' 天 ' + h + ' 小时';
    if (h > 0) return h + ' 小时 ' + m + ' 分';
    return m + ' 分';
}

/** 按占用率给个颜色：绿 → 黄 → 红。 */
function levelColor(pct) {
    if (pct >= 85) return 'error';
    if (pct >= 65) return 'warning';
    return 'success';
}

function Screen(ctx) {
    const [stats, setStats] = ctx.useState('dmStats', null);
    const [running, setRunning] = ctx.useState('dmRunning', true);
    const [lastAt, setLastAt] = ctx.useState('dmLastAt', '—');
    const [lastErr, setLastErr] = ctx.useState('dmErr', '');

    const timerRef = ctx.useRef('dmTimer', null);
    const runningRef = ctx.useRef('dmRunningRef', true);

    // 设备静态信息只取一次
    const info = ctx.useMemo('dmInfo', function () {
        try { return ctx.device.info() || null; } catch (e) { return null; }
    }, []);

    function sample() {
        try {
            const s = ctx.device.stats();
            if (s && s.ok) {
                setStats(s);
                setLastErr('');
                setLastAt(new Date().toTimeString().slice(0, 8));
            } else {
                setLastErr((s && s.error) || '读取失败');
            }
        } catch (e) {
            setLastErr('采样异常：' + (e && e.message ? e.message : e));
        }
    }

    // 只建一次定时器
    if (timerRef.current == null) {
        sample();
        timerRef.current = setInterval(function () {
            if (runningRef.current) sample();
        }, 2000);
    }

    function toggleRunning() {
        const next = !runningRef.current;
        runningRef.current = next;
        setRunning(next);
        if (next) sample();
    }

    const s = stats;
    const cpu = s && s.cpu ? s.cpu : null;
    let cpuAvgKhz = 0;
    if (cpu && cpu.freqs && cpu.freqs.length) {
        let sum = 0;
        for (let i = 0; i < cpu.freqs.length; i++) sum += cpu.freqs[i];
        cpuAvgKhz = sum / cpu.freqs.length;
    }
    const cpuMaxKhz = cpu && cpu.freqMax > 0 ? cpu.freqMax : 0;
    const mem = s && s.memory ? s.memory : null;
    const sto = s && s.storage ? s.storage : null;
    const bat = s && s.battery ? s.battery : null;

    const freqPct = cpuAvgKhz > 0 && cpuMaxKhz > 0
        ? Math.min(100, cpuAvgKhz * 100 / cpuMaxKhz) : null;
    const memPct = mem ? mem.usedPercent : 0;
    const stoPct = sto ? sto.usedPercent : 0;

    const rows = [];

    // ---- 顶栏
    rows.push(ctx.UI.Row({
        fillMaxWidth: true,
        verticalAlignment: 'centerVertically'
    }, [
        ctx.UI.Text({
            text: running ? '● 实时监控中' : '❚❚ 已暂停',
            fontSize: 12,
            color: running ? 'success' : 'textsecondary',
            weight: 1
        }),
        ctx.UI.TextButton({
            text: running ? '暂停' : '继续',
            onClick: toggleRunning
        })
    ]));

    // ---- CPU（/proc/stat 被 SELinux 限制，改看平均频率占最高频率的比例）
    rows.push(ctx.UI.Text({ text: 'CPU', fontSize: 12, color: 'textsecondary', fillMaxWidth: true }));
    rows.push(ctx.UI.Row({ fillMaxWidth: true, verticalAlignment: 'centerVertically' }, [
        ctx.UI.Text({
            text: cpuAvgKhz > 0 ? fmtFreq(cpuAvgKhz) : '—',
            fontSize: 14,
            color: 'text',
            weight: 1
        }),
        ctx.UI.Text({
            text: freqPct === null ? '' : (freqPct.toFixed(0) + '%'),
            fontSize: 14,
            color: freqPct === null ? 'textsecondary' : levelColor(freqPct)
        })
    ]));
    rows.push(ctx.UI.LinearProgressIndicator({
        progress: freqPct === null ? 0 : freqPct / 100,
        color: freqPct === null ? 'textsecondary' : levelColor(freqPct),
        trackColor: 'border',
        fillMaxWidth: true
    }));
    if (cpu) {
        const parts = [];
        if (cpuMaxKhz > 0) parts.push('最高 ' + fmtFreq(cpuMaxKhz));
        parts.push(cpu.cores + ' 核');
        rows.push(ctx.UI.Text({
            text: parts.join(' · '),
            fontSize: 10,
            color: 'textsecondary',
            fillMaxWidth: true
        }));
    }

    // ---- 内存
    rows.push(ctx.UI.Text({ text: '内存', fontSize: 12, color: 'textsecondary', fillMaxWidth: true }));
    rows.push(ctx.UI.Row({ fillMaxWidth: true, verticalAlignment: 'centerVertically' }, [
        ctx.UI.Text({
            text: mem ? (fmtBytes(mem.usedBytes) + ' / ' + fmtBytes(mem.totalBytes)) : '—',
            fontSize: 14,
            color: 'text',
            weight: 1
        }),
        ctx.UI.Text({
            text: mem ? (memPct.toFixed(0) + '%') : '',
            fontSize: 14,
            color: levelColor(memPct)
        })
    ]));
    rows.push(ctx.UI.LinearProgressIndicator({
        progress: memPct / 100,
        color: levelColor(memPct),
        trackColor: 'border',
        fillMaxWidth: true
    }));
    if (mem) {
        rows.push(ctx.UI.Text({
            text: '可用 ' + fmtBytes(mem.availBytes)
                + '　本插件占用 ' + fmtBytes(mem.appPssBytes),
            fontSize: 10,
            color: 'textsecondary',
            fillMaxWidth: true
        }));
    }

    // ---- 存储
    rows.push(ctx.UI.Text({ text: '存储', fontSize: 12, color: 'textsecondary', fillMaxWidth: true }));
    rows.push(ctx.UI.Row({ fillMaxWidth: true, verticalAlignment: 'centerVertically' }, [
        ctx.UI.Text({
            text: sto ? (fmtBytes(sto.usedBytes) + ' / ' + fmtBytes(sto.totalBytes)) : '—',
            fontSize: 14,
            color: 'text',
            weight: 1
        }),
        ctx.UI.Text({
            text: sto ? (stoPct.toFixed(0) + '%') : '',
            fontSize: 14,
            color: levelColor(stoPct)
        })
    ]));
    rows.push(ctx.UI.LinearProgressIndicator({
        progress: stoPct / 100,
        color: levelColor(stoPct),
        trackColor: 'border',
        fillMaxWidth: true
    }));

    // ---- 电池 + 运行时长
    const batLine = bat
        ? ('电量 ' + bat.percent + '%　' + (bat.charging ? '充电中' : '放电中')
            + '　' + bat.temperature.toFixed(1) + '°C')
        : '电池信息不可用';
    rows.push(ctx.UI.Text({ text: '电池', fontSize: 12, color: 'textsecondary', fillMaxWidth: true }));
    rows.push(ctx.UI.Text({ text: batLine, fontSize: 13, color: 'text', fillMaxWidth: true }));
    rows.push(ctx.UI.Text({
        text: '已运行 ' + fmtUptime(s ? s.uptimeMs : 0)
            + '　更新于 ' + lastAt + '（每 2 秒）',
        fontSize: 10,
        color: 'textsecondary',
        fillMaxWidth: true
    }));

    if (lastErr) {
        rows.push(ctx.UI.Text({
            text: lastErr,
            fontSize: 11,
            color: 'error',
            fillMaxWidth: true
        }));
    }

    // ---- 底部操作
    rows.push(ctx.UI.Row({ horizontalArrangement: 'spacedBy', verticalAlignment: 'centerVertically' }, [
        ctx.UI.Button({
            text: '立即刷新',
            onClick: sample
        }),
        ctx.UI.AssistChip({
            text: '浮窗常驻',
            icon: 'smartphone',
            onClick: function () {
                // 监控窗用紧凑尺寸，避免挡住屏幕；显示后可拖右下角自由缩放
                ctx.overlay.show({ width: 0.62, maxHeight: 0.42 }).then(function (r) {
                    if (r && r.ok) {
                        ctx.showToast('已显示：拖右下角可调大小');
                    } else {
                        ctx.showToast('无法悬浮：' + ((r && r.error) || '未知原因'));
                    }
                }).catch(function (e) {
                    ctx.showToast('悬浮失败：' + (e && e.message ? e.message : e));
                });
            }
        }),
        ctx.UI.TextButton({
            text: '收起浮窗',
            onClick: function () { ctx.overlay.hide(); }
        })
    ]));

    // ---- 设备信息（页脚）
    if (info && info.ok) {
        rows.push(ctx.UI.Text({
            text: info.brand + ' ' + info.model + ' · Android ' + info.android
                + ' · ' + info.screen.width + '×' + info.screen.height,
            fontSize: 10,
            color: 'textsecondary',
            fillMaxWidth: true
        }));
    }

    return ctx.UI.Column({
        padding: 14,
        fillMaxWidth: true,
        verticalArrangement: 'spacedBy'
    }, rows);
}

module.exports = Screen;
