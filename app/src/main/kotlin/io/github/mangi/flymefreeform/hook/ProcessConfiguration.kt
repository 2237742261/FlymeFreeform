package io.github.mangi.flymefreeform.hook

import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
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

    /**
     * 把设置页广播转成新快照并热生效；**所有接收进程都必须走这一处**。
     *
     * 以前每个进程各手抄一份 copy(...)，各自只抄了自己关心的几项，
     * 结果「角落开关 / 触发范围 / 关闭方式 / 上滑迷你 / 横屏·游戏暂停」这些改了不生效、只能重启手机。
     */
    fun applySettingsBroadcast(intent: Intent): Boolean {
        if (intent.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED) return false
        val current = snapshot
        val next =
            current.copy(
                enabled =
                    intent.getBooleanExtra(ModulePreferences.EXTRA_MODULE_ENABLED, current.enabled),
                leftCornerEnabled =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_LEFT_CORNER_ENABLED,
                        current.leftCornerEnabled,
                    ),
                rightCornerEnabled =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_RIGHT_CORNER_ENABLED,
                        current.rightCornerEnabled,
                    ),
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(
                        intent.getIntExtra(
                            ModulePreferences.EXTRA_CORNER_TRIGGER_RANGE_DP,
                            current.cornerTriggerRangeDp,
                        ),
                    ),
                pinsSaved =
                    intent.getBooleanExtra(ModulePreferences.EXTRA_PINS_SAVED, current.pinsSaved),
                pinnedTargets =
                    if (intent.hasExtra(ModulePreferences.EXTRA_PINNED_TARGETS)) {
                        PinnedTargetCodec.decodeList(
                            intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS).orEmpty(),
                            ModulePreferences.MAX_OUTER_PINNED_APPS,
                        )
                    } else {
                        current.pinnedTargets
                    },
                innerPinsSaved =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_INNER_PINS_SAVED,
                        current.innerPinsSaved,
                    ),
                innerPinnedTargets =
                    if (intent.hasExtra(ModulePreferences.EXTRA_INNER_PINNED_TARGETS)) {
                        PinnedTargetCodec.decodeList(
                            intent.getStringExtra(ModulePreferences.EXTRA_INNER_PINNED_TARGETS)
                                .orEmpty(),
                            ModulePreferences.MAX_INNER_PINNED_APPS,
                        )
                    } else {
                        current.innerPinnedTargets
                    },
                outsideTapCloseMode =
                    if (intent.hasExtra(ModulePreferences.EXTRA_OUTSIDE_TAP_CLOSE_MODE)) {
                        OutsideTapCloseMode.fromStoredValue(
                            intent.getIntExtra(
                                ModulePreferences.EXTRA_OUTSIDE_TAP_CLOSE_MODE,
                                current.outsideTapCloseMode.storedValue,
                            ),
                        )
                    } else {
                        current.outsideTapCloseMode
                    },
                handleSwipeUpToMiniEnabled =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_HANDLE_SWIPE_UP_TO_MINI,
                        current.handleSwipeUpToMiniEnabled,
                    ),
                keepAwakeEnabled =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_KEEP_AWAKE_ENABLED,
                        current.keepAwakeEnabled,
                    ),
                radialVibrateEnabled =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_RADIAL_VIBRATE_ENABLED,
                        current.radialVibrateEnabled,
                    ),
                radialVibrateStrength =
                    ModulePreferences.coerceRadialVibrateStrength(
                        intent.getIntExtra(
                            ModulePreferences.EXTRA_RADIAL_VIBRATE_STRENGTH,
                            current.radialVibrateStrength,
                        ),
                    ),
                toolOrder =
                    if (intent.hasExtra(ModulePreferences.EXTRA_TOOL_ORDER)) {
                        intent
                            .getStringExtra(ModulePreferences.EXTRA_TOOL_ORDER)
                            .orEmpty()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    } else {
                        current.toolOrder
                    },
                pauseInLandscape =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_PAUSE_IN_LANDSCAPE,
                        current.pauseInLandscape,
                    ),
                pauseInGameMode =
                    intent.getBooleanExtra(
                        ModulePreferences.EXTRA_PAUSE_IN_GAME_MODE,
                        current.pauseInGameMode,
                    ),
            )
        override(next)
        return true
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
