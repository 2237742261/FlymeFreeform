package io.github.mangi.flymefreeform.window

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class OverlaySafeInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

internal data class OverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

internal data class RadialVisualMetrics(
    val radius: Float,
    val innerRadius: Float,
    val plateDiameter: Float,
    val iconDiameter: Float,
    val selectionEnterRadius: Float,
    val selectionKeepRadius: Float,
    val itemPadding: Float,
    val pixelsPerBaseDp: Float,
)

internal data class PanelVisualMetrics(
    val bounds: OverlayBounds,
    val columns: Int,
    val cellHeight: Float,
    val iconDiameter: Float,
    val labelTextSize: Float,
    val labelTopOffset: Float,
    val contentHorizontalPadding: Float,
    val horizontalTextPadding: Float,
    val topPadding: Float,
    val cornerRadius: Float,
    val maxScroll: Float,
    /** 工具栏格子四周内边距 */
    val cellPadding: Float,
    /** 工具栏整块底色内边距 */
    val stripPadding: Float,
    /** 索引栏上下留白 */
    val indexBarInset: Float,
    /** 是否显示右侧索引栏（横屏不显示） */
    val showIndexBar: Boolean,
    /** 横屏标记：横屏不做角落展开动画（窗口只有面板那么大，动画会像从中间飞出来） */
    val isLandscape: Boolean,
) {
    /** 只返回当前视口及相邻缓冲行，避免滚动时遍历完整应用目录。 */
    fun visibleItemRange(itemCount: Int, scroll: Float): IntRange {
        if (itemCount <= 0) return 1..0
        val firstRow =
            floor(((scroll - topPadding) / cellHeight).coerceAtLeast(0f))
                .toInt()
        val lastRow =
            floor(((scroll + bounds.height - topPadding) / cellHeight).coerceAtLeast(0f))
                .toInt() + 1
        val firstIndex = (firstRow * columns).coerceAtMost(itemCount)
        val endExclusive = min(itemCount, (lastRow + 1) * columns)
        return if (firstIndex < endExclusive) firstIndex until endExclusive else 1..0
    }
}

internal data class AdaptiveOverlayMetrics(
    val radial: RadialVisualMetrics,
    val panel: PanelVisualMetrics,
)

