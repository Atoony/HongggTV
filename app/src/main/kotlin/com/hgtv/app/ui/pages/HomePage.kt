package com.hgtv.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.hgtv.app.ui.theme.Surface

// wayfinder 票 04 验证过的样本剧（调试入口，保留在推荐行首）
const val DEBUG_SERIES_ID = "7688352674999176254"

/** 首页（SPEC §3.1）：继续观看 / 热播榜 / 为你推荐 三行。 */
@Composable
fun HomePage(
    feed: HongguoFeed,
    history: List<WatchEntry>,
    onOpenDetail: (String) -> Unit,
    onResume: (WatchEntry) -> Unit,
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

    LaunchedEffect(Unit) {
        try {
            rankItems = feed.hotDramaRank()
        } catch (e: Exception) {
            android.util.Log.w("HgHome", "热播榜失败", e)
            rankError = e.message ?: "热播榜加载失败"
        }
    }
    LaunchedEffect(Unit) {
        try {
            val page = feed.landPage(0, sessionId, emptyList())
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
                    items(history, key = { it.seriesId }) { entry ->
                        PosterCard(
                            title = entry.title,
                            badge = "看到第${entry.episode}集",
                            cover = entry.cover,
                            onClick = { onResume(entry) },
                        )
                    }
                }
            }
        }
        item { RowHeader("热播榜") }
        item {
            when {
                rankItems == null && rankError == null -> RowHint("加载中…")
                rankError != null -> RowHint("热播榜加载失败：$rankError")
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(rankItems.orEmpty(), key = { "rank-" + it.seriesId }) { item ->
                        PosterCard(
                            title = item.title,
                            badge = "#${item.rank} ${item.heat}".trim(),
                            cover = item.cover,
                            onClick = { onOpenDetail(item.seriesId) },
                        )
                    }
                }
            }
        }
        item { RowHeader("为你推荐") }
        item {
            when {
                feedItems.isEmpty() && feedError == null -> RowHint("加载中…")
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
                    items(feedItems, key = { it.seriesId }) { item ->
                        PosterCard(
                            title = item.title,
                            badge = buildString {
                                if (item.episodeCount.isNotEmpty()) append("${item.episodeCount}集")
                                if (item.remark.isNotEmpty()) append(" ${item.remark}")
                            }.trim(),
                            cover = item.cover,
                            onClick = { onOpenDetail(item.seriesId) },
                        )
                    }
                    if (feedHasMore) {
                        item(key = "more") {
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

/** 海报卡：2:3 海报 + 底部标题 + 左上角徽标。 */
@Composable
fun PosterCard(title: String, badge: String, cover: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(120), label = "poster")
    Column(
        Modifier
            .width(150.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            if (cover.isNotEmpty()) {
                AsyncImage(
                    model = cover,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(PosterPlaceholder),
                )
            }
            if (badge.isNotEmpty()) {
                Box(
                    Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(badge, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(3.dp, Primary, RoundedCornerShape(8.dp)),
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
private fun LoadMoreCard(loading: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0x33FF7043) else Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (loading) "加载中…" else "加载更多", color = if (focused) OnBg else Dim, fontSize = 14.sp)
    }
}
