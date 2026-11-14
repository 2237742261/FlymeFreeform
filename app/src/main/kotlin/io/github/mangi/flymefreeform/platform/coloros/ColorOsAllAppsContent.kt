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
        // 在数据加载前就把工具列表 hook 上，否则第一次打开时列表已经渲染完了。
        hookTools()
        ownSearches.add(search)
        hookCommonGroup()
        scheduleHeaderRename()
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

    /** 临时探针：dump 面板数据形态、分组标题来源、UserPanelView 的最近应用接口。定位完即删。 */
    private fun probeDump(stage: String) {
        runCatching {
            val sb = StringBuilder()
            sb.append("adapter=").append(findRecyclerAdapter(view)?.javaClass?.name).append("; ")
            val adapter = findRecyclerAdapter(view)
            val data = adapter?.let { runCatching { getMData.call(it) }.getOrNull() }
            if (data is List<*>) {
                sb.append("size=").append(data.size).append("; ")
                val kinds = LinkedHashMap<String, Int>()
                data.filterNotNull().forEach { kinds[it.javaClass.simpleName] = (kinds[it.javaClass.simpleName] ?: 0) + 1 }
                sb.append("kinds=").append(kinds).append("; ")
                data.filterNotNull()
                    .filter { it.javaClass.simpleName.contains("Title") }
                    .forEach { item ->
                        val text = runCatching { item.javaClass.getMethod("getText").invoke(item) }.getOrNull()
                        sb.append("TITLE[").append(item.javaClass.simpleName).append("=").append(text).append("]; ")
                    }
            } else {
                sb.append("mData=null; ")
            }
            val texts = ArrayList<String>()
            collectTexts(view, texts)
            sb.append("texts=").append(texts.take(20)).append("; ")
            val userPanel = runCatching { getMain.call(handler)?.let { getUser.call(it) } }.getOrNull()
            sb.append("userPanel=").append(userPanel?.javaClass?.name).append("; ")
            userPanel?.javaClass?.methods
                ?.filter { it.name.contains("ecent") && it.parameterCount <= 1 }
                ?.forEach { method ->
                    sb.append("m.").append(method.name).append("/").append(method.parameterCount)
                    if (method.parameterCount == 0) {
                        val result = runCatching { method.invoke(userPanel) }.getOrNull()
                        sb.append("->").append(result?.javaClass?.simpleName)
                        if (result is List<*>) {
                            sb.append("(").append(result.size).append(")")
                            result.take(3).forEach { entry ->
                                val label = entry?.let { e ->
                                    runCatching { e.javaClass.getMethod("getLabel").invoke(e) }.getOrNull()
                                }
                                sb.append(" ").append(entry?.javaClass?.simpleName).append("/").append(label)
                            }
                        }
                    }
                    sb.append("; ")
                }
            module.log(Log.INFO, TAG, "FF_PROBE_$stage $sb")
            val items = (data as? List<*>)?.filterNotNull().orEmpty()
            val titleLike = items.firstOrNull { it.javaClass.simpleName.contains("Title") }
            if (titleLike != null) {
                val dump = StringBuilder("ctors=")
                titleLike.javaClass.declaredConstructors.forEach { ctor ->
                    dump.append(ctor.parameterTypes.map { it.simpleName }).append("|")
                }
                dump.append(" fields=")
                titleLike.javaClass.declaredFields.take(10).forEach { field ->
                    dump.append(field.name).append(":").append(field.type.simpleName).append(",")
                }
                dump.append(" getters=")
                titleLike.javaClass.declaredMethods.filter { it.parameterCount == 0 }.take(14).forEach { method ->
                    val value = runCatching { method.invoke(titleLike) }.getOrNull()
                    dump.append(method.name).append("=")
                        .append(if (value is String) value else value?.javaClass?.simpleName).append(",")
                }
                module.log(Log.INFO, TAG, "FF_PROBE2_$stage $dump")
            }
            val appLike = items.firstOrNull { it.javaClass.name == DATA_CLASS }
            if (appLike != null) {
                val dump = StringBuilder("getters=")
                appLike.javaClass.declaredMethods.filter { it.parameterCount == 0 }.take(18).forEach { method ->
                    val value = runCatching { method.invoke(appLike) }.getOrNull()
                    dump.append(method.name).append("=")
                        .append(if (value is String) value else value?.javaClass?.simpleName).append(",")
                }
                module.log(Log.INFO, TAG, "FF_PROBE3_$stage $dump")
            }
            val layoutManager = findRecyclerLayoutManager(view)
            val span = runCatching {
                layoutManager?.javaClass?.getMethod("getSpanCount")?.invoke(layoutManager)
            }.getOrNull()
            module.log(Log.INFO, TAG, "FF_PROBE4_$stage span=$span lm=${layoutManager?.javaClass?.name}")
            val recent = runCatching {
                val panel = getMain.call(handler)?.let { getUser.call(it) }
                panel?.javaClass?.getMethod("getRecentData")?.invoke(panel)
            }.getOrNull()
            if (recent is List<*>) {
                val dump = StringBuilder("size=${recent.size} ")
                recent.filterNotNull().take(6).forEach { entry ->
                    dump.append("[").append(entry.javaClass.simpleName)
                    entry.javaClass.declaredMethods.filter { it.parameterCount == 0 }.take(8).forEach { method ->
                        val value = runCatching { method.invoke(entry) }.getOrNull()
                        dump.append(" ").append(method.name).append("=")
                            .append(if (value is String) value else value?.javaClass?.simpleName)
                    }
                    dump.append("] ")
                }
                module.log(Log.INFO, TAG, "FF_PROBE5_$stage $dump")
            }
            runCatching { java.io.File(context.cacheDir, "ff_probe.txt").appendText("[$stage] $sb\n") }
        }.onFailure { module.log(Log.WARN, TAG, "FF_PROBE_${stage}_FAILED", it) }
    }

    private fun findRecyclerAdapter(root: View): Any? {
        if (root.javaClass.name.contains("RecyclerView")) {
            return runCatching { root.javaClass.getMethod("getAdapter").invoke(root) }.getOrNull()
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val found = findRecyclerAdapter(root.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findRecyclerLayoutManager(root: View): Any? {
        if (root.javaClass.name.contains("RecyclerView")) {
            return runCatching { root.javaClass.getMethod("getLayoutManager").invoke(root) }.getOrNull()
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val found = findRecyclerLayoutManager(root.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun collectTexts(root: View, out: MutableList<String>) {
        if (root is android.widget.TextView) {
            root.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) collectTexts(root.getChildAt(index), out)
        }
    }

    private fun optionalAnimator(name: String): Method? = try {
        searchClass.getMethod(name).also { require(Animator::class.java.isAssignableFrom(it.returnType)) }
    } catch (_: NoSuchMethodException) {
        null
    }

    fun bindAdapter(adapter: Any, onClick: (Boolean, () -> Unit) -> Unit) {
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

    /** 在「工具」和「应用」之间插入「常用」分组（12 个最近使用过的应用）。 */
    private fun hookCommonGroup() {
        if (commonHooked) return
        commonHooked = true
        try {
            module.hook(getData).intercept { chain ->
                val original = chain.proceed()
                // SearchHelper 是进程级共享的：官方侧边栏的「全部」页也会走这个 hook。
                // 只认我们自己窗口那个实例，否则官方面板里会多出一组没人改名的「工具」栏。
                val owner = chain.thisObject
                @Suppress("UNCHECKED_CAST")
                val list = original as? List<Any?>
                if (list == null) {
                    original
                } else if (owner != null && ownSearches.contains(owner)) {
                    injectCommonGroup(list)
                    original
                } else {
                    original
                }
            }
            Log.i(TAG, "COMMON hook_ok")
        } catch (throwable: Throwable) {
            Log.i(TAG, "COMMON hook_fail " + throwable)
        }
    }

    private fun injectCommonGroup(list: List<Any?>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val data = list as? MutableList<Any?> ?: return
            val titles = data.indices.filter { isTitle(data[it]) }
            if (titles.size < 2) {
                return
            }
            if (data.any { isTitle(it) && titleText(it) == COMMON_TITLE }) return
            val apps = data.filterNotNull()
                .filter { it.javaClass.name == DATA_CLASS }
                .filter { (getKey.call(it) as? String)?.contains('.') == true }
            if (apps.size < COMMON_MIN_APPS) {
                return
            }
            val byPackage = LinkedHashMap<String, Any>()
            apps.forEach { entry ->
                entryIds(entry).forEach { id -> byPackage.putIfAbsent(id, entry) }
            }
            val recent = recentPackages()
            Log.i(
                TAG,
                "COMMON apps=" + apps.size + " ids=" + byPackage.size +
                    " keys=" + apps.take(3).map { getKey.call(it) } +
                    " recent=" + recent.take(3),
            )
            val picked = ArrayList<Any>()
            for (name in recent) {
                if (picked.size >= COMMON_LIMIT) break
                byPackage[name]?.let { picked.add(it) }
            }
            if (picked.isEmpty()) {
                Log.i(TAG, "COMMON empty ids=" + byPackage.size)
                return
            }
            val title = makeTitle(data[titles[0]])
            if (title == null) {
                Log.i(TAG, "COMMON title_null")
                return
            }
            val at = titles.last()
            val block = ArrayList<Any?>(picked.size + 1)
            block.add(title)
            block.addAll(picked)
            data.addAll(at, block)
            ourTitlePosition = at
            Log.i(
                TAG,
                "COMMON injected=" + picked.size + " at=" + at + " titles=" + titles +
                    " first=" + getKey.call(picked[0]),
            )
            scheduleHeaderRename()
        } catch (throwable: Throwable) {
            Log.i(TAG, "COMMON inject_fail " + throwable)
        }
    }

    /** 标题项的实际文案由布局决定，数据里改不动，只能在渲染出来后按位置改 TextView。 */
    private fun scheduleHeaderRename() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        listOf(200L, 500L, 900L, 1400L, 2000L, 2800L, 3800L, 5000L).forEach { delay ->
            handler.postDelayed({ renameCommonHeader() }, delay)
        }
    }

    /**
     * 按适配器位置改「常用」标题文案。
     *
     * 不能用「取最靠下的工具标题」这种启发式：面板滚动后我们那一格可能没绑定，
     * 屏上只剩原来那个工具标题，就会被误改成「常用」（用户报过「工具标题偶尔变常用」）。
     */
    private fun renameCommonHeader() {
        try {
            if (closed) return
            val position = ourTitlePosition
            if (position < 0) return
            val recyclers = ArrayList<ViewGroup>()
            collectRecyclers(view, recyclers)
            if (recyclers.isEmpty()) return
            for (recycler in recyclers) {
                // getAdapter / getChildAdapterPosition 声明在 RecyclerView 上，必须按实例类取，
                // 用 ViewGroup::class.java 取不到（上一版就栽在这，异常被 catch 吞掉，改名一次没跑）。
                val type = recycler.javaClass
                val adapter = try {
                    type.getMethod("getAdapter").invoke(recycler)
                } catch (_: Throwable) {
                    null
                }
                if (adapter == null || adapter.javaClass.name != ADAPTER_CLASS) continue
                val adapterPosition = type.getMethod("getChildAdapterPosition", View::class.java)
                var target: View? = null
                for (index in 0 until recycler.childCount) {
                    val child = recycler.getChildAt(index)
                    if ((adapterPosition.invoke(recycler, child) as? Int) == position) {
                        target = child
                        break
                    }
                }
                if (target == null) continue
                val textView = firstText(target) ?: continue
                if (textView.text?.toString() == COMMON_TITLE) return
                textView.text = COMMON_TITLE
                Log.i(TAG, "COMMON header_renamed pos=" + position)
                return
            }
        } catch (throwable: Throwable) {
            Log.i(TAG, "COMMON rename_fail " + throwable)
        }
    }

    private fun collectRecyclers(root: View, out: MutableList<ViewGroup>) {
        if (root is ViewGroup) {
            if (root.javaClass.name.contains("RecyclerView")) out.add(root)
            for (index in 0 until root.childCount) collectRecyclers(root.getChildAt(index), out)
        }
    }

    private fun firstText(root: View): android.widget.TextView? {
        if (root is android.widget.TextView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                firstText(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** 条目的可用身份：key、entryBean 的包名/别名都收进来，避免 key 不是包名时匹配不上。 */
    private fun entryIds(entry: Any): List<String> {
        val ids = ArrayList<String>(4)
        (getKey.call(entry) as? String)?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        val bean = try {
            getEntry.call(entry)
        } catch (_: RuntimeException) {
            null
        }
        if (bean != null) {
            bean.javaClass.methods
                .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .filter { it.name == "getPkg" || it.name == "getPackageName" || it.name == "getAlias" || it.name == "getMAlias" }
                .forEach { method ->
                    (runCatching { method.invoke(bean) }.getOrNull() as? String)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ids.add(it) }
                }
            try {
                bean.javaClass.declaredFields
                    .filter { it.name == "pkg" || it.name == "mPkg" }
                    .forEach { field ->
                        field.isAccessible = true
                        (field.get(bean) as? String)?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                    }
            } catch (_: RuntimeException) {
            }
        }
        return ids
    }

    private fun isTitle(item: Any?): Boolean =
        item?.javaClass?.simpleName?.contains("Title") == true

    private fun titleText(item: Any?): String? = try {
        item?.javaClass?.getMethod("getText")?.invoke(item)?.toString()
    } catch (_: RuntimeException) {
        null
    }

    /** 复制一个已有的标题项，只把文案换成 [COMMON_TITLE]。 */
    private fun makeTitle(template: Any?): Any? {
        if (template == null) return null
        return try {
            val type = template.javaClass
            val copy = type.getDeclaredConstructor().newInstance()
            type.declaredFields.forEach { field ->
                try {
                    field.isAccessible = true
                    field.set(copy, field.get(template))
                } catch (_: RuntimeException) {
                }
            }
            try {
                type.getDeclaredField("text").apply { isAccessible = true }.set(copy, COMMON_TITLE)
            } catch (_: RuntimeException) {
            }
            copy
        } catch (throwable: Throwable) {
            Log.i(TAG, "COMMON title_fail " + throwable)
            null
        }
    }

    /**
     * 最近使用过的应用包名。
     *
     * 首帧同步查一次（要等数据，躲不掉），之后走 15 秒缓存 + 后台刷新：
     * 主线程不再为统计查询付跨进程反序列化的代价。
     */
    private fun recentPackages(): List<String> {
        val now = System.currentTimeMillis()
        val cached = recentCache
        if (cached.isNotEmpty()) {
            if (now - recentCachedAt >= RECENT_CACHE_MS) refreshRecentAsync()
            return cached
        }
        val fresh = queryRecentPackages()
        if (fresh.isNotEmpty()) {
            recentCache = fresh
            recentCachedAt = System.currentTimeMillis()
        }
        return fresh
    }

    private fun refreshRecentAsync() {
        if (!recentRefreshing.compareAndSet(false, true)) return
        try {
            recentExecutor.execute {
                try {
                    val fresh = queryRecentPackages()
                    if (fresh.isNotEmpty()) {
                        recentCache = fresh
                        recentCachedAt = System.currentTimeMillis()
                        Log.i(TAG, "COMMON refreshed=" + fresh.size)
                    }
                } catch (throwable: Throwable) {
                    Log.i(TAG, "COMMON refresh_fail " + throwable)
                } finally {
                    recentRefreshing.set(false)
                }
            }
        } catch (throwable: Throwable) {
            recentRefreshing.set(false)
            Log.i(TAG, "COMMON refresh_submit_fail " + throwable)
        }
    }

    /**
     * 近 3 天的打开次数 / 前台时长。用 INTERVAL_DAILY 的日桶聚合，跨进程只回几百条，
     * 比数事件流轻得多；窗口是滑动的（今天往前 3 天），旧数据自己过期，不需要本地累加。
     */
    private fun queryRecentPackages(): List<String> {
        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        if (manager == null) {
            Log.i(TAG, "COMMON usm=null")
            return emptyList()
        }
        val end = System.currentTimeMillis()
        val stats =
            try {
                manager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    end - RECENT_WINDOW_MS,
                    end,
                )
            } catch (throwable: Throwable) {
                Log.i(TAG, "COMMON raw_fail " + throwable)
                null
            }.orEmpty()
        val launches = HashMap<String, Int>()
        val durations = HashMap<String, Long>()
        val lastUsed = HashMap<String, Long>()
        for (stat in stats) {
            if (stat == null) continue
            val name = stat.packageName ?: continue
            launches[name] = (launches[name] ?: 0) + launchCount(stat)
            durations[name] = (durations[name] ?: 0L) + stat.totalTimeInForeground
            val used = stat.lastTimeUsed
            if (used > (lastUsed[name] ?: 0L)) lastUsed[name] = used
        }
        val ranked =
            if (launchCountAvailable()) {
                launches.keys
                    .filter { name ->
                        (launches[name] ?: 0) >= RECENT_MIN_LAUNCHES ||
                            (durations[name] ?: 0L) >= RECENT_MIN_FOREGROUND_MS
                    }.sortedWith(
                        compareByDescending<String> { name -> launches[name] ?: 0 }
                            .thenByDescending { name -> lastUsed[name] ?: 0L },
                    )
            } else {
                // 拿不到打开次数时退化为「时长门槛 + 最后使用时间」，不至于整栏消失。
                durations.keys
                    .filter { name -> (durations[name] ?: 0L) >= RECENT_MIN_FOREGROUND_MS }
                    .sortedByDescending { name -> lastUsed[name] ?: 0L }
            }
        Log.i(
            TAG,
            "COMMON usage=" + ranked.size +
                " top=" + ranked.take(3).map { name -> name + ":" + (launches[name] ?: 0) },
        )
        if (ranked.isEmpty()) {
            val fallback = systemRecentPackages()
            Log.i(TAG, "COMMON fallback_sys=" + fallback.size)
            return fallback
        }
        return ranked
    }

    /** UsageStats.getAppLaunchCount() 是隐藏 API：先试方法，再试 mAppLaunchCount 字段，都没有就返回 0。 */
    private fun launchCount(stat: android.app.usage.UsageStats): Int {
        if (!launchCountProbed) {
            launchCountProbed = true
            launchCountGetter =
                runCatching { stat.javaClass.getMethod("getAppLaunchCount") }.getOrNull()
            if (launchCountGetter == null) {
                launchCountField =
                    runCatching { stat.javaClass.getField("mAppLaunchCount") }.getOrNull()
            }
            Log.i(
                TAG,
                "COMMON launch_probe getter=" + (launchCountGetter != null) +
                    " field=" + (launchCountField != null),
            )
        }
        launchCountGetter?.let { getter ->
            val value = runCatching { getter.invoke(stat) }.getOrNull()
            (value as? Number)?.let { return it.toInt() }
        }
        launchCountField?.let { field ->
            val value = runCatching { field.get(stat) }.getOrNull()
            (value as? Number)?.let { return it.toInt() }
        }
        return 0
    }

    private fun launchCountAvailable(): Boolean =
        launchCountGetter != null || launchCountField != null

    /** 兜底：直接问面板自己的「最近使用」列表。 */
    private fun systemRecentPackages(): List<String> = try {
        val panel = getMain.call(handler)?.let { getUser.call(it) }
        val recent = panel?.javaClass?.getMethod("getRecentData")?.invoke(panel) as? List<*>
        recent.orEmpty().filterNotNull()
            .mapNotNull { getKey.call(it) as? String }
            .distinct()
    } catch (throwable: Throwable) {
        Log.i(TAG, "COMMON sys_recent_fail " + throwable)
        emptyList()
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
        /** 探针与诊断日志用的 tag。 */
        private const val TAG = "FlymeFreeform"

        /** 工具列表 hook 只装一次：窗口是每次打开新建的，实例字段挡不住重复 hook。 */
        @Volatile
        private var toolsHooked = false

        /** 「常用」分组 hook 只装一次。 */
        @Volatile
        private var commonHooked = false

        const val COMMON_TITLE = "最近"
        const val COMMON_LIMIT = 12
        const val COMMON_MIN_APPS = 20
        const val RECENT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        /** 入榜门槛：近 3 天打开 ≥2 次，或累计前台 ≥60 秒。 */
        const val RECENT_MIN_LAUNCHES = 2
        const val RECENT_MIN_FOREGROUND_MS = 60_000L
        const val RECENT_CACHE_MS = 60_000L

        /** 最近名单缓存：主线程只读它，过期再交给后台刷。 */
        /** 我们自己的 SearchHelper 实例（弱引用）。官方「全部」页的实例不在里面。 */
        private val ownSearches: MutableSet<Any> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>()),
            )

        /** 我们插入的「常用」标题在适配器里的位置；-1 表示还没插过。 */
        @Volatile
        private var ourTitlePosition = -1

        @Volatile
        private var recentCache: List<String> = emptyList()

        @Volatile
        private var recentCachedAt = 0L

        private var launchCountGetter: java.lang.reflect.Method? = null
        private var launchCountField: java.lang.reflect.Field? = null
        private var launchCountProbed = false

        private val recentRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)
        private val recentExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "FlymeFreeform-Common").apply { isDaemon = true }
            }

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
