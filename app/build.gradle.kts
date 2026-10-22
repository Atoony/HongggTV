import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 100
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 发布签名读 keystore/hgtv-release.properties（gitignore，生成方式见 scripts/README-release.md）；
            // 未配置时回落 debug 签名，保证 release APK 始终可安装（票08：GitHub Releases 侧载通道）
            signingConfig = if (hgStoreFile != null && hgStorePassword != null && hgKeyAlias != null && hgKeyPassword != null) {
                signingConfigs.create("hgRelease") {
                    storeFile = rootProject.file(hgStoreFile!!)
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

// 线上探针开关（HGTV_LIVE_PROBE 环境变量会被常驻 daemon 固化，改走 -PhgLive 每次显式传）
tasks.withType<Test>().configureEach {
    if (project.hasProperty("hgLive")) environment("HGTV_LIVE_PROBE", "1")
}
