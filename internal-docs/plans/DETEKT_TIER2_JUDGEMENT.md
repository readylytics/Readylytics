# Detekt Tier 2 — Local, Mechanical-With-Judgement

**Status:** not started · **Created:** 2026-08-21 · **Owner:** unassigned
**Parent:** `DETEKT_BASELINE_BURNDOWN.md` §6, Tier 2
**Scope:** 111 baseline entries across 6 rules. Each fix is local to one file, but requires reading the context before applying.

---

## Rules in scope

| Entries | Rule | Fix pattern |
|--------:|------|-------------|
| 81 | `MaxLineLength` | Wrap lines. Run `ktlintFormat` after — ktlint also has line-length opinions and the two must agree. |
| 8 | `UseCheckOrError` | Replace `throw IllegalStateException(msg)` with `error(msg)`. |
| 7 | `DestructuringDeclarationWithTooManyEntries` | Introduce a named variable or use direct property access instead of positional destructuring. |
| 5 | `TooGenericExceptionThrown` | Replace `throw RuntimeException(msg)` / `throw Exception(msg)` with a specific exception type. |
| 5 | `ImplicitDefaultLocale` | Replace `String.format(...)` with `String.format(Locale.US, ...)` or `Locale.ROOT`. |
| 5 | `InstanceOfCheckForException` | Replace `e is FooException` with a multi-catch or typed catch block. |

---

## Affected files by rule

### `MaxLineLength` (81 entries)

Heaviest concentrations:

| Count | Module | File |
|------:|--------|------|
| 8 | `core/database` | `ScoringRepositoryImpl.kt` |
| 7 | `core/database` | `ReadinessSummaryCoordinator.kt` |
| 7 | `core/scoring` | `SleepDayAggregatorTest.kt` |
| 6 | `core/model` | `SettingsDefaults.kt` |
| 5 | `core/healthconnect` | `ResyncRangeUseCase.kt` |
| 4 | `feature/about` | `AppInfoSection.kt` |
| 4 | `core/scoring` | `DailyPromptFormatter.kt` / `DailyPromptFormatterTest.kt` |
| 15 | `core/database-schema` | Various DAO files (SQL query strings) |

**Module-by-module breakdown:**

**`core/database-schema`** (15 entries) — All are `@Query` SQL strings in DAO interfaces. Wrapping SQL across lines is fine but requires care with Room's annotation processor. Prefer breaking after SQL keywords (`SELECT`, `FROM`, `WHERE`, `AND`). Files: `BloodPressureRecordDao.kt`, `BodyFatRecordDao.kt`, `BodyTemperatureRecordDao.kt`, `DailySummaryDao.kt`, `HeartRateDao.kt` (3), `HrvDao.kt` (2), `OxygenSaturationRecordDao.kt`, `SleepSessionDao.kt` (2), `WeightRecordDao.kt`, `WorkoutDao.kt` (2).

**`core/database`** (20 entries) — `ScoringRepositoryImpl.kt` (8), `ReadinessSummaryCoordinator.kt` (7), `ScoringDayDataLoader.kt` (2), `ScoringSyncScopeOutputsDeterminismTest.kt` (1), `RoomAuditTrailRepositoryTest.kt` (1), `PersistenceBatchingTest.kt` (1).

**`core/scoring`** (16 entries) — `SleepDayAggregatorTest.kt` (7), `DailyPromptFormatter.kt` (2), `DailyPromptFormatterTest.kt` (1), `GetDailyPromptDataUseCase.kt` (2), `ComputeSleepMetricsUseCase.kt` (1), `SleepDayAggregator.kt` (2), `SleepTrendDayAssemblerTest.kt` (2).

**`core/model`** (11 entries) — `SettingsDefaults.kt` (4), `AppLog.kt` (2), `WorkoutMapper.kt` (1), `HealthConnectRepository.kt` (1), `HealthZone.kt` (1), `HrrToleranceRule.kt` (1), `ElevationGainCalculatorTest.kt` (1), `SleepMetricCardManagementDelegate.kt` (1), `VitalAssessmentTest.kt` (1).

**`core/healthconnect`** (8 entries) — `ResyncRangeUseCase.kt` (5), `HealthConnectRepositoryImpl.kt` (2), `HealthIngestionCoordinator.kt` (1), `StepCountFetcherRangeTest.kt` (1), `SyncWorkoutRouteUseCaseTest.kt` (1).

**`core/ui`** (2 entries) — `DataPointTooltipTest.kt` (1), `ZoneBandUtilsTest.kt` (1).

**`feature/about`** (4 entries) — `AppInfoSection.kt` (4) — long prose strings. Wrap with string concatenation or extract to `strings.xml`.

**`app`** (1 entry) — `LocalBackupManagerTest.kt`.

**Strategy:** Work one module at a time. After wrapping, run `./gradlew ktlintFormat` then `./gradlew :module:detekt` — ktlint may re-wrap differently. Iterate until both pass. `?.` and chained calls: break before the dot. String templates in log/debug lines: break at the `+` or use multi-line `"""`.

### `UseCheckOrError` (8 entries)

