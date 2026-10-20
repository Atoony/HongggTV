# Wayfinder 地图：红果 TV 短剧 App 设计

> label: `wayfinder:map` · tracker: local-markdown（本目录）· 创建于 2026-10-19
>
> **✅ 已到达目的地（2026-10-20）**：`hg-tv/SPEC.md`（九章）+ `hg-tv/DESIGN.md`（视觉规范）定稿并验收通过，票 01–13 全部关闭。后续实施不在本图范围。

## Destination

一份**可开工的 Android TV 端红果短剧 App 页面级设计 spec**：功能范围、页面与导航结构、遥控交互规范（焦点流转 + 键位映射）、页面↔接口数据对接表、播放方案、技术选型全部锁定——之后照着做即可，无需再做任何重大决策。地图本身不含实现代码。

## Notes

- **独立新项目**（`D:\CC-project\hg-tv` 下新代码库，不动 guoapp 本体），借鉴 guoapp 已验证的红果接口知识，**接口层纯 Kotlin 重写**（票 13 修订，不用 Go 资产）。
- **只做红果单源；播放坚持纯红果**——取流是硬阻塞，攻坚优先，不混用其他站源。
- v1 功能：浏览/分类首页、搜索（含系列剧集搜索）、详情+选集、播放器、追剧+历史、收藏；**游客模式**（无红果账号）。**分期**：第一期 = 找剧+看剧 MVP（票 10），追剧/收藏/设置/系列聚合/榜单完整页/倍速延后二期。
- 目标设备：Android TV + 主流第三方盒子/电视（国产 ROM 兼容）。
- 导航范式：**左侧竖直导航栏**；UI/UX 参考 https://github.com/cat3399/blbl 。
- 需用户拍板时：生成交互式 HTML 到 `interaction/`，用户点选后回贴答案（用户 2026-10-19 指定）。
- 本 tracker 约定：票 = `tickets/NN-slug.md`，frontmatter 含 `status: open|closed`、`assignee:`（claim 即认领）、`blocked-by: [id...]`；**frontier** = open ∧ 无 open 阻塞 ∧ 无 assignee；resolution 写在票文件 `## Resolution` 段，关闭时改 `status: closed`。

## Decisions so far

- [Charting 基线：目的地与项目范围](tickets/01-charting-scope.md)：spec 型目的地、独立新项目、只红果、页面级深度
- [Charting 基线：产品边界](tickets/02-charting-product.md)：v1 功能清单、游客模式、设备范围、左侧导航范式、blbl 参考
- [播放源边界：纯红果攻坚](tickets/03-playback-pure-hongguo.md)：取流不可恢复也不混源，诊断是全图最高优先级
- [研究：红果数据模型与接口对接清单](tickets/07-research-hongguo-data-map.md)：v1 功能数据全覆盖（追剧/历史/收藏为纯本地存储）；系列/季无接口字段、纯标题正则启发式，"系列剧集搜索"可行但有命名局限；播放仍是唯一重大缺口
- [研究：blbl 项目 TV UI/UX 调研](tickets/06-research-blbl-ux.md)：原生 Android B站 TV 客户端（已归档、无 License，只搬思路不抄代码）；13 项可借鉴（两级导航/焦点缩放+四层焦点记忆/Dpad 网格控制/返回键链/播放页选集面板等）；错误与空态是短板需自建
- [研究：TV 技术栈对比](tickets/05-research-tech-stack.md)：五维对比原生四维占优——Compose for TV 已 stable（focusRestorer 官方 API）而 Flutter 官方不支持 TV 目标；Go 桥是 c-shared .so + FFI（非 gomobile），原生仅 ~30 行 JNI 即可零改动复用 2.4 万行 Go 资产；倾向原生 Kotlin + Compose for TV + Media3（拍板留票 08）
- [研究：红果取流诊断](tickets/04-research-stream-diagnosis.md)：根因=取流接口静默要求 X-Ladon/X-Argus 风控头（缺失时 200+0 字节，低敏接口不校验）；最小修复已在线验证——加 `X-Ladon=base64(BE32(ts))`、`X-Argus=base64(LE32(ts))` 约 6 行即稳定取流成功（CDN 直链+1080+CENC 密钥）；无需真机抓包/设备注册
- [决策：技术栈拍板](tickets/08-decide-tech-stack.md)：原生 Kotlin + Compose for TV + Media3；只复用 guoapp 的 Go API 层与处理方式（c-shared .so + JNI 零改动），UI 与本地存储原生重写
- [设计：第一期信息架构收敛](tickets/10-design-ia-navigation.md)：MVP 聚焦找剧+看剧——侧边栏 3 项（首页/搜索/历史）、首页三行无 banner、播放器 blbl 式（←→ seek、↓ 呼出选项栏：暂停/上一集/下一集/选集/设置）、三键交互零长按零 MENU、返回四级链；追剧/收藏/设置/系列聚合/榜单页/倍速延后二期
- [设计：搜索交互](tickets/11-design-search-interaction.md)：26 键 ABC 字母序（非 QWERTY）+数字行+双候选区（联想词行+拼音候选行）；进页落键盘、打字后 ↓ 直达联想词（4 键一次搜索）；搜索历史第一期做（本地 10 条）；结果按季直出、季号金色角标、空态自建
- [决策：播放链路方案](tickets/09-decide-playback-plan.md)：第一期只用 App video_model 主链 + Ladon/Argus 6 行修复（三级链砍掉）；错误规范 = 重试 2 次退避 → 响应内多 URL 切换 → 错误页；空响应监控第一期做（后台计数+日志）；CENC 沿用本地流服务（若票 13 改 Kotlin 重写则调整）
- [决策：接口层实现方式](tickets/13-decide-impl-asset.md)：**纯 Kotlin 重写，只借鉴 guoapp 的接口处理方式**（签名翻译 + 线上测试钉死 = 第一里程碑）；不用 Go .so/JNI；修订票 08 复用边界与票 09 CENC 条（改 Media3 自定义 DataSource）
- [定稿：spec 组装与验收](tickets/12-finalize-spec.md)：`SPEC.md` 九章 + `DESIGN.md` 视觉规范，用户 2026-10-20 验收通过；minSdk 21、1GB 低端基线、名称"红果果短剧"、GitHub Releases 分发；**地图完结**

## Not yet specified

- 用户实际测试用的电视/盒子型号（影响适配优先级，实施期收集，不阻塞 spec）

## Out of scope

- **多站源**：浏览/搜索/播放均不接其他站源（2026-10-19 用户拍板，Q3+Q6b）
- **下载/本地播放、多用户、弹幕**：v1 不做，留给未来版本（Q7 勾选结果）
- **手机/平板横屏兼容**（Q9 选 b 非 c）
- **实现代码本身**：本图是规划；开发在 spec 定稿后另行展开（Q1a）
