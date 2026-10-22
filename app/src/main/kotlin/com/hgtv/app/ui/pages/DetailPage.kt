package com.hgtv.app.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.hgtv.app.net.HongguoApi
import com.hgtv.app.ui.theme.Bg
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.Faint
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.KeySurfaceDim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.PosterPlaceholder
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.PrimarySoft

private const val EPISODE_GROUP = 50

/** 统一播放入口：(seriesId, episode)。 */
fun interface PlayRequestListener {
    fun onPlay(seriesId: String, episode: Int)
}

/** 详情 + 选集页（SPEC §3.3）：分栏；进页落"继续播放"主键。数据由上层缓存注入。 */
@Composable
fun DetailPage(
    seriesId: String,
    detail: HongguoApi.SeriesDetail,
    watchedEpisodes: Set<Int>,
    resumeEpisode: Int = 1,
    onPlayRequest: PlayRequestListener,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val resume = resumeEpisode.coerceIn(1, detail.episodes.size)
    DetailContent(detail = detail, resume = resume, watched = watchedEpisodes, onPlayRequest = onPlayRequest)
}

@Composable
private fun DetailContent(
    detail: HongguoApi.SeriesDetail,
    resume: Int,
    watched: Set<Int>,
    onPlayRequest: PlayRequestListener,
) {
    var selectedTab by remember { mutableIntStateOf((resume - 1) / EPISODE_GROUP) }
    val mainButtonFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { mainButtonFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = com.hgtv.app.ui.rememberCoverRequest(detail.cover),
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(PosterPlaceholder),
            )
            Text(
                detail.title,
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = OnBg),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("共 ${detail.episodeCnt} 集")
                    if (detail.remark.isNotEmpty()) append(" · ${detail.remark}")
                },
                style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
            )
            if (detail.categories.isNotEmpty()) {
                Text(
                    "分类：${detail.categories.joinToString(" / ")}",
                    style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "状态：" + if (detail.releaseStatus == "finished") "已完结" else "连载中",
                style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
            )
            if (detail.heat.isNotEmpty()) {
                Text("热度：${detail.heat}", style = TextStyle(fontSize = 14.sp, color = OnBgVariant))
            }
            Spacer(Modifier.height(8.dp))
            ResumeButton(
                episode = resume,
                focusRequester = mainButtonFocus,
                onClick = { onPlayRequest.onPlay(detail.seriesId, resume) },
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            val groupCount = (detail.episodes.size + EPISODE_GROUP - 1) / EPISODE_GROUP
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                items((0 until groupCount).toList()) { tab ->
                    val from = tab * EPISODE_GROUP + 1
                    val to = minOf((tab + 1) * EPISODE_GROUP, detail.episodes.size)
                    EpisodeTab("$from-$to", tab == selectedTab) { selectedTab = tab }
                }
            }
            val gridEpisodes = detail.episodes.filter { (it.index - 1) / EPISODE_GROUP == selectedTab }
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(gridEpisodes, key = { it.videoId }) { ep ->
                    val state = when {
                        ep.index == resume -> ChipState.CURRENT
                        watched.contains(ep.index) -> ChipState.WATCHED
                        else -> ChipState.NORMAL
                    }
                    EpisodeChip(index = ep.index, state = state) {
                        onPlayRequest.onPlay(detail.seriesId, ep.index)
                    }
                }
            }
        }
    }
}

private enum class ChipState { WATCHED, CURRENT, NORMAL }

@Composable
private fun ResumeButton(episode: Int, focusRequester: FocusRequester, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = com.hgtv.app.ui.focusScale(focused)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(PrimarySoft, shape)
            .border(2.dp, if (focused) Primary else Color.Transparent, shape)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶ 继续播放 第${episode}集", color = if (focused) OnBg else Primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) PrimarySoft else KeySurface, shape)
            .border(2.dp, if (focused) Primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Primary else OnBgVariant, fontSize = 14.sp)
    }
}

@Composable
private fun EpisodeChip(index: Int, state: ChipState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = com.hgtv.app.ui.focusScale(focused)
    val shape = RoundedCornerShape(4.dp)
    val (bg, fg, border) = when (state) {
        ChipState.WATCHED -> Triple(KeySurfaceDim, Faint, Color.Transparent)
        ChipState.CURRENT -> Triple(PrimarySoft, Primary, Primary)
        ChipState.NORMAL -> Triple(KeySurface, OnBgVariant, Color.Transparent)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp) // SPEC：chip ≥48dp 高
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg, shape)
            .border(2.dp, if (focused) Primary else border, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$index", color = fg, fontSize = 16.sp)
    }
}

/** 详情加载失败页（AppRoot 用） */
@Composable
fun DetailErrorPane(message: String, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Column(
        Modifier.fillMaxSize().background(Bg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("加载失败", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = OnBgVariant, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        RetryButton(onClick = onExit, text = "返回")
    }
}

@Composable
fun LoadingPane() {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Text("加载中…", color = Dim, fontSize = 16.sp)
    }
}

@Composable
fun RetryButton(onClick: () -> Unit, text: String, focusRequester: FocusRequester? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier
        .height(52.dp)
        .width(200.dp)
        .graphicsLayer { scaleX = if (focused) 1.04f else 1f; scaleY = if (focused) 1.04f else 1f }
        .clip(shape)
        .background(Primary, shape)
        .border(2.dp, if (focused) OnBg else Color.Transparent, shape)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    Box(
        m.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}
