# Task 9 report: one-million-row v7 benchmark gate

## Scope

- Base commit: `169ee74e0c347b97967ec024d1281f1dc49bc840`
- Added the release-like benchmark Android-test variant wiring.
- Added the deterministic encrypted v6/v7 one-million-row fixture, unchanged DB-001 gate,
  uninterrupted migration measurement, and cancel/resume measurement.
- Kept benchmark code in `app/src/androidTest`; no runtime or unit-test source set uses it.
- The repository already pinned AndroidX Benchmark JUnit 1.4.1 and already declared
  `androidTestImplementation(libs.androidx.benchmark.junit4)`, so no dependency version change was
  necessary.

## Availability and baseline diagnostics

Commands run before implementation:

```text
./gradlew :app:tasks --all
adb devices -l
./gradlew :app:compileDebugAndroidTestKotlin
```

Results:

- `connectedBenchmarkAndroidTest` did not exist before the Task 9 build-type wiring.
- `adb devices -l` returned `List of devices attached` with no device entries.
- Existing, unrelated debug Android-test compilation errors were present in
  `ScoringWalkForwardBenchmark.kt`, `BloodPressureRepositoryImplTest.kt`,
  `BodyFatRepositoryImplTest.kt`, and `WeightRepositoryImplTest.kt`. Task 9 does not repair them.

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
./gradlew :app:connectedBenchmarkAndroidTest \
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

- Passed (`BUILD SUCCESSFUL`).

```text
./gradlew :app:tasks --all
```

- Passed. `connectedBenchmarkAndroidTest` and `compileBenchmarkAndroidTestKotlin` are now present.

```text
./gradlew :app:compileBenchmarkAndroidTestKotlin
```

- Blocked by the same pre-existing Android-test failures established before implementation.
- The Task 9 benchmark file produced no compiler diagnostics.
- Exact unrelated diagnostics: eight errors in `ScoringWalkForwardBenchmark.kt` and one
  `timestampMs` error in each of `BloodPressureRepositoryImplTest.kt`,
  `BodyFatRepositoryImplTest.kt`, and `WeightRepositoryImplTest.kt`.

```text
./gradlew testDebugUnitTest
```

- Passed (`BUILD SUCCESSFUL`, 439 actionable tasks up-to-date).

```text
./gradlew lintRelease
```

- Passed (`BUILD SUCCESSFUL`, 18 executed and 494 up-to-date).

```text
codegraph index
```

- Passed: indexed 896 files; 75 nodes and 359 edges.

```text
./gradlew :app:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigrationBenchmark
```

- Not executed because `adb devices -l` showed no connected device. This no-device condition is
  independent of the unrelated Android-test compilation failures above.
