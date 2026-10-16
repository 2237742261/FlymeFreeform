package com.eta.flymefreeform.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.eta.flymefreeform.config.OutsideTapCloseMode
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 普通（非迷你）小窗态下，双击底部小横条关闭窗口。
 *
 * 原生 `FlexibleTaskCaptionView.onDoubleTap` 只有迷你态分支（退出迷你、恢复成全屏小窗），
 * 普通态直接 return false —— 双击横条原本毫无反应。这里在普通态接管：复用 ColorOS 自己的
 * `FlexibleTaskController.exitFlexibleTask(task, true, 0, 0)` 关掉小窗；迷你态仍交还原生逻辑。
 */
@SuppressLint("PrivateApi")
internal class HandleDoubleTapCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private val closeLogged = AtomicBoolean(false)
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    /** 原始双击兜底用的「上一次按下」时间与坐标（见 [handleRawDown]）。 */
    @Volatile private var lastRawDownAt = NEVER
    @Volatile private var lastRawDownX = 0f
    @Volatile private var lastRawDownY = 0f

    fun install(classLoader: ClassLoader) {
        try {
            val captionViewClass = classLoader.loadClass(CAPTION_VIEW_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val onDoubleTap =
                captionViewClass
                    .getDeclaredMethod("onDoubleTap", MotionEvent::class.java)
                    .apply { isAccessible = true }
            val access =
                ColorOsCaptionDoubleTapAccess(
                    captionViewClass = captionViewClass,
                    controllerClass = controllerClass,
                    taskClass = taskClass,
                )
            module
                .hook(onDoubleTap)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.handle_double_tap_close")
                .intercept { chain ->
                    val caption = chain.thisObject
                    if (caption == null || !environment.isModuleAllowed()) {
                        return@intercept chain.proceed()
                    }
                    // @author bomo 只有「小窗关闭方式 = 双击小横条」时才接管；
                    // 其余模式（不执行 / 单击关 / 双击关）下横条双击必须保持原生无反应。
                    if (
                        configuration.snapshot.outsideTapCloseMode !=
                            OutsideTapCloseMode.HandleDoubleTap
                    ) {
                        return@intercept chain.proceed()
                    }
                    val ordinaryState =
                        try {
                            !access.isMiniZoomState(caption)
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("DOUBLE_TAP_STATE_REFLECTION_FAILED", exception)
                            false
                        } catch (exception: RuntimeException) {
                            logFailure("DOUBLE_TAP_STATE_FAILED", exception)
                            false
                        }
                    if (!ordinaryState) return@intercept chain.proceed()
                    try {
                        access.closeFlexibleTask(caption)
                    } catch (exception: ReflectiveOperationException) {
                        logFailure("DOUBLE_TAP_CLOSE_REFLECTION_FAILED", exception)
                        return@intercept chain.proceed()
                    } catch (exception: RuntimeException) {
                        logFailure("DOUBLE_TAP_CLOSE_FAILED", exception)
                        return@intercept chain.proceed()
                    }
                    if (closeLogged.compareAndSet(false, true)) {
                        module.logFiltered(Log.DEBUG, TAG, "HANDLE_DOUBLE_TAP_CLOSE_TRIGGERED")
                    }
                    true
                }
            installRawDoubleTapFallback(captionViewClass, access)
            module.logFiltered(Log.INFO, TAG, "HANDLE_DOUBLE_TAP_CLOSE_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "DOUBLE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "DOUBLE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * @author bomo 输入法（IME）显示时「双击小横条关不掉」的兜底。
     *
     * 原因：第一击会被用来收起输入法，IME 退场导致窗口重排，`GestureDetector` 在重排中
     * 收到 CANCEL、双击状态被清空，于是第二次按下不再构成双击。
     * 这里在 `FlexibleCaptionView.dispatchTouchEvent` 上自己数 ACTION_DOWN：
     * 两次按下间隔够短、位置够近即视为双击，直接关闭小窗，不依赖 GestureDetector 的事件连续性。
     * 只在「小窗关闭方式 = 双击小横条」且非迷你态时生效；正常场景只是把关闭提前几十毫秒。
     */
    private fun installRawDoubleTapFallback(
        captionViewClass: Class<*>,
        access: ColorOsCaptionDoubleTapAccess,
    ) {
        try {
            val dispatchTouchEvent =
                captionViewClass.requiredCaptionMethod(
                    name = "dispatchTouchEvent",
                    parameterTypes = arrayOf(MotionEvent::class.java),
                )
            module
                .hook(dispatchTouchEvent)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.raw_double_tap_close")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    if (event != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        handleRawDown(chain.thisObject, event, access)
                    }
                    chain.proceed()
                }
            module.logFiltered(Log.INFO, TAG, "RAW_DOUBLE_TAP_FALLBACK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "RAW_DOUBLE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "RAW_DOUBLE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun handleRawDown(
        caption: Any?,
        event: MotionEvent,
        access: ColorOsCaptionDoubleTapAccess,
    ) {
        if (caption == null || !environment.isModuleAllowed()) return
        if (configuration.snapshot.outsideTapCloseMode != OutsideTapCloseMode.HandleDoubleTap) return
        val now = SystemClock.uptimeMillis()
        val previousAt = lastRawDownAt
        val previousX = lastRawDownX
        val previousY = lastRawDownY
        lastRawDownAt = now
        lastRawDownX = event.x
        lastRawDownY = event.y
        if (previousAt == NEVER || now - previousAt > RAW_DOUBLE_TAP_WINDOW_MS) return
        if (
            kotlin.math.abs(event.x - previousX) > RAW_DOUBLE_TAP_SLOP_PX ||
                kotlin.math.abs(event.y - previousY) > RAW_DOUBLE_TAP_SLOP_PX
        ) {
            return
        }
        val mini =
            try {
                access.isMiniZoomState(caption)
            } catch (exception: ReflectiveOperationException) {
                logFailure("RAW_DOUBLE_TAP_STATE_REFLECTION_FAILED", exception)
                true
            } catch (exception: RuntimeException) {
                logFailure("RAW_DOUBLE_TAP_STATE_FAILED", exception)
                true
            }
        if (mini) return
        try {
            access.closeFlexibleTask(caption)
            module.logFiltered(Log.DEBUG, TAG, "RAW_DOUBLE_TAP_CLOSE_TRIGGERED")
        } catch (exception: ReflectiveOperationException) {
            logFailure("RAW_DOUBLE_TAP_CLOSE_REFLECTION_FAILED", exception)
        } catch (exception: RuntimeException) {
            logFailure("RAW_DOUBLE_TAP_CLOSE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.logFiltered(Log.WARN, TAG, code, throwable)
    }

    private class ColorOsCaptionDoubleTapAccess(
        captionViewClass: Class<*>,
        controllerClass: Class<*>,
        taskClass: Class<*>,
    ) {
        private val taskField = captionViewClass.requiredCaptionField("mTask")
        private val controllerField = captionViewClass.requiredCaptionField("mFlexibleTaskController")
        private val isMiniZoomState = captionViewClass.requiredCaptionMethod("isMiniZoomState", 0)
        private val exitFlexibleTask =
            controllerClass.requiredCaptionMethod(
                name = "exitFlexibleTask",
                parameterTypes =
                    arrayOf(
                        taskClass,
                        Boolean::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
            )

        fun isMiniZoomState(caption: Any): Boolean = isMiniZoomState.invokeCaption(caption) == true

        fun closeFlexibleTask(caption: Any) {
            val task = taskField.get(caption) ?: return
            val controller = controllerField.get(caption) ?: return
            exitFlexibleTask.invokeCaption(controller, task, true, 0, 0)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 原始双击判定：两次按下的最大间隔与最大位移（与系统 GestureDetector 同量级）。 */
        const val RAW_DOUBLE_TAP_WINDOW_MS = 300L
        const val RAW_DOUBLE_TAP_SLOP_PX = 120f
        const val NEVER = 0L
    }
}

private fun Class<*>.requiredCaptionField(name: String): Field {
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

private fun Class<*>.requiredCaptionMethod(name: String, parameterCount: Int): Method {
    var current: Class<*>? = this
    while (current != null) {
        val match = current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
        if (match != null) return match.apply { isAccessible = true }
        current = current.superclass
    }
    throw NoSuchMethodException(name)
}

private fun Class<*>.requiredCaptionMethod(name: String, parameterTypes: Array<Class<*>>): Method {
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

private fun Method.invokeCaption(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
