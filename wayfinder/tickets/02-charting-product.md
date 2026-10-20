---
id: 02
title: Charting 基线：产品边界
type: grilling
status: closed
assignee: charting-session
blocked-by: []
---

## Question

v1（MVP）包含哪些功能？要不要红果登录？目标设备范围？主导航范式？

## Resolution

2026-10-19 第二轮 grilling（交互式 HTML `interaction/round2-frontier.html`）用户拍板：

1. **MVP 功能**：浏览/分类首页、搜索、详情+选集、播放器、追剧+历史、**收藏**；补充需求"**系列剧集搜索**"（语义待设计票澄清：按系列/季维度组织与搜索剧集）。弹幕、下载/本地播放、多用户不进 v1。
2. **账号模式 = a**：游客模式，v1 全走免登录接口；若诊断发现取流必须登录，届时单开决策票。
3. **目标设备 = b**：Android TV + 主流第三方盒子/电视（覆盖国产 ROM）；不做手机横屏。具体测试设备型号用户暂未提供。
4. **主导航范式 = a**：左侧竖直导航栏（Netflix/YouTube TV 风格）；UI/UX 参考 https://github.com/cat3399/blbl 。
5. **技术栈 = c**：无硬偏好，先研究对比（Flutter TV vs 原生 Compose for TV）再拍板 → 已开研究票 05 + 决策票 08。
