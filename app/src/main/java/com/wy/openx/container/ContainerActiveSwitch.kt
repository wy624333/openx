package com.wy.openx.container

/**
 * 「设为当前容器」开关的状态规则。抽成纯函数是为了能在 JVM 单测里直接验证
 * （UI 层要 Compose 环境，测不了；而这条规则错了用户就看到一个点不动的开关）。
 *
 * ## 旧实现错在哪
 * 旧代码是 `initial?.id == null || initial?.id == ContainerProfile.BUILTIN_ID`——
 * 拿「这个镜像是不是内置 Alpine」冒充「它是不是当前容器」。两头都错：
 *
 * - 内置镜像：恒显示「开」。用户点关，保存走的是 `if (setActive) setActiveProfile(id)`，
 *   `setActive=false` 直接跳过，**什么都没发生** → 「默认镜像那个开关关不掉」。
 * - 自定义镜像（用户下载导入的）：恒显示「关」。哪怕上一轮已经把它设成当前容器，
 *   再打开编辑页还是「关」→ 用户以为「打不开 / 开不起来」。
 *
 * 正确口径只有一个：**和真实的当前容器 id 比**。
 * 这么写的：
 * `var setAsDefault by remember { mutableStateOf(initial?.id == defaultContainerId) }`。
 */
object ContainerActiveSwitch {

    /**
     * 打开弹窗时开关的初始状态：这个镜像现在**是不是**当前容器。
     * 新建镜像（[profileId] 为 null）一律从关开始——新建不等于接管，
     * 默默把用户正在用的容器换掉是更糟的意外。
     */
    fun initialState(profileId: String?, activeProfileId: String): Boolean =
        profileId != null && profileId == activeProfileId

    /**
     * 当前容器这一行不允许「取消」：容器必须始终有一个是当前的，
     * 不存在「都不选」这种状态。所以当前容器的开关是锁定的。
     */
    fun canToggle(isCurrentlyActive: Boolean): Boolean = !isCurrentlyActive

    /**
     * 保存时真正要落库的值：当前容器恒为 true（重设同一个 id 是幂等的），
     * 其余取用户的选择。
     */
    fun toPersist(isCurrentlyActive: Boolean, setAsActive: Boolean): Boolean =
        isCurrentlyActive || setAsActive
}
