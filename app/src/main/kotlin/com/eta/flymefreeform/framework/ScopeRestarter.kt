package com.eta.flymefreeform.framework

import java.util.concurrent.TimeUnit

/**
 * 重启被模块注入的作用域进程。
 *
 * SystemUI / 桌面 / 侧边栏都是普通应用进程，杀掉后系统会自动拉起，用于让模块改动立即生效。
 * 注意：system_server 里的注入没法这样卸载 —— 它只在手机重启时重建，
 * 所以「关掉模块后彻底失效」仍然需要重启手机。
 */
internal object ScopeRestarter {
    private val TARGET_PACKAGES =
        listOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.coloros.smartsidebar",
        )

    /** @return 是否成功把重启指令交给 su（不代表进程一定已经起来）。 */
    fun restart(): Boolean =
        try {
            val script =
                TARGET_PACKAGES.joinToString(separator = "; ") { pkg ->
                    "pid=\$(pidof $pkg); if [ -n \"\$pid\" ]; then kill -9 \$pid; fi"
                }
            val process =
                ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
            process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (_: Throwable) {
            false
        }

    private const val SU_TIMEOUT_SECONDS = 8L
}
