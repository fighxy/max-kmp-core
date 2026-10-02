package com.max.core.protocol

/**
 * Wire opcodes of the Max (OneMe) binary protocol — the `opcode` field of the 10-byte header
 * (see [PacketHeader]).
 *
 * The table is the union of the two reference clients as reconciled in `docs/protocol.md` §E
 * (180 codes):
 * - kolibri: `kolibri-net/src/protocol/opcodes.rs` (168 constants),
 * - PyMax: `src/pymax/protocol/enums.py`, `Opcode` (179 members).
 *
 * Naming rules:
 * - the kolibri identifier is used when kolibri defines the code;
 * - codes that exist only in PyMax use the PyMax identifier and are marked "PyMax only";
 * - where PyMax uses a different identifier for the same code, it is given in a comment.
 *
 * Conflicts between the sources (same code, different names) are marked `CONFLICT` or
 * `PyMax: ...`; protocol.md is authoritative for values, and none of the sources disagree on a
 * numeric value. Opcodes whose payload schema is not known are marked `TODO: payload unknown`.
 *
 * Ordering and grouping follow the protocol.md §E summary tables, not numeric order.
 */
enum class Opcode(val value: Int) {
    // ── Session / service ───────────────────────────────────────────
    PING(1),
    DEBUG(2),
    RECONNECT(3),
    LOG(5),
    SESSION_INIT(6),
    // CONFLICT: kolibri CONTACTS_GET vs PyMax LOGIN2 (PyMax sends Login2Payload here); semantics unresolved, see protocol.md K11.
    // Sent as PyMax LOGIN2 by AuthApi.login2 / TokenLogin (single source: PyMax).
    CONTACTS_GET(8),

    // ── Profile / Auth ──────────────────────────────────────────────
    PROFILE(16),
    AUTH_REQUEST(17),
    AUTH(18),
    LOGIN(19),
    LOGOUT(20),
    SYNC(21),
    CONFIG(22),
    AUTH_CONFIRM(23),

    // ── Auth: 2FA / password ────────────────────────────────────────
    // 2FA: no payload builders in kolibri; PyMax-only schemas. 115 is implemented (AuthApi.checkPassword);
    // TODO: payload unknown for the rest unless noted
    AUTH_LOGIN_RESTORE_PASSWORD(101), // TODO: payload unknown
    AUTH_2FA_DETAILS(104), // Komet schema: {trackId} -> {password: {enabled, email, hint}}, TwoFactorApi.details
    EXTERNAL_CALLBACK(105), // Komet schema: {url} -> {botId, startParam}, BotsApi.externalCallback
    AUTH_VALIDATE_PASSWORD(107), // PyMax 2FA management, TwoFactorApi
    AUTH_VALIDATE_HINT(108), // PyMax 2FA management, TwoFactorApi
    AUTH_VERIFY_EMAIL(109), // PyMax 2FA management, TwoFactorApi
    AUTH_CHECK_EMAIL(110), // PyMax 2FA management, TwoFactorApi
    AUTH_SET_2FA(111), // PyMax 2FA management, TwoFactorApi
    AUTH_CREATE_TRACK(112), // PyMax 2FA management, TwoFactorApi
    AUTH_CHECK_PASSWORD(113), // PyMax 2FA management, TwoFactorApi
    AUTH_LOGIN_CHECK_PASSWORD(115), // PyMax CheckPasswordChallengePayload
    AUTH_LOGIN_PROFILE_DELETE(116), // TODO: payload unknown

    // ── Assets ─────────────────────────────────────────────────────
    PRESET_AVATARS(25),
    ASSETS_GET(26),
    ASSETS_UPDATE(27),
    ASSETS_GET_BY_IDS(28),
    ASSETS_ADD(29),
    ASSETS_REMOVE(259),
    ASSETS_MOVE(260),
    ASSETS_LIST_MODIFY(261),

    // ── Contacts ────────────────────────────────────────────────────
    SEARCH_FEEDBACK(31), // PyMax only (absent in kolibri)
    CONTACT_INFO(32),
    CONTACT_ADD(33),
    CONTACT_UPDATE(34),
    CONTACT_PRESENCE(35),
    CONTACT_LIST(36),
    CONTACT_SEARCH(37),
    CONTACT_MUTUAL(38),
    CONTACT_PHOTOS(39),
    CONTACT_SORT(40),
    CONTACT_VERIFY(42),
    REMOVE_CONTACT_PHOTO(43),
    CONTACT_INFO_BY_PHONE(46),

