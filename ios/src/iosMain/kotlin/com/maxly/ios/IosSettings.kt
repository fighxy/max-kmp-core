package com.maxly.ios

import com.maxly.core.api.AccountConfig
import com.maxly.core.api.ChatFolders
import com.maxly.core.api.EntryApp
import com.maxly.core.api.Folder
import com.maxly.core.api.MaxUser
import com.maxly.core.api.PrivacyConfig
import com.maxly.core.api.SessionInfo
import com.maxly.core.api.TwoFactorDetails
import com.maxly.core.api.WebAppInitData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * The own profile for the settings screen. [phone] is digits without `+` (empty when unknown),
 * [link] the profile link as a full URL (`invite-link` of the config, else the own contact's
 * link; empty when neither is known), [photoId] empty without an avatar.
 */
class IosMyProfile(
    val id: String,
    val firstName: String,
    val lastName: String,
    val description: String,
    val phone: String,
    val avatarUrl: String,
    val photoId: String,
    val link: String,
)

/**
 * User settings of `config.user` and settings-screen values of `config.server`. [known] is `false`
 * until the first `LOGIN` config arrived (the other values are then defaults).
 * Privacy ([PrivacyConfig], defaults of the MAX web client for a key the server did not send):
 * - [phonePrivacy]: `PHONE_NUMBER_PRIVACY`, `ALL`, `CONTACTS` or `NOBODY` (`_NONE_` is read as
 *   `NOBODY`), default `CONTACTS`;
 * - [onlineHidden]: `HIDDEN`, the online status is shown to nobody (`false`: to contacts);
 * - [searchByPhone], [incomingCalls], [chatInvites]: `SEARCH_BY_PHONE`, `INCOMING_CALL`,
 *   `CHATS_INVITE`, `ALL` or `CONTACTS`, default `ALL`; [safeContentOnly]: `CONTENT_LEVEL_ACCESS`;
 * - [safeMode]: `SAFE_MODE`. While it is on those four report the forced values (contacts, safe
 *   content) and [privacyLocked] is `true`: they cannot be changed until safe mode is off;
 * - [familyProtection]: `FAMILY_PROTECTION`, `OFF`, `ADMIN` (this account protects someone),
 *   `MANAGEABLE` (this account is protected: [privacyLocked], safe mode cannot be changed
 *   either) or `UNKNOWN` with the server's value in [familyProtectionRaw] (empty when absent);
 * - [showReadMark]: `SHOW_READ_MARK`, read only and unverified (no official client has a switch
 *   for it); `true` when absent, [showReadMarkKnown] tells whether the server sent it;
 * - [inactiveTtl]: `1M`, `3M` or `6M`;
 * - [inviteLink]: full URL or empty; [sferumBotId], [digitalIdBotId]: mini app bots.
 * - [quickReaction]: emoji for a double tap (`DOUBLE_TAP_REACTION_VALUE`), 👍 when unset.
 * - [quickReactionDisabled]: `DOUBLE_TAP_REACTION_DISABLED`.
 * - [storiesHistory]: `config.server["stories-history"]`, show the own-archive settings item
 *   (`false` when the server did not turn it on);
 * - [familyProtectionBotId]: `config.server["family-protection-botid"]` as a decimal string,
 *   empty when the server sent no positive id (the app's default is `0`).
 */
class IosAccountSettings(
    val known: Boolean,
    val phonePrivacy: String,
    val onlineHidden: Boolean,
    val safeMode: Boolean,
    val searchByPhone: String,
    val incomingCalls: String,
    val chatInvites: String,
    val safeContentOnly: Boolean,
    val familyProtection: String,
    val inactiveTtl: String,
    val inviteLink: String,
    val sferumBotId: Long,
    val digitalIdBotId: Long,
    val quickReaction: String,
    val quickReactionDisabled: Boolean,
    val familyProtectionRaw: String,
    val privacyLocked: Boolean,
    val showReadMark: Boolean,
    val showReadMarkKnown: Boolean,
    val storiesHistory: Boolean,
    val familyProtectionBotId: String,
)

/** One active session. [lastSeenMs] is Unix milliseconds (0 when unknown). */
class IosSession(
    val id: String,
    val client: String,
    val info: String,
    val location: String,
    val current: Boolean,
    val lastSeenMs: Long,
)

/** A blocked user. [phone] is digits without `+`, empty when hidden. */
class IosBlockedUser(val id: String, val name: String, val phone: String, val avatarUrl: String)

/** The cloud password state; [email] and [hint] are empty when not set. */
class IosTwoFactor(val enabled: Boolean, val email: String, val hint: String)

/** A mini app to open: [url] carries the launch data; [queryId] is empty when the server sent none. */
class IosMiniApp(val botId: Long, val url: String, val queryId: String)

/**
 * A chat folder. [chatIds] are the explicitly included chats, [filters] the server filters as
 * text (codes such as `"4"` or names such as `"CHANNEL"`), [isAllChats] marks the fixed "all
 * chats" folder, [pinnedCount] its pinned chats.
 */
