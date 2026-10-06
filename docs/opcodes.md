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
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | PyMax `VIDEO_CHAT_JOIN` | Komet `joinByLink`: `{joinLink, internalParams, isVideo}`; endpoint в JSON-строке ответа. Имя PyMax другое, медиа в ядре нет |
| cmd `2` | — | PyMax `EVENT` / kolibri `NOT_FOUND` | не opcode; [protocol.md §B.3](protocol.md) |

## Calls — что уже в ядре

Реализовано:

| code | имя | слой | источник payload |
|------|-----|------|------------------|
| 137 | `NOTIF_CALL_START` | `MaxEvent.CallStart` + `ConversationParams.decode` | kolibri `kolibri-net/src/calls/` (vcp: `<rawLen>:<base64(LZ4-block(JSON))>`). `callerId` + строковый `conversationId` обязательны; битый `vcp` оставляет `params = null` |
| 79 | `VIDEO_CHAT_HISTORY` | `CallsApi.history` → `CallLogEntry` | KometTeam/Komet `CallsModule.fetchHistory`: **request** `{}`; **reply** `{history: [{message: {id, time, sender, attaches: [{_type: "CALL", contactIds?, duration, hangupType, callType?}]}}]}`. Пункты без `CALL`-вложения пропускаются |
| 158 | `OK_TOKEN` | `CallsApi.requestCallsToken` / `MaxApi.calls` | **request** — пустой map `{}` (msgpack `80`). **reply** `{token, token_lifetime_ts?, token_refresh_ts?}` — observed-not-ref (заметки third-party, не kolibri/PyMax) |
| 78 | `VIDEO_CHAT_START_ACTIVE` | `CallsApi.initiateCall` | Komet `initiateCall`: `{conversationId, calleeIds, internalParams, isVideo}`. Endpoint из JSON-строки `internalCallerParams`; адрес ws2 — `ws2UrlFromEndpoint` с `Ws2ClientInfo.forCalls` (как Komet `Ws2Config`). WebRTC — в приложении |
| 76 | `VIDEO_CHAT_START` | `CallsApi.createConference` | Komet `createConference`: `{conversationId}` → `joinLink` / `callName` / `chatId`. Нет ссылки — отдельно 84 |
| 84 | `VIDEO_CHAT_CREATE_JOIN_LINK` | `CallsApi.createJoinLink` | Komet: `{conversationId}` → `joinLink` |
| 166 | `VIDEO_CHAT_JOIN_BY_LINK` | `CallsApi.joinByLink` | Komet `joinByLink`: `{joinLink, internalParams, isVideo}`. `joinLink` — токен ссылки `https://max.ru/joincall/<token>` (`CallLink.token`). Endpoint из `internalParams` или `internalCallerParams` |
| 164 | `VIDEO_CHAT_DELETE_HISTORY` | `CallsApi.deleteHistory` | Komet `CallsModule.deleteHistory`: `{historyIds: [id сообщений журнала]}`. Пустой список не отправляется |
| 89 | `LINK_INFO` (звонок) | `CallsApi.linkInfo` → `CallLinkInfo` | Komet `resolveCallLink`: `{link: "joincall/<token>"}` → `videoConference{conferenceId, callName, participantsCount, callType}` |

Не реализовано — в обоих референсах нет payload-builder / call site, либо схема не согласована:

| code | имя | почему не в API |
|------|-----|-----------------|
| 77 | `CHAT_MEMBERS_UPDATE` | не calls-control в нашем смысле; метод есть в `ChatsApi` |
| 103 | `GET_INBOUND_CALLS` | нет вектора в этом слайсе |
| 195 | `VIDEO_CHAT_MEMBERS` | нет вектора в этом слайсе |

ws2-сигналинг и WebRTC остаются на хосте. `ConversationParams.ws2Url` / `ws2UrlFromEndpoint` только собирают URL.

Сторонние заметки (не референс) иногда переименовывают `78` в `CALL_START` и вводят коды вроде `69 CALL_EDIT` / `83 CALL_LEAVE`. Эти имена и схемы **не** из kolibri/PyMax; в ядро они не переносятся. `83` в таблице ядра — `VIDEO_PLAY` (медиа), не leave.

## Реализовано в API (итог)

Источник payload — PyMax (`payloads.py` / mixins) или kolibri; каждый метод покрыт тестом с вектором.

