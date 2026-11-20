package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.PinnedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadialPackageNameTest {
    @Test fun appUsesPackageName() {
        assertEquals("com.example", PinnedTarget.App("com.example", "com.example.Main").radialPackageName())
    }
    @Test fun sidebarAppsStripComponent() {
        assertEquals("com.example", PinnedTarget.Sidebar(PinnedTarget.Kind.AppEntry, "com.example#com.example.Main", 0).radialPackageName())
        assertEquals("com.example", PinnedTarget.Sidebar(PinnedTarget.Kind.CloneApp, "com.example#Main", 0).radialPackageName())
    }
    @Test fun scanToolsDoNotExcludeTheirWholeApp() {
        assertNull(PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "com.example_scan", 0).radialPackageName())
        assertNull(PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "com.example#Scan", 0).radialPackageName())
    }
}
