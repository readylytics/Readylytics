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

Only `banisterMultiplier` is normalized. Cheng beta and iTRIMP B retain their profile-specific defaults — they control model shape, not magnitude scaling, and lack independent justification for unification at this time.

| Profile   | banisterMultiplier | chengBeta | itrimB |
|-----------|-------------------|-----------|--------|
| ATHLETE   | 1.0               | 0.07      | 2.9    |
| ACTIVE    | 1.0               | 0.09      | 2.1    |
| SEDENTARY | 1.0               | 0.11      | 1.5    |

`lnSigmaPrior` and `defaultSleepGoalHours` remain profile-specific (not TRIMP-related).

**Principle:** The Banister multiplier is a pure magnitude scaler on TRIMP output. Profile-dependent multipliers silently change training-load signal magnitude, making TRIMP values non-comparable across profiles. Normalizing to 1.0 makes TRIMP the canonical, profile-independent training-load signal. Users retain the ability to override the multiplier through advanced settings.

### Existing User Migration

`PhysiologyPreferences.updatePhysiologyProfile()` writes `profile.banisterMultiplier` (as `rasCalibration`), `profile.defaultChengBeta`, and `profile.defaultItrimB` to the proto DataStore at profile selection time.

**Strategy:** One-time DataStore migration keyed on a version flag (`trimpNormalizationMigrated: Boolean`).

Rules:
- New profile defaults are `1.0` for all profiles
- Cheng beta and iTRIMP B retain profile-specific defaults and are NOT normalized
- The one-time migration preserves every nonzero legacy stored multiplier because historical preference data cannot prove whether an old-default-valued field was explicitly overridden
- Stored multipliers of `0.0` (or unset/empty defaults) are normalized to `1.0`
- Set `trimpNormalizationMigrated = true` to prevent re-running

Migration runs at app startup before any scoring. The next sync/recompute picks up the new values.

### Affected Recalculation Paths

Every metric downstream of per-workout TRIMP changes when the Banister multiplier changes:

1. `ComputeWorkoutTrimpUseCase` — reads `prefs.banisterMultiplier` (affected), `chengBeta` / `itrimB` (unchanged)
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
| `core/model/.../PhysiologyProfile.kt` | Change `banisterMultiplier` to 1.0 for all profiles (Cheng/iTRIMP unchanged) |
| Proto schema (`user_preferences.proto`) | Add `trimp_normalization_migrated` bool field |
| `app/.../UserPreferencesMapper.kt` (or startup initializer) | Add Banister multiplier migration logic |
| Golden snapshot fixtures | Update expected values for Banister-model tests |

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
- **Canonical workout TRIMP source:** Impulses strictly use the user-selected canonical `modelTrimp` calculated by `ComputeWorkoutTrimpUseCase`. Edwards `trimp` is never used as a fallback. In-range impulses enter the accumulator only after same-pass calculation.
- Elapsed time in milliseconds internally, half-life in hours externally
- Superposition: workouts stack additively
- Rest days add no impulse, fatigue decays
- Workouts crossing midnight work correctly (continuous time, not calendar days)
- **Daily snapshot timing:** `evaluationTimeMs` for persisted daily values is set to next-day midnight (00:00 of day+1 in `scoringZoneId` via `ScoringDayContext.nextDayMidnightMs`). This represents fatigue state at the end of the scoring day — it captures all workouts that ended on or before that day and applies full overnight decay. This is the value stored in `DailySummaryEntity.residualFatigue`. The underlying model (`ComputeResidualFatigueUseCase.compute()`) accepts arbitrary `evaluationTimeMs` and can evaluate fatigue at any point in time — the daily snapshot is a persistence convenience, not a model limitation. Phase 2+ may evaluate at other timestamps (e.g., "current fatigue right now") using the same formula
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

Residual Fatigue reads from `WorkoutRecordEntity.endTime` and per-workout canonical `modelTrimp`. Edwards `trimp` is never an impulse source, and it never touches everyday-HR TRIMP data.

Changing `LoadSourceMode` does NOT change Residual Fatigue values.

---

## 4. Parameters

### Defaults (identical across all profiles)

