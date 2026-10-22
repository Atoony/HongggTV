package com.hgtv.app.perf

import android.app.ActivityManager
import android.content.Context

/**
 * 设备性能档位（SPEC §8 低端机降级四项）：
 * 关焦点缩放动画 / 列表预取 2–4 / 封面压缩 ≤400px / 内存缓存上限。
 * perfProfile 为纯函数便于单测；Application 启动时按 isLowRamDevice 钉死，进程内不变。
 */
data class PerfProfile(
    val focusScaleAnimation: Boolean,
    val prefetchAhead: Int,
    val coverMaxPx: Int?,
    val memoryCachePercent: Double,
)

fun perfProfile(isLowRamDevice: Boolean): PerfProfile =
    if (isLowRamDevice) {
        PerfProfile(
            focusScaleAnimation = false,
            prefetchAhead = 2,
            coverMaxPx = 400,
            memoryCachePercent = 0.10,
        )
    } else {
        PerfProfile(
            focusScaleAnimation = true,
            prefetchAhead = 4,
            coverMaxPx = null,
            memoryCachePercent = 0.20,
        )
    }

object DevicePerf {
    /** 默认按常规档；Application.onCreate 里 init() 覆盖（UI 线程惰性引用兜底）。 */
    var profile: PerfProfile = perfProfile(false)
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        profile = perfProfile(am.isLowRamDevice)
    }
}
