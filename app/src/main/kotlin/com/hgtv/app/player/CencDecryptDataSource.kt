package com.hgtv.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 样本级 CENC 解密 DataSource（票13：openhongguo 直链的 progressive CENC MP4）。
 *
 * 背景：media3 progressive Mp4Extractor 不支持 CENC（样本恒无加密标志，实测至 1.11），
 * 直链又无 HLS/DASH 变体——只能本地解密。CTR 是流密码，解密逐字节等长同位：
 * 本 DataSource 对调用方呈现"与原文件同构的明文流"，moov 一字不改（encv/senc 留存无害），
 * 样本表由 [CencMoovTables] 只读解析。
 *
 * 结构：头区（ftyp+moov+…，至首个样本前）整块缓存透传；样本区按窗口（≥512KB，边界
 * 对齐样本）用 base 拉原始字节、AES-CTR 逐样本原位解密；样本间隙字节透传。
 * seek（open(position) 落样本中间）时重解整样本、跳读至目标位——CTR 计数器无传染。
 */
class CencDecryptDataSource(
    private val base: DataSource,
    private val contentKey: ByteArray,
    private val factory: CencDecryptDataSourceFactory? = null,
) : DataSource {

    private var tables: CencMoovTables.CencMoov? = null
    private var headBytes: ByteArray = ByteArray(0) // [0, headEnd) 原始透传
    private var headEnd: Long = -1
    private var totalLen: Long = -1

    // 当前读会话
    private var pos: Long = -1
    private var specRemaining: Long = -1 // dataSpec.length（-1=未设）
    private var windowBuf: ByteArray? = null
    private var windowStart: Long = -1

    override fun addTransferListener(transferListener: TransferListener) {
        base.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        ensureTables(dataSpec)
        currentSpec = dataSpec
        pos = dataSpec.position
        specRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else -1
        windowBuf = null
        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalLen > 0 -> totalLen - pos
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (specRemaining == 0L) return C.RESULT_END_OF_INPUT
        val cap = if (specRemaining >= 0) specRemaining else Long.MAX_VALUE
        // 头区透传
        if (pos < headEnd) {
            val n = minOf(length.toLong(), headEnd - pos, cap).toInt()
            System.arraycopy(headBytes, pos.toInt(), buffer, offset, n)
            pos += n
            specRemaining = if (specRemaining > 0) specRemaining - n else specRemaining
            return n
        }
        // 样本区窗口
        var w = windowBuf
        if (w == null || pos >= windowStart + w.size) {
            if (totalLen > 0 && pos >= totalLen) return C.RESULT_END_OF_INPUT
            w = loadWindow(pos) ?: return C.RESULT_END_OF_INPUT
            if (w.isEmpty()) return C.RESULT_END_OF_INPUT // 尾部透传窗实读 0 字节=文件真到头
        }
        val n = minOf(length.toLong(), windowStart + w.size - pos, cap).toInt()
        System.arraycopy(w, (pos - windowStart).toInt(), buffer, offset, n)
        pos += n
        specRemaining = if (specRemaining > 0) specRemaining - n else specRemaining
        return n
    }

    @Throws(IOException::class)
    override fun close() {
        windowBuf = null
        base.close()
    }

    override fun getUri(): Uri? = base.uri

    override fun getResponseHeaders(): Map<String, List<String>> = base.responseHeaders

    /** 首次 open：拉头区探针、解析样本表。moov 实测 ~131KB，1MB 探针足够；超出即明确失败。 */
    private fun ensureTables(spec: DataSpec) {
        if (tables != null) return
        // 同 uri 重连/重备（换 prepare、线路重试）直接复用工厂缓存，免重拉 1MB 探针
        factory?.headCache?.let { c ->
            if (c.uri == spec.uri) {
                tables = c.tables
                headBytes = c.headBytes
                headEnd = c.headEnd
                totalLen = c.totalLen
                android.util.Log.i(TAG, "moov 样本表命中工厂缓存: ${c.tables.samples.size} 样本 uri=${spec.uri}")
                return
            }
        }
        val probe: ByteArray
        try {
            base.open(spec.buildUpon().setPosition(0).setLength(HEAD_PROBE_BYTES).build())
            probe = readFully(base, HEAD_PROBE_BYTES)
            totalLen = parseTotalFromContentRange(
                base.responseHeaders["Content-Range"]?.firstOrNull(),
            )
        } finally {
            base.close()
        }
        // 顶层盒走 ftyp/moov；moov 偏移换算样本绝对偏移
        var p = 0
        var moovAt = -1
        var moovSize = -1
        while (p + 8 <= probe.size) {
            val size = u32(probe, p).toInt()
            val type = String(probe, p + 4, 4, Charsets.US_ASCII)
            if (size < 8) break
            if (type == "moov") {
                moovAt = p
                moovSize = size
                break
            }
            p += size
        }
        if (moovAt < 0 || moovAt + moovSize > probe.size) {
            throw IOException("头区探针 ${probe.size}B 内未找到完整 moov（at=$moovAt size=$moovSize）")
        }
        val parsed = try {
            CencMoovTables.parse(probe.copyOfRange(moovAt, moovAt + moovSize))
        } catch (e: CencMoovTables.FormatException) {
            throw IOException("CENC 样本表解析失败: ${e.message}", e)
        }
        tables = parsed
        val first = parsed.samples.first().offset
        if (first > probe.size) {
            throw IOException("首个样本 @$first 超出头区探针 ${probe.size}B")
        }
        headBytes = probe.copyOf(first.toInt())
        headEnd = first
        factory?.headCache = CencDecryptDataSourceFactory.HeadCache(
            spec.uri, parsed, headBytes, headEnd, totalLen,
        )
        android.util.Log.i(
            TAG,
            "moov 样本表就绪: ${parsed.samples.size} 样本 ivSize=${parsed.ivSize} 头区=$headEnd total=$totalLen",
        )
    }

    /**
     * 从 [from] 起构建解密窗口；返回 null 表示无更多数据（EOF）。
     * 窗口起点对齐覆盖样本边界（from 落样本中间则回到该样本头重解），终点累计 ≥512KB
     * 后的样本边界（或文件尾/样本表尾），窗口内样本逐一原位解密、间隙透传。
     * 末样本之后的尾部字节（mdat 填充/free 盒）无加密样本，纯透传有界拉取。
     */
    private fun loadWindow(from: Long): ByteArray? {
        val samples = tables!!.samples
        // 首个 end > from 的样本（覆盖 from 或其后第一个）
        val idx = samples.indexOfFirst { it.offset + it.size > from }
        if (idx < 0) {
            // from 之后无样本：尾部透传窗口
            val end = if (totalLen > 0) minOf(totalLen, from + WINDOW_BYTES) else from + WINDOW_BYTES
            if (end <= from) return null
            val tail = loadRawRange(from, end)
            windowStart = from
            windowBuf = tail
            return tail
        }
        val winStart = minOf(from, samples[idx].offset)
        var winEnd = winStart
        var i = idx
        while (i < samples.size && winEnd - winStart < WINDOW_BYTES) {
            winEnd = samples[i].offset + samples[i].size
            i++
        }
        if (totalLen in 1..winEnd) winEnd = totalLen
        val data = loadRawRange(winStart, winEnd)
        // 窗口内完整样本原位解密（从 from 回退到样本头的部分会在 read 中跳过）；
        // 截断尾部的不完整样本保持密文——文件本身残缺，交由上层解析失败
        var j = idx
        while (j < samples.size) {
            val s = samples[j]
            if (s.offset >= winStart && s.offset + s.size <= winStart + data.size) {
                CencCtr.crypt(contentKey, s.iv, data, (s.offset - winStart).toInt(), s.size)
            } else if (s.offset >= winStart + data.size) break
            j++
        }
        windowStart = winStart
        windowBuf = data
        android.util.Log.d(TAG, "解密窗口 [$winStart, $winEnd) 实读=${data.size} 样本数=${j - idx} 首样本@$from")
        return data
    }

    /** 拉 [from, to) 原始字节；提前 EOF 按实读截断返回。 */
    private fun loadRawRange(from: Long, to: Long): ByteArray {
        val raw = ByteArray((to - from).toInt())
        var got = 0
        try {
            base.open(currentSpec!!.buildUpon().setPosition(from).setLength(to - from).build())
            got = readFullyInto(base, raw)
        } finally {
            base.close()
        }
        return if (got < raw.size) raw.copyOf(got) else raw
    }

    // ---------- 辅助 ----------

    private fun readFully(from: DataSource, upTo: Long): ByteArray {
        var out = ByteArray(64 * 1024)
        var filled = 0
        val buf = ByteArray(64 * 1024)
        while (filled < upTo) {
            val n = from.read(buf, 0, minOf(buf.size.toLong(), upTo - filled).toInt())
            if (n == C.RESULT_END_OF_INPUT) break
            if (filled + n > out.size) out = out.copyOf((filled + n) * 2)
            System.arraycopy(buf, 0, out, filled, n)
            filled += n
        }
        return out.copyOf(filled)
    }

    private fun readFullyInto(from: DataSource, into: ByteArray): Int {
        var filled = 0
        while (filled < into.size) {
            val n = from.read(into, filled, into.size - filled)
            if (n == C.RESULT_END_OF_INPUT) break
            filled += n
        }
        return filled
    }

    private var currentSpec: DataSpec? = null

    private fun parseTotalFromContentRange(cr: String?): Long {
        // "bytes 0-786431/1829447" → 1829447
        val total = cr?.substringAfterLast('/')?.toLongOrNull()
        return total ?: -1
    }

    private fun u32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xff) shl 24) or ((buf[at + 1].toLong() and 0xff) shl 16) or
            ((buf[at + 2].toLong() and 0xff) shl 8) or (buf[at + 3].toLong() and 0xff)

    companion object {
        private const val TAG = "OhgCenc"
        private const val HEAD_PROBE_BYTES = 1024L * 1024
        private const val WINDOW_BYTES = 512L * 1024
    }
}

