package com.maxly.core.calls

import com.maxly.core.api.asLong

/**
 * Why a call ended (`VIDEO_CHAT_HANGUP` 167 `reason`). Names are the app's enum.
 * Rejecting an incoming call sends [REJECTED].
 */
enum class CallHangupReason {
    TIMEOUT,
    BUSY,
    MISSED,
    REJECTED,
    FAILED,
    HUNGUP,
    CANCELED,
    CALL_TIMEOUT,
    REMOVED,
    SERVICE_UNAVAILABLE,
    PARTICIPANT_LIMIT_EXCEEDED,
    OBSOLETE_CLIENT,
    BANNED,
    ANOTHER_DEVICE,
    KILLED,
    KILLED_WITHOUT_DELETE,
    ADMIN_CLOSED,
    SOCKET_CLOSED,
    INITIALLY_CLOSED,
    ;

    companion object {
        fun from(name: String?): CallHangupReason? =
            entries.firstOrNull { it.name == name }
    }
}

/** `callType` of a call-log item. Anything else is not an item (the app drops it). */
enum class CallMedia { AUDIO, VIDEO }

/** `hangupType` of a call-log item. An unknown string is left out, the item stays. */
enum class CallEnd {
    HUNGUP, CANCELED, REJECTED, MISSED;

    companion object {
        fun from(name: String?): CallEnd? = entries.firstOrNull { it.name == name }
    }
}

/** `groupCallType` number: `0` link, `1` chat. Any other number is absent. */
enum class GroupCallKind(val wire: Int) {
    LINK(0),
    CHAT(1),
    ;

    companion object {
        fun from(value: Any?): GroupCallKind? = when (value.asLong()?.toInt()) {
            0 -> LINK
            1 -> CHAT
            else -> null
        }
    }
}

/**
 * One `callHistoryItems` entry (opcodes 163 and 165).
 * [callId] and [callType] are required. [durationMs] is null when the key is absent (`0` is a real duration).
 * [chatId] `0` means the app's default, no chat.
 */
data class CallHistoryItem(
    val historyId: Long,
    val callId: String,
    val callName: String?,
    val callerId: Long,
    val messageId: Long?,
    val chatId: Long,
    val callType: CallMedia,
    val hangupType: CallEnd?,
    val joinLink: String?,
    val time: Long,
    val durationMs: Long?,
    val groupCallType: GroupCallKind?,
) {
    companion object {
        fun from(value: Any?): CallHistoryItem? {
            val m = value as? Map<*, *> ?: return null
            val callId = (m["callId"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val callType = when (m["callType"] as? String) {
                "AUDIO" -> CallMedia.AUDIO
                "VIDEO" -> CallMedia.VIDEO
                else -> return null
            }
            return CallHistoryItem(
                historyId = m["historyId"].asLong() ?: 0L,
                callId = callId,
                callName = m["callName"] as? String,
                callerId = m["callerId"].asLong() ?: 0L,
                messageId = m["messageId"].asLong(),
                chatId = m["chatId"].asLong() ?: 0L,
                callType = callType,
                hangupType = CallEnd.from(m["hangupType"] as? String),
                joinLink = m["joinLink"] as? String,
                time = m["time"].asLong() ?: 0L,
                durationMs = m["durationMs"].asLong(),
                groupCallType = GroupCallKind.from(m["groupCallType"]),
            )
        }
    }
}

/** Reply of `CALL_HISTORY` 163. [sync] is the cursor to send next time. [reset] replaces the local log. */
data class CallHistoryPage(
    val items: List<CallHistoryItem>,
    val sync: Long,
    val reset: Boolean,
)

/** `NOTIF_CALL_HISTORY` 165 `action`. */
enum class CallHistoryAction {
    ADD, REMOVE;

    companion object {
        fun from(name: String?): CallHistoryAction? = entries.firstOrNull { it.name == name }
    }
}
