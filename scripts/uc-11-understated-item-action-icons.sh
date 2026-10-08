#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-11.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC11_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC11_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-11}
ADB="$SDK/platform-tools/adb"

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL="$SERIAL"
mkdir -p "$EVIDENCE/screenshots"
cd "$ROOT"

case "$SERIAL" in
    emulator-*) ;;
    *) echo "REFUSED: UC-11 must target a disposable emulator explicitly; got $SERIAL."; exit 1 ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-11 — Understated item action icons'

echo '$ verify released 0.10.0 failing-before icon geometry evidence'
grep -F 'uc11ItemActionsUseRestrainedGlyphsNeutralRestingTintAndDistinctTouchTargets' \
    "$EVIDENCE/released-0.10.0-icon-style-failing-before.txt" >/dev/null
grep -F 'expected:<52> but was:<94>' \
    "$EVIDENCE/released-0.10.0-icon-style-failing-before.txt" >/dev/null
grep -F 'FAILING_BEFORE_EXIT=1' \
    "$EVIDENCE/released-0.10.0-icon-style-failing-before.txt" >/dev/null
echo 'EXPECTED/ACTUAL: released 0.10.0 renders a 36dp glyph where UC-11 requires 20dp, while its 48dp target remains distinct.'

echo '$ verify exact candidate identity and clean tree'
grep -F 'applicationId = "dev.blocks.android"' app/build.gradle.kts >/dev/null
grep -F 'versionCode = 25' app/build.gradle.kts >/dev/null
grep -F 'versionName = "0.10.1"' app/build.gradle.kts >/dev/null
git diff --quiet
git diff --cached --quiet
echo 'EXPECTED/ACTUAL: package remains dev.blocks.android; candidate is 0.10.1/code 25; tracked tree is clean.'

echo '$ verify exact active API 0.8 and frozen compatibility manifests'
for contract in \
    contracts/blocks-service-api \
    contracts/blocks-service-api-legacy-v0_7 \
    contracts/blocks-service-api-legacy-v0_6 \
    contracts/blocks-service-api-legacy-v0_4 \
    contracts/blocks-service-api-legacy-v0_2
do
    (cd "$contract" && sha256sum --check SHA256SUMS)
done
echo 'EXPECTED/ACTUAL: all active API 0.8 and frozen 0.7/0.6/0.4/0.2 contract bytes match their manifests.'

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; JVM, lint, assemblies and every non-timing API-37 SQLite/Keystore test have zero failures, errors and skips.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ run the two populated Project screenshot captures against the exact candidate'
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesUc11PopulatedProjectLightAndDarkReviewPngsOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/uc11-screenshots.txt"
grep -F 'OK (1 test)' "$RESULTS/uc11-screenshots.txt" >/dev/null
REMOTE=/sdcard/Android/data/dev.blocks.android/files/review-0.10.1
for name in project-light project-dark; do
    "$ADB" -s "$SERIAL" pull "$REMOTE/$name.png" "$EVIDENCE/screenshots/$name.png" >/dev/null
done
echo 'EXPECTED/ACTUAL: populated Light and Dark Project PNGs show complete Link and Note action rows at rest.'

echo 'PAUSE visual-owner: architecture and owner inspect both PNGs before merge or release.'

echo '$ force-stop/restart offline and run the isolated established local-action performance gate'
"$ADB" -s "$SERIAL" shell am force-stop dev.blocks.android
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable
sleep 60
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.state.LocalActionPerformanceTest#realButtonsRenderSeededCreateAndParkResultsWithinBudget' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/performance-instrumentation.txt"
grep -F 'OK (1 test)' "$RESULTS/performance-instrumentation.txt" >/dev/null
echo 'EXPECTED/ACTUAL: force-stop/offline restart and isolated performance gate pass.'

python3 - "$ROOT" "$RESULTS" <<'PY'
from pathlib import Path
from xml.etree import ElementTree
import sys

root = Path(sys.argv[1]) / "app" / "build"
results = Path(sys.argv[2])
files = list((root / "test-results" / "testDebugUnitTest").glob("TEST-*.xml"))
files += list((results / "non-performance").glob("TEST-*.xml"))
if not files:
    raise SystemExit("no JVM or instrumentation XML results found")
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for path in files:
    suite = ElementTree.parse(path).getroot()
    for name in totals:
        totals[name] += int(suite.attrib.get(name, 0))
print("RESULT COUNTS " + " ".join(f"{name}={value}" for name, value in totals.items()))
if not totals["tests"] or totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit("test result gate is not zero-failure, zero-error and zero-skip")
print(f"RESULT COUNTS screenshots=1 performance=1 combined={totals['tests'] + 2}")
PY

echo '$ sha256sum exact APKs and screenshots; git rev-parse HEAD'
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    "$EVIDENCE/screenshots/project-light.png" "$EVIDENCE/screenshots/project-dark.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-11 cases 1-4: pass — exact action order, restrained glyph geometry, theme tint states, interaction writes and accessibility targets pass.'
echo 'VERDICT UC-11 case 5: blocked:owner — populated Light/Dark screenshots are ready for visual approval.'
echo 'VERDICT UC-11: blocked:owner only on the named visual pause.'
