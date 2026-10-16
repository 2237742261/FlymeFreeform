package com.eta.flymefreeform.window

import com.eta.flymefreeform.config.ModulePreferences
import kotlin.math.PI
import kotlin.math.sin

/**
 * 扇形与图标共享屏幕比例；条目多到挤不下时按弦长收紧图标，而不是互叠或截断。
 *
 * @author bomo 这几个基准常量是 **internal**：设置页的「轮盘缩放」预览
 * （`ui/ControlScreen.kt` 的 `RadialScalePreview`）要按同一套口径换算，
 * 避免 App 侧另抄一份常量后与 system_server 侧漂移。
 */
internal object RadialIconGeometry {
    internal const val BASE_SHORT_EDGE_DP = 400f

    /**
     * @author bomo 外圈基准半径（dp）。用户反馈序列：236 太挤 → 280 → 324 → 358 都嫌「太大」，
     * 2026-10-16 定为 **300**；实际大小由设置里的「轮盘缩放」百分比实时缩放（见 [fit] 的 `scalePercent`）。
     *
     * ⚠️ 2026-10-16 关键发现（别再踩）：**358 与 330 在实机上渲染结果完全一样**。
     * `fit()` 末尾按 `safeWidth / requestedExtent` 收口，而
     * `requestedExtent = (半径 + 图标半径 + 留白 + 回摆) × density`：
     * 358 → 1171px，被屏宽 1080 压到 0.92 倍 → 渲染半径 ≈ 988px；
     * 330 → 1081px，≈1.0 倍 → 渲染半径 ≈ 988px。二者只差 0.6px，肉眼不可能分辨。
     * 所以「改小半径」必须小到让 `requestedExtent` **明显低于屏宽**才会真的变小：
     * 300 → requestedExtent ≈ 983px（收口 1.0）→ 渲染半径 900px，比原来小约 9%。
     * 调大小优先用设置滑条（`KEY_RADIAL_SCALE_PERCENT`），别再靠改代码 + 重启试。
     *
     * ⚠️ 2026-10-16 第二个坑：`fit()` 里的 `pixelsPerBaseDp = density × windowScale`，
     * 而 `windowScale = (短边 / density) / 400dp`。本机短边 1080px / 3.0 = 360dp → **0.9**，
     * 所以 **1dp 基准 = 2.7px，不是 3px**：
     * 渲染半径 = `BASE_RADIUS_DP × 2.7`（fitScale 收口为 1 时）。
     * 300dp → 810px（实机 `RADIAL_METRICS radiusPx=809` 已核对）；
     * 245dp → 661px（0.2.8 实机核对 `radiusPx=661`）；
     * 250dp → 675px（2026-10-16 用户定稿）。
     * 报「多大」一律报渲染半径 px，不要报 dp 常量。
     */
    internal const val BASE_RADIUS_DP = 250f

    /**
     * @author bomo 图标基准直径（dp）。历史：47.5 → 43.8 → 39.8 → 32.5 → **47.5（2026-10-16 用户定稿）**。
     *
     * 用户原话「图标现在太小，改回最初的图标大小」——最初就是 47.5，所以直接回滚，
     * 而不是继续按「半径缩小 → 图标同比例缩小」的公式算（那会一路缩到 32.5）。
     * 槽位过密时（如外圈 10 槽）由弦长上限自动收紧（见 [fit]），不需要人为调小基准值。
     * 实机：47.5dp × 2.7 ≈ 128px（`RADIAL_METRICS iconPx`）。
     */
    internal const val BASE_ICON_DIAMETER_DP = 47.5f
    internal const val BASE_ITEM_PADDING_DP = 1.5f

    /**
     * @author bomo 图标放大的**百分比上限**（2026-10-17 用户要求）。
     *
     * 滑条超过这个百分比后，只有半径继续变大，图标停在 105% 时的大小：
     * 47.5 × 1.05 = 49.875dp ≈ 135px（本机 1 基准 dp = 2.7px）。
     * 用户原话「缩小没问题，但放得太大就不好看」—— 所以放大侧封顶，缩小侧不封。
     */
    internal const val ICON_SCALE_CAP_PERCENT = 105f

    /**
     * 与 RadialGeometry.SPAN_DEGREES 一致；槽位角 = SPAN / 条目数。
     *
     * @author bomo 84 → 86：配合半径 324dp，弦长刚好容下满尺寸图标。
     * 上限约 87° —— 再大最靠上的槽会越过 -90°，图标被推出屏幕右边缘。
     */
    internal const val SPAN_DEGREES = 86f

    /**
     * 图标下限：再小就点不准。到下限后宁可允许轻微互叠，也不继续缩小。
     * 10 槽位（9 应用 + 更多）时弦长约 37.3dp、收紧后约 35.8dp（半径 250dp），仍高于该下限。
     */
    private const val MIN_ICON_DIAMETER_DP = 30f

