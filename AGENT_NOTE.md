# AGENT_NOTE: служебная записка для агента, который подхватит работу

> Состояние на 2026-09-29, 07:20 (UTC+7). Файл не закоммичен, он лежит только в локальной копии.

## 1. Проект и цель

`fighxy/max-kmp-core` — приватное сетевое ядро на Kotlin Multiplatform для мессенджера Max. Платформы: iOS (приоритет), Android и JVM desktop. Протокол восстановлен по двум референсам, KometTeam/kolibri и MaxApiTeam/PyMax. Их используем только как описание схем, **код не копируем** (там GPL).

Цель: готовое ядро, которое iOS- и Android-приложения подключают через модуль `shared` (фасад `MaxClient`). В ядре есть транспорт, сессия, авторизация, API, события, локальное состояние и медиа.

Модули:
- `core/src/commonMain/kotlin/com/max/core/`: `transport`, `protocol`, `session`, `auth`, `api`, `events`, `state`, `media`, `calls`, `MaxError.kt`.
- Платформенный код: `core/src/iosMain` (Network.framework, NSURLSession) и `core/src/jvmAndroidShared` (сокеты, OkHttp).
- `shared/`: `MaxClient` и `PlatformSession` с хранилищами учётных данных (iOS Keychain, Android SharedPreferences, JVM файл).
- Документация: `README.md`, `docs/protocol.md`, `docs/opcodes.md`, `docs/architecture.md`, `docs/ios-plan.md`.

## 2. Что сделано

- Ветка `main`, всё запушено. Последний коммит — `ebf3356`.
- **Пять чужих коммитов** `f58f3bc..d9fc461` (2026-09-29, 03:23–03:42, подписаны как Ivan K). Это 10 файлов и около 620 строк, только звонки:
  - разбор `vcp` (LZ4 + JSON) в `calls/Vcp.kt`, `ConversationParams`, сборка ws2 URL;
  - событие `MaxEvent.CallStart` для опкода 137;
  - `CallsApi.requestCallsToken` (опкод 158), доступен через `MaxApi.calls`;
  - раздел про звонки в `docs/opcodes.md`.
  - Схема **ответа** на 158 взята из сторонних заметок (`icyfalc0n/maxcalls`), а не из kolibri или PyMax. В коде это помечено как observed-not-ref.
- **19 ночных коммитов** `2968afb..38d7497` построены поверх них линейно:
  - `MaxStore` и `EventRouter`: события и ответы API применяются к локальному состоянию, после переподключения ядро находит пропуски в истории;
  - фасад `MaxClient` в `shared`, хранилища токенов по платформам, проверка, что профиль устройства Android;
  - потоковая загрузка медиа с диска (`UploadSource`, на iOS через `NSURLSession fromFile`);
  - новые API: пользователи, аккаунт, 2FA, группы, опросы, отложенная отправка, комментарии, боты;
  - `MaxError`: виды ошибок и подсказки о повторе;
  - документация и расширенный CI.
- Тестов 258, локальный запуск через `/workspace/tools/run-tests.sh` (kotlinc + JUnit).

## 3. Совместимость с чужими коммитами

Ночные изменения ничего в них не ломают и не переписывают:
- `CallStart` проходит через `EventRouter`, а `MaxState` его сознательно игнорирует, потому что состояния звонков в ядре нет;
- раздел про звонки в `opcodes.md` перенесён в общую таблицу «сделано и блокеры» с сохранением содержания;
- все тесты из чужих коммитов проходят.

## 4. Состояние CI (`.github/workflows/ios-core.yml`, джоба `ios-and-jvm`)

- На `38d7497` (прогон 36501924674): JVM-тесты зелёные, компиляция Kotlin/Native для iOS Simulator зелёная, iOS Simulator тесты зелёные. **Упал шаг «Compile Android (core + shared)»**:
  `Inconsistent JVM-target compatibility detected for tasks 'compileDebugJavaWithJavac' (1.8) and 'compileDebugKotlinAndroid' (17)`.
  Java по умолчанию собиралась под 1.8, Kotlin под 17, и Gradle отказывается собирать.
- **Уже исправлено** в `ebf3356`: в блоки `android {}` файлов `core/build.gradle.kts` и `shared/build.gradle.kts` добавлен `compileOptions` с `JavaVersion.VERSION_17`. На `ebf3356` (прогон 36502212295) CI **полностью зелёный**, включая Android.

## 5. Что глянуть в первую очередь

1. **Сборка Android:** `compileOptions` в `core` и `shared`. Если появятся новые Android-модули (`android/`), им нужна та же Java 17. Надёжнее перейти на `jvmToolchain(17)`.
2. **Версии:** Gradle 8.10.2, Kotlin JVM target 17, `okhttp = "4.12.0"` в version catalog. Движки Ktor удалены как неиспользуемые.
3. **Звонки:** `core/.../calls/` (`Vcp.kt`, `CallsApi.kt`), `MaxEvent.CallStart`. Ответ на 158 не подтверждён референсами.
4. **Что проверить на живом сервере:** догрузку истории после переподключения, параллельную загрузку видео по частям на `VIDEO_UPLOAD`, ответ на 158.
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