    // ── Chats ───────────────────────────────────────────────────────
    CHAT_INFO(48),
    CHAT_HISTORY(49),
    CHAT_MARK(50),
    CHAT_MEDIA(51),
    CHAT_DELETE(52),
    CHATS_LIST(53),
    CHAT_CLEAR(54),
    CHAT_UPDATE(55),
    CHAT_CHECK_LINK(56),
    CHAT_JOIN(57),
    CHAT_LEAVE(58),
    CHAT_MEMBERS(59),
    PUBLIC_SEARCH(60),
    CHAT_PERSONAL_CONFIG(61),
    CHAT_LIVESTREAM_INFO(62), // PyMax only (absent in kolibri)
    CHAT_CREATE(63),

    // ── Messages ────────────────────────────────────────────────────
    MSG_SEND(64),
    MSG_TYPING(65),
    MSG_DELETE(66),
    MSG_EDIT(67),
    CHAT_SEARCH(68),
    MSG_SHARE_PREVIEW(70),
    MSG_GET(71),
    MSG_SEARCH_TOUCH(72),
    MSG_SEARCH(73),
    MSG_GET_STAT(74),
    CHAT_SUBSCRIBE(75),
    MSG_GET_COMMENTS_INFO(91), // PyMax only (absent in kolibri)
    MSG_DELETE_RANGE(92),
    MSG_DELETE_USER_COMMENTS(94), // PyMax only (absent in kolibri)

    // ── Reactions ───────────────────────────────────────────────────
    MSG_REACTION(178),
    MSG_CANCEL_REACTION(179),
    MSG_GET_REACTIONS(180),
    MSG_GET_DETAILED_REACTIONS(181),
    CHAT_REACTIONS_SETTINGS_SET(257),
    REACTIONS_SETTINGS_GET_BY_CHAT_ID(258),

    // ── Calls / video chat ──────────────────────────────────────────
    VIDEO_CHAT_START(76),
    CHAT_MEMBERS_UPDATE(77),
    VIDEO_CHAT_START_ACTIVE(78),
    VIDEO_CHAT_HISTORY(79),
    VIDEO_CHAT_CREATE_JOIN_LINK(84),
    GET_INBOUND_CALLS(103),
    VIDEO_CHAT_DELETE_HISTORY(164), // kolibri only (absent in PyMax)
    // PyMax: VIDEO_CHAT_JOIN. Join-by-link vs generic join unresolved (protocol.md K13)
    VIDEO_CHAT_JOIN_BY_LINK(166), // TODO: payload unknown
    VIDEO_CHAT_MEMBERS(195),

    // ── Media / files ───────────────────────────────────────────────
    PHOTO_UPLOAD(80),
    STICKER_UPLOAD(81),
    VIDEO_UPLOAD(82),
    VIDEO_PLAY(83),
    CHAT_PIN_SET_VISIBILITY(86),
    FILE_UPLOAD(87),
    FILE_DOWNLOAD(88),
    LINK_INFO(89),
    AUDIO_PLAY(301),

    // ── Sessions / phone binding ────────────────────────────────────
    SESSIONS_INFO(96),
    SESSIONS_CLOSE(97),
    PHONE_BIND_REQUEST(98),
    PHONE_BIND_CONFIRM(99),

    // ── Bots / callbacks ────────────────────────────────────────────
    CHAT_COMPLAIN(117),
    MSG_SEND_CALLBACK(118),
    SUSPEND_BOT(119),
    CHAT_BOT_COMMANDS(144),
    BOT_INFO(145),

    // ── Location / mentions ─────────────────────────────────────────
    LOCATION_STOP(124),
    LOCATION_SEND(125), // PyMax only (absent in kolibri)
    LOCATION_REQUEST(126), // PyMax only (absent in kolibri)
    GET_LAST_MENTIONS(127),

    // ── Stickers ────────────────────────────────────────────────────
    STICKER_CREATE(193),
    STICKER_SUGGEST(194),

    // ── Notifications (server pushes) ───────────────────────────────
    NOTIF_MESSAGE(128),
    NOTIF_TYPING(129),
    NOTIF_MARK(130),
    NOTIF_CONTACT(131),
    NOTIF_PRESENCE(132),
    NOTIF_CONFIG(134),
    NOTIF_CHAT(135),
    NOTIF_ATTACH(136),
    NOTIF_CALL_START(137),
    NOTIF_CONTACT_SORT(139),
    NOTIF_MSG_DELETE_RANGE(140),
    NOTIF_MSG_DELETE(142),
    NOTIF_CALLBACK_ANSWER(143),
    NOTIF_LOCATION(147),
    NOTIF_LOCATION_REQUEST(148),
    NOTIF_ASSETS_UPDATE(150),
    NOTIF_DRAFT(152),
    NOTIF_DRAFT_DISCARD(153),
    NOTIF_MSG_DELAYED(154),
    NOTIF_MSG_REACTIONS_CHANGED(155),
    NOTIF_MSG_YOU_REACTED(156),
    NOTIF_PROFILE(159),
    NOTIF_FOLDERS(277),
    NOTIF_BANNERS(292),

