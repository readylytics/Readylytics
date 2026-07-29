# F14 Baseline and Startup Profile Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Check off each task's checkbox as you complete it.

**Goal:** Generate, check in, package, and measure Readylytics Baseline and Startup Profiles for cold startup and the current chart-bearing top-level tabs.

**Architecture:** Extend the existing black-box `:benchmark` test module as the Baseline Profile producer and apply `androidx.baselineprofile` to `:app` as the consumer. Share benchmark-only app support with the plugin-created `nonMinifiedRelease` build, keep pure deterministic row construction directly JVM-testable, and keep all production data flow and scoring code unchanged.

**Tech Stack:** Kotlin, AGP 9.3.1 new DSL, AndroidX Benchmark/Baseline Profile 1.5.0-alpha07, UiAutomator 2.4.0, JUnit 4, Gradle Managed Devices, Compose semantics, Room.

## Global Constraints

- Implement only the approved design in `docs/superpowers/specs/2026-07-29-f14-baseline-profile-design.md`.
- Use the existing `:benchmark` module. Do not add a `:baselineprofile` module.
- Default generation uses `pixel9Api37`: Pixel 9, API 37, `aosp`.
- Connected generation is selected only by `-Preadylytics.baselineprofile.connected=true`.
- Profile generation must remain explicit. Do not attach it to release assembly.
- The profile app ID is exactly `app.readylytics.health.baselineprofile`.
- Cover Dashboard, Sleep, Vitals, Workouts, and Settings. Omit Insights.
- Interact with Sleep trend, Vitals HRV trend, and Workouts ACWR only. Do not add Dashboard or Settings interactions.
- Add only the approved non-visible `SleepTrendChart` and `AcwrChart` tags. Do not add user-facing UI, strings, or behavior.
- Resolve existing tab, Settings, and empty-state labels from the installed target package by string resource name. Do not assume a connected-device locale.
- Keep benchmark seeding and `testTagsAsResourceId` out of `main` and the production release runtime.
- Seed 180 days. Upsert stable rows independently per table; never delete existing rows.
- Do not change Health Connect ingestion, the Room schema, scoring coordination, or scoring formulas. If implementation appears to require one of those changes, stop and ask the user.
- `internal-docs/DATA_FLOW.md` is intentionally unchanged because F14 is profile tooling plus benchmark-only data.
- Run `codegraph index` after creating files and `codegraph sync` after moving shared benchmark support.
- Keep files at or below 400 lines where practical and below the 800-line hard limit.
- Record measured startup results but enforce no improvement threshold.
- Regeneration cadence is once per release and after performance-critical navigation or chart changes.

## File Responsibility Map

| Path | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | Upgrade Benchmark/Profile tooling and register the Baseline Profile plugin alias. |
| `build.gradle.kts` | Modify | Make the plugin alias available to modules. |
| `app/build.gradle.kts` | Modify | Apply the consumer plugin, configure profile variants/source sets, consume `:benchmark`, and package release profiles in the macrobenchmark APK. |
| `benchmark/build.gradle.kts` | Modify | Apply the producer plugin, register `pixel9Api37`, and select managed or connected generation. |
| `app/src/profileSeed/kotlin/app/readylytics/health/benchmark/BenchmarkSeedDataFactory.kt` | Create | Pure deterministic construction of 180 summary and sleep rows. |
| `app/src/test/kotlin/app/readylytics/health/benchmark/BenchmarkSeedDataFactoryTest.kt` | Create | JVM invariants for the real seed factory. |
| `app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt` | Move and modify | Android/Hilt/DAO orchestration for independent, idempotent table seeding. |
| `app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkSemantics.kt` | Move only | Existing Compose semantics publication for performance builds. |
| `app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt` | Create by move | Shared performance-build seeding orchestration. |
| `app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkSemantics.kt` | Create by move | Shared performance-build semantics publication. |
| `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt` | Create | Package IDs, permissions, bounded waits, navigation, chart reveal, and bounds-based gestures. |
| `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineProfileGenerator.kt` | Create | Isolated startup and runtime Baseline Profile collections. |
| `benchmark/src/main/kotlin/app/readylytics/health/benchmark/ScrollBenchmark.kt` | Modify | Reuse common support without changing measured journeys. |
| `benchmark/src/main/kotlin/app/readylytics/health/benchmark/StartupBenchmark.kt` | Modify | Compare explicit `None` and required Baseline Profile cold starts. |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt` | Modify | Publish `SleepTrendChart` only for rendered chart data. |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/AcwrChart.kt` | Modify | Publish `AcwrChart` only when at least one chart series has data. |
| `app/src/release/generated/baselineProfiles/baseline-prof.txt` | Generate | Canonical managed-device Baseline Profile rules. |
| `app/src/release/generated/baselineProfiles/startup-prof.txt` | Generate | Canonical managed-device Startup Profile rules. |
| `benchmark/README.md` | Modify | Exact generation, inspection, measurement, prerequisites, and cadence. |
| `benchmark/BASELINE.md` | Modify | Actual cold-start medians for both compilation modes. |
| `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` | Modify | Mark F14 landed and update the implementation-order table. |

