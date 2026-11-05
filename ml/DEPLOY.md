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
                   llm_proxy (LiteLLM, :4000) ◀──────────────────────────────┤  внутренняя сеть compose
                     ├──▶ gigachat_gateway (:8000) ──▶ GigaChat               │  режим gigachat
                     └──▶ OpenRouter https://openrouter.ai                    │  режим openrouter
                                                                              ├──▶ AutoGRAPH  https://web.tk-ekat.ru
                                                                              └──▶ SMTP-сервер (письма)
```

- Все сервисы — контейнеры одного `ml/docker-compose.yml`. Наружу (через nginx) открыт только `fleet_service`;
  `predictive_antifraud` и прокси модели `llm_proxy` вызываются только стендом по внутренней сети compose
  (`http://predictive_antifraud:8000`, `http://llm_proxy:4000`). Прокси повторяет обращения к модели и переходит
  к запасным моделям; его конфигурацию при каждом `docker compose up` строит одноразовый `llm_proxy_config` из
  `fleet_service/.env`.
- Модель выбирает одна настройка `FS_LLM_PROVIDER`: `gigachat` или `openrouter` (п. 4.2). Шлюз `gigachat_gateway`
  запускается всегда, порт наружу не открыт. Токен GigaChat, проверка TLS и преобразование сообщений — в нём.
- Логин и пароль AutoGRAPH на стенд не приходят: приложение передаёт токен сессии (`Authorization: Bearer`) и id
  схемы (`X-Schema-Id`). Пароль стенд получает, только если пользователь включил «Разрешить стенду входить
  самостоятельно», и хранит его зашифрованным.
- Ключи OpenRouter и GigaChat есть только в `ml/fleet_service/.env` на сервере, в APK их нет. Ключ GigaChat видит только
  шлюз (и `fleet_service`, которому нужно знать, настроен ли режим); прокси его не получает.
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
| Исходящие | `web.tk-ekat.ru:443`, активный провайдер модели (`api.giga.chat:443` и `ngw.devices.sberbank.ru:9443` или `openrouter.ai:443`), SMTP-сервер (587 или 465); при сборке — Docker Hub и PyPI |
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

### 4.2. Ассистент: GigaChat или OpenRouter

Настройки обоих режимов лежат рядом в `fleet_service/.env`, активный режим выбирает `FS_LLM_PROVIDER`:

```ini
FS_LLM_PROVIDER=gigachat               # gigachat или openrouter

FS_GIGACHAT_CREDENTIALS=<ключ авторизации>
FS_GIGACHAT_SCOPE=GIGACHAT_API_PERS    # PERS — физлица, B2B — пакеты, CORP — pay-as-you-go
FS_GIGACHAT_MODEL=GigaChat-2-Pro
FS_GIGACHAT_CA_BUNDLE=/srv/certs/russian_trusted_root_ca.pem

FS_OPENROUTER_API_KEY=sk-or-...        # ключ OpenRouter владельца стенда
FS_LLM_MODEL=openai/gpt-4o-mini        # модель с поддержкой tool calling — выбирает владелец стенда
```

Без ключа или модели активного режима стенд работает (телеметрия, аномалии), но `/v1/chat` отвечает 503 «ассистент не
настроен». `.env` без `FS_LLM_PROVIDER` (стенд обновили, а файл не трогали) — это `openrouter`, как раньше.

**GigaChat: ключ.** В личном кабинете `developers.sber.ru` создайте проект GigaChat API и получите ключ авторизации:
это Base64 от Client ID и Client Secret. Он идёт в `FS_GIGACHAT_CREDENTIALS`, `FS_GIGACHAT_SCOPE` должен совпасть с
тарифом проекта. Срочный токен (30 минут) шлюз получает и обновляет сам.

**GigaChat: сертификат.** Серверы GigaChat подписаны корневым сертификатом Минцифры, а проверка TLS в шлюзе включена
всегда: настройки отключения нет. Скачайте корневой сертификат с `developers.sber.ru/docs/ru/gigachat/certificates`
и положите в `ml/fleet_service/certs` (в контейнере это `/srv/certs`). Нужен формат PEM:

```bash
openssl x509 -in fleet_service/certs/<файл> -noout -subject     # читается — это уже PEM
openssl x509 -inform DER -in fleet_service/certs/<файл>.cer -out fleet_service/certs/russian_trusted_root_ca.pem   # если нет
```

Ключ задан, а файла нет или он неверный — **шлюз не стартует** с понятной ошибкой в `docker compose logs
gigachat_gateway`, и `docker compose up -d --wait` завершается ошибкой даже в режиме `openrouter`. Чат в этом режиме при
этом работает: шлюз ему не нужен. Без ключа шлюз стартует, `/health` отвечает, а чат в режиме `gigachat` — 503.

