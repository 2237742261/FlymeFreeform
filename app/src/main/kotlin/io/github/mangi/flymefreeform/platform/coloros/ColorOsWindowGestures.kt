package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.mangi.flymefreeform.hook.OemReflection
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.platform.Haptics
import io.github.mangi.flymefreeform.log.ModuleLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 长按**迷你窗**静音/恢复。
 *
 * 命中条件：按下时窗口已经处于系统的迷你窗形态（`isTaskInFlexibleState(task, 2)` 或
 * `getFlexibleZoomState() == 2`），按压点落在它的可见范围内，持续超过系统长按阈值、
 * 位移不超过触摸 slop。
 *
 * 只在迷你窗上生效：长按普通小窗是系统自己的「缩成迷你窗」手势，模块不能在同一段
 * 长按里去静音，否则用户每次想把窗口缩小都会把应用静音。形态必须在 **ACTION_DOWN
 * 那一刻**判定——等到长按阈值再判，普通小窗已经先被系统缩成迷你窗了，照样误判。
 *
 * 静音是写进 AudioService 的 per-uid 音量（`PlaybackActivityMonitor.mPamExt.
 * setVolumeForUid`），不会整体静音、不影响别的应用；但它会一直留在 AudioService 里，
 * 模块不主动回收就是「这个应用被永久静音」。所以静音只跟着**这个窗口**走：迷你窗、
 * 点开成的普通小窗、收成的贴边浮标都继续静音，窗口真的关掉之后由
 * [WindowMuteEvents] 的事件恢复音量（退场到后台 / 任务被移除），不轮询。
 */
