package io.github.mangi.flymefreeform.gesture

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
                // “更多”占最靠近角落的 0 号槽；应用按列表顺序从外侧向角落排列，
                // 因此列表第一个应用使用最外侧的槽位，避免设置页与扇形视觉顺序相反。
                val slot = itemCount - index - 1
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

    /** 外圈 + 内圈：内圈条目接在外圈后面，索引空间与外圈共用。 */
    fun layoutRings(
        side: CornerSide,
        width: Float,
        height: Float,
        outerRadius: Float,
        outerCount: Int,
        innerRadius: Float,
        innerCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RadialLayout {
        val outer = layout(side, width, height, outerRadius, outerCount, offsetX, offsetY)
        if (innerCount <= 0 || innerRadius <= 0f) return outer
        val inner = layout(side, width, height, innerRadius, innerCount, offsetX, offsetY)
        return RadialLayout(
            side = outer.side,
            origin = outer.origin,
            radius = outer.radius,
            itemCenters = outer.itemCenters + inner.itemCenters,
        )
    }

    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int?,
        enterRadius: Float,
        keepRadius: Float,
        outerCount: Int = layout.itemCenters.size,
        innerRadius: Float = 0f,
        iconRadius: Float = 0f,
    ): Int? {
        if (layout.radius <= 0f) return null
        if (iconRadius > 0f && layout.itemCenters.isNotEmpty()) {
            // 命中判定：手指与图标圆心的距离不超过 1/2 图标直径（也就是手指按在图标上）即选中，
            // 内外圈一起参与比较，取最近的那个；已经选中的图标给一点粘性，避免边界抖动。
            val hitRadius = iconRadius
            val stickyRadius = iconRadius * 1.25f
            if (previous != null && previous in layout.itemCenters.indices) {
                val center = layout.itemCenters[previous]
                if (hypot(x - center.x, y - center.y) <= stickyRadius) return previous
            }
            return layout.itemCenters.indices
                .minByOrNull { index ->
                    val center = layout.itemCenters[index]
                    hypot(x - center.x, y - center.y)
                }
                ?.takeIf { index ->
                    val center = layout.itemCenters[index]
                    hypot(x - center.x, y - center.y) <= hitRadius
                }
        }
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
