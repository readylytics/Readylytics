#!/usr/bin/env bash
# run-instrumented-tests.sh - Run Android instrumented tests and capture logs
# This script runs instrumented tests on the emulator and handles exit codes safely.

set -uo pipefail

echo "==> Clearing adb logcat..."
adb logcat -c || true

echo "==> Running instrumented tests..."
test_status=0

# Run the tests with a timeout
timeout --signal=TERM --kill-after=1m 25m ./gradlew connectedDebugAndroidTest -x :benchmark:connectedDebugAndroidTest --stacktrace --console=plain || test_status=$?

echo "==> Test run finished with exit code: ${test_status}"

echo "==> Dumping logcat to logcat.txt..."
adb logcat -d > logcat.txt || true

echo "==> Exiting with status ${test_status}"
exit "${test_status}"
