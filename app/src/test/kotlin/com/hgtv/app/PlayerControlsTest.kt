package com.hgtv.app

import com.hgtv.app.ui.pages.resolveStartMs
import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放器装载续播位置决策（票05：错误页重试断点不丢 / 换集从头）。 */
class PlayerControlsTest {

    @Test
    fun `首次装载且为起始集从入参断点起播`() {
        assertEquals(12345L, resolveStartMs(currentEpisode = 3, lastLoadedEpisode = -1, startEpisode = 3, startPositionMs = 12345L, resumeMs = 999_999L))
    }

    @Test
    fun `首次装载非起始集从头播`() {
        assertEquals(0L, resolveStartMs(currentEpisode = 5, lastLoadedEpisode = -1, startEpisode = 3, startPositionMs = 12345L, resumeMs = 999_999L))
    }

    @Test
    fun `换集从头播`() {
        assertEquals(0L, resolveStartMs(currentEpisode = 4, lastLoadedEpisode = 3, startEpisode = 3, startPositionMs = 12345L, resumeMs = 999_999L))
    }

    @Test
    fun `同集重试从最近播放位置续播`() {
        assertEquals(999_999L, resolveStartMs(currentEpisode = 3, lastLoadedEpisode = 3, startEpisode = 3, startPositionMs = 12345L, resumeMs = 999_999L))
    }

    @Test
    fun `浮层选回起始集视为换集从头播`() {
        assertEquals(0L, resolveStartMs(currentEpisode = 3, lastLoadedEpisode = 5, startEpisode = 3, startPositionMs = 12345L, resumeMs = 999_999L))
    }
}
