package com.hgtv.app.net

/**
 * 极简 JSON 值构造 + 序列化。手写以保证签名字节与发送字节完全一致，
 * 且 JVM 单元测试可用（Android stub 的 org.json 不可用）。
 */
sealed class JVal {
    data class JStr(val v: String) : JVal()
    data class JNum(val v: Long) : JVal()
    data class JBool(val v: Boolean) : JVal()
    data class JObj(val fields: List<Pair<String, JVal>>) : JVal()
    data class JArr(val items: List<JVal>) : JVal()

    fun serialize(): String = StringBuilder().also { write(this, it) }.toString()

    private fun write(v: JVal, sb: StringBuilder) {
        when (v) {
            is JStr -> writeString(v.v, sb)
            is JNum -> sb.append(v.v)
            is JBool -> sb.append(v.v)
            is JArr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(item, sb)
                }
                sb.append(']')
            }
            is JObj -> {
                sb.append('{')
                v.fields.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    companion object {
        fun obj(vararg fields: Pair<String, JVal>): JObj = JObj(fields.toList())
        fun arr(vararg items: JVal): JArr = JArr(items.toList())
        fun str(v: String): JStr = JStr(v)
        fun num(v: Long): JNum = JNum(v)
        fun bool(v: Boolean): JBool = JBool(v)
    }
}
