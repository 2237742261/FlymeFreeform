package io.github.mangi.flymefreeform.hook

/**
 * 厂商（ColorOS/OPlus）系统类的全限定名。
 *
 * 这些字符串分散在多个 Hook 时改名或换机型容易漏改一处；
 * 统一放这里之后，所有引用点只依赖这一个常量。
 */
internal object OemClasses {
    /** 设置首页所在的系统应用。首页是 androidx 偏好树，因此入口按偏好注入。 */
    const val SETTINGS_PACKAGE = "com.android.settings"

    /** 偏好树基类：`onCreatePreferences` 在 super 调用里完成整棵树。 */
    const val PREFERENCE_FRAGMENT_COMPAT = "androidx.preference.PreferenceFragmentCompat"

    /** 设置首页的 Fragment：覆盖了基类生命周期，插入点必须挂在这个类上。 */
    const val SETTINGS_HOME_FRAGMENT = "com.oplus.settings.feature.homepage.OplusTopLevelSettings"

    const val ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord"
    const val ACTIVITY_STARTER = "com.android.server.wm.ActivityStarter"
    const val ACTIVITY_TASK_MANAGER = "com.android.server.wm.ActivityTaskManagerService"
    const val DISPLAY_CONTENT = "com.android.server.wm.DisplayContent"
    const val FLEXIBLE_TASK_CONTROLLER = "com.android.server.wm.FlexibleTaskController"
    const val FLEXIBLE_TASK_SCALE_MANAGER = "com.android.server.wm.FlexibleTaskScaleManager"
    const val FLEXIBLE_WINDOW_SERVICE = "com.android.server.wm.FlexibleWindowManagerService"
    const val FLEXIBLE_CAPTION_VIEW = "com.android.server.wm.FlexibleCaptionView"
    const val FLOAT_HANDLE_CONTROLLER = "com.android.server.wm.FloatHandleController"
    const val TASK = "com.android.server.wm.Task"
    const val TASK_EXT_IMPL = "com.android.server.wm.TaskExtImpl"
    const val TASK_FRAGMENT = "com.android.server.wm.TaskFragment"
    const val WINDOW_STATE = "com.android.server.wm.WindowState"
    const val WINDOW_TOKEN = "com.android.server.wm.WindowToken"
    const val WINDOW_MANAGER_SERVICE = "com.android.server.wm.WindowManagerService"
    const val ABS_FLEXIBLE_TASK_EXIT_STRATEGY = "com.android.server.wm.AbsFlexibleTaskExitStrategy"
    const val EXIT_FLEXIBLE_TASK_TO_BACK_STRATEGY =
        "com.android.server.wm.ExitFlexibleTaskToBackStrategy"
    const val EXIT_FLEXIBLE_TASK_TO_FLOAT_STRATEGY =
        "com.android.server.wm.ExitFlexibleTaskToFloatStrategy"
}
