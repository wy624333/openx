package com.wy.openx.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTrafficMonitorTest {

    @Test
    fun `计数与重置`() {
        RemoteTrafficMonitor.reset()
        RemoteTrafficMonitor.addUploaded(1024L)
        RemoteTrafficMonitor.addDownloaded(2L * 1024L * 1024L)
        val snapshot = RemoteTrafficMonitor.state.value
        assertEquals(1024L, snapshot.uploaded)
        assertEquals(2L * 1024L * 1024L, snapshot.downloaded)

        RemoteTrafficMonitor.reset()
        assertEquals(RemoteTrafficMonitor.Snapshot(), RemoteTrafficMonitor.state.value)
    }

    @Test
    fun `格式化单位为 kb 与 mb`() {
        assertEquals("0kb", RemoteTrafficMonitor.format(0L))
        assertEquals("1kb", RemoteTrafficMonitor.format(1024L))
        assertEquals("1.5mb", RemoteTrafficMonitor.format(1536L * 1024L))
    }
}
