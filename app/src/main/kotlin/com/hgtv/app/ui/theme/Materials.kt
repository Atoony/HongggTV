package com.hgtv.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp

/**
 * v2 分层材质基建（DESIGN.md §1/§2/§6，票20）：页面底渐变 + 氛围辉光 + 骨架屏。
 * 全是画笔级绘制（零布局开销、无 blur），32 位真机全开（§8 性能底线）。
 */

/** 页面底（§2.1 bgGradient + ambientGlow）：纵向 BgTop→BgBottom + 顶部 Primary@7% 径向氛围。
 *  内容区与整页覆盖层统一消费；播放器/嵌入面板仍用纯色 Bg 兜底。 */
fun Modifier.hgPageBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(BgTop, BgBottom), startY = 0f, endY = size.height))
    drawRect(
        Brush.radialGradient(
            colors = listOf(Primary.copy(alpha = 0.07f), Color.Transparent),
            center = Offset(size.width * 0.5f, -size.height * 0.1f),
            radius = size.height * 0.6f,
        ),
    )
}

/** 侧栏底（§2.1 sideGradient）。 */
fun Modifier.sideBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(SideTop, SideBottom), startY = 0f, endY = size.height))
}

/** inkStops 品牌墨渐变底（§2.3：对角三停橙金墨）。只许三处：侧栏 HG 字标 / hero 主按钮 / 错误页主按钮。 */
fun Modifier.inkBackground(): Modifier = background(Brush.linearGradient(InkStops))

/** 骨架扫光周期（§7：1300ms linear 无限）。 */
const val SKELETON_PERIOD_MS = 1300

/** 骨架块扫光相位（0..1 循环）。 */
@Composable
private fun shimmerPhase(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PERIOD_MS, easing = LinearEasing)),
        label = "shimmerPhase",
    )
    return phase
}

/** 骨架群共享扫光相位：骨架模板根用 [ShimmerScope] 下发一次，块群共享单时钟
 *  （评审 finding：每块各建 infiniteTransition，首页骨架期同屏 ~21 个并发动画协程，
 *  低端 32 位真机骨架期掉帧）。 */
val LocalShimmerPhase = compositionLocalOf<Float?> { null }

/** 骨架模板根包裹器：内部所有 SkeletonBlock 共享一个扫光时钟。 */
@Composable
fun ShimmerScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShimmerPhase provides shimmerPhase(), content = content)
}

/**
 * 骨架块（§6 骨架屏）：skeletonBase 底 + skeletonHi 扫光带（20% 宽，1300ms linear 无限）。
 * 模板与目标布局同构由调用方给 [modifier] 尺寸保证；内容到达原位无缝替换。
 * 画笔级动画（§8），低端档同样开启。
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val phase = LocalShimmerPhase.current ?: shimmerPhase()
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                drawRect(color = SkeletonBase)
                val band = size.width * 0.2f
                val x = -band + phase * (size.width + 2 * band)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, SkeletonHi, Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + band, 0f),
                        tileMode = TileMode.Clamp,
                    ),
                )
            },
    )
}