| Parameter | Default | Range | Unit |
|-----------|---------|-------|------|
| `fatigueHalfLifeHours` | 24.0 | 6–96 | hours |
| `fatigueGain` | 1.0 | 0.1–5.0 | dimensionless |
| `enabled` | true | — | boolean |

**All values above are provisional calibration defaults and product guardrails.** They are not scientifically validated ranges. The defaults (24h half-life, 1.0 gain) were chosen as reasonable starting points for initial shadow-mode validation. They will be reviewed against real user data before Phase 2 promotion.

### Range Rationale (Product Guardrails)

These bounds prevent user-configured values from producing degenerate or misleading output:

- **Half-life 6h floor:** Sub-6h decay makes fatigue vanish within a single sleep cycle, rendering the metric meaninglessly volatile — it would never accumulate across training days.
- **Half-life 96h ceiling:** ~4 days. Beyond this, fatigue accumulation becomes indistinguishable from chronic training load (CTL), defeating the purpose of a separate acute fatigue signal.
- **Gain 0.1–5.0:** Prevents sign flip (gain > 0) and extreme sensitivity. 0.1 = heavily damped, 5.0 = amplified. The floor avoids a de-facto disable (use the toggle instead); the ceiling prevents unreasonable fatigue values that obscure real training patterns.

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

Changing any fatigue parameter (`residual_fatigue_enabled`, `residual_fatigue_half_life_hours`, `residual_fatigue_gain`, or resetting fatigue to defaults) invalidates resync checkpoints and triggers a historical recompute via `healthDataRefresh.refreshHistorical()`. The walk-forward recompute regenerates all `residualFatigue` values deterministically.

---

## 6. Persistence — Schema Changes

### Room Migration 12 → 13

```sql
ALTER TABLE daily_summaries ADD COLUMN residualFatigue REAL DEFAULT NULL;
```

Single column (not dual-variant). Residual Fatigue always uses workout-only TRIMP, independent of `LoadSourceMode`.

### Room Migration 13 → 14

```sql
CREATE INDEX IF NOT EXISTS index_workout_records_endTime_id ON workout_records (endTime, id);
```

Adds index on `workout_records(endTime, id)` supporting stable, deterministic canonical residual-fatigue impulse ordering used by exact retained-history reconstruction.

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

`HealthDatabase.DATABASE_VERSION` bumps to 14.

`DatabaseMigrations` includes `MIGRATION_12_13` (adding `residualFatigue`) and `MIGRATION_13_14` (adding `index_workout_records_endTime_id`).

---

## 7. Walk-Forward Integration

### Walk-Forward Fatigue Context (State Accumulator)

