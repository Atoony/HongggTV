---
id: 01
title: Charting 基线：目的地与项目范围
type: grilling
status: closed
assignee: charting-session
blocked-by: []
---

## Question

这张 wayfinder 图走到头是什么产物？新 App 与 guoapp 什么关系？站源范围多大？spec 细到什么程度？

## Resolution

2026-10-19 第一轮 grilling（交互式 HTML `interaction/round1-destination.html`）用户拍板，四项全按推荐：

1. **产物形态 = a**：可开工的设计方案（spec）+ 全部关键决策锁定；地图不含写代码。
2. **与 guoapp 关系 = a**：独立新项目（`D:\CC-project\hg-tv` 下新建代码库），专为 TV 从零设计 UI；复用 guoapp 已验证的红果接口层（搬 Go 代码或移植逻辑）。
3. **站源范围 = a**：只做红果，单源。
4. **spec 深度 = b（页面级）**：功能清单 + 重大决策结论 + 每页布局草案 + 焦点流转规则 + 遥控键映射表 + 页面↔接口数据对接表。
