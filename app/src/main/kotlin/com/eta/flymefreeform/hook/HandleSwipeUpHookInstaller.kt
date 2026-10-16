package com.eta.flymefreeform.hook

import android.annotation.SuppressLint
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.eta.flymefreeform.gesture.HandleSwipeUpModeRemapper
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 将 ColorOS 普通小窗底部手柄的原生上滑模式重映射为原生 MINI 模式，并放宽原生「快速上滑」门槛。 */
@SuppressLint("PrivateApi")
internal class HandleSwipeUpHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private val remapLogged = AtomicBoolean(false)
    private val thresholdRelaxedLogged = AtomicBoolean(false)
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val scaleManagerClass = classLoader.loadClass(SCALE_MANAGER_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val getGestureMode =
                scaleManagerClass.getDeclaredMethod(
                    "getGestureMode",
                    Double::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Point::class.java,
                    Point::class.java,
                ).apply { isAccessible = true }
            val access =
                ColorOsHandleSwipeAccess(
                    scaleManagerClass = scaleManagerClass,
                    controllerClass = controllerClass,
                    taskClass = taskClass,
                )
            module
                .hook(getGestureMode)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.handle_up_to_mini")
                .intercept { chain ->
                    val originalResult = chain.proceed()
                    val originalMode = (originalResult as? Number)?.toInt() ?: return@intercept originalResult
                    val settings = configuration.snapshot
                    if (!environment.isModuleAllowed() || !settings.handleSwipeUpToMiniEnabled) {
                        return@intercept originalResult
                    }
                    val actionFlag = (chain.getArg(1) as? Number)?.toInt() ?: return@intercept originalResult
                    val ordinary =
                        try {
                            chain.thisObject?.let(access::isOrdinaryFlexibleTask) == true
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("HANDLE_SWIPE_UP_STATE_REFLECTION_FAILED", exception)
                            false
                        } catch (exception: RuntimeException) {
                            logFailure("HANDLE_SWIPE_UP_STATE_FAILED", exception)
                            false
                        }
                    val mappedMode =
                        HandleSwipeUpModeRemapper.remap(
                            enabled = true,
                            actionFlag = actionFlag,
                            originalMode = originalMode,
                            flexibleState = if (ordinary) ORDINARY_FLEXIBLE_STATE else UNKNOWN_STATE,
                        )
                    if (mappedMode != originalMode) {
                        // 打点：只让「刚被本模块改写成迷你模式」的这次手势享受放宽后的门槛。
                        HandleSwipeUpSignal.markRemapped()
                        if (remapLogged.compareAndSet(false, true)) {
                            module.logFiltered(Log.DEBUG, TAG, "HANDLE_SWIPE_UP_MODE_REMAPPED")
                        }
                    }
                    mappedMode
                }
            installThresholdRelaxHook(scaleManagerClass, access)
            module.logFiltered(Log.INFO, TAG, "HANDLE_SWIPE_UP_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "HANDLE_SWIPE_UP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "HANDLE_SWIPE_UP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 放宽「稍微上滑 → 迷你小窗」的手势门槛。
     *
     * `startGestureCornerQuickSwip(F, Rect, I, I, Point)` 只做校验、不改行为：
     * 速度不足或位移不足时返回 -1，上游据此丢弃整个手势。这里在确认本次手势
     * 由 [HandleSwipeUpSignal] 标记为「上滑转迷你」时，把 -1 还原成原本要走的
     * gestureMode，于是轻推一下横条也能进迷你小窗。
     */
    private fun installThresholdRelaxHook(
        scaleManagerClass: Class<*>,
        access: ColorOsHandleSwipeAccess,
    ) {
        try {
            val startCornerQuickSwip =
                scaleManagerClass.getDeclaredMethod(
                    "startGestureCornerQuickSwip",
                    Float::class.javaPrimitiveType,
                    Rect::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Point::class.java,
                ).apply { isAccessible = true }
            module
                .hook(startCornerQuickSwip)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.handle_up_to_mini_threshold")
                .intercept { chain ->
                    val originalResult = chain.proceed()
                    val originalMode = (originalResult as? Number)?.toInt() ?: return@intercept originalResult
                    if (originalMode >= 0) return@intercept originalResult
                    val settings = configuration.snapshot
                    if (!environment.isModuleAllowed() || !settings.handleSwipeUpToMiniEnabled) {
                        return@intercept originalResult
                    }
                    val requestedMode = (chain.getArg(3) as? Number)?.toInt() ?: return@intercept originalResult
                    if (requestedMode != SWITCH_TO_MINI_MODE) return@intercept originalResult
                    // @author bomo 不再依赖「刚被本模块 remap 过」的时间窗：只要这一下确实发生在
                    // **普通小窗态的底部手柄**上、且目标模式是切迷你，就无条件放行原生门槛。
                    // 实测里时间窗会让慢速上滑漏判 —— 用户要的是「上滑就触发」，不是「快速上滑」。
                    val ordinary =
                        try {
                            chain.thisObject?.let(access::isOrdinaryFlexibleTask) == true
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("HANDLE_SWIPE_UP_THRESHOLD_REFLECTION_FAILED", exception)
                            false
                        } catch (exception: RuntimeException) {
                            logFailure("HANDLE_SWIPE_UP_THRESHOLD_STATE_FAILED", exception)
                            false
                        }
                    if (!ordinary) return@intercept originalResult
                    if (thresholdRelaxedLogged.compareAndSet(false, true)) {
                        module.logFiltered(Log.DEBUG, TAG, "HANDLE_SWIPE_UP_THRESHOLD_RELAXED")
                    }
                    requestedMode
                }
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "HANDLE_SWIPE_UP_THRESHOLD_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "HANDLE_SWIPE_UP_THRESHOLD_LINKAGE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.logFiltered(Log.WARN, TAG, code, throwable)
    }

    private class ColorOsHandleSwipeAccess(
        scaleManagerClass: Class<*>,
        controllerClass: Class<*>,
        taskClass: Class<*>,
    ) {
        private val taskField = scaleManagerClass.requiredSwipeField("mTask")
        private val controllerField = scaleManagerClass.requiredSwipeField("mFlexibleTaskController")
        private val isTaskInFlexibleState =
            controllerClass.requiredSwipeMethod(
                name = "isTaskInFlexibleState",
                parameterTypes =
                    arrayOf(
                        taskClass,
                        Int::class.javaPrimitiveType!!,
                    ),
            )

        fun isOrdinaryFlexibleTask(scaleManager: Any): Boolean {
            val task = taskField.get(scaleManager) ?: return false
            val controller = controllerField.get(scaleManager) ?: return false
            return isTaskInFlexibleState.invokeSwipe(controller, task, ORDINARY_FLEXIBLE_STATE) == true
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val SCALE_MANAGER_CLASS = "com.android.server.wm.FlexibleTaskScaleManager"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val ORDINARY_FLEXIBLE_STATE = 1
        const val UNKNOWN_STATE = -1
        const val SWITCH_TO_MINI_MODE = 4
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

private fun Class<*>.requiredSwipeField(name: String): Field {
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

private fun Class<*>.requiredSwipeMethod(name: String, parameterTypes: Array<Class<*>>): Method {
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

private fun Method.invokeSwipe(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
