package com.hgtv.app.ui.pages

import android.content.Context
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.hgtv.app.ui.theme.Outline
import com.hgtv.app.ui.theme.Primary
import com.hgtv.app.ui.theme.PrimarySoft
import kotlinx.coroutines.delay

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

    /** LRU 规整：新词提首、同名去重、超限淘汰最旧、空白词原样返回。 */
    fun reduce(list: List<String>, word: String, max: Int = 10): List<String> {
        if (word.isBlank()) return list
        return (list - word).toMutableList().apply { add(0, word) }.take(max)
    }

    fun push(ctx: Context, word: String) {
        val list = reduce(load(ctx), word).toMutableList()
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
    var results by remember { mutableStateOf<List<WebDrama>?>(null) } // null=未搜索；返回键盘保留
    var resultWord by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var emptyNotice by remember { mutableStateOf("") } // 空态提示，显示于键盘态候选区（SPEC：空态焦点回键盘）

    // 联想 300ms 停顿刷新：每次按键重启 effect，天然防抖（SPEC §3.2）
    LaunchedEffect(query, pinyinMode) {
        if (emptyNotice.isNotEmpty()) emptyNotice = "" // 用户重新打字即撤销空态提示
        if (query.isBlank()) {
            suggestions = emptyList()
            nameHits = emptyList()
        } else {
            delay(300)
            runCatching { suggestions = web.suggestions(query, 10) }
            runCatching { nameHits = web.nameIndex(query) }
        }
    }

    fun doSearch(word: String) {
        if (word.isBlank()) return
        SearchHistory.push(ctx, word)
        history = SearchHistory.load(ctx)
        emptyNotice = ""
        // 同词已有结果：直接复用，不重查（返回键盘保留输入与结果）
        if (word == resultWord && results?.isNotEmpty() == true && !searching) {
            showResults = true
            return
        }
        resultWord = word
        searching = true
        showResults = true
    }

    // 执行搜索
    LaunchedEffect(searching, resultWord) {
        if (!searching) return@LaunchedEffect
        try {
            results = web.search(resultWord).dramas
            if (results.isNullOrEmpty()) {
                showResults = false // 空态：回键盘 + 候选区居中提示 + 删词建议
                emptyNotice = "没有找到「$resultWord」相关短剧"
            }
        } catch (e: Exception) {
            results = emptyList()
            showResults = false
            emptyNotice = "搜索暂不可用，稍后再试"
        } finally {
            searching = false
        }
    }

    // 遥控返回：结果态先回键盘（保留输入与结果），键盘态交回上级返回链
    BackHandler(enabled = showResults) { showResults = false }

    Column(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(
            "搜索",
            style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            color = OnBg,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        val pinyinCandidates = if (pinyinMode) PinyinFallback.candidates(query) else emptyList()
        if (showResults) {
            ResultGrid(
                word = resultWord,
                loading = searching,
                dramas = results ?: emptyList(),
                onOpenDetail = onOpenDetail,
                onBack = { showResults = false },
            )
        } else {
            SearchBoard(
                query = query,
                pinyinMode = pinyinMode,
                suggestions = suggestions,
                nameHits = nameHits,
                pinyinCandidates = pinyinCandidates,
                history = history,
                emptyNotice = emptyNotice,
                onKey = { ch -> query = (query + if (pinyinMode) ch.lowercase() else ch).take(30) },
                onBackspace = { query = query.dropLast(1) },
                onToggleMode = { pinyinMode = !pinyinMode },
                onPickSuggestion = { doSearch(it) },
                onPickName = { doSearch(it) },
                onPickPinyin = { c ->
                    PinyinFallback.pending(query)?.let { pend -> query = query.dropLast(pend.length) + c }
                },
                onPickHistory = { doSearch(it) },
                onClearHistory = { SearchHistory.clear(ctx); history = emptyList() },
                onSearch = { doSearch(query) },
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
    pinyinCandidates: List<String>,
    history: List<String>,
    emptyNotice: String,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onPickName: (String) -> Unit,
    onPickPinyin: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSearch: () -> Unit,
) {
    val boardFocus = remember { FocusRequester() }
    val firstCandidateFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { boardFocus.requestFocus() } } // 进页/回键盘 落键盘

    val candidatesReady = suggestions.isNotEmpty() || nameHits.isNotEmpty() || pinyinCandidates.isNotEmpty()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QueryBox(query, pinyinMode)
        // 候选区：空态提示 > 空输入历史（显示于联想位）> 联想 / 直达 / 拼音
        when {
            emptyNotice.isNotEmpty() -> EmptyNotice(emptyNotice)
            query.isBlank() -> HistoryRow(history, onPickHistory, onClearHistory)
            else -> {
                // ↓ 直达的候选优先级（联想>直达>拼音）在此单点判定
                val firstFocusRow = when {
                    suggestions.isNotEmpty() -> 0
                    nameHits.isNotEmpty() -> 1
                    else -> 2
                }
                SuggestRow("联想", suggestions.map { it.name }, onPickSuggestion, firstFocus = if (firstFocusRow == 0) firstCandidateFocus else null)
                SuggestRow("直达", nameHits.map { it.title }, onPickName, firstFocus = if (firstFocusRow == 1) firstCandidateFocus else null)
                SuggestRow("拼音", pinyinCandidates, onPickPinyin, firstFocus = if (firstFocusRow == 2) firstCandidateFocus else null)
            }
        }
        Keyboard(
            pinyinMode = pinyinMode,
            candidatesReady = candidatesReady,
            firstCandidateFocus = firstCandidateFocus,
            onKey = onKey,
            onBackspace = onBackspace,
            onToggleMode = onToggleMode,
            onSearch = onSearch,
            modifier = Modifier.weight(1f).focusRequester(boardFocus),
        )
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

/** 空态（DESIGN §6）：虚线描边框 + 居中提示 + 行动建议。 */
@Composable
private fun EmptyNotice(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .drawBehind {
                drawRoundRect(
                    color = Outline,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = OnBg, fontSize = 16.sp)
            Text("试试更短的关键词或检查输入", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 搜索历史行（空输入时显示在联想位，SPEC §3.2）。 */
@Composable
private fun HistoryRow(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("历史", color = Primary, fontSize = 13.sp)
        if (history.isEmpty()) {
            Text("暂无搜索历史", color = Dim, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(history) { word -> Chip(word) { onPick(word) } }
            }
            Text(
                "清空",
                color = Dim,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(KeySurfaceDim)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SuggestRow(label: String, words: List<String>, onPick: (String) -> Unit, firstFocus: FocusRequester? = null) {
    if (words.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Primary, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(words) { i, w ->
                Chip(
                    w,
                    modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onPick(w) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) PrimarySoft else KeySurface)
            .border(1.dp, if (focused) Primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (focused) OnBg else OnBgVariant, fontSize = 14.sp, maxLines = 1)
    }
}

/** ABC 字母序键盘（SPEC §3.2）：数字行 + A-J / K-T / U-Z + 拼/EN ⌫ 空格 搜索。 */
private val KEY_ROWS = listOf(
    "1234567890",
    "ABCDEFGHIJ",
    "KLMNOPQRST",
    "UVWXYZ",
)

@Composable
private fun Keyboard(
    pinyinMode: Boolean,
    candidatesReady: Boolean,
    firstCandidateFocus: FocusRequester,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e -> // 打字后 ↓ 直达候选首项（联想>直达>拼音）
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && candidatesReady) {
                    runCatching { firstCandidateFocus.requestFocus() }.isSuccess
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KEY_ROWS.forEachIndexed { i, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    KeyButton(label = ch.toString(), weight = 1f, onClick = { onKey(ch.toString()) })
                }
                if (i == KEY_ROWS.lastIndex) { // U-Z 行尾功能键：拼/EN ⌫ 空格 搜索
                    KeyButton(label = if (pinyinMode) "拼/EN" else "EN/拼", weight = 1.4f, onClick = onToggleMode)
                    KeyButton(label = "⌫", weight = 1.4f, onClick = onBackspace)
                    KeyButton(label = "空格", weight = 1.4f, onClick = { onKey(" ") })
                    SearchKey(onSearch)
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
    val resultFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { resultFocus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackKey(onBack, modifier = Modifier.focusRequester(resultFocus))
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
            // 列表懒加载预取（SPEC §8）：焦点卡后 ahead 张海报预热
            val prefetcher = com.hgtv.app.ui.rememberCoverPrefetcher()
            prefetcher.update(dramas.map { it.cover })
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(dramas, key = { _, it -> it.seriesId }) { index, d ->
                    val season = HongguoWeb.seasonOf(d.title).season
                    PosterCard(
                        title = d.title,
                        badge = d.episodeCnt.ifEmpty { d.remark }.let { if (it.isNotEmpty() && !it.endsWith("集")) "${it}集" else it },
                        cover = d.cover,
                        onFocused = { prefetcher.onFocus(index) },
                        onClick = { onOpenDetail(d.seriesId) },
                        seasonBadge = if (season > 0) "第${season}季" else "",
                    )
                }
            }
        }
    }
}

@Composable
private fun BackKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
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
