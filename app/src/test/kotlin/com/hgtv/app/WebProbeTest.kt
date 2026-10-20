package com.hgtv.app

import com.hgtv.app.net.HongguoWeb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Web 通道探针（票 06 验收）：联想词 / 名称索引 / 搜索全链。 */
class WebProbeTest {

    private fun live(): Boolean = System.getenv("HGTV_LIVE_PROBE") == "1"

    @Test
    fun `联想词 - 拼音前缀返回白名单候选`() = runBlocking {
        assumeTrue(live())
        val web = HongguoWeb()
        val words = web.suggestions("zongcai", 10)
        assertTrue("联想词为空", words.isNotEmpty())
        println("联想 OK：${words.take(5).joinToString(" / ") { it.name + "(" + it.type + ")" }}")
    }

    @Test
    fun `搜索全链 - 关键词返回剧集且字段完整`() = runBlocking {
        assumeTrue(live())
        val web = HongguoWeb()
        val result = web.search("总裁是我前女友")
        assertTrue("搜索结果为空", result.dramas.isNotEmpty())
        val first = result.dramas.first()
        assertTrue("seriesId 非数字: ${first.seriesId}", first.seriesId.matches(Regex("^\\d{10,25}$")))
        assertTrue("标题为空", first.title.isNotEmpty())
        println("搜索 OK：${result.dramas.size} 部，首部=${first.title} id=${first.seriesId} 集=${first.episodeCnt}")
    }

    @Test
    fun `热搜榜通道 - rank 页解析`() = runBlocking {
        assumeTrue(live())
        val api = com.hgtv.app.net.HongguoApi()
        val feed = com.hgtv.app.net.HongguoFeed(api)
        val rank = feed.hotDramaRank()
        assertTrue("热播榜为空", rank.isNotEmpty())
        assertTrue("rank 序号异常", rank.first().rank >= 1)
        println("热播榜 OK：${rank.size} 部，榜首=${rank.first().title} (#${rank.first().rank} ${rank.first().heat})")
    }
}
