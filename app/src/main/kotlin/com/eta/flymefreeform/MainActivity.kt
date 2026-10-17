package com.eta.flymefreeform

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eta.flymefreeform.ui.FlymeFreeformNavHost
import com.eta.flymefreeform.ui.theme.FlymeFreeformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        val repository =
            (application as FlymeFreeformApplication).frameworkConnectionRepository
        val appRepository =
            (application as FlymeFreeformApplication).launcherAppRepository
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            FlymeFreeformTheme {
                FlymeFreeformNavHost(
                    state = state,
                    apps = apps,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onCornerCallModeChange = repository::setCornerCallMode,
                    onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                    onTriggerShapeChange = repository::setTriggerShape,
                    onTriggerHorizontalDpChange = repository::setTriggerHorizontalDp,
                    onTriggerVerticalDpChange = repository::setTriggerVerticalDp,
                    onPanelScaleChange = repository::setPanelScalePercent,
                    onRadialScaleChange = repository::setRadialScalePercent,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onDeveloperModeChange = repository::setDeveloperMode,
                    onRequestScopes = repository::requestMissingScopes,
                    onRestartScope = repository::restartScope,
                    onPinnedComponentsChange = repository::setPinnedComponents,
                    onInnerPinnedComponentsChange = repository::setInnerPinnedComponents,
                )
            }
        }
    }
}
