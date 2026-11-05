#!/usr/bin/env bash
# Сквозной сценарий стенда без реальных ключей: docker compose (fleet_service + прокси модели LiteLLM +
# predictive_antifraud + подставные OpenRouter и AutoGRAPH; predictive_antifraud настоящий, без моделей) → /health →
# машины → телеметрия → вопрос с вызовом tool → сбои модели через прокси (429 с повтором, переход к запасной модели,
# 402 без повтора и ответ резерва, breaker: резерв без OpenRouter и пробный запрос) → проверка машины → вопросы об аномалиях → проверка с сохранением → лента → решение → вопросы
# ассистенту о ленте и решении → перенос решений с устройства → фоновая проверка (доступ с паролем по согласию,
# стенд проверяет сам, одно уведомление на пользователя) → письмо (черновик от ассистента → подтверждение → письмо
# у подставного SMTP, повторное подтверждение отклонено) → перезапуск стенда (хранилище и доступ на месте) → отзыв
# доступа → проверка при остановленной аналитике.
# В конце — переключение режима: FS_LLM_PROVIDER=gigachat и обычный up -d --wait (чат с tool через шлюз на строгом
# подставном GigaChat, ноль запросов к подставному OpenRouter, очередь чатов при одном потоке) и обратно.
# Запуск из любого каталога: ml/fleet_service/scripts/smoke.sh
# Порты на хосте: SMOKE_FLEET_PORT (18080), SMOKE_PREDICTIVE_PORT (18001). KEEP=1 — не останавливать стенд.
set -euo pipefail

ML="$(cd "$(dirname "$0")/../.." && pwd)"
export FLEET_SERVICE_PORT="${SMOKE_FLEET_PORT:-18080}"
export PREDICTIVE_PORT="${SMOKE_PREDICTIVE_PORT:-18001}"
COMPOSE=(docker compose -p fleet-smoke -f "$ML/docker-compose.yml" -f "$ML/fleet_service/scripts/smoke.compose.yml")
BASE="http://127.0.0.1:$FLEET_SERVICE_PORT"
AUTH=(-H "Authorization: Bearer valid-token" -H "X-Schema-Id: schema-1")

RESULT_DIR="$(mktemp -d)"

cleanup() {
    local code=$?
    if [ "$code" != 0 ]; then
        echo "--- логи стенда ---"
        "${COMPOSE[@]}" logs --no-color --tail 80 || true
    fi
    [ "${KEEP:-0}" = 1 ] || "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$RESULT_DIR"
    exit "$code"
}
trap cleanup EXIT

step() { echo "▶ $*"; }
fail() { echo "✗ $*" >&2; exit 1; }
# Проверка JSON без jq: python3 из системы, выражение над переменной d.
# Вывод проверяется целиком, а не конвейером: при pipefail grep -q обрывает писателя (SIGPIPE), и утечка осталась бы
# незамеченной, а верный вывод уронил бы сценарий.
contains() { grep -qE "$2" <<<"$1"; }
json_check() { python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if ($1) else 1)"; }

step "сборка и запуск compose"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 180

step "GET /health fleet_service"
curl -fsS "$BASE/health" | json_check \
    'd["status"] == "ok" and d["llmConfigured"] and d["backgroundConfigured"] and d["emailConfigured"]' \
    || fail "fleet_service /health"

step "GET /health predictive_antifraud"
curl -fsS "http://127.0.0.1:$PREDICTIVE_PORT/health" | json_check 'd["status"] == "ok"' \
    || fail "predictive_antifraud /health"

QUESTION='{"messages":[{"role":"user","content":"Сколько топлива у машин парка?"}],"utcOffsetMinutes":300}'

step "POST /v1/chat без токена → 401"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$QUESTION" "$BASE/v1/chat")
[ "$code" = 401 ] || fail "ожидался 401, получен $code"

step "POST /v1/chat с недействительным токеном → 401"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Authorization: Bearer expired" -H "X-Schema-Id: schema-1" -d "$QUESTION" "$BASE/v1/chat")
[ "$code" = 401 ] || fail "ожидался 401, получен $code"

step "GET /v1/vehicles → машины пользователя"
vehicles=$(curl -fsS "${AUTH[@]}" "$BASE/v1/vehicles")
echo "$vehicles" | json_check '[v["id"] for v in d] == ["veh-1", "veh-2"]' || fail "список машин: $vehicles"

step "GET /v1/telemetry → VehicleTelemetry за сутки"
PERIOD="from=2026-09-16T00:00:00&to=2026-09-17T00:00:00&utcOffsetMinutes=300"
curl -fsS "${AUTH[@]}" "$BASE/v1/telemetry?vehicleId=veh-1&$PERIOD" | json_check \
    'len(d["tables"]["FUEL"]["timestamps"]) == 721 and set(d["tables"]["FUEL"]["columns"][0]["values"]) == {250.0}' \
    || fail "телеметрия veh-1"

