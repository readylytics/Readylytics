# Review Remediation Design

## Goal

Resolve the six findings from the Phase 5 review while preserving the offline-first Room contract,
the v7 integer-key schema, deterministic scoring, historical backup compatibility, and bounded,
recoverable upgrade behavior on large databases.

## Scope

The remediation covers:

1. Health-Connect-free recompute-only scoring.
2. Explicit zero totals for grouped step days with no records.
3. DST-safe walk-forward baseline prefetching.
4. Restore-time migration of v5 and v6 backups into the v7 entity model.
5. A resumable, foreground v5/v6-to-v7 database migration.
6. Synchronized stage-less-sleep methodology documentation.

No scoring coefficients or formulas change. The sleep documentation work describes behavior already
implemented by `SleepDataMapper` and the scoring engine.

## Architecture

### Recompute-only data flow

`ResyncRangeUseCase` will not call `StepCountFetcher` when `skipIngestAndPrune` is true. It will pass
`null` for each day's optional step override so `ScoringRepository.computeAndPersistDailySummary`
preserves the step count already stored in Room. Full resync continues fetching and replacing step
totals from Health Connect.

A regression test will revoke or fail all Health Connect operations and prove that recompute-only
still completes from Room data without invoking `StepCountFetcher`.

### Grouped step aggregation

For the all-devices branch of `StepCountFetcher.fetchRange`, each chunk will initialize every
calendar date in `[chunkStart, chunkEndExclusive)` to `0L` before overlaying Health Connect's grouped
results. This restores the old per-day-read behavior when Health Connect omits empty aggregate
buckets. The selected-device branch already resolves missing days to zero in `ResyncRangeUseCase`
and remains unchanged.

Tests will cover an entirely empty chunk and a sparse chunk with data on only one date.

### DST-safe baseline prefetch

`BaselineComputer.prefetchWalkForwardSessions` will derive its lower bound from the first score
day's midnight instant and subtract `HRV_SIGMA_WINDOW_DAYS` fixed 24-hour durations, matching the
per-day HRV methods. The upper bound remains the calendar end of `endDate` because it is already a
safe superset for all per-day queries.

The equivalence suite will use `Europe/Berlin` across the 2025 spring-forward transition and include
a session in the extra hour that the calendar-date lower bound currently omits. Prefetched and
direct-query paths must return identical RHR and HRV baseline inputs.

### Backup compatibility

`LocalRestoreManager` will treat backup format compatibility separately from the current Room schema
version. It will accept manifests for versions 5, 6, and 7 and reject versions outside that explicit
set before mutating the database.

The streaming restore path will decode HR and HRV rows through version-specific backup DTOs:

- v7 rows decode directly to the current entities.
- v5/v6 rows decode the legacy required `id`, retain `timestampMs`, and derive
  `sourceRecordId` by removing the final `_<timestampMs>` suffix used by the legacy mapper.
- If the expected suffix is absent, restore will preserve the complete legacy `id` as
  `sourceRecordId` instead of discarding the row.
- `rowId` remains zero so Room assigns it during upsert.

All other v5/v6 rows use their existing forward-compatible serializers. Missing v6-only archive
entries, including step records, are treated as empty only when their manifest version predates the
table. Manifest row counts are still checked for every entry that exists in that format.

Tests will use real v5-shaped JSON rows for HR and HRV, verify exact field conversion and restored
counts, and retain rejection tests for unsupported future versions.

### Resumable v5/v6-to-v7 migration

Room's `Migration.migrate` callback cannot provide the required recovery semantics because Room
wraps the entire callback in one transaction. The large-table work therefore moves to a pre-open
migration coordinator that accesses the encrypted SQLite file directly through SQLCipher before any
`HealthDatabase` instance is created.

The coordinator accepts both v5 (the schema currently on `main`) and v6 databases. A v5 file first
receives the existing small additive v5→v6 changes in one transaction: add nullable
`workout_records.modelTrimp`, create `step_records`, drop the redundant summary index, and set
`user_version = 6`. The SQL statements are shared with `DatabaseMigrations.MIGRATION_5_6` so the
external and Room paths cannot drift.

The large v6→v7 portion is a deterministic state machine with durable state stored in a small
migration metadata table inside the database:

1. `PREFLIGHT`
2. `UPGRADE_5_TO_6` when required
3. `CREATE_SHADOW_TABLES`
4. `COPY_HEART_RATE`
5. `COPY_HRV`
6. one state per required v7 index
7. `VALIDATE`
8. `SWAP`
9. `COMPLETE`

Copies use keyset pagination over the legacy text primary key:

```sql
INSERT OR IGNORE INTO heart_rate_records_v7 (...)
SELECT ...
FROM heart_rate_records
WHERE id > :lastCopiedId
ORDER BY id
LIMIT :batchSize
```

Each batch and its new `lastCopiedId` checkpoint commit in the same transaction. HRV uses the same
contract. Restarting replays at most one batch, and the v7 unique source/timestamp index makes replay
idempotent.

