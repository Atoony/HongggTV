package com.hgtv.app

import com.hgtv.app.net.HongguoWeb
import com.hgtv.app.player.StreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网页兜底线路自检（离线，夹具取自真实播放页 seriesDetail 片段）。 */
class WebPlayerDetailTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResource("web_player_fragment.html")!!.readText()

    @Test
    fun `播放页解析出全集 vid_list 与元数据`() {
        val d = HongguoWeb().parsePlayerDetail(fixture())
        assertEquals("7694653385668037657", d.seriesId)
        assertTrue(d.title.isNotEmpty())
        assertEquals(86, d.episodeCnt)
        assertEquals(86, d.episodes.size)
        assertEquals("7694655440692448281", d.episodes.first().videoId)
        d.episodes.forEachIndexed { i, ep -> assertEquals(i + 1, ep.index) }
        assertTrue(d.remark.isNotEmpty())
        assertTrue(d.cover.startsWith("http"))
    }

    @Test
    fun `无 seriesDetail 的页面抛异常`() {
        var thrown = false
        try {
            HongguoWeb().parsePlayerDetail("<html>nothing here</html>")
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `网页兜底线路 URL 形状`() {
        val opt = StreamRepository.webFallbackOption("7694655440692448281")
        assertEquals("https://hongguoduanju.com/video/public/7694655440692448281/360p.mp4", opt.urls.single())
        assertEquals(360, opt.quality)
        assertEquals(null, opt.cencKey)
    }
}
