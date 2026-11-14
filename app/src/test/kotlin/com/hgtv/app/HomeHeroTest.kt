package com.hgtv.app

import com.hgtv.app.data.WatchEntry
import com.hgtv.app.ui.pages.heroButtonText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 票20 首页 hero 主按钮文案（DESIGN §6：有进度"继续播放 · 第N集"、无则"播放第 1 集"）。 */
class HomeHeroTest {

    private fun entry(episode: Int = 3) = WatchEntry(
        seriesId = "1", title = "测试剧", cover = "",
        episode = episode, episodeCount = 80,
        positionMs = 300_000, durationMs = 900_000, updatedAt = 0L,
    )

    @Test
    fun `无进度播放第1集`() {
        assertEquals("播放第 1 集", heroButtonText(null))
    }

    @Test
    fun `有进度续播第N集`() {
        assertEquals("继续播放 · 第3集", heroButtonText(entry(3)))
        assertEquals("继续播放 · 第12集", heroButtonText(entry(12)))
    }
}
