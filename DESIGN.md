# 红果果短剧 · 视觉设计规范

> v1.0 · 2026-10-20 · 配色提取自 round4–9 交互稿(用户拍板认可),作为第一期实现的基础视觉规范。
> 交互规范(焦点流转/键位/返回链)见 `SPEC.md` §4;本文件只管"长什么样"。

---

## 1. 设计原则

- **深色为底**:客厅/暗环境观看,深色背景护眼且衬托视频内容(OLED 友好)
- **10-foot UI**:电视距离 2–3 米,文字偏大、焦点醒目、留白充足
- **一个强调色**:橘红只用于焦点、主按钮、进度等"当前/行动"语义,不大面积铺色
- **内容优先**:海报是主角,UI 元素(角标/进度条)低调退后

## 2. 色彩令牌

### 2.1 背景与表面(由深到浅)

| 令牌 | 色值 | 用途 |
| --- | --- | --- |
| `bg` | `#0F1115` | 页面/播放器背景(近黑深蓝灰) |
| `surface` | `#1A1D24` | 卡片、面板、弹层背景 |
| `keySurface` | `#191D26` | 键盘键面、侧边栏项、次级按钮 |
| `keySurfaceDim` | `#14171E` | 功能键(⌫/搜索/切换)、更次级控件 |
| `outline` | `#2A2E38` | 卡片/输入框描边、分隔线 |
| `posterPlaceholder` | `#262B36` / `#2E3442`(焦点态) | 海报加载占位 |

### 2.2 文字

| 令牌 | 色值 | 用途 | 对比度(vs bg) |
| --- | --- | --- | --- |
| `onBg` | `#E8EAED` | 主文字(标题/正文) | ~15:1 |
| `onBgVariant` | `#9AA0AB` | 次要文字(说明/时间码/角标) | ~7:1 |
| `onKey` | `#8A93A6` | 键盘键文字 | — |
| `dim` | `#6B7280` | 未选中导航项、提示性文字 | ~3.5:1,仅 ≥12sp |
| `faint` | `#4A5060` | 已看集灰显、装饰性文字 | 不可作正文 |

### 2.3 品牌强调(橘红系)

| 令牌 | 色值 | 用途 |
| --- | --- | --- |
| `primary` | `#FF7043` | 焦点描边、主按钮、进度条、当前集、光标 |
| `onPrimary` | `#FFFFFF` | 主按钮/焦点态上的文字 |
| `primarySoft` | `#FF7043` @ 12% | 焦点项底色、选中 tab 底、当前集 chip 底 |

### 2.4 状态与辅助色

| 令牌 | 色值 | 用途 |
| --- | --- | --- |
| `success` | `#4CAF8D`(@ 12% soft 同理) | 成功/已作答/推荐标记 |
| `seasonGold` | `#FFD479` | 搜索结果"第N季"角标(区别于普通灰角标) |
| `warning` | `#E8B64C` | 警告、错误提示(错误页标题/文案可用 `onBg`) |
| `scrim` | `rgba(0,0,0,0.7)` | 海报角标底、浮层遮罩(浮层可用 88–92%) |
| `progressTrack` | `rgba(255,255,255,0.15)` | 进度条底轨 |

### 2.5 Compose 映射建议

`MaterialTheme(colorScheme = darkColorScheme(background = bg, surface = surface, surfaceVariant = keySurface, outline = outline, primary = primary, onPrimary = onPrimary, onBackground = onBg, onSurface = onBg, secondary = success, tertiary = seasonGold, error = warning, ...))`;`primarySoft`/`keySurfaceDim` 等作为扩展令牌放同文件常量。

## 3. 焦点系统(TV 核心)

| 项 | 规范 |
| --- | --- |
| 缩放 | `1.04x`(网格卡片可到 1.06x),动画 `120ms` |
| 描边 | `2dp solid primary`,零阴影 |
| 底色 | `primarySoft`(卡片可只用描边+缩放不加底色) |
| 键盘焦点键 | 键面变 `primary`、文字 `#FFFFFF`、外描边白色 2dp |
| 非焦点可点击项 | 无描边;靠位置与底色层级区分 |

## 4. 排版

| 用途 | 字号 | 字重 | 颜色 |
| --- | --- | --- | --- |
| 页面大标题 | 24sp | 600 | `onBg` |
| 区块标题/行标签 | 16sp | 600 | `onBg` |
| 正文/列表项 | 14sp | 400 | `onBg` |
| 辅助说明 | 12sp(最小) | 400 | `onBgVariant` |
| 键盘键 | 16sp | 500 | `onKey`(焦点白) |
| 角标/时间码 | 10–12sp | 400 | `onBgVariant` on `scrim` |

字体:系统默认无衬线(中文 Roboto + 思源黑体系),不自带字体文件。

## 5. 形状与间距

| 项 | 规范 |
| --- | --- |
| 卡片/面板圆角 | 12dp |
| 海报圆角 | 5dp |
| chip(选集/联想词)圆角 | 4–6dp |
| 按钮圆角 | 8dp(全高胶囊可选) |
| 网格间距 | 8dp |
| 卡片内边距 | 12–16dp |
| 页面安全边距 | 48dp(TV 过扫描防裁切) |

## 6. 组件视觉规则

- **海报卡**:9:16;角标左下(`scrim` 底、`onBgVariant` 字,季号用 `seasonGold`);进度条 = 底缘 3dp 轨(`progressTrack`)+ `primary` 填充
- **侧边栏项**:选中 = `primarySoft` 底 + 2dp `primary` 描边 + 图标/文字变 `onBg`;未选中文字 `dim`
- **选集 chip**:已看 = `keySurfaceDim` 底 + `faint` 字;当前集 = `primarySoft` 底 + `primary` 字 + `primary` 描边;普通 = `keySurface` 底 + `onBgVariant` 字
- **播放器 OSD**:`scrim` 半透明底、圆角小条、3s 自动隐藏
- **错误页**:标题 `onBg` + 错误码一行 `onBgVariant` + 主按钮(`primary` 底白字)
- **空态**:虚线描边框(`outline`)+ 居中提示文字 + 行动建议,不用插画
