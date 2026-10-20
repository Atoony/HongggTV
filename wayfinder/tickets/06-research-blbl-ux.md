---
id: 06
title: 研究：blbl 项目 TV UI/UX 调研
type: research
status: closed
assignee: research-agent-06
blocked-by: []
---

## Question

https://github.com/cat3399/blbl （用户指定的 UI/UX 参考）在 TV 端交互设计上有哪些值得红果 TV App 借鉴的具体做法？

## 调研点

1. 项目定位与技术栈（是否 TV 端、什么框架、是否开源完整可跑）。
2. 导航结构：主导航形态、层级深度、页面间跳转与返回逻辑。
3. 焦点处理：焦点视觉（缩放/描边/阴影）、焦点记忆、列表滚动跟随。
4. 遥控键位：方向/OK/返回/菜单键乃至长按的用法分配。
5. 状态设计：加载态、错误重试、空态的呈现方式。
6. 布局范式：海报卡片规格、行/网格混排、详情页与播放页结构。

## 要求

- 用 WebFetch 抓仓库 README、源码结构、截图说明（若有）；必要时搜相关介绍文章。
- 产出**可借鉴点清单**（每条注明对应 blbl 的具体实现/位置），供决策票 10（信息架构设计）直接取用。
- 不 clone、不运行该项目（只读调研）。
- Resolution 写回本文件。

## Resolution

### 一、六项调研点结论（均有仓库内一手来源）

**1. 项目定位与技术栈**（来源：README.md、仓库主页）
原生 Android（Kotlin + AndroidX + ViewBinding）第三方 B 站客户端，触摸+遥控双输入，Android 5+，面向平板/TV/车机；Media3(ExoPlayer)+Ijkplayer 双内核、OkHttp、Material/RecyclerView/ViewPager2。开源完整可跑（Gradle + Actions 双工作流）。仓库已于 2026-07-25 归档（2.7k star，509 commits）；**无 License 标注**——只能借鉴设计思路，不可直接复制代码。README 致谢自述"绝大部分页面和操作逻辑都是抄袭 BBLL"，即其 TV 交互范式承自 BBLL 一系。

**2. 导航结构**（来源：`app/src/main/java/blbl/cat3399/ui/MainActivity.kt`、`MainRootNavRegistry.kt`）
- 左侧常驻侧边栏（搜索/推荐/分类/动态/直播/我的 + 顶部头像 + 底部设置）+ 右侧内容区，ConstraintLayout 双栏；层级仅两层（侧边栏 → 页内 Tab），无更深嵌套。
- 页面切换用 **show/hide + 确定性 tag（`"main_root_"+key`）复用根 Fragment**，非 replace；对隐藏页 `setMaxLifecycle(STARTED)`。滚动位置、页内 Tab 天然保留。
- 返回键优先级链：关浮层 → popBackStack → 焦点在侧边栏则双击退出 → 委托当前 Fragment 的 `BackPressHandler` 接口 → 不在启动页则回启动页 → 双击退出（1.5s 窗口，Toast "再按一次退出应用"）。启动页可由用户配置（`resolveLaunchNavId` + prefs）。

**3. 焦点处理**（来源：`core/ui/DpadGridController.kt`、`FocusReturn.kt`、`res/animator/blbl_focus_scale.xml`、`res/layout/item_video_card.xml`、MainActivity.kt）
- 焦点视觉：全局统一 `stateListAnimator=blbl_focus_scale`（**1.04x / 120ms** 缩放）+ MaterialCardView `strokeColor=blblFocusStrokeColor` **2dp 描边**，零 elevation；侧边栏选中态另用主题色填充 + 图标 tint + 标签展开（`SidebarNavAdapter.kt`）。
- 焦点记忆四层：Fragment show/hide 保页面状态；`FocusReturn`（弱引用捕获/恢复，含下一帧 post 重试）管面板开关往返；`lastMainFocusedView` 管侧边栏↔内容区往返；`lastKnownFocusedSpanIndex` 管网格列位置。
- 列表滚动跟随：`DpadGridController` 解决 TV 网格焦点五类缺陷——边缘防外溢（`FocusFinder` 搜索限定在 RecyclerView 内）、load-more 后焦点续接（pending 状态机，`position+spanCount` 落点）、adapter 重置期间焦点 parking（临时改 `descendantFocusability`）、缩放动画导致的顶行误判修正、16ms×30 次逐帧重试。
- MainActivity 的 DPAD_DOWN 护栏：按 DOWN 时临时 `FOCUS_BLOCK_DESCENDANTS` 屏蔽侧边栏/Tab 条，防焦点意外逃逸，松开 120ms 后释放。

