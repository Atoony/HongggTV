package com.hgtv.app

import com.hgtv.app.net.HongguoWeb
import com.hgtv.app.net.WebDrama
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 票06（SPEC §6.3）：季号解析 / 缺季补全（注入名称索引）/ 同系列相邻排序。 */
class SeasonLogicTest {

    private val web = HongguoWeb()

    private fun drama(id: String, title: String) = WebDrama(
        seriesId = id, title = title, cover = "", intro = "", episodeCnt = "80",
        remark = "", releaseStatus = "finished", categories = emptyList(), heat = "", score = "",
    )

    // ---------- seasonOf（特征固化） ----------

    @Test
    fun `标题季号解析 - 中文数字与阿拉伯数字与部`() {
        HongguoWeb.seasonOf("战神第二季").let { (b, s, u, l) ->
            assertEquals("战神", b); assertEquals(2, s); assertEquals("季", u); assertEquals("二", l)
        }
        assertEquals(3, HongguoWeb.seasonOf("重生第3季").season)
        assertEquals(1, HongguoWeb.seasonOf("先婚后爱第一部").season)
        assertEquals(0, HongguoWeb.seasonOf("无双神龙").season)
    }

    @Test
    fun `季号范围 1-200 之外视为无季`() {
        assertEquals(200, HongguoWeb.seasonOf("某剧第二百季").season)
        assertEquals(0, HongguoWeb.seasonOf("某剧第二百零一季").season)
        assertEquals(0, HongguoWeb.seasonOf("某剧第0季").season)
    }

    @Test
    fun `chineseSeason 数字转中文`() {
        assertEquals("二", HongguoWeb.chineseSeason(2))
        assertEquals("十", HongguoWeb.chineseSeason(10))
        assertEquals("二十一", HongguoWeb.chineseSeason(21))
        assertEquals("一百零五", HongguoWeb.chineseSeason(105))
    }

    // ---------- completeSeasons（注入名称索引） ----------

    @Test
    fun `缺季补全 - 构造标题加第X季补查并合入结果`() = runBlocking {
        val dramas = mutableListOf(drama("1000000000000000001", "战神第一季"), drama("1000000000000000003", "战神第三季"))
        var queried: String? = null
        val limited = web.completeSeasons("战神", dramas) { q ->
            queried = q
            if (q == "战神第二季") listOf(drama("1000000000000000002", "战神第二季")) else emptyList()
        }
        assertEquals(false, limited)
        assertEquals("战神第二季", queried)
        assertEquals(3, dramas.size)
        assertTrue(dramas.any { it.title == "战神第二季" })
    }

    @Test
    fun `缺季补全 - 请求预算 32 次封顶`() = runBlocking {
        // 季1与季30在列、缺 2..29：每次补查两个标签变体，候选远超 32 → 必须封顶
        val dramas = mutableListOf(drama("2000000000000000001", "某剧第一季"), drama("2000000000000000030", "某剧第三十季"))
        var calls = 0
        val limited = web.completeSeasons("某剧", dramas) { calls++; emptyList() }
        assertEquals(true, limited)
        assertEquals(32, calls)
    }

    @Test
    fun `关键词本身带季号时不做补全`() = runBlocking {
        val dramas = mutableListOf(drama("3000000000000000001", "战神第一季"), drama("3000000000000000003", "战神第三季"))
        var calls = 0
        web.completeSeasons("战神第二季", dramas) { calls++; emptyList() }
        assertEquals(0, calls)
    }

    // ---------- 同系列相邻排序 ----------

    @Test
    fun `同系列按季号升序相邻`() {
        val s1 = drama("1", "战神第一季"); val s2 = drama("2", "战神第二季"); val s3 = drama("3", "战神第三季")
        val other = drama("9", "别的剧")
        val sorted = web.sortSeasonsAdjacent(listOf(s3, other, s1, s2), "战神")
        val idx = sorted.map { it.seriesId }
        assertTrue("季1应在季2前", idx.indexOf("1") < idx.indexOf("2"))
        assertTrue("季2应在季3前", idx.indexOf("2") < idx.indexOf("3"))
        assertEquals("同系列应相邻", 2, idx.indexOf("3") - idx.indexOf("1"))
    }
}
