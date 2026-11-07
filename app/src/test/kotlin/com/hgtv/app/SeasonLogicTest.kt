package com.hgtv.app

import com.hgtv.app.domain.chineseSeason
import com.hgtv.app.domain.seasonOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** 季号解析特征固化（票06 起；票14 迁 domain 垫层）。
 *  票15：旧链 HongguoWeb 的缺季补全/同系列排序用例随逆向链退役（服务端 relevance 序替代）。 */
class SeasonLogicTest {

    @Test
    fun `标题季号解析 - 中文数字与阿拉伯数字与部`() {
        seasonOf("战神第二季").let { (b, s, u, l) ->
            assertEquals("战神", b); assertEquals(2, s); assertEquals("季", u); assertEquals("二", l)
        }
        assertEquals(3, seasonOf("重生第3季").season)
        assertEquals(1, seasonOf("先婚后爱第一部").season)
        assertEquals(0, seasonOf("无双神龙").season)
    }

    @Test
    fun `季号范围 1-200 之外视为无季`() {
        assertEquals(200, seasonOf("某剧第二百季").season)
        assertEquals(0, seasonOf("某剧第二百零一季").season)
        assertEquals(0, seasonOf("某剧第0季").season)
    }

    @Test
    fun `chineseSeason 数字转中文`() {
        assertEquals("二", chineseSeason(2))
        assertEquals("十", chineseSeason(10))
        assertEquals("二十一", chineseSeason(21))
        assertEquals("一百零五", chineseSeason(105))
    }
}
