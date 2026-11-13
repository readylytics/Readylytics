# Plan: Hexagonal Architecture (Ports & Adapters) Remediation

> **Status:** PROPOSED — not started. Written 2026-11-13 against `main` @ `f35bc9f`.
> Execute one phase per PR, in order. Each phase is independently shippable and behaviour-preserving.

## Context
This plan evaluates the Readylytics module graph against the Ports & Adapters model and lays out a phased, behaviour-preserving remediation. The scoring math (`core/scoring/**` formulas and coefficients) is **off-limits**: every phase is a move, split or rewire, protected by the existing golden and determinism tests. Each phase also adds a Konsist or architecture guard test, so the fixed boundary cannot regress. Follow every repository rule in `.claude/CLAUDE.md`: the pre-commit chain, no new detekt issues or `@Suppress`, synchronous `internal-docs/DATA_FLOW.md` updates, `codegraph index`/`sync`, and never uninstalling `app.readylytics.health`.

**Hexagonal mapping used in this repo:**
- Domain and ports: `core:model` (models, outbound port interfaces).
- Domain and application: `core:scoring` (pure engine, use cases).
- Outbound adapters: `core:database` + `core:database-schema` (Room), `core:healthconnect` (Health Connect), `app/data/*` (DataStore, backup files, security), `app/workers` (WorkManager).
- Inbound adapters: `feature/*` ViewModels and Compose, plus the WorkManager workers as scheduled inbound triggers.
- Composition root: the Hilt modules (`app/di`, `core/*/di`).

## Evaluation summary

**Already hexagonal (keep):**
- Every outbound port interface lives in `core:model/domain/{repository,sync,preferences,date,scoring}`. There are about 40 ports.
- `core:model` and `core:scoring` main sources have zero `android*`/Room/Health Connect/DataStore imports.
- Adapters implement ports cleanly:
  - `core:database` implements about 30 ports, all returning domain models (no Entity leaks).
  - `core:healthconnect` implements `HealthConnectRepository`.
  - `app/data/preferences` implements the DataStore ports.
- Feature modules (the inbound adapters) never import `core.database`, `core.healthconnect`, Room, WorkManager or `Context` in their ViewModels. Each ViewModel injects ports from `core:model` or use cases from `core:scoring`.
- Guardrails already exist: `app/src/test/.../CleanArchTest.kt` (Konsist) and `app/src/test/.../architecture/*`.

**Leaks, ranked:**

| # | Leak | Evidence |
|---|---|---|
| L1 | Backup/restore is a second persistence adapter wired straight into Room | `app/data/backup/*` makes about 98 `HealthDatabase.*Dao()` calls, imports 15+ entities, and uses `withTransaction` and a raw `PRAGMA` (`RestoreDatabaseOperations.kt`, `BackupStreamWriter.kt`, `RestoreBatchLoader.kt`, …). This forces `app` to depend on `core:database-schema`. |
| L2 | Application/scoring orchestration lives in the DB adapter, so one adapter depends on another | `core/database/domain/{scoring,sync}/*` holds 3 use cases that use only ports. Because of them, `core:healthconnect` needs `implementation(project(":core:database"))`, and only 4 imports use it. Scoring assembly (`DayAssembler`, `ReadinessSummaryCoordinator`, `FinalSummaryAssembler`, `MorningRecommendationAssembler`, …) also sits in `core/database/data/repository`. |
| L3 | Infrastructure in `app/domain` | `domain/sync/HistoricalResyncControllerImpl.kt` and `domain/migration/DatabaseMigrationController.kt` import `androidx.work` and concrete worker classes. |
| L4 | Workers hold business logic | `HealthResyncWorker.kt` (463 lines) drains dirty ranges, discards retention, prunes, persists TR config, and writes post-recompute version/baseline state. `BirthdayCheckWorker` does age math. |
| L5 | Concrete classes injected where ports exist | The concrete `app.data.preferences.SettingsRepository` is injected in `ThemeViewModel`, `SyncViewModel`, `BirthdayCheckWorker`, `DataCleanupWorker`, `PreferencesPrewarmer` and `DatabaseReadyStartupInitializer`. `SyncViewModel` also injects the concrete `SelectedDateRepository` and `ForegroundSyncController`. `UIPreferences` injects the concrete `HealthDeviceRepository`, which is adapter-to-adapter coupling. Workers use the concrete `RetentionCleanup`, `DataRollupManager` and `LocalBackupManager`. |
| L6 | Inbound side: domain math and orchestration in ViewModels | BMI is duplicated at `WeightDetailViewModel.kt:197` (`BmiService` exists). Lean mass is computed at `BodyFatDetailViewModel.kt:229`. `HeartRateDetailViewModel.computeZoneTotals` is domain math. `SleepViewModel` does an untyped 8-flow `combine`. `VitalsViewModel` is 425 lines, over the 400-line limit. `GetDashboardDataUseCase` imports `core.ui` types. `LocalBackupViewModel` verifies the password itself. |
| L7 | Minor | `WorkerModule` uses `@Provides` where `@Binds` would do. `ForegroundSyncController` takes `dagger.Lazy`. The `HealthChangeSynchronizer` port is declared inside its adapter module. `CircadianConsistencyRepository` is a concrete class named like a port. Test doubles are mostly MockK on concrete classes, `FakeAuditTrailRepository` is copied 5 times, and there is no shared fakes module. |

