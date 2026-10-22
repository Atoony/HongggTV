package com.hgtv.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** 搜索结果卡（Web 通道 + 名称索引共用） */
data class WebDrama(
    val seriesId: String,
    val title: String,
    val cover: String,
    val intro: String,
    val episodeCnt: String,
    val remark: String,
    val releaseStatus: String,
    val categories: List<String>,
    val heat: String,
    val score: String,
)

/** 联想词 */
data class Suggestion(val name: String, val type: String)

data class WebSearchResult(val dramas: List<WebDrama>, val total: Int, val limited: Boolean)

/** 红果 Web 通道（SPEC §6.1：搜索结果 / 联想词+名称索引；游客无签名）。 */
class HongguoWeb(
    private val baseURL: String = "https://hongguoduanju.com",
) {

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11; Redmi 25053RT47C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val WORD_TYPE_WHITELIST = setOf(
            "short_play_name", "short_play_category", "common_query", "actor_name", "short_play_actor",
        )
        private val SEASON_SUFFIX = Regex("第\\s*([0-9零〇一二两兩三四五六七八九十百]+)\\s*([季部])[\\p{P}\\s]*$")
        private val NUMERIC_ID = Regex("^\\d{10,25}$")
        private const val SEASON_QUERIES = 32
        private const val SEASON_BUDGET_MS = 25_000L
        private const val NAME_INDEX_COUNT = 50


        /** 联想响应解析：word_type 白名单过滤（SPEC §6.1）+ 同名去重 + count 截断；兼容 data 包裹结构。 */
        internal fun parseSuggestions(json: String, count: Int): List<Suggestion> {
            val root = JsonParser.parse(json) as? Map<String, Any?> ?: return emptyList()
            val records = JsonParser.arr(root["suggest_list"]) ?: JsonParser.arr(JsonParser.obj(root["data"])?.get("suggest_list"))
                ?: return emptyList()
            val out = ArrayList<Suggestion>()
            val seen = HashSet<String>()
            for (row in records) {
                val m = JsonParser.obj(row) ?: continue
                val name = JsonParser.str(m, "name")
                if (name.isEmpty() || !seen.add(name.lowercase())) continue
                val type = JsonParser.str(m, "word_type").takeIf { WORD_TYPE_WHITELIST.contains(it) } ?: continue
                out.add(Suggestion(name, type))
                if (out.size >= count) break
            }
            return out
        }

        /** 归一化比较文本：去空白/标点 + 小写（等价 Go hongguoSearchText） */
        internal fun searchText(text: String): String {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val sb = StringBuilder()
            for (c in nfkc) {
                if (Character.isWhitespace(c) || Character.isLetterOrDigit(c).not()) continue
                sb.append(Character.toLowerCase(c))
            }
            return if (sb.isEmpty()) text.trim().lowercase() else sb.toString()
        }

        /** 标题季号解析：(base, season, unit, label)；无季返回 ("",0,"","") */
        internal fun seasonOf(title: String): SeasonInfo {
            val t = Normalizer.normalize(title.trim(), Normalizer.Form.NFKC)
            val match = SEASON_SUFFIX.find(t) ?: return SeasonInfo("", 0, "", "")
            val base = t.substring(0, match.range.first).trimEnd { it.isWhitespace() || !it.isLetterOrDigit() }
            val label = match.groupValues[1]
            val unit = match.groupValues[2]
            val number = parseCnNumber(label) ?: return SeasonInfo("", 0, "", "")
            if (base.isEmpty() || number < 1 || number > 200) return SeasonInfo("", 0, "", "")
            return SeasonInfo(base, number, unit, label)
        }

        private fun parseCnNumber(label: String): Int? {
            label.toIntOrNull()?.let { return it }
            val digits = mapOf(
                '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
                '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            )
            if (!label.any { it == '十' || it == '百' }) {
                var n = 0
                for (c in label) {
                    val v = digits[c] ?: return null
                    n = n * 10 + v
                    if (n > 200) return null
                }
                return n
            }
            var digit = 0
            var previous = 1000
            var number = 0
            for (c in label) {
                val v = digits[c]
                if (v != null) {
                    digit = digit * 10 + v
                    continue
                }
                val unitVal = when (c) {
                    '十' -> 10
                    '百' -> 100
                    else -> return null
                }
                if (unitVal >= previous || digit > 9) return null
                number += maxOf(digit, 1) * unitVal
                digit = 0
                previous = unitVal
            }
            return number + digit
        }

        internal fun chineseSeason(number: Int): String {
            val digits = "零一二三四五六七八九"
            if (number < 10) return digits[number].toString()
            if (number < 100) {
                var prefix = ""
                if (number >= 20) prefix = digits[number / 10].toString()
                prefix += "十"
                if (number % 10 != 0) prefix += digits[number % 10]
                return prefix
            }
            var prefix = digits[number / 100].toString() + "百"
            val rest = number % 100
            if (rest == 0) return prefix
            prefix += when {
                rest < 10 -> "零"
                rest < 20 -> "一"
                else -> ""
            }
            return prefix + chineseSeason(rest)
        }
    }

    private suspend fun httpGet(url: String, referer: String, timeoutMs: Int = 12_000): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val status = conn.responseCode
            val body = (if (status in 200..399) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()
            status to body
        }

    // ---------- 联想 ----------

    suspend fun suggestions(query: String, count: Int = 10): List<Suggestion> {
        val q = normalizeKeyword(query) ?: return emptyList()
        val url = "$baseURL/incent_resource/suggestion?app_id=8662&query=${goEscapeQuery(q)}&count=$count"
        val (status, body) = httpGet(url, "$baseURL/")
        if (status != 200 || body.isEmpty()) return emptyList()
        return parseSuggestions(String(body, Charsets.UTF_8), count)
    }

    // ---------- 名称索引（搜索结果增强 + 缺季补全查询） ----------

    suspend fun nameIndex(query: String): List<WebDrama> {
        val q = normalizeKeyword(query) ?: return emptyList()
        val url = "$baseURL/incent_resource/suggestion?app_id=8662&query=${goEscapeQuery(q)}&count=$NAME_INDEX_COUNT"
        val (status, body) = httpGet(url, "$baseURL/", timeoutMs = 5_000)
        if (status != 200 || body.isEmpty()) return emptyList()
        val root = JsonParser.parse(String(body, Charsets.UTF_8)) as? Map<String, Any?> ?: return emptyList()
        val records = JsonParser.arr(root["suggest_list"]) ?: JsonParser.arr(JsonParser.obj(root["data"])?.get("suggest_list"))
            ?: return emptyList()
        val out = ArrayList<WebDrama>()
        for (row in records) {
            val m = JsonParser.obj(row) ?: continue
            if (JsonParser.str(m, "word_type") != "short_play_name") continue
            val video = JsonParser.obj(m["video_data"]) ?: continue
            val id = JsonParser.str(video, "series_id_str", "series_id")
            if (!NUMERIC_ID.matches(id)) continue
            val name = firstNonEmpty(JsonParser.str(m, "name"), JsonParser.str(m, "keyword"), id)
            dramaFromMap(video, fallbackTitle = name)?.let { out.add(it) }
        }
        return out
    }

    // ---------- 搜索页 ----------
    // 已知坑（SPEC §6.1）：带 page 参数请求时服务端 301 重定向到剥参 URL。
    // 本实现从不携带 page 参数（关键词走路径、MVP 单页结果），instanceFollowRedirects=true
    // 仅兜底 HTML 入口跳转，301 剥参路径天然不触发；线上探针（WebProbeTest）已验证。

    suspend fun searchPage(keyword: String): Pair<List<WebDrama>, Int> {
        val q = normalizeKeyword(keyword) ?: throw IllegalArgumentException("无效搜索词")
        val url = "$baseURL/search/${goEscapePath(q)}"
        val (status, body) = httpGet(url, "$baseURL/")
        if (status != 200 || body.isEmpty()) throw IllegalStateException("红果综合搜索暂不可用 HTTP $status")
        val html = String(body, Charsets.UTF_8)
        val router = parseRouterData(html) ?: throw IllegalStateException("红果搜索未返回有效结果")
        val loader = JsonParser.obj(router["loaderData"]) ?: throw IllegalStateException("红果搜索未返回有效结果")
        val page = loaderMap(loader, "search_(keyword)/page", "search_")
            ?: throw IllegalStateException("红果搜索未返回有效结果")
        if (page["isSuccess"] != true) throw IllegalStateException("红果搜索未返回有效结果")
        if (JsonParser.str(page, "query").isNotEmpty() && JsonParser.str(page, "query") != q) {
            throw IllegalStateException("红果搜索未返回有效结果")
        }
        val rows = JsonParser.arr(page["searchList"]) ?: emptyList()
        val out = ArrayList<WebDrama>()
        val seen = HashSet<String>()
        for (row in rows) {
            val m = JsonParser.obj(row) ?: continue
            if (JsonParser.obj(m["video_data"]) == null) continue
            dramaFromMap(m)?.let { if (seen.add(it.seriesId)) out.add(it) }
        }
        val total = JsonParser.str(page, "totalCount").toIntOrNull() ?: out.size
        return out to total
    }

    // ---------- 完整搜索（页 + 名称索引 + 缺季补全 + 排序） ----------

    suspend fun search(keyword: String): WebSearchResult {
        val q = normalizeKeyword(keyword) ?: throw IllegalArgumentException("请输入 1 至 80 个字符的搜索词")
        val names = runCatching { nameIndex(q) }.getOrDefault(emptyList())
        val pageResult = runCatching { searchPage(q) }
        val pageDramas = pageResult.getOrNull()?.first ?: emptyList()
        val pageTotal = pageResult.getOrNull()?.second ?: 0

        var dramas = mergeDramas(mutableListOf(), pageDramas)
        dramas = mergeDramas(dramas, names)
        if (dramas.isEmpty()) {
            if (pageResult.isFailure && names.isEmpty()) throw IllegalStateException("红果综合搜索暂不可用")
            return WebSearchResult(emptyList(), 0, true)
        }

        val seasonsLimited = completeSeasons(q, dramas)
        // 排序：标题匹配 rank → 同系列季号升序相邻
        val queryNorm = searchText(q)
        val ranked = dramas.sortedWith { a, b ->
            val ra = titleRank(a.title, q, queryNorm)
            val rb = titleRank(b.title, q, queryNorm)
            if (ra != rb) ra.compareTo(rb)
            else 0
        }.let { list -> sortSeasonsAdjacent(list, queryNorm) }

        val limited = pageResult.isFailure || seasonsLimited
        return WebSearchResult(ranked, maxOf(pageTotal, ranked.size), limited)
    }

    // ---------- 缺季补全 ----------

    private class Series(
        val title: String,
        val key: String,
        val unit: String,
    ) {
        val known = HashMap<Int, Boolean>()
        val labels = HashMap<Int, String>()
        var maximum = 0

        fun add(d: WebDrama): Boolean {
            val (base, season, unit, label) = seasonOf(d.title)
            val s = if (season == 0 && searchText(d.title) == key) 1 else season
            if (s == 0) return false
            if (season != 0 && (unit != this.unit || searchText(base) != key)) return false
            known[s] = true
            maximum = maxOf(maximum, s)
            if (label.isNotEmpty()) labels[s] = label
            return true
        }
    }

    internal suspend fun completeSeasons(
        keyword: String,
        dramas: MutableList<WebDrama>,
        fetch: suspend (String) -> List<WebDrama> = { nameIndex(it) },
    ): Boolean {
        if (seasonOf(keyword).season > 0) return false
        val query = searchText(keyword)
        val groups = ArrayList<Series>()
        val byKey = HashMap<String, Series>()
        for (d in dramas) {
            val (base, season, unit, _) = seasonOf(d.title)
            val key = searchText(base)
            if (season == 0 || !key.contains(query)) continue
            val gk = "$key:$unit"
            if (!byKey.contains(gk)) {
                byKey[gk] = Series(base, key, unit).also { groups.add(it) }
            }
        }
        val incomplete = groups.filter { g ->
            dramas.forEach { g.add(it) }
            g.known.size >= 2 && g.known.size < g.maximum
        }
        if (incomplete.isEmpty()) return false
        val deadline = System.currentTimeMillis() + SEASON_BUDGET_MS
        var requests = 0
        val attempted = HashSet<String>(listOf(keyword))
        for (g in incomplete) {
            while (requests < SEASON_QUERIES && System.currentTimeMillis() < deadline) {
                val next = g.nextQuery(attempted) ?: break
                attempted.add(next)
                requests++
                val names = runCatching { fetch(next) }.getOrDefault(emptyList())
                val matches = names.filter { g.add(it) }
                if (matches.isNotEmpty()) mergeDramas(dramas, matches)
            }
        }
        return incomplete.any { it.known.size < it.maximum }
    }

    private fun Series.nextQuery(attempted: Set<String>): String? {
        for (season in 1..maximum) {
            if (known[season] == true) continue
            var nearest = 0
            var distance = Int.MAX_VALUE
            for (k in 1..maximum) {
                if (labels[k] != null) {
                    val d = Math.abs(k - season)
                    if (d < distance) {
                        nearest = k
                        distance = d
                    }
                }
            }
            val labels = mutableListOf(chineseSeason(season), season.toString())
            // 邻近已知季若用阿拉伯数字，优先阿拉伯数字标签
            if (nearest > 0 && labelsOf(this, nearest).toIntOrNull() != null) {
                labels.reverse()
            }
            val queries = ArrayList<String>()
            if (season == 1) queries.add(title)
            for (l in labels) queries.add(title + "第" + l + unit)
            for (q in queries) {
                if (normalizeKeyword(q) != null && !attempted.contains(q)) return q
            }
        }
        return null
    }

    // ---------- 播放页详情兜底（游客网页通道：seriesDetail + 全集 vid_list，App 通道被风控时可用） ----------

    suspend fun playerDetail(seriesId: String): HongguoApi.SeriesDetail = withContext(Dispatchers.IO) {
        val (status, body) = httpGet("$baseURL/player/$seriesId", "$baseURL/")
        if (status != 200 || body.isEmpty()) throw IllegalStateException("播放页不可用 HTTP $status")
        parsePlayerDetail(String(body, Charsets.UTF_8))
    }

    internal fun parsePlayerDetail(html: String): HongguoApi.SeriesDetail {
        val detail = extractJsonObj(html, "seriesDetail") ?: throw IllegalStateException("播放页无 seriesDetail")
        val vidList = (JsonParser.arr(detail["vid_list"]) ?: emptyList())
            .map { JsonParser.str(it) }.filter { it.isNotEmpty() }
        if (vidList.isEmpty()) throw IllegalStateException("播放页无 vid_list")
        val episodeCnt = JsonParser.num(detail["episode_cnt"]).toInt().takeIf { it > 0 } ?: vidList.size
        val statusRaw = JsonParser.str(detail, "series_status").ifEmpty {
            JsonParser.str(JsonParser.nested(detail, "series_episode_info"), "series_status")
        }
        val tags = ArrayList<String>()
        JsonParser.arr(detail["tags"])?.forEach { t ->
            val n = when (t) {
                is String -> t
                else -> JsonParser.str(JsonParser.obj(t), "name", "tag_name", "title")
            }
            if (n.isNotEmpty() && !tags.contains(n)) tags.add(n)
        }
        return HongguoApi.SeriesDetail(
            seriesId = JsonParser.str(detail, "series_id").ifEmpty { seriesIdFallback(detail) },
            title = JsonParser.str(detail, "series_name"),
            cover = JsonParser.str(detail, "series_cover"),
            intro = JsonParser.str(detail, "series_intro"),
            episodeCnt = episodeCnt,
            remark = JsonParser.str(detail, "episode_right_text"),
            releaseStatus = when (statusRaw) {
                "1" -> "finished"
                "0" -> "ongoing"
                else -> ""
            },
            categories = tags,
            heat = "",
            episodes = vidList.mapIndexed { i, v -> HongguoApi.Episode(videoId = v, index = i + 1) },
            freeEpisodeCnt = JsonParser.num(detail["accessible_episode_cnt"]).toInt()
                .takeIf { it > 0 } ?: vidList.size,
        )
    }

    private fun seriesIdFallback(detail: Map<String, Any?>): String =
        JsonParser.str(detail, "series_id_str")

    /** 从 HTML 中按平衡花括号提取 "key":{...} JSON 对象（内嵌状态岛通用提取器；字符串内花括号安全）。 */
    private fun extractJsonObj(html: String, key: String): Map<String, Any?>? {
        val idx = html.indexOf("\"$key\":{")
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (esc) { esc = false; continue }
            when {
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            JsonParser.parse(html.substring(start, i + 1)) as? Map<String, Any?>
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    // ---------- 工具 ----------

    private fun labelsOf(s: Series, season: Int): String = s.labels[season] ?: ""

    private fun mergeDramas(base: MutableList<WebDrama>, batch: List<WebDrama>): MutableList<WebDrama> {
        val byId = HashMap<String, Int>()
        base.forEachIndexed { i, d -> byId[d.seriesId] = i }
        for (d in batch) {
            val i = byId[d.seriesId]
            if (i != null) base[i] = d
            else {
                byId[d.seriesId] = base.size
                base.add(d)
            }
        }
        return base
    }

    private fun loaderMap(loader: Map<String, Any?>, vararg names: String): Map<String, Any?>? {
        for (n in names) {
            val page = JsonParser.obj(loader[n])
            if (!page.isNullOrEmpty()) return page
        }
        for ((key, value) in loader) {
            for (n in names) {
                val prefix = n.trimEnd('$')
                if (prefix.isNotEmpty() && key.startsWith(prefix)) {
                    val page = JsonParser.obj(value)
                    if (!page.isNullOrEmpty()) return page
                }
            }
        }
        return null
    }

    private fun parseRouterData(html: String): Map<String, Any?>? {
        val idx = Regex("(?:window\\.)?_ROUTER_DATA\\s*=\\s*").find(html) ?: return null
        return runCatching { JsonParser.parsePrefix(html.substring(idx.range.first + idx.value.length)) as? Map<String, Any?> }.getOrNull()
    }

    private fun dramaFromMap(m: Map<String, Any?>, fallbackTitle: String = ""): WebDrama? {
        val vd = JsonParser.obj(m["video_data"]) ?: m
        val id = firstNonEmpty(JsonParser.str(vd, "series_id_str", "series_id"), JsonParser.str(m, "series_id_str", "series_id"))
        if (!NUMERIC_ID.matches(id)) return null
        val title = firstNonEmpty(
            JsonParser.str(vd, "series_title", "series_name", "title"),
            JsonParser.str(m, "series_name", "name"), fallbackTitle, id,
        )
        var remark = JsonParser.str(vd, "episode_right_text")
        var status = ""
        when (JsonParser.str(vd, "series_status")) {
            "1" -> status = "finished"
            "0" -> status = "ongoing"
        }
        if (status.isEmpty()) status = if (remark == "已完结") "finished" else ""
        val cnt = JsonParser.str(vd, "episode_cnt")
        if (remark.isEmpty() && cnt.isNotEmpty()) remark = "共${cnt}集"
        val cats = ArrayList<String>()
        (JsonParser.arr(vd["category_list"]) ?: emptyList()).forEach {
            val n = JsonParser.str(JsonParser.obj(it), "name")
            if (n.isNotEmpty() && !cats.contains(n)) cats.add(n)
        }
        val genre = JsonParser.str(vd, "category_name", "categoryName", "category")
        if (genre.isNotEmpty() && !cats.contains(genre)) cats.add(0, genre)
        return WebDrama(
            seriesId = id,
            title = title,
            cover = firstNonEmpty(JsonParser.str(vd, "series_cover", "cover"), JsonParser.str(m, "series_cover")),
            intro = firstNonEmpty(JsonParser.str(vd, "series_intro", "video_desc"), JsonParser.str(m, "series_intro")),
            episodeCnt = cnt,
            remark = remark,
            releaseStatus = status,
            categories = cats,
            heat = JsonParser.str(vd, "series_play_cnt", "play_cnt"),
            score = JsonParser.str(vd, "score"),
        )
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

    private fun normalizeKeyword(raw: String): String? {
        val q = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        if (q.isEmpty() || q.length > 80 || q.any { Character.isISOControl(it) }) return null
        return q
    }

    private fun titleRank(title: String, query: String, queryNorm: String): Int {
        val t = searchText(title)
        val words = queryNorm.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            t == queryNorm -> 0
            t.startsWith(queryNorm) -> 1
            t.contains(queryNorm) -> 2
            words.isNotEmpty() && words.all { t.contains(it) } -> 3
            else -> 4
        }
    }

    /** 同系列（base 相同 + unit 相同）按季号升序相邻：首个成员位置为锚，整组按季号升序吐出。 */
    internal fun sortSeasonsAdjacent(list: List<WebDrama>, queryNorm: String): List<WebDrama> {
        data class Keyed(val drama: WebDrama, val base: String, val season: Int, val unit: String)
        val keyed = list.map { d ->
            val (base, season, unit, _) = seasonOf(d.title)
            val s = if (season == 0 && searchText(d.title) == queryNorm) 1 else season
            Keyed(d, searchText(base), s, unit)
        }
        val groupKey = { k: Keyed -> k.base to k.unit }
        val counts = HashMap<Pair<String, String>, Int>()
        keyed.filter { it.season > 0 }.forEach { counts.merge(groupKey(it), 1, Int::plus) }
        val emitted = HashSet<Pair<String, String>>()
        val out = ArrayList<WebDrama>(keyed.size)
        for (k in keyed) {
            if (k.season > 0 && (counts[groupKey(k)] ?: 0) >= 2) {
                val gk = groupKey(k)
                if (!emitted.add(gk)) continue
                out.addAll(keyed.filter { it.season > 0 && groupKey(it) == gk }.sortedBy { it.season }.map { it.drama })
            } else {
                out.add(k.drama)
            }
        }
        return out
    }

    data class SeasonInfo(val base: String, val season: Int, val unit: String, val label: String)

    private fun goEscapeQuery(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = b.toInt() and 0xff
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> c.toChar().toString()
                else -> "%" + "%02X".format(c)
            }
        }

    private fun goEscapePath(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = b.toInt() and 0xff
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> c.toChar().toString()
                else -> "%" + "%02X".format(c)
            }
        }
}
