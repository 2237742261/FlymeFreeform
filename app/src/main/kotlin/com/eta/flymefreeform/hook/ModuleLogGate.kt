package com.eta.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * @author bomo 诊断日志闸门（用户 2026-10-17 定）。
 *
 * 背景：日常使用只需要「关键事件」日志，排查时又要全套诊断输出。于是把日志分两级：
 * - `Log.INFO` 及以上 = **关键事件**（Hook 安装、模块状态、配置应用、错误）—— 永远输出；
 * - `Log.DEBUG` = **诊断**（轮盘/面板几何、图标度量、每次手势与工具调用）—— 只在开发模式打开时输出。
 *
 * 开关就是配置快照里的 `developerMode`（默认关，见 `ModulePreferences.KEY_DEVELOPER_MODE`），
 * 由 [ProcessConfiguration] 在每次应用快照时同步过来，所以改设置经配置推送通道**即时生效**，
 * 不需要重装或重启。
 *
 * 用法：Hook 侧一律走 [logFiltered]，**不要再直接调 `module.log(...)`**。
 */
internal object ModuleLogGate {
    @Volatile
    var developerMode: Boolean = false

    /** 该级别此刻是否允许输出。 */
    fun allows(priority: Int): Boolean = priority != Log.DEBUG || developerMode
}

/** 受 [ModuleLogGate] 约束的日志出口；诊断行在开发模式关闭时静默丢弃。 */
internal fun XposedModule.logFiltered(
    priority: Int,
    tag: String,
    code: String,
    throwable: Throwable? = null,
) {
    if (!ModuleLogGate.allows(priority)) return
    log(priority, tag, code, throwable)
}
