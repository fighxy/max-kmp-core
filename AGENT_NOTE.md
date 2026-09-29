# AGENT_NOTE: служебная записка для агента, который подхватит работу

> Состояние на 2026-09-29. Файл в git, вместе с остальным ядром. Хеш HEAD здесь не фиксирую: коммит этой записки сам его сдвигает.

## 1. Проект и цель

`fighxy/max-kmp-core` — приватное сетевое ядро на Kotlin Multiplatform для мессенджера Max. Платформы: iOS (приоритет), Android и JVM desktop. Протокол восстановлен по двум референсам, KometTeam/kolibri и MaxApiTeam/PyMax. Их используем только как описание схем, **код не копируем** (там GPL).

Цель: готовое ядро, которое iOS- и Android-приложения подключают через модуль `shared` (фасад `MaxClient`). В ядре есть транспорт, сессия, авторизация, API, события, локальное состояние и медиа.

Модули:
- `core/src/commonMain/kotlin/com/max/core/`: `transport`, `protocol`, `session`, `auth`, `api`, `events`, `state`, `media`, `calls`, `MaxError.kt`.
- Платформенный код: `core/src/iosMain` (Network.framework, NSURLSession) и `core/src/jvmAndroidShared` (сокеты, OkHttp).
- `shared/`: `MaxClient` и `PlatformSession` с хранилищами учётных данных (iOS Keychain, Android SharedPreferences, JVM файл).
- `android/`, `ios/`, `desktop/` — оболочки приложений. У `android/` Java и Kotlin JVM target — 17.
- Документация: `README.md`, `docs/protocol.md`, `docs/opcodes.md`, `docs/architecture.md`, `docs/ios-plan.md`.

## 2. Что сделано

- Ветка `main`, всё запушено.
- Звонки: разбор `vcp`, `MaxEvent.CallStart` (137), `CallsApi.requestCallsToken` (158, ответ observed-not-ref, `icyfalc0n/maxcalls`), сборка ws2 URL. В query уходит профиль Android Pixel 8 (`UserAgentInfo`: Pixel 8, 26.25.0, Android 14), не строки kolibri-net SDK. `capabilities` и `clientType` остаются константами сигналинга.
- `ConversationParams.decode` отклоняет заявленную длину LZ4 больше 64 КиБ до Base64 и распаковки. Потолок пакета 32 МиБ не менялся. Битый `vcp` по-прежнему оставляет `CallStart` с `params = null`.
- Дыра истории хранится якорем (id локального хвоста на момент дыры). Страница `CHAT_HISTORY` закрывает её, только если содержит этот id; пустая страница тоже закрывает. Одна более новая страница дыру не закрывает.
- `MaxClient.logout` отменяет догрузку, останавливает `EventRouter` и дожидается текущего события, затем чистит снимок и снова запускает роутер. Сохранение токена и его очистка идут под одним mutex. Чужой аккаунт при `LOGIN` заменяет снимок целиком, а не сливает его с остатком.
- `fillGaps` листает историю назад (`gapFillCount` 40, не больше `gapFillPageLimit` страниц, по умолчанию 16). Если упёрлись в лимит или самая старая id не сдвигается, дыра остаётся открытой.
- Модуль `android/` есть. Java и Kotlin JVM target — 17. CI компилирует `:core`, `:shared` и `:android`, на устройстве не запускает.
- JVM-файл токена на POSIX создаётся сразу как `rw-------`, каталог — `rwx------`. Если права не встали, запись падает.
- Опкод 8 ядро шлёт как PyMax `LOGIN2` `{needProfile, contactsSync, configHash}`. Вопрос K11 (имя kolibri `CONTACTS_GET`) открыт.
- Локальный прогон тестов — Gradle (`:core:jvmTest`, `:shared:jvmTest`). Скрипт `/workspace/tools/run-tests.sh` относится к среде, где ядро писали, на этой машине его нет.

## 3. Совместимость с чужими коммитами

Ночные изменения ничего в них не ломают и не переписывают:
- `CallStart` проходит через `EventRouter`, а `MaxState` его сознательно игнорирует, потому что состояния звонков в ядре нет;
- раздел про звонки в `opcodes.md` перенесён в общую таблицу «сделано и блокеры» с сохранением содержания;
- все тесты из чужих коммитов проходят.

## 4. Состояние CI (`.github/workflows/ios-core.yml`, джоба `ios-and-jvm`)

- macos-14: JVM-тесты `:core` и `:shared`, компиляция и тесты Kotlin/Native для iOS Simulator, `compileDebugKotlinAndroid` для `:core` и `:shared`, `compileDebugKotlin` для `:android`. На устройстве Android не запускается.
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
  - поиск (37, 60, 68, 73);
  - опкоды 193, 194, 301;
  - звонки 76, 78, 79, 84, 103, 164, 166, 195;
  - 2FA-восстановление (101, 104, 105, 116);
  - транскрипция (202, 293);
  - истории (208–220).
- Вход по QR на новом устройстве (288, 289, 291) сознательно не делаем: он требует веб-тип устройства. Подтверждение QR с телефона (290) есть.
- ws2-сигнализация и WebRTC остаются на стороне приложения, в ядре только сборка URL.
- Не копировать GPL-код, не упоминать Telegram.
- Коммиты: автор и коммиттер `Ivan K <57476075+fighxy@users.noreply.github.com>`, английский язык, conventional commits.
- Пушить только по явной просьбе Ивана. Force-push только после подтверждения.
- Ревью кода по диапазону `f58f3bc..38d7497` Иван отменил, не начинать без новой просьбы.
