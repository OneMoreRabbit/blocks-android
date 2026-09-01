#!/bin/sh
# Build, boot, install, launch, and verify Blocks on the seat's headless KVM emulator.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-$(find /home/dev/.local/share/jdks -mindepth 1 -maxdepth 1 -type d | sort | tail -1)}
HOST_LIBS=/home/dev/.local/share/android-emulator-libs/usr/lib/x86_64-linux-gnu
AVD_NAME=blocks_api_37
SERIAL=emulator-5554
EVIDENCE="$ROOT/build/emulator-smoke"

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
export LD_LIBRARY_PATH="$HOST_LIBS:$HOST_LIBS/pulseaudio${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

mkdir -p "$EVIDENCE"

if ! emulator -list-avds | grep -qx "$AVD_NAME"; then
    printf 'no\n' | avdmanager create avd \
        --name "$AVD_NAME" \
        --package 'system-images;android-37.0;google_apis;x86_64' \
        --device pixel_6 \
        --force
fi

if adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "$SERIAL is already running; stop it before running this isolated smoke test" >&2
    exit 2
fi

"$ROOT/gradlew" --no-daemon testDebugUnitTest assembleDebug

emulator -avd "$AVD_NAME" -port 5554 -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot-save >"$EVIDENCE/emulator.log" 2>&1 &
EMULATOR_PID=$!
cleanup() {
    adb -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMULATOR_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

attempt=0
while [ "$attempt" -lt 180 ]; do
    if [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
        break
    fi
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "emulator exited before boot; see $EVIDENCE/emulator.log" >&2
        exit 1
    fi
    attempt=$((attempt + 1))
    sleep 1
done

if [ "$attempt" -ge 180 ]; then
    echo "emulator did not boot within 180 seconds" >&2
    exit 1
fi

adb -s "$SERIAL" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$SERIAL" shell am start -W -n dev.blocks.android/.MainActivity | tee "$EVIDENCE/launch.txt"
adb -s "$SERIAL" shell dumpsys activity activities >"$EVIDENCE/activities.txt"
grep -q 'dev.blocks.android/.MainActivity' "$EVIDENCE/activities.txt"

{
    echo "serial=$SERIAL"
    echo "acceleration=$(emulator -accel-check 2>&1 | tr '\n' ' ')"
    echo "api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "abi=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "package=$(adb -s "$SERIAL" shell pm path dev.blocks.android | tr -d '\r')"
    echo "activity=dev.blocks.android/.MainActivity"
} | tee "$EVIDENCE/summary.txt"
