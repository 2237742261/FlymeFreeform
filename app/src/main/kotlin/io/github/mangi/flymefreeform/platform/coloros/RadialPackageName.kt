package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.PinnedTarget

internal fun PinnedTarget.radialPackageName(): String? = when (this) {
    is PinnedTarget.App -> packageName
    is PinnedTarget.Sidebar -> when (kind) {
        PinnedTarget.Kind.AppEntry, PinnedTarget.Kind.CloneApp -> alias.substringBefore('#').takeIf { '.' in it }
        PinnedTarget.Kind.Tool, PinnedTarget.Kind.Shortcut -> null
    }
}