**4. 遥控键位**（来源：`core/tv/RemoteKeys.kt`、`MainActivity.dispatchKeyEvent`、`feature/player/PlayerDefaultShortPressDispatcher.kt`、`HoldToTriggerController.kt`）
- 全局：DPAD_LEFT 从内容区**最左缘**（几何判定：焦点 view 距主容器左缘 ≤24dp，StaggeredGrid 需首列）进侧边栏；DPAD_RIGHT 出；**MENU/REFRESH/F5 = 刷新当前页**（`RefreshKeyHandler` 接口递归分发到当前子页面）。
- OK 键：短按=点击；长按可配置（卡片长按动作走 prefs，`VideoCardActionController.resolveLongPressAction`）；`DpadGridController` 支持把 DPAD_CENTER/ENTER 长按转 `performLongClick`。
- 播放器键位三层策略：音量键走系统 AudioManager（失败回退重放）；MENU/SETTINGS/INFO/GUIDE/媒体键转播放器语义；其余键统一重放。另有 `PlayerCustomShortcut*` 五件套实现**用户自定义快捷键**（catalog + 输入策略 + 按键状态机）。
- `HoldToTriggerController`：长按 N ms 触发动作（如倍速）+ 0~1 进度实时反馈 + 短按仍是点击，触摸与按键（DPAD_CENTER/ENTER/SPACE/手柄 A）双路径统一。

**5. 状态设计**（来源：`feature/video/VideoGridFragment.kt`）
- 加载态：SwipeRefreshLayout 转圈，无骨架屏；TV 刷新键触发时手动驱动 `isRefreshing`。
- 错误态：**仅 Toast**（"加载失败，可查看 Logcat"），无错误布局、无重试按钮；重试 = 再按遥控刷新键。属开发者向的弱设计。
- 空态：**无专门空视图**，仅焦点兜底（recycler requestFocus）。
- 分页防抖由 `PagedGridStateMachine`（isLoading/endReached）承担；分页触发三路合一（滚动阈值距底 8 项 / D-pad 触底 / 视口填充监控大屏自动补页）。播放页缓冲为独立 overlay（大 ProgressBar+文案，focusable=false）。

**6. 布局范式**（来源：`res/layout/` 各 XML）
- 海报卡 `item_video_card.xml`：MaterialCardView 10dp 圆角、`constraintDimensionRatio 16:9` + centerCrop、封面底部渐变遮罩、角标体系（左下播放/弹幕数、右下时长、右上充电标、封面内观看进度条）、2 行标题 + 副标题行、焦点 1.04x+2dp 描边、四个快捷操作按钮（稍后再看/详情/UP主/不感兴趣）默认 gone 焦点/长按时浮出。
- 网格页 `fragment_video_grid.xml`：SwipeRefreshLayout 包单 RecyclerView，8dp padding、`clipChildren/clipToPadding=false`（焦点放大不裁剪）。
- 详情页 `activity_video_detail.xml`：**单 RecyclerView 多 viewType**——封面 header、选集、推荐全是列表项，页面仅一个悬浮返回按钮；焦点天然连续。
- 选集条目 `item_video_detail_playlist.xml`：MaterialButton 流式 chip，高 52dp、minWidth 140dp、圆角 20dp、焦点缩放——大目标好按。
- 播放页 `activity_player.xml`：渲染层（`use_controller=false` 自绘控制）→ 弹幕层 → 缓冲层 → 顶栏（invisible 占位）→ UP 快捷卡 → 底栏（SegmentedSeekBar+控制按钮行）→ **常驻底部细进度条**（控制栏隐藏时仍在）→ seek OSD → 47% 高度 guideline 定位的底部列表面板（页面列表/分P/推荐三 tab，`nextFocus*` 显式串联）→ 右侧信息/设置/评论抽屉。纯展示层一律 `focusable=false`，根布局关默认焦点高亮。
- 弹窗 `view_popup_modal.xml`：全屏 #66000000 遮罩（clickable 拦截）+ 居中 MaterialCardView（16dp 圆角、wrap_content）+ 标题/内容/右对齐按钮。
- 缩放体系 `core/ui/UiScale.kt`+`UiDensity`：以 1080p@1.5x 为基线做设备归一化，用户因子叠加；`PlayerUiMode.kt` 按因子统一缩放边距/字号/圆角。

