---
id: 13
title: 决策：红果接口层实现方式（Go 资产复用 vs Kotlin 重写）
type: grilling
status: closed
assignee: main-session-20261020
blocked-by: [09, 10]
---

## Question

用户重审票 08 的"复用 Go 资产"结论：第一期只借鉴 guoapp 的接口处理方式（纯 Kotlin 重写）是否更优？Go 资产除省时间外还有什么实质好处？

## 背景

- 票 08 拍板：原生 Kotlin + Compose for TV + Media3，复用 guoapp Go API 层（c-shared .so + JNI 零改动）。
- 用户 2026-10-20 在票 09 拍板回贴中质疑该结论（原话："本期不能只借鉴 guoapp 的接口处理方式吗，必须要用 go 资产吗，除了省时间之外还有其他好处吗"）。
- 第一期范围已收敛（票 10：找剧+看剧 MVP），需要重写的接口面随之缩小。
- 本项目为独立新项目、guoapp 冻结为参考——"guoapp 演进可同步修复"这一好处基本不成立。

## 产出方式

- `interaction/round8-impl-asset.html` 权衡对比 + 拍板；结论若变，修订票 08（在票 08 Resolution 追加修订记录，不删原结论）。

## Resolution

2026-10-20 用户经 `interaction/round8-impl-asset.html` 拍板（Q1 选 a）：**纯 Kotlin 重写，只借鉴 guoapp 的接口处理方式**。

### 重写范围（第一期，票 10 收敛后）

1. 签名链：X-Khronos / X-Gorgon / X-SS-STUB / X-SS-Req-Ticket + X-Ladon / X-Argus（翻译自 Go，~几百行）
2. App 接口 ×3：目录 feed、video_detail、video_model
3. Web 接口 ×3：搜索页（_ROUTER_DATA 提取）、suggestion、热播榜（HTML/JSON-LD 解析）
4. 名称索引 + 缺季补全（~200 行，逻辑照抄）
5. CENC：spade_a → 密钥解出 + Media3 自定义 DataSource 边读边 AES-CTR 解密（替代 Go 本地流服务）
6. 基础设施：随机 device_id/iid、公共 query、UA、空响应监控计数（票 09）

估 1500~2500 行 Kotlin；首版比 Go 复用多 1~2 周（含线上验证）。

### 实施要点

- **第一里程碑 = 签名链翻译 + 线上测试钉死**：把票 04 探针（详情通 / 取流通 / Ladon 消融矩阵）翻译成 Kotlin 测试，签名验证通过后再写业务接口——X-Gorgon 翻译是最大风险点，前置消化
- 已知坑随知识迁移：Web 搜索 page=1 301 剥参、bytevc2 编码跳过、联想 word_type 白名单、URL base64 解码

### 连带修订

- 票 08 复用边界（已追加修订记录）
- 票 09 "CENC 与播放集成"（Go 本地流服务 → Media3 自定义 DataSource，已改）
