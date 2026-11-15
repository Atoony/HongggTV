package com.hgtv.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.hgtv.app.perf.DevicePerf
import com.hgtv.app.ui.theme.Primary

/**
 * 票08 低端机降级的 UI 侧接点（SPEC §8）：焦点缩放 / 封面压缩 / 列表预取。
 * 全部读进程级 DevicePerf.profile，低端档：关缩放动画、封面 ≤400px、预取 2 张。
 * 票20 质改：焦点视觉统一升 v2 分层辉光（DESIGN §3），全 App 走 focusGlow/keyGlow。
 */

/** 焦点动效曲线（DESIGN §3）：180ms ease-out-quart。 */
val FocusEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
const val FocusDurationMs = 180

/** 焦点辉光最大半径（DESIGN §3 令牌 focusGlowRadius）。 */
val FocusGlowRadius = 14.dp

/** 焦点浮起投影高度（dp）：深色底上拉层次，聚焦时随缩放一起生效。 */
private val FocusLiftShadow = 12.dp

/** 辉光三圈（半径 @ 透明度）：4dp@22% / 8dp@12% / 14dp@6%（DESIGN §3）。 */
private val GlowRings = listOf(4.dp to 0.22f, 8.dp to 0.12f, 14.dp to 0.06f)

/** 焦点缩放（DESIGN §3 v2：1.07x/180ms ease-out-quart）；低端机跳过动画直接落缩放值。 */
@Composable
fun focusScale(focused: Boolean, focusedScale: Float = 1.07f): Float {
    val animated by animateFloatAsState(
        if (focused) focusedScale else 1f,
        tween(FocusDurationMs, easing = FocusEasing),
        label = "focusScale",
    )
    return if (DevicePerf.profile.focusScaleAnimation) animated else if (focused) focusedScale else 1f
}

/** 辉光/投影的进场进度（0→1 随焦点）：低端档直接跳变。 */
@Composable
private fun glowProgress(focused: Boolean): Float {
    val animated by animateFloatAsState(
        if (focused) 1f else 0f,
        tween(FocusDurationMs, easing = FocusEasing),
        label = "glowProgress",
    )
    return if (DevicePerf.profile.focusScaleAnimation) animated else if (focused) 1f else 0f
}

/**
 * 焦点辉光描边（DESIGN §3 v2 核心，drawBehind 画笔实现——32 位真机铁律禁 blur/RenderEffect）：
 * 2.5dp [borderColor] 描边 + 三圈 Primary 辉光（半径/透明度乘 [progress]）跟随组件圆角。
 * 低端门：调用方传 progress=0 时零辉光只留描边跳变。
 */
private fun Modifier.glowStrokes(
    progress: Float,
    showBorder: Boolean,
    borderColor: Color,
    borderWidth: Dp,
    shape: Shape,
    ringColor: Color = Primary,
    rings: List<Pair<Dp, Float>> = GlowRings,
    scale: Float = 1f,
): Modifier = drawBehind {
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    // scale 补偿：drawBehind 挂 graphicsLayer 外层时描边/辉光不随缩放变换。放大后视觉边缘
    // 在布局边缘【外】侧（每边外溢 ew/eh，正值），描边/辉光几何需同向外扩对齐视觉边缘。
    // ⚠️ 方向别写反：写成内缩会把描边画进组件本体，被后画的不透明背景整圈盖掉
    // （2026-11-15 模拟器像素级实证：白描边 0 命中+logcat p=1/border=true 并存）
    val ew = size.width * (scale - 1f) / 2f
    val eh = size.height * (scale - 1f) / 2f
    if (progress > 0.01f) {
        // 大圈先画小圈压上（亮压暗）；圈在视觉边缘外扩半径处，圆角同步外扩
        for ((radiusDp, alpha) in rings.asReversed()) {
            val r = radiusDp.toPx() * progress
            drawRoundRect(
                color = ringColor.copy(alpha = alpha * progress),
                topLeft = Offset(-ew - r / 2f, -eh - r / 2f),
                size = Size(size.width + ew * 2f + r, size.height + eh * 2f + r),
                cornerRadius = CornerRadius(corner + r / 2f, corner + r / 2f),
                style = Stroke(width = r),
            )
        }
    }
    if (showBorder) {
        // 描边整圈画在视觉边缘之外（rect 外扩 w/2，圆角同步）：旧版缩进 w/2 的内半圈会被
        // focusGlow 之后画的不透明背景（hero 墨渐变等）盖掉；画序放辉光圈后不被橙圈叠染
        val w = borderWidth.toPx()
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(-ew - w / 2f, -eh - w / 2f),
            size = Size(size.width + ew * 2f + w, size.height + eh * 2f + w),
            cornerRadius = CornerRadius(corner + w / 2f, corner + w / 2f),
            style = Stroke(width = w),
        )
    }
}

