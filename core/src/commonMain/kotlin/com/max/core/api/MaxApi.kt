package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.auth.asRequestSink
import com.max.core.calls.CallsApi
import com.max.core.epochMillis
import com.max.core.session.SessionMachine

/**
 * Request/response API over a logged-in connection: [messages], [chats], [users], [account],
 * [twoFactor], [bots], [calls], [complaints] and [stories].
 *
 * Use it with a `SessionMachine` that logs in through `TokenLogin.hook` (the requests need an
 * authenticated session), or with any [RequestSink]. Events (`com.max.core.events`) and media
 * (`com.max.core.media`) have their own entry points; push is not covered.
 *
 * @param clock wall-clock milliseconds, used for `cid`, `from`, `mark`, `marker` defaults.
 * @param cids the session's `cid` generator; pass the same one to every other sender of the
 *   session (e.g. `MediaApi`) so text and media messages never share an id.
 */
class MaxApi(sink: RequestSink, clock: () -> Long, val cids: ClientIdGenerator) {
    constructor(sink: RequestSink, clock: () -> Long = ::epochMillis) : this(sink, clock, ClientIdGenerator(clock))

    /** Over [session]'s `request` (and `sendWithoutReply` for fire-and-forget frames such as typing). */
    constructor(session: SessionMachine, clock: () -> Long = ::epochMillis) : this(session, clock, ClientIdGenerator(clock))

    /** Over [session]'s `request` and `sendWithoutReply`, drawing `cid`s from [cids]. */
    constructor(session: SessionMachine, clock: () -> Long, cids: ClientIdGenerator) :
        this(session.asRequestSink(), clock, cids)

    val messages: MessagesApi = MessagesApi(sink, clock, cids)
    val chats: ChatsApi = ChatsApi(sink, clock, messages)
    val users: UsersApi = UsersApi(sink)
    val account: AccountApi = AccountApi(sink)
    val twoFactor: TwoFactorApi = TwoFactorApi(sink)
    val bots: BotsApi = BotsApi(sink, clock)
    val calls: CallsApi = CallsApi(sink)
    val assets: AssetsApi = AssetsApi(sink)
    val search: SearchApi = SearchApi(sink)
    val complaints: ComplaintsApi = ComplaintsApi(sink)
    val stories: StoriesApi = StoriesApi(sink, clock)
}
