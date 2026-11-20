package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionHandleRegionTest {
    @Test fun captionModeNeverArmsOutsideTap() {
        val engine = OutsideTapGestureEngine()
        engine.updateMode(OutsideTapCloseMode.DoubleTap)
        engine.begin(Any(), 0, 10f, 10f, 0L, captured = true)
        assertTrue(engine.isTracking)
        engine.updateMode(OutsideTapCloseMode.CaptionDoubleTap)
        assertFalse(engine.isTracking)
        engine.begin(Any(), 0, 10f, 10f, 10L, captured = true)
        assertFalse(engine.isTracking)
        assertTrue(OutsideTapCloseMode.SingleTap.capturesOutsideTouches)
        assertTrue(OutsideTapCloseMode.DoubleTap.capturesOutsideTouches)
        assertFalse(OutsideTapCloseMode.Disabled.capturesOutsideTouches)
    }

    @Test fun onlyBottomCenterOfVisibleWindowIsEligible() {
        fun hit(x: Float, y: Float) = CaptionHandleRegion.contains(x, y, 100, 200, 500, 800, 2f)
        assertTrue(hit(300f, 790f))
        assertFalse(hit(300f, 700f))
        assertFalse(hit(300f, 801f))
        assertFalse(hit(105f, 790f))
        assertFalse(hit(600f, 790f))
    }
}