**Проверка до переключения.** Шлюз запущен и получает ключ в любом режиме, поэтому качество моделей проверяется, пока
пользователи работают через OpenRouter. Прогон тратит токены:

```bash
docker compose exec -e FS_EVAL_API_KEY=test fleet_service python -m fleet_service.agent.eval_tools \
    --base-url http://gigachat_gateway:8000/v1 --model GigaChat-2-Pro --model GigaChat-2-Max --repeat 3
```

**Переключение и откат** — одна настройка и одна команда, образы и APK не пересобираются:

```bash
# в fleet_service/.env: FS_LLM_PROVIDER=gigachat (откат — FS_LLM_PROVIDER=openrouter)
docker compose up -d --wait
curl -s 127.0.0.1:8080/health          # "llmProvider":"gigachat", "llmConfigured":true
docker compose logs fleet_service | grep 'модель: ответила' | tail -3     # после вопроса в чате: «режим gigachat»
```

Команда перезапускает прокси и шлюз при **любой** правке `fleet_service/.env` (`depends_on.restart`): прокси читает
конфигурацию лишь при старте, а одноразовые шаги настройки получают весь файл. Без правок они не перезапускаются.
`docker compose restart` `.env` не перечитывает: для смены режима он не подходит. Ответы, которые идут в этот момент,
оборвутся, а прокси стартует несколько десятков секунд, поэтому выбирайте спокойное время.

**Что учесть в режиме `gigachat`:**

- **Режимы не подменяют друг друга.** Кончились токены GigaChat или он недоступен — данные не уходят в OpenRouter сами.
- **Резерв работает в обоих режимах, без участия человека.** Если задан `FS_LLM_RESERVE_*` с внешним адресом, туда
  уходят сообщения чата, телеметрия, названия машин, тексты аномалий и черновики писем, как только GigaChat не ответил:
  после исчерпания повторов, при 402 и при отвергнутом запросе (422 от GigaChat прокси повторять не будет, но к резерву
  перейдёт). Какой адрес указать, решает тот, кто настраивает стенд. Сигнал — алерт `LLMReserveAnswering` и строка WARNING
  `модель: ответила … (reserve)`.
- **Один поток на тарифе `PERS`.** Чаты идут по одному: в этом режиме действует меньшее из `FS_CHAT_CONCURRENCY` и
  `FS_GIGACHAT_CONCURRENCY` (`GET /v1/status`, `limits.chat.concurrency`). Ответ с цепочкой tools держит единственное место,
  а ожидание входит в `FS_CHAT_TIMEOUT_SECONDS`: при нескольких пользователях возможны 504 и 503 (`FS_CHAT_QUEUE_MAX`,
  очередь полна). Пока GigaChat завис, чаты идут по одному и получают 504: сбоев за окно меньше `FS_BREAKER_FAILURES`, поэтому
  breaker и переход к резерву могут не сработать.
- **Брошенный запрос.** Когда прокси или стенд бросают запрос, шлюз сразу его отменяет и освобождает своё место. GigaChat
  может ещё считать поток занятым: тогда следующий запрос получит 429, и прокси его повторит.
- **`FS_GIGACHAT_TIMEOUT_SECONDS` меньше `FS_LLM_TIMEOUT_SECONDS`** — проверяется при старте, в режиме `gigachat`.
- **Без прокси** (`FS_LLM_PROXY_URL=` в `ml/.env`) стенд обращается к шлюзу напрямую: без повторов, запасных моделей и резерва.

**Режим `openrouter`:**
`FS_OPENROUTER_REQUIRE_PARAMETERS=true` и `FS_OPENROUTER_DATA_COLLECTION=deny` (по умолчанию) оставляют только
провайдеров, которые поддерживают tools и не сохраняют запросы; если у выбранной модели таких нет, ассистент
отвечает ошибкой — выберите другую модель (ослаблять `deny` — решение владельца).

Повторы и запасные модели (`FS_LLM_RETRIES`, `FS_LLM_FALLBACK_MODELS`, у GigaChat — `FS_GIGACHAT_FALLBACK_MODELS`, и др.) —
в том же файле, описаны в `.env.example` и `fleet_service/README.md` («Модель: прокси, повторы, запасные модели»). Запасную
модель ставьте только после проверки набором `python -m fleet_service.agent.eval_tools`. Кончились кредиты OpenRouter или
токены GigaChat (402) — ассистент отвечает «временно недоступен», в логе `fleet_service` строка уровня ERROR `кончились кредиты`.
Прокси не стартует (`docker compose ps -a`: `llm_proxy_config` не `Exited (0)` или `llm_proxy` не healthy) —
телеметрия и аномалии работают, чат отвечает 503; временно обойти прокси: строка `FS_LLM_PROXY_URL=` в `ml/.env` и
`docker compose up -d` (стенд пойдёт к модели напрямую, без повторов и запасных моделей).

