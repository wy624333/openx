/**
 * 演示包：界面部分。
 *
 * 覆盖二期新增组件（Slider / Dropdown / RadioButton / Tabs / ListItem / Chip / Image /
 * Dialog / ModalBottomSheet）与新增 ctx API（文件、剪贴板、通知、结构化存储）。
 *
 * 入口用 CommonJS 写法，三种入口写法都支持，见《插件开发指南》。
 */
function Screen(ctx) {
    // ---------------- 状态 ----------------
    const [tab, setTab] = ctx.useState('tab', 0);
    const [vol, setVol] = ctx.useState('vol', 50);
    const [mode, setMode] = ctx.useState('mode', 0);
    const [quality, setQuality] = ctx.useState('quality', 0);   // 下拉选中索引
    const [tags, setTags] = ctx.useState('tags', []);           // 多选标签
    const [note, setNote] = ctx.useState('note', '');
    const [imgData, setImgData] = ctx.useState('img', '');
    const [dialogOpen, setDialogOpen] = ctx.useState('dlg', false);
    const [sheetOpen, setSheetOpen] = ctx.useState('sheet', false);
    const [booting, setBooting] = ctx.useState('booted', false);
    const [err, setErr] = ctx.useState('err', '');

    // 统一包一层：把失败写进可见的错误状态，而不是被 try/catch 静默吞掉。
    // （之前踩过：ctx API 缺失时抛 ReferenceError，被 catch 吞了，
    //   用户只看到"点了没反应"，查了很久）
    function attempt(label, fn) {
        try {
            const r = fn();
            if (r && r.ok === false) setErr(label + '失败：' + (r.error || '未知'));
            else setErr('');
            return r;
        } catch (e) {
            setErr(label + '失败：' + (e && e.message ? e.message : String(e)));
            return null;
        }
    }

    // 首帧把已持久化的配置读回来（只做一次）
    if (!booting) {
        setBooting(true);
        const saved = ctx.store.get('settings', null);
        if (saved) {
            if (typeof saved.vol === 'number') setVol(saved.vol);
            if (typeof saved.mode === 'number') setMode(saved.mode);
            if (typeof saved.note === 'string') setNote(saved.note);
            if (Array.isArray(saved.tags)) setTags(saved.tags);
        }
    }

    // ---------------- 逻辑 ----------------
    function persist() {
        ctx.store.set('settings', {
            vol: vol, mode: mode, note: note, tags: tags
        });
    }

    function saveSettings() {
        persist();
        ctx.showToast('配置已保存（重启后仍在）');
    }

    function toggleTag(name) {
        const next = tags.indexOf(name) >= 0
            ? tags.filter(function (t) { return t !== name; })
            : tags.concat([name]);
        setTags(next);
    }

    async function doPickImage() {
        try {
            const res = await ctx.pickImage();
            if (res && res.ok && !res.cancelled) { setErr(''); setImgData(res.data); }
            else if (res && res.cancelled) setErr('未选择图片');
            else setErr('选图失败：' + (res && res.error ? res.error : '未知'));
        } catch (e) {
            setErr('选图异常：' + (e && e.message ? e.message : String(e)));
        }
    }

    async function doWriteFile() {
        try {
            const res = await ctx.writeFile('note.txt', note || '(空)');
            if (res && res.ok) { setErr(''); ctx.showToast('已写入沙箱 note.txt（' + res.size + ' 字节）'); }
            else setErr('写文件失败：' + (res && res.error ? res.error : '未知'));
        } catch (e) {
            setErr('写文件异常：' + (e && e.message ? e.message : String(e)));
        }
    }

    async function doReadFile() {
        try {
            const res = await ctx.readFile('note.txt');
            if (res && res.ok) { setErr(''); setNote(res.content); ctx.showToast('已读回 note.txt'); }
            else setErr('读文件失败：' + (res && res.error ? res.error : '还没有写过 note.txt'));
        } catch (e) {
            setErr('读文件异常：' + (e && e.message ? e.message : String(e)));
        }
    }

    function doCopy() {
        const r = attempt('复制', function () { return ctx.copy(note); });
        if (r && r.ok) ctx.showToast('已复制到剪贴板');
    }

    function doPaste() {
        const r = attempt('粘贴', function () { return ctx.paste(); });
        if (r && r.ok) setNote(r.text);
    }

    function doNotify() {
        const r = attempt('通知', function () {
            return ctx.notify('功能演示包', note ? ('内容：' + note) : '这是一条测试通知');
        });
        if (r && r.ok) ctx.showToast('通知已发送');
    }

    // ---------------- 组件片段 ----------------
    function section(title, children) {
        return ctx.UI.Card({ cornerRadius: 12 }, [
            ctx.UI.Text({ text: title, fontSize: 13, bold: true, color: 'textsecondary' }),
            ctx.UI.Spacer({ height: 8 })
        ].concat(children));
    }

    const qualityOptions = ['低', '中', '高', '极高'];

    const tabContent = tab === 0 ? [
        // ---- 交互组件 ----
        section('交互组件', [
            ctx.UI.Text({ text: '滑块（音量 ' + vol + '）', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.Slider({
                min: 0, max: 100, steps: 9,
                value: vol,
                onValueChange: function (p) { setVol(Math.round(p)); }
            }),
            ctx.UI.Spacer({ height: 10 }),

            ctx.UI.Text({ text: '下拉选择', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.Dropdown({
                items: qualityOptions,
                selectedIndex: quality,
                onSelect: function (p) {
                    setQuality(p.index);
                    ctx.showToast('已选：' + p.label);
                }
            }),
            ctx.UI.Spacer({ height: 10 }),

            ctx.UI.Text({ text: '单选', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.Row({ verticalAlignment: 'center' }, [
                ctx.UI.RadioButton({
                    checked: mode === 0,
                    onCheckedChange: function () { setMode(0); }
                }),
                ctx.UI.Text({ text: '模式一', fontSize: 14 }),
                ctx.UI.Spacer({ width: 12 }),
                ctx.UI.RadioButton({
                    checked: mode === 1,
                    onCheckedChange: function () { setMode(1); }
                }),
                ctx.UI.Text({ text: '模式二', fontSize: 14 })
            ]),
            ctx.UI.Spacer({ height: 10 }),

            ctx.UI.Text({ text: '标签（多选，点一下切换）', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.Row({ wrap: true, horizontalArrangement: 'spacedBy' },
                ['重要', '待办', '收藏', '归档'].map(function (t) {
                    return ctx.UI.Chip({
                        text: t,
                        selected: tags.indexOf(t) >= 0,
                        onClick: function () { toggleTag(t); }
                    });
                })
            )
        ]),

        ctx.UI.Spacer({ height: 12 }),

        // ---- 列表与浮层 ----
        section('列表与浮层', [
            ctx.UI.ListItem({
                title: '打开对话框',
                subtitle: '演示 Dialog 组件',
                leadingIcon: 'info',
                showArrow: true,
                onClick: function () { setDialogOpen(true); }
            }),
            ctx.UI.HorizontalDivider({}),
            ctx.UI.ListItem({
                title: '打开底部抽屉',
                subtitle: '演示 ModalBottomSheet',
                leadingIcon: 'list',
                showArrow: true,
                onClick: function () { setSheetOpen(true); }
            }),
            ctx.UI.HorizontalDivider({}),
            ctx.UI.ListItem({
                title: '当前标签数',
                subtitle: '选了 ' + tags.length + ' 个：' + (tags.join('、') || '无'),
                trailingText: String(tags.length)
            })
        ])
    ] : [
        // ---- 文件与系统 ----
        section('文件与系统', [
            ctx.UI.Text({ text: '笔记内容（会写进插件沙箱）', fontSize: 12, color: 'textsecondary' }),
            ctx.UI.TextField({
                value: note,
                onValueChange: function (p) { setNote(p); },
                placeholder: '输入点什么…',
                singleLine: false
            }),
            ctx.UI.Spacer({ height: 10 }),
            ctx.UI.Row({ wrap: true, horizontalArrangement: 'spacedBy' }, [
                ctx.UI.Button({ text: '写文件', onClick: doWriteFile }),
                ctx.UI.Button({ text: '读文件', onClick: doReadFile })
            ]),
            ctx.UI.Spacer({ height: 8 }),
            ctx.UI.Row({ wrap: true, horizontalArrangement: 'spacedBy' }, [
                ctx.UI.TextButton({ text: '复制', onClick: doCopy }),
                ctx.UI.TextButton({ text: '粘贴', onClick: doPaste }),
                ctx.UI.TextButton({ text: '发通知', onClick: doNotify })
            ])
        ]),

        ctx.UI.Spacer({ height: 12 }),

        section('图片', [
            ctx.UI.Text({
                text: imgData ? '已选择图片：' : '还没选图片（需要 file 权限）',
                fontSize: 12, color: 'textsecondary'
            }),
            ctx.UI.Spacer({ height: 8 }),
            imgData
                ? ctx.UI.Image({ src: imgData, height: 160, contentScale: 'fit', cornerRadius: 8 })
                : ctx.UI.Image({ src: '', alt: '（点击下方按钮选一张）', height: 80 }),
            ctx.UI.Spacer({ height: 8 }),
            ctx.UI.Button({ text: '选择图片', onClick: doPickImage })
        ])
    ];

    // ---------------- 主界面 ----------------
    const tree = ctx.UI.Column({ padding: 16 }, [
        ctx.UI.Text({ text: '功能演示包', fontSize: 20, bold: true }),
        ctx.UI.Text({
            text: '演示二期新增组件与能力',
            fontSize: 12, color: 'textsecondary'
        }),
        ctx.UI.Spacer({ height: 12 }),

        // 错误横幅：任何失败都会立即显示在这里（而不是只弹个 toast 就没了）
        err ? ctx.UI.Card({ background: '#3A1F1F', cornerRadius: 8 }, [
            ctx.UI.Text({ text: '⚠ ' + err, fontSize: 11, color: '#FF6B6B' })
        ]) : ctx.UI.Spacer({ height: 0 }),
        err ? ctx.UI.Spacer({ height: 8 }) : ctx.UI.Spacer({ height: 0 }),

        ctx.UI.Tabs({
            tabs: ['组件', '文件与图片'],
            selectedIndex: tab,
            onSelect: function (p) { setTab(p.index); }
        }),
        ctx.UI.Spacer({ height: 12 })
    ].concat(tabContent).concat([
        ctx.UI.Spacer({ height: 16 }),
        ctx.UI.HorizontalDivider({}),
        ctx.UI.Spacer({ height: 12 }),
        ctx.UI.Button({ text: '保存全部配置', onClick: saveSettings }),
        ctx.UI.Spacer({ height: 8 }),
        ctx.UI.Text({
            text: '状态：音量 ' + vol + ' · 模式 ' + mode + ' · 画质 ' +
                  qualityOptions[quality] + ' · 标签 ' + tags.length,
            fontSize: 10, color: 'textsecondary'
        })
    ]));

    // ---- 浮层：作为兄弟节点返回。宿主会让它们浮在整棵树上 ----
    const overlays = [];

    overlays.push(ctx.UI.Dialog({
        visible: dialogOpen,
        stateKey: 'dlg',
        title: '这是一个对话框',
        text: '确认后会弹一个 toast；取消则什么都不做。',
        confirmText: '确认',
        dismissText: '取消',
        onConfirm: function () {
            setDialogOpen(false);
            ctx.showToast('你点了确认');
        },
        onDismiss: function () { setDialogOpen(false); }
    }));

    overlays.push(ctx.UI.ModalBottomSheet({
        visible: sheetOpen,
        stateKey: 'sheet',
        title: '底部抽屉',
        onDismiss: function () { setSheetOpen(false); }
    }, [
        ctx.UI.Text({ text: '抽屉里的内容也是普通组件。', fontSize: 13 }),
        ctx.UI.Spacer({ height: 8 }),
        ctx.UI.Button({
            text: '关闭',
            onClick: function () { setSheetOpen(false); }
        })
    ]));

    // 用 Box 包一层：浮层节点不占位，但需要有个父节点承载它们
    return ctx.UI.Column({ fillMaxWidth: true }, [tree].concat(overlays));
}

module.exports = Screen;
