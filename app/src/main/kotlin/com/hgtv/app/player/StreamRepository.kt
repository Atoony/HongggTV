package com.hgtv.app.player

import android.util.Log
import com.hgtv.app.net.HongguoApi
import kotlinx.coroutines.delay

/** 空响应（200+0）监控：后台计数 + 日志；≥3 次/天 = 风控升级信号（SPEC §5.4）。 */
object EmptyResponseMonitor {
    private val counts = HashMap<String, Int>()
    private var day = -1L

    @Synchronized
    fun record(endpoint: String) {
        val today = System.currentTimeMillis() / 86_400_000L
        if (today != day) {
            day = today
            counts.clear()
        }
        val n = (counts[endpoint] ?: 0) + 1
        counts[endpoint] = n
        Log.w("HgEmptyResp", "200+0 静默拒绝 endpoint=$endpoint 今日第 $n 次")
    }
}

/** 取流错误（E-1xxx 取流 / E-2xxx 播放器，SPEC §5.2）。 */
class StreamException(val code: String, message: String) : Exception("[$code] $message") {
    companion object {
        fun fetch(message: String) = StreamException("E-1001", message)
        fun empty(endpoint: String) = StreamException("E-1002", "取流被静默拒绝（200+0）endpoint=$endpoint")
    }
}

/**
 * 取流仓库：SPEC §5.2 错误链第一段——同链路重试 2 次（退避 1s/3s）→ 抛 StreamException。
 * 响应内多线路（main_url + backup_url）由 PlayerController 切换。
 */
class StreamRepository(private val api: HongguoApi) {

    companion object {
        /** 网页兜底线路：游客直链 360p 干净 MP4（无 CENC），App 通道风控/失败时的最后一线。 */
        fun webFallbackOption(videoId: String): HongguoApi.StreamOption = HongguoApi.StreamOption(
            name = "网页直链",
            quality = 360,
            codec = "h264",
            urls = listOf("https://hongguoduanju.com/video/public/$videoId/360p.mp4"),
            cencKey = null,
        )
    }

    suspend fun fetchStreamsWithRetry(videoId: String): List<HongguoApi.StreamOption> {
        val backoffs = longArrayOf(0, 1000, 3000)
        var lastError: Exception? = null
        for (waitMs in backoffs) {
            if (waitMs > 0) delay(waitMs)
            try {
                return api.fetchStreams(videoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: HongguoApi.ApiException) {
                if (e.message?.contains("HTTP 200 0B") == true) {
                    EmptyResponseMonitor.record("video_model")
                    lastError = StreamException.empty("video_model")
                } else {
                    lastError = StreamException.fetch(e.message ?: "取流失败")
                }
            } catch (e: Exception) {
                lastError = StreamException.fetch("网络错误: ${e.message}")
            }
        }
        // 多线路 fallback 的最后一环：切网页直链（360p 无 DRM），免费集可直接播
        if (videoId.length >= 10) {
            Log.w("HgWebFallback", "App 取流重试耗尽，切网页直链 videoId=$videoId err=${lastError?.message}")
            return listOf(webFallbackOption(videoId))
        }
        throw lastError ?: StreamException.fetch("取流重试耗尽 videoId=$videoId")
    }
}
