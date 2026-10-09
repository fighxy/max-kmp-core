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
| 103 | `GET_INBOUND_CALLS` | схема неизвестна: в kolibri, PyMax и Komet только константа, запроса и разбора ответа нет (проверено 2026-10-08, см. ниже) |
| 195 | `VIDEO_CHAT_MEMBERS` | схема неизвестна: то же самое (проверено 2026-10-08, см. ниже) |

Поиск схем 103 и 195 (2026-10-08):
- kolibri (`a6cdce9`): только `kolibri-net/src/protocol/opcodes.rs` — константы `GET_INBOUND_CALLS = 103`, `VIDEO_CHAT_MEMBERS = 195` и их имена; в `kolibri-net/src/calls/` и во всех биндингах вызовов нет.
- PyMax (все ветки `MaxApiTeam/PyMax`, в т. ч. `dev/2.5.0` и `v1`): только `protocol/enums.py` (`static/enum.py` в `v1`); call site и payload-builder нет.
- KometTeam/Komet (`feature/FullStack` `58576bc` и остальные ветки): только `lib/core/protocol/opcode_map.dart` (`getInboundCalls`, `videoChatMembers`); `CallsModule` эти коды не шлёт. Тип `InboundCall` в `push/fkm_controller.dart` — данные пуша 137 (`vcp`, `conversationId`, `callerId`), не ответ 103.
- Сторонние заметки (`MaxApiTeam/Node-Max`, `openmax-server/server`, `openmax-server/docs`, `PronikFire/Max-API-Guide`, `zarazaex69/m`): только имена и номера, без запроса и ответа.

Пометить их **observed-not-ref**, как 158, нельзя: у 158 запрос и ответ хотя бы видели в сторонних заметках, а здесь не видел никто. Любое тело запроса было бы выдумкой, поэтому методов в `CallsApi` нет, а в `Opcodes.kt` стоит `TODO: payload unknown`. Нужен снятый трафик Android-клиента: запрос и ответ 103 и 195.

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
| заглушение чатов | `22` `{settings: {chats: {<chatId>: {dontDisturbUntil}}}}`, `config.chats` в `19` / `8`, пуш `134` `NOTIF_CONFIG` | `AccountConfig` (`chatMuteState`, `chatsKnown`, `mergedWith` / `replacedBy`), `AccountConfigUpdate`, `MaxEvent.ConfigUpdated`; `MaxClient.setChatMuted` / `setChatMuteUntil` / `isChatMuted` / `chatMuteUntil`; мост iOS `isChatMuted`, `chatMuteUntil`, `setChatMuteUntil`, события `chatMute` и `config`. См. «Заглушение чатов: config.chats, 22 и 134» |
| закреплённые чаты | `274` (`favorites` папки «Все чаты»), `272`, `277` (push), `config.chatFolders` в `19` | `ChatFolders`, `MaxClient.setPinnedChats` / `loadFolders`, схема Komet (protocol.md, «Закреплённые чаты») |
| чаты | `48`, `49`, `50`, `52`, `53`, `55`, `57`, `58`, `59`, `75`, `77`, `89` | `ChatsApi` (группы, ссылки, заявки, админы) |
| сообщения | `64` (текст, вложения, отложенная отправка, опросы, комментарии), `66`, `67`, `71`, `91`, `94`, `178`, `179`, `180`, `304` | `MessagesApi` |
| окно истории вокруг сообщения | `49` `CHAT_HISTORY` с `forward` и `backward` от времени сообщения (время — из стора или `71` `MSG_GET`) | мост iOS `loadHistoryAround`: переход к далёкой цитате, закрепу, найденному сообщению и листание от краёв окна; в `MaxStore` не пишется — это не свежая история |
| общие медиа | `51` `CHAT_MEDIA` (схема Komet `SharedContentModule.fetchMedia`: `{chatId, messageId, attachTypes, forward, backward}` → `{messages, total}`) | `MessagesApi.getChatMedia`, мост iOS `loadSharedMedia`; в `MaxStore` не пишется — это не сплошная история |
| реакции | `178`, `179` (и для комментариев, с `postId`), `180`, `181` (схема Komet), `155` (push); каталог `27` / `28` (`ANIMOJI_SET`, `ANIMOJI`, схема Komet) | `MessagesApi`, `AssetsApi` (`MaxApi.assets`), `MaxClient.setReaction` / `loadReactions` / `loadReactionUsers` / `reactionCatalog` |
| медиа | `80`, `82`, `83`, `87`, `88` | `MediaApi` (потоковая загрузка с диска через `UploadSource`) |
| боты | `105` (схема Komet), `118`, `160` (`queryId` / `query_id` необязателен) | `BotsApi` (`MaxApi.bots`), `EntryApp` (мини-приложения настроек) |
| звонки | `76`, `78`, `79`, `84`, `89` (ссылка на звонок), `137` (push), `158`, `164`, `166`; `103` и `195` нет (схема неизвестна) | `MaxEvent.CallStart`, `CallsApi`. `internalParams` — JSON Komet (`hexCapability` `3c02f`). Медиа на хосте |
| жалобы | `161`, `162` | `ComplaintsApi` (`MaxApi.complaints`). Типы с вектором: канал `2`, пользователь `6`. Тип сообщения не назван |
| поиск в чате | `73` | `SearchApi.searchInChat`: `{chatId, query, count}` |
| общие чаты | `198` | `ChatsApi.commonChats`: `{userIds:[id]}` → `commonChats` |
| push | `128`, `129`, `130`, `132`, `134`, `135`, `136`, `137`, `142`, `155`, `277` и др. | `EventParser` → `MaxEvents` → `EventRouter` → `MaxStore` |
| «печатает» | `65` (исходящий, без ожидания ответа), `129` (push, с `type`) | `MessagesApi.sendTyping`, `MaxClient.sendTyping`, `TypingType`; `MaxEvent.Typing.type` / `effectiveType`, `MaxState.typingUsersWithType` / `typingType`; мост iOS `sendTyping`, `IosTypingType`, `text` у события `typing`. См. «Печатает: 65 и 129» |
| кто прочитал | `48` (`participants`), `59` (`readMark`), `71` (время и автор сообщения, если его нет в сторе), `130` (push), `181`; ключ конфига `max-readmarks` | `MessageReaders`, `ReadersApi` (`MaxApi.readers`), `Chat.participants`, `ChatMember.readMark`, `AccountConfig.maxReadmarks`, `MaxState.chatReadMarks`; `MaxClient.loadMessageReaders` / `isMessageReadersAvailable`; мост iOS `loadMessageReaders`, `isReadersAvailable`, `IosMessageReader`. См. «Кто прочитал сообщение» |
| время правки сообщения | поле `updateTime` сообщения везде, где приходит сообщение (`19`, `49`, `67`, `71`, `128` и др.) | `MaxMessage.updateTime` (`null`, если поля нет или `0`); push правки без поля сохраняет известное время; мост iOS `IosMessage.updateTime`, `IosEvent.updateTime` (`0` — не правилось) |
| выбор нескольких сообщений | `66` `MSG_DELETE` (весь выбор одним запросом, `postId?`, `forMe`, `itemType`; ответ `messageIds` / `failedMessageIds`), `64` `MSG_SEND` со ссылкой `FORWARD` (по одному кадру на сообщение) | `MessagesApi.deleteMessages` (`DeleteResult`) / `forwardMessages` (`ForwardBatch`), `MaxClient.deleteMessages` / `forwardMessages`; мост iOS `deleteMessages` (с `postId` — `IosDeleteResult`), `forwardMessages` (`IosForwardResult`). См. «Выбор сообщений, черновики, форматирование, участники, контакты» |
| форматирование текста | `elements` в `64` и `67`, в каждом пришедшем сообщении и черновике | `TextElement`, `TextElementType`, `TextElementsJson`, `MaxMessage.textElements`; `MaxClient.sendFormattedText` / `editText`; мост iOS `sendFormattedText` и `editMessage` (метки `IosTextMark` или `elementsJson`), `editFormattedText`, `IosMessage.marks` / `elementsJson`, `IosEvent.marks` |
| черновики на сервере | `176` `DRAFT_SAVE`, `177` `DRAFT_DISCARD`, `drafts` в ответе `19` `LOGIN` | `DraftsApi`, `Drafts`, `MaxDraft`, `MaxState.drafts`; `MaxClient.saveDraft` / `discardDraft` / `drafts`; мост iOS `saveDraft`, `discardDraft`, `drafts()` (`IosDraft`) |
| участники группы | `59` `CHAT_MEMBERS` (страницы по `marker`, поиск по `query`), роли из `48` (`owner`, `admins`, `adminParticipants`) | `ChatsApi.getChatMembers` / `searchChatMembers`, `MemberListType`, `ChatRoles`, `ChatMemberEntry`, `ChatMembersResult`; `MaxClient.loadChatMembers` / `searchChatMembers`; мост iOS `loadChatMembers` (`IosChatMembersPage`, `IosGroupMember`), `searchChatMembers` |
| контакты и имена | `34` `UPDATE` / `REMOVE`, `41` `CONTACT_ADD_BY_PHONE`, пуш `131` `NOTIF_CONTACT`; книга устройства — без запросов | `UsersApi.renameContact` / `removeContact` / `addContactByPhone`, `PhoneNumbers`, `ContactNames`; `MaxClient.renameContact` / `removeContact` / `addContactByPhone` / `setAddressBook` / `setLocalName` / `displayName` / `displayLabel`; мост iOS те же имена, `IosPhoneContact`, `IosContact.displayName`, событие `contact` |

