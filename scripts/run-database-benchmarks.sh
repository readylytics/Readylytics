#!/usr/bin/env bash
# run-database-benchmarks.sh - run the :database-benchmark microbenchmarks on a connected device.
#
# Why this script exists instead of a plain Gradle invocation:
#
#   AGP does not deliver instrumentation-runner arguments whose KEY CONTAINS DOTS. Non-dotted keys
#   configured in `database-benchmark/build.gradle.kts` (`class`, `notAnnotation`) arrive correctly,
#   but `androidx.benchmark.suppressErrors` never reaches the runner -- neither from
#   `testInstrumentationRunnerArguments` nor from
#   `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=...`.
#
#   Proven on SM-A576B: with the argument delivered by `am instrument` directly, androidx.benchmark
#   downgrades DEBUGGABLE and NOT-AOT-COMPILED to WARNINGs and the benchmarks run. Without it every
#   measuring test fails with "ERRORS (not suppressed): DEBUGGABLE NOT-AOT-COMPILED".
#
#   So: Gradle builds and installs, `am instrument` runs. Timing numbers from a debuggable,
#   non-AOT-compiled build are NOT release-representative -- they are for relative before/after
#   comparison at a fixed scale, which is what Phase 0 needs.
#
# Usage:
#   scripts/run-database-benchmarks.sh                      # every benchmark class
#   scripts/run-database-benchmarks.sh <fully.qualified.Class[#method]>
set -uo pipefail

CLASS="${1:-}"
PKG="app.readylytics.health.databasebenchmark.test"
RUNNER="androidx.benchmark.junit4.AndroidBenchmarkRunner"
SUPPRESS="ACTIVITY-MISSING,DEBUGGABLE,EMULATOR,NOT-AOT-COMPILED"

echo "==> Building and installing the instrumentation APK..."
./gradlew :database-benchmark:assembleDebugAndroidTest || exit $?
APK=$(find database-benchmark/build/outputs/apk/androidTest/debug -name "*.apk" | head -1)
[ -n "${APK}" ] || { echo "no androidTest APK found"; exit 1; }
adb install -r -d "${APK}" || exit $?

# androidx.benchmark's IsolationActivity cannot reach an idle state behind a lock screen or with
# animations on; it times out after 45s with "Could not launch activity".
echo "==> Preparing the device (awake, animations off)..."
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

# The module filters benchmarks out of the routine sweep with notAnnotation=LargeTest; override it
# here so they actually run.
ARGS=(-e androidx.benchmark.suppressErrors "${SUPPRESS}"
      -e notAnnotation androidx.test.filters.FlakyTest)
[ -n "${CLASS}" ] && ARGS+=(-e class "${CLASS}")

echo "==> Running benchmarks${CLASS:+ (${CLASS})}..."
adb logcat -c || true
adb shell am instrument -w -r "${ARGS[@]}" "${PKG}/${RUNNER}" 2>&1 | tee /tmp/database-benchmarks.txt

echo "==> Metric lines (Phase0Metrics / BaselineMetrics / BenchmarkMetrics):"
adb logcat -d -s Phase0Metrics BaselineMetrics BenchmarkMetrics 2>/dev/null | grep -E "METRIC=|STAGE=" || \
  echo "(none emitted)"

if grep -q "FAILURES!!!" /tmp/database-benchmarks.txt; then
    echo "==> FAILED (full output in /tmp/database-benchmarks.txt)"
    exit 1
fi
echo "==> OK (full output in /tmp/database-benchmarks.txt)"
