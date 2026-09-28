package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.session.SessionMachine

/**
 * Request/response API over a logged-in connection: [messages] and [chats].
 *
 * Use it with a `SessionMachine` that logs in through `TokenLogin.hook` (the requests need an
 * authenticated session), or with any [RequestSink]. Events/notifications, media, calls and push
 * are outside this package.
 *
 * @param clock wall-clock milliseconds, used for `cid`, `from`, `mark`, `marker` defaults.
 */
class MaxApi(sink: RequestSink, clock: () -> Long = ::epochMillis) {
    /** Over [session]'s `request`. */
    constructor(session: SessionMachine, clock: () -> Long = ::epochMillis) :
        this(RequestSink { opcode, payload -> session.request(opcode, payload) }, clock)

    val messages: MessagesApi = MessagesApi(sink, clock)
    val chats: ChatsApi = ChatsApi(sink, clock)
}
