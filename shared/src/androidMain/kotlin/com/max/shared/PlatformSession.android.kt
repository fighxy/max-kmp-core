package com.max.shared

import android.content.Context
import android.content.SharedPreferences

@Volatile
private var appContext: Context? = null

/** Must be called once (e.g. in `Application.onCreate`) before a [MaxClient] is created. */
fun PlatformSession.init(context: Context) {
    appContext = context.applicationContext
}

internal actual fun platformName(): String = "android"

/** Private `SharedPreferences` file `max_kmp_<namespace>`. */
internal actual fun defaultKeyValueStore(namespace: String): KeyValueStore {
    val ctx = appContext ?: throw IllegalStateException("call PlatformSession.init(context) before creating a MaxClient")
    return SharedPreferencesStore(ctx.getSharedPreferences("max_kmp_" + safeName(namespace), Context.MODE_PRIVATE))
}

/** [KeyValueStore] over [SharedPreferences] (`commit()`, so a saved token survives a crash). */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
