package com.hgtv.app

import com.hgtv.app.ui.pages.PinyinFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 票06（SPEC §3.2 拼音候选行）：本地全拼逐字候选 —— 联想接口不可用时的输入兜底。 */
class PinyinFallbackTest {

    @Test
    fun `完整音节串可切分并给出末字候选`() {
        assertEquals(listOf("zong", "cai"), PinyinFallback.splitSyllables("zongcai"))
        assertEquals(listOf("zhan", "shen"), PinyinFallback.splitSyllables("zhanshen"))
        val c = PinyinFallback.candidates("zongcai")
        assertTrue("应含「才/财」: $c", c.contains("才") || c.contains("财"))
    }

    @Test
    fun `末段不完整音节按前缀聚合候选`() {
        // "zongc" 末段 c 不成音节：聚合 c 开头高频音节（cai/ca...）的首选字
        val c = PinyinFallback.candidates("zongc")
        assertTrue("不应为空: $c", c.isNotEmpty())
        assertTrue("应含「才」: $c", c.contains("才"))
    }

    @Test
    fun `v 作 ü 排写 lv nv 可切分`() {
        assertEquals(listOf("lv", "shi"), PinyinFallback.splitSyllables("lvshi"))
        assertTrue(PinyinFallback.candidates("lvshi").isNotEmpty())
    }

    @Test
    fun `非法或空输入返回空`() {
        assertTrue(PinyinFallback.splitSyllables("vxq").isEmpty())
        assertTrue(PinyinFallback.candidates("").isEmpty())
        assertTrue(PinyinFallback.candidates("战神").isEmpty())
    }

    @Test
    fun `候选按优先序去重且不超过 8 个`() {
        val c = PinyinFallback.candidates("zongc")
        assertEquals(c.size, c.distinct().size)
        assertTrue(c.size <= 8)
    }

    @Test
    fun `pending 返回末尾待上屏音节`() {
        assertEquals("cai", PinyinFallback.pending("zongcai"))
        assertEquals("c", PinyinFallback.pending("zongc"))
        assertEquals("zh", PinyinFallback.pending("zh"))
        assertEquals(null, PinyinFallback.pending("战神"))
        assertEquals(null, PinyinFallback.pending(""))
        assertEquals("cai", PinyinFallback.pending("总cai")) // 逐字上屏后继续打字
    }
}