step "GET /v1/telemetry чужой машины → 404"
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer other-token" -H "X-Schema-Id: schema-1" \
    "$BASE/v1/telemetry?vehicleId=veh-2&$PERIOD")
[ "$code" = 404 ] || fail "ожидался 404, получен $code"

step "POST /v1/chat → ответ с числом из tool get_vehicle_summary"
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d.get("reply", "")' || fail "ответ без данных tool: $reply"
echo "  ответ: $reply"

# Управление подставным OpenRouter из контейнера fakes (его порт наружу не открыт): путь и тело JSON.
fakes_api() { "${COMPOSE[@]}" exec -T fakes python -c '
import sys, urllib.request
data = sys.argv[2].encode() if len(sys.argv) > 2 else None
req = urllib.request.Request("http://localhost:9000" + sys.argv[1], data=data, headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req).read().decode())' "$@"; }

step "прокси модели передал OpenRouter маршрутизацию провайдеров (require_parameters)"
fakes_api /openrouter/_requests | json_check \
    'd and all(r["model"] == "fake/model" and r["provider"]["require_parameters"] is True for r in d)' \
    || fail "прокси не передал provider или модель"

step "OpenRouter ответил 429 с Retry-After → прокси повторил, ответ есть"
fakes_api /openrouter/_failures '[{"status":429,"retryAfter":1}]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d.get("reply", "")' || fail "ответ после 429: $reply"
# Три обращения к модели за ответ и один повтор.
fakes_api /openrouter/_requests | json_check 'len(d) == 4' || fail "прокси не повторил запрос после 429"

step "основная модель недоступна (503 × 3) → ответила запасная x/fallback"
fakes_api /openrouter/_failures '[{"status":503},{"status":503},{"status":503}]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d.get("reply", "")' || fail "ответ запасной модели: $reply"
fakes_api /openrouter/_requests | json_check '[r["model"] for r in d][:4] == ["fake/model"] * 3 + ["x/fallback"]' \
    || fail "прокси не перешёл к запасной модели"
contains "$("${COMPOSE[@]}" logs --no-color fleet_service)" 'модель: ответила .* \(fallback\), копия прокси fallback-1' \
    || fail "в логе стенда не видно ответа запасной модели"

NOTICE="упрощённом режиме"
step "OpenRouter ответил 402 → без повторов, ответила резервная модель вне OpenRouter с пометкой"
fakes_api /openrouter/_failures '[{"status":402},{"status":402}]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d["reply"] and "'"$NOTICE"'" in d["reply"]' \
    || fail "ответ резерва при 402: $reply"
# Первое обращение: основная и запасная модель — по одному разу (402 не повторяется), ответил резерв; следующие
# обращения ответа (402 в очереди кончились) — снова OpenRouter.
fakes_api /openrouter/_requests | json_check '[r["model"] for r in d][:3] == ["fake/model", "x/fallback", "fake/model"]' \
    || fail "402 повторялся"
fakes_api /reserve/_requests | json_check '[r["model"] for r in d] == ["fake/reserve"]' || fail "резерв не вызывался"
contains "$("${COMPOSE[@]}" logs --no-color fleet_service)" 'WARNING.*модель: ответила .* \(reserve\), копия прокси reserve' \
    || fail "в логе стенда нет WARNING об ответе резерва"

step "OpenRouter лежит (503 подряд) → breaker стенда открыт: следующий ответ — резерв без запросов к OpenRouter"
MANY_503=$(python3 -c 'import json; print(json.dumps([{"status": 503}] * 60))')
fakes_api /openrouter/_failures "$MANY_503" >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"'"$NOTICE"'" in d["reply"]' || fail "ответ при лежащем OpenRouter: $reply"
fakes_api /openrouter/_failures "$MANY_503" >/dev/null
started=$(date +%s%N)
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
elapsed_ms=$(( ($(date +%s%N) - started) / 1000000 ))
echo "$reply" | json_check '"'"$NOTICE"'" in d["reply"]' || fail "ответ при открытом breaker: $reply"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "при открытом breaker стенд всё равно ходил в OpenRouter"
curl -fsS "$BASE/health" | json_check 'd["breakers"]["llm"] == "open" and d["reserveConfigured"]' \
    || fail "/health не показывает открытый breaker модели"
echo "  ответ при открытом breaker за ${elapsed_ms} мс, запросов к OpenRouter: 0"

step "через FS_BREAKER_OPEN_SECONDS — пробный запрос к OpenRouter, ответ основной модели без пометки"
sleep 4
fakes_api /openrouter/_failures '[]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d["reply"] and "'"$NOTICE"'" not in d["reply"]' \
    || fail "после cooldown: $reply"
