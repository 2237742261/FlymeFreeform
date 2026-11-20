package io.github.mangi.flymefreeform.log

import android.util.Log

/** Bridges imported system hooks to the module's LSPosed logger. */
internal object ModuleLog {
    @Volatile private var sink: ((Int, String, Throwable?) -> Unit)? = null

    fun attach(next: (Int, String, Throwable?) -> Unit) { sink = next }
    fun info(code: String) { sink?.invoke(Log.INFO, code, null) }
    fun install(code: String) { sink?.invoke(Log.INFO, code, null) }
    fun warn(code: String, throwable: Throwable? = null) { sink?.invoke(Log.WARN, code, throwable) }
    fun debug(code: String) { sink?.invoke(Log.DEBUG, code, null) }
    fun debug(message: () -> String) { sink?.invoke(Log.DEBUG, message(), null) }
}
