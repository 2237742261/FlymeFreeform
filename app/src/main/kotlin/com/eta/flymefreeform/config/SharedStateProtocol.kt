package com.eta.flymefreeform.config

/**
 * Hook 进程 → 模块 App 的单向状态通道。
 *
 * 背景（实测）：libxposed 的 `RemotePreferences` 在 Hook 进程里是**只读**的
 * （`edit()` 抛 `UnsupportedOperationException: Read only implementation`），
 * 只有模块 App 自身能写。因此凡是 Hook 侧产生的共享状态（最近小窗、侧边栏工具目录），
 * 一律通过显式广播交给 App 落盘，Hook 侧再按只读方式读取。
 *
 * @author bomo
 */
internal object SharedStateProtocol {
    const val ACTION = "com.eta.flymefreeform.action.SHARED_STATE"
    const val EXTRA_KIND = "kind"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_CATALOG = "catalog"

    /** 最近小窗应用：每次成功打开一个小窗发一条。 */
    const val KIND_RECENT_FREEFORM = 1

    /** 侧边栏工具目录：侧边栏进程启动与面板打开时发布。 */
    const val KIND_TOOL_CATALOG = 2

    /**
     * App 主动向 system_server 索要工具目录。
     * 被动等中继会被 ColorOS 的启动延迟策略拖住（实测 BootPressureHolder delay start），
     * 打开设置时主动要一次最可靠。
     */
    const val ACTION_REQUEST_TOOLS = "com.eta.flymefreeform.action.REQUEST_TOOLS"

    /**
     * @author bomo App 改完设置后，把**整份配置**推给 Hook 进程（system_server）。
     *
     * 为什么必须推：`RemotePreferences` 在 Hook 进程只读且带**进程内缓存**，变更也不跨进程回调
     * （见 `ProcessConfiguration.refreshNow()` 注释）—— 不推这一把，用户改完设置就得重启手机
     * 才生效（曾因此被报「添加扇形应用无效」）。
     */
    const val ACTION_CONFIG_CHANGED = "com.eta.flymefreeform.action.CONFIG_CHANGED"

    /** 配置载荷：[ConfigSnapshotCodec.encode] 产出的文本。 */
    const val EXTRA_CONFIG = "config"

    /**
     * @author bomo App → Hook 进程的**硬停**信号（用户 2026-10-16 定）。
     *
     * 存在的意义：模块在 LSPosed 里被停用后，远端偏好与「模块是否启用」都不再可信，
     * 但**已注入的代码不会卸载**（system_server 尤其），角落轮盘照旧能呼出。
     * 这条广播与 LSPosed 无关，只要进程还活着就能收到 → 收到即把该进程整体停用，
     * 直到 App 再推一次配置（[ACTION_CONFIG_CHANGED]）为止。
     */
    const val ACTION_MODULE_STOP = "com.eta.flymefreeform.action.MODULE_STOP"

    /**
     * @author bomo SystemUI → system_server 的**握手探针**（2026-10-17 新增）。
     *
     * 存在的意义：角落热区在 SystemUI（[SystemUiCornerInputMonitor]），而轮盘本体在
     * system_server（`ColorOsFreeformCoordinator`，挂在 `FlexibleTaskController.systemReady`）。
     * 模块刚在 LSPosed 里启用、又没重启手机时，SystemUI 重启一次就能拿到热区，
     * 但 system_server 里的注入**只能在开机时加载** —— 于是热区会抢走角落触摸（拦住返回手势）
     * 却什么也呼不出来，用户看到的正是「扇区被屏蔽、轮盘不出」。
     *
     * 现在 SystemUI 启动后先发这条探针，只有收到 [ACTION_COORDINATOR_READY] 才允许抢占触摸：
     * 没收到 = system_server 侧没有本模块，热区不抢手势，返回手势照常。
     */
    const val ACTION_COORDINATOR_QUERY = "com.eta.flymefreeform.action.COORDINATOR_QUERY"

    /** system_server 侧轮盘协调器就绪的回执；只有它到了 SystemUI 才敢抢触摸。 */
    const val ACTION_COORDINATOR_READY = "com.eta.flymefreeform.action.COORDINATOR_READY"

    /** 侧边栏包名；用于校验目录广播的来源。 */
    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"

    /** 模块自身包名：广播必须显式指定，避免 Hook 进程（system_server）的 context 包名不是模块包。 */
    const val MODULE_PACKAGE = "com.eta.flymefreeform"

    /**
     * @author bomo App → Hook 进程广播的**签名级**权限（2026-10-16 新增）。
     *
     * 为什么必须有：`BroadcastReceiver.getSentFromUid()` / `getSentFromPackage()` 对普通应用
     * 发来的广播恒为 `-1` / `null`（它们服务的是系统组件），用它们校验发送方等于永远拒绝 ——
     * 这就是日志里成片 `CONFIG_PUSH_REJECTED_SENDER`、以及「改完设置必须重启手机」的根因。
     * 改成注册时要求本权限：只有与模块 App 同签名的应用持有它，框架在派发前就挡住了其它来源。
     * 声明方是模块自身（`AndroidManifest.xml`，`protectionLevel=signature`）。
     */
    const val PERMISSION_CONFIG_PUSH = "com.eta.flymefreeform.permission.CONFIG_PUSH"
}
