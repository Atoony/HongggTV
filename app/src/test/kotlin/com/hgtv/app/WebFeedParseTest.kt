package com.hgtv.app

import com.hgtv.app.net.HongguoApi
import com.hgtv.app.net.HongguoFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 票10：web 热播榜解析（真实 page=1/2 夹具，2026-10-31 抓取）。
 *  同日推荐行移除，buildWebFeed 组装用例随之删除，解析链用例保留。 */
class WebFeedParseTest {

    private fun feed() = HongguoFeed(HongguoApi())

    private fun fixture(page: Int): String =
        javaClass.classLoader!!.getResource("web_rank_p$page.html")!!.readText()

    @Test
    fun `page1 解析 - 20 部且集数取总集数字段`() {
        val rows = feed().parseRankPage(fixture(1))
        assertEquals(20, rows.size)
        val top = rows.first()
        assertEquals("7698294694886788121", top.seriesId)
        // episodeCount=总集数(int 987)；episodeVids(5 项)只是免费集 vid 列表
        assertEquals("987", top.episodeCount)
        assertTrue("title 为空", top.title.isNotEmpty())
        assertTrue("heat 为空", top.heat.isNotEmpty())
        assertTrue("cover 非绝对地址: ${top.cover}", top.cover.startsWith("https://"))
    }

    @Test
    fun `page2 解析 - 与 page1 零重叠`() {
        val p1 = feed().parseRankPage(fixture(1)).map { it.seriesId }.toSet()
        val p2 = feed().parseRankPage(fixture(2))
        assertEquals(20, p2.size)
        assertTrue("跨页重叠: ${p1 intersect p2.map { it.seriesId }.toSet()}", (p1 intersect p2.map { it.seriesId }.toSet()).isEmpty())
    }
}
