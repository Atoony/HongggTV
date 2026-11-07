package com.hgtv.app

import com.hgtv.app.domain.SeriesCard
import com.hgtv.app.domain.Suggestion
import com.hgtv.app.ui.pages.episodeBadge
import com.hgtv.app.ui.pages.firstCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 票11 三列焦点纯逻辑 + 集数角标；票14 改咬 domain 垫层（热门词降级源测试随 hotWordsFromRank 退役）。 */
class SearchColumnsTest {

    private fun drama(title: String) = SeriesCard(seriesId = "1".repeat(16), title = title, cover = "")

    @Test
    fun `中列首焦 - 联想最优先`() {
        assertEquals(
            0,
            firstCandidateRow(
                suggestions = listOf(Suggestion("战神", "战神")),
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
    fun `角标 - 总集数带集字`() {
        assertEquals("987集", episodeBadge(drama("总裁").copy(episodeCount = 987)))
    }

    @Test
    fun `角标 - 集数未知返回空串`() {
        assertEquals("", episodeBadge(drama("无名")))
    }
}
