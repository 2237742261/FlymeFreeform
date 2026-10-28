package io.github.mangi.flymefreeform.framework

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 102 的进程级所有者；所有同步 Binder 调用都串行限制在单一后台线程。 */
internal class FrameworkConnectionRepository(private val context: Context) {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
    private val liveServices = IdentityHashMap<XposedService, Unit>()
    private val diedBeforeBind = IdentityHashMap<XposedService, Unit>()
    private val pendingServices = IdentityHashMap<XposedService, Unit>()
    private val unavailableServices = IdentityHashMap<XposedService, FrameworkConnectionState>()
    private var activeConnection: ActiveConnection? = null
    private val toolOrderLock = Any()
    private var pendingToolOrder: List<String>? = null

    private val mutableState = MutableStateFlow(FrameworkConnectionState())
    val state: StateFlow<FrameworkConnectionState> = mutableState.asStateFlow()

    private val serviceListener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleServiceBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { handleServiceDied(service) }
            }
        }

    fun start() {
        check(started.compareAndSet(false, true)) { "Xposed Service listener is already registered" }
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun setModuleEnabled(enabled: Boolean) =
        updateSettings { it.copy(enabled = enabled) }

    fun setCornerSides(leftEnabled: Boolean, rightEnabled: Boolean) =
        updateSettings {
            val bothDisabled = !leftEnabled && !rightEnabled
            it.copy(
                leftCornerEnabled = if (bothDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothDisabled) true else rightEnabled,
            )
        }

    fun setCornerTriggerRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(rangeDp),
            )
        }

    fun setOutsideTapCloseMode(mode: OutsideTapCloseMode) =
        updateSettings { it.copy(outsideTapCloseMode = mode) }

    fun setToolOrder(aliases: List<String>) =
        updateSettings { it.copy(toolOrder = aliases.distinct()) }

    /**
     * 面板排序的落盘入口（供清单静态接收器调用，可能在 App 进程刚被拉起时执行）。
     *
     * 先排队，然后最多等 [timeoutMs] 让 LSPosed 服务连接就绪，再真正写入；
     * 连接还没就绪时不能像 updateSettings 那样直接 return，否则广播拉起进程的场景会丢数据。
     */
    fun persistToolOrderBlocking(aliases: List<String>, timeoutMs: Long = 8000L): Boolean {
        val ordered = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ordered.isEmpty()) return false
        synchronized(toolOrderLock) { pendingToolOrder = ordered }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activeConnection != null && mutableState.value.canChangeSettings) break
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return flushPendingToolOrder()
    }

    /** 连接就绪后把排队的顺序写进去；返回是否已落盘。 */
    private fun flushPendingToolOrder(): Boolean =
        synchronized(toolOrderLock) {
            val ordered = pendingToolOrder ?: return true
            val connection = activeConnection
            val previous = mutableState.value
            if (connection == null || !previous.canChangeSettings) return false
            val next = previous.settings.copy(toolOrder = ordered)
            if (next.toolOrder == previous.settings.toolOrder) {
                pendingToolOrder = null
                return true
            }
            if (!commitSettings(connection.preferences, next)) {
                isolateFailedConnection(connection, previous)
                return false
            }
            pendingToolOrder = null
            val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
            activeConnection = connection.copy(confirmedState = confirmed)
            mutableState.value = confirmed
            notifyPinnedTargetsChanged(next)
            true
        }

    fun setRadialVibrateEnabled(enabled: Boolean) =
        updateSettings { it.copy(radialVibrateEnabled = enabled) }

    fun setRadialVibrateStrength(strength: Int) =
        updateSettings {
            it.copy(
                radialVibrateStrength = ModulePreferences.coerceRadialVibrateStrength(strength),
            )
        }

    fun setKeepAwakeEnabled(enabled: Boolean) =
        updateSettings { it.copy(keepAwakeEnabled = enabled) }

    fun setHandleSwipeUpToMiniEnabled(enabled: Boolean) =
        updateSettings { it.copy(handleSwipeUpToMiniEnabled = enabled) }

    fun setPauseInLandscape(enabled: Boolean) =
        updateSettings { it.copy(pauseInLandscape = enabled) }

    fun setPauseInGameMode(enabled: Boolean) =
        updateSettings { it.copy(pauseInGameMode = enabled) }

    fun setInnerPinnedTargets(components: List<PinnedTarget>) =
        updateSettings { it.copy(innerPinsSaved = true, innerPinnedTargets = components) }

    fun setPinnedTargets(components: List<PinnedTarget>) =
        updateSettings {
            it.copy(
                pinsSaved = true,
                pinnedTargets =
                    io.github.mangi.flymefreeform.config.PinnedTargetCodec.normalize(components),
            )
        }

    fun requestMissingScopes() {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val current = mutableState.value
            if (!current.canRequestScope) return@execute
            val requested = current.missingScopes.toList()
            val requesting = current.copy(isRequestingScope = true, issue = null)
            activeConnection = connection.copy(confirmedState = requesting)
            mutableState.value = requesting
            try {
                connection.service.requestScope(
                    requested,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            worker.execute { refreshAfterScopeRequest(connection) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            worker.execute { finishScopeRequestWithError(connection) }
                        }
                    },
                )
            } catch (exception: XposedService.ServiceException) {
                finishScopeRequestWithError(connection)
            } catch (exception: UnsupportedOperationException) {
                finishScopeRequestWithError(connection)
            } catch (exception: SecurityException) {
                finishScopeRequestWithError(connection)
            } catch (exception: RuntimeException) {
                finishScopeRequestWithError(connection)
            }
        }
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val previous = mutableState.value
            if (!previous.canChangeSettings) return@execute
            val next = transform(previous.settings)
            if (next == previous.settings) return@execute
            mutableState.value = previous.copy(isUpdating = true, issue = null)
            val committed = commitSettings(connection.preferences, next)
            if (activeConnection?.service !== connection.service) return@execute
            if (committed) {
                val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
                activeConnection = connection.copy(confirmedState = confirmed)
                mutableState.value = confirmed
                // 任何设置变化都要推给各 hook 进程（以前只有少数几项，其余改完必须重启手机）
                if (next != previous.settings) notifyPinnedTargetsChanged(next)
            } else {
                isolateFailedConnection(connection, previous)
            }
        }
    }

    private fun notifyPinnedTargetsChanged(settings: ModuleSettingsSnapshot) {
        val intent = Intent(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
            putExtra(
                ModulePreferences.EXTRA_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(settings.pinnedTargets),
            )
            putExtra(ModulePreferences.EXTRA_PINS_SAVED, settings.pinsSaved)
            putExtra(
                ModulePreferences.EXTRA_INNER_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(
                    settings.innerPinnedTargets,
                    ModulePreferences.MAX_INNER_PINNED_APPS,
                ),
            )
            putExtra(ModulePreferences.EXTRA_INNER_PINS_SAVED, settings.innerPinsSaved)
            putExtra(ModulePreferences.EXTRA_MODULE_ENABLED, settings.enabled)
            putExtra(ModulePreferences.EXTRA_LEFT_CORNER_ENABLED, settings.leftCornerEnabled)
            putExtra(ModulePreferences.EXTRA_RIGHT_CORNER_ENABLED, settings.rightCornerEnabled)
            putExtra(ModulePreferences.EXTRA_CORNER_TRIGGER_RANGE_DP, settings.cornerTriggerRangeDp)
            putExtra(
                ModulePreferences.EXTRA_OUTSIDE_TAP_CLOSE_MODE,
                settings.outsideTapCloseMode.storedValue,
            )
            putExtra(
                ModulePreferences.EXTRA_HANDLE_SWIPE_UP_TO_MINI,
                settings.handleSwipeUpToMiniEnabled,
            )
            putExtra(ModulePreferences.EXTRA_PAUSE_IN_LANDSCAPE, settings.pauseInLandscape)
            putExtra(ModulePreferences.EXTRA_PAUSE_IN_GAME_MODE, settings.pauseInGameMode)
            putExtra(
                ModulePreferences.EXTRA_RADIAL_VIBRATE_ENABLED,
                settings.radialVibrateEnabled,
            )
            putExtra(
                ModulePreferences.EXTRA_RADIAL_VIBRATE_STRENGTH,
                settings.radialVibrateStrength,
            )
            putExtra(ModulePreferences.EXTRA_KEEP_AWAKE_ENABLED, settings.keepAwakeEnabled)
            putExtra(ModulePreferences.EXTRA_TOOL_ORDER, settings.toolOrder.joinToString(","))
        }
        try {
            context.sendBroadcast(intent.setPackage("android"))
            context.sendBroadcast(intent.setPackage("com.coloros.smartsidebar"))
            context.sendBroadcast(intent.setPackage("com.android.systemui"))
            // 桌面进程也装了角落热区，必须一起推（以前缺这一条，角落开关改完得杀 launcher）
            context.sendBroadcast(intent.setPackage("com.android.launcher"))
        } catch (_: RuntimeException) {
            // Remote preference commit remains authoritative if a target process is unavailable.
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (liveServices.remove(service) == null) {
            diedBeforeBind[service] = Unit
            return
        }
        pendingServices.remove(service)
        unavailableServices.remove(service)
        if (activeConnection?.service === service) activeConnection = null
        reconcileServices()
    }

    private fun reconcileServices() {
        if (liveServices.size > 1) {
            mutableState.value = multipleServicesState()
            return
        }
        val service = liveServices.keys.firstOrNull()
        if (service == null) {
            activeConnection = null
            mutableState.value = FrameworkConnectionState()
            return
        }
        activeConnection?.takeIf { it.service === service }?.let { connection ->
            mutableState.value = connection.confirmedState
            return
        }
        unavailableServices[service]?.let { state ->
            mutableState.value = state
            return
        }
        if (pendingServices.remove(service) != null) {
            activateService(service)
            return
        }
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Error,
                issue = FrameworkConnectionIssue.ConnectionFailed,
            )
    }

    private fun activateService(service: XposedService) {
        val result = probeService(service)
        if (result.connection != null) {
            unavailableServices.remove(service)
            activeConnection = result.connection
        } else {
            activeConnection = null
            unavailableServices[service] = result.state
        }
        mutableState.value = result.state
        // 连接刚就绪：把广播排队下来的排序落盘（App 被广播拉起时的关键一步）
        if (result.connection != null) flushPendingToolOrder()
    }

    private fun probeService(service: XposedService): ProbeResult {
        var frameworkName: String? = null
        var frameworkVersion: String? = null
        var apiVersion: Int? = null
        return try {
            apiVersion = service.apiVersion
            frameworkName = service.frameworkName.normalizedMetadata()
            frameworkVersion = service.frameworkVersion.normalizedMetadata()
            val properties = service.frameworkProperties
            when {
                apiVersion < XposedService.API_102 ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.ServiceApiTooOld)
                properties and XposedService.PROP_CAP_REMOTE == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
                properties and XposedService.PROP_CAP_SYSTEM == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.SystemCapabilityMissing)
                else -> {
                    val preferences = service.getRemotePreferences(ModulePreferences.GROUP)
                    val settings = readOrRepairSettings(preferences) ?: return ProbeResult.error(
                        frameworkName,
                        frameworkVersion,
                        apiVersion,
                        FrameworkConnectionIssue.WriteFailed,
                    )
                    val scopes = service.scope.filterTo(linkedSetOf()) { it in FrameworkConnectionState.REQUIRED_SCOPES }
                    ProbeResult.connected(service, preferences, frameworkName, frameworkVersion, apiVersion, settings, scopes)
                }
            }
        } catch (exception: XposedService.ServiceException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: UnsupportedOperationException) {
            ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
        } catch (exception: SecurityException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: RuntimeException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        }
    }

    private fun readOrRepairSettings(preferences: SharedPreferences): ModuleSettingsSnapshot? =
        try {
            ModuleSettingsSnapshot.readFrom(preferences)
        } catch (exception: ClassCastException) {
            ModuleSettingsSnapshot().takeIf { defaults -> commitSettings(preferences, defaults) }
        }

    // KTX edit 返回 Unit；这里必须将远端 commit 的失败结果传回 UI。
    @SuppressLint("UseKtx")
    private fun commitSettings(preferences: SharedPreferences, settings: ModuleSettingsSnapshot): Boolean =
        try {
            settings.writeTo(preferences.edit()).commit()
        } catch (exception: XposedService.ServiceException) {
            false
        } catch (exception: UnsupportedOperationException) {
            false
        } catch (exception: SecurityException) {
            false
        } catch (exception: RuntimeException) {
            false
        }

    private fun isolateFailedConnection(connection: ActiveConnection, previous: FrameworkConnectionState) {
        activeConnection = null
        val failed =
            previous.copy(
                status = FrameworkConnectionStatus.Error,
                isUpdating = false,
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.WriteFailed,
            )
        unavailableServices[connection.service] = failed
        mutableState.value = failed
    }

    private fun refreshAfterScopeRequest(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        pendingServices[connection.service] = Unit
        activeConnection = null
        reconcileServices()
    }

    private fun finishScopeRequestWithError(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        val failed =
            connection.confirmedState.copy(
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.ScopeRequestFailed,
            )
        activeConnection = connection.copy(confirmedState = failed)
        mutableState.value = if (liveServices.size > 1) multipleServicesState() else failed
    }

    private fun multipleServicesState() =
        FrameworkConnectionState(
            status = FrameworkConnectionStatus.Incompatible,
            issue = FrameworkConnectionIssue.MultipleServices,
        )

    private fun String.normalizedMetadata(): String? = trim().take(MAX_METADATA_LENGTH).ifEmpty { null }

    private data class ActiveConnection(
        val service: XposedService,
        val preferences: SharedPreferences,
        val confirmedState: FrameworkConnectionState,
    )

    private data class ProbeResult(val connection: ActiveConnection?, val state: FrameworkConnectionState) {
        companion object {
            fun connected(
                service: XposedService,
                preferences: SharedPreferences,
                frameworkName: String?,
                frameworkVersion: String?,
                apiVersion: Int,
                settings: ModuleSettingsSnapshot,
                scopes: Set<String>,
            ): ProbeResult {
                val state =
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Connected,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        apiVersion = apiVersion,
                        settings = settings,
                        grantedScopes = scopes,
                    )
                return ProbeResult(ActiveConnection(service, preferences, state), state)
            }

            fun failure(name: String?, version: String?, api: Int?, issue: FrameworkConnectionIssue) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Incompatible,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )

            fun error(
                name: String?,
                version: String?,
                api: Int?,
                issue: FrameworkConnectionIssue = FrameworkConnectionIssue.ConnectionFailed,
            ) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Error,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "FlymeFreeform-Service"
        const val MAX_METADATA_LENGTH = 80
    }
}
