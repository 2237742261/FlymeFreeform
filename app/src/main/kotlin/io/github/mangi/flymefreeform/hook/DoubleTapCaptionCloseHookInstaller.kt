package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.InputDevice
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import io.github.mangi.flymefreeform.gesture.OutsideTapGestureEngine
import io.github.mangi.flymefreeform.gesture.CaptionHandleRegion
import java.util.WeakHashMap
import io.github.mangi.flymefreeform.platform.coloros.ColorOsTaskImeBoundsAccess

/**
 * 双击小窗底部横条关闭小窗。
 *
 * 关键：挂的是 `FlexibleTaskCaptionView.dispatchTouchEvent`，**不是 `onDoubleTap`**，而且
 * **原厂逻辑照常执行**。以前去截 `onDoubleTap` 并跳过 proceed，会把 ColorOS 自己那个
 * 双击监听器（`android.telephony.Broum`）依赖的字段饿成 null，它继续往下走就拆箱炸，
 * system_server 直接重启（实测中窗双击白条稳定复现）。
 *
 * 这里自己数双击：两次点击间隔 ≤ 350ms、位移在触摸阈值内，并且只对「普通小窗（中窗）」动手；
 * 迷你小窗没有小白条，双击走原生互换，放行。
 */
@SuppressLint("PrivateApi")
internal class DoubleTapCaptionCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private val taps = WeakHashMap<Any, OutsideTapGestureEngine>()
    private var lastImeProbeAt = -FAILURE_LOG_INTERVAL_MS
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val captionClass = classLoader.loadClass(CAPTION_VIEW_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val dispatch =
                captionClass.requiredMethodOrNull("dispatchTouchEvent", 1)
                    ?: throw NoSuchMethodException("dispatchTouchEvent")
            // mTask / mFlexibleTaskController 声明在父类 FlexibleCaptionView 上，
            // getDeclaredField 只看本类会直接抛异常（这个坑踩过）。
            val captionTaskField = captionClass.requiredField("mTask")
            val captionControllerField = captionClass.requiredField("mFlexibleTaskController")
            val closeViewField = try {
                captionClass.requiredField("mCloseView")
            } catch (_: NoSuchFieldException) {
                null
            }
            val lightOpenClose = controllerClass.requiredMethodOrNull("handleCloseEventForLightOpenIfNeed", 1)
            val isTaskInFlexibleState =
                controllerClass.requiredMethod(
                    name = "isTaskInFlexibleState",
                    parameterTypes = arrayOf(taskClass, Int::class.javaPrimitiveType!!),
                )
            val visibleBounds = controllerClass.requiredMethod("getFlexibleTaskVisibleBounds", arrayOf(taskClass))
            val imeBoundsAccess = try {
                ColorOsTaskImeBoundsAccess(taskClass, classLoader.loadClass(TASK_EXT_CLASS), Rect::class.java)
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "CAPTION_IME_BOUNDS_UNAVAILABLE", exception)
                null
            }
            val exitFlexibleTask =
                controllerClass.requiredMethod(
                    name = "exitFlexibleTask",
                    parameterTypes =
                        arrayOf(
                            taskClass,
                            Boolean::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                        ),
                )

            module
                .hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.caption_double_tap_close")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    // 原厂逻辑必须照跑：它在初始化 ColorOS 那个双击监听器依赖的状态。
                    val result = chain.proceed()
                    val caption = chain.thisObject
                    if (event != null && caption != null) {
                        if (!environment.isModuleAllowed() || configuration.snapshot.outsideTapCloseMode != OutsideTapCloseMode.CaptionDoubleTap) return@intercept result
                        try {
                            val task = captionTaskField.get(caption)
                            val controller = captionControllerField.get(caption)
                            val onHandle = task != null && controller != null &&
                                isTaskInFlexibleState.invoke(controller, task, ORDINARY_FLEXIBLE_STATE) == true &&
                                event.pointerCount == 1 &&
                                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                                event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                                event.actionMasked in CAPTION_ACTIONS &&
                                hitsHandle(event, task, controller, visibleBounds, imeBoundsAccess)
                            val close = synchronized(taps) {
                                if (!onHandle) {
                                    taps.remove(caption)?.interrupt()
                                    false
                                } else {
                                    observeTap(taps.getOrPut(caption) {
                                        OutsideTapGestureEngine().apply { updateMode(OutsideTapCloseMode.DoubleTap) }
                                    }, task, event)
                                }
                            }
                            if (close) closeCaptionTask(captionTaskField, captionControllerField,
                                isTaskInFlexibleState, closeViewField, exitFlexibleTask, lightOpenClose, caption)
                        } catch (exception: ReflectiveOperationException) {
                            synchronized(taps) { taps.remove(caption) }
                            logFailure("CAPTION_TOUCH_REFLECTION_FAILED", exception)
                        } catch (exception: RuntimeException) {
                            synchronized(taps) { taps.remove(caption) }
                            logFailure("CAPTION_TOUCH_FAILED", exception)
                        }
                    }
                    result
                }
            configuration.observe { synchronized(taps) { taps.clear() } }
            environment.observe { synchronized(taps) { taps.clear() } }
            module.log(Log.INFO, TAG, "CAPTION_TAP_HOOK_INSTALLED", null)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun hitsHandle(
        event: MotionEvent, task: Any, controller: Any, visibleBounds: Method, imeAccess: ColorOsTaskImeBoundsAccess?,
    ): Boolean {
        val adjusted = try {
            (imeAccess?.get(task) as? Rect)?.let(::Rect)?.takeUnless { it.isEmpty }
        } catch (exception: Exception) {
            logFailure("CAPTION_IME_BOUNDS_READ_FAILED", exception)
            null
        }
        val bounds = adjusted ?: (visibleBounds.invoke(controller, task) as? Rect) ?: return false
        val hit = CaptionHandleRegion.contains(event.rawX, event.rawY, bounds.left, bounds.top,
            bounds.right, bounds.bottom, density)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - lastImeProbeAt >= 2_000L) {
                lastImeProbeAt = now
                module.log(Log.DEBUG, TAG, "CAPTION_HANDLE_HIT source=${if (adjusted != null) "ime" else "normal"} point=${event.rawX},${event.rawY} bounds=$bounds hit=$hit", null)
            }
        }
        return hit
    }

    private fun observeTap(engine: OutsideTapGestureEngine, task: Any, event: MotionEvent): Boolean {
        val id = event.getPointerId(0)
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> engine.begin(task, id, x, y, event.eventTime, captured = true)
            MotionEvent.ACTION_MOVE -> engine.move(task, id, 1, x, y, TOUCH_SLOP_DP * density)
            MotionEvent.ACTION_UP -> return engine.finish(task, id, x, y, event.eventTime,
                TAP_MAX_MS, TOUCH_SLOP_DP * density, DOUBLE_TAP_MS, DOUBLE_TAP_SLOP_DP * density)
        }
        return false
    }

    private fun closeCaptionTask(
        captionTaskField: Field,
        captionControllerField: Field,
        isTaskInFlexibleState: Method,
        closeViewField: Field?,
        exitFlexibleTask: Method,
        lightOpenClose: Method?,
        caption: Any?,
    ) {
        try {
            if (caption == null) return
            val task = captionTaskField.get(caption) ?: return
            val controller =
                captionControllerField.get(caption) ?: FlexibleTaskHandle.controller ?: return
            val ordinary =
                try {
                    isTaskInFlexibleState.invoke(controller, task, ORDINARY_FLEXIBLE_STATE) == true
                } catch (_: ReflectiveOperationException) {
                    false
                }
            if (!ordinary) return
            // Reuse the OEM close listener so it owns the return-to-home transition.
            val closeView = closeViewField?.get(caption) as? View
            if (closeView != null && closeView.isEnabled) {
                val clicked = runCatching { closeView.performClick() }.getOrDefault(false)
                if (clicked) {
                    module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_CLOSE path=native_button", null)
                    return
                }
                val syntheticClick = runCatching {
                    // dispatchTouchEvent expects coordinates local to the View, not window space.
                    val x = closeView.width / 2f
                    val y = closeView.height / 2f
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
                    try {
                        val downHandled = closeView.dispatchTouchEvent(down)
                        val upHandled = closeView.dispatchTouchEvent(up)
                        downHandled && upHandled
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                }.getOrDefault(false)
                if (syntheticClick) {
                    module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_CLOSE path=native_synthetic_click", null)
                    return
                }
            }
            // Keep the OEM light-open state coherent, then use its source-aware exit strategy.
            lightOpenClose?.let { method -> runCatching { method.invoke(controller, caption) } }
            val strategyClose = runCatching {
                val loader = controller.javaClass.classLoader
                val strategyClass = Class.forName(OemClasses.EXIT_FLEXIBLE_TASK_TO_BACK_STRATEGY, false, loader)
                val constructor = strategyClass.declaredConstructors.firstOrNull {
                    it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                } ?: return@runCatching false
                constructor.isAccessible = true
                val strategy = constructor.newInstance(task, true)
                strategyClass.methods.firstOrNull { it.name == "setSourceFrom" && it.parameterTypes.size == 1 }
                    ?.let { it.isAccessible = true; it.invoke(strategy, 1) }
                val base = Class.forName(OemClasses.ABS_FLEXIBLE_TASK_EXIT_STRATEGY, false, loader)
                val inner = controller.javaClass.getMethod("exitFlexibleTaskWindowInnerLocked", base)
                inner.isAccessible = true
                inner.invoke(controller, strategy)
                true
            }.getOrDefault(false)
            if (strategyClose) {
                module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_CLOSE path=oem_strategy", null)
            } else {
                module.log(Log.WARN, TAG, "CAPTION_DOUBLE_TAP_CLOSE failed_no_legacy_fallback", null)
            }
        } catch (exception: ReflectiveOperationException) {
            logFailure("CAPTION_CLOSE_REFLECTION_FAILED", exception)
        } catch (exception: RuntimeException) {
            logFailure("CAPTION_CLOSE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private val density: Float
        get() = android.content.res.Resources.getSystem().displayMetrics.density

    private companion object {
        /** 原厂 FlexibleTask 状态：1 = 普通小窗（中窗）。 */
        const val ORDINARY_FLEXIBLE_STATE = 1
        const val TAP_MAX_MS = 300L
        const val DOUBLE_TAP_MS = 350L
        const val TOUCH_SLOP_DP = 8f
        const val DOUBLE_TAP_SLOP_DP = 24f
        val CAPTION_ACTIONS = setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
        const val TAG = "FlymeFreeform"
        const val CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val TASK_EXT_CLASS = "com.android.server.wm.TaskExtImpl"
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

/** 沿父类链查找字段（原厂把 mTask 这类字段藏在父类上，getDeclaredField 只看本类）。 */
private fun Class<*>.requiredField(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(name)
}

/** 沿父类链按参数个数查找方法，找不到返回 null。 */
private fun Class<*>.requiredMethodOrNull(name: String, parameterCount: Int): Method? {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        }?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    return null
}

/** 沿父类链按精确参数类型查找方法，找不到抛异常（调用方会记 TARGET_UNAVAILABLE）。 */
private fun Class<*>.requiredMethod(name: String, parameterTypes: Array<Class<*>>): Method {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }
    throw NoSuchMethodException(name)
}
