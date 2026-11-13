package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 双击小窗底部横条关闭小窗。
 *
 * 只有「普通小窗（中窗）」才有小白条，双击它才关闭；迷你小窗没有小白条，
 * 双击走 ColorOS 原生的「与前台应用交换」，必须放行。
 *
 * 形态判定用原厂状态 [isTaskInFlexibleState]（1 = 普通小窗），不要自己量高度。
 */
@SuppressLint("PrivateApi")
internal class DoubleTapCaptionCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val captionClass = classLoader.loadClass(CAPTION_VIEW_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            // onDoubleTap 有几个重载，按参数个数从常见到冷门依次试，避免签名猜错就整个 hook 不装。
            val onDoubleTap =
                captionClass.requiredMethodOrNull("onDoubleTap", 1)
                    ?: captionClass.requiredMethodOrNull("onDoubleTap", 2)
                    ?: captionClass.requiredMethodOrNull("onDoubleTap", 0)
                    ?: throw NoSuchMethodException("onDoubleTap")
            // mTask / mFlexibleTaskController 声明在父类 FlexibleCaptionView 上，
            // getDeclaredField 只看本类会直接抛异常（曾经的坑：hook 静默不装）。
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

            module.log(Log.INFO, TAG, "CAPTION_DOUBLE_TAP_TARGET argCount=" + onDoubleTap.parameterCount)
            module
                .hook(onDoubleTap)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.caption_double_tap_close")
                .intercept { chain ->
                    val settings = configuration.snapshot
                    val allowed = environment.isModuleAllowed()
                    val mode = settings.outsideTapCloseMode
                    // 要关窗就不要再跑原厂那套双击逻辑：两次动作叠在一起会起两次「回桌面」转场，
                    // 壁纸放大到 1.2 之后还原那一段被冲掉（实测日志：
                    // OplusWallpaperSurfaceAnimation animation end matrix [1.2...] 后直接 IDLE）。
                    if (allowed && mode == OutsideTapCloseMode.CaptionDoubleTap) {
                        val closed =
                            closeCaptionTask(
                                captionTaskField,
                                captionControllerField,
                                isTaskInFlexibleState,
                                exitFlexibleTask,
                                chain.thisObject,
                            )
                        if (closed) return@intercept null
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "CAPTION_DOUBLE_TAP_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_DOUBLE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_DOUBLE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun closeCaptionTask(
        captionTaskField: Field,
        captionControllerField: Field,
        isTaskInFlexibleState: Method,
        exitFlexibleTask: Method,
        caption: Any?,
    ): Boolean {
        try {
            if (caption == null) return false
            val task = captionTaskField.get(caption) ?: return false
            // caption 自己握着所属的 controller，比全局那个可靠。
            val controller =
                captionControllerField.get(caption) ?: FlexibleTaskHandle.controller ?: return false
            val ordinary =
                try {
                    isTaskInFlexibleState.invoke(controller, task, ORDINARY_FLEXIBLE_STATE) == true
                } catch (_: ReflectiveOperationException) {
                    false
                }
            module.log(Log.INFO, TAG, "CAPTION_STATE_CHECK ordinary=" + ordinary)
            if (!ordinary) return false
            exitFlexibleTask.invoke(controller, task, true, 0, 0)
            module.log(Log.INFO, TAG, "CAPTION_DOUBLE_TAP_CLOSE")
            return true
        } catch (exception: ReflectiveOperationException) {
            logFailure("CAPTION_DOUBLE_TAP_CLOSE_REFLECTION_FAILED", exception)
            return false
        } catch (exception: RuntimeException) {
            logFailure("CAPTION_DOUBLE_TAP_CLOSE_FAILED", exception)
            return false
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private companion object {
        /** 原厂 FlexibleTask 状态：1 = 普通小窗（中窗）。 */
        const val ORDINARY_FLEXIBLE_STATE = 1
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
