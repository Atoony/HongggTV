package com.hgtv.app.ui.pages

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hgtv.app.domain.SeriesDetail
import com.hgtv.app.ui.focusScale
import com.hgtv.app.ui.rememberCoverRequest
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

/** 详情 + 选集页（票17 改版 D4-B）：上部整宽信息横幅（海报/标题/元信息/简介截断/按钮区），
 *  下部选集 tab + 整宽 8 列网格。进页焦点仍落"继续播放"主键（SPEC 不变）。数据由上层缓存注入。 */
@Composable
fun DetailPage(
    seriesId: String,
    detail: SeriesDetail,
    watchedEpisodes: Set<Int>,
    resumeEpisode: Int = 1,
    onPlayRequest: PlayRequestListener,
    onOpenAlbum: () -> Unit = {},
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val resume = resumeEpisode.coerceIn(1, detail.episodes.size)
    // 简介全文弹层（Q1-B）：DetailPage 自持；IntroPanel 内再挂 BackHandler（后注册者优先）关层不退页
    var showIntro by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // 背景氛围（Q2-B）：海报模糊放大铺底 + scrim 渐变压暗；与海报同源零额外请求
        PosterBackdrop(cover = detail.cover)
        DetailContent(
            // 覆盖层屏障（焦点铁律②）：弹层打开时才挂 canFocus 属性节点，下层横幅/网格整体退出焦点竞争
            modifier = Modifier.fillMaxSize().then(
                if (showIntro) Modifier.focusProperties { canFocus = false } else Modifier,
            ),
            detail = detail,
            resume = resume,
            watched = watchedEpisodes,
            introOpen = showIntro,
            onPlayRequest = onPlayRequest,
            onOpenAlbum = onOpenAlbum,
            onShowIntro = { showIntro = true },
        )
        if (showIntro) {
            IntroPanel(intro = detail.intro, onClose = { showIntro = false })
        }
    }
}

/** 背景氛围（Q2-B）：海报放大 1.15x 铺底，API 31+ RenderEffect 模糊；低于 31 blur 静默无效
 *  即退化方案"拉伸铺底 + 加重压暗"（清晰图噪点重，scrim 分档）。 */
@Composable
private fun PosterBackdrop(cover: String) {
    if (cover.isEmpty()) return
    val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = rememberCoverRequest(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                .blur(28.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    if (api31) {
                        listOf(Color(0x99101319), Color(0xE6101319)) // 已模糊：压暗保正文对比
                    } else {
                        listOf(Color(0xCC101319), Color(0xF0101319)) // 未模糊：加重压暗压噪点
                    },
                ),
            ),
        )
    }
}