fakes_api /openrouter/_requests | json_check 'len(d) >= 1' || fail "после срока breaker'а стенд не вернулся к OpenRouter"
curl -fsS "$BASE/health" | json_check 'd["breakers"]["llm"] == "closed"' || fail "breaker модели не закрылся"

step "повторный вопрос о прошлой неделе → телеметрия из кэша, AutoGRAPH не вызывается (видно в логе)"
ASK_WEEK='{"messages":[{"role":"user","content":"Сколько топлива было у FAW на прошлой неделе?"}],"utcOffsetMinutes":300}'
for _ in 1 2; do
    reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$ASK_WEEK" "$BASE/v1/chat")
    echo "$reply" | json_check '"средний уровень топлива 250.0 л за 2026-09-07T00:00" in d["reply"]' \
        || fail "ответ о прошлой неделе: $reply"
done
week_logs=$("${COMPOSE[@]}" logs --no-color fleet_service)
[ "$(grep -c 'телеметрия: схема schema-1, машина veh-1, 2026-09-07 00:00:00 — 2026-09-14 00:00:00, параметров' <<<"$week_logs")" = 1 ] \
    || fail "прошлая неделя загружалась из AutoGRAPH не один раз"
grep -q 'машина veh-1, 2026-09-07 00:00:00 — 2026-09-14 00:00:00 — из кэша (закрытый период)' <<<"$week_logs" \
    || fail "в логе нет ответа из кэша за прошлую неделю"

CHECK='{"vehicleId":"veh-2","from":"2026-09-16T06:00:00","to":"2026-09-16T12:00:00","utcOffsetMinutes":300}'
step "POST /v1/anomalies/check → правила нашли перегрев, модели аналитики не загружены"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$CHECK" "$BASE/v1/anomalies/check" | json_check \
    'd["rulesAnomalies"] == 6 and d["anomalies"][0]["id"] == "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T06:30"
     and d["analytics"]["predictive"]["status"] == "not_ready" and d["modelsReady"] is False' \
    || fail "проверка veh-2"

step "POST /v1/chat «есть ли аномалии» → найдены (check_vehicle)"
ASK_URAL='{"messages":[{"role":"user","content":"Есть ли аномалии у Урал?"}],"utcOffsetMinutes":300}'
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$ASK_URAL" "$BASE/v1/chat")
echo "$reply" | json_check '"найдено аномалий" in d.get("reply", "") and "Предиктивная проверка недоступна" in d["reply"]' \
    || fail "ответ об аномалиях: $reply"
echo "  ответ: $reply"

step "POST /v1/chat «проверь FAW» → нарушений по правилам нет, предиктивная проверка недоступна"
ASK_FAW='{"messages":[{"role":"user","content":"Проверь FAW на нарушения"}],"utcOffsetMinutes":300}'
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$ASK_FAW" "$BASE/v1/chat")
echo "$reply" | json_check '"предиктивная проверка недоступна" in d.get("reply", "")' || fail "ответ о FAW: $reply"
echo "  ответ: $reply"

SCAN='{"vehicleIds":["veh-2"],"from":"2026-09-16T06:00:00","to":"2026-09-16T12:00:00","utcOffsetMinutes":300}'
step "POST /v1/anomalies/scan → проверка с сохранением: 6 новых аномалий"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$SCAN" "$BASE/v1/anomalies/scan" | json_check \
    'd["results"][0]["ok"] and len(d["results"][0]["newIds"]) == 6 and d["lastScanAt"]' || fail "проверка с сохранением"

step "повторная проверка другим окном → те же id, новых нет"
SCAN2='{"vehicleIds":["veh-2"],"from":"2026-09-16T06:35:00","to":"2026-09-16T09:35:00","utcOffsetMinutes":300}'
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$SCAN2" "$BASE/v1/anomalies/scan" | json_check \
    'd["results"][0]["newIds"] == [] and len(d["results"][0]["anomalies"]) == 4' || fail "повторная проверка"

STORE_PERIOD="utcOffsetMinutes=300&from=2026-09-16T00:00:00&to=2026-09-17T00:00:00"
step "GET /v1/anomalies → лента из хранилища без дубликатов"
curl -fsS "${AUTH[@]}" "$BASE/v1/anomalies?$STORE_PERIOD" | json_check \
    'd["total"] == 6 and len({a["id"] for a in d["items"]}) == 6' || fail "лента из хранилища"