## Заглушение чатов: config.chats, 22 и 134

Заглушение хранится не в чате, а в конфиге аккаунта: `config.chats["<chatId>"].dontDisturbUntil`.
`0` — звук включён, `-1` — заглушён навсегда, иначе — время конца заглушения в мс (Unix). Чат без
записи в полном разделе `chats` считается со звуком. В карточке чата (`19`, `48`, `135`) поля
заглушения нет, поэтому пуши и страницы чатов его не трогают.

Почему метки слетали (подтверждено по коду, на живом сервере не воспроизводилось):

1. `AccountConfig.fromLoginReply` собирал из `config` ответа `LOGIN` новый конфиг целиком, и
   `MaxClient` подменял им известный. После переподключения ядро шлёт прежний `configHash`, и сервер
   может прислать `config` не полностью. Хеш состоит из нескольких частей, и Komet (`feature/FullStack`,
   `account.dart`) сохраняет `user`, `server` и `chatFolders` каждый, только если раздел пришёл, а
   `chat_parsing.dart` при отсутствии записи чата держит прежнее заглушение. Ответ без `chats` давал
   пустой `chats`, и все чаты выглядели «со звуком».
2. Мост iOS при известном конфиге без записи чата отдавал `muted = 0`. Приложение Maxly пишет `0` в базу как
   «звук включён», а `-1` пропускает. Вместе с п. 1 метка снималась со всех чатов сразу.
3. Пуш `NOTIF_CONFIG` 134 шёл как `MaxEvent.Unknown`: заглушение с другого устройства не
   применялось, а его хеш не сохранялся.
4. `setChatMuted` не переносил новый хеш в маркеры следующего `LOGIN`, как это делают
   `updatePrivacy` и `updateUserSettings`. После `CONFIG` 22 переподключение шло со старым хешем, и
   сервер присылал частичный конфиг из п. 1.
5. `setChatMuted` и `updateUserSettings` без загруженного конфига начинали с пустого `AccountConfig()`.
   Получался конфиг с одним чатом, и все остальные выглядели «со звуком».

Как теперь (`AccountConfig`, `MaxClient`):

- `AccountConfigUpdate` — раздел `config` как он пришёл: отсутствующий раздел (`null`) отличается от
  пустого.
- `LOGIN` с `configHash` по умолчанию (первый вход процесса) — полный снимок (`replacedBy`): пришедший
  раздел заменяет известный, отсутствующий остаётся. Пришедший `chats` ставит `chatsKnown = true`.
- `LOGIN` после переподключения (с прежним хешем), `LOGIN2` 8 и пуш `134` — дельта (`mergedWith`).
  Отсутствующий раздел остаётся. `user` и `server` сливаются по ключам. `chats` сливается по id чата,
  а запись чата — по полям: `{"dontDisturbUntil": 0}` включает звук и сохраняет `favIndex`, запись
  `null` удаляет настройки чата. `chatsKnown` не меняется: частичный `chats` ничего не говорит о чатах,
  которых в нём нет.
- Другой аккаунт (другой `userId` в ответе) — прежний конфиг не используется.
- Пуш `134`: `MaxEvent.ConfigUpdated(update)`. Схемы тела нет ни в одном референсе (Komet, PyMax и
  kolibri знают только константу), поэтому читаются обе формы конфига: `{config: {...}}`, как в
  `LOGIN`, и разделы `chats` / `user` / `server` / `hash` на верхнем уровне. Тело без них остаётся
  `MaxEvent.Unknown`. `MaxClient` сливает пуш в `accountConfig`, а `hash` сохраняет как `configHash`
  следующего `LOGIN`.
- `MaxClient.appliedEvents: SharedFlow<MaxEvent>` — каждый пуш по порядку, но только после того, как он
  применён: стор уже содержит его (первая ступень роутера), а `134` уже слит в `accountConfig`. Подходит
  для `ConfigUpdated`, `Presence` (`132`), `DraftSaved` / `DraftDiscarded` (`152` / `153`),
  `ContactUpdated` (`131`) и остальных событий; подписчик читает новое состояние без гонки со слиянием
  (раньше `router.on<ConfigUpdated>` мог прочитать `accountConfig` до слияния). Состояние может уже
  содержать и более поздние пуши, но никогда не меньше события. Горячий поток без повтора, без потерь,
  пока подписчик не отстал больше чем на `APPLIED_EVENTS_BUFFER` (1024): тогда ждёт ступень
  обработчиков роутера, стор — нет. Мост iOS (`watchEvents`) берёт пуши отсюда.
- `CONFIG` 22 (`setChatMuted`, `setChatMuteUntil`, `updateUserSettings`): хеш ответа уходит в маркеры
  `LOGIN`, только если в `accountConfig` уже есть конфиг от сервера. Без него новый конфиг знает только
  изменённый чат (`chatsKnown = false`), а следующий `LOGIN` всё равно просит конфиг целиком.
- Что показывать (`AccountConfig.chatMuteState`, `MaxClient.isChatMuted`): `true` — заглушён (`-1` или
  время конца ещё впереди), `false` — звук (`0`, истёкшее заглушение или нет записи при
  `chatsKnown`), `null` — неизвестно (нет конфига или нет записи без `chatsKnown`). `null` не
  сохранять как «звук включён». `isMuted` (`null` для чата без записи) не менялся.

Мост iOS:

- `IosChat.muted` и `MaxIosClient.isChatMuted(chatId)`: `1` заглушён, `0` звук, `-1` неизвестно
  (никогда не «навсегда»). `chatMuteUntil(chatId)` — сырое `dontDisturbUntil`, `Long.MIN_VALUE`, если
  неизвестно. `setChatMuteUntil(chatId, untilMs, onResult)` — заглушить до времени.
- `watchEvents` присылает `chatMute` при каждом изменении заглушения в `accountConfig` (пуш `134`,
  `setChatMuted`, `LOGIN`): `chatId`, `IosEvent.muted` `1` / `0` / `-1`, `timeMs` — сырое
  `dontDisturbUntil`. Отдельного события об истечении заглушения на время нет: по `timeMs` его
  считает приложение, или `muted` пересчитывается при следующем чтении списка.
- Событие `config` (без чата) приходит, когда конфиг появился, сброшен (вход, выход) или поменялся
  `chatsKnown`. Тогда надо перечитать заглушение всех чатов.

## Печатает: 65 и 129

Схема `65` и `129` — по Komet (`feature/FullStack`), PyMax и kolibri; набор значений `type` сверен с поведением официального веб-клиента MAX на проводе. Код ни откуда не брали.

