package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.calls.CallsApi
import com.max.core.epochMillis
import com.max.core.session.SessionMachine

/**
 * Request/response API over a logged-in connection: [messages], [chats], [users], [account],
 * [twoFactor] and [calls].
 *
 * Use it with a `SessionMachine` that logs in through `TokenLogin.hook` (the requests need an
 * authenticated session), or with any [RequestSink]. Events (`com.max.core.events`) and media
 * (`com.max.core.media`) have their own entry points; push is not covered.
 *
 * @param clock wall-clock milliseconds, used for `cid`, `from`, `mark`, `marker` defaults.
 */
class MaxApi(sink: RequestSink, clock: () -> Long = ::epochMillis) {
    /** Over [session]'s `request`. */
    constructor(session: SessionMachine, clock: () -> Long = ::epochMillis) :
        this(RequestSink { opcode, payload -> session.request(opcode, payload) }, clock)

    val messages: MessagesApi = MessagesApi(sink, clock)
    val chats: ChatsApi = ChatsApi(sink, clock)
    val users: UsersApi = UsersApi(sink)
    val account: AccountApi = AccountApi(sink)
    val twoFactor: TwoFactorApi = TwoFactorApi(sink)
    val calls: CallsApi = CallsApi(sink)
}
