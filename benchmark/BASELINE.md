# Baseline frame-timing numbers

**STATUS: PENDING** — No Android device or emulator is currently connected. The
baseline numbers below have not been recorded yet. To fill in the real numbers,
run the following command on a connected device/emulator:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

These numbers will be the "before" reference for the F1/F3/F4/F5/F9/F11/F15/F19 UI
items in `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`. After running
the command and extracting the real numbers from the JSON output, update the
table cells below and re-commit with the populated data. Do not overwrite this
entry — instead, append a new dated section after each relevant F-series item
lands.

## [PENDING] — before any F-series item

| Journey | P50 (ms) | P90 (ms) | P99 (ms) |
|---|---|---|---|
| vitalsFling | Pending (no device available) | Pending (no device available) | Pending (no device available) |
| vitalsChartPanAndZoom | Pending (no device available) | Pending (no device available) | Pending (no device available) |
| dashboardVitalsTabSwitch | Pending (no device available) | Pending (no device available) | Pending (no device available) |

## Startup (StartupBenchmark, same run)

| Mode | timeToInitialDisplayMs P50 |
|---|---|
| coldStart | Pending (no device available) |
| warmStart | Pending (no device available) |
| hotStart | Pending (no device available) |

## How to record the baseline

1. Ensure an Android device or emulator is connected and available to `adb`.
2. Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
3. Locate the JSON output file (typically at `benchmark/build/outputs/androidTest-results/connected/.../app.readylytics.health.benchmark-benchmarkData.json`).
4. Extract the `frameDurationCpuMs` P50/P90/P99 percentiles for each `ScrollBenchmark` journey and the `timeToInitialDisplayMs` P50 for each `StartupBenchmark` mode.
5. Replace each "Pending (no device available)" placeholder above with the actual number.
6. Commit with: `git commit -am "perf(M2): record baseline frame-timing numbers from real device run"`
