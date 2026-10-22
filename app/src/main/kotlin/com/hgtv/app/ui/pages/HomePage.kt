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
import androidx.compose.foundation.lazy.items
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
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.net.HongguoFeed
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.PosterPlaceholder
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.ProgressTrack
import com.hgtv.app.ui.theme.Scrim
import com.hgtv.app.ui.theme.SeasonGold
import com.hgtv.app.ui.theme.Surface

// wayfinder 票 04 验证过的样本剧（调试入口，保留在推荐行首）
const val DEBUG_SERIES_ID = "7688352674999176254"

/** 首页（SPEC §3.1）：继续观看 / 热播榜 / 为你推荐 三行，无 banner。 */
@Composable
fun HomePage(
    feed: HongguoFeed,
    history: List<WatchEntry>,
    historyReady: Boolean,
    active: Boolean,
    onOpenDetail: (String) -> Unit,
    onResume: (WatchEntry) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rankItems by remember { mutableStateOf<List<HongguoFeed.RankItem>?>(null) }
    var rankError by remember { mutableStateOf<String?>(null) }
    var feedItems by remember { mutableStateOf<List<HongguoFeed.FeedItem>>(emptyList()) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var feedHasMore by remember { mutableStateOf(false) }
    var feedOffset by remember { mutableStateOf(0) }
    var feedSession by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    val sessionId = remember { (1000000000000000000L..8999999999999999999L).random().toString() }

    // 列表懒加载预取（SPEC §8）：焦点落到第 i 张卡时预热其后 ahead 张（低端 2 / 常规 4）
    val resumePrefetch = com.hgtv.app.ui.rememberCoverPrefetcher()
    val rankPrefetch = com.hgtv.app.ui.rememberCoverPrefetcher()
    val feedPrefetch = com.hgtv.app.ui.rememberCoverPrefetcher()
    resumePrefetch.update(history.map { it.cover })

    // 进页焦点落位（SPEC §3.1）：有历史落"继续观看"首卡，无历史落推荐首卡；每会话仅一次
    val resumeFirstFocus = remember { FocusRequester() }
    val recommendFirstFocus = remember { FocusRequester() }
    var focusPlaced by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            rankItems = feed.hotDramaRank()
        } catch (e: Exception) {
            android.util.Log.w("HgHome", "热播榜失败", e)
            rankError = e.message ?: "热播榜加载失败"
        }
    }
    // 首拉等断点数据就绪，已看剧集进 filter_ids 去重（SPEC §6.1）
    LaunchedEffect(historyReady) {
        if (!historyReady) return@LaunchedEffect
        try {
            val page = feed.landPage(0, sessionId, history.map { it.seriesId })
            feedItems = page.items
            feedOffset = page.nextOffset
            feedSession = page.sessionId
            feedHasMore = page.hasMore
        } catch (e: Exception) {
            feedError = e.message
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
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val rankList = rankItems.orEmpty()
                    rankPrefetch.update(rankList.map { it.cover })
                    itemsIndexed(rankList, key = { _, it -> "rank-" + it.seriesId }) { index, item ->
                        PosterCard(
                            title = item.title,
                            badge = "NO.${item.rank}",
                            cover = item.cover,
                            onFocused = { rankPrefetch.onFocus(index) },
                            onClick = { onOpenDetail(item.seriesId) },
                        )
                    }
                }
            }
        }
        item { RowHeader("为你推荐") }
        item {
            when {
                !historyReady || (feedItems.isEmpty() && feedError == null) -> RowHint("加载中…")
                feedItems.isEmpty() && feedError != null -> RowHint("推荐加载失败：$feedError")
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val debugFirst = feedItems.none { it.seriesId == DEBUG_SERIES_ID }
                    if (debugFirst) {
                        item(key = "debug") {
                            PosterCard(title = "调试样本剧", badge = "调试", cover = "", onClick = { onOpenDetail(DEBUG_SERIES_ID) })
                        }
                    }
                    itemsIndexed(feedItems, key = { _, it -> it.seriesId }) { index, item ->
                        PosterCard(
                            title = item.title,
                            badge = buildString {
                                if (item.episodeCount.isNotEmpty()) append("${item.episodeCount}集")
                                if (item.remark.isNotEmpty()) append(" ${item.remark}")
                            }.trim(),
                            cover = item.cover,
                            focusRequester = if (index == 0) recommendFirstFocus else null,
                            onFocused = { feedPrefetch.onFocus(index) },
                            onClick = { onOpenDetail(item.seriesId) },
                        )
                    }
                    if (feedHasMore) {
                        item(key = "loadmore") {
                            LoadMoreCard(
                                loading = loadingMore,
                                onClick = {
                                    if (!loadingMore) {
                                        loadingMore = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 焦点落位在数据就绪后（推荐首卡异步到达；LazyRow item 挂载略晚时短暂重试；
    // 仅在首页可见时执行，避免详情/播放覆盖层下抢焦点；成功才置位）
    LaunchedEffect(active, historyReady, history.size, feedItems.size) {
        if (!active || !historyReady || focusPlaced) return@LaunchedEffect
        val target = when {
            history.isNotEmpty() -> resumeFirstFocus
            feedItems.isNotEmpty() -> recommendFirstFocus
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

    // 翻页加载（LoadMoreCard 点击触发）
    if (loadingMore && feedHasMore) {
        LaunchedEffect(feedOffset, loadingMore) {
            try {
                val watched = history.map { it.seriesId }
                val page = feed.landPage(feedOffset, feedSession.ifEmpty { sessionId }, watched + feedItems.map { it.seriesId })
                feedItems = (feedItems + page.items).distinctBy { it.seriesId }
                feedOffset = page.nextOffset
                feedSession = page.sessionId
                feedHasMore = page.hasMore
            } catch (_: Exception) {
                feedHasMore = false
            } finally {
                loadingMore = false
            }
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
    val scale = com.hgtv.app.ui.focusScale(focused, focusedScale)
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
                    model = com.hgtv.app.ui.rememberCoverRequest(cover),
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

@Composable
private fun LoadMoreCard(loading: Boolean, onClick: () -> Unit) {
    ActionCard(text = if (loading) "加载中…" else "加载更多", onClick = onClick)
}

/** 行尾竖版动作卡：与海报卡同尺寸（9:16 / 150dp），文字居中。 */
@Composable
private fun ActionCard(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = com.hgtv.app.ui.focusScale(focused)
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
