package io.github.mangi.flymefreeform.window

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import io.github.mangi.flymefreeform.gesture.RadialLayout
import io.github.mangi.flymefreeform.platform.coloros.AppCatalogSnapshot
import io.github.mangi.flymefreeform.platform.coloros.RadialEntry
import io.github.mangi.flymefreeform.ui.theme.CornerOverlayTheme
import io.github.mangi.flymefreeform.ui.theme.LocalRadialItemColors
import io.github.mangi.flymefreeform.ui.theme.RadialItemColors
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("ViewConstructor")
internal class CornerRadialOverlayView(
    context: Context,
    private val listener: Listener,
) : AbstractComposeView(context), LifecycleOwner, SavedStateRegistryOwner {
    interface Listener {
        fun onEntryCommitted(entry: RadialEntry)

        /** 面板里拖动排序后回调（alias 列表，按新顺序）。 */
        fun onToolOrderChanged(aliases: List<String> = emptyList())

        fun onMorePanelRequested()

        fun onDismissRequested()

        fun onCleanupFailed(throwable: Throwable)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val metricsState = mutableStateOf<AdaptiveOverlayMetrics?>(null)
    private val layoutState = mutableStateOf(EMPTY_LAYOUT)
    private val selectedIndexState = mutableIntStateOf(NO_SELECTION)
    private val panelModeState = mutableStateOf(false)
    private val backdropOnlyState = mutableStateOf(false)
    private val backdropAlpha = mutableFloatStateOf(1f)
    private var backdropAnimator: ValueAnimator? = null
    private val exitRequestState = mutableStateOf<ExitRequest?>(null)
    private val entryProgress = Animatable(0f)
    private val handoffEntryProgress = mutableFloatStateOf(0f)
    private val handoffLayoutState = mutableStateOf(EMPTY_LAYOUT)
    private val handoffMetricsState = mutableStateOf<RadialVisualMetrics?>(null)
    private var appliedWindowInsets: WindowInsets? = null
    private val catalogState = mutableStateOf(AppCatalogSnapshot())

    // 用 State 承载目录快照：面板在打开状态下收到新目录（例如排序落盘后重载）需要重组
    private val catalog: AppCatalogSnapshot
        get() = catalogState.value
    private val radialClipPath = Path()
    private val radialImages = mutableStateOf<List<RadialImage>>(emptyList())
    private val innerImages = mutableStateOf<List<RadialImage>>(emptyList())
    private var panelImages: Map<io.github.mangi.flymefreeform.config.PinnedTarget, ImageBitmap> = emptyMap()
    private var side = CornerSide.Right
    private var latestX = 0f
    private var latestY = 0f
    private var selectedIndex: Int? = null
    private var dismissing = false
    private var dismissNotified = false
    private var disposed = false
    private var lastTickUptime = 0L
    private var vibrateEnabled = false
    private var lastVibratedIndex: Int? = null
    private var lastVibrateUptime = 0L
    private var hadWindowFocus = false
    private var panelShownAt = 0L
    // 面板模式下窗口会缩到面板大小，这里记窗口左上角的屏幕坐标（轮盘模式为 0）
    private var screenWidth = 0
    private var screenHeight = 0
    private var cachedSafeInsets: OverlaySafeInsets? = null
    @Volatile
    private var cachedTuning: PanelTuning? = null
    private var cachedRadialInsets: OverlaySafeInsets? = null
    private val windowOriginX = mutableIntStateOf(0)
    private val windowOriginY = mutableIntStateOf(0)
    private var vibrateStrength = ModulePreferences.DEFAULT_RADIAL_VIBRATE_STRENGTH
    private val timeout = Runnable(::requestDismiss)
    private val dismissFallback = Runnable(::completeRadialExit)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun updateRadialAppearance(next: AppCatalogSnapshot) {
        if (disposed || dismissing || panelModeState.value) return
        // 只替换同一批应用的图像，不在滑选途中交换目标或重置入场动画。
        if (catalog.radialApps.map { it.target } != next.radialApps.map { it.target }) return
        if (catalog.innerApps.map { it.target } != next.innerApps.map { it.target }) return
        radialImages.value = next.radialApps.map(::RadialImage)
        innerImages.value = next.innerApps.map(::RadialImage)
    }

    /** 由协调器在呼出前写入当前震动配置（关=不震）。 */
    fun applyVibration(enabled: Boolean, strength: Int) {
        vibrateEnabled = enabled
        vibrateStrength = ModulePreferences.coerceRadialVibrateStrength(strength)
    }

    fun begin(
        side: CornerSide,
        catalog: AppCatalogSnapshot,
        x: Float,
        y: Float,
    ) {
        check(!isAttachedToWindow) { "Overlay must be initialized before it is attached" }
        this.side = side
        catalogState.value = catalog
        latestX = x
        latestY = y
        radialImages.value = catalog.radialApps.map(::RadialImage)
        innerImages.value = catalog.innerApps.map(::RadialImage)
        panelImages =
            (catalog.panelApps + catalog.tools + catalog.appGroups.flatMap { it.apps })
                .associate { entry -> entry.target to entry.icon.asImageBitmap() }
        selectedIndex = null
        selectedIndexState.intValue = NO_SELECTION
        handoffEntryProgress.floatValue = 0f
        handoffLayoutState.value = EMPTY_LAYOUT
        handoffMetricsState.value = null
        panelModeState.value = false
        exitRequestState.value = null
        dismissing = false
        dismissNotified = false
        val displayMetrics = resources.displayMetrics
        updateLayout(
            requestedWidth = displayMetrics.widthPixels,
            requestedHeight = displayMetrics.heightPixels,
            safeInsets = OverlaySafeInsets(),
        )
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
    }

    fun updateGesture(x: Float, y: Float): Int? {
        latestX = x
        latestY = y
        if (panelModeState.value || dismissing) return selectedIndex
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
        updateGestureFromLatestPoint()
        return selectedIndex
    }

    fun finishGesture() {
        val selection = selectedIndex
        when {
            selection == null -> dismissAnimated()
            selection < catalog.radialApps.size -> dismissAnimated(catalog.radialApps[selection])
            selection == catalog.radialApps.size -> showMorePanel()
            else ->
                catalog.innerApps
                    .getOrNull(selection - catalog.radialApps.size - 1)
                    ?.let(::dismissAnimated) ?: dismissAnimated()
        }
    }

    fun cancelGesture() {
        dismissAnimated()
    }

    fun dismissAnimated() = dismissAnimated(pendingCommit = null)

    fun disposeOverlay(): Throwable? {
        if (disposed) return null
        disposed = true
        backdropAnimator?.cancel()
        backdropAnimator = null
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        var failure: Throwable? = null
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            disposeComposition()
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        return failure
    }

    @Composable
    override fun Content() {
        CornerOverlayTheme {
            val metrics = metricsState.value
            val layout = layoutState.value
            if (metrics != null) {
                OverlayContent(metrics = metrics, layout = layout)
            }
        }
    }

    @Composable
    private fun OverlayContent(
        metrics: AdaptiveOverlayMetrics,
        layout: RadialLayout,
    ) {
        val panelProgress = remember { Animatable(0f) }
        val panelContentProgress = remember { Animatable(0f) }
        val radialHandoffProgress = remember { Animatable(0f) }
        val exitProgress = remember { Animatable(0f) }
        val panelInputEnabled = remember { mutableStateOf(false) }
        val itemRings =
            remember(layout.itemCenters.size) {
                List(layout.itemCenters.size) { Animatable(0f) }
            }
        val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

        val exitRequest = exitRequestState.value
        LaunchedEffect(panelModeState.value, exitRequest, backdropOnlyState.value) {
            if (panelModeState.value || exitRequest != null || backdropOnlyState.value) return@LaunchedEffect
            if (!animationsEnabled) {
                entryProgress.snapTo(1f)
            } else {
                entryProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(RadialEntryMotion.DURATION_MILLIS.toInt(), easing = LinearEasing),
                )
            }
        }
        LaunchedEffect(selectedIndexState.intValue, itemRings) {
            val selected = selectedIndexState.intValue
            coroutineScope {
                itemRings.forEachIndexed { index, ring ->
                    launch {
                        val target = if (index == selected) 1f else 0f
                        if (!animationsEnabled) {
                            ring.snapTo(target)
                        } else {
                            ring.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    RadialEntryMotion.SELECTION_DURATION_MILLIS.toInt(),
                                    easing = { RadialEntryMotion.selectionProgress(it) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(panelModeState.value) {
            if (panelModeState.value) {
                panelInputEnabled.value = false
                if (!animationsEnabled) {
                    radialHandoffProgress.snapTo(1f)
                    panelProgress.snapTo(1f)
                    panelContentProgress.snapTo(1f)
                    panelInputEnabled.value = true
                } else {
                    coroutineScope {
                        launch {
                            radialHandoffProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = RadialHandoffMotion.DURATION_MILLIS.toInt(),
                                        easing = LinearEasing,
                                    ),
                            )
                        }
                        launch {
                            panelProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    folmeSpring(
                                        damping = PANEL_EXPAND_DAMPING,
                                        response = PANEL_EXPAND_RESPONSE_SECONDS,
                                        visibilityThreshold = PANEL_VISIBILITY_THRESHOLD,
                                    ),
                            )
                        }
                        launch {
                            panelContentProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = PANEL_CONTENT_FADE_MILLIS,
                                        delayMillis = PANEL_CONTENT_DELAY_MILLIS,
                                        easing = LinearOutSlowInEasing,
                                    ),
                            )
                            panelInputEnabled.value = true
                        }
                    }
                }
            }
        }
        LaunchedEffect(exitRequest) {
            if (exitRequest != null) {
                exitProgress.snapTo(0f)
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = RadialDismissMotion.DURATION_MILLIS.toInt(),
                            easing = LinearEasing,
                        ),
                )
                completeRadialExit()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val radialLayout = if (panelModeState.value) handoffLayoutState.value else layout
            val radialMetrics =
                if (panelModeState.value) {
                    handoffMetricsState.value ?: metrics.radial
                } else {
                    metrics.radial
                }
            RadialCanvas(
                metrics = radialMetrics,
                layout = radialLayout,
                panelProgress = panelProgress,
                radialHandoffProgress = radialHandoffProgress,
                exitProgress = exitProgress,
                itemRings = itemRings,
                animationsEnabled = animationsEnabled,
            )
            if (panelModeState.value) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    requestDismiss()
                                }
                            },
                )
                MoreAppsPanel(
                    metrics = metrics.panel,
                    panelProgress = panelProgress,
                    contentProgress = panelContentProgress,
                    inputEnabled = panelInputEnabled.value,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }

    @Composable
    private fun RadialCanvas(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        panelProgress: Animatable<Float, *>,
        radialHandoffProgress: Animatable<Float, *>,
        exitProgress: Animatable<Float, *>,
        itemRings: List<Animatable<Float, *>>,
        animationsEnabled: Boolean,
    ) {
        val itemColors = LocalRadialItemColors.current
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            if (backdropOnlyState.value) {
                drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * backdropAlpha.floatValue)
                return@Canvas
            }
            val panel =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    panelProgress.value.coerceIn(0f, 1f)
                }
            val handoff =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    radialHandoffProgress.value
                }
            val exitRequest = exitRequestState.value
            val entrySample = RadialEntryMotion.sample(
                when {
                    !animationsEnabled -> 1f
                    exitRequest != null -> exitRequest.entryProgress
                    panelModeState.value -> handoffEntryProgress.floatValue
                    else -> entryProgress.value
                },
            )
            val exitSample = RadialDismissMotion.sample(exitProgress.value)
            val handoffVisuals = RadialHandoffMotion.sample(
                frozenRevealProgress = entrySample.contentAlpha,
                handoffProgress = handoff,
                panelProgress = panel,
            )
            // 提早松手时从当前入场帧衔接退出，不跳到完整展开位置。
            val dismissalBlend = exitProgress.value.coerceIn(0f, 1f)
            val dismissal = if (exitRequest == null) null else RadialEntryVisuals(
                contentAlpha = entrySample.contentAlpha * exitSample.contentAlpha,
                radialProgress = exitSample.radialProgress + (entrySample.radialProgress - 1f) * (1f - dismissalBlend),
                horizontalOvershoot = exitSample.horizontalOvershoot + entrySample.horizontalOvershoot * (1f - dismissalBlend),
                rotationDegrees = exitSample.rotationDegrees + entrySample.rotationDegrees * (1f - dismissalBlend),
                iconScale = exitSample.iconScale + (entrySample.iconScale - 1f) * (1f - dismissalBlend),
            )
            val visual = dismissal ?: entrySample
            // 面板模式下窗口只有面板那么大，黑色蒙层会变成一道黑影扫过（用户反馈）→ 直接不画
            val scrimAlpha = if (panelModeState.value) 0f else visual.contentAlpha
            if (scrimAlpha > 0f) {
                drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * scrimAlpha)
            }
            val alpha = visual.contentAlpha * if (panelModeState.value) handoffVisuals.contentAlpha else 1f
            if (alpha <= 0f) return@Canvas
            drawRadialItems(
                metrics = metrics,
                layout = layout,
                motion = visual,
                contentAlpha = alpha,
                contentScale = if (panelModeState.value) handoffVisuals.contentScale else 1f,
                itemRings = itemRings,
                colors = itemColors,
            )
        }
    }

    private fun DrawScope.drawRadialItems(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        motion: RadialEntryVisuals,
        contentAlpha: Float,
        contentScale: Float,
        itemRings: List<Animatable<Float, *>>,
        colors: RadialItemColors,
    ) {
        val direction = if (layout.side == CornerSide.Left) 1f else -1f
        val overshoot = direction * RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP * metrics.pixelsPerBaseDp * motion.horizontalOvershoot
        layout.itemCenters.forEachIndexed { index, destination ->
            val centerX = layout.origin.x + (destination.x - layout.origin.x) * motion.radialProgress + overshoot
            val centerY = layout.origin.y + (destination.y - layout.origin.y) * motion.radialProgress
            val scale = motion.iconScale * contentScale
            val ringProgress = itemRings.getOrNull(index)?.value ?: 0f
            val diameter = metrics.iconDiameter * scale
            rotate(motion.rotationDegrees, pivot = Offset(centerX, centerY)) {
                if (index < catalog.radialApps.size) {
                    radialImages.value.getOrNull(index)?.let { image ->
                        drawSystemImage(image.icon, centerX, centerY, diameter, contentAlpha)
                        drawIconFinish(centerX, centerY, diameter, contentAlpha, colors.iconShadeAlpha)
                    }
                } else if (index == catalog.radialApps.size) {
                    drawMoreItem(centerX, centerY, diameter, contentAlpha, colors)
                } else {
                    innerImages.value.getOrNull(index - catalog.radialApps.size - 1)?.let { image ->
                        drawSystemImage(image.icon, centerX, centerY, diameter, contentAlpha)
                        drawIconFinish(centerX, centerY, diameter, contentAlpha, colors.iconShadeAlpha)
                    }
                }
                val strokeWidth = metrics.itemPadding * scale * ringProgress
                if (strokeWidth > 0f) {
                    drawCircle(
                        color = Color.White,
                        radius = diameter / 2f + strokeWidth / 2f,
                        center = Offset(centerX, centerY),
                        alpha = RadialEntryMotion.SELECTION_RING_ALPHA * contentAlpha,
                        style = Stroke(width = strokeWidth),
                    )
                }
                // 左右扇形的角标分别位于左下角、右下角，避开相邻图标，不参与圆形裁剪。
                radialImages.value.getOrNull(index)?.let { image ->
                    image.badge?.let { badge ->
                        drawBadge(
                            badge, centerX, centerY, diameter, contentAlpha,
                            horizontalOffsetFraction = if (layout.side == CornerSide.Left) image.badgeLeftOffsetFraction else 0f,
                        )
                    }
                }
            }
        }
    }

    /** 圆形图标上叠 1px 半透明白色内描边，让深色图标在遮罩上保有清晰边缘；深色模式另加压暗层。 */
    private fun DrawScope.drawIconFinish(
        centerX: Float,
        centerY: Float,
        diameter: Float,
        alpha: Float,
        shadeAlpha: Float,
    ) {
        if (diameter <= ICON_EDGE_WIDTH_PX * 2f) return
        val center = Offset(centerX, centerY)
        if (shadeAlpha > 0f) drawCircle(color = Color.Black, radius = diameter / 2f, center = center, alpha = shadeAlpha * alpha)
        drawCircle(
            color = Color.White,
            radius = diameter / 2f - ICON_EDGE_WIDTH_PX / 2f,
            center = center,
            alpha = ICON_EDGE_ALPHA * alpha,
            style = Stroke(width = ICON_EDGE_WIDTH_PX),
        )
    }

    private fun DrawScope.drawBadge(
        badge: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
        horizontalOffsetFraction: Float,
    ) {
        val pixels = size.roundToInt()
        if (pixels <= 0) return
        drawImage(
            image = badge,
            dstOffset = IntOffset(
                (centerX - pixels / 2f + pixels * horizontalOffsetFraction).roundToInt(),
                (centerY - pixels / 2f).roundToInt(),
            ),
            dstSize = IntSize(pixels, pixels),
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }

    /**
     * 按 ColorOS 17 工具栏溢出按钮的观感绘制：圆盘、向下偏移的外沿投影或微光、上下沿高光，
     * 以及横排三点；配色随深浅色切换。比例取自同一按钮 40dp 圆盘内 24dp 图标的实机测量，随图标直径缩放。
     */
    private fun DrawScope.drawMoreItem(
        centerX: Float,
        centerY: Float,
        diameter: Float,
        alpha: Float,
        colors: RadialItemColors,
    ) {
        val radius = diameter / 2f
        if (radius <= 0f) return
        if (colors.moreShadow.alpha > 0f && colors.moreShadowSpreadFraction > 0f) {
            val shadowCenter = Offset(centerX, centerY + diameter * colors.moreShadowOffsetFraction)
            val shadowRadius = radius + diameter * colors.moreShadowSpreadFraction
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colors.moreShadow,
                    radius / shadowRadius to colors.moreShadow,
                    1f to colors.moreShadow.copy(alpha = 0f),
                    center = shadowCenter,
                    radius = shadowRadius,
                ),
                radius = shadowRadius,
                center = shadowCenter,
                alpha = alpha,
            )
        }
        drawCircle(
            brush = Brush.verticalGradient(
                0f to colors.morePlateTop,
                1f to colors.morePlateBottom,
                startY = centerY - radius,
                endY = centerY + radius,
            ),
            radius = radius,
            center = Offset(centerX, centerY),
            alpha = alpha,
        )
        val rimWidth = diameter * MORE_RIM_WIDTH_FRACTION
        if (colors.moreRimSolid) {
            // 深色：一整圈纯色描边（原来的 verticalGradient 在深色下只剩上、下两个半圈白边）。
            drawCircle(
                color = colors.moreRim,
                radius = radius - rimWidth / 2f,
                center = Offset(centerX, centerY),
                alpha = alpha,
                style = Stroke(width = rimWidth),
            )
        } else {
            drawCircle(
                brush = Brush.verticalGradient(
                    0f to colors.moreRim,
                    0.5f to colors.moreRim.copy(alpha = 0f),
                    1f to colors.moreRim,
                    startY = centerY - radius,
                    endY = centerY + radius,
                ),
                radius = radius - rimWidth / 2f,
                center = Offset(centerX, centerY),
                alpha = alpha,
                style = Stroke(width = rimWidth),
            )
        }
        for (offset in -1..1) {
            drawCircle(
                color = colors.moreDot,
                radius = diameter * MORE_DOT_RADIUS_FRACTION,
                center = Offset(centerX + offset * diameter * MORE_DOT_SPACING_FRACTION, centerY),
                alpha = alpha,
            )
        }
    }

    private fun DrawScope.drawSystemImage(
        image: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
    ) {
        if (image.width <= 0 || image.height <= 0 || size <= 0f) return
        val aspectRatio = image.width.toFloat() / image.height
        val drawWidth = if (aspectRatio >= 1f) size else size * aspectRatio
        val drawHeight = if (aspectRatio >= 1f) size / aspectRatio else size
        val destinationSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1))
        radialClipPath.rewind()
        radialClipPath.addOval(Rect(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f))
        clipPath(radialClipPath) {
            drawImage(
                image = image,
                dstOffset =
                    IntOffset(
                        (centerX - destinationSize.width / 2f).roundToInt(),
                        (centerY - destinationSize.height / 2f).roundToInt(),
                    ),
                dstSize = destinationSize,
                alpha = alpha,
                filterQuality = FilterQuality.High,
            )
        }
    }

    @Composable
    private fun MoreAppsPanel(
        metrics: PanelVisualMetrics,
        panelProgress: Animatable<Float, *>,
        contentProgress: Animatable<Float, *>,
        inputEnabled: Boolean,
        animationsEnabled: Boolean,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val composeContext = androidx.compose.ui.platform.LocalContext.current
        val squircleEnabled = isSquircleEnabled()
        // 跟随系统深色模式：system_server 里只能读窗口 context 的 uiMode
        val dark =
            (composeContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val panelColor = if (dark) Color(0xFF1C1C1E) else Color.White
        val labelColor = if (dark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
        val sectionColor = if (dark) Color(0xFF9A9A9A) else Color(0xFF7A7A7A)
        // 展开/收起按钮要和底栏拉开对比，别糊成一片
        val toggleBackground = if (dark) Color(0xFF4C4C58) else Color(0xFFD2D2DC)
        val stripBackground = if (dark) Color(0xFF33333A) else Color(0xFFE8E8EF)
        val bounds = metrics.bounds
        val width = with(density) { bounds.width.toDp() }
        val height = with(density) { bounds.height.toDp() }
        val cornerRadius = with(density) { metrics.cornerRadius.toDp() }
        val horizontalPadding = with(density) { metrics.contentHorizontalPadding.toDp() }
        val verticalPadding = with(density) { metrics.topPadding.toDp() }
        val cellHeight = with(density) { metrics.cellHeight.toDp() }
        val iconSize = with(density) { metrics.iconDiameter.toDp() }
        val labelGap = with(density) { (metrics.labelTopOffset - metrics.iconDiameter / 2f).toDp() }
        val labelSize = with(density) { metrics.labelTextSize.toSp() }

        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        var toolsExpanded by remember { mutableStateOf(false) }
        // 工具栏展开后「长按 1.3 秒」进入的排序会话；点「收起」结束并提交顺序
        var ordering by remember { mutableStateOf(false) }
        var orderDirty by remember { mutableStateOf(false) }
        // 拖动排序先在本地列表里改，松手时提交
        val orderedTools = remember { mutableStateListOf<RadialEntry>() }
        // 已提交但服务端还没回传一致的顺序：用它给目录排序，避免收起瞬间跳回旧顺序
        var pendingOrder by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(catalog.tools, pendingOrder) {
            if (ordering) return@LaunchedEffect
            val source =
                if (pendingOrder.isEmpty()) catalog.tools
                else {
                    val index = pendingOrder.withIndex().associate { (i, alias) -> alias to i }
                    catalog.tools.sortedBy { entry ->
                        val alias =
                            (entry.target as? io.github.mangi.flymefreeform.config.PinnedTarget.Sidebar)
                                ?.alias
                        index[alias] ?: Int.MAX_VALUE
                    }
                }
            orderedTools.clear()
            orderedTools.addAll(source)
        }
        val showToggle = orderedTools.size > TOOL_STRIP_FIXED_COUNT
        val expandedNow = toolsExpanded || ordering
        val visibleTools =
            if (expandedNow) orderedTools.toList() else orderedTools.take(TOOL_STRIP_FIXED_COUNT)
        val stripItems: List<RadialEntry?> =
            if (showToggle) visibleTools + listOf<RadialEntry?>(null) else visibleTools
        // 「展开/收起」按钮所在的格子不参与长按排序（长按它不该进编辑态）
        val toggleIndex = if (showToggle) stripItems.lastIndex else -1
        val stripRows = if (stripItems.isEmpty()) 0 else (stripItems.size + TOOL_STRIP_SLOTS - 1) / TOOL_STRIP_SLOTS
        // 工具栏格子：图标 + 四周等距内边距（展开时再加一行文字）
        val cellPadding = with(density) { metrics.cellPadding.toDp() }
        val stripPadding = with(density) { metrics.stripPadding.toDp() }
        val indexBarInset = with(density) { metrics.indexBarInset.toDp() }
        val stripCellHeight =
            if (expandedNow) iconSize + cellPadding * 2 + (labelSize.value * 1.6f).dp
            else iconSize + cellPadding * 2
        val stripHintReserve = if (ordering) 20.dp else 0.dp
        val stripReserve =
            if (stripRows == 0) 0.dp
            else stripCellHeight * stripRows.toFloat() + 32.dp + stripHintReserve
        val groupOffsets =
            remember(catalog.appGroups) {
                var index = 0
                catalog.appGroups
                    .map { group ->
                        val start = index
                        index += 1 + group.apps.size
                        group.letter to start
                    }
                    .toMap()
            }
        val vibrateEnabled = catalog.settings.radialVibrateEnabled
        val vibrateStrength = catalog.settings.radialVibrateStrength

        fun tickVibrate() {
            if (vibrateEnabled) {
                io.github.mangi.flymefreeform.platform.Haptics.tick(composeContext, vibrateStrength)
            }
        }

        fun commitToolOrder() {
            val aliases =
                orderedTools.mapNotNull { entry ->
                    (entry.target as? io.github.mangi.flymefreeform.config.PinnedTarget.Sidebar)?.alias
                }
            orderDirty = false
            ordering = false
            pendingOrder = aliases
            listener.onToolOrderChanged(aliases)
        }

        Box(
            modifier =
                Modifier
                    .absoluteOffset {
                        IntOffset(
                            bounds.left.roundToInt() - windowOriginX.intValue,
                            bounds.top.roundToInt() - windowOriginY.intValue,
                        )
                    }
                    .requiredSize(width = width, height = height)
                    .panelReveal(
                        progress = {
                            // 横屏不做角落展开动画：窗口只有面板那么大，展开过程看起来像「从中间飞进来」
                            if (animationsEnabled && !metrics.isLandscape) panelProgress.value else 1f
                        },
                        seedSize = metrics.iconDiameter,
                        cornerRadius = metrics.cornerRadius,
                        anchorOnLeft = side == CornerSide.Left,
                        squircleEnabled = squircleEnabled,
                    )
                    .squircleSurface(color = panelColor, cornerRadius = cornerRadius)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            resetPanelTimeout()
                            waitForUpOrCancellation()
                        }
                    },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.columns),
                state = gridState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress =
                                if (animationsEnabled) contentProgress.value.coerceIn(0f, 1f) else 1f
                            alpha = progress
                            translationY =
                                (1f - progress) * metrics.iconDiameter * PANEL_CONTENT_TRANSLATION_FRACTION
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                contentPadding =
                    PaddingValues(
                        start = horizontalPadding,
                        end =
                            if (metrics.showIndexBar) INDEX_BAR_WIDTH + INDEX_BAR_GAP
                            else horizontalPadding,
                        top = verticalPadding + 4.dp,
                        bottom = verticalPadding + stripReserve + 8.dp,
                    ),
                verticalArrangement = Arrangement.Top,
                userScrollEnabled = inputEnabled && !ordering,
            ) {
                catalog.appGroups.forEach { group ->
                    item(key = "header_" + group.letter, span = { GridItemSpan(metrics.columns) }) {
                        Text(
                            text = group.letter,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
                            color = sectionColor,
                            fontSize = labelSize * 1.30f,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    items(
                        items = group.apps,
                        key = { entry -> io.github.mangi.flymefreeform.config.PinnedTargetCodec.encode(entry.target) },
                    ) { entry ->
                        PanelCell(
                            entry = entry,
                            images = panelImages,
                            iconSize = iconSize,
                            labelSize = labelSize,
                            labelColor = labelColor,
                            labelGap = labelGap,
                            cellHeight = cellHeight,
                            inputEnabled = inputEnabled,
                            onEntry = listener::onEntryCommitted,
                        )
                    }
                }
            }

            if (stripItems.isNotEmpty()) {
                ToolStripBlock(
                    items = stripItems,
                    slots = metrics.columns,
                    toggleIndex = toggleIndex,
                    expanded = expandedNow,
                    ordering = ordering,
                    images = panelImages,
                    iconSize = iconSize,
                    labelSize = labelSize,
                    labelColor = labelColor,
                    labelGap = labelGap,
                    cellHeight = stripCellHeight,
                    showLabels = expandedNow,
                    inputEnabled = inputEnabled,
                    onEntry = { entry -> if (!ordering) listener.onEntryCommitted(entry) },
                    onToggle = {
                        if (expandedNow) {
                            // 松手时已经提交过；这里只负责收起（万一还有未提交的改动也一并保存）
                            toolsExpanded = false
                            if (orderDirty) {
                                commitToolOrder()
                            } else {
                                ordering = false
                            }
                        } else {
                            toolsExpanded = true
                        }
                    },
                    onStartOrdering = {
                        if (!ordering) {
                            ordering = true
                            orderDirty = false
                            tickVibrate()
                        }
                    },
                    onFinishOrdering = {
                        // 松手直接提交，不用再点「收起」
                        if (ordering) {
                            if (orderDirty) commitToolOrder() else ordering = false
                        }
                    },
                    onMoveTool = { from, to ->
                        if (from in orderedTools.indices && to in orderedTools.indices) {
                            orderedTools.add(to, orderedTools.removeAt(from))
                            orderDirty = true
                        }
                    },
                    onVibrate = ::tickVibrate,
                    toggleBackground = toggleBackground,
                    stripBackground = stripBackground,
                    stripPadding = stripPadding,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // 左右等距 → 工具栏在面板里居中；不躲索引条
                            .padding(
                                start = horizontalPadding,
                                end = horizontalPadding,
                                bottom = 8.dp,
                            ),
                )
            }

            if (groupOffsets.size > 1 && !ordering && metrics.showIndexBar) {
                LetterIndexBar(
                    letters = catalog.appGroups.map { it.letter },
                    color = sectionColor,
                    modifier = Modifier.align(Alignment.CenterEnd).width(INDEX_BAR_WIDTH),
                    verticalInset = indexBarInset,
                    onLetter = { letter ->
                        toolsExpanded = false
                        val target = groupOffsets[letter]
                        if (target != null) {
                            scope.launch { gridState.scrollToItem(target) }
                        }
                        tickVibrate()
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ToolStripBlock(
        items: List<RadialEntry?>,
        slots: Int,
        toggleIndex: Int,
        expanded: Boolean,
        ordering: Boolean,
        images: Map<io.github.mangi.flymefreeform.config.PinnedTarget, ImageBitmap>,
        iconSize: androidx.compose.ui.unit.Dp,
        labelSize: androidx.compose.ui.unit.TextUnit,
        labelColor: Color,
        labelGap: androidx.compose.ui.unit.Dp,
        cellHeight: androidx.compose.ui.unit.Dp,
        showLabels: Boolean,
        inputEnabled: Boolean,
        onEntry: (RadialEntry) -> Unit,
        onToggle: () -> Unit,
        onStartOrdering: () -> Unit,
        onFinishOrdering: () -> Unit,
        onMoveTool: (Int, Int) -> Unit,
        onVibrate: () -> Unit,
        toggleBackground: Color,
        stripBackground: Color,
        stripPadding: androidx.compose.ui.unit.Dp = 5.dp,
        modifier: Modifier = Modifier,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cellHeightPx = with(density) { cellHeight.toPx() }
        var blockWidth by remember { mutableIntStateOf(0) }
        Column(
            modifier =
                modifier
                    .padding(top = 4.dp)
                    .onSizeChanged { blockWidth = it.width }
                    .then(
                        // 工具栏展开后：长按图标满 1.3 秒震动，不松手直接拖动排序
                        if (!expanded) {
                            Modifier
                        } else {
                            Modifier.pointerInput(items.size, blockWidth, cellHeightPx) {
                                fun indexAt(position: Offset): Int {
                                    if (blockWidth <= 0 || items.isEmpty()) return -1
                                    val cellWidth = blockWidth / slots.toFloat()
                                    val column =
                                        (position.x / cellWidth).toInt().coerceIn(0, slots - 1)
                                    val row = (position.y / cellHeightPx).toInt().coerceAtLeast(0)
                                    return (row * slots + column).coerceIn(0, items.lastIndex)
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // withTimeoutOrNull 只在「超时」时返回 null，即长按满 1.3 秒
                                    val heldLongEnough =
                                        withTimeoutOrNull(ORDER_LONG_PRESS_MS) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change =
                                                    event.changes.firstOrNull { it.id == down.id }
                                                        ?: return@withTimeoutOrNull Unit
                                                if (!change.pressed) return@withTimeoutOrNull Unit
                                                if ((change.position - down.position).getDistance() >
                                                    viewConfiguration.touchSlop
                                                ) {
                                                    return@withTimeoutOrNull Unit
                                                }
                                            }
                                        } == null
                                    if (!heldLongEnough) return@awaitEachGesture
                                    val startIndex = indexAt(down.position)
                                    if (startIndex < 0 || startIndex == toggleIndex) {
                                        return@awaitEachGesture
                                    }
                                    onStartOrdering()
                                    var dragging = startIndex
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                        val target = indexAt(change.position)
                                        if (dragging >= 0 && target >= 0 && target != dragging) {
                                            onMoveTool(dragging, target)
                                            dragging = target
                                            onVibrate()
                                        }
                                    }
                                    onFinishOrdering()
                                }
                            }
                        },
                    )
                    // 工具栏整体一块底色，和上方应用网格区分开
                    .background(stripBackground, RoundedCornerShape(22.dp))
                    .padding(horizontal = stripPadding, vertical = stripPadding),
        ) {
            if (ordering) {
                Text(
                    text = "拖动排序中 · 松手即保存",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = labelColor,
                    fontSize = labelSize * 0.9f,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            items.chunked(slots).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        if (item == null) {
                            ToolStripToggle(
                                expanded = expanded,
                                iconSize = iconSize,
                                labelSize = labelSize,
                                labelColor = labelColor,
                                cellHeight = cellHeight,
                                showLabel = showLabels,
                                centerVertically = true,
                                inputEnabled = inputEnabled,
                                onToggle = onToggle,
                                background = toggleBackground,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            PanelCell(
                                entry = item,
                                images = images,
                                iconSize = iconSize,
                                labelSize = labelSize,
                                labelColor = labelColor,
                                labelGap = labelGap,
                                cellHeight = cellHeight,
                                showLabel = showLabels,
                                centerVertically = true,
                                // 排序中不响应点击，长按拖动交给父级手势
                                inputEnabled = inputEnabled && !ordering,
                                onEntry = onEntry,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .then(
                                            if (ordering) {
                                                Modifier.graphicsLayer {
                                                    scaleX = 1.06f
                                                    scaleY = 1.06f
                                                    alpha = 1f
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                            )
                        }
                    }
                    repeat((slots - row.size).coerceAtLeast(0)) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PanelCell(
        entry: RadialEntry,
        images: Map<io.github.mangi.flymefreeform.config.PinnedTarget, ImageBitmap>,
        iconSize: androidx.compose.ui.unit.Dp,
        labelSize: androidx.compose.ui.unit.TextUnit,
        labelColor: Color,
        labelGap: androidx.compose.ui.unit.Dp,
        cellHeight: androidx.compose.ui.unit.Dp,
        showLabel: Boolean = true,
        centerVertically: Boolean = false,
        inputEnabled: Boolean,
        onEntry: (RadialEntry) -> Unit,
        onLongPress: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier =
                modifier
                    .height(cellHeight)
                    .combinedClickable(
                        enabled = inputEnabled,
                        onClick = { onEntry(entry) },
                        onLongClick = onLongPress,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
        ) {
            images[entry.target]?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(iconSize * TOOL_ICON_CORNER_RATIO)),
                    filterQuality = FilterQuality.High,
                )
            }
            if (!showLabel) return@Column
            Spacer(modifier = Modifier.height(labelGap))
            Text(
                text = entry.label,
                modifier = Modifier.fillMaxWidth(),
                color = labelColor,
                fontSize = labelSize,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ToolStripToggle(
        expanded: Boolean,
        iconSize: androidx.compose.ui.unit.Dp,
        labelSize: androidx.compose.ui.unit.TextUnit,
        labelColor: Color,
        cellHeight: androidx.compose.ui.unit.Dp,
        showLabel: Boolean = true,
        centerVertically: Boolean = false,
        inputEnabled: Boolean,
        onToggle: () -> Unit,
        onLongPress: () -> Unit = {},
        background: Color = MiuixTheme.colorScheme.surfaceVariant,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier =
                modifier
                    .height(cellHeight)
                    .combinedClickable(
                        enabled = inputEnabled,
                        onClick = onToggle,
                        onLongClick = onLongPress,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(iconSize)
                        .background(
                            color = background,
                            shape = RoundedCornerShape(percent = 50),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (expanded) "\u2212" else "\u22ef",
                    color = labelColor,
                    fontSize = labelSize * 1.2f,
                    maxLines = 1,
                )
            }
            if (!showLabel) return@Column
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (expanded) "收起" else "展开",
                modifier = Modifier.fillMaxWidth(),
                color = labelColor,
                fontSize = labelSize,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun LetterIndexBar(
        letters: List<String>,
        color: Color,
        modifier: Modifier = Modifier,
        verticalInset: androidx.compose.ui.unit.Dp = 45.dp,
        onLetter: (String) -> Unit,
    ) {
        val barHeight = remember { mutableIntStateOf(0) }
        val lastPicked = remember { mutableStateOf("") }
        val currentOnLetter by rememberUpdatedState(onLetter)
        val rows = letters.size
        val inset = verticalInset
        Column(
            modifier =
                modifier
                    // 撑满面板高度并让每条字母等分；上下各留一段空白，字母不贴面板边缘
                    .fillMaxHeight()
                    .padding(vertical = inset)
                    .onSizeChanged { barHeight.intValue = it.height }
                    .pointerInput(letters) {
                        // 按 y 位置算字母下标；连续拖动时只在字母变化时触发，避免抖动
                        fun pick(y: Float) {
                            val height = barHeight.intValue
                            if (rows <= 0 || height <= 0) return
                            val index = ((y / height) * rows).toInt().coerceIn(0, rows - 1)
                            val letter = letters[index]
                            if (letter != lastPicked.value) {
                                lastPicked.value = letter
                                currentOnLetter(letter)
                            }
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                lastPicked.value = ""
                                pick(offset.y)
                            },
                            onVerticalDrag = { change, _ -> pick(change.position.y) },
                        )
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            letters.forEach { letter ->
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).clickable { onLetter(letter) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    private val tuningObserver =
        object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                cachedTuning = null
                updateLayout()
            }
        }

    private fun registerTuningObserver() {
        try {
            PanelTuning.KEYS.forEach { key ->
                context.contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor(key),
                    false,
                    tuningObserver,
                )
            }
        } catch (_: RuntimeException) {
            // 拿不到就退回编译期默认值，不影响功能
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerTuningObserver()
        if (!disposed && !lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        post {
            if (!disposed && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
    }

    override fun onDetachedFromWindow() {
        try {
            context.contentResolver.unregisterContentObserver(tuningObserver)
        } catch (_: RuntimeException) {
        }
        val cleanupFailure = disposeOverlay()
        super.onDetachedFromWindow()
        cleanupFailure?.let(listener::onCleanupFailed)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (screenWidth == 0 || screenHeight == 0) {
            // 首次布局：窗口是全屏的，这里的尺寸就是屏幕尺寸，几何全部以它为准
            screenWidth = w
            screenHeight = h
            updateLayout()
            return
        }
        if (w == screenWidth && h == screenHeight) {
            updateLayout()
            return
        }
        // 面板模式会把窗口缩到面板大小，这是预期变化，不能当成「屏幕尺寸变了」而直接关面板
        if (panelModeState.value || windowOriginX.intValue != 0 || windowOriginY.intValue != 0) return
        if (oldw != 0) requestDismiss()
        updateLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        appliedWindowInsets = insets
        val result = super.onApplyWindowInsets(insets)
        updateLayout()
        return result
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (!panelModeState.value || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) requestDismiss()
        return true
    }

    private fun updateLayout() {
        if (screenWidth <= 0 || screenHeight <= 0 || disposed) return
        // 面板窗口会被缩小，几何必须一直按屏幕尺寸算，否则面板会越缩越小
        updateLayout(
            requestedWidth = screenWidth,
            requestedHeight = screenHeight,
            safeInsets = cachedSafeInsets ?: currentSafeInsets().also { cachedSafeInsets = it },
        )
    }

    private fun updateLayout(
        requestedWidth: Int,
        requestedHeight: Int,
        safeInsets: OverlaySafeInsets,
    ) {
        if (requestedWidth <= 0 || requestedHeight <= 0 || disposed) return
        val radialInsets = cachedRadialInsets ?: currentRadialInsets().also { cachedRadialInsets = it }
        val safeWidth = (requestedWidth - radialInsets.left - radialInsets.right).coerceAtLeast(1f)
        val safeHeight = (requestedHeight - radialInsets.top - radialInsets.bottom).coerceAtLeast(1f)
        val systemIconDiameter = systemIconSize()
        val metrics =
            AdaptiveOverlayGeometry.calculate(
                width = requestedWidth.toFloat(),
                height = requestedHeight.toFloat(),
                safeInsets = safeInsets,
                systemIconSize = systemIconDiameter,
                density = resources.displayMetrics.density,
                radialItemCount = catalog.radialApps.size + 1,
                radialInsets = radialInsets,
                fontScale = resources.configuration.fontScale,
                panelItemCount = catalog.panelApps.size,
                anchorOnLeft = side == CornerSide.Left,
                tuning = cachedTuning ?: PanelTuning.read(context.contentResolver).also {
                    cachedTuning = it
                },
            )
        metricsState.value = metrics
        layoutState.value =
            RadialGeometry.layoutRings(
                side = side,
                width = safeWidth,
                height = safeHeight,
                offsetX = radialInsets.left,
                offsetY = radialInsets.top,
                outerRadius = metrics.radial.radius,
                outerCount = catalog.radialApps.size + 1,
                innerRadius = metrics.radial.innerRadius,
                innerCount = catalog.innerApps.size,
            )
        if (!panelModeState.value && !dismissing) updateGestureFromLatestPoint()
    }

    private fun updateGestureFromLatestPoint() {
        val metrics = metricsState.value ?: return
        val layout = layoutState.value
        if (layout.itemCenters.isEmpty()) return
        val next =
            RadialGeometry.selection(
                layout = layout,
                x = latestX,
                y = latestY,
                previous = selectedIndex,
                enterRadius = metrics.radial.selectionEnterRadius,
                keepRadius = metrics.radial.selectionKeepRadius,
                outerCount = catalog.radialApps.size + 1,
                innerRadius = metrics.radial.innerRadius,
                iconRadius = metrics.radial.iconDiameter / 2f,
            )
        if (next == selectedIndex) return
        selectedIndex = next
        selectedIndexState.intValue = next ?: NO_SELECTION
        if (next == null) {
            // 离开图标区：清掉“上一个震过的图标”，下次滑回来才会再震
            lastVibratedIndex = null
            return
        }
        val now = SystemClock.uptimeMillis()
        // 只有滑到了另一个图标才震：同一个图标停住 / 在边缘抖动都不会连续震
        if (vibrateEnabled && next != lastVibratedIndex && now - lastVibrateUptime >= VIBRATE_MIN_INTERVAL_MS) {
            lastVibrateUptime = now
            lastVibratedIndex = next
            tickVibrate()
        } else if (!vibrateEnabled) {
            lastVibratedIndex = next
        }
    }

    private fun tickVibrate() {
        // 与设置页强度条预览共用同一套参数（platform/Haptics）。
        io.github.mangi.flymefreeform.platform.Haptics.tick(context, vibrateStrength)
    }

    private fun showMorePanel() {
        if (dismissing || panelModeState.value) return
        selectedIndex = null
        handoffEntryProgress.floatValue = entryProgress.value
        handoffLayoutState.value = layoutState.value
        handoffMetricsState.value = metricsState.value?.radial
        removeCallbacks(timeout)
        listener.onMorePanelRequested()
    }

    fun setWindowOrigin(x: Int, y: Int) {
        windowOriginX.intValue = x
        windowOriginY.intValue = y
    }

    /**
     * 面板模式应当使用的窗口矩形（屏幕坐标）。
     *
     * 全屏窗口会把桌面/导航手势整块吃掉（桌面底部上滑失效），所以开面板时把窗口缩到面板大小；
     * 上边/左右各外扩 8dp 给阴影留位置，**底边不外扩**，把屏幕底部留回给系统手势。
     */
    fun panelWindowRect(): android.graphics.Rect? {
        val panel = metricsState.value?.panel ?: return null
        val inset = 0
        val bounds = panel.bounds
        return android.graphics.Rect(
            bounds.left.toInt() - inset,
            bounds.top.toInt() - inset,
            bounds.right.toInt() + inset,
            bounds.bottom.toInt(),
        )
    }

    fun showBuiltInMorePanel() {
        if (disposed || !isAttachedToWindow) return
        backdropOnlyState.value = false
        panelModeState.value = true
        panelShownAt = SystemClock.uptimeMillis()
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
        requestFocus()
    }

    /**
     * 面板窗口在 IME 层级：回桌面 / 切应用时系统会先隐藏再显示它，
     * 表现就是「面板先消失、又闪一下回来」。这里在失去焦点时直接收掉整块。
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            hadWindowFocus = true
            return
        }
        if (!hadWindowFocus) return
        hadWindowFocus = false
        if (!panelModeState.value || dismissing) return
        if (SystemClock.uptimeMillis() - panelShownAt < PANEL_FOCUS_GRACE_MS) return
        requestDismiss()
    }

    fun retainBackdropForPanel() {
        backdropAlpha.floatValue = 1f
        backdropOnlyState.value = true
    }

    fun beginBackdropExit() {
        if (disposed || !backdropOnlyState.value) return
        backdropAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            backdropAlpha.floatValue = 0f
            return
        }
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            addUpdateListener {
                val alpha = AllAppsPanelMotion.exit(it.currentPlayTime / 1000f).alpha
                backdropAlpha.floatValue = alpha
                if (alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD) {
                    backdropAlpha.floatValue = 0f
                    cancel()
                }
            }
            start()
        }
    }

    fun hideBackdropAfterFrame(onHidden: () -> Unit) {
        backdropAnimator?.cancel()
        backdropAnimator = null
        if (disposed || !isAttachedToWindow) {
            onHidden()
            return
        }
        // 必须等透明帧提交后才让截图等工具执行，不能只确认状态变量已经更新。
        if (isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { post { onHidden() } }
        } else {
            postOnAnimation { postOnAnimation { onHidden() } }
        }
        backdropAlpha.floatValue = 0f
        invalidate()
    }

    private fun dismissAnimated(pendingCommit: RadialEntry?) {
        if (dismissing || disposed) return
        dismissing = true
        removeCallbacks(timeout)
        exitRequestState.value = ExitRequest(pendingCommit, entryProgress.value)
        removeCallbacks(dismissFallback)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeRadialExit()
        } else {
            val durationScale = ValueAnimator.getDurationScale().coerceAtLeast(1f)
            val fallbackDelay =
                (RadialDismissMotion.DURATION_MILLIS * durationScale + DISMISS_FALLBACK_GRACE_MS)
                    .roundToInt()
                    .toLong()
            postDelayed(dismissFallback, fallbackDelay)
        }
    }

    private fun completeRadialExit() {
        if (dismissNotified || disposed) return
        removeCallbacks(dismissFallback)
        val pendingCommit = exitRequestState.value?.pendingCommit
        exitRequestState.value = null
        if (pendingCommit == null) {
            requestDismiss()
        } else {
            dismissNotified = true
            removeCallbacks(timeout)
            listener.onEntryCommitted(pendingCommit)
        }
    }

    private fun resetPanelTimeout() {
        if (!panelModeState.value || disposed) return
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
    }

    private fun requestDismiss() {
        if (dismissNotified || disposed) return
        dismissNotified = true
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        listener.onDismissRequested()
    }

    private fun mergeCleanupFailure(
        current: Throwable?,
        next: Throwable,
    ): Throwable =
        current?.also { previous -> previous.addSuppressed(next) } ?: next

    private fun currentRadialInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        // 扇形属于角落手势本身；系统手势保留区及屏幕圆角不是整页绘制边距。
        // 只避让实际系统栏、缺口和键盘，正常竖屏圆心落在物理侧边与导航栏顶边。
        val insets = windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        return OverlaySafeInsets(insets.left.toFloat(), insets.top.toFloat(), insets.right.toFloat(), insets.bottom.toFloat())
    }

    private fun currentSafeInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        val drawingInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
        val gestureInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or
                    WindowInsets.Type.mandatorySystemGestures(),
            )
        val topLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        return OverlaySafeInsets(
            left = maxOf(drawingInsets.left, gestureInsets.left).toFloat(),
            top =
                maxOf(
                    drawingInsets.top,
                    gestureInsets.top,
                    topLeft?.radius ?: 0,
                    topRight?.radius ?: 0,
                ).toFloat(),
            right = maxOf(drawingInsets.right, gestureInsets.right).toFloat(),
            bottom =
                maxOf(
                    drawingInsets.bottom,
                    gestureInsets.bottom,
                    bottomLeft?.radius ?: 0,
                    bottomRight?.radius ?: 0,
                ).toFloat(),
        )
    }

    private fun systemIconSize(): Float =
        try {
            context
                .getSystemService(ActivityManager::class.java)
                ?.launcherLargeIconSize
                ?.toFloat()
                ?: 0f
        } catch (_: RuntimeException) {
            0f
        }

    private data class ExitRequest(val pendingCommit: RadialEntry?, val entryProgress: Float)

    private companion object {
        val EMPTY_LAYOUT =
            RadialLayout(
                side = CornerSide.Right,
                origin = io.github.mangi.flymefreeform.gesture.GesturePoint(0f, 0f),
                radius = 0f,
                itemCenters = emptyList(),
            )
        const val NO_SELECTION = -1
        // 工具栏：长按 1.3 秒才进入拖动排序；格子高度 = 图标 × 1.42（图标 + 一行文字）
        const val ORDER_LONG_PRESS_MS = 600L
        const val STRIP_CELL_HEIGHT_RATIO = 1.75f
        val STRIP_CELL_PADDING = 8.dp
        val INDEX_BAR_VERTICAL_INSET = 45.dp
        // 面板刚开时的焦点抖动窗口（系统 updateViewLayout 会先给一次 false）
        const val PANEL_FOCUS_GRACE_MS = 600L
        const val ICON_EDGE_ALPHA = 0x24 / 255f
        // 描边固定为物理 1px 发丝线，不随密度和扇形缩放加粗。
        const val ICON_EDGE_WIDTH_PX = 1f
        const val PANEL_SURFACE_ALPHA = 253f / 255f
        // 24dp 图标在 40dp 圆盘内：点径 3.5、点距 7.125（图标视口单位）。
        const val MORE_DOT_RADIUS_FRACTION = 1.75f / 24f * 0.6f
        const val MORE_DOT_SPACING_FRACTION = 7.125f / 24f * 0.6f
        const val MORE_RIM_WIDTH_FRACTION = 0.02f
        const val PANEL_EXPAND_DAMPING = 0.9f
        const val PANEL_EXPAND_RESPONSE_SECONDS = 0.3f
        const val PANEL_VISIBILITY_THRESHOLD = 0.0001f
        const val PANEL_CONTENT_DELAY_MILLIS = 45
        const val PANEL_CONTENT_FADE_MILLIS = 180
        const val PANEL_CONTENT_TRANSLATION_FRACTION = 0.12f
        const val TICK_INTERVAL_MS = 80L
        const val VIBRATE_MIN_INTERVAL_MS = 70L
        const val TOOL_STRIP_FIXED_COUNT = 4
        const val TOOL_STRIP_SLOTS = 5
        const val APP_GRID_COLUMNS = 5
        val INDEX_BAR_WIDTH = 23.dp
        val INDEX_BAR_GAP = 1.5.dp
        const val TOOL_ICON_CORNER_RATIO = 0.24f
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val PANEL_TIMEOUT_MS = 15_000L
        const val DISMISS_FALLBACK_GRACE_MS = 260L
    }
}

private fun Modifier.panelReveal(
    progress: () -> Float,
    seedSize: Float,
    cornerRadius: Float,
    anchorOnLeft: Boolean,
    squircleEnabled: Boolean,
): Modifier =
    drawWithCache {
        val revealPath = Path()
        onDrawWithContent {
            val revealProgress = progress()
            val revealWidth = PanelRevealMotion.extent(size.width, seedSize, revealProgress)
            val revealHeight = PanelRevealMotion.extent(size.height, seedSize, revealProgress)
            val revealLeft =
                PanelRevealMotion.horizontalOffset(size.width, revealWidth, anchorOnLeft)
            val revealTop = PanelRevealMotion.verticalOffset(size.height, revealHeight)
            revealPath.rewind()
            revealPath.addSquircleRect(
                width = revealWidth,
                height = revealHeight,
                cornerRadius = cornerRadius,
                squircleEnabled = squircleEnabled,
            )
            translate(left = revealLeft, top = revealTop) {
                clipPath(revealPath) {
                    translate(left = -revealLeft, top = -revealTop) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }

private class RadialImage(entry: RadialEntry) {
    val icon: ImageBitmap = entry.icon.asImageBitmap()
    val badge: ImageBitmap? = entry.badge?.asImageBitmap()
    val badgeLeftOffsetFraction: Float = entry.badgeLeftOffsetFraction
}
