package com.hgtv.app.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * 一次性诊断探针（清晰度档位归一）：解剖指定剧第 1 集每条 stream 的
 * definition/quality/width/height 原始字段，确定 quality 取值链该以哪边归一。
 * 用法：-e hgLive 1 -e hgQuery 军需处长的账本
 */
@RunWith(AndroidJUnit4::class)
class ProbeStreamFields {
    private fun live() = InstrumentationRegistry.getArguments().getString("hgLive") == "1"
    private fun query() = InstrumentationRegistry.getArguments().getString("hgQuery") ?: "总裁"

    @Test
    fun dumpStreamFields() {
        if (!live()) return
        val c = OpenhongguoClient.create(
            ApplicationProvider.getApplicationContext<Context>(),
            ClientOptions(),
        )
        kotlinx.coroutines.runBlocking {
            val page = c.search(query())
            println("HGPROBE search(${query()})：${page.items.size} 部，首部=${page.items.firstOrNull()?.title}")
            val series = page.items.firstOrNull() ?: return@runBlocking
            val eps = c.listEpisodes(series.id)
            val ep = eps.items.firstOrNull { it.playable } ?: eps.items.first()
            println("HGPROBE listEpisodes：${eps.items.size} 集，取第${ep.number}集 ${ep.id}")
            kotlinx.coroutines.delay(450)
            val ms = measureTimeMillis {
                val r = c.resolvePlayback(ep.id)
                println("HGPROBE protected=${r.playback.protected} streams=${r.playback.streams.size}")
                r.playback.streams.forEach { s ->
                    println(
                        "HGPROBE stream definition=${s.definition} quality=${s.quality} " +
                            "w=${s.width} h=${s.height} codec=${s.codec} " +
                            "hls=${s.hls} dash=${s.dash} prot=${s.protected}"
                    )
                }
            }
            println("HGPROBE done in ${ms}ms")
        }
    }
}
