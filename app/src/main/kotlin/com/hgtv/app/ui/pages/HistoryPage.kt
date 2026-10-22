package com.hgtv.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.Outline
import com.hgtv.app.ui.theme.Primary

/** 历史页（SPEC §3.5）：纯网格倒序无筛选；OK 直接续播（跳过详情页）。 */
@Composable
fun HistoryPage(
    history: List<WatchEntry>,
    historyReady: Boolean,
    onResume: (WatchEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("历史", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = OnBg)
            if (historyReady && history.isNotEmpty()) ClearButton(onClear)
        }
        when {
            !historyReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = Dim, fontSize = 16.sp)
            }
            history.isEmpty() -> HistoryEmpty()
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(history, key = { it.seriesId }) { entry ->
                        PosterCard(
                            title = entry.title,
                            badge = entry.episodeBadge,
                            cover = entry.cover,
                            modifier = Modifier.fillMaxWidth(),
                            progress = entry.progressFraction,
                            focusedScale = 1.06f, // DESIGN §3：网格卡片可到 1.06x
                            onClick = { onResume(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** 空态（DESIGN §6）：虚线描边框 + 居中提示 + 行动建议，不用插画。 */
@Composable
private fun HistoryEmpty() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .dashedOutline(RoundedCornerShape(12.dp))
                .padding(horizontal = 48.dp, vertical = 40.dp),
        ) {
            Text("暂无观看记录", color = OnBg, fontSize = 18.sp)
            Text("去首页或搜索找一部剧开始追吧", color = Dim, fontSize = 13.sp)
        }
    }
}

/** 虚线描边框（DESIGN §6 空态规范：outline 色圆角虚线）。仅支持统一圆角（调用点均 12dp）；内缩半笔宽防裁切。 */
private fun Modifier.dashedOutline(shape: RoundedCornerShape, strokeWidth: Dp = 1.dp): Modifier =
    drawBehind {
        val stroke = strokeWidth.toPx()
        val r = shape.topStart.toPx(size, this)
        drawRoundRect(
            color = Outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
    }

@Composable
private fun ClearButton(onClear: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .height(40.dp)
            .width(120.dp)
            .clip(shape)
            .background(if (focused) Primary else KeySurface, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClear),
        contentAlignment = Alignment.Center,
    ) {
        Text("清空历史", color = if (focused) Color.White else OnBgVariant, fontSize = 14.sp)
    }
}
