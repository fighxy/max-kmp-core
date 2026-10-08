# AGENT_NOTE: служебная записка для агента, который подхватит работу

> Состояние на 2026-09-29. Файл в git, вместе с остальным ядром. Хеш HEAD здесь не фиксирую: коммит этой записки сам его сдвигает.

## 1. Проект и цель

`fighxy/max-kmp-core` — приватное сетевое ядро на Kotlin Multiplatform для мессенджера Max. Платформы: iOS (приоритет), Android и JVM desktop. Протокол восстановлен по референсам KometTeam/kolibri и MaxApiTeam/PyMax; с 2026-09-29 Иван разрешил и KometTeam/Komet (клиент на Dart). Их используем только как описание схем, **код не копируем** (там GPL). **У Komet актуальная ветка — `feature/FullStack`** (не `main`): схемы сверять с ней (указание Ивана от 2026-10-05).

Цель: готовое ядро, которое iOS- и Android-приложения подключают через модуль `shared` (фасад `MaxClient`). В ядре есть транспорт, сессия, авторизация, API, события, локальное состояние и медиа.

Модули:
- `core/src/commonMain/kotlin/com/max/core/`: `transport`, `protocol`, `session`, `auth`, `api`, `events`, `state`, `media`, `calls`, `MaxError.kt`.
- Платформенный код: `core/src/iosMain` (Network.framework, NSURLSession) и `core/src/jvmAndroidShared` (сокеты, OkHttp).
- `shared/`: `MaxClient` и `PlatformSession` с хранилищами учётных данных (iOS Keychain, Android SharedPreferences, JVM файл).
- `android/`, `ios/`, `desktop/` — оболочки. У `android/` Java и Kotlin JVM target — 17. `ios/` собирает статический XCFramework `MaxIos` (`assembleMaxIosReleaseXCFramework`). Для Swift наружу только `MaxIosClient` и плоские `Ios*`; `MaxClient` и токен Keychain остаются внутри.
- Документация: `README.md`, `docs/protocol.md`, `docs/opcodes.md`, `docs/architecture.md`, `docs/ios-plan.md`.

## 2. Что сделано

- Ветка `main`, всё запушено.
- Звонки: разбор `vcp`, `MaxEvent.CallStart` (137), `CallsApi.requestCallsToken` (158, ответ observed-not-ref, `icyfalc0n/maxcalls`), сборка ws2 URL. В query уходит профиль Android Pixel 8 (`UserAgentInfo`: Pixel 8, 26.25.0, Android 14), не строки kolibri-net SDK. `capabilities` и `clientType` остаются константами сигналинга.
- `ConversationParams.decode` отклоняет заявленную длину LZ4 больше 64 КиБ до Base64 и распаковки. Потолок пакета 32 МиБ не менялся. Битый `vcp` по-прежнему оставляет `CallStart` с `params = null`.
- Дыра истории хранится якорем (id локального хвоста на момент дыры). Закрывает её только страница догрузки истории (`StateReducer.putHistoryPage` / `MaxStore.putHistory`), если содержит этот id; пустая страница тоже закрывает. Правка, повтор или вставка сообщения (`putMessages`) дыру не закрывают. Одна более новая страница дыру не закрывает.
- `MaxClient.logout` отменяет догрузку, останавливает `EventRouter` и дожидается текущего события, затем чистит снимок и снова запускает роутер. Сохранение токена и его очистка идут под одним mutex. Чужой аккаунт при `LOGIN` заменяет снимок целиком, а не сливает его с остатком.
- `fillGaps` листает историю назад (`gapFillCount` 40, не больше `gapFillPageLimit` страниц, по умолчанию 16). Если упёрлись в лимит или самая старая id не сдвигается, дыра остаётся открытой.
- Модуль `android/` есть. Java и Kotlin JVM target — 17. CI компилирует `:core`, `:shared` и `:android`, на устройстве не запускает.
- JVM-файл токена на POSIX создаётся сразу как `rw-------`. Каталог ставится в `rwx------`, только если хранилище само его создало; права существующего каталога не трогаются. Если права файла не встали, запись падает.
- Опкод 8 ядро шлёт как PyMax `LOGIN2` `{needProfile, contactsSync, configHash}`. Вопрос K11 (имя kolibri `CONTACTS_GET`) открыт.
- Локальный прогон тестов — Gradle (`:core:jvmTest`, `:shared:jvmTest`). Скрипт `/workspace/tools/run-tests.sh` относится к среде, где ядро писали, на этой машине его нет.

- Контакты и журнал звонков для iOS: `MaxState.contactIds` (список `contacts` из `LOGIN`, без себя; дельта-`LOGIN` без поля его не трогает), `CallsApi.history()` (79 по схеме Komet), `MaxIosClient.loadContacts` / `loadCallHistory` (`IosContact`, `IosCall`). У диалога в `IosChat` имя и аватар собеседника (из `participants` и `users`, недостающие догружаются `CONTACT_INFO`), плюс `avatarUrl` и `lastAuthorId`.

