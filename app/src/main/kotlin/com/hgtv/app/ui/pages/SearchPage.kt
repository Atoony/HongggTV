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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.hgtv.app.net.HongguoFeed
import com.hgtv.app.net.HongguoWeb
import com.hgtv.app.net.Suggestion
import com.hgtv.app.net.WebDrama
import com.hgtv.app.ui.rememberRowPrefetch
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

/** 中列首焦区判定（票11 焦点流）：联想 > 直达 > 拼音；全空返回 null（中列无候选，RIGHT 走默认焦点搜索）。 */
internal fun firstCandidateRow(
    suggestions: List<Suggestion>,
    nameHits: List<WebDrama>,
    pinyinCandidates: List<String>,
): Int? = when {
    suggestions.isNotEmpty() -> 0
    nameHits.isNotEmpty() -> 1
    pinyinCandidates.isNotEmpty() -> 2
    else -> null
}

/** 右列热门词（降级源=热播榜标题，侦察结论见票11）：滤空白、同名去重、保序，截 max 条。 */
internal fun hotWordsFromRank(ranks: List<HongguoFeed.RankItem>, max: Int = 10): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (r in ranks) {
        val title = r.title.trim()
        if (title.isEmpty() || !seen.add(title)) continue
        out.add(title)
        if (out.size >= max) break
    }
    return out
}

