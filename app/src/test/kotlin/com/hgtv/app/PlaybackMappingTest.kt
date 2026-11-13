package com.hgtv.app

import com.hgtv.app.data.OpenhongguoSource.Companion.mapError
import com.hgtv.app.data.OpenhongguoSource.Companion.mapPlayback
import com.hgtv.app.domain.StreamException
import dev.openhongguo.ContentKey
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.Playback
import dev.openhongguo.PlaybackResult
import dev.openhongguo.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 票13：SDK 播放结果 → domain 播放模型的映射（纯函数，离线可测；SDK 类型只允许出现在
 * 数据源层及其映射测试）。形态事实取自票12 探针纪要（.scratch/mvp/probe-openhongguo-2026-11-06.md）。
 */
class PlaybackMappingTest {

    private fun sdkStream(
        definition: String = "1080p",
        height: Int = 1080,
        width: Int = 0,
        codec: String = "bytevc1",
        url: String = "https://cdn.example.com/v.mp4",
        backupUrl: String = "https://cdn.example.com/v-bak.mp4",
        hls: Boolean = false,
        dash: Boolean = false,
    ) = Stream(
        definition = definition, quality = "normal", codec = codec,
        width = width, height = height, bitrate = 0, size = 0,
        url = url, backupUrl = backupUrl,
        hls = hls, dash = dash, protected = true, encryption = "cenc-aes-ctr",
    )

    private fun result(
        streams: List<Stream>,
        protected: Boolean = true,
        hexKey: String? = "e744f6c5a1b2c3d4e5f60718293a4b5c",
        keyError: OpenhongguoException? = null,
    ) = PlaybackResult(
        playback = Playback(
            episodeId = "7700000000000000001", videoId = "7700000000000000001",
            mediaType = "video", durationSeconds = 90, expiresAt = 0,
            protected = protected, streams = streams, audioUrl = "",
        ),
        contentKey = hexKey?.let { ContentKey(it, "v1") },
        contentKeyError = keyError,
    )

    @Test
    fun `五档加密流映射为质量降序线路且密钥还原 16 字节`() {
        val playback = mapPlayback(
            result(
                listOf(
                    sdkStream(definition = "360p", height = 360),
                    sdkStream(definition = "1080p", height = 1080),
                    sdkStream(definition = "720p", height = 720),
                    sdkStream(definition = "480p", height = 480),
                    sdkStream(definition = "540p", height = 540),
                )
            ),
            "7700000000000000001",
        )
        assertEquals(listOf(1080, 720, 540, 480, 360), playback.lines.map { it.quality })
        assertEquals("1080p", playback.lines.first().name)
        assertEquals("bytevc1", playback.lines.first().codec)
        assertTrue(playback.lines.all { it.urls == listOf("https://cdn.example.com/v.mp4", "https://cdn.example.com/v-bak.mp4") })
        assertEquals(16, playback.contentKey!!.size)
        assertEquals(0xe7, playback.contentKey[0].toInt() and 0xff)
    }

    /** 2026-11-13 实测（军需处长的账本）：竖屏流 w/h 都有且 h=物理高度（1080p 档 = 1080x1920），
     *  档位须归一到短边口径，否则 UI 标 1920P 且默认上限 1080 匹配不到最高档。 */
    @Test
    fun `竖屏流档位归一到短边而非物理高度`() {
        val playback = mapPlayback(
            result(
                listOf(
                    sdkStream(definition = "1080p", width = 1080, height = 1920),
                    sdkStream(definition = "720p", width = 720, height = 1280),
                    sdkStream(definition = "540p", width = 540, height = 960),
                    sdkStream(definition = "480p", width = 480, height = 854),
                    sdkStream(definition = "360p", width = 360, height = 640),
                )
            ),
            "7700000000000000001",
        )
        assertEquals(listOf(1080, 720, 540, 480, 360), playback.lines.map { it.quality })
    }

    @Test
    fun `无宽高流仍走 definition 数字兜底`() {
        val playback = mapPlayback(
            result(listOf(sdkStream(definition = "720p", height = 0))),
            "7700000000000000001",
        )
        assertEquals(listOf(720), playback.lines.map { it.quality })
    }

    @Test
    fun `dash 分段流被滤除 仅剩分段流时报 E-1003`() {
        val mixed = mapPlayback(
            result(listOf(sdkStream(dash = true), sdkStream(definition = "720p", height = 720))),
            "7700000000000000001",
        )
        assertEquals(listOf(720), mixed.lines.map { it.quality })

        try {
            mapPlayback(result(listOf(sdkStream(dash = true))), "7700000000000000001")
            fail("仅剩 dash 流应报无兼容线路")
        } catch (e: StreamException) {
            assertEquals("E-1003", e.code)
        }
    }

    @Test
    fun `protected 密钥缺失报 E-1004`() {
        try {
            mapPlayback(result(listOf(sdkStream()), hexKey = null, keyError = OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED, "no key", false, 0)), "7700000000000000001")
            fail("受保护流无密钥应失败")
        } catch (e: StreamException) {
            assertEquals("E-1004", e.code)
            assertTrue(e.message!!.contains("ANONYMOUS_LIMITED"))
        }
    }

    @Test
    fun `protected 密钥非法 hex 或非 16 字节报 E-1004`() {
        for (bad in listOf("zz32", "e744")) {
            try {
                mapPlayback(result(listOf(sdkStream()), hexKey = bad), "7700000000000000001")
                fail("非法密钥 $bad 应失败")
            } catch (e: StreamException) {
                assertEquals("E-1004", e.code)
            }
        }
    }

    @Test
    fun `明文流不带密钥`() {
        val playback = mapPlayback(result(listOf(sdkStream()), protected = false), "7700000000000000001")
        assertNull(playback.contentKey)
        assertEquals(1, playback.lines.size)
    }

    @Test
    fun `height 为 0 时从 definition 解析档位`() {
        val playback = mapPlayback(result(listOf(sdkStream(definition = "720p", height = 0))), "7700000000000000001")
        assertEquals(720, playback.lines.single().quality)
        assertEquals("720p", playback.lines.single().name)
    }

    @Test
    fun `backupUrl 为空的线路只保留主地址`() {
        val playback = mapPlayback(result(listOf(sdkStream(backupUrl = ""))), "7700000000000000001")
        assertEquals(listOf("https://cdn.example.com/v.mp4"), playback.lines.single().urls)
    }

    @Test
    fun `SDK 异常映射 E-1001 且保留错误码与重试提示`() {
        val e = mapError(OpenhongguoException(ErrorCode.RATE_LIMITED, "slow down", true, 30_000))
        assertEquals("E-1001", e.code)
        assertTrue(e.message!!.contains("RATE_LIMITED"))
        assertTrue(e.message!!.contains("30000"))
    }
}
