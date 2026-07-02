#!/usr/bin/env bash
# run-instrumented-tests.sh - Run Android instrumented tests and capture logs
# This script runs instrumented tests on the emulator and handles exit codes safely.

set -uo pipefail

echo "==> Clearing adb logcat..."
adb logcat -c || true

echo "==> Disabling background Bugle/RCS churn that contends for emulator CPU..."
adb shell pm disable-user --user 0 com.google.android.apps.messaging || true

echo "==> Running instrumented tests..."

# The emulator occasionally hits an intermittent ART/zygote race
# (ClassNotFoundException on androidx.startup.InitializationProvider during an
# app process restart), which stalls the UTP driver instead of failing cleanly.
# Retry once on a timeout (exit 124) only -- a real test failure is not retried.
max_attempts=2
attempt=1
test_status=0

while [ "${attempt}" -le "${max_attempts}" ]; do
    echo "==> Attempt ${attempt}/${max_attempts}..."
    test_status=0
    timeout --signal=TERM --kill-after=30s 15m ./gradlew connectedDebugAndroidTest -x :benchmark:connectedDebugAndroidTest --stacktrace --console=plain || test_status=$?

    echo "==> Attempt ${attempt} finished with exit code: ${test_status}"

    if [ "${test_status}" -ne 124 ]; then
        break
    fi

    echo "==> Timed out -- cleaning up stuck processes/daemon before retry..."
    ./gradlew --stop || true
    adb shell am force-stop app.readylytics.health || true
    adb shell am force-stop app.readylytics.health.test || true

    attempt=$((attempt + 1))
done

echo "==> Test run finished with exit code: ${test_status}"

echo "==> Dumping logcat to logcat.txt..."
adb logcat -d > logcat.txt || true

echo "==> Exiting with status ${test_status}"
exit "${test_status}"
