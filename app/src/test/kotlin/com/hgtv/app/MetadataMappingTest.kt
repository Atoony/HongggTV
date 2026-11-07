package com.hgtv.app

import com.hgtv.app.data.OpenhongguoSource.Companion.formatCount
import com.hgtv.app.data.OpenhongguoSource.Companion.mapHotWords
import com.hgtv.app.data.OpenhongguoSource.Companion.mapRank
import com.hgtv.app.data.OpenhongguoSource.Companion.mapSearch
import com.hgtv.app.data.OpenhongguoSource.Companion.mapSeriesDetail
import com.hgtv.app.data.OpenhongguoSource.Companion.mapSuggest
import com.hgtv.app.data.TTLCache
import dev.openhongguo.HotBoard
import dev.openhongguo.HotItem
import dev.openhongguo.HotSearch
import dev.openhongguo.HotTopic
import dev.openhongguo.InfoKind
import dev.openhongguo.InfoRow
import dev.openhongguo.Page
import dev.openhongguo.RankItem
import dev.openhongguo.Series
import dev.openhongguo.SeriesDetail as SdkSeriesDetail
import dev.openhongguo.SeriesStatus
import dev.openhongguo.SeriesSummary
import dev.openhongguo.ShortRanking
import dev.openhongguo.Suggestion as SdkSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 票14：SDK 元数据 → domain 的映射（纯函数，离线可测；SDK 类型只允许出现在数据源层
 * 及其映射测试）。形态事实取自票12 探针纪要与 SDK testdata 夹具。
 */
class MetadataMappingTest {

    // ---------- 热播榜 ----------

    @Test
    fun `榜单映射 - rank 取页面名次 滤空 id 行 推广行照排`() {
        val cards = mapRank(
            ShortRanking(
                items = listOf(
                    RankItem(seriesId = "7100000000000101", title = "一", coverUrl = "c1", episodeCount = 60, position = 1),
                    RankItem(seriesId = "", title = "空行", position = 2),
                    RankItem(seriesId = "7100000000000102", title = "推广", coverUrl = "c2", episodeCount = 30, position = 2, promoted = true),
                ),
            ),
        )
        assertEquals(listOf("一", "推广"), cards.map { it.title })
        assertEquals(listOf(1, 2), cards.map { it.rank })
        assertEquals(listOf(60, 30), cards.map { it.episodeCount })
        assertEquals("c1", cards.first().cover)
    }

    // ---------- 热门词 ----------

    @Test
    fun `热门词 - 榜单服务端序 话题词优先于剧名 查词空退标题`() {
        val words = mapHotWords(
            HotSearch(
                boards = listOf(
                    HotBoard(
                        name = "热搜榜",
                        items = listOf(HotItem(title = "总裁"), HotItem(title = "地府")),
                    ),
                    HotBoard(
                        name = "热点话题榜",
                        topics = listOf(
                            HotTopic(title = "话题A", query = "queryA"),
                            HotTopic(title = "话题B", query = ""), // query 空 → 退标题
                        ),
                        items = listOf(HotItem(title = "总裁")), // 跨榜同名去重
                    ),
                ),
            ),
            max = 10,
        )
        assertEquals(listOf("总裁", "地府", "queryA", "话题B"), words)
    }

    @Test
    fun `热门词 - 滤空白并截 max`() {
        val words = mapHotWords(
            HotSearch(
                boards = listOf(
                    HotBoard(items = listOf(HotItem(title = "  "), HotItem(title = "剧1"), HotItem(title = "剧2"))),
                ),
            ),
            max = 2,
        )
        assertEquals(listOf("剧1", "剧2"), words)
    }

    // ---------- 联想 + 直达 ----------

    @Test
    fun `联想直达拆分 - 数字 keyword 行入直达 其余入联想`() {
        val columns = mapSuggest(
            listOf(
                SdkSuggestion(text = "战神", keyword = "7100000000000101", kind = 2), // 内容 ID 行
                SdkSuggestion(text = "战神归来", keyword = "战神归来", kind = 0),
                SdkSuggestion(text = "战神第二季", keyword = "", kind = 0), // keyword 空 → 退 text
                SdkSuggestion(text = "  ", keyword = "x", kind = 0), // 空文本滤除
            ),
        )
        assertEquals(1, columns.directs.size)
        assertEquals("7100000000000101", columns.directs.single().seriesId)
        assertEquals("战神", columns.directs.single().title)
        assertEquals(listOf("战神归来" to "战神归来", "战神第二季" to "战神第二季"), columns.words.map { it.text to it.query })
    }

    @Test
    fun `联想词上限 10 条`() {
        val rows = (1..12).map { SdkSuggestion(text = "词$it", keyword = "词$it", kind = 0) }
        assertEquals(10, mapSuggest(rows).words.size)
        assertEquals(0, mapSuggest(rows).directs.size)
    }

    // ---------- 搜索结果 ----------

