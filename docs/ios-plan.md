# План iOS-клиента (Swift), этап 1

> **План, не реализация.** Протокольные факты — [protocol.md](protocol.md); раскладка ядра — [architecture.md](architecture.md). Приоритеты: **P0** — войти и переписываться; **P1** — удобство и надёжность; **P2** — остальное / экспериментальное.

## 1. Как Swift вызывает ядро

| Вариант | Что это | Вердикт |
|---------|---------|---------|
| **Kotlin/Native framework export → XCFramework** | `binaries.framework` в `:shared`/`:ios` (уже есть `MaxShared`, `MaxIos`, static). Kotlin/Native генерирует Obj-C header; Swift импортирует модуль | **P0, выбран.** Ядро целиком на Kotlin — отдельный C ABI не нужен |
| **cinterop + `maxc.def`** | `.def` описывает C-заголовки/статическую либу, которые Kotlin/Native **импортирует** (Kotlin → C). Сейчас `ios/src/nativeInterop/cinterop/maxc.def` — заглушка (`headers = maxc.h`, `staticLibraries = libmaxc.a` закомментированы) | P2/опционально: только если появится внешняя C-библиотека (например, нативный LZ4/Zstd или Rust-ядро через C ABI) |
| Свой C ABI из Kotlin (`@CName`, экспорт C-символов) | Kotlin/Native → C-функции, Swift зовёт как C | Не нужен на этапе 1 |

Роль `maxc.def`: декларация для cinterop (`headers`, `staticLibraries`, `libraryPaths`, `package = com.max.ios.cinterop`). Это вход **в** Kotlin, а не выход к Swift. Swift-артефакт — статический XCFramework: `./gradlew :ios:assembleMaxIosReleaseXCFramework` (`ios/build.gradle.kts`). Результат: `ios/build/XCFrameworks/release/MaxIos.xcframework`.

Наружу из фреймворка экспортируется только `com.max.ios.MaxIosClient` и плоские типы `Ios*` (колбэки вместо `Flow`). `MaxClient` остаётся внутри: профиль устройства по-прежнему Android Pixel 8, токен — в Keychain `com.max.kmp.<namespace>`.

## 2. Swift-обёртки (P0)

```swift
// Тонкий слой над MaxIos
actor MaxSession {                                   // над com.max.shared.Session
    func connect() async throws -> HandshakeInfo      // TLS + opcode 6
    func request(_ opcode: UInt16, _ payload: Data) async throws -> Data
    var events: AsyncStream<CoreEvent> { get }        // pushes (NOTIF_MESSAGE 128, NOTIF_TYPING 129, NOTIF_MARK 130, …)
    var state: AsyncStream<SessionState> { get }      // Disconnected/Connecting/Connected/Online
    func close() async
}
actor MaxAuth {                                       // над AuthService
    func requestCode(phone: String) async throws -> String         // AUTH_REQUEST 17 → temp token
    func verify(token: String, code: String) async throws -> AuthStep  // AUTH 18 → .loggedIn(token) / .password(trackId, hint) / .register
    func checkPassword(trackId: String, password: String) async throws -> String  // 115
    func login(token: String) async throws -> LoginSnapshot         // LOGIN 19
}
```

- `suspend` из Kotlin → `async throws` (через completion handler Kotlin/Native или SKIE-подобный адаптер — выбор P0).
- `Flow` → `AsyncStream` через callback-адаптер в `IosBridge` (отмена стрима → cancel job).
- Потоки: ядро на корутинах; UI-модели — `@MainActor`.

## 3. UI (SwiftUI)

