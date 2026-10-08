package com.max.core.api

/**
 * The name a client shows for a user, from three sources in this order:
 *
 * 1. the contact name this account set on the server: the `CUSTOM` entry of `names`
 *    (`CONTACT_UPDATE` 34 `ADD` / `UPDATE` with `firstName` / `lastName`, KometTeam/Komet
 *    `ContactsModule`; Komet strips it after `REMOVE`);
 * 2. the name from the device address book that the client handed to the core (the phone book
 *    entry whose number is the user's `phone`, or a name set for the user id);
 * 3. the user's own profile name: the `ONEME` entry, else the first entry of `names`
 *    ([MaxUser.displayName]).
 *
 * Inside the `CUSTOM` entry `firstName lastName` wins over `name` (as Komet reads it); in the
 * profile entry `name` wins, as before.
 */
object ContactNames {
    const val CUSTOM = "CUSTOM"
    const val ONEME = "ONEME"

    /** `firstName lastName` of [n] when not blank, else its `name`; `null` when all are blank. */
    fun entryName(n: UserName): String? =
        listOfNotNull(n.firstName?.trim(), n.lastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
            ?: n.name?.trim()?.takeIf { it.isNotEmpty() }

    /** The name this account gave [user] (its `CUSTOM` entry), or `null`. */
    fun customName(user: MaxUser): String? = user.names.firstOrNull { it.type == CUSTOM }?.let(::entryName)

    /**
     * The user's own name: the `ONEME` entry, else [MaxUser.displayName]. Here `name` wins over
     * `firstName lastName`, as in [MaxUser.displayName], so names shown so far stay the same.
     */
    fun profileName(user: MaxUser): String? =
        user.names.firstOrNull { it.type == ONEME }?.let { n -> n.name?.trim()?.takeIf { it.isNotEmpty() } ?: entryName(n) }
            ?: user.displayName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The resolved name: [customName] > [addressBookName] > [profileName]. [user] may be `null`
     * (not loaded yet); then only [addressBookName] can name it.
     */
    fun resolve(user: MaxUser?, addressBookName: String?): String? =
        user?.let(::customName)
            ?: addressBookName?.trim()?.takeIf { it.isNotEmpty() }
            ?: user?.let(::profileName)

    /** [user] without its `CUSTOM` name (after the contact was removed). */
    fun withoutCustom(user: MaxUser): MaxUser {
        if (user.names.none { it.type == CUSTOM }) return user
        val raw = LinkedHashMap<Any?, Any?>(user.raw)
        (user.raw["names"] as? List<*>)?.let { list -> raw["names"] = list.filterNot { (it as? Map<*, *>)?.get("type") == CUSTOM } }
        return user.copy(names = user.names.filterNot { it.type == CUSTOM }, raw = raw)
    }
}
