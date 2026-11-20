package io.github.mangi.flymefreeform.hook

import io.github.mangi.flymefreeform.log.ModuleLog
import io.github.mangi.flymefreeform.platform.coloros.WindowMuteEvents
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 任务被移除时给长按静音投「窗口没了」事件。
 *
 * 关小窗走的是 `FlexibleTaskController` 的退场入口（事件在
 * [OutsideTapCloseHookInstaller] 里投）；但划掉任务、应用被杀、被系统清理这些路径不走退场，
 * 任务直接从任务栈里消失。静音如果只靠退场事件回收，这些情况就会留成永久静音，
 * 所以在这里补一路：`Task.removeImmediately()`（`removeIfPossible()` 内部也走它）。
 */
internal class WindowMuteHook(private val module: XposedModule) {

    fun install(classLoader: ClassLoader) {
        val taskClass =
            runCatching { classLoader.loadClass(OemClasses.TASK) }.getOrNull()
                ?: run {
                    ModuleLog.warn("WINDOW_MUTE_TASK_UNAVAILABLE", null)
                    return
                }
        var installed = 0
        for ((name, arity) in REMOVE_METHODS) {
            val method =
                runCatching {
                    taskClass.declaredMethods.firstOrNull {
                        it.name == name && it.parameterCount == arity
                    } ?: taskClass.methods.firstOrNull {
                        it.name == name && it.parameterCount == arity
                    }
                }.getOrNull() ?: continue
            installOn(method) || continue
            installed++
        }
        if (installed == 0) {
            ModuleLog.warn("WINDOW_MUTE_REMOVE_UNAVAILABLE", null)
        } else {
            ModuleLog.install("WINDOW_MUTE_REMOVAL_HOOK_INSTALLED_n" + installed)
        }
    }

    private fun installOn(method: java.lang.reflect.Method): Boolean =
        runCatching {
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.system.window_mute_removal_" + method.name + "_" + method.parameterCount)
                .intercept { chain ->
                    val result = chain.proceed()
                    val taskId = OemReflection.readField(chain.thisObject, "mTaskId") as? Int ?: -1
                    WindowMuteEvents.windowClosed(taskId)
                    result
                }
            true
        }.getOrElse { exception ->
            ModuleLog.warn("WINDOW_MUTE_REMOVAL_HOOK_FAILED_" + method.name, exception)
            false
        }

    private companion object {
        /** Task 的移除入口；ColorOS 16 是 0 参的 removeImmediately。 */
        val REMOVE_METHODS =
            listOf(
                "removeImmediately" to 0,
                "removeImmediately" to 1,
                "removeIfPossible" to 0,
            )
    }
}
