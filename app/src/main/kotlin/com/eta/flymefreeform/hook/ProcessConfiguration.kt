package com.eta.flymefreeform.hook

import android.content.ComponentName
import android.content.SharedPreferences
import android.util.Log
import com.eta.flymefreeform.config.ModulePreferences
import com.eta.flymefreeform.config.ModuleSettingsSnapshot
import com.eta.flymefreeform.config.PinnedComponentCodec
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

    /**
     * @author bomo 硬停开关（见 [applyStopOverride]）。
     * 置位后本进程一切功能立即失效，且**忽略远端偏好**（模块已被停用时它不再可信）。
     */
    @Volatile
    private var stopped = false

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

    /**
     * @author bomo 主动重读远端配置。
     *
     * **⚠️ 当前实际无效（2026-09-23 实测）**：LSPosed 远端偏好的读取是 `start()` 时的
     * **进程内缓存** —— 配置库中 `panel_scale_percent_v1` 已更新而本方法重读仍得旧值，
     * 且 `OnSharedPreferenceChangeListener` 不跨进程回调。因此 Hook 进程内设置的
     * 生效时机 = **Hook 进程重启**（重装 / 服务重连 / 整机重启）。
     * 本调用保留作占位：将来 LSPosed 修复跨进程通知或缓存后，接入方无需改动即自动
     * 变为真·热生效。
     */
    fun refreshNow() {
        val store = preferences ?: return
        refresh(store, false)
    }

    /**
     * @author bomo 应用「外部推来」的配置（模块 App 经广播送来）。
     *
     * 存在的意义：本进程读 `RemotePreferences` 是 `start()` 时的**进程内缓存**（见 [refreshNow]），
     * App 改完配置本进程感知不到。由 App 直接把新配置推过来即可绕开该缓存，实现「改完立即生效」。
     *
     * @param next 调用方已解析好的配置快照
     * @return 是否发生了实际变更（未变化时不打扰监听者）
     */
    fun applyExternal(next: ModuleSettingsSnapshot): Boolean {
        // App 推来的配置是唯一真源：它同时解除硬停（用户可能只是点了「重启作用域」，
        // 也可能是模块重新启用后 App 自动重推）。
        stopped = false
        if (next == snapshot) return false
        snapshot = next
        ModuleLogGate.developerMode = next.developerMode
        isAvailable = true
        log(Log.INFO, if (next.enabled) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED", null)
        log(Log.INFO, "MODULE_STATE_RELOADED_EXTERNAL", null)
        listeners.forEach { listener -> listener(next) }
        return true
    }

    /**
     * @author bomo 硬停：把本进程内的配置整体置为「停用」并通知所有监听者。
     *
     * 与 [applyExternal] 的区别是**不依赖 LSPosed**：模块在管理器里被停用后，
     * 远端偏好不可写、跨进程通知也不再保证，但已注入的代码还活着 —— 只要 App 发一条
     * `SharedStateProtocol.ACTION_MODULE_STOP` 广播，本进程就能立刻自我停用。
     * 恢复的唯一途径是 App 再推一次配置（[applyExternal]）。
     *
     * @return 是否发生了状态变化（已处于硬停时返回 false，不重复打扰监听者）
     */
    fun applyStopOverride(): Boolean {
        if (stopped) return false
        stopped = true
        val stoppedSnapshot =
            snapshot.copy(
                enabled = false,
                leftCornerEnabled = false,
                rightCornerEnabled = false,
            )
        snapshot = stoppedSnapshot
        isAvailable = true
        log(Log.INFO, "MODULE_STATE_DISABLED", null)
        log(Log.INFO, "MODULE_STOP_OVERRIDE_APPLIED", null)
        listeners.forEach { listener -> listener(stoppedSnapshot) }
        return true
    }

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    /**
     * 读取最近小窗应用；未写入或内容损坏时返回空列表。
     * 写入方是模块 App（Hook 进程的远端配置只读），见 `SharedStateProtocol`。
     */
    fun readRecentFreeform(): List<ComponentName> {
        val store = preferences ?: return emptyList()
        val raw =
            runCatching {
                store.getString(ModulePreferences.KEY_RECENT_FREEFORM, "") ?: ""
            }.getOrDefault("")
        return PinnedComponentCodec
            .decodeRaw(raw, ModulePreferences.MAX_RECENT_FREEFORM)
            .mapNotNull(ComponentName::unflattenFromString)
    }

    /** 读取侧边栏工具目录（由模块 App 落盘）；未写入时返回 null。 */
    fun readToolCatalog(): String? {
        val store = preferences ?: return null
        return runCatching {
            store.getString(ModulePreferences.KEY_TOOL_CATALOG, null)
        }.getOrNull()
    }

    private fun refresh(preferences: SharedPreferences, initial: Boolean) {
        // 硬停期间忽略远端偏好：模块已被停用时这份配置不再代表用户意图，
        // 让它覆盖硬停会让「关掉模块后轮盘照旧」重现。
        if (stopped) return
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
        ModuleLogGate.developerMode = next.developerMode
        if (initial || changed) {
            log(
                Log.INFO,
                if (next.enabled && isAvailable) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED",
                null,
            )
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
