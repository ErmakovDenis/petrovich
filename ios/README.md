# Петрович (iOS)

Нативный клиент на SwiftUI — порт Android-приложения. Ветка `feature/ios-app`.
Ходит в те же AutoGRAPH и стенд `fleet_service`.

## Требования

- macOS + Xcode 15+ (iOS 17 SDK)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)

На Linux можно править исходники; локально собрать и запустить — только на Mac.

## Сборка на iPhone без Mac

Локально с Linux поставить приложение на iPhone нельзя. Рабочий путь:

**правки на Linux → push → [Codemagic](https://codemagic.io) (облачный macOS) → TestFlight → iPhone**

1. Нужен **Apple Developer Program** и приложение в App Store Connect (`ru.petrovich.telemetry`).
2. Подключить репо к Codemagic, API key с именем `Petrovich App Store Connect`, подпись App Store.
3. Конфиг: корневой [`codemagic.yaml`](../codemagic.yaml) (workflow `ios-testflight`, ветка `feature/ios-app`).
4. На iPhone: приложение TestFlight → Install после успешного билда.

Подробный чеклист секретов и ASC — в [TESTFLIGHT.md](TESTFLIGHT.md).

Отладка как `adb` с Linux недоступна: смотрите демо/стенд и краши в TestFlight.

## Генерация проекта и запуск

```bash
cd ios
xcodegen generate          # создаёт Petrovich.xcodeproj из project.yml
open Petrovich.xcodeproj
```

В Xcode: схему **Petrovich** → симулятор → Run.

Либо из CLI на Mac:

```bash
xcodegen generate
xcodebuild -scheme Petrovich -destination 'platform=iOS Simulator,name=iPhone 16' build
```

## Конфиг

В `project.yml` / Info.plist:

| Ключ | По умолчанию |
|------|----------------|
| `STAND_URL` | `https://petrovichai.ru` |
| `AUTOGRAPH_BASE_URL` | `https://web.tk-ekat.ru/ServiceJSON/` |

Пароль AutoGRAPH — в Keychain; остальное — UserDefaults.

## Структура

```
Petrovich/
  App/           # @main, RootView, табы
  Design/        # палитра и компоненты как на Android
  Data/          # settings, AutoGRAPH, стенд, репозиторий
  Features/      # Onboarding, Home, Park, Problems, Chat, Report, Settings, Telegram
  Support/       # Keychain, форматтеры
  Resources/     # Info.plist, Assets
```

## Волны плана

0. Каркас + тема  
1. Онбординг / сессия  
2. Парк + настройки подключения  
3. Проблемы, главная, widget-stats  
4. Чат, доклад  
5. Telegram UI, APNs-регистрация  
6. Паритет / TestFlight — см. [TESTFLIGHT.md](TESTFLIGHT.md)

## Демо

На первом экране — «Демо-режим без логина»: синтетический парк, аномалии и виджеты без стенда.
