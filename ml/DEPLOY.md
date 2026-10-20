# Развёртывание стенда: ассистент и антифрод

Инструкция для администратора: как поднять на сервере `ml/fleet_service` (ассистент, телеметрия, проверка и
хранилище аномалий, фоновая проверка, письма) и `ml/predictive_antifraud` (предиктивная аналитика и антифрод),
открыть их приложению по HTTPS и сопровождать дальше. Описание эндпоинтов и всех `FS_*` —
[fleet_service/README.md](fleet_service/README.md), проверка приложения со стендом —
[MANUAL_CHECK.md](MANUAL_CHECK.md).

Секреты (ключи, пароли, токены, содержимое `.env`) никуда не пересылать — ни в чат, ни в задачи, ни в git.

## 1. Что разворачивается

```
Телефон (приложение) ──HTTPS:443──▶ nginx на сервере ──▶ 127.0.0.1:8080 ──▶ fleet_service (контейнер, :8000)
                                                                              │
                     predictive_antifraud (контейнер, :8000) ◀── X-API-Key ───┤  внутренняя сеть compose
                                                                              ├──▶ AutoGRAPH  https://web.tk-ekat.ru
                                                                              ├──▶ OpenRouter https://openrouter.ai
                                                                              └──▶ SMTP-сервер (письма)
```

- Оба сервиса — контейнеры одного `ml/docker-compose.yml`. Наружу (через nginx) открыт только `fleet_service`;
  `predictive_antifraud` вызывается только стендом по внутренней сети compose (`http://predictive_antifraud:8000`).
- Логин и пароль AutoGRAPH на стенд не приходят: приложение передаёт токен сессии (`Authorization: Bearer`) и id
  схемы (`X-Schema-Id`). Пароль стенд получает, только если пользователь включил «Разрешить стенду входить
  самостоятельно», и хранит его зашифрованным.
- Ключ OpenRouter есть только в `ml/fleet_service/.env` на сервере, в APK его нет.
- Данные стенда — SQLite-файл `/srv/data/fleet.db` в томе docker `ml_fleet_data` (аномалии, решения, доступ
  фоновой проверки, черновики и журнал писем).
- `predictive_antifraud` пока без обученных моделей: работает и отвечает `ready: false`, стенд показывает это как
  статус `not_ready`, проверка по правилам идёт как обычно.
- Release-сборка приложения ходит на стенд только по HTTPS; HTTP без TLS разрешён лишь в debug-сборке (п. 10).

## 2. Требования к серверу

| Что | Требование |
|---|---|
| ОС | Linux x86_64 или arm64 (проверено на Ubuntu) |
| Ресурсы | от 1 vCPU, 1 ГБ ОЗУ, 5 ГБ диска (образы ~0,5 ГБ, база — десятки МБ) |
| Docker | Docker Engine 24+ и плагин Compose **2.24+** (в `docker-compose.yml` — `env_file` с `required: false`) |
| Прочее | `git`, `openssl` (или `python3`) для ключей, `curl` для проверок; для HTTPS — `nginx` и `certbot` |
| Имя | DNS-запись A/AAAA (например `stand.example.ru`) на IP сервера — для сертификата Let's Encrypt |
| Входящие | 443/tcp (приложение) и 80/tcp (выпуск и продление сертификата); 22/tcp — администрирование |
| Исходящие | `web.tk-ekat.ru:443`, `openrouter.ai:443`, SMTP-сервер (587 или 465); при сборке — Docker Hub и PyPI |
| Время | синхронизировано (NTP): сетка проверок и сроки токенов считаются по UTC |

Docker ставится по официальной инструкции для своей ОС: https://docs.docker.com/engine/install/. Проверка:

```bash
docker --version             # 24 и выше
docker compose version       # v2.24 и выше
timedatectl | grep synchronized    # System clock synchronized: yes
```

## 3. Код

```bash
git clone git@github.com:ErmakovDenis/petrovich.git ~/petrovich    # ветка main — стенд целиком (шаги 1–6)
cd ~/petrovich/ml
```

