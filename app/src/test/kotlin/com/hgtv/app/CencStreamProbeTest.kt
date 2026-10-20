package com.hgtv.app

import com.hgtv.app.net.HongguoApi
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** CENC 流形态探针（票 04 风险项）：拉流前 64KB，分析 mp4 box 决定解密模式。 */
class CencStreamProbeTest {

    private fun live(): Boolean = System.getenv("HGTV_LIVE_PROBE") == "1"

    @Test
    fun `流形态 - 拉取头部并分析加密 box`() {
        assumeTrue(live())
        val api = HongguoApi()
        val streams = api.fetchStreams("7688396347107445822")
        val opt = streams.maxByOrNull { it.quality } ?: throw IllegalStateException("无流")
        val url = opt.urls.first()
        println("URL=$url")
        println("KEY=" + (opt.cencKey?.joinToString("") { "%02x".format(it) } ?: "无"))
        println("QUALITY=${opt.quality} NAME=${opt.name} URLS=${opt.urls.size}")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        val status = conn.responseCode
        println("HTTP=$status TYPE=${conn.contentType} LEN=${conn.contentLengthLong}")
        assumeTrue(status == 200)
        val head = ByteArray(64 * 1024)
        var filled = 0
        conn.inputStream.use { s ->
            while (filled < head.size) {
                val n = s.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        println("READ=$filled")
        // 顶层 box 遍历
        var off = 0
        while (off + 8 <= filled) {
            val size = ((head[off].toLong() and 0xff) shl 24) or ((head[off + 1].toLong() and 0xff) shl 16) or
                ((head[off + 2].toLong() and 0xff) shl 8) or (head[off + 3].toLong() and 0xff)
            val type = String(head, off + 4, 4, Charsets.US_ASCII)
            if (size < 8) {
                println("BOX@$off type=$type size=$size (size1→moov在尾部，头部到 $off 为止)")
                break
            }
            println("BOX@$off type=$type size=$size")
            if (type == "moov") {
                // moov 内二级 box 找 tenc/sinf/schm
                scanChildren(head, off + 8, (off + size).toInt().coerceAtMost(filled), 1)
            }
            off += size.toInt()
            if (off >= filled) break
        }
        // ftyp 内容（品牌）
        if (filled >= 16) println("BRAND=" + String(head, 8, 4, Charsets.US_ASCII))
    }

    private fun scanChildren(buf: ByteArray, from: Int, to: Int, depth: Int) {
        var off = from
        while (off + 8 <= to) {
            val size = ((buf[off].toLong() and 0xff) shl 24) or ((buf[off + 1].toLong() and 0xff) shl 16) or
                ((buf[off + 2].toLong() and 0xff) shl 8) or (buf[off + 3].toLong() and 0xff)
            val type = String(buf, off + 4, 4, Charsets.US_ASCII)
            if (size < 8) return
            val pad = "  ".repeat(depth)
            if (type in setOf("trak", "mdia", "minf", "stbl", "sinf", "schi", "moof", "traf")) {
                println("${pad}BOX@$off type=$type size=$size")
                scanChildren(buf, off + 8, (off + size).toInt().coerceAtMost(to), depth + 1)
            } else if (type in setOf("tenc", "schm", "enca", "encv", "senc", "saiz", "saio")) {
                // tenc 内容：version/flags + reserved + isProtected + perSampleIVSize + kid + constantIV
                var detail = ""
                if (type == "tenc" && size >= 28) {
                    val body = off + 8
                    val isProt = buf[body + 6].toInt() and 0xff
                    val ivSize = buf[body + 7].toInt() and 0xff
                    val kid = (body + 8 until body + 24).joinToString("") { "%02x".format(buf[it]) }
                    detail = " isProtected=$isProt perSampleIVSize=$ivSize kid=$kid"
                    if (isProt == 1 && ivSize == 0 && size > 28L + 8) {
                        val constIvLen = buf[body + 24].toInt() and 0xff
                        detail += " constantIVLen=$constIvLen"
                    }
                }
                println("${pad}CENC-BOX@$off type=$type size=$size$detail")
            }
            off += size.toInt()
        }
    }
}
