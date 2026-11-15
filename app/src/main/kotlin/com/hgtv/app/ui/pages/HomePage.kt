package com.hgtv.app.ui.pages

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.hgtv.app.data.OpenhongguoSource
import com.hgtv.app.data.WatchEntry
import com.hgtv.app.domain.SeriesCard
import com.hgtv.app.domain.SeriesDetail
import com.hgtv.app.ui.SectionHeader
import com.hgtv.app.ui.focusGlow
import com.hgtv.app.ui.rememberCoverRequest
import com.hgtv.app.ui.rememberRowPrefetch
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.inkBackground
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.PosterPlaceholder
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.ProgressTrack
import com.hgtv.app.ui.theme.Scrim
import com.hgtv.app.ui.theme.SeasonGold
import com.hgtv.app.ui.theme.ShimmerScope
import com.hgtv.app.ui.theme.SkeletonBlock
import com.hgtv.app.ui.theme.Surface

/** hero 主按钮文案（DESIGN §6）：有进度"继续播放 · 第N集"，无则"播放第 1 集"。 */
internal fun heroButtonText(resume: WatchEntry?): String =
    if (resume != null) "继续播放 · 第${resume.episode}集" else "播放第 1 集"

/** hero 卡片高（DESIGN §6 ≈180dp，模拟器实调）；海报 1.15x 上探出卡顶。 */
private val HeroCardH = 180.dp
private val HeroPosterH = HeroCardH * 1.15f

/** 首页（SPEC §3.1 v2）：item0 静态 hero（热播榜 NO.1）+ 继续观看 / 热播榜 两行。
 *  进页焦点落 hero 主按钮；hero 数据失败静默不渲染，回退 v1（有历史落继续观看首卡，
 *  无历史落热播榜首卡）。2026-10-31 "为你推荐"行移除（票10：与热播榜同源重复）。 */
