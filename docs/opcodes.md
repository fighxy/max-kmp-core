# Проблемы опкодов

> Инвентарь расхождений kolibri ∪ PyMax и дыр payload в ядре.
> Таблица значений и имён — [protocol.md §E](protocol.md); открытые вопросы K11–K13 — [protocol.md §K](protocol.md).
> Этот файл не задаёт схемы провода и не выдумывает payload.

Константа в `Opcodes.kt` ≠ клиентский API. Метод появляется только с вектором из kolibri или PyMax, либо явно помечен как **observed-not-ref**.

## Конфликты имён и семантики

| code | имя в ядре | другое имя | проблема |
|------|------------|------------|----------|
| 8 | `CONTACTS_GET` | PyMax `LOGIN2` | ядро шлёт форму PyMax `LOGIN2` `{needProfile, contactsSync, configHash}` через `AuthApi.login2`; [K11](protocol.md) остаётся: kolibri называет опкод `CONTACTS_GET` и сам его не шлёт |
| 158 | `OK_TOKEN` | PyMax `CALLS_TOKEN` | [K12](protocol.md): нет call site в kolibri и PyMax |
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | PyMax `VIDEO_CHAT_JOIN` | [K13](protocol.md): join-by-link vs generic join не снято; payload unknown |
| cmd `2` | — | PyMax `EVENT` / kolibri `NOT_FOUND` | не opcode; [protocol.md §B.3](protocol.md) |

## Calls — что уже в ядре

Реализовано:

| code | имя | слой | источник payload |
|------|-----|------|------------------|
| 137 | `NOTIF_CALL_START` | `MaxEvent.CallStart` + `ConversationParams.decode` | kolibri `kolibri-net/src/calls/` (vcp: `<rawLen>:<base64(LZ4-block(JSON))>`). `callerId` + строковый `conversationId` обязательны; битый `vcp` оставляет `params = null` |
| 79 | `VIDEO_CHAT_HISTORY` | `CallsApi.history` → `CallLogEntry` | KometTeam/Komet `CallsModule.fetchHistory`: **request** `{}`; **reply** `{history: [{message: {id, time, sender, attaches: [{_type: "CALL", contactIds?, duration, hangupType, callType?}]}}]}`. Пункты без `CALL`-вложения пропускаются |
| 158 | `OK_TOKEN` | `CallsApi.requestCallsToken` / `MaxApi.calls` | **request** — пустой map `{}` (msgpack `80`). **reply** `{token, token_lifetime_ts?, token_refresh_ts?}` — observed-not-ref (заметки third-party, не kolibri/PyMax) |

Не реализовано — в обоих референсах нет payload-builder / call site, либо схема не согласована:

| code | имя | почему не в API |
|------|-----|-----------------|
| 76 | `VIDEO_CHAT_START` | нет вектора в этом слайсе |
| 77 | `CHAT_MEMBERS_UPDATE` | не calls-control в нашем смысле |
| 78 | `VIDEO_CHAT_START_ACTIVE` | нет payload-builder в kolibri и PyMax |
| 84 | `VIDEO_CHAT_CREATE_JOIN_LINK` | нет вектора в этом слайсе |
| 103 | `GET_INBOUND_CALLS` | нет вектора в этом слайсе |
| 164 | `VIDEO_CHAT_DELETE_HISTORY` | только kolibri (в PyMax нет) |
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | payload unknown, K13 |
| 195 | `VIDEO_CHAT_MEMBERS` | нет вектора в этом слайсе |

ws2-сигналинг и WebRTC остаются на хосте. `ConversationParams.ws2Url` / `ws2UrlFromEndpoint` только собирают URL.

Сторонние заметки (не референс) иногда переименовывают `78` в `CALL_START` и вводят коды вроде `69 CALL_EDIT` / `83 CALL_LEAVE`. Эти имена и схемы **не** из kolibri/PyMax; в ядро они не переносятся. `83` в таблице ядра — `VIDEO_PLAY` (медиа), не leave.

## Реализовано в API (итог)