---

## Task 1: Wire the Baseline Profile producer and consumer

**Interfaces**

- Consumes: existing `:app` release build, existing `:benchmark` `com.android.test` module.
- Produces: `:app:generateReleaseBaselineProfile`, `pixel9Api37`, and the profile app package `app.readylytics.health.baselineprofile`.

**Files**

- Modify: `gradle/libs.versions.toml:38`
- Modify: `gradle/libs.versions.toml:130`
- Modify: `build.gradle.kts:5`
- Modify: `app/build.gradle.kts:53`
- Modify: `app/build.gradle.kts:160`
- Modify: `app/build.gradle.kts:343`
- Modify: `benchmark/build.gradle.kts:1`

- [ ] **Step 1: Prove profile generation is not wired**

Run:

```bash
./gradlew :app:generateReleaseBaselineProfile --dry-run
```

Expected: Gradle fails because `generateReleaseBaselineProfile` does not exist.

- [ ] **Step 2: Register the alpha plugin and compatible test tooling**

In `gradle/libs.versions.toml`, set:

```toml
benchmarkMacro = "1.5.0-alpha07"
uiautomator = "2.4.0"
```

Add under `[plugins]`:

```toml
androidx-baselineprofile = { id = "androidx.baselineprofile", version.ref = "benchmarkMacro" }
```

In the root `build.gradle.kts` plugin block, add:

```kotlin
alias(libs.plugins.androidx.baselineprofile) apply false
```

- [ ] **Step 3: Configure `:app` as the profile consumer**

Add to `app/build.gradle.kts`'s plugin block:

```kotlin
alias(libs.plugins.androidx.baselineprofile)
```

Inside `android { buildTypes { ... } }`, after the existing `benchmark` build type, register configuration for the plugin-created build type:

```kotlin
configureEach {
    if (name == "nonMinifiedRelease") {
        applicationIdSuffix = ".baselineprofile"
        signingConfig = signingConfigs.getByName("debug")
    }
}
```

Inside `android`, add source mappings:

```kotlin
sourceSets {
    getByName("benchmark").apply {
        kotlin.srcDirs("src/profileSupport/kotlin", "src/profileSeed/kotlin")
        baselineProfiles {
            srcDir("src/release/generated/baselineProfiles")
        }
    }
    configureEach {
        when (name) {
            "nonMinifiedRelease" ->
                kotlin.srcDirs("src/profileSupport/kotlin", "src/profileSeed/kotlin")
            "test" -> kotlin.srcDir("src/profileSeed/kotlin")
        }
    }
}
```

The explicit `benchmark` Baseline Profile source mapping is required because the macrobenchmark target is a custom build type, while generated text profiles live in `src/release`.

Add the consumer configuration after `android { ... }`:

```kotlin
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}
```

Add to dependencies beside `implementation(libs.androidx.profileinstaller)`:

```kotlin
baselineProfile(project(":benchmark"))
```

- [ ] **Step 4: Configure `:benchmark` as the producer**

Add to `benchmark/build.gradle.kts`:

```kotlin
import com.android.build.api.dsl.ManagedVirtualDevice
```

Add to its plugin block:

```kotlin
alias(libs.plugins.androidx.baselineprofile)
```

Before `android { ... }`, add:

```kotlin
val useConnectedProfileDevice =
    providers
        .gradleProperty("readylytics.baselineprofile.connected")
        .map(String::toBoolean)
        .orElse(false)
```

Inside `android`, add:

```kotlin
testOptions {
    managedDevices {
        localDevices {
            create("pixel9Api37") {
                device = "Pixel 9"
                apiLevel = 37
                systemImageSource = "aosp"
            }
        }
    }
}
```

After `android { ... }`, select exactly one generation mode:

```kotlin
baselineProfile {
    if (useConnectedProfileDevice.get()) {
        useConnectedDevices = true
    } else {
        managedDevices += "pixel9Api37"
        useConnectedDevices = false
    }
}
```

- [ ] **Step 5: Verify generated Gradle surfaces and package IDs**

Run:

```bash
./gradlew :app:tasks --all | rg 'generateReleaseBaselineProfile'
./gradlew :benchmark:tasks --all | rg 'pixel9Api37|connected.*BaselineProfile'
./gradlew :app:writeNonMinifiedReleaseApplicationId
./gradlew :app:writeBenchmarkApplicationId
rg -n 'app.readylytics.health.baselineprofile' app/build/intermediates/packaged_manifests/nonMinifiedRelease app/build/intermediates/application_id/nonMinifiedRelease
rg -n 'app.readylytics.health.macrobenchmark' app/build/intermediates/application_id/benchmark
```

Expected:

- `generateReleaseBaselineProfile` exists.
- `pixel9Api37` appears in producer tasks.
- non-minified release ID is `app.readylytics.health.baselineprofile`.
- benchmark ID remains `app.readylytics.health.macrobenchmark`.

- [ ] **Step 6: Verify explicit generation is not an assembly dependency**

Run:

```bash
./gradlew :app:assembleRelease --dry-run | rg 'generate.*BaselineProfile'
```