@Composable
fun HomePage(
    source: OpenhongguoSource,
    history: List<WatchEntry>,
    historyReady: Boolean,
    active: Boolean,
    entryFocus: FocusRequester,
    onOpenDetail: (String) -> Unit,
    onResume: (WatchEntry) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rankItems by remember { mutableStateOf<List<SeriesCard>?>(null) }
    var rankError by remember { mutableStateOf<String?>(null) }
    // hero = 热播榜 NO.1 + 一次 seriesDetail（数据源层 5min TTL 缓存，回首页零配额）；
    // 失败静默不渲染（票20：骨架期常驻 item0 占最终高度，杜绝列表跳动/锚定悬空）
    var heroDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    var heroFailed by remember { mutableStateOf(false) }
    val heroCard = rankItems?.firstOrNull()

    LaunchedEffect(Unit) {
        try {
            rankItems = source.hotRank()
        } catch (e: Exception) {
            android.util.Log.w("HgHome", "热播榜失败", e)
            rankError = e.message ?: "热播榜加载失败"
        }
    }

    LaunchedEffect(heroCard?.seriesId) {
        val sid = heroCard?.seriesId ?: return@LaunchedEffect
        heroDetail = null
        heroFailed = false
        heroDetail = try {
            source.seriesDetail(sid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消直抛：切 sid 不误伤新请求（票14 坑）
        } catch (e: Exception) {
            android.util.Log.w("HgHome", "hero 详情失败，静默降级回退 v1 落位", e)
            heroFailed = true
            null
        }
    }

    // hero 是否已定局（成功或失败）：落位效果等定局再放焦点，杜绝"先落继续观看首卡、
    // hero 半秒后插入列表头把焦点冲到侧栏 + hero 悬在视口外不被组合"的竞态（模拟器实测）
    val heroDecided = rankError != null ||
        (rankItems != null && (heroCard == null || heroDetail != null || heroFailed))

    val heroVisible = heroCard != null && heroDetail != null
    val heroEntry = if (heroVisible) history.find { it.seriesId == heroCard!!.seriesId } else null

    // focusPlaced 前置声明：hero 主按钮自取焦点成功时经 onClaimed 置位（见 HomeHero）
    var focusPlaced by rememberSaveable { mutableStateOf(false) }
    // 用户任意按键即放弃 hero 自动落焦（评审 finding：claim 窗口内导航走会被循环拽回）
    val userTouched = remember { mutableStateOf(false) }
    // hero 按钮快慢环全耗尽仍无焦点：放行 v1 回退（否则 heroVisible=true 挡住回退，整页无入口焦点）
    var heroClaimLost by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            // 预览阶段全按键可见（不消费放行）：用户一动手就不再自动 claim 焦点
            .onPreviewKeyEvent { userTouched.value = true; false }
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(44.dp), // DESIGN §4 v2.1：分区呼吸感（苹果 HIG 层级三件套之一）
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ---- item0：hero。key 恒定、内容三态原位换装（骨架→hero→失败塌陷），绝不移除重插：
        //      重插会被 LazyColumn 锚定在下方行，hero 悬在视口外永不组合（模拟器实测竞态）----
        item(key = "hero") {
            when {
                rankItems == null && rankError == null -> ShimmerScope {
                    Box(Modifier.fillMaxWidth().height(HeroPosterH)) {
                        SkeletonBlock(
                            Modifier.fillMaxWidth().height(HeroCardH).align(Alignment.BottomStart),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
                heroVisible -> HomeHero(
                    detail = heroDetail!!,
                    rankLabel = "热播榜 NO.${heroCard!!.rank}",
                    resume = heroEntry,
                    focusRequester = entryFocus,
                    autoClaim = active && !focusPlaced,
                    abortClaim = { userTouched.value },
                    onClaimed = { focusPlaced = true },
                    onClaimLost = { heroClaimLost = true },
                    onPlay = {
                        val card = heroCard!!
                        // 直接起播走既有 onResume/onPlay 链（AppRoot autoResume 加载完详情自动进播放）
                        onResume(heroEntry ?: WatchEntry(
                            seriesId = card.seriesId, title = card.title, cover = card.cover,
                            episode = 1, episodeCount = card.episodeCount,
                            positionMs = 0L, durationMs = 0L, updatedAt = 0L,
                        ))
                    },
                )
                else -> Box(Modifier) // 榜失败/详情失败：静默不渲染（高度塌陷，焦点回退 v1 落位）
            }
        }

        // ---- 继续观看 ----
        if (!historyReady || history.isNotEmpty()) {
            item(key = "resume-head") {
                SectionHeader(
                    "继续观看",
                    count = history.takeIf { historyReady }?.size?.let { "$it 部" },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            item(key = "resume-row") {
                if (!historyReady) {
                    SkeletonRow()
                } else {
                    // 列表懒加载预取（SPEC §8）：焦点落到第 i 张卡时预热其后 ahead 张（低端 2 / 常规 4）
                    val resumePrefetch = rememberRowPrefetch(history.map { it.cover })
                    // contentPadding 预留焦点放大溢出区：1.07x 海报卡上下溢 ~10dp，余量 14dp 覆盖
                    // 溢出+2dp；余量不足时 bringIntoView 拿"含缩放的 bounds"对齐视口会扯动外层
                    // LazyColumn 垂直微滚 ±2px 再回位＝上方行抖动（2026-11-15 帧级二分实锤）；
                    // start 6dp 同时让首卡聚焦左缘完整
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                    ) {
                        itemsIndexed(history, key = { _, it -> it.seriesId }) { index, entry ->
                            PosterCard(
                                title = entry.title,
                                badge = entry.episodeBadge,
                                cover = entry.cover,
                                progress = entry.progressFraction,
                                // heroDecided 门：hero 未定局时不挂 requester——AppRoot 的 t0 重试环
                                // 会提前把焦点塞给此卡并触发列表下滚，把 hero 顶出视口（实测竞态）
                                focusRequester = if (index == 0 && !heroVisible && heroDecided) entryFocus else null,
                                onFocused = { resumePrefetch.onFocus(index) },
                                onClick = { onResume(entry) },
                            )
                        }
                        item(key = "more") { MoreCard(onClick = onOpenHistory) }
                    }
                }
            }
        }

        // ---- 热播榜 ----
        item(key = "rank-head") {
            SectionHeader("热播榜", count = rankItems?.size?.let { "$it 部" }, modifier = Modifier.padding(start = 4.dp))
        }
        item(key = "rank-row") {
            when {
                rankItems == null && rankError == null -> SkeletonRow()
                rankError != null -> RowHint("热播榜加载失败：$rankError")
                else -> {
                    val rankList = rankItems.orEmpty()
                    val rankPrefetch = rememberRowPrefetch(rankList.map { it.cover })
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        // 同继续观看行：14dp 余量吸收放大溢出，杜绝 bringIntoView 扯动 LazyColumn（上方行抖动）
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                    ) {
                        itemsIndexed(rankList, key = { _, it -> "rank-" + it.seriesId }) { index, item ->
                            PosterCard(
                                title = item.title,
                                badge = "NO.${item.rank}",
                                cover = item.cover,
                                focusRequester = if (index == 0 && !heroVisible && heroDecided && history.isEmpty()) entryFocus else null,
                                onFocused = { rankPrefetch.onFocus(index) },
                                onClick = { onOpenDetail(item.seriesId) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 焦点落位（SPEC §3.1 v2：hero 主按钮优先，缺席回退 v1）。hero 成功路径不经此效果——
    // HomeHero 主按钮自取（requester 同帧迁移时 requestFocus 会"无异常但没给焦点"的静默
    // 空成功，按钮侧以真实 focused 态校验重试；此效果只兜 hero 失败/缺席的 v1 回退）。
    // ⚠️ 入口焦点重试共三处（AppRoot t0 环 / 本处 8×50ms / hero 快慢环）——调窗口三处一起看
    // （评审 finding #9，语义各异不强行统一）。
    LaunchedEffect(active, historyReady, history.size, rankItems?.size, rankError, heroDetail?.seriesId, heroFailed, heroClaimLost) {
        if (!active || !historyReady || focusPlaced) return@LaunchedEffect
        // hero 在场且 claim 未耗尽时让路（自取焦点成功路径不进此效果）；
        // heroClaimLost=按钮快慢环全失败，heroVisible 也要放行 v1 回退兜住入口焦点
        if (!heroDecided || (heroVisible && !heroClaimLost)) return@LaunchedEffect
        val resumeReady = history.isNotEmpty()
        val rankReady = !rankItems.isNullOrEmpty()
        if (!resumeReady && !rankReady) return@LaunchedEffect
        repeat(8) {
            if (runCatching { entryFocus.requestFocus() }.isSuccess) {
                focusPlaced = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
        }
    }
}

/** 静态 hero（DESIGN §6 v2，Q2-A）：热播榜 NO.1 长驻。海报模糊铺底（复用详情页
 *  PosterBackdrop 手法：1.15x + blur 28dp，API<31 加重压暗压噪点）+ 左文右图；
 *  右侧竖版海报 hero×1.15 高、底部对齐、上探出卡顶；**hero 内仅主按钮可聚焦**。 */
@Composable
private fun HomeHero(
    detail: SeriesDetail,
    rankLabel: String,
    resume: WatchEntry?,
    focusRequester: FocusRequester,
    autoClaim: Boolean,
    abortClaim: () -> Boolean,
    onClaimed: () -> Unit,
    onClaimLost: () -> Unit,
    onPlay: () -> Unit,
) {
    val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxWidth().height(HeroPosterH)) {
        // 模糊铺底卡（180dp，圆角 12）
        Box(
            Modifier
                .fillMaxWidth()
                .height(HeroCardH)
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = rememberCoverRequest(detail.cover),
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
                            listOf(Color(0x99101319), Color(0xE6101319))
                        } else {
                            listOf(Color(0xCC101319), Color(0xF0101319))
                        },
                    ),
                ),
            )
        }
        // 右侧竖版海报：高 hero×1.15 底部对齐（上探出铺底卡顶），圆角 8 + 投影
        AsyncImage(
            model = rememberCoverRequest(detail.cover),
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp)
                .height(HeroPosterH)
                .aspectRatio(9f / 16f)
                .graphicsLayer {
                    shape = RoundedCornerShape(8.dp)
                    shadowElevation = 12.dp.toPx()
                    clip = false
                }
                .clip(RoundedCornerShape(8.dp))
                .background(PosterPlaceholder),
        )
        // 左文区：眉标 / 标题 / 元信息 chips / 简介 + 主按钮
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 140.dp, top = 12.dp, bottom = 12.dp)
                .height(HeroCardH),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(rankLabel, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                detail.title,
                color = OnBg,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroChip(detail.categories.firstOrNull() ?: "短剧")
                HeroChip("共${detail.episodeCnt}集")
                HeroChip(if (detail.finished) "已完结" else "连载中")
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    detail.intro,
                    color = OnBgVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HeroPlayButton(
                    text = heroButtonText(resume),
                    focusRequester = focusRequester,
                    autoClaim = autoClaim,
                    abortClaim = abortClaim,
                    onClaimed = onClaimed,
                    onClaimLost = onClaimLost,
                    onClick = onPlay,
                )
            }
        }
    }
}

/** hero 主按钮（DESIGN §6）：inkStops 墨渐变胶囊 44dp，白字；hero 内唯一可聚焦件。
 *  自取焦点：requester 同帧从首卡迁来时 requestFocus 会静默空成功（无异常但不授权），
 *  故不信调用结果，以本按钮真实 focused 态为准重试。 */
@Composable
private fun HeroPlayButton(
    text: String,
    focusRequester: FocusRequester,
    autoClaim: Boolean,
    abortClaim: () -> Boolean,
    onClaimed: () -> Unit,
    onClaimLost: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(autoClaim) {
        if (!autoClaim) return@LaunchedEffect
        // 快环 12×60ms 覆盖常规授予；慢环再撑 ~3s 兜偶发冷启（同码实测一次 19ms 即中、
        // 一次 720ms 内 12 连拒——授予时机非确定，拉长窗口兜底，命中即停不抢用户后续操作）。
        // abortClaim：用户按下任意键（页面根 onPreviewKeyEvent 置位）即停——冷启窗口内
        // 用户已导航走时，循环继续 requestFocus 会把焦点强行拽回 hero 按钮（评审 finding）。
        // 全耗尽仍无焦点：onClaimLost 放行 v1 回退，不让整页入口焦点悬空。
        var tries = 0
        var aborted = false
        outer@ for (delayMs in List(12) { 60L } + List(10) { 300L }) {
            if (abortClaim()) {
                aborted = true
                break@outer
            }
            runCatching { focusRequester.requestFocus() }
            tries++
            kotlinx.coroutines.delay(delayMs)
            if (focused) {
                android.util.Log.d("HgHome", "claim 命中（第${tries}次）")
                onClaimed()
                break@outer
            }
        }
        if (!aborted && !focused) onClaimLost()
    }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(44.dp)
            .focusRequester(focusRequester)
            // hero 是首行：UP 不许逃逸出列表落侧栏（几何最近邻会抓到侧栏搜索项），
            // 指回自身 requester = UP 原地无动作（SPEC §4 跨行规则）
            .focusProperties { up = focusRequester }
            .focusGlow(focused, shape = shape, borderColor = Color.White)
            .clip(shape)
            .inkBackground()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun HeroChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Scrim)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = OnBgVariant, fontSize = 12.sp, maxLines = 1)
    }
}

/** 骨架行：与海报卡行同构（5 张骨架卡），加载到达原位无缝替换；块群共享扫光时钟。 */
@Composable
private fun SkeletonRow() {
    ShimmerScope {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp)) {
            repeat(5) { SkeletonPosterCard() }
        }
    }
}

/** 骨架海报卡：与 PosterCard 同构（150dp 宽 9:16 + 标题条）。 */
@Composable
private fun SkeletonPosterCard() {
    Column(Modifier.width(150.dp)) {
        SkeletonBlock(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f),
            shape = RoundedCornerShape(5.dp),
        )
        SkeletonBlock(
            Modifier.padding(top = 8.dp).width(90.dp).height(12.dp),
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
private fun RowHint(text: String) {
    Text(text, fontSize = 14.sp, color = Dim, modifier = Modifier.padding(start = 4.dp))
}

/** 海报卡（DESIGN §6）：9:16 海报、左下角标（scrim 底，季号 seasonGold）、底缘 3dp 进度条（progress 非空才画）。 */
@Composable
fun PosterCard(
    title: String,
    badge: String,
    cover: String,
    modifier: Modifier = Modifier.width(150.dp),
    progress: Float? = null,
    focusedScale: Float = 1.07f,
    seasonBadge: String = "",
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused?.invoke()
    }
    Column(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGlow(focused, shape = RoundedCornerShape(12.dp), focusedScale = focusedScale)
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
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(9f / 16f)
            .focusGlow(focused, shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) OnBg else Dim, fontSize = 16.sp)
    }
}
