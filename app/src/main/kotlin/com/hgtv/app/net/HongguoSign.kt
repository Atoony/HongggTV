package com.hgtv.app.net

import java.security.MessageDigest

/**
 * 红果 App 请求签名链，翻译自 guoapp Go 版 signHongguoRequest
 * (provider_hongguo_sign.go) + wayfinder 票 04 验证的 Ladon/Argus 修复。
 *
 * 头清单：
 *  - X-Khronos      = ts（秒，十进制）
 *  - X-Gorgon       = 8404401c0000 + 变换后 20 字节 payload 的 hex
 *  - X-SS-STUB      = md5(body) 大写 hex（有 body 时）
 *  - X-SS-Req-Ticket= 毫秒时间戳
 *  - X-Ladon        = base64(BE32(ts))
 *  - X-Argus        = base64(LE32(ts))
 */
object HongguoSign {

    private val GORGON_KEY = byteArrayOf(
        0x44, 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(), 0xa4.toByte(), 0xae.toByte(),
        0xf9.toByte(), 0xfc.toByte(), 0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75.toByte(),
        0x7c, 0xa3.toByte(), 0xc2.toByte(), 0xc4.toByte(), 0xa4.toByte(), 0x96.toByte(),
        0x93.toByte(), 0x8f.toByte(),
    )

    fun md5(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private fun rotateLeft8(v: Int, n: Int): Int {
        val x = v and 0xff
        return ((x shl n) or (x ushr (8 - n))) and 0xff
    }

    private fun reverse8(v: Int): Int {
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or ((v shr i) and 1)
        }
        return r
    }

    fun be32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        (v and 0xff).toByte(),
    )

    fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 24) and 0xff).toByte(),
    )

    /** 生成的头会写到请求上；rawQuery 必须是编码后的最终 query 字符串 */
    fun signHeaders(rawQuery: String, body: ByteArray?, nowMs: Long): Map<String, String> {
        val ts = nowMs / 1000
        val headers = LinkedHashMap<String, String>()

        val payload = ByteArray(20)
        val queryHash = md5(rawQuery.toByteArray(Charsets.UTF_8))
        System.arraycopy(queryHash, 0, payload, 0, 4)
        if (body != null) {
            val bodyHash = md5(body)
            System.arraycopy(bodyHash, 0, payload, 4, 4)
            headers["X-SS-STUB"] = hex(bodyHash).uppercase()
        }
        payload[12] = 0
        payload[13] = 6
        payload[14] = 11
        payload[15] = 28
        System.arraycopy(be32(ts), 0, payload, 16, 4)

        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor GORGON_KEY[i].toInt()).toByte()
        }
        for (i in payload.indices) {
            val mixed = rotateLeft8(payload[i].toInt(), 4) xor (payload[(i + 1) % payload.size].toInt() and 0xff)
            payload[i] = ((reverse8(mixed and 0xff) xor 0xff xor payload.size).toInt()).toByte()
        }

        val signature = ByteArray(6 + payload.size)
        signature[0] = 0x84.toByte()
        signature[1] = 0x04
        signature[2] = 0x40
        signature[3] = 0x1c
        signature[4] = 0
        signature[5] = 0
        System.arraycopy(payload, 0, signature, 6, payload.size)

        headers["X-Khronos"] = ts.toString()
        headers["X-Gorgon"] = hex(signature)
        headers["X-SS-Req-Ticket"] = nowMs.toString()
        return headers
    }

    fun ladon(tsSeconds: Long): String =
        java.util.Base64.getEncoder().encodeToString(be32(tsSeconds))

    fun argus(tsSeconds: Long): String =
        java.util.Base64.getEncoder().encodeToString(le32(tsSeconds))
}
