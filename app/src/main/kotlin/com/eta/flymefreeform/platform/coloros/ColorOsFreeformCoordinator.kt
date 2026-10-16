package com.eta.flymefreeform.platform.coloros

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import com.eta.flymefreeform.config.ConfigSnapshotCodec
import com.eta.flymefreeform.config.ModulePreferences
import com.eta.flymefreeform.config.SharedSettings
import java.util.ArrayDeque
import com.eta.flymefreeform.config.ToolCatalogCodec
import com.eta.flymefreeform.config.ModuleSettingsSnapshot
import com.eta.flymefreeform.config.SharedStateProtocol
import com.eta.flymefreeform.gesture.AdaptiveCornerGestureConfig
import com.eta.flymefreeform.gesture.CornerGestureConfig
import com.eta.flymefreeform.gesture.CornerGestureEngine
import com.eta.flymefreeform.gesture.CornerTriggerRegion
import com.eta.flymefreeform.gesture.GestureAction
import com.eta.flymefreeform.hook.ConfigPushReceiver
import com.eta.flymefreeform.hook.ModuleEnvironmentState
import com.eta.flymefreeform.hook.ProcessConfiguration
import com.eta.flymefreeform.window.CornerRadialOverlayView
import com.eta.flymefreeform.window.OverlaySafeInsets
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** system_server 中的唯一长期所有者：WMS 指针监听、Overlay、目录缓存与启动适配。 */
internal class ColorOsFreeformCoordinator(
    private val controller: Any,
    private val classLoader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val environmentState: ModuleEnvironmentState,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) : CornerRadialOverlayView.Listener {
    private val context = readField(controller, "mContext") as Context
    private val handler = Handler(Looper.getMainLooper())
    private val catalogExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, CATALOG_THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gestureEngine = CornerGestureEngine()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val launcher = ColorOsFreeformLauncher(context)
    private val sidebar = ColorOsSidebarClient(context, handler, logger)
    @Volatile
    private var latestToolCatalog: String? = null

    /** 最近小窗内存列表（头部最新）；跨重启由配置播种，跨进程由 Settings.Global 供读。 */
    private val recentFreeformEntries = ArrayDeque<ComponentName>()

    /** 最近一次指针位置；工具执行时作为 PageRout 点击坐标传给侧边栏。 */
    @Volatile
    private var latestPointerX = 0f

    @Volatile
    private var latestPointerY = 0f

    private val appCatalog =
        ColorOsAppCatalog(
            context,
            catalogExecutor,
            logger,
            toolCatalog = { latestToolCatalog ?: configuration.readToolCatalog() },
        ) { snapshot ->
            handler.post {
                // 后台任务完成时配置可能已再次变化，旧结果不得覆盖新外观。
                if (snapshot.matches(lastSettings)) {
                    catalogSnapshot = snapshot
                    overlay?.updateRadialAppearance(snapshot)
                }
            }
        }
    init {
        // 侧边栏直发 App 的广播会被 ColorOS 后台启动管控拦截，改经 system_server 转发。
        sidebar.onToolCatalog = ::publishToolCatalogToApp
        // 「最近小窗」点击：侧边栏无法自建小窗，转回 system_server 走既有启动链路。
        sidebar.onRecentComponent = { component ->
            handler.post { publishRecentFreeform(component) }
        }
        sidebar.onLaunchComponent = { component ->
            handler.post {
                if (isGestureEnvironmentAllowed()) {
                    launchCommittedApp(RadialAppEntry(component, component.className, EMPTY_ICON))
                }
            }
        }
    }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (lastSettings.enabled) appCatalog.refresh(lastSettings)
            }
        }

    @Volatile
    private var catalogSnapshot = AppCatalogSnapshot()
    private var pointerListener: Any? = null
    @Volatile
    private var pointerRegistered = false
    @Volatile
    private var pointerGeneration = 0L
    private var overlay: CornerRadialOverlayView? = null
    private var morePanelActive = false
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    private val pointerQueueLock = Any()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS

    fun start() {
        handler.post {
            recentFreeformEntries.addAll(configuration.readRecentFreeform())
            registerToolRequestReceiver()
            registerConfigPushReceiver()
            // 开机主动拉目录：Settings 副本不再依赖"用户先开一次面板"。
            sidebar.requestToolCatalog()
            environmentState.start(context)
            environmentState.observe { applySettings(configuration.snapshot) }
            registerPackageObserver()
            configuration.observe {
                handler.post { applySettings(configuration.snapshot) }
            }
        }
        logger(Log.INFO, "SYSTEM_GESTURE_COORDINATOR_READY", null)
    }

    private fun registerPackageObserver() {
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            // Coordinator 与 system_server 同生命周期；热重载被拒绝，因此只注册一次。
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PACKAGE_OBSERVER_UNAVAILABLE", exception)
        }
    }

    private fun applySettings(settings: ModuleSettingsSnapshot) {
        val selectionChanged =
            settings.pinsSaved != lastSettings.pinsSaved ||
                settings.pinnedComponents != lastSettings.pinnedComponents
        lastSettings = settings
        if (environmentState.isGestureAllowed(refreshKeyguard = true) && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
            val resuming = !pointerRegistered
            registerPointerListener()
            if (resuming || selectionChanged || !catalogSnapshot.matches(settings) || catalogSnapshot.radialApps.isEmpty()) {
                appCatalog.refresh(settings, reloadApps = resuming || selectionChanged || catalogSnapshot.radialApps.isEmpty())
            }
        } else {
            sidebar.cancel()
            unregisterPointerListener()
            activeEnvironmentApproved = false
            activeGestureConfig = null
            gestureEngine.cancel()
            removeOverlay()
        }
    }

    private fun registerPointerListener() {
        if (pointerRegistered) return
        val generation = pointerGeneration
        try {
            val listenerInterface =
                Class.forName(
                    "android.view.WindowManagerPolicyConstants\$PointerEventListener",
                    false,
                    classLoader,
                )
            val listener =
                Proxy.newProxyInstance(classLoader, arrayOf(listenerInterface)) { proxy, method, args ->
                    when (method.name) {
                        "onPointerEvent" -> {
                            val event = args?.firstOrNull() as? MotionEvent
                            if (event != null) enqueuePointerEvent(event, generation)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "FlymeFreeformPointerListener"
                        else -> null
                    }
                }
            val windowManagerService = readWindowManagerService()
            val register = findMethod(windowManagerService.javaClass, "registerPointerEventListener", 2)
            register.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
            pointerListener = listener
            pointerRegistered = true
            logger(Log.INFO, "SYSTEM_POINTER_LISTENER_REGISTERED", null)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_FAILED", exception)
        }
    }

    private fun enqueuePointerEvent(event: MotionEvent, generation: Long) {
        if (!pointerRegistered || generation != pointerGeneration) return
        val copy = QueuedPointerEvent(MotionEvent.obtain(event), generation)
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            val precedingMove =
                synchronized(pointerQueueLock) {
                    pendingMove.also { pendingMove = null }
                }
            handler.post {
                precedingMove?.let(::processPointerEvent)
                processPointerEvent(copy)
            }
            return
        }
        var shouldPost = false
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = copy
            if (!movePosted) {
                movePosted = true
                shouldPost = true
            }
        }
        if (shouldPost) handler.post(::drainPendingMove)
    }

    private fun drainPendingMove() {
        val event =
            synchronized(pointerQueueLock) {
                movePosted = false
                pendingMove.also { pendingMove = null }
            } ?: return
        processPointerEvent(event)
    }

    private fun processPointerEvent(queued: QueuedPointerEvent) {
        latestPointerX = queued.event.x
        latestPointerY = queued.event.y
        val event = queued.event
        try {
            if (queued.generation != pointerGeneration || !pointerRegistered) return
            handlePointerEvent(event)
        } catch (exception: RuntimeException) {
            gestureEngine.cancel()
            removeOverlay()
            unregisterPointerListener()
            logger(Log.ERROR, "SYSTEM_POINTER_PROCESSING_FAILED", exception)
        } finally {
            event.recycle()
        }
    }

    private fun unregisterPointerListener() {
        pointerGeneration++
        clearPendingMove()
        val listener = pointerListener ?: return
        pointerRegistered = false
        try {
            val windowManagerService = readWindowManagerService()
            val unregister = findMethod(windowManagerService.javaClass, "unregisterPointerEventListener", 2)
            unregister.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } finally {
            pointerListener = null
        }
    }

    private fun clearPendingMove() {
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = null
            movePosted = false
        }
    }

    private fun handlePointerEvent(event: MotionEvent) {
        if (morePanelActive) return
        val settings = lastSettings
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activeEnvironmentApproved = isGestureEnvironmentAllowed()
            if (!activeEnvironmentApproved) {
                activeGestureConfig = null
                return
            }
        } else if (!activeEnvironmentApproved) {
            return
        }
        if (overlay != null && !isDynamicEnvironmentAllowed()) {
            activeEnvironmentApproved = false
            cancelActiveGesture()
            return
        }
        val metrics = context.resources.displayMetrics
        val config =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AdaptiveCornerGestureConfig.create(
                    displayWidth = metrics.widthPixels.toFloat(),
                    displayHeight = metrics.heightPixels.toFloat(),
                    touchSlop = touchSlop,
                    density = metrics.density,
                    // @author bomo 热区由设置的形状 + 横/纵长度（三角形）与半径（扇形）决定。
                    triggerZone = CornerTriggerRegion.of(settings, metrics.density),
                    leftEnabled = settings.leftCornerEnabled,
                    rightEnabled = settings.rightCornerEnabled,
                ).also { activeGestureConfig = it }
            } else {
                activeGestureConfig ?: return
            }
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    gestureEngine.down(event.getPointerId(0), event.x, event.y, config)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                    gestureEngine.move(
                        event.getPointerId(index),
                        event.pointerCount,
                        event.getX(index),
                        event.getY(index),
                        config,
                    )
                }
                MotionEvent.ACTION_UP -> gestureEngine.up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()
                else -> if (gestureEngine.isClaimed) gestureEngine.cancel() else GestureAction.Ignore
            }
        when (action) {
            is GestureAction.Activate ->
                showOverlay(
                    side = action.side,
                    x = action.x,
                    y = action.y,
                )
            is GestureAction.Update -> {
                val selected = overlay?.updateGesture(action.x, action.y)
                gestureEngine.setSelection(selected)
            }
            is GestureAction.Commit -> overlay?.finishGesture()
            GestureAction.Cancel -> overlay?.cancelGesture()
            GestureAction.Ignore, GestureAction.PassThrough -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeEnvironmentApproved = false
            activeGestureConfig = null
        }
    }

    private fun showOverlay(
        side: com.eta.flymefreeform.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        removeOverlay()
        morePanelActive = false
        val params = createOverlayParams(focusable = false)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this)
            // @author bomo 键盘高度只留一条诊断日志，**不再参与几何**：
            // 轮盘位置恒定在角落，被键盘遮挡的问题改由窗口层级解决（见 OVERLAY_WINDOW_TYPE）。
            currentImeBottomInset()
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
                radialScalePercent = lastSettings.radialScalePercent,
            )
            overlay = view
            windowManager.addView(view, params)
        } catch (exception: RuntimeException) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", exception)
        } catch (error: LinkageError) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", error)
        }
    }

    private fun handleOverlayFailure(
        view: CornerRadialOverlayView?,
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        gestureEngine.cancel()
        if (view != null) {
            overlay = view
            removeOverlay()
        } else {
            overlay = null
        }
        logOverlayFailure(diagnosticCode, throwable)
    }

    private fun logOverlayFailure(
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayFailureLogAt >= OVERLAY_FAILURE_LOG_INTERVAL_MS) {
            lastOverlayFailureLogAt = now
            logger(Log.WARN, diagnosticCode, throwable)
        }
    }

    override fun onAppCommitted(entry: RadialAppEntry) {
        val generation = pointerGeneration
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
        handler.post {
            if (generation == pointerGeneration) launchCommittedApp(entry)
        }
    }

    private fun launchCommittedApp(entry: RadialAppEntry) {
        if (!isGestureEnvironmentAllowed()) return
        // 工具（小布识屏 / 屏幕翻译等）是侧边栏进程内的 AbsTool，不能当应用启动：
        // 转交侧边栏执行，不创建小窗，因此也不计入「最近小窗」。
        if (ModulePreferences.isToolComponent(entry.component)) {
            if (!isGestureEnvironmentAllowed()) {
                // 环境闸门（横屏/游戏模式/关键系统界面前台）拦截时不触发执行，之前是静默的。
                logger(Log.WARN, "TOOL_LAUNCH_ENV_BLOCKED ${entry.component.className}", null)
                return
            }
            logger(
                    Log.DEBUG,
                    "TOOL_LAUNCH_ATTEMPT ${entry.component.className} @${latestPointerX.toInt()},${latestPointerY.toInt()}",
                    null,
                )
            if (!sidebar.runTool(entry.component.className, latestPointerX, latestPointerY)) {
                logger(Log.WARN, "TOOL_LAUNCH_REJECTED ${entry.component.className}", null)
            }
            return
        }
        when (val result = launcher.launch(entry.component)) {
            FreeformLaunchResult.Started -> publishRecentFreeform(entry.component)
            FreeformLaunchResult.TargetUnavailable ->
                logger(Log.WARN, "FREEFORM_LAUNCH_TARGET_UNAVAILABLE", null)
            is FreeformLaunchResult.Failed ->
                logger(Log.WARN, result.diagnosticCode, result.cause)
        }
    }

    /**
     * Hook 进程的框架远端配置是只读的（实测 `edit()` 抛 `UnsupportedOperationException`），
     * 因此「最近小窗」经广播交给模块 App 落盘，侧边栏进程随后按只读方式读取渲染。
     */
    /**
     * 把侧边栏工具目录转发给模块 App。
     * system_server 发出的广播不受厂商「后台启动管控」限制（实测侧边栏进程会被拦），
     * App 收到后落盘，设置界面的工具列表与扇形的工具图标都读这份数据。
     */
    /**
     * App 打开设置时会广播索要工具目录（被动中继可能赶上 App 冷启动被厂商启动策略延迟），
     * 这里注册接收器：收到请求就回发一次目录广播并刷新 Settings 文本副本。
     */
    private fun registerToolRequestReceiver() {
        try {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        val cached = latestToolCatalog
                        if (cached == null) {
                            // 开机后还没拿到过目录：向侧边栏现拉一次，回包会再走本写入。
                            sidebar.requestToolCatalog()
                            return
                        }
                        SharedSettings.writeToolCatalog(context, stripIcons(cached))
                        broadcastToolCatalog(shrinkIcons(cached))
                        logger(Log.DEBUG, "TOOL_CATALOG_SERVED", null)
                    }
                },
                IntentFilter(SharedStateProtocol.ACTION_REQUEST_TOOLS),
            )
        } catch (exception: Exception) {
            logger(Log.WARN, "TOOL_REQUEST_RECEIVER_FAILED", exception)
        }
    }

    /**
     * @author bomo 接收模块 App 推来的**配置变更**（编解码见 `ConfigSnapshotCodec`）。
     *
     * 本进程读 RemotePreferences 是 `start()` 时的**进程内缓存**，无法自行感知设置改动
     * （见 `ProcessConfiguration.refreshNow()` 注释）；由 App 主动推送是唯一可靠的实时通道。
     * 解码失败时保持原配置不动，只留一条诊断码，不影响既有行为。
     */
    private fun registerConfigPushReceiver() {
        // @author bomo 必须导出：发送方是模块 App（另一个 UID），
        // 非导出接收器收不到跨 UID 广播（旧实现因此从来没生效过，日志里 CONFIG_PUSH_* 恒为 0）。
        ConfigPushReceiver.register(context, configuration, logger)
    }

    /**
     * 收到侧边栏目录：内存留全量（含图标，供扇形渲染）。
     *
     * 下行分两路（Settings.Global 单值实测上限 32KB，带图标目录约 60-110KB 必被拒）：
     * - Settings 写**去图标文本版**（约 700B，稳定），作低配兜底；
     * - 定向广播**压缩图标版**（72px WEBP）给 App 落远端配置——system_server 发出的
     *   广播不受厂商后台启动管控拦截（实测侧边栏进程直发会被拦），App 端选择应用
     *   界面的图标从此读取。
     */
    private fun publishToolCatalogToApp(catalog: String) {
        latestToolCatalog = catalog
        SharedSettings.writeToolCatalog(context, stripIcons(catalog))
        broadcastToolCatalog(shrinkIcons(catalog))
        logger(Log.DEBUG, "TOOL_CATALOG_RELAYED", null)
    }

    /** Settings 侧目录副本：去图标纯文本（`Settings.Global` 单值上限 32KB，实测 60KB 被拒）。 */
    private fun stripIcons(catalog: String): String =
        ToolCatalogCodec.encode(
            ToolCatalogCodec.decode(catalog).map { record -> record.copy(iconPng = null) },
        )

    /** 定向广播目录给模块 App（setPackage 显式指定），App 收到后落远端配置。 */
    private fun broadcastToolCatalog(catalog: String) {
        runCatching {
            context.sendBroadcast(
                Intent(SharedStateProtocol.ACTION)
                    .setPackage(SharedStateProtocol.MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(SharedStateProtocol.EXTRA_KIND, SharedStateProtocol.KIND_TOOL_CATALOG)
                    .putExtra(SharedStateProtocol.EXTRA_CATALOG, catalog),
            )
        }.onSuccess {
            logger(Log.DEBUG, "TOOL_CATALOG_BROADCAST", null)
        }.onFailure { exception ->
            logger(Log.WARN, "TOOL_CATALOG_BROADCAST_FAILED", exception)
        }
    }

    /**
     * 广播侧的目录副本压缩：图标统一压到 72px WEBP（每枚约 2-3KB，总量 60-110KB），
     * 广播 Intent 与 App 侧远端配置（SharedPreferences）均可承载。
     */
    private fun shrinkIcons(catalog: String): String =
        ToolCatalogCodec.encode(
            ToolCatalogCodec.decode(catalog).map { record ->
                record.copy(iconPng = record.iconPng?.let(::shrinkToWebp))
            },
        )

    private fun shrinkToWebp(bytes: ByteArray): ByteArray? =
        runCatching {
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = Bitmap.createScaledBitmap(source, TOOL_ICON_PX, TOOL_ICON_PX, true)
            java.io.ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.WEBP, 85, stream)
                stream.toByteArray()
            }
        }.getOrNull()

    /**
     * 记录「小窗打开」：内存列表即时更新，Settings.Global 供侧边栏面板实时读取
     * （跨进程广播在 ColorOS 上会被启动管控/延迟策略干扰，已弃用）。
     */
    private fun publishRecentFreeform(component: ComponentName) {
        recentFreeformEntries.remove(component)
        recentFreeformEntries.addFirst(component)
        while (recentFreeformEntries.size > ModulePreferences.MAX_RECENT_FREEFORM) {
            recentFreeformEntries.removeLast()
        }
        SharedSettings.writeRecentFreeform(context, recentFreeformEntries.toList())
    }

    override fun onMorePanelRequested() {
        if (!isGestureEnvironmentAllowed()) {
            removeOverlay()
            return
        }
        val view = overlay ?: return
        morePanelActive = true
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        if (!sidebar.open(
                // @author bomo 用手势真实方位决定面板锚向：原厂 mIsLeft 在自建面板路径下恒为默认值，
                // 会导致右侧呼出也贴左。
                leftSide = view.invokedSide == com.eta.flymefreeform.gesture.CornerSide.Left,
                beforeOpen = {
                    if (overlay === view && lastSettings.enabled && isGestureEnvironmentAllowed()) {
                        view.retainBackdropForPanel()
                        true
                    } else false
                },
                onResult = { result ->
                    if (overlay === view) {
                        if (result == ColorOsSidebarClient.Outcome.Fallback && lastSettings.enabled && isGestureEnvironmentAllowed() &&
                            context.getSystemService(android.os.UserManager::class.java)?.isUserForeground == true
                        ) {
                            view.visibility = android.view.View.VISIBLE
                            showBuiltInMorePanel(view)
                        } else if (result != ColorOsSidebarClient.Outcome.Shown) removeOverlay()
                    }
                },
                onExitStarted = { if (overlay === view) view.beginBackdropExit() },
                onHideBackdrop = { hidden ->
                    if (overlay === view) view.hideBackdropAfterFrame(hidden) else hidden()
                },
                onClosed = { if (overlay === view) removeOverlay() },
            )
        ) removeOverlay()
    }

    private fun showBuiltInMorePanel(view: CornerRadialOverlayView) {
        val params = createOverlayParams(focusable = true)
        try {
            windowManager.updateViewLayout(view, params)
            view.showBuiltInMorePanel()
            view.requestFocus()
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_FOCUS_FAILED", exception)
            removeOverlay()
        }
    }

    override fun onDismissRequested() {
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
    }

    override fun onCleanupFailed(throwable: Throwable) {
        logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
    }

    /**
     * @author bomo 轮盘几何回报（见 `CornerRadialOverlayView.onRadialMetrics`）。
     * `radiusPx` 才是**实际渲染**半径：它已经含 `fitScale` 收口与轮盘缩放百分比，
     * 与 `RadialIconGeometry.BASE_RADIUS_DP` 不是一回事（358dp 曾被屏宽压成 330dp 的观感）。
     */
    override fun onRadialMetrics(radiusPx: Float, iconPx: Float) {
        logger(
            Log.DEBUG,
            "RADIAL_METRICS radiusPx=${radiusPx.toInt()} iconPx=${iconPx.toInt()} " +
                "scalePercent=${lastSettings.radialScalePercent}",
            null,
        )
    }

    /**
     * @author bomo 轮盘落点回报（见 `CornerRadialOverlayView.onRadialPlacement`）。
     * 位置类投诉先看这一行：`origin` 应约等于 (屏宽 − 右 inset, 屏高 − 底 inset)，
     * 即物理右下角（右侧呼出）/ 左下角（左侧呼出）。
     */
    override fun onRadialPlacement(
        originX: Float,
        originY: Float,
        viewWidth: Int,
        viewHeight: Int,
        insets: OverlaySafeInsets,
    ) {
        logger(
            Log.DEBUG,
            "RADIAL_PLACEMENT origin=${originX.toInt()},${originY.toInt()} " +
                "view=${viewWidth}x$viewHeight " +
                "insets=${insets.left.toInt()},${insets.top.toInt()}," +
                "${insets.right.toInt()},${insets.bottom.toInt()}",
            null,
        )
    }

    private fun createOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
        // 非聚焦窗只监听指针（触摸交给 WMS 指针监听链路）；聚焦面板要自己收按键。
        val inputFlags =
            if (focusable) {
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                inputFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformCornerOverlay"
        }
    }

    private fun cancelActiveGesture() {
        activeGestureConfig = null
        gestureEngine.cancel()
        overlay?.cancelGesture()
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        if (sidebar.isPending) sidebar.cancel()
        morePanelActive = false
        val cleanupFailure = view.disposeOverlay()
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 已被系统移除；本地所有权仍需清空。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_REMOVE_FAILED", exception)
        }
        cleanupFailure?.let { throwable ->
            logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
        }
    }

    private fun isGestureEnvironmentAllowed(): Boolean {
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return false
        return !isCriticalSystemUiForeground()
    }

    private fun isDynamicEnvironmentAllowed(): Boolean = environmentState.isGestureAllowed()

    private fun isCriticalSystemUiForeground(): Boolean {
        focusedWindowPackage()?.let { packageName ->
            if (packageName in CRITICAL_PACKAGES) return true
        }
        return try {
            val atms = readField(controller, "mAtms") ?: return false
            val root = readField(atms, "mRootWindowContainer") ?: return false
            val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return false
            val activity =
                findMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return false
            val packageName = readField(activity, "packageName") as? String ?: return false
            packageName in CRITICAL_PACKAGES
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun focusedWindowPackage(): String? =
        try {
            val windowManagerService = readWindowManagerService()
            val root = readField(windowManagerService, "mRoot") ?: return null
            val display =
                findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
            val window = readField(display, "mCurrentFocus") ?: return null
            findMethod(window.javaClass, "getOwningPackage", 0).invoke(window) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun readWindowManagerService(): Any {
        val atms = readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }

    /**
     * @author bomo 输入法底部高度（px）—— **只做诊断，不再参与避让**。
     *
     * 2026-10-16 结论：轮盘位置恒定在屏幕角落，被输入法遮挡的问题改由窗口层级解决
     * （见 [OVERLAY_WINDOW_TYPE]）。这里保留取数只为在日志里留一条
     * `RADIAL_IME_INSET value=… src=…`，方便确认「键盘到底弹了多高」。
     *
     * 两条路径，**必须先问 InsetsState**：
     * 1. 小窗所在 `DisplayContent` 的 raw `InsetsState` 里 `ITYPE_IME` 源（真实键盘高度，
     *    实测 `visibleFrame=[0,1536][1080,2344]` → 808px）；
     * 2. `mImeWindow`（ColorOS 17）/ `mInputMethodWindow`（旧版）的 `getFrame()` ——
     *    ColorOS 17 上这个 frame 是**整块屏幕**（实测 `[0,120][1080,2344]`），
     *    `displayHeight - frame.top` 会得到 2224px 的假值，只能当兜底。
     */
    private fun currentImeBottomInset(): Float {
        val displayHeight = context.resources.displayMetrics.heightPixels
        val display = displayContentForIme()
        if (display == null) return recordImeInset(0f, "no_display")
        imeInsetFromInsetsState(display, displayHeight)?.let { return recordImeInset(it, "insets_state") }
        imeInsetFromImeWindow(display, displayHeight)?.let { return recordImeInset(it, "ime_window") }
        return recordImeInset(0f, "unavailable")
    }

    private fun recordImeInset(
        value: Float,
        source: String,
    ): Float {
        logger(Log.DEBUG, "RADIAL_IME_INSET value=$value src=$source", null)
        return value
    }

    /** 优先用小窗自己所在的显示区（`FlexibleTaskController.mFlexibleTasks`，与外部点击 hook 同一条路径）。 */
    private fun displayContentForIme(): Any? {
        try {
            val tasks = readField(controller, "mFlexibleTasks")
            if (tasks != null) {
                val task =
                    synchronized(tasks) {
                        (tasks as? Iterable<*>)?.firstOrNull { it != null }
                    }
                if (task != null) {
                    findMethod(task.javaClass, "getDisplayContent", 0).invoke(task)?.let { return it }
                }
            }
        } catch (_: ReflectiveOperationException) {
        } catch (_: RuntimeException) {
        }
        return try {
            val windowManagerService = readWindowManagerService()
            val root = readField(windowManagerService, "mRoot") ?: return null
            findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 路径 1：`DisplayContent` 里的输入法窗口。返回 null = 这条路径不可用，换下一条。 */
    private fun imeInsetFromImeWindow(
        display: Any,
        displayHeight: Int,
    ): Float? =
        try {
            val imeWindow =
                readField(display, "mImeWindow")
                    ?: readField(display, "mInputMethodWindow")
                    ?: return null
            val visible =
                findMethod(imeWindow.javaClass, "isVisible", 0).invoke(imeWindow) as? Boolean
            if (visible != true) return 0f
            val frame =
                findMethod(imeWindow.javaClass, "getFrame", 0).invoke(imeWindow)
                    as? android.graphics.Rect ?: return null
            imeInsetFromFrame(frame, displayHeight)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** 路径 2：显示区 raw `InsetsState` 的 IME 源 frame。 */
    private fun imeInsetFromInsetsState(
        display: Any,
        displayHeight: Int,
    ): Float? =
        try {
            val insetsController =
                findMethod(display.javaClass, "getInsetsStateController", 0).invoke(display)
                    ?: return null
            val insetsState =
                findMethod(insetsController.javaClass, "getRawInsetsState", 0).invoke(insetsController)
                    ?: return null
            val source =
                findMethod(insetsState.javaClass, "getSource", 1)
                    .invoke(insetsState, WindowInsets.Type.ime())
                    ?: return null
            val visible = findMethod(source.javaClass, "isVisible", 0).invoke(source) as? Boolean
            if (visible != true) return 0f
            val frame =
                findMethod(source.javaClass, "getFrame", 0).invoke(source) as? android.graphics.Rect
                    ?: return null
            imeInsetFromFrame(frame, displayHeight)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /**
     * 输入法 frame → 底部避让量（px）。
     *
     * frame 明显不合理（空 / 不在屏幕内）时返回 null，交给下一条路径；
     * 上限取屏幕高度的 [MAX_IME_HEIGHT_RATIO]：键盘不可能那么高。一旦拿到坏 frame，
     * 宁可把轮盘画小一点，也不能让 `fitScale` 把整圈缩成一个点（表现出来就是「只有遮罩没有轮盘」）。
     */
    private fun imeInsetFromFrame(
        frame: android.graphics.Rect,
        displayHeight: Int,
    ): Float? {
        if (frame.height() <= 0) return null
        val inset = (displayHeight - frame.top).toFloat()
        if (inset <= 0f) return null
        return inset.coerceAtMost(displayHeight * MAX_IME_HEIGHT_RATIO)
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long)

    private companion object {
        /** 小窗启动链路不使用图标，占位 1x1 即可。 */
        val EMPTY_ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"

        /** 设置侧目录副本的图标边长（px）。 */
        const val TOOL_ICON_PX = 72
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 输入法避让量的上限（占屏幕高度比例）；坏 frame 时宁可把轮盘画小，也不能让整圈缩成一个点。 */
        const val MAX_IME_HEIGHT_RATIO = 0.55f

        /**
         * @author bomo 轮盘 / 面板的窗口类型 —— **不要改回 `TYPE_APPLICATION_OVERLAY`**。
         *
         * 实机 `dumpsys window windows` 的 `mBaseLayer` 就是 WMS 层级表（layer × 10000）：
         * `APPLICATION_OVERLAY`(2038) = 111000（层 11） **低于** `INPUT_METHOD`(2011) = 131000（层 13），
         * 所以键盘一弹就把贴角的轮盘整圈盖住（用户 2026-10-16 报「只有遮罩没轮盘」）。
         * `TYPE_INPUT_METHOD_DIALOG`(2012) 层 14：刚好压在输入法上面、状态栏（层 15）下面，
         * 不产生 insets 源、不碰输入法本身，是满足「轮盘位置不动又不被键盘盖住」的最小改动。
         */
        val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}
