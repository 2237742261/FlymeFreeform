package com.eta.flymefreeform.framework

import androidx.compose.runtime.Immutable
import com.eta.flymefreeform.config.ModuleSettingsSnapshot

@Immutable
internal data class FrameworkConnectionState(
    val status: FrameworkConnectionStatus = FrameworkConnectionStatus.Waiting,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int? = null,
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    val grantedScopes: Set<String> = emptySet(),
    val isUpdating: Boolean = false,
    val isRequestingScope: Boolean = false,
    val issue: FrameworkConnectionIssue? = null,
) {
    val moduleEnabled: Boolean
        get() = settings.enabled

    val missingScopes: Set<String>
        get() = REQUIRED_SCOPES - grantedScopes

    /**
     * @author bomo 刻意**不含** [isUpdating]：一次写盘只有几十毫秒，但把它算进来会让
     * 每次改设置时整页控件瞬间变灰再恢复 —— 用户 2026-10-16 报的「界面抖一下」。
     * 并发写入由 repository 的单线程 worker 串行化，不需要靠禁用控件防重入。
     */
    val canChangeSettings: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isRequestingScope &&
                missingScopes.isEmpty()

    val canRequestScope: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isNotEmpty()

    companion object {
        val REQUIRED_SCOPES: Set<String> =
            linkedSetOf(
                "system",
                "com.android.systemui",
                "com.android.launcher",
                "com.coloros.smartsidebar",
            )
    }
}

internal enum class FrameworkConnectionStatus {
    Waiting,
    Connected,
    Incompatible,
    Error,
}

internal enum class FrameworkConnectionIssue {
    ServiceApiTooOld,
    RemoteCapabilityMissing,
    SystemCapabilityMissing,
    MultipleServices,
    ConnectionFailed,
    WriteFailed,
    ScopeRequestFailed,
}
