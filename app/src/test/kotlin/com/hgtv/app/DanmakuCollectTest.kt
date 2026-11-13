package com.hgtv.app

import com.hgtv.app.data.OpenhongguoSource.Companion.DANMAKU_MAX_PAGES
import com.hgtv.app.data.OpenhongguoSource.Companion.collectDanmaku
import com.hgtv.app.data.PlayerSettingsStore
import dev.openhongguo.Danmaku as SdkDanmaku
import dev.openhongguo.DanmakuPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 票19 ②：弹幕翻页聚合（纯函数离线测）。SDK 形态事实取自 2026-11-13 探针纪要（票面①）：
 * 30 秒一段、hasMore+cursor 链、空窗口仍 hasMore=true、total 跨页漂移不可依赖。
 */
class DanmakuCollectTest {

    private fun page(
        offsets: List<Pair<String, Long>>, // text to offsetMs
        hasMore: Boolean = false,
        cursor: String = "",
    ) = DanmakuPage(
        items = offsets.map { SdkDanmaku(text = it.first, offsetMs = it.second) },
        startMs = 0,
        endMs = 30_000,
        hasMore = hasMore,
        cursor = cursor,
    )

    @Test
    fun `串行翻页到 hasMore false 聚合按 offsetMs 排序`() = runBlocking {
        val calls = mutableListOf<String?>()
        val items = collectDanmaku { cursor ->
            calls += cursor
            when (cursor) {
                null -> page(listOf("早" to 8_000, "晚" to 2_000), hasMore = true, cursor = "c1")
                "c1" -> page(listOf("中" to 5_000), hasMore = true, cursor = "c2")
                else -> page(emptyList()) // 尾窗口空但 hasMore=false 收敛（探针实录形态）
            }
        }
        assertEquals(listOf<Long>(2_000, 5_000, 8_000), items.map { it.offsetMs })
        assertEquals(listOf(null, "c1", "c2"), calls)
    }

    @Test
    fun `空窗口 hasMore true 继续翻不提前收`() = runBlocking {
        val items = collectDanmaku { cursor ->
            if (cursor == null) page(emptyList(), hasMore = true, cursor = "c1") else page(listOf("一条" to 31_500))
        }
        assertEquals(listOf(31_500L), items.map { it.offsetMs })
    }

    @Test
    fun `hasMore true 但 cursor 空则终止防死循环`() = runBlocking {
        var calls = 0
        val items = collectDanmaku {
            calls++
            page(listOf("只有一条" to 1_000), hasMore = true, cursor = "")
        }
        assertEquals(1, calls)
        assertEquals(1, items.size)
    }

    @Test
    fun `页数上限保护恒 hasMore 不无限翻`() = runBlocking {
        var calls = 0
        val items = collectDanmaku {
            calls++
            page(listOf("第${calls}条" to calls * 1_000L), hasMore = true, cursor = "c$calls")
        }
        assertEquals(DANMAKU_MAX_PAGES, calls)
        assertEquals(DANMAKU_MAX_PAGES, items.size)
    }

    @Test
    fun `滤空白文本`() = runBlocking {
        val items = collectDanmaku {
            page(listOf("正常" to 1_000, "  " to 2_000, "" to 3_000))
        }
        assertEquals(listOf("正常"), items.map { it.text })
    }

    @Test
    fun `中途翻页失败静默返回空列表`() = runBlocking {
        val items = collectDanmaku { cursor ->
            if (cursor == null) page(listOf("首页有" to 1_000), hasMore = true, cursor = "c1")
            else throw IllegalStateException("频控/网络随便什么")
        }
        assertTrue("失败必须静默为空而非半截数据或异常", items.isEmpty())
    }

    @Test
    fun `取消直抛不被静默吞掉`() = runBlocking {
        try {
            collectDanmaku { throw CancellationException("退出播放器") }
            fail("应直抛 CancellationException")
        } catch (e: CancellationException) {
            // 票14 坑：吞取消会误吞退出播放器时的作用域取消
        }
    }

    @Test
    fun `开关默认开`() {
        // 票19 ⑤：默认开（关弹幕重启记忆靠 DataStore，默认值常量此处钉死防无声改动）
        assertTrue(PlayerSettingsStore.DANMAKU_DEFAULT)
    }
}
