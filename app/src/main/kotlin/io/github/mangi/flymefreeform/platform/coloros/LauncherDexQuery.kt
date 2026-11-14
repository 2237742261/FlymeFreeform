package io.github.mangi.flymefreeform.platform.coloros

import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType

/** 桌面输入适配的字节码证据；查询结果不持有 native bridge 或宿主对象。 */
internal object LauncherDexQuery {
    /**
     * 桌面手势服务。ColorOS 新版桌面把 `OplusBaseTouchInteractionService` 改名并混淆方法，
     * 硬编码旧类名时会静默挂不上 hook（症状：触发区内「返回/多任务」等系统手势不再被压制，
     * 与角手势、小窗动作同时触发）。照 Sillybomo/FlymeFreeform v0.2.7 的做法：
     * 旧类优先、新类兜底，逐个试。
     */
    val SERVICE_CLASSES =
        listOf(
            "com.android.quickstep.OplusBaseTouchInteractionService",
            "com.android.quickstep.TouchInteractionService",
        )
    const val INPUT_MONITOR_CLASS = "android.view.InputMonitor"
    const val RULE_VERSION = "launcher-input-v2-dexkit-2.3.0"

    data class Symbols(
        val inputMethod: String,
        val monitorField: String,
        val wrapperClass: String,
        val pilferMethod: String,
    )

    fun find(bridge: DexKitBridge): Symbols {
        val service = SERVICE_CLASSES.firstNotNullOfOrNull { name -> bridge.getClassData(name) }
            ?: throw NoSuchMethodException("LAUNCHER_DEX_SERVICE_MISSING")
        val input = service.methods.findMethod {
            matcher {
                paramTypes("android.view.InputEvent")
                returnType = "void"
                usingEqStrings("Unknown event ", "onInputEvent(), mDeviceState user is not unlocked")
                addInvoke("Landroid/view/MotionEvent;->getActionMasked()I")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }
            .singleOrNull() ?: throw NoSuchMethodException("LAUNCHER_DEX_INPUT_NOT_UNIQUE")

        // 从宿主字段类型向下定位真实抢占调用，排除同签名的空 pilferPointers 方法。
        val candidates = service.fields.filter { !Modifier.isStatic(it.modifiers) }.mapNotNull { owner ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("LAUNCHER_DEX_CANCELLED")
            val wrapper = owner.type
            val delegates = wrapper.fields.filter {
                it.typeName == INPUT_MONITOR_CLASS && !Modifier.isStatic(it.modifiers)
            }
            if (delegates.size != 1) return@mapNotNull null
            val delegate = delegates.single()
            val pilfer = wrapper.methods.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    addInvoke("Landroid/view/InputMonitor;->pilferPointers()V")
                }
            }.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.usingFields.any {
                    it.usingType == FieldUsingType.Read && it.field.descriptor == delegate.descriptor
                }
            }.singleOrNull() ?: return@mapNotNull null
            Symbols(input.name, owner.name, owner.typeName, pilfer.name)
        }
        return candidates.singleOrNull()
            ?: throw NoSuchFieldException("LAUNCHER_DEX_MONITOR_NOT_UNIQUE")
    }
}
