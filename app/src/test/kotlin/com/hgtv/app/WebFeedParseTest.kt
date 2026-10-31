package com.hgtv.app

import com.hgtv.app.net.HongguoApi
import com.hgtv.app.net.HongguoFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 票10：web 榜单分页推荐行（真实 page=1/2 夹具，2026-10-31 抓取）。 */
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

    @Test
    fun `buildWebFeed - filterIds 去重与字段映射`() {
        val rows = feed().parseRankPage(fixture(2))
        val blocked = rows.take(3).map { it.seriesId }
        val page = feed().buildWebFeed(rows, page = 2, filterIds = blocked + "0000000000000000000")
        assertEquals(17, page.items.size)
        assertTrue("被滤剧集仍出现", page.items.none { it.seriesId in blocked })
        val src = rows.first { it.seriesId !in blocked }
        val mapped = page.items.first()
        assertEquals(src.seriesId, mapped.seriesId)
        assertEquals(src.tags, mapped.categories)
        assertEquals(src.remark, mapped.remark)
        assertEquals(src.episodeCount, mapped.episodeCount)
    }

    @Test
    fun `buildWebFeed - 翻页游标`() {
        val rows = feed().parseRankPage(fixture(1))
        val f = feed()
        assertTrue(f.buildWebFeed(rows, 1, emptyList()).hasMore)
        assertTrue(f.buildWebFeed(rows, 4, emptyList()).hasMore)
        val last = f.buildWebFeed(rows, 5, emptyList())
        assertFalse(last.hasMore)
        assertEquals(6, last.nextOffset)
    }
}
