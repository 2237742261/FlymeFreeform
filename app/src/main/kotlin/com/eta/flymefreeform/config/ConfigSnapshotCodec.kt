package com.eta.flymefreeform.config

import android.content.SharedPreferences

/** 字段间分隔；用控制字符，正常配置值不会包含。 */
private const val LINE = "\u0001"
private const val PAIR = "\u0002"

/**
 * @author bomo 配置快照的跨进程编解码。
 *
 * **背景**：`RemotePreferences` 在 Hook 进程里是**只读 + 进程内缓存**，且
 * `OnSharedPreferenceChangeListener` 不跨进程回调（见 `ProcessConfiguration.refreshNow()` 注释）。
 * 于是模块 App 改完配置后，system_server 等 Hook 进程仍拿着**开机时那份快照** ——
 * 典型症状就是「设置里加了扇形应用，扇形里看不到」。
 *
 * **解法**：App 写盘成功后把**整份配置**经广播推给 system_server；接收方用
 * [MemoryPreferences]（内存版 SharedPreferences）承载这段文本，再交给
 * [ModuleSettingsSnapshot.readFrom] —— 从而**完全复用** writeTo/readFrom 的既有语义
 * （默认值、coerce、pins 编解码），不重复任何一条字段规则。
 */
internal object ConfigSnapshotCodec {
    /** 编码为可放进 Intent extra 的文本。 */
    fun encode(snapshot: ModuleSettingsSnapshot): String {
        val memory = MemoryPreferences()
        snapshot.writeTo(memory.edit()).apply()
        return memory.dump()
    }

    /** 解码；文本缺失或解析失败时返回 null（调用方应保持既有配置，不误伤）。 */
    fun decode(value: String?): ModuleSettingsSnapshot? {
        if (value.isNullOrEmpty()) return null
        return runCatching { ModuleSettingsSnapshot.readFrom(MemoryPreferences.parse(value)) }
            .getOrNull()
    }
}

/**
 * @author bomo 最小可用的内存 SharedPreferences，仅用于承载一次性的跨进程配置传递。
 * 不做磁盘持久化，也不支持变更监听（Hook 侧本就不需要变更通知）。
 */
internal class MemoryPreferences(
    private val values: MutableMap<String, Any?> = LinkedHashMap(),
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** 序列化为「类型标记 + 键 + 值」文本；仅覆盖 [ModuleSettingsSnapshot.writeTo] 会写到的类型。 */
    fun dump(): String =
        values.entries
            .mapNotNull { (key, value) ->
                when (value) {
                    is Boolean -> "B$PAIR$key$PAIR$value"
                    is Int -> "I$PAIR$key$PAIR$value"
                    is Long -> "L$PAIR$key$PAIR$value"
                    is Float -> "F$PAIR$key$PAIR$value"
                    is String -> "S$PAIR$key$PAIR$value"
                    else -> null
                }
            }
            .joinToString(LINE)

    private inner class Editor : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearRequested = false

        // 注意：不能写成 Kotlin 的 `apply { }` —— 会撞名 SharedPreferences.Editor.apply()。
        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            also { staged[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = also { staged[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            also { staged[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            also { staged[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            also { staged[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            also { staged[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = also { removals += key }

        override fun clear(): SharedPreferences.Editor = also { clearRequested = true }

        override fun commit(): Boolean {
            merge()
            return true
        }

        override fun apply() = merge()

        private fun merge() {
            if (clearRequested) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(staged)
            staged.clear()
            removals.clear()
            clearRequested = false
        }
    }

    companion object {
        fun parse(text: String): MemoryPreferences {
            val map = LinkedHashMap<String, Any?>()
            text.split(LINE).forEach { line ->
                if (line.isEmpty()) return@forEach
                val parts = line.split(PAIR, limit = 3)
                if (parts.size != 3) return@forEach
                val value: Any? =
                    when (parts[0]) {
                        "B" -> parts[2].toBooleanStrictOrNull()
                        "I" -> parts[2].toIntOrNull()
                        "L" -> parts[2].toLongOrNull()
                        "F" -> parts[2].toFloatOrNull()
                        "S" -> parts[2]
                        else -> null
                    }
                if (value != null) map[parts[1]] = value
            }
            return MemoryPreferences(map)
        }
    }
}
