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
2. Мост iOS при известном конфиге без записи чата отдавал `muted = 0`. Orbitle пишет `0` в базу как
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
- Пересылка: пакетной формы нет (ни у веб-клиента, ни у Komet, PyMax, kolibri). Каждое сообщение — отдельный `64` `MSG_SEND` с `link: {type: "FORWARD", chatId: <источник>, messageId}` и своим `cid`. `messageId` уходит строкой, как у PyMax (Komet шлёт числом; какой вид обязателен, не проверено). `MaxClient.forwardMessages` сортирует известные стору сообщения по `time` (от старых к новым, неизвестные — после, в данном порядке), необязательный комментарий уходит первым обычным текстом. Первая ошибка останавливает остальное: `ForwardBatch.failedIndex` — индекс неотправленного, `sent` — что ушло (в мосте `IosForwardResult.failedAt`, `-1` — всё отправлено). Пересылка в несколько чатов сразу — цикл на стороне приложения (веб: сначала комментарий во все чаты, потом каждое сообщение во все чаты).
- Копирование текста — только на клиенте, протокола нет.

### Черновики на сервере

- `176` `DRAFT_SAVE` `{chatId | userId, draft: {text?, elements, replyTo?}}` → `{time}`. Диалоги и «Избранное» адресуются `userId` собеседника (для «Избранного» — свой id), остальные чаты — `chatId` (`Drafts.address`). Пустой текст не шлётся, `elements` — всегда (может быть `[]`). `time` ответа становится `updateTime` черновика.
- `177` `DRAFT_DISCARD` `{chatId | userId, time}`, `time` — `updateTime` удаляемого черновика (`MaxClient.discardDraft` берёт его из стора; если черновика нет и время не передано, запрос не уходит).
- Вход: ответ `19` `LOGIN` несёт `drafts: {chats: {saved: {chatId: draft}, discarded: {chatId: time}}, users: {saved: {userId: draft}, discarded: {userId: time}}}`. Ключ `users` переводится в id диалога `me xor userId`. Черновик: `{saveTime, text, elements, replyTo?}`. В сторе (`MaxState.drafts`) побеждает более позднее время (равное заменяет); `discarded` удаляет черновик, если тот не новее времени удаления (веб-клиент удаляет без проверки — небольшое расхождение, чтобы не потерять свежий локальный черновик). При входе другим аккаунтом черновики прежнего сбрасываются.
- Исправлено: `SyncState.updatedBy` ставил `draftsSync` во время входа, хотя черновики выбрасывались, и сервер больше их не присылал. Теперь `draftsSync` при входе не меняется; ядро черновики между запусками не хранит, поэтому шлёт `-1` и получает все. `CredentialStore` пишет маркер под новым ключом `sync.drafts.v2`, старое значение `sync.drafts` (время входа) не читается и удаляется. `MaxState.draftsSyncTime` — наибольшее `updateTime` из стора, для приложения, которое будет хранить черновики само.
- Пуши `152` / `153`: веб-клиент их игнорирует, форма не подтверждена — ядро их не разбирает (остаются `MaxEvent.Unknown` с сырым телом).

### Форматирование текста

- `elements`: `[{type, from, length, entityId?, entityName?, attributes?}]`. `from` / `length` — в единицах UTF-16 (индексы `String` в Kotlin и `NSString`). Элементы могут перекрываться.
- Типы (`TextElementType`, в мосте `IosTextMarkType`): `STRONG` (жирный), `EMPHASIZED` (курсив), `UNDERLINE`, `STRIKETHROUGH`, `MONOSPACED`, `LINK` (`attributes.url`), `QUOTE`, `HEADING`, `USER_MENTION` (`entityId` — пользователь, бывает `entityName`), `ANIMOJI` (`entityId`, `attributes.animojiLottieUrl`).
- Чтение — как у веб-клиента: `CODE` читается как `MONOSPACED`; незнакомый тип сохраняется как есть; нет `from` — 0; нет `length` — до конца текста; элементы нулевой длины выбрасываются.
- Отправка и правка: `64` и `67` с `elements`. Элемент за пределами текста отбрасывается. `67` всегда несёт полный список, поле не опускается; `elements: []` снимает форматирование. Ответ `67` приходит без реакций; `StateReducer.putEditedMessage` сохраняет известные реакции.
- Мост iOS: `sendFormattedText` / `editMessage` принимают метки (`IosTextMark`) или `elementsJson` — JSON-массив тех же объектов (`TextElementsJson`); битый JSON — ошибка `UNKNOWN` до отправки. Исправлено: `editMessage(chatId, messageId, text)` без форматирования раньше слал `[]` и стирал разметку; теперь сохраняет форматирование сообщения из стора там, где оно помещается в новый текст. Приём: `MaxMessage.textElements`, в мосте `IosMessage.marks` / `IosMessage.elementsJson` / `IosEvent.marks`.

