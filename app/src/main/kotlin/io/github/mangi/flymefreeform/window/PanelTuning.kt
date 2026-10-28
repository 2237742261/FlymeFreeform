package io.github.mangi.flymefreeform.window

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log

/**
 * 「更多」面板的布局参数。
 *
 * 以前这些数字都是编译期常量，改一次就得重新出包 + 重启手机。
 * 现在既可以从 [Settings.Global] 覆盖（`settings put global flyme_ff_xxx 45`，写完整机立即生效），
 * 没写的时候用这里的默认值。
 */
internal data class PanelTuning(
    val iconDp: Float = 44f,
    val widthPct: Float = 88f,
    val rightMarginDp: Float = 10f,
    val bottomMarginDp: Float = 65f,
    /** 工具栏每个格子四周的内边距 */
    val cellPaddingDp: Float = 5f,
    /** 工具栏整块底色与内容之间的内边距 */
    val stripPaddingDp: Float = 5f,
    /** 索引栏上下留白 */
    val indexInsetDp: Float = 110f,
    val labelSp: Float = 11f,
    /** 横屏：微信小窗那种竖长条 */
    val landscapeWidthDp: Float = 420f,
    val landscapeColumns: Int = 5,
    val landscapeMarginDp: Float = 4f,
    val gridColumns: Int = 5,
    /** 横屏图标单独一档（竖屏 44dp 在横屏太占地方） */
    val landscapeIconDp: Float = 34f,
    /** 横屏不显示索引栏（列数少、面板矮，索引栏反而挡图标） */
    val landscapeIndexBar: Boolean = false,
) {
    companion object {
        const val KEY_ICON_DP = "flyme_ff_icon_dp"
        const val KEY_WIDTH_PCT = "flyme_ff_panel_width_pct"
        const val KEY_RIGHT_MARGIN_DP = "flyme_ff_right_margin_dp"
        const val KEY_BOTTOM_MARGIN_DP = "flyme_ff_bottom_margin_dp"
        const val KEY_CELL_PADDING_DP = "flyme_ff_cell_padding_dp"
        const val KEY_STRIP_PADDING_DP = "flyme_ff_strip_padding_dp"
        const val KEY_INDEX_INSET_DP = "flyme_ff_index_inset_dp"
        const val KEY_LABEL_SP = "flyme_ff_label_sp"
        const val KEY_LANDSCAPE_WIDTH_DP = "flyme_ff_landscape_width_dp"
        const val KEY_LANDSCAPE_COLUMNS = "flyme_ff_landscape_columns"
        const val KEY_LANDSCAPE_ICON_DP = "flyme_ff_landscape_icon_dp"

        val KEYS = arrayOf(
            KEY_ICON_DP,
            KEY_WIDTH_PCT,
            KEY_RIGHT_MARGIN_DP,
            KEY_BOTTOM_MARGIN_DP,
            KEY_CELL_PADDING_DP,
            KEY_STRIP_PADDING_DP,
            KEY_INDEX_INSET_DP,
            KEY_LABEL_SP,
            KEY_LANDSCAPE_WIDTH_DP,
            KEY_LANDSCAPE_COLUMNS,
            KEY_LANDSCAPE_ICON_DP,
        )

        fun read(resolver: ContentResolver?): PanelTuning {
            if (resolver == null) return PanelTuning()
            val defaults = PanelTuning()
            return try {
                defaults.copy(
                    iconDp = float(resolver, KEY_ICON_DP, defaults.iconDp),
                    widthPct = float(resolver, KEY_WIDTH_PCT, defaults.widthPct),
                    rightMarginDp = float(resolver, KEY_RIGHT_MARGIN_DP, defaults.rightMarginDp),
                    bottomMarginDp =
                        float(resolver, KEY_BOTTOM_MARGIN_DP, defaults.bottomMarginDp),
                    cellPaddingDp =
                        float(resolver, KEY_CELL_PADDING_DP, defaults.cellPaddingDp),
                    stripPaddingDp =
                        float(resolver, KEY_STRIP_PADDING_DP, defaults.stripPaddingDp),
                    indexInsetDp = float(resolver, KEY_INDEX_INSET_DP, defaults.indexInsetDp),
                    labelSp = float(resolver, KEY_LABEL_SP, defaults.labelSp),
                    landscapeWidthDp =
                        float(resolver, KEY_LANDSCAPE_WIDTH_DP, defaults.landscapeWidthDp),
                    landscapeColumns =
                        int(resolver, KEY_LANDSCAPE_COLUMNS, defaults.landscapeColumns),
                    landscapeIconDp =
                        float(resolver, KEY_LANDSCAPE_ICON_DP, defaults.landscapeIconDp),
                )
            } catch (exception: RuntimeException) {
                Log.w("FlymeFreeform", "PANEL_TUNING_READ_FAILED", exception)
                defaults
            }
        }

        private fun float(resolver: ContentResolver, key: String, fallback: Float): Float =
            Settings.Global.getString(resolver, key)?.trim()?.toFloatOrNull() ?: fallback

        private fun int(resolver: ContentResolver, key: String, fallback: Int): Int =
            Settings.Global.getString(resolver, key)?.trim()?.toIntOrNull()
                ?.coerceIn(2, 8) ?: fallback
    }
}
