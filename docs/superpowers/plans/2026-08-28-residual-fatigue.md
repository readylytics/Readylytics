# Residual Fatigue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shadow Residual Fatigue metric to the scoring pipeline — normalize Banister TRIMP multiplier across profiles, compute timestamp-aware exponential-decay fatigue from per-workout TRIMP, persist it on DailySummary, and expose model parameters through settings. Zero Readiness behavior change.

**Architecture:** Pure-Kotlin fatigue use case consumes existing per-workout TRIMP (no raw-HR re-scan). Walk-forward integration via a mutable state-accumulator context (O(W+D)) mirrors the existing TRIMP/baseline context pattern. One new nullable column on `daily_summaries`. DataStore migration normalizes Banister multiplier for existing users; auto-triggered recompute propagates changes.

**Tech Stack:** Kotlin, Room (migration 12→13), DataStore proto, Hilt DI, JUnit 5 (pure Kotlin tests, zero Android deps)

**Spec:** `docs/superpowers/specs/2026-08-28-residual-fatigue-design.md`

## Global Constraints

- `minSdk = 26`, `targetSdk = 37`
- All business/calculation logic: pure Kotlin, zero Android dependencies
- Unit tests mirror source package structure, test boundary conditions
- `computeReadinessScore()` must NOT be modified in any task (shadow mode)
- Pre-commit: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
- Detekt: no new issues. Boyscout rule on touched files.
- All user-facing strings in `app/src/main/res/values/strings.xml`
- Target ≤400 lines/file, hard limit ≤800

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `core/model/.../domain/scoring/ResidualFatigueConfig.kt` | Config data class (enabled, halfLifeHours, fatigueGain) |
| `core/scoring/.../domain/scoring/ComputeResidualFatigueUseCase.kt` | Pure fatigue computation (summation + accumulator) |
| `core/scoring/src/test/.../ComputeResidualFatigueUseCaseTest.kt` | Unit tests for fatigue formula |
| `core/model/.../domain/repository/WalkForwardFatigueContext.kt` | Mutable state-accumulator context for walk-forward |
| `core/database/.../data/local/migration/Migration12To13.kt` | ALTER TABLE SQL for residualFatigue column |

### Modified Files

| File | Change |
|------|--------|
| `core/model/.../data/preferences/PhysiologyProfile.kt` | `banisterMultiplier` → 1.0 all profiles |
| `app/src/main/proto/user_preferences.proto` | Add fields 90-93 (migration flag + fatigue settings) |
| `core/model/.../data/preferences/UserPreferences.kt` | Add fatigue preference fields |
| `core/model/.../data/preferences/SettingsDefaults.kt` | Add fatigue defaults |
| `core/model/.../domain/validation/SettingsValidators.kt` | Add fatigue validation rules |
| `core/model/.../domain/preferences/FeatureSettingsPorts.kt` | Add fatigue methods to `DisplaySettings` |
| `feature/settings/.../SettingsEvent.kt` | Add fatigue settings events |
| `app/.../data/preferences/PhysiologyPreferences.kt` | Add fatigue DataStore writers + migration |
| `app/.../data/preferences/UserPreferencesMapperExtensions.kt` | Map proto ↔ domain for fatigue fields |
| `app/.../data/preferences/UserPreferencesSerializerExtensions.kt` | Serialize fatigue fields |
| `app/.../data/preferences/SettingsRepository.kt` | Implement fatigue `DisplaySettings` methods |
| `core/model/.../domain/model/DailySummary.kt` | Add `residualFatigue: Float? = null` |
| `core/database-schema/.../entity/DailySummaryEntity.kt` | Add `residualFatigue: Float? = null` |
| `core/database/.../data/mapper/DailySummaryMapper.kt` | Map fatigue in `withLoadFields()` |
| `core/database/.../data/local/HealthDatabase.kt` | `DATABASE_VERSION = 13` |
| `core/database/.../data/local/DatabaseMigrations.kt` | Add `MIGRATION_12_13` to `all` array |
| `core/database-schema/.../dao/WorkoutDao.kt` | Add `getFatigueWorkoutInputs()` query |
| `core/model/.../domain/repository/ScoringRepository.kt` | Add `fetchWalkForwardFatigueContext()` |
| `core/database/.../data/repository/ScoringRepositoryImpl.kt` | Implement fatigue context + compute in `computeDailySummary()` |
| `core/database/.../data/repository/ScoringDayDataLoader.kt` | Add `loadFatigueWorkoutInputs()` |
| `core/database/.../domain/sync/DailyRecomputeSupport.kt` | Add fatigue context parameter to `recomputeDay()` + `buildWalkForwardFatigueContext()` |
| `core/healthconnect/.../domain/sync/DailySyncUseCase.kt` | Build + pass fatigue context |
| `core/healthconnect/.../domain/sync/ResyncRangeUseCase.kt` | Build + pass fatigue context |
| `internal-docs/DATA_FLOW.md` | Document fatigue pipeline + TRIMP normalization |

---

### Task 1: Normalize Banister Multiplier in Enum

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/PhysiologyProfile.kt:10-16`

**Interfaces:**
- Consumes: nothing new
- Produces: `PhysiologyProfile.ATHLETE.banisterMultiplier == 1.0f`, `ACTIVE.banisterMultiplier == 1.0f`, `SEDENTARY.banisterMultiplier == 1.0f` (Cheng beta and iTRIMP B unchanged)

- [ ] **Step 1: Verify existing tests reference the old multiplier values**

Run: `./gradlew :core:model:testDebugUnitTest 2>&1 | tail -5`
Expected: PASS (baseline — all tests pass before we change anything)

- [ ] **Step 2: Change the PhysiologyProfile enum defaults**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/PhysiologyProfile.kt`, change:

```kotlin
enum class PhysiologyProfile(
    val lnSigmaPrior: Float,
    val defaultSleepGoalHours: Float,
    val banisterMultiplier: Float,
    val defaultChengBeta: Float,
    val defaultItrimB: Float,
) {
    ATHLETE(
        lnSigmaPrior = 0.10f,
        defaultSleepGoalHours = 9.0f,
        banisterMultiplier = 1.00f,
        defaultChengBeta = 0.07f,
        defaultItrimB = 2.9f,
    ),
    ACTIVE(
        lnSigmaPrior = 0.15f,
        defaultSleepGoalHours = 8.0f,
        banisterMultiplier = 1.00f,   // was 1.35f
        defaultChengBeta = 0.09f,
        defaultItrimB = 2.1f,
    ),
    SEDENTARY(
        lnSigmaPrior = 0.20f,
        defaultSleepGoalHours = 7.5f,
        banisterMultiplier = 1.00f,   // was 1.75f
        defaultChengBeta = 0.11f,
        defaultItrimB = 1.5f,
    ),
}
```

