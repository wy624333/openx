/**
 * 桌面宠物 —— 后台侧。
 *
 * 唯一职责：**保活**。浮窗属于宿主进程，宿主被系统杀掉后宠物会一起消失；
 * 定时任务（清单里 schedule 的 keep-alive，每 15 分钟）负责把它拉回桌面。
 *
 * 服务侧的 ctx.overlay.* 返回的是 JSON 字符串（这条轨道没有 Promise 封装），
 * 取值前要先 JSON.parse。
 */

function parse(json) {
    if (!json) return null;
    try {
        return (typeof json === 'string') ? JSON.parse(json) : json;
    } catch (e) {
        return null;
    }
}

function petSize() {
    var n = parseInt(ctx.getEnv('pet_size') || '140', 10);
    return (n >= 96 && n <= 220) ? n : 140;
}

exports.onStart = function () {
    ctx.log('桌面宠物后台就绪（掉线会由定时任务拉回桌面）');
};

exports.onSchedule = function (taskId) {
    if (taskId !== 'keep-alive') return;

    var st = parse(ctx.overlay.isShowing());
    if (st && st.showing) return;

    var can = parse(ctx.overlay.canShow());
    if (!can || !can.canShow) {
        // 没开系统悬浮窗权限就安静跳过：提示留给用户主动点「放到桌面」时说
        return;
    }

    var size = petSize();
    ctx.overlay.show({
        width: size + 24,
        height: size + 92,
        minWidth: size,
        minHeight: size,
        chrome: false,
        transparent: true,
        dragAnywhere: true,
        rememberPosition: true
    });
    ctx.log('宠物不在桌面上，已拉回（体型 ' + size + '）');
};