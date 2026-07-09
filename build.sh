#!/usr/bin/env bash
# Build TinyGrok APK and copy it to the project root.
#
# Usage:
#   ./build.sh              # release APK → ./tiny-ggrok-<version>-universal.apk
#   ./build.sh --debug      # debug APK (no ABI splits path if missing → assembleDebug)
#   ./build.sh --emulate    # debug build, install & launch on emulator
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="$(grep -E 'versionName\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION="${VERSION:-0.0.0}"
APP_ID="com.tinyggrok.app"
AVD_NAME="${AVD_NAME:-Pixel_4_API_34}"

BUILD_TYPE="release"
DO_EMULATE=0

for arg in "$@"; do
  case "$arg" in
    --debug)   BUILD_TYPE="debug" ;;
    --emulate) BUILD_TYPE="debug"; DO_EMULATE=1 ;;
    -h|--help)
      sed -n '2,10p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--debug|--emulate]" >&2
      exit 1
      ;;
  esac
done

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  echo "Note: ./gradlew missing; using system gradle. Prefer: gradle wrapper"
  GRADLE=(gradle)
else
  echo "Error: no ./gradlew or gradle found." >&2
  exit 1
fi

if [[ "$BUILD_TYPE" == "release" ]]; then
  TASK=":app:assembleRelease"
  APK_DIR="app/build/outputs/apk/release"
  APK_GLOB="app-*-release.apk"
  PREFERRED="app-universal-release.apk"
else
  TASK=":app:assembleDebug"
  APK_DIR="app/build/outputs/apk/debug"
  APK_GLOB="app-*-debug.apk"
  PREFERRED="app-universal-debug.apk"
fi

echo "==> Building ($BUILD_TYPE)…"
"${GRADLE[@]}" "$TASK"

# Prefer the universal APK when ABI splits are enabled; otherwise take the first match.
SRC=""
if [[ -f "$APK_DIR/$PREFERRED" ]]; then
  SRC="$APK_DIR/$PREFERRED"
else
  # Fallback: plain app-release.apk / app-debug.apk (no splits)
  PLAIN="app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"
  if [[ -f "$PLAIN" ]]; then
    SRC="$PLAIN"
  else
    SRC="$(find "$APK_DIR" -maxdepth 1 -name "$APK_GLOB" 2>/dev/null | sort | head -1)"
    if [[ -z "$SRC" || ! -f "$SRC" ]]; then
      echo "Error: no APK found under $APK_DIR" >&2
      exit 1
    fi
  fi
fi

BASENAME="$(basename "$SRC")"
# root name: tiny-ggrok-0.0.1-universal-release.apk (or whatever the built file is)
OUT_NAME="tiny-ggrok-${VERSION}-${BASENAME#app-}"
DEST="$ROOT/$OUT_NAME"

cp -f "$SRC" "$DEST"
echo "==> Copied:"
echo "    $SRC"
echo "    → $DEST"
ls -lh "$DEST"

if [[ "$DO_EMULATE" -eq 1 ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "Error: adb not found (install Android platform-tools)." >&2
    exit 1
  fi

  if ! adb devices | grep -qE 'device$'; then
    if command -v emulator >/dev/null 2>&1; then
      echo "==> Starting emulator ($AVD_NAME)…"
      emulator -avd "$AVD_NAME" -netdelay none -netspeed full >/dev/null 2>&1 &
      echo "    waiting for device…"
      adb wait-for-device
      # boot completed
      while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
        sleep 2
      done
    else
      echo "Error: no device/emulator and 'emulator' not on PATH." >&2
      exit 1
    fi
  fi

  echo "==> Installing $DEST…"
  adb install -r "$DEST"
  echo "==> Launching $APP_ID…"
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "Done."
fi
