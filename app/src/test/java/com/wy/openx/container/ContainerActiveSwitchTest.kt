package com.wy.openx.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设为当前容器」开关的规则回归测试。
 *
 * 背景：用户报「默认镜像开关关不掉、自己下载的镜像开关打不开」。
 * 根因是开关初值拿「是不是内置镜像」冒充「是不是当前容器」，
 * 于是两头都错。这里把用户描述的两个场景原样钉成用例。
 */
class ContainerActiveSwitchTest {

    private val builtin = ContainerProfile.BUILTIN_ID
    private val downloaded = "custom-1730000000000"

    @Test
    fun `内置镜像不是当前容器时开关应为关`() {
        // 用户已经把自己下载的镜像设成当前容器了，内置 Alpine 此时并不在用
        assertFalse(ContainerActiveSwitch.initialState(builtin, activeProfileId = downloaded))
    }

    @Test
    fun `自定义镜像正是当前容器时开关应为开`() {
        // 关键回归：旧实现恒返回 false，导致「已经切过去了，编辑页还显示关」，
        // 用户看着就像开关打不开
        assertTrue(ContainerActiveSwitch.initialState(downloaded, activeProfileId = downloaded))
    }

    @Test
    fun `新建镜像初始一律为关`() {
        // null = 新建。新建不等于接管，不该默默把用户正在用的容器换掉
        assertFalse(ContainerActiveSwitch.initialState(null, activeProfileId = builtin))
        assertFalse(ContainerActiveSwitch.initialState(null, activeProfileId = downloaded))
    }

    @Test
    fun `空 activeProfileId 不会让任何镜像显示为当前`() {
        assertFalse(ContainerActiveSwitch.initialState(builtin, activeProfileId = ""))
        assertFalse(ContainerActiveSwitch.initialState(downloaded, activeProfileId = ""))
    }

    @Test
    fun `当前容器的开关不可取消`() {
        // 容器必须始终有一个是当前的，不存在「都不选」的状态
        assertFalse(ContainerActiveSwitch.canToggle(isCurrentlyActive = true))
        assertTrue(ContainerActiveSwitch.canToggle(isCurrentlyActive = false))
    }

    @Test
    fun `用户关掉当前容器的开关也不会把当前容器清空`() {
        // 落库值对当前容器恒为 true（重设同 id 幂等），
        // 不会走到 saveContainerProfile 里的 if(setActive) 为 false 而什么都不做
        assertTrue(ContainerActiveSwitch.toPersist(isCurrentlyActive = true, setAsActive = false))
        assertTrue(ContainerActiveSwitch.toPersist(isCurrentlyActive = true, setAsActive = true))
    }

    @Test
    fun `非当前容器按用户选择落库`() {
        assertTrue(ContainerActiveSwitch.toPersist(isCurrentlyActive = false, setAsActive = true))
        assertFalse(ContainerActiveSwitch.toPersist(isCurrentlyActive = false, setAsActive = false))
    }

    @Test
    fun `用户报告的两个场景端到端`() {
        // 场景一：默认镜像开关是开着的，点了关不掉
        val builtinActive = ContainerActiveSwitch.initialState(builtin, activeProfileId = builtin)
        assertTrue("内置且正在用 → 显示开", builtinActive)
        // 整行不可点、落库仍为 true：切换只能通过容器列表点击（那里有确认弹窗）
        assertFalse(ContainerActiveSwitch.canToggle(builtinActive))
        assertTrue(ContainerActiveSwitch.toPersist(builtinActive, setAsActive = false))

        // 场景二：下载的镜像开关是关的，点开不了
        val downloadedActive = ContainerActiveSwitch.initialState(downloaded, activeProfileId = builtin)
        assertFalse("不是当前容器 → 显示关", downloadedActive)
        assertTrue("可以点开", ContainerActiveSwitch.canToggle(downloadedActive))
        // 点开后保存 → 落库 true → 真的成为当前容器
        assertTrue(ContainerActiveSwitch.toPersist(downloadedActive, setAsActive = true))
        // 切过去之后再打开编辑页，必须显示开（旧实现在这里恒显示关）
        assertTrue(ContainerActiveSwitch.initialState(downloaded, activeProfileId = downloaded))
    }
}
