@file:OptIn(ExperimentalForeignApi::class)

package com.max.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain failure (an `OSStatus` other than success / not found). */
class KeychainException(val status: Int, message: String) : Exception("$message (OSStatus $status)")

/**
 * [KeyValueStore] over Keychain generic-password items: one item per key, `kSecAttrService` =
 * [service], `kSecAttrAccount` = key, value = UTF-8 bytes. Items are
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (usable for background reconnects after the
 * first unlock, not synced or restored to another device — the token is tied to this install's
 * device id).
 */
class KeychainKeyValueStore(val service: String) : KeyValueStore {

    override fun get(key: String): String? = withQuery(key) { q ->
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val out = alloc<CFTypeRefVar>()
            when (val status = SecItemCopyMatching(q, out.ptr)) {
                errSecSuccess -> (CFBridgingRelease(out.value) as? NSData)?.toByteArray()?.decodeToString()
                errSecItemNotFound -> null
                else -> throw KeychainException(status, "keychain read failed")
            }
        }
    }

    override fun put(key: String, value: String) {
        val data = value.encodeToByteArray().toNSData()
        val status = withQuery(key) { q ->
            withAttributes(data) { attrs -> SecItemUpdate(q, attrs) }
        }
        when (status) {
            errSecSuccess -> return
            errSecItemNotFound -> Unit
            else -> throw KeychainException(status, "keychain update failed")
        }
        val added = withQuery(key) { q ->
            val retained = CFBridgingRetain(data)
            try {
                CFDictionaryAddValue(q, kSecValueData, retained)
                CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(q, null)
            } finally {
                CFRelease(retained)
            }
        }
        if (added != errSecSuccess && added != errSecDuplicateItem) throw KeychainException(added, "keychain add failed")
    }

    override fun remove(key: String) {
        val status = withQuery(key) { q -> SecItemDelete(q) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw KeychainException(status, "keychain delete failed")
    }

    /** A mutable query `{class: generic password, service, account: key}`, released after [block]. */
    private inline fun <T> withQuery(key: String, block: (CFMutableDictionaryRef?) -> T): T {
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceRef = CFBridgingRetain(NSString.create(string = service))
        val accountRef = CFBridgingRetain(NSString.create(string = key))
        try {
            CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(q, kSecAttrService, serviceRef)
            CFDictionaryAddValue(q, kSecAttrAccount, accountRef)
            return block(q)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(q)
        }
    }

    private inline fun <T> withAttributes(data: NSData, block: (CFMutableDictionaryRef?) -> T): T {
        val attrs = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val retained: CFTypeRef? = CFBridgingRetain(data)
        try {
            CFDictionaryAddValue(attrs, kSecValueData, retained)
            return block(attrs)
        } finally {
            CFRelease(retained)
            CFRelease(attrs)
        }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return bytes!!.readBytes(n)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
