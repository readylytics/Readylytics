# Phase 0 Benchmark Baseline

> Companion to `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-02.
> Records current-implementation baseline numbers for the three hot paths later phases target, so
> those phases' benchmarks (PERF-001 sweep linker, PERF-002 incremental recompute, HC-001 streamed
> ingest) have something concrete to compare against.

## What's benchmarked

`app/src/androidTest/kotlin/app/readylytics/health/benchmark/ScoringWalkForwardBenchmark.kt`, using
`androidx.benchmark:benchmark-junit4` (in-process microbenchmark, not Macrobenchmark — the
`:benchmark` Gradle module is `com.android.test` black-box UI automation and has no visibility into
internal classes like `ScoringRepositoryImpl` or `SessionLinkReconcilerImpl`):

- `ingestBatchPersist` — a single 5,000-row HR batch upsert (matches production's write-side
  transaction batching, `HealthIngestionStore`).
- `reconcileThirtyDayWindow` — `SessionLinkReconcilerImpl.reconcile(...)` over a 30-day window with
  30 sleep sessions (the target of PERF-001's O(samples × sessions) finding).
- `recomputeSingleDay` — one day's `ScoringRepositoryImpl.computeAndPersistDailySummary(...)` call
  (the target of PERF-002's O(days × window) finding).

`app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/SyntheticDatasetGenerator.kt`
(WP-02a) is the larger-volume dataset generator (~1M HR samples / 30-day dense window by default,
~1,800 sleep sessions, ~1,000 workouts, nightly HRV, ~20% alternate-device rows, sparse 3,650-day
skeleton) intended for the 1M-record stress scenario described in the remediation plan's §7. It
lives in the `app/src/test` JVM/Robolectric source set and is **not** referenced by the
`app/src/androidTest` benchmark file above — those are separate Gradle source sets with no shared
`testFixtures` module in this repo today, so the instrumented benchmark seeds its own smaller,
self-contained dataset inline instead of importing the JVM-side generator.

## Known follow-up before this produces calibrated numbers

The `androidx.benchmark` Gradle plugin (clock-locking, warmup/measurement standardization, JSON
result output) and a dedicated `benchmark` build type are **not** wired into the `app` module as
part of this change — only the `androidx.benchmark:benchmark-junit4` library dependency was added
(`app/build.gradle.kts`, `gradle/libs.versions.toml`). `BenchmarkRule.measureRepeated` still runs
and measures without the plugin, but without it the numbers won't be sustained-performance-mode
calibrated the way `:benchmark`'s existing `StartupBenchmark` results are. Wiring the plugin either
means applying it to `app` (touches the main app build config) or extracting these benchmarks into
their own module — both are judgment calls better made with working Gradle access to verify the
change compiles, which this authoring session did not have (see the branch's commit history for
context). Until that's done, treat numbers from this suite as directionally useful, not
publication-grade.

## How to run (once Gradle/device access is available)

```
./gradlew :app:connectedBenchmarkAndroidTest --tests "*.ScoringWalkForwardBenchmark"
```

Confirm the exact task name once the `benchmark` build type / plugin wiring above lands — AndroidX
benchmark modules typically expose a `connected<BuildType>AndroidTest` task keyed to whichever
build type the plugin is applied to (`benchmark/build.gradle.kts`'s existing `create("benchmark")`
build type is the precedent to mirror if `app` gets its own).

## Recording results

Once a real run produces numbers, append them here as a dated entry:

```
### YYYY-MM-DD — <git commit / branch>
- ingestBatchPersist: <median time>
- reconcileThirtyDayWindow: <median time>
- recomputeSingleDay: <median time>
- Device/emulator profile: <model, API level>
```

No entries yet — this file was authored without Gradle/device access in this environment (see
`internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` Phase 0 for context);
the first real run should populate the entry above before later phases rely on these numbers.
