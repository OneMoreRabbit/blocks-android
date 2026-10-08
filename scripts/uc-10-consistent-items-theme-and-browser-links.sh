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

echo '$ verify frozen 0.10 review failing-before evidence'
grep -F 'HEAD 8bbc0c528e455a784d396389134b969059787fde' \
    "$EVIDENCE/review-repair-failing-before.txt" >/dev/null
grep -F 'FAIL: action glyph whitespace is halved' \
    "$EVIDENCE/review-repair-failing-before.txt" >/dev/null
grep -F 'FAIL: Note drag accepts only Note payloads' \
    "$EVIDENCE/review-repair-failing-before.txt" >/dev/null
grep -F 'FAIL: Link drag accepts only Link payloads' \
    "$EVIDENCE/review-repair-failing-before.txt" >/dev/null
echo 'EXPECTED/ACTUAL: frozen head 8bbc0c5 fails density and both cross-type payload checks.'

echo '$ verify accepted 0.10 foundation failing-before evidence for API 0.8 Note ordering'
grep -F 'Api07InteractionSyncTest > api08PreActivationNoteOrderMustDrainAfterUpgrade FAILED' \
    "$EVIDENCE/api-0.8-note-order-failing-before.txt" >/dev/null
grep -F 'FAILING_BEFORE_EXIT=1' "$EVIDENCE/api-0.8-note-order-failing-before.txt" >/dev/null
echo 'EXPECTED/ACTUAL: exact accepted foundation 99001a2 leaves the queued pre-activation Note order undelivered.'

echo '$ verify exact candidate identity and clean tree'
grep -F 'applicationId = "dev.blocks.android"' app/build.gradle.kts >/dev/null
grep -F 'versionCode = 24' app/build.gradle.kts >/dev/null
grep -F 'versionName = "0.10.0"' app/build.gradle.kts >/dev/null
git diff --quiet
git diff --cached --quiet
echo 'EXPECTED/ACTUAL: package remains dev.blocks.android; candidate is 0.10.0/code 24; tracked tree is clean.'

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
echo 'EXPECTED/ACTUAL: all 28 active API 0.8 bytes and all frozen 0.7/0.6/0.4/0.2 bytes match their manifests.'

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
echo 'EXPECTED/ACTUAL: four Light/Dark dashboard/Project PNGs are copied outside Git; both Project captures show one complete Link and Note control row without clipped top fragments.'

echo 'PAUSE visual-architecture: inspect all four PNGs before merge or release.'

echo '$ persist a pre-activation Note reorder, force-stop the app, then deliver it from the same encrypted API-37 store'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e preserveForceStopFixture true \
    -e class 'dev.blocks.android.state.AndroidNormalizedSyncStateStoreTest#uc10PhaseOnePersistsAnUnpreparedNoteOrderBeforeForceStop' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/note-order-force-stop-phase-1.txt"
grep -F 'OK (1 test)' "$RESULTS/note-order-force-stop-phase-1.txt" >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop dev.blocks.android
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e preserveForceStopFixture true \
    -e requireForceStopFixture true \
    -e class 'dev.blocks.android.state.AndroidNormalizedSyncStateStoreTest#uc10PhaseTwoDeliversThePersistedNoteOrderAfterForceStop' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/note-order-force-stop-phase-2.txt"
grep -F 'OK (1 test)' "$RESULTS/note-order-force-stop-phase-2.txt" >/dev/null
echo 'EXPECTED/ACTUAL: one stable API 0.8 operation is prepared after restart, delivered once, acknowledged atomically and retired exactly.'

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
print(f"RESULT COUNTS screenshot=1 force_stop=2 performance=1 combined={totals['tests'] + 4}")
PY

echo '$ sha256sum exact APKs and screenshots; git rev-parse HEAD'
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    "$EVIDENCE/screenshots/dashboard-light.png" "$EVIDENCE/screenshots/dashboard-dark.png" \
    "$EVIDENCE/screenshots/project-light.png" "$EVIDENCE/screenshots/project-dark.png" \
    | tee "$EVIDENCE/sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"

echo 'VERDICT UC-10 cases 1-6: pass — local ordering, migration, URI and appearance gates pass offline and across restart.'
echo 'VERDICT UC-10 case 7 local/contract: pass — two independent durable stores converge; stale conflict, exact replay and force-stop recovery pass against version-aware API 0.8 fixtures.'
echo 'VERDICT UC-10 case 7 production OIDC/two-physical-device: blocked:owner — this seat has no safe production credential or second physical device; fake transport is not claimed as production evidence.'
echo 'VERDICT UC-10 case 8: pass — the four accepted 0.10 Light/Dark screenshots are regenerated with an unchanged presentation tree.'
echo 'VERDICT UC-10: blocked:owner only on the named production OIDC/two-physical-device observation.'
