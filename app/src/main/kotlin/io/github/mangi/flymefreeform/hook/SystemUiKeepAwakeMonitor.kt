package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.R
import io.github.mangi.flymefreeform.config.ModulePreferences
import java.lang.reflect.Method

/**
 * 临时屏幕常亮：system_server 侧检测到「屏幕压暗后又被点亮」时发一条广播，这里弹一个气泡；
 * 点一下让当前屏幕保持常亮，息屏或切到别的应用即释放。气泡点击带震动反馈。
 *
 * 压暗检测**不在这里做**：SystemUI 是 platform_app 域，读不到 /sys/class/backlight
 * （openat 直接 EACCES），以前只能每 1.5 秒 fork 一次 dumpsys display 兜底，
 * 那是全量显示状态 dump、跑在 system_server 主线程，会把桌面转场动画卡掉帧。
 * 检测已搬到 `KeepAwakeDimWatcher`（system_server 内反射 PowerManagerService，纯内存读）。
 */
@SuppressLint("PrivateApi")
internal class SystemUiKeepAwakeMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val thread = HandlerThread("flyme-keepawake").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var running = false
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLogAt = 0L
    // 申请常亮时的前台应用；切到别的应用就立刻释放，让系统重新开始息屏计时。
    private var keepPackage: String? = null
    private var lastForegroundCheckAt = 0L
    private var cachedTaskService: Any? = null
    private var focusMethod: Method? = null
    private var focusProbed = false

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                releaseScreenOn()
                dismissBubble()
            }
        }

    /** system_server 的 KeepAwakeDimWatcher 只在「压暗后又被点亮」时发这一条。 */
    private val dimLitReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ModulePreferences.ACTION_KEEP_AWAKE_DIM_LIT) return
                if (!configuration.snapshot.keepAwakeEnabled) return
                if (!powerManager.isInteractive) return
                if (isKeyguardLocked()) {
                    module.log(Log.INFO, TAG, "KEEP_AWAKE_BUBBLE_SKIP keyguard")
                    return
                }
                module.log(Log.INFO, TAG, "KEEP_AWAKE_BUBBLE_EVENT")
                showBubble()
            }
        }

    fun start() {
        if (running) return
        running = true
        try {
            context.registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "KEEP_AWAKE_RECEIVER_FAILED", exception)
        }
        try {
            context.registerReceiver(
                dimLitReceiver,
                IntentFilter(ModulePreferences.ACTION_KEEP_AWAKE_DIM_LIT),
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "KEEP_AWAKE_DIM_RECEIVER_FAILED", exception)
        }
        handler.post(poll)
        module.log(Log.INFO, TAG, "KEEP_AWAKE_MONITOR_START")
    }

    private val poll =
        object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    if (configuration.snapshot.keepAwakeEnabled) {
                        watchForegroundApp()
                    } else if (bubble != null) {
                        dismissBubble()
                    }
                } catch (exception: RuntimeException) {
                    logThrottled("KEEP_AWAKE_POLL_FAILED", exception)
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    private fun showBubble() {
        mainHandler.post {
            if (bubble != null) {
                return@post
            }
            val view = ImageView(context).apply {
                setImageDrawable(loadBubbleIcon())
                setBackgroundColor(Color.TRANSPARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                outlineProvider =
                    object : android.view.ViewOutlineProvider() {
                        override fun getOutline(target: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, target.width, target.height)
                        }
                    }
                clipToOutline = true
                setOnTouchListener { target, event -> onBubbleTouch(target, event) }
            }
            val layoutParams =
                WindowManager.LayoutParams(
                    bubbleSizePx(),
                    bubbleSizePx(),
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    x = dp(BUBBLE_MARGIN_X_DP)
                    y = dp(BUBBLE_MARGIN_BOTTOM_DP)
                }
            try {
                windowManager.addView(view, layoutParams)
                bubble = view
                params = layoutParams
                module.log(Log.DEBUG, TAG, "KEEP_AWAKE_BUBBLE_SHOW")
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "KEEP_AWAKE_BUBBLE_ADD_FAILED", exception)
                return@post
            }
            mainHandler.postDelayed({ dismissBubble() }, BUBBLE_AUTO_DISMISS_MS)
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.setPressed(true)
            MotionEvent.ACTION_UP -> {
                view.setPressed(false)
                vibrate()
                keepScreenOn()
                dismissBubble()
                return true
            }
            MotionEvent.ACTION_CANCEL -> view.setPressed(false)
        }
        return true
    }

    private fun dismissBubble() {
        mainHandler.post {
            val view = bubble ?: return@post
            bubble = null
            params = null
            try {
                windowManager.removeView(view)
            } catch (exception: RuntimeException) {
                // 窗口可能已经被系统回收。
            }
        }
    }

    private fun vibrate() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(
                VibrationEffect.createOneShot(VIBRATE_DURATION_MS, VIBRATE_AMPLITUDE),
            )
            module.log(Log.DEBUG, TAG, "KEEP_AWAKE_VIBRATE")
        } catch (exception: RuntimeException) {
            logThrottled("KEEP_AWAKE_VIBRATE_FAILED", exception)
        }
    }

    private fun keepScreenOn() {
        try {
            keepPackage = foregroundPackage()
            val lock =
                wakeLock ?: powerManager
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, WAKE_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
                    .also { wakeLock = it }
            if (!lock.isHeld) {
                lock.acquire()
            }
            module.log(Log.INFO, TAG, "KEEP_SCREEN_ON_ACQUIRED app=$keepPackage")
        } catch (exception: RuntimeException) {
            logThrottled("KEEP_SCREEN_ON_FAILED", exception)
        }
    }

    // 常亮期间盯住前台应用：换成别的应用就释放，等价于「重新进入息屏计时」。
    private fun watchForegroundApp() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) return
        val now = SystemClock.uptimeMillis()
        if (now - lastForegroundCheckAt < FOREGROUND_POLL_MS) return
        lastForegroundCheckAt = now
        val current = foregroundPackage() ?: return
        val anchor = keepPackage ?: return
        if (current != anchor) {
            module.log(Log.INFO, TAG, "KEEP_SCREEN_ON_APP_SWITCHED $anchor -> $current RELEASE")
            releaseScreenOn()
        }
    }

    /**
     * 取当前焦点任务的包名。
     *
     * 走 `ActivityTaskManager.getFocusedRootTaskInfo()`（只取焦点那一个任务），
     * 比 `getTasks(...)` 轻得多——后者要在 system_server 侧组装整个任务列表再序列化回来。
     * 拿不到就返回 null，避免误判释放。
     */
    private fun foregroundPackage(): String? {
        val service = activityTaskService() ?: return null
        if (!focusProbed) {
            focusProbed = true
            focusMethod =
                try {
                    service.javaClass.getMethod("getFocusedRootTaskInfo")
                } catch (exception: ReflectiveOperationException) {
                    module.log(Log.WARN, TAG, "KEEP_AWAKE_FOCUS_TASK_METHOD_MISSING", exception)
                    null
                }
        }
        val method = focusMethod ?: return null
        val info =
            try {
                method.invoke(service)
            } catch (exception: ReflectiveOperationException) {
                focusMethod = null
                logThrottled("KEEP_AWAKE_FOCUS_TASK_FAILED", exception)
                null
            } ?: return null
        return packageOfTask(info)
    }

    private fun activityTaskService(): Any? {
        cachedTaskService?.let { return it }
        return try {
            val type = Class.forName("android.app.ActivityTaskManager")
            val service = type.getMethod("getService").invoke(null)
            service?.also { cachedTaskService = it }
        } catch (exception: ReflectiveOperationException) {
            logThrottled("KEEP_AWAKE_TASK_SERVICE_UNAVAILABLE", exception)
            null
        } catch (exception: RuntimeException) {
            null
        }
    }

    private fun packageOfTask(task: Any): String? {
        for (field in arrayOf("topActivity", "baseActivity")) {
            val component =
                try {
                    task.javaClass.getField(field).get(task)
                } catch (_: ReflectiveOperationException) {
                    null
                } ?: continue
            val name =
                try {
                    component.javaClass.getMethod("getPackageName").invoke(component) as? String
                } catch (_: ReflectiveOperationException) {
                    null
                }
            if (!name.isNullOrEmpty()) return name
        }
        return null
    }

    private fun releaseScreenOn() {
        try {
            keepPackage = null
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                lock.release()
                module.log(Log.INFO, TAG, "KEEP_SCREEN_ON_RELEASED")
            }
        } catch (exception: RuntimeException) {
            logThrottled("KEEP_SCREEN_ON_RELEASE_FAILED", exception)
        }
    }

    private fun isKeyguardLocked(): Boolean =
        try {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (exception: RuntimeException) {
            false
        }

    private fun loadBubbleIcon() =
        try {
            val moduleContext =
                context.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                )
            moduleContext.resources.getDrawable(R.drawable.keep_awake_bubble, null)
        } catch (exception: RuntimeException) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_FALLBACK_BACKGROUND)
            }
        }

    private fun bubbleSizePx(): Int = dp(BUBBLE_SIZE_DP)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun logThrottled(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < LOG_INTERVAL_MS) return
        lastLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MODULE_PACKAGE = "com.eta.flymefreeform"
        const val WAKE_LOCK_TAG = "flymefreeform:keep"
        const val POLL_INTERVAL_MS = 1_000L
        const val FOREGROUND_POLL_MS = 2_000L
        const val BUBBLE_AUTO_DISMISS_MS = 6_000L
        const val BUBBLE_SIZE_DP = 44
        const val BUBBLE_MARGIN_X_DP = 12
        const val BUBBLE_MARGIN_BOTTOM_DP = 96
        const val VIBRATE_DURATION_MS = 60L
        const val VIBRATE_AMPLITUDE = 255
        const val LOG_INTERVAL_MS = 10_000L
        const val COLOR_FALLBACK_BACKGROUND = 0xFFF2B01E.toInt()
    }
}
