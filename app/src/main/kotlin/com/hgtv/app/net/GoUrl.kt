package com.hgtv.app.net

/**
 * Go url.Values.Encode() 等价实现：key 字典序排序 + Go QueryEscape 编码。
 * 签名(X-Gorgon)与实际发送的 rawQuery 必须逐字节一致，Java URLEncoder
 * 与 Go 行为不同（~ 编码、* 不编码），故手写。
 */
object GoUrl {

    fun queryEscape(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) {
                sb.append(c.toChar())
            } else if (c == ' '.code) {
                sb.append('+')
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0f])
            }
        }
        return sb.toString()
    }

    /** 等价 Go url.Values.Encode()：按 key 排序（单值 key），k=v&k=v */
    fun encode(params: Map<String, String>): String {
        return params.entries.sortedWith(compareBy { it.key }).joinToString("&") { (k, v) ->
            queryEscape(k) + "=" + queryEscape(v)
        }
    }
}