/** CENC cenc 方案（AES-CTR）：计数器 = 8 字节 IV + 8 字节块计数（从 0 起）。加解密对称。 */
object CencCtr {

    fun crypt(key: ByteArray, iv8: ByteArray, data: ByteArray, from: Int, length: Int) {
        require(key.size == 16 && iv8.size == 8 && length >= 0) { "key=16B iv=8B，实际 key=${key.size} iv=${iv8.size} len=$length" }
        if (length == 0) return
        val counter = ByteArray(16)
        System.arraycopy(iv8, 0, counter, 0, 8)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val out = cipher.doFinal(data, from, length)
        System.arraycopy(out, 0, data, from, out.size)
    }
}

/** 播放器接线用：把 base 工厂包成解密工厂（明文流工厂直接透传）。 */
class CencDecryptDataSourceFactory(
    private val base: DataSource.Factory,
    private val contentKey: ByteArray,
) : DataSource.Factory {
    /**
     * 最近一次成功解析的头区/moov（单槽，按 uri 匹配）：换集/切清晰度换文件不命中，
     * 同 uri 的重连/重备/重 prepare 免重拉 1MB 探针。contentKey 每集独立，uri 即隔离键。
     */
    @Volatile
    internal var headCache: HeadCache? = null

    override fun createDataSource(): DataSource =
        CencDecryptDataSource(base.createDataSource(), contentKey, this)

    class HeadCache(
        val uri: Uri?,
        val tables: CencMoovTables.CencMoov,
        val headBytes: ByteArray,
        val headEnd: Long,
        val totalLen: Long,
    )
}
