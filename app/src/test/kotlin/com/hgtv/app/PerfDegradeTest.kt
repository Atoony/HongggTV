package com.hgtv.app

import com.hgtv.app.perf.perfProfile
import com.hgtv.app.ui.BackExit
import com.hgtv.app.ui.CoverPrefetcher
import com.hgtv.app.ui.prefetchRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 票08 低端机降级四项 + 首页双击退出窗口（SPEC §4/§8）。 */
class PerfDegradeTest {

    @Test
    fun `低端机四项降级全开`() {
        val p = perfProfile(true)
        assertFalse("低端机关焦点缩放动画", p.focusScaleAnimation)
        assertEquals("预取 2–4 区间低端取 2", 2, p.prefetchAhead)
        assertEquals("封面压缩 ≤400px", 400, p.coverMaxPx)
        assertTrue("内存缓存上限收紧", p.memoryCachePercent < perfProfile(false).memoryCachePercent)
    }

    @Test
    fun `常规机不降级`() {
        val p = perfProfile(false)
        assertTrue(p.focusScaleAnimation)
        assertEquals(4, p.prefetchAhead)
        assertNull("常规机不限封面尺寸", p.coverMaxPx)
    }

    @Test
    fun `预取区间只往后取且不越界`() {
        assertEquals(1..2, prefetchRange(0, 5, 2))
        assertEquals(3..4, prefetchRange(2, 5, 2))
        assertEquals(IntRange.EMPTY, prefetchRange(4, 5, 2))
        assertEquals(IntRange.EMPTY, prefetchRange(0, 5, 0))
        assertEquals(IntRange.EMPTY, prefetchRange(0, 0, 4))
    }

    @Test
    fun `焦点触发预取且同卡去重`() {
        val enqueued = mutableListOf<String>()
        val p = CoverPrefetcher(2) { enqueued.add(it) }
        p.update(listOf("a", "b", "c", "d", "e"))
        p.onFocus(0)
        assertEquals(listOf("b", "c"), enqueued)
        p.onFocus(0) // 同一张卡重复聚焦不重复预取
        assertEquals(listOf("b", "c"), enqueued)
        p.onFocus(1)
        assertEquals(listOf("b", "c", "d"), enqueued)
        p.update(listOf("x", "y")) // 列表更新后按新表预取
        p.onFocus(0)
        assertEquals(listOf("b", "c", "d", "y"), enqueued)
    }

    @Test
    fun `双击退出窗口边界1500毫秒`() {
        assertTrue(BackExit.isExit(1500, 100))
        assertTrue(BackExit.isExit(1599, 100))
        assertFalse(BackExit.isExit(1600, 100))
        assertFalse(BackExit.isExit(1_700_000_000_000, 0)) // 首按只提示不退出
    }
}
