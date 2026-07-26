# :benchmark module

Macrobenchmark suite (`androidx.benchmark.macro`) for `app.readylytics.health`.
This module is a `com.android.test` module targeting `:app`'s `benchmark` build
type (`initWith(release)`, debug-signed, non-debuggable, profileable). The
`benchmark` build type has its own `applicationIdSuffix = ".macrobenchmark"`
(installed as `app.readylytics.health.macrobenchmark`) so it installs
side-by-side with any existing `debug`/`release` install on the same device
instead of conflicting with it. (`.benchmark` alone was tried first and
collides with this `:benchmark` test module's own `namespace`
(`app.readylytics.health.benchmark`) — the target app and the test APK ended
up sharing one applicationId.)

Excluded from CI (`scripts/run-instrumented-tests.sh` runs
`-x :benchmark:connectedDebugAndroidTest`) — run locally on a connected
device/emulator:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

### Prerequisites

The target device/emulator needs Health Connect installed. `SyncViewModel`/
`AppNavHost` route to `AppDestination.Onboarding`/`AppDestination.Unavailable`
instead of the tab UI unless Health Connect reports all required permissions
granted (`HealthConnectRepositoryImpl.checkPermissions()`), so `ScrollBenchmark`'s
nav-item lookups (`By.text("Vitals")`) would otherwise fail. This IS automated:
`ScrollBenchmark`'s `@Before grantHealthConnectPermissions()` grants all six
required permissions (`READ_SLEEP`/`READ_HEART_RATE`/
`READ_HEART_RATE_VARIABILITY`/`READ_EXERCISE`/`READ_STEPS`/
`android.permission.health.READ_HEALTH_DATA_HISTORY` — see
`criticalPermissions`/`requiredPermissions` in
`core/healthconnect/.../HealthConnectRepositoryImpl.kt`) via
`UiAutomation.grantRuntimePermission(...)` before every test method, the same
mechanism `androidx.test.rules.GrantPermissionRule` uses internally. This was
added after confirming `connectedBenchmarkAndroidTest` reinstalls the target
app fresh on every invocation (a new install-path hash is logged each run),
which wipes any permission grant made between runs — manual/external granting
cannot survive this task's lifecycle, so it has to happen from inside the
instrumented test itself, after the fresh install.

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

Seeding is gated on `dao.count() == 0`, so it only fires against an empty
`daily_summaries` table — in practice this is every `connectedBenchmarkAndroidTest`
run, since the same fresh-reinstall behavior described in Prerequisites also
wipes any previously-synced app data, not just permissions. So the deterministic
180-day dataset is what every run actually measures against, not incidental
real device data.

See `BASELINE.md` for the last-recorded numbers and when/how to refresh them.