- `65` `MSG_TYPING`, клиент → сервер: `{chatId: Long, type: String}`, в комментариях к посту ещё `postId: Long`. Отправка fire-and-forget: ответ не ждём, ошибки отправки игнорируем (`MaxTransport.sendRequest` через `SessionMachine.sendWithoutReply` / `RequestSink.sendWithoutReply`). Поздний ответ сервера транспорт отбрасывает. `MessagesApi.sendTyping` возвращает `true`, если кадр записан в сокет, и `false`, если отправить не удалось (нет соединения и т. п.); исключение пробрасывается только при отмене корутины.
- `129` `NOTIF_TYPING`, сервер → клиент: `{chatId, userId, type}`. `chatId` и `userId` обязательны (без них событие `Unknown`). `MaxEvent.Typing.type` — сырое значение (`null`, если поля нет или оно не строка); `MaxEvent.Typing.effectiveType` — нормализованное (`TypingType.effective`).
- Значения `type` (константы `TypingType`, в мосте iOS `IosTypingType`):

  | значение | что делает пользователь |
  |----------|-------------------------|
  | `TEXT` | набирает текст |
  | `AUDIO` | записывает голосовое |
  | `VIDEO_MSG` | записывает видеосообщение (кружок) |
  | `PHOTO` | отправляет фото |
  | `VIDEO` | отправляет видео |
  | `FILE` | отправляет файл |
  | `STICKER` | выбирает стикер |

  Нет `type`, пустая строка или незнакомое значение означают `TEXT`. При отправке строка уходит как есть.
- Сигнала «перестал печатать» в протоколе нет. `MaxState.typingUsers` по-прежнему отдаёт `Set<Long>` с TTL `MaxState.DEFAULT_TYPING_TTL_MS` = 8 000 мс (эвристика клиента, с запасом над интервалом повтора 6 с). Эффективный `type` последнего пуша на пользователя в чате лежит рядом, в `MaxState.typingTypes`; читать через `typingUsersWithType(chatId, now, ttlMs)` (`Map<userId, type>`) и `typingType(chatId, userId, now, ttlMs)` (`null`, если пользователь уже не печатает) с тем же TTL. Новое сообщение отправителя и удаление чата убирают и метку времени, и `type`.
- Троттлинга в ядре нет: каждый вызов `sendTyping` (ядро, `MaxClient`, мост iOS) шлёт кадр. Клиенты повторяют `65`, пока пользователь занят, не чаще раза в 6 с на чат; собеседник гасит индикатор сам.

## Кто прочитал сообщение

Поведение сверено с официальным веб-клиентом MAX; схемы `48`, `59` и `181` — по Komet (`feature/FullStack`) и PyMax. Код ни откуда не брали.

- Отдельного запроса нет. Читатели — участники, чья отметка прочтения не меньше времени сообщения (равная считается прочтением), плюс все, кто поставил реакцию. Я и автор сообщения в список не входят.
- Отметка прочтения — время (мс) последнего прочитанного сообщения, а не момент чтения. Источники:
  - `48` `CHAT_INFO`: `participants` чата — `{userId: readMark}` (ключи бывают числами и строками). В ядре `Chat.participants`.
  - `59` `CHAT_MEMBERS` `{type: "MEMBER", chatId, marker, count: 50}`: у каждого участника `readMark` (`ChatMember.readMark`). Запрашивается, только если в `participants` меньше пользователей, чем `participantsCount`; страницы по `marker`, пока не увидим всех, не придёт пустая страница или `marker` 0 / повтор.
  - `130` `NOTIF_MARK` (push): `MaxState.readMarks`.
  Для каждого пользователя берётся более поздняя отметка (`MessageReaders.mergeMarks`, для стора — `MaxState.chatReadMarks`).
- Реакции: `181` `MSG_GET_DETAILED_REACTIONS` `{chatId, messageId, count: 100}` (у комментариев ещё `postId`), без пагинации, ответ `reactions: [{userId, reaction}]`. Если запрос упал, показываем только читателей, без ошибки.
- Где доступно (`MessageReaders.isAvailable`): только группы `type` `CHAT` без флага `videoConversation` (идёт групповой звонок) и с числом участников (`participantsCount`, без него — размер `participants`) не больше `max-readmarks` из `config.server` (`AccountConfig.maxReadmarks`, по умолчанию 100). Не бывает в диалогах, «Избранном», каналах и комментариях. Подходит любое отправленное сообщение, не только своё, без ограничения по давности.
- Порядок (`MessageReaders.build`): сначала поставившие реакцию — в порядке ответа `181`, у каждого эмодзи; потом прочитавшие без реакции — по отметке по убыванию, при равенстве по `userId` по возрастанию. Каждый пользователь один раз: прочитавший с реакцией — только в группе реакций (с отметкой, если она дошла до сообщения; иначе отметки нет, в мосте iOS `readMark = 0`).
- Загрузка (`ReadersApi.loadMessageReaders`, `MaxClient.loadMessageReaders`): каждое открытие экрана заново спрашивает `48`, чтобы отметки были свежими; свежий чат кладётся в стор. Недоступный чат — пустой список, больше запросов нет. Время и автор сообщения: из стора (загруженные сообщения или `lastMessage` чата), иначе `71` `MSG_GET`; не нашлось — ошибка. Затем `59` (при необходимости, ошибки игнорируются) и `181`. Имена неизвестных пользователей догружаются `CONTACT_INFO` (32) без ошибки при сбое.
- `74` `MSG_GET_STAT` к этому не относится (просмотры постов каналов) и не используется.

## Выбор сообщений, черновики, форматирование, участники, контакты

Главный источник — разбор веб-клиента MAX (что он шлёт и как читает ответы); Komet и PyMax — для сверки. Код ни откуда не брали.

### Выбор нескольких сообщений

- Удаление: один `66` `MSG_DELETE` на весь выбор, `{chatId, postId?, messageIds, forMe}` (`postId` — для комментариев поста канала); `forMe: true` — только у себя, `false` — у всех. Komet добавляет `itemType` (`REGULAR`, для отложенных `DELAYED`); ядро шлёт его, только если вызывающий передал `itemType`. Ответ `{messageIds, failedMessageIds?}`: `DeleteResult.deleted` — удалённые (`messageIds` ответа, без него — запрошенные) без отказанных, `DeleteResult.failed` — `failedMessageIds`. Другие устройства получают пуш `142`; себе сервер, судя по веб-клиенту, удаление не присылает, поэтому `MaxClient.deleteMessages` сам убирает из стора только `deleted` (последнее сообщение чата пересчитывается).
- Где что: ядро — `MessagesApi.deleteMessages(chatId, messageIds, forMe, itemType, postId): DeleteResult`; `MaxClient.deleteMessages(...)`: `DeleteResult`; мост iOS — прежний `deleteMessages(chatId, messageIds, forEveryone, onResult: (kind, key))` и `deleteMessages(chatId, messageIds, forEveryone, postId, onResult: (IosDeleteResult?, kind, key))`.
- Пересылка: пакетной формы нет (ни у веб-клиента, ни у Komet, PyMax, kolibri). Каждое сообщение — отдельный `64` `MSG_SEND` с `link: {type: "FORWARD", chatId: <источник>, messageId}` и своим `cid`. `messageId` уходит строкой, как у PyMax (Komet шлёт числом; какой вид обязателен, не проверено). `MaxClient.forwardMessages` сортирует известные стору сообщения по `time` (от старых к новым, при равном времени — по id; неизвестные — после, в данном порядке), необязательный комментарий уходит первым обычным текстом, обрезанный по краям (пустой не шлётся; фикстура Maxly `selection/forward.json`). Первая ошибка останавливает остальное: `ForwardBatch.failedIndex` — индекс неотправленного, `sent` — что ушло (в мосте `IosForwardResult.failedAt`, `-1` — всё отправлено). Пересылка в несколько чатов сразу — цикл на стороне приложения (веб: сначала комментарий во все чаты, потом каждое сообщение во все чаты).
- Копирование текста — только на клиенте, протокола нет.

### Черновики на сервере

