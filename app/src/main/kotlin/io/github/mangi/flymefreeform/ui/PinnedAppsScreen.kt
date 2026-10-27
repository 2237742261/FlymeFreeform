package io.github.mangi.flymefreeform.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.mangi.flymefreeform.R
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.apps.SidebarCatalogState
import io.github.mangi.flymefreeform.apps.SidebarCatalogStatus
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import io.github.mangi.flymefreeform.ui.component.TopBarBackdrop
import io.github.mangi.flymefreeform.ui.component.captureForTopBar
import io.github.mangi.flymefreeform.ui.component.rememberTopBarBackdrop
import io.github.mangi.flymefreeform.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun PinnedAppsScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onBack: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
    innerPinnedTargets: List<PinnedTarget> = emptyList(),
    onInnerPinnedTargetsChange: (List<PinnedTarget>) -> Unit = {},
) {
    val refreshCatalog by rememberUpdatedState(onRefreshCatalog)
    val cancelCatalog by rememberUpdatedState(onCancelCatalog)
    LifecycleResumeEffect(state.canChangeSettings) {
        if (state.canChangeSettings) refreshCatalog()
        onPauseOrDispose { cancelCatalog() }
    }
    val allEntries = remember(apps, sidebarCatalog.entries) { apps + sidebarCatalog.entries }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    // 拖动或增删后的本地顺序；远端配置回读同步前以它为准，避免列表闪回旧顺序
    var localOrder by remember { mutableStateOf<List<PinnedTarget>?>(null) }
    // 内圈独立顺序；空态时只画中心“更多”
    var innerLocalOrder by remember { mutableStateOf<List<PinnedTarget>?>(null) }
    // 当前正在编辑哪一圈：false=外圈，true=内圈
    var pickerRing by remember { mutableStateOf(false) }
    LaunchedEffect(state.canChangeSettings, state.issue) {
        if (!state.canChangeSettings || state.issue != null) localOrder = null
    }
    var draggingComponent by remember { mutableStateOf<PinnedTarget?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideWindowMinWidth
        val title = stringResource(R.string.radial_apps_screen_title)
        val navigationIcon: @Composable () -> Unit = { BackButton(onBack) }
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWide) {
                        SmallTopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            val direction = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(direction)
            val safeEnd = innerPadding.calculateEndPadding(direction)
            val safeWidth = (maxWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val side = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            val pinned = state.settings.pinnedTargets
            LaunchedEffect(pinned) {
                if (localOrder != null && pinned == localOrder) localOrder = null
            }
            val displayed = localOrder ?: pinned
            LaunchedEffect(innerPinnedTargets) {
                if (innerLocalOrder != null && innerPinnedTargets == innerLocalOrder) innerLocalOrder = null
            }
            val innerDisplayed = innerLocalOrder ?: innerPinnedTargets
            val appByComponent = remember(allEntries) { allEntries.associateBy(SelectableEntry::target) }
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(innerPadding)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding =
                    PaddingValues(
                        start = safeStart + side,
                        top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                        end = safeEnd + side,
                        bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                    ),
                overscrollEffect = null,
                ) {
                item(key = "added_intro") {
                    Text(
                        text =
                            stringResource(
                                R.string.added_outer_apps_title,
                                displayed.size,
                                ModulePreferences.MAX_OUTER_PINNED_APPS,
                            ),
                        modifier =
                            Modifier
                                .padding(start = 16.dp, bottom = 8.dp)
                                .semantics { heading() },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                item(key = "added") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        displayed.forEachIndexed { index, component ->
                            key(PinnedTargetCodec.encode(component)) {
                                AddedAppRow(
                                    component = component,
                                    app = appByComponent[component],
                                    index = index,
                                    enabled = state.canChangeSettings,
                                    draggable = state.canChangeSettings && displayed.size > 1,
                                    dragging = draggingComponent == component,
                                    dragOffsetY = dragOffsetY,
                                    rowHeightPx = rowHeightPx,
                                    onRowHeight = { height ->
                                        if (rowHeightPx != height) rowHeightPx = height
                                    },
                                    onRemove = {
                                        val updated = displayed.filterNot { it == component }
                                        localOrder = updated
                                        onPinnedTargetsChange(updated)
                                    },
                                    onDragStart = {
                                        localOrder = displayed
                                        draggingComponent = component
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { deltaY ->
                                        if (rowHeightPx > 0f) {
                                            dragOffsetY += deltaY
                                            val order =
                                                (localOrder ?: displayed).toMutableList()
                                            var current = order.indexOf(component)
                                            while (current >= 0 &&
                                                dragOffsetY > rowHeightPx / 2 &&
                                                current < order.size - 1
                                            ) {
                                                order.add(current + 1, order.removeAt(current))
                                                dragOffsetY -= rowHeightPx
                                                current++
                                            }
                                            while (current >= 0 &&
                                                dragOffsetY < -rowHeightPx / 2 &&
                                                current > 0
                                            ) {
                                                order.add(current - 1, order.removeAt(current))
                                                dragOffsetY += rowHeightPx
                                                current--
                                            }
                                            localOrder = order
                                        }
                                    },
                                    onDragEnd = {
                                        val order = localOrder
                                        draggingComponent = null
                                        dragOffsetY = 0f
                                        if (order != null) {
                                            if (order == pinned) {
                                                localOrder = null
                                            } else {
                                                onPinnedTargetsChange(order)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (displayed.isEmpty()) {
                            BasicComponent(
                                title = stringResource(R.string.added_apps_empty),
                                summary = stringResource(R.string.added_apps_empty_summary),
                                enabled = false,
                            )
                        }
                        BasicComponent(
                            title = stringResource(R.string.select_apps_action),
                            startAction = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .squircleBackground(
                                                MiuixTheme.colorScheme.secondaryContainer,
                                                12.dp,
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            },
                            onClick = {
                                pickerQuery = ""
                                pickerRing = false
                                pickerOpen = true
                            },
                            onClickLabel = stringResource(R.string.select_apps_action),
                            role = Role.Button,
                            enabled = state.canChangeSettings,
                        )
                    }
                }
                item(key = "inner_intro") {
                    Text(
                        text =
                            stringResource(
                                R.string.added_inner_apps_title,
                                innerDisplayed.size,
                                ModulePreferences.MAX_INNER_PINNED_APPS,
                            ),
                        modifier =
                            Modifier
                                .padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                                .semantics { heading() },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                item(key = "inner_added") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        innerDisplayed.forEachIndexed { index, component ->
                            key("inner_" + PinnedTargetCodec.encode(component)) {
                                AddedAppRow(
                                    component = component,
                                    app = appByComponent[component],
                                    index = index,
                                    enabled = state.canChangeSettings,
                                    draggable = false,
                                    dragging = false,
                                    dragOffsetY = 0f,
                                    rowHeightPx = rowHeightPx,
                                    onRowHeight = {},
                                    onRemove = {
                                        val updated = innerDisplayed.filterNot { it == component }
                                        innerLocalOrder = updated
                                        onInnerPinnedTargetsChange(updated)
                                    },
                                    onDragStart = {},
                                    onDrag = {},
                                    onDragEnd = {},
                                )
                            }
                        }
                        if (innerDisplayed.isEmpty()) {
                            BasicComponent(
                                title = stringResource(R.string.added_apps_empty),
                                summary = stringResource(R.string.inner_apps_empty_summary),
                                enabled = false,
                            )
                        }
                        BasicComponent(
                            title = stringResource(R.string.select_apps_action),
                            startAction = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .squircleBackground(
                                                MiuixTheme.colorScheme.secondaryContainer,
                                                12.dp,
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            },
                            onClick = {
                                pickerQuery = ""
                                pickerRing = true
                                pickerOpen = true
                            },
                            onClickLabel = stringResource(R.string.select_apps_action),
                            role = Role.Button,
                            enabled = state.canChangeSettings,
                        )
                    }
                }
                }
            }
            // 勾选状态跟随 displayed，sheet 内增删即时可见
            val activeList = if (pickerRing) innerDisplayed else displayed
            val activeLimit =
                if (pickerRing) ModulePreferences.MAX_INNER_PINNED_APPS
                else ModulePreferences.MAX_OUTER_PINNED_APPS
            val addedSet = remember(activeList) { activeList.toSet() }
            // 已经放进另一圈的条目在这一圈不可选（两圈互斥）
            val otherRingSet =
                remember(pickerRing, displayed, innerDisplayed) {
                    (if (pickerRing) displayed else innerDisplayed).toSet()
                }
            val candidates =
                remember(allEntries, pickerQuery) {
                    if (pickerQuery.isBlank()) {
                        allEntries
                    } else {
                        allEntries.filter {
                            it.label.contains(pickerQuery, ignoreCase = true) ||
                                it.packageName.contains(pickerQuery, ignoreCase = true)
                        }
                    }
                }
            val groups = remember(candidates) {
                listOf(
                    R.string.entry_group_tools to candidates.filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.Tool },
                    R.string.entry_group_shortcuts to candidates.filter {
                        val kind = (it.target as? PinnedTarget.Sidebar)?.kind
                        kind == PinnedTarget.Kind.Shortcut || kind == PinnedTarget.Kind.AppEntry
                    },
                    R.string.entry_group_cloned_apps to candidates.filter {
                        (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp
                    },
                    R.string.entry_group_apps to candidates.filter { it.target is PinnedTarget.App },
                ).filter { it.second.isNotEmpty() }
            }
            OverlayBottomSheet(
                show = pickerOpen,
                title = stringResource(R.string.select_apps_action),
                onDismissRequest = { pickerOpen = false },
            ) {
                Column {
                    TextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.search_apps_placeholder),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Basic.Search,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            )
                        },
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        if (sidebarCatalog.status != SidebarCatalogStatus.Ready) {
                            item(key = "sidebar_status") {
                                BasicComponent(
                                    title = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading else R.string.sidebar_catalog_unavailable),
                                    summary = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading_summary else R.string.sidebar_catalog_retry_summary),
                                    onClick = onRefreshCatalog,
                                    onClickLabel = stringResource(R.string.retry_action),
                                    role = Role.Button,
                                    enabled = state.canChangeSettings && sidebarCatalog.status != SidebarCatalogStatus.Loading,
                                )
                            }
                        }
                        when {
                            allEntries.isEmpty() ->
                                item(key = "apps_loading") {
                                    BasicComponent(
                                        title = stringResource(R.string.available_apps_loading),
                                        summary = stringResource(R.string.available_apps_loading_summary),
                                        enabled = false,
                                    )
                                }
                            candidates.isEmpty() ->
                                item(key = "apps_no_match") {
                                    BasicComponent(
                                        title = stringResource(R.string.no_matching_apps),
                                        enabled = false,
                                    )
                                }
                            else -> groups.forEach { (title, entries) ->
                                item(key = "group_$title") {
                                    Text(
                                        text = stringResource(title),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                                        style = MiuixTheme.textStyles.subtitle,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                items(entries, key = { PinnedTargetCodec.encode(it.target) }) { app ->
                                    val added = app.target in addedSet
                                    val inOtherRing = app.target in otherRingSet
                                    BasicComponent(
                                        title = app.label,
                                        summary =
                                            if (inOtherRing) {
                                                stringResource(
                                                    if (pickerRing) {
                                                        R.string.entry_already_in_outer
                                                    } else {
                                                        R.string.entry_already_in_inner
                                                    },
                                                )
                                            } else {
                                                app.packageName
                                            },
                                        startAction = { AppIcon(app) },
                                        endActions =
                                            if (added) {
                                                {
                                                    Icon(
                                                        imageVector = MiuixIcons.Basic.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        onClick = {
                                            val updated =
                                                if (added) {
                                                    activeList.filterNot { it == app.target }
                                                } else {
                                                    (activeList + app.target).take(activeLimit)
                                                }
                                            if (pickerRing) {
                                                innerLocalOrder = updated
                                                onInnerPinnedTargetsChange(updated)
                                            } else {
                                                localOrder = updated
                                                onPinnedTargetsChange(updated)
                                            }
                                        },
                                        onClickLabel =
                                            stringResource(
                                                if (added) {
                                                    R.string.remove_named_app_action
                                                } else {
                                                    R.string.add_named_app_action
                                                },
                                                app.label,
                                            ),
                                        role = Role.Button,
                                        enabled =
                                            state.canChangeSettings &&
                                                !inOtherRing &&
                                                (added || activeList.size < activeLimit) &&
                                                (added || app.target is PinnedTarget.App || sidebarCatalog.status == SidebarCatalogStatus.Ready),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddedAppRow(
    component: PinnedTarget,
    app: SelectableEntry?,
    index: Int,
    enabled: Boolean,
    draggable: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    rowHeightPx: Float,
    onRowHeight: (Float) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 被拖行换位时，其余行先从旧视觉位置吸附再弹簧归位，形成实时让位动画
    val offsetAnim = remember { Animatable(0f) }
    var restIndex by remember { mutableIntStateOf(index) }
    LaunchedEffect(index, dragging) {
        if (dragging || index == restIndex) {
            restIndex = index
        } else {
            offsetAnim.snapTo(offsetAnim.value + (restIndex - index) * rowHeightPx)
            restIndex = index
            offsetAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
        }
    }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragModifier =
        if (draggable) {
            Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragCancel = { currentOnDragEnd() },
                    onDragEnd = { currentOnDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(amount.y)
                    },
                )
            }
        } else {
            Modifier
        }
    BasicComponent(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onRowHeight(size.height.toFloat()) }
                .graphicsLayer { translationY = if (dragging) dragOffsetY else offsetAnim.value }
                .zIndex(if (dragging) 1f else 0f)
                .then(dragModifier),
        title = app?.label ?: component.displayIdentity(),
        summary = if (app == null) stringResource(R.string.pinned_app_missing) else null,
        startAction = {
            if (app != null) {
                AppIcon(app)
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "?",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body1,
                    )
                }
            }
        },
        endActions = {
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                backgroundColor = MiuixTheme.colorScheme.error,
                cornerRadius = 13.dp,
                minWidth = 26.dp,
                minHeight = 26.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription =
                        stringResource(
                            R.string.remove_named_app_action,
                            app?.label ?: component.displayIdentity(),
                        ),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onError,
                )
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun AppIcon(app: SelectableEntry) {
    Box(modifier = Modifier.size(44.dp)) {
        Image(
            bitmap = app.icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.matchParentSize().squircleClip(12.dp),
        )
        app.badge?.let { badge ->
            Image(
                bitmap = badge.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back_action),
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun PinnedTarget.displayIdentity(): String = when (this) {
    is PinnedTarget.App -> packageName
    is PinnedTarget.Sidebar -> alias
}
