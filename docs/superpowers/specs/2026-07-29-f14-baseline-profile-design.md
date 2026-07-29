# F14 Baseline Profile Generation Design

**Status:** Approved design

**Date:** 2026-07-29

**Source:** F14 in `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`

## Goal

Ship generated Baseline and Startup Profiles with Readylytics release builds so cold startup and the first interaction with the app's chart screens can be compiled ahead of use. Keep profile generation explicit, reproducible on a Gradle-managed device, and available on a connected device without replacing an installed production app.

## Scope

The generated critical user journey covers the five current top-level tabs:

1. Dashboard
2. Sleep
3. Vitals
4. Workouts
5. Settings

Insights is omitted because it is a Dashboard card and detail sheet, not a top-level tab.

Dashboard and Settings are rendered but receive no synthetic interaction. The journey pans and pinch-zooms one existing Vico chart on each chart-bearing tab:

- Sleep: Sleep trend chart
- Vitals: HRV trend chart
- Workouts: ACWR chart

No new user-facing chart, control, or representative interaction is added.

## Tooling and Module Boundary

Extend the existing `:benchmark` module rather than create a second profile-producer module. The module remains the single home for black-box performance automation, Health Connect permission setup, UI selectors, critical user journeys, profile generation, and profile measurement.

Use `androidx.baselineprofile` 1.5.0-alpha07. Readylytics uses AGP 9.3.1 with `android.newDsl=true`; the 1.5 line supports AGP 9's new DSL. Keep the existing stable ProfileInstaller dependency in `:app`.

Apply the Baseline Profile plugin to:

- `:benchmark` as the profile producer
- `:app` as the profile consumer

Wire `baselineProfile(project(":benchmark"))` in `app/build.gradle.kts`. Save generated profiles in the release source set and commit them. Disable automatic generation during assembly so normal builds and CI do not require an emulator.

## Generation Targets

Configure a Gradle-managed device named `pixel9Api37` with:

- Device: Pixel 9
- API level: 37
- System image source: `aosp`

Managed-device generation is the default. Connected-device generation is selected explicitly with:

```bash
-Preadylytics.baselineprofile.connected=true
```

The build configuration must select one mode per invocation:

- Default: `pixel9Api37`, with connected devices disabled
- Property enabled: connected device, with managed devices excluded

Connected generation requires a rooted device or API level 33 or newer. The connected device must provide Health Connect; API 37 includes it as a platform service.

## Profile-Enabled App Variant

The producer profiles the plugin-created non-minified release-derived app variant. Configure that build for local installation with the debug signing key and the exact application ID suffix:

```text
.baselineprofile
```

Its application ID is therefore:

```text
app.readylytics.health.baselineprofile
```

The suffix prevents connected generation from replacing an installed production Readylytics app. Profile rules describe release bytecode and remain consumable by the unsuffixed release artifact.

The non-minified profile-generation build must compile the existing benchmark-only support:

- deterministic Room seeding
- Compose `testTagsAsResourceId` publication

Share the implementation source directory between the existing `benchmark` build and the non-minified release-derived build. Do not move benchmark support into `main` and do not add benchmark seeding or test-tag publication to production release runtime behavior.

## Profile Collections

Use separate `BaselineProfileRule` collections for startup and runtime journeys.

### Startup Collection

The startup collection:

1. Grants all required Health Connect permissions to `app.readylytics.health.baselineprofile`.
2. Forces a cold launch.
3. Starts the main activity.
4. Waits for the existing Dashboard root test tag.

Set `includeInStartupProfile = true` only for this collection. Its rules contribute to both the Baseline Profile and Startup Profile.

### Critical User Journey Collection

The runtime collection:

1. Cold-launches the app and waits for Dashboard.
2. Opens Sleep.
3. Selects 30D.
4. Scrolls vertically until the Sleep trend chart is visible.
5. Waits for a non-empty Sleep trend chart, then pans horizontally, pinches open, and pinches closed.
6. Opens Vitals.
7. Selects 30D.
8. Waits for a non-empty HRV trend chart, then performs the same pan and zoom gestures.
9. Opens Workouts.
10. Selects 30D.
11. Scrolls vertically until ACWR is visible.
12. Waits for a non-empty ACWR chart, then performs the same pan and zoom gestures.
13. Opens Settings and waits for Settings content.

Set `includeInStartupProfile = false` for this collection. Its rules remain in the Baseline Profile but do not expand DEX startup layout with unrelated runtime paths.

Extract focused journey helpers inside `:benchmark` for:

- target package IDs
- Health Connect permission grants
- bounded object/content waits
- tab navigation
- 30D selection
- vertical reveal
- horizontal pan and pinch zoom

