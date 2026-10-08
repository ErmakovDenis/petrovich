#!/usr/bin/env bash
# Сквозной сценарий стенда без реальных ключей: docker compose (fleet_service + predictive_antifraud + подставные
# OpenRouter и AutoGRAPH; predictive_antifraud настоящий, без моделей) → /health → машины → телеметрия → вопрос
# с вызовом tool → проверка машины → вопросы об аномалиях → проверка с сохранением → лента → решение → вопросы
# ассистенту о ленте и решении → перенос решений с устройства → перезапуск стенда (хранилище на месте) → проверка
# при остановленной аналитике.
# Запуск из любого каталога: ml/fleet_service/scripts/smoke.sh
# Порты на хосте: SMOKE_FLEET_PORT (18080), SMOKE_PREDICTIVE_PORT (18001). KEEP=1 — не останавливать стенд.
set -euo pipefail

ML="$(cd "$(dirname "$0")/../.." && pwd)"
export FLEET_SERVICE_PORT="${SMOKE_FLEET_PORT:-18080}"
export PREDICTIVE_PORT="${SMOKE_PREDICTIVE_PORT:-18001}"
COMPOSE=(docker compose -p fleet-smoke -f "$ML/docker-compose.yml" -f "$ML/fleet_service/scripts/smoke.compose.yml")
BASE="http://127.0.0.1:$FLEET_SERVICE_PORT"
AUTH=(-H "Authorization: Bearer valid-token" -H "X-Schema-Id: schema-1")

cleanup() {
    local code=$?
    if [ "$code" != 0 ]; then
        echo "--- логи стенда ---"
        "${COMPOSE[@]}" logs --no-color --tail 80 || true
    fi
    [ "${KEEP:-0}" = 1 ] || "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    exit "$code"
}
trap cleanup EXIT

step() { echo "▶ $*"; }
fail() { echo "✗ $*" >&2; exit 1; }
# Проверка JSON без jq: python3 из системы, выражение над переменной d.
json_check() { python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if ($1) else 1)"; }

step "сборка и запуск compose"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 180

step "GET /health fleet_service"
curl -fsS "$BASE/health" | json_check 'd["status"] == "ok" and d["llmConfigured"] is True' \
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

step "перезапуск fleet_service → хранилище и решение на месте"
"${COMPOSE[@]}" restart fleet_service >/dev/null 2>&1
for _ in $(seq 1 60); do curl -fsS "$BASE/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "${AUTH[@]}" "$BASE/v1/anomalies?$STORE_PERIOD&status=false_alarm" | json_check \
    'd["total"] == 1 and d["items"][0]["resolvedBy"] == "Петров" and d["lastScanAt"]' \
    || fail "хранилище после перезапуска"

step "predictive_antifraud остановлен → проверка по правилам работает, аналитика unavailable"
"${COMPOSE[@]}" stop predictive_antifraud >/dev/null 2>&1
curl -fsS -H 'Content-Type: application/json' "${AUTH[@]}" -d "$CHECK" "$BASE/v1/anomalies/check" | json_check \
    'd["rulesAnomalies"] == 6 and d["analytics"]["antifraud"]["status"] == "unavailable"' \
    || fail "проверка без аналитики"

logs=$("${COMPOSE[@]}" logs --no-color fleet_service)
step "вызовы tools видны в логе стенда"
grep -q 'модель вызывает tool get_vehicle_summary' <<<"$logs" || fail "в логе нет вызова get_vehicle_summary"
grep -q 'модель вызывает tool check_vehicle' <<<"$logs" || fail "в логе нет вызова check_vehicle"
grep -q 'модель вызывает tool list_anomalies' <<<"$logs" || fail "в логе нет вызова list_anomalies"
grep -q 'модель вызывает tool get_anomaly' <<<"$logs" || fail "в логе нет вызова get_anomaly"

step "в логах стенда нет токена и ключа"
if grep -qE 'valid-token|other-token|test-key' <<<"$logs"; then
    fail "токен или ключ попал в лог fleet_service"
fi

echo "✓ smoke: все проверки пройдены"