    // ── Transcription ───────────────────────────────────────────────
    // PyMax: TRANSCRIBE_MEDIA. No call sites in either source
    AUDIO_TRANSCRIPTION(202), // {chatId, messageId, mediaId} -> {transcriptionStatus, transcription?} (KometTeam/Komet)
    // PyMax: NOTIF_TRANSCRIPTION. No call sites in either source
    TRANSCRIPTION_RESULT(293), // push {messageId, chatId?, mediaId?, transcriptionStatus, transcription}, maybe inside `message`

    // ── Misc ────────────────────────────────────────────────────────
    // PyMax: CALLS_TOKEN. No call sites in either source (protocol.md K12); CallsApi.requestCallsToken
    // sends `{}` (observed-not-ref, semantics unconfirmed)
    OK_TOKEN(158),
    WEB_APP_INIT_DATA(160),
    COMPLAIN(161),
    COMPLAIN_REASONS_GET(162),
    DRAFT_SAVE(176),
    DRAFT_DISCARD(177),
    CHAT_HIDE(196),
    CHAT_SEARCH_COMMON_PARTICIPANTS(198),
    PROFILE_DELETE(199),
    PROFILE_DELETE_TIME(200),
    ORG_INFO(256), // PyMax only (absent in kolibri)
    AUTH_QR_APPROVE(290),
    CHAT_SUGGEST(300),
    BANNERS_GET(302), // PyMax only (absent in kolibri)
    MSG_DELIVERY(303), // PyMax only (absent in kolibri)

    // ── QR auth ─────────────────────────────────────────────────────
    GET_QR(288), // PyMax only (absent in kolibri)
    GET_QR_STATUS(289), // PyMax only (absent in kolibri)
    LOGIN_BY_QR(291), // PyMax only (absent in kolibri)

    // ── Polls ───────────────────────────────────────────────────────
    SEND_VOTE(304),
    VOTERS_LIST_BY_ANSWER(305),
    GET_POLL_UPDATES(306),

    // ── Folders ─────────────────────────────────────────────────────
    FOLDERS_GET(272),
    FOLDERS_GET_BY_ID(273),
    FOLDERS_UPDATE(274),
    FOLDERS_REORDER(275),
    FOLDERS_DELETE(276),

    // ── Stories ─────────────────────────────────────────────────────
    // TODO: payload unknown — applies to every opcode in this group
    STORIES_LIST(208), // TODO: payload unknown
    // PyMax: STORIES_LIST_BY_OWNER_ID (kolibri name() string is also STORIES_LIST_BY_OWNER_ID)
    STORIES_LIST_BY_OWNER(209), // TODO: payload unknown
    // PyMax: STORIES_GET_BY_OWNER_ID (kolibri name() string is also STORIES_GET_BY_OWNER_ID)
    STORIES_GET_BY_OWNER(210), // TODO: payload unknown
    STORIES_GET_STATS(211), // TODO: payload unknown
    STORIES_GET_DETAILED_STATS(212), // TODO: payload unknown
    STORIES_REACT(213), // TODO: payload unknown
    STORIES_MARK(214), // TODO: payload unknown
    STORIES_SEND(215), // TODO: payload unknown
    NOTIF_STORIES_UPDATE(216), // TODO: payload unknown
    STORIES_EDIT(217), // TODO: payload unknown
    STORIES_DELETE(218), // TODO: payload unknown
    STORIES_GET_BY_STORY_ID(220); // TODO: payload unknown

    companion object {
        private val byValue: Map<Int, Opcode> = entries.associateBy { it.value }

        /** Returns the opcode for a raw wire [value], or `null` if it is not in the table. */
        fun fromValue(value: Int): Opcode? = byValue[value]

        /** Same as [fromValue] for the signed 16-bit header field ([PacketHeader.opcode]). */
        fun fromValue(value: Short): Opcode? = byValue[value.toInt() and 0xFFFF]

        /** Human-readable label for logs: the enum name, or `UNKNOWN(n)` for unmapped codes. */
        fun nameOf(value: Int): String = byValue[value]?.name ?: "UNKNOWN($value)"
    }
}
