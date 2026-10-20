---
id: 05
title: 研究：TV 技术栈对比（Flutter vs 原生 Compose for TV）
type: research
status: closed
assignee: research-agent-05
blocked-by: []
---

## Question

为一个"遥控器焦点交互为核心、目标含国产盒子、需接入 Go 红果接口层"的 Android TV 短剧 App，Flutter 与原生 Android（Compose for TV）两条路线各自的真实成熟度如何？事实对比是什么？

## 对比维度（决策票 08 拍板所需）

1. **焦点控制**：D-pad 焦点遍历的成熟度与控制粒度——Flutter（FocusNode/FocusTraversalPolicy、社区 TV 库现状）vs 原生（Leanback、Compose for TV 的 focus restorer 等）；焦点记忆（页面返回恢复焦点位置）两侧支持度。
2. **视频播放器生态**：HLS/加密流支持、倍速、进度条控制、与 Go 层配合的播放器封装（Flutter: media_kit/video_player 等；原生: ExoPlayer/Media3）。
3. **Go 接口层接入**：guoapp 已有 Flutter↔Go 桥（`guoapp/native/bridge`）；若走原生，Go 层如何接入（移植成 Kotlin？gomobile AAR？sidecar 进程？）成本对比。
4. **国产盒子兼容**：低端 ROM/低性能设备上的表现、WebView/渲染差异、已知坑。
5. **单人开发效率**：UI 迭代速度、调试体验、与 guoapp 经验的复用度。

## 要求

- 产出**事实对比表** + 倾向性建议（不拍板，拍板在票 08）。
- 用 WebSearch/WebFetch 查最新文档与社区实践（2025-2026 现状），不凭训练记忆下结论。
- Resolution 写回本文件。

## Resolution

调研日期 2026-10-19。方法：四路并行一手来源核查（flutter.dev / docs.flutter.dev / api.flutter.dev / pub.dev / flutter GitHub issues；developer.android.com / androidx releases / Google 官方博客；guoapp 本地代码检视）。所有 GitHub issue 状态为当日抓取；标注"未证实"处为未能从一手来源确认的事项。

### 事实对比表（5 维度）