ID='rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z'
ENC_ID=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$ID")
step "POST /v1/anomalies/{id}/resolve → ложная тревога, кто решил — из X-User-Name"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -H 'X-User-Name: %D0%9F%D0%B5%D1%82%D1%80%D0%BE%D0%B2' \
    -d '{"resolution":"FALSE_ALARM","reason":"Ошибка датчика","utcOffsetMinutes":300}' \
    "$BASE/v1/anomalies/$ENC_ID/resolve" | json_check \
    'd["resolution"] == "FALSE_ALARM" and d["resolvedBy"] == "Петров"' || fail "решение по аномалии"

step "POST /v1/chat «какие аномалии 16.09» → list_anomalies"
ASK_LIST='{"messages":[{"role":"user","content":"Какие аномалии были 16.09?"}],"utcOffsetMinutes":300}'
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$ASK_LIST" "$BASE/v1/chat")
echo "$reply" | json_check '"аномалий за период: 6" in d.get("reply", "")' || fail "ответ о ленте: $reply"
echo "  ответ: $reply"

step "POST /v1/chat из карточки (anomalyId) «что решили» → get_anomaly"
ASK_DECISION="{\"messages\":[{\"role\":\"user\",\"content\":\"Что решили по этой аномалии?\"}],\"utcOffsetMinutes\":300,\"anomalyId\":\"$ID\"}"
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$ASK_DECISION" "$BASE/v1/chat")
echo "$reply" | json_check '"ложная тревога (Ошибка датчика), решил Петров" in d.get("reply", "")' \
    || fail "ответ о решении: $reply"
echo "  ответ: $reply"

step "POST /v1/anomalies/import → решение с устройства перенесено, несопоставленное — в отчёте"
IMPORT='{"utcOffsetMinutes":300,"items":[
  {"localId":"rule|overheat|veh-2|TemperatureCOOL|2026-09-16T07:30","vehicleId":"veh-2","kind":"overheat",
   "parameterName":"TemperatureCOOL","eventTime":"2026-09-16T07:30","resolution":"CONFIRMED"},
  {"localId":"rule|drain|veh-2|FuelDrainVolume|2026-09-16T07:00","vehicleId":"veh-2","kind":"drain",
   "parameterName":"FuelDrainVolume","eventTime":"2026-09-16T07:00","resolution":"CONFIRMED"}]}'
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$IMPORT" "$BASE/v1/anomalies/import" | json_check \
    'd["applied"] == 1 and len(d["unmatched"]) == 1 and d["unmatched"][0]["localId"].startswith("rule|drain")' \
    || fail "перенос решений"

