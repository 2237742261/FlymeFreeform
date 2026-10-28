package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.mangi.flymefreeform.apps.AppSelectionPolicy
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.PinnedTarget
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal data class RadialEntry(
    val target: PinnedTarget,
    val label: String,
    val icon: Bitmap,
    val badge: Bitmap? = null,
    val badgeLeftOffsetFraction: Float = 0f,
)

/** 「更多」面板里按首字母分组的一栏。 */
internal data class AppLetterGroup(val letter: String, val apps: List<RadialEntry>)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialEntry> = emptyList(),
    val innerApps: List<RadialEntry> = emptyList(),
    val panelApps: List<RadialEntry> = emptyList(),
    /** 「更多」面板首行的工具（已按用户自定义顺序排好）。 */
    val tools: List<RadialEntry> = emptyList(),
    /** 「更多」面板应用区（应用 + 分身，按首字母分组）。 */
    val appGroups: List<AppLetterGroup> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
) {
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedTargets == settings.pinnedTargets &&
            this.settings.innerPinsSaved == settings.innerPinsSaved &&
            this.settings.innerPinnedTargets == settings.innerPinnedTargets
}

/** 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    logger: (Int, String, Throwable?) -> Unit,
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    // 中文 → 拼音（ICU Han-Latin），用于「更多」面板的字母分组与排序
    private val pinyinTransliterator =
        runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()

    private val contentRevision = AtomicLong()
    private var cachedContent: CatalogContent? = null // 仅目录工作线程访问。

    @Volatile private var sidebarEntries: List<SelectableEntry> = emptyList()

    fun updateSidebarEntries(entries: List<SelectableEntry>, settings: ModuleSettingsSnapshot) {
        sidebarEntries = entries.toList()
        refresh(settings)
    }

    fun refresh(settings: ModuleSettingsSnapshot, reloadApps: Boolean = true) {
        if (reloadApps) contentRevision.incrementAndGet()
        executor.execute {
            val revision = contentRevision.get()
            val cached = cachedContent
            val content =
                if (cached != null && cached.revision == revision &&
                    cached.settings.pinsSaved == settings.pinsSaved &&
                    cached.settings.pinnedTargets == settings.pinnedTargets &&
                    cached.settings.innerPinsSaved == settings.innerPinsSaved &&
                    cached.settings.innerPinnedTargets == settings.innerPinnedTargets
                ) {
                    cached
                } else {
                    loadContent(settings, revision) ?: return@execute
                }
            cachedContent = content
            val shapedRadial = (content.radialApps + content.innerApps).map { entry ->
                val drawable = content.radialSources[entry.target]
                if (drawable == null) entry else {
                    try {
                        entry.copy(
                            icon = iconRenderer
                                .shapedIcon(drawable)
                                .toBitmap(),
                        )
                    } catch (_: RuntimeException) {
                        entry
                    }
                }
            }
            val shapedOuter = shapedRadial.take(content.radialApps.size)
            val shapedInner = shapedRadial.drop(content.radialApps.size)
            shapedRadial.forEach { entry ->
                entry.icon.prepareToDraw()
                entry.badge?.prepareToDraw()
            }
            if (revision == contentRevision.get()) {
                publish(
                    AppCatalogSnapshot(
                        radialApps = shapedOuter,
                        innerApps = shapedInner,
                        panelApps = content.panelApps,
                        tools = content.tools,
                        appGroups = content.appGroups,
                        settings = settings,
                    ),
                )
            }
        }
    }

    private fun loadContent(settings: ModuleSettingsSnapshot, revision: Long): CatalogContent? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val user = Process.myUserHandle()
        val activities = launcherApps.getActivityList(null, user)
        val entries =
            activities
                .asSequence()
                .filterNot { info -> info.componentName.packageName == MODULE_PACKAGE }
                .mapNotNull(::toEntry)
                .distinctBy(RadialEntry::target)
                .toList()
        val sidebar = sidebarEntries
        val nativeEntries = sidebar.map {
            RadialEntry(it.target, it.label, it.icon, it.badge, it.badge?.leftOffsetFraction() ?: 0f)
        }
        val byComponent = (entries + nativeEntries).associateBy(RadialEntry::target)
        val recents = recentComponents().map { it.toTarget() }.mapNotNull(byComponent::get).distinctBy(RadialEntry::target)
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val outerApps =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                availablePins = settings.pinnedTargets.mapNotNull(byComponent::get),
                recent = recents,
                all = alphabetical,
                identity = RadialEntry::target,
                limit = ModulePreferences.MAX_OUTER_PINNED_APPS,
            )
        val innerApps =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.innerPinsSaved,
                availablePins = settings.innerPinnedTargets.mapNotNull(byComponent::get),
                recent = emptyList(),
                all = emptyList(),
                identity = RadialEntry::target,
                limit = ModulePreferences.MAX_INNER_PINNED_APPS,
            )
        // 「更多」面板：工具行（按自定义顺序）+ 应用区（应用与分身按首字母分组）
        val toolEntries =
            nativeEntries.filter {
                (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.Tool
            }
        val cloneEntries =
            nativeEntries.filter {
                (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp
            }
        val orderedTools = orderTools(toolEntries, settings.toolOrder)
        val appGroups = groupByLetter(entries + cloneEntries)
        val excluded = (outerApps + innerApps).mapTo(HashSet(), RadialEntry::target)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialEntry::target,
            )
        val activityByComponent = activities.associateBy { it.componentName.toTarget() }
        // 分身与主应用共用图标资源；包内只有一个入口时复用主用户的自适应图标，与普通应用同样原生塑形。
        val cloneActivities = sidebar
            .filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp }
            .mapNotNull { entry ->
                activities.filter { it.componentName.packageName == entry.packageName }
                    .singleOrNull()?.let { entry.target to it }
            }.toMap()
        val sources = (outerApps + innerApps).mapNotNull { entry ->
            val info = activityByComponent[entry.target] ?: cloneActivities[entry.target]
            if (info == null) return@mapNotNull entry.target to entry.icon.toDrawable(context.resources)
            try {
                entry.target to info.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) {
                null
            }
        }.toMap()
        panel.forEach { it.icon.prepareToDraw() }
        return CatalogContent(
            revision = revision,
            settings = settings,
            radialApps = outerApps,
            innerApps = innerApps,
            panelApps = panel,
            radialSources = sources,
            tools = orderedTools,
            appGroups = appGroups,
        )
    }

    private data class CatalogContent(
        val revision: Long,
        val settings: ModuleSettingsSnapshot,
        val radialApps: List<RadialEntry>,
        val innerApps: List<RadialEntry>,
        val panelApps: List<RadialEntry>,
        val radialSources: Map<PinnedTarget, Drawable>,
        val tools: List<RadialEntry>,
        val appGroups: List<AppLetterGroup>,
    )

    /** 工具行顺序：先按用户配置的 alias 顺序，未配置的按名称排在后面。 */
    private fun orderTools(tools: List<RadialEntry>, order: List<String>): List<RadialEntry> {
        if (tools.isEmpty()) return emptyList()
        if (order.isEmpty()) return tools.sortedBy { it.label }
        val byAlias =
            tools.associateBy { (it.target as? PinnedTarget.Sidebar)?.alias.orEmpty() }
        val ordered = order.mapNotNull { byAlias[it] }
        val rest =
            tools
                .filter { (it.target as? PinnedTarget.Sidebar)?.alias.orEmpty() !in order }
                .sortedBy { it.label }
        return (ordered + rest).distinctBy(RadialEntry::target)
    }

    /**
     * 应用区：应用 + 分身混排，按拼音首字母分组（非 A-Z 归 #，排在最后）。
     *
     * 中文名必须走 ICU 的 Han-Latin 转写拿拼音，不能直接用 Collator/首个字符 ——
     * 否则中文应用会全部堆进 “#”（就是「好多应用没归到字母里」的原因）。
     */
    private fun groupByLetter(apps: List<RadialEntry>): List<AppLetterGroup> {
        val keyed = apps.distinctBy(RadialEntry::target).map { it to sortKeyOf(it.label) }
        val sorted = keyed.sortedWith { first, second -> first.second.compareTo(second.second) }
        val groups = LinkedHashMap<String, MutableList<RadialEntry>>()
        sorted.forEach { (entry, key) ->
            groups.getOrPut(letterOf(entry.label, key)) { mutableListOf() } += entry
        }
        return groups.entries
            .sortedWith { first, second -> compareLetters(first.key, second.key) }
            .map { AppLetterGroup(it.key, it.value) }
    }

    /** 排序键 = 标签的拼音（小写、只留字母）；拿不到转写就退回小写原名。 */
    private fun sortKeyOf(label: String): String {
        val latin =
            pinyinTransliterator?.transliterate(label)?.lowercase().orEmpty()
                .filter { it in 'a'..'z' }
        return latin.ifEmpty { label.lowercase(Locale.ROOT) }
    }

    /**
     * 分组字母：**先看原始名称的首字符**，再看拼音。
     *
     * 「123云盘」这类数字/符号开头的应用必须归 #，不能被拼音转写成 yunpan 后混进 Y
     * （用户 2026-10-28 反馈）。首字符是字母或汉字时，才用排序键（拼音）的首字母。
     */
    private fun letterOf(label: String, key: String): String {
        val rawFirst = label.trim().firstOrNull() ?: return "#"
        if (!rawFirst.isLetter()) return "#"
        val first = key.firstOrNull() ?: return "#"
        return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
    }

    private fun compareLetters(first: String, second: String): Int =
        when {
            first == "#" -> if (second == "#") 0 else 1
            second == "#" -> -1
            else -> first.compareTo(second)
        }

    /** 在目录工作线程测量可见范围；左侧只平移角标，避免翻转其中的数字。 */
    private fun Bitmap.leftOffsetFraction(): Float {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var right = 0
        pixels.forEachIndexed { index, pixel ->
            if (pixel ushr 24 != 0) {
                val x = index % width
                left = minOf(left, x)
                right = maxOf(right, x + 1)
            }
        }
        return if (left < right) (width - left - right).toFloat() / width else 0f
    }

    private fun ComponentName.toTarget() = PinnedTarget.App(packageName, className)

    private fun recentComponents(): List<ComponentName> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task -> task.origActivity ?: task.baseIntent.component }
                .filterNot { component -> component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun toEntry(info: LauncherActivityInfo): RadialEntry? =
        try {
            RadialEntry(
                target = info.componentName.toTarget(),
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun Drawable.toBitmap(): Bitmap {
        val systemIconSize =
            context.getSystemService(ActivityManager::class.java)?.launcherLargeIconSize ?: 0
        val targetSize =
            (systemIconSize.takeIf { it > 0 }
                ?: maxOf(intrinsicWidth, intrinsicHeight, 1)).coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        return createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private companion object {
        const val OUTER_RING_MAX_APPS = 6
        const val INNER_RING_MAX_APPS = 5
        const val RECENT_LIMIT = 48
        const val MODULE_PACKAGE = "com.eta.flymefreeform"
    }
}
