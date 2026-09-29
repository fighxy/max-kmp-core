@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSLock

/**
 * Swift entry of the network core.
 *
 * The framework exports only the types in this file. [MaxClient] stays inside it: the device
 * profile is still the Android Pixel 8 profile, and the login token stays in the Keychain store
 * `com.max.kmp.<namespace>`. Callbacks run on the core dispatcher, not the main thread.
 *
 * Error boundary: nothing here throws into Swift. Every asynchronous operation calls its callback
 * exactly once, with a null error kind on success or an [com.max.core.ErrorKind] name (plus the
 * server error key, if any) on failure, including cancellation (`CANCELLED`) and calls made after
 * [close]. The [MaxClient] is created on first use, so a Keychain failure while loading the
 * device identity becomes an error kind of the failing call instead of an exception in the
 * initializer. The synchronous getters fall back to `failed` / empty / `false`. Exceptions thrown
 * by a callback itself are dropped and never abort the process.
 */
class MaxIosClient internal constructor(
    private val scope: CoroutineScope,
    private val factory: (CoroutineScope) -> MaxClient,
) {
    constructor(namespace: String) : this(newScope(), { scope -> MaxClient(MaxClientConfig(namespace = namespace), scope = scope) })

    private val clientLock = NSLock()
    private var created: MaxClient? = null
    private val watches = mutableListOf<Watcher>()

    /** The client, created on the first call; throws what the constructor threw (retried next time). */
    private fun client(): MaxClient = clientLock.locked { created ?: factory(scope).also { created = it } }

    fun phaseName(): String = attempt("failed") { phaseOf(client().state.value) }

    fun currentUserId(): String = attempt("") { client().userId.value?.toString().orEmpty() }

    fun hasStoredToken(): Boolean = attempt(false) { client().hasStoredToken }

    fun start(onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }) { phaseOf(it.start()) }
    }

    fun requestCode(phone: String, resend: Boolean, onResult: (IosCodeRequest?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val type = if (resend) CodeRequestType.RESEND else CodeRequestType.START_AUTH
            val code = c.requestCode(phone, type)
            IosCodeRequest(code.token, code.codeLength ?: 0)
        }
    }

    fun verifyCode(token: String, code: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            when (val result = c.verifyCode(token, code)) {
                is VerifyResult.LoggedIn -> loggedInStep(c)
                is VerifyResult.PasswordRequired -> IosAuthStep("password", result.trackId, result.hint.orEmpty(), "", "")
                is VerifyResult.RegistrationRequired -> IosAuthStep("register", "", "", result.registerToken, "")
            }
        }
    }

    fun checkPassword(trackId: String, password: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.checkPassword(trackId, password)
            loggedInStep(c)
        }
    }

    fun register(registerToken: String, firstName: String, lastName: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.register(registerToken, firstName, lastName.takeIf { it.isNotBlank() })
            loggedInStep(c)
        }
    }

    fun logout(onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.logout() }
    }

    fun loadChats(onResult: (List<IosChat>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.loadChats()
            c.store.state.value.chats.values.map(::chatSnapshot)
        }
    }

    fun loadChat(chatId: String, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c -> chatSnapshot(c.api.chats.getChat(parseId(chatId))) }
    }

    fun loadHistory(chatId: String, beforeMs: Long, limit: Int, onResult: (List<IosMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val history = c.loadHistory(parseId(chatId), from = beforeMs.takeIf { it > 0 }, backward = limit.coerceIn(1, 100))
            history.messages.map { messageSnapshot(it, chatId) }
        }
    }

    fun sendText(chatId: String, text: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c -> messageSnapshot(c.sendText(parseId(chatId), text), chatId) }
    }

    fun markRead(chatId: String, messageId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.api.messages.markRead(parseId(chatId), parseId(messageId)) }
    }

    /** A dead [IosWatch] (no callbacks) when the client cannot be created. */
    fun watchState(onEach: (String) -> Unit): IosWatch = watch { c -> c.watchState { guarded { onEach(phaseOf(it)) } } }

    fun watchEvents(onEach: (IosEvent) -> Unit): IosWatch = watch { c ->
        c.watchEvents { event -> guarded { flatten(event).forEach(onEach) } }
    }

    /** Disconnects and releases the client. It cannot be used afterwards; [onDone] is always called once. */
    fun close(onDone: () -> Unit) {
        clientLock.locked {
            watches.forEach { it.cancel() }
            watches.clear()
        }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                clientLock.locked { created }?.close()
            } catch (t: Throwable) {
                // closing is best effort; the scope is cancelled below either way
            } finally {
                guarded(onDone)
                scope.cancel()
            }
        }
    }

    private fun loggedInStep(c: MaxClient): IosAuthStep =
        IosAuthStep("loggedIn", "", "", "", c.userId.value?.toString().orEmpty())

    private fun watch(start: (MaxClient) -> Watcher): IosWatch {
        val watcher = try {
            start(client())
        } catch (t: Throwable) {
            return IosWatch(null)
        }
        clientLock.locked { watches += watcher }
        return IosWatch(watcher)
    }

    /**
     * Runs [body] and calls [onResult] exactly once: `(value, null, null)` on success,
     * `(fallback, kind, errorKey)` on any failure. ATOMIC start: the body runs (and reports
     * `CANCELLED`) even when the scope is already cancelled by [close].
     */
    private fun <T> perform(onResult: (T, String?, String?) -> Unit, fallback: () -> T, body: suspend (MaxClient) -> T) {
        scope.launch(start = CoroutineStart.ATOMIC) {
            val outcome = try {
                Result.success(body(client()))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            outcome.fold(
                onSuccess = { guarded { onResult(it, null, null) } },
                onFailure = { t ->
                    val (kind, key) = classify(t)
                    guarded { onResult(fallback(), kind, key) }
                },
            )
        }
    }

    private fun runUnit(onResult: (String?, String?) -> Unit, body: suspend (MaxClient) -> Unit) {
        perform<Unit>({ _, kind, key -> onResult(kind, key) }, { }) { body(it) }
    }
}

/** Scope of a [MaxIosClient]: exceptions that escape anyway are dropped instead of aborting the app. */
private fun newScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

private inline fun <T> attempt(fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    fallback
}

/** Runs a Swift callback; whatever it throws stays here. */
private inline fun guarded(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        // a callback must not break the core coroutine that calls it
    }
}

/** Decimal id from Swift; a malformed one becomes an [IllegalArgumentException] (error kind `UNKNOWN`). */
private fun parseId(value: String): Long =
    value.toLongOrNull() ?: throw IllegalArgumentException("not a numeric id: \"$value\"")

/** Cancels one [MaxIosClient.watchState] or [MaxIosClient.watchEvents] subscription. */
class IosWatch internal constructor(private val watcher: Watcher?) {
    fun cancel() {
        watcher?.cancel()
    }
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
