package com.max.core.api

/**
 * What a pin update did (`PINNED_MESSAGE_UPDATE` 242 `action`, and `lastAction` of a pin state).
 * Wire values are the app's: pin `0`, unpin `1`, unpin every pin `2`. An unknown byte is not a value.
 */
enum class PinAction(val wire: Int) {
    PIN(0),
    UNPIN(1),
    UNPIN_ALL(2),
    ;

    companion object {
        fun from(value: Any?): PinAction? = when (value.asLong()?.toInt()) {
            0 -> PIN
            1 -> UNPIN
            2 -> UNPIN_ALL
            else -> null
        }
    }
}

/**
 * Who a pin change applies to. Bits of `changedPinnedMessageType` (the app's pin type):
 * [FOR_ALL] is bit 1, [FOR_ME] is bit 2. `0` means the state did not say.
 */
object PinScope {
    const val FOR_ALL: Int = 1
    const val FOR_ME: Int = 2
}

/**
 * Pin state of one chat (`pinnedMessagesState` of opcodes 240, 242 and push 243).
 *
 * Field names are the app's parser keys. A missing time is `0`. A missing id is `null`
 * (the app stores `0` for "none", and this model does the same by dropping `0`).
 * A missing count is `null`, not `0`: `0` means the chat has no pins.
 * [changedType] is the raw bitfield; [forAll] and [forMe] read [PinScope].
 * [lastAction] is `null` when the key is absent or the byte is not 0, 1 or 2.
 *
 * `changedMessageId` is read only when `changedPinnedMessageId` is absent: the web client
 * uses that shorter name, the app does not.
 */
data class PinnedMessageState(
    val chatId: Long,
    val lastPinnedUpdateTime: Long,
    val prevPinnedUpdateTime: Long,
    val totalPinnedCount: Int?,
    val changedMessageId: Long?,
    val changedType: Int,
    val lastAction: PinAction?,
    val lastPinnedMessageId: Long?,
) {
    val forAll: Boolean get() = changedType and PinScope.FOR_ALL != 0
    val forMe: Boolean get() = changedType and PinScope.FOR_ME != 0

    companion object {
        private val KEYS = listOf(
            "chatId",
            "lastPinnedUpdateTime",
            "prevPinnedUpdateTime",
            "totalPinnedMessagesCount",
            "changedPinnedMessageId",
            "changedMessageId",
            "changedPinnedMessageType",
            "lastAction",
            "lastPinnedMessageId",
        )

        fun from(value: Any?): PinnedMessageState? {
            val m = value as? Map<*, *> ?: return null
            if (KEYS.none { m.containsKey(it) }) return null
            val changed = m["changedPinnedMessageId"].asLong() ?: m["changedMessageId"].asLong()
            return PinnedMessageState(
                chatId = m["chatId"].asLong() ?: 0L,
                lastPinnedUpdateTime = m["lastPinnedUpdateTime"].asLong() ?: 0L,
                prevPinnedUpdateTime = m["prevPinnedUpdateTime"].asLong() ?: 0L,
                totalPinnedCount = m["totalPinnedMessagesCount"].asLong()?.toInt(),
                changedMessageId = changed?.takeIf { it != 0L },
                changedType = m["changedPinnedMessageType"].asLong()?.toInt() ?: 0,
                lastAction = PinAction.from(m["lastAction"]),
                lastPinnedMessageId = m["lastPinnedMessageId"].asLong()?.takeIf { it != 0L },
            )
        }
    }
}
