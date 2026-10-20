package com.hgtv.app.ui.pages

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hgtv.app.net.HongguoWeb
import com.hgtv.app.net.Suggestion
import com.hgtv.app.net.WebDrama
import com.hgtv.app.ui.theme.Dim
import com.hgtv.app.ui.theme.KeySurface
import com.hgtv.app.ui.theme.KeySurfaceDim
import com.hgtv.app.ui.theme.OnBg
import com.hgtv.app.ui.theme.OnBgVariant
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.PrimarySoft

/** 搜索历史（进程内 LRU + SharedPreferences 持久，最多 10 条）。 */
object SearchHistory {
    private const val PREFS = "hgtv_search"
    private const val KEY = "history"
    private var cache: MutableList<String>? = null

    fun load(ctx: Context): List<String> {
        cache?.let { return it.toList() }
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val list = raw.split('\n').filter { it.isNotBlank() }.toMutableList()
        cache = list
        return list.toList()
    }

    fun push(ctx: Context, word: String) {
        val list = load(ctx).toMutableList()
        list.remove(word)
        list.add(0, word)
        while (list.size > 10) list.removeAt(list.size - 1)
        cache = list
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) {
        cache = mutableListOf()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** 搜索页（SPEC §3.2 / 票 06）：软键盘 + 双候选区 + 历史 + 结果网格。 */
@Composable
fun SearchPage(
    web: HongguoWeb,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var pinyinMode by remember { mutableStateOf(true) } // 拼 / EN
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var nameHits by remember { mutableStateOf<List<WebDrama>>(emptyList()) }
    var history by remember { mutableStateOf(SearchHistory.load(ctx)) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<WebDrama>?>(null) } // null=未搜索
    var resultWord by remember { mutableStateOf("") }

    // 拼模式下：query 变化 → 联想词 + 名称索引（双候选区）
    LaunchedEffect(query, pinyinMode) {
        if (query.isBlank()) {
            suggestions = emptyList()
            nameHits = emptyList()
        } else {
            runCatching { suggestions = web.suggestions(query, 10) }
            runCatching { nameHits = web.nameIndex(query) }
        }
    }

    fun doSearch(word: String) {
        if (word.isBlank()) return
        SearchHistory.push(ctx, word)
        history = SearchHistory.load(ctx)
        resultWord = word
        searching = true
        results = null
    }

    // 执行搜索
    LaunchedEffect(searching, resultWord) {
        if (!searching) return@LaunchedEffect
        try {
            results = web.search(resultWord).dramas
        } catch (e: Exception) {
            results = emptyList()
        } finally {
            searching = false
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(
            "搜索",
            style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            color = OnBg,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        val r = results
        if (r == null && !searching) {
            SearchBoard(
                query = query,
                pinyinMode = pinyinMode,
                suggestions = suggestions,
                nameHits = nameHits,
                history = history,
                onKey = { query = (query + it).take(30) },
                onBackspace = { query = query.dropLast(1) },
                onToggleMode = { pinyinMode = !pinyinMode },
                onPickSuggestion = { doSearch(it.name) },
                onPickName = { doSearch(it.title) },
                onPickHistory = { doSearch(it) },
                onClearHistory = { SearchHistory.clear(ctx); history = emptyList() },
                onSearch = { doSearch(query) },
            )
        } else {
            ResultGrid(
                word = resultWord,
                loading = searching,
                dramas = r ?: emptyList(),
                onOpenDetail = onOpenDetail,
                onBack = { results = null },
            )
        }
    }
}

@Composable
private fun SearchBoard(
    query: String,
    pinyinMode: Boolean,
    suggestions: List<Suggestion>,
    nameHits: List<WebDrama>,
    history: List<String>,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onPickSuggestion: (Suggestion) -> Unit,
    onPickName: (WebDrama) -> Unit,
    onPickHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSearch: () -> Unit,
) {
    val boardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { boardFocus.requestFocus() } }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        // 左：输入区 + 候选 + 键盘
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QueryBox(query, pinyinMode)
            if (query.isNotBlank()) {
                SuggestRow("联想", suggestions.take(6).map { it.name }, onPick = { w ->
                    suggestions.find { it.name == w }?.let(onPickSuggestion)
                })
                if (nameHits.isNotEmpty()) {
                    SuggestRow("直达", nameHits.take(4).map { it.title }, onPick = { w ->
                        nameHits.find { it.title == w }?.let(onPickName)
                    })
                }
            }
            Keyboard(
                pinyinMode = pinyinMode,
                onKey = onKey,
                onBackspace = onBackspace,
                onToggleMode = onToggleMode,
                onSearch = onSearch,
                modifier = Modifier.weight(1f).focusRequester(boardFocus),
            )
        }
        // 右：搜索历史
        Column(Modifier.weight(0.45f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜索历史", color = OnBgVariant, fontSize = 15.sp)
                if (history.isNotEmpty()) {
                    Text(
                        "清空",
                        color = Dim,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(KeySurfaceDim)
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (history.isEmpty()) {
                Text("暂无搜索历史", color = Dim, fontSize = 13.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history) { word ->
                        Chip(word) { onPickHistory(word) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryBox(query: String, pinyinMode: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KeySurface)
            .border(1.dp, Primary, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (query.isEmpty()) "输入剧名 / 拼音首字母" else query, color = if (query.isEmpty()) Dim else OnBg, fontSize = 18.sp)
        Text(if (pinyinMode) "拼" else "EN", color = Primary, fontSize = 14.sp)
    }
}

@Composable
private fun SuggestRow(label: String, words: List<String>, onPick: (String) -> Unit) {
    if (words.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Primary, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(words) { w -> Chip(w) { onPick(w) } }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) PrimarySoft else KeySurface)
            .border(1.dp, if (focused) Primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (focused) OnBg else OnBgVariant, fontSize = 14.sp, maxLines = 1)
    }
}

private val KEY_ROWS = listOf(
    "1234567890",
    "QWERTYUIOP",
    "ASDFGHJKL",
    "ZXCVBNM",
)

@Composable
private fun Keyboard(
    pinyinMode: Boolean,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    KeyButton(
                        label = ch.toString(),
                        weight = 1f,
                        onClick = { onKey(ch.toString()) },
                    )
                }
                if (row.length < 10) {
                    when (row) {
                        "ASDFGHJKL" -> KeyButton(label = "空格", weight = 1f, onClick = { onKey(" ") })
                        "ZXCVBNM" -> {
                            KeyButton(label = "⌫", weight = 1.6f, onClick = onBackspace)
                            KeyButton(label = if (pinyinMode) "拼/EN" else "EN/拼", weight = 1.6f, onClick = onToggleMode)
                            SearchKey(onSearch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyButton(label: String, weight: Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .weight(weight)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Primary else KeySurface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.White else OnBg, fontSize = if (label.length == 1) 18.sp else 13.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchKey(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .weight(2.2f)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFFFF8A65) else Primary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("搜索", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResultGrid(
    word: String,
    loading: Boolean,
    dramas: List<WebDrama>,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackKey(onBack)
            Text(
                if (loading) "正在搜索「$word」…" else "「$word」的结果（${dramas.size} 部）",
                color = OnBg,
                fontSize = 18.sp,
            )
        }
        if (!loading && dramas.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有找到「$word」相关短剧", color = Dim, fontSize = 16.sp)
            }
        } else if (!loading) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(dramas, key = { it.seriesId }) { d ->
                    PosterCard(
                        title = d.title,
                        badge = buildString {
                            if (d.episodeCnt.isNotEmpty()) append("${d.episodeCnt}集")
                            if (d.score.isNotEmpty()) append(" ${d.score}")
                        }.trim(),
                        cover = d.cover,
                        onClick = { onOpenDetail(d.seriesId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackKey(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .height(40.dp)
            .width(88.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Primary else KeySurface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("← 返回", color = if (focused) Color.White else OnBgVariant, fontSize = 14.sp)
    }
}
