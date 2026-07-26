# :benchmark module

Macrobenchmark suite (`androidx.benchmark.macro`) for `app.readylytics.health`.
This module is a `com.android.test` module targeting `:app`'s `benchmark` build
type (`initWith(release)`, debug-signed, non-debuggable, profileable).

Excluded from CI (`scripts/run-instrumented-tests.sh` runs
`-x :benchmark:connectedDebugAndroidTest`) — run locally on a connected
device/emulator:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

## Test classes

- `StartupBenchmark.kt` — cold/warm/hot start `StartupTimingMetric`.
- `ScrollBenchmark.kt` — `FrameTimingMetric` journeys on Vitals:
  - `vitalsFling` — vertical fling to bottom and back, x2.
  - `vitalsChartPanAndZoom` — switches to the 30D range, then horizontal pan +
    pinch-zoom/pinch-close on the HRV trend chart.
  - `dashboardVitalsTabSwitch` — Dashboard <-> Vitals tab switch, x3.

## Deterministic data

`ScrollBenchmark`'s journeys need real `daily_summaries` rows for the Vitals
charts to render (an empty DB shows skeletons/placeholders, not charts). The
`benchmark` build type seeds 180 days of deterministic data once, on first
launch, via `app/src/benchmark/kotlin/.../benchmark/BenchmarkDataSeeder.kt`
(overrides the no-op `app/src/main` version of the same class for this build
type only — see that file's doc comment). Seeding is async and idempotent, so
it never affects `StartupBenchmark`'s numbers and only costs time once.

See `BASELINE.md` for the last-recorded numbers and when/how to refresh them.
