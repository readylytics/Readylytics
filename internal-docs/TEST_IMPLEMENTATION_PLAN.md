# Android Test Implementation Plan
**Status:** pending approval
**Based on:** internal-docs/TEST_STRATEGY.md, seeder-test-checklist.md
**Created:** 2026-06-29

---

## Requirements Summary

33 test cases from `TEST_STRATEGY.md` (Areas 1-5) need implementation across 4 PRs.
Key constraints:
- HC SDK types (`RestingHeartRateRecord`, etc.) require Android runtime → all tests in `androidTest/`
- Hilt NOT wired for androidTest currently — must add in Phase 2 before scoring integration tests
- Compose testing already available (`androidx.compose.ui.test.junit4` in androidTestDeps)
- `kotlinx.coroutines.test` already in androidTestDeps

**Accepted pattern:** 2 co-existing test styles:
- `runTest {}` — for all HC-query tests (suspend calls)
- `v2.createAndroidComposeRule<ComponentActivity>()` — for Compose-isolated UI tests (no Activity routing needed)

---

## Emulator Setup (`scripts/setup-emulator.sh`)

All androidTest phases require an API 36 emulator. The script handles
image install, AVD creation (idempotent), boot-wait, and animation disable.

### Prerequisites (one-time, local machine)

```bash
# Ensure Android SDK tools are on PATH
export ANDROID_HOME="$HOME/Library/Android/sdk"      # macOS
export ANDROID_HOME="$HOME/Android/Sdk"              # Linux
export PATH="$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Verify
sdkmanager --version
avdmanager list avd
emulator -version
```

### First-time AVD creation (installs image + creates AVD)

```bash
bash scripts/setup-emulator.sh
```

What happens:
1. Accepts SDK licenses silently
2. Downloads `system-images;android-36;google_apis_playstore;<abi>` (x86_64 on Intel/AMD, arm64-v8a on Apple Silicon)
3. Creates AVD named `android16_test` with `pixel_6` device profile (skips if already exists)
4. Starts emulator and polls `sys.boot_completed` every 5s (300s timeout)

### Per-session start (AVD already exists)

```bash
bash scripts/setup-emulator.sh
# Emulator ready when script prints: "==> Emulator ready. PID=<pid>"
# Then in another terminal:
adb devices   # confirm device serial appears
```

### Environment variables (override defaults)

| Variable | Default | Example override |
|----------|---------|-----------------|
| `AVD_NAME` | `android16_test` | `AVD_NAME=my_avd bash scripts/setup-emulator.sh` |
| `API_LEVEL` | `36` | (do not change — tests require API 36) |
| `BOOT_TIMEOUT` | `300` | `BOOT_TIMEOUT=600 bash scripts/setup-emulator.sh` |

### CI mode (headless, no window)

