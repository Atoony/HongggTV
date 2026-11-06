package com.hgtv.app.player

/**
 * openhongguo 直链（qznovelvod CDN）CENC progressive MP4 的 moov 只读解析。
 *
 * 目的：给解密 DataSource 提供每样本（文件绝对偏移、字节大小、8 字节 IV）总表——
 * media3 的 progressive Mp4Extractor 不支持 CENC（样本输出恒无加密标志），
 * 样本级解密只能自做（CTR 流密码，解密后逐字节等长同位，moov 无需改动）。
 *
 * 布局按探针实证（MediaBoxProbeTest，2026-11），与标准 MP4 的差异：
 *  - stco/stsz/stsc 被该 muxer FullBox 化：4cc 后多 4 字节 ver/flags(0)，计数字段再后移 4；
 *  - tenc 为旧版布局（ver/flags 后 reserved 仅 3 字节），且盒 size 字段少写 1（实际占 33）
 *    ——盒树遍历不能依赖它，改按特征全 stbl 区扫描；
 *  - senc v0 flags=0：无子样本表，每样本仅 perSampleIVSize 字节 IV（全样本加密）。
 *
 * 任何不符（如 senc 带子样本表、计数不齐）直接抛 [FormatException]：明确失败好过静默错解。
 */
object CencMoovTables {

    class FormatException(message: String) : Exception(message)

    /** 一个加密样本：文件绝对偏移（原始坐标系，解密前后同位）、大小、8 字节 IV。 */
    class CencSample(val offset: Long, val size: Int, val iv: ByteArray)

    /**
     * 全文件样本总表（全部 trak 合并，按偏移升序）。
     * [ivSize] 来自 tenc（perSampleIVSize，实测 8）。
     */
    class CencMoov(val samples: List<CencSample>, val ivSize: Int)

    /** 解析 moov 区字节（stco 值即文件绝对偏移，无需换算）。 */
    fun parse(moov: ByteArray): CencMoov {
        var ivSize = -1
        val samples = mutableListOf<CencSample>()
        forEachChild(moov, 8, moov.size) { type, start, size ->
            if (type == "trak") {
                ivSize = parseTrak(moov, start, size, samples, ivSize)
            }
        }
        if (samples.isEmpty()) throw FormatException("moov 内无加密样本表")
        if (ivSize !in 1..16) throw FormatException("tenc perSampleIVSize 异常: $ivSize")
        samples.sortBy { it.offset }
        return CencMoov(samples, ivSize)
    }

