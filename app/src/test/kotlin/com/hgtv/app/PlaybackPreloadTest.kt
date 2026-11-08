package com.hgtv.app

import com.hgtv.app.domain.Playback
import com.hgtv.app.player.PlaybackPreloadCache
import com.hgtv.app.player.preloadPlayback
import com.hgtv.app.player.shouldAutoNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun playbackOf(episodeId: String) = Playback(
    episodeId = episodeId,
    contentKey = null,
    lines = listOf(Playback.Line(name = "1080p", quality = 1080, codec = "bytevc1", urls = listOf("https://cdn.example/$episodeId.mp4"))),
)

/** 票16 预载决策与缓存（D2-A/D3-A）：该不该预载 / 缓存是否可用 / 在途去重。 */
class PlaybackPreloadTest {

    @Test
    fun `预载缓存按集命中`() {
        var now = 0L
        val cache = PlaybackPreloadCache(nowMs = { now })
        val ep4 = playbackOf("ep4")
        cache.put(ep4)
        assertSame(ep4, cache.get("ep4"))
        assertNull("未预载过的集不命中", cache.get("ep9"))
    }

    @Test
    fun `缓存超龄弃用重取`() {
        var now = 0L
        val cache = PlaybackPreloadCache(nowMs = { now })
        cache.put(playbackOf("ep4"))
        now = 10 * 60_000L - 1
        assertNotNull("窗口内最后 1ms 仍新鲜", cache.get("ep4"))
        now = 10 * 60_000L
        assertNull("超龄视同未缓存（直链可能已过期）", cache.get("ep4"))
    }

    @Test
    fun `invalidate 弃缓存强制现取`() {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        cache.put(playbackOf("ep4"))
        cache.invalidate("ep4")
        assertNull(cache.get("ep4"))
    }

    @Test
    fun `预载成功入缓存`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        val fetched = playbackOf("ep4")
        assertTrue(preloadPlayback("ep4", cache) { fetched })
        assertSame(fetched, cache.get("ep4"))
    }

    @Test
    fun `预载失败静默不入缓存`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        assertFalse("失败静默不扰播放", preloadPlayback("ep4", cache) { throw IllegalStateException("配额耗尽") })
        assertNull("失败结果不进缓存", cache.get("ep4"))
    }

    @Test
    fun `预载取消直抛不吞`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        try {
            preloadPlayback("ep4", cache) { throw CancellationException("作用域取消") }
            fail("取消必须直抛（票14 吞取消坑）")
        } catch (e: CancellationException) {
            // 预期：退出播放器/换集取消预载，不能被静默吞掉
        }
    }

    @Test
    fun `已预载不重复发起`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        val first = playbackOf("ep4")
        cache.put(first)
        var fetches = 0
        assertTrue(preloadPlayback("ep4", cache) { fetches++; playbackOf("ep4-another") })
        assertEquals("重连后再 READY 不重复消耗匿名配额", 0, fetches)
        assertSame(first, cache.get("ep4"))
    }

    @Test
    fun `在途取流同集等结果不双取`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = async { cache.loadDedup("ep4") { entered.complete(Unit); release.await(); playbackOf("ep4") } }
        entered.await() // 第一路已进入 fetch（模拟 READY 后预载在途）
        var soloFetches = 0
        val jumper = async { cache.loadDedup("ep4") { soloFetches++; playbackOf("ep4-solo") } } // ⏭ 跳到同集
        release.complete(Unit)
        val loaded = loader.await()
        assertSame("⏭ 等到在途预载的结果", loaded, jumper.await())
        assertEquals("在途期间跳集不双发 video_model", 0, soloFetches)
    }

    @Test
    fun `在途失败等待方回落自己现取`() = runBlocking {
        val cache = PlaybackPreloadCache(nowMs = { 0L })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preloader = async {
            try {
                cache.loadDedup("ep4") { entered.complete(Unit); release.await(); throw IllegalStateException("配额耗尽") }
            } catch (e: IllegalStateException) {
                "预载方收到自己的失败"
            }
        }
        entered.await()
        val fresh = playbackOf("ep4-fresh")
        val loader = async { cache.loadDedup("ep4") { fresh } } // 等待方在途期间进来
        release.complete(Unit)
        assertEquals("预载方收到自己的失败", preloader.await())
        assertSame("在途失败不连坐，装载方自己现取", fresh, loader.await())
    }

    @Test
    fun `autoNext 关闭不发起预载`() {
        assertFalse(shouldAutoNext(autoNext = false, currentEpisode = 3, episodeCount = 10))
    }

    @Test
    fun `开启且有下一集才发起`() {
        assertTrue(shouldAutoNext(autoNext = true, currentEpisode = 3, episodeCount = 10))
    }

    @Test
    fun `末集无下一集不发起`() {
        assertFalse(shouldAutoNext(autoNext = true, currentEpisode = 10, episodeCount = 10))
    }
}
