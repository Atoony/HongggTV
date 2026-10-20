package com.hgtv.app.net

/**
 * 无依赖 JSON 解析器（JVM 单元测试与 Android 通用；大整数 ID 保留为字符串避免精度丢失）。
 * 返回值类型：Map<String, Any?> / List<Any?> / String / Long / Double / Boolean / null。
 */
object JsonParser {

    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) throw IllegalArgumentException("JSON 尾部多余字符 @${p.pos}")
        return v
    }

    /** 只解析首个 JSON 值并忽略尾部（等价 Go json.Decoder.Decode，用于 HTML 内嵌 JSON）。 */
    fun parsePrefix(text: String): Any? {
        val p = P(text)
        p.skipWs()
        return p.value()
    }

    private class P(val s: String) {
        var pos = 0
        fun eof() = pos >= s.length
        fun skipWs() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun expect(c: Char) {
            if (eof() || s[pos] != c) throw IllegalArgumentException("期望 '$c' @$pos")
            pos++
        }

        fun value(): Any? {
            if (eof()) throw IllegalArgumentException("意外结束 @$pos")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (s.length - pos < w.length || s.substring(pos, pos + w.length) != w)
                throw IllegalArgumentException("非法字面量 @$pos")
            pos += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (!eof() && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                if (eof()) throw IllegalArgumentException("对象未闭合 @$pos")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw IllegalArgumentException("期望 , 或 } @$pos")
                }
            }
        }

        fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipWs()
            if (!eof() && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (eof()) throw IllegalArgumentException("数组未闭合 @$pos")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw IllegalArgumentException("期望 , 或 ] @$pos")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw IllegalArgumentException("字符串未闭合")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw IllegalArgumentException("转义未闭合")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("\\u 越界")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = pos
            if (!eof() && (s[pos] == '-' || s[pos] == '+')) pos++
            var isDouble = false
            while (!eof()) {
                val c = s[pos]
                if (c.isDigit()) pos++
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (c == '.' || c == 'e' || c == 'E') isDouble = true
                    pos++
                } else break
            }
            val text = s.substring(start, pos)
            if (text.isEmpty()) throw IllegalArgumentException("空数字 @$start")
            if (!isDouble) {
                text.toLongOrNull()?.let { return it }
                return text // 超长整数保留原文本
            }
            return text.toDouble()
        }
    }

    // ---- 取值工具 ----

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String = when (v) {
        is String -> v
        is Long -> v.toString()
        is Double -> v.toString()
        is Boolean -> v.toString()
        else -> ""
    }

    fun str(m: Map<String, Any?>?, vararg keys: String): String {
        if (m == null) return ""
        for (k in keys) {
            val v = m[k] ?: continue
            val s = str(v)
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    fun num(v: Any?): Long = when (v) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    fun nested(v: Any?, vararg keys: String): Map<String, Any?>? {
        var cur = obj(v) ?: return null
        for (k in keys) cur = obj(cur[k]) ?: return null
        return cur
    }
}
