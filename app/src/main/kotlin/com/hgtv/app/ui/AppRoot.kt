package com.hgtv.app.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.focusProperties
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
    var detailData by remember { mutableStateOf<com.hgtv.app.domain.SeriesDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    // 合集覆盖层（票17）：详情之上、播放器之下的整页层；数据按 seriesId 缓存，开关不重拉
    var albumOpen by rememberSaveable { mutableStateOf(false) }
    var albumData by remember { mutableStateOf<com.hgtv.app.domain.Album?>(null) }
    var albumError by remember { mutableStateOf<String?>(null) }
    var albumSeriesId by remember { mutableStateOf<String?>(null) }
    var playEpisode by remember { mutableStateOf<Int?>(null) }
    var playResumeMs by remember { mutableStateOf(0L) }
    var autoResume by remember { mutableStateOf<com.hgtv.app.data.WatchEntry?>(null) } // 历史/首页续播：加载完详情直接进播放
    // 内容区入口焦点：由当前页的首个可见交互组件持有（侧栏 RIGHT / 页面切换 / 覆盖层关闭都请求它）。
    // ⚠️ 不能挂 focusGroup 容器上——requestFocus 会落在组节点本身，无视觉无语义（2026-11-07
    // 实测：侧栏按右焦点"消失"，dump 无 focused 节点，方向键也爬不出来）。
    val pageEntryFocus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 数据源层（ADR 0001）：一切红果数据经 openhongguo SDK，App 级单例（票13 播放链 + 票14 元数据）
    val ohgSource = remember { com.hgtv.app.data.OpenhongguoSource(context) }
    val watchStore = remember { com.hgtv.app.data.WatchStore(context) }
    val playerSettings = remember { com.hgtv.app.data.PlayerSettingsStore(context) }
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO) }
    // null = DataStore 首读未完成（首页首拉 filter_ids 依赖已看列表就绪）
    val historyState by watchStore.entries.collectAsState(initial = null as List<com.hgtv.app.data.WatchEntry>?)
    val history = historyState ?: emptyList<com.hgtv.app.data.WatchEntry>()

    // SPEC §4 返回四级链末端："首页双击退出"落地为顶级页通用（首页/搜索键盘态/历史——
    // 否则这些页返回无出口）；无覆盖层时 1.5s 窗口双击退出，首按提示。
    // 注册在本层最前 = 最低优先级，详情/播放/搜索结果的 BackHandler 先消费。
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = detailSeriesId == null && playEpisode == null) {
        val now = System.currentTimeMillis()
        if (BackExit.isExit(now, lastBackAt)) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        }
    }

    // 详情数据加载（缓存于 AppRoot，播放退出回详情不重拉）
    LaunchedEffect(detailSeriesId) {
        val sid = detailSeriesId ?: return@LaunchedEffect
        if (detailData?.seriesId == sid) return@LaunchedEffect
        detailData = null
        detailError = null
        try {
            detailData = ohgSource.seriesDetail(sid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消直抛：detailSeriesId 切换不误报"加载失败"（票14 评审）
        } catch (e: Exception) {
            detailError = e.message ?: "详情加载失败"
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

    // 合集取数（票17）：仅在多季详情"查看合集"打开时拉取；错误不缓存（频控/抖动重进可重试），
    // 同合集不重拉（匿名配额纪律）——缓存键虽是首拉季的 seriesId，但跳季后的新 sid 命中
    // 已缓存季列表时沿用同一份 albumData（合集页逐季跳转不重复花配额）
    LaunchedEffect(albumOpen, detailSeriesId) {
        val sid = detailSeriesId ?: return@LaunchedEffect
        if (!albumOpen) return@LaunchedEffect
        if (albumSeriesId == sid && albumData != null) return@LaunchedEffect
        if (albumData?.seasons?.any { it.seriesId == sid } == true) return@LaunchedEffect
        albumSeriesId = sid
        albumData = null
        albumError = null
        try {
            albumData = ohgSource.seasons(sid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            albumError = e.message ?: "合集加载失败"
        }
    }

    fun openDetail(sid: String, resume: com.hgtv.app.data.WatchEntry? = null) {
        autoResume = resume
        // 换剧重置合集层：焦点若穿透到下层点开新剧（合集页边界 RIGHT 清焦点的极端路径），
        // albumOpen 残留会把新详情直接盖在别人的合集页下（2026-11-08 模拟器实测）。
        // ⚠️ 一切详情入口（首页/搜索/历史/合集跳季）必须经本函数——直赋 detailSeriesId
        // 会旁路 albumOpen/autoResume 重置（评审 finding）
        albumOpen = false
        if (detailSeriesId != sid) {
            detailData = null
            detailError = null
        }
        detailSeriesId = sid
    }

    // 页面切换/覆盖层关闭后重落入口焦点。带重试：入口 requester 由页面首个可见交互组件持有，
    // 数据未就绪/子模式切换（如搜索结果态）时有短暂悬空窗口，一次请求会失败（2026-11-07
    // 实测：详情→搜索结果页返回后焦点掉根部无人捡，且波及后续页面）。
    LaunchedEffect(current, detailSeriesId, playEpisode) {
        if (detailSeriesId == null && playEpisode == null) {
            repeat(8) {
                if (runCatching { pageEntryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }

    // 下层焦点屏障：详情/播放覆盖层打开时下层子树（侧栏+内容页）整体退出焦点竞争——覆盖层
    // 换装移除持焦组件时焦点若掉进被画面盖住的下层网格，方向键要在下层逐行爬才能回覆盖层。
    // ⚠️ 屏障必须"条件挂载"而非常驻 focusProperties{canFocus=...}：祖先上只要存在
    // canFocus 属性节点（哪怕恒为 true），LazyColumn/LazyRow 的方向进入就会落进容器
    // 内部隐形节点（无视觉、a11y 不暴露）——2026-11-07 最小复现二分定位（步B/C/D）。
    // 常态（无覆盖层）树中不存在该节点，方向搜索直达卡片；覆盖层打开时挂上，canFocus
    // 沿树继承，下层全部子级退出焦点竞争。
    val overlayOpen = detailSeriesId != null || playEpisode != null
    Row(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .then(if (overlayOpen) Modifier.focusProperties { canFocus = false } else Modifier),
    ) {
        Sidebar(
            selected = current,
            onSelect = { current = it },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (current) {
                NavDest.HOME -> HomePage(
                    source = ohgSource,
                    history = history,
                    historyReady = historyState != null,
                    entryFocus = pageEntryFocus,
                    // 详情/播放覆盖层打开时首页不抢焦点（继续观看首卡落位会打断播放器按键）
                    active = detailSeriesId == null && playEpisode == null,
                    onOpenDetail = { openDetail(it) },
                    onResume = { entry -> openDetail(entry.seriesId, entry) },
                    onOpenHistory = { current = NavDest.HISTORY },
                )
                NavDest.SEARCH -> SearchPage(
                    source = ohgSource,
                    entryFocus = pageEntryFocus,
                    onOpenDetail = { openDetail(it) },
                )
                NavDest.HISTORY -> HistoryPage(
                    history = history,
                    historyReady = historyState != null,
                    entryFocus = pageEntryFocus,
                    onResume = { entry -> openDetail(entry.seriesId, entry) },
                    onClear = { scope.launch { watchStore.clear() } },
                )
            }
        }
    }

    // 详情覆盖层（合集层打开时卸载——同播放器模式：返回时重组，入口焦点由页面自落）。
    // Box 级 BackHandler 兜底"加载中"分支：该窗口内 DetailPage/ErrorPane 均未组合，无人
    // 消费返回键会穿透（弱网下直接退出 App，评审 finding）
    if (detailSeriesId != null && playEpisode == null && !albumOpen) {
        val data = detailData
        val watched = history.find { it.seriesId == detailSeriesId }?.let { (1 until it.episode).toSet() } ?: emptySet()
        BackHandler { detailSeriesId = null }
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                detailError != null -> com.hgtv.app.ui.pages.DetailErrorPane(message = detailError!!) { detailSeriesId = null }
                data == null -> com.hgtv.app.ui.pages.LoadingPane()
                else -> DetailPage(
                    seriesId = data.seriesId,
                    detail = data,
                    watchedEpisodes = watched,
                    resumeEpisode = history.find { it.seriesId == data.seriesId }?.episode ?: 1,
                    onPlayRequest = { _, episode ->
                        playEpisode = episode
                        playResumeMs = 0L
                    },
                    onOpenAlbum = { albumOpen = true },
                    onExit = { detailSeriesId = null },
                )
            }
        }
    }

    // 合集覆盖层（票17 Q6-B 全屏，详情之上）。Box 级 BackHandler 同款兜底加载中窗口
    if (detailSeriesId != null && playEpisode == null && albumOpen) {
        BackHandler { albumOpen = false }
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                albumError != null -> com.hgtv.app.ui.pages.DetailErrorPane(message = albumError!!) { albumOpen = false }
                albumData == null -> com.hgtv.app.ui.pages.LoadingPane()
                else -> com.hgtv.app.ui.pages.AlbumPage(
                    album = albumData!!,
                    currentSeasonIndex = detailData?.seasonIndex ?: 0,
                    onOpenSeason = { sid ->
                        albumOpen = false
                        openDetail(sid)
                    },
                    onExit = { albumOpen = false },
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
                source = ohgSource,
                watchStore = watchStore,
                settings = playerSettings,
                onExit = {
                    playEpisode = null
                    playResumeMs = 0L
                },
            )
        }
    }
}

@Composable
private fun Sidebar(selected: NavDest, onSelect: (NavDest) -> Unit) {
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
            )
        }
    }
}

/** 侧栏项不挂 focusProperties{right} 跳转目标：默认方向搜索找几何最近的可见可交互组件，
 *  且不经过 FocusRequester——自定义目标一旦悬空（页面数据未就绪/结果态键盘卸载），
 *  焦点搜索在框架内解析悬空 requester 直接 IllegalStateException 闪退（2026-11-07 实测）。 */
@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
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
    val scale = focusScale(focused)
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
