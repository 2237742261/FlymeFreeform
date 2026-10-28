package io.github.mangi.flymefreeform.hook

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.coloros.ColorOsSidebarEntryEndpoint
import io.github.mangi.flymefreeform.platform.coloros.ColorOsSidebarTarget
import io.github.mangi.flymefreeform.platform.coloros.SidebarEntryProtocol
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 只注入侧边栏 UI 进程；服务方法照常执行一次，应用/工具的启动仍交给原厂路由。 */
internal class SidebarHookInstaller(private val module: XposedModule, private val configuration: ProcessConfiguration) {
    private var entryEndpoint: ColorOsSidebarEntryEndpoint? = null

    fun install(loader: ClassLoader, applicationInfo: ApplicationInfo) {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val serviceClass = loader.loadClass(ColorOsSidebarTarget.SERVICE_CLASS)
            require(Service::class.java.isAssignableFrom(serviceClass))
            val bind = serviceClass.getDeclaredMethod("onBind", Intent::class.java)
            val unbind = serviceClass.getDeclaredMethod("onUnbind", Intent::class.java)
            val destroy = serviceClass.getDeclaredMethod("onDestroy")
            require(bind.returnType == IBinder::class.java)

            handles += module.hook(bind).setId("flymefreeform.sidebar.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val service = chain.thisObject as? Service
                    val intent = chain.getArg(0) as? Intent
                    if (service != null && intent?.action == SidebarEntryProtocol.BIND_ACTION) {
                        if (entryEndpoint == null && ColorOsSidebarTarget.supportedUid(service) == Process.myUid()) {
                            entryEndpoint = ColorOsSidebarEntryEndpoint(service, loader, configuration, ::log)
                        }
                        entryEndpoint?.takeIf { it.service === service }?.binder ?: original
                    } else original
                }
            handles += module.hook(unbind).setId("flymefreeform.sidebar.unbind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == SidebarEntryProtocol.BIND_ACTION) entryEndpoint?.onUnbound(intent.identifier)
                    original
                }
            handles += module.hook(destroy).setId("flymefreeform.sidebar.destroy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (entryEndpoint?.service === chain.thisObject) {
                        val previous = entryEndpoint
                        entryEndpoint = null
                        previous?.dispose()
                    }
                    chain.proceed()
                }
            log(Log.INFO, "SIDEBAR_ENTRY_BRIDGE_INSTALLED", null)
        } catch (exception: ReflectiveOperationException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "SIDEBAR_ENTRY_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "SIDEBAR_ENTRY_HOOK_INSTALL_FAILED", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "SIDEBAR_ENTRY_LINKAGE_FAILED", error)
        }
    }

    private fun log(priority: Int, code: String, throwable: Throwable?) = module.log(priority, "FlymeFreeform", code, throwable)
}