- `176` `DRAFT_SAVE` `{chatId | userId, draft: {text?, elements, replyTo?}}` → `{time}` (тело и адрес проверяются по `drafts/outgoing.json`; решение «отправлять ли» и обрезка — у приложения). Диалоги и «Избранное» адресуются `userId` собеседника (для «Избранного» — свой id), остальные чаты — `chatId` (`Drafts.address`). Пустой текст не шлётся, `elements` — всегда (может быть `[]`). `time` ответа становится `updateTime` черновика.
- `177` `DRAFT_DISCARD` `{chatId | userId, time}`, `time` — `updateTime` удаляемого черновика (`MaxClient.discardDraft` берёт его из стора; если черновика нет и время не передано, запрос не уходит).
- Вход: ответ `19` `LOGIN` несёт `drafts: {chats: {saved: {chatId: draft}, discarded: {chatId: time}}, users: {saved: {userId: draft}, discarded: {userId: time}}}`. Ключ `users` переводится в id диалога `me xor userId`. Черновик: `{saveTime, text, elements, replyTo?}`. В сторе (`MaxState.drafts`) побеждает более позднее время (равное заменяет); `discarded` удаляет черновик, если тот не новее времени удаления (веб-клиент удаляет без проверки — небольшое расхождение, чтобы не потерять свежий локальный черновик). При входе другим аккаунтом черновики прежнего сбрасываются.
- Исправлено: `SyncState.updatedBy` ставил `draftsSync` во время входа, хотя черновики выбрасывались, и сервер больше их не присылал. Теперь `draftsSync` при входе не меняется; ядро черновики между запусками не хранит, поэтому шлёт `-1` и получает все. `CredentialStore` пишет маркер под новым ключом `sync.drafts.v2`, старое значение `sync.drafts` (время входа) не читается и удаляется. `MaxState.draftsSyncTime` — наибольшее `updateTime` из стора, для приложения, которое будет хранить черновики само.
- Пуши `152` `NOTIF_DRAFT` / `153` `NOTIF_DRAFT_DISCARD` (черновик сохранён / стёрт на другом устройстве): веб-клиент их игнорирует, форма **не подтверждена**. Ядро читает их терпимо, предполагая форму запросов: `152` `{chatId | userId, draft: {text, elements, replyTo?, attaches?, saveTime | updateTime | time}}` (время может лежать и рядом с `draft`), `153` `{chatId | userId, time}`; id бывают строками. Без адреса, без `draft`-словаря или без времени пуш остаётся `MaxEvent.Unknown` и ничего не меняет; битые `elements` / `text` отбрасываются, черновик читается. События: `MaxEvent.DraftSaved` / `MaxEvent.DraftDiscarded` (`targetChatId(me)`: `chatId` или диалог `me xor userId`; пока свой id неизвестен, пуш диалога пропускается).
- Слияние пушей (общее правило Maxly, фикстура `drafts/merge.json`): `152` заменяет наш черновик только при **строго** большем времени (`Drafts.mergeRemote`; равное — остаётся наш); пустой черновик (текст пустой после обрезки и нет `replyTo`, `MaxDraft.isEmpty`) читается как стирание в его время; `153` стирает, если наш черновик не новее (`updateTime <= time`). Черновик только из ответа (пустой текст + `replyTo`) — настоящий. Пустой `saved` из `LOGIN` — нет черновика, а стирание в его время (метка, см. ниже). `attaches` входящего черновика читаются (`MaxDraft.attaches`, сырые словари) и никогда не отправляются.
- Метки стирания (`MaxState.draftDiscards`, `MaxState.draftDiscardedAt(chatId)`, `MaxClient.draftDiscards` / `draftDiscardedAt(chatId)`, мост `draftDiscardedAt(chatId)`): для каждого чата хранится серверное время последнего известного стирания его черновика. Источники: `discarded` из `LOGIN`, пустой `saved` из `LOGIN`, пуш `153`, пустой пуш `152`, своё `177` (`MaxClient.discardDraft` — время, ушедшее в запросе) и отправка, забравшая черновик (`MaxStore.takeDraft` — `updateTime` черновика). Правила:
  - стирание во время `T`: черновик стора с `updateTime > T` остаётся, и метка не ставится (он уже новее стирания); иначе черновик удаляется, метка = большее из прежней и `T`;
  - черновик с сервера (`LOGIN` `saved`, `152`) со временем `S` принимается только при `S >` метки и тогда снимает метку; при `S <=` метки он игнорируется. **Равное время — побеждает стирание** (фикстура `discard-equal-clears`, правило 3 README); черновик против черновика — по-прежнему «равное оставляет наш»;
  - свой подтверждённый `176` всегда снимает метку (это последнее слово по чату);
  - у чата либо черновик, либо метка, не оба; метки сбрасываются вместе с черновиками при входе другим аккаунтом и `MaxStore.clear`. `MaxState.draftsSyncTime` учитывает и метки.
- Черновик в приложении (композер): `Drafts.reconcile(local, server, discardedAt)` / `MaxClient.reconcileDraft(chatId, local)` / мост `reconcileDraft(chatId, text, elementsJson, replyTo, updateTime)` — правило фикстуры `drafts/merge.json`: пустой черновик — нет черновика; из двух побеждает более позднее время, при равном — локальный; стирание со временем не меньше победителя очищает. Так `discard-newer-clears` (локальный 1000, стирание 1500 → пусто) проходит: раньше стирание без серверного черновика не оставляло следа, и приложение не могло узнать о нём. `SharedFixturesTest` проигрывает фикстуру в этом режиме (пуши `152`/`153` в обоих порядках и одним снимком `LOGIN`) и в прежнем (локальный черновик в сторе) — все 13 случаев проходят; `members/search.json` — все 11.
- Событие моста `draft`: при пропаже черновика `IosEvent.draft == null`, а `IosEvent.timeMs` — время метки стирания (`0`, если черновик ушёл без метки). Событие приходит и когда меняется только метка (стирание без черновика в сторе).
- Сырые пуши `152`/`153` пишутся в диагностический лог (`MaxClient.onDiagnostic`, в мосте iOS — `IosDiagnostics.installDiagnosticLogger`, без него — логгер ошибок, строки с префиксом `DIAG `), разобранные и нет (`MaxEvent.Unknown`). Формат (`DiagnosticLog.draftPush`): `push 152 -> DraftSaved | {…}`. Сохраняются все ключи, числа (id, время), булевы значения, `type` элементов, `_type` вложений и другие значения-перечисления (`status`, `action`, ...), строки под ключами `…id`, `…ids`, `…time` и строки из цифр. `text` — только длина и не больше двух первых символов (`"пр…(len=12)"`), прочие строки — только длина (`"<str len=8>"`), байты — `"<bytes N>"`. Строка не длиннее 2000 символов (`DiagnosticLog.MAX_ENTRY_CHARS`). Нужно, чтобы подтвердить форму пушей по логам с устройства.
- После успешной отправки (`MaxClient.sendText`, `sendFormattedText`, `sendAttachments`, `sendMedia`) ядро само убирает черновик чата из стора (`MaxStore.takeDraft`, атомарно — из двух параллельных отправок его получит одна) и в фоне шлёт `177` с его `updateTime` — ровно один раз, как веб-клиент. Ошибка `177` не ломает отправку: уходит в `MaxClient.onBackgroundError` (в мосте iOS — в `IosDiagnostics`). `sendSticker`, `sendContact` и комментарий пересылки черновик не трогают. Приложению больше не нужно вызывать `discardDraft` после отправки.
- Порядок запросов черновика (`DraftQueue` в ядре, через него идут `MaxClient.saveDraft`, `discardDraft` и `177` после отправки): по каждому чату `176` / `177` уходят по одному, в порядке вызова. `177` после отправки ждёт, пока завершится `176`, уже ушедший до неё (успехом или ошибкой), и стирает на позднейшее из времени забранного черновика и времени такого `176` — поздний `176` не оставит на сервере устаревший черновик. `176`, вызванный до отправки и ещё не ушедший к её успеху, не отправляется; черновик `176`, бывшего в полёте, в стор не попадает. Оба случая — `DraftSupersededException(chatId, time)` (`time` = `null`, если запрос не уходил); мост `saveDraft` отдаёт тогда `0` без ошибки. `176`, вызванный уже после начала отправки, — новый черновик: он не отбрасывается и отправкой не забирается.
- Адрес неизвестного стору чата — по id, как у веб-клиента: `0` — «Избранное» (свой id), положительный — диалог (`userId = chatId xor me`), отрицательный — группа/канал (`chatId`).

### Форматирование текста

