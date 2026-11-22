/**
 * 桌面宠物 —— 界面（v2：真立绘版）。
 *
 * 移植自 keleus/deepseek-pet（MIT）的美术与状态设计，按 OpenX 插件规范重写：
 *   · 立绘来自原项目的 30 张透明 WebP，用 `asset://assets/xxx.webp` 读包内素材
 *   · 原作的动画是 CSS 变换，这里改用宿主节点的 alpha / rotate / scale / offsetY
 *   · 原作的表情由 DSH 会话状态驱动（正在跑哪个工具、上下文占用…）。OpenX 侧由
 *     `ctx.host.session()` 提供（需 `session` 权限）：思考 / 干活 / 等你确认 / 吃满 / 出错都会换脸。
 *     另有本地信号：时段、空闲时长、点击互动。
 *
 * 同一份 UI 同时服务面板与桌面浮窗（浮窗 chrome:false + transparent），
 * 所以布局只依赖自身尺寸，不假设外面有标题栏。
 */

/** 素材表。键是动作名，值是 assets/ 下的文件名（不含扩展名）。 */
var ART = {
    idle: ['deepseek-idle', 'reaction-relaxed', 'reaction-cheerful', 'reaction-proud'],
    pat: ['reaction-cheerful', 'reaction-proud', 'reaction-relaxed', 'reaction-satiated', 'reaction-shocked'],
    morning: 'reaction-cheerful',
    noon: 'reaction-eating-rice',
    afternoon: 'reaction-thinking',
    evening: 'reaction-relaxed',
    night: 'reaction-sleepy',
    hungry: 'reaction-hungry',
    nap: 'reaction-pillow',
    sleep: 'reaction-sleeping',
    gone: 'reaction-blindfold',
    // ── 跟着 AI 会话状态演（需 session 权限）──
    thinking: ['reaction-thinking', 'reaction-deepseek-pressure'],
    working: ['reaction-desk-coding', 'frame-desk-coding-hands-up', 'frame-thinking-keypress'],
    waiting: ['reaction-skeptical', 'reaction-angry'],
    done: ['reaction-desk-done', 'reaction-proud'],
    failed: ['reaction-desk-facepalm', 'reaction-apologetic'],
    full: ['reaction-satiated', 'reaction-deepseek-rice']
};

/** 点击时的短句池，按当前时段/状态给。 */
var LINES = {
    idle: ['戳我干嘛～', '我一直在这儿', '要不要一起干活？', '（歪头看你）'],
    morning: ['早上好呀', '今天也要加油'],
    noon: ['该吃饭了', '中午吃点好的'],
    afternoon: ['下午容易困呢', '喝杯水吧'],
    evening: ['晚上好', '今天辛苦了'],
    night: ['该睡了…', '（打哈欠）'],
    hungry: ['我饿了…', '有白米饭吗'],
    nap: ['（抱着枕头）困…', '让我眯一会儿'],
    sleep: ['Zzz…', '（睡着了，别吵）'],
    pat: ['嘿嘿', '舒服～', '再摸摸', '喵？不对，我是鲸']
};

function pick(list, index) {
    if (!list || !list.length) return '';
    return list[Math.abs(index) % list.length];
}

/** 当前时段键。 */
function timeSlot(hour) {
    if (hour >= 5 && hour < 11) return 'morning';
    if (hour >= 11 && hour < 14) return 'noon';
    if (hour >= 14 && hour < 18) return 'afternoon';
    if (hour >= 18 && hour < 23) return 'evening';
    return 'night';
}

function drawSizeOf(ctxNow) {
    var n = parseInt(ctxNow.getEnv('pet_size') || '200', 10);
    if (!(n >= 4 && n <= 320)) n = 200;
    return n;
}

/**
 * 空闲档位：从「最后一次互动」到现在的时长决定表情。
 * 与原作一致：不随机，只是随时间单向推进，所以不会出现刚睡着又突然精神的情况。
 */
function idleStage(idleMs, baseMin) {
    var hungryAt = baseMin * 60 * 1000;
    if (idleMs > hungryAt * 6) return 'sleep';
    if (idleMs > hungryAt * 3) return 'nap';
    if (idleMs > hungryAt) return 'hungry';
    return 'normal';
}