Для клонирования по SSH серверу нужен доступ к репозиторию — например, deploy key только на чтение
(GitHub → Settings → Deploy keys) на ключ сервера `~/.ssh/id_ed25519.pub`.

Дальше все команды — из каталога `~/petrovich/ml`, если не сказано иное. Имя проекта compose — по имени каталога
(`ml`): контейнеры `ml-fleet_service-1`, `ml-predictive_antifraud-1`, том `ml_fleet_data`.

## 4. Настройки

Файлы настроек не в git (`.gitignore`): `fleet_service/.env`, `predictive_antifraud/.env`,
`fleet_service/config/recipients.toml`, `ml/.env`. Создать из образцов:

```bash
cp fleet_service/.env.example fleet_service/.env
cp predictive_antifraud/.env.example predictive_antifraud/.env
chmod 600 fleet_service/.env predictive_antifraud/.env
```

Пустое значение в `fleet_service/.env` — значение по умолчанию; все `FS_*` описаны прямо в `.env.example`.

### 4.1. Ключ между стендом и аналитикой

Общий секрет двух сервисов, придумывается при развёртывании:

```bash
openssl rand -hex 32         # или: python3 -c "import secrets; print(secrets.token_hex(32))"
```

Одно и то же значение — в двух файлах:

| Файл | Переменная |
|---|---|
| `predictive_antifraud/.env` | `PA_API_KEY=<ключ>` |
| `fleet_service/.env` | `FS_PREDICTIVE_API_KEY=<ключ>` |

Разные значения — аналитика отвечает стенду 401, в ответе проверки `analytics.*.status` = `unavailable`; пустые
оба — проверка ключа выключена (только для локальной разработки).

### 4.2. Ассистент (OpenRouter)

В `fleet_service/.env`:

```ini
FS_OPENROUTER_API_KEY=sk-or-...        # ключ OpenRouter владельца стенда
FS_LLM_MODEL=openai/gpt-4o-mini        # модель с поддержкой tool calling — выбирает владелец стенда
```

Без ключа или модели стенд работает (телеметрия, аномалии), но `/v1/chat` отвечает 503 «ассистент не настроен».
`FS_OPENROUTER_REQUIRE_PARAMETERS=true` и `FS_OPENROUTER_DATA_COLLECTION=deny` (по умолчанию) оставляют только
провайдеров, которые поддерживают tools и не сохраняют запросы; если у выбранной модели таких нет, ассистент
отвечает 502 — выберите другую модель (ослаблять `deny` — решение владельца).

### 4.3. Фоновая проверка на стенде

Стенд сам проверяет машины по расписанию (по умолчанию каждые 15 минут за 3 часа), даже когда телефоны выключены.
Нужен ключ шифрования доступа (токены сессий и пароли хранятся в базе шифротекстом):

```bash
python3 -c "import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())"
```

```ini
FS_ACCESS_ENCRYPTION_KEY=<результат команды>
```

Ключ держать только в `.env` (не в томе с базой) и сохранить в менеджере паролей: без него копия базы не
раскрывает секреты, но и стенд не сможет их прочитать. Смена ключа — п. 8.5. Пусто — фоновая проверка выключена,
в приложении переключатель «Фоновая проверка на стенде» сообщит, что стенд её не принял, и телефон проверяет сам.

### 4.4. Письма (необязательно)

Ассистент готовит черновик, письмо уходит только по кнопке «Отправить» в приложении. В `fleet_service/.env`:

```ini
FS_SMTP_HOST=smtp.example.ru
FS_SMTP_PORT=587
FS_SMTP_SECURITY=starttls              # starttls (587), ssl (465), none — только локальный сервер
FS_SMTP_USERNAME=petrovich@example.ru
FS_SMTP_PASSWORD=<пароль SMTP или пароль приложения>
FS_SMTP_FROM=petrovich@example.ru
FS_EMAIL_RECIPIENTS_PATH=/srv/config/recipients.toml
```

Список получателей — адресов вне него ассистент указать не может:

```bash
cp fleet_service/config/recipients.example.toml fleet_service/config/recipients.toml
chmod 600 fleet_service/config/recipients.toml
```

В `recipients.toml` — `id` (латиница, цифры, `-`, `_`), `name`, `role`, `email` и необязательно `schemas` (id схем
AutoGRAPH, где получатель доступен). Каталог `fleet_service/config` смонтирован в контейнер как `/srv/config`
(только чтение). Ошибка в файле — стенд не запустится (текст ошибки — в логе). Для первой проверки оставьте только
свой адрес. Пустые `FS_SMTP_HOST`, `FS_SMTP_FROM` или `FS_EMAIL_RECIPIENTS_PATH` — письма выключены.

### 4.5. Порты на хосте

По умолчанию compose публикует `fleet_service` на `0.0.0.0:8080`, `predictive_antifraud` — на `0.0.0.0:8001`.
На сервере с nginx оба порта нужны только локально. Файл `ml/.env` (рядом с `docker-compose.yml`; его читает
сам docker compose, в контейнеры он не попадает):

```bash
cat > .env <<'EOF'
# Порты стенда на хосте: только локально, наружу — nginx (DEPLOY.md, п. 6).
FLEET_SERVICE_PORT=127.0.0.1:8080
PREDICTIVE_PORT=127.0.0.1:8001
EOF
```

Это важно: порты, опубликованные docker, обходят `ufw`/`firewalld`, поэтому закрыть `0.0.0.0:8001` файрволом
хоста не получится — только привязкой к `127.0.0.1`. Занятые порты меняются здесь же (`127.0.0.1:18080`).

### 4.6. Остальное

Остальные `FS_*` — по умолчанию. Пороги правил `FS_RULES__*` не менять: они должны совпадать с приложением, иначе
одно событие может прийти дважды (`fleet_service/README.md`, «Правила»). `FS_BACKGROUND_INTERVAL_MINUTES` и
`FS_BACKGROUND_WINDOW_HOURS` — частота и окно фоновой проверки. `FS_LOG_LEVEL=INFO`.

## 5. Сборка и запуск

```bash
docker compose up -d --build --wait
docker compose ps        # оба контейнера Up (healthy), порты 127.0.0.1:8080 и 127.0.0.1:8001
```

`--wait` ждёт healthcheck обоих сервисов (до минуты). Миграции базы применяются при старте
(`FS_DB_AUTO_MIGRATE=true`). Контейнеры с `restart: unless-stopped` поднимаются сами после перезагрузки сервера
(служба docker должна быть включена: `sudo systemctl enable docker`).

Проверка без приложения (на сервере):

```bash
curl -s 127.0.0.1:8080/health
# {"status":"ok","llmConfigured":true,"backgroundConfigured":true,"emailConfigured":true}
curl -s 127.0.0.1:8001/health      # {"status":"ok"}
curl -s 127.0.0.1:8001/ready       # {"models":[{"name":"predictive","ready":false,...},{"name":"antifraud",...}]}
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8080/v1/vehicles     # 401 — без токена стенд не отвечает
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
    127.0.0.1:8001/v1/antifraud/check                                   # 401 — аналитика без ключа не отвечает
docker compose logs fleet_service | tail -20      # без ERROR и Traceback
```

`llmConfigured: false` — не заданы `FS_OPENROUTER_API_KEY` или `FS_LLM_MODEL`; `backgroundConfigured: false` —
нет `FS_ACCESS_ENCRYPTION_KEY` (или `FS_BACKGROUND_ENABLED=false`); `emailConfigured: false` — не заданы SMTP,
отправитель или файл получателей. Предпоследний `curl` даёт 422 вместо 401 — `PA_API_KEY` пуст.

Совпадение ключа аналитики и связь с AutoGRAPH и OpenRouter этими командами не проверяются: для них нужен токен
сессии AutoGRAPH — проверяются из приложения (п. 7). Доступность внешних систем с сервера:

