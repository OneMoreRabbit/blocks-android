#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-02.
# Run from the blocks-android repository with one clean API-36+ test device attached.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/Android/Sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
EVIDENCE=${UC02_EVIDENCE_DIR:-/home/dev/work/Blocks-ui-review/0.9.0}

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$EVIDENCE"
cd "$ROOT"

echo 'CASE UC-02 — Ordered Project work and reversible Park removal'
echo '$ adb devices'
adb devices
echo 'EXPECTED: exactly one API-36-or-newer device is listed as device.'

echo '$ ./gradlew --no-daemon testDebugUnitTest --tests dev.blocks.android.sync.ProjectInteractionOrderingTest --tests dev.blocks.android.project.ProjectResourceUriTest'
./gradlew --no-daemon testDebugUnitTest \
    --tests dev.blocks.android.sync.ProjectInteractionOrderingTest \
    --tests dev.blocks.android.project.ProjectResourceUriTest
echo 'EXPECTED: BUILD SUCCESSFUL; OneNote corpus, local ordering, rollback, restart, tenant isolation and Park tombstone tests all pass.'

ANDROID_CASES='dev.blocks.android.ProjectInteractionPresentationTest#oneNoteLaunchLinkReorderAndConfirmedParkDeleteRemainLocalAcrossRestart,dev.blocks.android.state.AndroidNormalizedSyncStateStoreTest#realDatabaseMigratesSchemaTenThenSeedsInteractionAggregatesOnActivation,dev.blocks.android.state.AndroidNormalizedSyncStateStoreTest#realDatabasePersistsInteractionStateAndRollsBackTheWholeMutationOnFailure'
echo "$ ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$ANDROID_CASES"
./gradlew --no-daemon connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$ANDROID_CASES"
echo 'EXPECTED: BUILD SUCCESSFUL; zero skipped tests; real SQLite/Keystore and presentation journeys pass.'

echo '$ ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon connectedDebugAndroidTest \
    '-Pandroid.testInstrumentationRunnerArguments.class=dev.blocks.android.state.LocalActionPerformanceTest'
echo 'EXPECTED: BUILD SUCCESSFUL; zero skips; all six 500-Project button-to-visible p95 measurements remain below 100 ms.'

echo '$ ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest'
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
echo 'EXPECTED: BUILD SUCCESSFUL and both debug APKs exist.'

echo '$ adb install -r app/build/outputs/apk/debug/app-debug.apk'
adb install -r app/build/outputs/apk/debug/app-debug.apk
echo 'EXPECTED: Success.'
echo '$ adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
echo 'EXPECTED: Success.'
echo "$ adb shell am instrument -w -e class dev.blocks.android.ProjectInteractionPresentationTest#writesPopulatedEmptyAndLongHistoryReviewPngsOutsideTheAppRepository dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w \
    -e class 'dev.blocks.android.ProjectInteractionPresentationTest#writesPopulatedEmptyAndLongHistoryReviewPngsOutsideTheAppRepository' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner
echo 'EXPECTED: OK (1 test); the app remains installed so its external review files can be retrieved.'

echo "$ adb pull /sdcard/Android/data/dev.blocks.android/files/review-0.9.0/. $EVIDENCE/"
adb pull /sdcard/Android/data/dev.blocks.android/files/review-0.9.0/. "$EVIDENCE/"
echo 'EXPECTED: project-populated.png, project-empty.png and project-long-history.png are copied outside git.'

echo 'PAUSE: physical OneNote launch'
echo 'On a supported physical device with OneNote installed: add each accepted ordinary onenote: fixture from project-resource-uri-validation-v0_3.json, including a non-HTTP payload; verify no launch occurs on save/render, then tap its title and verify Android generic ACTION_VIEW opens the copied destination in OneNote.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r one_note_verdict
else
    one_note_verdict='BLOCKED — physical supported device and installed OneNote required'
fi
echo "VERDICT physical OneNote: $one_note_verdict"

echo 'PAUSE: visual review'
echo "Review $EVIDENCE/project-populated.png, project-empty.png and project-long-history.png for compact Link controls, banner/title density, History separators and 48dp targets."
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r visual_verdict
else
    visual_verdict='BLOCKED — owner visual review required'
fi
echo "VERDICT visual review: $visual_verdict"

echo 'VERDICT far-end convergence: blocked:blocks.component.blocks-service'
echo 'EXPECTED: API 0.6 remains byte-compatible; proposed 0.7 operations stay durable and pending until a separately accepted Service contract exists.'
