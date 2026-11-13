package com.hgtv.app.probe

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.hgtv.app.data.OpenhongguoSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openhongguo.ClientOptions
import dev.openhongguo.DanmakuOptions
import dev.openhongguo.OpenhongguoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 票 19 ①：listDanmaku 探针（渲染动工前的第一步，不绿不写渲染）。
 *
 * 线上纪律同票 12：hgLive=1 才真正打线上；一次只跑一个方法
 * （-Pandroid.testInstrumentationRunnerArguments.class=...#方法名），不连发；
 * 页间 450ms 退避对齐票 09 纪律。日志走 logcat tag=OhgProbe，结论回填票内。
 */
@RunWith(AndroidJUnit4::class)
class DanmakuProbeTest {

    private val tag = "OhgProbe"

    private fun live(): Boolean = InstrumentationRegistry.getArguments().getString("hgLive") == "1"

    private fun log(msg: String) {
        Log.i(tag, msg)
        println(msg) // gradle 控制台同步可见（test 输出流）
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    /**
     * 单用例三段：翻页节奏（串行翻到 hasMore=false）→ 汇总 → startMs 中段重读。
     * 选集按 commentCount 挑弹幕最密的集（Episode.CommentCount 即弹幕池统计口径）。
     */
    @Test
    fun danmaku_pageRhythm_andStartMs() {
        assumeTrue(live())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = OpenhongguoClient.create(context, ClientOptions(logger = { Log.d(tag, it) }))

        runBlocking {
            // ① 定剧：热词 → 搜索 → 弹幕最密的一集
            val hot = c.hotSearch()
            val query = hot.boards.firstNotNullOfOrNull { b -> b.topics.firstOrNull()?.query }
                ?: hot.boards.firstOrNull()?.items?.firstOrNull()?.title
                ?: "总裁"
            val page = c.search(query)
            assertTrue("搜索无结果", page.items.isNotEmpty())
            val series = page.items.first()
            delay(450)
            val eps = c.listEpisodes(series.id)
            assertTrue("分集列表为空", eps.items.isNotEmpty())
            val ep = eps.items.maxByOrNull { it.commentCount } ?: eps.items.first()
            log("选剧：《${series.title}》seriesId=${series.id} 共 ${eps.items.size} 集 → 第${ep.number}集（commentCount=${ep.commentCount}，全榜最高）episodeId=${ep.id}")

            // ② 翻页节奏：startMs=0 起串行翻到 hasMore=false
            var pages = 0
            var items = 0
            var cursor: String? = null
            var coveredEnd = 0L
            val t0 = System.nanoTime()
            while (true) {
                if (pages > 0) delay(450) // 不连发
                val t = System.nanoTime()
                val p = c.listDanmaku(series.id, ep.id, DanmakuOptions(cursor = cursor))
                pages++
                items += p.items.size
                coveredEnd = maxOf(coveredEnd, p.endMs)
                log(
                    "② 第${pages}页 ${elapsedMs(t)}ms：items=${p.items.size} 窗口=[${p.startMs},${p.endMs}](${p.endMs - p.startMs}ms) " +
                        "total=${p.total} hasMore=${p.hasMore} cursor=${p.cursor.length}字符 首offset=${p.items.firstOrNull()?.offsetMs} 尾offset=${p.items.lastOrNull()?.offsetMs}"
                )
                assertTrue("窗口宽度异常（${p.endMs - p.startMs}ms，上游口径 30 秒一段）", p.endMs - p.startMs in 1..60_000)
                if (!p.hasMore) break
                assertTrue("hasMore=true 但 cursor 为空（会死循环）", p.cursor.isNotEmpty())
                cursor = p.cursor
                assertTrue("翻页超过 ${OpenhongguoSource.Companion.DANMAKU_MAX_PAGES} 页，疑似不收敛",
                    pages < OpenhongguoSource.Companion.DANMAKU_MAX_PAGES)
            }
            log("② 汇总：${elapsedMs(t0)}ms 翻完 $pages 页，聚合 $items 条，覆盖到 ${coveredEnd}ms，服务端 total 见上")

            // ③ startMs 中段重读：从覆盖中段起读，验证"从包含该位置的一段起返回"
            val midMs = (coveredEnd / 2).coerceAtLeast(1_000)
            delay(450)
            val t2 = System.nanoTime()
            val re = c.listDanmaku(series.id, ep.id, DanmakuOptions(startMs = midMs))
            log(
                "③ startMs=$midMs 重读 ${elapsedMs(t2)}ms：items=${re.items.size} 窗口=[${re.startMs},${re.endMs}] " +
                        "hasMore=${re.hasMore} 首offset=${re.items.firstOrNull()?.offsetMs}（首条可早于 startMs，上游文档口径）"
            )
            assertTrue("startMs 重读未从包含位置的一段起（窗口 [${re.startMs},${re.endMs}] vs startMs=$midMs）", re.endMs >= midMs)
        }
    }
}
