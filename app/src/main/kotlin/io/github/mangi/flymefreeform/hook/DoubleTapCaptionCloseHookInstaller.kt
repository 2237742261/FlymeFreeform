package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/** 双击普通小窗底部横条关闭：直接结束最上一层小窗任务。 */
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
            val onDoubleTap =
                captionClass.declaredMethods.firstOrNull { it.name == "onDoubleTap" }
                    ?: throw NoSuchMethodException("onDoubleTap")
            onDoubleTap.isAccessible = true
            val getTopZoomTask =
                controllerClass.declaredMethods.firstOrNull {
                    it.name == "getTopZoomTask" && it.parameterCount == 0
                } ?: throw NoSuchMethodException("getTopZoomTask")
            getTopZoomTask.isAccessible = true
            val exitFlexibleTask =
                controllerClass.declaredMethods.firstOrNull {
                    it.name == "exitFlexibleTask" && it.parameterCount == EXIT_PARAMETER_COUNT
                } ?: throw NoSuchMethodException("exitFlexibleTask")
            exitFlexibleTask.isAccessible = true

            module
                .hook(onDoubleTap)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.caption_double_tap_close")
                .intercept { chain ->
                    val result = chain.proceed()
                    val settings = configuration.snapshot
                    val mode = settings.outsideTapCloseMode
                    // 迷你小窗上的双击是 ColorOS 原生的「与前台应用交换」，不能当成关闭：
                    // 以前无条件 exitFlexibleTask，会把刚交换出来的小窗直接关掉（用户报「换完就消失」）。
                    val mini = isMiniCaption(chain.thisObject) || isMiniTopTask(getTopZoomTask)
                    if (
                        environment.isModuleAllowed() &&
                            mode == OutsideTapCloseMode.CaptionDoubleTap &&
                            !mini
                    ) {
                        closeTopFlexibleTask(getTopZoomTask, exitFlexibleTask)
                    } else if (mini) {
                        module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_SKIP_MINI")
                    }
                    result
                }
            module.log(Log.INFO, TAG, "CAPTION_DOUBLE_TAP_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_DOUBLE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_DOUBLE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /** 迷你态判定①：底部横条自己就知道（`FlexibleTaskCaptionView.isMiniZoomState()`）。 */
    private fun isMiniCaption(caption: Any?): Boolean {
        if (caption == null) return false
        return try {
            val method =
                caption.javaClass.methods.firstOrNull {
                    it.name == "isMiniZoomState" && it.parameterCount == 0
                } ?: return false
            method.invoke(caption) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /** 迷你态判定②：问 controller 最上层小窗是否处于迷你态。 */
    private fun isMiniTopTask(getTopZoomTask: Method): Boolean {
        return try {
            val controller = FlexibleTaskHandle.controller ?: return false
            val task = getTopZoomTask.invoke(controller) ?: return false
            val method =
                controller.javaClass.methods.firstOrNull {
                    it.name == "isTaskInMiniFlexible" && it.parameterCount == 1
                } ?: return false
            method.invoke(controller, task) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun closeTopFlexibleTask(getTopZoomTask: Method, exitFlexibleTask: Method) {
        try {
            val controller = FlexibleTaskHandle.controller ?: return
            val task = getTopZoomTask.invoke(controller) ?: return
            exitFlexibleTask.invoke(controller, task, true, 0, 0)
            module.log(Log.DEBUG, TAG, "CAPTION_DOUBLE_TAP_CLOSE")
        } catch (exception: ReflectiveOperationException) {
            logFailure("CAPTION_DOUBLE_TAP_CLOSE_REFLECTION_FAILED", exception)
        } catch (exception: RuntimeException) {
            logFailure("CAPTION_DOUBLE_TAP_CLOSE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val EXIT_PARAMETER_COUNT = 4
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
