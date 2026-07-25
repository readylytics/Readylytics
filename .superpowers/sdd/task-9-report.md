# Task 9 report: one-million-row v7 benchmark gate

## Scope

- Base commit: `169ee74e0c347b97967ec024d1281f1dc49bc840`
- Added the isolated release-like `:database-benchmark` Android test module. It targets
  `:app`'s existing `benchmark` build type without changing the app-wide Android-test build type,
  so normal `:app` debug instrumentation remains available. The test module filters only its own
  automatically created debug variant through the Android Components variant API.
- Added a benchmark-build-only public bridge to the internal production v7 migrator. The bridge is
  absent from debug and release runtime artifacts.
- Added deterministic encrypted v6/v7 one-million-row templates, the unchanged DB-001 gate,
  production space-preflight and peak-disk measurements, uninterrupted migration timing, and
  cancel/resume timing.
- The repository already pinned AndroidX Benchmark JUnit 1.4.1 and already declared
  `androidTestImplementation(libs.androidx.benchmark.junit4)`. The isolated module reuses that
  version; no dependency version change was necessary.

## Availability and baseline diagnostics

Commands run before implementation:

```text
./gradlew :app:tasks --all
adb devices -l
./gradlew :app:compileDebugAndroidTestKotlin
```

Results:

- `:app:connectedBenchmarkAndroidTest` did not exist before the original Task 9 build-type wiring.
- `adb devices -l` returned `List of devices attached` with no device entries.
- Existing, unrelated debug Android-test compilation errors were present in
  `ScoringWalkForwardBenchmark.kt`, `BloodPressureRepositoryImplTest.kt`,
  `BodyFatRepositoryImplTest.kt`, and `WeightRepositoryImplTest.kt`. Task 9 does not repair them.

The review correction removes the global `android.testBuildType = "benchmark"` override. The
database benchmark now has its own `:database-benchmark:connectedBenchmarkAndroidTest` task, while
`:app:connectedDebugAndroidTest` remains the app's normal instrumentation entry point.

## Measurement methodology

- Build immutable encrypted v6 and v7 templates with 1,000,000 heart-rate rows and representative
  five-minute HRV history. Seed in 5,000-row transactions and checkpoint/truncate WAL.
- Capture each template's database-file size before any timed ingest.
- Discard two warm-up pairs in AB then BA order.
- Measure eight fresh-clone pairs, alternating AB and BA order. Each sample times only one
  5,000-row insert transaction; template copying, database opening, schema-version lookup on the
  already-open connection, and cleanup are outside the timed interval. The acceptance calculation
  uses each schema's median rows/second.
- Keep separate AndroidX `BenchmarkRule` diagnostics with two warmups and eight measurements per
  schema. These diagnostics do not decide the cross-schema DB-001 gate.
- Exercise the production migrator's fail-closed space preflight to record `requiredBytes`, then
  run the actual migration with real `StatFs.availableBytes`.
- Sample the database, WAL, and SHM footprint concurrently every 10 ms throughout migration and
  report absolute peak bytes and additional peak bytes.
- Cancel after the first durable 10,000-row heart-rate copy checkpoint, verify the checkpoint, then
  time the production resume path and verify final row counts.

## Benchmark evidence

No DB-001 measurements are recorded. A compatible device was not connected, so the exact benchmark
command could not execute. In particular, this report makes no claim about:

- v6 or v7 database bytes;
- v6 or v7 5,000-row ingest throughput;
- throughput gain or size reduction;
- full migration duration or peak disk footprint;
- resume duration or result; or
- whether the DB-001 acceptance gate passed.

Required device command:

```text
./gradlew :database-benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigrationBenchmark
```

The benchmark itself asserts the unchanged decision rule:

```text
throughputGain >= 0.30 || sizeReduction >= 0.25
```

## Verification

Commands and outcomes:

```text
./gradlew ktlintFormat
```

- Passed on the final formatted source (`BUILD SUCCESSFUL`).

```text
./gradlew :database-benchmark:compileBenchmarkKotlin
```

- Passed (`BUILD SUCCESSFUL`). This proves the isolated test module can compile against the
  benchmark-only app bridge and production database model. In `measureFreshIngest`, the
  reviewer-inspectable order is now: open the fixture once, query `user_version` on that connection,
  start the timer, and insert; the removed `Fixture.version()` helper can no longer open a nested
  encrypted connection.

```text
./gradlew :database-benchmark:tasks --all
./gradlew :database-benchmark:compileDebugKotlin --dry-run
```

- The task listing contains `compileBenchmarkKotlin` but no `compileDebugKotlin`. Selecting the
  exact debug compile task fails at task selection with `task 'compileDebugKotlin' not found`,
  proving the module's automatic debug variant is absent rather than compilation-broken.

```text
./gradlew :database-benchmark:build
```

- Passed (`BUILD SUCCESSFUL`) with only the benchmark Android variant enabled.

```text
./gradlew :database-benchmark:assembleBenchmark
```

- Passed (`BUILD SUCCESSFUL`). This includes R8 shrinking for both the benchmark target and test
  APK, tested-app obfuscation compatibility, schema-asset packaging, and APK packaging.

```text
./gradlew :app:compileDebugAndroidTestKotlin
```

- Failed only with the same pre-existing diagnostics established before Task 9: eight errors in
  `ScoringWalkForwardBenchmark.kt` and one `timestampMs` error in each of
  `BloodPressureRepositoryImplTest.kt`, `BodyFatRepositoryImplTest.kt`, and
  `WeightRepositoryImplTest.kt`. The restored task targets the debug variant, and no Task 9 file
  appears in its diagnostics.

```text
./gradlew :app:testDebugUnitTest
```

- Passed (`BUILD SUCCESSFUL`), proving the app debug unit-test variant remains intact.

```text
./gradlew testDebugUnitTest
```

- Passed (`BUILD SUCCESSFUL`). A first run caught the benchmark sampler's hardcoded
  `Dispatchers.IO` through the repository-wide `CleanArchTest`; the dispatcher was moved into the
  isolated module's DI package, the focused architecture test passed, and then the full suite
  passed.

```text
./gradlew lintRelease
```

- Passed (`BUILD SUCCESSFUL`).

```text
codegraph sync
codegraph index
```

- `codegraph sync` passed: seven files added, two modified, and one removed.
- The final post-commit `codegraph index` exited successfully after indexing 902 files with no
  unread path.

```text
./gradlew :database-benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigrationBenchmark
```

- Not executed because `adb devices -l` showed no connected device. DB-001 remains Pending.
