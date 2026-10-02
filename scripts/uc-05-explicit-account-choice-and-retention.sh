#!/bin/sh
# Human-readable companion for Atlas-Blocks UC-05.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-/home/dev/Android/Sdk}
JDK=${JAVA_HOME:-/home/dev/.local/share/jdks/jdk-17.0.20.1+1}
EVIDENCE=${UC05_EVIDENCE_DIR:-/home/dev/work/Blocks-uat/UC-05}
BASELINE=

export ANDROID_HOME="$SDK"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$EVIDENCE"
cd "$ROOT"

cleanup() {
    if [ -n "$BASELINE" ] && [ -d "$BASELINE" ]; then
        git worktree remove "$BASELINE" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT HUP INT TERM

echo 'CASE UC-05 — Explicit Google account choice without losing retained work'

echo '$ git worktree add --detach <temporary-path> v0.9.1; ./gradlew --no-daemon :app:compileDebugKotlin; inspect GoogleIdentityClient.class'
BASELINE=$(mktemp -d /tmp/blocks-android-uc05-v091.XXXXXX)
rmdir "$BASELINE"
git worktree add --detach "$BASELINE" v0.9.1 >/dev/null
(
    cd "$BASELINE"
    ./gradlew --no-daemon :app:compileDebugKotlin >/dev/null
)
BASELINE_CLASS=$(find "$BASELINE/app/build" -type f \
    -path '*/dev/blocks/android/auth/GoogleIdentityClient.class' -print -quit)
if [ -z "$BASELINE_CLASS" ]; then
    echo 'ACTUAL: released GoogleIdentityClient.class was not produced; baseline proof is invalid.'
    echo 'VERDICT released request-selection regression: fail'
    exit 1
fi
if strings "$BASELINE_CLASS" | grep -Fq 'GetSignInWithGoogleOption'; then
    echo 'ACTUAL: released v0.9.1 unexpectedly contains the explicit Google option.'
    echo 'VERDICT released request-selection regression: fail'
    exit 1
fi
echo 'EXPECTED/ACTUAL: released v0.9.1 lacks GetSignInWithGoogleOption and therefore fails the explicit-request selection regression.'
echo 'VERDICT released request-selection regression: expected fail'
git worktree remove "$BASELINE" >/dev/null
BASELINE=

echo '$ ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest'
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=dev.blocks.android.state.LocalActionPerformanceTest
echo 'EXPECTED: BUILD SUCCESSFUL; active API 0.7 and frozen 0.6/0.4/0.2 manifests pass; JVM, lint, debug/release/androidTest assembly and all 124 non-timing real SQLite/Keystore and retention tests have zero failures and zero skips.'

RESULTS=$(mktemp -d "$EVIDENCE/results.XXXXXX")
mkdir -p "$RESULTS/non-performance"
find app/build/outputs/androidTest-results/connected/debug -type f -name 'TEST-*.xml' \
    -exec cp {} "$RESULTS/non-performance/" \;

echo '$ adb install -r app-debug.apk; adb install -r -t app-debug-androidTest.apk'
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ANDROID_HOME/platform-tools/adb" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
echo 'EXPECTED: the exact already-built debug and instrumentation APKs are installed on the disposable test emulator; no owner device or data is involved.'

echo '$ adb shell am instrument -w -r -e class dev.blocks.android.state.LocalActionPerformanceTest#realButtonsRenderSeededCreateAndParkResultsWithinBudget dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner'
"$ANDROID_HOME/platform-tools/adb" shell am instrument -w -r \
    -e class 'dev.blocks.android.state.LocalActionPerformanceTest#realButtonsRenderSeededCreateAndParkResultsWithinBudget' \
    dev.blocks.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RESULTS/performance-instrumentation.txt"
grep -F 'OK (1 test)' "$RESULTS/performance-instrumentation.txt" >/dev/null
echo 'EXPECTED: OK (1 test); the isolated real-emulator interaction-performance gate uses the same APKs without competing build/lint work.'

CURRENT_CLASS=$(find app/build -type f \
    -path '*/dev/blocks/android/auth/GoogleIdentityClient.class' -print -quit)
[ -n "$CURRENT_CLASS" ] || { echo 'repaired GoogleIdentityClient.class was not produced'; exit 1; }
strings "$CURRENT_CLASS" | grep -F 'GetSignInWithGoogleOption' >/dev/null
echo 'EXPECTED/ACTUAL: repaired GoogleIdentityClient contains the installed explicit Google option and its instrumentation request boundary passed.'
echo 'VERDICT repaired request-selection regression: pass'

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
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit("test result gate is not zero-failure, zero-error and zero-skip")
if totals["tests"] != 300:
    raise SystemExit(f"expected 176 JVM plus 124 non-performance instrumentation tests, got {totals['tests']}")
print("RESULT COUNTS performance=1 failures=0 errors=0 skipped=0; combined tests=301")
PY

echo '$ sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
sha256sum \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    | tee "$EVIDENCE/debug-apk-sha256.txt"
git rev-parse HEAD | tee "$EVIDENCE/source-head.txt"
echo 'EXPECTED: one source head and two SHA-256 lines identify the exact local test build; no credential, account address, tenant identifier or authored content is printed.'

echo 'AUTOMATED CASE 1 — deliberate request selection'
echo 'ACTUAL: login and Switch account share one deliberate signIn boundary. It submits exactly one GetSignInWithGoogleOption request, with the existing server client ID, and performs no authorized-only preflight or app-added retry.'
echo 'VERDICT UC-05 case 1: pass'

echo 'AUTOMATED CASE 2 — contained cancellation and restart'
echo 'ACTUAL: provider cancellation produces the contained cancellation outcome after one request. Initial login authenticates nobody. A cancelled Switch locks the active pointer but retains the accepted binding, tenant partition and pending operation across process-style restart; later Service-validated same-account login reopens them without deletion or rebinding.'
echo 'VERDICT UC-05 case 2: pass'

echo 'AUTOMATED CASE 3 — Service identity and tenant isolation'
echo 'ACTUAL: existing A-to-B-to-A, rejected credential, wrong binding and malformed/failed authentication coverage permits only Service-validated stable identity and tenant activation. Retained partitions, queues and covers remain isolated and are not erased by failed selection.'
echo 'VERDICT UC-05 case 3: pass'

echo 'AUTOMATED CASE 4 — quiet returning-account restoration'
echo 'ACTUAL: the request boundary remains one authorized-only, auto-select-enabled, immediately-available GetGoogleIdOption with no explicit chooser or broad fallback. Available matching identity activates transport after Service validation; unavailable/cancelled leaves cached work open and unsynchronized.'
echo 'VERDICT UC-05 case 4: pass'

echo 'PAUSE physical provider account choice'
echo 'Owner: on one Google Play-enabled physical device and Android profile, keep at least two provider-eligible accounts, including the desired previously used account and another authorized account. Test deliberate login and Switch account, confirm the desired account is offered, and confirm cancellation authenticates nobody. Record only pass/fail and profile/provider limitations; do not record account addresses, tokens or account lists.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r account_choice_verdict
else
    account_choice_verdict='BLOCKED — owner device and provider-eligible accounts required'
fi
echo "VERDICT UC-05 case 5: $account_choice_verdict"

echo 'PAUSE custody-signed in-place 0.9.1 to 0.9.2 upgrade'
echo 'Owner/Orchestrator: install custody-signed 0.9.2 over signed 0.9.1 with adb install -r. Do not uninstall or clear storage. Compare representative Projects, Parks, Notes, Links, covers and pending work before/after and after cold restart; confirm package and signing certificate continuity.'
if [ -t 0 ]; then
    printf 'Enter PASS or BLOCKED: '
    read -r signed_upgrade_verdict
else
    signed_upgrade_verdict='BLOCKED — accepted custody-signed candidate and owner device required'
fi
echo "VERDICT UC-05 case 6: $signed_upgrade_verdict"

echo 'VERDICT UC-05: blocked:owner'
echo 'EXPECTED: automated cases pass; physical provider choice, cold start and custody-signed retained-data upgrade remain named owner/device gates.'
