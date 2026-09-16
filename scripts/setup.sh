#!/usr/bin/env bash
# Первичная настройка на Linux x86_64: JDK 17, Android SDK, эмулятор и виртуальное устройство.
# Повторный запуск безопасен — уже установленное пропускается.
#
#   scripts/setup.sh              — всё, включая эмулятор (~5 ГБ)
#   scripts/setup.sh --no-emulator — только то, что нужно для сборки (~1 ГБ)
#
# Куда ставится: $ANDROID_TOOLS_DIR (по умолчанию ~/Android): jdk-17/ и Sdk/.
# Уже установленные JDK 17 / Android SDK (Android Studio, $JAVA_HOME, $ANDROID_HOME) используются как есть.
set -euo pipefail
source "$(dirname "$0")/env.sh"

WITH_EMULATOR=1
[ "${1:-}" = "--no-emulator" ] && WITH_EMULATOR=0

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
PLATFORM="android-35"
SYSTEM_IMAGE="system-images;$PLATFORM;google_apis;x86_64"

for tool in curl unzip tar; do
    command -v "$tool" >/dev/null || { echo "Нужна утилита $tool: sudo apt install $tool" >&2; exit 1; }
done
mkdir -p "$ANDROID_TOOLS_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 1. JDK 17
java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]}'; }
if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME/bin/java")" = "17" ]; then
    echo "✓ JDK 17: $JAVA_HOME"
elif command -v java >/dev/null && [ "$(java_major java)" = "17" ]; then
    echo "✓ JDK 17 из PATH: $(command -v java)"
else
    echo "→ Скачиваю JDK 17 (Temurin) в $ANDROID_TOOLS_DIR/jdk-17"
    curl -fL --retry 3 --progress-bar -o "$TMP/jdk.tar.gz" "$JDK_URL"
    mkdir -p "$TMP/jdk" && tar xzf "$TMP/jdk.tar.gz" -C "$TMP/jdk"
    rm -rf "$ANDROID_TOOLS_DIR/jdk-17" && mv "$TMP"/jdk/jdk-17* "$ANDROID_TOOLS_DIR/jdk-17"
    export JAVA_HOME="$ANDROID_TOOLS_DIR/jdk-17" PATH="$ANDROID_TOOLS_DIR/jdk-17/bin:$PATH"
fi

# 2. Android command-line tools
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
    echo "✓ Android SDK: $ANDROID_HOME"
else
    echo "→ Скачиваю Android command-line tools в $ANDROID_HOME"
    curl -fL --retry 3 --progress-bar -o "$TMP/cmdline.zip" "$CMDLINE_TOOLS_URL"
    mkdir -p "$ANDROID_HOME/cmdline-tools" && unzip -q "$TMP/cmdline.zip" -d "$TMP/cmdline"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" && mv "$TMP/cmdline/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

# 3. Пакеты SDK
PACKAGES=("platform-tools" "platforms;$PLATFORM" "build-tools;35.0.0")
[ "$WITH_EMULATOR" = 1 ] && PACKAGES+=("emulator" "$SYSTEM_IMAGE")
echo "→ Устанавливаю пакеты SDK: ${PACKAGES[*]} (первый раз — несколько минут)"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${PACKAGES[@]}" | grep -vE '^\[=*\s*\]|^Loading|^$|SDK XML|released at different times' || true

# 4. local.properties для Gradle / Android Studio
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
echo "✓ local.properties → $ANDROID_HOME"

# 5. Виртуальное устройство
if [ "$WITH_EMULATOR" = 1 ]; then
    if "$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
        echo "✓ Эмулятор '$AVD_NAME' уже создан"
    else
        echo "→ Создаю эмулятор '$AVD_NAME' (Pixel 7, Android 15)"
        echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
            -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_7 >/dev/null
        CONFIG="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD_NAME.avd/config.ini"
        sed -i 's/^hw.ramSize *=.*/hw.ramSize = 4096M/; s/^hw.keyboard *=.*/hw.keyboard = yes/' "$CONFIG"
    fi
    if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
        echo "! Нет доступа к /dev/kvm. Выполните: sudo usermod -aG kvm \$USER и перезайдите в систему."
    fi
fi

echo
echo "Готово. Дальше: scripts/run.sh"
