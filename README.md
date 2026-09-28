# max-kmp-core

Нативное сетевое ядро мессенджера Max на **Kotlin Multiplatform**.

Цель — переносимый клиентский core (протокол, TLS-транспорт, сессия, авторизация),
который можно подключать к Android, iOS и desktop без дублирования логики.
Архитектура опирается на два референса:

- [KometTeam/kolibri](https://github.com/KometTeam/kolibri) — Rust-ядро: бинарный
  фрейминг, MessagePack, LZ4/Zstd, TLS-сессия, handshake / ping / reconnect,
  медиа-загрузки и сигналинг звонков.
- [MaxApiTeam/PyMax](https://github.com/MaxApiTeam/PyMax) — Python-клиент с
  типизированными payload'ами и полным списком опкодов.

Сейчас репозиторий — **каркас без реализации**: модули и публичные типы
размечены, логика помечена `TODO`.

## Стек

- Kotlin Multiplatform
- kotlinx-coroutines
- Ktor client engines (HTTP-слой, пока не используются); транспорт — raw TLS через `java.net.Socket`/`javax.net.ssl` (JVM/Android) и Apple Network.framework (iOS; прокси — iOS 17+)
- kotlinx-serialization (JSON-хелперы); MessagePack — собственный кодек на чистом Kotlin (`DefaultMessagePackCodec`)

## Модули

| Модуль | Назначение |
|--------|------------|
| [`core`](core/) | Общий KMP-код: протокол (фрейминг, опкоды, MessagePack), транспорт (TLS), сессия (handshake, ping, reconnect), авторизация |
| [`shared`](shared/) | Публичный API для всех платформ: `Session`, `request(opcode, payload)`, поток пушей |
| [`android`](android/) | Android-таргет, JNI-обёртки (если понадобится нативный слой) |
| [`ios`](ios/) | iOS-таргет, cinterop к C ABI |
| [`desktop`](desktop/) | Desktop (Compose Multiplatform) для Windows / Linux / macOS |

## Таргеты

- Android
- iOS (`iosArm64`, `iosSimulatorArm64`)
- JVM / Desktop

## Статус

Скелет Gradle + пустые пакеты. Следующие шаги:

1. Зафиксировать MessagePack-кодек для KMP.
2. Портировать 10-байтовый заголовок и таблицу опкодов (из kolibri / PyMax).
3. Реализовать TLS-сокет и session state machine.
4. Собрать auth-flow (SMS / token) поверх `request`.
5. Подключить Android / iOS / desktop потребители.


## Документация

- [docs/protocol.md](docs/protocol.md) — архитектура протокола Max (референс kolibri / PyMax).
- [docs/architecture.md](docs/architecture.md) — раскладка модулей и классов KMP-ядра.
- [docs/ios-plan.md](docs/ios-plan.md) — план iOS-клиента на Swift (этап 1).

## Лицензия

MIT — см. [LICENSE](LICENSE).