## Phased refactor plan

**Execution conventions:**
- One branch and one PR per phase.
- A phase may start only once the previous phase is merged. Phases 6 and 7 are independent of 2–5 and may run in parallel.
- If a phase uncovers a needed change to scoring output, stop. That is out of scope for this plan.

Phases are ordered from lowest to highest risk. Each phase follows the same steps: (a) add a characterization or architecture test that fails, or would fail on the old code, (b) move and rewire, (c) add a Konsist guard, (d) update docs if the touched area is load-bearing, (e) run `codegraph index`/`sync`, (f) commit.

### Phase 1 — Quick wins: concrete classes to ports (L3, L5, L7)
- Inject the `core.model...SettingsRepository` interface, or the narrow ports `UserPreferencesReader`/`DisplaySettings`, instead of the concrete app class in `ThemeViewModel`, `SyncViewModel`, `BirthdayCheckWorker`, `DataCleanupWorker`, `PreferencesPrewarmer` and `DatabaseReadyStartupInitializer`. If an interface method is missing, widen the port in `core/model/.../preferences/` rather than keep the concrete type.
- `SyncViewModel`: replace `SelectedDateRepository` with `SelectedDateStore` and `ForegroundSyncController` with `ForegroundSyncGateway` (extend the gateway if needed).
- `UIPreferences`: put `HealthDeviceRepository` behind a new `HealthDeviceLookup` port in `core:model/domain/repository`, bound in `DatabaseRepositoryModule`.
- Move `HistoricalResyncControllerImpl` and `DatabaseMigrationControllerImpl` from `app/domain/{sync,migration}` to `app/workers/` (or `app/data/work/`), since they are WorkManager adapters. The port interfaces stay where they are. Also move `HealthDataRefreshAdapter` to `app/data/sync/`.
- `WorkerModule`: switch to `@Binds`.
- `ForegroundSyncController`: replace `dagger.Lazy<WorkerScheduler>` with `javax.inject.Provider`. Check that this doesn't create a cycle first.
- **Guards** (`CleanArchTest`):
  - `app/domain` has no `androidx.*` or `app.readylytics.health.workers` imports.
  - The concrete `data.preferences.SettingsRepository` is referenced only in `data/preferences` and `di`.
  - `ui/**` ViewModels don't import `core.database`.

### Phase 2 — Sync application layer out of the DB adapter (L2, part 1)
- Move these classes, with no logic change:
  - `core/database/domain/sync/DailyRecomputeSupport.kt` to `core/scoring/.../application/recompute/`.
  - `core/database/domain/scoring/TrainingReadinessProjectionRecomputeUseCase.kt` to `core/scoring/.../application/recompute/`.
  - `core/database/domain/scoring/RecomputeTodayUseCase.kt` to `core/scoring/.../application/recompute/`. Its `@Binds` to `RecomputeToday` moves from `ScoringSyncBindingsModule` to `core/scoring/di/ScoringBindsModule`.
  - All three depend only on `core:model` ports and `core:scoring`, which is verified.
- Move their unit tests (`core/database/src/test/.../domain/{scoring,sync}/*Test.kt` that use fakes or mocks) to `core:scoring` tests. Golden tests that need Room (`WalkForward*EquivalenceTest`, `ScoringSyncScopeOutputsDeterminismTest`, `ResidualFatigueScoringIntegrityTest`) stay in `core:database` and just update their imports.
- Update the imports in `core/healthconnect/domain/sync/{DailySyncUseCase,ResyncRangeUseCase,HistoricalRecomputePhase,FullHistoricalResyncUseCase}.kt`.
- In `core/healthconnect/build.gradle.kts`, change `implementation(project(":core:database"))` to `testImplementation`, since its tests use Room fakes.
- **Guards:** `core:healthconnect` main never imports `core.database`. No class under `core.database..` is named `*UseCase`.
- **Docs:** update `internal-docs/DATA_FLOW.md` (sync/recompute section paths) and the CLAUDE.md references.

