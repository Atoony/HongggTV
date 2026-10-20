package com.hgtv.app

import com.hgtv.app.net.GoUrl
import com.hgtv.app.net.HongguoSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 签名链纯逻辑自检（离线）。线上端到端见 LiveProbeTest。 */
class HongguoSignTest {

    @Test
    fun `Go 风格 query 编码`() {
        assertEquals("a%2Ab", GoUrl.queryEscape("a*b"))
        assertEquals("a+b", GoUrl.queryEscape("a b"))
        assertEquals("a~b", GoUrl.queryEscape("a~b"))
        assertEquals("a-b_c.d", GoUrl.queryEscape("a-b_c.d"))
        assertEquals(
            "aid=8662&resolution=1280%2A2772&zz=%E4%B8%AD",
            GoUrl.encode(mapOf("zz" to "中", "aid" to "8662", "resolution" to "1280*2772")),
        )
    }

    @Test
    fun `Gorgon 结构`() {
        val nowMs = 1697000000123L
        val body = """{"series_id":"7688352674999176254"}""".toByteArray()
        val rawQuery = "aid=8662&ts=1697000000"
        val headers = HongguoSign.signHeaders(rawQuery, body, nowMs)
        val gorgon = headers.getValue("X-Gorgon")
        assertEquals("8404401c0000", gorgon.substring(0, 12))
        assertEquals(52, gorgon.length)
        assertTrue(gorgon.drop(12).all { it in "0123456789abcdef" })
        assertEquals("1697000000", headers.getValue("X-Khronos"))
        assertEquals("1697000000123", headers.getValue("X-SS-Req-Ticket"))
        assertEquals(32, headers.getValue("X-SS-STUB").length)
        assertEquals(HongguoSign.md5(body).joinToString("") { "%02x".format(it) }.uppercase(), headers.getValue("X-SS-STUB"))
    }

    @Test
    fun `无 body 时无 STUB`() {
        val headers = HongguoSign.signHeaders("aid=8662", null, 1697000000123L)
        assertTrue(!headers.containsKey("X-SS-STUB"))
        assertEquals(52, headers.getValue("X-Gorgon").length)
    }

    @Test
    fun `Gorgon 对 ts 敏感`() {
        val body = "{}".toByteArray()
        val a = HongguoSign.signHeaders("aid=1", body, 1697000000123L).getValue("X-Gorgon")
        val b = HongguoSign.signHeaders("aid=1", body, 1697000001123L).getValue("X-Gorgon")
        assertTrue(a != b)
    }

    @Test
    fun `Ladon Argus 端序`() {
        // BE32(0x6521E100) = 65 21 E1 00 → base64 "ZSHhAA=="
        assertEquals("ZSHhAA==", HongguoSign.ladon(0x6521E100L))
        // LE32 = 00 E1 21 65 → "AOEhZQ=="
        assertEquals("AOEhZQ==", HongguoSign.argus(0x6521E100L))
    }
}
