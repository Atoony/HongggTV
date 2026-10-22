package com.hgtv.app

import com.hgtv.app.net.HongguoApi
import com.hgtv.app.net.HongguoDevice
import com.hgtv.app.net.HongguoIdentity
import com.hgtv.app.player.StreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** 票09 风控加固：九项中的可离线验证部分。 */
class RiskControlTest {

    // ---------- 第 7 项：19 位正数 + 持久化 ----------

    @Test
    fun `deviceId 恒为 19 位正数且在 1e18 到 9e18 区间`() {
        repeat(200) {
            val id = HongguoDevice.newDeviceId().toLong()
            assertTrue(id >= 1_000_000_000_000_000_000L)
            assertTrue(id < 9_000_000_000_000_000_000L)
        }
    }

    @Test
    fun `identity 编解码往返`() {
        val identity = HongguoIdentity("1234567890123456789", "8999999999999999999")
        assertEquals(identity, HongguoDevice.decode(HongguoDevice.encode(identity)))
    }

    @Test
    fun `坏数据解码返回 null`() {
        assertNull(HongguoDevice.decode("123\n456"))                       // 位数不足
        assertNull(HongguoDevice.decode("9000000000000000000\n1234567890123456789")) // 越上界
        assertNull(HongguoDevice.decode("-1234567890123456789\n1234567890123456789")) // 负数
        assertNull(HongguoDevice.decode(""))                               // 空串
    }

    @Test
    fun `loadOrNew 首次生成并落盘 二次读取同一身份`() {
        val dir = Files.createTempDirectory("hg-identity").toFile()
        val first = HongguoDevice.loadOrNew(dir)
        // 落盘线程异步写，等文件出现
        val file = File(dir, "hg_identity.txt")
        var waited = 0
        while (!file.isFile && waited < 3000) {
            Thread.sleep(50); waited += 50
        }
        assertTrue("identity 文件应已落盘", file.isFile)
        val second = HongguoDevice.loadOrNew(dir)
        assertEquals(first, second)
    }

    @Test
    fun `坏身份文件触发重新生成`() {
        val dir = Files.createTempDirectory("hg-identity-bad").toFile()
        val file = File(dir, "hg_identity.txt")
        file.writeText("garbage")
        val identity = HongguoDevice.loadOrNew(dir)
        assertNotEquals("garbage", identity.deviceId)
        assertTrue(HongguoDevice.decode(HongguoDevice.encode(identity)) == identity) // 重新生成的也是合法 19 位
    }

    // ---------- 第 4 项：CF 挑战页检测 ----------

    @Test
    fun `识别 CF 挑战页特征`() {
        assertTrue(HongguoApi.isCfChallenge("<html><head><title>Just a moment...</title>".toByteArray()))
        assertTrue(HongguoApi.isCfChallenge("...window._cf_chl_opt={...}...".toByteArray()))
        assertTrue(HongguoApi.isCfChallenge("...cf-chl-bypass...".toByteArray()))
        assertFalse(HongguoApi.isCfChallenge("{\"code\":0,\"data\":{}}".toByteArray()))
        assertFalse(HongguoApi.isCfChallenge(ByteArray(0)))
    }

    // ---------- 第 6 项：响应体 20MB 流式上限 ----------

    @Test
    fun `readBounded 小于上限全量读出`() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val out = HongguoApi.readBounded(ByteArrayInputStream(payload), 2048)
        assertTrue(out.contentEquals(payload))
    }

    @Test
    fun `readBounded 超上限中止`() {
        val bomb = ByteArray(64 * 1024 * 1024) // 假 Content-Length 内存炸弹
        try {
            HongguoApi.readBounded(ByteArrayInputStream(bomb), 20 * 1024 * 1024)
            throw AssertionError("应在上限处中止")
        } catch (e: HongguoApi.ApiException) {
            assertTrue(e.message!!.contains("20MB"))
        }
    }

    // ---------- 第 5 项：重试分类矩阵 ----------

    @Test
    fun `仅 408 429 与 5xx 可重试`() {
        assertTrue(StreamRepository.retriable(HongguoApi.ApiException("e", 408)))
        assertTrue(StreamRepository.retriable(HongguoApi.ApiException("e", 429)))
        assertTrue(StreamRepository.retriable(HongguoApi.ApiException("e", 500)))
        assertTrue(StreamRepository.retriable(HongguoApi.ApiException("e", 503)))
        assertFalse(StreamRepository.retriable(HongguoApi.ApiException("e", 302)))   // 重定向
        assertFalse(StreamRepository.retriable(HongguoApi.ApiException("e", 200)))   // 空响应
        assertFalse(StreamRepository.retriable(HongguoApi.ApiException("e", 403)))
        assertFalse(StreamRepository.retriable(HongguoApi.ApiException("取流响应非 JSON"))) // 解析失败
        assertFalse(StreamRepository.retriable(HongguoApi.ChallengeException("CF", 403)))
        assertTrue(StreamRepository.retriable(java.io.IOException("connect timed out"))) // 网络层
    }

    // ---------- 第 9 项：回显防呆 ----------

    @Test
    fun `服务回显 base64 请求作为 url 判失败`() {
        // main_url = 请求参数的 base64 回显（非媒体地址）→ 该线路判失败丢弃
        val echoed = java.util.Base64.getEncoder().encodeToString(
            "{\"video_id\":\"1234567890123\"}".toByteArray()
        )
        val api = HongguoApi(identity = HongguoIdentity("1234567890123456789", "1234567890123456789"))
        assertTrue(api.mediaAddresses(mapOf("main_url" to echoed)).isEmpty())
        // 正常线路不受影响：明文 http 直通
        assertEquals(
            listOf("https://cdn.example.com/v.mp4"),
            api.mediaAddresses(mapOf("main_url" to "https://cdn.example.com/v.mp4")),
        )
    }
}