// ── 运动（参考 Aharou 的做法：状态机 + 逐帧只改窗口坐标，不重渲染）──
//
// 关键点：走动时**只调 `ctx.overlay.moveTo`**，不 setState —— 改 state 会重建整棵
// UI 树，20fps 跑不起。呼吸/浮动/倾斜交给宿主的 `animate`（Compose 无限循环，60fps、
// 零 JS 开销）。两者叠在一起才像“活着”。
var MOTION = { IDLE: 0, WALK: 1 };

/** 运动循环间隔：20fps。只改窗口坐标，开销极小。 */
var MOTION_MS = 50;
/** 走路速度（dp/s）。比 Aharou 的 62 温和一点。 */
var WALK_SPEED_DP = 46;
/** 走动/弹跳用的安全边：窗口比立绘大这么多（每边 dp），太小会被动画裁掉，太大贴不住边。 */
var OVERLAY_PAD_DP = 4;
/**
 * 走到/拖到距屏幕边这么近（dp）就吸附上去。
 *
 * 要**几乎贴住**才吸：留一点缝（不到半个身位）视觉上还是“在边上”，
 * 但早早就吸会把“离边还有一截”的宠物硬拽过去，还直接半隐藏，很突兀。
 */
var EDGE_SNAP_DP = 10;
/** 贴边后缩进去，只露出一部分（0.55 = 露出 55%）。 */
var TUCK_VISIBLE = 0.55;

/** 自己走到终点时的吸附容差（dp）：跟拖拽一致，都是“几乎贴住才吸”。 */
var WALK_END_SNAP_DP = 10;

/** 待机多久后做一次“要不要走走”的决定（毫秒区间）。 */
var IDLE_DECIDE_MIN = 7000;
var IDLE_DECIDE_MAX = 17000;
/** 决定走走的概率。 */
var WALK_CHANCE = 0.45;

/** AI 会话状态的中文短题（面板底部状态行用）。 */
var SESSION_TITLES = {
    thinking: 'AI 在思考',
    working: 'AI 在干活',
    waiting: '等你确认',
    done: '刚干完',
    failed: '出错了',
    full: '上下文吃满'
};

/** AI 会话状态对应的台词（只在档位变化时说一句，不唠叨）。 */
var SESSION_LINES = {
    thinking: ['让我想想…', '（皱眉思考）', '在推理中'],
    working: ['在干活！', '查一下资料', '（敲键盘）'],
    waiting: ['等你点一下', '我在这儿等你', '（等得有点久了…）'],
    done: ['搞定！', '活儿干完了'],
    failed: ['出错了…', '（挠头）'],
    full: ['脑子快满了', '上下文吃多了，要不要开新会话']
};

/**
 * 用宿主会话快照决定「现在该演哪一段」。
 *
 * 优先级：等你确认 > 出错/重连 > 在跑工具 > 在思考 > 上下文吃满。
 * pending 放最前：此刻**必须用户做点什么**，宠物该提醒他。
 */
function sessionStageOf(s) {
    if (!s) return '';
    if (s.pending) return 'waiting';
    if (s.reconnecting) return 'failed';
    if (s.busy && s.tool) return 'working';
    if (s.busy) return 'thinking';
    if (s.contextTokens >= 60000) return 'full';
    return '';
}

/** 会话档位是否算“AI 正在忙”（此时不要自己到处走，分散注意）。 */
function sessionBusyStage(st) {
    return st === 'thinking' || st === 'working' || st === 'waiting';
}