| Module | File | Current code |
|--------|------|-------------|
| `core/database` | `SqlCipherKeyManager.kt` | `throw IllegalStateException("Encrypted key not found...")` |
| `core/database` | `SqlCipherKeyManager.kt` | `throw IllegalStateException("Encryption IV not found...")` |
| `app` | `FileBackupStore.kt` | `throw IllegalStateException("Failed to delete local file")` |
| `app` | `LocalBackupManager.kt` | `throw IllegalStateException("Backup password not set")` |
| `app` | `LocalRestoreManager.kt` | `throw IllegalStateException("Could not open backup URI")` |
| `app` | `LocalRestoreManager.kt` | `throw IllegalStateException("No JSON file found in backup ZIP")` |
| `app` | `SafBackupStore.kt` | `throw IllegalStateException("Could not read backup")` |
| `app` | `SafBackupStore.kt` | `throw IllegalStateException("Failed to delete SAF document")` |

Fix: replace each with `error("message")`. One-to-one substitution, no behaviour change.

### `DestructuringDeclarationWithTooManyEntries` (7 entries)

All 7 in `feature/settings` → `DashboardCardsSettingsViewModelTest.kt`. Pattern: `val (viewModel, configsFlow, vitalsConfigsFlow, displaySettings) = buildViewModel(...)`.

Fix options:
1. Use named properties: `val result = buildViewModel(...); val viewModel = result.first; ...`
2. Create a named test helper class: `data class TestEnv(val viewModel, val configs, val vitals, val display)`.
Option 2 is cleaner — one change at the top of the test, then replace all 7 destructuring sites.

### `TooGenericExceptionThrown` (5 entries)

| Module | File | Current |
|--------|------|---------|
| `core/database` | `DatabaseReadinessGateTest.kt` | `throw RuntimeException("disk error")` — test code simulating failure. Acceptable; suppress or use a custom test exception. |
| `core/database` | `SqlCipherKeyManager.kt` | `throw RuntimeException("SQLCipher migration failed", e)` — wrap in a domain-specific `EncryptionMigrationException`. |
| `core/model` | `ErrorBoundaryTest.kt` | `throw RuntimeException("still failing")` — test code. Same as above. |
| `core/model` | `Result.kt` | `throw Exception(reason)` — review carefully; this may be a deliberate generic throw in a Result-type helper. |
| `app` | `EncryptionManager.kt` | `throw RuntimeException("Failed to generate master key", ex)` — wrap in `EncryptionInitException`. |

**Caution:** Test files throwing `RuntimeException` to simulate failures is common and correct. Options: introduce a `TestException` type used across tests, or `@Suppress` with reason.

### `ImplicitDefaultLocale` (5 entries)

| Module | File | Current |
|--------|------|---------|
| `core/ui` | `TrendCharts.kt` | `String.format("%.${n}f", value)` (2 entries) |
| `core/designsystem` | `Color.kt` | `String.format("#%06X", ...)` |
| `app` | `LoadTestResult.kt` | `String.format("%.0f", ...)` and `String.format("%.2f", ...)` |

Fix: add `java.util.Locale.US` (or `Locale.ROOT` for hex) as first argument. Pure mechanical.

### `InstanceOfCheckForException` (5 entries)

| Module | File | Check |
|--------|------|-------|
| `core/healthconnect` | `HealthConnectRepositoryImpl.kt` | `e !is HealthConnectPermissionRevokedException` |
| `app` | `LocalRestoreManager.kt` | `e is CancellationException` |
| `app` | `LocalRestoreManager.kt` | `e is ZipException` |
| `app` | `PeriodicHealthSyncWorker.kt` | `e is HealthConnectPermissionRevokedException` |
| `app` | `PeriodicHealthSyncWorker.kt` | `e is SecurityException` |

Fix: replace `catch (e: Exception) { if (e is Foo) ... }` with separate `catch (e: Foo)` blocks. **Read the catch body carefully** — some of these deliberately catch broad then narrow (especially `CancellationException` rethrow patterns governed by the Konsist rule).

---

## Execution plan

Recommended order (least judgement first):

1. **`UseCheckOrError`** (8 entries) — pure substitution, zero risk.
2. **`ImplicitDefaultLocale`** (5 entries) — add a parameter, no logic change.
3. **`DestructuringDeclarationWithTooManyEntries`** (7 entries) — single test file.
4. **`TooGenericExceptionThrown`** (5 entries) — test files: suppress; production: introduce typed exception.
5. **`InstanceOfCheckForException`** (5 entries) — read catch bodies before changing.
6. **`MaxLineLength`** (81 entries) — bulk of the work. Do module-by-module, commit per module.

After each group: remove the `<ID>` lines from the baseline, run `./gradlew :module:detekt`, commit.

---

## Constraints

All constraints from `DETEKT_BASELINE_BURNDOWN.md` §7 apply. Key ones for Tier 2:
- `MaxLineLength` wrapping must not fight `ktlintFormat`. Run both and iterate.
- `InstanceOfCheckForException` changes must not break the `CancellationException` rethrow policy (Konsist-owned).
- `git diff -- '**/detekt-baseline.xml'` must show **removals only**.
- Unit test count must not drop below 3,082.
- `./gradlew ktlintCheck detekt testDebugUnitTest` must pass.
- Run `./gradlew lintRelease` at the end.
