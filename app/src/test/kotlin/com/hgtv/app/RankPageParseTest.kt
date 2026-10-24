package com.hgtv.app

import com.hgtv.app.net.HongguoApi
import com.hgtv.app.net.HongguoFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 热播榜页离线解析（2026-10-24 改版回归）：数据移入 r 标签直出，mergeLoaderData 沦为 stub。 */
class RankPageParseTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResource("rank_hot_drama_fragment.html")!!.readText()

    @Test
    fun `r 标签新结构 - 20 部全量解析`() {
        val rank = HongguoFeed(HongguoApi()).parseRankPage(fixture())
        assertEquals(20, rank.size)
        val top = rank.first()
        assertEquals(1, top.rank)
        assertTrue("seriesId 非数字: ${top.seriesId}", top.seriesId.matches(Regex("^\\d{10,25}$")))
        assertTrue("标题为空", top.title.isNotEmpty())
        assertTrue("heat 为空", top.heat.isNotEmpty())
        assertTrue("集数为空", top.episodeCount.isNotEmpty())
        assertTrue("封面为空", top.cover.isNotEmpty())
    }

    @Test
    fun `无数据标签时保持解析失败`() {
        try {
            HongguoFeed(HongguoApi()).parseRankPage("<html><body>ok</body></html>")
            fail("应抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("热播榜解析失败", e.message)
        }
    }
}
