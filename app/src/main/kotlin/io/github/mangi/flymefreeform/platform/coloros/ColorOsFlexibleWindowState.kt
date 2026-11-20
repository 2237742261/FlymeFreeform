package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.hook.OemReflection

/**
 * 浮动窗口的系统形态状态（普通小窗 / 迷你窗 / 贴边悬挂）。
 *
 * 两条判据都来自系统自己，模块不自己造状态：
 *
 * 1. `FlexibleTaskController.isTaskInFlexibleState(task, state)`：系统查询任务当前形态的
 *    官方入口，`OutsideTapCloseHookInstaller` 判「普通小窗」用的就是它；
 * 2. `Task.getWrapper().getExtImpl().getFlexibleZoomState()`：`FlexibleCaptionView`
 *    的 `isMiniZoomState` / `isMiniOrReadyState` 读的同一个字段。
 *
 * 两个都拿不到时才退回尺寸判定——「迷你窗就是最小的可见窗口」。
 *
 * 状态取值：`1` = 普通小窗，`2` = 迷你窗，`8` = ready 态（贴边悬挂）。
 */
internal object ColorOsFlexibleWindowState {

    /** 全屏任务：没有进入任何浮动形态。 */
    const val FULLSCREEN = 0

    /** 普通小窗（带标题的标准浮动窗）。 */
    const val ORDINARY = 1

    /** 迷你窗：最小的可见窗口形态。 */
    const val MINI = 2

    /** ready 态：贴边悬挂。 */
    const val READY = 8

    /** 取不到状态（厂商成员缺失、反射被拦）时的返回值。 */
    const val UNREADABLE = -1

    /**
     * 尺寸兜底阈值：宽、高都不超过屏幕的这么多百分比，才算「最小的可见窗口」。
     *
     * 只在两条状态判据都拿不到时使用。正常小窗默认约占屏宽 80%，迷你窗只有屏宽的一小半，
     * 55% 给两侧都留了余量。
     */
    private const val MINI_MAX_SIZE_PERCENT = 55

    /** `Task.getWrapper().getExtImpl().getFlexibleZoomState()`；读不到返回 [UNREADABLE]。 */
    fun zoomStateOf(task: Any?): Int {
        if (task == null) return UNREADABLE
        return runCatching {
            val wrapper =
                OemReflection.requireMethod(task.javaClass, "getWrapper", 0).invoke(task)
                    ?: return@runCatching UNREADABLE
            val ext =
                OemReflection.requireMethod(wrapper.javaClass, "getExtImpl", 0).invoke(wrapper)
                    ?: return@runCatching UNREADABLE
            val method = OemReflection.requireMethod(ext.javaClass, "getFlexibleZoomState", 0)
            method.invoke(ext) as? Int ?: UNREADABLE
        }.getOrDefault(UNREADABLE)
    }

    /**
     * 系统的官方查询：这个任务此刻是不是 [state] 形态。判不了返回 null。
     */
    fun isInState(controller: Any?, task: Any?, state: Int): Boolean? =
        OemReflection.call(controller, "isTaskInFlexibleState", task, state) as? Boolean

    /**
     * 这个窗口此刻是不是迷你窗。
     *
     * 两条系统判据任一命中即算迷你窗；两条都明确说「不是」才判否。都拿不到时才按尺寸：
     * 宽、高都明显小于屏幕才算，普通小窗被用户手动缩小不会被误认成迷你窗。
     *
     * 为什么不能「普通小窗也算」：长按普通小窗是系统自己的「缩成迷你窗」手势，
     * 模块在同一段长按里静音，用户每次想把窗口缩小都会把应用静音。
     */
    fun isMiniWindow(
        apiSaysMini: Boolean?,
        zoomState: Int,
        widthPx: Int,
        heightPx: Int,
        displayWidthPx: Int,
        displayHeightPx: Int,
    ): Boolean {
        if (apiSaysMini == true) return true
        if (zoomState == MINI) return true
        if (apiSaysMini == false) return false
        if (zoomState != UNREADABLE) return false
        if (widthPx <= 0 || heightPx <= 0 || displayWidthPx <= 0 || displayHeightPx <= 0) return false
        return widthPx * 100 <= displayWidthPx * MINI_MAX_SIZE_PERCENT &&
            heightPx * 100 <= displayHeightPx * MINI_MAX_SIZE_PERCENT
    }
}
