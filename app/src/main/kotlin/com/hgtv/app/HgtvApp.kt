package com.hgtv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.hgtv.app.perf.DevicePerf

/** Application：低端机档位钉死 + Coil 全局 ImageLoader（内存缓存上限按档位，SPEC §8）。 */
class HgtvApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DevicePerf.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(DevicePerf.profile.memoryCachePercent)
                    .build()
            }
            .build()
}
