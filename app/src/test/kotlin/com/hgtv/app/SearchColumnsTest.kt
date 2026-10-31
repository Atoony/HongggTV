package com.hgtv.app

import com.hgtv.app.net.HongguoFeed.RankItem
import com.hgtv.app.net.Suggestion
import com.hgtv.app.net.WebDrama
import com.hgtv.app.ui.pages.episodeBadge
import com.hgtv.app.ui.pages.firstCandidateRow
import com.hgtv.app.ui.pages.hotWordsFromRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 票11：三列焦点/热门词纯逻辑 —— 中列首焦优先级（联想>直达>拼音）+ 热播榜标题降级热门词。 */
class SearchColumnsTest {

    private fun drama(title: String) = WebDrama(
        seriesId = "1".repeat(16), title = title, cover = "", intro = "", episodeCnt = "",
        remark = "", releaseStatus = "", categories = emptyList(), heat = "", score = "",
    )

    private fun rank(title: String, r: Int) = RankItem(
        rank = r, seriesId = "1".repeat(16), title = title, cover = "", heat = "",
        tags = emptyList(), episodeCount = "", remark = "",
    )

    @Test
    fun `中列首焦 - 联想最优先`() {
        assertEquals(
            0,
            firstCandidateRow(
                suggestions = listOf(Suggestion("战神", "short_play_name")),
                nameHits = listOf(drama("战神归来")),
                pinyinCandidates = listOf("战"),
            ),
        )
    }

    @Test
    fun `中列首焦 - 无联想落直达`() {
        assertEquals(
            1,
            firstCandidateRow(emptyList(), listOf(drama("战神归来")), listOf("战")),
        )
    }

    @Test
    fun `中列首焦 - 仅拼音落拼音区`() {
        assertEquals(2, firstCandidateRow(emptyList(), emptyList(), listOf("战", "站")))
    }

    @Test
    fun `中列首焦 - 全空无候选`() {
        assertNull(firstCandidateRow(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `热门词 - 榜首标题保序截断`() {
        val words = hotWordsFromRank((1..12).map { rank("剧$it", it) }, max = 10)
        assertEquals((1..10).map { "剧$it" }, words)
    }

    @Test
    fun `热门词 - 同名去重`() {
        val words = hotWordsFromRank(listOf(rank("总裁", 1), rank("总裁", 2), rank("战神", 3)))
        assertEquals(listOf("总裁", "战神"), words)
    }

    @Test
    fun `热门词 - 空白标题滤除`() {
        val words = hotWordsFromRank(listOf(rank("  ", 1), rank("", 2), rank("总裁", 3)))
        assertEquals(listOf("总裁"), words)
    }

    @Test
    fun `角标 - episode_cnt 优先补集字`() {
        assertEquals("60集", episodeBadge(drama("总裁").copy(episodeCnt = "60", remark = "已完结")))
    }

    @Test
    fun `角标 - episode_cnt 空退 remark 不重复集字`() {
        assertEquals("全95集", episodeBadge(drama("战神").copy(remark = "全95集")))
    }

    @Test
    fun `角标 - 双空返回空串`() {
        assertEquals("", episodeBadge(drama("无名")))
    }
}
