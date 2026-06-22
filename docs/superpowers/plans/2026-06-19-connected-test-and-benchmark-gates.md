# Connected Test and Benchmark Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair connected-device functional gates and separate macrobenchmark / battery measurement from normal CI.

**Architecture:** Keep functional connected tests in `connectedDebugAndroidTest` and move benchmark / long-running power measurement into dedicated instrumentation and manual workflows. Preserve app behavior; only test harness, benchmark module, and CI wiring change. Functional device verification stays deterministic and fast enough for release gating.

**Tech Stack:** Kotlin 2.3, Android/AGP 9.2, Compose Material 3, Hilt, Room 2.8, Health Connect 1.1, WorkManager 2.11, AndroidX Test, Macrobenchmark, GitHub Actions.

---

### Task 1: Repair Connected Functional Gate

**Files:**
- Modify: `app/src/androidTest/kotlin/app/readylytics/health/data/repository/SleepSessionRepositoryImplTest.kt`
- Modify: `app/src/androidTest/kotlin/app/readylytics/health/performance/StartupDataStoreTest.kt`
- Delete/move: `app/src/androidTest/kotlin/app/readylytics/health/performance/StartupBenchmark.kt`
- Delete/replace: `app/src/androidTest/kotlin/app/readylytics/health/performance/BatteryTest.kt`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/AndroidManifest.xml`
- Create: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/StartupBenchmark.kt`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Fix only invalid functional test fixtures/signatures**

Change sleep cutoff fixture so old session actually ends before cutoff:

```kotlin
session(id = "old", startTime = t1Start, endTime = t2Start - 1)
```

Give `StartupDataStoreTest.dataStoreReadTime` explicit `Unit` return and braces:

```kotlin
@Test
fun dataStoreReadTime() {
    runBlocking {
        // existing body
        assertTrue(elapsed < 50)
        testFile.delete()
    }
}
```

- [ ] **Step 2: Run focused connected tests**

Run:

```powershell
.\gradlew connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.repository.SleepSessionRepositoryImplTest,app.readylytics.health.performance.StartupDataStoreTest
```

Expected: PASS; no JUnit initialization error and sleep deletion assertion passes.

- [ ] **Step 3: Create dedicated Macrobenchmark module**

Use `com.android.test`, `targetProjectPath = ":app"`, `AndroidBenchmarkRunner`, and a non-debuggable benchmark target variant. Follow official setup: <https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview>.

Required module contract:

```kotlin
android {
    namespace = "app.readylytics.health.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
```

Move cold/warm/hot startup tests unchanged except package. Add `include(":benchmark")`.

- [ ] **Step 4: Replace battery test with non-gating measurement**

Remove five-minute `Thread.sleep` test from `connectedAndroidTest`. Create documented manual/benchmark procedure in `internal-docs/PERFORMANCE_TESTING.md` using Battery Historian/Power Profiler over at least 24 hours with controlled device, screen, sync, and charging state. Do not extrapolate a five-minute charge-counter sample.

- [ ] **Step 5: Separate CI gates**

Keep functional device tests in `connectedDebugAndroidTest`; run Macrobenchmark from dedicated scheduled/manual workflow or managed physical device. CI functional gate must not include multi-minute battery sleeps.

- [ ] **Step 6: Verify full functional suite**

Run: `.\gradlew connectedAndroidTest`

Expected: PASS, 0 failed tests, process exits without manual termination.

- [ ] **Step 7: Verify Macrobenchmark module**

Run the generated benchmark connected task shown by `.\gradlew :benchmark:tasks --all`.

Expected: cold/warm/hot tests execute against non-debuggable app; no `DEBUGGABLE` or `NOT-SELF-INSTRUMENTING` configuration error.

- [ ] **Step 8: Commit**

```powershell
git add settings.gradle.kts gradle/libs.versions.toml app/src/androidTest benchmark .github/workflows/ci.yml internal-docs/PERFORMANCE_TESTING.md
git commit -m "test: repair device and performance release gates"
```
