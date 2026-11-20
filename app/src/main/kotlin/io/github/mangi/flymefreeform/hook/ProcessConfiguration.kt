package io.github.mangi.flymefreeform.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/** Hook 进程内只读配置快照；任何类型损坏都会让该进程整体失败关闭。 */
internal class ProcessConfiguration(
    private val log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    @Volatile
    var snapshot: ModuleSettingsSnapshot = FAIL_CLOSED
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(ModuleSettingsSnapshot) -> Unit>()
    private var preferences: SharedPreferences? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { changed, _ -> refresh(changed, false) }

    fun start(remotePreferences: SharedPreferences) {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = remotePreferences
        remotePreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh(remotePreferences, true)
    }

    fun observe(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    /** 接收经调用方 UID 校验的运行时推送，避免等待目标进程重新加载。 */
    fun override(next: ModuleSettingsSnapshot) {
        isAvailable = true
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { listener -> listener(next) }
    }

    /** 不依赖跨进程回调/广播：主动重读一次远程偏好，用于「设置改了但进程收不到通知」的机型。 */
    fun reload() {
        val prefs = preferences ?: return
        val before = snapshot
        refresh(prefs, false)
        if (snapshot != before) {
            log(
                Log.INFO,
                "MODULE_CONFIG_RELOADED pins=" + snapshot.pinnedTargets.size +
                    " inner=" + snapshot.innerPinnedTargets.size,
                null,
            )
        }
    }

    private fun refresh(preferences: SharedPreferences, initial: Boolean) {
        val next =
            try {
                ModuleSettingsSnapshot.readFrom(preferences).also { isAvailable = true }
            } catch (exception: ClassCastException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_TYPE_MISMATCH", exception)
                FAIL_CLOSED
            } catch (exception: RuntimeException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_READ_FAILED", exception)
                FAIL_CLOSED
            }
        val changed = next != snapshot
        snapshot = next
        if (initial || changed) {
            if (changed) {
                log(
                    Log.INFO,
                    if (next.enabled && isAvailable) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED",
                    null,
                )
            }
            listeners.forEach { listener -> listener(next) }
        }
    }

    private companion object {
        val FAIL_CLOSED =
            ModuleSettingsSnapshot(
                enabled = false,
                leftCornerEnabled = false,
                rightCornerEnabled = false,
            )
    }
}