DEVICE="6f1d2c3e-8a4b-4c5d-9e6f-0a1b2c3d4e5f"
PETR=(-H "X-User-Name: $(python3 -c 'import urllib.parse; print(urllib.parse.quote("Пётр"))')")
step "POST /v1/background/access → доступ с паролем по согласию (стенд проверил пароль входом)"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" "${PETR[@]}" \
    -d "{\"deviceId\":\"$DEVICE\",\"utcOffsetMinutes\":300,\"savePassword\":true,\"password\":\"fake-password\"}" \
    "$BASE/v1/background/access" | json_check \
    'd["registered"] and d["tokenActive"] and d["passwordStored"] and d["intervalMinutes"] == 0.05' \
    || fail "выдача доступа"

step "фоновая проверка: стенд сам проверил схему за последние 3 ч"
for _ in $(seq 1 40); do
    curl -fsS "${AUTH[@]}" "$BASE/v1/background/access?deviceId=$DEVICE" | json_check 'd["lastOkAt"]' && break
    sleep 1
done
curl -fsS "${AUTH[@]}" "$BASE/v1/background/access?deviceId=$DEVICE" | json_check 'd["lastOkAt"] and d["lastError"] is None' \
    || fail "фоновая проверка не прошла"
RECENT=$(python3 -c 'from datetime import datetime, timedelta, UTC; print((datetime.now(UTC) + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S"))')
curl -fsS "${AUTH[@]}" "$BASE/v1/anomalies?utcOffsetMinutes=300&vehicleId=veh-2&from=$RECENT" | json_check \
    'd["total"] >= 2 and all(a["title"] for a in d["items"])' || fail "фоновая проверка не пополнила ленту"

step "POST /v1/notifications/claim → одно уведомление на пользователя на двух устройствах"
CLAIM='"ids":["rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z","rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z"]'
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" "${PETR[@]}" -d "{\"deviceId\":\"$DEVICE\",$CLAIM}" \
    "$BASE/v1/notifications/claim" | json_check 'len(d["ids"]) == 2' || fail "отметка уведомлений"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" "${PETR[@]}" \
    -d "{\"deviceId\":\"0a0b0c0d-0000-4000-8000-00000000000b\",$CLAIM}" \
    "$BASE/v1/notifications/claim" | json_check 'd["ids"] == []' || fail "второе устройство получило повтор"

smtp_messages() { "${COMPOSE[@]}" exec -T fakes python -c \
    'import urllib.request; print(urllib.request.urlopen("http://localhost:9000/smtp/messages").read().decode())'; }
ASK_MAIL='{"messages":[{"role":"user","content":"Подготовь письмо механику о перегреве Урала"}],"utcOffsetMinutes":300,"allowEmail":true}'
step "POST /v1/chat «подготовь письмо» → черновик (list_recipients → draft_email), письмо не отправлено"
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" "${PETR[@]}" -d "$ASK_MAIL" "$BASE/v1/chat")
echo "$reply" | json_check 'len(d["drafts"]) == 1 and d["drafts"][0]["recipients"][0]["id"] == "mechanic"
    and "отправьте его кнопкой" in d["reply"]' || fail "черновик письма: $reply"
DRAFT=$(python3 -c 'import json, sys; print(json.loads(sys.argv[1])["drafts"][0]["id"])' "$reply")
smtp_messages | json_check 'd == []' || fail "письмо ушло без подтверждения"

step "POST /v1/emails/{id}/confirm чужим пользователем → 404"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" -H 'X-User-Name: other' "$BASE/v1/emails/$DRAFT/confirm")
[ "$code" = 404 ] || fail "ожидался 404, получен $code"

step "POST /v1/emails/{id}/confirm → письмо получено подставным SMTP"
curl -fsS -X POST "${AUTH[@]}" "${PETR[@]}" "$BASE/v1/emails/$DRAFT/confirm" | json_check 'd["status"] == "sent"' \
    || fail "подтверждение письма"
smtp_messages | json_check 'len(d) == 1 and d[0]["to"] == ["mechanic@example.org"]
    and d[0]["subject"] == "Перегрев: Урал NEXT А001АА" and "по запросу пользователя Пётр" in d[0]["body"]' \
    || fail "письмо не дошло до SMTP"

step "повторное подтверждение → 409, второго письма нет"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" "${PETR[@]}" "$BASE/v1/emails/$DRAFT/confirm")
[ "$code" = 409 ] || fail "ожидался 409, получен $code"
smtp_messages | json_check 'len(d) == 1' || fail "второе письмо"

step "перезапуск fleet_service → хранилище и решение на месте"
"${COMPOSE[@]}" restart fleet_service >/dev/null 2>&1
for _ in $(seq 1 60); do curl -fsS "$BASE/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "${AUTH[@]}" "$BASE/v1/anomalies?$STORE_PERIOD&status=false_alarm" | json_check \
    'd["total"] == 1 and d["items"][0]["resolvedBy"] == "Петров" and d["lastScanAt"]' \
    || fail "хранилище после перезапуска"

step "доступ фоновой проверки пережил перезапуск; отзыв доступа"
curl -fsS "${AUTH[@]}" "$BASE/v1/background/access?deviceId=$DEVICE" | json_check \
    'd["registered"] and d["passwordStored"]' || fail "доступ после перезапуска"
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "{\"deviceId\":\"$DEVICE\"}" \
    "$BASE/v1/background/access/revoke" | json_check 'd["registered"] is False' || fail "отзыв доступа"
curl -fsS "${AUTH[@]}" "$BASE/v1/background/access?deviceId=$DEVICE" | json_check 'd["registered"] is False' \
    || fail "доступ остался после отзыва"

step "predictive_antifraud остановлен → проверка по правилам работает, аналитика unavailable"
"${COMPOSE[@]}" stop predictive_antifraud >/dev/null 2>&1
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$CHECK" "$BASE/v1/anomalies/check" | json_check \
    'd["rulesAnomalies"] == 6 and d["analytics"]["antifraud"]["status"] == "unavailable"' \
    || fail "проверка без аналитики"

step "неверный адрес OpenRouter (FS_OPENROUTER_BASE_URL) при заданном резерве → чат отвечает через резерв"
SMOKE_OPENROUTER_URL=http://openrouter.invalid/api/v1 "${COMPOSE[@]}" up -d --wait --wait-timeout 180 \
    --force-recreate llm_proxy_config llm_proxy >/dev/null 2>&1
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d["reply"] and "'"$NOTICE"'" in d["reply"]' \
    || fail "ответ при неверном адресе OpenRouter: $reply"

logs=$("${COMPOSE[@]}" logs --no-color fleet_service)
step "GET /metrics: метрики для алертов на месте, без токенов и схем"
metrics=$(curl -fsS "$BASE/metrics")
for series in fleet_llm_responses_total 'fleet_llm_credits_exhausted_total{provider=' fleet_upstream_requests_total \
    fleet_rejections_total fleet_cache_requests_total fleet_breaker_state fleet_queue_depth 'http_requests_total{' \
    'fleet_llm_provider_info{provider="openrouter"} 1.0'; do
    grep -qF "$series" <<<"$metrics" || fail "в /metrics нет $series"
done
grep -q 'status="200"' <<<"$metrics" || fail "http_requests_total без точного кода ответа"
if grep -qE 'valid-token|schema-1|veh-1' <<<"$metrics"; then fail "в /metrics попали данные пользователей"; fi

step "вызовы tools видны в логе стенда"
grep -q 'модель вызывает tool get_vehicle_summary' <<<"$logs" || fail "в логе нет вызова get_vehicle_summary"
grep -q 'модель вызывает tool check_vehicle' <<<"$logs" || fail "в логе нет вызова check_vehicle"
grep -q 'модель вызывает tool list_anomalies' <<<"$logs" || fail "в логе нет вызова list_anomalies"
grep -q 'модель вызывает tool get_anomaly' <<<"$logs" || fail "в логе нет вызова get_anomaly"

step "фоновая проверка видна в логе стенда"
grep -q 'фоновая проверка: схема schema-1' <<<"$logs" || fail "в логе нет фоновой проверки"

step "в логах стенда нет токена, ключа, пароля, адресов и текста письма"
if grep -qE 'valid-token|other-token|login-token|test-key|reserve-key|fake-password|smtp-pass|mechanic@example|системы охлаждения' <<<"$logs"; then
    fail "токен, ключ, пароль, адрес или текст письма попал в лог fleet_service"
fi

step "в логах прокси модели нет ключей OpenRouter и резерва"
if contains "$("${COMPOSE[@]}" logs --no-color llm_proxy llm_proxy_config)" 'test-key|reserve-key|test-gigachat-key'; then
    fail "ключ модели попал в лог прокси"
fi

step "в базе стенда нет токена и пароля в открытом виде"
if contains "$("${COMPOSE[@]}" exec -T fleet_service sh -c 'cat /srv/data/fleet.db*' | tr -d '\000')" 'valid-token|login-token|fake-password'; then
    fail "токен или пароль лежит в базе в открытом виде"
fi

gigachat_api() { "${COMPOSE[@]}" exec -T fakes python -c '
import sys, urllib.request
data = sys.argv[2].encode() if len(sys.argv) > 2 else None
req = urllib.request.Request("http://localhost:9000" + sys.argv[1], data=data, headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req).read().decode())' "$@"; }
gateway_metrics() { "${COMPOSE[@]}" exec -T gigachat_gateway python -c \
    'import urllib.request; print(urllib.request.urlopen("http://localhost:8000/metrics").read().decode())'; }

step "шлюз запущен и в режиме openrouter: проверка качества идёт до переключения, данные в OpenRouter не меняются"
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 >/dev/null 2>&1
gigachat_api /gigachat/_config '{}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
"${COMPOSE[@]}" exec -T -e FS_EVAL_API_KEY=test fleet_service python -m fleet_service.agent.eval_tools \
    --base-url http://gigachat_gateway:8000/v1 --model GigaChat-2-Pro >"$RESULT_DIR/eval.txt"
grep -q 'Верный tool: ' "$RESULT_DIR/eval.txt" || fail "eval_tools через шлюз: $(tail -3 "$RESULT_DIR/eval.txt")"
if grep -q 'ошибка:' "$RESULT_DIR/eval.txt"; then fail "eval_tools через шлюз получил ошибки"; fi
gigachat_api /gigachat/_stats | json_check 'd["requests"] > 0' || fail "eval_tools не дошёл до GigaChat"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "eval_tools ходил в OpenRouter"

step "FS_LLM_PROVIDER=gigachat, docker compose up -d --wait → чат с tool через шлюз, ноль запросов к OpenRouter"
export SMOKE_LLM_PROVIDER=gigachat
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 >/dev/null 2>&1
curl -fsS "$BASE/health" | json_check 'd["llmProvider"] == "gigachat" and d["llmConfigured"]' || fail "/health в режиме gigachat"
gigachat_api /gigachat/_config '{}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d.get("reply", "")' || fail "ответ через шлюз: $reply"
echo "  ответ: $reply"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "в режиме gigachat были запросы к OpenRouter"
fakes_api /reserve/_requests | json_check 'd == []' || fail "в режиме gigachat были запросы к резерву"
gigachat_api /gigachat/_requests | json_check '
    len(d) == 3 and [m["function_call"]["name"] for m in d[-1]["messages"] if "function_call" in m] == ["list_vehicles", "get_vehicle_summary"]
    and all(m["functions_state_id"] for m in d[-1]["messages"] if "function_call" in m)
    and [m["role"] for m in d[0]["messages"]] == ["system", "user"]' \
    || fail "через прокси потерялся functions_state_id или цепочка tools"
contains "$("${COMPOSE[@]}" logs --no-color fleet_service)" 'модель: ответила .* \(primary\), копия прокси primary, режим gigachat' \
    || fail "в логе стенда не видно ответа в режиме gigachat"

step "ключ GigaChat не виден контейнеру llm_proxy и логам; файл шлюза с правами 600"
if contains "$("${COMPOSE[@]}" exec -T llm_proxy sh -c 'env; cat /config/litellm.env /config/litellm.yaml')" 'test-gigachat-key'; then
    fail "ключ GigaChat виден контейнеру llm_proxy"
fi
if contains "$("${COMPOSE[@]}" exec -T llm_proxy sh -c 'env; cat /config/litellm.env')" 'test-key'; then
    fail "в режиме gigachat прокси видит ключ OpenRouter"
fi
[ "$("${COMPOSE[@]}" exec -T gigachat_gateway stat -c %a /config/gigachat.env | tr -d '[:space:]')" = 600 ] || fail "права gigachat.env"
if contains "$("${COMPOSE[@]}" exec -T gigachat_gateway sh -c 'env; cat /config/gigachat.env')" 'smtp-pass|reserve-key|pa-key|test-key|AAAAAAAA'; then
    fail "шлюз видит чужие секреты"
fi
if contains "$("${COMPOSE[@]}" logs --no-color llm_proxy llm_proxy_config gigachat_gateway gigachat_gateway_config fleet_service)" 'test-gigachat-key|giga-token'; then
    fail "ключ или токен GigaChat попал в лог"
fi

step "один поток: одновременные чаты идут по очереди, шлюз не отвечает 429, breaker не открывается"
curl -fsS -H "Authorization: Bearer smoke-admin" "$BASE/v1/status" | json_check \
    'd["llm"]["provider"] == "gigachat" and d["llm"]["model"] == "GigaChat-2-Pro" and d["limits"]["chat"]["concurrency"] == 1' \
    || fail "/v1/status в режиме gigachat"
gigachat_api /gigachat/_config '{"delay":0.5,"maxStreams":1}' >/dev/null
for n in 1 2 3 4; do
    curl -sS -o "$RESULT_DIR/chat-$n.json" -w '%{http_code}' -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" \
        "$BASE/v1/chat" >"$RESULT_DIR/chat-$n.code" &
done
wait
for n in 1 2 3 4; do
    [ "$(cat "$RESULT_DIR/chat-$n.code")" = 200 ] || fail "чат $n: $(cat "$RESULT_DIR/chat-$n.code") $(cat "$RESULT_DIR/chat-$n.json")"
done
gigachat_api /gigachat/_stats | json_check 'd["peak"] == 1 and d["rejected"] == 0 and d["requests"] == 12' \
    || fail "потоки GigaChat: $(gigachat_api /gigachat/_stats)"
grep -qF 'fleet_rejections_total{reason="gigachat_no_slot"} 0.0' <<<"$(gateway_metrics)" || fail "шлюз отвечал 429: нет места"
curl -fsS "$BASE/health" | json_check 'd["breakers"]["llm"] == "closed"' || fail "breaker открылся при очереди чатов"
gigachat_api /gigachat/_config '{}' >/dev/null

step "GigaChat ответил 429 с Retry-After → прокси повторил через шлюз; 422 → без повторов, ответ резерва"
gigachat_api /gigachat/_config '{"failures":[{"status":429,"retryAfter":1}]}' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d["reply"]' || fail "ответ после 429 через шлюз: $reply"
gigachat_api /gigachat/_stats | json_check 'd["requests"] == 4' || fail "прокси не повторил запрос после 429 шлюза"
gigachat_api /gigachat/_config '{"failures":[{"status":422,"message":"Invalid params"}]}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"'"$NOTICE"'" in d["reply"]' || fail "ответ резерва при 422 GigaChat: $reply"
# Одно обращение с 422 без повтора (шлюз отдаёт 400, прокси уходит к резерву) и два следующих обращения ответа.
gigachat_api /gigachat/_stats | json_check 'd["requests"] == 3' || fail "422 шлюза повторялся: $(gigachat_api /gigachat/_stats)"
fakes_api /reserve/_requests | json_check 'len(d) == 1' || fail "при 422 GigaChat не ответил резерв"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "при 422 GigaChat данные ушли в OpenRouter"
gigachat_api /gigachat/_config '{}' >/dev/null

step "GigaChat ответил 402 → без повторов, ответ резерва с пометкой; запросов к OpenRouter нет"
gigachat_api /gigachat/_config '{"failures":[{"status":402,"message":"Payment required"}]}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"'"$NOTICE"'" in d["reply"]' || fail "ответ резерва при 402 GigaChat: $reply"
fakes_api /reserve/_requests | json_check 'len(d) == 1' || fail "резерв при 402 GigaChat"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "при 402 GigaChat данные ушли в OpenRouter"
gigachat_api /gigachat/_config '{}' >/dev/null

step "метрики режима gigachat: провайдер, запросы шлюза к GigaChat, 402 по провайдеру"
metrics=$(curl -fsS "$BASE/metrics")
grep -qF 'fleet_llm_provider_info{provider="gigachat"} 1.0' <<<"$metrics" || fail "в /metrics нет активного провайдера"
grep -qF 'fleet_llm_credits_exhausted_total{provider="gigachat"}' <<<"$metrics" || fail "в /metrics нет ряда 402 GigaChat"
gateway=$(gateway_metrics)
grep -qF 'upstream="gigachat"' <<<"$gateway" || fail "шлюз не считает запросы к GigaChat"
grep -qF 'upstream="gigachat_oauth"' <<<"$gateway" || fail "шлюз не считает получение токена"

step "режим gigachat без резерва: 402 → 503 без повторов и счётчик; 422 → 502 без повторов; данные никуда больше не уходят"
export SMOKE_RESERVE_URL=
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 >/dev/null 2>&1
curl -fsS "$BASE/health" | json_check 'd["reserveConfigured"] is False and d["llmProvider"] == "gigachat"' || fail "/health без резерва"
gigachat_api /gigachat/_config '{"failures":[{"status":402,"message":"Payment required"}]}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
code=$(curl -s -o "$RESULT_DIR/402.json" -w '%{http_code}' -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
[ "$code" = 503 ] || fail "402 GigaChat без резерва: ожидался 503, получен $code: $(cat "$RESULT_DIR/402.json")"
gigachat_api /gigachat/_stats | json_check 'd["requests"] == 1' || fail "402 GigaChat повторялся: $(gigachat_api /gigachat/_stats)"
grep -qF 'fleet_llm_credits_exhausted_total{provider="gigachat"} 1.0' <<<"$(curl -fsS "$BASE/metrics")" \
    || fail "402 GigaChat не посчитан с провайдером"
gigachat_api /gigachat/_config '{"failures":[{"status":422,"message":"Invalid params"}]}' >/dev/null
code=$(curl -s -o "$RESULT_DIR/422.json" -w '%{http_code}' -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
[ "$code" = 502 ] || fail "422 GigaChat без резерва: ожидался 502, получен $code: $(cat "$RESULT_DIR/422.json")"
gigachat_api /gigachat/_stats | json_check 'd["requests"] == 1' || fail "422 GigaChat повторялся: $(gigachat_api /gigachat/_stats)"
fakes_api /openrouter/_requests | json_check 'd == []' || fail "без резерва данные ушли в OpenRouter"
fakes_api /reserve/_requests | json_check 'd == []' || fail "без резерва данные ушли на адрес резерва"
gigachat_api /gigachat/_config '{}' >/dev/null
unset SMOKE_RESERVE_URL

step "обратно: FS_LLM_PROVIDER=openrouter, docker compose up -d --wait → чат через OpenRouter, ноль запросов к GigaChat"
export SMOKE_LLM_PROVIDER=openrouter
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 >/dev/null 2>&1
curl -fsS "$BASE/health" | json_check 'd["llmProvider"] == "openrouter" and d["llmConfigured"]' || fail "/health в режиме openrouter"
gigachat_api /gigachat/_config '{}' >/dev/null
fakes_api /openrouter/_failures '[]' >/dev/null
reply=$(curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$QUESTION" "$BASE/v1/chat")
echo "$reply" | json_check '"средний уровень топлива 250.0 л" in d.get("reply", "")' || fail "ответ через OpenRouter: $reply"
fakes_api /openrouter/_requests | json_check 'len(d) == 3' || fail "в режиме openrouter нет запросов к OpenRouter"
gigachat_api /gigachat/_stats | json_check 'd["requests"] == 0' || fail "в режиме openrouter были запросы к GigaChat"
if contains "$("${COMPOSE[@]}" exec -T llm_proxy sh -c 'env; cat /config/litellm.env /config/litellm.yaml')" 'test-gigachat-key'; then
    fail "в режиме openrouter ключ GigaChat виден контейнеру llm_proxy"
fi

step "повторный docker compose up -d --wait без правок не перезапускает прокси и шлюз"
started() { docker inspect -f '{{.State.StartedAt}}' "$("${COMPOSE[@]}" ps -q "$1")"; }
before="$(started llm_proxy) $(started gigachat_gateway)"
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 >/dev/null 2>&1
[ "$before" = "$(started llm_proxy) $(started gigachat_gateway)" ] || fail "прокси или шлюз перезапущены без изменений"

echo "✓ smoke: все проверки пройдены"
