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

### Prerequisites

The target device/emulator needs Health Connect installed with all of the
app's required permissions granted:
`READ_SLEEP`/`READ_HEART_RATE`/`READ_HEART_RATE_VARIABILITY`/`READ_EXERCISE`/
`READ_STEPS` plus `android.permission.health.READ_HEALTH_DATA_HISTORY` (see
`criticalPermissions`/`requiredPermissions` in
`core/healthconnect/.../HealthConnectRepositoryImpl.kt`). There is no
separate "onboarding completed" flag: `SyncViewModel`/`AppNavHost` route to
`AppDestination.Onboarding`/`AppDestination.Unavailable` instead of the tab
UI purely based on this live permission check
(`HealthConnectRepositoryImpl.checkPermissions()` returning
`PermissionStatus.Missing`/`Unavailable`), so `ScrollBenchmark`'s nav-item
lookups (`By.text("Vitals")`) will fail unless the check reports `Granted`.
Grant the required permissions manually on the device before running the
suite — this is not automated.

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
launch, via `app/src/benchmark/kotlin/.../benchmark/BenchmarkDataSeeder.kt`.
There is no `app/src/main` copy of this class: `debug`, `release`, and
`benchmark` each compile their own copy of `BenchmarkDataSeeder` from their
own source set (the `debug`/`release` copies are no-ops; only `benchmark`'s
actually seeds data), so exactly one is on the compile path per build
variant and there is no redeclaration conflict. Seeding is async and
idempotent, and only runs once DB migration readiness is confirmed
(`HealthDashboardApplication`); it never affects `StartupBenchmark`'s numbers
and only costs time once.

Note this only applies to a fresh install: the seeder is gated on
`dao.count() == 0`, so it only fires against an empty `daily_summaries`
table. A device that already satisfies the Prerequisites section above
(real, synced Health Connect data) will already have rows and the seeder
will skip — the journeys will exercise that real data instead, which still
satisfies "the chart has content" but won't match the specific deterministic
values described below.

See `BASELINE.md` for the last-recorded numbers and when/how to refresh them.
