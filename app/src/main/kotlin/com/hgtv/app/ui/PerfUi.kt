package com.hgtv.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

/** 封面请求：低端机限宽 ≤400px（SPEC §8 封面压缩）。 */
@Composable
fun rememberCoverRequest(model: String): ImageRequest {
    val context = LocalContext.current
    val maxPx = DevicePerf.profile.coverMaxPx
    return remember(model, maxPx) {
        ImageRequest.Builder(context)
            .data(model)
            .apply { if (maxPx != null) size(maxPx) }
            .build()
    }
}

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
            if (url.isNotEmpty()) {
                loader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .apply { if (maxPx != null) size(maxPx) }
                        .build(),
                )
            }
        }
    }
}
