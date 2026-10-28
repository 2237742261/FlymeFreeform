package io.github.mangi.flymefreeform

import android.app.Application
import android.util.Log
import io.github.mangi.flymefreeform.apps.LauncherAppRepository
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.apps.SidebarEntryRepository
import io.github.mangi.flymefreeform.framework.FrameworkConnectionRepository

class FlymeFreeformApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    internal lateinit var sidebarEntryRepository: SidebarEntryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository(this).also { it.start() }
        sidebarEntryRepository = SidebarEntryRepository(this)
        launcherAppRepository = LauncherAppRepository(this).also { it.refresh() }
        syncToolOrderFromSettings()
    }

    /**
     * 工具栏顺序的真值在 Settings.Global（system_server 直接读写）。
     * 这里把它同步进 RemotePreferences，保证设置页推配置时不会把顺序覆盖回旧值。
     */
    private fun syncToolOrderFromSettings() {
        Thread {
            try {
                val raw =
                    android.provider.Settings.Global.getString(
                        contentResolver,
                        ModulePreferences.GLOBAL_KEY_TOOL_ORDER,
                    ) ?: return@Thread
                val aliases = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (aliases.isEmpty()) return@Thread
                if (frameworkConnectionRepository.state.value.settings.toolOrder == aliases) {
                    return@Thread
                }
                val saved = frameworkConnectionRepository.persistToolOrderBlocking(aliases)
                Log.i(TAG, "TOOL_ORDER_SYNCED_FROM_SETTINGS count=" + aliases.size + " ok=" + saved)
            } catch (_: RuntimeException) {
                Log.w(TAG, "TOOL_ORDER_SYNC_FAILED")
            }
        }.start()
    }

    /**
     * 「更多」面板里拖动排序完成后，system_server 会发广播过来；
     * 落盘由清单里静态注册的 [ToolOrderReceiver] 负责（进程不在也能被拉起）。
     */

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
