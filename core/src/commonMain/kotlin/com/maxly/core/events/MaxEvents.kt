package com.maxly.core.events

import com.maxly.core.session.SessionMachine
import com.maxly.core.transport.TlsTransport
import com.maxly.core.transport.TransportPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Typed events over a push stream.
 *
 * The transport already routes every packet that is not a reply to a request into
 * `MaxTransport.pushes` (like kolibri's dispatcher broadcast channel); `SessionMachine.pushes`
 * exposes it across reconnects. This class only parses that stream with [EventParser].
 *
 * The underlying flow is hot and does not replay: collect before `connect()` to see the pushes
 * the server sends right after `LOGIN`. Neither reference acknowledges notifications, so nothing
 * is sent back.
 */
class MaxEvents(private val pushes: Flow<TransportPacket>) {
    constructor(session: SessionMachine) : this(session.pushes)
    constructor(transport: TlsTransport) : this(transport.pushes)

    /** Every push as a [MaxEvent] ([MaxEvent.Unknown] for the ones without a typed model). */
    val all: Flow<MaxEvent> = pushes.map(EventParser::parse)

    val messages: Flow<MaxEvent.NewMessage> get() = all.filterIsInstance()
    val edits: Flow<MaxEvent.MessageEdited> get() = all.filterIsInstance()
    val deletions: Flow<MaxEvent.MessagesDeleted> get() = all.filterIsInstance()
    val chatUpdates: Flow<MaxEvent.ChatUpdated> get() = all.filterIsInstance()
    val typing: Flow<MaxEvent.Typing> get() = all.filterIsInstance()
    val reads: Flow<MaxEvent.MessageRead> get() = all.filterIsInstance()
    val presence: Flow<MaxEvent.Presence> get() = all.filterIsInstance()
    val reactions: Flow<MaxEvent.ReactionsChanged> get() = all.filterIsInstance()
    val attachmentsReady: Flow<MaxEvent.AttachmentReady> get() = all.filterIsInstance()
    val calls: Flow<MaxEvent.CallStart> get() = all.filterIsInstance()

    /** Events of type [T]. */
    inline fun <reified T : MaxEvent> of(): Flow<T> = all.filterIsInstance()
}
