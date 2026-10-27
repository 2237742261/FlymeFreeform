package io.github.mangi.flymefreeform.hook

/**
 * 保存 system_server 里 FlexibleTaskController 的实例，
 * 供各个 hook（双击关闭、后续常亮逻辑）复用。
 */
internal object FlexibleTaskHandle {
    @Volatile
    var controller: Any? = null
}
