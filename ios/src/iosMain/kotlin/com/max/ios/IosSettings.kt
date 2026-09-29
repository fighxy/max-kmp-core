package com.max.ios

import com.max.core.api.AccountConfig
import com.max.core.api.ChatFolders
import com.max.core.api.EntryApp
import com.max.core.api.Folder
import com.max.core.api.MaxUser
import com.max.core.api.SessionInfo
import com.max.core.api.TwoFactorDetails
import com.max.core.api.WebAppInitData
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
 * - [phonePrivacy]: `ALL`, `CONTACTS` or `NOBODY` (`_NONE_` is reported as `NOBODY`);
 * - [onlineHidden]: `HIDDEN`, the online status is shown to nobody;
 * - [inactiveTtl]: `1M`, `3M` or `6M`;
 * - [familyProtection]: `ON` or `OFF`;
 * - [inviteLink]: full URL or empty; [sferumBotId], [digitalIdBotId]: mini app bots.
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
    return IosAccountSettings(
        known = config != null,
        phonePrivacy = access(c.userString("PHONE_NUMBER_PRIVACY"), "ALL"),
        onlineHidden = c.userFlag("HIDDEN") ?: false,
        safeMode = c.userFlag("SAFE_MODE") ?: false,
        searchByPhone = access(c.userString("SEARCH_BY_PHONE"), "ALL"),
        incomingCalls = access(c.userString("INCOMING_CALL"), "CONTACTS"),
        chatInvites = access(c.userString("CHATS_INVITE"), "CONTACTS"),
        safeContentOnly = c.userFlag("CONTENT_LEVEL_ACCESS") ?: false,
        familyProtection = if (c.userFlag("FAMILY_PROTECTION") == true) "ON" else "OFF",
        inactiveTtl = c.userString("INACTIVE_TTL")?.uppercase()?.takeIf { it in INACTIVE_TTLS } ?: "6M",
        inviteLink = c.inviteLink.orEmpty(),
        sferumBotId = c.entryAppBotId(EntryApp.SFERUM),
        digitalIdBotId = c.entryAppBotId(EntryApp.DIGITAL_ID),
    )
}

/** Privacy access as `ALL` / `CONTACTS` / `NOBODY`; PyMax's `_NONE_` means nobody too. */
private fun access(value: String?, fallback: String): String = when (value?.uppercase()) {
    "ALL" -> "ALL"
    "CONTACTS" -> "CONTACTS"
    "NOBODY", "_NONE_", "NONE" -> "NOBODY"
    else -> fallback
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
