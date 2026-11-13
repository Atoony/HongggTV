package com.hgtv.app.player

import com.hgtv.app.domain.Danmaku

/**
 * 弹幕调度引擎（票19 ③）：只做"哪条弹幕何时入哪条轨道"的纯逻辑，位置与绘制归
 * DanmakuCanvas。条目位置是播放头的纯函数——暂停冻结、倍速跟随天然成立；拖动
 * 跳变触发本地重定位（全量已在本地，零请求，票19 拍板 Q3-A/Q4-A）。
 *
 * 速度全轨统一（viewportW/crossMs），条目间永无追尾；同轨安全间距按"尾条右缘已
 * 让出 gap"判定，无空轨则丢弃该条（票19 探针实测 ≈1 条/13 秒的低密度，极少发生）。
 */
internal class DanmakuEngine(
    private val crossMs: Long = DANMAKU_CROSS_MS,
    private val laneGapPx: Float = DANMAKU_LANE_GAP_PX,
    private val seekJumpMs: Long = DANMAKU_SEEK_JUMP_MS,
) {

    /** 已入轨弹幕（调度态；位置由播放头现算，不落盘）。 */
    data class Active(val text: String, val lane: Int, val spawnMs: Long, val widthPx: Float)

    /** 绘制快照元素。 */
    data class Snapshot(val text: String, val lane: Int, val xLeft: Float, val widthPx: Float)

    /** 已按 offsetMs 升序（collectDanmaku 输出序）。 */
    private var items: List<Danmaku> = emptyList()

    /** items 的 spawn 指针（单调推进，防同条重复入轨）。 */
    private var nextSpawn = 0

    private val actives = ArrayList<Active>()

    /** 每轨最后入轨的条目（同轨安全间距判定）。 */
    private lateinit var laneTail: Array<Active?>

    private var lastHeadMs = -1L

    /** 换集/重拉数据：弃活动条目，指针对齐到 [playheadMs] 之后的首条（跳过的当已过场，不补放）。 */
    fun reset(items: List<Danmaku>, playheadMs: Long) {
        this.items = items
        nextSpawn = items.indexOfFirst { it.offsetMs >= playheadMs }.takeIf { it >= 0 } ?: items.size
        actives.clear()
        // 尾条一并弃：残留的 spawnMs 大于回拖后的播放头会让 freeLane 算出负 advanced
        // 误判全轨占用，后续到点弹幕被静默丢弃（code-review 2026-11-13）
        if (::laneTail.isInitialized) java.util.Arrays.fill(laneTail, null)
        lastHeadMs = playheadMs
    }

    /**
     * 每帧喂播放头：检测拖动重定位、淘汰离场条目、把 offsetMs 已到的条目入轨。
     * [textWidth] 量文本像素宽（绘制侧的 Paint），[laneCount]/[viewportW] 定轨道与速度。
     */
    fun onFrame(playheadMs: Long, viewportW: Float, laneCount: Int, textWidth: (String) -> Float) {
        if (viewportW <= 0f || laneCount <= 0) return
        if (lastHeadMs >= 0 && kotlin.math.abs(playheadMs - lastHeadMs) > seekJumpMs) {
            reset(items, playheadMs) // 拖动（前跳/后跳统一重定位）
        }
        lastHeadMs = playheadMs
        ensureLanes(laneCount)

        val speed = viewportW / crossMs // px per ms，全轨统一
        // 离场淘汰：xLeft = W - (t - spawn)*speed < -width ⟺ (t - spawn)*speed > W + width
        val iter = actives.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            if ((playheadMs - a.spawnMs) * speed > viewportW + a.widthPx) iter.remove()
        }
        // 入轨：帧粒度推进指针（条目实际 spawn 时刻记 offsetMs，绘制零帧误差）
        while (nextSpawn < items.size && items[nextSpawn].offsetMs <= playheadMs) {
            val d = items[nextSpawn++]
            val w = textWidth(d.text)
            val lane = freeLane(playheadMs, w, speed, laneCount)
            if (lane >= 0) {
                val a = Active(d.text, lane, d.offsetMs, w)
                actives.add(a)
                laneTail[lane] = a
            }
        }
    }

    /** 绘制快照：xLeft 由播放头现算（暂停即冻结）。 */
    fun snapshot(playheadMs: Long, viewportW: Float): List<Snapshot> {
        if (actives.isEmpty() || viewportW <= 0f) return emptyList()
        val speed = viewportW / crossMs
        return actives.map { Snapshot(it.text, it.lane, viewportW - (playheadMs - it.spawnMs) * speed, it.widthPx) }
    }

    /** 同轨安全：尾条右缘 W - (t - spawn)*speed + width ≤ W - gap。无空轨返回 -1（丢弃）。 */
    private fun freeLane(playheadMs: Long, newWidth: Float, speed: Float, laneCount: Int): Int {
        for (lane in 0 until laneCount) {
            val tail = laneTail[lane] ?: return lane
            val advanced = (playheadMs - tail.spawnMs) * speed
            if (advanced >= tail.widthPx + laneGapPx) return lane
        }
        return -1
    }

    private fun ensureLanes(laneCount: Int) {
        if (!::laneTail.isInitialized || laneTail.size != laneCount) {
            laneTail = arrayOfNulls(laneCount)
            // 轨道数变化（尺寸/字号变动）：活动条目轨道索引可能越界，一并重定位
            actives.clear()
        }
    }

    companion object {
        /** 横穿一屏宽的基线时长（模拟器实测口径调参：低密度下取慢飘更有氛围）。 */
        internal const val DANMAKU_CROSS_MS = 8_000L

        /** 同轨相邻两条的最小像素间隙。 */
        internal const val DANMAKU_LANE_GAP_PX = 16f

        /** 播放头跳变阈值：超过视为拖动（正常帧推进 ≤2x 速下每 16ms 约 33ms）。 */
        internal const val DANMAKU_SEEK_JUMP_MS = 500L
    }
}