**Резервная модель (необязательно).** Отвечает, когда основной провайдер недоступен или кончились кредиты, и пока открыт
breaker модели (`FS_BREAKER_*`) — тогда стенд сразу идёт к ней. Подключается при запуске контейнеров, без пересборки:

- внешний OpenAI-совместимый адрес — в `fleet_service/.env`:
  ```ini
  FS_LLM_RESERVE_BASE_URL=https://<провайдер>/v1
  FS_LLM_RESERVE_MODEL=<модель с tool calling>
  FS_LLM_RESERVE_API_KEY=<ключ>
  ```
- своя модель на GPU-сервере — профиль `reserve-llm` (vLLM; базовый стенд инференс LLM не тянет, по умолчанию
  профиль не запускается). В `ml/.env` — `RESERVE_LLM_MODEL` (по умолчанию `Qwen/Qwen2.5-7B-Instruct`),
  `RESERVE_LLM_TOOL_PARSER` (`hermes` для Qwen), `RESERVE_LLM_MAX_MODEL_LEN`, при закрытой модели — `HF_TOKEN`; в
  `fleet_service/.env` — `FS_LLM_RESERVE_BASE_URL=http://reserve_llm:8000/v1` и `FS_LLM_RESERVE_MODEL` (то же имя),
  ключ не нужен. Нужны драйвер NVIDIA и nvidia-container-toolkit:
  ```bash
  docker compose --profile reserve-llm up -d --wait     # первая загрузка весов — минуты
  docker compose logs reserve_llm | tail                # «Application startup complete»
  ```

После правки `.env` — `docker compose up -d` (конфигурация прокси пересоздаётся); `/health` покажет
`"reserveConfigured": true`. До включения прогоните резерв набором проверки на tools
(`python -m fleet_service.agent.eval_tools --base-url <адрес резерва> --model <модель>`, `fleet_service/README.md`):
если он заметно хуже основной, оставьте `FS_LLM_RESERVE_DEGRADED=true` — ответы через него получат пометку
«упрощённый режим». Ответ резерва — строка WARNING `модель: ответила … (reserve)` в логе `fleet_service`: если таких
много, проверьте провайдера и кредиты или токены.

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
docker compose ps -a     # fleet_service, llm_proxy, gigachat_gateway, predictive_antifraud — Up (healthy), порты
                         # 127.0.0.1:8080 и 127.0.0.1:8001; llm_proxy_config и gigachat_gateway_config — Exited (0)
```

`--wait` ждёт healthcheck сервисов (до двух минут: прокси модели стартует дольше). Миграции базы применяются при старте
(`FS_DB_AUTO_MIGRATE=true`). Контейнеры с `restart: unless-stopped` поднимаются сами после перезагрузки сервера
(служба docker должна быть включена: `sudo systemctl enable docker`).

Проверка без приложения (на сервере):

```bash
curl -s 127.0.0.1:8080/health
# {"status":"ok","llmProvider":"gigachat","llmConfigured":true,"backgroundConfigured":true,"emailConfigured":true,...}
curl -s 127.0.0.1:8001/health      # {"status":"ok"}
curl -s 127.0.0.1:8001/ready       # {"models":[{"name":"predictive","ready":false,...},{"name":"antifraud",...}]}
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8080/v1/vehicles     # 401 — без токена стенд не отвечает
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
    127.0.0.1:8001/v1/antifraud/check                                   # 401 — аналитика без ключа не отвечает
