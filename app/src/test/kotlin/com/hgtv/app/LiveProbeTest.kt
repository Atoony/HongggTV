package com.hgtv.app

import com.hgtv.app.net.JVal
import com.hgtv.app.net.HongguoApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 线上探针（票 02 第一里程碑）：设置 HGTV_LIVE_PROBE=1 启用。
 * 覆盖 SPEC：详情通 / 取流通 / Ladon 消融矩阵 / device_id 随机性。
 */
class LiveProbeTest {

    companion object {
        // wayfinder 票 04 已验证的样本剧集
        const val SERIES_ID = "7688352674999176254"
        const val VIDEO_ID = "7688396347107445822"
    }

    private fun live(): Boolean = System.getenv("HGTV_LIVE_PROBE") == "1"

    @Test
    fun `详情探针 - 全套签名头返回有效剧集数据`() {
        assumeTrue(live())
        val api = HongguoApi()
        val detail = api.fetchDetail(SERIES_ID)
        assertTrue(detail.episodes.isNotEmpty())
        assertEquals(SERIES_ID, detail.seriesId)
        detail.episodes.forEachIndexed { i, ep -> assertEquals(i + 1, ep.index) }
        assertTrue(detail.title.isNotEmpty())
        println("详情 OK：${detail.title} 共${detail.episodeCnt}集 ${detail.remark} 分类=${detail.categories}")
    }

    @Test
    fun `取流探针 - video_list 非空且含高清档`() {
        assumeTrue(live())
        val api = HongguoApi()
        val streams = api.fetchStreams(VIDEO_ID)
        assertTrue(streams.isNotEmpty())
        val best = streams.first()
        assertTrue(best.quality >= 720)
        assertTrue(best.urls.isNotEmpty())
        assertTrue(best.urls.all { it.startsWith("http") })
        val bytevc2 = streams.count { it.codec.contains("bytevc2") }
        assertEquals(0, bytevc2)
        println("取流 OK：${streams.joinToString { "${it.name}/${it.quality}p/${it.urls.size}路" + (if (it.cencKey != null) "/CENC" else "") }}")
    }

    @Test
    fun `消融矩阵 - 去掉 Ladon Argus 复现 200 加 0 字节`() {
        assumeTrue(live())
        val api = HongguoApi()
        val body = JVal.obj(
            "video_id" to JVal.str(VIDEO_ID),
            "content_type" to JVal.num(1),
            "biz_param" to JVal.obj(
                "need_all_video_definition" to JVal.bool(true),
                "video_platform" to JVal.num(3),
            ),
        )
        val (status, _, content) = api.request("/novel/player/video_model/v1/", body, skipLadonArgus = true)
        assertEquals(200, status)
        assertTrue("去掉 X-Ladon/X-Argus 应被 200+0 静默拒绝，实际 ${content.size} 字节", content.isEmpty())
        println("消融 OK：HTTP $status ${content.size} 字节（风控头确实生效）")
    }

    @Test
    fun `设备身份 - 每次随机生成且 19 位数字`() {
        val a = com.hgtv.app.net.HongguoIdentity.random()
        val b = com.hgtv.app.net.HongguoIdentity.random()
        assertTrue(a.deviceId != b.deviceId)
        assertTrue(a.installId != b.installId)
        assertTrue(a.deviceId.matches(Regex("^\\d{19}$")))
        assertTrue(a.installId.matches(Regex("^\\d{19}$")))
    }
}