/** 搜索页（票 11 三列改版，B 站 TV 式）：左列键盘输入 / 中列联想直达 / 右列历史热门 + 结果网格。 */
@Composable
fun SearchPage(
    web: HongguoWeb,
    feed: HongguoFeed,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var pinyinMode by remember { mutableStateOf(true) } // 拼 / EN
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var nameHits by remember { mutableStateOf<List<WebDrama>>(emptyList()) }
    var suggestLoading by remember { mutableStateOf(false) } // 联想在飞：中列区分「加载中/无候选」
    var history by remember { mutableStateOf(SearchHistory.load(ctx)) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<WebDrama>?>(null) } // null=未搜索；返回键盘保留
    var resultWord by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var emptyNotice by remember { mutableStateOf("") } // 空态提示，显示于键盘态中列（SPEC：空态焦点回键盘）

    // 右列热门词：降级源=热播榜 top 标题（票11 侦察结论：web 域无热搜词榜）；失败静默隐藏
    LaunchedEffect(Unit) {
        hotWords = try {
            hotWordsFromRank(feed.hotDramaRank())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 联想 300ms 停顿刷新：每次按键重启 effect，天然防抖（SPEC §3.2）
    LaunchedEffect(query, pinyinMode) {
        if (emptyNotice.isNotEmpty()) emptyNotice = "" // 用户重新打字即撤销空态提示
        if (query.isBlank()) {
            suggestLoading = false
            suggestions = emptyList()
            nameHits = emptyList()
        } else {
            suggestLoading = true
            delay(300)
            // 被取消的旧 effect 不得把新 effect 的加载态提前置 false（票11 评审同型修复对齐）
            try {
                suggestions = runCatching { web.suggestions(query, 10) }.getOrDefault(emptyList())
                nameHits = runCatching { web.nameIndex(query) }.getOrDefault(emptyList())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            suggestLoading = false
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
                suggestLoading = suggestLoading,
                history = history,
                hotWords = hotWords,
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
                onPickHotWord = { doSearch(it) },
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
    suggestLoading: Boolean,
    history: List<String>,
    hotWords: List<String>,
    emptyNotice: String,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onPickName: (String) -> Unit,
    onPickPinyin: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onPickHotWord: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val boardFocus = remember { FocusRequester() }
    val firstCandidateFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { boardFocus.requestFocus() } } // 进页/回键盘 落键盘

    // 中列首焦区单点判定（联想>直达>拼音）；null=中列无候选
    val firstRow = firstCandidateRow(suggestions, nameHits, pinyinCandidates)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // 左列（约 45%）：QueryBox + ABC 键盘
        Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QueryBox(query, pinyinMode)
            Keyboard(
                pinyinMode = pinyinMode,
                // 空态提示占据中列时首焦 requester 未挂载，行尾 RIGHT 须走默认搜索（评审#4）
                rightTarget = if (firstRow != null && emptyNotice.isEmpty()) firstCandidateFocus else null,
                onKey = onKey,
                onBackspace = onBackspace,
                onToggleMode = onToggleMode,
                onSearch = onSearch,
                modifier = Modifier.weight(1f).focusRequester(boardFocus),
            )
        }
        // 中列（约 30%）：联想 / 直达 / 拼音 竖排列表（B 站样式条目）
        MiddleColumn(
            query = query,
            emptyNotice = emptyNotice,
            suggestions = suggestions,
            nameHits = nameHits,
            pinyinCandidates = pinyinCandidates,
            suggestLoading = suggestLoading,
            firstRow = firstRow,
            firstCandidateFocus = firstCandidateFocus,
            onPickSuggestion = onPickSuggestion,
            onPickName = onPickName,
            onPickPinyin = onPickPinyin,
            modifier = Modifier.weight(0.30f),
        )
        // 右列（约 25%）：搜索历史 + 热门
        RightColumn(
            history = history,
            hotWords = hotWords,
            onPickHistory = onPickHistory,
            onClearHistory = onClearHistory,
            onPickHotWord = onPickHotWord,
            modifier = Modifier.weight(0.25f),
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

/** 中列：联想/直达/拼音竖排候选；空输入空态引导；空结果提示；联想未回时轻提示。 */
@Composable
private fun MiddleColumn(
    query: String,
    emptyNotice: String,
    suggestions: List<Suggestion>,
    nameHits: List<WebDrama>,
    pinyinCandidates: List<String>,
    suggestLoading: Boolean,
    firstRow: Int?,
    firstCandidateFocus: FocusRequester,
    onPickSuggestion: (String) -> Unit,
    onPickName: (String) -> Unit,
    onPickPinyin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        emptyNotice.isNotEmpty() -> Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            EmptyNotice(emptyNotice)
        }
        query.isBlank() -> Column(
            modifier.fillMaxHeight().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("用左侧键盘输入剧名 / 拼音首字母", color = OnBg, fontSize = 15.sp)
            Text("或从右侧「历史 / 热门」选词", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        firstRow == null -> Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            // 评审#1：区分在飞（防抖/网络）与真无候选（EN 无匹配/纯数字），不再永久「加载中」
            Text(if (suggestLoading) "联想加载中…" else "无联想候选", color = Dim, fontSize = 13.sp)
        }
        else -> {
            val listState = rememberLazyListState()
            // 候选集变化（新按键/联想直达到达）时滚回顶：LazyColumn 按键锚定滚动，
            // 上方 prepend 新区段会锚住旧首可见项把首焦区滚出视口（未组合→focusRequester 悬空）
            LaunchedEffect(query, suggestions.size, nameHits.size, pinyinCandidates.size) {
                if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 4.dp),
            ) {
                candidateSection(
                    label = "联想",
                    entries = suggestions.map { it.name to "" },
                    firstFocus = if (firstRow == 0) firstCandidateFocus else null,
                    onPick = onPickSuggestion,
                )
                candidateSection(
                    label = "直达",
                    entries = nameHits.take(NAME_HITS_SHOWN).map { it.title to episodeBadge(it) },
                    firstFocus = if (firstRow == 1) firstCandidateFocus else null,
                    onPick = onPickName,
                )
                candidateSection(
                    label = "拼音",
                    entries = pinyinCandidates.map { it to "" },
                    firstFocus = if (firstRow == 2) firstCandidateFocus else null,
                    onPick = onPickPinyin,
                )
            }
        }
    }
}

/** 剧卡集数角标：episode_cnt 优先退 remark，缺「集」后缀则补——中列直达与结果网格共用，避免两套格式（评审#6）。 */
internal fun episodeBadge(d: WebDrama): String =
    d.episodeCnt.ifEmpty { d.remark }.let { if (it.isNotEmpty() && !it.endsWith("集")) "${it}集" else it }

/** 中列直达展示上限（nameIndex 最多回 50 条，不截会把拼音区挤出屏幕外）。 */
private const val NAME_HITS_SHOWN = 10

private fun LazyListScope.candidateSection(
    label: String,
    entries: List<Pair<String, String>>,
    firstFocus: FocusRequester?,
    onPick: (String) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "${label}-head") { SectionLabel(label) }
    itemsIndexed(entries, key = { i, _ -> "$label-$i" }) { i, (main, sub) ->
        CandidateItem(
            text = main,
            sub = sub,
            modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            onClick = { onPick(main) },
        )
    }
}

/** 右列：搜索历史（LRU 竖排 + 清空）+ 热门（热播榜标题降级，票11 侦察结论）。 */
@Composable
private fun RightColumn(
    history: List<String>,
    hotWords: List<String>,
    onPickHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onPickHotWord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "history-head") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("搜索历史", color = Primary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    Text(
                        "清空",
                        color = Dim,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(KeySurfaceDim)
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
        if (history.isEmpty()) {
            item(key = "history-empty") { Text("暂无搜索历史", color = Dim, fontSize = 13.sp) }
        } else {
            items(history, key = { "history-$it" }) { word ->
                CandidateItem(word, onClick = { onPickHistory(word) })
            }
        }
        if (hotWords.isNotEmpty()) {
            item(key = "hot-head") { SectionLabel("热门") }
            itemsIndexed(hotWords, key = { i, _ -> "hot-$i" }) { i, word ->
                CandidateItem(word, leading = "${i + 1}", onClick = { onPickHotWord(word) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Primary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
}

/** B 站样式条目：整行可聚焦，主文本 + 可选副文本/序号。 */
@Composable
private fun CandidateItem(
    text: String,
    modifier: Modifier = Modifier,
    sub: String = "",
    leading: String = "",
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) PrimarySoft else KeySurface)
            .border(1.dp, if (focused) Primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading.isNotEmpty()) {
            Text(leading, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text(
            text,
            color = if (focused) OnBg else OnBgVariant,
            fontSize = 15.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (sub.isNotEmpty()) {
            Text(sub, color = Dim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** ABC 字母序键盘（SPEC §3.2 / 票11 三列）：数字行 + A-J / K-T / U-Z + 拼/EN ⌫ 空格 搜索。 */
private val KEY_ROWS = listOf(
    "1234567890",
    "ABCDEFGHIJ",
    "KLMNOPQRST",
    "UVWXYZ",
)

@Composable
private fun Keyboard(
    pinyinMode: Boolean,
    rightTarget: FocusRequester?, // 中列首焦（联想>直达>拼音）；null=中列无候选，行尾 RIGHT 走默认
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行尾键（每行最后一列）聚焦标记：仅行尾 RIGHT 进中列（票11），行内 RIGHT 照常移动
    var lastColFocused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e -> // 行尾 RIGHT → 中列候选首项（联想>直达>拼音）；DOWN 留给行间移动（票11）
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && lastColFocused && rightTarget != null) {
                    runCatching { rightTarget.requestFocus() }.isSuccess
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KEY_ROWS.forEachIndexed { i, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    // 行尾判定：末行字母后还有功能键，视觉末列只有 SearchKey
                    val isLast = ch == row.last() && i != KEY_ROWS.lastIndex
                    KeyButton(
                        label = ch.toString(),
                        weight = 1f,
                        onFocusGained = { lastColFocused = isLast },
                        onClick = { onKey(ch.toString()) },
                    )
                }
                if (i == KEY_ROWS.lastIndex) { // U-Z 行尾功能键：拼/EN ⌫ 空格 搜索
                    KeyButton(label = if (pinyinMode) "拼/EN" else "EN/拼", weight = 1.4f, onFocusGained = { lastColFocused = false }, onClick = onToggleMode)
                    KeyButton(label = "⌫", weight = 1.4f, onFocusGained = { lastColFocused = false }, onClick = onBackspace)
                    KeyButton(label = "空格", weight = 1.4f, onFocusGained = { lastColFocused = false }, onClick = { onKey(" ") })
                    SearchKey(onFocusGained = { lastColFocused = true }, onClick = onSearch)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyButton(
    label: String,
    weight: Float,
    onFocusGained: () -> Unit = {},
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .weight(weight)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Primary else KeySurface)
            .onFocusChanged { if (it.isFocused) onFocusGained() }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.White else OnBg, fontSize = if (label.length == 1) 18.sp else 13.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchKey(onFocusGained: () -> Unit = {}, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .weight(2.2f)
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFFFF8A65) else Primary)
            .onFocusChanged { if (it.isFocused) onFocusGained() }
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
            val prefetcher = rememberRowPrefetch(dramas.map { it.cover })
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
                        badge = episodeBadge(d),
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
