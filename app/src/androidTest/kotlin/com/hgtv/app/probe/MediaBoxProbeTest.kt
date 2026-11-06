package com.hgtv.app.probe

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 票13 诊断探针：SDK 直链的 MP4 容器形态（一次性，hgLive=1 单方法跑）。
 * 背景：模拟器真播报 `Invalid NAL length`（样本仍加密 → DRM 会话没吃到流），
 * 头号嫌疑是票04 研究预警过的 moov 缺 pssh（ExoPlayer#8237 无 pssh 不建会话）。
 * 产出：盒树（moov 递归）+ pssh/sinf/tenc/senc 有无 + 是否 fragmented（moof）。
 */
@RunWith(AndroidJUnit4::class)
class MediaBoxProbeTest {

    private val tag = "OhgBoxProbe"
    private fun live() = InstrumentationRegistry.getArguments().getString("hgLive") == "1"
    private fun log(msg: String) {
        Log.i(tag, msg)
        println(msg)
    }

    @Test
    fun box_inspect() {
        assumeTrue(live())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OpenhongguoClient.create(context, ClientOptions(logger = { Log.d(tag, it) }))
        runBlocking {
            // 首页调试样本剧（HomePage.DEBUG_SERIES_ID），一集即可
            val eps = client.listEpisodes("7688352674999176254")
            val ep = eps.items.first { it.playable }
            val r = client.resolvePlayback(ep.id)
            // 五档全览：是否有 HLS/DASH 变体（fMP4 分片才有现成 CENC 播放路径）
            r.playback.streams.forEach { s ->
                log("STREAM ${s.definition}/${s.codec} enc=${s.encryption} hls=${s.hls} dash=${s.dash} url=${s.url.take(96)}")
            }
            val s = r.playback.streams.first()
            log("episode=${ep.id} protected=${r.playback.protected} contentKey=${r.contentKey != null}")
            fetchAndWalk(s.url)
        }
    }

    private fun fetchAndWalk(url: String) {
        val want = 768 * 1024
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-${want - 1}")
            setRequestProperty("User-Agent", "com.phoenix.read/73532")
            setRequestProperty("Referer", "https://novel.snssdk.com/")
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        val status = conn.responseCode
        val total = conn.getHeaderField("Content-Range") ?: conn.getHeaderField("Content-Length") ?: "?"
        log("HTTP $status total=$total type=${conn.contentType}")
        val head = ByteArray(want)
        val input: InputStream = conn.inputStream
        var filled = 0
        while (filled < want) {
            val n = input.read(head, filled, want - filled)
            if (n < 0) break
            filled += n
        }
        conn.disconnect()
        log("fetched $filled bytes")
        Cursor(head, filled).also { c ->
            c.walk(0, filled.toLong(), 0)
            // 首个 mdat 之外若还有内容（moof 在 mdat 后），继续走 top-level
        }
        scanTenc(head, filled)
        scanBoxes(head, filled)
    }

