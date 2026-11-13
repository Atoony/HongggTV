package com.hgtv.app.ui.pages

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hgtv.app.data.OpenhongguoSource
import com.hgtv.app.data.PlayerSettingsStore
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.data.WatchStore
import com.hgtv.app.domain.Playback
import com.hgtv.app.domain.StreamException
import com.hgtv.app.domain.Episode
import com.hgtv.app.player.CencDecryptDataSourceFactory
import com.hgtv.app.player.PlaybackPreloadCache
import com.hgtv.app.player.preloadPlayback
import com.hgtv.app.player.shouldAutoNext
import com.hgtv.app.ui.focusScale
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
private const val TAG = "HgtvPlayer"

/** 倍速档位（操作栏倍速弹层）：短剧场景偏快档 */
private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** 播放器覆盖层状态机（SPEC §3.4：态1 无 / 态2 选项栏 / 态3 选集 / 态2' 设置；倍速/清晰度为操作栏快捷弹层） */
private enum class OverlayState { NONE, CONTROLS, EPISODES, SETTINGS, SPEED, QUALITY }

private data class PlayError(val code: String, val message: String)

private enum class ChipLook { WATCHED, CURRENT, NORMAL }

@Composable
fun PlayerScreen(
    seriesId: String,
    title: String,
    cover: String,
    episodeCount: Int,
    episodes: List<Episode>,
    startEpisode: Int,
    startPositionMs: Long,
    watchedEpisodes: Set<Int>,
    source: OpenhongguoSource,
    watchStore: WatchStore,
    settings: PlayerSettingsStore,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    // 票16 D1-A：播放器常亮——进播放器给 Activity window 加 FLAG_KEEP_SCREEN_ON，退出即清；
    // 覆盖暂停/缓冲/错误页全部状态（Netflix 惯例：暂停≠离开），无需权限
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var currentEpisode by rememberSaveable { mutableIntStateOf(startEpisode) }
    var options by remember { mutableStateOf<List<Playback.Line>>(emptyList()) }
    // 播放级内容密钥（票12 实测：每集独立、集内跨线路共享），明文流为 null
    var contentKey by remember { mutableStateOf<ByteArray?>(null) }
    var urlIndex by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(OverlayState.NONE) }
    var error by remember { mutableStateOf<PlayError?>(null) }
    var osd by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var reconnects by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 自动连播开关：DataStore 持久化（票05），断网/重启后仍生效
    val autoNext by settings.autoNext.collectAsState(initial = PlayerSettingsStore.AUTO_NEXT_DEFAULT)
    // 倍速：DataStore 持久化，Flow 初值为默认 1.0，真实持久值到达后由下方 effect 应用
    val speed by settings.speed.collectAsState(initial = PlayerSettingsStore.SPEED_DEFAULT)
    var preferredQuality by rememberSaveable { mutableIntStateOf(1080) }
    var loadToken by remember { mutableIntStateOf(0) } // 换集/重试信号
    // 断点续播：最近已知播放位置（5s 快照 + 出错时捕获），错误页重试从这续播而非 startPositionMs
    var resumeMs by remember { mutableLongStateOf(startPositionMs) }
    var lastLoadedEpisode by remember { mutableIntStateOf(-1) } // -1 = 尚未装载过
    var switchOsdPending by remember { mutableStateOf(false) } // 换集 READY 后把"正在切换…"换成"第N集"
    // 票16 D2-A/D3-A：下一集预解析——READY 信号 tick + 按集缓存（会话级，随播放器销毁）
    var readyTick by remember { mutableIntStateOf(0) }
    val preloadCache = remember { PlaybackPreloadCache() }

    val stageFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    fun pickOption(): Playback.Line? =
        options.filter { it.quality <= preferredQuality }.maxByOrNull { it.quality } ?: options.maxByOrNull { it.quality }

    // 展示口径用"实际在播线路"档位而非偏好上限：上限（默认 1080）高于本集线路时实际播的是
    // 回落后的档位，按钮/弹层若显示上限会与画面不符、且弹层里上限不在选项中导致无当前项高亮
    val activeQuality = pickOption()?.quality

    fun prepareAt(positionMs: Long) {
        val option = pickOption() ?: run {
            error = PlayError("E-1003", "无可用播放线路")
            return
        }
        val uri = option.urls[urlIndex.coerceIn(0, option.urls.size - 1)]
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("com.phoenix.read/73532")
            // 票09 第 8 项（minireel source_config:98-107）：App 通道 CDN 头 Referer/Origin 同源；
            // 禁透明 gzip（Range/CDN 友好）。SDK 直链同 App 通道 CDN，沿用。
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Encoding" to "identity",
                    "Referer" to "https://novel.snssdk.com/",
                    "Origin" to "https://novel.snssdk.com",
                )
            )
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
        val key = contentKey
        // CENC 流：样本级 AES-CTR 解密在 DataSource 层完成——openhongguo 直链是无 pssh 的
        // progressive CENC MP4（tenc 还是旧布局），media3 progressive 不支持 CENC、直链又无
        // HLS/DASH 变体，票13 改本地解密后按明文渐进播放（moov 只读解析、逐字节等长同位）。
        // 明文流：DataSource 原样透传。
        val dataFactory: DataSource.Factory =
            if (key != null) CencDecryptDataSourceFactory(httpFactory, key) else httpFactory
        val sourceFactory = DefaultMediaSourceFactory(dataFactory)
        val mediaItem = MediaItem.fromUri(uri)
        val source = sourceFactory.createMediaSource(mediaItem)
        player.setMediaSource(source, positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    // 装载（初次 + 换集 + 错误重试统一走 loadToken/currentEpisode）
    LaunchedEffect(currentEpisode, loadToken) {
        val ep = episodes.firstOrNull { it.number == currentEpisode } ?: return@LaunchedEffect
        val firstLoad = lastLoadedEpisode == -1
        // 票05：换集/重试期间 OSD"正在切换…"，READY 后换成"第N集"；初次进播放器直接报集号
        osd = if (firstLoad) "第${ep.number}集" else "正在切换…"
        switchOsdPending = !firstLoad
        val startAt = resolveStartMs(currentEpisode, lastLoadedEpisode, startEpisode, startPositionMs, resumeMs)
        lastLoadedEpisode = currentEpisode
        reconnects = 0
        urlIndex = 0
        // 票13：网页兜底时代的免费集预检随旧链退役——线路可用性以数据源（SDK）为唯一真相，
        // 取不到的错误由 StreamException 走错误页
        buffering = true
        error = null
        try {
            // 票16：错误页重试弃缓存强制现取（直链可能已过期）；装载先查预载缓存
            // （未超龄命中直用），未命中现取（同集在途预载则等它，防配额双耗）
            if (loadToken > 0) preloadCache.invalidate(ep.episodeId)
            val cached = preloadCache.get(ep.episodeId)
            if (cached != null) Log.d(TAG, "预载命中：第${ep.number}集")
            val playback = cached ?: preloadCache.loadDedup(ep.episodeId) { source.playback(it) }
            options = playback.lines
            contentKey = playback.contentKey
            prepareAt(startAt)
        } catch (e: StreamException) {
            error = PlayError(e.code, e.message ?: "取流失败")
        } catch (e: Exception) {
            error = PlayError("E-1001", e.message ?: "取流失败")
        }
    }

    // 票16 D2-A/D3-A：当前集 READY 后按决策后台预解析下一集（失败静默、已缓存不重复发起；
    // autoNext 关/无下一集不发起，已发起的缓存谁取谁用）。readyTick==0 = 尚未 READY 过，
    // 须挡住初次组合触发——否则装载还没成功就抢发预载，白耗匿名配额
    LaunchedEffect(readyTick, autoNext) {
        if (readyTick == 0 || !shouldAutoNext(autoNext, currentEpisode, episodeCount)) return@LaunchedEffect
        val next = episodes.firstOrNull { it.number == currentEpisode + 1 } ?: return@LaunchedEffect
        val ok = preloadPlayback(next.episodeId, preloadCache) { source.playback(it) }
        if (ok) Log.d(TAG, "已预载第${next.number}集") else Log.d(TAG, "预载第${next.number}集失败（静默）")
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
                    // 进错误页前保住断点：重试从这续播（票05 断点不丢）
                    resumeMs = player.currentPosition
                    error = PlayError("E-2001", "播放中断：${err.errorCodeName}")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = player.duration
                    reconnects = 0
                    readyTick++ // 票16：READY 信号（驱动下一集预载）
                    if (switchOsdPending) {
                        switchOsdPending = false
                        osd = "第${currentEpisode}集"
                    }
                }
                if (state == Player.STATE_ENDED && shouldAutoNext(autoNext, currentEpisode, episodeCount)) {
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
                resumeMs = player.currentPosition
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

    // 倍速应用（单一机制：持久值到达/变更即设；player 级参数，换集不重置）
    LaunchedEffect(player, speed) { player.setPlaybackParameters(PlaybackParameters(speed)) }

    // 返回链：浮层 → 选项栏 → 退播放回详情
    BackHandler {
        when (overlay) {
            OverlayState.EPISODES, OverlayState.SETTINGS,
            OverlayState.SPEED, OverlayState.QUALITY,
            -> overlay = OverlayState.CONTROLS
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
                        // Android 上 e.key.keyCode 是键码左移 32 位的 Long，toInt() 恒 0；须取 nativeKeyEvent.keyCode。
                        // 按键会 KeyDown/KeyUp 各派发一次，只认 KeyDown 防 seek 双跳。
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key.nativeKeyCode) {
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
            // 根因修复 2026-11-07：ControlButton 的 focusRequester 曾挂在 clickable 之后，
            // Compose 要求在其之前才绑定——requestFocus() 抛异常被吞，呼出后焦点从未落位
            // （要按多次↓爬回暂停键）。等一帧再请求是次级保险（态1舞台移除的焦点清除同帧竞态），
            // 另有 AppRoot 下层屏障兜底。
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { pauseFocus.requestFocus() }
            }
            ControlsBar(
                player = player,
                isPlaying = isPlaying,
                durationMs = durationMs,
                speedLabel = "${speed}x",
                qualityLabel = "${activeQuality ?: preferredQuality}P",
                pauseFocus = pauseFocus,
                onPrev = { if (currentEpisode > 1) currentEpisode -= 1 },
                onNext = { if (currentEpisode < episodeCount) currentEpisode += 1 },
                onEpisodes = { overlay = OverlayState.EPISODES },
                onSpeed = { overlay = OverlayState.SPEED },
                onQuality = { overlay = OverlayState.QUALITY },
                onSettings = { overlay = OverlayState.SETTINGS },
                onDismiss = { overlay = OverlayState.NONE },
            )
        }

        // 倍速弹层（操作栏入口，2026-11-07 用户拍板从设置挪操作栏）
        if (overlay == OverlayState.SPEED) {
            val popupFocus = remember { FocusRequester() }
            // 等帧理由同态 2
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { popupFocus.requestFocus() }
            }
            OptionPopup(
                title = "倍速",
                options = SPEED_OPTIONS.map { "${it}x" },
                current = "${speed}x",
                initialFocus = popupFocus,
                onPick = { label ->
                    scope.launch { settings.setSpeed(label.removeSuffix("x").toFloat()) }
                    osd = "倍速 $label"
                    overlay = OverlayState.CONTROLS
                },
            )
        }

        // 清晰度弹层（操作栏入口；档位来自当前集线路，选值即设上限并原地换流）
        if (overlay == OverlayState.QUALITY) {
            val popupFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { popupFocus.requestFocus() }
            }
            OptionPopup(
                title = "清晰度",
                options = options.map { it.quality }.distinct().sortedDescending().map { "${it}P" },
                current = activeQuality?.let { "${it}P" },
                initialFocus = popupFocus,
                onPick = { label ->
                    val q = label.removeSuffix("P").toInt()
                    // 上限照收紧（跨集偏好），但仅当选后实际线路会变才原地换流——
                    // 如上限 1080 实际本就播 720，点"720P"不该断流重载同一条线路
                    preferredQuality = q
                    if (q != activeQuality) {
                        prepareAt(player.currentPosition)
                        osd = "清晰度 $label"
                    }
                    overlay = OverlayState.CONTROLS
                },
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

        // 态 2' 设置面板（清晰度/倍速挪操作栏后仅剩自动连播）
        if (overlay == OverlayState.SETTINGS) {
            val settingsFocus = remember { FocusRequester() }
            // 入口聚焦开关（等帧理由同态 2）
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { settingsFocus.requestFocus() }
            }
            SettingsPanel(
                autoNext = autoNext,
                initialFocus = settingsFocus,
                onAutoNext = { v -> scope.launch { settings.setAutoNext(v) } },
                onClose = { overlay = OverlayState.CONTROLS },
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
    speedLabel: String,
    qualityLabel: String,
    pauseFocus: FocusRequester,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onSpeed: () -> Unit,
    onQuality: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var hideTick by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    // 5s 无操作收起（票05：呼出即开始计时，任意遥控键/点击重置）
    LaunchedEffect(hideTick) {
        delay(CONTROLS_AUTO_HIDE_MS)
        onDismiss()
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
            .onPreviewKeyEvent { e ->
                // ↑ 收起选项栏（SPEC §3.4 态2）；其余方向/OK 键重置 5s 收起计时
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onDismiss(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    -> {
                        hideTick++; false
                    }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        // 进度行：进度条 + 时间（2026-11-07 用户拍板：暂停键移下行按钮行，不与进度条同行）
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
        // 按钮行：暂停紧邻上一集左侧；传输类用图标（2026-11-07 用户拍板），倍速/清晰度显示当前值
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ControlButton(
                text = if (isPlaying) "⏸" else "▶",
                fontSize = 18.sp,
                horizontal = 15.dp,
                focusRequester = pauseFocus,
                onClick = {
                    if (isPlaying) player.pause() else player.play()
                    hideTick++
                },
            )
            ControlButton("⏮", fontSize = 18.sp, horizontal = 15.dp, onClick = { onPrev(); hideTick++ })
            ControlButton("⏭", fontSize = 18.sp, horizontal = 15.dp, onClick = { onNext(); hideTick++ })
            ControlButton("☰", fontSize = 18.sp, horizontal = 15.dp, onClick = { onEpisodes(); hideTick++ })
            ControlButton(speedLabel, onClick = onSpeed)
            ControlButton(qualityLabel, onClick = onQuality)
            ControlButton("⚙", fontSize = 18.sp, horizontal = 15.dp, onClick = { onSettings(); hideTick++ })
        }
    }
}

/**
 * 装载续播位置决策（票05 断点不丢）：
 * - 同集重试（错误页"重试"/loadToken++）→ 从最近播放位置续播；
 * - 首次装载且为起始集 → 从入参断点（历史续播）起播；
 * - 换集（含浮层选回起始集）→ 从头播。
 */
internal fun resolveStartMs(
    currentEpisode: Int,
    lastLoadedEpisode: Int,
    startEpisode: Int,
    startPositionMs: Long,
    resumeMs: Long,
): Long = when {
    currentEpisode == lastLoadedEpisode -> resumeMs
    lastLoadedEpisode == -1 && currentEpisode == startEpisode -> startPositionMs
    else -> 0L
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun ControlButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    fontSize: TextUnit = 15.sp,
    horizontal: Dp = 20.dp,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    val shape = RoundedCornerShape(8.dp)
    // ⚠️ focusRequester 必须在 clickable（产生 focusable 的地方）之前：挂在其后不绑定，
    // requestFocus() 抛 "not attached" 被外层吞掉——2026-11-07 播放器呼出后焦点爬行的根因
    var m = Modifier
        .height(44.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    m = m
        .clip(shape)
        .background(if (focused || selected) PrimarySoft else KeySurface, shape)
        .border(2.dp, if (focused) Primary else Color.Transparent, shape)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Box(m.padding(horizontal = horizontal), contentAlignment = Alignment.Center) {
        Text(text, color = if (focused) OnBg else if (selected) Primary else OnBgVariant, fontSize = fontSize)
    }
}

/** 倍速/清晰度快捷弹层（操作栏入口）：居中小面板，选值即回操作栏。 */
@Composable
private fun OptionPopup(
    title: String,
    options: List<String>,
    current: String?,
    initialFocus: FocusRequester,
    onPick: (String) -> Unit,
) {
    // current 不在选项中（如持久上限高于本集线路）时落到首项
    val focusTarget = options.firstOrNull { it == current } ?: options.firstOrNull()
    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(KeySurfaceDim)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = OnBg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (options.isEmpty()) {
                Text("暂无可选项", color = OnBgVariant, fontSize = 14.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { opt ->
                        ControlButton(
                            text = opt,
                            selected = opt == current,
                            focusRequester = if (opt == focusTarget) initialFocus else null,
                            onClick = { onPick(opt) },
                        )
                    }
                }
            }
        }
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
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
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

/** 态 3 选集：右侧抽屉（2026-11-07 用户拍板，替换全屏铺满）——左 55% 透画面、右 45% 菜单。 */
@Composable
private fun EpisodeOverlay(
    episodes: List<Episode>,
    current: Int,
    watched: Set<Int>,
    onPick: (Int) -> Unit,
) {
    val groupCount = (episodes.size + 49) / 50
    var selectedTab by remember { mutableIntStateOf(((current - 1) / 50).coerceAtLeast(0)) }
    val gridState = rememberLazyGridState()
    val currentFocus = remember { FocusRequester() }
    // 入口聚焦当前集：先滚进视口再请求（chip 未组合时 requester 悬空，票11 坑），runCatching 兜底
    LaunchedEffect(Unit) {
        gridState.scrollToItem(((current - 1) % 50).coerceAtLeast(0))
        runCatching { currentFocus.requestFocus() }
    }
    Row(Modifier.fillMaxSize()) {
        // 左侧透画面：不设背景不响应，遥控返回键关抽屉（BackHandler 已挂）
        Spacer(Modifier.weight(0.55f).fillMaxHeight())
        Column(
            Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .background(Scrim.copy(alpha = 0.96f))
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text("选集", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                items((0 until groupCount).toList()) { tab ->
                    val from = tab * 50 + 1
                    val to = minOf((tab + 1) * 50, episodes.size)
                    TabButton("$from-$to", tab == selectedTab) { selectedTab = tab }
                }
            }
            val gridEpisodes = episodes.filter { (it.number - 1) / 50 == selectedTab }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(gridEpisodes, key = { it.episodeId }) { ep ->
                    val look = when {
                        ep.number == current -> ChipLook.CURRENT
                        watched.contains(ep.number) -> ChipLook.WATCHED
                        else -> ChipLook.NORMAL
                    }
                    ChipOverlay(
                        ep.number,
                        look,
                        modifier = if (ep.number == current) Modifier.focusRequester(currentFocus) else Modifier,
                    ) { onPick(ep.number) }
                }
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
private fun ChipOverlay(index: Int, look: ChipLook, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    val shape = RoundedCornerShape(4.dp)
    val (bg, fg, border) = when (look) {
        ChipLook.WATCHED -> Triple(KeySurfaceDim, Faint, Color.Transparent)
        ChipLook.CURRENT -> Triple(PrimarySoft, Primary, Primary)
        ChipLook.NORMAL -> Triple(KeySurface, OnBgVariant, Color.Transparent)
    }
    Box(
        modifier
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

/** 态 2' 设置：自动连播开关（清晰度/倍速 2026-11-07 挪操作栏后仅剩此项）。 */
@Composable
private fun SettingsPanel(
    autoNext: Boolean,
    initialFocus: FocusRequester,
    onAutoNext: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.96f)).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("设置", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("自动连播（${if (autoNext) "开" else "关"}）", color = OnBgVariant, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlButton(if (autoNext) "开" else "关", onClick = { onAutoNext(!autoNext) }, focusRequester = initialFocus)
        }
        ControlButton("返回", onClick = onClose)
    }
}
