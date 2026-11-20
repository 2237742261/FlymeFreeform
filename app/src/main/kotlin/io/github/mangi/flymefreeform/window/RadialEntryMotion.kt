package io.github.mangi.flymefreeform.window

internal data class RadialEntryVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    /** Multiplier for the horizontal, inward overshoot distance; may become negative. */
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
)

/** Corner expansion and selection animation. */
internal object RadialEntryMotion {
    const val DURATION_MILLIS = 380L
    const val SELECTION_DURATION_MILLIS = 130L
    const val HORIZONTAL_OVERSHOOT_DP = 6f

    private const val EXPANSION_MILLIS = 130f

    private val fade = CubicMotionCurve(0.33f, 0f, 0.67f, 1f)
    private val expandPosition = CubicMotionCurve(0.24f, 0.55f, 0.53f, 0.8f)
    private val selection = CubicMotionCurve(0.33f, 0f, 0.66f, 1f)

    fun sample(linearProgress: Float): RadialEntryVisuals {
        // 展开仍在 130ms 内完成，只是不再旋转 / 过冲 / 回弹
        val expand =
            ((linearProgress.coerceIn(0f, 1f) * DURATION_MILLIS) / EXPANSION_MILLIS)
                .coerceIn(0f, 1f)
        return RadialEntryVisuals(
            contentAlpha = expand,
            radialProgress = expandPosition.sample(expand),
            horizontalOvershoot = 0f,
            rotationDegrees = 0f,
            iconScale = 1f,
        )
    }

    /** Eases the selected icon growth. */
    fun selectionProgress(linearProgress: Float): Float = selection.sample(linearProgress)

}

/** Evaluates time-based cubic motion while preserving values outside the unit interval. */
internal class CubicMotionCurve(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    fun sample(linearProgress: Float): Float {
        val x = linearProgress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        repeat(20) {
            val parameter = (low + high) * 0.5f
            if (coordinate(parameter, x1, x2) < x) {
                low = parameter
            } else {
                high = parameter
            }
        }
        // Time is bounded, but the curve's output must retain its overshoot.
        return coordinate((low + high) * 0.5f, y1, y2)
    }

    private fun coordinate(parameter: Float, first: Float, second: Float): Float {
        val remainder = 1f - parameter
        return 3f * remainder * remainder * parameter * first +
            3f * remainder * parameter * parameter * second +
            parameter * parameter * parameter
    }
}