- [ ] **Step 3: Fix any tests that assert old ACTIVE/SEDENTARY multiplier values**

Search for tests referencing `1.35` or `1.75` in the context of `banisterMultiplier`:

Run: `grep -rn "1\.35\|1\.75\|banisterMultiplier" --include="*.kt" core/model/src/test/ core/scoring/src/test/ core/database/src/test/ app/src/test/ feature/settings/src/test/`

Update any assertions to expect `1.0f` for all profiles. Leave Cheng beta / iTRIMP B assertions unchanged.

- [ ] **Step 4: Run tests to verify**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/PhysiologyProfile.kt
# also add any fixed test files
git commit -m "feat: normalize Banister multiplier to 1.0 for all physiology profiles

Cheng beta and iTRIMP B retain profile-specific defaults.
Only affects new profile selections; existing stored preferences unchanged."
```

---

### Task 2: DataStore Migration for Banister Multiplier + Auto-Recompute

**Files:**
- Modify: `app/src/main/proto/user_preferences.proto:279` — add field 90
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapperExtensions.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/preferences/TrimpNormalizationMigrationTest.kt` (new)

**Interfaces:**
- Consumes: `PhysiologyProfile.*.banisterMultiplier` (all 1.0f from Task 1)
- Produces: `trimpNormalizationMigrated` flag on proto; `migrateTrimpDefaults()` function; auto-recompute via `workerScheduler.scheduleResyncWorker(recomputeOnly = true)`

- [ ] **Step 1: Add proto field for migration flag**

In `app/src/main/proto/user_preferences.proto`, before the closing brace of `UserPreferencesProto`, add:

```proto
    // One-time flag: Banister multiplier defaults have been migrated to 1.0.
    // Prevents re-running the migration on subsequent launches.
    bool trimp_normalization_migrated = 90;
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/app/readylytics/health/data/preferences/TrimpNormalizationMigrationTest.kt`:

```kotlin
package app.readylytics.health.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrimpNormalizationMigrationTest {

    @Test
    fun `ACTIVE user with default 1_35 migrates to 1_0`() {
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 1.35f,
            alreadyMigrated = false,
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun `SEDENTARY user with default 1_75 migrates to 1_0`() {
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 1.75f,
            alreadyMigrated = false,
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun `ATHLETE user with 1_0 stays at 1_0`() {
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 1.0f,
            alreadyMigrated = false,
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun `user who customized to 1_50 keeps 1_50`() {
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 1.50f,
            alreadyMigrated = false,
        )
        assertEquals(1.50f, result)
    }

    @Test
    fun `already migrated returns stored value unchanged`() {
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 1.35f,
            alreadyMigrated = true,
        )
        assertEquals(1.35f, result)
    }

    @Test
    fun `proto3 zero default migrates to 1_0`() {
        // proto3 float default is 0.0 — a user who never selected a profile
        val result = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = 0.0f,
            alreadyMigrated = false,
        )
        assertEquals(1.0f, result)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TrimpNormalizationMigrationTest*" 2>&1 | tail -5`
Expected: FAIL — `TrimpMigrationHelper` does not exist

- [ ] **Step 4: Implement TrimpMigrationHelper**

Add to `app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt`, at file scope (outside the class):

```kotlin
internal object TrimpMigrationHelper {
    private val OLD_PROFILE_DEFAULTS = setOf(1.00f, 1.35f, 1.75f)

    fun migrateRasCalibration(
        storedValue: Float,
        alreadyMigrated: Boolean,
    ): Float {
        if (alreadyMigrated) return storedValue
        // proto3 zero default = un-set, treat as needing migration
        if (storedValue == 0.0f || storedValue in OLD_PROFILE_DEFAULTS) return 1.0f
        return storedValue
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TrimpNormalizationMigrationTest*" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 6: Add migration call to PhysiologyPreferences**

In `PhysiologyPreferences`, add:

```kotlin
suspend fun migrateTrimpDefaultsIfNeeded() {
    dataStore.updateData { proto ->
        if (proto.trimpNormalizationMigrated) return@updateData proto
        val newRasCal = TrimpMigrationHelper.migrateRasCalibration(
            storedValue = proto.rasCalibration,
            alreadyMigrated = false,
        )
        proto.toBuilder()
            .setRasCalibration(newRasCal)
            .setTrimpNormalizationMigrated(true)
            .build()
    }
}
```

- [ ] **Step 7: Wire migration into DatabaseReadyStartupInitializer**

In `DatabaseReadyStartupInitializer.initializeIfReady()`, add a `runNonFatal` block **before** the scoring version check (line ~44):

```kotlin
runNonFatal("TRIMP normalization migration") {
    physiologyPreferences.get().migrateTrimpDefaultsIfNeeded()
}
```

Add `physiologyPreferences: Lazy<PhysiologyPreferences>` to the constructor. Wire in `HealthDashboardApplication` where `DatabaseReadyStartupInitializer` is constructed.

The existing scoring-version recompute mechanism (`CURRENT_SCORING_VERSION` check) handles the recompute trigger: bump `SettingsDefaults.CURRENT_SCORING_VERSION` from `1` to `2`. This enqueues `scheduleResyncWorker(recomputeOnly = true)` which calls `recomputeRange()` with `skipIngestAndPrune = true` over the full retained history. No new worker needed.

- [ ] **Step 8: Bump CURRENT_SCORING_VERSION**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`:

```kotlin
const val CURRENT_SCORING_VERSION = 2   // was 1
```

- [ ] **Step 9: Map the new proto field**