    /**
     * 内圈与外圈图标之间的径向间隙（dp，按图标边缘到边缘计）。
     *
     * @author bomo 由 12dp 逐步调到 18dp（用户 2026-09-22 实机连试 24 / 16 / 20 后定稿：20 偏大、16 略紧）。
     * 该间隙在算法里是**恒等量**（与图标直径无关），改这个值即精确生效。
     *
     * 为什么 12dp 会"粘"住：两圈中心距 = (外圈直径 + 内圈直径) / 2 + 间隙，
     * 而选中判定（`RadialGeometry.selection`）只要手指仍在原选中槽位的
     * `keepRadius`（= 图标直径 × 1.25）内就保持不变。图标同为 47.5dp 时：
     * `中心距 − keepRadius = 间隙 − 0.25 × 直径 = 间隙 − 11.875dp`，
     * 12dp 只余 0.125dp（等于没有，跨圈必然切不过去），18dp 余 6.125dp（手感均衡）。
     * 不宜超过 ~30dp：内圈半径随之间变小，内圈相邻弦长不足会把内圈图标压小。
     */
    internal const val RING_GAP_DP = 18f

    /**
     * @author bomo `fitScale` 地板（缩放下限）。
     *
     * 兜底：任何异常大的安全区 inset（坏 frame、离谱的键盘避让量）都**不能**把整圈缩成一个点 ——
     * 用户看到的表现是「只有压暗遮罩、没有轮盘」（2026-10-16 实机报告）。
     * 到下限后宁可让弧线略微越过安全区，也必须保证看得见。
     */
    private const val MIN_FIT_SCALE = 0.6f

    /**
     * @author bomo 设置页预览用的**外缘半径**（基准 dp）：图标圆心半径 + 图标半径 + 留白。
     *
     * 与 [fit] 同源（半径按滑条缩放、图标按 [ICON_SCALE_CAP_PERCENT] 封顶）。
     * 设置页必须调这个函数，**不要自己抄公式** —— 两边漂移预览就不准了。
     */
    internal fun previewOuterEdgeDp(scalePercent: Int): Float {
        val scale = ModulePreferences.coerceRadialScalePercent(scalePercent) / 100f
        val iconScale = minOf(scale, ICON_SCALE_CAP_PERCENT / 100f)
        return BASE_RADIUS_DP * scale +
            BASE_ICON_DIAMETER_DP * iconScale / 2f +
            BASE_ITEM_PADDING_DP
    }

    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
        innerCount: Int = 0,
        /** @author bomo 轮盘整体缩放百分比（100 = [BASE_RADIUS_DP]）；来自模块设置，呼出时实时读取。 */
        scalePercent: Int = 100,
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount >= 1)
        // 半径按滑条缩放；**图标只放大到 [ICON_SCALE_CAP_PERCENT] 就封顶**，
        // 超过之后滑条只扩大半径与整体范围，图标保持 105% 的大小（2026-10-17 用户要求）。
        val scale =
            ModulePreferences.coerceRadialScalePercent(scalePercent) / 100f
        val baseRadiusDp = BASE_RADIUS_DP * scale
        val iconScale = minOf(scale, ICON_SCALE_CAP_PERCENT / 100f)
        val baseIconDiameterDp = BASE_ICON_DIAMETER_DP * iconScale
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 相邻圆心距 = 2R·sin(槽位角/2)，图标直径 + 留白不得超过它，否则相邻图标互叠、滑选歧义。
        // 半径 250dp 时：7 槽位（6 应用 + 更多）弦长 53.2dp，10 槽位弦长 37.3dp；
        // 基准直径 47.5dp 在 7 槽位下仍留约 4.2dp 间隙；10 槽位会被收紧到约 35.8dp。
        val slotDegrees = SPAN_DEGREES / itemCount
        // 0.995 系留 0.5% 浮点余量，避免恰好取等时相邻图标判为重叠。
        val chordDp =
            2f * baseRadiusDp * sin(slotDegrees / 2f * PI.toFloat() / 180f) * 0.995f
        val maxDiameterDp =
            (chordDp - BASE_ITEM_PADDING_DP).coerceAtLeast(MIN_ICON_DIAMETER_DP)
        val diameterDp = baseIconDiameterDp.coerceAtMost(maxDiameterDp)
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val requestedExtent =
            (baseRadiusDp + diameterDp * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale =
            minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
                .coerceAtLeast(MIN_FIT_SCALE)
        val unit = pixelsPerBaseDp * fitScale
        val diameter = diameterDp * unit
        // 内圈：图标与外圈同尺寸（不缩小），半径 = 外圈半径 − 两圈图标半径和 − 间隙；
        // 外圈完全不动，只把内圈整体内移。内圈唯一收紧手段是自身相邻弦长
        // （内圈条目数增多时弦长变短，先按等大假设估算半径，一轮近似即可）。
        var innerRadius = 0f
        var innerDiameter = 0f
        if (innerCount > 0) {
            val innerSlotDegrees = SPAN_DEGREES / innerCount
            val innerChordDp =
                2f * (baseRadiusDp - diameterDp - RING_GAP_DP) *
                    sin(innerSlotDegrees / 2f * PI.toFloat() / 180f) * 0.995f
            val innerDiameterDp =
                diameterDp.coerceAtMost(
                    (innerChordDp - BASE_ITEM_PADDING_DP).coerceAtLeast(MIN_ICON_DIAMETER_DP),
                )
            innerRadius = (baseRadiusDp - (diameterDp + innerDiameterDp) / 2f - RING_GAP_DP) * unit
            innerDiameter = innerDiameterDp * unit
        }
        return RadialVisualMetrics(
            radius = baseRadiusDp * unit,
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = diameter * 0.9f,
            selectionKeepRadius = diameter * 1.25f,
            itemPadding = BASE_ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
            innerRadius = innerRadius,
            innerIconDiameter = innerDiameter,
        )
    }
}
