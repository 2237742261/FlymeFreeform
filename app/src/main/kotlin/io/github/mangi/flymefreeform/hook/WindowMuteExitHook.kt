package io.github.mangi.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.log.ModuleLog
import io.github.mangi.flymefreeform.platform.coloros.WindowMuteEvents

/** Restore per-app volume when a flexible window exits, except when it becomes a float handle. */
internal class WindowMuteExitHook(private val module: XposedModule) {
    fun install(classLoader: ClassLoader) {
        runCatching {
            val controller = classLoader.loadClass(OemClasses.FLEXIBLE_TASK_CONTROLLER)
            val floatStrategy = classLoader.loadClass(OemClasses.EXIT_FLEXIBLE_TASK_TO_FLOAT_STRATEGY)
            val method = OemReflection.findMethodByArity(controller, "exitFlexibleTaskWindowInnerLocked", 1)
                ?: throw NoSuchMethodException("exitFlexibleTaskWindowInnerLocked")
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.window_mute_exit")
                .intercept { chain ->
                    val strategy = chain.args.firstOrNull()
                    if (strategy != null && !floatStrategy.isInstance(strategy)) {
                        val task = OemReflection.readField(strategy, "mTask")
                        val taskId = OemReflection.readField(task, "mTaskId") as? Int ?: -1
                        WindowMuteEvents.windowClosed(taskId)
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, "FlymeFreeform", "WINDOW_MUTE_EXIT_HOOK_INSTALLED", null)
        }.onFailure { ModuleLog.warn("WINDOW_MUTE_EXIT_HOOK_FAILED", it) }
    }
}
