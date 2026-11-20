#!/usr/bin/env bash
# Все проверки планов в ml/docs: gradle, pytest всех сервисов, правила алертов (promtool), smoke.sh. Логи — в
# $FLEET_CHECK_LOGS (по умолчанию временный каталог), итог — таблица Markdown в stdout. Код выхода 1, если хоть одна
# проверка упала.
#
#   .claude/skills/fleet-check/check.sh            — всё
#   .claude/skills/fleet-check/check.sh --no-smoke — без docker compose (быстро); promtool остаётся: это один контейнер
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
LOGS="${FLEET_CHECK_LOGS:-$(mktemp -d -t fleet-check-XXXX)}"
mkdir -p "$LOGS"
SMOKE=1
[ "${1:-}" = "--no-smoke" ] && SMOKE=0

ROWS=()
FAILED=0
run() { # имя, лог, команда…
    local name="$1" log="$LOGS/$2"; shift 2
    local start=$SECONDS
    if ( "$@" ) >"$log" 2>&1; then
        ROWS+=("| $name | ✅ | $((SECONDS - start)) с | $(grep -v '^[[:space:]]*$' "$log" | tail -n 1 | cut -c1-80) | \`$log\` |")
    else
        FAILED=1
        ROWS+=("| $name | ❌ | $((SECONDS - start)) с | $(grep -m1 -E 'FAILED|FAIL|Error|error:' "$log" | cut -c1-80) | \`$log\` |")
    fi
}
skip() { ROWS+=("| $1 | ⏭ | — | $2 | — |"); }

gradle_tests() { source scripts/env.sh && ./gradlew testDebugUnitTest --console=plain; }
gradle_build() { source scripts/env.sh && ./gradlew assembleDebug --console=plain; }
pytest_in() { "ml/$1/.venv/bin/pytest" -q "ml/$1" -p no:cacheprovider --rootdir "ml/$1"; }
promtool_rules() {
    # Образ — тот же, что у prometheus в compose: версия не расходится при обновлении.
    local image
    image="$(grep -m1 -oE 'prom/prometheus:[^[:space:]"]+' ml/docker-compose.yml)" || { echo "error: нет образа prometheus в compose"; return 1; }
    local promtool=(docker run --rm --entrypoint promtool -v "$ROOT/ml/monitoring:/m:ro" -w /m "$image")
    "${promtool[@]}" check rules alerts.yml && "${promtool[@]}" test rules alerts_test.yml
}

if [ -f local.properties ]; then
    run "gradle testDebugUnitTest" gradle-test.log gradle_tests
    run "gradle assembleDebug" gradle-build.log gradle_build
else
    FAILED=1; skip "gradle" "нет local.properties — выполните scripts/setup.sh --no-emulator"
fi

for svc in predictive_antifraud fleet_service telegram_bot; do
    if [ ! -d "ml/$svc" ]; then
        skip "pytest $svc" "сервиса ещё нет"
    elif [ ! -x "ml/$svc/.venv/bin/pytest" ]; then
        FAILED=1; skip "pytest $svc" "нет venv: cd ml/$svc && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'"
    else
        run "pytest $svc" "pytest-$svc.log" pytest_in "$svc"
    fi
done

if [ ! -f ml/monitoring/alerts.yml ]; then
    skip "promtool" "алертов ещё нет"
elif ! docker info >/dev/null 2>&1; then
    FAILED=1; skip "promtool" "нет доступа к docker"
else
    run "promtool (алерты)" promtool.log promtool_rules
fi

if [ "$SMOKE" = 0 ]; then
    skip "smoke.sh" "пропущен (--no-smoke)"
elif [ -x ml/fleet_service/scripts/smoke.sh ]; then
    run "smoke.sh (docker compose)" smoke.log ml/fleet_service/scripts/smoke.sh
else
    skip "smoke.sh" "сценария ещё нет"
fi

echo "| Проверка | Итог | Время | Последняя строка / ошибка | Лог |"
echo "|---|---|---|---|---|"
printf '%s\n' "${ROWS[@]}"
echo
echo "Ветка: $(git branch --show-current), коммит: $(git rev-parse --short HEAD), изменённых файлов: $(git status --porcelain | wc -l)"
exit $FAILED
