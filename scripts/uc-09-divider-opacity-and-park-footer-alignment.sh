#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-09.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC09_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC09_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-09}
ADB="$SDK/platform-tools/adb"

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL="$SERIAL"
mkdir -p "$EVIDENCE/screenshots"
cd "$ROOT"

case "$SERIAL" in
    emulator-*) ;;
    *)
        echo "REFUSED: UC-09 must target a disposable emulator explicitly; got $SERIAL."
        exit 1
        ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-09 — Divider opacity and Park footer alignment'

echo '$ verify released 0.9.5 focused failing-before evidence'
grep -F 'expected:<204> but was:<51>' "$EVIDENCE/released-0.9.5-focused-fail.txt" >/dev/null
grep -F 'Tests run: 2,  Failures: 2' "$EVIDENCE/released-0.9.5-focused-fail.txt" >/dev/null
echo 'EXPECTED/ACTUAL: released 0.9.5 fails both clarified assertions: divider alpha 51 rather than 204 and the Park date does not occupy/alignment-test the shared 48dp footer.'

echo '$ verify exact candidate identity and clean tree'
grep -F 'applicationId = "dev.blocks.android"' app/build.gradle.kts >/dev/null
grep -F 'versionCode = 23' app/build.gradle.kts >/dev/null
grep -F 'versionName = "0.9.6"' app/build.gradle.kts >/dev/null
git diff --quiet
git diff --cached --quiet
echo 'EXPECTED/ACTUAL: package remains dev.blocks.android; candidate is 0.9.6/code 23; tracked tree is clean.'

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; active API 0.7 and frozen manifests, JVM, lint, assemblies and every non-timing SQLite/Keystore test have zero failures and zero skips.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ adb -s <disposable-emulator> install -r exact app and test APKs'
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
echo 'EXPECTED/ACTUAL: app and instrumentation APKs come from the same exact clean build.'

echo '$ run the UC-09 screenshot capture against the exact installed artifacts'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesUc09MultipleParkFooterReviewPngOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/uc09-screenshot.txt"
grep -F 'OK (1 test)' "$RESULTS/uc09-screenshot.txt" >/dev/null
REMOTE_SCREENSHOTS=/sdcard/Android/data/dev.blocks.android/files/review-0.9.6
"$ADB" -s "$SERIAL" pull "$REMOTE_SCREENSHOTS/project-multiple-park-footers.png" \
    "$EVIDENCE/screenshots/project-multiple-park-footers.png" >/dev/null
echo 'EXPECTED/ACTUAL: the multiple-Park footer PNG is copied outside Git.'

echo 'PAUSE visual-architecture: inspect the PNG before merge or release.'

echo '$ stop the candidate, settle the disposable emulator and run the isolated local-action performance gate'
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
echo 'EXPECTED/ACTUAL: unchanged local-action performance gate passes using the exact built artifacts.'

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
if not totals["tests"]:
    raise SystemExit("the test result set is empty")
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit("test result gate is not zero-failure, zero-error and zero-skip")
print(f"RESULT COUNTS isolated-performance=1 combined={totals['tests'] + 1}")
PY

echo '$ sha256sum exact APKs and screenshot; git rev-parse HEAD'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    "$EVIDENCE/screenshots/project-multiple-park-footers.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-09 case 1: pass — every between-record divider is 1dp blocks_line at alpha 204; no final trailing divider exists.'
echo 'VERDICT UC-09 case 2: pass — every Park date and bin occupies one 48dp footer; rendered centres differ by at most one physical pixel.'
echo 'VERDICT UC-09 case 3: pass — the existing delete confirmation remains wired and cancellation creates no tombstone or queue change.'
echo 'VERDICT UC-09 case 4: pass — released 0.9.5 fails both focused assertions; the full candidate gate has zero skips.'
echo 'VERDICT UC-09 case 5: blocked:architecture — review the outside-Git PNG before merge or release.'
echo 'VERDICT UC-09: blocked:architecture'
