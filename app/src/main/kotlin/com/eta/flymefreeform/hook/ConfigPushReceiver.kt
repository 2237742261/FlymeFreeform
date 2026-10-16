package com.eta.flymefreeform.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.eta.flymefreeform.config.ConfigSnapshotCodec
import com.eta.flymefreeform.config.SharedStateProtocol

/**
 * @author bomo App → Hook 进程的配置通道（广播）。
 *
 * 为什么单靠 `RemotePreferences` 的变更通知不够：
 * 1. 模块在 LSPosed 管理器里被停用后，远端偏好不可写、通知也不再可靠，
 *    而**已注入的代码不会卸载**（system_server 尤其）—— 角落轮盘会照旧呼出；
 * 2. 系统 UI / 桌面 / 侧边栏这些进程要跟着设置实时变（热区窗口尺寸、是否抢占触摸）。
 *
 * 因此每个被注入的进程都注册一个**导出**接收器：广播是唯一不依赖 LSPosed 的通道。
 *
 * ⚠️ 2026-10-16 修复（之前整条通道是死的）：
 * 旧实现用 `sentFromUid` / `sentFromPackage` 校验发送方，而这两个值对**普通应用**发来的
 * 广播恒为 `-1` / `null`（它们服务的是系统组件，见 `getSentFromUid` 的语义），于是每条推送
 * 都被判成「来源不明」—— 日志里成片的 `CONFIG_PUSH_REJECTED_SENDER`，
 * 后果是「改完设置必须重启手机」+「硬停不生效」。
 * 现在改为**注册时要求签名级权限** [SharedStateProtocol.PERMISSION_CONFIG_PUSH]：
 * 只有与模块 App 同签名的应用持有它，框架在派发前就挡住了其它来源 ——
 * 收不到即不可信，收到的载荷再由 [ConfigSnapshotCodec.decode] 严格解析。
 * 另注册一个**不要求权限的探针**接收器，只记发送方信息、不应用任何配置：
 * 若探针收到而信任通道没收到，就能一眼看出是权限校验挡住的（而不是广播没发出来）。
 */
internal object ConfigPushReceiver {
    private const val TAG = "FlymeFreeform"

    fun register(
        context: Context,
        configuration: ProcessConfiguration,
        log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
    ) {
        val filter =
            IntentFilter().apply {
                addAction(SharedStateProtocol.ACTION_CONFIG_CHANGED)
                addAction(SharedStateProtocol.ACTION_MODULE_STOP)
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    log(
                        Log.DEBUG,
                        "CONFIG_PUSH_RECEIVED action=$action uid=$sentFromUid pkg=$sentFromPackage",
                        null,
                    )
                    when (action) {
                        SharedStateProtocol.ACTION_MODULE_STOP -> {
                            if (configuration.applyStopOverride()) {
                                log(Log.INFO, "MODULE_STOP_APPLIED", null)
                            }
                        }

                        SharedStateProtocol.ACTION_CONFIG_CHANGED -> {
                            val next =
                                ConfigSnapshotCodec.decode(
                                    intent.getStringExtra(SharedStateProtocol.EXTRA_CONFIG),
                                )
                            if (next == null) {
                                log(Log.WARN, "CONFIG_PUSH_DECODE_FAILED", null)
                                return
                            }
                            if (configuration.applyExternal(next)) {
                                log(Log.INFO, "CONFIG_PUSH_APPLIED", null)
                            }
                        }
                    }
                }
            }
        try {
            context.registerReceiver(
                receiver,
                filter,
                SharedStateProtocol.PERMISSION_CONFIG_PUSH,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            log(Log.WARN, "CONFIG_PUSH_RECEIVER_FAILED", exception)
        }
        registerProbe(context, filter, log)
    }

    /** 诊断用镜像接收器：不要求权限、**不应用任何配置**，只把发送方信息写进日志。 */
    private fun registerProbe(
        context: Context,
        filter: IntentFilter,
        log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
    ) {
        val probe =
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    log(
                        Log.DEBUG,
                        "CONFIG_PUSH_PROBE action=${intent?.action} uid=$sentFromUid pkg=$sentFromPackage",
                        null,
                    )
                }
            }
        try {
            context.registerReceiver(probe, filter, Context.RECEIVER_EXPORTED)
        } catch (exception: RuntimeException) {
            log(Log.WARN, "CONFIG_PUSH_PROBE_FAILED", exception)
        }
    }
}
