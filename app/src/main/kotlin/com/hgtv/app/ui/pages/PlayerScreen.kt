package com.hgtv.app.ui.pages

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.data.WatchStore
import com.hgtv.app.net.HongguoApi
import com.hgtv.app.player.CencDataSource
import com.hgtv.app.player.StreamException
import com.hgtv.app.player.StreamRepository
import com.hgtv.app.ui.theme.Bg
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.Faint
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.KeySurfaceDim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.PrimarySoft
import com.hgtv.app.ui.theme.ProgressTrack
import com.hgtv.app.ui.theme.Scrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEEK_STEP_MS = 10_000L
private const val SAVE_INTERVAL_MS = 5_000L
private const val CONTROLS_AUTO_HIDE_MS = 5_000L
private const val OSD_DURATION_MS = 3_000L
private const val RECONNECT_LIMIT = 3

/** 播放器覆盖层状态机（SPEC §3.4：态1 无 / 态2 选项栏 / 态3 选集 / 态2' 设置） */
private enum class OverlayState { NONE, CONTROLS, EPISODES, SETTINGS }

private data class PlayError(val code: String, val message: String)

private enum class ChipLook { WATCHED, CURRENT, NORMAL }

@Composable
fun PlayerScreen(
    seriesId: String,
    title: String,
    cover: String,
    episodeCount: Int,
    episodes: List<HongguoApi.Episode>,
    startEpisode: Int,
    startPositionMs: Long,
    watchedEpisodes: Set<Int>,
    freeEpisodeCnt: Int = Int.MAX_VALUE,
    api: HongguoApi,
    watchStore: WatchStore,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val streamRepo = remember(api) { StreamRepository(api) }

    var currentEpisode by rememberSaveable { mutableIntStateOf(startEpisode) }
    var options by remember { mutableStateOf<List<HongguoApi.StreamOption>>(emptyList()) }
    var urlIndex by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(OverlayState.NONE) }
    var error by remember { mutableStateOf<PlayError?>(null) }
    var osd by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var reconnects by remember { mutableIntStateOf(0) }
    var autoNext by rememberSaveable { mutableStateOf(true) }
    var preferredQuality by rememberSaveable { mutableIntStateOf(1080) }
    var loadToken by remember { mutableIntStateOf(0) } // 换集/重试信号

    val stageFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    fun pickOption(): HongguoApi.StreamOption? =
        options.filter { it.quality <= preferredQuality }.maxByOrNull { it.quality } ?: options.maxByOrNull { it.quality }

    fun prepareAt(positionMs: Long) {
        val option = pickOption() ?: run {
            error = PlayError("E-1003", "无可用播放线路")
            return
        }
        val uri = option.urls[urlIndex.coerceIn(0, option.urls.size - 1)]
        val webLine = uri.contains("hongguoduanju.com")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("com.phoenix.read/73532")
            // 票09 第 8 项（minireel source_config:98-107）：Referer/Origin 同源 + identity
            // 禁透明 gzip（Range/CDN 友好）
            .setDefaultRequestProperties(
                mapOf("Accept-Encoding" to "identity") +
                    if (webLine) mapOf(
                        "Referer" to "https://hongguoduanju.com/",
                        "Origin" to "https://hongguoduanju.com",
                    ) else mapOf(
                        "Referer" to "https://novel.snssdk.com/",
                        "Origin" to "https://novel.snssdk.com",
                    )
            )
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
        val key = option.cencKey
        val dsFactory = CencDataSource.Factory(httpFactory) { key }
        val source = DefaultMediaSourceFactory(dsFactory).createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(source, positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    // 装载（初次 + 换集 + 错误重试统一走 loadToken/currentEpisode）
    LaunchedEffect(currentEpisode, loadToken) {
        val ep = episodes.firstOrNull { it.index == currentEpisode } ?: return@LaunchedEffect
        osd = "第${ep.index}集"
        reconnects = 0
        urlIndex = 0
        // 网页兜底详情只带免费集直链：非免费集直接走错误页，不空跑取流重试
        if (ep.index > freeEpisodeCnt) {
            buffering = false
            error = PlayError("E-1003", "第${ep.index}集为非免费集，当前线路仅支持前 $freeEpisodeCnt 集")
            return@LaunchedEffect
        }
        buffering = true
        error = null
        try {
            options = streamRepo.fetchStreamsWithRetry(ep.videoId)
            prepareAt(if (ep.index == startEpisode) startPositionMs else 0L)
        } catch (e: StreamException) {
            error = PlayError(e.code, e.message ?: "取流失败")
        } catch (e: Exception) {
            error = PlayError("E-1001", e.message ?: "取流失败")
        }
    }

    // OSD 3s 自动消失
    LaunchedEffect(osd) {
        if (osd != null) {
            delay(OSD_DURATION_MS)
            osd = null
        }
    }

    // 播放器事件：多线路切换 / 重连 / 自动连播
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: PlaybackException) {
                val option = pickOption()
                if (option != null && urlIndex + 1 < option.urls.size) {
                    urlIndex++
                    prepareAt(player.currentPosition)
                } else if (reconnects < RECONNECT_LIMIT) {
                    reconnects++
                    urlIndex = 0
                    prepareAt(player.currentPosition)
                } else {
                    error = PlayError("E-2001", "播放中断：${err.errorCodeName}")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = player.duration
                    reconnects = 0
                }
                if (state == Player.STATE_ENDED && autoNext && currentEpisode < episodeCount) {
                    currentEpisode += 1
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 断点每 5s 落盘
    fun snapshot() = WatchEntry(
        seriesId = seriesId,
        title = title,
        cover = cover,
        episode = currentEpisode,
        episodeCount = episodeCount,
        positionMs = player.currentPosition,
        durationMs = player.duration,
        updatedAt = System.currentTimeMillis(),
    )

    LaunchedEffect(player, currentEpisode) {
        while (true) {
            delay(SAVE_INTERVAL_MS)
            if (player.duration > 0 && player.currentPosition > 0) {
                watchStore.upsert(snapshot())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (player.duration > 0 && player.currentPosition > 0) {
                kotlinx.coroutines.runBlocking { watchStore.upsert(snapshot()) }
            }
        }
    }

    // 返回链：浮层 → 选项栏 → 退播放回详情
    BackHandler {
        when (overlay) {
            OverlayState.EPISODES, OverlayState.SETTINGS -> overlay = OverlayState.NONE
            OverlayState.CONTROLS -> overlay = OverlayState.NONE
            OverlayState.NONE -> onExit()
        }
    }

    // 态 1 焦点兜底（无覆盖层且无错误时舞台聚焦，接 ←→ ↓ OK）
    LaunchedEffect(overlay, error) {
        if (overlay == OverlayState.NONE && error == null) runCatching { stageFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize().align(Alignment.Center),
        )

        // 态 1 纯净观看：←→ seek ±10s；↓/OK 呼出选项栏
        if (overlay == OverlayState.NONE && error == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(stageFocus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        when (e.key.keyCode.toInt()) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)); true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(player.duration)); true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                overlay = OverlayState.CONTROLS; true
                            }
                            else -> false
                        }
                    },
            )
        }

        // OSD（第N集 / 正在切换…）
        osd?.let { text ->
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Scrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(text, color = OnBg, fontSize = 16.sp)
            }
        }
        if (buffering && error == null) {
            Box(Modifier.align(Alignment.Center)) {
                Text("加载中…", color = Dim, fontSize = 14.sp)
            }
        }

        // 态 2 选项栏
        if (overlay == OverlayState.CONTROLS && error == null) {
            LaunchedEffect(Unit) { runCatching { pauseFocus.requestFocus() } }
            ControlsBar(
                player = player,
                isPlaying = isPlaying,
                durationMs = durationMs,
                pauseFocus = pauseFocus,
                onPrev = { if (currentEpisode > 1) currentEpisode -= 1 },
                onNext = { if (currentEpisode < episodeCount) currentEpisode += 1 },
                onEpisodes = { overlay = OverlayState.EPISODES },
                onSettings = { overlay = OverlayState.SETTINGS },
                onDismiss = { overlay = OverlayState.NONE },
            )
        }

        // 态 3 选集浮层
        if (overlay == OverlayState.EPISODES) {
            EpisodeOverlay(
                episodes = episodes,
                current = currentEpisode,
                watched = watchedEpisodes,
                onPick = { ep ->
                    if (ep != currentEpisode) currentEpisode = ep
                    overlay = OverlayState.NONE
                },
            )
        }

        // 态 2' 设置面板
        if (overlay == OverlayState.SETTINGS) {
            SettingsPanel(
                preferredQuality = preferredQuality,
                autoNext = autoNext,
                onQuality = { q ->
                    if (q != preferredQuality) {
                        preferredQuality = q
                        prepareAt(player.currentPosition)
                    }
                    overlay = OverlayState.NONE
                },
                onAutoNext = { autoNext = it },
                onClose = { overlay = OverlayState.NONE },
            )
        }

        // 错误页：OK=从断点重试 / 返回=回详情
        error?.let { err ->
            LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
            Column(
                Modifier.fillMaxSize().background(Bg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("播放失败", color = OnBg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("${err.code} · ${err.message}", color = OnBgVariant, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RetryButton(onClick = { loadToken++ }, text = "重试", focusRequester = retryFocus)
                    RetryButton(onClick = onExit, text = "返回详情")
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ControlsBar(
    player: Player,
    isPlaying: Boolean,
    durationMs: Long,
    pauseFocus: FocusRequester,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var hideTick by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(hideTick) {
        if (hideTick > 0) {
            delay(CONTROLS_AUTO_HIDE_MS)
            onDismiss()
        }
    }
    // 进度实时刷新（500ms 轮询，控制条可见期间）
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            delay(500)
        }
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Scrim.copy(alpha = 0.92f))
            .padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ControlButton(
                text = if (isPlaying) "⏸" else "▶",
                focusRequester = pauseFocus,
                onClick = {
                    if (isPlaying) player.pause() else player.play()
                    hideTick++
                },
            )
            SeekBar(
                positionMs = pos,
                durationMs = durationMs,
                onSeek = { ms ->
                    player.seekTo(ms)
                    hideTick++
                },
                modifier = Modifier.weight(1f),
            )
            Text("${formatMs(pos)} / ${formatMs(durationMs)}", color = OnBgVariant, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ControlButton("上一集", onClick = { onPrev(); hideTick++ })
            ControlButton("下一集", onClick = { onNext(); hideTick++ })
            ControlButton("选集", onClick = { onEpisodes(); hideTick++ })
            ControlButton("设置", onClick = { onSettings(); hideTick++ })
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun ControlButton(text: String, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "ctrl")
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier
        .height(44.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(if (focused) PrimarySoft else KeySurface, shape)
        .border(2.dp, if (focused) Primary else Color.Transparent, shape)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    Box(m.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (focused) OnBg else OnBgVariant, fontSize = 15.sp)
    }
}

/** 进度条：焦点态 ←→ = 拖进度（±10s） */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(if (focused) 14.dp else 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x33FFFFFF) else ProgressTrack)
            .border(2.dp, if (focused) Primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null) {}
            .onPreviewKeyEvent { e ->
                if (!focused) return@onPreviewKeyEvent false
                when (e.key.keyCode.toInt()) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeek((positionMs - SEEK_STEP_MS).coerceAtLeast(0)); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeek((positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)); true
                    }
                    else -> false
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Primary),
        )
    }
}

