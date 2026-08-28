# Residual Fatigue Foundation — Design Spec

## Goal

Introduce a timestamp-aware Residual Fatigue model as an internal/shadow metric while:

- Keeping current Readiness behavior unchanged
- Keeping existing Strain/load behavior unchanged (except TRIMP default normalization)
- Avoiding duplicate/raw-HR processing
- Supporting deterministic incremental and historical recomputation
- Exposing model parameters through advanced settings

---

## 1. TRIMP Default Normalization

### Current State

`PhysiologyProfile.kt` enum defines per-profile TRIMP parameter defaults:

| Profile   | banisterMultiplier | chengBeta | itrimB |
|-----------|-------------------|-----------|--------|
| ATHLETE   | 1.00              | 0.07      | 2.9    |
| ACTIVE    | 1.35              | 0.09      | 2.1    |
| SEDENTARY | 1.75              | 0.11      | 1.5    |

### New Defaults

All three profiles get identical TRIMP parameters:

| Profile   | banisterMultiplier | chengBeta | itrimB |
|-----------|-------------------|-----------|--------|
| ATHLETE   | 1.0               | 0.09      | 2.1    |
| ACTIVE    | 1.0               | 0.09      | 2.1    |
| SEDENTARY | 1.0               | 0.09      | 2.1    |

`lnSigmaPrior` and `defaultSleepGoalHours` remain profile-specific (not TRIMP-related).

**Principle:** TRIMP is the canonical training-load signal and must not silently change magnitude because of physiology profile selection. All three TRIMP model parameters (Banister multiplier, Cheng beta, iTRIMP B) follow this principle. Users retain the ability to override any parameter through advanced settings.

### Existing User Migration

`PhysiologyPreferences.updatePhysiologyProfile()` writes `profile.banisterMultiplier` (as `rasCalibration`), `profile.defaultChengBeta`, and `profile.defaultItrimB` to the proto DataStore at profile selection time. Existing users have old defaults baked into their stored preferences.

**Strategy:** One-time DataStore migration keyed on a version flag (`trimpNormalizationMigrated: Boolean`).

Rules:
- If stored `rasCalibration` matches old profile default (1.00 / 1.35 / 1.75), reset to 1.0
- If stored `chengBeta` matches old default (0.07 / 0.09 / 0.11), reset to 0.09
- If stored `itrimpB` matches old default (2.9 / 2.1 / 1.5), reset to 2.1
- If stored value does not match any old default, user customized it — leave it alone
- Set `trimpNormalizationMigrated = true` to prevent re-running

Migration runs at app startup (in the existing `DatabaseReadyStartupInitializer` or equivalent preference-migration path) before any scoring. The next sync/recompute picks up the new values.

### Affected Recalculation Paths

Every metric downstream of per-workout TRIMP changes when the multiplier changes:

1. `ComputeWorkoutTrimpUseCase` — reads `prefs.banisterMultiplier` / `chengBeta` / `itrimB`
2. `ComputeDailyTrimpUseCase` — sums per-workout TRIMP
3. `WorkoutRecordEntity.modelTrimp` — lazily backfilled on next walk-forward recompute
4. `BuildLoadSeriesUseCase` — ATL / CTL / strainRatio / loadScore
5. `RasCalculator.calculateDailyTrimp` — daily RAS
6. `RasTotalsComputer` — rolling RAS totals
7. Load contribution to Readiness via `computeReadinessScore()`

Impact: ACTIVE users see TRIMP decrease ~26% (1.35 → 1.0). SEDENTARY users see ~43% decrease (1.75 → 1.0). ATHLETE users: no change (already 1.0). All downstream metrics (ATL, CTL, strain ratio, load score, RAS, Readiness load component) shift proportionally.

### Files Modified

| File | Change |
|------|--------|
| `core/model/.../PhysiologyProfile.kt` | Change enum parameter defaults |
| Proto schema (`user_preferences.proto`) | Add `trimp_normalization_migrated` bool field |
| `app/.../UserPreferencesMapper.kt` (or startup initializer) | Add migration logic |
| Golden snapshot fixtures | Update expected values |

