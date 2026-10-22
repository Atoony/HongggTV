package com.hgtv.app.ui

/** SPEC §4 返回四级链末端：首页双击退出窗口 1.5s（首按提示，窗口内再按退出）。 */
object BackExit {
    const val WINDOW_MS = 1500L

    fun isExit(nowMs: Long, lastBackMs: Long): Boolean = nowMs - lastBackMs < WINDOW_MS
}
