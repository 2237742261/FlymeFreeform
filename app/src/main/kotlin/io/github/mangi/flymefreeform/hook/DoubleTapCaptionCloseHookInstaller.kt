package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

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
    private var lastTapAt = 0L
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
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
            val isTaskInFlexibleState =
                controllerClass.requiredMethod(
                    name = "isTaskInFlexibleState",
                    parameterTypes = arrayOf(taskClass, Int::class.javaPrimitiveType!!),
                )
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
                    if (event != null) {
                        val settings = configuration.snapshot
                        if (environment.isModuleAllowed() &&
                            settings.outsideTapCloseMode == OutsideTapCloseMode.CaptionDoubleTap
                        ) {
                            observeTap(
                                captionTaskField,
                                captionControllerField,
                                isTaskInFlexibleState,
                                exitFlexibleTask,
                                chain.thisObject,
                                event,
                            )
                        }
                    }
                    result
                }
            module.log(Log.INFO, TAG, "CAPTION_TAP_HOOK_INSTALLED", null)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun observeTap(
        captionTaskField: Field,
        captionControllerField: Field,
        isTaskInFlexibleState: Method,
        exitFlexibleTask: Method,
        caption: Any?,
        event: MotionEvent,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                // 得先是个「点击」：按下没滑动太久、没飘太远。
                if (now - downAt > TAP_MAX_MS) return
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) return
                if (lastTapAt != 0L && now - lastTapAt <= DOUBLE_TAP_MS) {
                    lastTapAt = 0L
                    closeCaptionTask(
                        captionTaskField,
                        captionControllerField,
                        isTaskInFlexibleState,
                        exitFlexibleTask,
                        caption,
                    )
                } else {
                    lastTapAt = now
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                downAt = 0L
                lastTapAt = 0L
            }
        }
    }

    private fun closeCaptionTask(
        captionTaskField: Field,
        captionControllerField: Field,
        isTaskInFlexibleState: Method,
        exitFlexibleTask: Method,
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
            exitFlexibleTask.invoke(controller, task, true, 0, 0)
            module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_CLOSE", null)
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

    private val touchSlop: Float
        get() = TOUCH_SLOP_DP * android.content.res.Resources.getSystem().displayMetrics.density

    private companion object {
        /** 原厂 FlexibleTask 状态：1 = 普通小窗（中窗）。 */
        const val ORDINARY_FLEXIBLE_STATE = 1
        const val TAP_MAX_MS = 300L
        const val DOUBLE_TAP_MS = 350L
        const val TOUCH_SLOP_DP = 24f
        const val TAG = "FlymeFreeform"
        const val CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
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