/**
 * 焦点辉光描边（DESIGN §3 v2）：仅描边 + 三圈辉光，无缩放/投影——给自带生长态的
 * 控件（播放器进度条）用；常规可聚焦件一律走 focusGlow。
 */
@Composable
fun Modifier.glowBorder(
    focused: Boolean,
    shape: Shape,
    borderColor: Color = Primary,
    borderWidth: Dp = 2.5.dp,
    showBorder: Boolean = focused,
    ringColor: Color = Primary,
    scale: Float = 1f,
): Modifier {
    val progress = if (DevicePerf.profile.focusScaleAnimation) glowProgress(focused) else 0f
    return glowStrokes(progress, showBorder, borderColor, borderWidth, shape, ringColor, scale = scale)
}

/**
 * 焦点辉光完整件（DESIGN §3 v2）：缩放 1.07x + 投影 12dp + 三圈辉光 + 2.5dp 描边，
 * 全 App 可聚焦组件统一走这里，不各写各的。低端档（focusScaleAnimation=false）：
 * 辉光/投影/动画全关，只留描边 + 缩放跳变（§3 低端门）。
 * [showBorder] 供"选中态也带描边"的场景（侧栏导航项）传 focused||selected。
 */
@Composable
fun Modifier.focusGlow(
    focused: Boolean,
    shape: Shape,
    focusedScale: Float = 1.07f,
    borderColor: Color = Primary,
    borderWidth: Dp = 2.5.dp,
    showBorder: Boolean = focused,
    ringColor: Color = Primary,
): Modifier {
    val scale = focusScale(focused, focusedScale)
    val animated = DevicePerf.profile.focusScaleAnimation
    val shadowTarget = if (focused) FocusLiftShadow else 0.dp
    val animatedShadow by animateDpAsState(
        shadowTarget,
        tween(FocusDurationMs, easing = FocusEasing),
        label = "focusGlowShadow",
    )
    // ⚠️ 链序铁律（2026-11-15 模拟器像素级实证）：glowBorder 必须在 graphicsLayer 之外
    // （前挂）。GraphicsLayerScope.shape 在 shadowElevation>0 时同时用于【裁剪 layer 内容】
    // （Compose 官方语义）——drawBehind 挂 layer 内层时，聚焦投影一亮，外扩描边+辉光整层
    // 被裁到 shape 边界（内半圈再被不透明背景盖）＝聚焦视觉全灭。挂外层后不随缩放变换，
    // 由 glowStrokes 的 scale 参数补偿几何对齐放大后的视觉边缘。
    return this
        .glowBorder(focused, shape, borderColor, borderWidth, showBorder, ringColor, scale = scale)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.shape = shape
            if (animated) shadowElevation = animatedShadow.toPx()
        }
}

/**
 * focusGlow 的内容段（graphicsLayer 拆挂，2026-11-15 真机 BRAVIA 帧级实锤）：
 * 旧 [focusGlow] 把 graphicsLayer(scale) 挂 clickable 外层，focusTarget 的 bounds 上行
 * 被 scale 放大，bringIntoView 拿「含缩放的 bounds」连环请求滚动——Lazy 容器边缘（首页末行
 * 热播榜贴滚动底）左右选卡时整页 ±4px 往复抖动。拆挂链序（懒加载行内组件统一）：
 * `.glowBorder(描边,最外防裁剪) → .clickable(focusTarget,不进 scale) → .focusScaleLayer(本段)`
 * scale 只变换 clickable 内容，视觉与旧版一致，焦点/无障碍 bounds 恒为布局几何。
 */
@Composable
fun Modifier.focusScaleLayer(
    focused: Boolean,
    shape: Shape,
    focusedScale: Float = 1.07f,
): Modifier {
    val scale = focusScale(focused, focusedScale)
    val animated = DevicePerf.profile.focusScaleAnimation
    val animatedShadow by animateDpAsState(
        if (focused) FocusLiftShadow else 0.dp,
        tween(FocusDurationMs, easing = FocusEasing),
        label = "focusGlowShadow",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.shape = shape
        if (animated) shadowElevation = animatedShadow.toPx()
    }
}

/**
 * 键盘焦点键轻辉光（DESIGN §3：键面 primary + 白字白描边不变，另加 8dp 白辉光）。
 * 键盘键网密排无缩放；辉光同 180ms 曲线，低端档跳变。
 * 几何复用 glowStrokes（边缘外描边 + 辉光圈），只是白描边 2dp + 单圈白辉光。
 */
@Composable
fun Modifier.keyGlow(focused: Boolean, shape: Shape): Modifier {
    val progress = if (DevicePerf.profile.focusScaleAnimation) glowProgress(focused) else 0f
    return glowStrokes(
        progress = progress,
        showBorder = focused,
        borderColor = Color.White,
        borderWidth = 2.dp,
        shape = shape,
        ringColor = Color.White,
        rings = listOf(8.dp to 0.20f),
    )
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
