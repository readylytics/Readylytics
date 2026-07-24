# Task 6 Report: Resumable SQLCipher v7 Copy Engine

## Outcome

- Added `V7DatabaseMigrator.migrate(onProgress)` for encrypted v5/v6 database files.
- Preflights `db + WAL + 25% + 64 MiB` against `StatFs.availableBytes` before any migration table
  is created. A v6 database with the durable `v7` checkpoint resumes under the originally accepted
  preflight instead of recalculating against its already-expanded database and shadow files.
- Converts SQLCipher readiness/open and preflight exceptions to `V7MigrationResult.Failed` while
  continuing to rethrow cooperative cancellation.
- Advances v5 to v6 with every statement in `DatabaseUpgradeSql.V5_TO_V6` plus the version pragma
  in one transaction.
- Creates one durable `v7` checkpoint row and HR/HRV shadow tables with explicit, collision-free
  v7 index names.
- Copies HR and HRV using 10,000-row `id > ?` keyset batches. Each insert, last-key lookup, copied
  count update, and checkpoint commit is atomic. The checkpoint is re-read after each transaction;
  coroutine cancellation and `yield()` are honored between transactions.
- Normalizes only an exact trailing `_<timestampMs>` suffix. A source/time collision is ignored
  during copy but fails final count validation, leaving both authoritative v6 source tables intact.
- Creates each secondary index in a separate checkpointed transaction.
- Validates fixed source totals, equal source/target counts, and zero duplicate source/time groups,
  then repeats the same authoritative validation under the final write lock immediately before the
  only destructive operation.
- Performs cutover in a real SQLCipher `BEGIN IMMEDIATE` transaction via
  `beginTransactionNonExclusive()`, renames both shadow tables, installs Room's generated v7
  identity hash, drops migration metadata, and advances `user_version` to 7 atomically.
- Updated the Room entities, exported v7 schema, schema index contract test, and
  `internal-docs/DATA_FLOW.md`.

## TDD Evidence

### RED

The instrumented suite was created before `V7DatabaseMigrator` existed:

```text
./gradlew :app:compileDebugAndroidTestKotlin
```

The expected new-test failures were unresolved `V7DatabaseMigrator` references and inferred
progress callback types. The same compile also exposed unrelated pre-existing errors in
`ScoringWalkForwardBenchmark` and three repository instrumented tests.

### GREEN

After implementation:

```text
./gradlew ktlintFormat :app:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 7s
305 actionable tasks: 10 executed, 295 up-to-date
```

The full app androidTest compilation now reports no error in
`V7DatabaseMigratorInstrumentedTest`; it remains blocked by the unrelated sources listed under
"Instrumented-test blockers."

## Phase and Interruption Coverage

`V7DatabaseMigratorInstrumentedTest` creates Room v5/v6 fixtures, converts them to SQLCipher,
enables WAL, and covers:

- cancellation after the first committed 10,000-row HR batch, checkpoint inspection, reopen, and
  resume;
- cancellation and resume at both copy phases, all six secondary-index phases, and validation;
- v5 shared additive upgrade with workout `modelTrimp` and `step_records` preservation;
- exact suffix normalization and non-matching suffix preservation;
- insufficient-space return before metadata/shadow creation;
- an unreadable/corrupt SQLCipher file returning typed `Failed` during readiness instead of
  escaping an exception;
- low-space resume after an accepted preflight and durable 10,000-row checkpoint;
- normalized source/time collision failing closed with both legacy tables intact;
- a source insert after the durable validation checkpoint failing closed during locked cutover
  revalidation, with the v6 sources and shadows intact;
- final counts, WAL mode, cleanup of `_v7`/metadata tables, explicit Room identity hash, and
  `MigrationTestHelper.runMigrationsAndValidate(name, 7, true)` on a plaintext export without a
  Room v6→v7 migration.

The forced callback throws `CancellationException`. The migrator rethrows it, and every durable
checkpoint mutation being tested has already committed in the same transaction as its data/index
operation. Resume starts from the stored phase/last key.

## API and Plan Corrections

Two abbreviated-plan assumptions were corrected after validation:

1. An external version pragma is insufficient for Room. The atomic cutover also replaces
   `room_master_table` id 42 with generated v7 identity
   `54bca00d5cb026eb7ed7aa31e58c34f8`; otherwise Room rejects the migrated database.
2. SQLCipher 4.16's `execSQL("BEGIN IMMEDIATE")` is intercepted by `SQLiteSession.executeSpecial`
   and mapped to its exclusive transaction mode. `beginTransactionNonExclusive()` maps to an
   actual `BEGIN IMMEDIATE`, so the implementation uses that API with
   `setTransactionSuccessful()/endTransaction()` for automatic rollback.

The generated `HealthDatabase_Impl` identity and all eight explicit v7 index names were compared
with checked-in `7.json` and matched.

