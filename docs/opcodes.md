# Проблемы опкодов

> Инвентарь расхождений kolibri ∪ PyMax и дыр payload в ядре.
> Таблица значений и имён — [protocol.md §E](protocol.md); открытые вопросы K11–K13 — [protocol.md §K](protocol.md).
> Этот файл не задаёт схемы провода и не выдумывает payload.

Константа в `Opcodes.kt` ≠ клиентский API. Метод появляется только с вектором из kolibri или PyMax, либо явно помечен как **observed-not-ref**.

## Конфликты имён и семантики

| code | имя в ядре | другое имя | проблема |
|------|------------|------------|----------|
| 8 | `CONTACTS_GET` | PyMax `LOGIN2` | [K11](protocol.md): семантика не снята; payload unknown |
| 158 | `OK_TOKEN` | PyMax `CALLS_TOKEN` | [K12](protocol.md): нет call site в kolibri и PyMax |
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | PyMax `VIDEO_CHAT_JOIN` | [K13](protocol.md): join-by-link vs generic join не снято; payload unknown |
| cmd `2` | — | PyMax `EVENT` / kolibri `NOT_FOUND` | не opcode; [protocol.md §B.3](protocol.md) |

## Calls — что уже в ядре

Реализовано:

| code | имя | слой | источник payload |
|------|-----|------|------------------|
| 137 | `NOTIF_CALL_START` | `MaxEvent.CallStart` + `ConversationParams.decode` | kolibri `kolibri-net/src/calls/` (vcp: `<rawLen>:<base64(LZ4-block(JSON))>`). `callerId` + строковый `conversationId` обязательны; битый `vcp` оставляет `params = null` |
| 158 | `OK_TOKEN` | `CallsApi.requestCallsToken` / `MaxApi.calls` | **request** — пустой map `{}` (msgpack `80`). **reply** `{token, token_lifetime_ts?, token_refresh_ts?}` — observed-not-ref (заметки third-party, не kolibri/PyMax) |

Не реализовано — в обоих референсах нет payload-builder / call site, либо схема не согласована:

| code | имя | почему не в API |
|------|-----|-----------------|
| 76 | `VIDEO_CHAT_START` | нет вектора в этом слайсе |
| 77 | `CHAT_MEMBERS_UPDATE` | не calls-control в нашем смысле |
| 78 | `VIDEO_CHAT_START_ACTIVE` | нет payload-builder в kolibri и PyMax |
| 79 | `VIDEO_CHAT_HISTORY` | нет вектора в этом слайсе |
| 84 | `VIDEO_CHAT_CREATE_JOIN_LINK` | нет вектора в этом слайсе |
| 103 | `GET_INBOUND_CALLS` | нет вектора в этом слайсе |
| 164 | `VIDEO_CHAT_DELETE_HISTORY` | только kolibri (в PyMax нет) |
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | payload unknown, K13 |
| 195 | `VIDEO_CHAT_MEMBERS` | нет вектора в этом слайсе |

ws2-сигналинг и WebRTC остаются на хосте. `ConversationParams.ws2Url` / `ws2UrlFromEndpoint` только собирают URL.

Сторонние заметки (не референс) иногда переименовывают `78` в `CALL_START` и вводят коды вроде `69 CALL_EDIT` / `83 CALL_LEAVE`. Эти имена и схемы **не** из kolibri/PyMax; в ядро они не переносятся. `83` в таблице ядра — `VIDEO_PLAY` (медиа), не leave.

## `TODO: payload unknown`

Группы, у которых в kolibri и PyMax нет builder'а (или он есть только у одной стороны и не сверен):

- `8` `CONTACTS_GET` (K11)
- 2FA / password: `101`, `104`, `105`, `107`, `108`, `109`, `110`, `111`, `112`, `113`, `115`, `116`
- `166` `VIDEO_CHAT_JOIN_BY_LINK` (K13)
- транскрипция: `202` `AUDIO_TRANSCRIPTION`, `293` `TRANSCRIPTION_RESULT` — call sites нет ни в одном референсе
- stories: `208`–`218`, `220`

`158` больше не «payload unknown» для **request** (пустой map). Reply по-прежнему observed-not-ref.

## Только в одном источнике

Присутствуют в `Opcodes.kt` как union-таблица, без отдельного API, пока нет второго подтверждения или вектора:

**Только PyMax:** `31` `SEARCH_FEEDBACK`, `62` `CHAT_LIVESTREAM_INFO`, `91` `MSG_GET_COMMENTS_INFO`, `94` `MSG_DELETE_USER_COMMENTS`, `125` `LOCATION_SEND`, `126` `LOCATION_REQUEST`, `256` `ORG_INFO`, `288` `GET_QR`, `289` `GET_QR_STATUS`, `291` `LOGIN_BY_QR`, `302` `BANNERS_GET`, `303` `MSG_DELIVERY`.

**Только kolibri:** `164` `VIDEO_CHAT_DELETE_HISTORY`.

QR-вход `288`/`289`/`291` описан у PyMax как web-клиент; с телефона реализован только `AUTH_QR_APPROVE` `290`.
