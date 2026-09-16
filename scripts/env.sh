# Окружение для сборки и эмулятора. Подключается из других скриптов: source scripts/env.sh
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-$HOME/Android/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

AVD_NAME="${AVD_NAME:-petrovich}"
APP_ID="ru.petrovich.telemetry"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

check_kvm() {
    if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
        echo "Нет доступа к /dev/kvm — эмулятор не запустится." >&2
        echo "Выполните: sudo usermod -aG kvm \$USER  и перезайдите в систему" >&2
        echo "(или на текущую сессию: sudo setfacl -m u:\$USER:rw /dev/kvm)" >&2
        exit 1
    fi
}

emulator_running() {
    adb devices 2>/dev/null | grep -q '^emulator-.*device$'
}

wait_for_boot() {
    echo "Жду загрузки Android…"
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
    echo "Эмулятор готов."
}