## Reviewer Follow-up

Three Important review findings were addressed test-first:

1. The low-space resume regression was written before the readiness change. With the original
   implementation it would return `InsufficientSpace`, because the preflight included the
   already-created shadows. Readiness now checks both `user_version == 6` and the durable `v7`
   checkpoint before deciding whether a new preflight is required. Fresh v5/v6, current v7, and
   unsupported versions retain their existing preflight path.
2. The post-validation source-mutation regression was written before cutover revalidation. With
   the original implementation the resumed `SWAP` phase would drop the authoritative source and
   lose the inserted row. The same fixed-total, source/target-count, and duplicate-group checks now
   execute again inside `beginTransactionNonExclusive()` before either source table is dropped.
   A mismatch rolls back and returns `Failed`, preserving the authoritative v6 source.
3. The unreadable-readiness regression was written before widening the exception boundary. With
   the reviewed implementation, SQLCipher open/decryption/schema-read failures escaped because
   `readReadiness()` ran before the `try`. Readiness and the ordered preflight decision now execute
   inside the same boundary as the state machine: ordinary exceptions become typed `Failed`, while
   `CancellationException` is rethrown unchanged. Checkpoint detection still precedes the
   fresh-only disk calculation.

The app instrumentation compiler remains blocked before these regressions can execute by the
unrelated sources listed below. The regression sources were nevertheless added before production
changes, and the fresh compiler diagnostics contain no migration-test error.

The review's Minor note—that the v5 fixture does not also exercise cancellation/resume between the
shared v5→v6 upgrade and shadow copying—is recorded as non-blocking and was not expanded in this
focused safety fix.

Reviewer-follow-up verification:

```text
./gradlew ktlintFormat
BUILD SUCCESSFUL in 3s

./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests app.readylytics.health.data.migration.DatabaseMigrationModelsTest
BUILD SUCCESSFUL in 23s

./gradlew testDebugUnitTest lintRelease
BUILD SUCCESSFUL in 1m 23s
1004 actionable tasks: 22 executed, 982 up-to-date
```

Final exception-boundary follow-up:

```text
./gradlew ktlintFormat
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests app.readylytics.health.data.migration.DatabaseMigrationModelsTest
BUILD SUCCESSFUL in 9s

./gradlew testDebugUnitTest lintRelease
BUILD SUCCESSFUL in 1m 31s
1004 actionable tasks: 21 executed, 983 up-to-date
```

No file was added by the review follow-up, so the AGENTS.md new-file rule did not require another
`codegraph index`.

## Verification

Final fresh combined verification:

```text
./gradlew testDebugUnitTest lintRelease
BUILD SUCCESSFUL in 1m 43s
1013 actionable tasks: 29 executed, 984 up-to-date
```

Other successful checks:

```text
./gradlew ktlintFormat
./gradlew :app:compileDebugKotlin
./gradlew :core:database:compileDebugAndroidTestKotlin
./gradlew :app:kspDebugKotlin
git diff --check
codegraph index
Indexed 887 files
78 nodes, 386 edges in 3.0s
```

## Instrumented-test Blockers

`adb devices -l` completed successfully but listed no connected device/emulator, so the requested
API 26+ connected run could not execute.

The requested fallback app androidTest compile was attempted again after the reviewer fixes. Its
final errors are
pre-existing and outside Task 6:

- `ScoringWalkForwardBenchmark.kt`: unresolved `WorkoutMapper`, unresolved `measureRepeated`,
  obsolete `ScoringRepositoryImpl` constructor arguments, and missing
  `scoringHistoryRepository`.
- `BloodPressureRepositoryImplTest.kt`, `BodyFatRepositoryImplTest.kt`, and
  `WeightRepositoryImplTest.kt`: unresolved `timestampMs`.

No Task 6 migration-test source was named in the final compiler diagnostics. Per instruction, these
unrelated files were not modified.

## Backprop and Self-review

The SQLCipher transaction reinterpretation, missing Room identity, repeated-preflight resume
failure, readiness exception escape, and validation/cutover write race were traced during
self-review and review. They are now represented by executable instrumented assertions/phase
coverage and by the synchronized data-flow documentation. There is no repository-root `SPEC.md`,
so the backprop skill had no §B/§V ledger to amend.

Self-review confirmed:

- no source-table drop occurs before validated atomic cutover;
- no blanket delete or destructive fallback exists;
- v5→v6 and every copy/index/checkpoint mutation has an explicit transaction boundary;
- source keys and SQL bind values are parameterized;
- exact normalization SQL matches the task formula for HR and HRV;
- every copied count equals its shadow-table count before progress continues;
- cancellation is never converted to `Failed`;
- the final physical indexes, Room entity declarations, generated implementation, exported schema,
  and schema contract test use the same explicit names.
