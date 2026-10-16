#!/usr/bin/env bash
#
# APK хөрвүүлж, утсанд суулгана.
#
#   ./build-apk.sh              — зөвхөн build
#   ./build-apk.sh install      — build + USB-ээр холбогдсон утсанд суулгах
#   ./build-apk.sh test         — зөвхөн unit тест
#
# Эхний удаад Gradle болон хамаарлуудыг татна — 5-10 минут.

set -euo pipefail
cd "$(dirname "$0")"

line() { printf '─%.0s' {1..58}; echo; }

# ── Android SDK олох ────────────────────────────────────────────────
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in \
      "$HOME/Library/Android/sdk" \
      "$HOME/Android/Sdk" \
      "/usr/local/share/android-sdk"; do
    if [[ -d "$candidate" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  echo "✗ Android SDK олдсонгүй."
  echo "  Android Studio суулгасан бол ихэвчлэн:"
  echo "    ~/Library/Android/sdk   (macOS)"
  echo "  Гараар заах:  ANDROID_HOME=<зам> ./build-apk.sh"
  exit 1
fi

echo "sdk.dir=$ANDROID_HOME" > local.properties
export PATH="$ANDROID_HOME/platform-tools:$PATH"

line
echo "  ANDROID_HOME : $ANDROID_HOME"
echo "  Java         : $(java -version 2>&1 | head -1)"
line

case "${1:-build}" in
  test)
    ./gradlew :app:testDebugUnitTest
    echo
    echo "Тестийн тайлан:"
    echo "  app/build/reports/tests/testDebugUnitTest/index.html"
    ;;

  install)
    ./gradlew :app:assembleDebug
    APK="app/build/outputs/apk/debug/app-debug.apk"
    line
    if ! command -v adb >/dev/null; then
      echo "✗ adb олдсонгүй ($ANDROID_HOME/platform-tools)"
      echo "  APK: $APK"
      exit 1
    fi
    if [[ -z "$(adb devices | sed -n '2p')" ]]; then
      echo "✗ Утас холбогдоогүй байна."
      echo "  1. USB-ээр холбо"
      echo "  2. Тохиргоо → Developer options → USB debugging асаа"
      echo "  3. Утсан дээр гарах цонхыг зөвшөөр"
      echo
      echo "  APK бэлэн: $APK"
      exit 1
    fi
    echo "  Суулгаж байна…"
    adb install -r "$APK"
    adb shell monkey -p mn.gertu.zkagent -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    line
    echo "  ✓ Суулгалаа. Утсан дээр ZkAgent нээгдсэн байх ёстой."
    echo
    echo "  Лог харах:"
    echo "    adb logcat -s ZkAgent"
    ;;

  *)
    ./gradlew :app:assembleDebug
    line
    echo "  ✓ APK бэлэн:"
    echo "    app/build/outputs/apk/debug/app-debug.apk"
    echo
    echo "  Суулгах:  ./build-apk.sh install"
    echo "  эсвэл APK-г утас руугаа хуулаад нээнэ"
    ;;
esac