docker compose logs fleet_service | tail -20      # без ERROR и Traceback
```

`llmConfigured: false` — не заданы ключ или модель активного режима (`FS_GIGACHAT_CREDENTIALS` и `FS_GIGACHAT_MODEL` либо
`FS_OPENROUTER_API_KEY` и `FS_LLM_MODEL`); `backgroundConfigured: false` — нет `FS_ACCESS_ENCRYPTION_KEY` (или `FS_BACKGROUND_ENABLED=false`); `emailConfigured: false` — не заданы SMTP,
отправитель или файл получателей. Предпоследний `curl` даёт 422 вместо 401 — `PA_API_KEY` пуст.

Совпадение ключа аналитики и связь с AutoGRAPH и моделью этими командами не проверяются: для них нужен токен
сессии AutoGRAPH — проверяются из приложения (п. 7). Доступность внешних систем с сервера:

```bash
docker compose exec fleet_service python -c "import httpx; print(httpx.get('https://web.tk-ekat.ru/ServiceJSON/EnumSchemas', timeout=15).status_code)"
docker compose exec llm_proxy python -c "import urllib.request; print(urllib.request.urlopen('https://openrouter.ai/api/v1/models', timeout=15).status)"
# режим gigachat: 401 — связь и сертификат в порядке (токена нет); CERTIFICATE_VERIFY_FAILED — проверьте файл из п. 4.2
docker compose exec gigachat_gateway sh -c 'set -a; . /config/gigachat.env; python -c "import httpx, os, ssl; print(httpx.get(os.environ[\"FS_GIGACHAT_BASE_URL\"] + \"/models\", verify=ssl.create_default_context(cafile=os.environ[\"FS_GIGACHAT_CA_BUNDLE\"]), timeout=15).status_code)"'
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

    # Наружу — только API стенда и /health; /docs и /openapi.json — справка для разработки, /metrics — для Prometheus,
    # который ходит к контейнеру напрямую, минуя nginx (п. 8.7).
    location ~ ^/(docs|redoc|openapi\.json|metrics) { return 404; }

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
6. Telegram-бот (если нужен): бот у @BotFather; на стенде `FS_TELEGRAM_BOT_USERNAME` и
   `FS_TELEGRAM_SERVICE_TOKEN`, у бота `ml/telegram_bot/.env` (`TB_BOT_TOKEN`, `TB_STAND_TOKEN` — тот же ключ);
   `docker compose --profile telegram up -d --build`. Отправьте боту /start и введите код в приложении (Настройки → Telegram).
   Должен прийти ответ «Вы подключены к схеме…», а новые события — карточками не позже чем через 15 минут после
   фоновой проверки. Подробности — [telegram_bot/README.md](telegram_bot/README.md).

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
| `FS_LLM_PROVIDER` и другие настройки модели | Та же команда `docker compose up -d --wait`. Любая правка `fleet_service/.env` перезапускает и прокси, и шлюз: чат недолго недоступен (п. 4.2) |
| файл в `fleet_service/certs` при том же имени | `docker compose restart gigachat_gateway` (сертификат читается при старте) |
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
- **Ключ GigaChat** — `FS_GIGACHAT_CREDENTIALS`, затем `docker compose up -d --wait`: шлюз получит новый токен сам.
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

### 8.7. Мониторинг (необязательно)

Prometheus и Grafana — профиль `monitoring` в `docker-compose.yml`, без него не запускаются:

**Сначала — запрет `/metrics` в nginx.** Эндпоинт без авторизации. В конфиге из п. 6 запрет уже есть; в конфиге,
созданном раньше, строку нужно поправить: `location ~ ^/(docs|redoc|openapi\.json|metrics) { return 404; }`, затем
`sudo nginx -t && sudo systemctl reload nginx`. Потом:

```bash
# fleet_service/.env: FS_METRICS_ENABLED=true (по умолчанию выключено).
# ml/.env: пароль Grafana, без него контейнер не запустится. Порты по умолчанию 9090 и 3000 — PROMETHEUS_PORT, GRAFANA_PORT.
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)" >> .env
docker compose --profile monitoring up -d --wait
```

Пароль Grafana применяется только при первом запуске. Сменить позже:
`docker compose --profile monitoring exec grafana grafana cli admin reset-admin-password <новый пароль>`.

Оба порта опубликованы только на `127.0.0.1`; с рабочей машины — через туннель
`ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 <сервер>`. Дашборд «Петрович: стенд» в Grafana создаётся сам, алерты — в
Prometheus на вкладке Alerts. **Alertmanager не настроен: алерты видны только там, уведомления никуда не уходят.**

| Алерт | Что значит | Что делать |
|---|---|---|
| `LLMCreditsExhausted` | Провайдер (метка `provider`) ответил 402. У GigaChat 402 считает и шлюз, поэтому алерт срабатывает, даже если ответила запасная модель или резерв; у OpenRouter — только без резерва | OpenRouter: пополнить кредиты или поднять лимит ключа. GigaChat: купить пакет токенов для модели или сменить тариф |
| `LLMReserveAnswering` | Ассистент отвечает через резервную модель, данные уходят на её адрес. При заданном резерве это и есть сигнал о 402: прокси уходит к резерву, до стенда 402 не доходит | Проверить кредиты и токены (`docker compose logs llm_proxy gigachat_gateway \| grep 402`), провайдера, сеть стенда |
| `CircuitBreakerOpen` | Breaker не закрыт дольше 5 минут, и пробные запросы не проходят | Смотреть `GET /v1/status` и логи: какая внешняя система недоступна |
| `HighShareOf503` | Больше 5 % ответов стенда — 503 | Смотреть отказы по причине на дашборде: предел, breaker или модель не настроена |

