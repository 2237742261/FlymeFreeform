package com.eta.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 仅启用已核对的系统包；包名相同或方法名相似不足以证明兼容。
 *
 * @author bomo 版本判断分级化：
 *  - 快速通道：精确版本（16.14.6）直接放行；
 *  - 宽容通道：侧边栏版本号 ≥ 17.9.2（见 [VERSION_CODE_17_9_2]）一律放行，不绑定 SDK，
 *    覆盖 17.9.2 及其后所有升级；
 *  - 兜底：其余未精确命中时，只要 SDK 不低于 [MIN_VERIFIED_SDK] 仍放行。
 * 放行后的兼容性交由结构校验（服务、Provider、权限、进程名、system flag）
 * 与运行期诊断码兜底，失败即安全降级。
 * 这样侧边栏小版本更新不会让功能整体失效，而真正的不兼容仍会安全降级。
 */
internal object ColorOsSidebarTarget {
    const val PACKAGE_NAME = "com.coloros.smartsidebar"
    const val PROCESS_NAME = "$PACKAGE_NAME:ui"
    const val SERVICE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.UIService"
    const val BIND_ACTION = "com.eta.flymefreeform.action.BIND_ALL_APPS_V1"
    const val REQUIRED_PERMISSION = "oppo.permission.OPPO_COMPONENT_SAFE"
    const val PROVIDER_AUTHORITY = "com.coloros.sidebar"
    const val PREPARE_METHOD = "bindServiceForTransferDock"

    /**
     * @author bomo 最低已验证的 SDK 版本（Android 16 = 36）。
     * 精确版本未命中时，只要不低于该基线仍继续尝试：
     * 侧边栏小版本更新（如 17.9.2 → 17.9.3）不应导致功能整体失效；
     * 真正的不兼容会由结构校验与运行期诊断码暴露并安全降级。
     */
    const val MIN_VERIFIED_SDK = 36

    /**
     * @author bomo 智能侧边栏 17.9.2 的 longVersionCode，作为"宽容放行"的版本下限。
     * 侧边栏版本号编码规则：major × 10^7 + minor × 10^3 + patch，
     * 故 17.9.2 → 17×10^7 + 9×10^3 + 2 = 170009002。
     * 凡 sideBarVersionCode ≥ 此值的侧边栏一律视为适配（覆盖 17.9.2 及其后所有升级）。
     */
    const val VERSION_CODE_17_9_2 = 170009002L

    fun supportedUid(context: Context): Int? {
        val manager = context.packageManager
        return try {
            val info = manager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            // @author bomo：分级版本判断。智能侧边栏更新频率高，精确版本号一旦漂移，
            // 旧实现会直接放弃（返回 null）导致「全部」面板整体不可用。
            // 快速通道：已核对版本的精确匹配（16.14.6）。
            // 宽容通道：侧边栏版本号 ≥ 17.9.2 一律放行（不再要求精确相等、也不再绑定 SDK），
            // 覆盖 17.9.2 及其后所有升级（17.9.3 / 17.10 / 18.x …）；仅当版本低于
            // [VERSION_CODE_17_9_2] 且 SDK 低于 [MIN_VERIFIED_SDK] 时才放弃。
            // 后续结构校验（服务/Provider/权限/进程名/system flag）与运行期诊断码兜底，
            // 失败即安全降级，不崩溃。
            val verified = when {
                Build.VERSION.SDK_INT == 36 && info.longVersionCode == 160014006L && info.versionName == "16.14.6" -> true
                info.longVersionCode >= VERSION_CODE_17_9_2 -> true
                else -> false
            }
            if (!verified && Build.VERSION.SDK_INT < MIN_VERIFIED_SDK) return null
            val service =
                manager.getServiceInfo(
                    ComponentName(PACKAGE_NAME, SERVICE_CLASS),
                    PackageManager.ComponentInfoFlags.of(0),
                )
            val app = service.applicationInfo
            val provider = manager.resolveContentProvider(PROVIDER_AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
                ?: return null
            if (provider.packageName != PACKAGE_NAME ||
                provider.name != "com.coloros.edgepanel.api.SideBarProvider" ||
                provider.applicationInfo.uid != app.uid || !provider.enabled || !provider.exported ||
                provider.readPermission != "com.coloros.sidebar.permission.data" ||
                provider.writePermission != "com.coloros.sidebar.permission.data"
            ) return null
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (!app.enabled || !service.enabled || !service.exported ||
                app.flags and systemFlags == 0 ||
                app.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
                service.permission != REQUIRED_PERMISSION || service.processName != PROCESS_NAME
            ) {
                null
            } else {
                app.uid
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
