package io.github.mangi.flymefreeform.apps

internal object AppSelectionPolicy {
    fun <T> radialItems(
        pinsSaved: Boolean,
        availablePins: List<T>,
        recent: List<T>,
        all: List<T>,
        identity: (T) -> Any,
        limit: Int,
    ): List<T> {
        // 没有固定应用时保持空态（只画中心“更多”），不再拿最近/全部应用凑数。
        if (!pinsSaved) return emptyList()
        return (availablePins + recent).distinctBy(identity).take(limit)
    }

    fun <T> panelItems(
        recent: List<T>,
        all: List<T>,
        excluded: Set<Any>,
        identity: (T) -> Any,
    ): List<T> =
        (recent + all).distinctBy(identity).filterNot { identity(it) in excluded }
}