- `elements`: `[{type, from, length, entityId?, entityName?, attributes?}]`. `from` / `length` — в единицах UTF-16 (индексы `String` в Kotlin и `NSString`). Элементы могут перекрываться.
- Типы (`TextElementType`, в мосте `IosTextMarkType`): `STRONG` (жирный), `EMPHASIZED` (курсив), `UNDERLINE`, `STRIKETHROUGH`, `MONOSPACED`, `LINK` (`attributes.url`), `QUOTE`, `HEADING`, `USER_MENTION` (`entityId` — пользователь, бывает `entityName`), `ANIMOJI` (`entityId`, `attributes.animojiLottieUrl`).
- Чтение (`TextElement.parse`) — как у веб-клиента и по общему правилу (`formatting/parse-*.json`): `CODE` читается как `MONOSPACED`; известный тип — без учёта регистра (`strong` → `STRONG`); незнакомый тип сохраняется со своим написанием. У элемента любого типа все прочие ключи (вложенные объекты, массивы, числа) лежат в `TextElement.extra`, а весь объект как пришёл — в `TextElement.raw` (не участвует в `equals`, `copy` его сбрасывает). `toPayload` (а значит `TextElementsJson.write`, `IosMessage.elementsJson`, `IosDraft.elementsJson` и правка) отдаёт `raw` без изменений, если разбор ничего не поменял (тот же тип в том же написании, `from` / `length` / `entityId` пришли числами и не обрезаны); иначе — поля в порядке Komet и затем `extra`; нет `from` — 0; нет `length` — до конца текста; элементы нулевой и отрицательной длины выбрасываются. Исправлено по фикстурам (раньше не проходили 5 случаев): при известной длине текста отрезок, начинающийся на конце текста или дальше, выбрасывается, хвост за концом обрезается (пустой текст — разметки нет); `LINK` без непустого `attributes.url` выбрасывается; тип в другом регистре раньше оставался незнакомым.
- Отправка и правка: `64` и `67` с `elements`. Элемент за пределами текста отбрасывается. `67` всегда несёт полный список, поле не опускается; `elements: []` снимает форматирование. Ответ `67` приходит без реакций; `StateReducer.putEditedMessage` сохраняет известные реакции.
- Мост iOS: `sendFormattedText` / `editMessage` принимают метки (`IosTextMark`) или `elementsJson` — JSON-массив тех же объектов (`TextElementsJson`); битый JSON — ошибка `UNKNOWN` до отправки. Исправлено: `editMessage(chatId, messageId, text)` без форматирования раньше слал `[]` и стирал разметку; теперь сохраняет форматирование сообщения из стора там, где оно помещается в новый текст. Приём: `MaxMessage.textElements`, в мосте `IosMessage.marks` / `IosMessage.elementsJson` / `IosEvent.marks`.

### Участники группы

- Свои права в чате (`ChatRights.of(chat, me)`, `MaxClient.chatRights` / `myRole` / `myPermissions`, мост `chatRights(chatId)` → `IosChatRights(isOwner, isAdmin, permissions, canDeleteAnyMessage)`): роль из `owner`, `admins`, `adminParticipants` карточки чата в сторе, биты — `adminParticipants[me].permissions` как прислал сервер (`null` / `-1`, если записи в `adminParticipants` нет или биты неизвестны; права владельца от битов не зависят). Диалог, «Избранное», неизвестный чат — без прав.
- Биты прав (проверено по бандлу веб-клиента, класс админа: `FLAG_POST_EDIT_DELETE_MESSAGE=1`, `ADD_REMOVE_MEMBER=2`, `ADD_ADMIN=4`, `CHANGE_CHAT_INFO=8`, `PIN_MESSAGE=16`, `READ_ALL_MESSAGES=32`, `CALL=64`, `EDIT_LINK=128`, `POST_MESSAGE=256`, `EDIT_MESSAGE=512`, `DELETE_MESSAGE=1024`, `VIEW_STATS=2048`; PyMax `ChannelPermissions.DELETE_MESSAGE = 1024`; Komet: в группе «удалять сообщения» — бит 1, в канале «удалять посты» — 1024). «Удалять любые сообщения» (`ChatRoles.canDeleteAnyMessage`, веб-клиент `viewerCanDeleteAnyMessage`): владелец всегда; в группе — админ с битом **1**; в канале — админ с битом 1024 или 1. То есть «1024 = удаление» верно для каналов, в группах веб-клиент смотрит бит 1.

- `59` `CHAT_MEMBERS` `{chatId, type: "MEMBER", marker, count: 50}`: первая страница — `marker` 0, дальше `marker` из ответа. Ответ без `marker` — последняя страница: `ChatMembersPage.marker == null` (раньше ядро превращало отсутствие в 0, и загрузчик мог начать сначала). Конец (`ChatMembersResult.nextMarker == null`, в мосте пустая строка): нет `marker`, он 0, повторяет запрошенный или страница пустая. Загрузчик «кто прочитал» (`ReadersApi`) останавливается так же.
- Поиск: `59` `{chatId, type, query}` (`ChatsApi.searchChatMembers`, `MaxClient.searchChatMembers`, мост `searchChatMembers`). Типы списка (`MemberListType`): `MEMBER`, `ADMIN`, `BLOCKED_MEMBER`, `JOIN_REQUEST`, `COMMENTS_BLACKLIST`.
- Имя для упоминаний (`mentionName`). Проверено по бандлу веб-клиента MAX: отдельного поля в протоколе нет, веб-клиент выводит его из `link` контакта — путь URL без ведущего `/` (`new URL(link).pathname.slice(1)`; пустой путь или не абсолютный URL — нет имени). У группы/канала так же, но ссылка-приглашение `/join/…` имени не даёт; у диалога — имя собеседника. Контакты участников в ответе `59` (`members[].contact`) веб-клиент кладёт в тот же кэш контактов, так что `mentionName` участника — из `contact.link`. Ядро: `MentionNames.ofUser` / `ofChat`, `MaxUser.mentionName`, `Chat.mentionName`; мост: `IosGroupMember.mentionName`, `IosContact.mentionName`, `IosProfile.mentionName` (пустая строка — нет). Не проверено живым дампом, приходит ли `link` в каждом ответе `59` (в коде веб-клиента это не видно); у пользователя без короткого имени `link` нет, и `@`-поиск его не найдёт — как в веб-клиенте.
- Поиск по загруженным участникам (общее правило, фикстура `members/search.json`, копия в `core/src/jvmTest/resources/fixtures/members/`): `MemberSearch.matches` / `filter`, мост `filterMembers(members, query)` — подстрока полного имени или `mentionName` без учёта регистра, `ё` = `е`, пробелы по краям не важны, пустой запрос — все; запрос с `@` ищет только по `mentionName` (`@` один — все). Веб-клиент ищет так же локально (индекс по `fullName` и `mentionName`) и, пока список участников загружен не весь, дополнительно шлёт `59` с `query` — текстом после `@`.
- Роли (`ChatRoles`) — из чата (`48` `CHAT_INFO` или список чатов): `owner` — владелец, `admins` — id админов, `adminParticipants` — `{userId: {permissions, alias, inviterId?}}` (биты прав как прислал сервер). Если чата нет в сторе, `MaxClient.loadChatMembers` перед первой страницей спрашивает `48` (без ошибки при сбое: тогда все — обычные участники). Профили и `presence` (если новее) уходят в стор.

### Удаление у всех (`selection/delete.json`)

- `MessageDeletion.scope` / `summary` / `plan`, `MaxClient.deletePlan(chatId, messageIds)` и `canDeleteForEveryone(chatId, messageId)`, мост `deletePlan(chatId, messageIds)` → `IosDeletePlan(scopes, canDelete, showsForEveryone, forEveryoneByDefault, forcesForEveryone)` и `canDeleteForEveryone(chatId, messageId)`. Для каждого сообщения — `ALL` / `SELF` / `NONE`:
  - «Избранное» (id `0`) — `SELF`;
  - сообщения нет в сторе (неотправленное, локальный id) — `SELF`, в канале без прав — `NONE`;
  - диалог (и бот) — своё моложе `edit-timeout` — `ALL`, иначе `SELF`;
  - группа — с правом удалять любые — `ALL`; своё моложе — `ALL`; иначе `SELF`;
  - канал — с правом — `ALL`, иначе `NONE`.
