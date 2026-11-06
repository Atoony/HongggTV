package com.hgtv.app

import com.hgtv.app.player.CencMoovTables
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CencMoovTables 离线测试：夹具按探针实证布局构造
 * （stco/stsz/stsc FullBox 化 + tenc 旧版 reserved 3 字节 + senc v0 全样本加密）。
 */
class CencMoovTablesTest {

    // ---------- 夹具构造 ----------

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return u32(8L + body.size) + type.toByteArray(Charsets.US_ASCII) + body
    }

    private val verFlags0 = u32(0)

    /** FullBox 化的 stbl 表盒（该 muxer 实测形态）。 */
    private fun stco(vararg offsets: Long) = box("stco", verFlags0, u32(offsets.size.toLong()), *offsets.map { u32(it) }.toTypedArray())

    private fun stsz(vararg sizes: Int): ByteArray {
        val fixed = if (sizes.size == 1) u32(sizes[0].toLong()) + u32(sizes.size.toLong())
        else u32(0) + u32(sizes.size.toLong()) + sizes.map { u32(it.toLong()) }.reduce { a, b -> a + b }
        return box("stsz", verFlags0, fixed)
    }

    private fun stsc(vararg runs: Triple<Int, Int, Int>): ByteArray {
        val entries = runs.map { u32(it.first.toLong()) + u32(it.second.toLong()) + u32(it.third.toLong()) }.reduce { a, b -> a + b }
        return box("stsc", verFlags0, u32(runs.size.toLong()) + entries)
    }

    private fun senc(vararg ivs: ByteArray): ByteArray {
        val body = ivs.fold(u32(ivs.size.toLong())) { acc, iv -> acc + iv }
        return box("senc", verFlags0, body)
    }

    private fun iv8(seed: Int): ByteArray = ByteArray(8) { ((seed + it) and 0xff).toByte() }

    /** 旧版 tenc：ver/flags(4)+reserved(3)+isProtected(1)+ivSize(1)+KID(16)；size 声明按 muxer 实测少 1。 */
    private fun tencOld(ivSize: Int): ByteArray {
        val body = u32(0) + byteArrayOf(0, 0, 0) + byteArrayOf(1, ivSize.toByte()) + ByteArray(16) { (it * 3).toByte() }
        return u32(8L + body.size - 1) + "tenc".toByteArray(Charsets.US_ASCII) + body
    }

    /** 标准 tenc（ISO/IEC 23001-7）：ver/flags(4)+reserved(2)+isProtected(1)+ivSize(1)+KID(16)；模拟器实测亦有此布局。 */
    private fun tencStd(ivSize: Int): ByteArray {
        val body = u32(0) + byteArrayOf(0, 0) + byteArrayOf(1, ivSize.toByte()) + ByteArray(16) { (it * 3).toByte() }
        return u32(8L + body.size) + "tenc".toByteArray(Charsets.US_ASCII) + body
    }

    private fun stbl(vararg children: ByteArray) = box("stbl", *children)

    private fun trak(stbl: ByteArray, vararg extra: ByteArray) =
        box("trak", box("tkhd"), box("mdia", box("mdhd"), box("minf", stbl, *extra)))

    private fun moov(vararg traks: ByteArray) = box("moov", *traks)

    // ---------- 解析 ----------

    @Test
    fun `单 trak 样本表展开 偏移大小与 IV 对齐`() {
        // chunk1(偏移1000)=2 样本(10,20)，chunk2(偏移2000)=1 样本(30)
        val moov = moov(trak(stbl(
            stco(1000, 2000),
            stsz(10, 20, 30),
            stsc(Triple(1, 2, 1), Triple(2, 1, 1)),
            senc(iv8(1), iv8(2), iv8(3)),
            tencOld(8),
        )))
        val parsed = CencMoovTables.parse(moov)
        assertEquals(8, parsed.ivSize)
        assertEquals(3, parsed.samples.size)
        val s = parsed.samples
        assertEquals(1000L, s[0].offset) // stco 值即文件绝对偏移
        assertEquals(10, s[0].size)
        assertArrayEquals(iv8(1), s[0].iv)
        assertEquals(1010L, s[1].offset)
        assertEquals(20, s[1].size)
        assertArrayEquals(iv8(2), s[1].iv)
        assertEquals(2000L, s[2].offset)
        assertEquals(30, s[2].size)
        assertArrayEquals(iv8(3), s[2].iv)
    }

    @Test
    fun `双 trak 合并按偏移升序`() {
        val moov = moov(
            trak(stbl(stco(1000), stsz(10), stsc(Triple(1, 1, 1)), senc(iv8(1)), tencOld(8))),
            trak(stbl(stco(500), stsz(6), stsc(Triple(1, 1, 1)), senc(iv8(9)), tencOld(8))),
        )
        val parsed = CencMoovTables.parse(moov)
        assertEquals(listOf(500L, 1000L), parsed.samples.map { it.offset })
        assertEquals(listOf(6, 10), parsed.samples.map { it.size })
    }

    @Test
    fun `stsz 固定尺寸分支`() {
        // sampleSize=16、count=1：条目区为空，尺寸来自固定值
        val moov = moov(trak(stbl(
            stco(800),
            stsz(16),
            stsc(Triple(1, 1, 1)),
            senc(iv8(4)),
            tencOld(8),
        )))
        val parsed = CencMoovTables.parse(moov)
        assertEquals(1, parsed.samples.size)
        assertEquals(16, parsed.samples[0].size)
    }

    @Test
    fun `tenc 坏 size 不影响 sinf 后续解析`() {
        // tenc 声明 size 少 1（实测形态），sinf/schi 内 tenc 后还有 btrt；
        // 盒树遍历只走 stbl 直接子层，tenc 走特征扫描——坏 size 必须无影响
        val sinf = box(
            "sinf",
            box("frma", "hvc1".toByteArray()),
            box("schm", u32(0) + "cenc".toByteArray(Charsets.US_ASCII) + u16(0x10000)),
            box("schi", tencOld(8), box("btrt", u32(0), u32(0), u32(0))),
        )
        val stsd = box("stsd", verFlags0, u32(1) + box("encv", ByteArray(70) + sinf))
        val moov = moov(trak(stbl(
            stsd,
            stco(1000),
            stsz(10),
            stsc(Triple(1, 1, 1)),
            senc(iv8(1)),
        )))
        val parsed = CencMoovTables.parse(moov)
        assertEquals(8, parsed.ivSize)
        assertEquals(1, parsed.samples.size)
    }

    @Test
    fun `tenc 标准布局 reserved 2 字节也能识别`() {
        // 模拟器实测（2026-11-07）：同 CDN 存在标准布局文件，旧版特征扫不到导致 ivSize=-1
        val moov = moov(trak(stbl(
            stco(1000),
            stsz(10),
            stsc(Triple(1, 1, 1)),
            senc(iv8(1)),
            tencStd(8),
        )))
        val parsed = CencMoovTables.parse(moov)
        assertEquals(8, parsed.ivSize)
        assertEquals(1, parsed.samples.size)
    }

    @Test
    fun `明文轨无 senc 无 tenc 跳过不进加密表`() {
        // CENC 标准形态（加密视频+明文音频）：明文轨缺 senc 应跳过，其字节按间隙透传
        val plainAudioTrak = trak(stbl(stco(500), stsz(6), stsc(Triple(1, 1, 1)))) // 无 senc/tenc
        val encryptedTrak = trak(stbl(
            stco(1000), stsz(10), stsc(Triple(1, 1, 1)),
            senc(iv8(1)), tencOld(8),
        ))
        val parsed = CencMoovTables.parse(moov(plainAudioTrak, encryptedTrak))
        assertEquals(listOf(1000L), parsed.samples.map { it.offset }) // 明文轨样本不进表
        assertEquals(8, parsed.ivSize)
    }

    // ---------- 失败路径 ----------

    @Test
    fun `senc 带子样本表抛 FormatException`() {
        // flags=0x2：每样本 IV 后还有子样本表——未支持，明确失败
        val body = u32(0x2) + u32(1) + iv8(1) + u16(1) + u16(4) + u32(100)
        val moov = moov(trak(stbl(
            stco(1000), stsz(10), stsc(Triple(1, 1, 1)),
            box("senc", body), tencOld(8),
        )))
        try {
            CencMoovTables.parse(moov)
            fail("应抛 FormatException")
        } catch (e: CencMoovTables.FormatException) {
            assertTrue(e.message!!.contains("子样本"))
        }
    }

    @Test
    fun `senc 与 stsz 样本数不一致抛 FormatException`() {
        val moov = moov(trak(stbl(
            stco(1000), stsz(10, 20), stsc(Triple(1, 2, 1)),
            senc(iv8(1)), tencOld(8), // 1 个 IV vs 2 个样本
        )))
        try {
            CencMoovTables.parse(moov)
            fail("应抛 FormatException")
        } catch (e: CencMoovTables.FormatException) {
            assertTrue(e.message!!.contains("样本数"))
        }
    }

    @Test
    fun `缺 senc 抛 FormatException`() {
        val moov = moov(trak(stbl(stco(1000), stsz(10), stsc(Triple(1, 1, 1)), tencOld(8))))
        try {
            CencMoovTables.parse(moov)
            fail("应抛 FormatException")
        } catch (e: CencMoovTables.FormatException) {
            assertTrue(e.message!!.contains("senc"))
        }
    }
}