    /** trak→mdia→minf→stbl 三层纯容器，收集 stco/stsz/stsc/senc 并展开成样本表。 */
    private fun parseTrak(
        buf: ByteArray, trakStart: Int, trakSize: Int,
        out: MutableList<CencSample>, ivSizeIn: Int,
    ): Int {
        var ivSize = ivSizeIn
        var stco: LongArray? = null
        var stsz: IntArray? = null
        var stsc: List<Triple<Int, Int, Int>>? = null // (firstChunk, samplesPerChunk, descIdx)
        var senc: Array<ByteArray>? = null
        var tencIvSize = -1
        forEachChild(buf, trakStart + 8, trakStart + trakSize) { type, start, size ->
            if (type == "mdia") {
                forEachChild(buf, start + 8, start + size) { t2, s2, z2 ->
                    if (t2 == "minf") {
                        forEachChild(buf, s2 + 8, s2 + z2) { t3, s3, z3 ->
                            if (t3 == "stbl") {
                                // tenc 深埋 sinf/schi 且盒 size 字段不可信，按特征扫描；
                                // 先扫再遍历——senc 的 IV 定长读取依赖 ivSize
                                tencIvSize = scanTencIvSize(buf, s3 + 8, s3 + z3)
                                if (tencIvSize > 0) ivSize = tencIvSize
                                forEachChild(buf, s3 + 8, s3 + z3) { t4, s4, z4 ->
                                    when (t4) {
                                        "stco" -> stco = parseStco(buf, s4, z4)
                                        "stsz" -> stsz = parseStsz(buf, s4, z4)
                                        "stsc" -> stsc = parseStsc(buf, s4, z4)
                                        "senc" -> senc = parseSenc(buf, s4, z4, tencIvSize)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 明文轨（无 senc 亦无 tenc，如未加密音频轨）跳过：其样本不进加密表，字节按间隙透传；
        // tenc 声明加密却无 senc 属未知布局，明确失败
        val ivs = senc
            ?: if (tencIvSize < 0) return ivSize
            else throw FormatException("tenc 声明加密却缺 senc（IV 表布局未知）")
        val chunkOffsets = stco ?: throw FormatException("stbl 缺 stco")
        val sampleSizes = stsz ?: throw FormatException("stbl 缺 stsz")
        val runs = stsc ?: throw FormatException("stbl 缺 stsc")
        if (ivs.size != sampleSizes.size) {
            throw FormatException("senc 样本数 ${ivs.size} != stsz 样本数 ${sampleSizes.size}")
        }
        // stsc 展开：run 覆盖 firstChunk..nextRun.firstChunk-1，末 run 覆盖到 chunk 末尾
        var sampleIdx = 0
        for (chunk in chunkOffsets.indices) {
            val chunkNo = chunk + 1
            var runIdx = runs.indexOfLast { it.first <= chunkNo }
            if (runIdx < 0) throw FormatException("stsc 无覆盖 chunk $chunkNo 的 run")
            val perChunk = runs[runIdx].second
            var offInChunk = 0L
            repeat(perChunk) {
                if (sampleIdx >= sampleSizes.size) return@repeat
                out += CencSample(
                    offset = chunkOffsets[chunk] + offInChunk,
                    size = sampleSizes[sampleIdx],
                    iv = ivs[sampleIdx],
                )
                offInChunk += sampleSizes[sampleIdx]
                sampleIdx++
            }
        }
        if (sampleIdx != sampleSizes.size) {
            throw FormatException("stsc 展开只覆盖 $sampleIdx/${sampleSizes.size} 样本")
        }
        return ivSize
    }

    /** stco（FullBox 化）：entryCount@start+12，条目 u32 从 start+16。 */
    private fun parseStco(buf: ByteArray, start: Int, size: Int): LongArray {
        val count = u32(buf, start + 12).toInt()
        if (count < 0 || start + 16L + count * 4L > start + size) {
            throw FormatException("stco count=$count 越界 size=$size")
        }
        return LongArray(count) { u32(buf, start + 16 + it * 4) }
    }

    /** stsz（FullBox 化）：sampleSize@start+12（0=每样本可变），count@start+16，条目从 start+20。 */
    private fun parseStsz(buf: ByteArray, start: Int, size: Int): IntArray {
        val fixed = u32(buf, start + 12).toInt()
        val count = u32(buf, start + 16).toInt()
        if (fixed != 0) return IntArray(count) { fixed }
        if (count < 0 || start + 20L + count * 4L > start + size) {
            throw FormatException("stsz count=$count 越界 size=$size")
        }
        return IntArray(count) { u32(buf, start + 20 + it * 4).toInt() }
    }

    /** stsc（FullBox 化）：entryCount@start+12，条目 (u32,u32,u32) 从 start+16。 */
    private fun parseStsc(buf: ByteArray, start: Int, size: Int): List<Triple<Int, Int, Int>> {
        val count = u32(buf, start + 12).toInt()
        if (count < 0 || start + 16L + count * 12L > start + size) {
            throw FormatException("stsc count=$count 越界 size=$size")
        }
        return (0 until count).map {
            Triple(
                u32(buf, start + 16 + it * 12).toInt(),
                u32(buf, start + 20 + it * 12).toInt(),
                u32(buf, start + 24 + it * 12).toInt(),
            )
        }
    }

    /** senc v0 flags=0：sampleCount@start+12，随后每样本 ivSize 字节 IV（无子样本表）。 */
    private fun parseSenc(buf: ByteArray, start: Int, size: Int, ivSize: Int): Array<ByteArray> {
        val flags = u32(buf, start + 8) and 0xffffff
        if (flags and 0x2 != 0L) {
            throw FormatException("senc 带子样本表（flags=$flags），未支持——按探针实证此 CDN 为全样本加密")
        }
        val count = u32(buf, start + 12).toInt()
        if (ivSize !in 1..16 || count < 0 || start + 16L + count * ivSize.toLong() > start + size) {
            throw FormatException("senc count=$count ivSize=$ivSize 越界 size=$size")
        }
        return Array(count) { buf.copyOfRange(start + 16 + it * ivSize, start + 16 + (it + 1) * ivSize) }
    }

    /**
     * tenc 特征扫描：兼容两种布局（模拟器实测同一 CDN 不同文件混用）——
     * 旧版 reserved 3 字节（isProtected@4cc+11、ivSize@4cc+12、KID@4cc+13）；
     * 标准 reserved 2 字节（isProtected@4cc+10、ivSize@4cc+11、KID@4cc+12）。
     * 两特征互不误报：对方布局的读数位上分别是 reserved(0) / KID 首字节。
     * 取首个命中；盒 size 声明不可信，不参与判定。
     */
    private fun scanTencIvSize(buf: ByteArray, from: Int, to: Int): Int {
        val probe = "tenc".toByteArray(Charsets.US_ASCII)
        var i = from
        while (i + 13 <= to) { // 特征只需读到 ivSize@4cc+12（KID 不参与判定）
            if (buf[i] == probe[0] && buf[i + 1] == probe[1] && buf[i + 2] == probe[2] && buf[i + 3] == probe[3]) {
                if ((buf[i + 11].toInt() and 0xff) == 1 && (buf[i + 12].toInt() and 0xff) in 1..16) {
                    return buf[i + 12].toInt() and 0xff // 旧版
                }
                if ((buf[i + 10].toInt() and 0xff) == 1 && (buf[i + 11].toInt() and 0xff) in 1..16) {
                    return buf[i + 11].toInt() and 0xff // 标准
                }
            }
            i++
        }
        return -1
    }

    /** 顺序子盒遍历；size<8 或 type 非法即停（tenc 类坏 size 盒之后的区域不赌）。 */
    private inline fun forEachChild(
        buf: ByteArray, from: Int, to: Int,
        visit: (type: String, start: Int, size: Int) -> Unit,
    ) {
        var pos = from
        while (pos + 8 <= to) {
            val size = u32(buf, pos).toInt()
            val type = String(buf, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || !type.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return
            visit(type, pos, size)
            pos += size
        }
    }

    private fun u32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xff) shl 24) or ((buf[at + 1].toLong() and 0xff) shl 16) or
            ((buf[at + 2].toLong() and 0xff) shl 8) or (buf[at + 3].toLong() and 0xff)
}