The source/timestamp unique indexes use distinct final v7 names and are created while the shadow
tables are empty, before copying begins. This makes replay idempotent without a large index build.
Each remaining secondary `CREATE INDEX` commits and checkpoints independently before cutover. The
last transaction validates source/target row counts and uniqueness, drops the legacy tables, renames
the shadow tables, removes migration metadata, and sets `PRAGMA user_version = 7`. A process death
before that transaction leaves the complete v6 tables authoritative; a death during it rolls the
whole swap back.

Before creating shadow tables, the coordinator measures the database and available filesystem
space. It requires enough headroom for the new tables plus a safety margin. Insufficient space
returns a recoverable blocked state without changing legacy data and presents actionable UI copy.

### Startup and progress gating

The app must not instantiate Room while a v5 or v6 database is being upgraded. Database-bound application
dependencies will be injected lazily, and startup scheduling/backfill will wait on a
`DatabaseReadinessGate`.

`MainActivity` will resolve readiness before constructing the normal navigation graph:

- new database or v7 database: continue normally;
- v5/v6 database: enqueue unique foreground migration work and show a blocking Material 3 migration
  surface;
- recoverable preflight failure: show the required-space message and retry action;
- unrecoverable validation failure: preserve the v6 file, show a non-destructive error, and offer
  diagnostic export/retry rather than destructive migration.

The foreground worker publishes phase/current/total through `WorkInfo.progress` and a determinate
notification. The migration gets its own progress model because resync's `RecalcProgress` represents
calendar-day scoring and must not be overloaded with database rows. Existing backup, cleanup, sync,
and baseline jobs cannot start until readiness is `Ready`.

### Documentation synchronization

The stage-less behavior will be described consistently in:

- `ABOUT.md`
- `docs/about.md`
- `internal-docs/DATA_FLOW.md`
- the in-app About resources in `feature/about/src/main/res/values/strings.xml`
- any sleep tooltip resource that describes Architecture handling

The copy will state that when a source supplies no stages, total duration falls back to the raw
session span, Architecture contributes zero, and Sleep Score reweights to Duration 75% /
Restoration 25%. It will distinguish a truly stage-less session from suspicious but non-empty stage
data.

The database and backup sections of `internal-docs/DATA_FLOW.md` and `docs/backup-and-data.md` will
also document the resumable v7 upgrade and accepted backup versions.

## Error Handling and Recovery

- Cancellation is always rethrown by sync and migration coroutines.
- Recompute-only runs never translate Health Connect failure into retry because they never access
  Health Connect.
- Migration batches are idempotent and resume from durable checkpoints.
- Validation failure never drops the legacy tables.
- Unsupported or malformed backups fail validation before the restore transaction clears data.
- Database migration and restore logs contain phase/count information but no health values,
  record identifiers, encryption keys, or file contents.

## Testing Strategy

### Pure/unit tests

- Recompute-only does not invoke Health Connect or `StepCountFetcher` and preserves stored steps.
- Full resync still fetches steps and applies explicit zero totals.
- Sparse grouped aggregates return every requested date.
- Walk-forward baseline prefetch equals direct queries across spring-forward DST.
- Backup version policy accepts 5/6/7 and rejects older/unknown/future versions.
- Legacy HR/HRV backup DTO conversion handles normal and suffix-mismatch identifiers.
- Migration state transitions, batching, checkpoint replay, free-space decisions, and validation
  are tested as pure Kotlin where possible.

### Instrumented database tests

- Seeded encrypted v5 and v6 databases migrate to the exported v7 Room schema.
- Killing after every copy batch/index phase resumes without loss or duplication.
- Validation failure preserves readable legacy tables and version 6.
- WAL sidecars and SQLCipher opening are exercised.
- A large fixture proves copy work is bounded by batch size and reports progress.
- Restore fixtures for v5, v6, and v7 produce the same current entities.

### Performance gate

Before release, record the DB-001 decision evidence on the 1M-row fixture. Retaining v7 requires at
least one original gate to pass: ingest throughput improves by at least 30%, or database size
shrinks by at least 25%. The migration benchmark also records peak disk usage, batch latency,
restart recovery, and total foreground duration.

### Repository verification

Run focused tests after each remediation task, then:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
./gradlew lintRelease
```

Run the migration/restore instrumentation suite on an API 26+ emulator with SQLCipher and WAL
enabled. Run documentation drift tests as part of `testDebugUnitTest`.

## Delivery Order

1. Land the three bounded behavioral fixes and their regression tests.
2. Add backup-version migration and restore fixtures.
3. Build the pre-open migration coordinator and instrumented recovery tests.
4. Gate application startup and add foreground progress UI/notification.
5. Add the 1M-row benchmark evidence and finalize v7 index/schema exports.
6. Synchronize all required documentation and run the complete verification matrix.

This order keeps early changes independently reviewable while ensuring the v7 schema is not
considered release-ready until backup compatibility, migration recovery, progress, and benchmark
gates all pass.
