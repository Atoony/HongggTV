plugins {
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.1.20：openhongguo 0.2.0 的 kotlin_module 是 2.1.0 metadata，1.9 编译器读不了（票12 接入的必要升级）
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