| Экран | P | Протокол |
|-------|---|----------|
| Ввод телефона → код из SMS | **P0** | 17 → 18 → 19 (protocol.md §D.1) |
| Пароль 2FA (если `passwordChallenge`) | **P0** | 115 `AUTH_LOGIN_CHECK_PASSWORD` (§D.2); builders только в PyMax |
| Регистрация нового аккаунта | P1 | `REGISTER` token, PyMax `ConfirmRegistrationPayload` (§D.1, §F.1) |
| Вход по QR | P1 | PyMax: `GET_QR` 288 → poll `GET_QR_STATUS` 289 → `LOGIN_BY_QR` 291; approve с другого устройства 290 (§D.3). В kolibri 288/289/291 отсутствуют — проверить на wire |
| Список чатов | **P0** | чаты из ответа `LOGIN` (PyMax `LoginResponse.chats`) + `CHATS_LIST` 53 |
| История сообщений | **P0** | `CHAT_HISTORY` 49 (PyMax `ChatHistoryPayload`, `backward=40`) |
| Отправка текста | **P0** | `MSG_SEND` 64 (`chatId`, `message{text,cid,elements,attaches}`) |
| Входящие обновления | **P0** | push `NOTIF_MESSAGE` 128, `NOTIF_MSG_DELETE` 142, `NOTIF_MARK` 130, `NOTIF_TYPING` 129 |
| Прочитано / typing | P1 | `CHAT_MARK` 50, `MSG_TYPING` 65 |
| Фото/файлы | P1 | §G (PHOTO_UPLOAD 80, FILE_UPLOAD 87) |
| Реакции, редактирование, удаление | P1 | 178/179, 67, 66 |
| Звонки | P2 | §H (vcp + ws2; WebRTC на стороне приложения) |
| Stories | **P2, EXPERIMENTAL** | см. §6 |

## 4. Хранение

- **P0:** login token и `deviceId`/`instanceId` — Keychain (`TokenStore` actual в `iosMain`). Стабильность identity важна: kolibri `call_bot` сохраняет `device_id`/`instance_id`, чтобы токен оставался валидным (protocol.md §D.1).
- P1: sync markers (`chatsSync`, `contactsSync`, …, `configHash`) для повторного `LOGIN` (PyMax `SyncPayload`).
- P1: локальный кэш чатов/сообщений (SQLite/SwiftData) — вне ядра.

## 5. Push-уведомления

| Пункт | P | Статус |
|-------|---|--------|
| Capability Push Notifications, entitlement `aps-environment` (`development`/`production`) | P1 | стандарт iOS |
| Регистрация APNs, получение device token | P1 | стандарт iOS |
| **Передача токена серверу Max** | P1 | **не найдено** в обоих репо: опкода/payload регистрации push-токена нет. Есть только `userAgent.pushDeviceType` в handshake; во всех примерах значение `"GCM"` (protocol.md §C.2). Какое значение для iOS (`APNS`?), и каким опкодом отдать токен — **неизвестно**, нужен эксперимент/анализ официального клиента |
| Notification Service Extension (расшифровка/обогащение, mutable-content) | P2 | формат push-payload от сервера — **неизвестно** |
| Foreground-обновления без APNs | **P0** | держать сессию: PING 29 s с `interactive` (§C.3); при уходе в фон — `interactive=false` / разрыв, при возврате — reconnect + `LOGIN` |
| Background refresh | P2 | зависит от push-регистрации |

## 6. Stories — EXPERIMENTAL (P2)

Опкоды 208–220 (`STORIES_LIST` 208, `STORIES_LIST_BY_OWNER(_ID)` 209, `STORIES_GET_BY_OWNER(_ID)` 210, `STORIES_GET_STATS` 211, `STORIES_GET_DETAILED_STATS` 212, `STORIES_REACT` 213, `STORIES_MARK` 214, `STORIES_SEND` 215, `NOTIF_STORIES_UPDATE` 216, `STORIES_EDIT` 217, `STORIES_DELETE` 218, `STORIES_GET_BY_STORY_ID` 220) объявлены в `kolibri:kolibri-net/src/protocol/opcodes.rs:201-213` и `PyMax:src/pymax/protocol/enums.py:158-169`. **Ни в одном репо нет payload-схем, API-методов или моделей** (поиск `stor*` вне enum в PyMax — не найдено). Статус: payload неизвестен, реализация только после wire-исследования, за feature-флагом.

## 7. Сводка приоритетов

- **P0:** XCFramework export; `MaxSession`/`MaxAuth` (async/await, `AsyncStream`); SMS-вход + 2FA пароль; Keychain token/identity; список чатов, история, отправка текста, входящие 128; foreground-сессия (PING, reconnect 3 s → 96 s).
- **P1:** QR-вход, регистрация, прочитано/typing, фото/файлы, реакции/правка/удаление, APNs + регистрация токена (после выяснения опкода), proxy, Минцифры CA opt-in, кэш.
- **P2:** Notification Service Extension, звонки (CallSignaling + WebRTC), stories (EXPERIMENTAL), видео parallel upload, cinterop `maxc.def` при внешней C-библиотеке.

Связанные документы: [architecture.md](architecture.md), [protocol.md](protocol.md) (§D авторизация, §E опкоды, §K открытые вопросы).
