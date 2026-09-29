package com.max.shared

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SharedPreferencesStoreTest {
    /** In-memory [SharedPreferences]; [commitResult] decides what `commit()` reports. */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Any?>()
        var commitResult = true

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private val removed = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                changes[key!!] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                changes[key!!] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                changes[key!!] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                changes[key!!] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                changes[key!!] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                changes[key!!] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                removed += key!!
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                if (!commitResult) return false
                if (clearAll) data.clear()
                removed.forEach { data.remove(it) }
                data.putAll(changes)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun successfulCommitsAreStored() {
        val prefs = FakePrefs()
        val store = SharedPreferencesStore(prefs)
        store.put("token", "t")
        assertEquals("t", store.get("token"))
        store.remove("token")
        assertNull(store.get("token"))
    }

    @Test
    fun failedCommitIsNotReportedAsSuccess() {
        val prefs = FakePrefs()
        val store = SharedPreferencesStore(prefs)
        store.put("token", "old")
        prefs.commitResult = false
        assertFailsWith<KeyValueStoreException> { store.put("token", "new") }
        assertFailsWith<KeyValueStoreException> { store.remove("token") }
        assertEquals("old", store.get("token"))
        // the error reaches the credential layer instead of a silent success
        assertFailsWith<KeyValueStoreException> { CredentialStore(store, "max.default").loadOrCreate() }
    }
}
