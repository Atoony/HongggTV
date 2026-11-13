package com.hgtv.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.hgtv.app.perf.DevicePerf

/**
 * 票08 低端机降级的 UI 侧接点（SPEC §8）：焦点缩放 / 封面压缩 / 列表预取。
 * 全部读进程级 DevicePerf.profile，低端档：关缩放动画、封面 ≤400px、预取 2 张。
 */

/** 焦点缩放（DESIGN §2 焦点：1.04x/120ms）；低端机跳过动画直接落缩放值。 */
@Composable
fun focusScale(focused: Boolean, focusedScale: Float = 1.04f): Float {
    val animated by animateFloatAsState(
        if (focused) focusedScale else 1f,
        tween(120),
        label = "focusScale",
    )
    return if (DevicePerf.profile.focusScaleAnimation) animated else if (focused) focusedScale else 1f
}

/** 焦点浮起投影高度（dp）：深色底上拉层次，聚焦时随缩放一起生效。 */
private val FocusLiftShadow = 10.dp

/**
 * 焦点放大浮起（2026-11-13 拍板方案 A，替代裸 graphicsLayer 缩放）：
 * 缩放是绘制级、布局占位不变，放大溢出会被父级 Lazy* 的 clipToBounds 裁掉——封面
 * "只见中间"的根因；溢出完整显示由各页 Lazy 容器 contentPadding 预留（配套改动）。
 * 1.04x 每边溢出约 3dp，远小于卡片间距（8-16dp），不会压邻卡，故无需 zIndex 置顶——
 * 曾加 zIndex 盖邻，模拟器实测在「更多›」行尾卡上间歇性触发幽灵切页（一次 RIGHT 直跳
 * 历史页，2026-11-13 两轮铁证；复现不稳定，根因未深挖），收益为零风险为真，已移除。
 * 圆角投影提供焦点层次；低端机投影同缩放跳动画直落。
 */
@Composable
fun Modifier.focusLift(focused: Boolean, focusedScale: Float = 1.04f, shape: Shape): Modifier {
    val scale = focusScale(focused, focusedScale)
    val shadowTarget = if (focused) FocusLiftShadow else 0.dp
    val animatedShadow by animateDpAsState(shadowTarget, tween(120), label = "focusLiftShadow")
    val animated = DevicePerf.profile.focusScaleAnimation
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.shape = shape
            shadowElevation = (if (animated) animatedShadow else shadowTarget).toPx()
        }
}

/** 封面请求：低端机限宽 ≤400px（SPEC §8 封面压缩）。 */
@Composable
fun rememberCoverRequest(model: String): ImageRequest {
    val context = LocalContext.current
    val maxPx = DevicePerf.profile.coverMaxPx
    return remember(model, maxPx) { coverRequest(context, model, maxPx) }
}

private fun coverRequest(context: android.content.Context, url: String, maxPx: Int?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply { if (maxPx != null) size(maxPx) }
        .build()

/** 应预取的下标区间：焦点卡之后 ahead 张（列表懒加载预取 2–4）。 */
fun prefetchRange(index: Int, size: Int, ahead: Int): IntRange {
    if (ahead <= 0 || size <= 0) return IntRange.EMPTY
    return (index + 1)..minOf(index + ahead, size - 1)
}

/** 海报预取器：焦点落到第 i 张卡时预热其后 ahead 张；同卡重复聚焦与已预取 URL 不重复触发。 */
class CoverPrefetcher(private val ahead: Int, private val enqueue: (String) -> Unit) {
    private var covers = emptyList<String>()
    private var lastFocused = -1
    private val seen = HashSet<String>()

    fun update(covers: List<String>) {
        this.covers = covers
    }

    fun onFocus(index: Int) {
        if (index == lastFocused) return
        lastFocused = index
        prefetchRange(index, covers.size, ahead).forEach { i ->
            if (seen.add(covers[i])) enqueue(covers[i])
        }
    }
}

@Composable
fun rememberCoverPrefetcher(): CoverPrefetcher {
    val context = LocalContext.current
    val loader = context.imageLoader
    val ahead = DevicePerf.profile.prefetchAhead
    val maxPx = DevicePerf.profile.coverMaxPx
    return remember {
        CoverPrefetcher(ahead) { url ->
            if (url.isNotEmpty()) loader.enqueue(coverRequest(context, url, maxPx))
        }
    }
}

/** 行级预取接线：内部完成 update，杜绝"建了 onFocus 却忘了 update"（评审抓出的推荐行 no-op）。 */
@Composable
fun rememberRowPrefetch(covers: List<String>): CoverPrefetcher {
    val prefetcher = rememberCoverPrefetcher()
    SideEffect { prefetcher.update(covers) }
    return prefetcher
}
