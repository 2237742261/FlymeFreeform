package com.eta.flymefreeform.config

/**
 * 「小窗关闭方式」协议；存储值属于已发布配置格式，不得改作其他含义。
 *
 * @author bomo 声明顺序 = 设置界面下拉框顺序（用户 2026-10-16 定：
 * 不执行 / 双击小横条 / 单击关闭 / 双击关）。`storedValue` 与声明顺序解耦，
 * 新增的 [HandleDoubleTap] 取 3，不动已发布的 0/1/2。
 */
internal enum class OutsideTapCloseMode(val storedValue: Int) {
    /** 不接管任何点击（默认）。 */
    Disabled(0),

    /**
     * 双击底部小横条关闭小窗（`FlexibleTaskCaptionView.onDoubleTap`）；
     * 与窗外点击无关，因此不参与窗外短点击判定。
     */
    HandleDoubleTap(3),
    SingleTap(1),
    DoubleTap(2),
    ;

    /**
     * 是否启用「点击小窗外关闭」。
     *
     * 只有 [SingleTap] / [DoubleTap] 属于窗外短点击模式；[HandleDoubleTap] 走横条，
     * 若也按窗外模式建保护区，会让标题层多占一块触摸区却没有对应功能。
     */
    val closesOnOutsideTap: Boolean
        get() = this == SingleTap || this == DoubleTap

    companion object {
        fun fromStoredValue(value: Int): OutsideTapCloseMode =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
