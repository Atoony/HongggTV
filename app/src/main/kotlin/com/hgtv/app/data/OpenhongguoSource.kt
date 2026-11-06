package com.hgtv.app.data

import android.content.Context
import android.util.Log
import com.hgtv.app.domain.Playback
import com.hgtv.app.domain.StreamException
import com.hgtv.app.domain.contentKeyFromHex
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.PlaybackResult
import kotlinx.coroutines.CancellationException

/**
 * 数据源层适配（ADR 0001 / CONTEXT.md）：openhongguo SDK 是一切红果数据的唯一入口，
 * SDK 类型只出现在本文件（及其映射测试），出关即 domain 值类型。重试与匿名设备轮换
 * 全交 SDK 内建（ClientOptions 默认 retry=3/deviceResets=2，Q7 共识，不自建重试）。
 */
class OpenhongguoSource(context: Context) {

    private val client = OpenhongguoClient.create(
        context,
        ClientOptions(logger = { Log.d(TAG, it) }), // 设备轮换/重试可观测（logcat tag=OhgSdk）
    )

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

    companion object {
        private const val TAG = "OhgSdk"
        private val QUALITY_NUMBER = Regex("\\d+")

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
                    val height = (s.height.takeIf { it > 0 })
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

        /** SDK 异常 → E-1001（SDK 已重试耗尽，这里只做错误页文案）。 */
        internal fun mapError(e: OpenhongguoException): StreamException = StreamException.fetch(
            buildString {
                append("SDK ").append(e.code)
                if (e.retryAfterMs > 0) append("（${e.retryAfterMs}ms 后可重试）")
                append("：").append(e.message)
            }
        )
    }
}
