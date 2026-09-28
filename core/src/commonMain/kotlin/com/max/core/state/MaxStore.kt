package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.ChatHistory
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PresenceInfo
import com.max.core.auth.LoginResult
import com.max.core.epochMillis
import com.max.core.events.MaxEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Thread-safe, in-memory holder of [MaxState]. Every mutation is an atomic
 * `MutableStateFlow.update` with a [StateReducer] function, so pushes and API results can be
 * applied from any coroutine.
 *
 * Feed it with [applyLogin] (or `TokenLogin.result`), [putChats] / [putHistory] after API calls,
 * and [apply] for each [MaxEvent] — `com.max.core.events.EventRouter` does the latter for a
 * session. Nothing is persisted.
 *
 * @param messageLimit messages kept per chat (oldest dropped first); `0` keeps everything.
 * @param clock local wall clock in ms, used for typing timestamps.
 */
class MaxStore(
    val messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
    private val clock: () -> Long = ::epochMillis,
    initial: MaxState = MaxState(),
) {
    private val _state = MutableStateFlow(initial)

    /** The current snapshot; collect it to observe every change. */
    val state: StateFlow<MaxState> = _state.asStateFlow()

    /** Chat list, latest activity first ([MaxState.chatList]). */
    val chatList: Flow<List<Chat>> get() = state.map { it.chatList }.distinctUntilChanged()

    /** Messages of one chat. */
    fun messages(chatId: Long): Flow<List<MaxMessage>> = state.map { it.messagesOf(chatId) }.distinctUntilChanged()

    /** Presence of one user (`null` until known). */
    fun presence(userId: Long): Flow<PresenceInfo?> = state.map { it.presence[userId] }.distinctUntilChanged()

    /** Applies one push ([StateReducer.reduce]). */
    fun apply(event: MaxEvent) = _state.update { StateReducer.reduce(it, event, clock(), messageLimit) }

    /** Seeds from a `LOGIN` reply ([StateReducer.login]). */
    fun applyLogin(result: LoginResult) = _state.update { StateReducer.login(it, result, messageLimit) }

    fun putChats(chats: List<Chat>) = _state.update { StateReducer.putChats(it, chats) }

    fun putUsers(users: List<MaxUser>) = _state.update { StateReducer.putUsers(it, users) }

    fun putMessages(chatId: Long, messages: List<MaxMessage>) = _state.update { StateReducer.putMessages(it, chatId, messages, messageLimit) }

    /** A `CHAT_HISTORY` result: its messages and, when requested with `getChat`, its chat. */
    fun putHistory(chatId: Long, history: ChatHistory) = _state.update { s ->
        val withChat = history.chat?.let { StateReducer.putChat(s, it) } ?: s
        StateReducer.putMessages(withChat, chatId, history.messages, messageLimit)
    }

    fun removeChat(chatId: Long) = _state.update { StateReducer.removeChat(it, chatId) }

    /** Drops everything (e.g. on logout). */
    fun clear() {
        _state.value = MaxState()
    }

    companion object {
        const val DEFAULT_MESSAGE_LIMIT: Int = 500
    }
}
