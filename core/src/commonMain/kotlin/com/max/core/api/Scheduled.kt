package com.max.core.api

/**
 * `NOTIF_MSG_DELAYED` 154 `updateTypeId`, as the app names it (`mgc` toString).
 * Wire bytes: created `0`, edited `1`, deleted `2`, fired `3`. Any other byte is not a value
 * (the app stores that as `UNKNOWN`).
 */
enum class DelayedUpdate(val wire: Int) {
    CREATED(0),
    EDITED(1),
    DELETED(2),
    FIRE_SUCCESS(3),
    ;

    companion object {
        fun from(value: Any?): DelayedUpdate? = when (value.asLong()?.toInt()) {
            0 -> CREATED
            1 -> EDITED
            2 -> DELETED
            3 -> FIRE_SUCCESS
            else -> null
        }
    }
}

/** One poll to refresh (`GET_POLL_UPDATES` 306 item of `polls`: `messageId`, `pollId`). */
data class PollRef(val messageId: Long, val pollId: Long)
