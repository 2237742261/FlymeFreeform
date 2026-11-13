package io.github.mangi.flymefreeform.hook

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion

/**
 * 在 system_server 的 UAH 输入通道上拦截底角触摸。
 *
 * ColorOS 17.5.4 起，桌面侧的 OplusBaseTouchInteractionService 被移除，手势判定搬到
 * com.android.server.oplus.uah.util.OplusUAwareInputHelper$UahExInputEventReceiver
 * → dealWithInputEvent(InputEvent)；onInputEvent 是接收器回调，两个都钩，一套实现覆盖两版。
 * 落点在触发区内时跳过原方法，UAH 收不到这次触摸，系统的上滑/返回就不会启动。
 */
internal class UahInputSuppressor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    @Volatile
    private var suppressing = false

    fun install(loader: ClassLoader) {
        val type = runCatching { loader.loadClass(RECEIVER_CLASS) }.getOrNull()
        if (type == null) {
            module.log(Log.WARN, TAG, "UAH_RECEIVER_UNAVAILABLE", null)
            return
        }
        val targets =
            type.declaredMethods.filter { candidate ->
                candidate.name in METHOD_NAMES &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0].name == "android.view.InputEvent"
            }
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "UAH_METHOD_UNAVAILABLE", null)
            return
        }
        targets.forEach { method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.uah_input_" + method.name)
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    if (event == null) {
                        suppressing = false
                        return@intercept chain.proceed()
                    }
                    if (!configuration.snapshot.enabled || !environment.isGestureAllowed()) {
                        suppressing = false
                        return@intercept chain.proceed()
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (!insideTrigger(event)) {
                                suppressing = false
                                return@intercept chain.proceed()
                            }
                            suppressing = true
                            null
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            val holding = suppressing
                            suppressing = false
                            if (holding) null else chain.proceed()
                        }
                        else -> if (suppressing) null else chain.proceed()
                    }
                }
            module.log(Log.INFO, TAG, "UAH_INPUT_HOOK_INSTALLED_" + method.name, null)
        }
    }

    private fun insideTrigger(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
        if (event.pointerCount != 1) return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
        val settings = configuration.snapshot
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return false
        return CornerTriggerRegion.detectSide(
            x = event.rawX,
            y = event.rawY,
            displayWidth = width,
            displayHeight = height,
            radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density),
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val RECEIVER_CLASS =
            "com.android.server.oplus.uah.util.OplusUAwareInputHelper\$UahExInputEventReceiver"
        val METHOD_NAMES = setOf("dealWithInputEvent", "onInputEvent")
    }
}
