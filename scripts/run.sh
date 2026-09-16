#!/usr/bin/env bash
# Собрать debug-APK, запустить эмулятор (если не запущен), установить и открыть приложение.
# Параметры передаются в emulator.sh, например: scripts/run.sh --gpu host
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_DIR"

./gradlew assembleDebug
emulator_running || "$(dirname "$0")/emulator.sh" "$@"
wait_for_boot

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell cmd alarm set-timezone Asia/Yekaterinburg >/dev/null 2>&1 || true
adb shell am start -n "$APP_ID/.MainActivity"
echo "Приложение запущено. Логи: adb logcat --pid=\$(adb shell pidof $APP_ID)"
