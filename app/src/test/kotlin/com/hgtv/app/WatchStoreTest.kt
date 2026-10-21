package com.hgtv.app

import com.hgtv.app.data.WatchStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** 观看历史序列化自检（离线）。新装机 DataStore 为空串是首个真实场景（模拟器首启崩溃回归）。 */
class WatchStoreTest {

    @Test
    fun `空串返回空列表不抛异常`() {
        assertEquals(emptyList<com.hgtv.app.data.WatchEntry>(), WatchStore.deserialize(""))
    }

    @Test
    fun `序列化往返`() {
        val e = com.hgtv.app.data.WatchEntry(
            seriesId = "7688352674999176254",
            title = "测试剧",
            cover = "https://example.com/c.jpg",
            episode = 3,
            episodeCount = 80,
            positionMs = 125_000,
            durationMs = 900_000,
            updatedAt = 1697000000123L,
        )
        val out = WatchStore.deserialize(WatchStore.serialize(listOf(e)))
        assertEquals(listOf(e), out)
    }
}
