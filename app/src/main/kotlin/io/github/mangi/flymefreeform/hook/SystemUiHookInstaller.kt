package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.platform.coloros.ColorOsSystemUiApplicationTarget
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: SystemUiCornerInputMonitor? = null
    private var keepAwakeMonitor: SystemUiKeepAwakeMonitor? = null

    fun install(classLoader: ClassLoader) {
        try {
            val target =
                ColorOsSystemUiApplicationTarget.resolve { className ->
                    classLoader.loadClass(className)
                } ?: throw NoSuchMethodException("SystemUI application onCreate")
            val onCreate = target.onCreate
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        startInputMonitor(context)
                        startKeepAwakeMonitor(context)
                        registerSettingsReceiver(context)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun startInputMonitor(context: Context) {
        try {
            val monitor = SystemUiCornerInputMonitor(context, module, configuration)
            inputMonitor = monitor
            monitor.start()
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_READY")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    // 设置页的「临时常亮」/「轮盘震动」改动靠这条广播热生效（跨进程远程偏好不会自己回调）。
    private fun registerSettingsReceiver(context: Context) {
        try {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        if (intent?.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED) return
                        if (!intent.hasExtra(ModulePreferences.EXTRA_RADIAL_VIBRATE_STRENGTH)) return
                        val snapshot = configuration.snapshot
                        configuration.override(
                            snapshot.copy(
                                enabled =
                                    intent.getBooleanExtra(
                                        ModulePreferences.EXTRA_MODULE_ENABLED,
                                        snapshot.enabled,
                                    ),
                                keepAwakeEnabled =
                                    intent.getBooleanExtra(
                                        ModulePreferences.EXTRA_KEEP_AWAKE_ENABLED,
                                        snapshot.keepAwakeEnabled,
                                    ),
                                radialVibrateEnabled =
                                    intent.getBooleanExtra(
                                        ModulePreferences.EXTRA_RADIAL_VIBRATE_ENABLED,
                                        snapshot.radialVibrateEnabled,
                                    ),
                                radialVibrateStrength =
                                    ModulePreferences.coerceRadialVibrateStrength(
                                        intent.getIntExtra(
                                            ModulePreferences.EXTRA_RADIAL_VIBRATE_STRENGTH,
                                            snapshot.radialVibrateStrength,
                                        ),
                                    ),
                            ),
                        )
                    }
                },
                IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            module.log(Log.INFO, TAG, "SYSTEMUI_SETTINGS_RECEIVER_READY")
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_SETTINGS_RECEIVER_FAILED", exception)
        }
    }

    private fun startKeepAwakeMonitor(context: Context) {
        try {
            val monitor = SystemUiKeepAwakeMonitor(context, module, configuration)
            keepAwakeMonitor = monitor
            monitor.start()
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "KEEP_AWAKE_MONITOR_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
