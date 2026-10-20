---
id: 08
title: 决策：技术栈拍板
type: grilling
status: closed
assignee: main-session-20261019
blocked-by: [05]
---

## Question

基于研究票 05 的事实对比，拍板 TV App 技术栈：Flutter 还是原生 Android（Compose for TV）？以及随之而来的 Go 接口层接入方式。

## 待拍板时需呈现

- 研究 05 的对比表与倾向建议
- 与"纯红果 + 取流诊断结果"的耦合（如取流修复依赖 Go 层签名代码，换栈会否影响攻坚节奏）
- 拍板后 graduating：播放器选型、Go 层接入细节（见地图 Not yet specified）

（本票为 HITL：以交互式 HTML 向用户呈现对比并拍板。）

## Resolution（2026-10-19，用户经 `interaction/round3-techstack.html` 拍板）

**技术栈 = 原生 Kotlin + Compose for TV + Media3**（研究票 05 倾向方案）。

**复用边界（用户补充确认）**："只利用原项目中的 API 和其处理方式，其他使用原生"——可行，具体为：
- **复用（零改动，经 c-shared .so + ~30 行 JNI）**：guoapp Go 层全部——签名链（含票 04 的 X-Ladon/X-Argus 修复）、App/Web 接口调用、video_model 解析（CDN 直链 + CENC 密钥）、弹幕独立签名链路、目录/榜单/封面缓存、本地流服务（resolve/fallback，CENC 解密继续由 Go 承担）。
- **不复用、原生重写**：全部 UI（Compose for TV）、本地存储（DataStore，追剧/历史/收藏的 WatchEntry/FollowState 结构照搬设计）、播放器（Media3：HLS/倍速/进度）。

连带落定（自地图 Not yet specified 毕业）：**播放器选型 = Media3**；**Go 接入 = c-shared .so + JNI**。

### 修订（2026-10-20，票 13）

用户经 `interaction/round8-impl-asset.html` 重审"复用边界"：上文的"只利用原项目中的 API 和其处理方式"，"利用"一词现落实为**只借鉴其知识**（接口清单、签名算法、参数坑、CENC 密钥规则——已沉淀于票 04/07），**不引入任何 Go 代码**：

- 接口层 **纯 Kotlin 重写**（签名链翻译 + App/Web 接口 + 缺季补全 + CENC），c-shared .so + JNI 方案**取消**
- 不变部分：UI = Compose for TV、存储 = DataStore、播放器 = Media3

详见票 13。
