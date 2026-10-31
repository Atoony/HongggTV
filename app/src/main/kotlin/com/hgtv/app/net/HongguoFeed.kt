package com.hgtv.app.net

/** 首页行数据源：Web 短剧热播榜。
 *  2026-10-31 票10：landpage 游客链被服务端收紧全灭（签名/TLS/cookie/设备注册排除矩阵闭合），
 *  首页行数据切 web 榜单（hongguoduanju.com 域不受影响）。
 *  同日"为你推荐"行移除（与热播榜同源重复），webFeedPage/FeedItem/LandPage 一并删除。 */
class HongguoFeed(
    private val api: HongguoApi,
    private val web: HongguoWeb = HongguoWeb(),
) {

    data class RankItem(
        val rank: Int,
        val seriesId: String,
        val title: String,
        val cover: String,
        val heat: String,
        val tags: List<String>,
        val episodeCount: String,
        val remark: String = "",
    )

    private val rankCache = HashMap<Int, Pair<Long, List<RankItem>>>()
    private val RANK_CACHE_TTL_MS = 5 * 60_000L

    /** Web 热播榜（SPEC §6.1：/rank/hot-drama；page=1..5，各 20 部）。
     *  TTL 缓存（票11 评审#2）：首页与搜索页共用一源，进页不重拉同一 page（IP 频控风险）。 */
    suspend fun hotDramaRank(page: Int = 1): List<RankItem> {
        val now = System.currentTimeMillis()
        val cached = rankCache[page]
        if (cached != null && now - cached.first < RANK_CACHE_TTL_MS) return cached.second
        val path = if (page > 1) "/rank/hot-drama?page=$page" else "/rank/hot-drama"
        val (status, body) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            webRawGet(path) // 挂 IO：LaunchedEffect 主线程直调会 NetworkOnMainThread
        }
        if (status != 200 || body.isEmpty()) throw IllegalStateException("热播榜请求失败 HTTP $status")
        val parsed = parseRankPage(String(body, Charsets.UTF_8))
        rankCache[page] = now to parsed
        return parsed
    }


    /** rank 页解析（离线可测）：优先 _ROUTER_DATA.content；为空或 router 缺失时走 script 标签兜底（两代结构）。
     *  票10 起 router 整体缺失也尝试 r 标签（数据与 router 结构解耦，抗再改版）。 */
    internal fun parseRankPage(html: String): List<RankItem> {
        var content: Map<String, Any?>? = null
        val router = parseRouter(html)
        if (router != null) {
            val loader = JsonParser.nested(router, "loaderData", "rank_hot-drama/page")
            if (loader != null) content = JsonParser.obj(loader["content"]) ?: emptyMap()
        }
        if (content == null || content["rankList"] == null) {
            content = extractMergedContent(html, "rank_hot-drama/page") ?: emptyMap()
        }
        if (content["isSuccess"] != true || content["rankList"] == null) {
            throw IllegalStateException("热播榜解析失败")
        }
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
                    // episodeCount=总集数(987 级 int)；episodeVids 只是网页兜底免费集 vid 列表，仅作兜底
                    episodeCount = JsonParser.num(m["episodeCount"]).toInt()
                        .takeIf { it > 0 }?.toString()
                        ?: (JsonParser.arr(m["episodeVids"])?.size ?: 0).toString(),
                    remark = (JsonParser.arr(m["statusTags"]) ?: emptyList())
                        .map { JsonParser.str(it) }.firstOrNull() ?: "",
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
        return runCatching { JsonParser.parsePrefix(html.substring(idx.range.first + idx.value.length)) as? Map<String, Any?> }.getOrNull()
    }

    /** script 标签兜底（两代结构）：
     *  新（2026-10-24 改版）：data-fn-name="r"，args=[key,"content",{json}]——单引号 attr、实体编码、内容直出；
     *  旧：data-fn-name="mergeLoaderData"，args=[key,[{key:"content",routerDataFnArgs:["{...}"]}]]（双重 JSON；线上现为 stub，保留兼容）。 */
    private fun extractMergedContent(html: String, loaderKey: String): Map<String, Any?>? {
        for (m in Regex("""<script[^>]*data-fn-name="r"[^>]*>""").findAll(html)) {
            val attr = Regex("""data-fn-args='([^']*)'""").find(m.value)?.groupValues?.get(1) ?: continue
            val args = runCatching { JsonParser.parse(htmlUnescape(attr)) as? List<Any?> }.getOrNull() ?: continue
            if (args.getOrNull(0) as? String != loaderKey) continue
            if (args.getOrNull(1) as? String != "content") continue
            return args.getOrNull(2) as? Map<String, Any?>
        }
        val tagRe = Regex("""<script[^>]*data-fn-name="mergeLoaderData"[^>]*>""")
        for (m in tagRe.findAll(html)) {
            val tag = m.value
            val attr = Regex("""data-fn-args="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: continue
            val args = runCatching { JsonParser.parse(htmlUnescape(attr)) as? List<Any?> }.getOrNull() ?: continue
            if (args.getOrNull(0) as? String != loaderKey) continue
            val desc = (args.getOrNull(1) as? List<Any?>)?.firstOrNull() as? Map<String, Any?> ?: continue
            if (desc["key"] != "content") continue
            val inner = (desc["routerDataFnArgs"] as? List<Any?>)?.firstOrNull() as? String ?: continue
            return runCatching { JsonParser.parse(inner) as? Map<String, Any?> }.getOrNull()
        }
        return null
    }

    private fun htmlUnescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