@Composable
private fun EpisodeOverlay(
    episodes: List<HongguoApi.Episode>,
    current: Int,
    watched: Set<Int>,
    onPick: (Int) -> Unit,
) {
    val groupCount = (episodes.size + 49) / 50
    var selectedTab by remember { mutableIntStateOf(((current - 1) / 50).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.96f)).padding(48.dp)) {
        Text("选集", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            items((0 until groupCount).toList()) { tab ->
                val from = tab * 50 + 1
                val to = minOf((tab + 1) * 50, episodes.size)
                TabButton("$from-$to", tab == selectedTab) { selectedTab = tab }
            }
        }
        val gridEpisodes = episodes.filter { (it.index - 1) / 50 == selectedTab }
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(gridEpisodes, key = { it.videoId }) { ep ->
                val look = when {
                    ep.index == current -> ChipLook.CURRENT
                    watched.contains(ep.index) -> ChipLook.WATCHED
                    else -> ChipLook.NORMAL
                }
                ChipOverlay(ep.index, look) { onPick(ep.index) }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) PrimarySoft else KeySurfaceDim, shape)
            .border(2.dp, if (focused) Primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Primary else OnBgVariant, fontSize = 14.sp)
    }
}

@Composable
private fun ChipOverlay(index: Int, look: ChipLook, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "epchip")
    val shape = RoundedCornerShape(4.dp)
    val (bg, fg, border) = when (look) {
        ChipLook.WATCHED -> Triple(KeySurfaceDim, Faint, Color.Transparent)
        ChipLook.CURRENT -> Triple(PrimarySoft, Primary, Primary)
        ChipLook.NORMAL -> Triple(KeySurface, OnBgVariant, Color.Transparent)
    }
    Box(
        Modifier
            .height(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg, shape)
            .border(2.dp, if (focused) Primary else border, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$index", color = fg, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsPanel(
    preferredQuality: Int,
    autoNext: Boolean,
    onQuality: (Int) -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.96f)).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("设置", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("清晰度（当前 ${if (preferredQuality >= 1080) "1080P" else "720P"}）", color = OnBgVariant, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlButton("1080P", onClick = { onQuality(1080) })
            ControlButton("720P", onClick = { onQuality(720) })
        }
        Text("自动连播（${if (autoNext) "开" else "关"}）", color = OnBgVariant, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlButton(if (autoNext) "开" else "关", onClick = { onAutoNext(!autoNext) })
        }
        ControlButton("返回", onClick = onClose)
    }
}
