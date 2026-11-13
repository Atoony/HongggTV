package com.hgtv.app

import com.hgtv.app.domain.Danmaku
import com.hgtv.app.player.DanmakuEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 票19 ③：弹幕调度谓词（纯逻辑离线测）。坐标约定：viewportW=100px、crossMs=8000
 * → speed=0.0125px/ms；文本宽恒 50px（textWidth stub），gap=16px → 同轨放行阈值
 * advanced ≥ 66px（6280ms）。形态事实见票19 探针纪要（低密度 ≈1 条/13 秒）。
 */
class DanmakuEngineTest {

    private val w = 100f
    private val laneCount = 4
    private fun engine(items: List<Danmaku>, head: Long = 0L) =
        DanmakuEngine().apply { reset(items, head) }

    private fun frame(e: DanmakuEngine, head: Long) = e.onFrame(head, w, laneCount) { 50f }

    /** 逐帧步进（真实帧循环每帧推进 ≤33ms，单步大跳会被正当判为拖动重定位）。 */
    private fun advance(e: DanmakuEngine, from: Long, to: Long, stepMs: Long = 100L) {
        var h = from
        while (h < to) {
            h = minOf(h + stepMs, to)
            frame(e, h)
        }
    }

    @Test
    fun `到点入轨 位置随播放头左移`() {
        val e = engine(listOf(Danmaku("A", 1_000)))
        frame(e, 999)
        assertTrue("未到点不入轨", e.snapshot(999, w).isEmpty())
        frame(e, 1_000)
        assertEquals(listOf("A"), e.snapshot(1_000, w).map { it.text })
        assertEquals(w, e.snapshot(1_000, w).first().xLeft, 0f) // 入轨瞬间贴右缘
        advance(e, 1_000, 1_800) // 前进 800ms → 10px
        assertEquals(w - 10f, e.snapshot(1_800, w).first().xLeft, 0.001f)
    }

    @Test
    fun `同轨防重叠 分轨承载 轨道满丢弃`() {
        val e = engine(listOf(Danmaku("A", 1_000), Danmaku("B", 1_000), Danmaku("C", 1_000)))
        frame(e, 1_000)
        // 4 轨：A/B/C 各占一轨（同帧同点，advanced=0 < 66 全冲突）
        assertEquals(listOf("A" to 0, "B" to 1, "C" to 2), e.snapshot(1_000, w).map { it.text to it.lane })
        // 5 条同点、4 轨：第 5 条丢弃不崩溃
        val e2 = engine((1..5).map { Danmaku("D$it", 1_000) })
        frame(e2, 1_000)
        assertEquals(4, e2.snapshot(1_000, w).size)
    }

    @Test
    fun `尾条让出间隙后同轨放行`() {
        val e = engine(listOf(Danmaku("A", 1_000), Danmaku("B", 6_300)))
        frame(e, 1_000)
        frame(e, 6_300) // (6300-1000)*0.0125=66px ≥ 50+16 → A 所在轨已放行
        val lanes = e.snapshot(6_300, w).map { it.text to it.lane }
        assertEquals("B 应与 A 同轨", 0, lanes.first { it.first == "B" }.second)
    }

    @Test
    fun `暂停冻结 同头重帧不重放不位移`() {
        val e = engine(listOf(Danmaku("A", 1_000)))
        frame(e, 1_000)
        val before = e.snapshot(1_000, w)
        repeat(3) { frame(e, 1_000) } // 暂停：播放头不动
        assertEquals(before, e.snapshot(1_000, w))
    }

    @Test
    fun `拖动前跳重定位 已过条目不补放`() {
        val e = engine(listOf(Danmaku("A", 1_000), Danmaku("C", 31_000)))
        frame(e, 1_000)
        assertTrue(e.snapshot(1_000, w).isNotEmpty())
        frame(e, 30_000) // 前跳 >500ms → 重定位：活动清空、指针跳到 ≥30000
        assertTrue("跳变后活动条目应清空", e.snapshot(30_000, w).isEmpty())
        frame(e, 31_000)
        assertEquals(listOf("C"), e.snapshot(31_000, w).map { it.text }) // A 已过场不补放
    }

    @Test
    fun `拖动后跳重定位 条目可重新入轨`() {
        val e = engine(listOf(Danmaku("A", 1_000)))
        frame(e, 30_000) // 从中段起播：A 已过，无
        assertTrue(e.snapshot(30_000, w).isEmpty())
        frame(e, 500) // 回拖：|500-30000|>500 → 重定位回 500，指针回 0
        frame(e, 1_000)
        assertEquals(listOf("A"), e.snapshot(1_000, w).map { it.text })
    }

    @Test
    fun `已入轨后回拖 残留尾条不吞后续弹幕`() {
        // code-review 2026-11-13：reset 曾不清 laneTail，回拖后尾条 spawnMs > 新播放头
        // → freeLane 负 advanced 误判全轨占用，重新播放到该时刻的弹幕被静默丢弃
        val four = listOf(Danmaku("P", 58_000), Danmaku("Q", 58_000), Danmaku("R", 58_000), Danmaku("S", 58_000))
        val e = engine(listOf(Danmaku("A", 1_000)) + four)
        advance(e, 1_000, 58_000) // A 入轨离场；P/Q/R/S 同刻分满 4 轨
        assertEquals(4, e.snapshot(58_000, w).size)
        frame(e, 50_000) // 回拖 8s → 重定位：活动清空、指针对齐 ≥50000（P 在内）
        advance(e, 50_000, 58_000)
        assertEquals("回拖后重播到 58s，四条应全部重新入轨", 4, e.snapshot(58_000, w).size)
    }

    @Test
    fun `换集 reset 弃旧集`() {
        val e = engine(listOf(Danmaku("旧集", 1_000)))
        frame(e, 1_000)
        assertTrue(e.snapshot(1_000, w).isNotEmpty())
        e.reset(listOf(Danmaku("新集", 2_000)), 0L)
        assertTrue("reset 后旧集活动条目应清空", e.snapshot(1_000, w).isEmpty())
        frame(e, 2_000)
        assertEquals(listOf("新集"), e.snapshot(2_000, w).map { it.text })
    }

    @Test
    fun `横穿离场后淘汰`() {
        val e = engine(listOf(Danmaku("A", 1_000)))
        frame(e, 1_000)
        advance(e, 1_000, 12_000) // (11000)*0.0125=137.5px < W+w=150 仍在
        assertEquals(1, e.snapshot(12_000, w).size)
        advance(e, 12_000, 13_100) // 151.25px > 150 → 完全出画淘汰
        assertTrue("完全横穿后应淘汰", e.snapshot(13_100, w).isEmpty())
    }

    @Test
    fun `倍速跟随由播放头驱动 引擎无涉`() {
        // 倍速下播放头前进更快，引擎只认播放头：播放头到哪位移就到哪（帧循环喂 head 即可）
        val e = engine(listOf(Danmaku("A", 1_000)))
        frame(e, 1_000)
        advance(e, 1_000, 2_600) // 播放头推进 1.6s（2x 下 0.8s 墙钟走完）→ 20px
        assertEquals(w - 20f, e.snapshot(2_600, w).first().xLeft, 0.001f)
    }
}