- «Моложе» строго: `now - time < editTimeout * 1000` (часы устройства против серверного `time`). `edit-timeout` — `AccountConfig.editTimeoutSeconds` / `MaxClient.editTimeoutSeconds` / мост `editTimeoutSeconds()`, нет ключа — **0** (как в умолчаниях веб-клиента): своё у всех не удалить. Неизвестный стору чат: положительный id — диалог, отрицательный — группа.
- Сводка: `canDelete` — непустой выбор без `NONE`; `showsForEveryone` — все `ALL` и не канал; `forEveryoneByDefault` = `showsForEveryone`; `forcesForEveryone` — канал, где все `ALL`. Проверено по веб-клиенту (`Message.canDelete`); не реализованы его ветки комментариев канала и опции `delete-msg-fys-large-chat-disabled` (в большой группе чужое тогда нельзя удалить и у себя).
- Порядок пересылки (`ForwardOrder.of`, его использует `MaxClient.forwardMessages`): от старых к новым, равное время — по id. Несколько целей (комментарий в каждую, затем каждое сообщение во все) — цикл приложения.
- Листание участников: `ChatMembersResult.appendTo(loaded)` выбрасывает повторы и заканчивает список, если страница не принесла новых (`members/paging.json`); порядок списка «владелец, админы, остальные» — `ChatMembersResult.byRole` (`members/roles.json`).

### Контакты

- Переименовать: `34` `CONTACT_UPDATE` `{contactId, action: "UPDATE", firstName, lastName}`; пустая фамилия уходит `null`, каждое имя не длиннее 64 символов. Пустое имя разрешено и уходит `""` (как у веб-клиента): с фамилией сервер подставляет собственное имя человека, оба пустых — возвращаются исходные имена. Ответ `{contact}`, имя становится записью `CUSTOM`. Проверка «имя не пустое» удалена из ядра, `MaxClient` и моста (изменение поведения: раньше пустое имя давало ошибку до отправки).
- Удалить: `34` `{contactId, action: "REMOVE"}`, отмена — `ADD`. Стор убирает id из контактов и запись `CUSTOM`; пользователь и чат остаются (с контактом из ответа, если он есть).
- Добавить по телефону: `41` `CONTACT_ADD_BY_PHONE` `{phone, firstName?, lastName?}` → `{contact, new}` (`ContactByPhone.isNew`).
- Пуш `131` `NOTIF_CONTACT` `{contact}` (`MaxEvent.ContactUpdated`): стор обновляет пользователя, если `updateTime` не старее известного; в мосте — событие `contact`.
- Книга устройства на сервер не отправляется: `21` `SYNC` не используется (форма не подтверждена), импорт удалён. Ядро только принимает локальные имена (`setAddressBook`, `setLocalName`) и сопоставляет их по нормализованному телефону.

### Нормализация телефона (`PhoneNumbers.normalize`)

Ядро следует правилам, фикстуры к которым лежат в `fighxy/Maxly` (`test-fixtures/names/`). Копии фикстур уровня ядра (Maxly, ветка `ios/evening`, коммит `8f859e1`; список — `core/src/jvmTest/resources/fixtures/SOURCE.md`: `names/*`, `drafts/merge`, `drafts/outgoing`, `formatting/parse-*`, `members/search|paging|roles`, `selection/delete|forward`) лежат в `core/src/jvmTest/resources/fixtures/`, их проигрывает `SharedFixturesTest`; все случаи проходят. Правила поля ввода и экранов (`formatting/serialize-*`, `toggle`, `replace`, `edit`, `selection/copy`) — дело приложений и не проигрываются; `readers/`, `typing/`, `calls/` пока не разобраны.

1. убираются пробелы (любые, и неразрывные), дефисы и все тире Юникода (неразрывный дефис, en/em-тире, минус), скобки, точки и косая черта; ведущий `+` сохраняется (исправлено: раньше `+7 913 123‑45–67` давал `null`);
2. ведущие `00` считаются `+`;
3. 11 цифр без `+`, начинаются с 8 → `+7…`; 11 цифр с 7 → добавляется `+`;
4. ровно 10 цифр без `+` → `+7` перед ними;
5. иностранные номера сохраняют `+`; результат всегда с `+`;
6. меньше 7 или больше 15 цифр (или посторонние символы) → `null`;
7. один нормализованный номер дважды в книге → побеждает первое непустое имя.

### Какое имя показывать

Один резолвер в ядре — `ContactNames.resolve` (`MaxState.displayName`, `MaxClient.displayName`, мост iOS `displayName` и все имена моста: заголовки диалогов, авторы, участники, звонки, `IosContact.displayName`):

1. имя из книги устройства (локальное имя пользователя, иначе запись книги с тем же нормализованным номером);
2. имя контакта, заданное этим аккаунтом (`CUSTOM`);
3. собственное имя профиля (`ONEME`);
4. первая запись `names`, у которой есть имя (исправлено: раньше бралась первая запись, даже пустая);
5. телефон (`+7…`; номер, который не приводится, — как есть, например `900`);
6. «Участник» (`ContactNames.label`, `MaxState.displayLabel`, `MaxClient.displayLabel`).

Настройка `preferAddressBookNames` (`MaxState`, `MaxStore.setPreferAddressBookNames`, `MaxClient.preferAddressBookNames`, мост `setPreferAddressBookNames` / `preferAddressBookNames()`), по умолчанию `true` — порядок выше. `false` меняет местами шаги 1 и 2: своё имя контакта (`CUSTOM`) выше книги. Пока владелец продукта не решил, остаётся `true`. Настройка устройства: переживает выход и смену аккаунта.

Согласовано с командой iOS (README фикстур обновлён в `8f859e1`, случаи `custom-parts-over-name`, `custom-name-fallback`): для `CUSTOM` ядро берёт сначала `firstName lastName` (их заполняет форма переименования), для остальных записей — `name`. Там же согласовано: пустой `176` `DRAFT_SAVE` не несёт `text`, а незнакомые типы элементов форматирования сохраняются и при правке отправляются обратно без изменений.

Книга и локальные имена — только на устройстве. Переживают смену аккаунта в `LOGIN`, сбрасываются при выходе (`MaxStore.clear`).

## Присутствие («был в сети»)

Источники: разбор веб-клиента MAX (основной), KometTeam/Komet (`PresenceFetch`), PyMax, kolibri. Код не брали.

- Значение: `{seen?, status?}`, `seen` — Unix-секунды (ядро понимает и мс, `Presences.seenMs`). `status` (`PresenceStatus`, как читает веб-клиент): `0` или нет поля — не в сети, `seen` — когда был; `1` — в сети; `2` — был недавно (время скрыто); `3` — давно; другое значение читается как `2`. `-1` (`UNKNOWN`) — «ничего не известно», сервер его не шлёт; в мосте `seen` `0` — неизвестно, «недавно» из него не показывается.
- `19` `LOGIN`: ответ несёт `presence: {userId: {seen, status}}` (Komet `PresenceFetch.primeAll`; **подтверждено** Komet и веб-клиентом). Исправлено: ядро не читало его, но двигало `presenceSync` — дельта терялась при переподключении. Теперь `StateReducer.login` кладёт `presence` в стор, а `presenceSync` двигается только после этого (`TokenLogin.presenceApplied`, вызывает `MaxClient` после `MaxStore.applyLogin`; `SyncState.updatedBy` его больше не трогает в `TokenLogin`). Ответ, который клиент отбросил (устаревший вход), маркер не двигает.
- Новая сессия (каждый `LOGIN`): всё «в сети» сразу становится «не в сети» со временем последнего известного «в сети» (`StateReducer.invalidateOnline`), затем применяется `presence` ответа. Пользователей, которые были «в сети», а в ответе (дельте) их нет, `MaxClient` переспрашивает `35` в фоне (`MaxClientConfig.refreshPresenceOnLogin`, по умолчанию да).
- `35` `CONTACT_PRESENCE` `{contactIds: [id]}` → `{presence: {userId: {seen, status}}}` — **подтверждено** веб-клиентом и Komet (`PresenceFetch`, пачки по 100). Id, которого нет в ответе, веб-клиент считает «давно» (`3`) — ядро так же. `UsersApi.getPresence(ids)`, `MaxClient.loadPresence(ids)` (кладёт в стор), мост `loadPresence(userIds, onResult)`. Пачки по 100 (`UsersApi.PRESENCE_BATCH`), по очереди; повторы id выбрасываются.
- `132` `NOTIF_PRESENCE` `{userId, presence: {seen?, status?}}`. Исправлено: пуш без `seen` стирал время — теперь прежнее `seen` сохраняется (`Presences.merge`).
- Срок жизни: `presence-ttl` из `config.server` (секунды, по умолчанию 300; `AccountConfig.presenceTtlSeconds`). «В сети» без обновления дольше срока становится «не в сети» с `seen` = последнее известное время в сети (позднее из `seen` и момента, когда «в сети» пришло). Время обновления — `MaxState.presenceTimes` (локальные мс). Чтение с учётом срока: `MaxState.presenceAt` / `presenceStatus`, `MaxClient.presenceOf` / `presenceStatusOf`; стор чистит раз в `MaxClientConfig.presenceSweepIntervalMs` (15 с, `MaxClient.expirePresence`). Правило срока — предположение по `presence-ttl` конфига; как именно сервер его применяет, не проверено.
- Участники (`59`): `presence` участника уходит в стор, если новее известного; в мосте `IosGroupMember.presence` — полный код.
- `1` `PING` `{interactive}`: ядро слало всегда `true`. Теперь `MaxClient.setInteractive(Boolean)` / мост `setAppActive(Boolean)`: следующий `PING` несёт флаг, при смене сразу уходит один `PING` (как `set_ping_interactive` у kolibri), и `LOGIN` переподключения шлёт тот же `interactive`. Веб-клиент шлёт «не бездействует», PyMax — `set_presence`. Отдельного сообщения «ухожу из сети» ни в одном источнике нет — не придумываем: сервер решает сам по `interactive`. Период — 29 s, первый `PING` сразу после входа; во время звонка приложение держит `setInteractive(true)` (Android-клиент не гасит интерактивные `PING`, пока идёт звонок). Ответ ядра на серверный `PING` (`cmd=1`, пустое тело) идёт мимо guard-а режима призрака (protocol.md §C.3).

