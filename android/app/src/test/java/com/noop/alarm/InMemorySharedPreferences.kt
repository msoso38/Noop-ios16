package com.noop.alarm

import android.content.SharedPreferences

/**
 * Pure-JVM SharedPreferences fake for unit tests. No Robolectric (this project doesn't use it).
 *
 * Implements only the surface the unified alarm store + migration touch: get/set String, Int, Long,
 * Boolean, Float, StringSet, plus `remove`, `clear`, `contains`, `all`. Listener registration is a
 * no-op. Edits are visible after `apply()` or `commit()` (we treat them identically).
 */
class InMemorySharedPreferences : SharedPreferences {

    private val map: MutableMap<String, Any?> = HashMap()

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>)?.toHashSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending: MutableMap<String, Any?> = HashMap()
        private val removed: MutableSet<String> = HashSet()
        private var clear = false

        override fun putString(k: String, v: String?): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putStringSet(k: String, v: Set<String>?): SharedPreferences.Editor { pending[k] = v?.toHashSet(); return this }
        override fun putInt(k: String, v: Int): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putLong(k: String, v: Long): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putFloat(k: String, v: Float): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { pending[k] = v; return this }
        override fun remove(k: String): SharedPreferences.Editor { removed += k; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            for (k in removed) map.remove(k)
            for ((k, v) in pending) map[k] = v
        }
    }
}