---

## 2. Residual Fatigue Domain Model

### Types

New file: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/ResidualFatigueConfig.kt`

```kotlin
data class ResidualFatigueConfig(
    val enabled: Boolean = true,
    val halfLifeHours: Float = 24f,
    val fatigueGain: Float = 1.0f,
)
```

### Formula

Each recorded workout creates a fatigue impulse:

```
impulse_i = fatigueGain * workoutTrimp_i
```

Residual Fatigue at evaluation time `t`:

```
F(t) = Σ impulse_i * 2^(-(t - workoutEnd_i) / halfLifeHours)
```

where:
- `t` and `workoutEnd_i` are in hours (converted from epoch millis)
- Summation is over all workouts where `workoutEnd_i <= t`
- `workoutTrimp_i` is the per-workout `modelTrimp` (user-selected TRIMP model value)

Equivalent: `F(t) = Σ impulse_i * exp(-ln(2) * (t - workoutEnd_i) / halfLifeHours)`

### Use Case

New file: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCase.kt`

```kotlin
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
            val decay = 2.0.pow(-elapsedMs / halfLifeMs)
            fatigue += config.fatigueGain * w.trimp * decay
        }
        return fatigue.toFloat()
    }
}
```

### Properties

- Pure Kotlin, zero Android dependencies
- Uses `WorkoutRecordEntity.endTime` as impulse timestamp
- Uses `COALESCE(modelTrimp, trimp)` as per-workout TRIMP (handles pre-v6 rows)
- Elapsed time in milliseconds internally, half-life in hours externally
- Superposition: workouts stack additively
- Rest days add no impulse, fatigue decays
- Workouts crossing midnight work correctly (continuous time, not calendar days)
- `evaluationTimeMs` = next-day midnight for daily snapshot storage (end of the scoring day)
- Zero TRIMP and future workouts contribute nothing
- When disabled, returns 0f

### Internal Semantics

```
0   = no modeled residual training fatigue
>0  = higher = more residual training fatigue
```

Not inverted. Not normalized to 0-100. Raw fatigue state stored as-is.

---

## 3. Residual Fatigue Input Guarantee

Residual Fatigue always uses **workout-only TRIMP**, regardless of `LoadSourceMode`.

The existing codebase computes per-workout TRIMP through `ComputeWorkoutTrimpUseCase` → `ComputeDailyTrimpUseCase` regardless of the selected load source mode. `WorkoutRecordEntity.modelTrimp` stores the result. Everyday-HR TRIMP is a separate, independent computation.

Residual Fatigue reads from `WorkoutRecordEntity.endTime` and `COALESCE(modelTrimp, trimp)`. It never touches everyday-HR TRIMP data.

Changing `LoadSourceMode` does NOT change Residual Fatigue values.

---

## 4. Parameters

### Defaults (identical across all profiles)

| Parameter | Default | Range | Unit |
|-----------|---------|-------|------|
| `fatigueHalfLifeHours` | 24.0 | 6–96 | hours |
| `fatigueGain` | 1.0 | 0.1–5.0 | dimensionless |
| `enabled` | true | — | boolean |

These are product/calibration defaults, not scientifically proven universal constants.

### Range Rationale

- **Half-life 6h floor:** Prevents numerical instability. Sub-6h recovery makes the metric meaninglessly volatile.
- **Half-life 96h ceiling:** ~4 days. Covers even very slow recovery kinetics. Beyond this, the metric becomes indistinguishable from CTL (chronic training load).
- **Gain 0.1–5.0:** Allows meaningful scaling without sign flip. 0.1 = heavily damped, 5.0 = amplified sensitivity.

### Effective Value Hierarchy

```
1. User override (stored in DataStore proto)  ← highest priority
2. Profile default (all profiles: 24h / 1.0)  ← fallback
3. System default (hardcoded in ResidualFatigueConfig) ← compile-time
```

Levels 2 and 3 are currently identical. If per-profile defaults are introduced later, level 2 diverges.

---

## 5. Settings

### DataStore Proto Additions

