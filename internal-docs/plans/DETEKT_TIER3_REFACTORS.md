# Detekt Tier 3 — Real Refactors

**Status:** not started · **Created:** 2026-08-21 · **Owner:** unassigned
**Parent:** `DETEKT_BASELINE_BURNDOWN.md` §6, Tier 3
**Scope:** 360 baseline entries across 7 rules. These are the actual architecture debt — each fix is a genuine code decomposition.

---

## Rules in scope

| Entries | Rule | Fix pattern |
|--------:|------|-------------|
| 185 | `LongMethod` | Extract helper functions. No expression rewriting — relocate only. |
| 59 | `TooManyFunctions` | Extract cohesive function groups into collaborator classes or extension-function files. |
| 50 | `CyclomaticComplexMethod` | Decompose branching logic. `when` + early return. Extract predicates. |
| 42 | `LongParameterList` | Parameter-object extraction. See `WorkoutsStateInputs` as the reference pattern. |
| 11 | `ComplexCondition` | Extract boolean conditions into named predicates. |
| 8 | `LargeClass` | Extract collaborators. See `ScoringRepositoryImpl` decomposition as reference. |
| 5 | `NestedBlockDepth` | Flatten with early returns, guard clauses, or extracted helpers. |

---

## Per-file refactor units

Each file below is an independent refactor. Work them in any order. Files with the most entries yield the most baseline reduction per commit.

### Top-priority files (7+ entries)

#### `LocalRestoreManager.kt` — 18 entries
- Rules: `LongMethod` (3), `CyclomaticComplexMethod` (2), `ComplexCondition` (2), `LargeClass` (1), `NestedBlockDepth` (2), plus 8 Tier 1/2 entries
- Module: `app`
- This is the single heaviest file. Backup restore logic with deep nesting and complex JSON parsing.
- **Strategy:** Extract JSON section parsers into dedicated helpers (one per backup section). The `restoreFromBackup` method likely contains the bulk — split into `restorePreferences`, `restoreDatabase`, `validateBackup` steps.
- **Caution:** Restore logic is critical path. Golden test coverage may not exist — verify test coverage before starting. Do not change any restore behaviour.

#### `ScoringSyncScopeOutputsDeterminismTest.kt` — 14 entries
- Rules: `ExplicitItLambdaParameter` (12, Tier 1), `LongMethod` (implied from test size)
- Module: `core/database`
- Test file. Tier 1 handles the 12 `ExplicitItLambdaParameter` entries. Remaining `LongMethod` entries: extract test helper methods for common assertion patterns.

#### `ScoringRepositoryImpl.kt` — 13 entries
- Rules: `LongMethod` (implied), `LongParameterList` (3), `TooManyFunctions` (1), `CyclomaticComplexMethod` (implied)
- Module: `core/database`
- **Already partially decomposed** (863 → 440 lines in prior refactor). Remaining entries are in `computeDailySummary` and the walk-forward recompute loop.
- **Hard constraint:** Scoring math is off-limits. Only relocate expressions. Golden snapshots in `core/database/src/test/resources/golden/` must not change.
- **Strategy:** Extract data-loading preparatory blocks into `ScoringDayDataLoader` methods. Extract the TRIMP bucketing setup into a helper.

#### `ReadinessSummaryCoordinator.kt` — 11 entries
- Rules: `LongMethod` (implied), `LongParameterList` (2), `MaxLineLength` (7, Tier 2)
- Module: `core/database`
- After Tier 2 handles the 7 `MaxLineLength` entries, remaining Tier 3 entries are in the coordinator's `buildReadinessSummary` method.
- **Strategy:** Extract sleep-metric computation (deep/REM percentages, HR percentile) into a `SleepMetricsComputer` helper.

#### `DashboardMetricCardPreviews.kt` — 11 entries
- Rules: `UnusedPrivateMember` (10, Tier 1), `LongMethod` (1)
- Module: `feature/dashboard`
- Tier 1 handles the 10 unused preview entries. Remaining `LongMethod` is likely one large preview composable — split into smaller previews.

#### `HealthConnectRepositoryImpl.kt` — 11 entries
- Rules: `LargeClass` (1), `TooManyFunctions` (implied), other structural
- Module: `core/healthconnect`
- **Strategy:** Group read methods by data type. Extract `ExerciseSessionReader`, `SleepSessionReader` etc.
- **Caution:** Touches ingestion pipeline — `DATA_FLOW.md` must be updated.

#### `DashboardCardsSettingsViewModelTest.kt` — 10 entries
- Rules: `LongMethod` (3), `DestructuringDeclarationWithTooManyEntries` (7, Tier 2)
- Module: `feature/settings`
- Test file. Tier 2 handles the destructuring. Remaining `LongMethod` entries: extract shared test setup and assertion helpers.

#### `WorkoutDetailViewModelTest.kt` — 9 entries
- Rules: `LongMethod` (8), `LargeClass` (1)
- Module: `feature/workouts`
- Test file. Extract common workout test fixtures and assertion helpers.

#### `ResyncRangeUseCase.kt` — 9 entries
- Rules: `LongMethod` (implied), `MaxLineLength` (5, Tier 2), `ReturnCount` (implied)
- Module: `core/healthconnect`
- After Tier 2 handles `MaxLineLength`, remaining entries are in the four-phase resync method.
- **Strategy:** Each phase (chunked ingest, prune, reconcile, recompute) is already conceptually separate — extract into named methods.
- **Caution:** Touches ingestion pipeline — `DATA_FLOW.md` must be updated.

