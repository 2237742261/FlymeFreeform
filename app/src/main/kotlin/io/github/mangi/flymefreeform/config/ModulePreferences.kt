package io.github.mangi.flymefreeform.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val ACTION_PINNED_TARGETS_CHANGED =
        "com.eta.flymefreeform.action.PINNED_TARGETS_CHANGED"
    const val EXTRA_PINNED_TARGETS = "pinned_targets"
    const val EXTRA_PINS_SAVED = "pins_saved"
    const val EXTRA_INNER_PINNED_TARGETS = "inner_pinned_targets"
    const val EXTRA_INNER_PINS_SAVED = "inner_pins_saved"
    const val EXTRA_MODULE_ENABLED = "module_enabled"
    const val EXTRA_RADIAL_VIBRATE_ENABLED = "radial_vibrate_enabled"
    const val EXTRA_RADIAL_VIBRATE_STRENGTH = "radial_vibrate_strength"
    const val EXTRA_KEEP_AWAKE_ENABLED = "keep_awake_enabled"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_PINNED_TARGETS = "corner_targets_v2"
    /** 内圈固定项（与外圈相互独立）。 */
    const val KEY_INNER_PINNED_TARGETS = "corner_inner_targets_v1"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled_v2"
    const val KEY_KEEP_AWAKE_ENABLED = "keep_awake_enabled_v1"

    /** 轮盘震动：手指滑到应用图标上给一次触感。 */
    const val KEY_RADIAL_VIBRATE_ENABLED = "radial_vibrate_enabled_v1"
    const val KEY_RADIAL_VIBRATE_STRENGTH = "radial_vibrate_strength_v1"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 50
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.Disabled
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = false
    const val DEFAULT_KEEP_AWAKE_ENABLED = false
    const val DEFAULT_RADIAL_VIBRATE_ENABLED = false
    const val DEFAULT_RADIAL_VIBRATE_STRENGTH = 60
    const val MIN_RADIAL_VIBRATE_STRENGTH = 10
    const val MAX_RADIAL_VIBRATE_STRENGTH = 100

    fun coerceRadialVibrateStrength(value: Int): Int =
        value.coerceIn(MIN_RADIAL_VIBRATE_STRENGTH, MAX_RADIAL_VIBRATE_STRENGTH)
    const val DEFAULT_PAUSE_IN_LANDSCAPE = true
    const val DEFAULT_PAUSE_IN_GAME_MODE = true
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MAX_OUTER_PINNED_APPS = 6
    const val MAX_INNER_PINNED_APPS = 5
    const val MAX_PINNED_APPS = MAX_OUTER_PINNED_APPS + MAX_INNER_PINNED_APPS

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)
}
