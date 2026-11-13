# 红果果短剧 hg-tv

在 Android TV 上看红果短剧的非官方客户端。纯遥控器操作，为电视大屏而生。

> **免责声明**：本项目为个人学习与研究项目，与红果短剧 / 字节跳动无任何关联。所有内容与数据来自 [openhongguo](https://github.com/beilunyang/openhongguo) 非官方 SDK，不保证持续可用（红果服务端随时可能收紧接口导致功能失效）。仅供个人学习交流，**禁止商用**，请于 24 小时内删除并支持正版。

## 功能

- **首页** — 继续观看（断点续播）+ 热播榜
- **搜索** — 遥控器拼音首字母键盘（6×6），联想 / 直达 / 热门词三列同屏
- **详情页** — 整页信息横幅、多季合集页（全屏选季）、简介全文弹层、海报模糊背景
- **播放器** — 换集 / 断点续播 / 倍速（0.75–2.0x，换集保持）/ 清晰度切换 / 自动连播 / 下一集预载（跳集秒开）/ 选集抽屉 / 播放中屏幕常亮
- **加密流播放** — 红果 CENC 加密分集的 AES-CTR 本地样本级解密，无需系统 DRM 支持（minSdk 21 可用）
- **观看历史** — 本机存储，覆盖安装不丢

## 下载安装

1. 到 [Releases](https://github.com/Atoony/HongggTV/releases) 下载最新 APK
2. 用 U 盘或 [ADB](https://developer.android.com/tools/adb)（`adb install -r xxx.apk`，电视开启"ADB 调试"后 `adb connect <电视IP>:5555`）侧载到电视 / 盒子
3. 系统要求 Android 5.0+（API 21+）的电视或盒子；同签名覆盖升级可保留观看历史与进度

## 技术栈

- **UI**：Kotlin 2.1 + Jetpack Compose（Material3），全键盘焦点导航（DPAD/方向键，不依赖触屏）
- **播放**：Media3 / ExoPlayer，自研 CENC AES-CTR 解密 DataSource
- **数据**：[openhongguo](https://github.com/beilunyang/openhongguo) SDK（一切红果数据的唯一入口，见 [ADR 0001](docs/adr/0001-openhongguo-as-data-source.md)）+ 独立 domain 垫层（UI 不直接消费 SDK 类型）
- **存储**：DataStore Preferences（观看进度 / 断点 / 设置），无任何账号体系

## 开发

```bash
git clone https://github.com/Atoony/HongggTV.git
cd HongggTV
./gradlew assembleDebug        # 构建（需 JDK 17+，ANDROID_HOME 就绪）
./gradlew testDebugUnitTest    # 92 个离线单元测试
./scripts/release.sh           # 本地出 release 包到 dist/（签名配置见 scripts/README-release.md）
```

## 已知限制

- 依赖非官方接口：红果服务端策略变化可能导致播放 / 榜单随时失效（v0.0.2 即为应对 [2026-11-10 服务端签名校验](https://github.com/Atoony/HongggTV/releases/tag/v0.0.2) 的紧急修复）
- 匿名设备有请求配额，高频换集 / 搜索可能触发服务端频控
- 仅在 Sony BRAVIA（Android 12）与模拟器（Android TV 36 x86_64）上验证过

## 致谢

- [openhongguo](https://github.com/beilunyang/openhongguo) — 数据层所依赖的非官方红果 SDK