In `UserPreferencesMapperExtensions.kt`, ensure `trimpNormalizationMigrated` is mapped (it's only consumed at the proto layer by `migrateTrimpDefaultsIfNeeded`, but the serializer extension needs it for backup/restore completeness).

In `UserPreferencesSerializerExtensions.kt`, add the field to the serialization path.

- [ ] **Step 10: Run full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS. Golden snapshot tests may need fixture updates for changed TRIMP values from the scoring version bump.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/proto/user_preferences.proto \
       app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt \
       app/src/test/kotlin/app/readylytics/health/data/preferences/TrimpNormalizationMigrationTest.kt \
       app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt
# also add mapper/serializer changes and any fixed golden fixtures
git commit -m "feat: migrate existing users' Banister multiplier to 1.0

One-time DataStore migration resets stored rasCalibration to 1.0 when it
matches a known old profile default (1.0/1.35/1.75). Custom values preserved.
Bumps CURRENT_SCORING_VERSION to 2 to trigger auto-recompute of historical
scores on next app launch."
```

---

### Task 3: Residual Fatigue Domain Model + Use Case

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/ResidualFatigueConfig.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCase.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCaseTest.kt`

**Interfaces:**
- Consumes: nothing from prior tasks (standalone pure Kotlin)
- Produces:
  - `ResidualFatigueConfig(enabled: Boolean = true, halfLifeHours: Float = 24f, fatigueGain: Float = 1.0f)`
  - `ComputeResidualFatigueUseCase.FatigueWorkoutInput(endTimeMs: Long, trimp: Float)`
  - `ComputeResidualFatigueUseCase.compute(evaluationTimeMs: Long, workouts: List<FatigueWorkoutInput>, config: ResidualFatigueConfig): Float`
  - `ComputeResidualFatigueUseCase.advanceAccumulator(accumulatedFatigue: Double, lastEvalMs: Long, currentEvalMs: Long, newImpulses: List<FatigueWorkoutInput>, config: ResidualFatigueConfig): Pair<Double, Long>`

**PREREQUISITE:** Before implementing, verify COALESCE(modelTrimp, trimp) semantics per spec Section 2. Run:
```
grep -A5 "modelTrimp" core/database-schema/.../entity/WorkoutRecordEntity.kt
```
Confirm the comment on `modelTrimp` (line 29-32) documents that `trimp` is the Edwards zone-weighted value while `modelTrimp` is the user-selected model. Both are per-workout HR-integrated training load over the same workout boundary. The COALESCE fallback is semantically safe — both represent integrated workout load, differing only in integration method. Document this verification in a code comment on the DAO query (Task 5).

- [ ] **Step 1: Write ResidualFatigueConfig**

Create `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/ResidualFatigueConfig.kt`:

```kotlin
package app.readylytics.health.core.model.domain.scoring

data class ResidualFatigueConfig(
    val enabled: Boolean = true,
    val halfLifeHours: Float = 24f,
    val fatigueGain: Float = 1.0f,
)
```

- [ ] **Step 2: Write the failing tests**

Create `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCaseTest.kt`:

```kotlin
package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.pow

class ComputeResidualFatigueUseCaseTest {

    private val useCase = ComputeResidualFatigueUseCase()
    private val defaultConfig = ResidualFatigueConfig()
    private val halfLifeMs = 24.0 * 3_600_000.0

    private fun workout(endTimeMs: Long, trimp: Float) =
        ComputeResidualFatigueUseCase.FatigueWorkoutInput(endTimeMs, trimp)

    @Test
    fun `single workout - fatigue equals gain times trimp at workout end`() {
        val w = workout(endTimeMs = 1000L, trimp = 100f)
        val result = useCase.compute(
            evaluationTimeMs = 1000L,
            workouts = listOf(w),
            config = defaultConfig,
        )
        assertEquals(100f, result, 0.01f)
    }

    @Test
    fun `single workout - fatigue halves after one half-life`() {
        val endMs = 0L
        val evalMs = (24 * 3_600_000).toLong() // 24h later
        val w = workout(endTimeMs = endMs, trimp = 100f)
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        assertEquals(50f, result, 0.01f)
    }

    @Test
    fun `multiple workouts stack additively`() {
        val w1 = workout(endTimeMs = 0L, trimp = 80f)
        val w2 = workout(endTimeMs = (12 * 3_600_000).toLong(), trimp = 60f)
        val evalMs = (24 * 3_600_000).toLong()
        val result = useCase.compute(evalMs, listOf(w1, w2), defaultConfig)
        val expected = (80.0 * 2.0.pow(-24.0 / 24.0) + 60.0 * 2.0.pow(-12.0 / 24.0)).toFloat()
        assertEquals(expected, result, 0.01f)
    }

    @Test
    fun `rest day - no new impulse, fatigue decays`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val evalMs = (48 * 3_600_000).toLong() // 2 days later
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        assertEquals(25f, result, 0.01f)
    }

    @Test
    fun `same TRIMP at 06h vs 21h - different next-morning fatigue`() {
        val morningEnd = (6 * 3_600_000).toLong()
        val eveningEnd = (21 * 3_600_000).toLong()
        val nextMorningEval = (30 * 3_600_000).toLong() // 06:00 next day

        val morningResult = useCase.compute(nextMorningEval, listOf(workout(morningEnd, 100f)), defaultConfig)
        val eveningResult = useCase.compute(nextMorningEval, listOf(workout(eveningEnd, 100f)), defaultConfig)

        // 24h decay vs 9h decay — evening workout has more residual fatigue
        assert(eveningResult > morningResult)
    }

    @Test
    fun `workout crossing midnight - endTime determines timing`() {
        // workout 22:00 to 01:00 next day
        val endMs = (25 * 3_600_000).toLong() // 01:00 (hour 25 from epoch)
        val evalMs = (30 * 3_600_000).toLong() // 06:00 same day
        val w = workout(endMs, 80f)
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        val expected = (80.0 * 2.0.pow(-5.0 / 24.0)).toFloat()
        assertEquals(expected, result, 0.01f)
    }

    @Test
    fun `zero TRIMP contributes nothing`() {
        val w = workout(endTimeMs = 0L, trimp = 0f)
        val result = useCase.compute(1000L, listOf(w), defaultConfig)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `empty workout list returns zero`() {
        val result = useCase.compute(1000L, emptyList(), defaultConfig)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `disabled config returns zero`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val result = useCase.compute(1000L, listOf(w), defaultConfig.copy(enabled = false))
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `custom gain scales output proportionally`() {
        val w = workout(endTimeMs = 1000L, trimp = 100f)
        val gain2 = useCase.compute(1000L, listOf(w), defaultConfig.copy(fatigueGain = 2.0f))
        val gain1 = useCase.compute(1000L, listOf(w), defaultConfig)
        assertEquals(gain1 * 2f, gain2, 0.01f)
    }

    @Test
    fun `custom half-life changes decay rate`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val evalMs = (12 * 3_600_000).toLong()
        // With 12h half-life, 12h elapsed = exactly half
        val result = useCase.compute(evalMs, listOf(w), defaultConfig.copy(halfLifeHours = 12f))
        assertEquals(50f, result, 0.01f)
    }

    @Test
    fun `accumulator produces identical results to summation`() {
        val workouts = listOf(
            workout(endTimeMs = 0L, trimp = 100f),
            workout(endTimeMs = (8 * 3_600_000).toLong(), trimp = 60f),
            workout(endTimeMs = (20 * 3_600_000).toLong(), trimp = 80f),
        )
        val evalMs = (36 * 3_600_000).toLong()

        val summationResult = useCase.compute(evalMs, workouts, defaultConfig)

        // Accumulator path
        var accFatigue = 0.0
        var lastEvalMs = Long.MIN_VALUE
        for (w in workouts) {
            val (newFatigue, newLastEval) = useCase.advanceAccumulator(
                accumulatedFatigue = accFatigue,
                lastEvalMs = lastEvalMs,
                currentEvalMs = w.endTimeMs,
                newImpulses = listOf(w),
                config = defaultConfig,
            )
            accFatigue = newFatigue
            lastEvalMs = newLastEval
        }
        // Decay to final eval
        val (finalFatigue, _) = useCase.advanceAccumulator(accFatigue, lastEvalMs, evalMs, emptyList(), defaultConfig)

        assertEquals(summationResult, finalFatigue.toFloat(), 0.01f)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "*ComputeResidualFatigueUseCaseTest*" 2>&1 | tail -5`
Expected: FAIL — `ComputeResidualFatigueUseCase` does not exist

- [ ] **Step 4: Implement ComputeResidualFatigueUseCase**

Create `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCase.kt`:

```kotlin
package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import javax.inject.Inject
import kotlin.math.pow

class ComputeResidualFatigueUseCase @Inject constructor() {

    data class FatigueWorkoutInput(
        val endTimeMs: Long,
        val trimp: Float,
    )

    fun compute(
        evaluationTimeMs: Long,
        workouts: List<FatigueWorkoutInput>,
        config: ResidualFatigueConfig,
    ): Float {
        if (!config.enabled) return 0f
        val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
        var fatigue = 0.0
        for (w in workouts) {
            if (w.trimp <= 0f || w.endTimeMs > evaluationTimeMs) continue
            val elapsedMs = (evaluationTimeMs - w.endTimeMs).toDouble()
            fatigue += config.fatigueGain * w.trimp * 2.0.pow(-elapsedMs / halfLifeMs)
        }
        return fatigue.toFloat()
    }

    fun advanceAccumulator(
        accumulatedFatigue: Double,
        lastEvalMs: Long,
        currentEvalMs: Long,
        newImpulses: List<FatigueWorkoutInput>,
        config: ResidualFatigueConfig,
    ): Pair<Double, Long> {
        if (!config.enabled) return 0.0 to currentEvalMs
        val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
        var fatigue = if (lastEvalMs == Long.MIN_VALUE) {
            0.0
        } else {
            val elapsed = (currentEvalMs - lastEvalMs).toDouble()
            accumulatedFatigue * 2.0.pow(-elapsed / halfLifeMs)
        }
        for (impulse in newImpulses) {
            if (impulse.trimp <= 0f) continue
            val elapsed = (currentEvalMs - impulse.endTimeMs).toDouble().coerceAtLeast(0.0)
            fatigue += config.fatigueGain * impulse.trimp * 2.0.pow(-elapsed / halfLifeMs)
        }
        return fatigue to currentEvalMs
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "*ComputeResidualFatigueUseCaseTest*" 2>&1 | tail -5`
Expected: PASS (all 12 tests)

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/ResidualFatigueConfig.kt \
       core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCase.kt \
       core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCaseTest.kt
git commit -m "feat: add ResidualFatigueConfig and ComputeResidualFatigueUseCase

Pure Kotlin fatigue model: F(t) = Σ gain * trimp_i * 2^(-(t - end_i) / halfLife).
Both summation and state-accumulator paths with equivalence test.
Not yet wired into scoring pipeline."
```

---

### Task 4: Fatigue Settings Infrastructure

**Files:**
- Modify: `app/src/main/proto/user_preferences.proto` — add fields 91-93
- Modify: `core/model/.../data/preferences/UserPreferences.kt:114`
- Modify: `core/model/.../data/preferences/SettingsDefaults.kt`
- Modify: `core/model/.../domain/validation/SettingsValidators.kt`
- Modify: `core/model/.../domain/preferences/FeatureSettingsPorts.kt:74-95`
- Modify: `feature/settings/.../SettingsEvent.kt`
- Modify: `app/.../data/preferences/PhysiologyPreferences.kt`
- Modify: `app/.../data/preferences/UserPreferencesMapperExtensions.kt`
- Modify: `app/.../data/preferences/UserPreferencesSerializerExtensions.kt`
- Modify: `app/.../data/preferences/SettingsRepository.kt`

**Interfaces:**
- Consumes: `ResidualFatigueConfig` from Task 3
- Produces:
  - `UserPreferences.residualFatigueEnabled: Boolean`, `.residualFatigueHalfLifeHours: Float`, `.residualFatigueGain: Float`
  - `SettingsValidators.FATIGUE_HALF_LIFE_RULE`, `.FATIGUE_GAIN_RULE`
  - `DisplaySettings.updateResidualFatigueEnabled(Boolean)`, `.updateResidualFatigueHalfLifeHours(Float)`, `.updateResidualFatigueGain(Float)`
  - `SettingsEvent.ResidualFatigueEnabledChanged`, `.ResidualFatigueHalfLifeChanged`, `.ResidualFatigueGainChanged`

- [ ] **Step 1: Add proto fields**

In `user_preferences.proto`, add before closing brace:

```proto
    // Residual Fatigue model parameters (Phase 1 — shadow mode).
    // Proto3 defaults: bool=false, float=0.0. Domain read boundary resolves
    // unset (0.0) to SettingsDefaults values.
    optional bool residual_fatigue_enabled = 91;
    optional float residual_fatigue_half_life_hours = 92;
    optional float residual_fatigue_gain = 93;
```

- [ ] **Step 2: Add SettingsDefaults constants**

In `SettingsDefaults.kt`, add after `LAST_GLOBAL_DISPLAY_MODE`:

```kotlin
const val RESIDUAL_FATIGUE_ENABLED = true
const val RESIDUAL_FATIGUE_HALF_LIFE_HOURS = 24f
const val RESIDUAL_FATIGUE_GAIN = 1.0f
```

- [ ] **Step 3: Add UserPreferences fields**

In `UserPreferences.kt`, add before the closing paren (after `lastRecalcHypersomniaOnsetPercent`):

```kotlin
val residualFatigueEnabled: Boolean = SettingsDefaults.RESIDUAL_FATIGUE_ENABLED,
val residualFatigueHalfLifeHours: Float = SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
val residualFatigueGain: Float = SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
```

- [ ] **Step 4: Add validation rules**

In `SettingsValidators.kt`, add after `TRIMP_ITRIMP_B_FACTOR_RULE`:

```kotlin
val FATIGUE_HALF_LIFE_RULE = FloatRangeRule(6f, 96f, "Half-life: 6–96 hours")
val FATIGUE_GAIN_RULE = FloatRangeRule(0.1f, 5.0f, "Gain: 0.1–5.0")
```

- [ ] **Step 5: Add DisplaySettings methods**

In `FeatureSettingsPorts.kt`, add to `DisplaySettings` interface (after `updateItrimB`):

```kotlin
suspend fun updateResidualFatigueEnabled(enabled: Boolean)
suspend fun updateResidualFatigueHalfLifeHours(hours: Float)
suspend fun updateResidualFatigueGain(value: Float)
```

- [ ] **Step 6: Add SettingsEvent variants**

In `SettingsEvent.kt`, add after `ResetTrimpToProfileDefaults`:

```kotlin
data class ResidualFatigueEnabledChanged(
    val enabled: Boolean,
) : SettingsEvent

data class ResidualFatigueHalfLifeChanged(
    val hours: Float,
) : SettingsEvent

data class ResidualFatigueGainChanged(
    val value: Float,
) : SettingsEvent
```

- [ ] **Step 7: Add DataStore writers**

In `PhysiologyPreferences.kt`, add validation and writer methods:

```kotlin
private fun Float.toValidFatigueHalfLife() = coerceIn(6f, 96f)
private fun Float.toValidFatigueGain() = coerceIn(0.1f, 5.0f)

suspend fun updateResidualFatigueEnabled(enabled: Boolean) {
    dataStore.updateData { it.toBuilder().setResidualFatigueEnabled(enabled).build() }
}

suspend fun updateResidualFatigueHalfLifeHours(hours: Float) {
    dataStore.updateData {
        it.toBuilder().setResidualFatigueHalfLifeHours(hours.toValidFatigueHalfLife()).build()
    }
}

suspend fun updateResidualFatigueGain(value: Float) {
    dataStore.updateData {
        it.toBuilder().setResidualFatigueGain(value.toValidFatigueGain()).build()
    }
}
```

- [ ] **Step 8: Map proto ↔ domain**

In `UserPreferencesMapperExtensions.kt`, add fatigue fields to the mapping function (follow existing patterns — resolve `optional` 0.0 to defaults):

```kotlin
residualFatigueEnabled = if (proto.hasResidualFatigueEnabled()) proto.residualFatigueEnabled
    else SettingsDefaults.RESIDUAL_FATIGUE_ENABLED,
residualFatigueHalfLifeHours = if (proto.hasResidualFatigueHalfLifeHours() &&
    proto.residualFatigueHalfLifeHours > 0f) proto.residualFatigueHalfLifeHours
    else SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
residualFatigueGain = if (proto.hasResidualFatigueGain() &&
    proto.residualFatigueGain > 0f) proto.residualFatigueGain
    else SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
```

In `UserPreferencesSerializerExtensions.kt`, add:

```kotlin
setResidualFatigueEnabled(domain.residualFatigueEnabled)
setResidualFatigueHalfLifeHours(domain.residualFatigueHalfLifeHours)
setResidualFatigueGain(domain.residualFatigueGain)
```

- [ ] **Step 9: Implement DisplaySettings in SettingsRepository**

In `SettingsRepository.kt`, delegate to `PhysiologyPreferences`:

```kotlin
override suspend fun updateResidualFatigueEnabled(enabled: Boolean) =
    physiologyPreferences.updateResidualFatigueEnabled(enabled)
override suspend fun updateResidualFatigueHalfLifeHours(hours: Float) =
    physiologyPreferences.updateResidualFatigueHalfLifeHours(hours)
override suspend fun updateResidualFatigueGain(value: Float) =
    physiologyPreferences.updateResidualFatigueGain(value)
```

- [ ] **Step 10: Run tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add app/src/main/proto/user_preferences.proto \
       core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/domain/validation/SettingsValidators.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/FeatureSettingsPorts.kt \
       feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsEvent.kt \
       app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt \
       app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapperExtensions.kt \
       app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializerExtensions.kt \
       app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsRepository.kt
git commit -m "feat: add Residual Fatigue settings infrastructure

Proto fields, UserPreferences, validation rules, DisplaySettings port,
events, DataStore writers. Settings exist but nothing reads them yet."
```

---

### Task 5: DB Migration 12→13 + Entity/Mapper Changes

**Files:**
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration12To13.kt`
- Modify: `core/database-schema/.../entity/DailySummaryEntity.kt:102`
- Modify: `core/model/.../domain/model/DailySummary.kt:59`
- Modify: `core/database/.../data/mapper/DailySummaryMapper.kt:124-146,173-195`
- Modify: `core/database/.../data/local/HealthDatabase.kt:103`
- Modify: `core/database/.../data/local/DatabaseMigrations.kt:6,191-203`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: `DailySummaryEntity.residualFatigue: Float?`, `DailySummary.residualFatigue: Float?`, bidirectional mapping in `DailySummaryMapper`

- [ ] **Step 1: Create Migration12To13**

Create `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration12To13.kt`:

```kotlin
package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN residualFatigue REAL DEFAULT NULL")
        }
    }