## Режим призрака и скрытые отметки о прочтении

Источник поведения — KometTeam/Komet (GPL-3.0): изучено только поведение, код не брали. Два **независимых** переключателя устройства, оба хранятся в `KeyValueStore` (`max.<ns>.ghostMode`, `max.<ns>.hideReadReceipts`, по умолчанию выключены), переживают выход и перезапуск и действуют до первого запроса процесса.

**Где применяются.** `MaxTransport.outboundGuard` (`OutboundGuard`) смотрит каждый кадр в момент записи в сокет — и `request`/`requestRaw`, и `sendRequest` (без ответа), и таймер `PING`. Поэтому обходных путей нет (кроме ответа на серверный `PING`: это служебное подтверждение транспорта с пустым телом, guard его не видит): прямой вызов `api.messages.markRead` / `sendTyping` / `api.stories.mark` тоже упирается в него, а запрос, выпущенный до переключения, но записываемый после, следует новому значению. Отброшенный кадр — `OutboundBlockedException` (вызывающему), таймер `PING` его глотает. Правила — `GhostMode.decide`:

| Переключатель | Опкод | Что делаем |
|---|---|---|
| `ghostMode` | `1` `PING`, `19` `LOGIN` | `interactive` → `false` (поле добавляется, если его нет) |
| `ghostMode` | `49` `CHAT_HISTORY` | `interactive: true` → `false`; без поля — как есть |
| `ghostMode` | `65` `MSG_TYPING` | не отправляется (любой `type`: текст, голос, кружок, фото, видео, файл, стикер) |
| `hideReadReceipts` | `50` `CHAT_MARK` `READ_MESSAGE` (и без `type`) | не отправляется; `SET_AS_UNREAD` уходит |
| `hideReadReceipts` | `214` `STORIES_MARK` | не отправляется |
| `hideReadReceipts` | `303` `MSG_DELIVERY` | не отправляется (ядро его и так не шлёт) |

Всё остальное уходит без изменений (тест `GhostModeTest.everyOtherOpcodePassesUntouchedWithBothSwitchesOn`).

**Режим призрака** (`MaxClient.ghostMode` / `setGhostMode`, мост `setGhostMode` / `ghostMode()`, событие `ghostMode` с `text` `on`/`off`). Сервер получает `interactive = appActive && !ghostMode`: в `PING`, в первом `LOGIN` (`start()` и `loginWithToken`; сохранённый режим действует уже на нём — `MaxTransport.presetPingInteractive`) и в `LOGIN` переподключения. Состояние приложения (`setInteractive` / `setAppActive`) запоминается (`MaxClient.isInteractive`); при переключении сразу уходит один `PING` с новым значением, при выключении в foreground — `true` (снова «в сети» сразу). `PING` подтверждения входа по QR тоже уважает режим. `MaxClient.sendTyping` в режиме возвращает `false` без кадра; мост отвечает без ошибки.

**Скрытые отметки о прочтении** (`MaxClient.hideReadReceipts`, мост `setHideReadReceipts` / `hideReadReceipts()`, событие `hideReadReceipts`). `MaxClient.markRead(chatId, messageId, mark?)` (и мост `markRead` / `markReadAt`) ничего не шлёт и читает чат локально: `MaxState.localReads` (`chatId → LocalRead(messageId, time)`), `MaxClient.localReadMarks` (только чтение), мост `localReadMarkOf(chatId)`. Метки сохраняются под ключом `max.<ns>.localReads.<userId>` (переживают перезапуск и повторный вход в тот же аккаунт; другой аккаунт их не видит, стор чистит их при смене аккаунта). После каждого изменения стора (`LOGIN`, синхронизация чатов, пуши) `StateReducer.applyLocalReads` кладёт их поверх серверного счётчика: непрочитанные — только сообщения после `max(серверная метка, локальная)`: `0`, если последнее сообщение не новее метки; иначе число чужих сообщений после метки, если стор держит их все; иначе серверный счётчик (меньше узнать нельзя). Локальная метка удаляется, как только своя серверная метка (`participants[me]` / `readMarks`) её догнала. «Отметить непрочитанным» удаляет локальную метку. Выключение ничего не отправляет задним числом: следующая обычная отметка чата уходит как всегда и покрывает всё до её сообщения (`CHAT_MARK` накопительный). `MaxClient.markStorySeen` возвращает `false` без кадра.

**Проверка своего статуса.** `MaxClient.checkOwnPresence(): PresenceInfo?` — `35` `CONTACT_PRESENCE` `{contactIds: [myId]}` мимо кэша (`UsersApi.getPresenceReply`, сырой ответ); `status == 1` — «в сети», иначе `seen`. Нет своей записи в ответе — `null` (ничего не придумываем, «давно» не подставляем). Результат кладётся в стор. Мост `checkOwnPresence(onResult: (IosPresence?, String?, String?) -> Unit)`. Ядро **не опрашивает** само: клиент спрашивает раз в 10–30 с, пока экран со статусом виден и приложение в foreground, в фоне — пауза. Запрос уходит в обоих режимах. Что сервер отдаёт свой статус по своему id — видно по поведению Komet (`SelfCheckService`, раз в 30 с, `forceRefresh`), живым дампом не проверено.

**Komet vs ядро.**

| Komet | Ядро |
|---|---|
| ghost: `PING`/`LOGIN` `interactive = !ghost`, один `PING` при переключении | то же, плюс запомненное состояние приложения (`appActive && !ghost`) |
| ghost: не шлёт «печатает» стикер-панели (другого «печатает» Komet не шлёт) | все `65` отбрасываются в транспорте |
| `antiRead`: не шлёт `CHAT_MARK READ_MESSAGE`, чат читается локально (счётчик 0, своя метка в участниках) | `hideReadReceipts`; локальные метки отдельно от серверных, сохраняются, переживают `LOGIN`, сбрасываются серверной меткой |
| просмотры историй отмечает | `214` не шлём при `hideReadReceipts` |
| `PING` подтверждения QR — `interactive: true` | уважает ghost |
| история отложенных — `CHAT_HISTORY` `interactive: true` | при ghost → `false` |
| самопроверка: `35` по своему id раз в 30 с | `checkOwnPresence()` без опроса в ядре |
| настройки приватности `HIDDEN` («кто видит, что я в сети») и `SHOW_READ_MARK` — отдельно, ghost их не трогает | так же: не меняем автоматически; `HIDDEN` — `MaxClient.setOnlineHidden` / мост `setPrivacyFlag("HIDDEN", …)`, `SHOW_READ_MARK` только читается (см. «Настройки приватности») |

