package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.PinnedTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialCatalogReadinessTest {
    @Test fun emptyBootSnapshotCannotOpen() {
        assertFalse(AppCatalogSnapshot().isReadyFor(ModuleSettingsSnapshot()))
    }
    @Test fun intentionallyEmptySelectionCanOpenAfterAppsLoad() {
        val settings = ModuleSettingsSnapshot(pinsSaved = true)
        assertTrue(AppCatalogSnapshot(settings = settings, appsLoaded = true).isReadyFor(settings))
    }
    @Test fun configuredToolsWaitForSidebarCompletion() {
        val settings = ModuleSettingsSnapshot(pinsSaved = true, pinnedTargets = listOf(
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0)))
        val appsOnly = AppCatalogSnapshot(settings = settings, appsLoaded = true)
        assertFalse(appsOnly.isReadyFor(settings))
        assertTrue(appsOnly.copy(sidebarLoaded = true).isReadyFor(settings))
    }
    @Test fun staleSelectionCannotPassGate() {
        val settings = ModuleSettingsSnapshot(pinsSaved = true)
        val ready = AppCatalogSnapshot(settings = settings, appsLoaded = true, sidebarLoaded = true)
        assertFalse(ready.isReadyFor(settings.copy(innerPinsSaved = true)))
    }
}
