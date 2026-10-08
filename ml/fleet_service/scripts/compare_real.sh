#!/usr/bin/env bash
# Сверка Python-агрегации и правил стенда с Kotlin (TripTablesMapper, BaselineAnomalyDetector) на сохранённых
# реальных ответах GetTripTables — аналог REAL_TRIP_TABLES в приложении. Каталог: по одному ответу на машину
# в файле *.json (сутки данных).
#   ml/fleet_service/scripts/compare_real.sh /path/to/dir
# 1) Kotlin-тест TripTablesMapperTest.realResponses строит VehicleTelemetry и аномалии и пишет эталоны
#    в REAL_TRIP_TABLES_OUT;
# 2) tests/test_golden.py и tests/test_rules_golden.py строят то же на Python и сравнивают.
set -euo pipefail

DIR="$(cd "${1:?укажите каталог с ответами GetTripTables}" && pwd)"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="${REAL_TRIP_TABLES_OUT:-$ROOT/app/build/real-trip-tables}"
export REAL_TRIP_TABLES="$DIR" REAL_TRIP_TABLES_OUT="$OUT"

rm -rf "$OUT"
echo "▶ Kotlin: эталоны → $OUT"
(cd "$ROOT" && source scripts/env.sh >/dev/null && \
    ./gradlew testDebugUnitTest --tests "ru.petrovich.telemetry.TripTablesMapperTest.realResponses" --rerun -q)
# --rerun: переменные окружения не входят в ключ кэша gradle — без него тест мог бы не запуститься повторно.

echo "▶ Python: сверка агрегации и правил"
cd "$ROOT/ml/fleet_service"
.venv/bin/pytest -q tests/test_golden.py tests/test_rules_golden.py -k "real"
