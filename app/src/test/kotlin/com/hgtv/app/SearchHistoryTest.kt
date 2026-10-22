package com.hgtv.app

import com.hgtv.app.ui.pages.SearchHistory
import org.junit.Assert.assertEquals
import org.junit.Test

/** 票06：搜索历史 LRU —— 去重提首、上限 10 条、空词忽略。 */
class SearchHistoryTest {

    @Test
    fun `新词进队首`() {
        assertEquals(listOf("战神", "总裁"), SearchHistory.reduce(listOf("总裁"), "战神"))
    }

    @Test
    fun `重复词去重并提到队首`() {
        assertEquals(
            listOf("总裁", "战神", "千金"),
            SearchHistory.reduce(listOf("总裁", "战神", "千金"), "总裁"),
        )
    }

    @Test
    fun `超过 10 条淘汰最旧`() {
        val old = (1..10).map { "词$it" }
        val out = SearchHistory.reduce(old, "新词")
        assertEquals(10, out.size)
        assertEquals("新词", out.first())
        assertEquals("词9", out.last())
        assertEquals(true, out.contains("词1"))
        assertEquals(false, out.contains("词10"))
    }

    @Test
    fun `空白词忽略`() {
        assertEquals(listOf("总裁"), SearchHistory.reduce(listOf("总裁"), "  "))
    }
}
