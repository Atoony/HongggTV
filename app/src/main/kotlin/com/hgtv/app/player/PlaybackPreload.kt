package com.hgtv.app.player

import com.hgtv.app.domain.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 下一集预解析（票16，2026-11-08 电视实测拍板 D2-A/D3-A）：
 * 当前集 READY 后自动连播场景下，后台预取下一集 Playback（线路全集+密钥）入缓存，
 * 换集装载先查缓存直用，缩短"正在切换…"窗口。
 */

/**
 * 自动连播门槛：autoNext 开启且有下一集。两处共用（票16 评审收敛，改口径只动一处）：
 * STATE_ENDED 自动推进 / READY 后发起下一集预载（D3-A：仅 autoNext 开启才发起）。
 */
internal fun shouldAutoNext(autoNext: Boolean, currentEpisode: Int, episodeCount: Int): Boolean =
    autoNext && currentEpisode < episodeCount

/**
 * 按 episodeId 存取流结果 + 本地时间戳（时钟注入可离线测）。
 * 与 data.TTLCache 同形过期谓词但不合并：那边单槽值缓存、这边键控+在途去重+淘汰，
 * 硬凑泛型反而是过度设计（票16 评审裁量）。
 */
internal class PlaybackPreloadCache(
    private val ttlMs: Long = PRELOAD_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val cachedAt: Long, val playback: Playback)

    private val entries = HashMap<String, Entry>()

    /** 同集在途取流（票16 评审：⏭ 跳到正在预载的集时等结果，防匿名配额双耗）。 */
    private val inFlight = HashMap<String, CompletableDeferred<Playback?>>()

    /** 未超龄命中；超龄视同未缓存（SDK 直链带 expiresAt，保守本地窗口，弃用重取）。 */
    fun get(episodeId: String): Playback? =
        entries[episodeId]?.takeIf { nowMs() - it.cachedAt < ttlMs }?.playback

    fun put(playback: Playback) {
        evictExpired()
        entries[playback.episodeId] = Entry(nowMs(), playback)
    }

    /** 弃某集缓存（错误页重试：直链可能已过期，强制现取）。 */
    fun invalidate(episodeId: String) {
        entries.remove(episodeId)
    }

    /**
     * 取流（装载/预载共用）：未超龄命中直用；同集在途则等它（失败/被弃置 null → 自己现取，
     * 装载错误语义不被在途方吞掉）；否则现取并按集入缓存。调用方全在主线程组合协程，
     * 挂起点之间无并发写。
     */
    suspend fun loadDedup(episodeId: String, fetch: suspend (String) -> Playback): Playback {
        get(episodeId)?.let { return it }
        inFlight[episodeId]?.let { pending ->
            return pending.await() ?: fetch(episodeId)
        }
        val mine = CompletableDeferred<Playback?>()
        inFlight[episodeId] = mine
        return try {
            val playback = fetch(episodeId)
            put(playback)
            mine.complete(playback)
            playback
        } catch (e: Throwable) {
            mine.complete(null) // 在途等待方回落自己现取
            throw e
        } finally {
            inFlight.remove(episodeId)
        }
    }

    /** 追剧长会话防只增不减（minSdk 21，无 removeIf，迭代器淘汰）。 */
    private fun evictExpired() {
        val cutoff = nowMs() - ttlMs
        val iter = entries.entries.iterator()
        while (iter.hasNext()) if (iter.next().value.cachedAt < cutoff) iter.remove()
    }

    companion object {
        /** 票16 D2-A：10min 保守窗口。 */
        internal const val PRELOAD_TTL_MS = 10 * 60_000L
    }
}

/**
 * 预载一步（票16 D2-A）：未缓存才现取入缓存（在途去重见 [PlaybackPreloadCache.loadDedup]）。
 * 失败静默返回 false 不扰当前播放；取消直抛（票14 runCatching 吞取消坑——吞了会误吞
 * 退出播放器时的作用域取消）。
 */
internal suspend fun preloadPlayback(
    episodeId: String,
    cache: PlaybackPreloadCache,
    fetch: suspend (String) -> Playback,
): Boolean {
    if (cache.get(episodeId) != null) return true
    return try {
        cache.loadDedup(episodeId, fetch)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
