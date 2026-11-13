package com.hgtv.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.hgtv.app.domain.Danmaku
import com.hgtv.app.player.DanmakuEngine

/** 滚动区布局参数（帧循环算出、Canvas 消费；data class 结构等值写入不触发多余重组）。 */
private data class DanmakuLayout(
    val leftPx: Float,
    val topPx: Float,
    val laneHeightPx: Float,
    val widthPx: Float,
)

/**
 * 弹幕画布（票19 ③）：Compose Canvas 自绘白字深描边滚动弹幕（零新依赖，弃弹幕库——
 * SDK 数据无颜色/类型/发送者字段、密度低，库能力全用不上，why 见 CONTEXT.md 弹幕词条）。
 *
 * 滚动区 = 视频显示区上半屏（避让下方硬字幕）。竖屏短剧在横屏电视 RESIZE_MODE_FIT
 * 后是中央竖条，弹幕收在条内不漂黑边。调度全部在 [DanmakuEngine]（播放头纯函数）：
 * 暂停冻结 = 播放头不动不重绘；倍速跟随 = 播放头自己加速；拖动 = 引擎跳变重定位，
 * 全量已在本地零请求。0 条静默（PlayerScreen 不挂载）。
 */
@Composable
internal fun DanmakuCanvas(player: Player, items: List<Danmaku>, modifier: Modifier = Modifier) {
    // items 即换集信号（PlayerScreen 换集先置空再装新集），引擎随 items 重建、弃旧集
    val engine = remember(items) { DanmakuEngine().apply { reset(items, player.currentPosition) } }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var snapshots by remember { mutableStateOf(emptyList<DanmakuEngine.Snapshot>()) }
    var layout by remember { mutableStateOf<DanmakuLayout?>(null) }

    val density = LocalDensity.current
    val fontPx = with(density) { DanmakuFontSp.toPx() }
    val paints = remember(fontPx) { danmakuPaints(fontPx) }
    val baselineOffset = remember(paints) { paintBaselineOffset(paints.first) }

    // keys 含 engine（remember(items) 随换集重建）：调用方直接换一份非空列表也保证帧循环
    // 挂上新引擎，契约由结构保证而非仅注释（code-review 2026-11-13）
    LaunchedEffect(player, viewport, fontPx, engine) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        var lastHead = -1L
        while (true) {
            withFrameNanos { }
            val head = player.currentPosition
            if (head == lastHead) continue // 暂停冻结：播放头不动则不调度不重绘
            lastHead = head

            // 视频显示区（RESIZE_MODE_FIT 数学，与 PlayerView 同口径）：取 min 缩放居中
            val cw = viewport.width.toFloat()
            val ch = viewport.height.toFloat()
            val vs = player.videoSize
            val scale = if (vs.width > 0 && vs.height > 0) minOf(cw / vs.width, ch / vs.height) else 1f
            val dispW = if (vs.width > 0) vs.width * scale else cw
            val dispH = if (vs.height > 0) vs.height * scale else ch
            val left = (cw - dispW) / 2f
            // 滚动区：显示区上半，顶部留一口气
            val top = (ch - dispH) / 2f + with(density) { DanmakuTopPadDp.toPx() }
            val regionH = dispH / 2f
            val laneHeight = fontPx * 1.7f
            val laneCount = (regionH / laneHeight).toInt().coerceIn(1, DanmakuMaxLanes)

            layout = DanmakuLayout(left, top, laneHeight, dispW)
            engine.onFrame(head, dispW, laneCount) { paints.first.measureText(it) }
            snapshots = engine.snapshot(head, dispW)
        }
    }

    Canvas(modifier.onSizeChanged { viewport = it }) {
        val lay = layout ?: return@Canvas
        for (s in snapshots) {
            val x = lay.leftPx + s.xLeft
            if (x + s.widthPx < lay.leftPx) continue // 防御：完全出画不画
            val y = lay.topPx + s.lane * lay.laneHeightPx + baselineOffset
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.drawText(s.text, x, y, paints.first) // 先描边后填充
                nc.drawText(s.text, x, y, paints.second)
            }
        }
    }
}

// ── 绘制参数（模拟器实测口径，票19 拍板"票内定"）──────────────────────────────

/** 字号基线（≈时间码/按钮字号档，竖屏条内 20 字占满屏宽）。 */
private val DanmakuFontSp = 15.sp

/** 滚动区顶距（避画面上缘）。 */
private val DanmakuTopPadDp = 10.dp

/** 轨道数上限（密度 ≈1 条/13 秒，8 轨绰绰有余）。 */
private const val DanmakuMaxLanes = 8

/** 白字 + 深描边（描边宽 ≈ 字号 1/8，圆角连接消锯齿尖刺）。 */
private fun danmakuPaints(fontPx: Float): Pair<Paint, Paint> {
    val stroke = Paint().apply {
        isAntiAlias = true
        textSize = fontPx
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = fontPx / 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xB21A1A1A.toInt()
    }
    val fill = Paint(stroke).apply {
        style = Paint.Style.FILL
        color = 0xFFF2F2F2.toInt()
    }
    return stroke to fill
}

/** 轨道内文字垂直居中的基线偏移（标准 ascent/descent 公式；复用绘制 Paint 不再另建）。 */
private fun paintBaselineOffset(paint: Paint): Float {
    val fm = paint.fontMetrics
    return (paint.textSize * 1.7f - (fm.descent - fm.ascent)) / 2f - fm.ascent
}
