package com.hgtv.app

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hgtv.app.player.CencCtr
import com.hgtv.app.player.CencDecryptDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * CencDecryptDataSource 端到端（内存 base）：合成加密文件（真实布局夹具 moov + CTR 加密样本）
 * → 解密读出应与原始明文逐字节一致；seek（open 落样本中间）正确；spec.length 契约遵守。
 */
class CencDecryptDataSourceTest {

    // ---------- 夹具 ----------

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return u32(8L + body.size) + type.toByteArray(Charsets.US_ASCII) + body
    }

    private val key = ByteArray(16) { (it + 1).toByte() }
    private fun iv8(seed: Int) = ByteArray(8) { ((seed * 7 + it) and 0xff).toByte() }

    /** 合成文件：ftyp + moov(样本表) + 按偏移排布的加密样本（间隙填透传字节）。 */
    private class Fixture(val bytes: ByteArray, val plain: ByteArray)

    private fun buildFile(sampleSpecs: List<Triple<Long, Int, ByteArray>>): Fixture {
        val totalEnd = sampleSpecs.maxOf { it.first + it.second }
        // moov 放样本前：ftyp(16) + moov（固定构造，样本表参数化）
        // 期望明文 = 头区/间隙透传字节（与密文同位同值）+ 样本区明文
        val plainAll = ByteArray(totalEnd.toInt()) { (it * 13 + 5).toByte() }
        val cipherAll = ByteArray(totalEnd.toInt()) { (it * 13 + 5).toByte() }
        sampleSpecs.forEachIndexed { i, (off, size, iv) ->
            val samplePlain = ByteArray(size) { ((i + 1) * 31 + it).toByte() }
            System.arraycopy(samplePlain, 0, plainAll, off.toInt(), size)
            val enc = samplePlain.copyOf()
            CencCtr.crypt(key, iv, enc, 0, size) // CTR 对称：加密明文得密文
            System.arraycopy(enc, 0, cipherAll, off.toInt(), size)
        }
        val verFlags0 = u32(0)
        val stco = box("stco", verFlags0, u32(sampleSpecs.size.toLong()), *sampleSpecs.map { u32(it.first) }.toTypedArray())
        val stsz = box(
            "stsz", verFlags0,
            u32(0) + u32(sampleSpecs.size.toLong()) + sampleSpecs.map { u32(it.second.toLong()) }.reduce { a, b -> a + b },
        )
        // 单 run：每 chunk 1 样本
        val stsc = box("stsc", verFlags0, u32(1) + u32(1) + u32(1) + u32(1))
        val senc = box("senc", verFlags0, u32(sampleSpecs.size.toLong()) + sampleSpecs.map { it.third }.reduce { a, b -> a + b })
        val tenc = u32(8 + 25 - 1) + "tenc".toByteArray() + u32(0) + byteArrayOf(0, 0, 0) + byteArrayOf(1, 8) + ByteArray(16)
        val moov = box(
            "moov",
            box("trak", box("mdia", box("minf", box("stbl", stco, stsz, stsc, senc, tenc)))),
        )
        val ftyp = box("ftyp", "isom".toByteArray())
        // 头区 = ftyp + moov，长度对齐到首个样本偏移（夹具里偏移从 moov 尾后起）
        val headLen = ftyp.size + moov.size
        val bytes = cipherAll // 偏移坐标系：ftyp+moov 已含在内（见下）
        // 把头写进坐标系前部：夹具样本偏移从 headLen 起，正好；期望明文的头区=原始字节（透传）
        System.arraycopy(ftyp, 0, bytes, 0, ftyp.size)
        System.arraycopy(moov, 0, bytes, ftyp.size, moov.size)
        System.arraycopy(ftyp, 0, plainAll, 0, ftyp.size)
        System.arraycopy(moov, 0, plainAll, ftyp.size, moov.size)
        return Fixture(bytes, plainAll)
    }

    /** 内存 base：只支持顺序读一个 open 会话，记录每次 open 的区间（断言网络请求形状）。
     * [truncateAt] 模拟服务端提前断流：文件视图只到该长度。 */
    private open class MemDataSource(
        private val file: ByteArray,
        private val truncateAt: Int = Int.MAX_VALUE,
    ) : DataSource {
        val opens = mutableListOf<String>()
        private var p = 0L
        private var end = 0L
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            opens += "[${dataSpec.position}, ${if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1 else dataSpec.position + dataSpec.length})"
            p = dataSpec.position
            end = minOf(
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) p + dataSpec.length else file.size.toLong(),
                minOf(file.size.toLong(), truncateAt.toLong()),
            )
            return end - p
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (p >= end || p >= file.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - p, file.size - p).toInt()
            System.arraycopy(file, p.toInt(), buffer, offset, n)
            p += n
            return n
        }

        override fun close() {}
        override fun getUri() = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    }

    // mockable jar 的 Uri.parse 返 null；工厂缓存按 uri 相等匹配，共享同一 mock 实例（真实 Uri 按值相等）
    private val specUri: android.net.Uri = org.mockito.Mockito.mock(android.net.Uri::class.java)

    private fun readAll(ds: DataSource, position: Long, length: Long): ByteArray {
        ds.open(
            DataSpec.Builder()
                .setUri(specUri)
                .setPosition(position)
                .setLength(length)
                .build(),
        )
        var out = ByteArray(64 * 1024)
        var filled = 0
        val buf = ByteArray(4096)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n <= 0) break
            if (filled + n > out.size) out = out.copyOf((filled + n) * 2)
            System.arraycopy(buf, 0, out, filled, n)
            filled += n
        }
        ds.close()
        return out.copyOf(filled)
    }

    // ---------- 用例 ----------

    @Test
    fun `CTR 对称性往返`() {
        val plain = ByteArray(100) { (it * 3).toByte() }
        val iv = iv8(1)
        val enc = plain.copyOf()
        CencCtr.crypt(key, iv, enc, 0, enc.size)
        assertTrue(!enc.contentEquals(plain))
        CencCtr.crypt(key, iv, enc, 0, enc.size)
        assertArrayEquals(plain, enc)
    }

    @Test
    fun `全量顺序读出明文`() {
        // 偏移含间隙：s1(1000,10) s2(1010,20) s3(2000,30)——s2..s3 间 970B 透传
        val f = buildFile(
            listOf(
                Triple(1000L, 10, iv8(1)),
                Triple(1010L, 20, iv8(2)),
                Triple(2000L, 30, iv8(3)),
            ),
        )
        val ds = CencDecryptDataSource(MemDataSource(f.bytes), key)
        val out = readAll(ds, 0, C.LENGTH_UNSET.toLong())
        assertEquals(f.bytes.size.toLong(), out.size.toLong())
        assertArrayEquals(f.plain, out)
    }

    @Test
    fun `seek 落样本中间从头重解仍正确`() {
        val f = buildFile(listOf(Triple(1000L, 1000, iv8(5))))
        val ds = CencDecryptDataSource(MemDataSource(f.bytes), key)
        // 1050 = 样本(1000..2000)中间
        val out = readAll(ds, 1050, C.LENGTH_UNSET.toLong())
        assertArrayEquals(f.plain.copyOfRange(1050, 2000), out)
    }

    @Test
    fun `spec length 契约 读量不超限`() {
        val f = buildFile(listOf(Triple(1000L, 1000, iv8(9))))
        val ds = CencDecryptDataSource(MemDataSource(f.bytes), key)
        val out = readAll(ds, 900, 100) // 跨头区尾 100B（头=ftyp+moov≈1000 内）
        assertEquals(100, out.size)
        assertArrayEquals(f.plain.copyOfRange(900, 1000), out)
    }

    @Test
    fun `多窗口文件全量读出 网络请求为有界区间`() {
        // 3 个 ~300KB 样本 → 900KB 内容，窗口 512KB → 至少 2 次样本区请求
        val specs = (0 until 3).map { Triple(1000L + it * 300_000L, 300_000, iv8(it + 1)) }
        val f = buildFile(specs)
        val mem = MemDataSource(f.bytes)
        val ds = CencDecryptDataSource(mem, key)
        val out = readAll(ds, 0, C.LENGTH_UNSET.toLong())
        assertArrayEquals(f.plain, out)
        // 首请求=头区探针（1MB 上限，内存源给到文件尾），后续窗口请求均为有界
        assertTrue(mem.opens.size >= 2)
        mem.opens.drop(1).forEach {
            val len = it.substringAfter(", ").dropLast(1).toLong() - it.substringAfter("[").substringBefore(",").toLong()
            assertTrue("窗口请求应有界: $it", len in 1..1_048_576)
        }
    }

    @Test
    fun `头区探针无 moov 抛 IOException`() {
        val mem = MemDataSource(ByteArray(64) { 7 })
        val ds = CencDecryptDataSource(mem, key)
        try {
            readAll(ds, 0, C.LENGTH_UNSET.toLong())
            throw AssertionError("应抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("moov"))
        }
    }

    @Test
    fun `提前断流时窗口内完整样本仍解密`() {
        // s1(1000,300) 完整 + s2(1300,500)：视图截到 1500 → s2 残缺
        // 期望：s1 区明文；s2 前 200B 保持密文（文件原字节）；1500 处 EOF
        val f = buildFile(listOf(Triple(1000L, 300, iv8(3)), Triple(1300L, 500, iv8(4))))
        val ds = CencDecryptDataSource(MemDataSource(f.bytes, truncateAt = 1500), key)
        val out = readAll(ds, 0, C.LENGTH_UNSET.toLong())
        assertEquals(1500, out.size)
        assertArrayEquals(f.plain.copyOfRange(1000, 1300), out.copyOfRange(1000, 1300)) // s1 解密
        assertArrayEquals(f.bytes.copyOfRange(1300, 1500), out.copyOfRange(1300, 1500)) // s2 密文
    }

    @Test
    fun `末样本之后尾部字节透传可读`() {
        // 文件末尾追加 200B（mdat 填充/free 形态）：样本表外字节应透传读出而非 EOF 丢弃
        val f = buildFile(listOf(Triple(1000L, 1000, iv8(6))))
        val withTail = f.bytes + ByteArray(200) { (it + 9).toByte() }
        val ds = CencDecryptDataSource(MemDataSource(withTail), key)
        val out = readAll(ds, 0, C.LENGTH_UNSET.toLong())
        assertEquals(2200, out.size)
        assertArrayEquals(f.plain, out.copyOf(2000))
        assertArrayEquals(withTail.copyOfRange(2000, 2200), out.copyOfRange(2000, 2200))
    }

    @Test
    fun `工厂缓存命中免重拉探针`() {
        // 同 uri 第二个 DataSource（重连/重备形态）：不再发 position=0 的探针请求
        val f = buildFile(listOf(Triple(1000L, 1000, iv8(7))))
        val created = mutableListOf<MemDataSource>()
        val factory = androidx.media3.datasource.DataSource.Factory { MemDataSource(f.bytes).also { created += it } }
        val decFactory = com.hgtv.app.player.CencDecryptDataSourceFactory(factory, key)
        assertArrayEquals(f.plain, readAll(decFactory.createDataSource(), 0, C.LENGTH_UNSET.toLong()))
        assertArrayEquals(f.plain, readAll(decFactory.createDataSource(), 0, C.LENGTH_UNSET.toLong()))
        assertEquals(2, created.size)
        assertTrue("第二实例不应有探针请求: ${created[1].opens}", created[1].opens.none { it.startsWith("[0, ") })
    }
}