```

- [ ] **Step 2: Add field to DailySummaryEntity**

In `DailySummaryEntity.kt`, add after `napCount` (line 102):

```kotlin
val residualFatigue: Float? = null,
```

- [ ] **Step 3: Add field to DailySummary domain model**

In `DailySummary.kt`, add after `napCount` (line 59):

```kotlin
val residualFatigue: Float? = null,
```

- [ ] **Step 4: Add mapping in DailySummaryMapper**

In `DailySummaryMapper.kt`, add `residualFatigue = entity.residualFatigue` at the end of the `DailySummary.withLoadFields(entity: DailySummaryEntity)` method (after `napCount`, around line 145):

```kotlin
residualFatigue = entity.residualFatigue,
```

And in the reverse `DailySummaryEntity.withLoadFields(domain: DailySummary)` method (after `napCount`, around line 194):

```kotlin
residualFatigue = domain.residualFatigue,
```

- [ ] **Step 5: Bump DATABASE_VERSION**

In `HealthDatabase.kt`, change:

```kotlin
const val DATABASE_VERSION = 13
```

- [ ] **Step 6: Register migration**

In `DatabaseMigrations.kt`, add import:

```kotlin
import app.readylytics.health.core.database.data.local.migration.MIGRATION_12_13
```

Add to the `all` array:

```kotlin
val all: Array<Migration> =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
    )
