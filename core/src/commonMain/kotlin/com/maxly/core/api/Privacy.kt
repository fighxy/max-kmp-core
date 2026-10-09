package com.maxly.core.api

/**
 * Who may do something: the value of the access keys of `config.user` (`SEARCH_BY_PHONE`,
 * `INCOMING_CALL`, `CHATS_INVITE`, `PHONE_NUMBER_PRIVACY`). The MAX web client and Komet send and
 * read `NOBODY`; PyMax sends `_NONE_` (in the web client that is the "no sound" value of the
 * push settings, not a privacy value). [parse] accepts both.
 */
enum class PrivacyAccess(val wire: String) {
    ALL("ALL"),
    CONTACTS("CONTACTS"),
    NOBODY("NOBODY"),
    ;

    companion object {
        /** `ALL`, `CONTACTS`, `NOBODY` (also `_NONE_` / `NONE`), any case; `null` for anything else. */
        fun parse(value: Any?): PrivacyAccess? = when ((value as? String)?.trim()?.uppercase()) {
            "ALL" -> ALL
            "CONTACTS" -> CONTACTS
            "NOBODY", "_NONE_", "NONE" -> NOBODY
            else -> null
        }
    }
}

/**
 * `FAMILY_PROTECTION` of `config.user`, a string as the MAX web client reads it: `OFF`; `ADMIN`
 * (this account protects someone); `MANAGEABLE` (this account is protected: safe mode, calls,
 * search, invites and content are managed by the admin and cannot be changed here). Any other
 * value is [UNKNOWN] with the raw value kept in [PrivacyConfig.familyProtectionRaw].
 */
enum class FamilyProtection { OFF, ADMIN, MANAGEABLE, UNKNOWN }

/**
 * The privacy settings of `config.user` (screen "Security" of MAX), with the defaults of the MAX
 * web client for a key the server did not send:
 * - [searchByPhone] `SEARCH_BY_PHONE` (`ALL` / `CONTACTS`, default `ALL`);
 * - [incomingCalls] `INCOMING_CALL` (`ALL` / `CONTACTS`, default `ALL`);
 * - [chatInvites] `CHATS_INVITE` (`ALL` / `CONTACTS`, default `ALL`);
 * - [safeContentOnly] `CONTENT_LEVEL_ACCESS` (`true`: safe content only, default `false`);
 * - [safeMode] `SAFE_MODE` (default `false`);
 * - [onlineHidden] `HIDDEN` (`true`: nobody sees the online status, `false`: contacts; default `false`);
 * - [phoneNumber] `PHONE_NUMBER_PRIVACY` (`ALL` / `CONTACTS` / `NOBODY`, default `CONTACTS`);
 * - [familyProtection] `FAMILY_PROTECTION`;
 * - [showReadMark] `SHOW_READ_MARK`, `null` when absent. Read only: the official clients have no
 *   switch for it and whether the server honours it is not verified.
 *
 * While [safeMode] is on, [LOCKED_BY_SAFE_MODE] (search, calls, invites, content) are forced to
 * contacts / safe content and cannot be changed ([locked]); the same holds while the account is
 * protected ([FamilyProtection.MANAGEABLE]). The four fields then report the forced values.
 */
