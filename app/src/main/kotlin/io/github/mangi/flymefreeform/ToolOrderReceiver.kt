package io.github.mangi.flymefreeform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.mangi.flymefreeform.config.ModulePreferences

/**
 * 「更多」面板里拖动排序后的落盘入口。
 *
 * 必须是清单里静态注册的接收器：从轮盘打开面板时 App 进程通常不在，
 * 动态注册的接收器根本不存在，广播会被静默丢弃（这正是「排序不保存」的根因）。
 * 收到后走 goAsync，等 LSPosed 服务连上再落盘，不依赖 App 恰好在前台。
 */
class ToolOrderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModulePreferences.ACTION_TOOL_ORDER_CHANGED) return
        val raw = intent.getStringExtra(ModulePreferences.EXTRA_TOOL_ORDER) ?: return
        val aliases = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (aliases.isEmpty()) return
        Log.i(TAG, "TOOL_ORDER_RECEIVED count=" + aliases.size)
        val application = context.applicationContext as? FlymeFreeformApplication ?: return
        val pending = goAsync()
        Thread {
            try {
                val saved = application.frameworkConnectionRepository.persistToolOrderBlocking(aliases)
                Log.i(TAG, "TOOL_ORDER_SAVED count=" + aliases.size + " ok=" + saved)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