```bash
docker compose exec fleet_service python -c "import httpx; print(httpx.get('https://web.tk-ekat.ru/ServiceJSON/EnumSchemas', timeout=15).status_code)"
docker compose exec fleet_service python -c "import httpx; print(httpx.get('https://openrouter.ai/api/v1/models', timeout=15).status_code)"
# ожидается 200 (у AutoGRAPH без сессии допустим и другой код) — важно, что нет ошибки соединения или таймаута
```

## 6. HTTPS (nginx + Let's Encrypt)

Приложению нужен адрес вида `https://stand.example.ru`. Ниже — nginx на хосте; подойдёт и другой обратный прокси,
если он выполняет требования в конце раздела.

```bash
sudo apt install -y nginx certbot      # Debian/Ubuntu
sudo mkdir -p /var/www/certbot
```

**Шаг 1 — только HTTP, для выпуска сертификата.** `/etc/nginx/sites-available/petrovich-stand`
(`stand.example.ru` — своё имя):

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name stand.example.ru;

    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/petrovich-stand /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/certbot -d stand.example.ru \
    --deploy-hook "systemctl reload nginx"       # сертификат продлевается сам (таймер certbot), nginx перечитывает его
```

**Шаг 2 — HTTPS.** Дописать в тот же файл:

```nginx
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;                      # nginx 1.25.1+; на старом — «listen 443 ssl http2;» и без этой строки
    server_name stand.example.ru;

    ssl_certificate     /etc/letsencrypt/live/stand.example.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/stand.example.ru/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    # Телеметрия за 7 дней и проверка машины — до 300 с (приложение ждёт 300 с), чат — до 120 с.
    proxy_read_timeout 310s;
    proxy_send_timeout 310s;
    # Перенос истории с устройства (до 1000 записей) и история чата (до 40 сообщений по 4000 символов).
    client_max_body_size 5m;

    # Наружу — только API стенда и /health; /docs и /openapi.json — справка для разработки.
    location ~ ^/(docs|redoc|openapi\.json) { return 404; }

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
curl -s https://stand.example.ru/health                                   # {"status":"ok",...}
curl -s -o /dev/null -w '%{http_code}\n' https://stand.example.ru/v1/vehicles   # 401
curl -s -o /dev/null -w '%{http_code}\n' https://stand.example.ru/docs          # 404
curl -s -o /dev/null -w '%{http_code}\n' http://stand.example.ru:8001/health --max-time 5   # 000 — аналитика снаружи недоступна
sudo certbot renew --dry-run                                              # продление сработает
```

Последние две проверки — с другой машины (не с сервера): с самого сервера `127.0.0.1` доступен всегда.

Файрвол (если включён `ufw`): `sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp`.

Требования к любому обратному прокси: TLS с доверенным сертификатом (самоподписанный release-сборка не примет);
таймаут ответа не меньше 310 с; тело запроса до 5 МБ; заголовки `Authorization`, `X-Schema-Id`, `X-User-Name`
передаются без изменений; кэширование ответов выключено. Стенд можно опубликовать и с префиксом пути
(`https://example.ru/petrovich` → `http://127.0.0.1:8080/`, префикс прокси срезает) — приложение дописывает пути
к адресу из настроек.

## 7. Подключение приложения

В приложении: «Настройки» → «Ещё» → «Подключение и данные» → раздел «Стенд» → «Адрес стенда»
`https://stand.example.ru` → «Сохранить адрес». Дальше включать переключатели по одному: «Данные через стенд»,
«Ассистент через стенд», в разделе «Аномалии» — «Аномалии со стенда», «Хранить аномалии на стенде», «Фоновая
проверка на стенде»; «Письма из чата» — в разделе «Стенд». Демо-режим со стендом не работает: нужен вход
реальной учётной записью AutoGRAPH.

Что проверить после каждого переключателя и как откатиться — [MANUAL_CHECK.md](MANUAL_CHECK.md), пп. 4–7.
Минимальная приёмка развёртывания:

