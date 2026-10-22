package com.hgtv.app

import com.hgtv.app.net.HongguoWeb
import org.junit.Assert.assertEquals
import org.junit.Test

/** 票06（SPEC §6.1）：联想响应解析 —— word_type 白名单过滤 + 同名去重 + count 截断。 */
class SuggestionParseTest {

    @Test
    fun `白名单外的 word_type 被过滤`() {
        val json = """
            {"suggest_list":[
              {"name":"总裁重生","word_type":"short_play_name"},
              {"name":"某推广词","word_type":"ad_word"},
              {"name":"总裁夫人","word_type":"short_play_name"},
              {"name":"战神演员","word_type":"short_play_actor"},
              {"name":"普通词","word_type":""}
            ]}
        """.trimIndent()
        val out = HongguoWeb.parseSuggestions(json, 10)
        assertEquals(listOf("总裁重生", "总裁夫人", "战神演员"), out.map { it.name })
        assertEquals("short_play_name", out[0].type)
    }

    @Test
    fun `同名去重保留首个且按 count 截断`() {
        val json = """
            {"suggest_list":[
              {"name":"战神","word_type":"short_play_name"},
              {"name":"战神","word_type":"common_query"},
              {"name":"战神归来","word_type":"short_play_name"},
              {"name":"战神殿","word_type":"short_play_name"}
            ]}
        """.trimIndent()
        val out = HongguoWeb.parseSuggestions(json, 2)
        assertEquals(listOf("战神", "战神归来"), out.map { it.name })
    }

    @Test
    fun `data 包裹结构兼容`() {
        val json = """{"data":{"suggest_list":[{"name":"闪婚","word_type":"short_play_name"}]}}"""
        val out = HongguoWeb.parseSuggestions(json, 10)
        assertEquals(listOf("闪婚"), out.map { it.name })
    }

    @Test
    fun `空 name 或坏结构返回空`() {
        assertEquals(emptyList<String>(), HongguoWeb.parseSuggestions("""{"suggest_list":[]}""", 10).map { it.name })
        assertEquals(emptyList<String>(), HongguoWeb.parseSuggestions("""{"code":1}""", 10).map { it.name })
    }
}