    /** saio/stco/senc 偏移语义实证：手术器要平移它们，先看值长什么样。 */
    private fun scanBoxes(buf: ByteArray, limit: Int) {
        for (type in listOf("saio", "stco", "stsz", "stsc", "senc")) {
            val probe = type.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val t = indexOf(buf, probe, from, limit) ?: break
                from = t + 1
                val size = u32At(buf, t - 4)
                val hex = (t - 8 until minOf(t + 32, limit)).joinToString(" ") { "%02x".format(buf[it]) }
                log("[$type] @${t} size=$size head=[$hex]")
            }
        }
    }

    private fun u32At(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xff) shl 24) or ((buf[at + 1].toLong() and 0xff) shl 16) or
            ((buf[at + 2].toLong() and 0xff) shl 8) or (buf[at + 3].toLong() and 0xff)

    /** 采样条目固定字段因条目版本而异（视觉 78B/音频 28B+），递归对不齐——直接全量扫 tenc 4cc。 */
    private fun scanTenc(buf: ByteArray, limit: Int) {
        val probe = "tenc".toByteArray(Charsets.US_ASCII)
        var from = 0
        while (true) {
            val t = indexOf(buf, probe, from, limit) ?: break
            from = t + 1
            // t 指向 4cc(type 字段)：size@t-4，body=ver/flags(4)+reserved(4)+isProtected(1)+ivSize(1)+KID(16)
            val size = ((buf[t - 4].toLong() and 0xff) shl 24) or ((buf[t - 3].toLong() and 0xff) shl 16) or
                ((buf[t - 2].toLong() and 0xff) shl 8) or (buf[t - 1].toLong() and 0xff)
            val hex = (t - 8 until minOf(t + 40, limit)).joinToString(" ") { "%02x".format(buf[it]) }
            log("TENC@${t} size=$size hex(size@-4起)=[$hex]")
        }
    }

    private fun indexOf(buf: ByteArray, needle: ByteArray, from: Int, limit: Int): Int? {
        outer@ for (i in from..limit - needle.size) {
            for (j in needle.indices) if (buf[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    /** 顺序游标盒遍历；容器盒递归（moov 内三到四层足够看到 pssh/stsd/sinf）。 */
    private inner class Cursor(private val buf: ByteArray, private val limit: Int) {
        private val containers =
            setOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "moof", "traf", "mvex", "edts", "udta")

        /** 进入容器体前的额外跳过：stsd=ver/flags+entryCount(8)，meta=ver/flags(4)+空位(4)。 */
        private fun bodySkip(type: String): Long = when (type) {
            "stsd", "meta" -> 8
            else -> 0
        }

        fun u32(at: Int): Long =
            ((buf[at].toLong() and 0xff) shl 24) or ((buf[at + 1].toLong() and 0xff) shl 16) or
                ((buf[at + 2].toLong() and 0xff) shl 8) or (buf[at + 3].toLong() and 0xff)

        fun fourcc(at: Int): String = String(buf, at, 4, Charsets.US_ASCII)

        fun walk(start: Long, end: Long, depth: Int) {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= limit) {
                val at = pos.toInt()
                var size = u32(at)
                val type = fourcc(at + 4)
                var headerLen = 8L
                if (size == 1L) {
                    if (at + 16 > limit) return
                    size = 0
                    for (i in 8 until 16) size = (size shl 8) or (buf[at + i].toLong() and 0xff)
                    headerLen = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                log("${"  ".repeat(depth)}[$type] @${pos} size=$size")
                if (type == "pssh") dumpPssh((pos + headerLen).toInt())
                if (type == "tenc") dumpTenc((pos + headerLen).toInt())
                if (type in containers && depth < 8) {
                    walk(pos + headerLen + bodySkip(type), minOf(pos + size, end), depth + 1)
                }
                if (size <= 0) return
                pos += size
            }
        }

        private fun dumpPssh(body: Int) {
            if (body + 24 > limit) return log("  pssh body 过短")
            val version = buf[body].toInt()
            val systemId = (body + 4 until body + 20).joinToString("") { "%02x".format(buf[it]) }
            var line = "  pssh v$version systemId=$systemId"
            if (version == 1 && body + 24 <= limit) {
                val kidCount = u32(body + 20).toInt()
                line += " kids="
                for (i in 0 until kidCount) {
                    val k = body + 24 + i * 16
                    if (k + 16 > limit) break
                    line += (k until k + 16).joinToString("") { "%02x".format(buf[it]) } + ";"
                }
            }
            log(line)
        }

        private fun dumpTenc(body: Int) {
            if (body + 24 > limit) return log("  tenc body 过短")
            // version/flags(4) 保留(4) isProtected(1) perSampleIVSize(1) KID(16)
            val kid = (body + 10 until body + 26).joinToString("") { "%02x".format(buf[it]) }
            log("  tenc protected=${buf[body + 8]} ivSize=${buf[body + 9]} KID=$kid")
        }
    }
}