```

- [ ] **Step 7: Run tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS. If `DailySummaryMapperTest` exists, it may need updating for the new field.

- [ ] **Step 8: Commit**

```bash
git add core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration12To13.kt \
       core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DailySummaryEntity.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/DailySummary.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/data/mapper/DailySummaryMapper.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrations.kt
git commit -m "feat: add residualFatigue column (DB migration 12→13)

Single nullable REAL column on daily_summaries. Bidirectional mapping in
DailySummaryMapper. NULL for all existing rows until scoring pipeline populates."
```

---

### Task 6: Wire Fatigue into Walk-Forward Scoring Pipeline

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt`
- Modify: `core/database-schema/.../dao/WorkoutDao.kt` — add `getFatigueWorkoutInputs()` query
- Modify: `core/database/.../data/repository/ScoringDayDataLoader.kt` — add `loadFatigueWorkoutInputs()`
- Modify: `core/model/.../domain/repository/ScoringRepository.kt` — add `fetchWalkForwardFatigueContext()`
- Modify: `core/database/.../data/repository/ScoringRepositoryImpl.kt` — implement fatigue context + compute
- Modify: `core/database/.../domain/sync/DailyRecomputeSupport.kt` — add fatigue context parameter
- Modify: `core/healthconnect/.../domain/sync/DailySyncUseCase.kt` — build + pass fatigue context
- Modify: `core/healthconnect/.../domain/sync/ResyncRangeUseCase.kt` — build + pass fatigue context
- Test: shadow mode verification (Readiness unchanged)