```proto
bool residual_fatigue_enabled = <next_field>;
float residual_fatigue_half_life_hours = <next_field>;
float residual_fatigue_gain = <next_field>;
```

### UserPreferences Additions

```kotlin
val residualFatigueEnabled: Boolean = true,
val residualFatigueHalfLifeHours: Float = 24f,
val residualFatigueGain: Float = 1.0f,
```

### Validation Rules (SettingsValidators)

```kotlin
val FATIGUE_HALF_LIFE_RULE = FloatRangeRule(6f, 96f, "Half-life: 6–96 hours")
val FATIGUE_GAIN_RULE = FloatRangeRule(0.1f, 5.0f, "Gain: 0.1–5.0")
```

### Settings Events (SettingsEvent)

```kotlin
data class ResidualFatigueEnabledChanged(val enabled: Boolean) : SettingsEvent
data class ResidualFatigueHalfLifeChanged(val hours: Float) : SettingsEvent
data class ResidualFatigueGainChanged(val value: Float) : SettingsEvent
data object ResetFatigueToDefaults : SettingsEvent
```

### Settings Port

Add to `DisplaySettings` interface (where TRIMP model/parameters already live):

```kotlin
suspend fun updateResidualFatigueEnabled(enabled: Boolean)
suspend fun updateResidualFatigueHalfLifeHours(hours: Float)
suspend fun updateResidualFatigueGain(value: Float)
```

### Recomputation Trigger

Changing any fatigue parameter triggers `HealthSyncUseCase.recomputeRange()` over the full retained history (same pattern as TRIMP model changes via `SettingsEvent.RecalculateScores`). The walk-forward recompute regenerates all `residualFatigue` values deterministically.

---

## 6. Persistence — Schema Changes

### Room Migration 12 → 13

```sql
ALTER TABLE daily_summaries ADD COLUMN residualFatigue REAL DEFAULT NULL;
```

Single column (not dual-variant). Residual Fatigue always uses workout-only TRIMP, independent of `LoadSourceMode`.

### Entity Changes

`DailySummaryEntity.kt` — add:
```kotlin
val residualFatigue: Float? = null,
```

`DailySummary.kt` (domain model) — add:
```kotlin
val residualFatigue: Float? = null,
```

`DailySummaryMapper.kt` — add to `withLoadFields()`:
```kotlin
residualFatigue = entity.residualFatigue,
// (and reverse direction)
```

### Backup/Restore

`DailySummaryEntity` is `@Serializable`. The new nullable field with default `null` is backwards-compatible:
- Newer app restoring older backup: field absent → null (correct, will be recomputed)
- Older app restoring newer backup: unknown field ignored by kotlinx.serialization (safe)

No backup format version bump needed.

### Database Version

`HealthDatabase.DATABASE_VERSION` bumps from 12 to 13.

`DatabaseMigrations` gains `MIGRATION_12_13` with the single ALTER TABLE statement.

---

## 7. Walk-Forward Integration

### Walk-Forward Fatigue Context

