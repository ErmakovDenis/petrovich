#!/usr/bin/env bash
# Запуск эмулятора Android.
#   scripts/emulator.sh              — с окном (программная отрисовка, стабильно)
#   scripts/emulator.sh --gpu host   — с окном, через видеокарту (быстрее; на Wayland может ронять GNOME Shell)
#   scripts/emulator.sh --headless   — без окна
#   scripts/emulator.sh --wipe       — сбросить данные эмулятора
set -euo pipefail
source "$(dirname "$0")/env.sh"

GPU="swiftshader_indirect"
EXTRA=()
while [ $# -gt 0 ]; do
    case "$1" in
        --gpu) GPU="$2"; shift ;;
        --headless) EXTRA+=(-no-window) ;;
        --wipe) EXTRA+=(-wipe-data) ;;
        *) echo "Неизвестный параметр: $1" >&2; exit 2 ;;
    esac
    shift
done

check_kvm
if emulator_running; then
    echo "Эмулятор уже запущен."
    exit 0
fi

LOG="$HOME/.android/emulator-$AVD_NAME.log"
echo "Запускаю эмулятор '$AVD_NAME' (gpu=$GPU), лог: $LOG"
nohup emulator -avd "$AVD_NAME" -gpu "$GPU" -no-boot-anim -no-snapshot-save "${EXTRA[@]}" > "$LOG" 2>&1 &
wait_for_boot
