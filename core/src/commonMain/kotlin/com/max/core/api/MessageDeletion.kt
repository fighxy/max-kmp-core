package com.max.core.api

import com.max.core.epochMillis
import com.max.core.state.MaxState

/** How one message may be deleted: for everyone, only for this account, or not at all. */
enum class DeleteScope { ALL, SELF, NONE }

/** The kinds of chat the delete rule tells apart ([MessageDeletion]). */
enum class DeleteChatKind {
    SAVED, DIALOG, GROUP, CHANNEL;

    companion object {
        /**
         * The kind of [chatId]: id `0` is Saved Messages; otherwise the stored [chat]'s `type`
         * (`DIALOG`, `CHANNEL`, anything else a group). A chat the store does not know: a
         * positive id is a dialog, a negative one a group.
         */
        fun of(chatId: Long, chat: Chat?): DeleteChatKind = when {
            chatId == 0L -> SAVED
            chat?.type == "CHANNEL" -> CHANNEL
            chat?.type == "DIALOG" -> DIALOG
            chat == null && chatId > 0 -> DIALOG
            else -> GROUP
        }
    }
}

/**
 * The delete dialog for a selection: [scopes] per message id in the asked order and the
 * switches of the confirmation ([MessageDeletion.summary]).
 */
data class DeletePlan(
    val scopes: Map<Long, DeleteScope>,
    val canDelete: Boolean,
    val showsForEveryone: Boolean,
    val forEveryoneByDefault: Boolean,
    val forcesForEveryone: Boolean,
)

/**
 * Who may delete what (shared Orbitle rule, fixture `selection/delete.json`; the MAX web client's
 * `Message.canDelete` for the same chat kinds):
 *
 * | chat | rule |
 * |---|---|
 * | Saved Messages (id `0`) | [DeleteScope.SELF] |
 * | not sent yet (not in the store) | [DeleteScope.SELF]; in a channel without rights [DeleteScope.NONE] |
 * | dialog | own and younger than `edit-timeout` — [DeleteScope.ALL], otherwise [DeleteScope.SELF] |
 * | group | may delete any message ([ChatRoles.canDeleteAnyMessage]) — ALL; own and younger — ALL; otherwise SELF |
 * | channel | may delete any message — ALL, otherwise NONE |
 *
 * "Younger" is strict: `now - time < editTimeout * 1000`; `edit-timeout` absent is `0` (an own
 * message cannot be deleted for everyone), as in the web client. Not covered (the web client has
 * them): channel comments and the server option `delete-msg-fys-large-chat-disabled`.
 */
object MessageDeletion {
    /** The scope of one message. [sent] `false`: an unsent message that only the device has. */
    fun scope(
        kind: DeleteChatKind,
        own: Boolean,
        sent: Boolean,
        timeMs: Long,
        nowMs: Long,
        editTimeoutSeconds: Long,
        canDeleteAny: Boolean,
    ): DeleteScope {
        if (kind == DeleteChatKind.SAVED) return DeleteScope.SELF
        if (!sent) return if (kind == DeleteChatKind.CHANNEL && !canDeleteAny) DeleteScope.NONE else DeleteScope.SELF
        val fresh = own && nowMs - timeMs < editTimeoutSeconds * 1000
        return when (kind) {
            DeleteChatKind.DIALOG -> if (fresh) DeleteScope.ALL else DeleteScope.SELF
            DeleteChatKind.GROUP -> if (canDeleteAny || fresh) DeleteScope.ALL else DeleteScope.SELF
            DeleteChatKind.CHANNEL -> if (canDeleteAny) DeleteScope.ALL else DeleteScope.NONE
            DeleteChatKind.SAVED -> DeleteScope.SELF
        }
    }

    /**
     * The confirmation for [scopes] of one selection in a [kind] chat: `canDelete` — a non-empty
     * selection without [DeleteScope.NONE]; `showsForEveryone` (the "delete for everyone" switch)
     * — every scope [DeleteScope.ALL] and not a channel; `forEveryoneByDefault` — the switch
     * starts on whenever it is shown; `forcesForEveryone` — a channel where everything is ALL:
     * deleted for everyone only, without a switch.
     */
    fun summary(kind: DeleteChatKind, scopes: Map<Long, DeleteScope>): DeletePlan {
        val values = scopes.values
        val any = values.isNotEmpty()
        val allForEveryone = any && values.all { it == DeleteScope.ALL }
        val shows = allForEveryone && kind != DeleteChatKind.CHANNEL
        return DeletePlan(
            scopes = scopes,
            canDelete = any && values.none { it == DeleteScope.NONE },
            showsForEveryone = shows,
            forEveryoneByDefault = shows,
            forcesForEveryone = allForEveryone && kind == DeleteChatKind.CHANNEL,
        )
    }

    /**
     * [summary] of [messageIds] in [chatId] from the store [state] at [nowMs]: own messages by
     * [MaxState.me], times and senders of the stored messages; an id the store does not hold
     * counts as an unsent message. Rights by [ChatRights.of].
     */
    fun plan(state: MaxState, chatId: Long, messageIds: List<Long>, editTimeoutSeconds: Long, nowMs: Long): DeletePlan {
        val chat = state.chats[chatId]
        val kind = DeleteChatKind.of(chatId, chat)
        val canDeleteAny = ChatRights.of(chat, state.me).canDeleteAnyMessage
        val stored = state.messages[chatId].orEmpty().associateBy { it.id }
        val scopes = LinkedHashMap<Long, DeleteScope>()
        for (id in messageIds) {
            if (id in scopes) continue
            val m = stored[id]
            scopes[id] = scope(
                kind = kind,
                own = m != null && state.me != null && m.sender == state.me,
                sent = m != null,
                timeMs = m?.time ?: 0L,
                nowMs = nowMs,
                editTimeoutSeconds = editTimeoutSeconds,
                canDeleteAny = canDeleteAny,
            )
        }
        return summary(kind, scopes)
    }

    /** [plan] at the device clock. */
    fun plan(state: MaxState, chatId: Long, messageIds: List<Long>, editTimeoutSeconds: Long): DeletePlan =
        plan(state, chatId, messageIds, editTimeoutSeconds, epochMillis())
}

/** Order of a forwarded selection (shared Orbitle rule, fixture `selection/forward.json`). */
object ForwardOrder {
    /**
     * [messageIds] without repeats, oldest first by [times] (equal times by id as a number); ids
     * without a known time keep their given order after the others.
     */
    fun of(messageIds: List<Long>, times: Map<Long, Long>): List<Long> =
        messageIds.distinct().withIndex()
            .sortedWith(compareBy({ times[it.value] == null }, { times[it.value] ?: 0L }, { if (times[it.value] == null) 0L else it.value }, { it.index }))
            .map { it.value }
}
