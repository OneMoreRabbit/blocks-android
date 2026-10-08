#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-10.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/Android/Sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC10_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC10_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-10}
ADB="$SDK/platform-tools/adb"

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL="$SERIAL"
mkdir -p "$EVIDENCE/screenshots"
cd "$ROOT"

case "$SERIAL" in
    emulator-*) ;;
    *) echo "REFUSED: UC-10 must target a disposable emulator explicitly; got $SERIAL."; exit 1 ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-10 — Consistent item controls, appearance and browser links'

echo '$ verify released 0.9.6 failing-before evidence'
grep -F 'ProjectResourceUriTest > matchesTheSharedVersionedAcceptanceCorpusIncludingOrdinaryOneNoteUris FAILED' \
    "$EVIDENCE/released-0.9.6-uri-v0.4-fail.txt" >/dev/null
echo 'EXPECTED/ACTUAL: released 0.9.6 refuses the owner brace-bearing HTTPS value under corpus v0.4.'

echo '$ verify exact candidate identity and clean tree'
grep -F 'applicationId = "dev.blocks.android"' app/build.gradle.kts >/dev/null
grep -F 'versionCode = 24' app/build.gradle.kts >/dev/null
grep -F 'versionName = "0.10.0"' app/build.gradle.kts >/dev/null
git diff --quiet
git diff --cached --quiet
echo 'EXPECTED/ACTUAL: package remains dev.blocks.android; candidate is 0.10.0/code 24; tracked tree is clean.'

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; contract, JVM, lint, assemblies and every non-timing API-37 SQLite/Keystore test have zero failures and zero skips.'
echo 'ACTUAL: the API-37 gate includes a real normalized 0.9.6 SQLite/Keystore fixture upgraded in place to the Note-order schema, then reopened and compared row-for-row.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ run the four UC-10 screenshot captures against the exact candidate'
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesUc10LightAndDarkDashboardAndProjectReviewPngsOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/uc10-screenshots.txt"
grep -F 'OK (1 test)' "$RESULTS/uc10-screenshots.txt" >/dev/null
REMOTE=/sdcard/Android/data/dev.blocks.android/files/review-0.10.0
for name in dashboard-light dashboard-dark project-light project-dark; do
    "$ADB" -s "$SERIAL" pull "$REMOTE/$name.png" "$EVIDENCE/screenshots/$name.png" >/dev/null
done
echo 'EXPECTED/ACTUAL: four Light/Dark dashboard/Project PNGs are copied outside Git.'

echo 'PAUSE visual-architecture: inspect all four PNGs before merge or release.'

echo '$ force-stop/restart offline and run the isolated local-action performance gate'
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
print(f"RESULT COUNTS screenshot=1 performance=1 combined={totals['tests'] + 2}")
PY

echo '$ sha256sum exact APKs and screenshots; git rev-parse HEAD'
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    "$EVIDENCE/screenshots/dashboard-light.png" "$EVIDENCE/screenshots/dashboard-dark.png" \
    "$EVIDENCE/screenshots/project-light.png" "$EVIDENCE/screenshots/project-dark.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-10 cases 1-6: pass — local ordering, migration, URI and appearance gates pass offline and across restart.'
echo 'VERDICT UC-10 case 7: deferred — Service API 0.8 is proposed but not active.'
echo 'VERDICT UC-10 case 8: blocked:architecture — inspect the four outside-Git PNGs.'
echo 'VERDICT UC-10: blocked:architecture'
