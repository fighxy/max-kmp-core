package com.maxly.core.api

/**
 * The one rule for the name a client shows for a user (shared by the Maxly clients, fixtures
 * `test-fixtures/names/`), first match wins:
 *
 * 1. the name from the device address book (supplied by the client and matched on the device by
 *    the phone, [PhoneNumbers.normalize], or set for the user id; never sent to the server);
 * 2. the contact name this account set on the server: the `CUSTOM` entry of `names`
 *    (`CONTACT_UPDATE` 34 `ADD` / `UPDATE`);
 * 3. the person's own name: the `ONEME` entry;
 * 4. the first entry of `names` that has a name;
 * 5. the phone number (`+79131234567`; a number the rules do not normalize is shown as is);
 * 6. [FALLBACK], "Участник" ([label] only; [resolve] gives `null`).
 *
 * Inside an entry `firstName lastName` wins over `name` for `CUSTOM` (the rename form fills
 * them); for `ONEME` and the first entry `name` wins, as [MaxUser.displayName] reads it.
 * Mentions keep inserting the person's own name, never one of these.
 *
 * With `preferAddressBook = false` (`MaxState.preferAddressBookNames`, a client setting) steps 1
 * and 2 swap: the own `CUSTOM` name wins over the address book. The default stays the order above.
 */
object ContactNames {
    const val CUSTOM = "CUSTOM"
    const val ONEME = "ONEME"

    /** The last fallback of [label]. */
    const val FALLBACK = "Участник"

    /** `firstName lastName` of [n] when not blank, else its `name`; `null` when all are blank. */
    fun entryName(n: UserName): String? =
        listOfNotNull(n.firstName?.trim(), n.lastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
            ?: n.name?.trim()?.takeIf { it.isNotEmpty() }

    private fun ownName(n: UserName): String? = n.name?.trim()?.takeIf { it.isNotEmpty() } ?: entryName(n)

    /** The name this account gave [user] (its `CUSTOM` entry), or `null`. */
    fun customName(user: MaxUser): String? = user.names.firstOrNull { it.type == CUSTOM }?.let(::entryName)

    /** The user's own name: the `ONEME` entry, else the first entry of `names` with a name. */
    fun profileName(user: MaxUser): String? =
        user.names.firstOrNull { it.type == ONEME }?.let(::ownName)
            ?: user.names.firstNotNullOfOrNull(::ownName)

    /** Step 5: the phone normalized ([PhoneNumbers.normalize]), else its digits as they are. */
    fun phoneName(user: MaxUser): String? {
        val phone = user.phone?.takeIf { it > 0 } ?: return null
        return PhoneNumbers.normalize(phone) ?: phone.toString()
    }

    /** Steps 1-5 of the rule; `null` when none applies. [user] may be `null` (not loaded yet). */
    fun resolve(user: MaxUser?, addressBookName: String?): String? = resolve(user, addressBookName, true)

    /**
     * Steps 1-5 of the rule; [preferAddressBook] `false` puts the own `CUSTOM` name before the
     * address-book name. `null` when none applies.
     */
    fun resolve(user: MaxUser?, addressBookName: String?, preferAddressBook: Boolean): String? {
        val book = addressBookName?.trim()?.takeIf { it.isNotEmpty() }
        val custom = user?.let(::customName)
        val first = if (preferAddressBook) book ?: custom else custom ?: book
        return first
            ?: user?.let(::profileName)
            ?: user?.let(::phoneName)
    }

    /** [resolve], or [FALLBACK]. */
    fun label(user: MaxUser?, addressBookName: String?): String = resolve(user, addressBookName) ?: FALLBACK

    /** [resolve] with [preferAddressBook], or [FALLBACK]. */
    fun label(user: MaxUser?, addressBookName: String?, preferAddressBook: Boolean): String =
        resolve(user, addressBookName, preferAddressBook) ?: FALLBACK

    /** [user] without its `CUSTOM` name (after the contact was removed). */
    fun withoutCustom(user: MaxUser): MaxUser {
        if (user.names.none { it.type == CUSTOM }) return user
        val raw = LinkedHashMap<Any?, Any?>(user.raw)
        (user.raw["names"] as? List<*>)?.let { list -> raw["names"] = list.filterNot { (it as? Map<*, *>)?.get("type") == CUSTOM } }
        return user.copy(names = user.names.filterNot { it.type == CUSTOM }, raw = raw)
    }
}
