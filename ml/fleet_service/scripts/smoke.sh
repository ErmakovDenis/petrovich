#!/usr/bin/env bash
# Сквозной сценарий стенда без реальных ключей: docker compose (fleet_service + predictive_antifraud + подставные
# OpenRouter и AutoGRAPH) → /health → машины → телеметрия → вопрос в /v1/chat с вызовом tool.
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

logs=$("${COMPOSE[@]}" logs --no-color fleet_service)
step "вызов tool виден в логе стенда"
grep -q 'модель вызывает tool get_vehicle_summary' <<<"$logs" || fail "в логе нет вызова get_vehicle_summary"

step "в логах стенда нет токена и ключа"
if grep -qE 'valid-token|other-token|test-key' <<<"$logs"; then
    fail "токен или ключ попал в лог fleet_service"
fi

echo "✓ smoke: все проверки пройдены"
