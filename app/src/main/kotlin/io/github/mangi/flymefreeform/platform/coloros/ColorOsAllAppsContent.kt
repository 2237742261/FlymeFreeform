package io.github.mangi.flymefreeform.platform.coloros

import android.animation.Animator
import android.content.Context
import android.util.Log
import android.os.IInterface
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isNotEmpty
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 仅适配已核对的侧栏版本：独立创建内容视图，不修改原生面板状态。 */
internal class ColorOsAllAppsContent(
    loader: ClassLoader,
    target: ColorOsSidebarVisualTarget,
    private val module: io.github.libxposed.api.XposedModule,
) {
    private val allClass = loader.loadClass(ALL_CLASS)
    private val searchClass = loader.loadClass(SEARCH_CLASS)
    private val mainClass = loader.loadClass(MAIN_CLASS)
    private val userClass = loader.loadClass(USER_CLASS)
    private val cardClass = loader.loadClass("com.coui.appcompat.cardview.COUICardView")
    private val handlerClass = loader.loadClass(HANDLER_CLASS)
    private val handler = handlerClass.getField("INSTANCE").get(null)
    private val getMain = handlerClass.getMethod("getMPanelMainView")
    private val getResident = handlerClass.getMethod("getMResidentProcessHandler")
    private val getState = mainClass.getMethod("getMState")
    private val getUser = mainClass.getMethod("getMPanel")
    private val getLeft = mainClass.getMethod("getMIsLeft")
    private val getRadius = cardClass.getMethod("getRadius")
    private val getWeight = cardClass.getMethod("getWeight")
    private val getColor = userClass.getMethod("getUserPanelBgColor")
    private val getSearch = allClass.getMethod("getSearchHelper")
    private val getRouter = searchClass.getMethod("getAppDataHandler")
    private val load = searchClass.getMethod("loadData")
    private val stop = searchClass.getMethod("stopLoadData")
    private val getSearchState = searchClass.getMethod("getSearchState")
    private val getData = searchClass.getMethod("getCurrentDataList")
    private val dataClass = loader.loadClass(DATA_CLASS)
    private val getKey = dataClass.getMethod("getKey")
    private val getEntry = dataClass.getMethod("getEntryBean")
    private val getType = getEntry.returnType.getMethod("getType")
    private val getLabel = getEntry.returnType.getMethod("getLabel")
    private val adapterClass = loader.loadClass(ADAPTER_CLASS)
    private val getMData = adapterClass.getMethod("getMData")
    private val helperClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper")
    private val getAllTools = helperClass.getMethod("getAllAppListForTool")
    private val toolBeanLabel = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean").getMethod("getLabel")
    private val getClick = adapterClass.getMethod("getOnItemClick")
    private val setClick = adapterClass.getMethod("setOnItemClick", getClick.returnType)
    private val invokeClick = getClick.returnType.getMethod("invoke", Any::class.java, Any::class.java)
    private val nativeUnit = checkNotNull(target.unit.get(null))
    private val cancelSearch = allClass.getMethod("cancelSearchEditing", Boolean::class.javaPrimitiveType)
    private val enterSearch = optionalAnimator("getEnterSearchAnimatorSet")
    private val exitSearch = optionalAnimator("getExitSearchAnimatorSet")
    private val resource = checkNotNull(target.resource.get(null))
    private val isLandscape = target.landscape
    private val isPortrait = target.portrait
    private val isTabletop = target.hover
    private val gap = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper").getMethod("getPanelGap")
    private val sidebarBlur = ColorOsSidebarBlur(target.blur)
    private val shadow = target.shadow

    // Service 使用平台默认主题；原厂内容依赖 App 的配置/主题包装，不能直接传 Service。
    private val context = themedContext(loader)

    val view = construct(allClass) as ViewGroup
    private val search = getSearch.call(view)!!
    val router = getRouter.call(search)!!
    private var closed = false
    private var loading = false
    private var blur: ColorOsSidebarBlur.Session? = null
    private var blurView: View? = null
    private var blurPrepared = false
    private var card: FrameLayout? = null

    init {
        require(ViewGroup::class.java.isAssignableFrom(allClass))
        require(FrameLayout::class.java.isAssignableFrom(cardClass))
        require(getRouter.returnType.name == ROUTER_CLASS)
        allClass.getMethod("showAllPanel", Boolean::class.javaPrimitiveType).call(view, leftSide())
    }

    fun ready(): Boolean = view.isNotEmpty() && getMain.call(handler) != null &&
        (getResident.call(handler) as? IInterface)?.asBinder()?.isBinderAlive == true

    fun sidebarHidden(): Boolean = (getMain.call(handler)?.let { getState.call(it) } as? Enum<*>)?.name == "FLOAT_BAR_SHOWING"

    fun leftSide(): Boolean =
        try {
            // 按呼出角落决定面板靠哪边：左下角呼出靠左，右下角呼出靠右。
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                "flyme_ff_more_panel_left",
                0,
            ) == 1
        } catch (_: RuntimeException) {
            false
        }

    fun startLoading() {
        if (closed || loading) return
        loading = true
        load.call(search)
    }

    fun refresh() {
        if (closed || !loading) return
        stop.call(search)
        load.call(search)
    }

    @Suppress("DiscouragedApi")
    fun bindClose(action: () -> Unit) {
        if (closed) return
        val toolbarId = context.resources.getIdentifier("all_app_toolbar", "id", ColorOsSidebarTarget.PACKAGE_NAME)
        val closeId = context.resources.getIdentifier("close", "id", ColorOsSidebarTarget.PACKAGE_NAME)
        val toolbar = if (toolbarId == 0) null else view.findViewById<View>(toolbarId)
        val close = if (closeId == 0) null else view.findViewById<View>(closeId)
        when {
            toolbar != null -> {
                val menu = toolbar.javaClass.getMethod("getMenu").call(toolbar) as Menu
                checkNotNull(menu.findItem(resourceId("cancel", "id")))
                    .setOnMenuItemClickListener { action(); true }
            }
            close != null -> close.setOnClickListener { action() }
            else -> throw IllegalStateException("SIDEBAR_CLOSE_CONTROL_UNAVAILABLE")
        }
    }

    private fun optionalAnimator(name: String): Method? = try {
        searchClass.getMethod(name).also { require(Animator::class.java.isAssignableFrom(it.returnType)) }
    } catch (_: NoSuchMethodException) {
        null
    }

    fun bindAdapter(adapter: Any, onClick: (Boolean, () -> Unit) -> Unit) {
        hookTools()
        hookMData()
        reorderTools(adapter)
        observeAdapter(adapter)
        val original = getClick.call(adapter) ?: return
        val callback = Proxy.newProxyInstance(getClick.returnType.classLoader, arrayOf(getClick.returnType)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    if (!closed) {
                        val index = args?.getOrNull(0) as? Int
                        val clickType = args?.getOrNull(1) as? Int
                        val list = getData.call(search) as? List<*>
                        val data = index?.let { list?.getOrNull(it) }
                        if (data != null && clickType != null && dataClass.isInstance(data)) {
                            val key = getKey.call(data)
                            val entry = getEntry.call(data)
                            val tool = entry != null && getType.call(entry) == 0
                            onClick(tool) {
                                // 动画期间目录可能刷新，执行前按原条目标识重新定位，不能复用旧索引。
                                val current = getData.call(search) as? List<*>
                                val targetIndex = current?.indexOfFirst { it != null && dataClass.isInstance(it) && getKey.call(it) == key } ?: -1
                                if (!closed && targetIndex >= 0) invokeClick.call(original, targetIndex, clickType)
                            }
                        }
                    }
                    nativeUnit
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "AllAppsClickCallback"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        setClick.call(adapter, callback)
    }

    private val toolHeadOrder =
        listOf("微信扫一扫", "支付宝扫一扫", "微信支付", "支付宝付款")
    private var reorderRunning = false

    private fun matches(name: String, label: String?): Boolean {
        if (label == null) return false
        val a = name.replace(" ", "")
        val b = label.replace(" ", "")
        return b == a || b.contains(a)
    }

    private fun labelOf(data: Any?): String? {
        if (data == null || !dataClass.isInstance(data)) return null
        return runCatching {
            val entry = getEntry.call(data) ?: return@runCatching null
            getLabel.invoke(entry)?.toString()
        }.getOrNull()
    }

    private var toolsHooked = false

    private fun hookTools() {
        if (toolsHooked) return
        toolsHooked = true
        try {
            module.hook(getAllTools).intercept { chain ->
                val original = chain.proceed()
                @Suppress("UNCHECKED_CAST")
                val list = original as? List<Any?> ?: return@intercept original
                reorderList(list)
            }
            Log.i("FlymeFreeform", "TOOLS hook_ok")
        } catch (throwable: Throwable) {
            Log.i("FlymeFreeform", "TOOLS hook_fail " + throwable)
        }
    }

    private fun reorderList(list: List<Any?>): List<Any?> {
        if (list.size < toolHeadOrder.size) return list
        val labelOfBean: (Any?) -> String? = { bean ->
            if (bean == null) null else runCatching { toolBeanLabel.invoke(bean)?.toString() }.getOrNull()
        }
        val current = list.take(toolHeadOrder.size).map(labelOfBean)
        if (toolHeadOrder.indices.all { i -> matches(toolHeadOrder[i], current.getOrNull(i)) }) return list
        val used = HashSet<Any>()
        val head =
            toolHeadOrder.mapNotNull { name ->
                list.firstOrNull { bean -> bean != null && !used.contains(bean) && matches(name, labelOfBean(bean)) }
                    ?.also { used.add(it) }
            }
        if (head.size != toolHeadOrder.size) {
            Log.i("FlymeFreeform", "TOOLS miss head=" + head.map(labelOfBean) + " all=" + list.take(12).map(labelOfBean))
            return list
        }
        Log.i("FlymeFreeform", "TOOLS done head=" + head.map(labelOfBean))
        return head + list.filterNot { bean -> head.any { it === bean } }
    }

    private fun hookMData() {
        if (mDataHooked) return
        mDataHooked = true
        try {
            module.hook(getMData).intercept { chain ->
                val ordered = orderedItems
                if (ordered != null && ordered.isNotEmpty()) ordered else chain.proceed()
            }
            Log.i("FlymeFreeform", "REORDER hook_mdata_ok")
        } catch (throwable: Throwable) {
            Log.i("FlymeFreeform", "REORDER hook_mdata_fail " + throwable)
        }
    }

    private fun reorderTools(adapter: Any) {
        if (reorderRunning) return
        try {
            val raw = runCatching { getMData.call(adapter) }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val list = raw as? List<Any?> ?: return
            if (list.size < toolHeadOrder.size) return
            val currentLabels = list.take(toolHeadOrder.size).map { labelOf(it) }
            if (toolHeadOrder.indices.all { i -> matches(toolHeadOrder[i], currentLabels.getOrNull(i)) }) {
                orderedItems = list
                return
            }
            Log.i("FlymeFreeform", "REORDER all=" + list.mapNotNull { labelOf(it) }.joinToString("|"))
            val used = HashSet<Any>()
            val head =
                toolHeadOrder.mapNotNull { name ->
                    list.firstOrNull { item -> item != null && !used.contains(item) && matches(name, labelOf(item)) }
                        ?.also { used.add(it) }
                }
            if (head.size != toolHeadOrder.size) {
                Log.i("FlymeFreeform", "REORDER miss head=" + head.map { labelOf(it) })
                return
            }
            reorderRunning = true
            try {
                orderedItems = head + list.filterNot { item -> head.any { it === item } }
                Log.i("FlymeFreeform", "REORDER done head=" + head.map { labelOf(it) })
            } finally {
                reorderRunning = false
            }
        } catch (_: Exception) {
        }
    }


    private var orderedItems: List<Any?>? = null
    private var mDataHooked = false

    private fun observeAdapter(adapter: Any) {
        try {
            val register = adapterClass.methods.firstOrNull { it.name == "registerAdapterDataObserver" } ?: return
            val observerClass = register.parameterTypes.firstOrNull() ?: return
            val observer = Proxy.newProxyInstance(observerClass.classLoader, arrayOf(observerClass)) { _, method, _ ->
                if (method.name == "onChanged") reorderTools(adapter)
                null
            }
            register.invoke(adapter, observer)
        } catch (_: Exception) {
        }
    }

    fun leaveSearch(): Boolean {
        if (getSearchState.call(search) != true) return false
        cancelSearch.call(view, true)
        return true
    }

    fun createCard(): FrameLayout {
        val original = getMain.call(handler)?.let { getUser.call(it) }
            ?: throw IllegalStateException("SIDEBAR_STYLE_NOT_READY")
        val surface = construct(cardClass) as FrameLayout
        cardClass.getMethod("setRadius", Float::class.javaPrimitiveType).call(surface, getRadius.call(original))
        cardClass.getMethod("setWeight", Float::class.javaPrimitiveType).call(surface, getWeight.call(original))
        cardClass.getMethod("setPreventCornerOverlap", Boolean::class.javaPrimitiveType).call(surface, false)
        cardClass.getMethod("setUseCompatPadding", Boolean::class.javaPrimitiveType).call(surface, false)
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, getColor.call(original))
        surface.clipToOutline = true
        surface.isForceDarkAllowed = false
        shadow?.setup?.call(null, surface)
        shadow?.update?.call(null, surface, 1f)
        surface.addView(view, FrameLayout.LayoutParams(-1, -1))
        card = surface
        return surface
    }

    /** 新增背景 View 后必须先完成一次布局，再让厂商模糊管理器读取其窗口坐标。 */
    fun attachBlur(): Boolean {
        val surface = card ?: return false
        if (blur != null) return true
        if (!blurPrepared) {
            blurPrepared = true
            if (!sidebarBlur.available) return true
            blurView = View(context).also { surface.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
            return false
        }
        val background = blurView ?: return true
        if (!background.isLaidOut || background.isLayoutRequested ||
            background.width != surface.width || background.height != surface.height
        ) return false
        val session = sidebarBlur.attach(background)
        if (session == null) {
            surface.removeView(background)
            blurView = null
            return true
        }
        background.background?.setBounds(0, 0, background.width, background.height)
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, 0)
        blur = session
        blurView = background
        updateBlur(1f)
        return true
    }

    fun updateBlur(alpha: Float) {
        blur?.update(alpha)
        card?.let { shadow?.update?.call(null, it, alpha) }
    }

    fun dimensions() = AllAppsPanelGeometry.Dimensions(
        dimension("all_app_panel_max_width"), dimension("all_app_panel_max_height"),
        dimension("all_app_panel_height_small_portrait"), dimension("all_app_panel_margin_min"),
        dimension("all_app_panel_margin_max"), gap.call(null) as Int,
    )

    fun mode(): AllAppsPanelGeometry.Mode = when {
        isTabletop.call(resource, false) == true -> AllAppsPanelGeometry.Mode.Tabletop
        isLandscape.call(resource) == true -> AllAppsPanelGeometry.Mode.Landscape
        isPortrait.call(resource) == true -> AllAppsPanelGeometry.Mode.Portrait
        else -> AllAppsPanelGeometry.Mode.LargePortrait
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            if (view.isNotEmpty()) {
                (enterSearch?.call(search) as? Animator)?.cancel()
                (exitSearch?.call(search) as? Animator)?.cancel()
            }
        } finally {
            try {
                if (loading) stop.call(search)
            } finally {
                loading = false
                blurView?.background = null
                blurView = null
                sidebarBlur.close()
                blur = null
                card = null
            }
        }
    }

    private fun dimension(name: String): Int = context.resources.getDimensionPixelSize(resourceId(name, "dimen"))

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME).also { require(it != 0) }

    private fun Method.call(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    private fun construct(type: Class<*>): Any = try {
        type.getConstructor(Context::class.java).newInstance(context)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    @Suppress("DiscouragedApi")
    private fun themedContext(loader: ClassLoader): Context {
        val native = (getMain.call(handler) as? View)?.context
            ?: loader.loadClass("com.coloros.common.App").getField("sContext").get(null) as? Context
            ?: throw IllegalStateException("ALL_APPS_THEME_CONTEXT_UNAVAILABLE")
        for (name in listOf("couiColorPrimaryTextOnPopup", "couiColorPrimaryNeutral", "couiColorSurfaceWithCard")) {
            val id = native.resources.getIdentifier(name, "attr", ColorOsSidebarTarget.PACKAGE_NAME)
            val value = TypedValue()
            check(id != 0 && native.theme.resolveAttribute(id, value, true) &&
                value.type != TypedValue.TYPE_ATTRIBUTE && value.type != TypedValue.TYPE_NULL
            ) { "ALL_APPS_THEME_ATTRIBUTE_UNAVAILABLE" }
        }
        return native
    }

    companion object {
        const val ALL_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppPanelView"
        const val ADAPTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppRecyclerAdapter"
        const val ROUTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.AllAppDataHandlerImpl"
        const val DATA_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData"
        const val SEARCH_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.utils.SearchHelper"
        const val HANDLER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl"
        const val MAIN_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView"
        const val USER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView"
    }
}