function Screen(ctx) {
    var UI = ctx.UI;

    var artPair = ctx.useState('pet_art', ART.idle[0]);
    var bubblePair = ctx.useState('pet_bubble', '');
    var tickPair = ctx.useState('pet_tick', 0);
    var fadePair = ctx.useState('pet_fade', 1);
    var shownPair = ctx.useState('pet_showing', false);
    var sessPair = ctx.useState('pet_sess_stage', '');

    var art = artPair[0];
    var setArt = artPair[1];
    var bubble = bubblePair[0];
    var setBubble = bubblePair[1];
    var tick = tickPair[0] || 0;
    var setTick = tickPair[1];
    var fade = fadePair[0];
    var setFade = fadePair[1];
    var showing = shownPair[0] === true;
    var setShowing = shownPair[1];
    var sessStage = sessPair[0] || '';
    var setSessStage = sessPair[1];

    var refs = {
        last: ctx.useRef('pet_last_touch', 0),
        started: ctx.useRef('pet_started', false),
        rotateAt: ctx.useRef('pet_rotate_at', 0),
        rotateIdx: ctx.useRef('pet_rotate_idx', 0),
        patUntil: ctx.useRef('pet_pat_until', 0),
        patIdx: ctx.useRef('pet_pat_idx', 0),
        bubbleSeq: ctx.useRef('pet_bubble_seq', 0),
        greetFor: ctx.useRef('pet_greet_for', ''),
        // 定时器只在首帧创建一次，闭包里捕获的 state 永远是初始值 ——
        // 当前图与淡入进度必须放 ref，否则「同一张图反复 set」会把重渲染打死循环。
        artRef: ctx.useRef('pet_art_ref', ART.idle[0]),
        fadeRef: ctx.useRef('pet_fade_ref', 1),
        needIdle: ctx.useRef('pet_need_idle', false),
        // 运动状态（只用 ref：20fps 的循环里读 state 拿到的永远是首帧值）
        motion: ctx.useRef('pet_motion', MOTION.IDLE),
        walkTo: ctx.useRef('pet_walk_to', null),
        decideAt: ctx.useRef('pet_decide_at', 0),
        geo: ctx.useRef('pet_geo', null),
        passApplied: ctx.useRef('pet_pass_applied', null),
        sizeApplied: ctx.useRef('pet_size_applied', null),
        walkStartedAt: ctx.useRef('pet_walk_started_at', 0),
        wasDragging: ctx.useRef('pet_was_dragging', false),
        // 会话状态抽样（每 10 拍一次，即 ~500ms）
        sessTick: ctx.useRef('pet_sess_tick', 0),
        sessStage: ctx.useRef('pet_sess_stage_ref', ''),
        // 忙完那一下的「搞定」表情要留几秒，否则一闪就没
        doneUntil: ctx.useRef('pet_done_until', 0),
        // 上次生效的「贴边收进去」设置（改了就立刻重贴一次）
        tuckApplied: ctx.useRef('pet_tuck_applied', null)
    };

    // 换图 + 记下当前图
    function switchArt(next, fromAlpha) {
        if (!next || refs.artRef.current === next) return;
        refs.artRef.current = next;
        refs.fadeRef.current = fromAlpha;
        setArt(next);
        setFade(fromAlpha);
    }

    var name = ctx.getEnv('pet_name') || '小鲸';
    var size = drawSizeOf(ctx);
    var talk = (ctx.getEnv('pet_talk') || 'true') !== 'false';
    var greetOn = (ctx.getEnv('pet_greet') || 'true') !== 'false';
    var rotateSec = parseInt(ctx.getEnv('pet_rotate') || '24', 10) || 24;
    var baseMin = parseInt(ctx.getEnv('pet_idle_min') || '10', 10) || 10;

    var now = Date.now();
    var slot = timeSlot(new Date(now).getHours());
    var stage = idleStage(now - (refs.last.current || now), baseMin);
    var patting = now < (refs.patUntil.current || 0);

    // 注意：走动 / 贴边 / 穿透这三个开关**不能**在这里读一次就完 ——
    // tickMotion 是首帧创建的闭包，变量捕获的是渲染时的值，用户在面板里改完
    // 它永远看不到新值。所以一律在各个函数内部即时读 ctx.getEnv。

    /**
     * 贴边吸附：走到或拖到边缘附近就贴住。
     *
     * 关键点：窗口是“立绘 + 每边 OVERLAY_PAD_DP”的矩形，如果直接把**窗口**贴到
     * 屏幕边，立绘看着还会差一小截（那圈余量）。所以贴边时要把这圈余量**推到屏幕外**。
     *
     * 开了「贴边收进去」的话，在此基础上再缩进半个身体。
     */
    function snapPx(density) {
        return EDGE_SNAP_DP * (density || 2.75);
    }

    /**
     * 身体的**可见位置**（窗口坐标夹回屏内）。
     *
     * 收起状态下窗口坐标是负的（向左收）或超出右界的，直接拿它算走动目标会被
     * 夹成 0，结果就是“想走却原地踏步，然后又被吸回边上”。
     */
    function logicalX(g) {
        var maxX = Math.max(0, (g.screenWidth || 0) - (g.width || 0));
        return Math.max(0, Math.min(maxX, g.x || 0));
    }

    /**
     * @param tolDp 吸附容差（dp）。拖拽松手与走到终点都用 [EDGE_SNAP_DP]：几乎贴住才吸。
     */
    function snapNear(g, tolDp) {
        if (!g || !g.width || !g.screenWidth) return;
        var tuckOn = (ctx.getEnv('pet_tuck') || 'false') === 'true';
        var density = g.density || 2.75;
        var padPx = Math.round(OVERLAY_PAD_DP * density);
        var snap = (tolDp || EDGE_SNAP_DP) * density;
        var maxX = Math.max(0, g.screenWidth - g.width);
        var x = g.x;
        // 缩进量按**立绘宽**算（窗口比立绘大 2*padPx）
        var bodyW = Math.max(1, g.width - 2 * padPx);
        var tuckPx = Math.round(bodyW * (1 - TUCK_VISIBLE));
        if (x <= snap) {
            ctx.overlay.moveTo(tuckOn ? -padPx - tuckPx : -padPx, g.y);
        } else if (x >= maxX - snap) {
            ctx.overlay.moveTo(tuckOn ? maxX + padPx + tuckPx : maxX + padPx, g.y);
        }
    }

    /**
     * 运动循环：20fps，**只调窗口坐标，不改 state**。
     *
     * 改 state 会重建整棵 UI 树，20fps 跑不起；呼吸/浮动/倾斜已经交给宿主的
     * `animate`（Compose 无限循环，60fps、零 JS 开销）。两边叠在一起才像“活着”。
     */
    function tickMotion() {
        var g = null;
        try {
            var r = ctx.overlay.geometry();
            if (r && r.ok) g = r;
        } catch (e) { g = null; }
        if (!g || !g.showing) return;
        refs.geo.current = g;

        var wantedSize = drawSizeOf(ctx);
        if (refs.sizeApplied.current !== wantedSize) {
            try {
                var sized = ctx.overlay.resize(wantedSize + OVERLAY_PAD_DP * 2,
                    wantedSize + OVERLAY_PAD_DP * 2);
                if (sized && sized.ok) {
                    refs.sizeApplied.current = wantedSize;
                    // 尺寸和立绘都要用新设置，不用首帧闭包里的旧 size。
                    setTick(function (n) { return (n || 0) + 1; });
                    return;
                }
            } catch (e) { /* 浮窗未就绪时下次再试 */ }
        }

        // 穿透开关：每拍读配置（改完立即生效）
        var passWant = (ctx.getEnv('pet_passthrough') || 'false') === 'true';
        if (refs.passApplied.current !== passWant) {
            refs.passApplied.current = passWant;
            try { ctx.overlay.setPassThrough(passWant); } catch (e) { /* 忽略 */ }
        }

        // ── 会话状态：AI 在忙什么，宠物就演什么（每 ~500ms 抽样一次）。
        // 只在档位**变化**时换图 + 说一句，避免每拍都重渲染。
        refs.sessTick.current = (refs.sessTick.current || 0) + 1;
        if (refs.sessTick.current % 10 === 0) {
            var watchOn = (ctx.getEnv('pet_session') || 'true') !== 'false';
            var snap = null;
            if (watchOn) {
                try {
                    var sr = ctx.host.session();
                    if (sr && sr.ok) snap = sr;
                } catch (e) { snap = null; }
            }
            var nextStage = sessionStageOf(snap);
            // 从「忙」回到「闲」的那一下，先演 4 秒「搞定」，别直接切回待机
            if (nextStage === '' && sessionBusyStage(refs.sessStage.current)) {
                refs.doneUntil.current = Date.now() + 4000;
            }
            if (nextStage === '' && refs.doneUntil.current > Date.now()) nextStage = 'done';
            if (nextStage !== refs.sessStage.current) {
                var wasSession = refs.sessStage.current !== '';
                refs.sessStage.current = nextStage;
                setSessStage(nextStage);
                var group = ART[nextStage];
                if (group && group.length) {
                    switchArt(pick(group, refs.sessTick.current), 0.5);
                } else if (wasSession) {
                    // 回到空闲：立即交还给心跳换回待机图，否则会顶着 AI 表情等 24 秒轮换
                    refs.needIdle.current = true;
                }
                var lines = SESSION_LINES[nextStage];
                if (lines) say(pick(lines, refs.sessTick.current), 5200);
            }
        }

        // AI 正在忙（思考/干活/等你确认）时不要自己到处走：会把注意力引走
        if (sessionBusyStage(refs.sessStage.current)) {
            refs.motion.current = MOTION.IDLE;
            refs.walkTo.current = null;
            return;
        }

        // 用户在拖：一切让位（否则会跟手指抢方向盘）
        if (g.dragging) {
            refs.wasDragging.current = true;
            refs.motion.current = MOTION.IDLE;
            refs.walkTo.current = null;
            return;
        }
        // 刚松手：立即贴边。不这么做就要等下一次待机决策（最长 17 秒）才吸附，
        // 用户会以为“拖到边上没反应”。
        if (refs.wasDragging.current) {
            refs.wasDragging.current = false;
            snapNear(g);
            refs.decideAt.current = Date.now() + IDLE_DECIDE_MIN
                + Math.random() * (IDLE_DECIDE_MAX - IDLE_DECIDE_MIN);
            return;
        }

        var w = g.width || 0;
        var sw = g.screenWidth || 0;
        if (w <= 0 || sw <= 0) return;
        var maxX = Math.max(0, sw - w);
        var density = g.density || 2.75;
        var t = Date.now();

        // 收起开关一改就立即重贴一次：否则已经贴在边上的宠物要等到下次被拖才反应
        var tuckWant = (ctx.getEnv('pet_tuck') || 'false') === 'true';
        if (refs.tuckApplied.current !== tuckWant) {
            refs.tuckApplied.current = tuckWant;
            snapNear(g);
        }

        if (refs.motion.current === MOTION.WALK && refs.walkTo.current != null) {
            var target = Math.max(0, Math.min(maxX, refs.walkTo.current));
            var dx = target - g.x;
            var step = Math.max(1, Math.round(WALK_SPEED_DP * density * MOTION_MS / 1000));
            if (Math.abs(dx) <= step) {
                ctx.overlay.moveTo(target, g.y);
                refs.walkTo.current = null;
                refs.motion.current = MOTION.IDLE;
                refs.decideAt.current = t + IDLE_DECIDE_MIN + Math.random() * (IDLE_DECIDE_MAX - IDLE_DECIDE_MIN);
                snapNear({ x: target, y: g.y, width: w, screenWidth: sw, density: density }, WALK_END_SNAP_DP);
            } else {
                ctx.overlay.moveTo(g.x + (dx > 0 ? step : -step), g.y);
            }
            return;
        }

        // 待机：到点做一次决定（走走，或者贴着边站住）
        if (t >= (refs.decideAt.current || 0)) {
            refs.decideAt.current = t + IDLE_DECIDE_MIN + Math.random() * (IDLE_DECIDE_MAX - IDLE_DECIDE_MIN);
            var walkOn = (ctx.getEnv('pet_walk') || 'true') !== 'false';
            if (walkOn && Math.random() < WALK_CHANCE) {
                // 从**可见位置**出发，而不是窗口坐标：收起时窗口在屏外，拿它算会白走
                var from = logicalX(g);
                var snap = snapPx(density);
                var dir = Math.random() < 0.5 ? -1 : 1;
                // 已经贴着某一侧时就只往场内走，否则目标被夹回 0，走过去又被吸回来
                if (from <= snap) dir = 1;
                else if (from >= maxX - snap) dir = -1;
                var dist = (0.15 + Math.random() * 0.35) * sw * dir;
                refs.walkTo.current = Math.round(Math.max(0, Math.min(maxX, from + dist)));
                refs.motion.current = MOTION.WALK;
                refs.walkStartedAt.current = t;
            }
            // 不走路就原地待着：**待机时绝不吸附**。
            // 在这里吸附会让“站在中间偏左一点”的宠物被强行拉到边上，很像它自己乱跑。
        }
    }

    // ── 定时器：整只宠物的心跳。Screen 每次重渲染都会重新执行，所以只能挂一次，
    //    否则每改一次状态就多一个定时器，越跑越快直到引擎被拖死。
    if (!refs.started.current) {
        refs.started.current = true;
        refs.last.current = now;
        refs.rotateAt.current = now + rotateSec * 1000;

        setInterval(function () {
            var t = Date.now();
            setTick(function (prev) { return (prev || 0) + 1; });

            // 淡入：换图后 alpha 在几个 tick 内爬到 1
            if (refs.fadeRef.current < 1) {
                refs.fadeRef.current = Math.min(1, refs.fadeRef.current + 0.22);
                setFade(refs.fadeRef.current);
            }

            // 久没人理 -> 换状态图（只在跨档位时换，避免抖）
            var st = idleStage(t - (refs.last.current || t), baseMin);
            var want = st === 'sleep' ? ART.sleep : (st === 'nap' ? ART.nap : (st === 'hungry' ? ART.hungry : null));
            if (want) {
                switchArt(want, 0.45);
                return;
            }

            // 摸摸结束后回到待机图。不能只靠「等下一次轮换」——
            // 那样开心脸会挂到下一个轮换周期（默认 24 秒），看着像卡住了。
            if (refs.needIdle.current && (refs.patUntil.current || 0) < t) {
                refs.needIdle.current = false;
                refs.rotateIdx.current = (refs.rotateIdx.current || 0) + 1;
                switchArt(pick(ART.idle, refs.rotateIdx.current), 0.6);
            }

            // 待机轮换：按累计时间稳定推进（不随机）
            if (t >= (refs.rotateAt.current || 0)) {
                refs.rotateAt.current = t + rotateSec * 1000;
                if ((refs.patUntil.current || 0) < t) {
                    refs.rotateIdx.current = (refs.rotateIdx.current || 0) + 1;
                    switchArt(pick(ART.idle, refs.rotateIdx.current), 0.55);
                }
            }
        }, 250);

        // 运动循环：20fps，只改窗口坐标（见 tickMotion）。
        // 与上面的心跳分开：心跳是要重渲染的（慢），运动是不重渲染的（快）。
        refs.decideAt.current = now + 3000;
        setInterval(tickMotion, MOTION_MS);
    }

    // 点一下：换一张开心图 + 说句话
    function pat() {
        var t = Date.now();
        refs.last.current = t;
        refs.patUntil.current = t + 4000;
        refs.patIdx.current = (refs.patIdx.current || 0) + 1;
        refs.needIdle.current = true;
        switchArt(pick(ART.pat, refs.patIdx.current), 0.4);

        if (!talk) return;
        var pool;
        if (stage === 'hungry') pool = LINES.hungry;
        else if (stage === 'nap') pool = LINES.nap;
        else if (stage === 'sleep') pool = LINES.sleep;
        else pool = (LINES[slot] || LINES.idle).concat(LINES.idle);
        say(pick(pool, refs.patIdx.current + tick));
    }

    function say(text, ms) {
        if (!text) return;
        // 桌面上用宿主的气泡窗口：浮窗本体只有 size+8dp，摆不下字，而且把浮窗
        // 改大会多挡一圈触摸。面板里依旧用普通 Text 气泡。
        if (ctx.container === 'overlay') {
            try { ctx.overlay.say(text, ms || 4200); } catch (e) { /* 没有 overlay 能力就安静失败 */ }
            return;
        }
        setBubble(text);
        // 连续触发时只让最后一次的定时器生效，否则前一条会提前把新气泡清掉
        refs.bubbleSeq.current = (refs.bubbleSeq.current || 0) + 1;
        var mine = refs.bubbleSeq.current;
        setTimeout(function () {
            if (refs.bubbleSeq.current === mine) setBubble('');
        }, ms || 4200);
    }

    // 进页面/首次上桌时的问候（每档只问候一次）。走 say() 而不是直接 setBubble：
    // 桌面上文字要用宿主的气泡窗口，直接设 state 会像“问候了但没显示”。
    if (greetOn && refs.greetFor.current !== slot) {
        refs.greetFor.current = slot;
        say(pick(LINES[slot] || LINES.idle, 0), 4500);
    }

    // ── 视觉动作全部交给宿主的 `animate`（Compose 无限循环，60fps、零重渲染）。
    // 这里只决定「用哪套参数」：睡着幅度小、走路小跳快而碎、被摸时弹一下。
    var sleeping = stage === 'sleep';
    var walking = refs.motion.current === MOTION.WALK;
    var animate = {
        bob: sleeping ? 0.8 : (walking ? 2.2 : 1.6),
        bobMs: sleeping ? 3200 : (walking ? 620 : 1800),
        tilt: sleeping ? 0 : 0.5,
        tiltMs: 4600,
        breathe: sleeping ? 0.006 : 0.012,
        breatheMs: sleeping ? 3400 : 2200
    };
    // 被摸时弹一下：静态 offsetY 叠在 animate 之上（只重渲染两次，不是逐帧）。
    // 幅度 ≤ OVERLAY_PAD_DP，否则头顶会被窗口边界切掉。
    var hopY = patting ? -2 : 0;

    // 容器身份：宿主每次渲染都会告诉插件「你现在在哪儿」
    // 'overlay' = 桌面浮窗（只留立绘），其它 = 插件面板（完整控制界面）
    var inOverlay = ctx.container === 'overlay';

    // 浮窗是否已在桌面上：只有面板关心（浮窗里不画按钮）
    if (!inOverlay) {
        try {
            var st = ctx.overlay.isShowing();
            if (st && st.ok && (st.showing === true) !== showing) setShowing(st.showing === true);
        } catch (e) { /* 没有 overlay 权限时忽略 */ }
    }

    function spawn() {
        var can = false;
        try {
            var r = ctx.overlay.canShow();
            can = !!(r && r.canShow);
        } catch (e) { can = false; }
        if (!can) {
            say('还不能上桌面：去系统设置里给 OpenX 开「显示在其他应用上层」');
            return;
        }
        // 窗口贴着立绘：素材已裁到内容边界，四周只留 OVERLAY_PAD_DP 给呼吸/弹跳/倾斜。
        var pad = OVERLAY_PAD_DP * 2;
        ctx.overlay.show({
            width: size + pad,
            height: size + pad,
            minWidth: size,
            minHeight: size,
            chrome: false,
            transparent: true,
            dragAnywhere: true,
            rememberPosition: true
        });
        setShowing(true);
        say('我出来啦，拖我就行');
    }

    function recall() {
        try { ctx.overlay.hide(); } catch (e) { /* 忽略 */ }
        setShowing(false);
        say('我回去睡会儿');
    }

    var stageText = {
        normal: '精神',
        hungry: '饿了',
        nap: '犯困',
        sleep: '睡着了'
    }[stage];

    var children = [];

    // 顶部「放到桌面 / 收回」只属于面板：浮窗里只要立绘，
    // 收回改到插件面板里点（用户反馈：浮窗里的按钮碍眼）。
    if (!inOverlay) {
        children.push(UI.Row({ fillMaxWidth: true, horizontalArrangement: 'end' }, [
            UI.TextButton({
                text: showing ? '收回' : '放到桌面',
                fontSize: 11,
                color: 'primary',
                onClick: function () { if (showing) recall(); else spawn(); }
            })
        ]));
    }

    // 立绘本体：呼吸/浮动/倾斜/点击弹跳都走绘制变换（不改布局尺寸，周围不会被顶开）。
    // 浮窗里不挂 onLongClick：整个窗口都能拖，长按容易被当成拖动，
    // 误触后宠物就没了（收回入口只在面板里）。
    children.push(UI.Box({ fillMaxWidth: true, contentAlignment: 'center' }, [
        UI.Image({
            src: 'asset://assets/' + art + '.webp',
            width: size,
            height: size,
            contentScale: 'fit',
            alpha: fade == null ? 1 : fade,
            // 呼吸/浮动/倾斜走宿主 animate；这里只叠一个“被摸就弹一下”的静态偏移
            offsetY: hopY,
            pivotX: 0.5,
            pivotY: 1.0,
            animate: animate,
            alt: name,
            onClick: pat,
            onLongClick: inOverlay ? null : recall
        })
    ]));

    // 气泡：固定占一行，避免有字/没字时整只宠物上下跳
    // 气泡 / 状态文字 / 提示：都只属于面板。浮窗里只留立绘
    if (!inOverlay) {
        children.push(UI.Box({ fillMaxWidth: true, height: 28, contentAlignment: 'center' }, [
            UI.Text({
                text: bubble || (sleeping ? '（zzz…）' : ''),
                fontSize: 12,
                color: '#8A94A6',
                textAlign: 'center'
            })
        ]));

        children.push(UI.Row({ fillMaxWidth: true, horizontalArrangement: 'center' }, [
            UI.Text({
                text: name + ' · ' + (SESSION_TITLES[sessStage] || stageText) + ' · 点我摸摸，长按收回',
                fontSize: 10,
                color: '#6E7787'
            })
        ]));

        if (!showing) {
            children.push(UI.Spacer({ height: 2 }));
            children.push(UI.Text({
                text: '点上面「放到桌面」，我会浮在其它应用上，可以拖着走。',
                fontSize: 10,
                color: '#5D6675',
                textAlign: 'center'
            }));
        }
    }

    // 浮窗不设内边距：立绘直接对着窗口边界，多一层 padding 会让它看起来偏了一截
    return UI.Column({ padding: inOverlay ? 0 : 6, fillMaxWidth: true }, children);
}

module.exports = Screen;
