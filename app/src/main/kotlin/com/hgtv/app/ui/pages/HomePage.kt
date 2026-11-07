package com.hgtv.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.hgtv.app.data.OpenhongguoSource
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.domain.SeriesCard
import com.hgtv.app.ui.focusScale
import com.hgtv.app.ui.rememberCoverRequest
import com.hgtv.app.ui.rememberRowPrefetch
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.PosterPlaceholder
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.ProgressTrack
import com.hgtv.app.ui.theme.Scrim
import com.hgtv.app.ui.theme.SeasonGold
import com.hgtv.app.ui.theme.Surface

// wayfinder 票 04 验证过的样本剧（调试入口，保留在热播榜首）
const val DEBUG_SERIES_ID = "7688352674999176254"

/** 首页（SPEC §3.1）：继续观看 / 热播榜 两行，无 banner。
 *  2026-10-31 "为你推荐"行移除：票10 后与热播榜同源（web 榜单分页），两行头部完全重复。 */
@Composable
fun HomePage(
    source: OpenhongguoSource,
    history: List<WatchEntry>,
    historyReady: Boolean,
    active: Boolean,
    onOpenDetail: (String) -> Unit,
    onResume: (WatchEntry) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rankItems by remember { mutableStateOf<List<SeriesCard>?>(null) }
    var rankError by remember { mutableStateOf<String?>(null) }

    // 进页焦点落位（SPEC §3.1）：有历史落"继续观看"首卡，无历史落热播榜首卡；每会话仅一次
    val resumeFirstFocus = remember { FocusRequester() }
    val rankFirstFocus = remember { FocusRequester() }
    var focusPlaced by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            rankItems = source.hotRank()
        } catch (e: Exception) {
            android.util.Log.w("HgHome", "热播榜失败", e)
            rankError = e.message ?: "热播榜加载失败"
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (history.isNotEmpty()) {
            item { RowHeader("继续观看") }
            item {
                // 列表懒加载预取（SPEC §8）：焦点落到第 i 张卡时预热其后 ahead 张（低端 2 / 常规 4）
                val resumePrefetch = rememberRowPrefetch(history.map { it.cover })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(history, key = { _, it -> it.seriesId }) { index, entry ->
                        PosterCard(
                            title = entry.title,
                            badge = entry.episodeBadge,
                            cover = entry.cover,
                            progress = entry.progressFraction,
                            focusRequester = if (index == 0) resumeFirstFocus else null,
                            onFocused = { resumePrefetch.onFocus(index) },
                            onClick = { onResume(entry) },
                        )
                    }
                    item(key = "more") { MoreCard(onClick = onOpenHistory) }
                }
            }
        }
        item { RowHeader("热播榜") }
        item {
            when {
                rankItems == null && rankError == null -> RowHint("加载中…")
                rankError != null -> RowHint("热播榜加载失败：$rankError")
                else -> {
                    val rankList = rankItems.orEmpty()
                    // 调试入口（原推荐行首，2026-10-31 推荐行移除后挪到热播榜首）
                    val debugFirst = rankList.none { it.seriesId == DEBUG_SERIES_ID }
                    val rankPrefetch = rememberRowPrefetch(
                        (if (debugFirst) listOf("") else emptyList()) + rankList.map { it.cover }
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (debugFirst) {
                        item(key = "debug") {
                            PosterCard(title = "调试样本剧", badge = "调试", cover = "", focusRequester = rankFirstFocus, onClick = { onOpenDetail(DEBUG_SERIES_ID) })
                        }
                    }
                    itemsIndexed(rankList, key = { _, it -> "rank-" + it.seriesId }) { index, item ->
                        PosterCard(
                            title = item.title,
                            badge = "NO.${item.rank}",
                            cover = item.cover,
                            focusRequester = if (index == 0 && !debugFirst) rankFirstFocus else null,
                            onFocused = { rankPrefetch.onFocus(index + if (debugFirst) 1 else 0) },
                            onClick = { onOpenDetail(item.seriesId) },
                        )
                    }
                    }
                }
            }
        }
    }

    // 焦点落位在数据就绪后（热播榜首卡异步到达；LazyRow item 挂载略晚时短暂重试；
    // 仅在首页可见时执行，避免详情/播放覆盖层下抢焦点；成功才置位）
    LaunchedEffect(active, historyReady, history.size, rankItems?.size) {
        if (!active || !historyReady || focusPlaced) return@LaunchedEffect
        val target = when {
            history.isNotEmpty() -> resumeFirstFocus
            !rankItems.isNullOrEmpty() -> rankFirstFocus
            else -> return@LaunchedEffect
        }
        repeat(8) {
            if (runCatching { target.requestFocus() }.isSuccess) {
                focusPlaced = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
        }
    }
}

@Composable
private fun RowHeader(text: String) {
    Text(text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = OnBg)
}

@Composable
private fun RowHint(text: String) {
    Text(text, fontSize = 14.sp, color = Dim)
}

/** 海报卡（DESIGN §6）：9:16 海报、左下角标（scrim 底，季号 seasonGold）、底缘 3dp 进度条（progress 非空才画）。 */
@Composable
fun PosterCard(
    title: String,
    badge: String,
    cover: String,
    modifier: Modifier = Modifier.width(150.dp),
    progress: Float? = null,
    focusedScale: Float = 1.04f,
    seasonBadge: String = "",
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused, focusedScale)
    LaunchedEffect(focused) {
        if (focused) onFocused?.invoke()
    }
    Column(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            if (cover.isNotEmpty()) {
                AsyncImage(
                    model = rememberCoverRequest(cover),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(PosterPlaceholder),
                )
            } else {
                Box(Modifier.fillMaxSize().background(PosterPlaceholder))
            }
            if (badge.isNotEmpty() || seasonBadge.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (badge.isNotEmpty()) CardBadge(badge, OnBgVariant)
                    if (seasonBadge.isNotEmpty()) CardBadge(seasonBadge, SeasonGold)
                }
            }
            progress?.let { fraction ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(ProgressTrack))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(Primary))
            }
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Primary, RoundedCornerShape(5.dp)),
                )
            }
        }
        Text(
            title,
            color = OnBg,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CardBadge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Scrim)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

/** "更多›"卡：仅继续观看行尾，进历史页（SPEC §3.1）。 */
@Composable
private fun MoreCard(onClick: () -> Unit) {
    ActionCard(text = "更多›", onClick = onClick)
}

/** 行尾竖版动作卡：与海报卡同尺寸（9:16 / 150dp），文字居中。 */
@Composable
private fun ActionCard(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(9f / 16f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(2.dp, if (focused) Primary else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) OnBg else Dim, fontSize = 16.sp)
    }
}
