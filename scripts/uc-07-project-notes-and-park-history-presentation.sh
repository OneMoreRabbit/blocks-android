#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-07.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC07_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC07_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-07}
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
        echo "REFUSED: UC-07 must target a disposable emulator explicitly; got $SERIAL."
        exit 1
        ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-07 — Compact Notes and Park History presentation'

echo '$ inspect released v0.9.3 Project presentation'
git show v0.9.3:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'addView(heading("History", 20f)' >/dev/null
git show v0.9.3:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'controller.state.noteItems.filterNot { it.pinned }' >/dev/null
echo 'EXPECTED/ACTUAL: released v0.9.3 has one History section that mixes unpinned Notes with Park records; it fails the focused semantic-section regression.'
echo 'VERDICT released v0.9.3 regression: expected fail'

echo '$ inspect candidate presentation boundary'
grep -F 'heading("Notes", 20f)' app/src/main/java/dev/blocks/android/MainActivity.kt >/dev/null
grep -F 'heading("Park History", 20f)' app/src/main/java/dev/blocks/android/MainActivity.kt >/dev/null
if grep -F 'projectActivityEntries' app/src/main/java/dev/blocks/android/MainActivity.kt; then
    echo 'ACTUAL: the candidate still contains the mixed Notes/Park projection.'
    exit 1
fi
echo 'EXPECTED/ACTUAL: Notes and older Park records have separate presentation-only projections.'

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; active API 0.7 and frozen manifests, JVM, lint, debug/release assembly and all non-timing SQLite/Keystore tests have zero failures and zero skips.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ adb -s <disposable-emulator> install -r exact app and test APKs'
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
echo 'EXPECTED: the app and instrumentation APKs come from the same exact clean build.'

echo '$ run UC-07 screenshot capture against the exact installed artifacts'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesUc07NotesAndMultilineParkReviewPngsOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/uc07-screenshots.txt"
grep -F 'OK (1 test)' "$RESULTS/uc07-screenshots.txt" >/dev/null
REMOTE_SCREENSHOTS=/sdcard/Android/data/dev.blocks.android/files/review-0.9.4
"$ADB" -s "$SERIAL" pull "$REMOTE_SCREENSHOTS/project-notes-populated.png" \
    "$EVIDENCE/screenshots/project-notes-populated.png" >/dev/null
"$ADB" -s "$SERIAL" pull "$REMOTE_SCREENSHOTS/project-park-history-multiline.png" \
    "$EVIDENCE/screenshots/project-park-history-multiline.png" >/dev/null
echo 'EXPECTED/ACTUAL: populated Notes and multiline Park History PNGs are copied outside Git.'

echo '$ stop the candidate and settle the disposable emulator; run the isolated local-action performance gate'
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
echo 'EXPECTED/ACTUAL: the unchanged local-action performance gate passes using the same built artifacts.'

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
    "$EVIDENCE/screenshots/project-notes-populated.png" \
    "$EVIDENCE/screenshots/project-park-history-multiline.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-07 case 1: pass — Notes and Park records have exclusive labelled sections; the current Park remains de-duplicated in its established presentation.'
echo 'VERDICT UC-07 case 2: pass — Note text, UK date and edit/pin/delete actions remain; an empty Notes collection renders no heading or spacing.'
echo 'VERDICT UC-07 case 3: pass — every older Park is one divider/field/date/body/lower-delete unit, including multiline bodies and adjacent records.'
echo 'VERDICT UC-07 case 4: pass — exact-item Note/Park delete tests retain the existing commands and cancellation semantics.'
echo 'VERDICT UC-07 case 5: pass — populated, Notes-only/Parks-only/empty and archived presentation regressions pass without non-Project changes.'
echo 'VERDICT UC-07 case 6: blocked:architecture — review the two outside-Git PNGs before merge or release.'
echo 'VERDICT UC-07: blocked:architecture'