| область | опкоды | слой |
|---------|--------|------|
| сессия | `1`, `6`, `8` (как PyMax `LOGIN2`) | `SessionMachine`, `TokenLogin` |
| вход | `17`, `18`, `19`, `20`, `23`, `115`, `290` | `AuthApi`, `TokenLogin` |
| 2FA | `104` (схема Komet), `107`–`113` | `TwoFactorApi` (`MaxApi.twoFactor`): `details` / `status`, смена почты (`sendEmailCode`, `confirmEmailCode`, `commitEmail`) |
| пользователи | `8` (`{contactsSync: 0}`, схема Komet), `32`, `34` (в т. ч. `BLOCK` / `UNBLOCK`), `36` (чёрный список, схема Komet), `46`, `21`, `96` | `UsersApi` (`MaxApi.users`), `MaxClient.syncContacts` |
| аккаунт | `16`, `22`, `43`, `97`, `199`, `272`, `274`, `275`, `276` (43, 199, 275 и общий `22 {settings:{user}}` по схеме Komet) | `AccountApi` (`MaxApi.account`), `AccountConfig` (`config` из `19`); `MaxClient` сохраняет новый токен (97), `configHash` (22) и держит `accountConfig` |
| закреплённые чаты | `274` (`favorites` папки «Все чаты»), `272`, `277` (push), `config.chatFolders` в `19` | `ChatFolders`, `MaxClient.setPinnedChats` / `loadFolders`, схема Komet (protocol.md, «Закреплённые чаты») |
| чаты | `48`, `49`, `50`, `52`, `53`, `55`, `57`, `58`, `59`, `75`, `77`, `89` | `ChatsApi` (группы, ссылки, заявки, админы) |
| сообщения | `64` (текст, вложения, отложенная отправка, опросы, комментарии), `66`, `67`, `71`, `91`, `94`, `178`, `179`, `180`, `304` | `MessagesApi` |
| окно истории вокруг сообщения | `49` `CHAT_HISTORY` с `forward` и `backward` от времени сообщения (время — из стора или `71` `MSG_GET`) | мост iOS `loadHistoryAround`: переход к далёкой цитате, закрепу, найденному сообщению и листание от краёв окна; в `MaxStore` не пишется — это не свежая история |
| общие медиа | `51` `CHAT_MEDIA` (схема Komet `SharedContentModule.fetchMedia`: `{chatId, messageId, attachTypes, forward, backward}` → `{messages, total}`) | `MessagesApi.getChatMedia`, мост iOS `loadSharedMedia`; в `MaxStore` не пишется — это не сплошная история |
| реакции | `178`, `179` (и для комментариев, с `postId`), `180`, `181` (схема Komet), `155` (push); каталог `27` / `28` (`ANIMOJI_SET`, `ANIMOJI`, схема Komet) | `MessagesApi`, `AssetsApi` (`MaxApi.assets`), `MaxClient.setReaction` / `loadReactions` / `loadReactionUsers` / `reactionCatalog` |
| медиа | `80`, `82`, `83`, `87`, `88` | `MediaApi` (потоковая загрузка с диска через `UploadSource`) |
| боты | `105` (схема Komet), `118`, `160` (`queryId` / `query_id` необязателен) | `BotsApi` (`MaxApi.bots`), `EntryApp` (мини-приложения настроек) |
| звонки | `76`, `78`, `79`, `84`, `137` (push), `158`, `166` | `MaxEvent.CallStart`, `CallsApi`. `internalParams` — JSON Komet (`hexCapability` `3c02f`). Медиа на хосте |
| жалобы | `161`, `162` | `ComplaintsApi` (`MaxApi.complaints`). Типы с вектором: канал `2`, пользователь `6`. Тип сообщения не назван |
| поиск в чате | `73` | `SearchApi.searchInChat`: `{chatId, query, count}` |
| общие чаты | `198` | `ChatsApi.commonChats`: `{userIds:[id]}` → `commonChats` |
| push | `128`, `129`, `130`, `132`, `135`, `136`, `137`, `142`, `155`, `277` и др. | `EventParser` → `MaxEvents` → `EventRouter` → `MaxStore` |

## Блокеры: нужен снятый трафик

Не реализованы, потому что payload не подтверждён ни kolibri, ни PyMax. Для каждого нужен дамп
запроса/ответа реального Android-клиента (Pixel 8 профиль):

- `81` `STICKER_UPLOAD` / отправка стикера — схема вложения-стикера в `MSG_SEND` не подтверждена
- `65` `MSG_TYPING` (исходящий «печатает») — builder'а нет; входящий `129` уже разбирается
- поиск: `37` `CONTACT_SEARCH` (`60`, `68`, `73` уже в `SearchApi`)
- `193` `STICKER_CREATE`, `194` `STICKER_SUGGEST`, `301` `AUDIO_PLAY`
- звонки: `103`, `164`, `195`; семантика ответа `158` (K12). `76`, `78`, `79`, `84`, `166` есть в `CallsApi` по вектору Komet, без WebRTC
- 2FA/пароль: `101`, `116`
- транскрипция `202`/`293`, stories `208`–`218`, `220`
- `LOG` (`5`) — телеметрия, намеренно не отправляется

QR-вход на стороне нового устройства (`288`/`289`/`291`) у PyMax требует `deviceType = WEB`; это
противоречит правилу «клиент всегда Android», поэтому не реализован. Подтверждение QR с телефона
(`290`) есть; перед ним `MaxClient.approveQrLogin` шлёт `1 {interactive: true}` и `96` и ждёт 300 мс
(по Komet, без этого сервер отклоняет подтверждение).

## Только в одном источнике

Присутствуют в `Opcodes.kt` как union-таблица; API — только там, где есть вектор:

**Только PyMax:** `31` `SEARCH_FEEDBACK`, `62` `CHAT_LIVESTREAM_INFO`, `91` `MSG_GET_COMMENTS_INFO` и `94` `MSG_DELETE_USER_COMMENTS` (оба в API по вектору PyMax), `125` `LOCATION_SEND`, `126` `LOCATION_REQUEST`, `256` `ORG_INFO`, `288` `GET_QR`, `289` `GET_QR_STATUS`, `291` `LOGIN_BY_QR`, `302` `BANNERS_GET`, `303` `MSG_DELIVERY`.

**Только kolibri:** `164` `VIDEO_CHAT_DELETE_HISTORY` (в API по схеме Komet).

QR-вход `288`/`289`/`291` описан у PyMax как web-клиент; с телефона реализован только `AUTH_QR_APPROVE` `290`.
