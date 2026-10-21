package com.hgtv.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import com.hgtv.app.ui.pages.DetailPage
import com.hgtv.app.ui.pages.HistoryPage
import com.hgtv.app.ui.pages.HomePage
import com.hgtv.app.ui.pages.PlayerScreen
import com.hgtv.app.ui.pages.SearchPage
import com.hgtv.app.ui.theme.Bg
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.PrimarySoft
import com.hgtv.app.ui.theme.Surface

enum class NavDest(val label: String) {
    HOME("首页"),
    SEARCH("搜索"),
    HISTORY("历史"),
}

/** 全局导航壳：左侧边栏 3 项 + 内容区（focusRestorer 保持每页状态与焦点）。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppRoot() {
    var current by rememberSaveable { mutableStateOf(NavDest.HOME) }
    // 覆盖层 1：详情页（seriesId）；覆盖层 2：播放器
    var detailSeriesId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailData by remember { mutableStateOf<com.hgtv.app.net.HongguoApi.SeriesDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var playEpisode by remember { mutableStateOf<Int?>(null) }
    var playResumeMs by remember { mutableStateOf(0L) }
    var autoResume by remember { mutableStateOf<com.hgtv.app.data.WatchEntry?>(null) } // 历史/首页续播：加载完详情直接进播放
    val contentRequester = remember { FocusRequester() }
    val api = remember { com.hgtv.app.net.HongguoApi() }
    val feed = remember { com.hgtv.app.net.HongguoFeed(api) }
    val web = remember { com.hgtv.app.net.HongguoWeb() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val watchStore = remember { com.hgtv.app.data.WatchStore(context) }
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO) }
    val history by watchStore.entries.collectAsState(initial = emptyList())

    // 详情数据加载（缓存于 AppRoot，播放退出回详情不重拉）
    LaunchedEffect(detailSeriesId) {
        val sid = detailSeriesId ?: return@LaunchedEffect
        if (detailData?.seriesId == sid) return@LaunchedEffect
        detailData = null
        detailError = null
        try {
            detailData = api.fetchDetail(sid)
        } catch (e: Exception) {
            // App 通道被风控时兜底：网页播放页带全集 vid_list（游客可访问）
            try {
                detailData = web.playerDetail(sid)
            } catch (e2: Exception) {
                detailError = "${e.message}（网页兜底也失败：${e2.message}）"
            }
        }
    }

    // 续播直进（详情就绪后自动开播放器，跳过详情页展示）
    LaunchedEffect(detailData, autoResume) {
        val entry = autoResume
        if (entry != null && detailData?.seriesId == entry.seriesId) {
            playEpisode = entry.episode
            playResumeMs = entry.positionMs
            autoResume = null
        }
    }

    fun openDetail(sid: String, resume: com.hgtv.app.data.WatchEntry? = null) {
        autoResume = resume
        if (detailSeriesId != sid) {
            detailData = null
            detailError = null
        }
        detailSeriesId = sid
    }

    LaunchedEffect(current, detailSeriesId, playEpisode) {
        if (detailSeriesId == null && playEpisode == null) runCatching { contentRequester.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Bg)) {
        Sidebar(
            selected = current,
            onSelect = { current = it },
            rightTarget = contentRequester,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(contentRequester)
                .focusGroup(),
        ) {
            when (current) {
                NavDest.HOME -> HomePage(
                    feed = feed,
                    history = history,
                    onOpenDetail = { detailSeriesId = it },
                    onResume = { entry -> openDetail(entry.seriesId, entry) },
                )
                NavDest.SEARCH -> SearchPage(
                    web = web,
                    onOpenDetail = { detailSeriesId = it },
                )
                NavDest.HISTORY -> HistoryPage(
                    history = history,
                    onResume = { entry -> openDetail(entry.seriesId, entry) },
                    onClear = { scope.launch { watchStore.clear() } },
                )
            }
        }
    }

    // 详情覆盖层
    if (detailSeriesId != null && playEpisode == null) {
        val data = detailData
        val watched = history.find { it.seriesId == detailSeriesId }?.let { (1 until it.episode).toSet() } ?: emptySet()
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                detailError != null -> com.hgtv.app.ui.pages.DetailErrorPane(message = detailError!!) { detailSeriesId = null }
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…", color = Dim, fontSize = 16.sp)
                }
                else -> DetailPage(
                    seriesId = data.seriesId,
                    detail = data,
                    watchedEpisodes = watched,
                    resumeEpisode = history.find { it.seriesId == data.seriesId }?.episode ?: 1,
                    onPlayRequest = { _, episode ->
                        playEpisode = episode
                        playResumeMs = 0L
                    },
                    onExit = { detailSeriesId = null },
                )
            }
        }
    }

    // 播放器覆盖层（退播放 → 回详情）
    playEpisode?.let { episode ->
        val data = detailData
        if (data != null) {
            val entry = history.find { it.seriesId == data.seriesId }
            PlayerScreen(
                seriesId = data.seriesId,
                title = data.title,
                cover = data.cover,
                episodeCount = data.episodes.size,
                episodes = data.episodes,
                startEpisode = episode,
                startPositionMs = playResumeMs,
                watchedEpisodes = entry?.let { (1 until it.episode).toSet() } ?: emptySet(),
                freeEpisodeCnt = data.freeEpisodeCnt,
                api = api,
                watchStore = watchStore,
                onExit = {
                    playEpisode = null
                    playResumeMs = 0L
                },
            )
        }
    }
}

@Composable
private fun Sidebar(selected: NavDest, onSelect: (NavDest) -> Unit, rightTarget: FocusRequester) {
    Column(
        Modifier
            .width(132.dp)
            .fillMaxHeight()
            .background(Color(0xFF12151B))
            .padding(horizontal = 12.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NavDest.entries.forEach { dest ->
            SidebarItem(
                label = dest.label,
                selected = dest == selected,
                onClick = { onSelect(dest) },
                rightTarget = rightTarget,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    rightTarget: FocusRequester,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "scale")
    val shape = RoundedCornerShape(8.dp)
    // 选中框跟随焦点（TV 惯例）；当前页在未聚焦时以左侧竖条标记
    val highlighted = focused || selected

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (highlighted) PrimarySoft else Color.Transparent, shape)
            .border(
                width = 2.dp,
                color = if (focused) Primary else Color.Transparent,
                shape = shape,
            )
            .drawBehind {
                if (selected && !focused) {
                    drawRoundRect(
                        color = Primary,
                        topLeft = Offset(0f, size.height * 0.22f),
                        size = Size(3.dp.toPx(), size.height * 0.56f),
                    )
                }
            }
            .focusProperties { right = rightTarget }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = when {
                selected -> OnBg
                focused -> OnBg
                else -> Dim
            },
        )
    }
}

/** 通用可聚焦占位卡（骨架期验证焦点流转/状态保持用）。 */
@Composable
fun PlaceholderCard(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "card")
    Box(
        modifier
            .size(width = 128.dp, height = 224.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(
                width = 2.dp,
                color = if (focused) Primary else Color(0x00000000),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(text = text, color = OnBg, fontSize = 14.sp)
    }
}

@Composable
fun PageTitle(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        color = OnBg,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

@Composable
fun HgSpacer(width: Int = 0, height: Int = 0) {
    if (width > 0) Spacer(Modifier.width(width.dp))
    if (height > 0) Spacer(Modifier.height(height.dp))
}
