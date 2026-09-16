# Общее окружение для скриптов. Подключается так: source scripts/env.sh
#
# Где искать инструменты (первое найденное):
#   JDK 17:      $JAVA_HOME → ~/Android/jdk-17 → JBR из Android Studio → java из PATH
#   Android SDK: $ANDROID_HOME → $ANDROID_SDK_ROOT → ~/Android/Sdk

ANDROID_TOOLS_DIR="${ANDROID_TOOLS_DIR:-$HOME/Android}"

if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$ANDROID_TOOLS_DIR/jdk-17" /opt/android-studio/jbr "$HOME/android-studio/jbr" /snap/android-studio/current/jbr; do
        if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
    done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$ANDROID_TOOLS_DIR/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

AVD_NAME="${AVD_NAME:-petrovich}"
APP_ID="ru.petrovich.telemetry"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

check_kvm() {
    if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
        echo "Нет доступа к /dev/kvm — эмулятор не запустится." >&2
        echo "Выполните: sudo usermod -aG kvm \$USER  и перезайдите в систему" >&2
        echo "(или только на текущую сессию: sudo setfacl -m u:\$USER:rw /dev/kvm)" >&2
        exit 1
    fi
}

check_avd() {
    if ! emulator -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
        echo "Эмулятор '$AVD_NAME' не найден. Сначала выполните: scripts/setup.sh" >&2
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
