package com.hgtv.app.probe

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hgtv.app.player.ClearKeyLicense
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.OpenhongguoException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 票 12：openhongguo SDK 四连探针（换芯第一里程碑）。
 *
 * 线上纪律：instrumentation 参数 hgLive=1 才真正打线上（设备进程读不到 gradle 环境变量，
 * 走 -Pandroid.testInstrumentationRunnerArguments.hgLive=1）；且一次只跑一个方法
 * （-Pandroid.testInstrumentationRunnerArguments.class=...#方法名），不连发。
 * 日志走 logcat tag=OhgProbe，探针纪要落 .scratch/mvp/probe-openhongguo-2026-11-06.md。
 */
@RunWith(AndroidJUnit4::class)
class OpenhongguoProbeTest {

    private val tag = "OhgProbe"

    /** 一次用例内 resolvePlayback 的上限：克制连发，5 集足够覆盖加密占比采样。 */
    private val maxEpisodeProbes = 5

    private fun live(): Boolean = InstrumentationRegistry.getArguments().getString("hgLive") == "1"

    private fun client(context: Context): OpenhongguoClient =
        OpenhongguoClient.create(context, ClientOptions(logger = { Log.d(tag, it) }))

    private fun log(msg: String) {
        Log.i(tag, msg)
        println(msg) // gradle 控制台同步可见（test 输出流）
    }

    /** 每个环节的延迟（毫秒），纪要素材。 */
    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    /**
     * 四连探针：hotSearch 取热词 → search → listEpisodes → resolvePlayback（≤5 集）→ contentKey。
     * 验收：至少一集 protected=true 且内容密钥非空、可被 ClearKeyLicense.hexToBytes 还原成 16 字节。
     */
    @Test
    fun fourChain_searchToContentKey() {
        assumeTrue(live())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = client(context)

        val result = runBlocking {
            // ① hotSearch：热词来源（票 14 元数据切换时热词行的真实数据源）
            val t0 = System.nanoTime()
            val hot = c.hotSearch()
            log("① hotSearch ${elapsedMs(t0)}ms：猜你想搜 ${hot.guesses.size} 部，榜单 ${hot.boards.map { "${it.name}(${it.items.size}剧/${it.topics.size}词)" }}")
            val query = hot.boards.firstNotNullOfOrNull { b -> b.topics.firstOrNull()?.query }
                ?: hot.boards.firstOrNull()?.items?.firstOrNull()?.title
                ?: "总裁"
            log("热词取定：$query")

            // ② search：热词搜索短剧
            val t1 = System.nanoTime()
            val page = c.search(query)
            log("② search(\"$query\") ${elapsedMs(t1)}ms：${page.items.size} 部，hasMore=${page.hasMore}")
            assertTrue("搜索无结果", page.items.isNotEmpty())
            val series = page.items.first()

            // ③ listEpisodes：分集列表
            val t2 = System.nanoTime()
            val eps = c.listEpisodes(series.id)
            log("③ listEpisodes(${series.id}) ${elapsedMs(t2)}ms：《${series.title}》${eps.items.size} 集，hasMore=${eps.hasMore}")
            assertTrue("分集列表为空", eps.items.isNotEmpty())

            // ④ resolvePlayback：逐集解析（≤maxEpisodeProbes，只挑 playable 的）
            var protectedHit: Pair<String, String>? = null // episodeId to hexKey
            val errorCodes = mutableListOf<String>()
            val probed = eps.items.filter { it.playable }.take(maxEpisodeProbes)
            assertTrue("没有 playable 分集", probed.isNotEmpty())
            for ((i, ep) in probed.withIndex()) {
                if (i > 0) kotlinx.coroutines.delay(450) // 不连发：对齐票09 退避纪律，克制设备注册量
                val t3 = System.nanoTime()
                try {
                    val r = c.resolvePlayback(ep.id)
                    val key = r.contentKey
                    val streams = r.playback.streams.joinToString { s ->
                        "${s.definition}/${s.quality}/${s.codec}${if (s.protected) "/CENC" else ""}"
                    }
                    log(
                        "④ resolvePlayback(第${ep.number}集 ${ep.id}) ${elapsedMs(t3)}ms：protected=${r.playback.protected} " +
                            "key=${key?.key?.take(8)}…(v${key?.version}) keyErr=${r.contentKeyError?.code} [$streams]"
                    )
                    if (r.playback.protected && key != null && key.key.isNotEmpty() && protectedHit == null) {
                        protectedHit = ep.id to key.key
                    }
                } catch (e: OpenhongguoException) {
                    // 记录错误形态后继续采样（票12 验收要的是记录而非中断），全败才在收尾断言失败
                    errorCodes += "${e.code}(retryable=${e.retryable},retryAfterMs=${e.retryAfterMs})"
                    log("④ resolvePlayback(第${ep.number}集) ${elapsedMs(t3)}ms 抛错：code=${e.code} retryable=${e.retryable} retryAfterMs=${e.retryAfterMs} ${e.message}")
                }
            }
            if (errorCodes.isNotEmpty()) log("resolvePlayback 错误实录：$errorCodes")
            protectedHit to probed.size
        }

        val (hit, probedCount) = result
        log("加密覆盖：$probedCount 集中命中 ${if (hit == null) 0 else 1} 集加密分集")
        assertNotNull("≤$maxEpisodeProbes 集内未命中加密分集（protected+contentKey）", hit)
        val (episodeId, hexKey) = hit!!

        // key 消费：SDK hex 串 → ClearKeyLicense 还原 16 字节 AES 密钥
        val keyBytes = ClearKeyLicense.hexToBytes(hexKey)
        assertNotNull("contentKey 非法 hex：$hexKey", keyBytes)
        assertEquals(16, keyBytes!!.size)
        log("⑤ contentKey 消费 OK：episode=$episodeId key=16字节(${hexKey.length} 位 hex)")
    }

    /**
     * 设备档案探针：确认落 noBackupFilesDir/openhongguo/anonymous.json，
     * 两个客户端实例先后请求后 device_id_str 不变（复用同一匿名设备）。
     */
    @Test
    fun deviceProfile_persistAndReuse() {
        assumeTrue(live())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = File(context.noBackupFilesDir, "openhongguo/anonymous.json")

        val firstId = deviceIdAfterRequest(context, profile)
        val secondId = deviceIdAfterRequest(context, profile)
        log("设备档案：$profile device_id_str=$firstId → $secondId")
        assertEquals("二次启动未复用同一匿名设备", firstId, secondId)
    }

    /** 新建客户端实例发起一次请求，返回档案里的 device_id_str（触发懒注册后必然非空）。 */
    private fun deviceIdAfterRequest(context: Context, profile: File): String {
        val c = client(context)
        runBlocking {
            val t = System.nanoTime()
            val page = c.search("总裁")
            log("search 触发注册/复用 ${elapsedMs(t)}ms：${page.items.size} 部")
        }
        assertTrue("设备档案未落盘：$profile", profile.isFile)
        val json = JSONObject(profile.readText())
        val id = json.optString("device_id_str")
        assertTrue("档案缺 device_id_str", id.isNotEmpty())
        return id
    }
}
