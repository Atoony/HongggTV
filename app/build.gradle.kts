import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名配置（票08）：keystore/hgtv-release.properties 不入库，四键齐才启用 hgRelease 签名
val hgKeystoreProps = Properties().apply {
    val f = rootProject.file("keystore/hgtv-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hgStoreFile: String? = hgKeystoreProps.getProperty("storeFile")
val hgStorePassword: String? = hgKeystoreProps.getProperty("storePassword")
val hgKeyAlias: String? = hgKeystoreProps.getProperty("keyAlias")
val hgKeyPassword: String? = hgKeystoreProps.getProperty("keyPassword")

android {
    namespace = "com.hgtv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hgtv.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "0.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 发布签名读 keystore/hgtv-release.properties（gitignore，生成方式见 scripts/README-release.md）；
            // 未配置时回落 debug 签名，保证 release APK 始终可安装（票08：GitHub Releases 侧载通道）
            signingConfig = if (hgStoreFile != null && hgStorePassword != null && hgKeyAlias != null && hgKeyPassword != null) {
                signingConfigs.create("hgRelease") {
                    storeFile = rootProject.file(hgStoreFile)
                    storePassword = hgStorePassword
                    keyAlias = hgKeyAlias
                    keyPassword = hgKeyPassword
                }
            } else {
                logger.warn("hgtv: 未找到 keystore/hgtv-release.properties，release 构建将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        // media3 DataSpec 构造依赖 android.net.Uri.parse（返回 null 即可），
        // 其余单测均为纯 JVM 逻辑，不受默认值化影响
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text")

    // Compose for TV
    implementation("androidx.tv:tv-material:1.0.0-beta01")

    // Media3 播放器
    implementation("androidx.media3:media3-exoplayer:1.6.0")
    implementation("androidx.media3:media3-ui:1.6.0")
    implementation("androidx.media3:media3-datasource:1.6.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 海报加载
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 数据源层（ADR 0001 换芯）：一切红果数据经此 SDK。
    // 临时本地 AAR = 上游 main@015a388（0.2.0 + Argus/Ladon 播放签名修复 + 弹幕 API）：
    // 2026-11-10 起服务端在 video_model/v1/ 校验 x-argus/x-ladon，Maven 0.2.0 播放被判
    // 200+空 body；Maven 发新版后切回坐标依赖并删除 app/libs 下该 AAR。
    implementation(files("libs/openhongguo-0.2.1-signfix.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测构造 media3 DataSpec 需要非空 android.net.Uri（mockable jar 全返 null）
    testImplementation("org.mockito:mockito-core:5.17.0")

    // 票 12 探针：SDK 四连链路的 instrumented test 载体
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// ── 真机数据保护闸（2026-11-13 事故教训）─────────────────────────────────────
// 设备级任务（connected*/install*）会吃掉 adb devices 里所有在线设备，且 AGP 在
// debug/release 签名冲突时会自动 uninstall 再装——当天曾把 BRAVIA 真机上的正式包
// 连同观看历史等数据一起清掉（手动 adb install -r 遇签名冲突只会报错、不动已装包）。
// 因此：任何非模拟器设备在线、且未用 ANDROID_SERIAL 限定目标时，直接拒绝执行。
// 真机装包统一走 scripts/tv-install.sh（同签名 install -r 覆盖，数据保留）。
tasks.matching { it.name.startsWith("connected") || it.name.startsWith("install") }.configureEach {
    doFirst {
        if (System.getenv("ANDROID_SERIAL") != null) return@doFirst
        val adb = File(
            android.sdkDirectory,
            "platform-tools/" + (if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"),
        )
        if (!adb.exists()) return@doFirst
        val proc = ProcessBuilder(adb.absolutePath, "devices").start()
        val online = proc.inputStream.bufferedReader().readLines()
            .filter { it.endsWith("\tdevice") }
            .map { it.substringBefore('\t') }
            .filter { !it.startsWith("emulator-") }
        if (online.isNotEmpty()) {
            throw GradleException(
                "真机在线（${online.joinToString()}）：设备级 gradle 任务会在所有设备上执行，" +
                    "签名冲突时 AGP 自动卸载重装会清掉真机 App 数据。请设 ANDROID_SERIAL=<模拟器id> 限定后重跑；" +
                    "真机装包走 scripts/tv-install.sh。",
            )
        }
    }
}
