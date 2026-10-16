package com.eta.flymefreeform.hook

import android.os.SystemClock

/**
 * 记录「普通小窗底部手柄上滑 → 迷你小窗」最近一次模式重映射的时间戳。
 *
 * 原生 `FlexibleTaskScaleManager.startGestureCornerQuickSwip` 自带两道门槛
 * （|yVel| ≥ 屏高×0.3、位移 ≥ 100dp），任一不满足就直接返回 -1 放弃手势。
 * 门槛本身对 ColorOS 其它角落快滑入口是有意义的，所以不能无条件放开；
 * 这里只在「刚刚由本模块把上滑模式改写成迷你模式」的那一次手势内放宽。
 */
internal object HandleSwipeUpSignal {
    private const val NEVER = 0L

    @Volatile
    private var lastRemapUptimeMs = NEVER

    fun markRemapped() {
        lastRemapUptimeMs = SystemClock.uptimeMillis()
    }

    fun isWithinWindow(windowMs: Long): Boolean {
        val last = lastRemapUptimeMs
        return last != NEVER && SystemClock.uptimeMillis() - last <= windowMs
    }
}
