# Baseline frame-timing numbers

## F14 cold-start compilation comparison — 2026-07-29

Measured on physical Samsung SM-A576B (Android API 36), using three iterations
per cold-start mode. Results JSON:
`benchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark/connected/SM-A576B - 16/app.readylytics.health.benchmark-benchmarkData.json`.

| Compilation mode | `timeToInitialDisplayMs` median |
|---|---:|
| `CompilationMode.None()` | 563.629961 ms |
| `CompilationMode.Partial(BaselineProfileMode.Require)` | 467.200859 ms |

The required Baseline Profile median is lower in this run. F14 enforces no
performance threshold; record and review measured results when regenerating.

**STATUS: PARTIALLY RECORDED** — `vitalsFling`, `vitalsChartPanAndZoom`, `coldStart`, and `warmStart` frame-timing & startup numbers extracted from physical device benchmark run (Samsung SM-A576B, Android API 36). `dashboardVitalsTabSwitch` and `hotStart` remain pending due to SQLCipher key race on tab navigation during clean install runs.

Once connected to a device/emulator after resolving the tab navigation blocker:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

These numbers will be the "before" reference for the F1/F3/F4/F5/F9/F11/F15/F19 UI
items in `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`. After running
the command and extracting the real numbers from the JSON output, update the
table cells below and re-commit with the populated data. Do not overwrite this
entry — instead, append a new dated section after each relevant F-series item
lands.

## M2 Initial Baseline — before any F-series item

| Journey | P50 (ms) | P90 (ms) | P99 (ms) |
|---|---|---|---|
| vitalsFling | 18.05 ms | 20.20 ms | 24.79 ms |
| vitalsChartPanAndZoom | 21.68 ms | 25.98 ms | 35.70 ms |
| dashboardVitalsTabSwitch | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) |

## Startup (StartupBenchmark, same run)

| Mode | timeToInitialDisplayMs P50 |
|---|---|
| coldStart | 520.41 ms |
| warmStart | 184.68 ms |
| hotStart | Pending (failed intermittently during benchmark run, see note above) |

## How to record the baseline

1. Ensure an Android device or emulator is connected and available to `adb`.
2. Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
3. Locate the JSON output file (typically at `benchmark/build/outputs/androidTest-results/connected/.../app.readylytics.health.benchmark-benchmarkData.json`).
4. Extract the `frameDurationCpuMs` P50/P90/P99 percentiles for each `ScrollBenchmark` journey and the `timeToInitialDisplayMs` P50 for each `StartupBenchmark` mode.
5. Replace each "Pending (no device available)" placeholder above with the actual number.
6. Commit with: `git commit -am "perf(M2): record baseline frame-timing numbers from real device run"`
