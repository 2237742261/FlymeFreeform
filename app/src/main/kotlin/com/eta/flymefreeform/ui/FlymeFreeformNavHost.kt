package com.eta.flymefreeform.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.eta.flymefreeform.apps.InstalledLauncherApp
import com.eta.flymefreeform.config.CornerCallMode
import com.eta.flymefreeform.config.OutsideTapCloseMode
import com.eta.flymefreeform.framework.ScopeRestartOutcome
import com.eta.flymefreeform.config.TriggerShape
import com.eta.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<InstalledLauncherApp>,
    onModuleEnabledChange: (Boolean) -> Unit,
    /** @author bomo 轮盘呼出方式（常规 / 仅左下 / 仅右下）变更。 */
    onCornerCallModeChange: (CornerCallMode) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    /** @author bomo 触发热区形状（扇形 / 三角形）变更。 */
    onTriggerShapeChange: (TriggerShape) -> Unit,
    /** @author bomo 三角形热区横向长度变更（沿屏幕底边）。 */
    onTriggerHorizontalDpChange: (Int) -> Unit,
    /** @author bomo 三角形热区纵向高度变更（沿屏幕侧边）。 */
    onTriggerVerticalDpChange: (Int) -> Unit,
    /** @author bomo 「全部」面板缩放百分比变更。 */
    onPanelScaleChange: (Int) -> Unit,
    /** @author bomo 角落轮盘缩放百分比变更（热生效，见 `KEY_RADIAL_SCALE_PERCENT`）。 */
    onRadialScaleChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    /** @author bomo 开发模式（诊断日志总开关）变更。 */
    onDeveloperModeChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    /** @author bomo 「重启作用域」：硬停已注入进程 + 重启 SystemUI/桌面/侧边栏。 */
    onRestartScope: suspend () -> ScopeRestartOutcome,
    onPinnedComponentsChange: (List<ComponentName>) -> Unit,
    onInnerPinnedComponentsChange: (List<ComponentName>) -> Unit,
) {
    val backStack = rememberNavBackStack(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
        entryProvider =
            entryProvider {
                entry<AppRoute.Settings> {
                    ControlScreen(
                        state = state,
                        onModuleEnabledChange = onModuleEnabledChange,
                        onCornerCallModeChange = onCornerCallModeChange,
                        onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                        onTriggerShapeChange = onTriggerShapeChange,
                        onTriggerHorizontalDpChange = onTriggerHorizontalDpChange,
                        onTriggerVerticalDpChange = onTriggerVerticalDpChange,
                        onPanelScaleChange = onPanelScaleChange,
                        onRadialScaleChange = onRadialScaleChange,
                        onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                        onHandleSwipeUpToMiniEnabledChange =
                            onHandleSwipeUpToMiniEnabledChange,
                        onPauseInLandscapeChange = onPauseInLandscapeChange,
                        onPauseInGameModeChange = onPauseInGameModeChange,
                        onDeveloperModeChange = onDeveloperModeChange,
                        onRequestScopes = onRequestScopes,
                        onRestartScope = onRestartScope,
                        onNavigateToPinnedApps = navigateToPinnedApps,
                    )
                }
                entry<AppRoute.PinnedApps> {
                    PinnedAppsScreen(
                        state = state,
                        apps = apps,
                        onBack = popBackStack,
                        onPinnedComponentsChange = onPinnedComponentsChange,
                        onInnerPinnedComponentsChange = onInnerPinnedComponentsChange,
                    )
                }
            },
    )
}
