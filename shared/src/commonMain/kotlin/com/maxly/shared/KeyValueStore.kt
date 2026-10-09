package com.maxly.shared

import com.maxly.core.auth.DEFAULT_CONFIG_HASH
import com.maxly.core.auth.SyncState
import com.maxly.core.session.randomHexId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Small string key-value persistence used for credentials. Platform implementations
 * ([PlatformSession.defaultStore]): Keychain on iOS, `SharedPreferences` on Android, a
 * properties file on JVM. Implementations must be safe to call from any thread.
 *
 * [put] and [remove] return only once the change is stored; when it could not be stored they
 * throw (e.g. [KeyValueStoreException], `KeychainException`, `IOException`) instead of reporting
 * success.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** A [KeyValueStore] could not persist a change. */
class KeyValueStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Non-persistent [KeyValueStore] (tests, or when nothing should survive the process). */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = MutableStateFlow(initial)
    val snapshot: Map<String, String> get() = map.value
    override fun get(key: String): String? = map.value[key]
    override fun put(key: String, value: String) = map.update { it + (key to value) }
    override fun remove(key: String) = map.update { it - key }
}

/**
 * What a client persists between runs (PyMax `SessionStore`: device id, `mt_instanceid`, token,
 * sync markers). The login token belongs to [deviceId] / [instanceId], so the identity is kept
 * after logout and only the token is cleared.
 */
data class StoredCredentials(
    val deviceId: String,
    val instanceId: String,
    val token: String? = null,
    val userId: Long? = null,
    val sync: SyncState = SyncState(),
)

/** Reads / writes [StoredCredentials] under `<prefix>.` keys of a [KeyValueStore]. */
class CredentialStore(private val kv: KeyValueStore, private val prefix: String = "max") {

    /** Stored credentials, or `null` when no identity was saved yet. */
    fun load(): StoredCredentials? {
        val deviceId = get("deviceId") ?: return null
        val instanceId = get("instanceId") ?: return null
        return StoredCredentials(
            deviceId = deviceId,
            instanceId = instanceId,
            token = get("token"),
            userId = get("userId")?.toLongOrNull(),
            sync = SyncState(
                chatsSync = get("sync.chats")?.toLongOrNull() ?: -1,
                contactsSync = get("sync.contacts")?.toLongOrNull() ?: -1,
                // Older builds saved the login time under "sync.drafts", which hid server drafts.
                draftsSync = get(DRAFTS_SYNC_KEY)?.toLongOrNull() ?: -1,
                presenceSync = get("sync.presence")?.toLongOrNull() ?: -1,
                configHash = decodeHash(get("sync.configHash")),
            ),
        )
    }

    /** Stored credentials, or a fresh random identity (saved right away). */
    fun loadOrCreate(): StoredCredentials = load() ?: StoredCredentials(randomHexId(), randomHexId()).also(::save)

    fun save(c: StoredCredentials) {
        put("deviceId", c.deviceId)
        put("instanceId", c.instanceId)
        put("token", c.token)
        put("userId", c.userId?.toString())
        put("sync.chats", c.sync.chatsSync.toString())
        put("sync.contacts", c.sync.contactsSync.toString())
        put(DRAFTS_SYNC_KEY, c.sync.draftsSync.toString())
        put("sync.drafts", null) // the legacy key
        put("sync.presence", c.sync.presenceSync.toString())
        put("sync.configHash", encodeHash(c.sync.configHash))
    }

    /** Forgets the token, user and sync markers; keeps the device identity. */
    fun clearToken() {
        val c = load() ?: return
        save(StoredCredentials(c.deviceId, c.instanceId))
    }

    /** Forgets everything, including the identity. */
    fun clearAll() {
        listOf(
            "deviceId", "instanceId", "token", "userId", "sync.chats", "sync.contacts", "sync.drafts", DRAFTS_SYNC_KEY, "sync.presence", "sync.configHash",
        ).forEach { kv.remove("$prefix.$it") }
    }

    private fun get(key: String): String? = kv.get("$prefix.$key")
    private fun put(key: String, value: String?) = if (value == null) kv.remove("$prefix.$key") else kv.put("$prefix.$key", value)

    private fun encodeHash(v: Any): String = if (v is Number) "l:${v.toLong()}" else "s:$v"
    private fun decodeHash(v: String?): Any = when {
        v == null -> DEFAULT_CONFIG_HASH
        v.startsWith("l:") -> v.substring(2).toLongOrNull() ?: DEFAULT_CONFIG_HASH
        v.startsWith("s:") -> v.substring(2)
        else -> v
    }
}

/** Key of [SyncState.draftsSync]; a new name, so a login time saved by older builds is not read. */
private const val DRAFTS_SYNC_KEY = "sync.drafts.v2"