#### `BaselineComputer.kt` — 7 entries
- Module: `core/database`
- **Hard constraint:** Scoring math is off-limits. Only extract computation blocks into helper methods. Golden snapshots must not change.

#### `SleepDayAggregatorTest.kt` — 7 entries
- Rules: `LongMethod` (2), `MaxLineLength` (7, Tier 2)
- Module: `core/scoring`
- Test file. Extract test fixture builders.

### Medium-priority files (4-6 entries)

| Count | Module | File | Primary rules |
|------:|--------|------|---------------|
| 6 | `core/database` | `SqlCipherKeyManager.kt` | `TooManyFunctions`, `UseCheckOrError` (Tier 2) |
| 6 | `feature/sleep` | `SleepTrendChart.kt` | `LongMethod`, `LongParameterList` |
| 6 | `app` | `LocalBackupManager.kt` | `LongMethod`, `SwallowedException` (Tier 4) |
| 6 | `feature/insights` | `InsightCauseRanker.kt` | `ComplexCondition` (4), `LongMethod` |
| 5 | `feature/workouts` | `WorkoutsViewModelTest.kt` | `LongMethod` (3), `LargeClass` (1) |
| 4 | `core/scoring` | `ScoringPointInTimeRegressionTest.kt` | `LongMethod` (4) |
| 4 | `core/scoring` | `GetWorkoutDisplayMetricsUseCaseTest.kt` | `LongMethod` (4) |
| 4 | `app` | `SyncViewModel.kt` | `LongMethod`, `SwallowedException` (Tier 4), `ThrowsCount` (Tier 4) |

### Lower-priority files (2-3 entries each)

Files with 2-3 entries. Fix opportunistically or batch by module:

**`core/database`:** `ScoringGoldenSnapshotTest.kt` (2), `ScoringRepositoryN1Test.kt` (2), `RollingWindowTest.kt` (2)
**`core/scoring`:** `SleepScoringStrategy.kt` (2), `ComputeSleepMetricsUseCase.kt` (2), `SleepTrendDayAssemblerTest.kt` (2), `LoadScoringStrategy.kt` (1), `DailyMetricsMapper.kt` (1)
**`core/model`:** `WorkoutMapper.kt` (1+)
**`core/ui`:** `UniversalMetricCard.kt` (2), `TrendCharts.kt` (2), `ReorderableGrid.kt` (2)
**`core/healthconnect`:** `HealthChangeSynchronizerImpl.kt` (1), `HealthIngestionCoordinator.kt` (1)
**`core/designsystem`:** `ThemeColorUtils.kt` (3)
**`feature/settings`:** `ThresholdSettings.kt` (2), `SettingsScreen.kt` (2), `LocalBackupSettings.kt` (3)
**`feature/workouts`:** `WorkoutPerformanceCharts.kt` (4), `WorkoutsScreen.kt` (1)
**`feature/vitals`:** `VitalsStateFactory.kt` (1), `VitalsCardFactory.kt` (1)
**`feature/sleep`:** `SleepLayoutRenderers.kt` (3), `SleepStagesChart.kt` (1), `SleepHrChart.kt` (1), `SleepViewModelTest.kt` (1)
**`feature/dashboard`:** `WorkoutsCardFactory.kt` (1), `GetWorkoutMetricsUseCase.kt` (1)
**`feature/onboarding`:** `OnboardingRoute.kt` (1)
**`app`:** `UserPreferencesSerializer.kt` (1), `UserPreferencesMapper.kt` (1), various other single-entry files

### Single-entry files (1 entry each)

Many files have exactly 1 entry — typically `TooManyFunctions` on a large class or `LongMethod` on a single function. These are usually fixable by one extract-method refactor. Not enumerated individually — pick them up after clearing the heavy hitters.

---

## Key principles (from DETEKT_BASELINE_BURNDOWN.md §6)

1. **No expression is rewritten, only relocated.** Move code into a helper unchanged. Do not "clean it up" on the way.
2. **Scoring math is off-limits.** `domain/scoring/**` formulas, coefficients, operator order and constants must not change. Fix `LongMethod`/`CyclomaticComplexMethod` by relocating, never rewriting.
3. **Golden snapshots are sacrosanct.** Never regenerate `core/database/src/test/resources/golden/` to make a test pass.
4. **Parameter-object extraction** is the standard fix for `LongParameterList`. See `WorkoutsStateInputs` as reference.
5. **File size targets:** ≤ 400 lines preferred, hard limit 800.

---

## Execution plan

1. Complete Tier 1 and Tier 2 first — they clear entries from files that also have Tier 3 issues, reducing the remaining work.
2. Work top-priority files first (7+ entries) — biggest baseline reduction per commit.
3. One commit per file refactor. Commit message: `refactor(module): decompose FileName for detekt compliance`.
4. After each refactor, verify:
   - `./gradlew :module:detekt` passes
   - `./gradlew :module:testDebugUnitTest` passes
   - `git diff -- '**/detekt-baseline.xml'` shows removals only
   - For scoring/ingestion files: golden snapshots unchanged, `DATA_FLOW.md` updated

---

## Constraints

All constraints from `DETEKT_BASELINE_BURNDOWN.md` §7 apply. Additionally:
- Files touching ingestion pipeline or scoring use-cases require `DATA_FLOW.md` updates.
- New extracted files require `codegraph index` afterwards.
- Structural moves require `codegraph sync`.
- Unit test count must not drop below 3,082.
- `./gradlew ktlintCheck detekt testDebugUnitTest` must pass per commit.
- Run `./gradlew lintRelease` at the end.