### Участники группы

- `59` `CHAT_MEMBERS` `{chatId, type: "MEMBER", marker, count: 50}`: первая страница — `marker` 0, дальше `marker` из ответа. Ответ без `marker` — последняя страница: `ChatMembersPage.marker == null` (раньше ядро превращало отсутствие в 0, и загрузчик мог начать сначала). Конец (`ChatMembersResult.nextMarker == null`, в мосте пустая строка): нет `marker`, он 0, повторяет запрошенный или страница пустая. Загрузчик «кто прочитал» (`ReadersApi`) останавливается так же.
- Поиск: `59` `{chatId, type, query}` (`ChatsApi.searchChatMembers`, `MaxClient.searchChatMembers`, мост `searchChatMembers`). Типы списка (`MemberListType`): `MEMBER`, `ADMIN`, `BLOCKED_MEMBER`, `JOIN_REQUEST`, `COMMENTS_BLACKLIST`.
- Роли (`ChatRoles`) — из чата (`48` `CHAT_INFO` или список чатов): `owner` — владелец, `admins` — id админов, `adminParticipants` — `{userId: {permissions, alias, inviterId?}}` (биты прав как прислал сервер). Если чата нет в сторе, `MaxClient.loadChatMembers` перед первой страницей спрашивает `48` (без ошибки при сбое: тогда все — обычные участники). Профили и `presence` (если новее) уходят в стор.

### Контакты

- Переименовать: `34` `CONTACT_UPDATE` `{contactId, action: "UPDATE", firstName, lastName}`; пустая фамилия уходит `null`, каждое имя не длиннее 64 символов. Ответ `{contact}`, имя становится записью `CUSTOM`.
- Удалить: `34` `{contactId, action: "REMOVE"}`, отмена — `ADD`. Стор убирает id из контактов и запись `CUSTOM`; пользователь и чат остаются (с контактом из ответа, если он есть).
- Добавить по телефону: `41` `CONTACT_ADD_BY_PHONE` `{phone, firstName?, lastName?}` → `{contact, new}` (`ContactByPhone.isNew`).
- Пуш `131` `NOTIF_CONTACT` `{contact}` (`MaxEvent.ContactUpdated`): стор обновляет пользователя, если `updateTime` не старее известного; в мосте — событие `contact`.
- Книга устройства на сервер не отправляется: `21` `SYNC` не используется (форма не подтверждена), импорт удалён. Ядро только принимает локальные имена (`setAddressBook`, `setLocalName`) и сопоставляет их по нормализованному телефону.

### Нормализация телефона (`PhoneNumbers.normalize`)

Ядро следует правилам, фикстуры к которым лежат в Orbitle `test-fixtures/names/` (по одному тесту на правило в `ContactsEditTest`, правило 7 — в `ContactNamesStateTest`):

1. убираются пробелы, дефисы, скобки и точки; ведущий `+` сохраняется;
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
4. первая запись `names`;
5. телефон (`+7…`);
6. «Участник» (`ContactNames.label`, `MaxState.displayLabel`, `MaxClient.displayLabel`).

Книга и локальные имена — только на устройстве. Переживают смену аккаунта в `LOGIN`, сбрасываются при выходе (`MaxStore.clear`).

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
- пуши черновиков `152` `NOTIF_DRAFT`, `153` `NOTIF_DRAFT_DISCARD` — веб-клиент их игнорирует, форма не подтверждена; `21` `SYNC` (загрузка книги) — не подтверждён и не используется

QR-вход на стороне нового устройства (`288`/`289`/`291`) у PyMax требует `deviceType = WEB`; это
противоречит правилу «клиент всегда Android», поэтому не реализован. Подтверждение QR с телефона
(`290`) есть; перед ним `MaxClient.approveQrLogin` шлёт `1 {interactive: true}` и `96` и ждёт 300 мс
(по Komet, без этого сервер отклоняет подтверждение).

## Только в одном источнике

Присутствуют в `Opcodes.kt` как union-таблица; API — только там, где есть вектор:

**Только PyMax:** `31` `SEARCH_FEEDBACK`, `62` `CHAT_LIVESTREAM_INFO`, `91` `MSG_GET_COMMENTS_INFO` и `94` `MSG_DELETE_USER_COMMENTS` (оба в API по вектору PyMax), `125` `LOCATION_SEND`, `126` `LOCATION_REQUEST`, `256` `ORG_INFO`, `288` `GET_QR`, `289` `GET_QR_STATUS`, `291` `LOGIN_BY_QR`, `302` `BANNERS_GET`, `303` `MSG_DELIVERY`.

**Только kolibri:** `164` `VIDEO_CHAT_DELETE_HISTORY` (в API по схеме Komet).

QR-вход `288`/`289`/`291` описан у PyMax как web-клиент; с телефона реализован только `AUTH_QR_APPROVE` `290`.