| 维度 | Flutter | 原生（Kotlin + Compose for TV + Media3） | 相对优势 |
|---|---|---|---|
| **1. D-pad 焦点控制** | 官方明确"Android TV is not an officially supported target"（[#12866](https://github.com/flutter/website/issues/12866)，2026-01-05 团队回复；umbrella issue [flutter#180542](https://github.com/flutter/flutter/issues/180542) 仅 P3、无里程碑、无 assignee）。核心 Focus API（FocusNode/FocusTraversalGroup/ReadingOrder/OrderedTraversalPolicy）稳定，但面向桌面键盘设计，无任何 TV 官方文档。焦点记忆无官方 save/restore API（仅 FocusScopeNode 隐式记忆），社区靠 [fluttercandies/dpad](https://pub.dev/packages/dpad)（v3.1.1，2026-10 初发布，唯一活跃 TV 焦点库，周下载 5.18k，DpadRegion+memoryKey）补漏。TV 核心 bug 长期 open：焦点丢失 [#49783](https://github.com/flutter/flutter/issues/49783)（2020 开，P2）、TextField D-pad broken [#147772](https://github.com/flutter/flutter/issues/147772)、SELECT/ENTER 断言错误 [#101275](https://github.com/flutter/flutter/issues/101275)（P2）、key repeat 不保证 + [#110950](https://github.com/flutter/flutter/issues/110950) 断言 bug | [androidx.tv:tv-material 1.1.0 stable](https://developer.android.com/jetpack/androidx/releases/tv)（2026-05-06）、tv-foundation 1.0.0 stable。焦点记忆是官方一等 API：`Modifier.focusRestorer()`（Lazy* 容器自动是 focus group，官方 TV 迁移指南标准做法=每行 LazyRow 挂无参 focusRestorer）；跨页返回 saveFocusedChild()/restoreFocusedChild()；focusGroup/focusProperties(onEnter/onExit)/FocusManager.moveFocus + 2D 空间焦点遍历。注意 TvLazy* 已废弃移除（功能并入 compose-foundation 1.7 标准 Lazy*，pivot 定位改 BringIntoViewSpec）。已知缺陷仅 focusRestorer 数据变化回归 [b/481564275](https://issuetracker.google.com/issues/481564275)。Leanback 已官方弃用（"Use Compose for TV instead"），Google 主推 Compose for TV | **原生显著占优** |
| **2. 视频播放器生态** | `video_player` 2.14.1（官方、极活跃），Android 底层已迁 [Media3-ExoPlayer](https://pub.dev/packages/video_player_android/changelog)（升至 media3 1.9.x）→ HLS/AES-128 由 Media3 内核支持，位置流 100ms 利于进度条；但 **DRM 不支持**（[PR #11115](https://github.com/flutter/packages/pull/11115) 未合并），带 token 加密 m3u8 有 open P2 [#115422](https://github.com/flutter/flutter/issues/115422)。`media_kit` 1.2.6（2025-12 后未更新）官方声明[有限维护](https://github.com/media-kit/media-kit/issues/1337)（2025-11-25，"没有全职工程师"），DRM 不支持，Amlogic mediacodec_embed 失效（[#614](https://github.com/media-kit/media-kit/issues/614)）、HDR 元数据清零（[#1243](https://github.com/media-kit/media-kit/issues/1243)）。`better_player` 2026-08 复活至 1.26.0（Media3 + Widevine L1/L3 + BetterPlayerPool 播放器池/预加载，命中短剧场景，但命名空间破坏性变更）。**盒子关键坑**：[flutter#155355](https://github.com/flutter/flutter/issues/155355)（P3 回归 open）：video_player_android 2.7.2+ 在 API 29+ 的 **Amlogic/MediaTek 盒子 100% 复现解码器初始化失败**，workaround=platform view 渲染 | [Media3 1.11.1 stable](https://developer.android.com/jetpack/androidx/releases/media3)（2026-09-10）：[HLS](https://developer.android.com/media/media3/exoplayer/hls) AES-128=YES、Widevine（FMP4）、倍速 `setPlaybackSpeed`（Sonic 变速不变调，1.10+ 另有官方 Compose 版 PlaybackSpeedControl）、seekTo；缓存 SimpleCache+CacheDataSource；预加载 DefaultPreloadManager（[官方 2025-09 博客](https://android-developers.googleblog.com/2025/09/introducing-preloading-with-media3.html)）；`media3-ui-compose` PlayerSurface（1.8.0+）/ media3-ui-compose-material3（1.9.0+），官方 TV 迁移指南明确"never wrap PlayerView in AndroidView"。ExoPlayer 即 Android TV 原生路径，Google TV 生态大规模验证 | **原生占优**（Flutter 内核同为 Media3，但包装层多盒子回归、无 DRM 通道） |
| **3. Go 接口层接入** | **现状即答案**：guoapp 已是 `go build -buildmode=c-shared` 直出 `libduanju_core.so`（arm64-v8a/armeabi-v7a/x86_64，min API 26），`lib/core_bridge.dart` dart:ffi 调 2 个 C 符号（DuanjuRequest/DuanjuFree，JSON-in/out）——**不经 gomobile** | Go 层（≈2.4 万行）**零改动**复用：Kotlin 侧 `System.loadLibrary("duanju_core")` + `external fun DuanjuRequest(...)` JNI 声明（≈30 行胶水）调同一批 C 符号。备选：gomobile bind（半弃疗：无版本承诺、无 map/channel/unsigned、panic 不可跨边界、[Go 1.23 旧 Android SIGSYS 崩溃](https://github.com/golang/go/issues/70495)）；sidecar（Syncthing 模式：Go 二进制伪装 lib*.so + ProcessBuilder + localhost REST，生产验证充分但进程管理复杂）；全量移植 Kotlin（2.4 万行无成熟 Go→Kotlin 工具，成本最高，不必要） | **基本持平**——现有桥是 C ABI+JSON 协议，天然 UI 框架无关，"走原生必须重写 Go 层"的前提不成立 |
| **4. 国产盒子兼容** | 硬下限 **minSdk 24**（[Flutter 支持矩阵](https://docs.flutter.dev/reference/supported-platforms)）→ Android 6 老盒子（老天猫魔盒 S905 等）直接出局。Impeller 在无 Vulkan 的盒子主流 GPU（PowerVR/老 Mali）回退 OpenGLES，有未修渲染 bug [flutter#179268](https://github.com/flutter/flutter/issues/179268)（open P2，2025-12）。修改版 Android TV 13 真机 Flutter 白屏/打不开案例（[#152918](https://github.com/flutter/flutter/issues/152918)）。低端机型卡顿有实测（[fvp#208](https://github.com/wang-bin/fvp/issues/208)：小米电视 4C 等）。视频层叠加 #155355 Amlogic 回归 | minSdk 自主可控；国内侧载分发不受 Google Play 64 位政策约束（可只发 armeabi-v7a，覆盖"64 位硬件跑 32 位系统"的盒子）；ExoPlayer + 系统 D-pad 为 TV 原生路径。坑：若走 H5 混合则 WebView 陈旧不可更新（盒子实测案例多）；MIUI for TV 等定制差异 | **原生占优**（Flutter 有 minSdk 24 + Impeller 回退 + Amlogic 视频回归三重已知风险） |
| **5. 单人开发效率** | 开发者 Flutter 熟练度可直接复用（guoapp 全部 Flutter：85 个顶层 dart 文件、media_kit 深度封装、dart:ffi 桥模式可照抄）；热重载迭代快。代价：TV 焦点需以 dpad 包为基座自行踩坑（SELECT/长按/焦点丢失等 open bug 全要自己绕）、TV 模拟器识别还有回归 [#175440](https://github.com/flutter/flutter/issues/175440) | 若无 Kotlin/Compose 经验则有学习曲线。但 TV 组件/文档/codelab/官方示例齐全（[tv-samples](https://github.com/android/tv-samples)：JetStreamCompose 等，2026-07 仍在维护），焦点方案有官方迁移指南逐条对照，TV 交互踩坑面小 | **Flutter 占优**（对 Flutter 背景单人开发者；是对冲前三维度劣势的唯一显著筹码） |

### 倾向性建议（供票 08 拍板，本研究不拍板）

5 个维度中**原生在 4 个维度占优**（焦点、播放器、盒子兼容为显著优势，Go 接入持平），Flutter 仅在单人开发效率占优——而 Go 接入持平这一点削弱了 Flutter 最大的传统理由（"Go 层已接好"）：guoapp 的桥是 C ABI + JSON 协议，原生侧只需约 30 行 JNI 胶水即可零改动复用全部 Go 资产。

**倾向：原生 Kotlin + Compose for TV（tv-material 1.1.0）+ Media3（1.11.1）**。本项目两大硬需求恰好命中原生强项：遥控器焦点交互为核心（focusRestorer 是官方一等 API vs Flutter 官方明确不支撑 TV）、目标含国产盒子（minSdk 自主 + ExoPlayer 原生路径 vs Flutter 的 minSdk 24/Impeller 回退/#155355 Amlogic 回归三重已知风险）。

若决策仍选 Flutter，需明确接受以下工程前提并以之约束设计：焦点基座 = fluttercandies/dpad（唯一活跃库）；播放器 = video_player（Media3 内核）且必须实现 Amlogic 盒子的 platform view 渲染 fallback（#155355）；放弃 Android 6 盒子（minSdk 24）；真机验证清单须含 PowerVR/老 Mali GPU 设备（Impeller #179268）；倍速保音调需真机验证（video_player 音调行为官方未说明，media_kit 有 setPitch 但有限维护）。

另注（渠道事实，与 UI 框架无关）：当贝市场上架硬性要求遥控器适配、软著、ICP 备案（2024-04 后未备案全量下架）、加载 10s 内、每周发版 ≤2 次；海信需 .znds 后缀包、TCL 走欢视助手（来源：[当贝开发者规则](https://dev.dangbei.com/rule.php)、[当贝海信页](https://www.dangbei.com/haixin.html)）。

### 主要未证实事项（决策时知悉）

- media_kit/video_player/better_player 的倍速音调保持行为：官方文档未明示（Media3 Sonic 变速不变调有官方来源，包装层未证实）。
- Flutter 3.41+ 在 Android 10+ 强制 Impeller 的说法来自社区博客，官方 release note 层面未逐条核实。
- MIUI for TV 各批次底层 Android 版本分布无官方数据。
- Carousel 完整 API 签名（tv-material 组件存在性有 release notes 佐证，签名未逐项验证）。

