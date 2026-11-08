package com.hgtv.app.ui.pages

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hgtv.app.domain.Album
import com.hgtv.app.domain.Season
import com.hgtv.app.domain.chineseSeason
import com.hgtv.app.domain.seasonStatusLabel
import com.hgtv.app.ui.focusScale
import com.hgtv.app.ui.theme.Bg
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.Faint
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.KeySurfaceDim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.SeasonGold
import kotlinx.coroutines.delay

/**
 * 全屏合集页（票17 Q6-B）：页头合集名 + 合集简介 + 共N季；季卡网格（金色"第N季"角标 +
 * 集数/状态文案，当前季金框高亮，未上线季置灰不可点），OK 跳该季详情。
 * 挂在详情覆盖层之上（AppRoot 条件组合，同播放器模式——详情页卸载，返回即整层关闭）。
 */
@Composable
fun AlbumPage(
    album: Album,
    /** 当前季序号（1 起；0 = 未知不高亮）。来自当前详情的 seasonIndex。 */
    currentSeasonIndex: Int,
    onOpenSeason: (String) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    // 入口焦点：首个可点季卡持有（焦点铁律①——requester 挂首个可见交互件；未上线季不可点，
    // 持焦者首选 released 卡）。退化兜底：全季未上线（或当前季不在列表）时挂首卡，
    // 8×50ms 重试全空转会让页面零焦点（评审 finding）。数据注入前短暂悬空，8×50ms 重试（HistoryPage 同款）。
    val firstCardFocus = remember { FocusRequester() }
    val firstReleased = album.seasons.indexOfFirst { it.released }
    val entryIndex = if (firstReleased >= 0) firstReleased else 0

    LaunchedEffect(Unit) {
        repeat(8) {
            if (runCatching { firstCardFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 48.dp, vertical = 32.dp)) {
        // 页头：合集名 + 共N季 + 合集级简介（季无每季简介，CONTEXT.md）
        Text(
            album.title.ifEmpty { "合集" },
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = OnBg),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "共 ${album.seasons.size} 季",
            style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
        )
        if (album.description.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                album.description,
                style = TextStyle(fontSize = 14.sp, color = OnBgVariant),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            itemsIndexed(album.seasons, key = { _, s -> s.seriesId }) { index, season ->
                SeasonCard(
                    season = season,
                    current = season.index == currentSeasonIndex,
                    focusRequester = if (index == entryIndex) firstCardFocus else null,
                    onClick = { onOpenSeason(season.seriesId) },
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(
    season: Season,
    current: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val released = season.released
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = focusScale(focused)
    val shape = RoundedCornerShape(8.dp)
    // 当前季金框（SeasonGold）标记；聚焦时 Primary 焦点框优先（焦点视觉全 app 唯一口径）。
    // 未上线季聚焦用暗框（Dim）——可聚焦可浏览但不可点（Netflix 惯例），不与正常焦点框混淆
    val borderColor = when {
        focused && released -> Primary
        focused -> Dim
        current -> SeasonGold
        else -> Color.Transparent
    }
    var m = Modifier
        .fillMaxWidth()
        .height(112.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(if (released) KeySurface else KeySurfaceDim, shape)
        .border(if (current && !focused) 3.dp else 2.dp, borderColor, shape)
    if (focusRequester != null) m = m.focusRequester(focusRequester) // 未上线卡 focusable 也可持有入口焦点（全未上线兜底）
    // 未上线季置灰不可点：不挂 clickable，但挂 focusable 持焦——否则整页方向搜索可能无目标
    // （单 released 卡在首列时 RIGHT 清焦点穿透到下层，2026-11-08 模拟器实测；CENTER 无反应即"不可点"）
    if (released) {
        m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        m = m.focusable(interactionSource = interaction)
    }
    Box(m, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "第${chineseSeason(season.index)}季",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = if (released) SeasonGold else Faint,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (released) "${season.episodeCount}集 · ${seasonStatusLabel(season)}" else "未上线",
                style = TextStyle(fontSize = 14.sp),
                color = if (released) OnBgVariant else Faint,
            )
        }
    }
}