New file: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt`

```kotlin
data class WalkForwardFatigueContext(
    val workouts: List<ComputeResidualFatigueUseCase.FatigueWorkoutInput>,
)
```

Prefetched once per walk-forward from `WorkoutDao`:
- Range: `[walkForwardStart - maxLookbackDays, walkForwardEnd]`
- Max lookback = 8 * maxHalfLifeHours / 24 = 8 * 96 / 24 = 32 days
- Query: `SELECT endTime, COALESCE(modelTrimp, trimp) FROM workout_records WHERE endTime >= ? AND endTime <= ? ORDER BY endTime`
- Mapped to `FatigueWorkoutInput(endTimeMs, trimp)`

### ScoringRepository Integration

`ScoringRepository` interface gains:
```kotlin
suspend fun fetchWalkForwardFatigueContext(
    startDate: LocalDate,
    endDate: LocalDate,
    zoneId: ZoneId,
): WalkForwardFatigueContext
```

`ScoringRepositoryImpl`:
- `fetchWalkForwardFatigueContext()` queries `WorkoutDao` with the lookback window
- `computeAndPersistDailySummary()` 5-arg overload gains optional `fatigueContext` parameter
- Inside `computeDailySummary()`, after workouts are processed:

```kotlin
val fatigueConfig = ResidualFatigueConfig(
    enabled = prefs.residualFatigueEnabled,
    halfLifeHours = prefs.residualFatigueHalfLifeHours,
    fatigueGain = prefs.residualFatigueGain,
)
val residualFatigue = computeResidualFatigueUseCase.compute(
    evaluationTimeMs = context.nextDayMidnightMs,
    workouts = fatigueContext?.workouts
        ?.filter { it.endTimeMs <= context.nextDayMidnightMs }
        ?: loadWorkoutsForFatigue(context),
    config = fatigueConfig,
)
```

The result is set on the `DailySummary` before persistence.

### DailyRecomputeSupport

`recomputeDay()` overloads gain an optional `WalkForwardFatigueContext` parameter, passed through to `ScoringRepository`.

`buildWalkForwardFatigueContext()` added alongside existing `buildWalkForwardTrimpContext()` and `buildWalkForwardBaselineContext()`.

### Callers

`DailySyncUseCase` and `ResyncRangeUseCase` already build walk-forward contexts before the day loop. They add a `fatigueContext = recomputeSupport.buildWalkForwardFatigueContext(...)` call alongside the existing TRIMP and baseline context builds, and pass it through.

Single-day recompute (no walk-forward context) falls back to a per-day workout query internally.

---

## 8. Shadow Mode

### Phase 1 Behavior

Residual Fatigue is computed and persisted on every `computeAndPersistDailySummary()` call but does NOT affect Readiness.

Current Readiness formula remains:

```
Readiness = 0.40 * Restoration + 0.30 * Sleep + 0.30 * Load
```

No fourth pillar. No weight changes. `computeReadinessScore()` unchanged.

### Future Architecture (NOT implemented)

```
Readiness
├── Restoration  40%
├── Sleep        30%
└── Load         30%
      ├── existing Load Score (ATL/CTL/strain)
      └── Residual Fatigue
```

Eventually: `LoadReadiness = f(LoadScore, ResidualFatigue)`. Weights and function TBD.

### What Phase 1 Enables

- Validate Residual Fatigue against historical data
- Compare with ATL/CTL/strain/HRV trends
- Tune default parameters before assigning Readiness weight
- Debug via the lightweight comparison mechanism (Section 11)

---

## 9. Deterministic Recalculation

### Hard Requirement

> Same workout history + same settings + same evaluation timestamp = identical Residual Fatigue.

### Independence Guarantees

Results must NOT depend on:
- Sync range (partial vs full)
- Sync order (chronological requirement satisfied by walk-forward)
- Chunk size (HC ingestion chunks don't affect persisted workout data)
- Active `LoadSourceMode` (fatigue always uses workout-only TRIMP)

### How It's Achieved

1. **Inputs are deterministic:** `WorkoutRecordEntity.endTime` and `COALESCE(modelTrimp, trimp)` are stable once ingested. TRIMP is recomputed deterministically from the same HR samples + settings.
2. **Formula is pure:** `ComputeResidualFatigueUseCase.compute()` is a stateless function over its inputs.
3. **Evaluation time is deterministic:** `nextDayMidnightMs` is derived from `targetDate` + `zoneId`, both fixed per scoring-day context.
4. **Walk-forward context is complete:** The prefetched workout list covers the full lookback window, so partial vs full resync produces the same context for any given day.

### Cold Start

When workout history begins, `F(0) = 0` before any workouts. This is mathematically correct: no prior training = no residual fatigue.

**Warm-up transient:** The first ~2-3 half-lives (48-72h at default 24h) show lower-than-steady-state values. This is inherent to the exponential-decay model and is the same transient ATL/CTL exhibit. Document this in user-facing help text when the metric becomes visible.

If the user has pre-existing workout history (e.g., first install with Health Connect data), the full historical resync reconstructs fatigue from all available workouts — no warm-up artifact beyond the earliest available data.

---

## 10. Performance

### Computation Cost

- **Historical reconstruction:** O(W) where W = number of workouts in lookback window. Max lookback = 32 days. Typical W = 30-60 workouts (1-2/day). Negligible.
- **Incremental daily sync:** O(W) with W = workouts in 32-day window. Same cost. No raw-HR scan.
- **Walk-forward N days:** One prefetch query + N * O(W_day) evaluations where W_day = workouts in lookback. Total O(N * W_max) where W_max ≈ 60. Negligible vs the sleep/TRIMP/baseline computations that dominate each day.

### Data Flow

```
raw workout HR
      ↓
