package com.hgtv.app.data

import android.content.Context
import android.util.Log
import com.hgtv.app.domain.Album
import com.hgtv.app.domain.Episode
import com.hgtv.app.domain.Playback
import com.hgtv.app.domain.SeriesCard
import com.hgtv.app.domain.Season
import com.hgtv.app.domain.SeriesDetail
import com.hgtv.app.domain.StreamException
import com.hgtv.app.domain.Suggestion
import com.hgtv.app.domain.contentKeyFromHex
import dev.openhongguo.Album as SdkAlbum
import dev.openhongguo.ClientOptions
import dev.openhongguo.HotSearch
import dev.openhongguo.InfoKind
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.Page
import dev.openhongguo.PlaybackResult
import dev.openhongguo.RankItem
import dev.openhongguo.Series
import dev.openhongguo.SeriesDetail as SdkSeriesDetail
import dev.openhongguo.SeriesStatus
import dev.openhongguo.SeriesSummary
import dev.openhongguo.ShortRanking
import dev.openhongguo.Suggestion as SdkSuggestion
import kotlinx.coroutines.CancellationException

/** 搜索中列候选（票11 三列）：联想词 + 直达卡，一次 suggest 调用拆两区。 */
data class SuggestColumns(
    val words: List<Suggestion>,
    val directs: List<SeriesCard>,
)

/** 单值 TTL 缓存（票14 评审收敛热播榜/热门词两处 Pair 同形；时钟注入可离线测）。 */
internal class TTLCache<T>(private val ttlMs: Long, private val nowMs: () -> Long) {
    private var entry: Pair<Long, T>? = null

    /** 命中未过期值；未写过或已过期返回 null。 */
    fun get(): T? = entry?.takeIf { nowMs() - it.first < ttlMs }?.second

    fun put(value: T) {
        entry = nowMs() to value
    }
}

/**
 * 数据源层适配（ADR 0001 / CONTEXT.md）：openhongguo SDK 是一切红果数据的唯一入口，
 * SDK 类型只出现在本文件（及其映射测试），出关即 domain 值类型。重试与匿名设备轮换
 * 全交 SDK 内建（ClientOptions 默认 retry=3/deviceResets=2，Q7 共识，不自建重试）。
 */
