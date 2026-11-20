package io.github.mangi.flymefreeform.gesture

/** Restrict the caption observer to the central bottom handle band of visible task bounds. */
internal object CaptionHandleRegion {
    fun contains(x: Float, y: Float, left: Int, top: Int, right: Int, bottom: Int, density: Float): Boolean {
        if (right <= left || bottom <= top || !density.isFinite() || density <= 0f) return false
        val margin = (right - left) * 0.15f
        val stripTop = maxOf(top.toFloat(), bottom - 32f * density)
        return x >= left + margin && x <= right - margin && y >= stripTop && y <= bottom
    }
}
