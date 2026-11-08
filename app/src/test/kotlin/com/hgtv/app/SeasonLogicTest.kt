package com.hgtv.app

import com.hgtv.app.domain.Season
import com.hgtv.app.domain.chineseSeason
import com.hgtv.app.domain.seasonOf
import com.hgtv.app.domain.seasonStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 季号解析特征固化（票06 起；票14 迁 domain 垫层）。
 *  票15：旧链 HongguoWeb 的缺季补全/同系列排序用例随逆向链退役（服务端 relevance 序替代）。 */
class SeasonLogicTest {

    @Test
    fun `季状态文案 - 未上线优先 完结次之 默认连载`() {
        // episodeCount=0 的季即使带完结状态也是未上线（SDK 夹具形态：即将上线的季 episode_cnt=0）
        assertEquals("未上线", seasonStatusLabel(Season("s", 3, 0, finished = true)))
        assertEquals("已完结", seasonStatusLabel(Season("s", 1, 154, finished = true)))
        assertEquals("连载中", seasonStatusLabel(Season("s", 2, 118, finished = false)))
        assertTrue(Season("s", 1, 154, finished = true).released)
        assertFalse(Season("s", 3, 0, finished = false).released)
    }


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

    @Test
    fun `chineseSeason 千位退化数据兜底阿拉伯数字`() {
        // mapAlbum 的季 index 来自 SDK 不设上限，千位中文转换越界会闪退（评审 finding）
        assertEquals("1234", chineseSeason(1234))
        assertEquals("零", chineseSeason(0)) // 个位仍在正常中文域
    }
}
