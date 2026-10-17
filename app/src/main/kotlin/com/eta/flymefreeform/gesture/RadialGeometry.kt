package com.eta.flymefreeform.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class GesturePoint(val x: Float, val y: Float)

internal data class RadialLayout(
    val side: CornerSide,
    val origin: GesturePoint,
    val radius: Float,
    val itemCenters: List<GesturePoint>,
)

internal object RadialGeometry {
    private const val SPAN_DEGREES = 86f
    private const val SAFE_DEGREES = 3f

    fun layout(
        side: CornerSide,
        width: Float,
        height: Float,
        radius: Float,
        itemCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RadialLayout {
        val origin = GesturePoint(offsetX + if (side == CornerSide.Left) 0f else width, offsetY + height)
        if (itemCount <= 0) return RadialLayout(side, origin, radius, emptyList())
        val step = SPAN_DEGREES / itemCount
        val centers =
            List(itemCount) { index ->
                // 列表末项是「更多」：固定占最靠近角落的 0 号槽（用户明确要求它钉在下方，
                // 2026-10-17）。应用按**列表顺序自上而下**排：index 0 在最上面的槽，
                // 最后一个应用紧贴「更多」上方。
                // ⚠️ 2026-10-17 之前是反的（index 0 在最靠近角落的 1 号槽），
                // 于是轮盘从下往上是列表正序、从上往下看是倒序，与设置页列表顺序相反。
                val slot = if (index == itemCount - 1) 0 else itemCount - 1 - index
                val angle =
                    if (side == CornerSide.Left) {
                        -SAFE_DEGREES - (slot + 0.5f) * step
                    } else {
                        -180f + SAFE_DEGREES + (slot + 0.5f) * step
                    }
                val radians = angle * PI.toFloat() / 180f
                GesturePoint(
                    x = origin.x + cos(radians) * radius,
                    y = origin.y + sin(radians) * radius,
                )
            }
        return RadialLayout(side, origin, radius, centers)
    }

    /**
     * 内圈排布：全部条目都是应用，均匀落在 [radiusInner] 的弧上（无「更多」特殊槽）。
     * 角度公式与外圈一致，保证两圈视觉上同心。
     */
    fun layoutInner(
        side: CornerSide,
        width: Float,
        height: Float,
        radiusInner: Float,
        itemCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RadialLayout {
        val origin = GesturePoint(offsetX + if (side == CornerSide.Left) 0f else width, offsetY + height)
        if (itemCount <= 0 || radiusInner <= 0f) {
            return RadialLayout(side, origin, radiusInner, emptyList())
        }
        val step = SPAN_DEGREES / itemCount
        val centers =
            List(itemCount) { index ->
                // @author bomo 内圈同样**自上而下**排（2026-10-17 用户要求内外圈一起改）：
                // index 0 落在最上面的槽，与外圈方向一致。
                val slot = itemCount - 1 - index
                val angle =
                    if (side == CornerSide.Left) {
                        -SAFE_DEGREES - (slot + 0.5f) * step
                    } else {
                        -180f + SAFE_DEGREES + (slot + 0.5f) * step
                    }
                val radians = angle * PI.toFloat() / 180f
                GesturePoint(
                    x = origin.x + cos(radians) * radiusInner,
                    y = origin.y + sin(radians) * radiusInner,
                )
            }
        return RadialLayout(side, origin, radiusInner, centers)
    }

    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int?,
        enterRadius: Float,
        keepRadius: Float,
    ): Int? {
        if (layout.radius <= 0f) return null
        if (previous != null && previous in layout.itemCenters.indices) {
            val center = layout.itemCenters[previous]
            if (hypot(x - center.x, y - center.y) <= keepRadius) return previous
        }
        return layout.itemCenters
            .indices
            .minByOrNull { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y)
            }
            ?.takeIf { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y) <= enterRadius
            }
    }

    fun polarAngle(layout: RadialLayout, point: GesturePoint): Float =
        atan2(point.y - layout.origin.y, point.x - layout.origin.x)
}
