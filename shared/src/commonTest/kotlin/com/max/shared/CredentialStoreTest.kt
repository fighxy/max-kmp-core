package com.max.shared

import com.max.core.auth.DEFAULT_CONFIG_HASH
import com.max.core.auth.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CredentialStoreTest {
    @Test
    fun roundTripAndClear() {
        val kv = InMemoryKeyValueStore()
        val store = CredentialStore(kv, "p")
        assertNull(store.load())
        val created = store.loadOrCreate()
        assertEquals(16, created.deviceId.length)
        assertNotEquals(created.deviceId, created.instanceId)
        assertEquals(created, store.loadOrCreate())
        assertEquals(DEFAULT_CONFIG_HASH, created.sync.configHash)

        val full = created.copy(token = "t", userId = 9, sync = SyncState(1, 2, 3, 4, 77L))
        store.save(full)
        assertEquals(full, store.load())
        store.save(full.copy(sync = SyncState(configHash = "l:odd")))
        assertEquals("l:odd", store.load()!!.sync.configHash)

        store.clearToken()
        assertEquals(StoredCredentials(created.deviceId, created.instanceId), store.load())
        assertEquals(setOf("p.deviceId", "p.instanceId"), kv.snapshot.keys.filter { it in setOf("p.deviceId", "p.instanceId", "p.token") }.toSet())
        store.clearAll()
        assertNull(store.load())
        assertEquals(emptyMap(), kv.snapshot)
    }

    @Test
    fun namespacesDoNotCollide() {
        val kv = InMemoryKeyValueStore()
        val a = CredentialStore(kv, "a").loadOrCreate()
        val b = CredentialStore(kv, "b").loadOrCreate()
        assertNotEquals(a.deviceId, b.deviceId)
        assertEquals(a, CredentialStore(kv, "a").load())
    }
}
