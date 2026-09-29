package com.max.ios

import com.max.core.ErrorKind
import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.auth.CodeRequestType
import com.max.core.auth.VerifyResult
import com.max.core.events.MaxEvent
import com.max.core.toMaxError
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import com.max.shared.Watcher
import com.max.shared.ClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Swift entry of the network core.
 *
 * The framework exports only the types in this file. [MaxClient] stays inside it: the device
 * profile is still the Android Pixel 8 profile, and the login token stays in the Keychain store
 * `com.max.kmp.<namespace>`. Callbacks run on the core dispatcher, not the main thread.
 * A null error kind means success.
 */
class MaxIosClient(namespace: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = MaxClient(MaxClientConfig(namespace = namespace), scope = scope)
    private val watches = mutableListOf<Watcher>()

    fun phaseName(): String = phaseOf(client.state.value)

    fun currentUserId(): String = client.userId.value?.toString().orEmpty()

    fun hasStoredToken(): Boolean = client.hasStoredToken

    fun start(onResult: (String?, String?, String?) -> Unit) {
        launch(onResult) { phaseOf(client.start()) }
    }

    fun requestCode(phone: String, resend: Boolean, onResult: (IosCodeRequest?, String?, String?) -> Unit) {
        launchValue(onResult) {
            val type = if (resend) CodeRequestType.RESEND else CodeRequestType.START_AUTH
            val code = client.requestCode(phone, type)
            IosCodeRequest(code.token, code.codeLength ?: 0)
        }
    }

    fun verifyCode(token: String, code: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        launchValue(onResult) {
            when (val result = client.verifyCode(token, code)) {
                is VerifyResult.LoggedIn -> loggedInStep()
                is VerifyResult.PasswordRequired -> IosAuthStep("password", result.trackId, result.hint.orEmpty(), "", "")
                is VerifyResult.RegistrationRequired -> IosAuthStep("register", "", "", result.registerToken, "")
            }
        }
    }

    fun checkPassword(trackId: String, password: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        launchValue(onResult) {
            client.checkPassword(trackId, password)
            loggedInStep()
        }
    }

    fun register(registerToken: String, firstName: String, lastName: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        launchValue(onResult) {
            client.register(registerToken, firstName, lastName.takeIf { it.isNotBlank() })
            loggedInStep()
        }
    }

    fun logout(onResult: (String?, String?) -> Unit) {
        scope.launch {
            try {
                client.logout()
                onResult(null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val (kind, key) = classify(t)
                onResult(kind, key)
            }
        }
    }

    fun loadChats(onResult: (List<IosChat>, String?, String?) -> Unit) {
        launchList(onResult) {
            client.loadChats()
            client.store.state.value.chats.values.map(::chatSnapshot)
        }
    }

    fun loadChat(chatId: String, onResult: (IosChat?, String?, String?) -> Unit) {
        launchValue(onResult) { chatSnapshot(client.api.chats.getChat(chatId.toLong())) }
    }

    fun loadHistory(chatId: String, beforeMs: Long, limit: Int, onResult: (List<IosMessage>, String?, String?) -> Unit) {
        launchList(onResult) {
            val history = client.loadHistory(chatId.toLong(), from = beforeMs.takeIf { it > 0 }, backward = limit.coerceIn(1, 100))
            history.messages.map { messageSnapshot(it, chatId) }
        }
    }

    fun sendText(chatId: String, text: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        launchValue(onResult) { messageSnapshot(client.sendText(chatId.toLong(), text), chatId) }
    }

    fun markRead(chatId: String, messageId: String, onResult: (String?, String?) -> Unit) {
        scope.launch {
            try {
                client.api.messages.markRead(chatId.toLong(), messageId.toLong())
                onResult(null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val (kind, key) = classify(t)
                onResult(kind, key)
            }
        }
    }

    fun watchState(onEach: (String) -> Unit): IosWatch = track(client.watchState { onEach(phaseOf(it)) })

    fun watchEvents(onEach: (IosEvent) -> Unit): IosWatch = track(client.watchEvents { event ->
        flatten(event).forEach(onEach)
    })

    /** Disconnects and releases the client. It cannot be used afterwards. */
    fun close(onDone: () -> Unit) {
        watches.forEach { it.cancel() }
        watches.clear()
        scope.launch {
            try {
                client.close()
            } finally {
                onDone()
                scope.cancel()
            }
        }
    }

    private fun loggedInStep(): IosAuthStep =
        IosAuthStep("loggedIn", "", "", "", client.userId.value?.toString().orEmpty())

    private fun track(watcher: Watcher): IosWatch {
        watches += watcher
        return IosWatch(watcher)
    }

    private fun launch(onResult: (String?, String?, String?) -> Unit, body: suspend () -> String) {
        scope.launch {
            try {
                onResult(body(), null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val (kind, key) = classify(t)
                onResult(null, kind, key)
            }
        }
    }

    private fun <T> launchValue(onResult: (T?, String?, String?) -> Unit, body: suspend () -> T) {
        scope.launch {
            try {
                onResult(body(), null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val (kind, key) = classify(t)
                onResult(null, kind, key)
            }
        }
    }

    private fun <T> launchList(onResult: (List<T>, String?, String?) -> Unit, body: suspend () -> List<T>) {
        scope.launch {
            try {
                onResult(body(), null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val (kind, key) = classify(t)
                onResult(emptyList(), kind, key)
            }
        }
    }
}

/** Cancels one [MaxIosClient.watchState] or [MaxIosClient.watchEvents] subscription. */
class IosWatch internal constructor(private val watcher: Watcher) {
    fun cancel() = watcher.cancel()
}

/** `AUTH_REQUEST` reply. [codeLength] is 0 when the server omitted it. */
class IosCodeRequest(val token: String, val codeLength: Int)

/**
 * Next auth step. [kind] is `loggedIn`, `password` or `register`.
 * Unused strings are empty.
 */
class IosAuthStep(
    val kind: String,
    val trackId: String,
    val hint: String,
    val registerToken: String,
    val userId: String,
)

/** One chat, ids as decimal strings. [updatedAtMs] and message times are Unix milliseconds. */
class IosChat(
    val id: String,
    val title: String,
    val type: String,
    val lastMessageId: String,
    val lastText: String,
    val updatedAtMs: Long,
    val unread: Int,
)

/** One message. [authorId] is empty when the server omitted the sender. */
class IosMessage(
    val id: String,
    val chatId: String,
    val authorId: String,
    val text: String,
    val timeMs: Long,
)

/**
 * A push the app stores or shows.
 *
 * [kind] is `message`, `edited`, `deleted`, `chat`, `typing` or `read`.
 * [unread] is `-1` when this event does not change the unread counter.
 * Call, presence and unknown pushes are not forwarded.
 */
class IosEvent(
    val kind: String,
    val chatId: String,
    val messageId: String,
    val authorId: String,
    val text: String,
    val title: String,
    val chatType: String,
    val timeMs: Long,
    val unread: Int,
)

private fun phaseOf(state: ClientState): String = when (state) {
    ClientState.Idle -> "idle"
    ClientState.Connecting -> "connecting"
    is ClientState.AwaitingAuth -> "awaitingAuth"
    is ClientState.Ready -> "ready"
    is ClientState.Reconnecting -> "reconnecting"
    is ClientState.TokenRejected -> "tokenRejected"
    is ClientState.Failed -> "failed"
}

private fun classify(t: Throwable): Pair<String, String?> {
    val error = t.toMaxError()
    val kind = if (error.kind == ErrorKind.UNKNOWN && error.message.contains("not found", ignoreCase = true)) {
        "NOT_FOUND"
    } else {
        error.kind.name
    }
    return kind to error.errorKey
}

private fun chatSnapshot(chat: Chat): IosChat {
    val last = chat.lastMessage
    val updated = when {
        chat.lastEventTime > 0 -> chat.lastEventTime
        last != null -> last.time
        else -> 0L
    }
    return IosChat(
        id = chat.id.toString(),
        title = chat.title.orEmpty(),
        type = chat.type,
        lastMessageId = last?.id?.toString().orEmpty(),
        lastText = last?.text.orEmpty(),
        updatedAtMs = updated,
        unread = chat.newMessages,
    )
}

private fun messageSnapshot(message: MaxMessage, fallbackChatId: String): IosMessage = IosMessage(
    id = message.id.toString(),
    chatId = message.chatId?.toString() ?: fallbackChatId,
    authorId = message.sender?.toString().orEmpty(),
    text = message.text,
    timeMs = message.time,
)

private fun flatten(event: MaxEvent): List<IosEvent> = when (event) {
    is MaxEvent.NewMessage -> listOf(messageEvent("message", event.message))
    is MaxEvent.MessageEdited -> listOf(messageEvent("edited", event.message))
    is MaxEvent.MessagesDeleted -> event.messageIds.map { id ->
        iosEvent(kind = "deleted", chatId = event.chatId.toString(), messageId = id.toString())
    }
    is MaxEvent.ChatUpdated -> listOf(chatEvent(event.chat))
    is MaxEvent.Typing -> listOf(iosEvent(kind = "typing", chatId = event.chatId.toString(), authorId = event.userId.toString()))
    is MaxEvent.MessageRead -> listOf(
        iosEvent(
            kind = "read",
            chatId = event.chatId.toString(),
            authorId = event.userId.toString(),
            timeMs = event.mark,
            unread = if (event.setAsUnread) 1 else 0,
        ),
    )
    else -> emptyList()
}

private fun messageEvent(kind: String, message: MaxMessage): IosEvent = iosEvent(
    kind = kind,
    chatId = message.chatId?.toString().orEmpty(),
    messageId = message.id.toString(),
    authorId = message.sender?.toString().orEmpty(),
    text = message.text,
    timeMs = message.time,
)

private fun chatEvent(chat: Chat): IosEvent {
    val snap = chatSnapshot(chat)
    return iosEvent(
        kind = "chat",
        chatId = snap.id,
        messageId = snap.lastMessageId,
        text = snap.lastText,
        title = snap.title,
        chatType = snap.type,
        timeMs = snap.updatedAtMs,
        unread = snap.unread,
    )
}

private fun iosEvent(
    kind: String,
    chatId: String = "",
    messageId: String = "",
    authorId: String = "",
    text: String = "",
    title: String = "",
    chatType: String = "",
    timeMs: Long = 0,
    unread: Int = -1,
): IosEvent = IosEvent(kind, chatId, messageId, authorId, text, title, chatType, timeMs, unread)
