package com.hgtv.app

import com.hgtv.app.net.JsonParser
import com.hgtv.app.player.ClearKeyLicense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** W3C ClearKey 许可证构造（票 04：ClearKey DRM session 做样本级 CENC 解密）。 */
class ClearKeyLicenseTest {

    private val kid = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    private val key = ByteArray(16) { (it * 17 + 3).toByte() }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `CDM 请求 kids 转为 W3C ClearKey 响应`() {
        val request = """{"kids":["${b64url(kid)}"],"type":"temporary"}"""
        val resp = ClearKeyLicense.buildResponse(request, key)!!

        val root = JsonParser.parse(resp) as Map<String, Any?>
        assertEquals("temporary", root["type"])
        val keys = root["keys"] as List<*>
        assertEquals(1, keys.size)
        val entry = keys[0] as Map<String, Any?>
        assertEquals("oct", entry["kty"])
        assertEquals(b64url(kid), entry["kid"])
        assertEquals(b64url(key), entry["k"])
    }

    @Test
    fun `多个 kid 全部回应同一内容密钥`() {
        val kid2 = ByteArray(16) { (it + 100).toByte() }
        val request = """{"kids":["${b64url(kid)}","${b64url(kid2)}"]}"""
        val root = JsonParser.parse(ClearKeyLicense.buildResponse(request, key)!!) as Map<String, Any?>
        val keys = root["keys"] as List<*>
        assertEquals(2, keys.size)
        keys.forEach { assertEquals(b64url(key), (it as Map<String, Any?>)["k"]) }
    }

    @Test
    fun `请求缺 kids 或 kids 为空返回 null`() {
        assertNull(ClearKeyLicense.buildResponse("""{"type":"temporary"}""", key))
        assertNull(ClearKeyLicense.buildResponse("""{"kids":[]}""", key))
        assertNull(ClearKeyLicense.buildResponse("not json", key))
    }

    @Test
    fun `密钥仅内存传递 - b64url 编码无 padding 且 URL 安全`() {
        // 0xFB/0xFF 会产生标准 base64 的 +/，URL-safe 字母表必须是 -_
        val tricky = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val enc = ClearKeyLicense.b64url(tricky)
        assertTrue("含 padding", !enc.endsWith("="))
        assertTrue("非 URL 安全字母表", enc.none { it == '+' || it == '/' })
        assertTrue(tricky.contentEquals(Base64.getUrlDecoder().decode(enc)))
    }

    // 票 12：数据源层（openhongguo）内容密钥是 16 字节 AES 密钥的小写 hex 串

    @Test
    fun `hexToBytes - 32 位小写 hex 还原 16 字节内容密钥`() {
        val hex = "00112233445566778899aabbccddeeff"
        val bytes = ClearKeyLicense.hexToBytes(hex)!!
        assertEquals(16, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0xff, bytes[15].toInt() and 0xFF)
    }

    @Test
    fun `hexToBytes - 空串奇数长度或非 hex 字符返回 null`() {
        assertNull(ClearKeyLicense.hexToBytes(""))
        assertNull(ClearKeyLicense.hexToBytes("abc"))
        assertNull(ClearKeyLicense.hexToBytes("zz"))
        assertNull(ClearKeyLicense.hexToBytes("00gg"))
    }

    @Test
    fun `SDK 内容密钥全链 - hex 串到合法 ClearKey 响应`() {
        // SDK 形态（contentKey.key 小写 hex）→ buildResponse 消费 → W3C 响应里 k 配对成功
        val hex = "3f10a7c2d84b95e60133ad9f6c2b7e40"
        val keyBytes = ClearKeyLicense.hexToBytes(hex)!!
        val request = """{"kids":["${b64url(kid)}"],"type":"temporary"}"""
        val root = JsonParser.parse(ClearKeyLicense.buildResponse(request, keyBytes)!!) as Map<String, Any?>
        val entry = (root["keys"] as List<*>)[0] as Map<String, Any?>
        assertEquals(b64url(keyBytes), entry["k"])
        assertEquals(b64url(kid), entry["kid"])
        assertEquals("temporary", root["type"])
    }
}