data class PrivacyConfig(
    val searchByPhone: PrivacyAccess = PrivacyAccess.ALL,
    val incomingCalls: PrivacyAccess = PrivacyAccess.ALL,
    val chatInvites: PrivacyAccess = PrivacyAccess.ALL,
    val safeContentOnly: Boolean = false,
    val safeMode: Boolean = false,
    val onlineHidden: Boolean = false,
    val phoneNumber: PrivacyAccess = PrivacyAccess.CONTACTS,
    val familyProtection: FamilyProtection = FamilyProtection.OFF,
    val familyProtectionRaw: String? = null,
    val showReadMark: Boolean? = null,
) {
    /** Whether search, calls, invites and content are locked (safe mode or a protected account). */
    val locked: Boolean get() = safeMode || familyProtection == FamilyProtection.MANAGEABLE

    /** Whether [key] cannot be changed now ([locked] keys, [SHOW_READ_MARK] always). */
    fun isReadOnly(key: String): Boolean =
        key == SHOW_READ_MARK || key == FAMILY_PROTECTION || (locked && key in LOCKED_BY_SAFE_MODE) ||
            (familyProtection == FamilyProtection.MANAGEABLE && key == SAFE_MODE)

    companion object {
        const val SEARCH_BY_PHONE = "SEARCH_BY_PHONE"
        const val INCOMING_CALL = "INCOMING_CALL"
        const val CHATS_INVITE = "CHATS_INVITE"
        const val CONTENT_LEVEL_ACCESS = "CONTENT_LEVEL_ACCESS"
        const val SAFE_MODE = "SAFE_MODE"
        const val SAFE_MODE_NO_PIN = "SAFE_MODE_NO_PIN"
        const val HIDDEN = "HIDDEN"
        const val PHONE_NUMBER_PRIVACY = "PHONE_NUMBER_PRIVACY"
        const val FAMILY_PROTECTION = "FAMILY_PROTECTION"
        const val SHOW_READ_MARK = "SHOW_READ_MARK"

        /** The keys safe mode sets and locks. */
        val LOCKED_BY_SAFE_MODE: Set<String> = setOf(SEARCH_BY_PHONE, INCOMING_CALL, CHATS_INVITE, CONTENT_LEVEL_ACCESS)

        /** The keys [payload] accepts. */
        val WRITABLE: Set<String> = setOf(SEARCH_BY_PHONE, INCOMING_CALL, CHATS_INVITE, CONTENT_LEVEL_ACCESS, SAFE_MODE, HIDDEN, PHONE_NUMBER_PRIVACY)

        /** The privacy settings of [config]'s `user` (defaults for a missing `config`). */
        fun from(config: AccountConfig?): PrivacyConfig {
            val c = config ?: return PrivacyConfig()
            val rawFamily = c.userString(FAMILY_PROTECTION)?.trim()
            val family = when (rawFamily?.uppercase()) {
                null, "" -> FamilyProtection.OFF
                "OFF" -> FamilyProtection.OFF
                "ADMIN" -> FamilyProtection.ADMIN
                "MANAGEABLE" -> FamilyProtection.MANAGEABLE
                else -> FamilyProtection.UNKNOWN
            }
            val safeMode = c.userFlag(SAFE_MODE) ?: false
            val forced = safeMode
            return PrivacyConfig(
                searchByPhone = if (forced) PrivacyAccess.CONTACTS else access(c, SEARCH_BY_PHONE, PrivacyAccess.ALL),
                incomingCalls = if (forced) PrivacyAccess.CONTACTS else access(c, INCOMING_CALL, PrivacyAccess.ALL),
                chatInvites = if (forced) PrivacyAccess.CONTACTS else access(c, CHATS_INVITE, PrivacyAccess.ALL),
                safeContentOnly = forced || (c.userFlag(CONTENT_LEVEL_ACCESS) ?: false),
                safeMode = safeMode,
                onlineHidden = c.userFlag(HIDDEN) ?: false,
                phoneNumber = access(c, PHONE_NUMBER_PRIVACY, PrivacyAccess.CONTACTS),
                familyProtection = family,
                familyProtectionRaw = rawFamily?.takeIf { it.isNotEmpty() },
                showReadMark = c.userFlag(SHOW_READ_MARK),
            )
        }

        private fun access(c: AccountConfig, key: String, fallback: PrivacyAccess): PrivacyAccess =
            PrivacyAccess.parse(c.user[key]) ?: fallback

        /**
         * The `config.user` values that set [key] to [value] (`CONFIG` 22), checked:
         * - `SEARCH_BY_PHONE`, `INCOMING_CALL`, `CHATS_INVITE`: [PrivacyAccess] or its name, `ALL` / `CONTACTS`;
         * - `PHONE_NUMBER_PRIVACY`: `ALL` / `CONTACTS` / `NOBODY` (sent as `NOBODY`);
         * - `HIDDEN`, `CONTENT_LEVEL_ACCESS`: a boolean (or `"true"` / `"false"`);
         * - `SAFE_MODE`: a boolean; on also sets the locked keys (contacts, safe content) and
         *   `SAFE_MODE_NO_PIN` as Komet does; off sends only `SAFE_MODE` and `SAFE_MODE_NO_PIN`.
         *
         * [current] (the known settings) rejects a change of a read-only key ([isReadOnly]); pass
         * `null` to skip that check. Throws [IllegalArgumentException] for an unknown or read-only
         * key or a bad value, [IllegalStateException] for a locked one.
         */
        fun payload(key: String, value: Any, current: PrivacyConfig? = null): Map<String, Any?> {
            val k = key.trim().uppercase()
            require(k in WRITABLE) { if (k == SHOW_READ_MARK || k == FAMILY_PROTECTION) "read-only setting: $k" else "unknown privacy setting: $key" }
            if (current != null) check(!current.isReadOnly(k)) { "$k is locked (safe mode or family protection)" }
            return when (k) {
                SEARCH_BY_PHONE, INCOMING_CALL, CHATS_INVITE -> {
                    val a = accessOf(value)
                    require(a == PrivacyAccess.ALL || a == PrivacyAccess.CONTACTS) { "bad $k: $value" }
                    mapOf(k to a.wire)
                }
                PHONE_NUMBER_PRIVACY -> mapOf(k to accessOf(value).wire)
                HIDDEN, CONTENT_LEVEL_ACCESS -> mapOf(k to flagOf(k, value))
                SAFE_MODE -> if (flagOf(k, value)) {
                    linkedMapOf(
                        INCOMING_CALL to PrivacyAccess.CONTACTS.wire, SEARCH_BY_PHONE to PrivacyAccess.CONTACTS.wire,
                        SAFE_MODE_NO_PIN to true, CONTENT_LEVEL_ACCESS to true,
                        CHATS_INVITE to PrivacyAccess.CONTACTS.wire, SAFE_MODE to true,
                    )
                } else {
                    linkedMapOf(SAFE_MODE_NO_PIN to false, SAFE_MODE to false)
                }
                else -> throw IllegalArgumentException("unknown privacy setting: $key")
            }
        }

        private fun accessOf(value: Any): PrivacyAccess =
            (value as? PrivacyAccess) ?: PrivacyAccess.parse(value) ?: throw IllegalArgumentException("bad privacy access: $value")

        private fun flagOf(key: String, value: Any): Boolean = when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("bad $key: $value")
            }
            else -> throw IllegalArgumentException("bad $key: $value")
        }
    }
}