    @Test
    fun `搜索结果映射 - 滤空 id`() {
        val cards = mapSearch(
            Page(
                items = listOf(
                    SeriesSummary(id = "7100000000000101", title = "一", coverUrl = "c1", episodeCount = 987),
                    SeriesSummary(id = "", title = "空"),
                ),
                hasMore = true,
                cursor = "20",
            ),
        )
        assertEquals(1, cards.size)
        assertEquals("7100000000000101", cards.single().seriesId)
        assertEquals(987, cards.single().episodeCount)
        assertEquals(0, cards.single().rank)
    }

    // ---------- 详情 + 选集 ----------

    private fun sdkEpisode(id: String, number: Int) = dev.openhongguo.Episode(id = id, seriesId = "7100000000000101", number = number)

    private fun sdkSeries(
        status: SeriesStatus = SeriesStatus.END,
        rightText: String = "全2集",
        category: String = "古装",
        playCount: Long = 340_000L,
        subtitles: List<String> = listOf("全2集", "红果热度值12万"),
        episodeCount: Int = 2,
        rows: List<InfoRow> = listOf(
            InfoRow(kind = InfoKind.SEASON_ORDER, text = "第1季"),
            InfoRow(kind = InfoKind.CATEGORY, text = "末世"),
            InfoRow(kind = InfoKind.CATEGORY, text = "古装"), // 与 category 同名去重
        ),
    ) = Series(
        id = "7100000000000101", title = "Fixture Lantern Court", description = "A public synopsis.",
        coverUrl = "https://media.invalid/cover.jpg", episodeCount = episodeCount,
        rightText = rightText, subtitles = subtitles, status = status, category = category,
        playCount = playCount,
        rows = rows,
    )

    @Test
    fun `详情映射 - 状态分类热度与集排序去重重编号`() {
        val d = mapSeriesDetail(
            SdkSeriesDetail(
                series = sdkSeries(),
                // 乱序 + 重复 id：按 number 排序、去重、1..n 连续重编号
                episodes = listOf(sdkEpisode("7100000000000202", 2), sdkEpisode("7100000000000201", 1), sdkEpisode("7100000000000201", 1)),
            ),
            "7100000000000101",
        )
        assertEquals(listOf("7100000000000201" to 1, "7100000000000202" to 2), d.episodes.map { it.episodeId to it.number })
        assertTrue(d.finished)
        assertEquals(listOf("古装", "末世"), d.categories)
        assertEquals("红果热度值12万", d.heat) // subtitles 热度行优先
        assertEquals("全2集", d.remark)
        assertEquals(2, d.episodeCnt)
        assertEquals("Fixture Lantern Court", d.title)
    }

    @Test
    fun `详情映射 - 热度退播放量万亿格式化 连载态与缺省回填`() {
        val d = mapSeriesDetail(
            SdkSeriesDetail(
                series = sdkSeries(
                    status = SeriesStatus.UPDATING,
                    rightText = "",
                    category = "",
                    playCount = 1_234_567_890L,
                    subtitles = emptyList(),
                    episodeCount = 0,
                    rows = emptyList(),
                ),
                episodes = listOf(sdkEpisode("7100000000000201", 1), sdkEpisode("7100000000000202", 2)),
            ),
            "7100000000000101",
        )
        assertEquals(false, d.finished)
        assertEquals("12.3亿", d.heat)
        assertEquals("共2集", d.remark)
        assertEquals(2, d.episodeCnt) // episodeCount=0 → 退集数列表长度
        assertEquals(emptyList<String>(), d.categories)
    }

    @Test
    fun `详情映射 - 缺剧或空分集报错`() {
        try {
            mapSeriesDetail(SdkSeriesDetail(series = null, episodes = emptyList()), "7100000000000101")
            fail("缺剧应报错")
        } catch (_: IllegalStateException) {
        }
        try {
            mapSeriesDetail(SdkSeriesDetail(series = sdkSeries(), episodes = emptyList()), "7100000000000101")
            fail("空分集应报错")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `计数万万亿格式化`() {
        assertEquals("9999", formatCount(9_999L))
        assertEquals("1万", formatCount(10_000L))
        assertEquals("34万", formatCount(340_000L))
        assertEquals("1.5亿", formatCount(150_000_000L))
        assertEquals("12.3亿", formatCount(1_234_567_890L))
    }

    // ---------- TTL 缓存（Q7：进页不重拉，假时钟离线测） ----------

    @Test
    fun `TTL 缓存 - 未写过或已过期返回 null TTL 内命中 过期重写复活`() {
        var now = 0L
        val cache = TTLCache<List<String>>(ttlMs = 300_000L, nowMs = { now })
        assertNull(cache.get()) // 未写过
        cache.put(listOf("a"))
        assertEquals(listOf("a"), cache.get()) // TTL 内命中
        now = 300_000L
        assertNull(cache.get()) // 恰好到 ttl 即失效（Q7 的 5min 语义）
        cache.put(listOf("b"))
        assertEquals(listOf("b"), cache.get()) // 过期后重写复活
    }
}
