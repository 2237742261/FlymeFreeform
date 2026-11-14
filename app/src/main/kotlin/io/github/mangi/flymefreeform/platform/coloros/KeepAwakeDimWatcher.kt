package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import java.lang.reflect.Method

/**
 * 屏幕压暗检测（临时常亮用）。
 *
 * 为什么要放在 system_server：SystemUI 是 platform_app 域，读不到
 * /sys/class/backlight/panel0-backlight/brightness（openat 直接 EACCES），
 * 以前只能退回「每 1.5 秒 fork 一次 dumpsys display」，那是全量显示状态 dump、
 * 跑在 system_server 主线程，会把桌面转场动画卡掉帧。
 *
 * 这里直接读系统内存里的显示策略：PowerManagerService → mPowerGroups →
 * PowerGroup.getPolicyLocked()（DisplayPowerRequest.policy）。一次调用几微秒，
 * 不起进程、不读文件、不跨 binder。
 *
 * 检测到「压暗 → 又被点亮」时发一条广播给 SystemUI，由它弹常亮气泡。
 */
internal class KeepAwakeDimWatcher(
    private val context: Context,
    private val configuration: ProcessConfiguration,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val thread = HandlerThread("flyme-dim").apply { start() }
    private val handler = Handler(thread.looper)

    private var running = false
    private var dimmed = false
    private var dimSince = 0L
    private var powerService: Any? = null
    private var powerGroup: Any? = null
    private var policyMethod: Method? = null
    private var policyProbed = false
    private var lastLoggedPolicy = Int.MIN_VALUE
    private var lastLogAt = 0L

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    if (configuration.snapshot.keepAwakeEnabled) {
                        poll()
                    } else if (dimmed) {
                        dimmed = false
                    }
                } catch (exception: RuntimeException) {
                    logThrottled("KEEP_AWAKE_DIM_POLL_FAILED", exception)
                } catch (exception: ReflectiveOperationException) {
                    logThrottled("KEEP_AWAKE_DIM_POLL_REFLECT_FAILED", exception)
                }
                handler.postDelayed(this, pollIntervalMs())
            }
        }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
        logger(Log.INFO, "KEEP_AWAKE_DIM_WATCHER_START", null)
    }

    private fun poll() {
        val policy = displayPolicy() ?: return
        if (policy != lastLoggedPolicy) {
            lastLoggedPolicy = policy
            logger(Log.DEBUG, "KEEP_AWAKE_DISPLAY_POLICY policy=$policy dimmed=$dimmed", null)
        }
        when (policy) {
            POLICY_DIM -> {
                if (!dimmed) {
                    dimmed = true
                    dimSince = SystemClock.uptimeMillis()
                    logger(Log.DEBUG, "KEEP_AWAKE_DIM_ENTERED", null)
                }
            }
            POLICY_BRIGHT -> {
                if (dimmed) {
                    val idle = SystemClock.uptimeMillis() - dimSince
                    dimmed = false
                    logger(Log.INFO, "KEEP_AWAKE_DIM_LIT_AGAIN after=${idle}ms", null)
                    notifyLitAgain()
                }
            }
            else -> if (dimmed && policy != POLICY_DOZE) dimmed = false
        }
    }

    /** 间隔照参考实现：功能关 2s、已压暗 250ms 加密采样、其余 1s。 */
    private fun pollIntervalMs(): Long {
        if (!configuration.snapshot.keepAwakeEnabled) return IDLE_POLL_MS
        return if (dimmed) DIM_POLL_MS else ACTIVE_POLL_MS
    }

    private fun notifyLitAgain() {
        try {
            val intent =
                Intent(ModulePreferences.ACTION_KEEP_AWAKE_DIM_LIT)
                    .setPackage(SYSTEM_UI_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        } catch (exception: RuntimeException) {
            logThrottled("KEEP_AWAKE_DIM_BROADCAST_FAILED", exception)
        }
    }

    /**
     * 当前显示策略：优先 `PowerGroup.getPolicyLocked()`，拿不到就退回读
     * `PowerGroup.mDisplayPowerRequest.policy` 字段。两条都是内存读。
     */
    private fun displayPolicy(): Int? {
        val group = powerGroup() ?: return null
        if (!policyProbed) {
            policyProbed = true
            policyMethod =
                try {
                    findZeroArgMethod(group.javaClass, "getPolicyLocked")
                } catch (exception: NoSuchMethodException) {
                    logger(Log.WARN, "KEEP_AWAKE_POLICY_METHOD_MISSING", exception)
                    null
                }
        }
        policyMethod?.let { method ->
            val value =
                try {
                    method.invoke(group)
                } catch (exception: ReflectiveOperationException) {
                    policyMethod = null
                    logThrottled("KEEP_AWAKE_POLICY_INVOKE_FAILED", exception)
                    null
                }
            (value as? Number)?.let { return it.toInt() }
        }
        // 兜底：直接读 DisplayPowerRequest.policy
        val request = readField(group, "mDisplayPowerRequest") ?: return null
        return (readField(request, "policy") as? Number)?.toInt()
    }

    /**
     * PowerManagerService 在 Android 17 上按 display group 管理电源请求，
     * `getPolicyLocked()` 已经搬到 PowerGroup 上（PowerManagerService 本身没有这个方法）。
     * 这里从 PWS 的 `mPowerGroups`（SparseArray）里取默认组，取不到就遍历字段兜底。
     */
    private fun powerGroup(): Any? {
        powerGroup?.let { return it }
        val service = powerService() ?: return null

        val named = readField(service, "mPowerGroups") as? SparseArray<*>
        named?.let { groups ->
            groups.get(DEFAULT_DISPLAY_GROUP_ID)?.let { return it.also { group -> powerGroup = group } }
            if (groups.size() > 0) {
                groups.valueAt(0)?.let { return it.also { group -> powerGroup = group } }
            }
        }

        var current: Class<*>? = service.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (!SparseArray::class.java.isAssignableFrom(field.type)) continue
                val groups =
                    try {
                        field.isAccessible = true
                        field.get(service) as? SparseArray<*>
                    } catch (_: Exception) {
                        null
                    } ?: continue
                for (index in 0 until groups.size()) {
                    val item = groups.valueAt(index) ?: continue
                    if (item.javaClass.name.endsWith(".PowerGroup")) {
                        return item.also { group -> powerGroup = group }
                    }
                }
            }
            current = current.superclass
        }
        logger(Log.WARN, "KEEP_AWAKE_POWER_GROUP_MISSING", null)
        return null
    }

    /**
     * system_server 里 power 的 binder 是内部类 `PowerManagerService$BinderService`，
     * 本身不是 PWS，要顺着外部类引用回到 PowerManagerService 实例。
     */
    private fun powerService(): Any? {
        powerService?.let { return it }
        val binder =
            try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getMethod("getService", String::class.java)
                getService.invoke(null, Context.POWER_SERVICE)
            } catch (exception: ReflectiveOperationException) {
                logThrottled("KEEP_AWAKE_POWER_SERVICE_UNAVAILABLE", exception)
                null
            } ?: return null
        val resolved = unwrapPowerManagerService(binder)
        if (resolved == null) {
            logger(Log.WARN, "KEEP_AWAKE_POWER_SERVICE_TYPE type=${binder.javaClass.name}", null)
            return null
        }
        return resolved.also { powerService = it }
    }

    private fun unwrapPowerManagerService(instance: Any): Any? {
        if (instance.javaClass.name.endsWith(".PowerManagerService")) return instance
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (!field.type.name.endsWith(".PowerManagerService")) continue
                try {
                    field.isAccessible = true
                    field.get(instance)?.let { outer -> return outer }
                } catch (_: Exception) {
                    // 换下一个字段
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: IllegalAccessException) {
                return null
            }
        }
        return null
    }

    private fun findZeroArgMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun logThrottled(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < LOG_INTERVAL_MS) return
        lastLogAt = now
        logger(Log.WARN, code, throwable)
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val DEFAULT_DISPLAY_GROUP_ID = 0
        const val POLICY_OFF = 0
        const val POLICY_DOZE = 1
        const val POLICY_DIM = 2
        const val POLICY_BRIGHT = 3
        const val IDLE_POLL_MS = 2_000L
        const val ACTIVE_POLL_MS = 1_000L
        const val DIM_POLL_MS = 250L
        const val LOG_INTERVAL_MS = 10_000L
    }
}
