package io.github.mangi.flymefreeform.platform.coloros

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.isNotEmpty
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import io.github.mangi.flymefreeform.window.AllAppsPanelMotion

/** 独立窗口只承载本次新建的全部内容；不挂接原侧栏父视图，也不改变其状态机。 */
internal class ColorOsAllAppsWindow(
    private val context: Context,
    val content: ColorOsAllAppsContent,
    private val onShown: () -> Unit,
    private val onClosed: () -> Unit,
    private val onExitStarted: () -> Unit,
    private val beforeTool: (() -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    /** 面板整体等比缩放比例（移植自 Sillybomo/FlymeFreeform 的 panelScale）；50-100，存 Settings.Global。 */
    private val panelScale: Float =
        (android.provider.Settings.Global.getInt(
            context.contentResolver,
            "flyme_ff_panel_scale",
            90,
        ) / 100f).coerceIn(0.5f, 1f)
    private val card = content.createCard()
    private var attached = false
    private var closed = false
    private var exiting = false
    private var entering = true
    private var animationStart = 0L
    private var durationScale = 1f
    private var notified = false
    private var hiddenAction: (() -> Unit)? = null
    private val dimensions = content.dimensions()
    private val mode = content.mode()
    private val leftSide = content.leftSide()
    private var insets: WindowInsets? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { back() }
    private val animation = Runnable { animateFrame() }
    private val timeout = Runnable { dismiss() }
    private val firstFrame = ViewTreeObserver.OnPreDrawListener { prepareFirstFrame() }
    private val closeDialogsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                dismiss()
            }
        }
    private var waitingForFirstFrame = true
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                android.util.Log.i("FlymeFreeform", "ALLAPPS_OUTSIDE")
                dismiss()
                return true
            }
            if (exiting || entering) return true
            resetTimeout()
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            }
        }

        override fun onDetachedFromWindow() {
            backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
            backDispatcher = null
            super.onDetachedFromWindow()
            if (!closed) dispose()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            placeCard()
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            // 窗口缩到面板大小后，点面板外不再进我们的 view，靠失焦收掉
            android.util.Log.i("FlymeFreeform", "ALLAPPS_FOCUS has=" + hasFocus + " entering=" + entering + " exiting=" + exiting)
            if (!hasFocus && !entering && !exiting) dismiss()
        }
    }

    init {
        root.setBackgroundColor(0)
        root.isFocusableInTouchMode = true
        root.clipChildren = false
        card.alpha = 0f
        card.visibility = View.INVISIBLE
        content.view.alpha = 0f
        card.isClickable = true
        // 首帧就按面板几何尺寸建 card，避免先铺满窗口再缩回来造成闪动。
        val metrics = manager.currentWindowMetrics
        val safe = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        // 和 show() 用同一窗口尺寸（全屏减状态栏、导航条），避免首帧后 placeCard 再算一次而跳格。
        val winWidth = metrics.bounds.width()
        val winHeight = (metrics.bounds.height() - safe.top - safe.bottom).coerceAtLeast(1)
        val initial = AllAppsPanelGeometry.calculate(
            winWidth, winHeight, dimensions, mode, leftSide,
        )
        @SuppressLint("RtlHardcoded")
        val cardLayout = FrameLayout.LayoutParams(initial.width, initial.height).apply {
            leftMargin = initial.left
            topMargin = initial.top
            gravity = Gravity.TOP or Gravity.LEFT
        }
        root.addView(card, cardLayout)
        root.setOnClickListener { dismiss() }
        root.setOnApplyWindowInsetsListener { _, value ->
            insets = value
            placeCard()
            value
        }
        content.bindClose { dismiss() }
    }

    fun show() {
        check(!attached && !closed)
        // 窗口铺满屏幕、底部留出系统手势区：面板外点击由窗口自己吃掉（只收起面板，不穿透到桌面图标）。
        val metrics = manager.currentWindowMetrics
        val safe = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = metrics.bounds.width()
        val top = safe.top
        val height = (metrics.bounds.height() - safe.top - safe.bottom).coerceAtLeast(1)
        // 2038 是应用悬浮窗，只有应用窗口才能当输入法目标；2012 是输入法层，搜索框永远弹不出键盘。
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformAllApps"
        }
        manager.addView(root, params)
        try {
            context.registerReceiver(
                closeDialogsReceiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                Context.RECEIVER_EXPORTED,
            )
        } catch (_: RuntimeException) {
            // 拿不到就只靠窗口失焦
        }
        attached = true
        root.viewTreeObserver.addOnPreDrawListener(firstFrame)
        root.requestApplyInsets()
        root.requestFocus()
    }

    private fun prepareFirstFrame(): Boolean {
        if (closed) return true
        if (!waitingForFirstFrame) return true
        return try {
            val layout = card.layoutParams as FrameLayout.LayoutParams
            if (root.width <= 0 || root.height <= 0 || insets == null ||
                card.isLayoutRequested || card.width != layout.width || card.height != layout.height ||
                card.left != layout.leftMargin || card.top != layout.topMargin || !content.attachBlur()
            ) return false
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            val initial = AllAppsPanelMotion.enter(0f)
            card.scaleX = initial.scale * panelScale
            card.scaleY = initial.scale * panelScale
            content.view.alpha = initial.alpha
            card.alpha = 1f
            card.visibility = View.VISIBLE
            waitingForFirstFrame = false
            root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
            startAnimation()
            resetTimeout()
            true
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
            true
        }
    }

    fun isShown(): Boolean = attached && !closed && !exiting && !entering &&
        root.isShown && card.width > 0 && content.view.isNotEmpty()

    fun beginItemClick(afterHidden: (() -> Unit)? = null): Boolean {
        if (!isShown()) return false
        hiddenAction = afterHidden
        dismiss()
        return true
    }

    fun dismiss() {
        if (closed || exiting) return
        exiting = true
        entering = false
        onExitStarted()
        root.removeCallbacks(timeout)
        if (waitingForFirstFrame) {
            dispose()
            return
        }
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        startAnimation()
    }

    fun dispose() {
        if (closed) return
        closed = true
        try {
            context.unregisterReceiver(closeDialogsReceiver)
        } catch (_: RuntimeException) {
        }
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
        hiddenAction = null
        root.removeCallbacks(animation)
        root.removeCallbacks(timeout)
        try {
            content.close()
        } catch (exception: Exception) {
            onFailure(exception)
        } finally {
            if (attached) {
                attached = false
                try {
                    manager.removeViewImmediate(root)
                } catch (_: IllegalArgumentException) {
                    // 系统已移除窗口。
                }
            }
            root.removeAllViews()
            onClosed()
        }
    }

    private fun back() = safely {
        if (!exiting && !content.leaveSearch()) dismiss()
    }

    // leftMargin 是物理坐标，必须与初次放置使用同一坐标系。
    @SuppressLint("RtlHardcoded")
    private fun placeCard() {
        if (closed || root.width <= 0 || root.height <= 0) return
        // 窗口铺满屏幕，card 用同一套几何算居中位置。
        val safe = insets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val bounds = AllAppsPanelGeometry.calculate(
            root.width, root.height, dimensions, mode, leftSide,
            safe?.left ?: 0, safe?.top ?: 0, safe?.right ?: 0, maxOf(safe?.bottom ?: 0, ime),
        )
        val params = card.layoutParams as FrameLayout.LayoutParams
        if (params.width == bounds.width && params.height == bounds.height &&
            params.leftMargin == bounds.left && params.topMargin == bounds.top
        ) return
        params.width = bounds.width
        params.height = bounds.height
        params.leftMargin = bounds.left
        params.topMargin = bounds.top
        params.gravity = Gravity.TOP or Gravity.LEFT
        card.layoutParams = params
        applyBaseTransform()
    }

    private fun applyBaseTransform() {
        if (card.width <= 0 || card.height <= 0) return
        // 缩放锚点贴呼出侧，面板缩小后仍靠同一边，而不是四周均匀收缩。
        card.pivotX = if (content.leftSide()) 0f else card.width.toFloat()
        card.pivotY = card.height / 2f
    }

    private fun startAnimation() {
        root.removeCallbacks(animation)
        durationScale = ValueAnimator.getDurationScale()
        animationStart = SystemClock.uptimeMillis()
        if (!ValueAnimator.areAnimatorsEnabled() || durationScale <= 0f) {
            if (exiting) finishExit() else finishEnter()
            return
        }
        root.postOnAnimation(animation)
    }

    private fun animateFrame(): Unit = safely {
        if (closed) return@safely
        val elapsed = (SystemClock.uptimeMillis() - animationStart) / durationScale
        val frame = if (exiting) AllAppsPanelMotion.exit(elapsed / 1_000f) else AllAppsPanelMotion.enter(elapsed / 1_000f)
        // 材质底色不参与入场渐变，只有内容淡入；避免暗色桌面透入后再变亮。
        card.alpha = if (exiting) frame.alpha else 1f
        content.view.alpha = if (exiting) 1f else frame.alpha
        card.pivotX = if (content.leftSide()) 0f else card.width.toFloat()
        card.pivotY = card.height / 2f
        card.scaleX = frame.scale * panelScale
        card.scaleY = frame.scale * panelScale
        content.updateBlur(if (exiting) frame.alpha else 1f)
        if (exiting && (frame.alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishExit()
        } else if (!exiting && ((frame.alpha >= 0.997f && frame.scale >= 0.997f) || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishEnter()
        } else {
            root.postOnAnimation(animation)
        }
    }

    private fun finishEnter() {
        entering = false
        card.alpha = 1f
        content.view.alpha = 1f
        card.scaleX = panelScale
        card.scaleY = panelScale
        content.updateBlur(1f)
        if (!notified) {
            notified = true
            onShown()
        }
    }

    private fun finishExit() {
        card.alpha = 0f
        root.setBackgroundColor(0)
        content.updateBlur(0f)
        val action = hiddenAction
        hiddenAction = null
        if (action == null) {
            dispose()
            return
        }
        // 先提交不可见帧，再执行原厂工具；短暂保留窗口所有权供系统判断前台发起者。
        root.postOnAnimation {
            safely {
                if (closed) return@safely
                beforeTool {
                    safely {
                        if (closed) return@safely
                        action()
                        root.postOnAnimation { if (!closed) dispose() }
                    }
                }
            }
        }
    }

    private fun resetTimeout() {
        root.removeCallbacks(timeout)
        root.postDelayed(timeout, 120_000L)
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
        }
    }
}