**Interfaces:**
- Consumes:
  - `ComputeResidualFatigueUseCase` (Task 3)
  - `ResidualFatigueConfig` (Task 3)
  - `UserPreferences.residualFatigueEnabled/HalfLifeHours/Gain` (Task 4)
  - `DailySummary.residualFatigue` (Task 5)
- Produces:
  - `WalkForwardFatigueContext(workoutsByEndTimeMs, accumulatedFatigue, lastEvaluationTimeMs)`
  - `ScoringRepository.fetchWalkForwardFatigueContext(startDate, endDate, zoneId)`
  - `DailyRecomputeSupport.recomputeDay(day, steps, prefs, trimpCtx, baselineCtx, fatigueCtx)` (6-arg overload)
  - `DailyRecomputeSupport.buildWalkForwardFatigueContext(startDate, endDate, zoneId)`
  - Populated `DailySummary.residualFatigue` after sync/resync

- [ ] **Step 1: Create WalkForwardFatigueContext**

Create `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt`:

```kotlin
package app.readylytics.health.core.model.domain.repository

data class FatigueWorkoutInput(
    val endTimeMs: Long,
    val trimp: Float,
)

class WalkForwardFatigueContext(
    val workoutsByEndTimeMs: List<FatigueWorkoutInput>,
) {
    var accumulatedFatigue: Double = 0.0
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE
    var workoutCursor: Int = 0
}
```

Note: This is a `class` not `data class` because it has mutable state (accumulator). The workout list is immutable; the accumulator state advances during walk-forward.

- [ ] **Step 2: Add WorkoutDao query for fatigue inputs**

In `WorkoutDao.kt`, add:

```kotlin
@Query(
    // Verified: both modelTrimp (user-selected TRIMP model) and trimp (Edwards zone-weighted)
    // are per-workout HR-integrated training load over the same workout boundary. The COALESCE
    // fallback is semantically safe for rows not yet backfilled with modelTrimp.
    "SELECT endTime AS endTimeMs, COALESCE(modelTrimp, trimp) AS trimp FROM workout_records " +
        "WHERE endTime >= :fromMs AND endTime <= :toMs " +
        "AND COALESCE(modelTrimp, trimp) > 0 " +
        "ORDER BY endTime ASC",
)
suspend fun getFatigueWorkoutInputs(
    fromMs: Long,
    toMs: Long,
): List<FatigueWorkoutInput>
```

This requires `FatigueWorkoutInput` to be importable by Room. Since `FatigueWorkoutInput` lives in `core/model` which `core/database-schema` depends on, this works. Room maps `endTimeMs` and `trimp` column aliases to the constructor parameters.

- [ ] **Step 3: Add ScoringDayDataLoader helper**

In `ScoringDayDataLoader.kt`, add:

```kotlin
suspend fun loadFatigueWorkoutInputs(fromMs: Long, toMs: Long): List<FatigueWorkoutInput> =
    workoutDao.getFatigueWorkoutInputs(fromMs, toMs)
```

Add import: `import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput`

- [ ] **Step 4: Add ScoringRepository interface method**

In `ScoringRepository.kt`, add:

```kotlin
suspend fun fetchWalkForwardFatigueContext(
    startDate: LocalDate,
    endDate: LocalDate,
    zoneId: ZoneId,
): WalkForwardFatigueContext
```

Add import: `import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext`

Also add a new overload of `computeAndPersistDailySummary` with fatigue context:

```kotlin
suspend fun computeAndPersistDailySummary(
    targetDate: LocalDate,
    steps: Long?,
    prefs: UserPreferences,
    trimpContext: WalkForwardTrimpContext,
    baselineContext: WalkForwardBaselineContext,
    fatigueContext: WalkForwardFatigueContext,
)
```

