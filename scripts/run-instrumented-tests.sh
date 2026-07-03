#!/usr/bin/env bash
# run-instrumented-tests.sh - Run Android instrumented tests and capture logs
# This script runs instrumented tests on the emulator and handles exit codes safely.

set -uo pipefail

echo "==> Clearing adb logcat..."
adb logcat -c || true

echo "==> Disabling background Bugle/RCS churn that contends for emulator CPU..."
adb shell pm disable-user --user 0 com.google.android.apps.messaging || true

echo "==> Running instrumented tests..."

# Retry once on a timeout (exit 124) only -- a real test failure is not retried.
# (A prior version of this comment attributed hangs to a ClassNotFoundException on
# androidx.startup.InitializationProvider in the app.readylytics.health.test process.
# That was root-caused: androidx.benchmark.macro (pulled into app's androidTestImplementation
# for the unused BaselineProfileGenerator) transitively required androidx.tracing:tracing-perfetto,
# which added an InitializationProvider <provider> to the test APK manifest whose class AGP then
# excluded from the test APK dex as "already present in the app APK" -- fine for the shared-process
# instrumentation, but fatal for InstrumentationActivityInvoker$EmptyActivity, which runs in the
# test package's own separate process. Fixed by dropping the unused BaselineProfileGenerator and
# its androidx.benchmark.macro dependency from app/build.gradle.kts.)
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