New file: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt`

```kotlin
data class WalkForwardFatigueContext(
    val pendingWorkouts: PriorityQueue<FatigueWorkoutInput>,
    var accumulatedFatigue: Double = 0.0,
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE,
    val registeredWorkoutIds: MutableSet<String> = mutableSetOf(),
)
```

**Exact retained-history seeding & same-pass registration:**
- **Historical seed:** `WorkoutDao.getCanonicalFatigueSeed` queries Room for all workouts with `startTime < walkForwardStartMs` and non-null positive `modelTrimp`, ordered by `(endTime ASC, id ASC)`. There is no bounded 32-day lookback window — all earlier retained canonical workouts are loaded.
- **In-range workouts:** Current-range workouts are not loaded from DB upfront. Instead, as each day's workouts are computed by `ComputeDailyTrimpUseCase`, `DailyTrimpComputer` emits canonical per-workout impulses and registers them with the accumulator via `registerWorkoutImpulse(workoutId, endTimeMs, modelTrimp)`. Registration is idempotent by workout ID.
- Partitioning by workout start keeps boundary-straddling workouts in the seed so they are neither recalculated nor omitted.

**State accumulator pattern — O(workouts + days):**

During walk-forward processing, the fatigue context maintains a running accumulated state. For each scoring day:

1. **Decay** accumulated fatigue from `lastEvaluationTimeMs` to current evaluation time: `accumulatedFatigue *= 2^(-(currentTimeMs - lastEvaluationTimeMs) / halfLifeMs)`
2. **Add impulses** for any pending workouts with `endTimeMs <= currentEvaluationTimeMs`: `accumulatedFatigue += fatigueGain * trimp`
3. **Persist** `accumulatedFatigue` as the day's `residualFatigue`
4. **Advance** `lastEvaluationTimeMs = currentEvaluationTimeMs`

**Equivalence:** The accumulator produces mathematically identical results to the summation formula `F(t) = Σ impulse_i * 2^(-(t - end_i) / halfLife)` — both are exact for exponential decay with superposition.

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
- `fetchWalkForwardFatigueContext()` queries `WorkoutDao` for historical seed workouts before `startDate` in `(endTime, id)` order
- `computeAndPersistDailySummary()` overload passes `fatigueContext` parameter
- Inside `computeDailySummary()`, after workouts are processed, advances the accumulator and reads the current fatigue value

For single-day recompute (no walk-forward context), the fallback path queries all retained canonical impulses with `endTime <= evaluationTimeMs` and sums them via `ComputeResidualFatigueUseCase.compute()`.

### DailyRecomputeSupport

`recomputeDay()` overloads pass `WalkForwardFatigueContext` through to `ScoringRepository`.

`buildWalkForwardFatigueContext()` added alongside existing `buildWalkForwardTrimpContext()` and `buildWalkForwardBaselineContext()`.

### Callers

`DailySyncUseCase` and `ResyncRangeUseCase` build walk-forward contexts before the day loop:
`fatigueContext = recomputeSupport.buildWalkForwardFatigueContext(...)`
and pass it through oldest-day-first. The fatigue context is mutable — each `recomputeDay` call advances its internal state.

---

## 8. Shadow Mode

### Phase 1 Behavior — Strictly Shadow-Only

Residual Fatigue is computed and persisted on every `computeAndPersistDailySummary()` call. **Zero Readiness behavior change.** This is a hard constraint, not a suggestion.

Current Readiness formula remains exactly:

```
Readiness = 0.40 * Restoration + 0.30 * Sleep + 0.30 * Load
```

No fourth pillar. No weight changes. No conditional logic that reads `residualFatigue` in the Readiness path. `computeReadinessScore()` must not be modified in any Phase 1 commit. The purpose of shadow mode is to validate the fatigue model against historical behavior before it influences any user-visible score.

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
- Sync range (partial vs full) — full, partial, resumed, incremental, and backfill paths reconstruct the exact same residual fatigue value for the same retained history.
- Sync order (chronological requirement satisfied by walk-forward)
- Chunk size (HC ingestion chunks don't affect persisted workout data)
- Active `LoadSourceMode` (fatigue always uses workout-only TRIMP)

### How It's Achieved

1. **Inputs are deterministic:** `WorkoutRecordEntity.endTime` and canonical `modelTrimp` are stable once ingested. TRIMP is recomputed deterministically from the same HR samples + settings without falling back to Edwards TRIMP.
2. **Formula is pure:** `ComputeResidualFatigueUseCase.compute()` is a stateless function over its inputs.
3. **Evaluation time is deterministic:** `nextDayMidnightMs` is derived from `targetDate` + `zoneId`, both fixed per scoring-day context.
4. **Exact retained-history seeding:** The context seeds from all earlier retained canonical workouts before `walkForwardStart`, so partial vs full resync produces the exact same context for any given day.

### Cold Start

`F(0) = 0` at the boundary of available workout history. This is a model boundary condition — we lack earlier training data, not proof the user was fully rested. The result is a temporary underestimation of true fatigue during the first ~2-3 half-lives (48-72h at default 24h).

This transient is inherent to any exponential-decay model initialized without prior state (ATL/CTL exhibit the same). It is acceptable because:
- For new users: Health Connect typically provides weeks of historical data; full resync reconstructs fatigue from all available workouts, so the underestimation window usually predates the first visible score.
- For existing users: the recompute walks forward from the earliest retained workout data. Underestimation only affects days near the retention boundary, which are rarely viewed.

Document this boundary condition in developer documentation. When the metric becomes user-visible (Phase 2+), add user-facing help text explaining that fatigue accuracy improves after the first few days of data.

---

## 10. Performance

### Computation Cost

- **Walk-forward N days (accumulator):** Seed query for retained history + O(W + D) where W = total workouts, D = days recomputed. In-range workouts are registered during the same pass as they are computed; each day performs decay multiplication + impulse additions.
- **Single-day recompute (fallback summation):** O(W_retained) where W_retained = retained canonical workouts at or before evaluation timestamp. Only used for individual day rescores outside walk-forward.
- **Incremental daily sync (1 day):** O(W_retained) via exact seed reconstruction. Negligible vs sleep/TRIMP computations.
- No raw-HR scan in any path.

### Data Flow

```
raw workout HR
      ↓
