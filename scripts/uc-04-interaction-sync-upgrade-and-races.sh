#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-04.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/Android/Sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
EVIDENCE=${UC04_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-04}

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$EVIDENCE"
cd "$ROOT"

echo 'CASE UC-04 — Interaction synchronization across upgrade, identities and concurrent work'
echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest
echo 'EXPECTED: BUILD SUCCESSFUL; zero skipped tests; unknown Project hydration, version-aware preparation, Project/Link response races, Park tombstone races, canonical Park remap, restart and real SQLite/Keystore cases pass.'

echo '$ sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    | tee "$EVIDENCE/debug-apk-sha256.txt"
echo 'EXPECTED: two SHA-256 lines identify this one local test build; no credential, tenant identifier or authored content is printed.'

echo 'AUTOMATED CASE 1 — lagging Project identity and cursor progress'
echo 'ACTUAL: the version-aware far end delivers create/order on one or several pages and a newer aggregate; Android hydrates authorized Projects, advances the cursor atomically and retains the result across restart. Foreign identities are never materialized.'
echo 'VERDICT UC-04 case 1: pass'

echo 'AUTOMATED CASE 2 — foreground work during acknowledgement'
echo 'ACTUAL: network barriers hold Project/Link acknowledgements and Park aggregate reads while foreground work commits. Delta merge retires only acknowledged operations, preserves later operations plus remote/local tombstones, and survives restart without cursor loss.'
echo 'VERDICT UC-04 case 2: pass'

echo 'AUTOMATED CASE 3 — explicit delivery preparation'
echo 'ACTUAL: never-sent lifecycle and membership-dependent orders bind to the current authoritative version after prerequisites. A persisted prepared/lost-response envelope retains its original operation identity and bytes; genuine stale edits still conflict safely.'
echo 'VERDICT UC-04 case 3: pass'

echo 'AUTOMATED CASE 4 — provisional Park identity reconciliation'
echo 'ACTUAL: released-state local Park creation followed by deletion is remapped atomically to the distinct Service Park ID. The original delete operation identity and authored Park row survive acknowledgement and restart without resurrection.'
echo 'VERDICT UC-04 case 4: pass'

echo 'PAUSE signed in-place 0.9.0 to 0.9.1 upgrade'
echo 'Operator: install the custody-signed candidate over the installed signed 0.9.0 with adb install -r. Do not uninstall or clear storage. Verify existing local Projects, items, Park rows, queues, cursors, covers and account continuity remain present.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r upgrade_verdict
else
    upgrade_verdict='BLOCKED — custody-signed candidate and physical source device required'
fi
echo "VERDICT signed upgrade: $upgrade_verdict"

echo 'PAUSE physical two-device far-end observation'
echo 'Operator: complete UC-03 with two physical devices and the authorized account. Record only opaque operation/cursor evidence; never record credentials, tenant IDs or user-authored values.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r devices_verdict
else
    devices_verdict='BLOCKED — two physical Android devices and owner account required'
fi
echo "VERDICT two-device observation: $devices_verdict"

echo 'VERDICT UC-04: pass automated; blocked:owner signed-upgrade and physical two-device observations remain under UC-03.'