- Полный список чатов: `LOGIN` отдаёт только первые чаты и `chatMarker`, остальное — страницы `CHATS_LIST {marker, count: 50}` с `marker` следующей страницы (как Komet `paginateChats`). `ChatsApi.fetchChatsPage`, `MaxClient.loadAllChats()`; `MaxIosClient.loadChats` листает всё один раз после входа, потом обновляет только первую страницу.
- Закреплённые чаты (схема Komet, код не брали): это `favorites` папки «Все чаты» (`all.chat.folder`). Приходят в `LOGIN` (`config.chatFolders`), в ответе `FOLDERS_GET` 272 и пушем `NOTIF_FOLDERS` 277 (`MaxEvent.FoldersChanged`); лежат в `MaxState.chatFolders`, наружу — `pinnedChatIds`, закреплённые первыми в `chatList`. Закрепить, открепить и переставить — один `FOLDERS_UPDATE` 274 со всем списком (`MaxClient.setPinnedChats`; стор меняется только после ответа сервера). iOS: `MaxIosClient.setPinnedChats` / `watchPinnedChats`, первый `loadChats` после входа ещё и делает `FOLDERS_GET`. На живом сервере не проверено.
- Звонки 103 и 195 (2026-10-08): схем нет ни в kolibri, ни в PyMax, ни в Komet, поэтому методы не добавлены. В `Opcodes.kt` у обоих `TODO: payload unknown`, в KDoc `CallsApi` сказано, почему их нет. Тест `CallsApiTest.inboundCallsAndCallMembersKeepTheirReferenceCodes` закрепляет коды и имена.
- Контакты из ответа опкода 8 (`contactInfos` у PyMax, `contacts` у Komet) тоже попадают в `MaxState.contactIds` (`StateReducer.putContacts`).

## 3. Совместимость с чужими коммитами

Ночные изменения ничего в них не ломают и не переписывают:
- `CallStart` проходит через `EventRouter`, а `MaxState` его сознательно игнорирует, потому что состояния звонков в ядре нет;
- раздел про звонки в `opcodes.md` перенесён в общую таблицу «сделано и блокеры» с сохранением содержания;
- все тесты из чужих коммитов проходят.

## 4. Состояние CI (`.github/workflows/ios-core.yml`, джоба `ios-and-jvm`)

- macos-14: JVM-тесты `:core` и `:shared`, компиляция и тесты Kotlin/Native для iOS Simulator, `compileDebugKotlinAndroid` для `:core` и `:shared`, `compileDebugKotlin` для `:android`, линковка `:ios:linkDebugFrameworkIosSimulatorArm64`, тесты фасада `:ios:iosSimulatorArm64Test`, Android unit-тесты `:shared:testDebugUnitTest` (fake SharedPreferences, плюс common-тесты `shared` на Android JVM). На устройстве ничего из этого не запускается. Release XCFramework собирает приложение Orbitl.
- Рассинхрон Java 1.8 и Kotlin 17 на KMP-модулях закрыт `compileOptions` Java 17 в `core` и `shared`. У `:android` то же самое: `compileOptions` и `jvmTarget` 17.
- Предупреждение `kotlin.mpp.enableCInteropCommonization` безвредно.

## 5. Что глянуть в первую очередь

1. **Сборка Android:** `android/build.gradle.kts` уже на Java 17, модуль компилируется в CI и не запускается на устройстве. SharedPreferences по-прежнему нужен один вызов `PlatformSession.init(context)` в приложении.
2. **Версии:** Gradle 8.10.2, Kotlin 2.1.10, JVM target 17, `okhttp = "4.12.0"` в version catalog. Движки Ktor удалены как неиспользуемые.
3. **Звонки:** `core/.../calls/` (`Vcp.kt`, `CallsApi.kt`), `MaxEvent.CallStart`. Ответ на 158 не подтверждён референсами. Заявленная длина `vcp` ограничена 64 КиБ.
4. **Что проверить на живом сервере:** догрузку истории после переподключения, когда пропуск больше одной страницы в 40 сообщений; параллельную загрузку видео по частям на `VIDEO_UPLOAD`; ответ на 158.
5. **Android на устройстве:** хранилище SharedPreferences только компилируется, его не запускали. Нужен один вызов `PlatformSession.init(context)`.
6. **Предупреждение в логах CI** про `kotlin.mpp.enableCInteropCommonization` безвредно.

## 6. Что НЕ трогать и что уже решено

