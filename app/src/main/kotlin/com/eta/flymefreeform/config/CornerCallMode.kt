package com.eta.flymefreeform.config

/**
 * @author bomo 轮盘呼出方式（2026-10-17 用户要求）。
 *
 * 设置页原来是「左下角手势」「右下角手势」两个独立开关，
 * 现在合并成一个下拉选择。**存储仍是既有的两个布尔键**
 * （[ModulePreferences.KEY_LEFT_CORNER_ENABLED] / [ModulePreferences.KEY_RIGHT_CORNER_ENABLED]），
 * 所以 SystemUI 热区、launcher 抢占、system_server 指针监听三处代码一行都不用改。
 */
internal enum class CornerCallMode(
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
) {
    /** 常规：左下与右下两个角落都能呼出（默认）。 */
    Both(leftEnabled = true, rightEnabled = true),

    /** 仅左下角呼出。 */
    LeftOnly(leftEnabled = true, rightEnabled = false),

    /** 仅右下角呼出。 */
    RightOnly(leftEnabled = false, rightEnabled = true),
    ;

    companion object {
        /**
         * 由存储的两个布尔反推选中项。
         * 两个都为 false（老版本允许这种组合）时回到常规，
         * 不会把设置页卡在一个“不存在”的选项上。
         */
        fun resolve(leftEnabled: Boolean, rightEnabled: Boolean): CornerCallMode =
            entries.firstOrNull {
                it.leftEnabled == leftEnabled && it.rightEnabled == rightEnabled
            } ?: Both
    }
}
