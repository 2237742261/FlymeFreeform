package io.github.mangi.flymefreeform.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.mangi.flymefreeform.config.ModulePreferences

/**
 * 轮盘滑选与设置页强度条预览共用的一套触感，改一个数字两边同时变。
 *
 * 注意：不要改成 USAGE_TOUCH / PRIMITIVE_CLICK —— 系统「触感反馈」关闭时
 * （本机 haptic_feedback_enabled=0）这两类会被 VibratorManagerService 整体过滤掉，完全不震。
 */
internal object Haptics {
    private const val DURATION_MS = 20L

    fun tick(context: Context, strengthPercent: Int) {
        try {
            val vibrator = resolve(context) ?: return
            if (!vibrator.hasVibrator()) return
            val amplitude =
                (80 + ModulePreferences.coerceRadialVibrateStrength(strengthPercent) * 175 / 100)
                    .coerceIn(80, 255)
            vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, amplitude))
        } catch (_: RuntimeException) {
            // 触感失败不影响交互
        }
    }

    private fun resolve(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
