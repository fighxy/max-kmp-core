package com.max.core.session

/**
 * Session state machine: handshake (opcode 6), keepalive ping, reconnect + backoff.
 * Mirrors kolibri-net `Session`.
 *
 * TODO: implement states and transitions.
 */
enum class SessionState {
    Disconnected,
    Connecting,
    Connected,
    Online,
}

class SessionMachine(
    // TODO: config, transport
) {
    var state: SessionState = SessionState.Disconnected
        private set

    suspend fun connect(): Unit = error("TODO: SessionMachine.connect")
    suspend fun disconnect(): Unit = error("TODO: SessionMachine.disconnect")
}
