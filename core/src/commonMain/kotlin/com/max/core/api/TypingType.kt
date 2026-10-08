package com.max.core.api

/**
 * `type` values of `MSG_TYPING` 65 (sent by [MessagesApi.sendTyping]) and of the `NOTIF_TYPING`
 * 129 push (`com.max.core.events.MaxEvent.Typing.type`).
 *
 * On the wire the `type` is a plain string: [MessagesApi.sendTyping] sends any string as given,
 * and `MaxEvent.Typing.type` keeps the raw incoming value. A missing, blank or unrecognised value
 * means [TEXT] ([effective]).
 */
object TypingType {
    /** Typing text. Also what a push without (or with an unrecognised) `type` means. */
    const val TEXT: String = "TEXT"

    /** Recording a voice message. */
    const val AUDIO: String = "AUDIO"

    /** Recording a video message (round video note). */
    const val VIDEO_MSG: String = "VIDEO_MSG"

    /** Sending a photo. */
    const val PHOTO: String = "PHOTO"

    /** Sending a video. */
    const val VIDEO: String = "VIDEO"

    /** Sending a file. */
    const val FILE: String = "FILE"

    /** Choosing a sticker. */
    const val STICKER: String = "STICKER"

    /** Every known value, [TEXT] first. */
    val all: List<String> = listOf(TEXT, AUDIO, VIDEO_MSG, PHOTO, VIDEO, FILE, STICKER)

    /** [type] when it is one of [all]; [TEXT] for `null`, blank or unrecognised values. */
    fun effective(type: String?): String = if (type != null && type in all) type else TEXT
}