class IosFolder(
    val id: String,
    val title: String,
    val chatIds: List<String>,
    val filters: List<String>,
    val isAllChats: Boolean,
    val pinnedCount: Int,
)

internal fun myProfileSnapshot(user: MaxUser, config: AccountConfig?): IosMyProfile {
    val name = user.names.firstOrNull()
    val first = name?.firstName?.takeIf { it.isNotBlank() } ?: name?.name.orEmpty()
    return IosMyProfile(
        id = user.id.toString(),
        firstName = first,
        lastName = name?.lastName.orEmpty(),
        description = user.description?.trim().orEmpty(),
        phone = user.phone?.takeIf { it > 0 }?.toString().orEmpty(),
        avatarUrl = user.baseUrl.orEmpty(),
        photoId = user.photoId?.takeIf { it > 0 }?.toString().orEmpty(),
        link = (config?.inviteLink ?: AccountConfig.normalizeLink(user.link)).orEmpty(),
    )
}

internal fun settingsSnapshot(config: AccountConfig?): IosAccountSettings {
    val c = config ?: AccountConfig()
    val p = PrivacyConfig.from(c)
    return IosAccountSettings(
        known = config != null,
        phonePrivacy = p.phoneNumber.name,
        onlineHidden = p.onlineHidden,
        safeMode = p.safeMode,
        searchByPhone = p.searchByPhone.name,
        incomingCalls = p.incomingCalls.name,
        chatInvites = p.chatInvites.name,
        safeContentOnly = p.safeContentOnly,
        familyProtection = p.familyProtection.name,
        inactiveTtl = c.userString("INACTIVE_TTL")?.uppercase()?.takeIf { it in INACTIVE_TTLS } ?: "6M",
        inviteLink = c.inviteLink.orEmpty(),
        sferumBotId = c.entryAppBotId(EntryApp.SFERUM),
        digitalIdBotId = c.entryAppBotId(EntryApp.DIGITAL_ID),
        quickReaction = quickReaction(c),
        quickReactionDisabled = c.userFlag("DOUBLE_TAP_REACTION_DISABLED") == true,
        familyProtectionRaw = p.familyProtectionRaw.orEmpty(),
        privacyLocked = p.locked,
        showReadMark = p.showReadMark ?: true,
        showReadMarkKnown = p.showReadMark != null,
        storiesHistory = c.storiesHistory,
        familyProtectionBotId = c.familyProtectionBotId?.toString().orEmpty(),
    )
}

/** `DOUBLE_TAP_REACTION_VALUE`: an emoji string, or a map with `id` / `reaction`. Blank stays 👍. */
private fun quickReaction(config: AccountConfig): String {
    val raw = config.user["DOUBLE_TAP_REACTION_VALUE"]
    val text = when (raw) {
        is String -> raw.trim()
        is Map<*, *> -> ((raw["id"] ?: raw["reaction"]) as? String)?.trim()
        else -> null
    }
    return text?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: "👍"
}

internal val INACTIVE_TTLS = setOf("1M", "3M", "6M")

internal fun sessionSnapshot(s: SessionInfo): IosSession {
    val seen = s.lastSeen ?: 0L
    return IosSession(
        id = s.id.orEmpty(),
        client = (s.client ?: listOfNotNull(s.appVersion, s.platform).joinToString(" ")).trim(),
        info = (s.info ?: s.deviceName ?: s.userAgent).orEmpty().trim(),
        location = s.location.orEmpty().trim(),
        current = s.current,
        lastSeenMs = if (seen in 1 until 100_000_000_000L) seen * 1000 else seen,
    )
}

internal fun blockedSnapshot(user: MaxUser): IosBlockedUser = IosBlockedUser(
    id = user.id.toString(),
    name = user.displayName.orEmpty(),
    phone = user.phone?.takeIf { it > 0 }?.toString().orEmpty(),
    avatarUrl = user.baseUrl.orEmpty(),
)

internal fun twoFactorSnapshot(d: TwoFactorDetails): IosTwoFactor = IosTwoFactor(d.enabled, d.email.orEmpty(), d.hint.orEmpty())

internal fun miniAppSnapshot(botId: Long, data: WebAppInitData): IosMiniApp = IosMiniApp(botId, data.url, data.queryId.orEmpty())

internal fun folderSnapshots(folders: ChatFolders): List<IosFolder> {
    val all = folders.allChats?.id
    return folders.folders.map { f -> folderSnapshot(f, f.id == all) }
}

private fun folderSnapshot(f: Folder, isAll: Boolean): IosFolder = IosFolder(
    id = f.id,
    title = f.title,
    chatIds = f.include.map { it.toString() },
    filters = f.filters.mapNotNull { v ->
        when (v) {
            null -> null
            is Number -> v.toLong().toString()
            else -> v.toString()
        }
    },
    isAllChats = isAll,
    pinnedCount = f.favorites.distinct().size,
)

/** Folder filters from Swift (`"4"`, `"CHANNEL"`): numbers go to the server as numbers, as Komet sends them. */
internal fun parseFilters(filters: List<String>): List<Any?> = filters.map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: it }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return out
}