1. «Данные через стенд» — таблицы и графики машины за 24 ч совпадают с тем, что было без стенда.
2. «Аномалии со стенда» → «Проблемы» → «Проверить сейчас» — без ошибки; на сервере
   `docker compose logs fleet_service | grep 'проверка: схема'` — строки вида `…: правила N, аналитика
   not_ready/not_ready, …`. `unavailable/unavailable` и рядом `аналитика …: ответ 401` — ключи п. 4.1 не совпали.
3. «Ассистент через стенд» → «Какие у меня машины?» — ответ со списком машин.
4. «Фоновая проверка на стенде» — под переключателем «Доступ стенда: сессия AutoGRAPH»; не позже чем через
   15 минут в логе `docker compose logs fleet_service | grep 'фоновая проверка: схема'`.
5. «Письма из чата» — письмо на свой адрес из `recipients.toml` приходит только после «Отправить».

## 8. Сопровождение

### 8.1. Логи

```bash
docker compose logs -f fleet_service              # ассистент, телеметрия, аномалии, фоновая проверка, письма
docker compose logs -f predictive_antifraud
docker compose logs fleet_service | grep -iE 'session=|bearer|password=|sk-or'   # после маркеров — только ***
```

Токены, пароли, ключи OpenRouter и адреса писем в логах маскируются или не пишутся. Чтобы логи docker не
заняли диск — ротация в `/etc/docker/daemon.json`:

```json
{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }
```

затем `sudo systemctl restart docker` и `docker compose up -d --force-recreate` (настройка действует на новые
контейнеры).

### 8.2. Изменение настроек

| Что изменили | Что сделать |
|---|---|
| `fleet_service/.env`, `predictive_antifraud/.env`, `ml/.env` | `docker compose up -d --wait` — compose пересоздаёт контейнер с новыми значениями. `docker compose restart` `.env` **не перечитывает** |
| `fleet_service/config/recipients.toml` | `docker compose restart fleet_service` (файл читается при старте) |
| модели в `predictive_antifraud/models/` | `docker compose restart predictive_antifraud` (п. 9) |

### 8.3. Обновление

```bash
cd ~/petrovich/ml
# 1. Резервная копия базы — п. 8.4.
# 2. Новый код:
git -C .. pull
# 3. Пересборка и перезапуск; миграции базы применятся при старте:
docker compose up -d --build --wait
docker compose ps && curl -s 127.0.0.1:8080/health
```

Пересоздание занимает несколько секунд: запросы приложения в это время завершатся ошибкой стенда, фоновая
проверка продолжится по расписанию после старта. Откат: вернуть прежний коммит (`git -C .. checkout <коммит>`),
восстановить базу из копии, сделанной до обновления (п. 8.4), `docker compose up -d --build --wait` — новая
схема базы старому коду может быть непонятна, поэтому именно восстановление, а не только откат кода.

Миграции вручную (если `FS_DB_AUTO_MIGRATE=false`):

```bash
docker compose exec fleet_service python -m fleet_service.store.migrate              # до последней версии
docker compose exec fleet_service python -m fleet_service.store.migrate downgrade 0002   # откат к версии
```

### 8.4. Резервная копия и восстановление

База в режиме WAL, поэтому копируется не файл, а снимок через `sqlite3`-backup (стенд можно не останавливать):

```bash
docker compose exec fleet_service python -c "import sqlite3; d = sqlite3.connect('/srv/data/backup.db'); sqlite3.connect('/srv/data/fleet.db').backup(d); d.close()"
docker compose cp fleet_service:/srv/data/backup.db ./fleet-$(date +%F).db
docker compose exec fleet_service rm /srv/data/backup.db
```

Отдельно и надёжно (менеджер паролей, не рядом с копией базы) хранить `fleet_service/.env`,
`predictive_antifraud/.env` и `recipients.toml`: без `FS_ACCESS_ENCRYPTION_KEY` сохранённый доступ фоновой
проверки не расшифровать (приложения выдадут его заново, сохранённые пароли пользователям придётся разрешить
заново).

Восстановление (`fleet-2026-10-20.db` — своя копия):