@SuppressLint("PrivateApi")
internal class ColorOsWindowGestures(
    private val context: Context,
    private val controller: Any,
    private val configuration: ProcessConfiguration,
    private val handler: Handler,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var pressedTask: Any? = null
    @Volatile private var armed = false
    val isTracking: Boolean get() = armed
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    /** 静音时按下的窗口：任务号用来匹配「这个窗口没了」的事件。 */
    private class MutedWindow(val pkg: String, val taskId: Int)

    /** 被本功能静音的 uid → 静音时按下的那个窗口。 */
    private val muted = ConcurrentHashMap<Int, MutedWindow>()

    init {
        WindowMuteEvents.observe(::onWindowClosed)
    }

    /**
     * 窗口没了：退场到后台（`exitFlexibleTaskWindowInnerLocked` 非浮动策略）或任务被移除。
     *
     * 收成贴边浮标不算「没了」，那条路径不会发事件，静音继续保持。
     * 事件来自系统的退场/移除路径（可能正持着窗口锁），恢复动作扔回主线程做。
     */
    private fun onWindowClosed(taskId: Int) {
        if (muted.isEmpty()) return
        handler.post { restoreForClosedTask(taskId) }
    }

    private fun restoreForClosedTask(taskId: Int) {
        val target = muted.entries.firstOrNull { it.value.taskId == taskId } ?: return
        if (setVolumeForUid(RESTORE_VOLUME, target.key, target.value.pkg)) {
            muted.remove(target.key)
            ModuleLog.info("MINI_LONG_PRESS_AUTO_UNMUTE_" + target.value.pkg)
        } else {
            // 恢复失败就把台账留着：关功能/停用模块时的 releaseAll 还会再试一次。
            ModuleLog.warn("MINI_LONG_PRESS_MUTE_UNAVAILABLE_" + target.value.pkg)
        }
    }

    private val fire =
        Runnable {
            if (!armed) return@Runnable
            if (!configuration.snapshot.longPressMuteEffective) {
                cancel()
                return@Runnable
            }
            armed = false
            fired = true
            val task = pressedTask ?: return@Runnable
            runCatching { toggleMute(task) }
                .onFailure { ModuleLog.warn("WINDOW_MUTE_FAILED", it) }
        }

    /** 返回 true 表示这一下已经触发过静音，交给上层决定是否消费。 */
    fun onEvent(event: MotionEvent): Boolean {
        if (!configuration.snapshot.longPressMuteEffective) {
            cancel()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                fired = false
                downX = event.x
                downY = event.y
                val task = miniWindowAt(event.x, event.y) ?: return false
                pressedTask = task
                armed = true
                handler.postDelayed(fire, longPressTimeout)
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancel()
            MotionEvent.ACTION_MOVE -> {
                if (!armed) return fired
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > SLOP_PX * SLOP_PX) cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasFired = fired
                cancel()
                fired = false
                return wasFired
            }
        }
        return false
    }

    private fun cancel() {
        armed = false
        handler.removeCallbacks(fire)
    }

    private fun taskIdOf(task: Any): Int {
        var type: Class<*>? = task.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mTaskId") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return (runCatching { field.get(task) }.getOrNull() as? Int) ?: -1
            }
            type = type.superclass
        }
        return -1
    }

    /**
     * 按下位置所在的迷你窗任务；普通小窗、贴边悬挂、没有浮动窗口都返回 null。
     *
     * 形态必须在 DOWN 当帧判定并锁定：等到长按阈值再判，系统可能已经把普通小窗缩成了
     * 迷你窗，同一段长按就会被误当成迷你窗长按。
     */
    private fun miniWindowAt(x: Float, y: Float): Any? {
        val task = OemReflection.call(controller, "getTopZoomTask") ?: return null
        val taskBounds = OemReflection.call(task, "getBounds") as? Rect ?: return null
        // 迷你窗是缩放后的窗口：task 的 bounds 仍是整块显示区域，长按屏幕任何位置都会落进去。
        // 系统的 getFlexibleTaskVisibleBounds() 给的是实际可见（缩放后）的矩形，按它判定，
        // 「在迷你窗外面长按」才不会被算成迷你窗长按；取不到时退回 task bounds。
        val visible = visibleBoundsOf(task) ?: taskBounds
        if (!visible.contains(x.toInt(), y.toInt())) return null
        val metrics = context.resources.displayMetrics
        val apiSaysMini =
            ColorOsFlexibleWindowState.isInState(controller, task, ColorOsFlexibleWindowState.MINI)
        val state = ColorOsFlexibleWindowState.zoomStateOf(task)
        val size =
            "vb" + visible.width() + "x" + visible.height() +
                "_tb" + taskBounds.width() + "x" + taskBounds.height()
        if (ColorOsFlexibleWindowState.isMiniWindow(
                apiSaysMini,
                state,
                visible.width(),
                visible.height(),
                metrics.widthPixels,
                metrics.heightPixels,
            )
        ) {
            ModuleLog.info("MINI_LONG_PRESS_ARMED_api" + apiSaysMini + "_zoom" + state + "_" + size)
            return task
        }
        // Ordinary-window touches are left to ColorOS; do not log each normal tap.
        return null
    }

    /** 系统给出的实际可见矩形；取不到或为空返回 null。 */
    private fun visibleBoundsOf(task: Any): Rect? {
        val rect =
            OemReflection.call(controller, "getFlexibleTaskVisibleBounds", task) as? Rect ?: return null
        return if (rect.isEmpty) null else rect
    }

    fun toggleMute(task: Any?) {
        val target = task ?: return
        val pkg = OemReflection.call(target, "getBasePackageName") as? String ?: return
        val uid =
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                ?: return
        val previous = muted.remove(uid)
        if (previous != null) {
            if (setVolumeForUid(RESTORE_VOLUME, uid, pkg)) {
                Haptics.confirmation(context)
                ModuleLog.info("MINI_LONG_PRESS_unmute_" + pkg)
            } else {
                // 恢复失败就记回台账：窗口关掉时还会再试一次，不能让应用留在静音里。
                muted[uid] = previous
                ModuleLog.warn("MINI_LONG_PRESS_MUTE_UNAVAILABLE_" + pkg)
            }
            return
        }
        val taskId = taskIdOf(target)
        if (taskId <= 0) {
            // 拿不到任务号就跟不了「这个窗口没了」的事件：宁可不静音，也不要把应用永久静音。
            ModuleLog.warn("MINI_LONG_PRESS_NO_TASK_ID_" + pkg)
            return
        }
        if (!hasActiveMedia(uid)) return
        if (!setVolumeForUid(MUTED_VOLUME, uid, pkg)) {
            ModuleLog.warn("MINI_LONG_PRESS_MUTE_UNAVAILABLE_" + pkg)
            return
        }
        muted[uid] = MutedWindow(pkg, taskId)
        Haptics.confirmation(context)
        ModuleLog.info("MINI_LONG_PRESS_mute_" + pkg)
    }

    /** 把已经静音的应用全部恢复回去：关闭功能、模块停用时调用。 */
    fun releaseAll() {
        if (muted.isEmpty()) return
        for ((uid, entry) in muted) {
            if (setVolumeForUid(RESTORE_VOLUME, uid, entry.pkg)) muted.remove(uid)
        }
        if (muted.isEmpty()) ModuleLog.info("MINI_LONG_PRESS_RELEASED_ALL")
    }

    /**
     * 只改这一个 uid 的播放音量：AudioService.mPlaybackMonitor.mPamExt.setVolumeForUid(...)。
     * 依次尝试 ext 与 monitor 本身，接口不存在就返回 false（由调用方记 WARN）。
     */
    private fun setVolumeForUid(volume: Float, uid: Int, pkg: String): Boolean {
        val audioService = audioService() ?: return false
        val monitor = OemReflection.readField(audioService, "mPlaybackMonitor") ?: return false
        val ext = OemReflection.readField(monitor, "mPamExt")
        for (target in listOfNotNull(ext, monitor)) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val method =
                    runCatching {
                        type!!.declaredMethods.firstOrNull {
                            it.name == "setVolumeForUid" && it.parameterCount == 4
                        }
                    }.getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return runCatching { method.invoke(target, volume, uid, pkg, true) }.isSuccess
                }
                type = type.superclass
            }
        }
        return false
    }

    private fun hasActiveMedia(uid: Int): Boolean = runCatching {
        val configs = OemReflection.call(audioService(), "getActivePlaybackConfigurations") as? List<*>
            ?: return@runCatching false
        configs.filterIsInstance<android.media.AudioPlaybackConfiguration>().any { config ->
            val usage = config.audioAttributes.usage
            (usage == android.media.AudioAttributes.USAGE_MEDIA || usage == android.media.AudioAttributes.USAGE_GAME) &&
                OemReflection.call(config, "isActive") == true &&
                OemReflection.call(config, "getClientUid") == uid
        }
    }.getOrDefault(false)

    private fun audioService(): Any? {
        audioServiceCache?.let { return it }
        val binder =
            runCatching {
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "audio") as? android.os.IBinder
            }.getOrNull() ?: return null
        audioServiceCache = binder
        return binder
    }

    private var audioServiceCache: Any? = null

    private companion object {
        /** 位移超过这个像素数就不再算长按。 */
        const val SLOP_PX = 40f

        /** 静音态与恢复态的音量（per-uid 增益，1f = 正常）。 */
        const val MUTED_VOLUME = 0f
        const val RESTORE_VOLUME = 1f
    }
}
