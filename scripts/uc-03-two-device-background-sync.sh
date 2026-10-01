#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-03.
# Production API 0.7 is accepted; physical two-device pauses remain explicit release evidence.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
EVIDENCE=${UC03_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-03}

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$EVIDENCE"
cd "$ROOT"

echo 'CASE UC-03 — Two-device background synchronization'
echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest
echo 'EXPECTED: BUILD SUCCESSFUL; zero skipped tests; API 0.7 manifest, header, ordering, Park tombstone, returning-account, tenant, restart, queue, cursor and real SQLite/Keystore cases pass.'

echo '$ sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    | tee "$EVIDENCE/debug-apk-sha256.txt"
echo 'EXPECTED: two SHA-256 lines identify the exact local test artifacts; no credential or authored content is printed.'

echo 'PAUSE source-device upgrade and cached-first reopen'
echo 'Operator: record the custody-signed source/candidate APK SHA-256 and certificate fingerprint outside this script. Upgrade the source device with adb install -r. Do not uninstall or clear data. Cold-start Blocks and verify cached Projects, Parks, Notes, Links, covers, drafts and pending work appear before network completion. A single previously authorized account must resume without a chooser; otherwise verify one muted unsynchronized indicator and the contained reconnect action.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r source_upgrade_verdict
else
    source_upgrade_verdict='BLOCKED — custody-signed candidate and physical source device required'
fi
echo "VERDICT source-device upgrade: $source_upgrade_verdict"

echo 'PAUSE production Service identity and migration ledger'
echo 'Operator/architecture: confirm the accepted Service 0.7 deployment and migration ledger through 0014, then record only the image digest, API version and opaque tenant identifiers. Do not record tokens or user-authored project content.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r ledger_verdict
else
    ledger_verdict='PASS — durable architecture acceptance records Service 0.7 through migration 0014'
fi
echo "VERDICT Service ledger: $ledger_verdict"

echo 'PAUSE source-device far-end save'
echo 'Operator: on the source device create or rename one Project and add one Park, Note and Link. Wait for background synchronization. Architecture verifies only opaque identities, operation acknowledgements and cursor movement at Service; evidence must not contain the authored values.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r source_sync_verdict
else
    source_sync_verdict='BLOCKED — physical source device and authorized test account required'
fi
echo "VERDICT source far-end save: $source_sync_verdict"

echo 'PAUSE clean second-device pull'
echo 'Operator: install the same content-hash candidate on a second clean physical device, sign into the same Google account, verify the same opaque Service tenant identity, and wait for background pull. Confirm the Project, Park, Note, Link and navigation key arrive once.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r second_pull_verdict
else
    second_pull_verdict='BLOCKED — second physical Android device required'
fi
echo "VERDICT second-device pull: $second_pull_verdict"

echo 'PAUSE offline convergence and restart'
echo 'Operator: edit independent items on both devices, including one edit while a device is offline. Reorder Projects and Links and delete one Park on either device. Restore connectivity, verify stable identities, exact-operation acknowledgement, no duplicate event, no resurrection and no lost local intent, then cold-restart both apps and repeat pull. Confirm covers and Dashboard presentation preferences remain device-local.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r convergence_verdict
else
    convergence_verdict='BLOCKED — two physical devices required'
fi
echo "VERDICT offline convergence and restart: $convergence_verdict"

echo 'VERDICT UC-03: blocked:owner'
echo 'EXPECTED: production API 0.7 is accepted; this remains blocked only on the named physical source-device and second-device pauses.'