```bash
docker compose stop fleet_service
docker compose run --rm --no-deps -v "$PWD/fleet-2026-10-20.db:/restore.db:ro" fleet_service \
    sh -c 'rm -f /srv/data/fleet.db-wal /srv/data/fleet.db-shm && cp /restore.db /srv/data/fleet.db'
docker compose up -d --wait
```

### 8.5. Смена ключей

- **Ключ аналитики** — новое значение одновременно в `PA_API_KEY` и `FS_PREDICTIVE_API_KEY`, затем
  `docker compose up -d --wait`.
- **Ключ OpenRouter** — `FS_OPENROUTER_API_KEY`, затем `docker compose up -d --wait`.
- **`FS_ACCESS_ENCRYPTION_KEY`** — только при утечке: сохранённые токены и пароли станут нечитаемыми, стенд их
  сотрёт; приложения выдадут токен заново при следующем фоновом обновлении (до 15 минут), пароли — только если
  пользователь снова включит «Разрешить стенду входить самостоятельно».

### 8.6. Остановка и удаление

```bash
docker compose stop                 # остановить, всё сохранится
docker compose down                 # удалить контейнеры; база (том ml_fleet_data) сохранится
docker compose down -v              # удалить и базу — аномалии, решения и доступ пропадут безвозвратно
```

Стенд запускается **одним** процессом: фоновая проверка идёт внутри него. Не масштабировать
(`docker compose up --scale fleet_service=2`) и не добавлять `--workers` в команду uvicorn — проверки и
уведомления задвоятся.

## 9. Модели антифрода и предиктивной аналитики

Сейчас моделей нет, и это рабочее состояние: проверка аномалий идёт правилами, в ответе — `modelsReady: false`,
ассистент не говорит «нарушений нет», пока аналитика недоступна. Когда модель будет обучена и загрузчик
реализован (`predictive_antifraud/README.md`, «Как подключить модель»):

```bash
cp predictive.onnx antifraud.onnx predictive_antifraud/models/     # имена — <predictive|antifraud>.<расширение>
docker compose up -d --build --wait predictive_antifraud           # --build — если менялись зависимости
curl -s 127.0.0.1:8001/ready                                       # ready: true и версия модели
```

Каталог `predictive_antifraud/models` смонтирован в контейнер только для чтения; файлы моделей в git не попадают.

## 10. Стенд для отладки в локальной сети (без HTTPS)

Только для debug-сборки приложения (в ней разрешён HTTP) и только в доверенной сети: без `ml/.env` (п. 4.5)
`fleet_service` слушает `0.0.0.0:8080`. Адрес в приложении — `http://<IP сервера>:8080`; телефон по USB —
`adb reverse tcp:8080 tcp:8080` и `http://127.0.0.1:8080`. Аналитика при этом тоже открыта на `:8001` — поэтому
`PA_API_KEY` задавать и здесь. Проверка без внешних систем — `fleet_service/scripts/smoke.sh` (подставные
OpenRouter, AutoGRAPH и SMTP, свой проект compose на портах 18080/18001, рабочий стенд не трогает).

## 11. Неполадки

