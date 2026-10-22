package com.hgtv.app

import com.hgtv.app.data.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 票07 卡片角标与进度条比例（继续观看行 / 历史页共用格式）。 */
class WatchEntryBadgeTest {

    private fun entry(episode: Int = 3, count: Int = 80, pos: Long = 300_000, dur: Long = 900_000) =
        WatchEntry(
            seriesId = "1", title = "测试剧", cover = "",
            episode = episode, episodeCount = count,
            positionMs = pos, durationMs = dur, updatedAt = 0L,
        )

    @Test
    fun `角标第N斜杠M集`() {
        assertEquals("第3/80集", entry().episodeBadge)
    }

    @Test
    fun `总集数未知只显示第N集`() {
        assertEquals("第3集", entry(count = 0).episodeBadge)
    }

    @Test
    fun `进度比例正常`() {
        assertEquals(1f / 3f, entry(pos = 300_000, dur = 900_000).progressFraction!!, 0.001f)
    }

    @Test
    fun `进度比例时长无效返回null`() {
        assertNull(entry(dur = 0).progressFraction)
        assertNull(entry(dur = -1).progressFraction)
    }

    @Test
    fun `进度比例越界裁剪到0与1`() {
        assertEquals(1f, entry(pos = 2_000_000, dur = 900_000).progressFraction!!, 0f)
        assertEquals(0f, entry(pos = -5, dur = 900_000).progressFraction!!, 0f)
    }
}