@Composable
private fun DetailContent(
    modifier: Modifier,
    detail: SeriesDetail,
    resume: Int,
    watched: Set<Int>,
    introOpen: Boolean,
    onPlayRequest: PlayRequestListener,
    onOpenAlbum: () -> Unit,
    onShowIntro: () -> Unit,
) {
    var selectedTab by remember(detail.seriesId) { mutableIntStateOf((resume - 1) / EPISODE_GROUP) }
    val mainButtonFocus = remember { FocusRequester() }
    val introButtonFocus = remember { FocusRequester() }
    // 弹层曾开过：关闭后焦点回简介按钮（调用方）；首次组合（introOpen=false 且未开过）不抢主键入口焦点
    var introVisited by remember { mutableStateOf(false) }

    LaunchedEffect(detail.seriesId) { runCatching { mainButtonFocus.requestFocus() } }
    LaunchedEffect(introOpen) {
        if (introOpen) {
            introVisited = true
        } else if (introVisited) {
            // 等一帧：弹层卸载同帧的焦点清除竞态（PlayerScreen 态2 同款次级保险）
            androidx.compose.runtime.withFrameNanos { }
            runCatching { introButtonFocus.requestFocus() }
        }
    }

    Column(modifier) {
        // ---- 顶部信息横幅（D4-B）----
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            AsyncImage(
                model = rememberCoverRequest(detail.cover),
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(156.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PosterPlaceholder),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    detail.title,
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = OnBg),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("共 ${detail.episodeCnt} 集")
                        if (detail.remark.isNotEmpty()) append(" · ${detail.remark}")
                        append(" · " + if (detail.finished) "已完结" else "连载中")
                        if (detail.heat.isNotEmpty()) append(" · ${detail.heat}")
                        if (detail.categories.isNotEmpty()) append(" · ${detail.categories.joinToString(" / ")}")
                    },
                    style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (detail.intro.isNotEmpty()) {
                    Text(
                        detail.intro,
                        style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 已知边界（2026-11-08 模拟器实测，暂不修）：行尾按钮再按 RIGHT，方向搜索无
                    // 同级目标，焦点落进下方网格的容器隐形节点（Lazy 内部锚点，CENTER 无反应），
                    // 但方向键可继续走、不穿透下层——非死锁，真机观察是否值得修再定
                    ResumeButton(
                        episode = resume,
                        focusRequester = mainButtonFocus,
                        onClick = { onPlayRequest.onPlay(detail.seriesId, resume) },
                    )
                    if (detail.intro.isNotEmpty()) {
                        ActionChipButton(text = "简介", focusRequester = introButtonFocus, onClick = onShowIntro)
                    }
                    // 仅多季剧显示（D6-A）：单季零打扰零配额；Q7-B 标题旁不标季角标
                    if (detail.isMultiSeason) {
                        ActionChipButton(text = "查看合集", onClick = onOpenAlbum)
                    }
                }
            }
        }

        // ---- 选集区：tab + 整宽 8 列网格 ----
        val groupCount = (detail.episodes.size + EPISODE_GROUP - 1) / EPISODE_GROUP
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
        ) {
            items((0 until groupCount).toList()) { tab ->
                val from = tab * EPISODE_GROUP + 1
                val to = minOf((tab + 1) * EPISODE_GROUP, detail.episodes.size)
                EpisodeTab("$from-$to", tab == selectedTab) { selectedTab = tab }
            }
        }
        val gridEpisodes = detail.episodes.filter { (it.number - 1) / EPISODE_GROUP == selectedTab }
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 24.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(gridEpisodes, key = { it.episodeId }) { ep ->
                val state = when {
                    ep.number == resume -> ChipState.CURRENT
                    watched.contains(ep.number) -> ChipState.WATCHED
                    else -> ChipState.NORMAL
                }
                EpisodeChip(index = ep.number, state = state) {
                    onPlayRequest.onPlay(detail.seriesId, ep.number)
                }
            }
        }
    }
}

/** 简介全文弹层（Q1-B）：居中面板，返回/关闭按钮收层；正文区可聚焦可滚动（↓ 滚动，滚到底 ↓ 落关闭）。 */
@Composable
private fun IntroPanel(intro: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val textFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { textFocus.requestFocus() }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(620.dp)
                .heightIn(max = 520.dp)
                .clip(shape)
                .background(Color(0xFF161A21))
                .padding(28.dp),
        ) {
            Text("简介", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OnBg))
            Spacer(Modifier.height(12.dp))
            val scroll = rememberScrollState()
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Text(
                intro,
                style = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, color = OnBgVariant),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .focusRequester(textFocus)
                    .focusable(interactionSource = interaction)
                    .border(1.5.dp, if (focused) PrimarySoft else Color.Transparent, RoundedCornerShape(4.dp))
                    .padding(4.dp),
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                ActionChipButton(text = "关闭", onClick = onClose)
            }
        }
    }
}

private enum class ChipState { WATCHED, CURRENT, NORMAL }

@Composable
private fun ResumeButton(episode: Int, focusRequester: FocusRequester, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .width(250.dp)
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

/** 次级动作按钮（横幅"简介/查看合集"、弹层"关闭"共用形态）。 */
@Composable
private fun ActionChipButton(text: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier
        .height(56.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(KeySurface, shape)
        .border(2.dp, if (focused) Primary else Color.Transparent, shape)
    // ⚠️ focusRequester 必须在 clickable（产生 focusable 的地方）之前（2026-11-07 根因修复同款）
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    Box(
        m.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) OnBg else OnBgVariant, fontSize = 16.sp)
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
    val scale = focusScale(focused)
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
