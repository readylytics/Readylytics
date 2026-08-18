#!/usr/bin/env bash
# run-instrumented-tests-local.sh - Run Android instrumented tests on a local physical
# device/emulator with CI-equivalent conditions, without touching unrelated device state.
#
# Unlike scripts/run-instrumented-tests.sh (CI-only), this script does NOT disable any
# app on the device -- it is safe to run against a developer's own phone.
set -uo pipefail

echo "==> Reading current animation scale settings..."
orig_window_scale=$(adb shell settings get global window_animation_scale | tr -d '\r')
orig_transition_scale=$(adb shell settings get global transition_animation_scale | tr -d '\r')
orig_animator_scale=$(adb shell settings get global animator_duration_scale | tr -d '\r')

restore_animations() {
    echo "==> Restoring animation scales (window=${orig_window_scale}, transition=${orig_transition_scale}, animator=${orig_animator_scale})..."
    adb shell settings put global window_animation_scale "${orig_window_scale}" || true
    adb shell settings put global transition_animation_scale "${orig_transition_scale}" || true
    adb shell settings put global animator_duration_scale "${orig_animator_scale}" || true
}
trap restore_animations EXIT

echo "==> Disabling animations (matches CI's disable-animations: true)..."
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

echo "==> Clearing adb logcat..."
adb logcat -c || true

echo "==> Running instrumented tests with CI-equivalent benchmark suppression..."
# Same suppressErrors set as scripts/run-instrumented-tests.sh:37-38 -- without these,
# ScoringWalkForwardBenchmark fails locally with:
#   java.lang.AssertionError: ERRORS (not suppressed): ACTIVITY-MISSING DEBUGGABLE NOT-AOT-COMPILED
test_status=0
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=ACTIVITY-MISSING,DEBUGGABLE,EMULATOR,NOT-AOT-COMPILED \
    --stacktrace --console=plain || test_status=$?

echo "==> Dumping logcat to logcat-local.txt..."
adb logcat -d > logcat-local.txt || true

echo "==> Test run finished with exit code: ${test_status}"
exit "${test_status}"
