package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import io.github.mangi.flymefreeform.gesture.PointerStreamGate
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.window.CornerRadialOverlayView
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
    private val appCatalog =
        ColorOsAppCatalog(context, catalogExecutor, logger) { snapshot ->
            handler.post {
                // 后台任务完成时配置可能已再次变化，旧结果不得覆盖新外观。
                if (snapshot.matches(lastSettings)) {
                    catalogSnapshot = snapshot
                    overlay?.updateRadialAppearance(snapshot)
                }
            }
        }
    private val entryCatalog = ColorOsEntryCatalogBroker(context, handler, logger) { entries ->
        appCatalog.updateSidebarEntries(entries, lastSettings)
    }
    private var entryLaunchGeneration = 0L
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                entryCatalog.invalidate()
                if (lastSettings.enabled) {
                    appCatalog.refresh(lastSettings)
                    if (lastSettings.hasSidebarPins) entryCatalog.refresh()
                }
            }
        }
    private val pinnedTargetsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ||
                    sentFromUid != moduleUid()
                ) return
                configuration.applySettingsBroadcast(intent)
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
    /** 面板模式下窗口/面板占用的屏幕矩形；用于判断「点到面板外」。 */
    private var morePanelBounds: android.graphics.Rect? = null
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    /** Settings.Global 里持久化的工具顺序；非空时优先于 App 推送的 toolOrder。 */
    @Volatile
    private var persistentToolOrder: List<String> = emptyList()
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    private val pointerQueueLock = Any()
    private val pointerStreamGate = PointerStreamGate()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS

    fun start() {
        handler.post {
            persistentToolOrder =
                readToolOrderFromFile().ifEmpty { readToolOrderFromSettings() }
            if (persistentToolOrder.isNotEmpty()) {
                logger(
                    Log.INFO,
                    "TOOL_ORDER_RESTORED count=" + persistentToolOrder.size,
                    null,
                )
                configuration.override(
                    configuration.snapshot.copy(toolOrder = persistentToolOrder),
                )
            }
            entryCatalog.start()
            environmentState.start(context)
            environmentState.observe { applySettings(configuration.snapshot) }
            registerPackageObserver()
            registerPinnedTargetsObserver()
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

    private fun registerPinnedTargetsObserver() {
        try {
            context.registerReceiver(
                pinnedTargetsReceiver,
                IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PINNED_TARGETS_OBSERVER_UNAVAILABLE", exception)
        }
    }

    /**
     * 面板里拖动排序后：把新顺序发给 App 落盘。
     * 进程内 RemotePreferences 是只读实现（edit() 直接抛 UnsupportedOperationException），
     * 写入只能由 App 进程做；App 侧用**清单静态注册**的接收器，进程不在也能被拉起。
     */
    private fun publishToolOrder(aliases: List<String>) {
        val ordered = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ordered.isEmpty()) return
        // 先在 system_server 内存里生效：即使 App 落盘慢一步，面板/目录也立刻用新顺序，
        // 不会出现「收起后恢复原状」的观感。
        configuration.override(configuration.snapshot.copy(toolOrder = ordered))
        // 持久化真值写进 Settings.Global：system_server 自己能读回，重启也不丢。
        val writtenSettings = writeToolOrderToSettings(ordered)
        val writtenFile = writeToolOrderToFile(ordered)
        val written = writtenSettings || writtenFile
        if (written) persistentToolOrder = ordered
        try {
            context.sendBroadcast(
                Intent(ModulePreferences.ACTION_TOOL_ORDER_CHANGED)
                    .setPackage(SidebarEntryProtocol.MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(ModulePreferences.EXTRA_TOOL_ORDER, ordered.joinToString(",")),
            )
            logger(
                Log.INFO,
                "TOOL_ORDER_PUBLISHED count=" + ordered.size + " head=" + ordered.first() +
                    " settings=" + written,
                null,
            )
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "TOOL_ORDER_PUBLISH_FAILED", exception)
        }
    }

    /** 兜底通道：system_server 对 /data/system 有写权限，文件不依赖 SettingsProvider。 */
    private val toolOrderFile = java.io.File("/data/system/flyme_freeform_tool_order.txt")

    private fun writeToolOrderToFile(ordered: List<String>): Boolean =
        try {
            toolOrderFile.writeText(ordered.joinToString(","))
            true
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "TOOL_ORDER_FILE_WRITE_FAILED", exception)
            false
        }

    private fun readToolOrderFromFile(): List<String> =
        try {
            if (!toolOrderFile.exists()) {
                emptyList()
            } else {
                toolOrderFile
                    .readText()
                    .trim()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "TOOL_ORDER_FILE_READ_FAILED", exception)
            emptyList()
        }

    private fun writeToolOrderToSettings(ordered: List<String>): Boolean =
        try {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                ModulePreferences.GLOBAL_KEY_TOOL_ORDER,
                ordered.joinToString(","),
            )
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "TOOL_ORDER_SETTINGS_WRITE_FAILED", exception)
            false
        }

    /** 读回持久化的顺序；没有就返回空表。 */
    private fun readToolOrderFromSettings(): List<String> =
        try {
            android.provider.Settings.Global
                .getString(
                    context.contentResolver,
                    ModulePreferences.GLOBAL_KEY_TOOL_ORDER,
                )
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "TOOL_ORDER_SETTINGS_READ_FAILED", exception)
            emptyList()
        }

    private fun moduleUid(): Int = try {
        context.packageManager.getApplicationInfo(
            SidebarEntryProtocol.MODULE_PACKAGE,
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
        ).uid
    } catch (_: RuntimeException) {
        -1
    }

    private fun applySettings(incoming: ModuleSettingsSnapshot) {
        // 排序的真值在 Settings.Global（见 publishToolOrder）：App 推来的 toolOrder 可能是旧值，
        // 这里以持久化的顺序为准，避免把用户刚调好的顺序覆盖回旧顺序。
        val settings =
            if (persistentToolOrder.isNotEmpty() && incoming.toolOrder != persistentToolOrder)
                incoming.copy(toolOrder = persistentToolOrder)
            else incoming
        val selectionChanged =
            settings.pinsSaved != lastSettings.pinsSaved ||
                settings.pinnedTargets != lastSettings.pinnedTargets ||
                settings.innerPinsSaved != lastSettings.innerPinsSaved ||
                settings.innerPinnedTargets != lastSettings.innerPinnedTargets ||
                settings.toolOrder != lastSettings.toolOrder
        lastSettings = settings
        if (environmentState.isGestureAllowed(refreshKeyguard = true) && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
            val resuming = !pointerRegistered
            if ((resuming || selectionChanged) && settings.hasSidebarPins) entryCatalog.refresh()
            registerPointerListener()
            if (resuming || selectionChanged || !catalogSnapshot.matches(settings) || catalogSnapshot.radialApps.isEmpty()) {
                appCatalog.refresh(settings, reloadApps = resuming || selectionChanged || catalogSnapshot.radialApps.isEmpty())
            }
        } else {
            sidebar.cancel()
            entryLaunchGeneration++
            entryCatalog.cancel()
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
            // 系统指针监听仅有隐藏接口；注册失败由本方法回退，不影响系统输入。
            @SuppressLint("PrivateApi")
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
            logger(Log.DEBUG, "SYSTEM_POINTER_LISTENER_REGISTERED", null)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_FAILED", exception)
        }
    }

    private fun enqueuePointerEvent(event: MotionEvent, generation: Long) {
        if (!pointerRegistered || generation != pointerGeneration) return
        val stream = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerStreamGate.begin()
        } else {
            pointerStreamGate.current()
        }
        // DOWN 仍用于取消待执行动作；主线程排除角落手势后才停止复制该流的后续事件。
        if (event.actionMasked != MotionEvent.ACTION_DOWN && !pointerStreamGate.shouldForward(stream)) return
        val copy = QueuedPointerEvent(MotionEvent.obtain(event), generation, stream)
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
        val event = queued.event
        try {
            if (queued.generation != pointerGeneration || !pointerRegistered) return
            if (event.actionMasked != MotionEvent.ACTION_DOWN && !pointerStreamGate.shouldForward(queued.stream)) return
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                entryLaunchGeneration++
                entryCatalog.cancelLaunch()
                // 面板窗口只覆盖面板本身，面板外的点击由这里兜：点到外面就关掉面板
                val panel = morePanelBounds
                if (morePanelActive && panel != null &&
                    !panel.contains(event.rawX.toInt(), event.rawY.toInt())
                ) {
                    logger(Log.DEBUG, "MORE_PANEL_OUTSIDE_TAP", null)
                    removeOverlay()
                    return
                }
            }
            handlePointerEvent(event)
            if (!shouldKeepPointerStream()) {
                activeEnvironmentApproved = false
                activeGestureConfig = null
                pointerStreamGate.ignore(queued.stream)
            }
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
        pointerStreamGate.reset()
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

    private fun shouldKeepPointerStream(): Boolean =
        activeEnvironmentApproved && !morePanelActive && when (gestureEngine.phase) {
            GesturePhase.Armed, GesturePhase.Revealing, GesturePhase.Selecting -> true
            else -> false
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
                    triggerRangeDp = settings.cornerTriggerRangeDp,
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
        side: io.github.mangi.flymefreeform.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        entryLaunchGeneration++
        entryCatalog.cancelLaunch()
        removeOverlay()
        morePanelActive = false
        val params = createOverlayParams(focusable = false)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this)
            val vibration = configuration.snapshot
            view.applyVibration(vibration.radialVibrateEnabled, vibration.radialVibrateStrength)
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
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

    override fun onEntryCommitted(entry: RadialEntry) {
        entryLaunchGeneration++
        entryCatalog.cancelLaunch()
        val generation = pointerGeneration
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
        handler.post {
            if (generation == pointerGeneration) launchCommittedEntry(entry)
        }
    }

    private fun launchCommittedEntry(entry: RadialEntry) {
        if (!isGestureEnvironmentAllowed()) return
        val target = entry.target
        if (target is PinnedTarget.Sidebar) {
            val focus = focusedWindow() ?: return
            val rotation = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            val generation = ++entryLaunchGeneration
            // removeViewImmediate 后等待两次合成帧，再把执行权交给取屏类工具。
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback {
                    if (generation == entryLaunchGeneration && overlay == null && isGestureEnvironmentAllowed()) {
                        entryCatalog.launch(target) {
                            generation == entryLaunchGeneration && overlay == null && isGestureEnvironmentAllowed() &&
                                focusedWindow() === focus &&
                                context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation == rotation
                        }
                    }
                }
            }
            return
        }
        target as PinnedTarget.App
        when (val result = launcher.launch(android.content.ComponentName(target.packageName, target.className))) {
            FreeformLaunchResult.Started -> Unit
            FreeformLaunchResult.TargetUnavailable ->
                logger(Log.WARN, "FREEFORM_LAUNCH_TARGET_UNAVAILABLE", null)
            is FreeformLaunchResult.Failed ->
                logger(Log.WARN, result.diagnosticCode, result.cause)
        }
    }

    override fun onToolOrderChanged(aliases: List<String>) {
        if (aliases.isEmpty()) return
        publishToolOrder(aliases)
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
        // 自绘「更多」面板：完全不碰原生侧边栏的 UI 链路
        showBuiltInMorePanel(view)
    }

    private fun showBuiltInMorePanel(view: CornerRadialOverlayView) {
        val params = createOverlayParams(focusable = true)
        // 面板模式把窗口缩到面板本身：全屏窗口会拦掉桌面底部上滑（系统手势拿不到事件）
        view.panelWindowRect()?.let { rect ->
            morePanelBounds = android.graphics.Rect(rect)
            params.width = rect.width()
            params.height = rect.height()
            params.gravity = Gravity.TOP or Gravity.START
            params.x = rect.left
            params.y = rect.top
            view.setWindowOrigin(rect.left, rect.top)
        }
        try {
            windowManager.updateViewLayout(view, params)
            view.showBuiltInMorePanel()
            view.requestFocus()
            logger(
                Log.DEBUG,
                "MORE_PANEL_SHOWN window=" + params.width + "x" + params.height +
                    " at=" + params.x + "," + params.y +
                    " letters=" + catalogSnapshot.appGroups.joinToString("") { it.letter },
                null,
            )
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

    private fun createOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
        // 层级：2012(INPUT_METHOD_DIALOG) 刚好压在输入法(2011)之上、状态栏之下；
        // 用 2038(APPLICATION_OVERLAY) 时轮盘会被弹出的输入法整块盖住（本机实测）。
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
        morePanelBounds = null
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

    private fun focusedWindowPackage(): String? = try {
        focusedWindow()?.let { findMethod(it.javaClass, "getOwningPackage", 0).invoke(it) as? String }
    } catch (_: ReflectiveOperationException) { null }
      catch (_: RuntimeException) { null }

    private fun focusedWindow(): Any? = try {
        val root = readField(readWindowManagerService(), "mRoot") ?: return null
        val display = findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
        readField(display, "mCurrentFocus")
    } catch (_: ReflectiveOperationException) { null }
      catch (_: RuntimeException) { null }

    private fun readWindowManagerService(): Any {
        val atms = readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
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

    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long, val stream: Long)

    private companion object {
        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"
        // 2012：压在输入法之上、状态栏之下（2038 会被输入法盖住）
        val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L
        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}