/** 扇形使用统一比例；面板保留自身的系统图标尺度与排版预算。 */
internal object AdaptiveOverlayGeometry {
    fun calculate(
        width: Float,
        height: Float,
        safeInsets: OverlaySafeInsets,
        systemIconSize: Float,
        density: Float,
        radialItemCount: Int,
        radialInsets: OverlaySafeInsets = safeInsets,
        fontScale: Float,
        panelItemCount: Int,
        anchorOnLeft: Boolean,
        tuning: PanelTuning = PanelTuning(),
    ): AdaptiveOverlayMetrics {
        require(width > 0f && height > 0f)

        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(1f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(1f)
        val shortEdge = min(width, height)
        val iconSeed = systemIconSize.takeIf { it > 0f } ?: shortEdge * FALLBACK_ICON_FRACTION
        val plateDiameter =
            (iconSeed * PANEL_BASE_ICON_FRACTION)
                .coerceIn(shortEdge * PANEL_BASE_MIN_FRACTION, shortEdge * PANEL_BASE_MAX_FRACTION)
        val panelReferenceRadius =
            min(shortEdge * PANEL_REFERENCE_WIDTH_FRACTION, safeHeight * PANEL_REFERENCE_HEIGHT_FRACTION)
                .coerceAtLeast(plateDiameter * PANEL_REFERENCE_MIN_PLATE_DISTANCE)
        val radial = RadialIconGeometry.fit(width, height, density, radialInsets, radialItemCount)

        // 面板离屏幕右缘/底边留白（由 PanelTuning 提供，可热更）
        val landscape = width > height
        val outerMarginRight = tuning.rightMarginDp * density
        val outerMarginBottom =
            (if (landscape) tuning.landscapeMarginDp else tuning.bottomMarginDp) * density
        val requestedContentHorizontalPadding = plateDiameter * PANEL_HORIZONTAL_PADDING_FRACTION
        val availablePanelWidth = (safeWidth - outerMarginRight * 2).coerceAtLeast(1f)
        val availablePanelHeight = (safeHeight - outerMarginBottom * 2).coerceAtLeast(1f)
        val columns = if (landscape) tuning.landscapeColumns else tuning.gridColumns
        // 横屏：微信小窗那种竖长条。宽度按「列数 × (图标 + 间距)」算出来，
        // 不再用固定 420dp —— 那样每格比图标宽一大截，图标之间空得离谱（用户反馈）。
        val landscapeIconPx = tuning.landscapeIconDp * density
        val landscapeGapPx = LANDSCAPE_ICON_GAP_DP * density
        val landscapePadPx = 12f * density
        val naturalLandscapeWidth =
            columns * (landscapeIconPx + landscapeGapPx) + landscapePadPx * 2
        val panelWidth =
            if (landscape) {
                min(
                    min(naturalLandscapeWidth, tuning.landscapeWidthDp * density),
                    availablePanelWidth,
                ).coerceAtLeast(1f)
            } else {
                (width * tuning.widthPct / 100f).coerceAtLeast(1f)
            }
        // 内边距越小，同宽下格子越宽、图标越大、左右间距越小
        val contentHorizontalPadding =
            max(
                PANEL_MIN_CONTENT_PADDING_DP * density,
                min(
                    requestedContentHorizontalPadding,
                    panelWidth * PANEL_MAX_CONTENT_HORIZONTAL_PADDING_FRACTION,
                ),
            )
        val cellWidth =
            ((panelWidth - contentHorizontalPadding * 2) / columns).coerceAtLeast(1f)
        // 图标大小主要跟着格子走（原来被 plateDiameter×1.07 卡死，实际只有 ~34dp）
        // 图标固定 47dp（用户指定），格子太小时才按格子收敛
        val panelIconDiameter =
            min(
                (if (landscape) tuning.landscapeIconDp else tuning.iconDp) * density,
                cellWidth * PANEL_ICON_OVERFLOW_RATIO,
            ).coerceAtLeast(1f)
        val labelTextSize = (tuning.labelSp * density) * fontScale.coerceAtLeast(0.1f)
        val horizontalTextPadding = labelTextSize * PANEL_LABEL_HORIZONTAL_PADDING_FRACTION
        val labelGap = PANEL_LABEL_GAP_DP * density
        val labelLineHeight = labelTextSize * PANEL_LABEL_LINE_HEIGHT_FRACTION
        // 行高 = 图标 + 标签 + 上下留白；留白给足，图标之间才不会挤在一起
        val cellHeight =
            panelIconDiameter + labelGap + labelLineHeight + PANEL_CELL_EXTRA_PADDING_DP * density
        val verticalPadding = cellHeight * PANEL_VERTICAL_PADDING_FRACTION
        val panelHeight =
            if (landscape) {
                // 竖长条：横屏直接按整屏高度算（不再扣状态栏/导航栏，否则顶部和底部各少一截）
                (height - outerMarginBottom * 2).coerceAtLeast(1f)
            } else {
                min(availablePanelHeight, safeHeight * PANEL_HEIGHT_FRACTION).coerceAtLeast(1f)
            }
        val naturalPanelHeight = panelHeight
        val left =
            if (anchorOnLeft) {
                safeInsets.left + outerMarginRight
            } else {
                width - outerMarginRight - panelWidth
            }
        val bottom = if (landscape) height - outerMarginBottom
        else height - safeInsets.bottom - outerMarginBottom
        val bounds = OverlayBounds(left, bottom - panelHeight, left + panelWidth, bottom)
        val cornerRadius = min(plateDiameter * PANEL_CORNER_FRACTION, min(panelWidth, panelHeight) / 2)

        return AdaptiveOverlayMetrics(
            radial = radial,
            panel =
                PanelVisualMetrics(
                    bounds = bounds,
                    columns = columns,
                    cellHeight = cellHeight,
                    iconDiameter = panelIconDiameter,
                    labelTextSize = labelTextSize,
                    labelTopOffset = panelIconDiameter / 2 + labelGap,
                    contentHorizontalPadding = contentHorizontalPadding,
                    horizontalTextPadding = horizontalTextPadding,
                    topPadding = verticalPadding,
                    cornerRadius = cornerRadius,
                    maxScroll = (naturalPanelHeight - panelHeight).coerceAtLeast(0f),
                    cellPadding = tuning.cellPaddingDp * density,
                    stripPadding = tuning.stripPaddingDp * density,
                    indexBarInset = tuning.indexInsetDp * density,
                    showIndexBar = !landscape || tuning.landscapeIndexBar,
                    isLandscape = landscape,
                ),
        )
    }

    private const val FALLBACK_ICON_FRACTION = 0.13f
    private const val PANEL_HEIGHT_FRACTION = 0.75f
    private const val PANEL_ICON_OVERFLOW_RATIO = 0.92f
    private const val LANDSCAPE_ICON_GAP_DP = 16f
    private const val PANEL_LABEL_GAP_DP = 4f
    private const val PANEL_CELL_EXTRA_PADDING_DP = 12f
    private const val PANEL_MIN_CONTENT_PADDING_DP = 12f
    private const val PANEL_BASE_ICON_FRACTION = 0.66f
    private const val PANEL_BASE_MIN_FRACTION = 0.07f
    private const val PANEL_BASE_MAX_FRACTION = 0.10f
    private const val PANEL_REFERENCE_WIDTH_FRACTION = 0.60f
    private const val PANEL_REFERENCE_HEIGHT_FRACTION = 0.34f
    private const val PANEL_REFERENCE_MIN_PLATE_DISTANCE = 4.8f
    private const val PANEL_HORIZONTAL_PADDING_FRACTION = 0.40f
    private const val PANEL_MAX_CONTENT_HORIZONTAL_PADDING_FRACTION = 0.16f
    private const val PANEL_LABEL_HORIZONTAL_PADDING_FRACTION = 0.20f
    private const val PANEL_LABEL_LINE_HEIGHT_FRACTION = 1.15f
    private const val PANEL_VERTICAL_PADDING_FRACTION = 0.18f
    private const val PANEL_CORNER_FRACTION = 0.80f
}