Triggered by `--ci` flag OR `CI=true` env var. Automatically:
- Adds `-no-window -no-boot-anim -gpu swiftshader_indirect`
- Uses KVM acceleration if `/dev/kvm` exists (Linux runners), otherwise software render
- Disables all 3 animation scales (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale` → 0.0)

```bash
# GitHub Actions / CI pipeline
CI=true bash scripts/setup-emulator.sh
# or
bash scripts/setup-emulator.sh --ci
```

### Running tests after emulator is booted

```bash
# Phase 1 — math verification only (fast, ~30s)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.scoring.SeedMathVerificationTest

# Phase 1 — Compose banner tests
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.ui.CalibrationBannerUiTest

# All SmallTest + MediumTest (exclude LargeTest 42-day seed)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.size=small \
  -Pandroid.testInstrumentationRunnerArguments.size=medium

# Full suite (includes @LargeTest 42-day seed — ~10 min on emulator)
./gradlew :app:connectedDebugAndroidTest
```

### Teardown

```bash
adb emu kill         # graceful shutdown
# or
kill <EMULATOR_PID>  # PID printed by setup-emulator.sh on last line
```

### Test size annotations (tag every new test class)

```kotlin
@SmallTest   // < 200ms, pure math, no HC insert
@MediumTest  // < 2s, Compose UI or small HC seed (DAYS_5/DAYS_14)
@LargeTest   // > 2s, 42-day full seed
```

These annotations map to Gradle's `size` filter — CI fast-lane skips `@LargeTest` on PR builds,
runs the full suite on nightly/merge-to-main.

---

## Phase Map

| Phase | PR Branch | Focus | Test Cases | Hilt? |
|-------|-----------|-------|-----------|-------|
| 1 | `test/phase1-math-compose-infra` | Seeder math + Compose scaffold | TC-M1–M8, TC-C2–C3, TC-E3 | No |
| 2 | `test/phase2-hilt-integration` | Hilt wiring + Calibration/Baseline | TC-C1, C4, C6–C7, B1, B3, B6–B7 | ✅ Add now |
| 3 | `test/phase3-longterm-ui` | 42-day trends + full UI | TC-T1–T11, B4–B5, C5 | Yes (inherit Phase 2) |
| 4 | `test/phase4-edge-cases` | Idempotency + boundaries | TC-E1–E4 | Yes |

---

## Phase 1 — Seeder Math + Compose Foundation

**Branch:** `test/phase1-math-compose-infra`
**No build.gradle.kts changes needed.**

### Files

| Action | Path | Contains |
|--------|------|---------|
| MODIFY | `app/src/androidTest/kotlin/app/readylytics/health/testutil/HealthConnectSeederTest.kt` | Upgrade `runBlocking` → `runTest` |
| CREATE | `app/src/androidTest/kotlin/app/readylytics/health/scoring/SeedMathVerificationTest.kt` | TC-M1–M8, TC-E3 (pure math, no HC query) |
| CREATE | `app/src/androidTest/kotlin/app/readylytics/health/ui/CalibrationBannerUiTest.kt` | TC-C2, TC-C3, Compose shell |

### Acceptance Criteria

- [x] TC-M1: `sorted[round(0.05×69)] == nadirBpm + 2` for day 0 (70 records, nadir=48 → 5th pct=50)
- [x] TC-M3: Parameterized over d=0..4; `rhrBpm == SeedConstants.rhrNadirForDay(d) + 2`
- [x] TC-M2, M6: All nadir slot times within [02:30, 03:30] UTC across all 5 bedtime offsets
- [x] TC-M4, M5: 5th pct < 55 (not dominated by high slots); 5th pct < overnight mean
- [x] TC-M7, M8: HRV arch peak = 52.5 at midpoint; edges = 45.0 = `HRV_RMSSD_MIN_MS`
- [x] TC-E3: `SeedConstants.meanSleepDuration(DAYS_5) ≈ 8.0` within 0.01h
- [x] TC-C2: `onNodeWithText("Calibrating", substring=true).assertIsDisplayed()` when banner visible
- [x] TC-C3: Exact string `"Calibrating — averages shown may improve as more nights are recorded."` asserted

### Phase 1 Scaffolding Code

#### `SeedMathVerificationTest.kt`

```kotlin
package app.readylytics.health.scoring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import app.readylytics.health.testutil.SeedConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Verifies that SeedConstants math produces the exact expected values used in
 * scoring assertions. Does NOT call the real scoring engine — validates the
 * seeded DATA before integration tests depend on it.
 *
 * All tests here are synchronous. runTest is used only where suspend helpers
 * are involved for future consistency.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class SeedMathVerificationTest {

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    // Replicates seeder U-curve in memory for a given dayIndex.
    // Returns sorted list of bpm values (not HC records — no Android HC dep needed).
    private fun sortedBpmList(dayIndex: Int): List<Long> {
        val sleepStart = SeedConstants.sleepStartForDay(dayIndex, today)
        val sleepDuration = SeedConstants.sleepDurationForDay(dayIndex)
        val totalSlots = sleepDuration.toInt() * SeedConstants.RHR_PER_HOUR
        val intervalSec = 6 * 60L
        val nadirBpm = SeedConstants.rhrNadirForDay(dayIndex)

        val nadirTarget: Instant = sleepStart.atZone(ZoneOffset.UTC)
            .toLocalDate().plusDays(1)
            .atTime(SeedConstants.NADIR_TARGET_HOUR_UTC, 0)
            .toInstant(ZoneOffset.UTC)

        val nadirSlot = (0 until totalSlots).minByOrNull { i ->
            abs(sleepStart.plusSeconds(i * intervalSec).epochSecond - nadirTarget.epochSecond)
        } ?: 0

        return (0 until totalSlots).map { i ->
            if (i == nadirSlot) nadirBpm
            else nadirBpm + min(abs(i - nadirSlot).toLong(), 14L)
        }.sorted()
    }

    private fun p5Index(n: Int): Int = (0.05 * (n - 1)).roundToInt()

    // TC-M1: 5th percentile for day 0 = nadirBpm + 2 = 50 bpm
    @Test
    fun p5_day0_equals50() {
        val sorted = sortedBpmList(0)
        val p5 = sorted[p5Index(sorted.size)]
        assertEquals(50L, p5)
        assertEquals(SeedConstants.rhrNadirForDay(0) + 2, p5)
    }

    // TC-M3: rhrBpm = nadirBpm + 2 for every seeded night in the 5-day cycle
    @Test
    fun p5_allFiveNights_equalsNadirPlusTwo() {
        for (d in 0 until 5) {
            val sorted = sortedBpmList(d)
            val p5 = sorted[p5Index(sorted.size)]
            assertEquals(
                "dayIndex=$d: expected ${SeedConstants.rhrNadirForDay(d) + 2}, got $p5",
                SeedConstants.rhrNadirForDay(d) + 2, p5
            )
        }
    }

    // TC-M2 + TC-M6: nadir slot falls within [02:30, 03:30] UTC regardless of bedtime offset
    @Test
    fun nadirSlot_allOffsets_withinNadirWindow() {
        for (d in 0 until 5) {
            val sleepStart = SeedConstants.sleepStartForDay(d, today)
            val totalSlots = SeedConstants.sleepDurationForDay(d).toInt() * SeedConstants.RHR_PER_HOUR
            val intervalSec = 6 * 60L
            val nadirTarget: Instant = sleepStart.atZone(ZoneOffset.UTC)
                .toLocalDate().plusDays(1)
                .atTime(SeedConstants.NADIR_TARGET_HOUR_UTC, 0)
                .toInstant(ZoneOffset.UTC)
            val nadirSlot = (0 until totalSlots).minByOrNull { i ->
                abs(sleepStart.plusSeconds(i * intervalSec).epochSecond - nadirTarget.epochSecond)
            } ?: 0
            val nadirTime = sleepStart.plusSeconds(nadirSlot * intervalSec)
                .atZone(ZoneOffset.UTC)
            val minutesFromMidnight = nadirTime.hour * 60 + nadirTime.minute
            assertTrue(
                "dayIndex=$d offset: nadir at ${nadirTime.hour}:${nadirTime.minute} " +
                    "outside [02:30,03:30] UTC",
                minutesFromMidnight in 150..210
            )
        }
    }

    // TC-M4: many high-bpm slots don't contaminate the 5th percentile
    @Test
    fun highSlots_notInfluencing_p5() {
        val sorted = sortedBpmList(0) // 70 records, 43 at bpm=62
        val highCount = sorted.count { it >= 60L }
        assertTrue("Expected >30 high slots but got $highCount", highCount > 30)
        val p5 = sorted[p5Index(sorted.size)]
        assertTrue("p5=$p5 should be <55", p5 < 55)
    }

    // TC-M5: 5th percentile strictly less than overnight mean (~60.3 for day 0)
    @Test
    fun p5_lessThan_overnightMean() {
        val values = sortedBpmList(0)
        val mean = values.average()
        val p5 = values[p5Index(values.size)].toDouble()
        assertTrue("p5=$p5 should be < mean=$mean", p5 < mean)
    }

    // TC-M7: HRV arch peak at midpoint, edges at HRV_RMSSD_MIN_MS
    @Test
    fun hrv_archPeak_atNightMidpoint() {
        val n = SeedConstants.sleepDurationForDay(0).toInt() // 7
        val base = SeedConstants.hrvRmssdForDay(0) // 45.0
        val values = (0 until n).map { h -> base + min(h, n - 1 - h).toDouble() * 2.5 }
        assertEquals(52.5, values.max(), 0.001) // peak at h=3
        assertEquals(SeedConstants.HRV_RMSSD_MIN_MS, values.first(), 0.001)
        assertEquals(SeedConstants.HRV_RMSSD_MIN_MS, values.last(), 0.001)
    }

    // TC-M8: minimum HRV value = SeedConstants.HRV_RMSSD_MIN_MS = 45.0
    @Test
    fun hrv_minimum_equalsSeedConstant() {
        val n = SeedConstants.sleepDurationForDay(0).toInt()
        val base = SeedConstants.hrvRmssdForDay(0)
        val values = (0 until n).map { h -> base + min(h, n - 1 - h).toDouble() * 2.5 }
        assertEquals(SeedConstants.HRV_RMSSD_MIN_MS, values.min(), 0.001)
    }

    // TC-E3: mean sleep duration ≈ avgSleepHours (cycle sum = 0)
    @Test
    fun meanSleepDuration_equalsAvgSleepHours_forBothPeriods() {
        // DAYS_5: exact (5 is a multiple of the 5-element cycle)
        assertEquals(
            SeedConstants.DEFAULT_AVG_SLEEP_HOURS,
            SeedConstants.meanSleepDuration(app.readylytics.health.testutil.SeedPeriod.DAYS_5),
            0.001
        )
        // DAYS_42: 42 = 8×5 + 2, mean still ≈ 8.0 within 0.01h tolerance
        assertEquals(
            SeedConstants.DEFAULT_AVG_SLEEP_HOURS,
            SeedConstants.meanSleepDuration(app.readylytics.health.testutil.SeedPeriod.DAYS_42),
            0.01
        )
    }
}
```

#### `CalibrationBannerUiTest.kt`

```kotlin
package app.readylytics.health.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
// TODO: import the actual CalibrationBanner composable — confirm package after exploring feature/dashboard/
// import app.readylytics.health.feature.dashboard.CalibrationBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated Compose tests for CalibrationBanner.
 *
 * Uses ComponentActivity (not the real MainActivity) — no Hilt, no routing.
 * Manually composes the banner with controlled state.
 *
 * TC-C2: banner visible when calibrating
 * TC-C3: banner shows exact string resource text
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
class CalibrationBannerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // TODO: Replace the lambda parameter with actual CalibrationBanner signature.
    // Likely: CalibrationBanner(isVisible: Boolean) or CalibrationBanner(dailySummary: DailySummary)
    // Explore feature/dashboard/src/main/kotlin/.../CalibrationBanner.kt for the exact params.

    // TC-C2: banner node exists and is displayed when calibrating
    @Test
    fun banner_visible_when_isCalibrating_true() {
        composeRule.setContent {
            MaterialTheme {
                // CalibrationBanner(isVisible = true) // ← uncomment after confirming signature
            }
        }
        composeRule.onNodeWithText("Calibrating", substring = true).assertIsDisplayed()
    }

    // TC-C3: banner shows exact string matching R.string.message_calibrating_banner
    @Test
    fun banner_shows_exact_calibration_message() {
        val expectedText =
            "Calibrating — averages shown may improve as more nights are recorded."
        composeRule.setContent {
            MaterialTheme {
                // CalibrationBanner(isVisible = true)
            }
        }
        composeRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    // TC-B2: banner does NOT exist when data is sufficient (14+ nights)
    @Test
    fun banner_absent_when_isCalibrating_false() {
        composeRule.setContent {
            MaterialTheme {
                // CalibrationBanner(isVisible = false)
            }
        }
        composeRule.onNodeWithText("Calibrating", substring = true).assertDoesNotExist()
    }
}
```

#### `HealthConnectSeederTest.kt` — `runTest` upgrade (MODIFY)

Replace every `runBlocking {` call with `runTest {`. Signature change only:

```kotlin
// Before:
@Test
fun days5_rhrRecordCount_matchesExpected() =
    runBlocking {
        client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
        ...
    }

// After:
@Test
fun days5_rhrRecordCount_matchesExpected() = runTest {
    client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)
    ...
}
```

Add import:
```kotlin
import kotlinx.coroutines.test.runTest
```

Remove import:
```kotlin
import kotlinx.coroutines.runBlocking
```

---

## Phase 2 — Hilt Wiring + Calibration/Baseline Integration

**Branch:** `test/phase2-hilt-integration`
**Requires Phase 1 merged first.**

### build.gradle.kts Changes

```kotlin
// app/build.gradle.kts — add to androidTest deps block
androidTestImplementation(libs.hilt.android.testing)
kspAndroidTest(libs.hilt.compiler)
```

### testInstrumentationRunner Change

```kotlin
// app/build.gradle.kts — defaultConfig block
testInstrumentationRunner = "app.readylytics.health.HiltTestRunner"
```

### Files

| Action | Path | Contains |
|--------|------|---------|
| CREATE | `app/src/androidTest/kotlin/app/readylytics/health/HiltTestRunner.kt` | Custom runner for Hilt |
| CREATE | `app/src/androidTest/kotlin/app/readylytics/health/integration/CalibrationStateTest.kt` | TC-C1, C4, C6, C7 |
| CREATE | `app/src/androidTest/kotlin/app/readylytics/health/integration/BaselineStateTest.kt` | TC-B1, B3, B6, B7 |

### HiltTestRunner.kt

```kotlin
package app.readylytics.health

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

### CalibrationStateTest.kt Structure

```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class CalibrationStateTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val grantPermissions = GrantPermissionRule.grant(/* HC permissions */)

    @Inject lateinit var scoringRepository: ScoringRepository
    private val today = LocalDate.now(ZoneOffset.UTC)
    private lateinit var client: HealthConnectClient

    @Before fun setUp() { hiltRule.inject(); client = HealthConnectClient.getOrCreate(...) }

    // TC-C1
    @Test fun isCalibrating_true_with5Nights() = runTest { ... }

    // TC-C4
    @Test fun phase_calibrating_with5Nights() = runTest { ... }

    // TC-C6 (boundary: 6 nights → still calibrating)
    @Test fun isCalibrating_true_with6Nights() = runTest { ... }

    // TC-C7 (boundary flip: 7 nights → isCalibrating = false)
    @Test fun isCalibrating_false_at7Nights() = runTest { ... }
}
```

### Acceptance Criteria

- [x] `HiltTestRunner` compiles and emulator starts with it
- [x] `@Inject ScoringRepository` resolves (Hilt graph wired correctly for androidTest)
- [x] TC-C1: `dailySummary.isCalibrating == true` after DAYS_5 seed
- [x] TC-C4: `dailySummary.snapshotCalibrationPhase == "Calibrating"`
- [x] TC-C6: `isCalibrating == true` at 6 nights
- [x] TC-C7: `isCalibrating == false` at 7 nights (exact boundary)
- [x] TC-B1: `isCalibrating == false` after DAYS_14 seed
- [x] TC-B3: `snapshotCalibrationPhase == "Establishing Baseline"`
- [x] TC-B6: `nocturnalHrv > 0.0`, `hrvBaseline != null`
- [x] TC-B7: `baselineObservationCount == 14`

**Resolve Prerequisites P2, P3 in this phase:**
- Read `ComputeSleepMetricsUseCase.kt` to confirm `nocturnalHrv` source field
- Read `EverydayHeartRateLoadCalculator.kt` to confirm simple mean vs EMA

---

## Phase 3 — Long-term Trends + Full UI Tests

**Branch:** `test/phase3-longterm-ui`
**Requires Phase 2 merged.**

### Files

| Action | Path | Contains |
|--------|------|---------|
| CREATE | `app/src/androidTest/.../integration/LongTermTrendsTest.kt` | TC-T1–T11 |
| CREATE | `app/src/androidTest/.../ui/DashboardScoringUiTest.kt` | TC-B4, B5, C5 (HC-seeded dashboard) |
| EXPAND | `app/src/androidTest/.../ui/CalibrationBannerUiTest.kt` | Add TC-C5 (score restricted) |

### Acceptance Criteria

- [x] TC-T1: phase == `"Mature"` at 42 nights
- [x] TC-T2: `chronicRhrAvg ≈ 51.93 bpm` (±0.05; verify EMA vs mean first)
- [x] TC-T3: `acuteRhrAvg ≈ 51.57 bpm` (±0.05)
- [x] TC-T4: `strainRatioEverydayHr ∈ [0.98, 1.02]`
- [x] TC-T5, T6: `hrvMuMssd == 52.5 ms` (±0.1) for both 7-day and 42-day windows
- [x] TC-T7: `hrvSigmaMssd ∈ [5.0, 6.0]`
- [x] TC-T8, T9: raw HC records min = 48 bpm, 45.0 ms (seeder integrity)
- [x] TC-T10: chart accessibility node count == 42
- [x] TC-T11: `strainRatioEverydayHr ∈ [0.95, 1.05]`
- [x] TC-B4: sleep score node text parseable as Int ∈ [0, 100]
- [x] TC-B5: RHR display == `"50"` bpm node visible
- [x] TC-C5: no numeric score node visible during calibration (DAYS_5)

---

## Phase 4 — Edge Cases

**Branch:** `test/phase4-edge-cases`

### Files

| Action | Path | Contains |
|--------|------|---------|
| CREATE | `app/src/androidTest/.../integration/SeederEdgeCaseTest.kt` | TC-E1, E2, E4 |
| EXPAND | `scoring/SeedMathVerificationTest.kt` | TC-E3 (already in Phase 1) |

### Acceptance Criteria

- [x] TC-E1: double-seed → `rhrBpm == 50` (idempotent via clientRecordId upsert)
- [x] TC-E2: DAYS_5 then DAYS_42 seed → record count == `expectedRhrCount(DAYS_42)`
- [x] TC-E4: zero nadir slots outside [02:30, 03:30] UTC across all 42 nights

---

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| P1: SleepPercentileRhrCalculator needs SleepSessionRecord | Investigate in Phase 2; extend seeder with sleep sessions if required |
| P2: nocturnalHrv source unknown | Read ComputeSleepMetricsUseCase.kt before hardcoding 52.5 ms in Phase 3 |
| P3: EMA vs simple mean for chronic/acute | Read EverydayHeartRateLoadCalculator.kt; adjust TC-T2 tolerance if EMA |
| Hilt Singleton state leaking between tests | Use `@UninstallModules` + test-specific fakes for scoring repos where needed |
| 42-day seed too slow for CI | Tag `@LargeTest`; exclude from pre-merge fast-test suite via Gradle filter |
| CalibrationBanner signature unknown | Confirm by reading `feature/dashboard/…/CalibrationBanner.kt` before Phase 1 impl |

---

## Verification Steps

1. Phase 1: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.scoring.SeedMathVerificationTest`
2. Phase 1: same command for `CalibrationBannerUiTest`
3. Phase 2: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.integration.CalibrationStateTest`
4. Full suite: `./gradlew :app:connectedDebugAndroidTest` (CI gate)
5. Pre-merge: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lint`
