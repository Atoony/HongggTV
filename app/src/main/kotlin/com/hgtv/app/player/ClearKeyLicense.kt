package com.hgtv.app.player

import com.hgtv.app.net.JsonParser
import com.hgtv.app.net.JVal
import java.util.Base64

/**
 * W3C ClearKey 许可证构造（票 04：本地 DRM session 做样本级 CENC 解密）。
 *
 * Android ClearKey CDM 的 key request 是 JSON `{"kids":["<b64url>"],"type":...}`，
 * provideKeyResponse 需回 W3C ClearKey JSON：
 * `{"keys":[{"kty":"oct","kid":"<b64url>","k":"<b64url>"}],"type":"temporary"}`，
 * kid/k 均为 base64url 无 padding（EME 规范）。内容密钥（spade_a 解出）仅内存传递不落盘。
 */
object ClearKeyLicense {

    fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** 从 CDM 请求 JSON 提取 kids，构造与 [contentKey] 配对的 ClearKey 响应；无 kid 返回 null。 */
    fun buildResponse(requestJson: String, contentKey: ByteArray): String? {
        val kids = runCatching { JsonParser.parse(requestJson) as? Map<String, Any?> }.getOrNull()
            ?.let { JsonParser.arr(it["kids"]) }
            ?.filterIsInstance<String>()
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val entries = kids.map { kid ->
            JVal.obj(
                "kty" to JVal.JStr("oct"),
                "kid" to JVal.JStr(kid),
                "k" to JVal.JStr(b64url(contentKey)),
            )
        }
        return JVal.obj(
            "keys" to JVal.JArr(entries),
            "type" to JVal.JStr("temporary"),
        ).serialize()
    }
}