### 二、红果 TV 短剧 App 可直接搬用清单

1. **侧边栏主导航 + show/hide 根 Fragment（确定性 tag + setMaxLifecycle）**——状态保留机制整体可搬（MainActivity.kt / MainRootNavRegistry.kt）。
2. **返回键优先级链 + 双击退出 + 非启动页先回启动页**——语义链完整，可原样照搬（MainActivity.kt）。
3. **焦点视觉体系：1.04x/120ms stateListAnimator + 2dp 描边，elevation=0**——参数可直接复用，全局一致性靠统一 attr（blbl_focus_scale.xml / item_video_card.xml）。
4. **DpadGridController 五件能力（边缘防外溢/列记忆/load-more 焦点续接/焦点 parking/顶行误判修正）**——任何 TV 网格页（海报墙、选集网格）都需要，思路+参数可搬（core/ui/DpadGridController.kt）。
5. **进侧边栏的几何判定（≤24dp + 首列）**——防方向键误触侧边栏，直接搬（MainActivity.canEnterSidebarFrom）。
6. **MENU 键=刷新当前页（RefreshKeyHandler 递归分发）**——TV 端低成本的重试/刷新通道，直接搬（core/tv/RemoteKeys.kt + ui/RefreshKeyHandler.kt）。
7. **长按 OK=快捷操作/倍速 + HoldToTrigger 进度反馈**——短剧场景长按可映射"跳过片头/下一集"，直接搬（HoldToTriggerController.kt）。
8. **播放器键位三层策略 + 常驻底部进度条 + seek OSD + 47% 底部选集面板（三 tab）**——短剧"连播+快速换集"高频，此结构直接搬（activity_player.xml / PlayerDefaultShortPressDispatcher.kt）。
9. **详情页单 RecyclerView 多 viewType（header=封面+简介、选集、推荐混排）**——短剧详情页同构，直接搬（activity_video_detail.xml）。
10. **选集大按钮 chip（52dp 高 / minWidth 140dp / 圆角 20dp）**——遥控好按，直接搬（item_video_detail_playlist.xml）。
11. **海报卡角标+渐变遮罩+封面观看进度条范式**——搬机制，比例换掉（见下）（item_video_card.xml）。
12. **UiDensity 1080p@1.5x 基线归一化 + 用户缩放因子**——TV 分辨率碎片化适配方案，直接搬（core/ui/UiScale.kt）。
13. **分页三路合一（滚动阈值 + D-pad 触底 + 视口填充监控）**——大屏一屏不满自动补页，直接搬（VideoGridFragment.kt）。

### 三、不适用 / 需改造

1. **竖屏短剧内容**：blbl 全套按 16:9 横屏设计；竖屏 9:16 播放时底栏/侧面板布局需重排（机制、焦点链可留，布局参数要重算）；海报卡只需把 `constraintDimensionRatio` 换 9:16 并把角标改为集数/完结标。
2. **横向海报行**：blbl 推荐页是整页网格，**没有横滑行（row）实现**；红果首页海报行需自建横向 RecyclerView 的行内边缘 + 上下行切换逻辑（DpadGridController 思路可参照但无现成代码）。
3. **大集数选集（80-100+ 集）**：blbl 的 chip 流式布局在集数巨大时会失控，需改造为分组 tab（第 1-50 集 / 51-100）或分页网格；其分P面板"tab+列表"结构可作骨架。
4. **错误态/空态**：blbl 仅 Toast + 无空视图，是明显短板——红果作为正式产品需专门错误页（焦点可及的重试按钮）与空态引导，**不可照搬**。
5. **无 License**：仓库未标注开源协议且已归档，只可借鉴交互设计与参数，**不可复制其代码**。
6. 弹幕、充电、追番等 B 站特有功能与短剧场景无关，不适用。