Reuse helpers from existing Macrobenchmarks only when their target package and behavior match. Keep package IDs explicit because Macrobenchmarks target `app.readylytics.health.macrobenchmark`, while profile generation targets `app.readylytics.health.baselineprofile`.

## Deterministic Data

The managed device is fresh, so chart data cannot depend on prior user state or Health Connect records. Expand the existing app `benchmark`-support seeder to produce all data required by the three chart journeys.

Retain 180 deterministic `DailySummaryEntity` rows and add:

- non-zero `trimpWorkoutOnly`
- non-zero `trimpEverydayHr`

Add 180 deterministic `SleepSessionEntity` rows with stable IDs and physiologically valid start, end, duration, and awake values. Sleep sessions end on their represented local dates so `SleepViewModel`'s production grouping creates non-null Sleep trend points across all supported ranges.

Seed summary and sleep tables independently:

- Check each table's own state.
- Upsert stable records into a missing table.
- Never call `deleteAll()`.
- A cancellation or process death between table writes must be recoverable on the next launch.

Put deterministic row construction in a focused, pure Kotlin benchmark source file. Keep Android/Hilt/DAO access in `BenchmarkDataSeeder`.

The application continues to wait for the existing database-readiness gate before seeding. Production repositories and Room-backed StateFlows then render the profile journey's screens exactly as they do in release builds.

## Automation Selectors and Failures

Retain the existing `HrvTrendChart` tag. Add non-visible Compose test tags to the existing chart containers:

```text
SleepTrendChart
AcwrChart
```

Do not alter chart appearance, behavior, layout, or user-facing strings.

All automation waits are bounded and fail with a message that identifies:

- missing Health Connect permission grant
- missing tab
- missing 30D selector
- chart not found
- chart still displaying its empty state
- Settings content not rendered

Gesture coordinates come from the tagged chart's visible bounds. Do not use fixed screen coordinates. Generation must fail instead of silently producing a startup-only or incomplete profile when deterministic seeding or navigation breaks.

## Generated Artifacts

Explicit generation saves and checks in:

```text
app/src/release/generated/baselineProfiles/baseline-prof.txt
app/src/release/generated/baselineProfiles/startup-prof.txt
```

The managed-device output is the canonical source of the committed files. Validate connected-device generation before the final managed-device run so the checked-in artifacts always come from `pixel9Api37`.

The implementation must verify that the release-derived APK contains compiled profile assets:

```text
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
```

The compiled Baseline Profile must remain under Android's 1.5 MB limit.

## Measurement

Extend `StartupBenchmark` with two explicit cold-start measurements against `app.readylytics.health.macrobenchmark`:

- `CompilationMode.None()`
- `CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)`

Record both median cold-start results in `benchmark/BASELINE.md`. Report the comparison without enforcing a percentage threshold in this first F14 measurement.

The existing warm/hot startup and frame-timing journeys remain available. F14 does not claim a cold-start win unless the actual benchmark output is recorded.

## Documentation and Cadence

Update `benchmark/README.md` with:

- managed-device generation command
- connected-device generation command and prerequisites
- output paths
- artifact inspection command
- measurement command
- regeneration policy

Regenerate and review both profiles:

1. once per release
2. after performance-critical navigation or chart changes

Generation remains an explicit release-checklist action rather than an `assembleRelease` dependency.

After verification, update F14's implementation status in `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`.

No `internal-docs/DATA_FLOW.md` change is required. This work changes benchmark/profile tooling and benchmark-only seed data; it does not change production Health Connect ingestion, Room schema, scoring coordination, or formulas.

## Verification

Verification includes:

1. Pure JVM tests for deterministic seed construction:
   - exactly 180 summary rows
   - exactly 180 sleep sessions
   - stable IDs and timestamps for a fixed date/zone
   - valid session ordering and durations
   - non-zero load values
2. Existing debug unit suite.
3. Managed-device profile generation on `pixel9Api37`.
4. A connected-device generation smoke run.
5. Presence of both checked-in text profiles.
6. Presence and size validation of compiled profile assets in the release-derived APK.
7. Cold-start Macrobenchmark runs in both explicit compilation modes.
8. Startup result recording in `benchmark/BASELINE.md`.
9. Mandatory repository checks:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
./gradlew lintRelease
```

New files require `codegraph index`. Helper extraction is a structural refactor and requires `codegraph sync`.

## Out of Scope

- Adding or opening Insights
- Adding charts or interactions to Dashboard or Settings
- Changing scoring formulas
- Changing Health Connect ingestion
- Changing the Room schema
- Automatic profile generation during release assembly
- Enforcing a startup-improvement percentage threshold
