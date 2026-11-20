package io.github.mangi.flymefreeform.window

/** 扇形与图标共享屏幕比例；条目数量只改变角度，不改变半径。 */
internal object RadialIconGeometry {
    private const val BASE_SHORT_EDGE_DP = 400f
    private const val BASE_RADIUS_DP = 262f
    private const val BASE_ICON_DIAMETER_DP = 50f
    private const val BASE_ITEM_PADDING_DP = 3.25f
    private const val RING_GAP_DP = 9.5f
    // Retract the outer ring by 0.4 of the original icon radius; scale both rings and icons together.
    private const val RADIAL_SCALE = (BASE_RADIUS_DP - BASE_ICON_DIAMETER_DP * 0.5f * 0.4f) / BASE_RADIUS_DP

    const val SELECTION_GROWTH = 0.12f
    // At seven outer slots, two adjacent highlights can animate at once.
    private const val OUTER_SELECTION_GROWTH = 0.09f

    fun selectionGrowthAt(index: Int, outerCount: Int): Float =
        if (index < outerCount) OUTER_SELECTION_GROWTH else SELECTION_GROWTH
    private const val OUTER_INSET_DP = 2f

    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount in 1..7)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val requestedExtent =
            (BASE_RADIUS_DP + BASE_ICON_DIAMETER_DP * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = pixelsPerBaseDp * fitScale * RADIAL_SCALE
        val diameter = BASE_ICON_DIAMETER_DP * unit
        val innerRadius =
            (BASE_RADIUS_DP - BASE_ICON_DIAMETER_DP - RING_GAP_DP) * unit
        return RadialVisualMetrics(
            radius = BASE_RADIUS_DP * unit - OUTER_INSET_DP * pixelsPerBaseDp * fitScale,
            innerRadius = innerRadius.coerceAtLeast(0f),
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = diameter * 0.9f,
            selectionKeepRadius = diameter * 1.25f,
            itemPadding = BASE_ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
        )
    }
}
