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

**STATUS: PENDING** — `ScrollBenchmark`'s three journeys have not been recorded.
A device was connected and repeatedly tested against this session, but every
fresh install hit a real, reproducible SQLCipher key/DB race on first launch
that routes the app into its recovery screen instead of the tab UI — see
`internal-docs/plans/KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md` for the
full writeup. This is unrelated to the M2 Macrobenchmark work itself (the
`ScrollBenchmark` code, testTags, and permission auto-granting were all
verified working correctly once the app actually reaches the Vitals tab).
Once that race is fixed, run the following on a connected device/emulator to
fill in the numbers below:

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
| vitalsFling | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) |
| vitalsChartPanAndZoom | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) |
| dashboardVitalsTabSwitch | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) |

## Startup (StartupBenchmark, same run)

`coldStart`/`warmStart` passed cleanly in multiple runs this session (both
unaffected by the tab-navigation blocker above, since they only measure
launch-to-first-frame, not reaching Vitals). `hotStart` failed intermittently
with "Unable to read any metrics during benchmark" — not yet investigated,
may be related to the same first-launch instability or may be a separate,
narrower issue. None of these numbers were captured/extracted this session
(the run's purpose was verifying `ScrollBenchmark` reachability, not yet
recording a clean full-suite baseline) — do not treat "passed" as "recorded."

| Mode | timeToInitialDisplayMs P50 |
|---|---|
| coldStart | Pending (passed in test runs this session, numbers not extracted) |
| warmStart | Pending (passed in test runs this session, numbers not extracted) |
| hotStart | Pending (failed intermittently this session, see note above) |

## How to record the baseline

1. Ensure an Android device or emulator is connected and available to `adb`.
2. Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
3. Locate the JSON output file (typically at `benchmark/build/outputs/androidTest-results/connected/.../app.readylytics.health.benchmark-benchmarkData.json`).
4. Extract the `frameDurationCpuMs` P50/P90/P99 percentiles for each `ScrollBenchmark` journey and the `timeToInitialDisplayMs` P50 for each `StartupBenchmark` mode.
5. Replace each "Pending (no device available)" placeholder above with the actual number.
6. Commit with: `git commit -am "perf(M2): record baseline frame-timing numbers from real device run"`