| Признак | Причина и что делать |
|---|---|
| `docker compose up` ругается на поля `required` / `path` в `env_file` или `depends_on` | Compose старше 2.24 — обновить плагин |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | Порт занят — другой порт в `ml/.env` (п. 4.5) и в `proxy_pass` nginx |
| `fleet_service` перезапускается, в логе ошибка `recipients.toml` | Ошибка в файле получателей — исправить или временно очистить `FS_EMAIL_RECIPIENTS_PATH` |
| `fleet_service` не стартует, ошибка проверки `FS_*` | Неверное значение (диапазон, `FS_SMTP_SECURITY`, `FS_BACKGROUND_WINDOW_HOURS` больше `FS_TELEMETRY_MAX_PERIOD_HOURS`) — текст ошибки в `docker compose logs fleet_service` |
| `/health`: `llmConfigured: false` / `backgroundConfigured: false` / `emailConfigured: false` | Не заданы переменные из пп. 4.2 / 4.3 / 4.4, либо `.env` правили, а контейнер не пересоздали (п. 8.2) |
| Приложение: «Стенд не принял сессию AutoGRAPH» | Токен истёк и повторный вход не помог — войти заново в настройках приложения; проверить доступность AutoGRAPH с сервера (п. 5) |
| Приложение не подключается к `https://…`, в браузере телефона адрес открывается | Release-сборка не доверяет сертификату: нужен доверенный (Let's Encrypt), полная цепочка — `fullchain.pem` |
| Ответы на телеметрию за 3–7 дней обрываются через ~60 с (`504` от nginx) | Нет `proxy_read_timeout 310s` в nginx (п. 6) |
| `413 Request Entity Too Large` при включении «Хранить аномалии на стенде» | Нет `client_max_body_size 5m` в nginx |
| Проверка аномалий работает, но `analytics … unavailable`, в логе `аналитика …: ответ 401` | `PA_API_KEY` ≠ `FS_PREDICTIVE_API_KEY` (п. 4.1), после правки — `docker compose up -d --wait` |
| В логе `аналитика …: сервис не ответил` | `predictive_antifraud` остановлен или перезапускается — `docker compose ps`, `docker compose logs predictive_antifraud` |
| Ассистент: 502 «модель ответила ошибкой» | Ключ OpenRouter, баланс, имя модели или нет провайдера под `require_parameters`/`deny` (п. 4.2); подробности — в логе `fleet_service` |
| Ассистент: 504 | Модель не уложилась в `FS_LLM_TIMEOUT_SECONDS` / `FS_CHAT_TIMEOUT_SECONDS` (держать меньше 120 с) |
| Письма: «Письмо не отправлено: …» | SMTP: адрес, порт и `FS_SMTP_SECURITY` должны соответствовать друг другу (587 — `starttls`, 465 — `ssl`), пароль; исходящий порт открыт у хостера |
| На «Главной» карточка «Стенд давно не проверял данные» | Стенд не достучался до AutoGRAPH или токены истекли — `docker compose logs fleet_service \| grep 'фоновая проверка'` |

## 12. Как проверена инструкция

2026-10-20, ветка `fleet/step-6` (тот же код влит в `main` PR #5), Docker 29.3 / Compose 5.1, nginx 1.27.
Чистая копия `ml/` из git, пп. 4–8 выполнены по тексту; AutoGRAPH, OpenRouter и SMTP заменены подставными
(`fleet_service/tests/fakes.py`), домен и сертификат — самоподписанные, порты сдвинуты, чтобы не мешать рабочему
стенду.

Проверено: `.env` из образцов, ключи из команд п. 4, `ml/.env` привязывает оба порта к `127.0.0.1`; сборка и
`up --wait` (оба healthy); все ответы п. 5, включая 401 аналитики без ключа и 422 при пустом `PA_API_KEY`;
`/v1/anomalies/check` — аналитика `not_ready` при совпавших ключах и `unavailable` с `аналитика …: ответ 401` в
логе при разных; `docker compose restart` не перечитывает `.env`, `up -d` пересоздаёт контейнер; конфиг nginx из
п. 6 дословно — `nginx -t`, TLS и HTTP/2, `/health`, 401, 404 для `/docs` и `/openapi.json`, редирект с HTTP и
отдача `/.well-known/acme-challenge/`, машины, проверка и чат через HTTPS, тело 3 МБ, ответ через 75 с без обрыва;
резервная копия и восстановление п. 8.4 (решение, принятое после копии, после восстановления пропало); база
переживает `down`/`up`; ручные миграции и откат к `0002`; ошибка в `recipients.toml` — контейнер
перезапускается с понятным текстом в логе; маскирование секретов в логе; `smoke.sh` — все шаги.

Не проверено (нужны реальный сервер, ключи и учётные данные — выполняет человек): выпуск и продление
сертификата Let's Encrypt, доступ к AutoGRAPH и OpenRouter с сервера (п. 5), ответы реальной модели, отправка
через реальный SMTP, приложение на телефоне (п. 7), ротация логов docker (п. 8.1).