- **Клиент всегда представляется Android** (Pixel 8, Android 14, 26.25.0/6790, arm64-v8a). Реальные данные iPhone или iOS нигде не отправлять. `platformName()` в протоколе не используется.
- Не реализовывать опкоды с неизвестной схемой (`TODO: payload unknown`) и не выдумывать payload. Блокеры, для которых нужен снятый трафик Android-клиента:
  - стикеры (81);
  - исходящее «печатает» (65);
  - поиск по контактам (37); `60`, `68` и `73` уже в `SearchApi` (73 — вектор Komet `{chatId, query, count}`);
  - опкоды 193, 194, 301;
  - звонки 103 (`GET_INBOUND_CALLS`) и 195 (`VIDEO_CHAT_MEMBERS`): 2026-10-08 проверены kolibri, все ветки PyMax и Komet `feature/FullStack`, везде только константа, ни запроса, ни ответа (подробности в `docs/opcodes.md`). Метода в `CallsApi` нет, observed-not-ref тоже не подходит. `76`, `78`, `79`, `84`, `89`, `164`, `166` есть в `CallsApi` по вектору Komet, без WebRTC (`hexCapability` `3c02f`);
  - 2FA-восстановление (101, 104, 105, 116);
  - транскрипция (202, 293);
  - истории (208–220).
- Вход по QR на новом устройстве (288, 289, 291) сознательно не делаем: он требует веб-тип устройства. Подтверждение QR с телефона (290) есть.
- ws2-сигнализация и WebRTC остаются на стороне приложения, в ядре только сборка URL.
- Не копировать GPL-код, не упоминать сторонние мессенджеры в коде и истории.
- Коммиты: автор и коммиттер `Ivan K <57476075+fighxy@users.noreply.github.com>`, английский язык, conventional commits.
- Пушить только по явной просьбе Ивана. Force-push только после подтверждения.
- Ревью кода по диапазону `f58f3bc..38d7497` Иван отменил, не начинать без новой просьбы.

## 7. Исправления по ревью e385796 (15 пунктов, по коммиту на пункт)

Поведение:
- **Swift-граница.** Все публичные `suspend` у `MaxClient` и `Session` помечены `@Throws(CancellationException::class, Exception::class)`, конструктор `MaxClient` и `openSession` — `@Throws(Exception::class)`. `MaxIosClient` ловит всё и отдаёт вид ошибки в callback ровно один раз, включая `CANCELLED` и вызовы после `close`. Ошибка Keychain приходит видом ошибки, процесс не падает. Swift API `MaxIosClient` не менялся. API ядра (`client.api`, фреймворк MaxCore) аннотациями не покрыт, Swift работает только через `MaxIosClient`.
- **Полный снимок.** Первый `LOGIN` нового `MaxClient` шлёт нулевые маркеры и `configHash` по умолчанию: снимок не хранится между процессами, дельта на пустой кэш потеряла бы данные.
- **Поздние ответы.** Операции берут билет (сессия входа + поколение аккаунта) до запроса и применяют результат под lifecycle-lock. Если за это время был logout, отказ токена, close или другой аккаунт, бросается `SessionClosedException`, стор и токен не меняются.
- **logout/close из обработчика события** работают: роутер не ждёт сам себя, обязательная очистка идёт в `NonCancellable`.
- **События без потерь.** Роутер `MaxClient` читает `session.reliablePushes` (неограниченный канал на подписчика). В два этапа: сначала применение к стору, потом обработчики в отдельной корутине, так что медленный обработчик не блокирует ни стор, ни чтение сокета. Публичный `MaxClient.events` остаётся с потерями для медленного подписчика.
- **Отправка.** Успешный `sendText` делает сообщение превью чата (`lastMessage`, `lastEventTime`) без роста unread.
- **Unread.** Пересчёт от read-mark используется, только если кэш покрывает весь диапазон; иначе берётся `max(серверный счётчик, посчитанный)`.
- **LOGIN2.** Профиль из `LOGIN2` сливается с `LOGIN` (`LoginResult.withLogin2`) до применения к стору и сохранения учётных данных; устаревший `login2Result` не переиспользуется.
- **cid.** Один потокобезопасный `ClientIdGenerator` на сессию (`MaxApi.cids`), его же получает `MediaApi`.
- **Таймаут запроса** покрывает ожидание write-lock, запись и ответ. При таймауте посреди записи соединение закрывается, pending-запрос удаляется при любом выходе.
- **Хранилища.** `FileKeyValueStore` публикует кэш только после успешной записи и не меняет права чужого каталога. Android `SharedPreferencesStore` бросает `KeyValueStoreException`, если `commit()` вернул `false`.
- **putChats** применяет пачку за один проход и ищет дыры только у изменённых чатов.

Новое в публичном API (всё аддитивно): `MaxTransport.reliablePushes()`, `SessionMachine.reliablePushes`, `StateReducer.putSentMessage` / `putHistoryPage`, `MaxStore.putSentMessage`, `LoginResult.withLogin2`, `MaxApi.cids` и конструкторы `MaxApi(..., cids)`, перегрузка `MediaApi(session, http, cids, clock)`, `KeyValueStoreException`, `SessionClosedException` у операций `MaxClient`, аннотации `@Throws`. Поведенческое изменение для вызывающих: `StateReducer.putMessages` больше не закрывает дыру истории.
