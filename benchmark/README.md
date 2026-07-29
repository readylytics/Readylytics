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

Android/Hilt seeding and performance-only Compose semantics are shared from
`app/src/profileSupport/kotlin`; pure row construction is shared from
`app/src/profileSeed/kotlin`. Both the benchmark and non-minified profile
builds receive those directories; production release receives neither.

The seed covers 180 days, which includes 7D, 30D, and 180D ranges. Summary and
sleep-session rows are checked and upserted independently, so the seeder never
deletes existing data. Seeding happens before scroll journeys and is excluded
from `StartupBenchmark` timing.

## Baseline and Startup Profiles

Canonical generation uses the Gradle-managed Pixel 9 API 36 AOSP device. API
36 is the approved fallback because the requested API 37 AOSP ARM64 system
image is unavailable in the local SDK:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Connected generation requires one selected rooted device or API 33+ device
with Health Connect:

```bash
./gradlew :app:generateReleaseBaselineProfile \
  -Preadylytics.baselineprofile.connected=true
```

The generated files are:

- `app/src/release/generated/baselineProfiles/baseline-prof.txt`
- `app/src/release/generated/baselineProfiles/startup-prof.txt`

Validate packaged binary assets with:

```bash
./gradlew :app:assembleBenchmark
unzip -l app/build/outputs/apk/benchmark/app-benchmark.apk \
  | rg 'assets/dexopt/baseline\\.prof$|assets/dexopt/baseline\\.profm$'
unzip -p app/build/outputs/apk/benchmark/app-benchmark.apk \
  assets/dexopt/baseline.prof | wc -c
```

Measure startup compilation modes with the explicit debug-signed benchmark
variant:

```bash
ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.StartupBenchmark
```

Regenerate and review both profiles once per release and after
performance-critical navigation or chart changes. Generation is explicit and
is not part of normal release assembly.

See `BASELINE.md` for the last-recorded numbers and when/how to refresh them.
