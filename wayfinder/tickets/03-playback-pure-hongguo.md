---
id: 03
title: 播放源边界：纯红果攻坚
type: grilling
status: closed
assignee: charting-session
blocked-by: []
---

## Question

若红果 App 取流接口（`/novel/player/video_model/v1/`）诊断后仍不可恢复，播放层的边界是什么？

## Resolution

2026-10-19 第二轮 grilling 用户拍板：**b —— 坚持纯红果，攻坚到底**。

- 不采用"元数据红果 + 播放借其他站源"的混源方案；取流不通过就继续攻坚（抓包对比、参数调整等）。
- **推论**：研究票 04（取流诊断）是全图最高优先级、最重阻塞——纯红果决策使整个播放闭环押在它身上。
- 若研究最终证实 App 端取流在原理上不可行，此决策需带着事实重新拍板（在决策票 09 中呈现，不擅自推翻）。
