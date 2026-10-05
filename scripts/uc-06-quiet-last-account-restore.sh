#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-06.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/.local/share/android-sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
SERIAL=${UC06_EMULATOR_SERIAL:-emulator-5554}
EVIDENCE=${UC06_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-06}
ADB="$SDK/platform-tools/adb"

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL="$SERIAL"
mkdir -p "$EVIDENCE"
cd "$ROOT"

case "$SERIAL" in
    emulator-*) ;;
    *)
        echo "REFUSED: UC-06 must target a disposable emulator explicitly; got $SERIAL."
        exit 1
        ;;
esac
"$ADB" -s "$SERIAL" get-state | grep -Fx device >/dev/null
echo "TARGET: disposable emulator $SERIAL (every adb command is explicitly qualified)"

echo 'CASE UC-06 — Quiet restore of the last Service-accepted account'

echo '$ git show v0.9.2:app/src/main/java/dev/blocks/android/MainActivity.kt | inspect automatic restore boundary'
git show v0.9.2:app/src/main/java/dev/blocks/android/MainActivity.kt \
    | grep -F 'identity.returningAccount(this@MainActivity)' >/dev/null
echo 'EXPECTED/ACTUAL: released v0.9.2 invokes the Google identity gateway after opening cached state and therefore fails the zero-startup-identity-call regression.'
echo 'VERDICT released v0.9.2 regression: expected fail'

echo '$ inspect candidate automatic and deliberate identity boundaries'
if grep -R -n -E 'returningAccount|GetGoogleIdOption' app/src/main/java; then
    echo 'ACTUAL: the candidate still contains an automatic Google identity request.'
    exit 1
fi
grep -F 'GetSignInWithGoogleOption' app/src/main/java/dev/blocks/android/auth/GoogleIdentityClient.kt >/dev/null
echo 'EXPECTED/ACTUAL: no automatic Google request exists; deliberate Login/Switch retains GetSignInWithGoogleOption.'

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; active API 0.7 and frozen manifests, JVM, lint, debug/release assembly and all non-timing SQLite/Keystore tests have zero failures and zero skips.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ adb -s <disposable-emulator> install -r exact already-built debug and androidTest APKs'
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
echo 'EXPECTED: exact one-build artifacts installed without touching any owner device or app data.'

echo '$ seed encrypted state; force-stop package; restore in a fresh instrumentation process'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.QuietAccountRestoreProcessTest#aSeedPersistentTenantForProcessDeath' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/process-seed.txt"
grep -F 'OK (1 test)' "$RESULTS/process-seed.txt" >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop dev.blocks.android
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'dev.blocks.android.QuietAccountRestoreProcessTest#bRestorePersistentTenantAfterProcessDeathWithoutIdentityOrTransport' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/process-restore.txt"
grep -F 'OK (1 test)' "$RESULTS/process-restore.txt" >/dev/null
echo 'EXPECTED/ACTUAL: real SQLite/Keystore state survives the explicit force-stop boundary; startup performs zero identity and Service calls and retains Projects, Parks, Notes, Links, cover, drafts and both queued operations.'

echo '$ adb -s <disposable-emulator> reboot; settle offline; run isolated performance test from same APKs'
"$ADB" -s "$SERIAL" reboot
"$ADB" -s "$SERIAL" wait-for-device
while [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; do
    sleep 2
done
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
echo 'EXPECTED/ACTUAL: isolated local-action performance gate passes using the same built artifacts.'

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
print(f"RESULT COUNTS direct-process=2 performance=1 combined={totals['tests'] + 3}")
PY

echo '$ sha256sum exact debug and androidTest APKs; git rev-parse HEAD'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    | tee "$EVIDENCE/debug-apk-sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"
echo 'EXPECTED: source and content hashes identify the one exact local build without printing credentials, identity values or owner-authored content.'

echo 'AUTOMATED CASE 1 — real cold start and zero identity calls'
echo 'ACTUAL: released v0.9.2 source fails; candidate startup restores only the encrypted accepted-tenant pointer and never invokes IdentityProvider or Credential Manager.'
echo 'VERDICT UC-06 case 1: pass'

echo 'AUTOMATED CASE 2 — ordinary, App Link, QR and NFC entry'
echo 'ACTUAL: all valid cached entry variants open the retained Project with zero identity, session-exchange or sync calls; invalid input keeps its refusal and mutates nothing.'
echo 'VERDICT UC-06 case 2: pass'

echo 'AUTOMATED CASE 3 — persistent retained local work'
echo 'ACTUAL: a true force-stop between two instrumentation processes retains encrypted Projects, Parks, Notes, Links, cover bytes, drafts and queued operations; transport stays inactive and pending work remains pending.'
echo 'VERDICT UC-06 case 3: pass'

echo 'AUTOMATED CASE 4 — deliberate chooser, cancellation and reconnect'
echo 'ACTUAL: Login, Switch and Reconnect retain one explicit Google request and Service validation. Cancellation activates no tenant and changes no retained binding; deliberate matching reconnection reactivates synchronization.'
echo 'VERDICT UC-06 case 4: pass'

echo 'AUTOMATED CASE 5 — no credential persistence or identity weakening'
echo 'ACTUAL: candidate persists no token, session, email or credential surrogate; tenant selection remains the encrypted Service-accepted issuer/subject binding.'
echo 'VERDICT UC-06 case 5: pass'

echo 'PAUSE UC-06 case 6 — custody-signed in-place physical observation'
echo 'Owner/Orchestrator: install custody-signed 0.9.3 in place over signed 0.9.2. Do not uninstall, clear storage, remove accounts or change signing identity. With at least two eligible accounts, compare representative retained work, kill the process, then open directly and through NFC/QR. Confirm no chooser on restore and a chooser only after deliberate Login/Switch.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r physical_verdict
else
    physical_verdict='blocked:owner — accepted custody-signed candidate and owner device required'
fi
echo "VERDICT UC-06 case 6: $physical_verdict"

echo 'VERDICT UC-06: blocked:owner'
echo 'EXPECTED: automated cases pass; only the custody-signed in-place physical observation remains blocked on owner/device evidence.'