class OpenhongguoSource(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val client = OpenhongguoClient.create(
        context,
        ClientOptions(logger = { Log.d(TAG, it) }), // 设备轮换/重试可观测（logcat tag=OhgSdk）
    )

    /** 热播榜/热门词 5min TTL（Q7 共识：进页不重拉同一数据，防匿名配额浪费）。 */
    private val rankCache = TTLCache<List<SeriesCard>>(CACHE_TTL_MS, nowMs)
    private val hotWordsCache = TTLCache<List<String>>(CACHE_TTL_MS, nowMs)

    /** 播放模型；episodeId 与红果 videoId 同一 ID 空间（CONTEXT.md 分集）。 */
    suspend fun playback(episodeId: String): Playback = try {
        mapPlayback(client.resolvePlayback(episodeId), episodeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: StreamException) {
        throw e // mapPlayback 的 E-1003/E-1004 直达 UI，不被下面的兜底重包成 E-1001
    } catch (e: OpenhongguoException) {
        throw mapError(e)
    } catch (e: Exception) {
        throw StreamException.fetch("网络错误: ${e.message}")
    }

    /** 首页热播榜（短剧榜单默认榜，一页 10 部；SDK 页大小，匿名配额优先不翻页）。 */
    suspend fun hotRank(): List<SeriesCard> =
        rankCache.get() ?: mapRank(tryCall { client.shortRank() }).also { rankCache.put(it) }

    /** 搜索右列热门词真源（Q6：hotSearch 榜单序，话题词优先于剧名）。 */
    suspend fun hotWords(max: Int = 10): List<String> =
        hotWordsCache.get() ?: mapHotWords(tryCall { client.hotSearch() }, max).also { hotWordsCache.put(it) }

    /** 搜索中列：一次 suggest 拆联想（词）+ 直达（内容 ID 行）。 */
    suspend fun suggest(prefix: String): SuggestColumns {
        if (prefix.isBlank()) return SuggestColumns(emptyList(), emptyList())
        return mapSuggest(tryCall { client.suggest(prefix.trim()) })
    }

    /** 搜索结果首页（SDK 服务端 relevance 排序，web 时代客户端重排/缺季补全随旧链退役）。 */
    suspend fun search(query: String): List<SeriesCard> =
        mapSearch(tryCall { client.search(query.trim()) })

    /** 详情 + 选集：getSeriesBatch 单发（multi_video_detail 一次同回剧与全集，
     *  比 getSeries+listEpisodes 两发省一半）。 */
    suspend fun seriesDetail(seriesId: String): SeriesDetail {
        val batch = tryCall { client.getSeriesBatch(listOf(seriesId.trim())) }
        val detail = batch.firstOrNull() ?: throw IllegalStateException("短剧不存在或暂时不可见")
        return mapSeriesDetail(detail, seriesId)
    }

    /** 合集（票17）：SDK seasons() 一发全季（含未上线季与三级退化，均内建）。
     *  仅多季剧详情的"查看合集"可达（D6-A），单季剧零配额。 */
    suspend fun seasons(seriesId: String): Album = mapAlbum(tryCall { client.seasons(seriesId.trim()) })

    /** 元数据调用统一异常面：SDK 错误文案复用播放链的格式（含 code/重试窗口）。 */
    private suspend fun <T> tryCall(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OpenhongguoException) {
        throw IllegalStateException(sdkErrorMessage(e))
    } catch (e: Exception) {
        throw IllegalStateException("网络错误: ${e.message}")
    }

    companion object {
        private const val TAG = "OhgSdk"
        private const val CACHE_TTL_MS = 5 * 60_000L
        private val QUALITY_NUMBER = Regex("\\d+")

        /** 数字 keyword（≥10 位）= 内容 ID 行（SDK 文档：直达行为内容 ID）。 */
        private val CONTENT_ID = Regex("^\\d{10,25}$")

        /**
         * SDK 播放结果 → domain 播放模型（纯函数，离线可测）。
         * 票12 实测：短剧各档全为 progressive CENC mp4（hls/dash 均 false），密钥每集
         * 独立、集内跨档共享。dash/hls 分段流本应用合不了流（无对应 media3 模块、dash 另带
         * 独立音轨），显式滤除并以 E-1003 暴露，不裸播。
         */
        internal fun mapPlayback(result: PlaybackResult, episodeId: String): Playback {
            val playback = result.playback
            val contentKey = if (playback.protected) {
                val hex = result.contentKey?.key
                val bytes = hex?.takeIf { it.isNotEmpty() }?.let(::contentKeyFromHex)
                if (bytes == null || bytes.size != 16) {
                    val detail = result.contentKeyError?.let { "SDK ${it.code}: ${it.message}" }
                        ?: "密钥${if (hex.isNullOrEmpty()) "缺失" else "非法（非 32 位 hex）"}"
                    throw StreamException.keyUnavailable(detail)
                }
                bytes
            } else {
                null
            }

            val lines = playback.streams
                .filterNot { it.hls || it.dash }
                .map { s ->
                    // 档位口径 = 短边：竖屏短剧服务端 definition 即短边口径（"1080p" = 1080x1920），
                    // 取物理高度会把 1080p 档标成 1920P，且默认上限 1080 匹配不到它（实测掉到 540p 档）
                    val height = (minOf(s.width, s.height).takeIf { s.width > 0 && s.height > 0 })
                        ?: (s.height.takeIf { it > 0 })
                        ?: QUALITY_NUMBER.find(s.definition)?.value?.toIntOrNull()
                        ?: s.width
                    Playback.Line(
                        name = s.definition.ifEmpty { "${height}p" },
                        quality = height,
                        codec = s.codec,
                        urls = listOfNotNull(
                            s.url.takeIf { it.isNotEmpty() },
                            s.backupUrl.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
                .filter { it.quality > 0 && it.urls.isNotEmpty() }
                .sortedByDescending { it.quality }
            if (lines.isEmpty()) throw StreamException.noLine("分集无可播放线路（分段流或无地址）")

            return Playback(
                episodeId = playback.episodeId.ifEmpty { episodeId },
                contentKey = contentKey,
                lines = lines,
            )
        }

        /** SDK 错误文案（code + 重试窗口 + 消息）；播放链包 E-1001，元数据链直接 IllegalStateException。 */
        internal fun sdkErrorMessage(e: OpenhongguoException): String = buildString {
            append("SDK ").append(e.code)
            if (e.retryAfterMs > 0) append("（${e.retryAfterMs}ms 后可重试）")
            append("：").append(e.message)
        }

        /** SDK 异常 → E-1001（SDK 已重试耗尽，这里只做错误页文案）。 */
        internal fun mapError(e: OpenhongguoException): StreamException =
            StreamException.fetch(sdkErrorMessage(e))

        /** 短剧榜单 → 热播榜列表卡：rank=position（页面名次，跨页连续；推广行照排）。 */
        internal fun mapRank(ranking: ShortRanking): List<SeriesCard> = ranking.items
            .filter { it.seriesId.isNotEmpty() }
            .map {
                SeriesCard(
                    seriesId = it.seriesId,
                    title = it.title,
                    cover = it.coverUrl,
                    episodeCount = it.episodeCount,
                    rank = it.position,
                )
            }

        /** hotSearch → 热门词：榜单按服务端序，每榜内先话题词（query，空退 title）后剧名
         *  ——每榜内优先而非全榜话题词前置，保服务端榜单间的整体排序意图（票14 评审口径）；
         *  滤空白、去重、截 max（票11 降级源 hotWordsFromRank 同款纪律）。 */
        internal fun mapHotWords(hot: HotSearch, max: Int): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>(max.coerceAtMost(16))
            fun add(raw: String): Boolean { // 收一个词；返回是否已集满
                val w = raw.trim()
                if (w.isNotEmpty() && seen.add(w)) out.add(w)
                return out.size >= max
            }
            outer@ for (board in hot.boards) {
                for (topic in board.topics) if (add(topic.query.ifEmpty { topic.title })) break@outer
                for (item in board.items) if (add(item.title)) break@outer
            }
            return out
        }

        /** suggest 行拆两区：数字 keyword（≥10 位内容 ID）行入直达；其余入联想（上限 10，对齐旧链）。 */
        internal fun mapSuggest(rows: List<SdkSuggestion>): SuggestColumns {
            val words = ArrayList<Suggestion>()
            val directs = ArrayList<SeriesCard>()
            for (s in rows) {
                val text = s.text.trim()
                if (text.isEmpty()) continue
                val keyword = s.keyword.trim()
                if (CONTENT_ID.matches(keyword)) {
                    directs.add(SeriesCard(seriesId = keyword, title = text, cover = ""))
                } else if (words.size < SUGGEST_WORDS_MAX) {
                    words.add(Suggestion(text = text, query = keyword.ifEmpty { text }))
                }
            }
            return SuggestColumns(words = words, directs = directs)
        }

        /** 搜索结果页 → 列表卡（服务端 relevance 序）。 */
        internal fun mapSearch(page: Page<SeriesSummary>): List<SeriesCard> = page.items
            .filter { it.id.isNotEmpty() }
            .map {
                SeriesCard(
                    seriesId = it.id,
                    title = it.title,
                    cover = it.coverUrl,
                    episodeCount = it.episodeCount,
                )
            }

        /** 详情+选集映射：完结/分类（category + CATEGORY 行）/热度（subtitles 热度行优先，
         *  退 playCount 万亿格式化）；分集按 number 排序去重后 1..n 连续重编号（网页兜底链同款）。 */
        internal fun mapSeriesDetail(detail: SdkSeriesDetail, requestedId: String): SeriesDetail {
            val series = detail.series ?: throw IllegalStateException("短剧不存在或暂时不可见")
            val episodes = detail.episodes
                .filter { it.id.isNotEmpty() }
                .distinctBy { it.id }
                .sortedBy { it.number }
                .mapIndexed { i, ep -> Episode(episodeId = ep.id, number = i + 1) }
            if (episodes.isEmpty()) throw IllegalStateException("分集列表为空")
            val episodeCnt = series.episodeCount.takeIf { it > 0 } ?: episodes.size
            val categories = ArrayList<String>()
            series.category.trim().takeIf { it.isNotEmpty() }?.let(categories::add)
            for (row in series.rows) {
                if (row.kind != InfoKind.CATEGORY) continue
                val text = row.text.trim()
                if (text.isNotEmpty() && !categories.contains(text)) categories.add(text)
            }
            return SeriesDetail(
                seriesId = series.id.ifEmpty { requestedId },
                title = series.title,
                cover = series.coverUrl,
                intro = series.description,
                episodeCnt = episodeCnt,
                remark = series.rightText.ifEmpty { "共${episodeCnt}集" },
                finished = series.status == SeriesStatus.END,
                categories = categories,
                heat = series.subtitles.firstOrNull { it.contains("热度") }
                    ?: series.playCount.takeIf { it > 0 }?.let(::formatCount)
                    ?: "",
                isMultiSeason = series.isMultiSeason,
                seasonIndex = series.seasonIndex,
                episodes = episodes,
            )
        }

        /** SDK 合集 → domain（纯函数，离线可测）：滤空 id、去重（AlbumPage grid 以 seriesId
         *  为 key，退化路径重复 id 会组合期抛异常）、按季序稳定排序（SDK 已排，防御退化路径）。
         *  未上线季 = episodeCount 0（SDK 夹具形态：即将上线的季 episode_cnt=0，且打不开详情）。
         *  状态事实：seasons() 主路径（详情页系列剧行）只有当前请求季携带真实 status，其余季为
         *  默认连载——非当前季文案可能偏保守，可接受（票17 拍板不另花配额校正）。 */
        internal fun mapAlbum(album: SdkAlbum): Album = Album(
            title = album.title,
            description = album.description,
            seasons = album.seasons
                .filter { it.seriesId.isNotEmpty() }
                .distinctBy { it.seriesId }
                .sortedWith(compareBy({ it.index }, { it.seriesId }))
                .map {
                    Season(
                        seriesId = it.seriesId,
                        index = it.index.coerceAtLeast(1),
                        episodeCount = it.episodeCount,
                        finished = it.status == SeriesStatus.END,
                    )
                },
        )

        /** 计数万/亿格式化：340000→"34万"、1234567890→"12.3亿"（Literal Locale.ROOT 防千分位）。 */
        internal fun formatCount(n: Long): String = when {
            n >= 100_000_000L -> oneDecimal(n / 100_000_000.0) + "亿"
            n >= 10_000L -> oneDecimal(n / 10_000.0) + "万"
            else -> n.toString()
        }

        private fun oneDecimal(v: Double): String =
            String.format(java.util.Locale.ROOT, "%.1f", v).removeSuffix(".0")

        private const val SUGGEST_WORDS_MAX = 10
    }
}
