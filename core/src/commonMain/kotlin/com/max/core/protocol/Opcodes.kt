package com.max.core.protocol

/**
 * Protocol opcodes. Seed list aligned with kolibri / PyMax; expand as needed.
 * TODO: full table + docs per opcode.
 */
object Opcodes {
    const val PING: UShort = 1u
    const val SESSION_INIT: UShort = 6u
    const val AUTH_REQUEST: UShort = 17u
    const val AUTH: UShort = 18u
    const val LOGIN: UShort = 19u
    const val SYNC: UShort = 21u
    const val CHATS_LIST: UShort = 53u
    const val MSG_SEND: UShort = 64u
    const val NOTIF_MESSAGE: UShort = 128u
}