Запросы к GigaChat, получение токена, ожидание потока и тематические отказы считает шлюз: Prometheus собирает его
`/metrics` по внутренней сети (задание `gigachat_gateway`), порт наружу не открыт. На дашборде — активный провайдер, отказы
`blacklist` и нехватка мест в шлюзе.

`GET /metrics` отдаёт сам `fleet_service`. Данных пользователей там нет (метки — только система, код, причина), но
видны объём запросов и состояние внешних систем. На стенде без nginx (п. 10) метрики при `FS_METRICS_ENABLED=true`
доступны всем, кто видит порт.

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
С моделью расчёт тяжелее: повторите нагрузочный тест и при необходимости поправьте `PA_MAX_CONCURRENCY`
(`FS_PREDICTIVE_CONCURRENCY` стенда держите не больше него), `predictive_antifraud/README.md`, «Нагрузка».

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
| `/health` показывает прежний режим после правки `.env` | Использован `docker compose restart`: он `.env` не перечитывает. Выполнить `docker compose up -d --wait` |
| `/health` показывает `gigachat`, а отвечает прежняя модель | Прокси не перезапустился: например, стенд поднят `docker-compose.yml` без `depends_on.restart`. Выполнить `docker compose up -d --wait --force-recreate llm_proxy_config llm_proxy`; в логе `fleet_service` должно быть `режим gigachat` |
| `gigachat_gateway` перезапускается, в логе `FS_GIGACHAT_CA_BUNDLE` | Ключ задан, а файла сертификата нет или он не в формате PEM (п. 4.2) |
| Шлюз: `GigaChat отверг ключ авторизации` | Неверный `FS_GIGACHAT_CREDENTIALS` или `FS_GIGACHAT_SCOPE` не совпадает с тарифом; после правки — `docker compose up -d --wait` |
| Шлюз: `CERTIFICATE_VERIFY_FAILED` | Сервер подписан не тем корневым сертификатом, что в `FS_GIGACHAT_CA_BUNDLE`: проверьте файл (п. 4.2). Отключить проверку нельзя |
| Шлюз: `GigaChat ответил 422` на обращение после вызова функции | Сверить вид `arguments`: поставить `FS_GIGACHAT_ARGUMENTS_FORMAT=string`, затем `docker compose up -d --wait` |
| Шлюз: `кончились токены модели (402)` | Токены этой модели на тарифе закончились: пакет токенов, другая модель или `FS_GIGACHAT_FALLBACK_MODELS` |
| Шлюз: `тематическое ограничение (blacklist)` | Модель отказалась отвечать по теме; пользователь видит её текст. Если это обычные вопросы про сливы топлива, решает владелец (п. 5 плана) |
| Ассистент: 504 при нескольких пользователях в режиме `gigachat` | Один поток: ответы идут по одному (п. 4.2). Уменьшить `FS_CHAT_QUEUE_MAX`, чтобы отказывать сразу, или перейти на тариф с большим числом потоков |
| Ассистент: 504 | Модель не уложилась в `FS_LLM_TIMEOUT_SECONDS` / `FS_CHAT_TIMEOUT_SECONDS` (держать меньше 120 с) |
| Письма: «Письмо не отправлено: …» | SMTP: адрес, порт и `FS_SMTP_SECURITY` должны соответствовать друг другу (587 — `starttls`, 465 — `ssl`), пароль; исходящий порт открыт у хостера |
| На «Главной» карточка «Стенд давно не проверял данные» | Стенд не достучался до AutoGRAPH или токены истекли — `docker compose logs fleet_service \| grep 'фоновая проверка'` |

## 12. Как проверена инструкция

2026-11-05, ветка `gigachat/step-4`, режимы моделей (п. 4.2): `smoke.sh` на подставном GigaChat — переключение настройкой и
командой `up -d --wait` в обе стороны, отсутствие ключа у прокси, очередь чатов при одном потоке, 402 и 422 с резервом и без
него; TLS — на локальном HTTPS-сервере в тестах. **Настоящий GigaChat, сертификат Минцифры, получение ключа и команды
проверки связи с ним не проверялись: это шаг 5 плана `docs/gigachat-plan.md`, его делает человек.**

Основная инструкция:

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
