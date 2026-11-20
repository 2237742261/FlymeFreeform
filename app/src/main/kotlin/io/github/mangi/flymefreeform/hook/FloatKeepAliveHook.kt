package io.github.mangi.flymefreeform.hook

import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.mangi.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 「贴边挂机」：小窗收成贴边浮标后，不把它的任务停掉。
 *
 * 原厂把窗口收成浮标时会把任务移回后台（`Task.moveTaskToBack`），可见性遍历随后让
 * ActivityRecord 走 `makeInvisible()`，状态落到 STOPPED；进程变成 cached，稍后即被冻结
 * 或回收，正在下载/更新的应用也就停了。
 *
 * 这里只做一件事：浮标列表里的任务不参与这次可见性收尾——跳过 `makeInvisible()`、
 * 拒绝把 visibleRequested 改回 false、拦掉原厂那次显式的 `addToStopping`，并挡住到
 * STOPPING/STOPPED 的状态迁移。ActivityRecord 因此维持「可见但未置顶」的状态，进程
 * 留在 perceptible 档，不会被冻结。窗口本身仍由原厂隐藏，用户只看到浮标。
 */
internal class FloatKeepAliveHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private var getInstanceMethod: Method? = null
    private var isInFloatingListMethod: Method? = null
    private val loggedTasks = ConcurrentHashMap<Int, Boolean>()

    /** system_server 的 ClassLoader：Hans 白名单等反射调用靠它取类。 */
    private var serverLoader: ClassLoader? = null
    private var hansInstanceMethod: Method? = null
    private var hansAddMethod: Method? = null
    private var hansRemoveMethod: Method? = null
    private var hansResolved = false

    /** 横竖屏切换期间为 true：这时可见性一律放行，避免浮标里的窗口被重新显示出来。 */
    private val rotationGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 浮标任务 -> ActivityRecord，旋转时按它主动把可见性收回去。 */
    private val rotationRecords = ConcurrentHashMap<Int, WeakReference<Any>>()
    private val rotationLogged = ConcurrentHashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private val releasedTasks = ConcurrentHashMap<String, Boolean>()

    /**
     * 最近「用户点开浮标」的任务号 → 时刻。
     *
     * 展开浮标时系统会请求可见性，而这条请求和「浮标里的应用自己起新页面」走的是同一个
     * setVisibleRequested(true)。以前一律按「非游戏拒绝抬可见」处理，结果展开的那一下被拒，
     * 窗口停在 always_hidden + 浮标坐标上：屏幕上只剩任务快照（透明、比例不对）。
     * 用户点开浮标的短时间内放行一次。
     */
    private val unfloatAt = ConcurrentHashMap<Int, Long>()

    /** 最近一次「点开浮标」的时刻：拿不到任务号时按它整段放行。 */
    private val unfloatAllAt = java.util.concurrent.atomic.AtomicLong(0)

    fun install(classLoader: ClassLoader) {
        serverLoader = classLoader
        val recordClass =
            runCatching { classLoader.loadClass(RECORD_CLASS) }.getOrElse {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_RECORD_UNAVAILABLE", it)
                return
            }
        resolveController(classLoader)
        resolveHans()

        installWithoutVisibility(recordClass)
        installVisibleRequested(recordClass)
        installSetVisibilityGuard(recordClass)
        installStateGuard(recordClass)
        installStopGuard(recordClass)
        installPauseSkip(recordClass, classLoader)
        installPauseTimeout(recordClass)
        installGameDetectHook(classLoader)
        installAdjustOptionsGuard(classLoader)
        installFloatEjectGuard(classLoader)
        installRotationGuard(classLoader)
        installUnfloatObserver(classLoader)
        installHandleObserver(classLoader)
        installFloatHandleSync(classLoader)
        installHansFreezeGuard(classLoader)
        installLowLevelFreezeGuard(classLoader)
        installKeyguardSnapshot(classLoader)

        installFullScreenResetGuard(classLoader)
        installAudioFocusGuard(classLoader)
        installAwakeGuard(classLoader)
        installScreenOffGuard(classLoader)
        installClientVisibleKeepOn(classLoader)
        installDisplayStateGuard(classLoader)
        installKeyguardLockGuard(classLoader)
        installIdleExemption()
        ModuleLog.install("FLOAT_KEEP_ALIVE_HOOK_INSTALLED")
    }

    /**
     * 浮标任务不做可见性收尾：保持「可见但未置顶」，进程不掉档。
     *
     * 代价是锁屏/旋转/切换应用时原厂会按「任务仍可见」重排，窗口可能被重新提交出来；
     * 那几处由 [shouldKeepVisible] 与旋转守卫放行，见各类注释。
     */
    private fun installWithoutVisibility(recordClass: Class<*>) {
        val method =
            OemReflection.findMethod(recordClass, "makeInvisible") ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_MAKE_INVISIBLE")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.make_invisible")
            .intercept { chain ->
                if (shouldKeepVisible(chain.thisObject) && isGameRecord(chain.thisObject)) {
                    logSkip(chain.thisObject, "invisible")
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    private fun installVisibleRequested(recordClass: Class<*>) {
        val method =
            OemReflection.findMethod(recordClass, "setVisibleRequested", Boolean::class.javaPrimitiveType!!) ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_VISIBLE_REQUESTED")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.visible_requested")
            .intercept { chain ->
                val visible = chain.getArg(0) as? Boolean
                val keep = shouldKeep(chain.thisObject)
                val game = keep && isGameRecord(chain.thisObject)
                if (keep && !game && visible == true) {
                    val taskId = taskIdOfRecord(chain.thisObject)
                    if (isUnfloating(taskId)) {
                        // 用户刚点开浮标：这次可见性是「展开窗口」，必须放行，
                        // 否则窗口停在 always_hidden + 浮标坐标，屏幕上只剩半透明的任务快照。
                        ModuleLog.info("FLOAT_KEEP_ALIVE_UNFLOAT_ALLOW_task" + taskId)
                        chain.proceed()
                    } else {
                        // 非游戏（视频/短剧）：按上游策略拒绝抬成可见，换集就不会弹窗。
                        logDenyRaised(chain.thisObject)
                        chain.proceed(arrayOf<Any?>(false))
                    }
                } else if (visible == false && shouldKeepVisible(chain.thisObject) && game) {
                    logSkip(chain.thisObject, "visible_requested")
                    chain.proceed(arrayOf<Any?>(true))
                } else {
                    if (visible == false && shouldKeep(chain.thisObject)) {
                        logRelease(chain.thisObject, "visible_requested")
                    }
                    if (visible == true && shouldKeep(chain.thisObject)) {
                        logRaised(chain.thisObject)
                    }
                    chain.proceed()
                }
            }
    }

    // ==================== 分应用策略：非游戏拒绝可见，游戏保持旧逻辑 ====================

    /**
     * 判断包名是否游戏（按应用类别，不维护包名表）。
     *
     * 先问 ColorOS 自己的游戏识别（GameManagerServiceExtImpl.isGamePkg，云端包名表，
     * 冷门游戏也覆盖）；问不到再退回 ApplicationInfo.category == CATEGORY_GAME。
     */

    /**
     * 捕获 ColorOS 的游戏识别入口。
     *
     * GameManagerServiceExtImpl 没有公开的取实例方法，但系统会频繁调用它的
     * isGamePkg(String)。这里挂一个观察钩子：第一次被调用时记住实例，
     * 之后就能按需查询任意包名（结果同时缓存）。
     */
    private fun installGameDetectHook(classLoader: ClassLoader) {
        val type = runCatching { classLoader.loadClass(GAME_MANAGER_EXT) }.getOrNull()
        if (type == null) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_GAME_EXT")
            return
        }
        val methods =
            declaredMethodsNamed(type, "isGamePkg")
                .filter { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_IS_GAME_PKG")
            return
        }
        methods.forEachIndexed { index, method ->
            if (gameExtMethod == null) gameExtMethod = method
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.game_detect_$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val instance = chain.thisObject
                        if (instance != null) gameExtInstance = instance
                        val pkg = chain.getArg(0) as? String
                        if (pkg != null && result is Boolean) gameCache[pkg] = result
                        result
                    }
            }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_GAME_DETECT_HOOKED_" + methods.size)
    }

    private fun isGamePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        gameCache[pkg]?.let { return it }
        var result = false
        runCatching {
            val method = gameExtMethod
            val instance = gameExtInstance
            if (method != null) {
                method.isAccessible = true
                val value = if (instance != null) method.invoke(instance, pkg) else method.invoke(null, pkg)
                if (value is Boolean) result = value
            }
        }
        if (!result) {
            runCatching {
                val pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null)
                val info =
                    pm.javaClass
                        .getMethod(
                            "getApplicationInfo",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        ).invoke(pm, pkg, 0, 0)
                val category = info?.javaClass?.getField("category")?.getInt(info) ?: 0
                if (category == CATEGORY_GAME) result = true
            }
        }
        gameCache[pkg] = result
        if (gameDetectLogged.putIfAbsent(pkg, true) == null) {
            ModuleLog.info("FLOAT_KEEP_ALIVE_GAME_DETECT_" + pkg + "_" + result)
        }
        return result
    }

    private fun isGameRecord(record: Any?): Boolean =
        isGamePackage(OemReflection.readField(record, "packageName") as? String)

    private fun logDenyRaised(record: Any?) {
        val task = OemReflection.readField(record, "task") ?: OemReflection.readField(record, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: return
        if (denyRaisedLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_DENY_RAISED_task" + taskId)
    }

    private val denyRaisedLogged = ConcurrentHashMap<Int, Boolean>()

    /**
     * 认标记：哪一次启动是「浮标里的非游戏应用自己起了新页面」（短剧换集）。
     * 只识别、不改原厂判定结果；产出是一个时间窗。
     */
    private fun installAdjustOptionsGuard(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull() ?: return
        val methods =
            declaredMethodsNamed(flexibleClass, "isAllowedToAdjustOptions")
                .filter { it.returnType == Int::class.javaPrimitiveType }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_ADJUST_OPTIONS")
            return
        }
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.adjust_options_$index")
                .intercept { chain ->
                    if (configuration.snapshot.floatKeepAliveEffective) {
                        runCatching { noteFloatSelfStart(chain.args) }
                    }
                    chain.proceed()
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_ADJUST_OPTIONS_INSTALLED_" + methods.size)
    }

    private fun noteFloatSelfStart(args: List<Any?>) {
        var sourceTaskId = -1
        var sourcePackage: String? = null
        var lastPackage: String? = null
        var packageCount = 0
        for (arg in args) {
            val packageName = OemReflection.readField(arg, "packageName") as? String
            if (packageName != null) {
                lastPackage = packageName
                packageCount++
            }
            val task = OemReflection.readField(arg, "task") ?: OemReflection.readField(arg, "mTask")
            val taskId = (OemReflection.readField(task, "mTaskId") as? Int) ?: -1
            if (taskId > 0 && isFloating(taskId)) {
                sourceTaskId = taskId
                if (packageName != null) sourcePackage = packageName
            }
        }
        if (sourceTaskId <= 0 || sourcePackage == null || packageCount < 2) return
        if (lastPackage != sourcePackage) return
        if (isGamePackage(sourcePackage)) return
        suppressFloatEjectAt = android.os.SystemClock.uptimeMillis() + FLOAT_EJECT_WINDOW_MS
        if (floatSelfStartLogged.putIfAbsent(sourceTaskId.toString() + "/" + sourcePackage, true) == null) {
            ModuleLog.info("FLOAT_KEEP_ALIVE_FLOAT_SELF_START_task" + sourceTaskId + "_pkg" + sourcePackage)
        }
    }

    private val floatSelfStartLogged = ConcurrentHashMap<String, Boolean>()

    /** 非游戏应用自己起新页面时，别让原厂把 task 移前台、摘浮标（换集弹窗根因）。 */
    private fun installFloatEjectGuard(classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val methods = declaredMethodsNamed(controller, "moveTaskToFront").filter { it.returnType == Void.TYPE }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_MOVE_TASK_TO_FRONT")
            return
        }
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.eject_$index")
                .intercept { chain ->
                    val taskId = taskIdFromArgs(chain.args)
                    if (taskId > 0 && isFloating(taskId) &&
                        android.os.SystemClock.uptimeMillis() < suppressFloatEjectAt
                    ) {
                        if (ejectBlockedLogged.putIfAbsent(taskId, true) == null) {
                            ModuleLog.info("FLOAT_KEEP_ALIVE_EJECT_BLOCKED_task" + taskId)
                        }
                        null
                    } else {
                        chain.proceed()
                    }
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_EJECT_GUARD_INSTALLED_" + methods.size)
    }

    private val ejectBlockedLogged = ConcurrentHashMap<Int, Boolean>()


    /**
     * 另一条可见性通道：系统的解锁/应用切换流程会直接写 setVisibility(boolean)，
     * 不经过 setVisibleRequested。非游戏浮标应用同样拒绝「可见」。
     */
    private fun installSetVisibilityGuard(recordClass: Class<*>) {
        val methods =
            declaredMethodsNamed(recordClass, "setVisibility")
                .filter { it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_SET_VISIBILITY")
            return
        }
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.set_visibility_$index")
                .intercept { chain ->
                    val visible = chain.getArg(0) as? Boolean
                    val keep = shouldKeep(chain.thisObject)
                    if (visible == true && keep && !isGameRecord(chain.thisObject)) {
                        val taskId = taskIdOfRecord(chain.thisObject)
                        if (isUnfloating(taskId)) {
                            // 用户刚点开浮标：这次可见性是「展开窗口」，必须放行。
                            ModuleLog.info("FLOAT_KEEP_ALIVE_UNFLOAT_ALLOW_VIS_task" + taskId)
                            chain.proceed()
                        } else {
                            logDenyVisibility(chain.thisObject)
                            chain.proceed(arrayOf<Any?>(false))
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_SET_VISIBILITY_HOOKED_" + methods.size)
    }

    private fun logDenyVisibility(record: Any?) {
        val task = OemReflection.readField(record, "task") ?: OemReflection.readField(record, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: return
        if (denyVisibilityLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_DENY_VISIBILITY_task" + taskId)
    }

    private val denyVisibilityLogged = ConcurrentHashMap<Int, Boolean>()

    private fun installStateGuard(recordClass: Class<*>) {
        val stateClass =
            runCatching { recordClass.classLoader?.loadClass("$RECORD_CLASS\$State") ?: Class.forName("$RECORD_CLASS\$State") }.getOrNull()
                ?: return
        val method =
            OemReflection.findMethod(recordClass, "setState", stateClass, String::class.java) ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_SET_STATE")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.set_state")
            .intercept { chain ->
                val state = (chain.getArg(0) as? Enum<*>)?.name
                if (state in STOP_STATES && shouldKeep(chain.thisObject)) {
                    logSkip(chain.thisObject, "state_$state")
                    null
                } else {
                    if (state != null && shouldKeep(chain.thisObject)) {
                        logStateOnce(chain.thisObject, state)
                    }
                    chain.proceed()
                }
            }
    }

    /**
     * 挡下停止：ActivityRecord 的所有 `addToStopping` 重载都挂上。
     *
     * 签名会随 ROM 版本变化（有的机器是 `(boolean, String)`，有的多了个 boolean），
     * 按名字遍历全部 void 重载，避免某台机器上这道保护静默缺失。
     */
    private fun installStopGuard(recordClass: Class<*>) {
        val overloads =
            generateSequence(recordClass as Class<*>?) { it.superclass }
                .flatMap { type -> type.declaredMethods.asSequence() }
                .filter { it.name == "addToStopping" && it.returnType == Void.TYPE }
                .distinctBy { method -> method.parameterTypes.joinToString { it.name } }
                .toList()
        if (overloads.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_ADD_TO_STOPPING")
            return
        }
        overloads.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.add_to_stopping_$index")
                .intercept { chain ->
                    if (shouldKeep(chain.thisObject)) {
                        logSkip(chain.thisObject, "add_to_stopping")
                        null
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    /**
     * 暂停请求不发给客户端：服务端照常进入 PAUSING，再由这里补一次 activityPaused
     * 让 AMS 完成暂停并继续恢复下一个应用；客户端收不到 onPause，画面与逻辑继续跑。
     */
    /**
     * 暂停请求不发给客户端：服务端照常进入 PAUSING，再由这里补一次 activityPaused
     * 让 AMS 完成暂停并继续恢复下一个应用；客户端收不到 onPause，画面与逻辑继续跑。
     *
     * 注意：只挂「一个固定签名」会漏。实测出现过这样的一个事件：
     * `wm_pause_activity … pauseInRecentsAnim`，走的不是常规那条重载，
     * 首次贴边时 pause 就从这个口子漏过去，游戏当场停（第二次贴边已暂停，才显得正常）。
     * 这里按方法名把 TaskFragment 上所有带 ActivityRecord 参数的重载全挂上。
     */
    private fun installPauseSkip(recordClass: Class<*>, classLoader: ClassLoader) {
        val fragmentClass =
            runCatching { classLoader.loadClass(TASK_FRAGMENT_CLASS) }.getOrNull() ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_TASK_FRAGMENT")
                return
            }
        val methods =
            declaredMethodsNamed(fragmentClass, "schedulePauseActivity")
                .filter { method -> method.parameterTypes.any { it == recordClass } }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_SCHEDULE_PAUSE")
            return
        }
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.schedule_pause_$index")
                .intercept { chain ->
                    val record = chain.args.firstOrNull { recordClass.isInstance(it) }
                    if (record != null && shouldKeep(record) && scheduleFakePause(record)) {
                        logPausePath(method)
                        logSkip(record, "pause")
                        markPausing(chain.thisObject, record)
                        null
                    } else {
                        chain.proceed()
                    }
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_PAUSE_HOOKED_" + methods.size)
    }

    private val pausePathLogged = ConcurrentHashMap<String, Boolean>()

    private fun logPausePath(method: Method) {
        val signature = method.parameterTypes.joinToString(",") { it.simpleName }
        if (pausePathLogged.putIfAbsent(signature, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_PAUSE_PATH_" + signature)
    }

    private fun installPauseTimeout(recordClass: Class<*>) {
        val method = OemReflection.findMethod(recordClass, "schedulePauseTimeout") ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.pause_timeout")
            .intercept { chain ->
                if (shouldKeep(chain.thisObject)) {
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /**
     * 横竖屏切换：浮标里的窗口不能重新出现。
     *
     * 旋转时原厂会按「这个任务是否可见」重排浮动窗口，而保活为了进程档位一直把
     * ActivityRecord 按成可见，于是旋转一结束窗口就被重新提交出来。这里在配置变化
     * 开始时把浮标任务的可见性主动收回（守卫置位，可见性两道钩子整体放行），
     * 过渡结束 800ms 后再兜一次：仍是浮标却又是可见的，重新压下去。
     */
    private fun installRotationGuard(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull()
        if (flexibleClass != null) {
            val method =
                OemReflection.findMethod(
                    flexibleClass,
                    "onGlobalConfigurationChanged",
                    Int::class.javaPrimitiveType!!,
                    Configuration::class.java,
                    Configuration::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            if (method != null) {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.float_keep_alive.rotation_config")
                    .intercept { chain ->
                        val rotated = isRotationChange(chain.getArg(1), chain.getArg(2))
                        if (rotated) beginRotation()
                        val result = chain.proceed()
                        if (rotated) scheduleRotationEnd()
                        result
                    }
            } else {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_ROTATION_CONFIG")
            }
        }
        // 旋转过渡自身也有回调，作为配置变化之外的第二个入口。
        val floatClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
        if (floatClass != null) {
            OemReflection.findMethod(floatClass, "onDisplayRotationTransitionStarting")?.let { method ->
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.float_keep_alive.rotation_start")
                    .intercept { chain ->
                        beginRotation()
                        val result = chain.proceed()
                        scheduleRotationEnd()
                        result
                    }
            }
        }
    }

    /** 比较两份 Configuration 的窗口旋转值（windowConfiguration 是隐藏 API，走反射）。 */
    /**
     * 浮标被摘掉时记一行：息屏期间如果原厂把浮标收掉，这里能直接看到任务号与原因码。
     */
    private fun installHandleObserver(classLoader: ClassLoader) {
        val floatClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val method = OemReflection.findMethod(floatClass, "removeFloatHandle", Int::class.javaPrimitiveType!!, Any::class.java, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.handle_removed")
            .intercept { chain ->
                val taskId = chain.getArg(0) as? Int ?: -1
                val flag = chain.getArg(3) as? Int ?: -1
                markUnfloating(taskId)
                logHandleRemoved(taskId, flag, "public")
                runCatching { removeFloatWhite(taskId) }
                chain.proceed()
            }
        // 内部入口：public 的那条只是转手，真正清场走这里。
        OemReflection.findMethod(
            floatClass,
            "removeFloatHandleInner",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )?.let { inner ->
            module
                .hook(inner)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.handle_removed_inner")
                .intercept { chain ->
                    val taskId = chain.getArg(0) as? Int ?: -1
                    val flag = chain.getArg(2) as? Int ?: -1
                    markUnfloating(taskId)
                    logHandleRemoved(taskId, flag, "inner")
                    runCatching { removeFloatWhite(taskId) }
                    chain.proceed()
                }
        }
    }

    /**
     * 用户点开浮标（startActivityByFloatInfo）：记下任务号，随后的可见性请求要放行。
     *
     * 系统把「点浮标展开成窗口」也走这个方法，展开依赖一次 setVisibleRequested(true)。
     * 那次请求如果被 [installVisibleRequested] 的「非游戏拒绝抬可见」拦掉，窗口就永远停在
     * always_hidden，屏幕上只剩任务快照（表现为透明小窗、比例不对）。
     */
    private fun installUnfloatObserver(classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val methods = declaredMethodsNamed(controller, "startActivityByFloatInfo")
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_START_BY_FLOAT_INFO")
            return
        }
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.float_keep_alive.unfloat_" + index)
                .intercept { chain ->
                    if (configuration.snapshot.floatKeepAliveEffective) {
                        // FloatHandleInfo 里不一定有 mTaskId（实测拿不到），所以这里整段放行：
                        // 放行窗只覆盖「刚点开浮标」这几秒，短剧换集不受影响。
                        val taskId = taskIdFromArgs(chain.args)
                        markUnfloating(taskId)
                        ModuleLog.info("FLOAT_KEEP_ALIVE_UNFLOAT_OPEN_task" + taskId)
                    }
                    chain.proceed()
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_UNFLOAT_HOOKED_" + methods.size)
    }

    /** 记一次「用户点开/摘掉浮标」的时刻（全局放行窗 + 任务号）。 */
    private fun markUnfloating(taskId: Int) {
        val now = SystemClock.uptimeMillis()
        unfloatAllAt.set(now)
        if (taskId > 0) unfloatAt[taskId] = now
    }

    /** 这个任务是不是刚刚被用户点开/摘掉浮标（短时放行窗内）。 */
    private fun isUnfloating(taskId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - unfloatAllAt.get() <= UNFLOAT_PERMIT_MS) return true
        if (taskId <= 0) return false
        val at = unfloatAt[taskId] ?: return false
        if (now - at > UNFLOAT_PERMIT_MS) {
            unfloatAt.remove(taskId)
            return false
        }
        return true
    }

    /** ActivityRecord → 任务号；取不到返回 -1。 */
    private fun taskIdOfRecord(record: Any?): Int {
        val target = record ?: return -1
        val task = OemReflection.readField(target, "task") ?: OemReflection.readField(target, "mTask") ?: return -1
        return (OemReflection.readField(task, "mTaskId") as? Int) ?: -1
    }

    /**
     * 贴边动作本身同步白名单。
     *
     * 实测 `sweepExemptions` 会因为一次 `resetFlexibleTaskToFullScreen` 把包当成
     * 「已离开浮标」撤掉白名单，而重新贴边时没有任何 ActivityRecord 钩子被触发，
     * `shouldKeep` 不会重跑 —— 结果下次息屏 Hans 照冻。
     * 所以白名单在 `addFloatHandle*` 上补挂、在 `removeFloatHandle*` 上撤掉，不依赖状态变化。
     */
    private fun installFloatHandleSync(classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val methods =
            declaredMethodsNamed(controller, "addFloatHandle") +
                declaredMethodsNamed(controller, "addFloatHandleImmediately")
        methods.distinctBy { it.parameterTypes.joinToString(",") { type -> type.name } }
            .forEachIndexed { index, method ->
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.float_keep_alive.float_added_$index")
                    .intercept { chain ->
                        if (configuration.snapshot.floatKeepAliveEffective) {
                            runCatching { onFloatHandleAdded(chain.args) }
                        }
                        chain.proceed()
                    }
            }
        ModuleLog.install("FLOAT_KEEP_ALIVE_FLOAT_ADD_HOOKED_" + methods.size)
    }

    private fun onFloatHandleAdded(args: List<Any?>) {
        val taskId = taskIdFromArgs(args)
        if (taskId <= 0) return
        val record = rotationRecords[taskId]?.get()
        var pkg = OemReflection.readField(record, "packageName") as? String
        if (pkg.isNullOrEmpty()) {
            for (arg in args) {
                val candidate = OemReflection.readField(arg, "packageName") as? String
                if (!candidate.isNullOrEmpty()) {
                    pkg = candidate
                    break
                }
            }
        }
        if (pkg.isNullOrEmpty()) return
        hansCustomWhite(pkg, true)
        if (exemptedPackages.putIfAbsent(pkg, 0) == null) {
            val target = pkg
            exemptExecutor.execute {
                runCatching { deviceIdleCall("addPowerSaveWhitelistApp", target) }
            }
            scheduleExemptSweep()
        }
    }

    /** 浮标被摘时撤白名单；同包还有别的浮标任务就保留。 */
    private fun removeFloatWhite(taskId: Int) {
        val record = rotationRecords[taskId]?.get() ?: return
        val pkg = OemReflection.readField(record, "packageName") as? String ?: return
        val stillFloating =
            rotationRecords.any { (otherId, reference) ->
                otherId != taskId &&
                    reference.get()?.let { OemReflection.readField(it, "packageName") == pkg } == true &&
                    isFloating(otherId)
            }
        if (stillFloating) return
        hansCustomWhite(pkg, false)
        exemptedPackages.remove(pkg)
        exemptExecutor.execute { runCatching { deviceIdleCall("removePowerSaveWhitelistApp", pkg) } }
    }

    private fun taskIdFromArgs(args: List<Any?>): Int {
        for (arg in args) {
            val taskId = OemReflection.readField(arg, "mTaskId") as? Int ?: continue
            if (taskId > 0) return taskId
        }
        return -1
    }

    /** 按方法名取全部重载（沿继承链），同参数列表只保留最派生的那一个。 */
    private fun declaredMethodsNamed(type: Class<*>, name: String): List<Method> {
        val found = ArrayList<Method>(4)
        var current: Class<*>? = type
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != name) continue
                runCatching { method.isAccessible = true }
                found.add(method)
            }
            current = current.superclass
        }
        return found
    }

    private fun logHandleRemoved(taskId: Int, flag: Int, source: String) {
        if (handleRemovedLogged.putIfAbsent(taskId.toString() + "/" + source, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_HANDLE_REMOVED_task" + taskId + "_flag" + flag + "_" + source)
    }

    /**
     * 解锁瞬间给每个浮标任务拍一张快照：还在不在浮标列表里、可见性是什么、进程状态如何。
     * 用来判断「解锁后自己变全屏」到底是浮标被摘了，还是窗口被抬起来了。
     */
    private fun installKeyguardSnapshot(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull() ?: return
        val method =
            OemReflection.findMethod(
                flexibleClass,
                "notifyKeyguardStateChanged",
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.unlock_snapshot")
            .intercept { chain ->
                val showing = chain.getArg(1) as? Boolean
                // 解锁和横竖屏一样，是一次整屏可见性重排：这时把守卫放下，窗口才不会被顶出来。
                if (showing == false) {
                    unlockSettledUntil = android.os.SystemClock.uptimeMillis() + UNLOCK_SETTLE_MS
                    beginUnlockGuard()
                }
                val result = chain.proceed()
                if (showing == false) {
                    scheduleUnlockGuardEnd()
                    handler.postDelayed({ snapshotTrackedTasks("unlock") }, 500L)
                } else {
                    handler.postDelayed({ snapshotTrackedTasks("lock") }, 500L)
                }
                result
            }
    }

    /** 解锁过渡期间为 true：可见性一律放行，避免浮标任务被重新提交成可见窗口。 */
    private val unlockGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private val unlockLogged = ConcurrentHashMap<String, Boolean>()

    private fun beginUnlockGuard() {
        if (!unlockGuard.compareAndSet(false, true)) return
        rehideTracked("unlock")
    }

    private fun scheduleUnlockGuardEnd() {
        UNLOCK_CHECK_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                // 先压回去，再收回守卫，否则这次压回会被自己的钩子改回可见。
                rehideTracked("unlock" + (delay / 1000))
                if (delay == UNLOCK_CHECK_DELAYS_MS.last()) unlockGuard.set(false)
            }, delay)
        }
    }

    /** 仍是浮标、却被按成可见的任务，重新压回不可见，让它回到浮标状态。 */
    private fun rehideTracked(reason: String) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if (OemReflection.readField(record, "mVisibleRequested") == true) {
                if (OemReflection.callBoolean(record, "setVisibleRequested", false)) {
                    logUnlockRehide(taskId, reason)
                }
            }
        }
    }

    private fun logUnlockRehide(taskId: Int, reason: String) {
        if (unlockLogged.putIfAbsent(taskId.toString() + "/" + reason, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_UNLOCK_task" + taskId + "_rehide_" + reason)
    }

    private fun snapshotTrackedTasks(phase: String) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            val floating = isFloating(taskId)
            val visibleRequested = OemReflection.readField(record, "mVisibleRequested")
            ModuleLog.info("FLOAT_KEEP_ALIVE_SNAPSHOT_" + phase + "_task" + taskId +
                    "_floating" + floating + "_visibleReq" + visibleRequested)
        }
    }

    private val handleRemovedLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 锁屏/息屏期间不让原厂把浮标任务退回全屏。
     *
     * 原厂在 `isAllowedToAdjustOptions()` 里有一条：锁屏状态下若目标任务是浮窗，直接把任务
     * `resetFlexibleTaskToFullScreen()`。正常使用没问题，但保活时应用在锁屏期间还在跑，
     * 它自己起一个新 Activity（例如短剧自动连播）就会命中这条，浮标被摘掉、任务变全屏，
     * 解锁后用户看到的是一屏全屏窗口。
     */
    private fun installFullScreenResetGuard(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull() ?: return
        val taskClass = runCatching { classLoader.loadClass(TASK_CLASS) }.getOrNull() ?: return
        val method = OemReflection.findMethod(flexibleClass, "resetFlexibleTaskToFullScreen", taskClass, Int::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.fullscreen_reset")
            .intercept { chain ->
                val taskId = (OemReflection.readField(chain.getArg(0), "mTaskId") as? Int) ?: -1
                // 放到全屏也可能来自应用自己起新页面（短剧连播、换集）：
                // 此时任务仍是保活浮标，不能让它被摘成全屏。
                val fromAppStart = Throwable().stackTrace.any { frame ->
                    val name = frame.methodName
                    name.contains("startActivity") ||
                        name.contains("moveTaskToFront") ||
                        name.contains("resumeFocusedStacksTopActivities")
                }
                val justUnlocked = android.os.SystemClock.uptimeMillis() < unlockSettledUntil
                if (configuration.snapshot.floatKeepAliveEffective && isKeptFloat(taskId) &&
                    (isLockedOrOff(chain.thisObject) || fromAppStart || justUnlocked)
                ) {
                    logResetSkipped(taskId)
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /** 保活负责的浮标任务：还在浮标列表里，或本轮保活记录过的（原厂可能已先摘了浮标）。 */
    private fun isKeptFloat(taskId: Int): Boolean = taskId > 0 && (isFloating(taskId) || rotationRecords[taskId]?.get() != null)

    /**
     * 保活期间放行音频焦点。
     *
     * ColorOS 的音频加固（`HardeningEnforcer.blockFocusMethod`）不允许后台应用申请音频焦点
     * （AppOps 只给前台，`level: partial` / `exemption: 6`）。应用贴边成浮标后就是后台，
     * 换集/换曲重新申请焦点被拒，播放就自己停了，解锁回前台才恢复。
     * 浮标保活的语义是「这个应用还在前台服务工作」，所以这里直接判为不拦截。
     */
    private fun installAudioFocusGuard(classLoader: ClassLoader) {
        val enforcerClass = runCatching { classLoader.loadClass(HARDENING_ENFORCER_CLASS) }.getOrNull() ?: return
        val method =
            OemReflection.findMethod(
                enforcerClass,
                "blockFocusMethod",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                android.media.AudioAttributes::class.java,
            ) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.audio_focus")
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val uid = chain.getArg(0) as? Int ?: -1
                val taskId = keptFloatTaskOfUid(uid)
                if (taskId > 0) {
                    logFocusAllowed(taskId, uid)
                    false
                } else {
                    chain.proceed()
                }
            }
    }

    private fun keptFloatTaskOfUid(uid: Int): Int {
        if (uid <= 0) return -1
        var found = -1
        rotationRecords.forEach { (taskId, reference) ->
            if (found > 0) return@forEach
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if ((OemReflection.callNoArg(record, "getUid") as? Int) == uid) found = taskId
        }
        return found
    }

    private fun logFocusAllowed(taskId: Int, uid: Int) {
        if (focusAllowedLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_FOCUS_task" + taskId + "_uid" + uid + "_allowed")
    }

    private val focusAllowedLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用伪装成「亮屏、没进省电」。
     *
     * 视频类应用在换下一集前普遍会看 `PowerManager.isInteractive()`，息屏就不自动连播，
     * 于是浮标里的播放停在换集那一刻（日志：音轨 started 三分半，换集时应用自行 stopped）。
     * 这里只对「正在被保活的浮标应用」按调用 uid 返回亮屏，其它进程不受影响。
     */
    private fun installAwakeGuard(classLoader: ClassLoader) {
        val binderClass = runCatching { classLoader.loadClass(POWER_BINDER_CLASS) }.getOrNull() ?: return
        hookAwakeAnswer(binderClass, "isInteractive", true)
        hookAwakeAnswer(binderClass, "isDeviceIdleMode", false)
        hookAwakeAnswer(binderClass, "isLightDeviceIdleMode", false)
    }

    private fun hookAwakeAnswer(binderClass: Class<*>, name: String, answer: Boolean) {
        val method = OemReflection.findMethod(binderClass, name) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.awake_" + name)
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val uid = Binder.getCallingUid()
                val taskId = keptFloatTaskOfUid(uid)
                if (taskId > 0) {
                    logAwakeAnswer(name, taskId, uid)
                    answer
                } else {
                    chain.proceed()
                }
            }
    }

    private fun logAwakeAnswer(name: String, taskId: Int, uid: Int) {
        if (awakeLogged.putIfAbsent(name + "/" + taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_AWAKE_" + name + "_task" + taskId + "_uid" + uid)
    }

    private val awakeLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 不把息屏广播投给正在保活的浮标应用。
     *
     * 视频类应用收到 `ACTION_SCREEN_OFF` 后会把自己标成「熄屏」，当前这集照播，但换下一集时
     * 就不会自动连播（日志：锁屏后播 3 分半，换集时应用自行 pause 并停止申请播放）。
     * 亮屏时同样的换集就正常，所以判断依据是屏幕状态而不是焦点或进程档位。
     *
     * 做法只在这一条广播上把保活应用的包名加进 `excludedPackages`，其它应用照常收到。
     */
    private fun installScreenOffGuard(classLoader: ClassLoader) {
        val controllerClass = runCatching { classLoader.loadClass(BROADCAST_CONTROLLER_CLASS) }.getOrNull() ?: return
        val method =
            controllerClass.declaredMethods.firstOrNull {
                it.name == "broadcastIntentLocked" && it.parameterCount >= 20
            }?.apply { isAccessible = true } ?: return
        val excludedIdx = excludedPackagesIndex(method)
        if (excludedIdx < 0) return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.screen_off")
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val action = (chain.getArg(3) as? android.content.Intent)?.action
                if (action != android.content.Intent.ACTION_SCREEN_OFF) {
                    chain.proceed()
                } else {
                    val packages = keptFloatPackages()
                    if (packages.isEmpty()) {
                        chain.proceed()
                    } else {
                        val args = Array<Any?>(method.parameterCount) { chain.getArg(it) }
                        val previous = (args[excludedIdx] as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                        args[excludedIdx] = (previous + packages).distinct().toTypedArray()
                        logScreenOffSkipped(packages)
                        chain.proceed(args)
                    }
                }
            }
    }

    /** 找 `broadcastIntentLocked` 里 excludedPackages 那个 String[] 的下标（第三个 String[]）。 */
    private fun excludedPackagesIndex(method: Method): Int {
        var seen = 0
        method.parameterTypes.forEachIndexed { index, type ->
            if (type == Array<String>::class.java) {
                seen++
                if (seen == 3) return index
            }
        }
        return -1
    }

    private fun keptFloatPackages(): List<String> {
        val packages = ArrayList<String>(2)
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            (OemReflection.readField(record, "packageName") as? String)?.let { if (!packages.contains(it)) packages.add(it) }
        }
        return packages
    }

    private fun logScreenOffSkipped(packages: List<String>) {
        val key = packages.joinToString(",")
        if (screenOffLogged.putIfAbsent(key, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_SCREEN_OFF_skipped_" + key)
    }

    private val screenOffLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 锁屏期间仍然向应用报告「窗口可见」。
     *
     * 窗口管理器侧该任务照旧是不可见的（不会在锁屏上显示），这里只改下发给应用的那一个标志：
     * 应用侧 `onWindowVisibilityChanged(true)` / `View.isShown()` 保持可见。
     * 对视频类应用是关键路径：日志显示锁屏后播完当前集、准备连播下一集时，窗口被判为
     * 不可见就不连播；亮屏贴边时（窗口可见）完全正常。
     */
    private fun installClientVisibleKeepOn(classLoader: ClassLoader) {
        val tokenClass = runCatching { classLoader.loadClass(TOKEN_CLASS) }.getOrNull() ?: return
        val method = OemReflection.findMethod(tokenClass, "setClientVisible", Boolean::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.client_visible_keep")
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val visible = chain.getArg(0) as? Boolean
                val target = chain.thisObject
                if (visible == false && target != null) {
                    val keep =
                        (target.javaClass.name == RECORD_CLASS && shouldKeep(target)) ||
                            tokenBelongsToKeptFloat(target)
                    if (keep) {
                        logClientKeep(target)
                        return@intercept chain.proceed(arrayOf<Any?>(true))
                    }
                }
                chain.proceed()
            }
    }

    /** 窗口级 token（不是 ActivityRecord）是否属于保活浮标里的应用：看它下面窗口的包名。 */
    private fun tokenBelongsToKeptFloat(token: Any?): Boolean {
        val packages = keptFloatPackagesCached()
        if (packages.isEmpty()) return false
        val direct = OemReflection.readField(token, "packageName") as? String
        if (direct != null) return direct in packages
        val windows = OemReflection.readField(token, "mWindows") as? List<*> ?: return false
        for (window in windows) {
            val attrs = OemReflection.readField(window, "mAttrs") ?: continue
            val packageName = OemReflection.readField(attrs, "packageName") as? String ?: continue
            if (packageName in packages) return true
        }
        return false
    }

    /** 浮标应用包名缓存：窗口可见性回调很频繁，不能每次都遍历任务做 binder 查询。 */
    private fun keptFloatPackagesCached(): Set<String> {
        val now = android.os.SystemClock.uptimeMillis()
        val cached = keptPackagesCache
        if (cached != null && now - keptPackagesAt < KEPT_PACKAGES_TTL_MS) return cached
        val packages = keptFloatPackages().toHashSet()
        keptPackagesCache = packages
        keptPackagesAt = now
        return packages
    }

    private fun logClientKeep(record: Any?) {
        val task = OemReflection.readField(record, "task") ?: OemReflection.readField(record, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: -1
        if (clientKeepLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_CLIENT_VISIBLE_task" + taskId + "_kept")
    }

    private val clientKeepLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用把 `Display.getState()` 报成 STATE_ON。
     *
     * 除了电源状态，播放器还常用 `DisplayManager.getDisplayInfo(0).state` 判断屏幕是否可用；
     * 熄屏时它是 STATE_OFF/STATE_DOZE，播放器据此不启动下一集。这里对保活应用返回一份
     * state=STATE_ON 的副本（不修改系统内部对象），并记一行日志便于确认。
     */
    private fun installDisplayStateGuard(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(DISPLAY_BINDER_CLASS) }.getOrNull() ?: return
        val method = OemReflection.findMethod(cls, "getDisplayInfo", Int::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.display_state")
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val taskId = keptFloatTaskOfUid(Binder.getCallingUid())
                if (taskId <= 0) {
                    chain.proceed()
                } else {
                    val info = chain.proceed()
                    val state = OemReflection.readField(info, "state") as? Int ?: -1
                    if (info == null || state < 0 || state == DISPLAY_STATE_ON) {
                        info
                    } else {
                        val patched =
                            runCatching {
                                val ctor = info.javaClass.getDeclaredConstructor(info.javaClass).apply { isAccessible = true }
                                val copy = ctor.newInstance(info)
                                copy.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(copy, DISPLAY_STATE_ON)
                                copy
                            }.getOrNull()
                        if (patched == null) {
                            info
                        } else {
                            logDisplayState(taskId, state)
                            patched
                        }
                    }
                }
            }
    }

    private fun logDisplayState(taskId: Int, original: Int) {
        if (displayLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_DISPLAY_task" + taskId + "_off" + original + "_reported_2")
    }

    private val displayLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用回答「没有锁屏」。
     *
     * 按客户端行为确认了它换集的判断点：
     * `com.dragon.read.video.o.b()` 里 `if (!KeyguardManager.isKeyguardLocked()) { 继续播放 }`，
     * 锁屏时整段跳过，所以换集不连播。`KeyguardManager.isKeyguardLocked()` 的服务端就是
     * `WindowManagerService.isKeyguardLocked()`（转给 `mPolicy.isKeyguardLocked()`）。
     * 只对保活应用的调用方返回 false，系统内部组件走的是 LocalService，不受影响。
     */
    private fun installKeyguardLockGuard(classLoader: ClassLoader) {
        val wmsClass = runCatching { classLoader.loadClass(WMS_CLASS) }.getOrNull() ?: return
        val method = OemReflection.findMethod(wmsClass, "isKeyguardLocked") ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.float_keep_alive.keyguard_locked")
            .intercept { chain ->
                // 开关随时可改：每次调用都按当前设置判断，不依赖开机时的取值。
                if (!configuration.snapshot.floatKeepAliveEffective) return@intercept chain.proceed()
                val taskId = keptFloatTaskOfUid(Binder.getCallingUid())
                if (taskId > 0) {
                    logKeyguardFaked(taskId)
                    false
                } else {
                    chain.proceed()
                }
            }
    }

    private fun logKeyguardFaked(taskId: Int) {
        if (keyguardLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_KEYGUARD_task" + taskId + "_reported_unlocked")
    }

    private val keyguardLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 贴边浮标出现时给该应用加系统省电白名单（等于“允许完全后台运行”），
     * 离开浮标后撤销。
     *
     * 结论：锁屏后应用仍在播，但换集要新建连接/解析新域名，会被打盹下的后台网络限制卡住，
     * 表现为“这一集播完就停、解锁立刻继续”。加入白名单（待机桶随之变成 EXEMPTED）后不再受限。
     * 所以这一条只在保活期间生效，退出浮标即撤销，不留长期副作用。
     */
    private fun installIdleExemption() {
        exemptExecutor.execute {
            // 模组启动就补一次扫描，避免上次异常退出的残留。
            sweepExemptions()
        }
    }

    private val exemptExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "free-idle-exempt").apply { isDaemon = true }
        }

    /** 包名 -> userId，只记本模组加过的。 */
    private val exemptedPackages = ConcurrentHashMap<String, Int>()

    private fun exemptFloat(record: Any?) {
        val pkg = OemReflection.readField(record, "packageName") as? String
        if (pkg.isNullOrEmpty()) return
        val userId = (OemReflection.readField(record, "mUserId") as? Int) ?: 0
        if (exemptedPackages.putIfAbsent(pkg, userId) != null) return
        exemptExecutor.execute {
            val ok = runCatching { deviceIdleCall("addPowerSaveWhitelistApp", pkg) }.getOrDefault(false)
            if (ok) ModuleLog.info("FLOAT_KEEP_ALIVE_EXEMPT_" + pkg) else ModuleLog.warn("FLOAT_KEEP_ALIVE_EXEMPT_FAILED_" + pkg)
            // ColorOS 自己的 Hans 冻结不看 doze 白名单，另挂它那套自定义白名单（息屏 LcdOff 时用）。
            hansCustomWhite(pkg, true)
        }
        scheduleExemptSweep()
    }

    private fun scheduleExemptSweep() {
        if (exemptSweepScheduled.compareAndSet(false, true)) {
            handler.postDelayed({ exemptSweepScheduled.set(false); exemptExecutor.execute { sweepExemptions() } }, EXEMPT_SWEEP_MS)
        }
    }

    private val exemptSweepScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sweepExemptions() {
        val stillFloating = HashSet<String>()
        if (configuration.snapshot.floatKeepAliveEffective) {
            rotationRecords.forEach { (taskId, reference) ->
                val record = reference.get() ?: return@forEach
                if (!isFloating(taskId)) return@forEach
                (OemReflection.readField(record, "packageName") as? String)?.let { stillFloating.add(it) }
            }
        }
        val released = exemptedPackages.keys.filter { it !in stillFloating }
        released.forEach { pkg ->
            exemptedPackages.remove(pkg)
            val ok = runCatching { deviceIdleCall("removePowerSaveWhitelistApp", pkg) }.getOrDefault(false)
            if (ok) ModuleLog.info("FLOAT_KEEP_ALIVE_EXEMPT_OFF_" + pkg) else ModuleLog.warn("FLOAT_KEEP_ALIVE_EXEMPT_OFF_FAILED_" + pkg)
            hansCustomWhite(pkg, false)
        }
        if (exemptedPackages.isNotEmpty()) scheduleExemptSweep()
    }

    private fun deviceIdleCall(method: String, pkg: String): Boolean {
        val iface = deviceIdleController() ?: return false
        val m = iface.javaClass.getMethod(method, String::class.java)
        m.invoke(iface, pkg)
        return true
    }

    private var deviceIdleControllerCache: Any? = null

    private fun deviceIdleController(): Any? {
        deviceIdleControllerCache?.let { return it }
        val binder =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as? android.os.IBinder ?: return null
        val stub = Class.forName("android.os.IDeviceIdleController\$Stub")
        val iface =
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                ?: return null
        deviceIdleControllerCache = iface
        return iface
    }

    /**
     * ColorOS 自己的 Hans（`OplusHansManager`）冻结不看 doze 白名单。
     *
     * 实测：贴边后息屏，Hans 直接 `freeze uid: … scene: LcdOff`，应用「一息屏就停」。
     * 贴边期间把它挂进 Hans 自己的「自定义白名单」，离开浮标即撤。
     * 找不到这类（非 OPlus ROM）就静默跳过，不影响原有保活。
     */
    private fun hansCustomWhite(pkg: String, add: Boolean) {
        resolveHans()
        val method = (if (add) hansAddMethod else hansRemoveMethod) ?: return
        val getInstance = hansInstanceMethod ?: return
        val instance = runCatching { getInstance.invoke(null) }.getOrNull() ?: return
        val value = if (method.parameterTypes[0].isArray) arrayOf(pkg) else arrayListOf(pkg)
        runCatching { method.invoke(instance, value) }
            .onSuccess { ModuleLog.info("FLOAT_KEEP_ALIVE_HANS_WHITE_" + (if (add) "+" else "-") + pkg) }
            .onFailure { ModuleLog.warn("FLOAT_KEEP_ALIVE_HANS_WHITE_FAILED_" + pkg, it) }
    }

    private fun resolveHans() {
        if (hansResolved) return
        hansResolved = true
        val loader = serverLoader ?: return
        runCatching {
            val type = Class.forName(HANS_MANAGER_CLASS, false, loader)
            hansInstanceMethod = type.getDeclaredMethod("getInstance").apply { isAccessible = true }
            hansAddMethod = findHansWhiteMethod(type, "addCustomizeWhiteApp")
            hansRemoveMethod = findHansWhiteMethod(type, "removeCustomizeWhiteApp")
        }
        if (hansAddMethod != null && hansRemoveMethod != null) {
            ModuleLog.install("FLOAT_KEEP_ALIVE_HANS_API_READY")
        } else {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_HANS_API_MISSING")
        }
    }

    /** 签名随 ROM 版本变化：按名字找单参重载，参数是集合或 String[] 都接受。 */
    private fun findHansWhiteMethod(type: Class<*>, name: String): Method? {
        val candidates =
            (type.methods.asList() + type.declaredMethods.asList())
                .filter { it.name == name && it.parameterCount == 1 }
        val method =
            candidates.firstOrNull { it.parameterTypes[0].isAssignableFrom(ArrayList::class.java) }
                ?: candidates.firstOrNull {
                    it.parameterTypes[0].isArray && it.parameterTypes[0].componentType == String::class.java
                }
        return method?.apply { isAccessible = true }
    }


    /**
     * 直接挡 Hans 冻结（白名单那条在 ColorOS 17 上实测无效）。
     *
     * 实测日志：贴边 + 息屏后 Hans 仍然 `freeze uid: 10401 … scene: LcdOff`，
     * 尽管自定义白名单已挂上、`secure no_frozen` 也加过。这里把 Hans 管理器里所有
     * `freeze*` 方法挂一遍，参数里出现保活浮标应用的 uid/pid 就跳过这一次冻结。
     */

    /**
     * 兜底：直接拦住真正执行冻结的底层入口。
     *
     * Hans 那层判断（白名单 / wakelock 豁免）在这台机器上都没命中，但冻结最终要落到
     * `Process.setProcessFrozen` 或 `CachedAppOptimizer.freezeProcess`。这里按 pid/uid
     * 识别保活应用，命中就跳过这一次冻结。
     */
    private fun installLowLevelFreezeGuard(classLoader: ClassLoader) {
        var hooked = 0
        for (className in LOW_FREEZE_CLASSES) {
            val type = runCatching { classLoader.loadClass(className) }.getOrNull() ?: continue
            for (method in type.declaredMethods) {
                val name = method.name.lowercase()
                if (!name.contains("freezeprocess") &&
                    !name.contains("setprocessfrozen") &&
                    !name.contains("freezeapp")
                ) {
                    continue
                }
                if (method.returnType != Void.TYPE && method.returnType != Boolean::class.javaPrimitiveType) continue
                runCatching { method.isAccessible = true }
                runCatching {
                    module
                        .hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("free.low_freeze_" + className.hashCode() + "_" + method.name.hashCode())
                        .intercept { chain ->
                            val kept = keptFloatIdentities()
                            val hit =
                                chain.args.any { arg ->
                                    when (arg) {
                                        is Int -> arg in kept
                                        is IntArray -> arg.any { it in kept }
                                        else -> false
                                    }
                                }
                            if (hit) {
                                ModuleLog.info("FLOAT_KEEP_ALIVE_LOW_FREEZE_SKIPPED_" + method.name)
                                if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                            } else {
                                chain.proceed()
                            }
                        }
                }
                hooked++
            }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_LOW_FREEZE_HOOKED_" + hooked)
    }

    private fun installHansFreezeGuard(classLoader: ClassLoader) {
        val outer = runCatching { classLoader.loadClass(HANS_MANAGER_CLASS) }.getOrNull() ?: return
        val types = ArrayList<Class<*>>(4)
        types.add(outer)
        runCatching { outer.declaredClasses.forEach { types.add(it) } }
        val methods = ArrayList<Method>(32)
        val names = ArrayList<String>(32)
        for (type in types) {
            for (method in type.declaredMethods) {
                val name = method.name.lowercase()
                if (!name.contains("freeze") || name.contains("unfreeze")) continue
                runCatching { method.isAccessible = true }
                methods.add(method)
                names.add(method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } + ")")
            }
        }
        if (methods.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_HANS_FREEZE")
            return
        }
        ModuleLog.info("FLOAT_KEEP_ALIVE_HANS_FREEZE_METHODS_" + names.take(40).joinToString("|"))
        methods.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.hans.freeze_$index")
                .intercept { chain ->
                    runCatching { logHansFreezeCall(method.name, chain.args) }
                    val kept = keptFloatIdentities()
                    val packages = keptFloatPackages().toSet()
                    val hit = chain.args.any { arg -> argContains(arg, kept, packages) }
                    if (hit) {
                        ModuleLog.info("FLOAT_KEEP_ALIVE_HANS_FREEZE_HIT_" + method.name)
                        if (method.returnType == Void.TYPE) {
                            logHansFreezeSkipped(method.name)
                            null
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
        ModuleLog.install("FLOAT_KEEP_ALIVE_HANS_FREEZE_HOOKED_" + methods.size)
        // Hans 自己的「持有 wakelock 就免冻」判断：保活应用一律当免冻处理。
        val exempt =
            types.flatMap { it.declaredMethods.asList() }.filter {
                it.name.contains("isExemptFreezeImAppByWakelock") &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterCount >= 1
            }
        exempt.forEach { method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.hans.exempt_" + method.name.hashCode())
                .intercept { chain ->
                    val uid = chain.getArg(0) as? Int ?: -1
                    if (uid in keptFloatIdentities()) true else chain.proceed()
                }
        }
    }

    /** 冻结参数里可能直接是 uid/pid，也可能是它们的数组或集合；递归找一遍。 */
    private fun argContains(arg: Any?, kept: Set<Int>, packages: Set<String>): Boolean =
        when (arg) {
            null -> false
            is Int -> arg in kept
            is IntArray -> arg.any { it in kept }
            is Array<*> -> arg.any { argContains(it, kept, packages) }
            is Iterable<*> -> arg.any { argContains(it, kept, packages) }
            is String -> arg in packages
            else -> false
        }

    /** 保活浮标应用的 uid 与主进程 pid（Hans 的冻结参数可能是任意一种）。 */
    private fun keptFloatIdentities(): Set<Int> {
        val out = HashSet<Int>(4)
        rotationRecords.forEach { (taskId, reference) ->
            if (!isFloating(taskId)) return@forEach
            val record = reference.get() ?: return@forEach
            (OemReflection.callNoArg(record, "getUid") as? Int)?.let { out.add(it) }
            val app = OemReflection.readField(record, "app")
            (OemReflection.readField(app, "pid") as? Int)?.let { out.add(it) }
        }
        return out
    }

    /** 首次被调用时打一行（方法名 + 参数摘要），用来确认真正的冻结入口。 */
    private fun logHansFreezeCall(methodName: String, args: List<Any?>) {
        if (hansFreezeCallLogged.putIfAbsent(methodName, true) != null) return
        val summary = args.joinToString(",") { arg ->
            when (arg) {
                is Int -> arg.toString()
                is IntArray -> arg.take(6).joinToString(";")
                is Array<*> -> "array" + arg.size
                is Iterable<*> -> "list" + arg.count()
                is String -> arg
                null -> "null"
                else -> arg.javaClass.simpleName
            }
        }
        ModuleLog.info("FLOAT_KEEP_ALIVE_HANS_FREEZE_CALL_" + methodName + "_" + summary)
    }

    private val hansFreezeCallLogged = ConcurrentHashMap<String, Boolean>()

    /** GameManagerServiceExtImpl 的实例与方法：第一次被系统调用时捕获，之后用来查询。 */
    private var gameExtInstance: Any? = null
    private var gameExtMethod: Method? = null

    /** 游戏判定缓存：包名 -> 是否游戏。 */
    private val gameCache = ConcurrentHashMap<String, Boolean>()
    private val gameDetectLogged = ConcurrentHashMap<String, Boolean>()

    /** 浮标内起新页面后的窗口期：只对非游戏应用生效。 */
    @Volatile private var suppressFloatEjectAt = 0L

    /** 解锁后的过渡窗口：这段时间里不要把浮标任务 reset 成全屏。 */
    @Volatile private var unlockSettledUntil = 0L

    private var keptPackagesCache: Set<String>? = null
    private var keptPackagesAt = 0L

    private fun logHansFreezeSkipped(methodName: String) {
        ModuleLog.info("FLOAT_KEEP_ALIVE_HANS_FREEZE_SKIPPED_" + methodName)
    }

    private fun logResetSkipped(taskId: Int) {
        if (resetSkippedLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_SKIP_FULLSCREEN_task$taskId")
    }

    private val resetSkippedLogged = ConcurrentHashMap<Int, Boolean>()

    private fun isLockedOrOff(controller: Any?): Boolean {
        if (OemReflection.readField(controller, "mIsKeyGuardShowing") == true) return true
        val wms = OemReflection.readField(controller, "mFlexibleWindowManagerService")
        return OemReflection.callNoArg(wms, "isScreenOff") == true
    }

    private fun isRotationChange(oldArg: Any?, newArg: Any?): Boolean {
        if (oldArg == null || newArg == null) return false
        val oldConfig = OemReflection.readField(oldArg, "windowConfiguration") ?: return false
        val newConfig = OemReflection.readField(newArg, "windowConfiguration") ?: return false
        val oldRotation = OemReflection.readField(oldConfig, "mRotation") as? Int ?: return false
        val newRotation = OemReflection.readField(newConfig, "mRotation") as? Int ?: return false
        return oldRotation != newRotation
    }

    private fun beginRotation() {
        if (!rotationGuard.compareAndSet(false, true)) return
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if (OemReflection.callBoolean(record, "setVisibleRequested", false)) {
                logRotation(taskId, "release")
            }
        }
    }

    private fun scheduleRotationEnd() {
        // 原厂旋转过渡可能拖到几秒，单点 800ms 复查会漏，这里多查几次。
        ROTATION_CHECK_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (delay >= ROTATION_SETTLE_MS) finishRotation(dropGuard = true) else finishRotation(dropGuard = false)
            }, delay)
        }
    }

    private fun finishRotation(dropGuard: Boolean) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            // 旋转过程中窗口被重新提交成可见的，这里再压回去一次。
            if (OemReflection.readField(record, "mVisibleRequested") == true) {
                if (OemReflection.callBoolean(record, "setVisibleRequested", false)) {
                    logRotation(taskId, "rehide")
                }
            }
        }
        if (dropGuard) rotationGuard.set(false)
    }

    /**
     * 采样进程状态：/proc/<pid>/stat 的第三列（T = 被 freezer 停住）与 freezer cgroup。
     * 用来判断「息屏后应用没声音了」到底是掉档被冻，还是它自己停了播放。
     */
    private fun logProcState(record: Any?, taskId: Int, phase: String) {
        val app = OemReflection.readField(record, "app") ?: return
        val pid = (OemReflection.readField(app, "pid") as? Int) ?: return
        val stat = runCatching { java.io.File("/proc/" + pid + "/stat").readText() }.getOrNull()
        val state = stat?.substringAfterLast(")")?.trim()?.split(" ")?.firstOrNull() ?: "?"
        val freezer =
            runCatching {
                java.io.File("/proc/" + pid + "/cgroup").readText().lineSequence()
                    .firstOrNull { line -> line.contains("freezer") }?.substringAfter(":")?.trim()
            }.getOrNull() ?: "?"
        ModuleLog.info("FLOAT_KEEP_ALIVE_PROC_task" + taskId + "_" + phase + "_state" + state + "_freezer" + freezer)
    }

    /** 浮标任务每经历一个不同状态记一行，用于看清息屏期间它走到哪一步。 */
    private fun logStateOnce(record: Any?, state: String) {
        val target = record ?: return
        val task = OemReflection.readField(target, "task") ?: OemReflection.readField(target, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: return
        if (stateLogged.putIfAbsent(taskId.toString() + "/" + state, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_STATE_task" + taskId + "_" + state)
    }

    private val stateLogged = ConcurrentHashMap<String, Boolean>()

    /** 浮标任务被抬成可见时记一行（排障：谁把窗口顶出来的）。 */
    private fun logRaised(record: Any?) {
        val target = record ?: return
        val task = OemReflection.readField(target, "task") ?: OemReflection.readField(target, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: return
        if (raisedLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_RAISED_task" + taskId)
    }

    private val raisedLogged = ConcurrentHashMap<Int, Boolean>()

    private fun logRotation(taskId: Int, reason: String) {
        if (rotationLogged.putIfAbsent(taskId.toString() + "/" + reason, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_ROTATION_task" + taskId + "_" + reason)
    }


    private fun markPausing(fragment: Any?, record: Any?) {
        if (fragment == null || record == null) return
        var type: Class<*>? = fragment.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mPausingActivity") }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(fragment, record)
                }
                return
            }
            type = type.superclass
        }
    }

    private fun scheduleFakePause(record: Any?): Boolean {
        val target = record ?: return false
        val atmService = OemReflection.readField(target, "mAtmService") ?: return false
        val controller = OemReflection.readField(atmService, "mActivityClientController") ?: return false
        val handler = OemReflection.readField(atmService, "mH") as? Handler ?: return false
        val token = OemReflection.readField(target, "token") as? IBinder ?: return false
        val pausedMethod =
            runCatching {
                controller.javaClass.getDeclaredMethod("activityPaused", IBinder::class.java)
                    .apply { isAccessible = true }
            }.getOrNull() ?: return false
        handler.post {
            runCatching { pausedMethod.invoke(controller, token) }
                .onFailure {
                    ModuleLog.warn("FLOAT_KEEP_ALIVE_FAKE_PAUSE_FAILED", it)
                }
        }
        return true
    }

    private fun resolveController(classLoader: ClassLoader) {
        runCatching {
            val type = classLoader.loadClass(CONTROLLER_CLASS)
            getInstanceMethod = type.getDeclaredMethod("getInstance").apply { isAccessible = true }
            isInFloatingListMethod =
                type
                    .getDeclaredMethod("isInFloatingList", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
        }.onFailure {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_CONTROLLER_UNAVAILABLE", it)
        }
    }

    private fun shouldKeep(record: Any?): Boolean {
        if (record == null) return false
        if (!configuration.snapshot.floatKeepAliveEffective) return false
        if (OemReflection.readField(record, "finishing") == true) return false
        val task = OemReflection.readField(record, "task") ?: OemReflection.readField(record, "mTask") ?: return false
        val taskId = (OemReflection.readField(task, "mTaskId") as? Int) ?: return false
        if (!isFloating(taskId)) return false
        if (rotationRecords[taskId]?.get() !== record) {
            rotationRecords[taskId] = WeakReference(record)
        }
        exemptFloat(record)
        return true
    }

    private fun isFloating(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val getInstance = getInstanceMethod ?: return false
        val isInList = isInFloatingListMethod ?: return false
        val controller = runCatching { getInstance.invoke(null) }.getOrNull() ?: return false
        return runCatching { isInList.invoke(controller, taskId) == true }.getOrDefault(false)
    }

    /**
     * 可见性两道钩子只在「屏幕亮着且没锁屏」时生效。
     *
     * 锁屏时如果仍把浮标任务按成可见，窗口管理器会把任务表面（带浮标裁切）显示出来，
     * 锁屏上就能看到一小块迷你窗；放开可见性则只留浮标，暂停/停止照样拦着。
     */
    private fun shouldKeepVisible(record: Any?): Boolean =
        // 贴边挂机：锁屏/息屏期间也必须保持可见，否则应用会自己停音频；
        // 窗口由系统压在锁屏之下，用户看不到，不会把浮标画到锁屏上。
        shouldKeep(record) && !rotationGuard.get() && !unlockGuard.get()

    private fun isDisplayLockedOrOff(record: Any?): Boolean {
        val display = OemReflection.callNoArg(record, "getDisplayContent") ?: return false
        val policy = OemReflection.callNoArg(display, "getDisplayPolicy")
        if (policy != null) {
            if (OemReflection.callNoArg(policy, "isKeyguardShowing") == true) return true
            if (OemReflection.readField(policy, "mAwake") == false) return true
        }
        val state = OemReflection.readField(OemReflection.readField(display, "mDisplayInfo"), "state") as? Int
        return state == DISPLAY_STATE_OFF
    }


    private fun logRelease(record: Any?, reason: String) {
        val target = record ?: return
        val task = OemReflection.readField(target, "task") ?: OemReflection.readField(target, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: -1
        if (releasedTasks.putIfAbsent("$taskId/$reason", true) != null) return
        if (taskId > 0) {
            // Each task/reason gets at most one bounded diagnostic sample sequence.
            PROC_SAMPLE_DELAYS_MS.forEach { delay ->
                handler.postDelayed({ logProcState(target, taskId, "t" + (delay / 1000)) }, delay)
            }
        }
        ModuleLog.info("FLOAT_KEEP_ALIVE_RELEASE_task$taskId" + "_" + reason)
    }

    private fun logSkip(record: Any?, reason: String) {
        val target = record ?: return
        val task = OemReflection.readField(target, "task") ?: OemReflection.readField(target, "mTask")
        val taskId = (task?.let { OemReflection.readField(it, "mTaskId") } as? Int) ?: -1
        if (loggedTasks.putIfAbsent(taskId, true) == null) {
            ModuleLog.info("FLOAT_KEEP_ALIVE_KEEP_task$taskId" + "_" + reason)
        }
    }



    private companion object {
        const val TAG = "Free"
        const val RECORD_CLASS = OemClasses.ACTIVITY_RECORD
        /** 点开/摘掉浮标后多久内，展开引起的可见性请求一律放行。 */
        const val UNFLOAT_PERMIT_MS = 3_000L

        const val CONTROLLER_CLASS = OemClasses.FLOAT_HANDLE_CONTROLLER
        const val TASK_CLASS = OemClasses.TASK
        const val TOKEN_CLASS = OemClasses.WINDOW_TOKEN

        /** ColorOS 音频加固：决定要不要拒绝后台应用的音频焦点申请。 */
        const val HARDENING_ENFORCER_CLASS = "com.android.server.audio.HardeningEnforcer"

        /** 电源服务的 Binder 实现：应用的 isInteractive()/省电查询都从这里过。 */
        const val POWER_BINDER_CLASS = "com.android.server.power.PowerManagerService\$BinderService"

        /** 广播分发的收口：在这里可以按包名排除某条广播的接收者。 */
        const val BROADCAST_CONTROLLER_CLASS = "com.android.server.am.BroadcastController"

        /** 显示服务的 Binder 实现：`Display.getState()` 等查询都从这里过。 */
        const val DISPLAY_BINDER_CLASS = "com.android.server.display.DisplayManagerService\$BinderService"

        /** Display.STATE_ON */
        const val DISPLAY_STATE_ON = 2

        /** 窗口管理服务：应用的 KeyguardManager.isKeyguardLocked() 最终落到这里。 */
        const val WMS_CLASS = OemClasses.WINDOW_MANAGER_SERVICE
        const val TASK_FRAGMENT_CLASS = OemClasses.TASK_FRAGMENT
        val STOP_STATES = setOf("STOPPING", "STOPPED")

        /** ColorOS 的小窗控制器，横竖屏切换时由它重排浮动窗口。 */
        const val FLEXIBLE_CONTROLLER_CLASS = OemClasses.FLEXIBLE_TASK_CONTROLLER

        /** 旋转过渡结束后留一点余量再收回守卫。 */
        const val ROTATION_SETTLE_MS = 800L

        /** 放开可见性后采样进程状态的时间点（毫秒）。 */
        val PROC_SAMPLE_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L)

        /** 旋转后复查窗口是否被重新提交的时间点（毫秒）。 */
        val ROTATION_CHECK_DELAYS_MS = longArrayOf(800L, 2000L, 4000L, 8000L)

        /** 解锁过渡期间复查浮标是否被顶出来的时间点（毫秒），最后一个点后收回守卫。 */
        val UNLOCK_CHECK_DELAYS_MS = longArrayOf(800L, 1600L, 2600L)

        /** Display.STATE_OFF */
        const val DISPLAY_STATE_OFF = 1

        /** 复查白名单豁免的时间间隔（毫秒）。 */
        const val EXEMPT_SWEEP_MS = 60_000L

        /** ColorOS 的 Hans 冻结管理器：贴边期间要挂它的自定义白名单，息屏才不会被冻。 */
        const val HANS_MANAGER_CLASS = "com.android.server.am.OplusHansManager"

        /** 真正执行冻结的底层类。 */
        val LOW_FREEZE_CLASSES = listOf("android.os.Process", "com.android.server.am.CachedAppOptimizer")

        /** 窗口可见性回调里复用包名集合的时长（毫秒）。 */
        const val KEPT_PACKAGES_TTL_MS = 1_000L

        /** 解锁后不把浮标任务转全屏的过渡时长（毫秒）。 */
        const val UNLOCK_SETTLE_MS = 3_000L

        /** 浮标内起新页面后，吃掉「移出浮标」的窗口期（毫秒）。 */
        const val FLOAT_EJECT_WINDOW_MS = 2_500L

        /** ColorOS 的游戏识别入口（云端包名表）。 */
        const val GAME_MANAGER_EXT = "com.android.server.app.GameManagerServiceExtImpl"

        /** ApplicationInfo.CATEGORY_GAME */
        const val CATEGORY_GAME = 2
    }
}
