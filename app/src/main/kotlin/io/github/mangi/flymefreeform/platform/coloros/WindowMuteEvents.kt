package io.github.mangi.flymefreeform.platform.coloros

/**
 * 窗口生命周期的极简事件出口：只关心「这个窗口没了」。
 *
 * 长按静音是写进 AudioService 的 per-uid 音量，必须由事件回收（轮询既慢又浪费）。
 * 两个事件源都往这里投：
 *
 * 1. `FlexibleTaskController.exitFlexibleTaskWindowInnerLocked(strategy)`：窗口退场。
 *    退到后台（含被「自动收窗」替换成关闭）才算窗口没了；退成贴边浮标要保持静音；
 * 2. `Task.removeImmediately()` / `removeIfPossible()`：任务被移除（划掉、被杀、被系统清理）。
 *
 * 由 `ColorOsWindowGestures` 在构造时订阅。
 */
internal object WindowMuteEvents {

    @Volatile
    private var listener: ((taskId: Int) -> Unit)? = null

    fun observe(next: ((taskId: Int) -> Unit)?) {
        listener = next
    }

    /** 窗口没了：任务号无效时直接忽略。 */
    fun windowClosed(taskId: Int) {
        if (taskId <= 0) return
        listener?.invoke(taskId)
    }
}