existing per-workout TRIMP (ComputeWorkoutTrimpUseCase)
      ↓
WorkoutRecordEntity.modelTrimp (already persisted)
      ↓
Residual Fatigue (reads persisted TRIMP, no HR re-scan)
```

No new scan over raw HR records. No 5-minute buckets. Residual Fatigue consumes already-derived data.

### Memory

Walk-forward fatigue context holds `List<FatigueWorkoutInput>` — two fields per workout (Long + Float = 12 bytes). 1000 workouts = 12 KB. Negligible.

---

## 11. Test Strategy

### Unit Tests (pure Kotlin, zero Android deps)

`ComputeResidualFatigueUseCaseTest`:

| # | Test | Verifies |
|---|------|----------|
| 1 | Single workout: increase then decay | F(end) = gain*trimp; F(end+halfLife) = gain*trimp/2 |
| 2 | Multiple workouts: impulses stack | Superposition: F = sum of individual contributions |
| 3 | Rest day: decay only | No new impulse, fatigue decreases |
| 4 | Consecutive hard days: accumulation | Each day adds impulse on top of decaying prior |
| 5 | Same TRIMP at 06:00 vs 21:00 | Different next-morning fatigue (18h vs 3h decay) |
| 6 | Workout crossing midnight | endTime determines impulse timing, correct result |
| 7 | Zero TRIMP | Contributes nothing to sum |
| 8 | Empty workout list | F = 0 |
| 9 | Disabled config | Returns 0f |
| 10 | Missing/incomplete workout (no modelTrimp) | Falls back to zone-weighted trimp via COALESCE |

`ResidualFatigueDeterminismTest` (integration-level):

| # | Test | Verifies |
|---|------|----------|
| 11 | Incremental vs full resync | Identical residualFatigue values |
| 12 | Partial resync/backfill | Same result as full |
| 13 | Settings change (half-life) | Recompute produces correct new values |
| 14 | Settings change (gain) | Recompute produces correct new values |
| 15 | TRIMP multiplier change | Fatigue changes proportionally |
| 16 | Profile change | Fatigue uses new TRIMP, same fatigue params |
| 17 | LoadSourceMode change | Fatigue unchanged |

`TrimpNormalizationMigrationTest`:

| # | Test | Verifies |
|---|------|----------|
| 18 | ACTIVE user with default 1.35 | Migrates to 1.0 |
| 19 | SEDENTARY user with default 1.75 | Migrates to 1.0 |
| 20 | ATHLETE user with 1.00 | Stays at 1.00 |
| 21 | User who customized to 1.50 | Keeps 1.50 (not a known default) |
| 22 | Migration flag prevents double-run | Second call is no-op |
| 23 | Cheng beta migration (0.07 → 0.09) | Migrates if matches old default |
| 24 | iTRIMP B migration (2.9 → 2.1) | Migrates if matches old default |

### Debug/Comparison Mechanism

`ResidualFatigueDebugUseCase`: given a date range, returns rows of:

```
(date, residualFatigue, workoutTrimp, ATL, CTL, strainRatio, loadScore, readiness, zHrv, zRhr, sleepScore)
```

Pure query over stored `DailySummary` rows. No cloud telemetry. Callable from a debug settings screen or ADB command.

---

## 12. Commit Sequence

### Commit 1: Normalize TRIMP Defaults in Enum

**Purpose:** Change `PhysiologyProfile` enum parameter defaults to unified values.

**Files:**
- `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/PhysiologyProfile.kt`

**Tests:** Existing tests that reference `PhysiologyProfile.ACTIVE.banisterMultiplier` etc. must update expected values.

**Behavior change:** None. The enum defaults only take effect when `updatePhysiologyProfile()` is called (new profile selection). Existing stored prefs unchanged.

### Commit 2: DataStore Migration for Existing Users

**Purpose:** Migrate stored TRIMP parameters to new unified defaults for users who haven't manually customized them.

**Files:**
- Proto schema — add `trimp_normalization_migrated` field
- `app/.../UserPreferencesMapper.kt` or equivalent — migration logic
- Golden snapshot test fixtures — update expected TRIMP/load values

**Tests:** `TrimpNormalizationMigrationTest` (tests 18-24 above)

**Behavior change:** Yes. On next app launch + recompute, ACTIVE/SEDENTARY users see changed TRIMP values and all downstream metrics.

### Commit 3: Residual Fatigue Domain Model + Use Case

**Purpose:** Add `ResidualFatigueConfig` and `ComputeResidualFatigueUseCase` with full test coverage.

**Files:**
- `core/model/.../domain/scoring/ResidualFatigueConfig.kt` (new)
- `core/scoring/.../domain/scoring/ComputeResidualFatigueUseCase.kt` (new)
- `core/scoring/src/test/.../ComputeResidualFatigueUseCaseTest.kt` (new)

**Tests:** Tests 1-10 above.

**Behavior change:** None. New code, not wired into scoring pipeline.

### Commit 4: Fatigue Settings Infrastructure

**Purpose:** Add fatigue settings to DataStore proto, UserPreferences, validators, settings ports, and events.

**Files:**
- Proto schema — add `residual_fatigue_enabled`, `residual_fatigue_half_life_hours`, `residual_fatigue_gain`
- `core/model/.../domain/preferences/UserPreferences.kt` — add fields
- `core/model/.../domain/validation/SettingsValidators.kt` — add rules
- `core/model/.../domain/preferences/FeatureSettingsPorts.kt` — add to `DisplaySettings`
- `feature/settings/.../SettingsEvent.kt` — add events
- `app/.../UserPreferencesMapper.kt` — map proto ↔ domain
- `app/.../SettingsRepository.kt` — implement new settings methods

**Tests:** Validator edge-case tests, settings round-trip tests.

**Behavior change:** None. Settings exist but nothing reads them yet.

### Commit 5: DB Migration 12→13 + Entity/Mapper Changes

**Purpose:** Add `residualFatigue` column to `daily_summaries` table.

**Files:**
- `core/database-schema/.../entity/DailySummaryEntity.kt` — add field
- `core/model/.../domain/model/DailySummary.kt` — add field
- `core/database/.../mapper/DailySummaryMapper.kt` — add to `withLoadFields()`
- `core/database/.../local/HealthDatabase.kt` — bump version to 13
- `core/database/.../local/DatabaseMigrations.kt` — add `MIGRATION_12_13`
- `core/database/.../local/migration/Migration12To13.kt` (new) — ALTER TABLE SQL
- Backup serialization — verified compatible (nullable field with default)

**Tests:** `DatabaseMigrationTest` — verify migration preserves existing data and adds column. `DailySummaryEntitySerializationTest` — verify serialization round-trip.

**Behavior change:** None. Column is NULL for all rows.

### Commit 6: Wire Fatigue into Walk-Forward Scoring

**Purpose:** Compute and persist Residual Fatigue in the daily scoring pipeline (shadow mode).

**Files:**
- `core/model/.../repository/WalkForwardFatigueContext.kt` (new)
- `core/model/.../repository/ScoringRepository.kt` — add `fetchWalkForwardFatigueContext()`
- `core/database/.../repository/ScoringRepositoryImpl.kt` — implement fatigue computation in `computeDailySummary()`
- `core/database/.../domain/sync/DailyRecomputeSupport.kt` — add fatigue context parameter
- `core/healthconnect/.../sync/DailySyncUseCase.kt` — build and pass fatigue context
- `core/healthconnect/.../sync/ResyncRangeUseCase.kt` — build and pass fatigue context
- `core/database-schema/.../dao/WorkoutDao.kt` — add query for fatigue workout inputs
- `core/scoring/.../domain/scoring/ResidualFatigueDebugUseCase.kt` (new)

**Tests:** Tests 11-17 above (determinism tests). Integration test verifying fatigue appears in persisted DailySummary after sync. Test that Readiness values are unchanged (shadow mode verification).

**Behavior change:** `residualFatigue` column populated with values after sync/resync. Readiness unchanged.

### Commit 7: Documentation Sync

**Purpose:** Update load-bearing documentation.

**Files:**
- `internal-docs/DATA_FLOW.md` — add Residual Fatigue section, update TRIMP flow diagram, document TRIMP normalization
- `ABOUT.md` — document TRIMP normalization rationale, Residual Fatigue model (shadow status)

**Tests:** `DocumentationDriftTest` — if it checks TRIMP defaults, update expectations.

**Behavior change:** None.

---

## 13. Future-Proofing

The model is deliberately simple now. Architecture must not prevent later investigation of:

- Individual half-life calibration (per-user fitting)
- Workout-type-specific recovery (`halfLifeByExerciseType: Map<ExerciseType, Float>`)
- Intensity/duration-dependent recovery (non-linear impulse function)
- Multiple fatigue timescales (fast/slow components, Banister fitness-fatigue model)
- HRV/RHR-informed calibration (feedback loop adjusting half-life based on observed recovery)
- Sleep/recovery interaction (modulating decay rate based on sleep quality)
- Subjective recovery data (RPE, perceived fatigue)

**How the current design supports this:**

- `ComputeResidualFatigueUseCase` is a standalone pure function — easy to replace or extend
- `FatigueWorkoutInput` can be extended with exercise type, duration, intensity
- `ResidualFatigueConfig` can grow new parameters without breaking existing defaults
- Storing raw fatigue (not normalized 0-100) preserves dynamic range for future mappings
- The `fatigueGain` multiplier provides a scaling lever without formula changes
- Separate `WalkForwardFatigueContext` allows enriching workout data without touching TRIMP context

None of the above is implemented now.

---

## 14. Remaining Decisions / Risks

### Must Resolve Before Implementation

1. **Proto field numbers:** Exact field numbers for new proto fields must be assigned from the project's field-number registry to avoid collisions.
2. **SettingsDefaults constants location:** Decide whether fatigue defaults live in `SettingsDefaults` object (alongside `TRIMP_MODEL`) or inline in `ResidualFatigueConfig`. Recommend `SettingsDefaults` for consistency.

### Risks

1. **Golden snapshot tests:** TRIMP normalization in commits 1-2 changes all golden fixture expected values. These tests exist specifically to catch unintended scoring changes. Must update fixtures and add a comment explaining the intentional change.
2. **Backup forward-compatibility:** Confirmed safe — `@Serializable` with nullable defaults handles unknown fields gracefully. But verify with an explicit test (restore a backup created by the current app version after the schema change).
3. **Migration ordering:** The DataStore TRIMP migration (commit 2) and Room DB migration (commit 5) are independent. DataStore migration must run before the first scoring computation. Room migration runs at database open. Both happen at app startup — verify ordering.
4. **Recompute after TRIMP migration:** The DataStore migration itself doesn't trigger a recompute. **Decision: auto-trigger.** After migration, enqueue a one-time `HealthRecomputeWorker` (OneTimeWork, unique name, KEEP policy) that calls `HealthSyncUseCase.recomputeRange()` over the full retained history. User sees correct values on next app open. This worker follows the same pattern as the existing `HealthResyncWorker` but skips HC ingestion (recompute-only via `skipIngestAndPrune = true`).

---

## Data Flow Summary

```
                              ┌→ existing workout load path (ATL/CTL/strain/load)
Recorded workouts → TRIMP ───┤
                              └→ Residual Fatigue
                                        │
                                        │ shadow mode (Phase 1)
                                        │ persisted on DailySummary
                                        │ NOT wired to Readiness
                                        ▼
                                future Load Readiness (Phase 2+)

Everyday HR → Everyday TRIMP → existing load path only (never feeds Residual Fatigue)
```
