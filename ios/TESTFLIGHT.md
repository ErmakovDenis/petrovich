# TestFlight / паритет / сборка с Linux

С Linux IPA на iPhone не поставить напрямую. Путь: **Codemagic (macOS CI) → App Store Connect → TestFlight**.

Конфиг: корневой [`codemagic.yaml`](../codemagic.yaml).

## Одноразово (Apple + Codemagic)

### Apple Developer Program

- [ ] Членство Apple Developer Program (~$99/год) на Apple ID iPhone
- [ ] [developer.apple.com](https://developer.apple.com) → Identifiers: App ID `ru.petrovich.telemetry`
- [ ] Capabilities: Push Notifications (когда дойдёте до APNs)
- [ ] [App Store Connect](https://appstoreconnect.apple.com) → My Apps → **+** → iOS, Bundle ID `ru.petrovich.telemetry`, имя «Петрович»
- [ ] Users and Access → Integrations → App Store Connect API → ключ (роль **App Manager**), скачать `.p8` (один раз)
- [ ] Записать **Issuer ID**, **Key ID**, сохранить `.p8` вне git
- [ ] App Information → скопировать числовой **Apple ID** приложения → переменная `APP_STORE_APPLE_ID` в Codemagic

### Codemagic

- [ ] Аккаунт [codemagic.io](https://codemagic.io), подключить репозиторий `petrovich`
- [ ] Team integrations → Developer Portal → Add key  
      - Name: **ровно** `Petrovich App Store Connect` (как в `codemagic.yaml`)  
      - Issuer ID / Key ID / `.p8`
- [ ] Code signing identities:
  - Certificate: Generate или Fetch **Apple Distribution** через тот же API key
  - Profile: Fetch/создать **App Store** profile для `ru.petrovich.telemetry`
- [ ] Application → Environment variables: `APP_STORE_APPLE_ID` = числовой ID из ASC
- [ ] Scan for `codemagic.yaml` на ветке `feature/ios-app`

### iPhone

- [ ] Установить **TestFlight**
- [ ] App Store Connect → TestFlight → Internal Testing → добавить себя (тот же Apple ID)
- [ ] После первого успешного билда: Install в TestFlight

## Секреты (только UI Codemagic / парольница — не в git)

| Что | Куда |
|-----|------|
| ASC Issuer ID | Codemagic integration |
| ASC Key ID | Codemagic integration |
| ASC `.p8` | Codemagic integration |
| `APP_STORE_APPLE_ID` | Codemagic env var |
| Distribution cert / profile | Code signing identities |

Имена env из старых черновиков (`APP_STORE_CONNECT_*`) не нужны, если используете integration `auth: integration`.

## День за днём

```bash
# на Linux
git push origin feature/ios-app
# → webhook Codemagic, workflow ios-testflight (~10–20 мин)
# → IPA в App Store Connect → Internal TestFlight → Install на iPhone
```

Или Start build вручную в UI Codemagic.

После загрузки Apple обрабатывает билд 5–15 минут; затем он появляется в TestFlight.

### External vs Internal

- Сейчас в yaml: `submit_to_testflight: false` — только **Internal** (до 100 человек из команды ASC, без beta review).
- Для внешних тестеров: в ASC заполнить Test Information, в yaml выставить `submit_to_testflight: true` и `beta_groups:`.

## Перед релизом / паритет

- [ ] Иконка 1024×1024 в `Assets.xcassets/AppIcon`
- [ ] Privacy strings в Info.plist (микрофон / речь — заготовки уже есть)
- [ ] `STAND_URL` / `AUTOGRAPH_BASE_URL` для прод
- [ ] APNs key на стенде `fleet_service` (когда понадобится push)

### Чеклист паритета с Android

- [ ] Онбординг: вход AutoGRAPH, схема, демо
- [ ] Табы: Главная / Парк / Петрович / Проблемы / Настройки
- [ ] Главная: плашки, виджеты, чанки widget-stats
- [ ] Парк / Проблемы / Чат / Доклад / Telegram / Push / тема
- [ ] Тихие часы, mute kinds, графики — по мере нужды

## Сборка на физическом Mac (альтернатива)

```bash
cd ios && xcodegen generate
# Xcode → Product → Archive → Distribute App → App Store Connect / TestFlight
```

## Известные отличия от Android

- Без Mac нет live-отладки как у `adb logcat`; краши — через TestFlight/ASC + dSYM из артефактов Codemagic.
- BGTask слабее WorkManager — основной канал событий: push со стенда.
- Ручка `v1/devices/push-token` на стенде может отсутствовать — клиент уже вызывает её.
