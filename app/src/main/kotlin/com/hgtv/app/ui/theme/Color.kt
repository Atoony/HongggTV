package com.hgtv.app.ui.theme

import androidx.compose.ui.graphics.Color

// DESIGN.md §2 色彩令牌（v2：分层材质）
val Bg = Color(0xFF0F1115) // 纯色兜底：播放器黑场、嵌入面板、截断区
/** 页面底纵渐变两端（§2.1 bgGradient）；由 HgBackground 统一消费，页面不手写。 */
val BgTop = Color(0xFF141826)
val BgBottom = Color(0xFF0C0E13)
/** 侧栏底纵渐变两端（§2.1 sideGradient）。 */
val SideTop = Color(0xFF171B26)
val SideBottom = Color(0xFF0F1219)
/** 骨架块底色/扫光高亮（§2.4）；SkeletonBlock 统一消费。 */
val SkeletonBase = Color(0xFF1B1F27)
val SkeletonHi = Color(0xFF272C37)
val Surface = Color(0xFF1A1D24)
val KeySurface = Color(0xFF191D26)
val KeySurfaceDim = Color(0xFF14171E)
val Outline = Color(0xFF2A2E38)
val PosterPlaceholder = Color(0xFF262B36)
val PosterPlaceholderFocused = Color(0xFF2E3442)

val OnBg = Color(0xFFE8EAED)
val OnBgVariant = Color(0xFF9AA0AB)
val OnKey = Color(0xFF8A93A6)
val Dim = Color(0xFF6B7280)
val Faint = Color(0xFF4A5060)

val Primary = Color(0xFFFF7043)
val OnPrimary = Color(0xFFFFFFFF)
val PrimarySoft = Color(0x1FFF7043) // 12%
/** 品牌墨渐变三停（§2.3 inkStops，对角 45°，源自 HG 图标）。只许三处：侧栏字标 / hero 主按钮 / 错误页主按钮。 */
val InkStops = listOf(Color(0xFFFF6B3D), Color(0xFFFF9A4E), Color(0xFFFFD479))

val Success = Color(0xFF4CAF8D)
val SeasonGold = Color(0xFFFFD479)
val Warning = Color(0xFFE8B64C)
val Scrim = Color(0xB3000000) // 70%
val ProgressTrack = Color(0x26FFFFFF) // 15%