- [ ] **Step 5: Implement in ScoringRepositoryImpl**

Add `private val computeResidualFatigueUseCase = ComputeResidualFatigueUseCase()` field.

Implement `fetchWalkForwardFatigueContext()`:

```kotlin
override suspend fun fetchWalkForwardFatigueContext(
    startDate: LocalDate,
    endDate: LocalDate,
    zoneId: ZoneId,
): WalkForwardFatigueContext {
    val seedLookbackDays = 32L // 8 * 96h max half-life / 24
    val fromMs = startDate.minusDays(seedLookbackDays)
        .atStartOfDay(zoneId).toInstant().toEpochMilli()
    val toMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val workouts = dataLoader.loadFatigueWorkoutInputs(fromMs, toMs)
    return WalkForwardFatigueContext(workoutsByEndTimeMs = workouts)
}
```

Add new 6-arg `computeAndPersistDailySummary` overload (parallel to existing 5-arg):

```kotlin
override suspend fun computeAndPersistDailySummary(
    targetDate: LocalDate,
    steps: Long?,
    prefs: UserPreferences,
    trimpContext: WalkForwardTrimpContext,
    baselineContext: WalkForwardBaselineContext,
    fatigueContext: WalkForwardFatigueContext,
) = calculationMutex.withLock {
    val zoneId = prefs.scoringZone()
    val computed = computeDailySummary(targetDate, prefs, trimpContext, baselineContext, fatigueContext)
    val summary = if (steps != null) {
        computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    } else {
        computed
    }
    dataLoader.persistDailySummary(summary, zoneId)
}
```

Add `fatigueContext` parameter to the private `computeDailySummary`:

```kotlin
private suspend fun computeDailySummary(
    targetDate: LocalDate,
    prefs: UserPreferences,
    trimpContext: WalkForwardTrimpContext? = null,
    baselineContext: WalkForwardBaselineContext? = null,
    fatigueContext: WalkForwardFatigueContext? = null,
): DailySummary = withContext(defaultDispatcher) {
    // ... existing code through computeFinalSummary ...

    // After finalSummary is computed, before returning:
    val residualFatigue = computeResidualFatigue(context, fatigueContext, prefs)
    val summaryWithFatigue = finalSummary.copy(residualFatigue = residualFatigue)

    ScoringTelemetry.logTelemetry(/* unchanged */)
    summaryWithFatigue
}
```

Add the fatigue computation helper:

```kotlin
private fun computeResidualFatigue(
    context: ScoringDayContext,
    fatigueContext: WalkForwardFatigueContext?,
    prefs: UserPreferences,
): Float? {
    val config = ResidualFatigueConfig(
        enabled = prefs.residualFatigueEnabled,
        halfLifeHours = prefs.residualFatigueHalfLifeHours,
        fatigueGain = prefs.residualFatigueGain,
    )
    if (!config.enabled) return null

    val evalMs = context.nextDayMidnightMs

    if (fatigueContext != null) {
        // Walk-forward accumulator path: advance cursor + decay + add impulses
        val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
        // Decay from last evaluation
        if (fatigueContext.lastEvaluationTimeMs != Long.MIN_VALUE) {
            val elapsed = (evalMs - fatigueContext.lastEvaluationTimeMs).toDouble()
            fatigueContext.accumulatedFatigue *= 2.0.pow(-elapsed / halfLifeMs)
        }
        // Add impulses from workouts in (lastEval, currentEval]
        while (fatigueContext.workoutCursor < fatigueContext.workoutsByEndTimeMs.size) {
            val w = fatigueContext.workoutsByEndTimeMs[fatigueContext.workoutCursor]
            if (w.endTimeMs > evalMs) break
            if (fatigueContext.lastEvaluationTimeMs == Long.MIN_VALUE || w.endTimeMs > fatigueContext.lastEvaluationTimeMs) {
                val elapsed = (evalMs - w.endTimeMs).toDouble().coerceAtLeast(0.0)
                fatigueContext.accumulatedFatigue += config.fatigueGain * w.trimp * 2.0.pow(-elapsed / halfLifeMs)
            }
            fatigueContext.workoutCursor++
        }
        fatigueContext.lastEvaluationTimeMs = evalMs
        return fatigueContext.accumulatedFatigue.toFloat()
    }

    // Single-day fallback: summation over per-day query
    val lookbackMs = (8.0 * config.halfLifeHours * 3_600_000.0).toLong()
    val fromMs = evalMs - lookbackMs
    val workouts = runBlocking { dataLoader.loadFatigueWorkoutInputs(fromMs, evalMs) }
        .map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) }
    return computeResidualFatigueUseCase.compute(evalMs, workouts, config)
}
```

Wait — `runBlocking` is wrong here since we're already in a coroutine. The single-day fallback should be a `suspend` call. Restructure: the single-day path needs to be called from the suspend `computeDailySummary`, so `computeResidualFatigue` should be `suspend` when `fatigueContext` is null.

Actually, rethink: make `computeResidualFatigue` `suspend` and use `dataLoader.loadFatigueWorkoutInputs()` directly:

```kotlin
private suspend fun computeResidualFatigue(
    context: ScoringDayContext,
    fatigueContext: WalkForwardFatigueContext?,
    prefs: UserPreferences,
): Float? {
    val config = ResidualFatigueConfig(
        enabled = prefs.residualFatigueEnabled,
        halfLifeHours = prefs.residualFatigueHalfLifeHours,
        fatigueGain = prefs.residualFatigueGain,
    )
    if (!config.enabled) return null

    val evalMs = context.nextDayMidnightMs

    if (fatigueContext != null) {
        return advanceFatigueAccumulator(fatigueContext, evalMs, config)
    }

    // Single-day fallback: summation
    val lookbackMs = (8.0 * config.halfLifeHours * 3_600_000.0).toLong()
    val workouts = dataLoader.loadFatigueWorkoutInputs(evalMs - lookbackMs, evalMs)
    return computeResidualFatigueUseCase.compute(
        evalMs,
        workouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
        config,
    )
}

private fun advanceFatigueAccumulator(
    ctx: WalkForwardFatigueContext,
    evalMs: Long,
    config: ResidualFatigueConfig,
): Float {
    val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
    if (ctx.lastEvaluationTimeMs != Long.MIN_VALUE) {
        val elapsed = (evalMs - ctx.lastEvaluationTimeMs).toDouble()
        ctx.accumulatedFatigue *= 2.0.pow(-elapsed / halfLifeMs)
    }
    while (ctx.workoutCursor < ctx.workoutsByEndTimeMs.size) {
        val w = ctx.workoutsByEndTimeMs[ctx.workoutCursor]
        if (w.endTimeMs > evalMs) break
        if (ctx.lastEvaluationTimeMs == Long.MIN_VALUE || w.endTimeMs > ctx.lastEvaluationTimeMs) {
            val elapsed = (evalMs - w.endTimeMs).toDouble().coerceAtLeast(0.0)
            ctx.accumulatedFatigue += config.fatigueGain * w.trimp * 2.0.pow(-elapsed / halfLifeMs)
        }
        ctx.workoutCursor++
    }
    ctx.lastEvaluationTimeMs = evalMs
    return ctx.accumulatedFatigue.toFloat()
}
```