### Phase 3 — Thin workers (L4)
- Extract a `HistoricalRecomputeOrchestrator` application service into `core:healthconnect/domain/sync/`, next to `FullHistoricalResyncUseCase`. It takes these methods from `HealthResyncWorker`, unchanged:
  - `runTrainingReadinessProjection`
  - `runSelectedWorkoutRepair` / `pruneExcludedWorkoutsIfSelected`
  - `drainJournaledRepairTickets`
  - `runNormalRecompute`
  - `discardExpiredDirtyRanges`
  - `persistPostRecomputeState`
  - the helpers `resolveNextRange`, `pendingTicketRange` and `coversRetainedHistory`
- Progress goes out through a callback or `Flow`, which the worker maps to `setProgress` and the `ForegroundSyncController.onBackgroundRecalc*` bridge. This keeps the existing RecalcProgress path, as CLAUDE.md requires.
- The worker keeps only `inputData` parsing (`resolveExplicitRange`), `getForegroundInfo`, the readiness gate and the mapping to `Result.retry()`/`success()`. The target is under 150 lines.
- Preserve the contract: unique work name, `ExistingWorkPolicy.KEEP`, the foreground `dataSync` service, cooperative `ensureActive()`/`yield()`, and re-throwing `CancellationException`.
- `BirthdayCheckWorker`: move the age computation into `UserUseCase` (it already exists as the `UserProfileActions` port), or into a pure `AgeCalculator` in `core:model/domain/user`.
- Workers use ports instead of `RetentionCleanup`/`DataRollupManager`/`LocalBackupManager`: add `RetentionCleanupPort`, `DataRollupPort` and `ScheduledBackupRunner` ports in `core:model`, bound in the DB and app DI modules.
- Tests:
  - Move the worker-logic tests to orchestrator unit tests in `core:healthconnect` with fake ports.
  - Keep a thin worker test that covers progress and the retry mapping.
- **Guard:** classes under `app.readylytics.health.workers` don't inject concrete `core.database` types.
- **Docs:** update the worker section of `DATA_FLOW.md`.

### Phase 4 — Scoring orchestration out of the DB adapter (L2, part 2; the largest move)
- Split each class into a pure assembler and a DB loader:
  - Pure assembly and coordination move to `core:scoring/.../application/dailysummary/`. These files have zero DB imports today:
    - `ScoringDayContextResolver`, `CalibrationGate`, `BaseSummaryAssembler`, `WalkForwardContextLoader`, `ScoringTelemetry`
    - `recommendation/{MorningRecommendationAssembler,MorningRecoveryLoader,WorkoutExampleLoader}`
  - These files have 1–3 DB imports each and must be split first: `ReadinessSummaryCoordinator`, `FinalSummaryAssembler`, `DayAssembler`, `DailyTrimpComputer`, `RasTotalsComputer`, `ResidualFatigueComputer`.
- Introduce an outbound port, `ScoringDayInputsSource` in `core:model/domain/repository`. It returns a domain `ScoringDayInputs` bundle that replaces what `ScoringDayDataLoader`, `ScoringSeriesLoader`, `ScoringHeartRateDataLoader` and `BodyMetricsDataLoader` return today. A `ScoringDayPersistence` port takes the summary upsert and the frozen-baseline writes. The Room implementations stay in `core:database`.
- `ScoringRepositoryImpl` moves to `core:scoring` as the implementation of the `ScoringRepository` port. Keep the interface name to avoid churn, because CLAUDE.md and DATA_FLOW.md reference `ScoringRepository.computeDailySummary`.
- Split `ScoringHistoryRepositoryImpl` the same way. Strategy construction (`CompositeScoringCalculator`, the `*ScoringStrategy` classes) moves to core:scoring. The DAO reads stay in the DB module.
- The safety net must pass with bit-identical outputs:
  - `WalkForwardCorrectionEquivalenceTest`, `WalkForwardTransactionEquivalenceTest`, `ScoringSyncScopeOutputsDeterminismTest`, `ResidualFatigueScoringIntegrityTest`
  - `ChunkAlignmentIndependenceTest`
  - the `*DocumentationDriftTest*` suite
- Afterwards, check whether `core:database` still needs `core:scoring` (the goal is no). If it does, add a `testImplementation`-only exception with a written reason.
- **Guard:** `core.database..` main imports no `core.scoring..` (or an explicit allowlist).
- **Docs:** update the `DATA_FLOW.md` scoring use-case and coordinator section (paths only, no formula text).

