package io.github.mangi.flymefreeform.hook

import android.util.Log
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion

/**
 * 让「从角落上滑」不再被系统的 Home 手势接走。
 *
 * 系统手势的入口是 com.android.server.wm.SystemGesturesPointerEventListener：
 * 它是 WMS 的指针监听器，既不受 pilferPointers 影响，也不吃 updateInvalidRegion
 * 挖出来的那块洞，所以角落上滑它照样把整屏缩成最近任务。
 *
 * 这里在它的 onPointerEvent 入口把「落在扇形角区里的按下」整条吞掉：拿不到 DOWN，
 * 它就记不下起点，后续 MOVE/UP 也不会触发任何系统手势。扇形本身走模块自己的热区，
 * 不经过这里。
 */
internal class SystemGestureSuppressor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    @Volatile
    private var suppressing = false

    fun install(loader: ClassLoader) {
        val target = runCatching { loader.loadClass(CLASS_NAME) }.getOrNull()
        if (target == null) {
            module.log(Log.WARN, TAG, "SYSTEM_GESTURE_SUPPRESS_TARGET_MISSING", null)
            return
        }
        val method =
            target.declaredMethods.firstOrNull {
                it.name == "onPointerEvent" && it.parameterCount == 1
            }
        if (method == null) {
            module.log(Log.WARN, TAG, "SYSTEM_GESTURE_SUPPRESS_METHOD_MISSING", null)
            return
        }
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.system.gesture_suppress")
            .intercept { chain ->
                val event = chain.getArg(0) as? MotionEvent
                if (event == null) return@intercept chain.proceed()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        suppressing = isInCorner(event.x, event.y)
                        if (suppressing) return@intercept null
                    }
                    MotionEvent.ACTION_MOVE -> if (suppressing) return@intercept null
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        if (suppressing) {
                            suppressing = false
                            return@intercept null
                        }
                    else -> Unit
                }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "SYSTEM_GESTURE_SUPPRESS_INSTALLED", null)
    }

    private fun isInCorner(x: Float, y: Float): Boolean {
        val settings = configuration.snapshot
        if (!settings.enabled) return false
        if (!settings.leftCornerEnabled && !settings.rightCornerEnabled) return false
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        return CornerTriggerRegion.detectSide(
            x = x,
            y = y,
            displayWidth = metrics.widthPixels.toFloat(),
            displayHeight = metrics.heightPixels.toFloat(),
            radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density),
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val CLASS_NAME = "com.android.server.wm.SystemGesturesPointerEventListener"
    }
}
