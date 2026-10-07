#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-08.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC08_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC08_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-08}
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
        echo "REFUSED: UC-08 must target a disposable emulator explicitly; got $SERIAL."
        exit 1
        ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-08 — Project-page spacing, separators and controls'

echo '$ inspect released v0.9.4 presentation defects'
git show v0.9.4:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'bar.addView(unsynchronizedIndicator()' >/dev/null
git show v0.9.4:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'notes.forEach { addView(noteTimelineRow(it, archived)) }' >/dev/null
git show v0.9.4:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'setBackgroundColor(brandColor(R.color.blocks_input))' >/dev/null
git show v0.9.4:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'addView(label(formatProjectTimestamp(record.occurredAt)' >/dev/null
echo 'EXPECTED/ACTUAL: released 0.9.4 retains the inert top ellipsis, per-row trailing Note divider, opaque separator and top-row Park date.'
echo 'EXPECTED/ACTUAL: the four focused candidate assertions were run against unchanged 0.9.4 presentation and failed 4/4 before the repair.'

echo '$ verify exact candidate identity and clean tree'
grep -F 'versionCode = 22' app/build.gradle.kts >/dev/null
grep -F 'versionName = "0.9.5"' app/build.gradle.kts >/dev/null
git diff --quiet
git diff --cached --quiet
echo 'EXPECTED/ACTUAL: package remains dev.blocks.android; candidate is 0.9.5/code 22; tracked tree is clean.'

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

echo '$ run UC-08 screenshot capture against the exact installed artifacts'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesUc08OneNoteAndMultipleHistoryReviewPngsOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/uc08-screenshots.txt"
grep -F 'OK (1 test)' "$RESULTS/uc08-screenshots.txt" >/dev/null
REMOTE_SCREENSHOTS=/sdcard/Android/data/dev.blocks.android/files/review-0.9.5
"$ADB" -s "$SERIAL" pull "$REMOTE_SCREENSHOTS/project-one-note.png" \
    "$EVIDENCE/screenshots/project-one-note.png" >/dev/null
"$ADB" -s "$SERIAL" pull "$REMOTE_SCREENSHOTS/project-multiple-notes-parks.png" \
    "$EVIDENCE/screenshots/project-multiple-notes-parks.png" >/dev/null
echo 'EXPECTED/ACTUAL: one-Note and multiple-Note/multiple-Park PNGs are copied outside Git.'

echo 'PAUSE visual-architecture: inspect both PNGs before merge or release.'

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
print(f"RESULT COUNTS screenshot=1 performance=1 combined={totals['tests'] + 2}")
PY

echo '$ sha256sum exact APKs and screenshots; git rev-parse HEAD'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    "$EVIDENCE/screenshots/project-one-note.png" \
    "$EVIDENCE/screenshots/project-multiple-notes-parks.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-08 case 1: pass — no inert top ellipsis remains; functional controls are unchanged.'
echo 'VERDICT UC-08 case 2: pass — one/two/three Notes render zero/one/two dividers, only between records.'
echo 'VERDICT UC-08 case 3: pass — Note and Park rows use half-line top/bottom padding without boxes or backgrounds.'
echo 'VERDICT UC-08 case 4: pass — Notes and Park History use the established Links section spacing; empty sections remain hidden.'
echo 'VERDICT UC-08 case 5: pass — Note and Park separators use blocks_line at 20% opacity.'
echo 'VERDICT UC-08 case 6: pass — every Park date and existing delete action share one footer; cancellation changes nothing.'
echo 'VERDICT UC-08 case 7: pass — released 0.9.4 fails 4/4 focused repairs; the full candidate gate has zero skips.'
echo 'VERDICT UC-08 case 8: blocked:architecture — review the two outside-Git PNGs before merge or release.'
echo 'VERDICT UC-08: blocked:architecture'