Also add the `fatigueContext` to `FinalSummaryInputs`:

```kotlin
private data class FinalSummaryInputs(
    // ... existing fields ...
    val fatigueContext: WalkForwardFatigueContext?,
)
```

Pass `fatigueContext` through the same path as `trimpContext` and `baselineContext`.

Add required imports: `import kotlin.math.pow`, `import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig`, `import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext`.

- [ ] **Step 6: Add fatigue context to DailyRecomputeSupport**

Add a new 6-arg overload of `recomputeDay`:

```kotlin
suspend fun recomputeDay(
    day: LocalDate,
    steps: Long?,
    prefs: UserPreferences,
    trimpContext: WalkForwardTrimpContext,
    baselineContext: WalkForwardBaselineContext,
    fatigueContext: WalkForwardFatigueContext,
): Result<Unit> =
    try {
        scoringRepository.computeAndPersistDailySummary(day, steps, prefs, trimpContext, baselineContext, fatigueContext)
        logD("DailyRecomputeSupport") {
            "Day $day: scored atomically (steps=${steps?.toString() ?: "preserved"})"
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logE("DailyRecomputeSupport", e) { "Day $day sync failed" }
        Result.failure("Day $day sync failed", "DAY_SYNC_ERROR")
    }
```

Add `buildWalkForwardFatigueContext`:

```kotlin
suspend fun buildWalkForwardFatigueContext(
    startDate: LocalDate,
    endDate: LocalDate,
    zoneId: ZoneId,
): WalkForwardFatigueContext = scoringRepository.fetchWalkForwardFatigueContext(startDate, endDate, zoneId)
```

Add import: `import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext`

- [ ] **Step 7: Wire into DailySyncUseCase**

In `DailySyncUseCase.kt`, after the `baselineContext` build (line ~224), add:

```kotlin
val fatigueContext =
    recomputeSupport.buildWalkForwardFatigueContext(oldestTargetDay, today, zoneId)
```

Change the `recomputeSupport.recomputeDay` call (line ~250-256) from the 5-arg to 6-arg overload:

```kotlin
val result =
    recomputeSupport.recomputeDay(
        dayToScore,
        steps,
        prefs,
        trimpContext,
        baselineContext,
        fatigueContext,
    )
```

- [ ] **Step 8: Wire into ResyncRangeUseCase**

In `ResyncRangeUseCase.kt`, after the `baselineContext` build (line ~427-432), add a parallel fatigue context build:

```kotlin
val fatigueContext =
    if (!recomputeStartDate.isAfter(endDate)) {
        recomputeSupport.buildWalkForwardFatigueContext(recomputeStartDate, endDate, zoneId)
    } else {
        null
    }
```

Change the 5-arg `recomputeDay` call (line ~469-476) to 6-arg:

```kotlin
val dayResult =
    if (trimpContext != null && baselineContext != null && fatigueContext != null) {
        recomputeSupport.recomputeDay(
            day,
            stepsForDay,
            prefs,
            trimpContext,
            baselineContext,
            fatigueContext,
        )
    } else {
        recomputeSupport.recomputeDay(day, stepsForDay, prefs)
    }
```

- [ ] **Step 9: Run tests to verify shadow mode (Readiness unchanged)**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`

Verify existing scoring determinism tests pass — this confirms Readiness values are unchanged. The `ScoringDeterminismRegressionTest` and `ScoringPointInTimeRegressionTest` are the key ones.

- [ ] **Step 10: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt \
       core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/WorkoutDao.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt \
       core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ScoringRepository.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt \
       core/database/src/main/kotlin/app/readylytics/health/core/database/domain/sync/DailyRecomputeSupport.kt \
       core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailySyncUseCase.kt \
       core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt
git commit -m "feat: wire Residual Fatigue into walk-forward scoring pipeline

Shadow mode: computed and persisted on DailySummary.residualFatigue but
does NOT affect Readiness. O(W+D) state-accumulator via mutable
WalkForwardFatigueContext. Single-day fallback uses summation formula."
```

---

### Task 7: Documentation Sync

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`

**Interfaces:**
- Consumes: all prior tasks
- Produces: updated documentation

- [ ] **Step 1: Update DATA_FLOW.md**

Add a new section documenting:
1. TRIMP normalization: Banister multiplier unified to 1.0 across profiles; DataStore migration for existing users
2. Residual Fatigue pipeline: workout TRIMP → fatigue impulse → exponential decay → daily snapshot on DailySummary
3. Walk-forward integration: WalkForwardFatigueContext with state accumulator
4. Shadow mode: persisted but not wired to Readiness

- [ ] **Step 2: Verify no other load-bearing docs need updates**

Check whether `ABOUT.md` mentions TRIMP multiplier defaults or profile-specific training load. If so, update. Phase 1 (shadow mode) does not require user-facing documentation for Residual Fatigue itself.

- [ ] **Step 3: Commit**

```bash
git add internal-docs/DATA_FLOW.md
# and ABOUT.md if updated
git commit -m "docs: update DATA_FLOW.md for TRIMP normalization and Residual Fatigue pipeline"
```

- [ ] **Step 4: Run pre-commit validation**

Run: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
Expected: all PASS

- [ ] **Step 5: Run lint**

Run: `./gradlew lintRelease 2>&1 | tail -10`
Expected: PASS (no new issues)