### Phase 5 — Backup/restore behind ports (L1)
- Characterization first: add a golden round-trip test in `app/src/test/.../data/backup/`. It seeds an in-memory DB, backs up, restores into a fresh DB and compares row-by-row. It also restores a checked-in fixture archive from the current format version, so format compatibility is pinned.
- Ports in `core:model/domain/backup/`, using `java.io` streams so there are no Android types in signatures:
  - `HealthDataSnapshotSource`: streams each table section to a writer callback.
  - `HealthDataSnapshotSink`: `beginRestore`, `restoreSection`, `verifyIntegrity`, `finish`, inside one transaction scope.
  - `BackupCoverageInspector`
- Move the Room-touching files from `app/data/backup` to `core/database/.../data/backup/`, keeping their logic:
  - `RestoreDatabaseOperations`, `BackupStreamWriter`, `BackupTableWriter`, `RestoreBatchLoader`, `RestoreVitalsLoader`
  - `CoverageBackupWriter`, `CoverageRestoreLoader`, `BackupRecordDecoders`, `RestoreRecommendationCoverageChecker`, `BackupSnapshotExporter`, `RestoreMaintenanceCoordinator`
  - `core:database` is an Android lib, so `android.util.JsonReader` keeps working.
- `app/data/backup` keeps the archive, encryption, rotation, SAF/file stores, journals and the preferences parts, and talks to the DB only through the new ports.
- Remove `implementation(project(":core:database-schema"))` from `app/build.gradle.kts` if nothing else needs it. The migration code (`V7DatabaseMigrator`) may still need it; if so, keep it scoped to `data/migration`.
- **Guard:** `app` main imports `core.databaseschema..` or `HealthDatabase` only from `data/migration`, `di` and the application or activity bootstrap.
- **Docs:** this changes no data collection, so no `docs/privacy.md` update. Update `DATA_FLOW.md` only if it mentions the backup adapter paths.

### Phase 6 — Inbound adapters: domain logic out of ViewModels (L6)
- Vitals: replace the inline BMI in `WeightDetailViewModel.kt:197` with `BmiService.calculateBmi`. Move lean mass, zone totals (`HeartRateDetailViewModel.computeZoneTotals`, including the 10-minute gap constant) and the per-day BP/weight/body-fat averaging into pure functions under `core:model/domain/vitals/` or `core:scoring`, with boundary tests.
- Add an `ObserveSleepDayUseCase` to `feature/sleep/usecase` (or `core:scoring`). It replaces the untyped 8-flow `combine` in `SleepViewModel` with a typed data class.
- Split `VitalsViewModel` to under 400 lines with an `ObserveVitalsOverviewUseCase`.
- Dashboard: `GetDashboardDataUseCase` should return domain types. Move the `core.ui` presentation mapping into `DashboardUiStateTransformer`, and move the `*MetricPresentationFactory` files out of `usecase/` into `presentation/`.
- `LocalBackupViewModel`: move password verification into the `EncryptionManager` or `BackupService` port (add `verifyPassword`).
- **Guard:** `feature.*.usecase..` doesn't import `core.ui..`.

### Phase 7 — Test-double hygiene (L7)
- Add a `core:testing` module (Android library, using the `readylytics.android-library-conventions` plugin). It holds shared fakes for the most-mocked ports: `FakeAuditTrailRepository` (removes 5 copies), `FakeHealthConnectRepository`, `FakeCrashReportStore`, `FakeUserPreferencesReader`, `FakeDailySummaryRepository` and `FakeSettingsRepository`.
- Add it as `testImplementation` to the modules that need it, and swap the duplicated private fakes for the shared ones. Don't migrate the MockK usages wholesale; only the duplicated fakes.
- Optionally rename `CircadianConsistencyRepository` to `CircadianConsistencyCalculator`, since it isn't a port.

### Deferred, not in this plan
- Converting `core:model`/`core:scoring` to `kotlin("jvm")`: blocked, see `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md`.
- Extracting `core:healthconnect/domain/sync` into its own `:core:sync` application module. Revisit after Phase 3, once the module boundary is clean.

## Verification (every phase)
1. `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`. No new detekt issues and no `@Suppress` or baseline edits; if one seems unavoidable, stop and ask.
2. The new Konsist guards pass, and each fails when the old import is temporarily reintroduced (check by hand once).
3. Phases 2, 4 and 5: the golden and determinism suites listed above pass unchanged. Phase 5 also needs the new backup round-trip test and the fixture-restore test.
4. Phases 1 and 3: on a device or emulator (a debug build, never uninstall `app.readylytics.health`):
   - Pull-to-refresh still runs the current-day sync.
   - Settings → Resync still shows the "day X of Y" banner and notification.
   - Backup → restore round-trips.
5. `codegraph index` (new files) and `codegraph sync` (moves) after each phase.
6. After the final phase: `./gradlew lintRelease`.
