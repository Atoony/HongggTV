package com.hgtv.app.net

/** 首页行数据源：landpage 推荐行 + Web 热播榜行。 */
class HongguoFeed(
    private val api: HongguoApi,
    private val web: HongguoWeb = HongguoWeb(),
) {

    data class FeedItem(
        val seriesId: String,
        val title: String,
        val cover: String,
        val episodeCount: String,
        val remark: String,
        val categories: List<String>,
        val heat: String,
    )

    data class LandPage(
        val items: List<FeedItem>,
        val nextOffset: Int,
        val sessionId: String,
        val hasMore: Boolean,
    )

    data class RankItem(
        val rank: Int,
        val seriesId: String,
        val title: String,
        val cover: String,
        val heat: String,
        val tags: List<String>,
        val episodeCount: String,
    )

    /** landpage 推荐行（SPEC §6.1：18 条/页；filter_ids 已看去重）。genre=short_play。 */
    fun landPage(offset: Int, sessionId: String, filterIds: List<String>): LandPage {
        val body = JVal.obj(
            "req_scene" to JVal.str("default"),
            "offset" to JVal.num(offset.toLong()),
            "limit" to JVal.num(18),
            "req_type" to JVal.str("only_content"),
            "need_selector_panel" to JVal.bool(false),
            "client_req_type" to JVal.num(if (offset > 0) 2 else 3),
            "session_id" to JVal.str(sessionId),
            "filter_ids" to JVal.str(filterIds.joinToString(",")),
            "select_items" to JVal.obj(
                "genre" to JVal.arr(JVal.str("short_play")),
                "sort" to JVal.arr(),
                "gender" to JVal.arr(),
                "category_dim_theme" to JVal.arr(),
                "category_dim_role" to JVal.arr(),
                "category_dim_epoch" to JVal.arr(),
                "online_time" to JVal.arr(),
                "creation_status" to JVal.arr(),
            ),
        )
        val (status, _, content) = api.request("/reading/distribution/category/landpage/v/", body)
        if (status != 200 || content.isEmpty()) throw IllegalStateException("推荐请求失败 HTTP $status")
        val root = JsonParser.parse(String(content, Charsets.UTF_8)) as? Map<String, Any?>
            ?: throw IllegalStateException("推荐响应非 JSON")
        val data = JsonParser.nested(root, "data") ?: throw IllegalStateException("推荐响应缺少 data")
        val rows = JsonParser.arr(data["video_data"]) ?: emptyList()
        val items = ArrayList<FeedItem>()
        val seen = HashSet<String>()
        for (row in rows) {
            val m = JsonParser.obj(row) ?: continue
            val id = JsonParser.str(m, "series_id_str", "series_id")
            if (!HongguoApi.NUMERIC_ID.matches(id) || !seen.add(id)) continue
            items.add(
                FeedItem(
                    seriesId = id,
                    title = JsonParser.str(m, "series_title", "series_name", "title").ifEmpty { id },
                    cover = JsonParser.str(m, "series_cover", "cover"),
                    episodeCount = JsonParser.str(m, "episode_cnt"),
                    remark = JsonParser.str(m, "episode_right_text"),
                    categories = (JsonParser.arr(m["category_list"]) ?: emptyList()).mapNotNull {
                        JsonParser.str(JsonParser.obj(it), "name").ifEmpty { null }
                    },
                    heat = JsonParser.str(m, "series_play_cnt", "play_cnt"),
                ),
            )
        }
        return LandPage(
            items = items,
            nextOffset = JsonParser.str(data, "next_offset").toIntOrNull() ?: (offset + items.size),
            sessionId = JsonParser.str(data, "session_id").ifEmpty { sessionId },
            hasMore = data["has_more"] == true,
        )
    }

    /** Web 热播榜（SPEC §6.1：/rank/hot-drama，loaderData.rank_hot-drama/page.content.rankList）。 */
    fun hotDramaRank(): List<RankItem> {
        val (status, body) = webRawGet("/rank/hot-drama")
        if (status != 200 || body.isEmpty()) throw IllegalStateException("热播榜请求失败 HTTP $status")
        val html = String(body, Charsets.UTF_8)
        val router = parseRouter(html) ?: throw IllegalStateException("热播榜解析失败")
        val loader = JsonParser.nested(router, "loaderData", "rank_hot-drama/page")
            ?: throw IllegalStateException("热播榜解析失败")
        val content = JsonParser.obj(loader["content"]) ?: throw IllegalStateException("热播榜解析失败")
        val rows = JsonParser.arr(content["rankList"]) ?: emptyList()
        val out = ArrayList<RankItem>()
        for (row in rows) {
            val m = JsonParser.obj(row) ?: continue
            val id = firstNonEmpty(JsonParser.str(m, "seriesId"), JsonParser.str(m, "id"))
            if (!HongguoApi.NUMERIC_ID.matches(id)) continue
            out.add(
                RankItem(
                    rank = JsonParser.num(m["rank"]).toInt(),
                    seriesId = id,
                    title = JsonParser.str(m, "title"),
                    cover = coverAddress(JsonParser.str(m, "cover")),
                    heat = JsonParser.str(m, "heatText"),
                    tags = (JsonParser.arr(m["tags"]) ?: emptyList()).map { JsonParser.str(it) },
                    episodeCount = (JsonParser.arr(m["episodeVids"])?.size ?: 0).toString(),
                ),
            )
        }
        if (out.isEmpty()) throw IllegalStateException("热播榜为空")
        out.sortBy { it.rank }
        return out
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

    private fun coverAddress(path: String): String {
        if (path.isEmpty()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "https://p9-reading-sign.fqnovelpic.com" + path
    }

    private fun webRawGet(path: String): Pair<Int, ByteArray> {
        val url = "https://hongguoduanju.com$path"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        val status = conn.responseCode
        val body = (if (status in 200..399) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        return status to body
    }

    private fun parseRouter(html: String): Map<String, Any?>? {
        val idx = Regex("(?:window\\.)?_ROUTER_DATA\\s*=\\s*").find(html) ?: return null
        return runCatching { JsonParser.parse(html.substring(idx.range.first + idx.value.length)) as? Map<String, Any?> }.getOrNull()
    }
}