existing per-workout TRIMP (ComputeWorkoutTrimpUseCase)
      ↓
WorkoutRecordEntity.modelTrimp (already persisted)
      ↓
Residual Fatigue (reads canonical TRIMP, no HR re-scan)
```

No new scan over raw HR records. No 5-minute buckets. Residual Fatigue consumes already-derived data.

### Memory

Walk-forward fatigue context holds `PriorityQueue<FatigueWorkoutInput>` and registered ID set. 1000 workouts ≈ few tens of KB. Negligible.

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

`ResidualFatigueDeterminismTest` (integration-level):

| # | Test | Verifies |
|---|------|----------|
| 10 | Incremental vs full resync | Identical residualFatigue values |
| 11 | Partial resync/backfill | Same result as full |
| 12 | Settings change (half-life) | Recompute produces correct new values |
| 13 | Settings change (gain) | Recompute produces correct new values |
| 14 | TRIMP multiplier change | Fatigue changes proportionally |
| 15 | Profile change | Fatigue uses new TRIMP, same fatigue params |
| 16 | LoadSourceMode change | Fatigue unchanged |

`TrimpNormalizationMigrationTest`:

| # | Test | Verifies |
|---|------|----------|
| 17 | Nonzero stored multiplier (e.g. 1.35, 1.75, 1.50) | Preserved across migration |
| 18 | Default / zero multiplier | Normalized to 1.00 |
| 19 | ATHLETE user with 1.00 | Stays at 1.00 |
| 20 | Migration flag prevents double-run | Second call is no-op |
| 21 | Cheng beta unchanged by migration | Profile-specific values preserved |
| 22 | iTRIMP B unchanged by migration | Profile-specific values preserved |

### Debug/Comparison Mechanism

`ResidualFatigueDebugUseCase`: given a date range, returns rows of:

```
(date, residualFatigue, workoutTrimp, ATL, CTL, strainRatio, loadScore, readiness, zHrv, zRhr, sleepScore)
```

Pure query over stored `DailySummary` rows. No cloud telemetry. Callable from a debug settings screen or ADB command.

---

## 12. Commit Sequence

### Commit 1: Normalize Banister Multiplier in Enum

**Purpose:** Change `PhysiologyProfile.banisterMultiplier` to 1.0 for all profiles. `defaultChengBeta` and `defaultItrimB` remain profile-specific.

**Files:**
- `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/PhysiologyProfile.kt`

**Tests:** Existing tests that reference `PhysiologyProfile.ACTIVE.banisterMultiplier` etc. must update expected values. Tests referencing `defaultChengBeta` / `defaultItrimB` should remain unchanged.

**Behavior change:** None. The enum defaults only take effect when `updatePhysiologyProfile()` is called (new profile selection). Existing stored prefs unchanged.

### Commit 2: DataStore Migration for Existing Users

**Purpose:** Migrate stored Banister multiplier to 1.0 for users who haven't manually customized it. Cheng beta and iTRIMP B are not migrated.

**Files:**
- Proto schema — add `trimp_normalization_migrated` field
- `app/.../UserPreferencesMapper.kt` or equivalent — migration logic
- Golden snapshot test fixtures — update expected TRIMP/load values

**Tests:** `TrimpNormalizationMigrationTest` (tests 18-24 above)

**Behavior change:** Yes. On next app launch + recompute, ACTIVE/SEDENTARY users with Banister model selected see changed TRIMP values and all downstream metrics. Users on Cheng or iTRIMP models: no change (their multiplier was already profile-independent or not the Banister multiplier).

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
3. **Canonical modelTrimp source:** Residual fatigue impulses strictly consume `modelTrimp` calculated per workout by `ComputeWorkoutTrimpUseCase`. Edwards `trimp` is never used as a fallback.

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