**Что собеседники всё равно видят.** Отправку, правку и удаление сообщений, реакции, черновики (на других своих устройствах), загрузки файлов, звонки — каждое такое действие само раскрывает активность; сервер может по ним считать пользователя активным (не проверено). Другие сессии того же аккаунта шлют свой `interactive`. Кадр, уже записанный в сокет до переключения, не отозвать. Подтверждение QR с `interactive: false` сервер может отклонить (не проверено). Просмотр видео (`VIDEO_PLAY`) не трогаем — считает ли сервер по нему просмотры, неизвестно. Скрыть «в сети» на уровне сервера надёжнее настройкой приватности `HIDDEN`; ядро её не меняет само.

## Настройки приватности (`config.user`, `CONFIG` 22)

Источники: веб-клиент MAX (экран «Безопасность», модель настроек пользователя — значения по умолчанию и допустимые значения), Komet (`security_screen`, `PrivacyConfig`), PyMax. Код не брали.

- Изменение: `22` `CONFIG` `{settings: {user: {KEY: value}}}`; текущие значения — `config.user` в `LOGIN` 19, дальше `NOTIF_CONFIG` 134 и ответы `22`.
- Кэш: `MaxClient.updateUserSettings` кладёт в `accountConfig` известный `user` + отправленные значения + `user` ответа поверх (веб делает upsert ответа), так что ответ с частью ключей или без них ничего не теряет. `updatePrivacy` (PyMax-стиль) теперь идёт тем же путём — раньше он сохранял только хеш, и кэш оставался старым. Пуш `134` сливается по ключам (`AccountConfig.mergedWith`).
- `PrivacyAccess.NOBODY` уходил как `_NONE_` (из PyMax). Веб-модель принимает для `PHONE_NUMBER_PRIVACY` / `INCOMING_CALL` / `CHATS_INVITE` `NOBODY`, экран веба и Komet шлют `NOBODY`; `_NONE_` у веба — значение «без звука» для `PUSH_SOUND` / `CHATS_PUSH_SOUND`, не приватность. Теперь шлём `NOBODY`, читаем `NOBODY`, `_NONE_`, `NONE` (`PrivacyAccess.parse`).
- `FAMILY_PROTECTION` — строка `OFF` / `ADMIN` / `MANAGEABLE` (так читает веб); ядро читало её как bool, и любое реальное значение превращалось в `OFF`. Теперь `FamilyProtection` + сырое значение (`familyProtectionRaw`) для незнакомого.
- Чтение — `PrivacyConfig.from(config)` / `MaxClient.privacy`, значения по умолчанию веба:

| Ключ | Значения | По умолчанию |
|---|---|---|
| `SEARCH_BY_PHONE` | `ALL` / `CONTACTS` | `ALL` |
| `INCOMING_CALL` | `ALL` / `CONTACTS` | `ALL` (было `CONTACTS` в мосте) |
| `CHATS_INVITE` | `ALL` / `CONTACTS` | `ALL` (было `CONTACTS` в мосте) |
| `CONTENT_LEVEL_ACCESS` | bool, `true` — только безопасный контент | `false` |
| `SAFE_MODE` | bool | `false` |
| `HIDDEN` | bool: `true` — «в сети» не видит никто, `false` — контакты | `false` |
| `PHONE_NUMBER_PRIVACY` | `ALL` / `CONTACTS` / `NOBODY` | `CONTACTS` (было `ALL` в мосте) |
| `FAMILY_PROTECTION` | `OFF` / `ADMIN` / `MANAGEABLE` | `OFF`, только чтение |
| `SHOW_READ_MARK` | bool | нет ключа — `null`; только чтение: в официальных клиентах переключателя нет, работает ли на сервере — не проверено |

- Безопасный режим: при `SAFE_MODE` четыре ключа (`SEARCH_BY_PHONE`, `INCOMING_CALL`, `CHATS_INVITE`, `CONTENT_LEVEL_ACCESS`) отдаются принудительными (контакты, безопасный контент) и заблокированы (`PrivacyConfig.locked`, `isReadOnly`); их изменение — `IllegalStateException` без запроса (веб в этом режиме показывает предупреждение вместо списка; по справке смена на телефоне выключает режим — ядро этого не делает само: сначала `setSafeMode(false)`). То же при `FAMILY_PROTECTION = MANAGEABLE` (плюс сам `SAFE_MODE`), но значения тогда показываются как есть. Включение шлёт набор Komet (`SAFE_MODE`, `SAFE_MODE_NO_PIN`, `CONTENT_LEVEL_ACCESS` `true`, три `CONTACTS`), выключение — только `SAFE_MODE` и `SAFE_MODE_NO_PIN` `false`. ПИН-код не поддержан.
- Запись: `MaxClient.setPrivacy(key, value)` (проверка — `PrivacyConfig.payload`) и `setSearchByPhone`, `setIncomingCalls`, `setChatInvites`, `setSafeContentOnly`, `setSafeMode`, `setOnlineHidden`, `setPhoneNumberPrivacy`. Мост: `setPrivacy(key, value: String, onResult)`, `setPrivacyFlag(key, enabled: Boolean, onResult)`, `isPrivacyReadOnly(key)`; `IosAccountSettings` — новые поля `familyProtectionRaw`, `privacyLocked`, `showReadMark`, `showReadMarkKnown`. Ghost mode и скрытые отметки о прочтении эти настройки не трогают.

## Блокеры: нужен снятый трафик

Не реализованы, потому что payload не подтверждён ни kolibri, ни PyMax. Для каждого нужен дамп
запроса/ответа реального Android-клиента (Pixel 8 профиль):

- `81` `STICKER_UPLOAD` / отправка стикера — схема вложения-стикера в `MSG_SEND` не подтверждена
- поиск: `37` `CONTACT_SEARCH` (`60`, `68`, `73` уже в `SearchApi`)
- `193` `STICKER_CREATE`, `194` `STICKER_SUGGEST`, `301` `AUDIO_PLAY`
- звонки: `103`, `195` (ни в одном референсе нет запроса и ответа, см. «Calls — что уже в ядре»); семантика ответа `158` (K12). `76`, `78`, `79`, `84`, `89`, `164`, `166` есть в `CallsApi` по вектору Komet, без WebRTC
- 2FA/пароль: `101`, `116`
- транскрипция `202`/`293`, stories `208`–`218`, `220`
- `LOG` (`5`) — телеметрия, намеренно не отправляется
- пуши черновиков `152` `NOTIF_DRAFT`, `153` `NOTIF_DRAFT_DISCARD` — веб-клиент их игнорирует, форма не подтверждена (ядро читает их терпимо по форме запросов, см. «Черновики на сервере»; нужен дамп, чтобы подтвердить); `21` `SYNC` (загрузка книги) — не подтверждён и не используется

QR-вход на стороне нового устройства (`288`/`289`/`291`) у PyMax требует `deviceType = WEB`; это
противоречит правилу «клиент всегда Android», поэтому не реализован. Подтверждение QR с телефона
(`290`) есть; перед ним `MaxClient.approveQrLogin` шлёт `1 {interactive: true}` и `96` и ждёт 300 мс
(по Komet, без этого сервер отклоняет подтверждение).

## Только в одном источнике

Присутствуют в `Opcodes.kt` как union-таблица; API — только там, где есть вектор:

**Только PyMax:** `31` `SEARCH_FEEDBACK`, `62` `CHAT_LIVESTREAM_INFO`, `91` `MSG_GET_COMMENTS_INFO` и `94` `MSG_DELETE_USER_COMMENTS` (оба в API по вектору PyMax), `125` `LOCATION_SEND`, `126` `LOCATION_REQUEST`, `256` `ORG_INFO`, `288` `GET_QR`, `289` `GET_QR_STATUS`, `291` `LOGIN_BY_QR`, `302` `BANNERS_GET`, `303` `MSG_DELIVERY`.

**Только kolibri:** `164` `VIDEO_CHAT_DELETE_HISTORY` (в API по схеме Komet).

QR-вход `288`/`289`/`291` описан у PyMax как web-клиент; с телефона реализован только `AUTH_QR_APPROVE` `290`.