Expected: `rg` returns no match.

- [ ] **Step 7: Commit build wiring**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts benchmark/build.gradle.kts
git commit -m "perf: wire F14 profile generation"
```

---

## Task 2: Build and test deterministic benchmark seed data

**Interfaces**

- Consumes: `LocalDate`, `ZoneId`.
- Produces:

```kotlin
internal const val BENCHMARK_SEED_DAYS: Int

internal data class BenchmarkSeedData(
    val summaries: List<DailySummaryEntity>,
    val sleepSessions: List<SleepSessionEntity>,
)

internal fun buildBenchmarkSeedData(
    today: LocalDate,
    zoneId: ZoneId,
): BenchmarkSeedData
```

**Files**

- Create: `app/src/profileSeed/kotlin/app/readylytics/health/benchmark/BenchmarkSeedDataFactory.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/benchmark/BenchmarkSeedDataFactoryTest.kt`
- Move: `app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt`
- Move: `app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkSemantics.kt`
- Modify after move: `app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt`

- [ ] **Step 1: Write failing JVM invariants for the real factory**

Create `BenchmarkSeedDataFactoryTest.kt`:

```kotlin
package app.readylytics.health.benchmark

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSeedDataFactoryTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `builds exactly 180 stable rows for each table`() {
        val first = buildBenchmarkSeedData(today, zoneId)
        val second = buildBenchmarkSeedData(today, zoneId)

        assertEquals(BENCHMARK_SEED_DAYS, first.summaries.size)
        assertEquals(BENCHMARK_SEED_DAYS, first.sleepSessions.size)
        assertEquals(first, second)
        assertEquals(BENCHMARK_SEED_DAYS, first.summaries.map { it.dateMidnightMs }.toSet().size)
        assertEquals(BENCHMARK_SEED_DAYS, first.sleepSessions.map { it.id }.toSet().size)
    }

    @Test
    fun `summary dates cover today through day 179 with non-zero load`() {
        val rows = buildBenchmarkSeedData(today, zoneId).summaries

        assertEquals(today.atStartOfDay(zoneId).toInstant().toEpochMilli(), rows.first().dateMidnightMs)
        assertEquals(
            today.minusDays(179).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            rows.last().dateMidnightMs,
        )
        assertTrue(rows.all { (it.trimpWorkoutOnly ?: 0f) > 0f })
        assertTrue(rows.all { (it.trimpEverydayHr ?: 0f) > 0f })
    }

    @Test
    fun `sleep rows end on represented dates and contain valid durations`() {
        val sessions = buildBenchmarkSeedData(today, zoneId).sleepSessions

        sessions.forEachIndexed { index, session ->
            val representedDate = today.minusDays(index.toLong())
            val start = java.time.Instant.ofEpochMilli(session.startTime)
            val end = java.time.Instant.ofEpochMilli(session.endTime)

            assertEquals("benchmark-sleep-$representedDate", session.id)
            assertEquals(representedDate, end.atZone(zoneId).toLocalDate())
            assertEquals(session.durationMinutes.toLong(), Duration.between(start, end).toMinutes())
            assertEquals(
                session.durationMinutes,
                session.deepSleepMinutes +
                    session.remSleepMinutes +
                    session.lightSleepMinutes +
                    session.awakeMinutes,
            )
            assertTrue(session.startTime < session.endTime)
            assertTrue(session.awakeMinutes in 0 until session.durationMinutes)
            assertTrue(session.efficiency in 0f..100f)
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm the missing interface**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'app.readylytics.health.benchmark.BenchmarkSeedDataFactoryTest'
```

Expected: Kotlin compilation fails because `buildBenchmarkSeedData` and `BENCHMARK_SEED_DAYS` do not exist.

- [ ] **Step 3: Implement deterministic pure construction**

Create `BenchmarkSeedDataFactory.kt`:

```kotlin
package app.readylytics.health.benchmark

import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

internal const val BENCHMARK_SEED_DAYS = 180
private const val SEED_RANDOM_SEED = 20260202L
private const val BASE_HRV_MS = 45
private const val BASE_RHR_BPM = 58
private const val BASE_SPO2_PERCENT = 96.5f
private const val SLEEP_DURATION_MINUTES = 480

internal data class BenchmarkSeedData(
    val summaries: List<DailySummaryEntity>,
    val sleepSessions: List<SleepSessionEntity>,
)

internal fun buildBenchmarkSeedData(
    today: LocalDate,
    zoneId: ZoneId,
): BenchmarkSeedData {
    val random = Random(SEED_RANDOM_SEED)
    val dates = (0 until BENCHMARK_SEED_DAYS).map { today.minusDays(it.toLong()) }

    val summaries =
        dates.mapIndexed { index, date ->
            val trimp = 60f + (index % 14) * 3f
            DailySummaryEntity(
                dateMidnightMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                nocturnalHrv = (BASE_HRV_MS + random.nextInt(-8, 9)).coerceIn(20, 80),
                restingHeartRate = (BASE_RHR_BPM + random.nextInt(-5, 6)).coerceIn(40, 75),
                avgSleepingSpo2 =
                    (BASE_SPO2_PERCENT + random.nextFloat() * 3f - 1.5f)
                        .coerceIn(90f, 99f),
                hrvMuMssd = BASE_HRV_MS.toFloat(),
                rhrBpm = BASE_RHR_BPM.toFloat(),
                trimpWorkoutOnly = trimp,
                trimpEverydayHr = trimp + 12f,
                isCalibrating = false,
            )
        }

    val sleepSessions =
        dates.map { date ->
            val end = date.atTime(7, 0).atZone(zoneId).toInstant()
            val start = end.minus(SLEEP_DURATION_MINUTES.toLong(), ChronoUnit.MINUTES)
            SleepSessionEntity(
                id = "benchmark-sleep-$date",
                startTime = start.toEpochMilli(),
                endTime = end.toEpochMilli(),
                durationMinutes = SLEEP_DURATION_MINUTES,
                efficiency = 95.8f,
                deepSleepMinutes = 100,
                remSleepMinutes = 100,
                lightSleepMinutes = 260,
                awakeMinutes = 20,
                startZoneOffsetSeconds = start.atZone(zoneId).offset.totalSeconds,
                endZoneOffsetSeconds = end.atZone(zoneId).offset.totalSeconds,
                deviceName = "Readylytics benchmark seed",
            )
        }

    return BenchmarkSeedData(summaries = summaries, sleepSessions = sleepSessions)
}
```

- [ ] **Step 4: Move Android-only support into the shared performance source**

Move with `apply_patch`:

```text
app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt
→ app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt

app/src/benchmark/kotlin/app/readylytics/health/benchmark/BenchmarkSemantics.kt
→ app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkSemantics.kt
```

Do not move `app/src/benchmark/kotlin/app/readylytics/health/data/migration/V7DatabaseBenchmarkDriver.kt`; it remains specific to the existing macrobenchmark build.

- [ ] **Step 5: Make seeding independently recoverable**

Replace `BenchmarkSeedEntryPoint` and `seedIfNeeded` in the moved seeder with:

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BenchmarkSeedEntryPoint {
    fun dailySummaryDao(): DailySummaryDao

    fun sleepSessionDao(): SleepSessionDao
}

internal object BenchmarkDataSeeder {
    suspend fun seedIfNeeded(context: Context) {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context,
                BenchmarkSeedEntryPoint::class.java,
            )
        val zoneId = ZoneId.systemDefault()
        val data = buildBenchmarkSeedData(LocalDate.now(zoneId), zoneId)

        if (entryPoint.dailySummaryDao().count() < BENCHMARK_SEED_DAYS) {
            entryPoint.dailySummaryDao().upsertAll(data.summaries)
        }
        if (entryPoint.sleepSessionDao().count() < BENCHMARK_SEED_DAYS) {
            entryPoint.sleepSessionDao().upsertAll(data.sleepSessions)
        }
    }
}
```

Add the required `SleepSessionDao` import and remove `Random`, entity-construction constants, and the old `buildSeedRows`.

- [ ] **Step 6: Run focused and variant compilation checks**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'app.readylytics.health.benchmark.BenchmarkSeedDataFactoryTest'
./gradlew :app:compileBenchmarkKotlin
./gradlew :app:compileNonMinifiedReleaseKotlin
```

Expected: all three commands pass.

- [ ] **Step 7: Refresh the code index and commit**

Run:

```bash
codegraph index
codegraph sync
git add app/build.gradle.kts app/src/profileSeed app/src/profileSupport app/src/benchmark app/src/test/kotlin/app/readylytics/health/benchmark/BenchmarkSeedDataFactoryTest.kt
git commit -m "perf: seed F14 profile journeys"
```

---

## Task 3: Extract bounded black-box journey support

**Interfaces**

- Consumes: `MacrobenchmarkScope`, a target package ID, existing English tab/range labels, and Compose resource IDs.
- Produces:

```kotlin
internal const val MACROBENCHMARK_PACKAGE_NAME: String
internal const val BASELINE_PROFILE_PACKAGE_NAME: String

internal fun grantHealthConnectPermissions(packageName: String)
internal fun MacrobenchmarkScope.waitForDashboard()
internal fun appString(packageName: String, resourceName: String): String
internal fun MacrobenchmarkScope.navigateToTab(label: String)
internal fun MacrobenchmarkScope.selectThirtyDayRange()
internal fun MacrobenchmarkScope.revealChart(tag: String)
internal fun MacrobenchmarkScope.waitForNonEmptyChart(tag: String, packageName: String)
internal fun MacrobenchmarkScope.panAndZoomChart(tag: String)
internal fun MacrobenchmarkScope.waitForSettingsContent(packageName: String)
```

**Files**

- Create: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt`
- Modify: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/ScrollBenchmark.kt`

- [ ] **Step 1: Add common support and make existing benchmark imports fail**

Create `BenchmarkTestSupport.kt` with:

```kotlin
package app.readylytics.health.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val MACROBENCHMARK_PACKAGE_NAME = "app.readylytics.health.macrobenchmark"
internal const val BASELINE_PROFILE_PACKAGE_NAME = "app.readylytics.health.baselineprofile"
internal const val DASHBOARD_ROOT_TAG = "dashboard_lazy_column"
internal const val SLEEP_CHART_TAG = "SleepTrendChart"
internal const val HRV_CHART_TAG = "HrvTrendChart"
internal const val ACWR_CHART_TAG = "AcwrChart"
internal const val THIRTY_DAY_RANGE_LABEL = "30D"

private const val WAIT_TIMEOUT_MS = 15_000L
private const val MAX_VERTICAL_SCROLLS = 8

private val requiredHealthConnectPermissions =
    listOf(
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_HEART_RATE_VARIABILITY",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HEALTH_DATA_HISTORY",
    )

internal fun grantHealthConnectPermissions(packageName: String) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    requiredHealthConnectPermissions.forEach { permission ->
        runCatching { uiAutomation.grantRuntimePermission(packageName, permission) }
            .getOrElse { cause ->
                error("Failed to grant $permission to $packageName: ${cause.message}")
            }
    }
}

internal fun appString(
    packageName: String,
    resourceName: String,
): String {
    val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
    val packageContext =
        runCatching { instrumentationContext.createPackageContext(packageName, 0) }
            .getOrElse { cause ->
                error("Cannot load resources for $packageName: ${cause.message}")
            }
    val resourceId =
        packageContext.resources.getIdentifier(resourceName, "string", packageName)
    check(resourceId != 0) { "String resource $resourceName not found in $packageName" }
    return packageContext.getString(resourceId)
}

private fun MacrobenchmarkScope.waitForObject(
    selector: BySelector,
    failureMessage: String,
): UiObject2 {
    check(device.wait(Until.hasObject(selector), WAIT_TIMEOUT_MS)) { failureMessage }
    return device.findObject(selector) ?: error(failureMessage)
}

internal fun MacrobenchmarkScope.waitForDashboard() {
    waitForObject(By.res(DASHBOARD_ROOT_TAG), "Dashboard content did not render")
}

internal fun MacrobenchmarkScope.navigateToTab(label: String) {
    waitForObject(By.text(label), "$label tab not found").click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.selectThirtyDayRange() {
    waitForObject(
        By.text(THIRTY_DAY_RANGE_LABEL),
        "$THIRTY_DAY_RANGE_LABEL selector not found",
    ).click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.revealChart(tag: String) {
    repeat(MAX_VERTICAL_SCROLLS) {
        if (device.hasObject(By.res(tag))) return
        val scrollable =
            device.findObject(By.scrollable(true))
                ?: error("Scrollable container not found while revealing $tag")
        scrollable.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
    }
    error("$tag not found after $MAX_VERTICAL_SCROLLS vertical scroll attempts")
}

internal fun MacrobenchmarkScope.waitForNonEmptyChart(
    tag: String,
    packageName: String,
) {
    waitForObject(By.res(tag), "$tag chart not found")
    val noDataText = appString(packageName, "message_no_data_available")
    val emptyChart = By.res(tag).hasDescendant(By.text(noDataText))
    check(device.wait(Until.gone(emptyChart), WAIT_TIMEOUT_MS)) {
        "$tag still displays its empty state; benchmark seeding may have failed"
    }
}

internal fun MacrobenchmarkScope.panAndZoomChart(tag: String) {
    val chart = waitForObject(By.res(tag), "$tag chart not found for gestures")
    val bounds = chart.visibleBounds
    check(bounds.width() > 0 && bounds.height() > 0) { "$tag has empty visible bounds" }
    val inset = (bounds.width() / 8).coerceAtLeast(1)
    val centerY = bounds.centerY()

    device.swipe(bounds.right - inset, centerY, bounds.left + inset, centerY, 20)
    device.waitForIdle()
    device.swipe(bounds.left + inset, centerY, bounds.right - inset, centerY, 20)
    device.waitForIdle()
    chart.pinchOpen(0.8f, 200)
    device.waitForIdle()
    chart.pinchClose(0.8f, 200)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.waitForSettingsContent(packageName: String) {
    val dataAndBackup = appString(packageName, "settings_section_data_backup")
    waitForObject(By.text(dataAndBackup), "Settings content did not render")
}
```

Change one `ScrollBenchmark` reference from its private package constant to `MACROBENCHMARK_PACKAGE_NAME` before deleting the old private constant.

- [ ] **Step 2: Confirm duplicate private declarations prevent a clean extraction**

Run:

```bash
./gradlew :benchmark:assembleBenchmark
```

Expected: Kotlin fails because common and private support names collide or remain inconsistent.

- [ ] **Step 3: Complete the existing benchmark refactor**

In `ScrollBenchmark.kt`:

- delete private `PACKAGE_NAME`, `WAIT_TIMEOUT_MS`, `HRV_CHART_TAG`, `THIRTY_DAY_RANGE_LABEL`, `NO_DATA_TEXT`, `REQUIRED_HEALTH_CONNECT_PERMISSIONS`, `hrvChartShowingNoData`, and `navigateToVitals`;
- replace `PACKAGE_NAME` with `MACROBENCHMARK_PACKAGE_NAME`;
- replace the `@Before` body with:

```kotlin
grantHealthConnectPermissions(MACROBENCHMARK_PACKAGE_NAME)
```

- replace each Vitals navigation setup with:

```kotlin
navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_vitals"))
waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
```

- replace the range click with:

```kotlin
selectThirtyDayRange()
waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
```

- replace the chart gesture body with:

```kotlin
panAndZoomChart(HRV_CHART_TAG)
```

- replace hardcoded Vitals and Dashboard selectors in `dashboardVitalsTabSwitch` with:

```kotlin
navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_vitals"))
waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_dashboard"))
waitForDashboard()
```

Retain the existing iteration counts and the exact fling/tab-switch measurement loops.

- [ ] **Step 4: Verify the refactor**

Run:

```bash
./gradlew :benchmark:assembleBenchmark
```

Expected: build passes.

- [ ] **Step 5: Index and commit**

```bash
codegraph index
codegraph sync
git add benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt benchmark/src/main/kotlin/app/readylytics/health/benchmark/ScrollBenchmark.kt
git commit -m "refactor: share performance journeys"
```

---

## Task 4: Add the complete Baseline Profile collections

**Interfaces**

- Consumes: `BaselineProfileRule`, `BASELINE_PROFILE_PACKAGE_NAME`, common journey helpers.
- Produces:

```kotlin
@Test fun startup()
@Test fun criticalUserJourneys()
```

with startup-only rules isolated by `includeInStartupProfile`.

**Files**

- Create: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineProfileGenerator.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt:428`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/AcwrChart.kt:334`

- [ ] **Step 1: Write the generator before adding its missing selectors**

Create `BaselineProfileGenerator.kt`:

```kotlin
package app.readylytics.health.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun grantPermissions() {
        grantHealthConnectPermissions(BASELINE_PROFILE_PACKAGE_NAME)
    }

    @Test
    fun startup() =
        baselineProfileRule.collect(
            packageName = BASELINE_PROFILE_PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForDashboard()
        }

    @Test
    fun criticalUserJourneys() =
        baselineProfileRule.collect(
            packageName = BASELINE_PROFILE_PACKAGE_NAME,
            includeInStartupProfile = false,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForDashboard()

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_sleep"))
            selectThirtyDayRange()
            revealChart(SLEEP_CHART_TAG)
            waitForNonEmptyChart(SLEEP_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(SLEEP_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_vitals"))
            selectThirtyDayRange()
            waitForNonEmptyChart(HRV_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(HRV_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_workouts"))
            selectThirtyDayRange()
            revealChart(ACWR_CHART_TAG)
            waitForNonEmptyChart(ACWR_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(ACWR_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_settings"))
            waitForSettingsContent(BASELINE_PROFILE_PACKAGE_NAME)
        }
}
```

- [ ] **Step 2: Compile the generator**

Run:

```bash
./gradlew :benchmark:assembleNonMinifiedRelease
```

Expected: generator compiles.

- [ ] **Step 3: Run managed generation and confirm the missing Sleep tag**

Run:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Expected: managed-device generation fails with `SleepTrendChart not found after 8 vertical scroll attempts`.

- [ ] **Step 4: Tag only the rendered Sleep chart**

In `SleepTrendChart.kt`, import:

```kotlin
import androidx.compose.ui.platform.testTag
```

Change only the `hasData` branch's outer box:

```kotlin
Box(
    modifier =
        modifier
            .fillMaxWidth()
            .testTag("SleepTrendChart"),
) {
```

Leave the empty-state box untagged.

- [ ] **Step 5: Tag ACWR only when a series has data**

In `AcwrChart.kt`, import:

```kotlin
import androidx.compose.ui.platform.testTag
```

Before the chart host box, derive:

```kotlin
val hasData =
    remember(trimpPoints, ratioPoints) {
        trimpPoints.any { it.value != null } || ratioPoints.any { it.value != null }
    }
val chartModifier =
    if (hasData) {
        modifier.testTag("AcwrChart")
    } else {
        modifier
    }
```

Change the outer host to:

```kotlin
Box(modifier = chartModifier.fillMaxWidth()) {
```

Do not alter Vico layers, sizing, colors, gestures, or empty behavior.

- [ ] **Step 6: Generate both profiles on the managed device**

Run:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Expected:

- journey completes;
- `app/src/release/generated/baselineProfiles/baseline-prof.txt` exists and is non-empty;
- `app/src/release/generated/baselineProfiles/startup-prof.txt` exists and is non-empty.

Verify:

```bash
test -s app/src/release/generated/baselineProfiles/baseline-prof.txt
test -s app/src/release/generated/baselineProfiles/startup-prof.txt
wc -l app/src/release/generated/baselineProfiles/baseline-prof.txt app/src/release/generated/baselineProfiles/startup-prof.txt
```

- [ ] **Step 7: Commit generator, selectors, and initial managed artifacts**

```bash
codegraph index
git add benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineProfileGenerator.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/AcwrChart.kt app/src/release/generated/baselineProfiles
git commit -m "perf: generate F14 baseline profiles"
```

---

## Task 5: Measure both cold-start compilation modes

**Interfaces**

- Consumes: `app.readylytics.health.macrobenchmark`, packaged Baseline Profile assets.
- Produces: independent cold-start benchmark methods for no compilation and required Baseline Profile compilation.

**Files**

- Modify: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/StartupBenchmark.kt`

- [ ] **Step 1: Write explicit benchmark methods against a missing helper**

Replace `coldStart()` with:

```kotlin
@Test
fun coldStartCompilationNone() {
    measureColdStart(CompilationMode.None())
}

@Test
fun coldStartCompilationBaselineProfile() {
    measureColdStart(
        CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
    )
}
```

Do not add imports or `measureColdStart` yet.

- [ ] **Step 2: Confirm the new API references fail**

Run:

```bash
./gradlew :benchmark:assembleBenchmark
```

Expected: Kotlin fails on unresolved `CompilationMode`, `BaselineProfileMode`, and `measureColdStart`.

- [ ] **Step 3: Implement the shared cold-start measurement**

Add imports:

```kotlin
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
```

Add inside `StartupBenchmark`:

```kotlin
private fun measureColdStart(compilationMode: CompilationMode) {
    benchmarkRule.measureRepeated(
        packageName = MACROBENCHMARK_PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )
}
```

Replace the remaining hardcoded package IDs in warm and hot tests with `MACROBENCHMARK_PACKAGE_NAME`. Keep warm/hot startup behavior and iteration counts unchanged.

- [ ] **Step 4: Verify benchmark compilation**

Run:

```bash
./gradlew :benchmark:assembleBenchmark
```

Expected: build passes.

- [ ] **Step 5: Commit measurement code**

```bash
git add benchmark/src/main/kotlin/app/readylytics/health/benchmark/StartupBenchmark.kt
git commit -m "perf: compare F14 startup modes"
```

---

## Task 6: Validate connected generation, then regenerate canonical managed artifacts

**Interfaces**

- Consumes: one connected rooted or API 33+ device with Health Connect; `pixel9Api37`.
- Produces: connected-mode smoke evidence followed by canonical GMD-generated checked-in profiles.

**Files**

- Regenerate: `app/src/release/generated/baselineProfiles/baseline-prof.txt`
- Regenerate: `app/src/release/generated/baselineProfiles/startup-prof.txt`

- [ ] **Step 1: Resolve and inspect the connected target**

Run:

```bash
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell service check healthconnect
```

Expected:

- exactly one intended device is in `device` state;
- API level is 33 or newer, or the device is rooted;
- Health Connect service is available.

If more than one device is connected, select the intended device explicitly through `ANDROID_SERIAL` before continuing; do not let Gradle choose.

- [ ] **Step 2: Run connected profile generation**

Run:

```bash
./gradlew :app:generateReleaseBaselineProfile -Preadylytics.baselineprofile.connected=true
```

Expected: both startup and runtime collections complete on the connected device without using `pixel9Api37`.

- [ ] **Step 3: Regenerate canonical profiles with the managed device**

Run without the connected property:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Expected: generation runs on `pixel9Api37`; these outputs become canonical.

- [ ] **Step 4: Verify text artifacts**

Run:

```bash
test -s app/src/release/generated/baselineProfiles/baseline-prof.txt
test -s app/src/release/generated/baselineProfiles/startup-prof.txt
git diff --check
```

Expected: both files are non-empty and diff validation passes.

- [ ] **Step 5: Commit canonical regeneration if it changed tracked output**

Run:

```bash
git status --short app/src/release/generated/baselineProfiles
```

If tracked output changed, commit exactly those two generated files:

```bash
git add app/src/release/generated/baselineProfiles/baseline-prof.txt app/src/release/generated/baselineProfiles/startup-prof.txt
git commit -m "perf: refresh canonical F14 profiles"
```

---

## Task 7: Package, measure, document, and close F14

**Interfaces**

- Consumes: canonical text profiles, macrobenchmark cold-start JSON, current F14 plan entry.
- Produces: compiled APK profile assets below 1.5 MB, recorded medians, operating instructions, and landed F14 status.

**Files**

- Modify: `benchmark/README.md`
- Modify: `benchmark/BASELINE.md`
- Modify: `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md:4`
- Modify: `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md:635`
- Modify: `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md:767`

- [ ] **Step 1: Build and inspect the macrobenchmark APK**

Run:

```bash
./gradlew :app:assembleBenchmark
unzip -l app/build/outputs/apk/benchmark/app-benchmark.apk | rg 'assets/dexopt/baseline\.prof$|assets/dexopt/baseline\.profm$'
unzip -p app/build/outputs/apk/benchmark/app-benchmark.apk assets/dexopt/baseline.prof | wc -c
```

Expected:

- both `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm` are present;
- `baseline.prof` byte count is greater than zero and less than `1572864`.

- [ ] **Step 2: Run both startup measurements on the connected device**

Run:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.StartupBenchmark
```

Expected:

- `coldStartCompilationNone` completes;
- `coldStartCompilationBaselineProfile` completes with `BaselineProfileMode.Require`;
- benchmark JSON is written under `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/`.

- [ ] **Step 3: Extract exact medians from generated JSON**

Locate results:

```bash
rg -l '"coldStartCompilation(None|BaselineProfile)"' benchmark/build/outputs/connected_android_test_additional_output
```

For each matching JSON file, read the `timeToInitialDisplayMs` median and retain:

- method name;
- device model and API;
- iteration count;
- median milliseconds;
- run date.

Do not calculate or publish an improvement claim if either mode is missing.

- [ ] **Step 4: Record actual startup evidence**

In `benchmark/BASELINE.md`, add an `F14 cold-start compilation comparison` section with columns for compilation mode and median `timeToInitialDisplayMs`. Add one row for `CompilationMode.None()` and one for `CompilationMode.Partial(BaselineProfileMode.Require)`, copying each measured number exactly from benchmark JSON. State the device/API, date, iteration count, JSON result path, and that F14 currently has no enforced threshold. Report whether the Baseline Profile median is lower, equal, or higher strictly from those two recorded numbers.

- [ ] **Step 5: Document exact operation and cadence**

Add to `benchmark/README.md`:

````markdown
## Baseline and Startup Profiles

Canonical generation uses the Gradle-managed Pixel 9 API 37 AOSP device:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Connected generation requires one selected rooted device or API 33+ device with Health Connect:

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
  | rg 'assets/dexopt/baseline\.prof$|assets/dexopt/baseline\.profm$'
unzip -p app/build/outputs/apk/benchmark/app-benchmark.apk \
  assets/dexopt/baseline.prof | wc -c
```

Measure startup compilation modes with:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.StartupBenchmark
```

Regenerate and review both profiles once per release and after performance-critical navigation or chart changes. Generation is explicit and is not part of normal release assembly.
````

Also replace the existing `Deterministic data` section so it states:

- Android/Hilt seeding and performance semantics are shared from `app/src/profileSupport/kotlin`;
- pure row construction is shared from `app/src/profileSeed/kotlin`;
- the benchmark and non-minified profile builds receive both directories;
- production release receives neither directory;
- summaries and sleep sessions are checked and upserted independently;
- 180 days cover 7D, 30D, and 180D ranges;
- Startup Benchmark setup does not include seeding time.

- [ ] **Step 6: Run focused and mandatory repository verification**

Run in this order:

```bash
./gradlew :app:testDebugUnitTest --tests 'app.readylytics.health.benchmark.BenchmarkSeedDataFactoryTest'
./gradlew :benchmark:assembleBenchmark
./gradlew ktlintFormat
./gradlew testDebugUnitTest
./gradlew lintRelease
git diff --check
```

Expected: every command passes. Review formatter changes before staging; preserve unrelated user changes.

- [ ] **Step 7: Mark F14 implemented using the exact implementation commit**

Resolve the generator/artifact implementation commit:

```bash
git log --oneline -- app/src/release/generated/baselineProfiles/baseline-prof.txt | head -1
```

Use that exact short SHA to:

- move F14 from `Not yet implemented` to `Landed` at the document top;
- add an `Implemented` line containing that exact short SHA immediately below the F14 heading;
- change implementation-order row 16 from open to complete, including the same SHA;
- keep the no-threshold measurement result and regeneration cadence in the F14 notes.

- [ ] **Step 8: Refresh indexing and commit documentation/status**

Run:

```bash
codegraph index
codegraph sync
git add benchmark/README.md benchmark/BASELINE.md internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md
git commit -m "docs: record F14 profile results"
```

- [ ] **Step 9: Perform final clean-tree evidence check**

Run:

```bash
git status --short
git log -7 --oneline
```

Expected: no F14 work remains unstaged or uncommitted. Any unrelated pre-existing user changes remain untouched and are reported separately.

---

## Requirement Coverage

| Approved requirement | Implemented by |
|---|---|
| Extend `:benchmark`; no new module | Task 1 |
| Alpha AGP 9-compatible tooling | Task 1 |
| Pixel 9/API 37/AOSP managed default | Task 1 |
| Explicit connected alternative | Tasks 1 and 6 |
| Exact `.baselineprofile` package isolation | Task 1 |
| Benchmark support excluded from production release | Tasks 1 and 2 |
| 180-day deterministic Vitals/Workout/Sleep data | Task 2 |
| Independent idempotent upserts; no deletion | Task 2 |
| Bounded navigation/content failures and bounds gestures | Task 3 |
| Separate startup and runtime collections | Task 4 |
| Dashboard and all current tabs; Insights omitted | Task 4 |
| Sleep, HRV, and ACWR pan/zoom | Task 4 |
| Only approved non-visible chart tags | Task 4 |
| Checked-in Baseline and Startup text profiles | Tasks 4 and 6 |
| Managed output canonical after connected smoke | Task 6 |
| Explicit None versus required profile measurement | Tasks 5 and 7 |
| Report only; no threshold | Task 7 |
| Packaged assets and 1.5 MB validation | Task 7 |
| Commands, outputs, prerequisites, and both cadence triggers | Task 7 |
| F14 status update and repository verification | Task 7 |
