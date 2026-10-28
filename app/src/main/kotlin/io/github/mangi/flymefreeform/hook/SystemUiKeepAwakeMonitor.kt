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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 临时屏幕常亮：屏幕压暗（DIM）之后用户点亮，弹一个气泡；点一下让当前屏幕保持常亮，
 * 息屏或再次点击即释放。气泡点击带震动反馈。
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
    private var dimmed = false
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLogAt = 0L
    // 申请常亮时的前台应用；切到别的应用就立刻释放，让系统重新开始息屏计时。
    private var keepPackage: String? = null
    private var lastForegroundCheckAt = 0L

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                dimmed = false
                releaseScreenOn()
                dismissBubble()
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
        handler.post(poll)
        module.log(Log.INFO, TAG, "KEEP_AWAKE_MONITOR_START")
    }

    private val poll =
        object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    if (configuration.snapshot.keepAwakeEnabled) {
                        tick()
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

    private fun tick() {
        val state = readDisplayPolicy() ?: return
        when {
            state == STATE_DIM -> dimmed = true
            state == STATE_OFF -> {
                dimmed = false
                releaseScreenOn()
            }
            dimmed -> {
                dimmed = false
                if (!isKeyguardLocked()) {
                    showBubble()
                }
            }
        }
    }

    /** 读 ColorOS 的显示电源状态：压暗是 policy=DIM，正常为 BRIGHT。 */
    private fun readDisplayPolicy(): Int? {
        val process =
            try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "/system/bin/sh",
                        "-c",
                        "$DUMPSYS_COMMAND 2>/dev/null | $GREP_COMMAND -m1 -E " +
                            "'mScreenState=|mPendingRequestLocked=policy='",
                    ),
                )
            } catch (exception: Exception) {
                logThrottled("KEEP_AWAKE_EXEC_FAILED", exception)
                return null
            }
        val output =
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readLine().orEmpty()
                }
            } catch (exception: Exception) {
                null
            }
        process.destroy()
        val line = output ?: return null
        return when {
            line.contains("DIM") -> STATE_DIM
            line.contains("OFF") || line.contains("DOZE") -> STATE_OFF
            line.contains("BRIGHT") || line.contains("ON") -> STATE_BRIGHT
            else -> null
        }
    }

    private fun isKeyguardLocked(): Boolean =
        try {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (exception: RuntimeException) {
            false
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

    // 顶层任务里的包名（A=uid:pkg）；拿不到就返回 null，避免误判释放。
    private fun foregroundPackage(): String? {
        val process =
            try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "/system/bin/sh",
                        "-c",
                        "$ACTIVITY_DUMPSYS 2>/dev/null | $GREP_COMMAND -m1 -E 'A=[0-9]+:'",
                    ),
                )
            } catch (exception: Exception) {
                logThrottled("KEEP_AWAKE_FOREGROUND_EXEC_FAILED", exception)
                return null
            }
        val line =
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readLine().orEmpty()
                }
            } catch (exception: Exception) {
                null
            }
        process.destroy()
        val match = line?.let { FOREGROUND_PATTERN.find(it) } ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
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
        const val DUMPSYS_COMMAND = "/system/bin/dumpsys display"
        const val GREP_COMMAND = "/system/bin/grep"
        const val WAKE_LOCK_TAG = "flymefreeform:keep"
        const val POLL_INTERVAL_MS = 700L
        const val FOREGROUND_POLL_MS = 1_000L
        const val ACTIVITY_DUMPSYS = "/system/bin/dumpsys activity activities"
        val FOREGROUND_PATTERN = Regex("A=\\d+:([A-Za-z0-9_.]+)")
        const val BUBBLE_AUTO_DISMISS_MS = 6_000L
        const val BUBBLE_SIZE_DP = 44
        const val BUBBLE_MARGIN_X_DP = 12
        const val BUBBLE_MARGIN_BOTTOM_DP = 96
        const val VIBRATE_DURATION_MS = 60L
        const val VIBRATE_AMPLITUDE = 255
        const val LOG_INTERVAL_MS = 10_000L
        const val COLOR_FALLBACK_BACKGROUND = 0xFFF2B01E.toInt()
        const val STATE_DIM = 1
        const val STATE_BRIGHT = 2
        const val STATE_OFF = 3
    }
}