Источник payload — PyMax (`payloads.py` / mixins) или kolibri; каждый метод покрыт тестом с вектором.

| область | опкоды | слой |
|---------|--------|------|
| сессия | `1`, `6`, `8` (как PyMax `LOGIN2`) | `SessionMachine`, `TokenLogin` |
| вход | `17`, `18`, `19`, `20`, `23`, `115`, `290` | `AuthApi`, `TokenLogin` |
| 2FA | `107`–`113` | `TwoFactorApi` (`MaxApi.twoFactor`) |
| пользователи | `32`, `34`, `46`, `21`, `96` | `UsersApi` (`MaxApi.users`) |
| аккаунт | `16`, `22`, `97`, `272`, `274`, `276` | `AccountApi` (`MaxApi.account`); `MaxClient` сохраняет новый токен (97) и `configHash` (22) |
| чаты | `48`, `49`, `50`, `52`, `53`, `55`, `57`, `58`, `59`, `75`, `77`, `89` | `ChatsApi` (группы, ссылки, заявки, админы) |
| сообщения | `64` (текст, вложения, отложенная отправка, опросы, комментарии), `66`, `67`, `71`, `91`, `94`, `178`, `179`, `180`, `304` | `MessagesApi` |
| медиа | `80`, `82`, `83`, `87`, `88` | `MediaApi` (потоковая загрузка с диска через `UploadSource`) |
| боты | `118`, `160` | `BotsApi` (`MaxApi.bots`) |
| звонки | `137` (push), `158` | `MaxEvent.CallStart`, `CallsApi` |
| push | `128`, `129`, `130`, `132`, `135`, `136`, `137`, `142`, `155` и др. | `EventParser` → `MaxEvents` → `EventRouter` → `MaxStore` |

## Блокеры: нужен снятый трафик

Не реализованы, потому что payload не подтверждён ни kolibri, ни PyMax. Для каждого нужен дамп
запроса/ответа реального Android-клиента (Pixel 8 профиль):

- `81` `STICKER_UPLOAD` / отправка стикера — схема вложения-стикера в `MSG_SEND` не подтверждена
- `65` `MSG_TYPING` (исходящий «печатает») — builder'а нет; входящий `129` уже разбирается
- поиск: `37` `CONTACT_SEARCH`, `60` `PUBLIC_SEARCH`, `68` `CHAT_SEARCH`, `73` `MSG_SEARCH`
- `193` `STICKER_CREATE`, `194` `STICKER_SUGGEST`, `301` `AUDIO_PLAY`
- звонки: `76`, `78`, `79`, `84`, `103`, `164`, `166` (K13), `195`; семантика ответа `158` (K12)
- 2FA/пароль: `101`, `104`, `105`, `116`
- транскрипция `202`/`293`, stories `208`–`218`, `220`
- `LOG` (`5`) — телеметрия, намеренно не отправляется

QR-вход на стороне нового устройства (`288`/`289`/`291`) у PyMax требует `deviceType = WEB`; это
противоречит правилу «клиент всегда Android», поэтому не реализован. Подтверждение QR с телефона
(`290`) есть.

## Только в одном источнике

Присутствуют в `Opcodes.kt` как union-таблица; API — только там, где есть вектор:

**Только PyMax:** `31` `SEARCH_FEEDBACK`, `62` `CHAT_LIVESTREAM_INFO`, `91` `MSG_GET_COMMENTS_INFO` и `94` `MSG_DELETE_USER_COMMENTS` (оба в API по вектору PyMax), `125` `LOCATION_SEND`, `126` `LOCATION_REQUEST`, `256` `ORG_INFO`, `288` `GET_QR`, `289` `GET_QR_STATUS`, `291` `LOGIN_BY_QR`, `302` `BANNERS_GET`, `303` `MSG_DELIVERY`.

**Только kolibri:** `164` `VIDEO_CHAT_DELETE_HISTORY`.

QR-вход `288`/`289`/`291` описан у PyMax как web-клиент; с телефона реализован только `AUTH_QR_APPROVE` `290`.
