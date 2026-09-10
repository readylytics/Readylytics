# Readylytics Architecture, Health Data, and Scoring Remediation Plan

Review baseline: commit `0051abea`, 2026-09-10. Status: planning only; no implementation authorized by this document. Chosen location follows the existing `internal-docs/plans/` convention. Finding identifiers are local to this plan; similarly named identifiers in older source comments describe earlier remediation work.

Goal: make imports, historical corrections, scoring, and recovery deterministic and scalable while preserving Readylytics' offline product semantics. Execute the ordered work packages with a review checkpoint after each; this document specifies interfaces and invariants, not replacement implementations.

## 1. Executive Summary

Readylytics has a substantial modular architecture already: feature modules depend on domain ports, Room is the UI's source of truth, Health Connect is an ingestion adapter, and most calculation code is isolated in `core:scoring`. The pipeline already includes paged HR/HRV ingestion, targeted upserts, keyset reconciliation, frozen baselines, walk-forward contexts, encrypted storage, and durable WorkManager resync. A rewrite or another round of wholesale modularization is not justified.

The highest-priority problems are behavioral. Snapshot ingestion does not replace changed HR/HRV values or removed samples; interrupted delta processing can lose historical invalidation; resumed scans can skip deletion reconciliation; warm-tier data is not reconciled with corrected raw data. Scoring has future-data leakage in historical RHR backfill, inconsistent calibration state, stale outputs after source deletion, and inconsistent workout/sleep input assembly. These undermine confidence in historical and repeated scoring even where the underlying formulas are reasonable and internally isolated.

Scalability is only partially bounded. A Health Connect page limits parent records, not their nested samples; the coordinator retains a whole chunk's IDs; source-reference creation incurs a transaction per parent; baseline consumers reconstruct and sort warm samples repeatedly; backup materializes every source record. Existing million-row migration fixtures are useful but do not demonstrate that the present ingestion-to-score pipeline handles more than one million Health Connect HR records in 30 days.

Backup integrity and privacy repairs belong near the front of the roadmap: password rotation can leave mixed-password archives, restore can accept incomplete table sets, export lacks a consistent snapshot, and release logcat/crash output can retain unsanitized exception content. No external disclosure or remote exploit was demonstrated.

Recommended strategy: characterize the identified failures, repair authoritative replacement and durable invalidation, repair scoring assembly without changing coefficients, make resumption and tier coverage explicit, then optimize proven hot paths. Preserve current-day refresh, retention-bounded historical resync, the existing progress channel, source-selection semantics pending the decisions in section 14, and all documented heuristic limitations.

## 2. Repository Areas Reviewed

This is a repository-wide static architecture audit with detailed end-to-end inspection of the critical paths, not a claim that every line or device behavior was dynamically verified. The source inventory included 1,353 Kotlin files under app/core/features, module wiring, manifests, documentation, tests relevant to the findings, and benchmark scaffolding. No Gradle, device, migration, or performance results were generated for this planning change. Existing test names and benchmark reports are evidence of available validation infrastructure, not proof that current HEAD passes.

Paths below are repository-relative. To keep references readable, the following exact prefixes apply throughout this document:

| Alias | Exact directory |
|---|---|
| `HC/` | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/` |
| `DB/` | `core/database/src/main/kotlin/app/readylytics/health/core/database/` |
| `SCHEMA/` | `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/` |
| `MODEL/` | `core/model/src/main/kotlin/app/readylytics/health/core/model/` |
| `SCORE/` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/` |
| `APP/` | `app/src/main/kotlin/app/readylytics/health/` |
| `SETTINGS/` | `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/` |

Important reviewed areas:

- Guidance and methodology: `AGENTS.md`, `.claude/CLAUDE.md`, `README.md`, `ABOUT.md`, `internal-docs/DATA_FLOW.md`, `internal-docs/INSIGHTS.md`, `internal-docs/INSIGHT_RESEARCH_SUMMARY.md`, AI recommendation documentation, `docs/about.md`, `docs/privacy.md`, `docs/backup-and-data.md`, and relevant About/tooltip strings. Existing plans: `CORE_SCORING_JVM_MIGRATION.md` and `IDEA_HOME_SCREEN_WIDGETS.md` in this directory.
- Build/ownership: `settings.gradle.kts`, `gradle/libs.versions.toml`, core/feature build scripts, build conventions, `HC/di/HealthConnectModule.kt`, `DB/di/{DatabaseModule,DatabaseRepositoryModule,ScoringSyncBindingsModule}.kt`, `SCORE/di/`, `APP/di/{DataStoreModule,CoroutineDispatchersModule}.kt`.
- Import: `HC/data/healthconnect/{HealthConnectRepositoryImpl,HealthConnectRecordConverters,HealthChangeSynchronizerImpl,HealthChangeSyncSupport,StepRecordReader,IntervalTotalsReader}.kt`; `HC/domain/sync/{HealthSyncUseCase,DailySyncUseCase,ResyncRangeUseCase,FullHistoricalResyncUseCase,HealthIngestionCoordinator,ForegroundSyncController,StepCountFetcher,HealthConnectRetryPolicy,RetryWithBackoff}.kt`; `MODEL/domain/sync/mappers/`, `MODEL/domain/sync/link/`.
- Storage: all entity/DAO families under `SCHEMA/data/local/`; `DB/data/local/{HealthDatabase,DatabaseMigrations,RoomHealthIngestionStore,RoomHealthChangeIngestionStore,SessionLinkReconcilerImpl,DataRollupManager,MinuteBucketAggregator,WarmTierReconstructor,RetentionCleanup}.kt`; migration files through v19, database readiness and SQLCipher ownership.
- Scoring: `SCORE/domain/scoring/`, its strategies/components/sleep subpackages, cardio/calculation/recommendation/insight helpers; `DB/data/repository/{ScoringRepositoryImpl,ScoringDayDataLoader,ScoringHistoryRepositoryImpl,ScoringDayContextResolver,BaseSummaryAssembler,FinalSummaryAssembler,ReadinessSummaryCoordinator,ResidualFatigueComputer}.kt` and recommendation loaders; `MODEL/domain/sync/ScoreInvalidation.kt` and walk-forward context types.
- Background/UI/privacy: `APP/workers/`, startup orchestration, `APP/data/preferences/{HealthChangeTokenStoreImpl,ResyncCheckpointStoreImpl}.kt`, backup/restore/export/logging; dashboard/sleep/workouts/vitals/insights ViewModels and state factories, settings backup state, navigation and lifecycle-aware collection, manifests and backup/file-provider XML.
- Targeted validation infrastructure: `HealthSyncUseCaseTest`, `ScoringRepositoryImplTest`, biphasic integration tests, scoring documentation drift tests, DAO/migration/backup tests, `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`, `database-benchmark/`, `benchmark/{README,BASELINE}.md`.

API basis: the repository pins `androidx.health.connect:connect-client:1.1.0`, Room 2.8.5, SQLCipher 4.18.0, WorkManager 2.11.2, Kotlin catalog 2.4.10, AGP 9.4.0, minSdk 26, compile/target SDK 37. The cached **1.1.0 sources** for `ReadRecordsRequest` and `HealthConnectFeatures` were inspected: default page size is 1,000 records; ordinary public raw reads do not provide cross-origin deduplication; history/background support has runtime feature flags. Do not use restricted experimental deduplication internals or require an SDK upgrade for this plan. See [Health Connect releases](https://developer.android.com/jetpack/androidx/releases/health-connect) and [raw reads and access permissions](https://developer.android.com/health-and-fitness/health-connect/read-data).

Other authoritative references: [Changes synchronization](https://developer.android.com/health-and-fitness/health-connect/sync-data) documents separate per-type tokens, ID-only deletion changes and unused-token expiration within 30 days; [exercise routes](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes) distinguishes route data from consent requirements. [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) and [foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) establish that foreground WorkManager work is still subject to platform limits; target 37 does not grant unlimited execution. Sources checked on the audit date; provider-specific page-token persistence and device behavior remain validation items.

## 3. Current-State Architecture

```text
Health Connect 1.1.0 records / Changes API / aggregate steps
  -> repository converters -> app-owned DTOs
  -> DailySyncUseCase or ResyncRangeUseCase
  -> HealthIngestionCoordinator / HealthChangeSynchronizerImpl
  -> pure mappers + source filter -> Room ingestion stores
  -> SQLCipher Room v19: source identities, raw/session/vital tables
  -> full-range session relinking + workout zone metrics
  -> ScoringRepositoryImpl -> day loaders -> pure scoring use cases
  -> workout modelTrimp + daily_summaries + frozen baseline/recommendation
  -> repository Flows -> feature StateFlow -> collectAsStateWithLifecycle -> Compose

Raw HR >90 days -> DataRollupManager -> hr_minute_buckets
  -> reconstruction/weighted minute queries -> scoring and historical charts

Worker checkpoints + Changes tokens currently live in DataStore, separately from Room.
Backup/restore, cleanup, and rollup are additional local mutation paths.
```

`HealthSyncUseCase.syncMutex` serializes the two sync flows. Pull-to-refresh calls `triggerDailySync()` and scores today; the necessary preceding-day ingestion overlap supports overnight sessions. Old changes escalate to the historical worker. Full resync uses ingest, prune, reconcile, then ascending-date recompute, retaining the common progress bridge. Settings scoring changes enqueue recompute-only successors, not inline historical work. Do not widen foreground refresh to a catch-up scan.

Room v19 has 18 entities. HR/HRV use integer source FKs and natural unique `(sourceRecordRef,timestampMs)` indexes; sessions and workouts retain stable source IDs, while several vital IDs append timestamps. Raw HR has time/type/session indexes. Warm buckets have minute/type/session/device keys and nullable percentile sketches for legacy data, but no source-record FK. This is a meaningful provenance boundary, not simply another exact copy of raw data.

The scoring repository owns a calculation mutex and delegates to loaders and pure helpers. Some helpers are manually constructed private collaborators; that is valid ownership, not inherently a DI defect. Domain source purity is largely enforced by architecture tests, while `core:model` and `core:scoring` remain Android library build targets. The existing JVM migration plan records an AGP compiler compatibility blocker; do not revive it as a prerequisite for correctness fixes. No feature-module dependency on concrete database/Health Connect implementation was found in the inspected import scan. Widgets remain a concept plan, not an implemented invalidation consumer.

## 4. Findings Register

Severity is impact, not implementation order. **Confirmed** means the behavior follows directly from inspected code; it does not imply a device reproduction. **Suspected** identifies a risk whose manifestation still needs measurement or a controlled fixture. Complexity: S = local change, M = coordinated component change, L = persistence/protocol migration. All acceptance criteria below are mandatory for the associated work package.

### HC-001 — Snapshot imports do not authoritatively replace record contents

Category: ingestion correctness. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `SCHEMA/data/local/dao/HeartRateDao.kt:187`, `conflictTargetedUpsert`, updates only type/session/device; `HrvDao.kt:153` has the analogous RMSSD omission. `DB/data/local/RoomHealthIngestionStore.kt:103–138` invokes these directly. Its `reconcileHeartSource` checks parent IDs, and `reconcileCompositeMetric` accepts any timestamp-suffixed row whose base ID remains.

Current behavior/root cause: a stable-ID record with a changed value keeps the old BPM/RMSSD; removing one sample leaves that timestamp; moving a vital timestamp can leave both rows. Changes ingestion deletes first, so delta and full resync have different semantics. Impact: expired-token recovery and repeated full imports do not converge to a fresh database.

Remediation: preserve explicit source IDs through DTOs, replace a complete source record's payload atomically, update numeric columns, and reconcile its old/new sample keys only after a complete payload is available. Retain no-op writes for identical payloads. Do not replace the whole dataset or delete before remote reads finish.

Dependencies: CACHE-001, DB-001 for final protocol; a numeric-column correction can land first. Complexity: L. Migration risk: old stale samples require authoritative re-read; unavailable history must retain last valid data and be marked unreconciled. Acceptance: same-ID numeric change, removed/all-removed samples, moved timestamp, overlap, and replay twice produce the same rows and scores as clean ingest; interruption preserves a complete old or new payload.

### HC-002 — Resumed scans lose deletion completeness

Category: synchronization integrity. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `HC/domain/sync/HealthIngestionCoordinator.kt:386–390`, `collectReconcilableTypes`, excludes HR/HRV when resuming page tokens. `ResyncRangeUseCase.kt:317–335` still advances the chunk and later promotes baseline Changes tokens.

Current behavior/root cause: seen-ID sets exist only for the current process's pages. A deletion preceding baseline-token capture survives when the scan resumes, and that token cannot replay it. Impact: successful historical resync can retain deleted data indefinitely.

Remediation: persist seen identities under `(runId,chunkId,type)` and mark scan completeness, then use a bounded database anti-join before declaring the chunk complete. Safe initial fallback: restart the affected type's entire chunk scan and reconciliation; replay is acceptable until staging lands. Never prune from a partial or unavailable scan.

Dependencies: HC-001, HC-005, DB-001; staging is shared with PERF-001. Complexity: L. Migration risk: invalidate old incomplete checkpoint formats and replay safely, retaining prior data. Acceptance: kill in HR and HRV independently, with pre-baseline deletions and multi-page updates; resumed success matches uninterrupted rows and scores, and cancellation never treats unseen pages as deletions.

### HC-003 — Permission state is confused with empty data and stale tokens

Category: Health Connect lifecycle. Severity: **High**. Confidence: **High**. Status: **confirmed**; optional-read/regrant pruning race is **suspected**.

Affected/evidence: `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt:62–79,150–157` checks grants only for missing tokens; `APP/data/preferences/HealthChangeTokenStoreImpl.kt:59–69` merges new tokens without removing denied types. `StepRecordReader.readSteps` returns zero on security failure; `StepCountFetcher.fetchRange` seeds zero totals. Repository/reader permission APIs do not model feature-unavailable separately from permission-denied history/background support.

Current behavior/root cause: a revoked optional type with a token repeatedly schedules full resync; denied steps overwrite valid summary totals. Optional readers return empty then reconciliation rechecks permission, permitting a suspected deny/regrant race. Impact: unnecessary history work and false data deletion/zero presentation.

Remediation: return typed `Available(data)`, `Denied`, `Unsupported`, or failure from reads; only successful complete reads authorize empty-data deletion. Suspend denied token state, bootstrap on regrant, and probe 1.1.0 history/background feature flags before offering those permissions. Use null/no-update for unavailable steps, explicit zero for authorized empty days, in both source modes.

Dependencies: CACHE-001; DI-001 supplies common client/access policy. Complexity: M. Migration risk: replace token state per type without deleting local health data. Acceptance: grant→sync→revoke→repeat→regrant does not loop workers or zero data; unsupported-provider cases have actionable state; injected mid-read denial never authorizes pruning.

### HC-004 — VO2 Max and interval enrichments have incomplete update/deletion lifecycles

Category: supported-type completeness. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `MODEL/domain/model/HealthDataType.kt` omits VO2 Max; `HC/data/healthconnect/HealthChangeSynchronizerImpl` type dispatch and coordinator reconciliation also omit it, although `readVo2MaxRecords` and `RoomHealthIngestionStore.persist` store it. `IntervalTotalsReader` reads distance/elevation only as exercise enrichment; their own changes are not tracked.

Current behavior/root cause: historical VO2 updates/deletions remain stale; a distance/elevation change without an exercise change is not a delta trigger, and null enrichment preserves old totals even after an authoritative deletion. Impact: incorrect fitness history, distance, pace/speed, and elevation displays.

Remediation: register VO2 through permission, source selection, Changes dispatch, deletion, and invalidation. Track distance/elevation change ranges and refresh overlapping workouts, or implement a documented bounded refresh policy if independently tracking interval IDs is deliberately declined (OD-4). Distinguish authoritative absence from inaccessible enrichment.

Dependencies: HC-003, CACHE-001, DB-001. Complexity: M/L. Migration risk: optional registry additions must preserve stable existing enum names and older backup compatibility; add identity metadata only if required for interval deletion ranges. Acceptance: historical insert/update/delete beyond today's window converges for VO2; independent interval edits refresh affected workouts; no unrelated history is reread.

### HC-005 — Retry identity is not an immutable historical run

Category: durable work. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `FullHistoricalResyncUseCase.execute` resolves today/retention anew (`HC/domain/sync/FullHistoricalResyncUseCase.kt:48–75`); `ResyncRangeUseCase.kt:100–117` requires matching dates but includes a scoring fingerprint only for recompute-only mode.

Current behavior/root cause: retry across scoring midnight discards progress; full-resync retry after preference/zone change may resume recomputation with mixed settings. Impact: repeated restart of dense multi-year work and inconsistent frozen outputs.

Remediation: persist run ID, mode, immutable range/zone, selection identity, algorithm version and canonical scoring snapshot. Resume that run across midnight; enqueue today's follow-up separately. If scoring inputs change, preserve valid ingestion but restart affected reconcile/recompute under one new snapshot. Treat rejected provider page tokens as a reason to replay the incomplete scan, not as proof of completion. Preserve `KEEP` for explicit full resync and existing progress keys.

Dependencies: HC-002, CACHE-001. Complexity: L. Migration risk: version checkpoints; never promote tokens from a legacy/mismatched run. Acceptance: retry from every phase across midnight/DST and every material setting change yields a coherent result; foreground-service/job interruption resumes progress without widening daily refresh. Provider page-token longevity is an explicit device validation, not an assumed API guarantee.

### HC-006 — Exercise delta replacement destroys optional data and performs IPC inside Room transactions

Category: atomicity and background performance. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `HealthChangeSynchronizerImpl.kt:129–139,215–220` wraps processing in a Room transaction and deletes the old exercise first; `upsertExercise` calls two `sessionTotalFor` reads (`:312–313`). `RoomHealthChangeIngestionStore.deleteRecord` cascades route removal; `RoomHealthIngestionStore.persist` then finds no previous workout to preserve.

Current behavior/root cause: an update without route access loses previously imported GPS and optional totals; remote paging/retries hold the database writer. Impact: local route loss, prolonged locks, and inconsistent bulk/delta semantics.

Remediation: prepare remote enrichment before the transaction, then update the parent in place and reconcile children from explicit read outcomes. Preserve unreadable data; clear only authoritative removal. Atomically record old/new affected ranges with the mutation.

Dependencies: HC-001, HC-003, CACHE-001. Complexity: M. Migration risk: cannot recover erased routes without renewed provider access; do not request new location permissions merely to implement this fix. Acceptance: exercise updates with unavailable routes retain old points; known deletion removes them; no Health Connect call occurs within a Room transaction; failure before commit leaves the prior workout intact.

### DB-001 — Source identity metadata does not describe the records being reconciled

Category: schema and provenance. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `RoomHealthIngestionStore.persistHeartRateSamples/persistHrvSamples` sets every newly created source's `createdAtMs` to `samples.first().timestampMs`. `SourceRecordDao.getByRecordTypeAndRange` uses that field with inclusive endpoints; `HealthSourceRecordEntity` indexes only source ID. IDs are recovered using `substringBefore('_')` rather than carried explicitly.

Current behavior/root cause: reconciliation range membership depends on page composition and first insertion, not actual source/sample bounds. Source queries scan an unindexed type/time predicate. A provider ID containing an underscore is an unverified additional parsing risk, not an asserted HC behavior. Impact: missed/incorrect deletion scope and per-chunk scans of the source table.

Remediation: carry opaque source ID, origin, record type and actual covered start/end timestamps through DTOs; keep last-modified metadata when available. Add indexed reconciliation metadata and stop parsing IDs. Backfill bounds from child rows in keyset batches; mark sources with only legacy warm data as unknown rather than inventing timestamps/origins. Collect actual old and new sample extents for invalidation.

Dependencies: foundation for HC-001/002 and DB-002. Complexity: L. Migration risk: additive nullable columns/indexes first, explicit metadata completeness, source FK stability, extra storage proportional to parent records. Acceptance: changing page size/order never changes deletion membership; timestamp moves update bounds; query plans use the new index; migrated unknown identities cannot trigger destructive absence inference.

### DB-002 — Hot/warm coverage can overlap, lose samples, and retain deleted data

Category: tier correctness. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `DataRollupManager.rollupDayChunk` upserts buckets from remaining raw rows then deletes them. The cutoff from `RetentionBounds.resolveHotTierCutoffMs` is not minute-aligned. `MinuteBucketDao.upsertBuckets` replaces the complete minute key. `HrMinuteBucketEntity` has no source identity; source deletion and `SessionLinkReconcilerImpl` touch raw data only. `ScoringHistoryRepositoryImpl`, `ScoringDayDataLoader`, and `HeartRateRepositoryImpl` concatenate/sum both tiers.

Current behavior/root cause: successive partial-minute rollups can replace earlier samples with only the later remainder. Historical reimport overlaps existing warm buckets, double-counting observations; deleting/relinking a rolled source cannot update buckets accurately. This exceeds the documented approximation tolerance: it is stale/duplicate coverage, not percentile reconstruction error.

Remediation: roll up only complete minute coverage; define one authoritative tier/generation per covered interval. Stage historical refresh and replace affected warm coverage only after a successful complete read. For exact future source deletion, retain per-source aggregate contributions or enough durable provenance to rebuild the whole affected minute from authoritative data (OD-1). Reconcile warm session assignments before score publication. Do not add averages/percentiles from unrelated generations.

Dependencies: DB-001, CACHE-001, ARCH-001. Complexity: L. Migration risk: legacy buckets cannot reveal lost source membership or original samples. Preserve them as legacy/approximate until an authorized complete refresh replaces them; never blanket-delete warm history. Acceptance: repeated rollup within the same minute, mixed-tier boundary, historical edit/delete/relink, source selection, and killed refresh all maintain single coverage and deterministic within-tier results.

### CACHE-001 — Raw mutations and their invalidation are not durably atomic

Category: cache integrity. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `HealthChangeSynchronizerImpl.processChangesPage` commits Room mutations while affected dates remain in memory; token promotion is later in `DailySyncUseCase`. Replayed deletion obtains dates from the now-missing row in `RoomHealthChangeIngestionStore.affectedDatesForRecord`. Cleanup/rollup return ranges only after mutation and workers enqueue recompute afterward.

Current behavior/root cause: kill after historical deletion commit but before scoring; replay finds no old date, so the token can advance after today's score only. Moved records lose their old extent similarly. Kill after cleanup/rollup but before enqueue also loses required work. Delayed token advancement alone is insufficient.

Remediation: add a Room dirty-range journal/outbox in the same transaction as each authoritative mutation. Store reason, affected old/new extent, source generation and scoring snapshot identity; retain it until matching summaries/recommendations have committed. Replay and startup drain pending entries idempotently; checkpoint and token promotion require durable completion evidence.

Dependencies: no behavioral prerequisite; schema foundation for most ingestion fixes. Complexity: L. Migration risk: existing unknown stale history needs one bounded repair run, not guessed dirty dates. Acceptance: fault injection at every mutation/score/token/enqueue boundary loses neither a source update nor its dependent recomputation; rerunning deletion remains effective even after the source is gone.

### CACHE-002 — Fixed 84-day fan-out misses transitive and sparse-data dependencies

Category: incremental correctness. Severity: **High**. Confidence: **High**. Status: **confirmed** for incomplete dependency model; numerical size depends on data.

Affected/evidence: `MODEL/domain/sync/ScoreInvalidation.kt` widens by 84 days only. A changed sleep baseline affects later workout TRIMP, which then enters later load windows; `ResidualFatigueComputer` explicitly uses all retained canonical impulses with no finite tail cutoff. `RetentionCleanup.deleteBefore` only tracks HR/bucket dates, so sleep/HRV/vital-only deletions can return null. Cleanup/rollup workers use UTC-derived date ranges and `LocalDate.now(clock)` instead of the stored scoring zone.

Current behavior/root cause: the maximum individual lookback is treated as the dependency closure. Range tracking also assumes HR accompanies every other metric. Impact: stale later load/fatigue/recommendations, missed sparse-data updates, and possible missed leading scoring-zone date. The direct old fatigue tail may be tiny; its nonzero value alone does not justify changing the model.

Remediation: record metric-specific dependencies, including current/previous-day inputs, baseline→workout→load propagation, latest-value carry-forward vitals, and recommendation examples. Initially recompute the retained suffix from earliest affected score day through today for stateful/transitive changes; narrow only after equivalence proof. Resolve every mutation range using the run's scoring zone and intersect retained dates.

Dependencies: CACHE-001, SCORE-001–005, DB-002. Complexity: L. Migration risk: increased temporary rebuild cost; use resumable passes and retain previous valid outputs until replacement. Acceptance: incremental correction equals a clean ascending rebuild for early/middle/recent edits, HR-absent histories, DST, and non-UTC zones; no retained dependency is excluded by a single-window constant.

### SCORE-001 — Historical RHR backfill includes future nights

Category: **confirmed implementation bug**. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `SCORE/domain/scoring/BaselineComputer.kt:485–519`, `computeDayBackfillBaseline`, bounds HRV with `scoreDay < target` but filters RHR nadirs/history only by lower date; `computeBackfillBaselines` supplies nights through the maximum summary date.

Current behavior/root cause: an earlier frozen RHR/sigma can depend on later nights. Impact: baseline and score history changes when later data is added, contrary to walk-forward determinism.

Remediation: use the live RHR window's documented upper bound when selecting backfill nights; keep HRV's separate exclusion rule. Share the date-window policy between live/backfill paths. Do not change percentiles, thresholds or coefficients.

Dependencies: Phase-0 characterization; invalidate impacted frozen snapshots through CACHE-001. Complexity: M. Migration risk: corrected historical scores legitimately change; version the repair and recompute once. Acceptance: adding arbitrarily different nights after D leaves D's RHR/sigma unchanged; bulk backfill equals per-day reference on sparse and DST histories, including the exact D boundary.

### SCORE-002 — Calibration state is inconsistent across live, frozen, and matured histories

Category: **confirmed implementation bug**. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `ComputeSleepMetricsUseCase.resolveBaselineWindow` sets frozen history count to zero (`:538–545`), then `invoke` derives phase/calibration from that count (`:200–203`). Its live count is bounded by the 56-day HRV history although `components/Phase` requires 60 observations for MATURE. `AssembleDailySummaryUseCase.assembleCalibrated` does not explicitly clear a previously true `isCalibrating`.

Current behavior/root cause: baseline-window count, lifetime maturity count, and stored phase are conflated. Frozen replay can switch to calibration flags; live scoring cannot reach the documented mature threshold through this path; old calibration booleans can persist.

Remediation: give maturity its own bounded-query/count contract and persist/reuse the matching snapshot count/phase. Explicitly assign calibration fields on every branch. Preserve the documented <7-day calibration threshold and resolve lifetime-versus-window maturity semantics in OD-2 before changing that boundary.

Dependencies: SCORE-001, CACHE-001. Complexity: M. Migration risk: reconstruct legacy counts where source history permits, otherwise retain an explicit unknown state; never invent mature history. Acceptance: 6/7, 29/30, 59/60 valid observations, gaps, repeated frozen replay, and calibration→calibrated transition yield consistent booleans, phase, flags and display.

### SCORE-003 — Authoritative absence can preserve stale sleep and recommendation outputs

Category: **confirmed implementation bug** in deletion convergence. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `DB/data/repository/BaseSummaryAssembler.buildBaseSummary` copies the old summary; `FinalSummaryAssembler`'s no-session branch does not clear all former sleep fields. `ScoringRepositoryImpl.applyRecommendation` uses `assemble(...) ?: previous` for every missing result.

Current behavior/root cause: unavailable input and authoritative source absence use the same null/preserve behavior. After deletion, a prior score/HRV/sleep duration or workout recommendation can remain. Preserving a previous result on transient failure is an intentional useful policy, but it must not cover confirmed deletion.

Remediation: use explicit computed/absent/unavailable results in assembly; create fresh derived fields for an authoritative day, copying only independently owned state such as frozen configuration when valid. Clear dependent sleep/restoration/recommendation fields on confirmed absence and persist appropriate no-data state.

Dependencies: HC-003, CACHE-001. Complexity: M. Migration risk: formerly visible stale values become no-data; repair them using source truth. Acceptance: delete the only sleep after calibration and rebuild twice; no stale sleep or recommendation remains. A transient read/calculation failure still preserves the last valid snapshot with an unavailable status.

### SCORE-004 — Merged sleep duration and recovery inputs use different session scopes

Category: **confirmed implementation bug** / input-assembly mismatch. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `DB/data/repository/ReadinessSummaryCoordinator` builds a synthetic session with total sleep duration including supplemental sleep but core recovery start/end; `SCORE/domain/scoring/sleep/SleepNadirAnalyzer` normalizes core nadir position by that duration. `SleepModifierResolver` loads stages only for the synthetic base session ID, omitting later merged core segments.

Current behavior/root cause: a single synthetic session represents both whole-day duration and core-only recovery. A nap can change the late-nadir decision; fragmentation ignores other core stages. Impact: sleep/readiness depend on unrelated supplemental duration or which segment became the base session.

Remediation: pass separate whole-sleep duration/architecture and core recovery interval/stage inputs. Fetch stages for all canonical core IDs, ordered and deduplicated under the existing sleep policy. Preserve core merge/cutoff/nap rules and all weights.

Dependencies: characterization of `SleepDayAggregator`; SCORE-003 for result assembly. Complexity: M. Migration risk: biphasic scores may change; synchronize About/tooltips and versioned recompute. Acceptance: adding only a supplemental nap does not change core nadir/fragmentation; splitting an equivalent core into allowed segments produces the same core recovery result; stage-less and overlap fixtures remain supported.

### SCORE-005 — Workout display and persisted scoring use different TRIMP inputs

Category: **confirmed implementation bug** / consistency mismatch. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `SCORE/domain/scoring/ComputeWorkoutLoadMetricsUseCase` computes display TRIMP from stored `avgHr` and current preferences without supplying the summary's frozen hrMax. `ComputeDailyTrimpUseCase` uses loaded sample average (or zero when absent) and frozen hrMax; `GetWorkoutDisplayMetricsUseCase`, recommendation examples, and fatigue consume these different paths.

Current behavior/root cause: shared formula code does not guarantee shared input provenance. Same workout can display a different load/classification than its canonical daily/fatigue contribution, especially after raw rollup or hrMax changes.

Remediation: establish one canonical workout scoring input/result with explicit configuration snapshot, HR resolution and missing-sample policy. Presentation should read that result or use the exact same resolver; do not independently reconstruct coefficients in UI helpers. Confirm the no-HR fallback product rule in OD-3.

Dependencies: DB-002, SCORE-001/002. Complexity: M. Migration risk: modelTrimp repair changes later load/fatigue; rebuild its dependency suffix. Acceptance: all supported TRIMP models agree across workout card/detail, daily sum, recommendation examples and fatigue for frozen hrMax, absent HR, warm HR and changed settings; zero-duration inputs remain finite.

### PERF-001 — Paged ingestion still grows with chunk cardinality and performs per-source transactions

Category: large-volume ingestion. Severity: **High**. Confidence: **High**. Status: **confirmed** allocation/query patterns; device latency unmeasured.

Affected/evidence: `HealthIngestionCoordinator.streamAndPersistHeartSamples` retains `hrIds/hrvIds` for the entire chunk even with deletion reconciliation disabled. `HeartRateMapper.mapToInputs` flattens/sorts all nested samples in a page and copies each item. `RoomHealthIngestionStore` calls `SourceRecordDao.getOrCreateSourceRef` outside the 500-sample write transactions, once per parent. Full deletion reconciliation materializes source/deletion lists and uses unbounded session/step `IN/NOT IN` sets.

Root cause/impact: a parent-record page is mistaken for a bound on all downstream state. More than one million parent records can cause approximately one million source-reference transactions plus sample writes; dense nested pages and seen-ID sets consume proportional heap. SQLite parameter-limit failures are a suspected consequence requiring device/driver fixtures.

Remediation: page-sized bulk source lookup/insert under the sample transaction, sample-count-limited transform buffers, persisted seen-ID staging/anti-join, and keyset deletion. Do not collect seen IDs when not used. Begin with existing 500-row writes and 5,000-row reconciliation; tune only from measurements. The SDK-owned single-record payload remains an irreducible memory bound.

Dependencies: DB-001, HC-001/002. Complexity: L. Migration risk: staging storage/WAL growth; purge only completed/abandoned run staging. Acceptance: heap live set does not grow with total scanned IDs; transaction count scales with batches, not parent count; no SQL statement exceeds verified binding limits; repeated identical import avoids changed-row churn.

### PERF-002 — Historical scoring repeatedly expands warm data and re-reads baseline/fatigue histories

Category: scoring scalability. Severity: **High**. Confidence: **High**. Status: **confirmed** work patterns; cost magnitude unmeasured.

Affected/evidence: `ScoringHistoryRepositoryImpl.getSleepHrProjectionForSessions` queries warm buckets once per session, reconstructs `sampleCount` values, merges and sorts them. `getAvgSleepHrForSessions` calls this projection then groups it. `HistoricalSleepDayAssembler` groups/maps/sorts again. `WalkForwardBaselineContext` caches sessions, not derived nightly inputs. `ResidualFatigueComputer.computeAt` loads all prior canonical workouts; morning recommendation reconstruction invokes this path during daily historical scoring.

Root cause/impact: removing session-query N+1 did not remove sample-query N+1 or repeated historical calculations. Full replay can revisit overlapping night samples and workout prefixes for each day, tending toward O(D×history) work; warm compression saves disk but not reconstructed heap.

Remediation: batch warm reads, use weighted SQL sums/counts for averages, and compute exact per-night baseline statistics once per run/window. Cache small derived night records keyed by source revision/tier/algorithm/configuration. Use a separate ascending morning fatigue cursor alongside the existing end-of-day cursor; never rewind the latter. Preserve exact retained-history decay, percentile semantics and reference rounding.

Dependencies: SCORE-001–005, CACHE-001/002, DB-002. Complexity: L. Migration risk: a persistent nightly cache is optional; start with run-scoped caches to avoid premature schema. Acceptance: raw-night reads and prior-workout scans do not repeat per target day; optimized/reference scores match within existing documented storage precision; no synthetic full-history HR list is needed for an average or chart.

### PERF-003 — Rollup and backup retain unbounded materialization points

Category: database/memory scalability. Severity: **Medium**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `DataRollupManager.rollupDayChunk` reads every plausible sample in a day inside its transaction; `MinuteBucketAggregator` `groupBy` retains those samples and creates sorted per-bucket copies. `BackupStreamWriter.writeCoreTables` uses `SourceRecordDao.getAll()` even though most other tables are keyset-paged. Retention never removes orphan source metadata.

Root cause/impact: day-bounding does not bound arbitrary multi-device sample count; one million parent identities still expand in backup and accumulate after raw cleanup. Existing per-day transactions are better than whole-history transactions but insufficient as a strict memory guarantee.

Remediation: process ordered complete bucket groups in bounded row pages, carrying only the unfinished bucket (or a bounded exact BPM histogram after plausibility filtering). Page source metadata by integer ID; delete truly unreferenced metadata only after considering new warm provenance and pending jobs. Keep CPU-heavy aggregation out of long writer transactions using generation validation.

Dependencies: DB-002, ARCH-001, SEC-003. Complexity: M. Migration risk: orphan cleanup must not remove warm/deletion lineage or backup foreign keys. Acceptance: dense single-day rollup and million-source backup stay bounded; interruption preserves complete buckets; backups include every referenced source exactly once.

### ARCH-001 — Mutation ownership stops at the sync facade

Category: architecture/concurrency. Severity: **High**. Confidence: **High**. Status: **confirmed** missing coordination; interleavings require controlled validation.

Affected/evidence: `HealthSyncUseCase.withSyncLock` protects daily/resync/startup participants, while `DataRollupWorker`, `DataCleanupWorker`, `LocalRestoreServiceImpl` and backup operations use separate paths. Restore commits Room before preferences and reschedules workers during preference application. The scoring mutex cannot protect inputs loaded by other workflows.

Root cause/impact: SQLite serializes individual writes, not a complete data-generation transition. A sync can load old settings before restore and publish into restored data; rollup can change resolution during reconciliation/scoring; backups can cross generations.

Remediation: introduce a narrow domain maintenance/mutation coordinator shared by these operations, implemented once at application scope. Define lock order, generation checks, cancellation and restore recovery state. Begin with serialization for correctness; later permit measured read/snapshot concurrency. Retain both current sync entry points and avoid a feature→HC implementation dependency merely to acquire a mutex.

Dependencies: CACHE-001. Complexity: L. Migration risk: deadlock, excessive foreground waits, and process death between Room/DataStore commits; recover from a durable maintenance record before scheduling fresh work. Acceptance: concurrent restore/sync/rollup/cleanup cannot publish mixed-generation scores; interrupted restore resumes or reports its existing partial-recovery state; no nested lock inversion.

### DI-001 — Time and provider ownership bypass existing injectable boundaries

Category: DI/testable determinism. Severity: **Medium**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `StepRecordReader` and `IntervalTotalsReader` use mutable `clientOverride` and construct a client through `getOrCreate` rather than the singleton binding used by `HealthConnectRepositoryImpl`. `ResidualFatigueComputer.retentionStartMs` calls `Instant.now()`; `DataCleanupWorker`/`DataRollupWorker` derive today from `Clock.systemDefaultZone()` supplied by `DataStoreModule` rather than the stored scoring zone.

Root cause/impact: one ingestion operation can use separately owned provider access/retry seams; historical replay's retention gate depends on execution time outside its snapshot. Timezone changes can affect invalidation despite documented determinism.

Remediation: inject the same HC client/provider boundary into readers and remove production mutable test overrides; use existing dispatcher qualifiers. Pass one run instant, scoring zone and retention window into historical loaders; live UI may still use a fresh injected instant. Keep stateless private helper construction where ownership is already clear.

Dependencies: HC-003/005, ARCH-001. Complexity: M. Migration risk: Hilt startup on HC-unavailable devices must remain safely gated; no new singleton cache of mutable user preferences. Acceptance: all HC readers use the same fake/real client seam; fixed-clock replay does not consult wall time; stored scoring-zone behavior survives device travel.

### SEC-001 — Release diagnostics can expose raw exception content

Category: **privacy risk**, not demonstrated external disclosure. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `APP/HealthDashboardApplication.kt:201` installs the release sink; `APP/util/SecureFileLogSink.kt:77–84` sends original messages/throwables to logcat before file sanitization. `MODEL/domain/crashreport/CrashReportFormatter.kt:14` emits `stackTraceToString()`. Restore warning strings can contain backup-supplied values. `docs/privacy.md` claims debug-only diagnostics and no health data in crash reports.

Root cause/impact: destination-specific regex sanitization cannot enforce a production-safe event policy; nested exception messages can contain health/location/storage content. Output is local unless a user shares it, but it violates the disclosed boundary.

Remediation: format release-safe structured events before any sink, allowing approved fields and exception classes/frames rather than arbitrary payload text; apply to causes/suppressed exceptions, crash reports and exports. Define retirement of old unsafe log/report files and update disclosures.

Dependencies: none; can land early. Complexity: M. Migration risk: loss of diagnostic detail, handled with safe reason codes and retained stack frames. Acceptance: synthetic health values, GPS, source IDs, SAF URIs and nested payloads never appear in release output; manual sharing remains explicit; no network permission or telemetry is introduced.

### SEC-002 — Backup password rotation is not recoverable

Category: backup data integrity. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `SETTINGS/LocalBackupViewModel.kt:209–230`, `UpdateBackupPassword`, stores the new password even after re-encryption failure. `APP/data/backup/LocalBackupManager.reencryptBackups/reencryptOneBackup` publishes each archive separately.

Root cause/impact: failure midway produces mixed-password archives and changes the single stored credential. Returning early on failure alone would still leave partially rotated archives. Concurrent scheduled/manual rotation/backup also lacks common ownership.

Remediation: move rotation to a serialized recoverable service: stage and verify new archives, preserve old copies/credential recovery metadata, publish with a per-archive journal, then commit the selected password. SAF publication must not assume filesystem atomic rename. Retain compatibility with existing encrypted ZIPs.

Dependencies: ARCH-001 for common operation coordination; staging/journal can be isolated first. Complexity: L. Migration risk: existing mixed-password archives require user-supplied old passwords; do not delete them automatically. Acceptance: failures before/after each publication and before preference commit, plus process death, leave every original archive recoverable and retries convergent on both file and SAF stores.

### SEC-003 — Export does not capture a consistent recoverable snapshot

Category: backup consistency. Severity: **High**. Confidence: **High**. Status: **confirmed** absent snapshot; failure requires concurrent mutation.

Affected/evidence: `APP/data/backup/BackupStreamWriter.kt:42–61` separately reads counts, preferences and paged tables; `collectRowCounts` runs independent reads. No enclosing snapshot or operation coordinator connects source rows, raw HR, warm buckets and derived summaries.

Root cause/impact: a syntactically valid encrypted archive may contain mismatched source FKs, both/neither tier coverage, or summaries from another generation. Row counts can disagree even without parser errors.

Remediation: export a consistent Room snapshot with explicit preference/layout snapshots and source/scoring generation. Prefer a coherent snapshot boundary before compression/publication; benchmark reader lifetime and WAL growth. Do not hold a database write transaction across Health Connect, ZIP work or SAF publication. Include a bounded-source reader from PERF-003.

Dependencies: ARCH-001, CACHE-001, DB-002. Complexity: L. Migration risk: long read snapshots pin WAL; temporary disk budget and cancellation cleanup must be measured. Acceptance: force sync/rollup between table pages, restore the archive, and verify FKs/counts/generation/single coverage; failed export does not publish an apparently complete backup.

### SEC-004 — Restore validates version but not a complete replacement dataset

Category: data-loss risk. Severity: **High**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `APP/data/backup/LocalRestoreManager.readManifest` checks schema version but does not enforce table inventory or restored row counts. `performStreamingRestore:242–249` clears core tables unconditionally; `RestoreVitalsLoader` clears each vital table only when its JSON field appears.

Root cause/impact: a supported-version archive missing core arrays can erase core data and report success; missing legacy/optional vital arrays can instead leave pre-restore data mixed with restored history.

Remediation: validate schema-specific required/optional tables and counts; centrally define complete replacement semantics; absent legacy optional tables restore as empty/default. Validate before commit using staging or the existing rollback-capable transaction. Coordinate restored preferences, generation, pending jobs and caches through ARCH-001.

Dependencies: ARCH-001 for full operation integrity; parser/inventory guard may land first. Complexity: M/L. Migration risk: infer required arrays from actual historical formats v5 through current, not today's schema alone. Acceptance: missing required tables, duplicate source identities, FK failures or count mismatches leave the original database intact; legitimate legacy omissions do not retain unrelated current vitals.

### SEC-005 — Failed backups can remove recovery points and leave plaintext staging

Category: recovery/privacy risk. Severity: **Medium**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `LocalBackupManager.createBackup` prunes old archives before validating/creating a new one; it writes full plaintext JSON to cache, deleted only in `finally`. `APP/crashreport/CachePrune.kt` only cleans diagnostic directories.

Root cause/impact: a failed creation can remove the last old restore point; process death bypasses plaintext cleanup. Private cache is not an external disclosure, but health-data plaintext survives longer than intended.

Remediation: prune only after verified publication and preserve at least one recoverable archive. Stream directly into encryption where the existing ZIP API permits; otherwise use a dedicated private staging directory with startup orphan cleanup and exclusive operation ownership.

Dependencies: SEC-002/003, ARCH-001. Complexity: M. Migration risk: account for disk space and SAF partial files; only remove validated app-owned staging targets. Acceptance: failed backup never deletes the last good archive; restart after kill removes plaintext staging while preserving valid encrypted backups.

### UI-001 — Backup directory state can expose actions for the previous directory

Category: UI/domain-state consistency. Severity: **Medium**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `SETTINGS/LocalBackupViewModel.kt:64` reloads archives from `refreshTrigger`; `ChangeBackupDirectory:180–184` updates preferences without that trigger. The displayed directory can change while the list remains from the old location.

Root cause/impact: the domain query key omits directory identity; stale restore/delete actions appear under the new directory label.

Remediation: derive listing from `(directoryUri,refreshGeneration)` using cancellation-aware switching and explicit loading/error state; bind actions to the listed archive's location. Keep dropdown/toggle state in Compose and lifecycle-aware StateFlow collection.

Dependencies: none; coordinate with SEC-002 so rotation status comes from the service. Complexity: S. Migration risk: none. Acceptance: rapid A→B→A switches and inaccessible B never expose stale archive actions; no broad Compose redesign or new UI-state persistence.

### DOC-001 — Load-bearing documentation contains contradictory and stale contracts

Category: documentation mismatch. Severity: **Medium**. Confidence: **High**. Status: **confirmed**.

Affected/evidence: `DATA_FLOW.md` overview says v19 while section 1.4 says v17; mapper registry names removed vital mapper files and describes outdated module ownership. `docs/privacy.md` describes a ten-year local retention ceiling although disabled cleanup returns null and keeps local history indefinitely; ten years bounds resync. Its diagnostics claims conflict with SEC-001. `ABOUT.md` promises identical resync scores while later qualifying warm reconstruction.

Root cause/impact: successive remediation/features updated local sections without reconciling the complete contract. A future agent can implement an obsolete path or incorrectly promise deletion/determinism.

Remediation: update exact current ownership/schema and distinguish import horizon, local retention, within-tier determinism and cross-tier approximation. Tie each behavioral fix to the required document/string updates; do not duplicate formula coefficients in DATA_FLOW.

Dependencies: all implementation packages for their affected sections. Complexity: M distributed across packages. Migration risk: changing copy must not silently change product policy. Acceptance: registry paths resolve; schema version agrees; site/About/tooltips/permission/privacy copy agrees with code and required drift tests.

### SCORE-006 — Baseline fallback and displayed RHR references have inconsistent membership

Category: **maintainability concern** with a **likely implementation bug** in historical fallback. Severity: **Medium**. Confidence: **High** on code, **Medium** on reachable impact. Status: **suspected** user-facing fallback error.

Affected/evidence: `SleepPercentileRhrCalculator` excludes current session IDs and uses individual sessions/fixed 24-hour day bounds; `BaselineComputer.computeAdaptiveBaselineRhrBpmBetween` uses validated aggregated sleep days including its allowed current-day inputs. `RhrBaselineProvider` calls unbounded `BaselineComputer.rhrHistory`; `HrvBaselineProvider` has an analogous `getSleepSessionsSince` fallback. Other provider helpers have no observed production consumers beyond their definitions.

Root cause/impact: similarly named quantities hide distinct membership/precedence rules; historical fallback can see later data if invoked for an old date. It is not established that every different RHR reference is unintended.

Remediation: inventory consumers, remove unused providers, bound reachable fallback queries to the requested date and scoring zone, and name intentionally distinct display/adaptive baselines explicitly. OD-5 decides whether displayed ratio should use the same reference. Dependencies: SCORE-001/005, DI-001. Complexity: M. Migration risk: do not change ratio semantics silently. Acceptance: reachable historical fallback is future-independent; stored/displayed baseline reference and window are explicit; no dead compatibility path remains after consumer verification.

### SCORE-007 — Point-in-time and selected-device steps have inconsistent day boundaries

Category: **confirmed implementation bug** for VO2 upper bound; **product decision requiring confirmation** for step apportionment. Severity: **Medium**. Confidence: **High**. Status: **confirmed** behavior.

Affected/evidence: `FinalSummaryAssembler.resolveWearableVo2Max` uses `floorEntry(nextDayMidnightMs)`; `Vo2MaxRecordDao` uses `<= maxTimestampMs`, admitting tomorrow's midnight. `MODEL/domain/sync/mappers/StepsMapper.StepEntry` drops end time and `sumByDay` credits the entire interval to its start date. Selected-device `StepCountFetcher` also omits authorized zero days, so preserved summaries can retain old totals.

Root cause/impact: half-open days and interval attribution are not shared between query/prefetch and aggregate/raw modes. Midnight readings can affect the previous day; a cross-midnight steps record changes meaning with source mode.

Remediation: use exclusive next-day bounds consistently, stable ties for simultaneous VO2 readings, and typed complete daily results. Preserve interval start/end for steps; select and document apportionment in OD-6 instead of inventing per-step times. Dependencies: HC-003/004, CACHE-001. Complexity: M. Migration risk: boundary-day values change; no schema needed for step end time already present in raw records. Acceptance: midnight VO2 belongs only to the new day; query and prefetch match; selected-device authorized empty days clear; DST/cross-midnight step totals conserve the chosen rule's total.

## 5. Scoring and Metric Verification Matrix

“No independent defect found” means static formula/input review found no additional actionable issue; it is not a mathematical or clinical certification. Preserve existing finite-input guards, bounds, precision, and missing-data rules unless a listed fixture demonstrates a defect. Each repaired path must test zero/negative duration, zero denominators, NaN/infinity at the untrusted boundary, empty/sparse inputs, and rounding only where those values can reach that path; this is not a proposal for blanket tests of every helper.

| Metric | Implementation (under aliases above) | Source inputs | Documented rule | Implemented behavior / review result | Findings / required action |
|---|---|---|---|---|---|
| Sleep duration/efficiency | `SleepDataMapper`; `SCORE/domain/scoring/sleep/SleepDayAggregator`; `SleepScoringStrategy` | Stages/session milliseconds; minutes, percentages | Balanced duration 40%, architecture 20%, restoration 25%, fragmentation 15%; five selectable profiles | Stage-less span fallback; canonical core/supplemental aggregation; synthetic recovery scope differs | SCORE-004; preserve profiles, duration/efficiency curves and stage-less behavior |
| Architecture/segmented sleep | `SleepDayAggregator`; `components/SleepArchitectureTargets` | Deep/REM/light minutes, age, coverage | Count qualifying supplemental architecture under existing policy | Intentional coverage/canonicalization decisions, not a reason to redesign sleep | SCORE-004; characterize equivalent split/overlap inputs |
| Fragmentation/WASO | `sleep/SleepModifierResolver`, `SleepFragmentationCalculator` | Ordered stages/core intervals | Continuity over the scored core | Only base ID's stages fetched for merged core | SCORE-004; use complete core stages |
| Sleep debt | Sleep duration/insight helpers | Goal and recent duration | Duration deficits/context | No separate canonical debt-ledger engine identified | No new debt feature; preserve existing insight rules |
| Circadian/regularity | `CircadianConsistencyRepository`, `CircadianWakeBaseline` | Prior wake times, offsets, profile thresholds | Existing consistency model; sleep multiplier 0.92–1.00 | Existing pure policy; repeated history work and frozen travel context need attention | SCORE-004, PERF-002; preserve multiplier/thresholds |
| Nocturnal HRV | `sleep/CurrentNightHrvResolver`, `BaselineComputer` | RMSSD milliseconds, core IDs; resting fallback where specified | Mean/core selection with documented fallback | Depends on complete ingestion and source linking | HC-001, DB-002; reference core/missing-HRV fixtures |
| HRV lnμ/σ/Z | `BaselineComputer`, `LoadScoringStrategy.computeHrvZScore/hrvSigma` | Prior valid nights, ln(RMSSD), prior/floor, frozen values | Distinct mean/sigma windows and shrinkage | Log/prior model retained; phase and unbounded fallback are defective/risky | SCORE-002/006; do not replace statistical model |
| RHR/ratio/Z | `SleepPercentileRhrCalculator`, `BaselineZScoreComputer`, `BaselineComputer` | Plausible 30–230 BPM sleep samples, configured percentile, validated nights | Personal nocturnal floor and adaptive reference | Future leakage in backfill; display and adaptive references differ | SCORE-001/006; explicit windows and reference type |
| Nadir/restoration/flags | `SleepNadirAnalyzer`, `RestorationScoreAssembler`, `RecoveryFlagEvaluator` | Core nadir time, HRV/RHR deviations, phase, previous night | Core recovery and travel suppression | Supplemental duration/frozen history can alter core results | SCORE-002/004; preserve thresholds and coefficients |
| Sleep score | `ComputeSleepMetricsUseCase`, `strategies/SleepScoringStrategy` | Four components, profile/regularity | Weighted components and documented modifiers | Composition retained; stale/calibration/input scope defects propagate | SCORE-002/003/004; versioned repair |
| Readiness | `LoadScoringStrategy.computeReadinessScore`, `AssembleDailySummaryUseCase` | Restoration/current and prior context, load, illness flags | Existing weighting/caps, heuristic semantics | Old values can survive deletion; flags/phase disagree | SCORE-002/003; no coefficient changes |
| Workout TRIMP | `ComputeWorkoutTrimpUseCase`, `RasCalculator.calculateDailyTrimp` | Duration minutes, HR average/reserve, gender/model/parameters | Banister default; adapted Cheng/iTRIMP alternatives | Formula families are deliberate; display/daily inputs differ | SCORE-005; selected-model reference parity, no legacy-model substitution |
| Zones/intensity/TRIMP per minute | `MODEL/domain/heartrate/ZoneThresholds`; `WorkoutLoadClassifier`, `ComputeWorkoutLoadMetricsUseCase` | HR timestamps, thresholds, duration, canonical TRIMP | Existing zone-zero/gap/classification rules | Ingestion stores zone/legacy metrics separately from selected-model metrics | HC-006, SCORE-005; name both quantities; boundary/gap/zero-duration fixtures |
| Everyday HR load/coverage | `EverydayHeartRateLoadCalculator`; `ScoringDayDataLoader.loadMergedMinuteBuckets` | SQL minute means, exclusions, workout load, coverage | Exclude sleeping/workout intervals and zone-zero contribution under existing policy | Efficient SQL bucket path exists; stale/overlapping tiers undermine it | DB-002, CACHE-002; retain exclusions/coverage thresholds |
| ATL/CTL/strain/load | `RasScoringStrategy`, `BuildLoadSeriesUseCase`, `ReadinessSummaryCoordinator` | Date-indexed TRIMP with zero days | Seven-/42-day time scales and load-change score | EMA uses α=2/(window+1), seeded/sparse variants and up to 84 days' input; guidance shorthand “average” is imprecise | DOC-001, SCORE-005, CACHE-002; preserve EMA/sparse policy; align display history |
| RAS daily/weekly | `RasCalculator.calculateDailyRas`, `DB/.../RasTotalsComputer` | Model TRIMP, frozen scaling factor, prior six dates | Daily capped conversion and rolling total | No independent arithmetic defect found; repeated previous-day queries | PERF-002; preserve cap and summation/rounding order |
| Residual fatigue | `ComputeResidualFatigueUseCase`, `DB/.../ResidualFatigueComputer` | Canonical workout impulses/end times, gain/half-life | Exact retained-history exponential decay; current live vs end-of-day snapshot | End-of-day cursor exists; morning fallback repeatedly scans; finite invalidation inadequate | CACHE-002, PERF-002, SCORE-005; separate morning/end cursors |
| Training Readiness | `ComputeTrainingReadinessUseCase` | Recovery and residual-fatigue projection | Existing projection/clamping | No separate formula defect found; canonical load/calibration defects propagate | SCORE-002/005, CACHE-002 |
| VO2/fitness | `cardio/{UthVo2MaxCalculator,MaterkoAdaptedVo2MaxCalculator,Vo2MaxSourceResolver,CooperNormsClassifier}` | Wearable ml/kg/min or HRmax/RHR/HRV estimate | Wearable/estimate policy, calibrated/clamped or withheld states | Distinct calibration booleans intentional; upper day boundary and lifecycle incomplete | HC-004, SCORE-002/007; retain calculator policies |
| TSB | `cardio/TrainingStressBalanceCalculator` | CTL minus ATL | Existing descriptive bands | No independent subtraction/unit defect found | SCORE-005, CACHE-002; inherit repaired canonical load |
| Workout recommendation/examples | `DB/.../recommendation/`; `SCORE/domain/recommendation/` | Wake-anchored recovery, fatigue, 30-day example selection | Local Rest/Easy/Push-harder context, not a training prescription | Phase, stale absence, example TRIMP and repeated fatigue inputs propagate | SCORE-002/003/005, CACHE-002, PERF-002 |
| Insights/AI prompt export | `InsightEngine`, `InsightDeriver`, `GetDailyPromptDataUseCase` | Room summaries, baselines and preference snapshot | Baseline-driven, non-diagnostic local context | No external AI API; stale inputs/confidence propagate | SCORE-002/003, CACHE-002; validate affected insight/prompt only |
| Steps | `StepRecordReader`, `StepCountFetcher`, `StepsMapper` | Counts and interval milliseconds | Provider aggregate for all origins; raw selected device | Denied→zero; selected-device whole interval→start date | HC-003, SCORE-007; typed availability and explicit day attribution |
| Distance/speed/pace/elevation | `IntervalTotalsReader`, `SessionTotalsResolver`, `MODEL/domain/util/RouteDistanceCalculator`, workout presentation | Meters, elapsed time, coordinates/altitude | Prefer provider totals; haversine and ascent-threshold fallback | No independent conversion defect found; own-change tracking/route preservation incomplete | HC-004/006; retain meters/km/h conversion and documented fallback |
| Weight/BMI/body fat/BP | `BodyMetricsDataLoader`, `domain/calculation/HealthMetricsCalculator` and model classifiers | kg, height, %, mmHg; latest prior values | Local descriptive measurements/classification | Timestamp moves/latest-value carry-forward affect future days | HC-001, CACHE-002; maintain units/finite validation |
| Overnight SpO2/body temperature | `ScoringDayDataLoader`, body/temperature loaders | Percent, °C in sleep window and temperature history | Display/context with existing baseline; not new readiness terms | Source absence/synthetic interval scope requires characterization | HC-003, SCORE-004; do not insert into readiness formula |
| Calories/energy, exercise route calories | No canonical ingestion/calculation pipeline identified | None | Not currently supported here | Not a missing-remediation feature | No proposed implementation |

Scientific interpretation: the checked [Buchheit HR monitoring paper](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full) supports contextual interpretation of HR/HRV rather than a universal recovery prescription. The [Impellizzeri ACWR paper](https://pubmed.ncbi.nlm.nih.gov/32502973/) supports the repository's caution about causal injury predictions. These sources do not validate Readylytics' exact weights, thresholds, or adapted TRIMP models. No coefficient replacement is recommended; an independent scientific validation study is outside this remediation scope. Mathematical acceptance is against documented product formulas and independent reference calculations, not another wearable's scores.

## 6. Health Connect Ingestion Matrix

All rows use read-only Health Connect access. “Delta” means Changes processing when the type has a registered token. A complete authorized window may reconcile absence; denied/partial reads must not. Daily refresh still reads only its current-day/overnight overlap plus bounded delta processing, with older dirty dates handled durably.

| Source type | Read/paging strategy | Key and persistence target | Current update/deletion behavior | Recompute trigger / performance risk / findings |
|---|---|---|---|---|
| `HeartRateRecord` (many samples per record) | Stream 1,000-parent default pages; page flatten/sort; 500-sample writes | HC parent→`health_source_records`; `(sourceRef,timestampMs)`→`heart_rate_records`; later buckets | Bulk values/removed samples stale; delta deletes/reinserts; resumed/warm deletions incomplete | Daily/window or delta affected dates; whole-chunk ID set and per-parent transactions. HC-001/002, DB-001/002, CACHE-001, PERF-001 |
| `HeartRateVariabilityRmssdRecord` | Paged RMSSD; 500-row writes | Parent FK/time→`hrv_records` | Bulk numeric updates stale; delta replace; resume skips reconciliation | Sleep/HRV baseline suffix; same IDs/source lookup risks. HC-001/002, CACHE-001 |
| `SleepSessionRecord` + stages | All pages accumulated for chunk | HC ID→`sleep_sessions`; child `sleep_stages` replaced | Bulk upsert/stage replacement; delta parent replacement; full ID absence reconcile | Overlapping HR/HRV relink, sleep-day and baseline suffix. Large stages/ID bindings unbounded. SCORE-003/004, PERF-001 |
| `ExerciseSessionRecord` | Bulk chunk sessions; details/enrichment reads | HC ID→`workout_records`; selected-model TRIMP recomputed | Full parent absence reconcile; delta delete/reinsert loses unreadable enrichment | Session relink, workout metrics, load/fatigue/examples. HC-006, SCORE-005 |
| Exercise routes | Embedded/requested through exercise route API and consent | `(workout,point)`→`workout_route_points` | Bulk preserves unreadable prior routes; delta cascade defeats preservation | Workout detail/route fallback metrics; potentially large route lists and per-session IPC. HC-006, PERF-001 |
| `DistanceRecord` | Paged interval records accumulated for exercise range | Origin-aware resolved total on workout; no independent raw table | No own delta/delete identity; null result preserves prior total | Refresh only through exercise read; HC-004/006; need overlap invalidation |
| `ElevationGainedRecord` | Same interval enrichment strategy | Meters on workout | Same lifecycle gap | Same as distance; HC-004 |
| `StepsRecord` | Raw pages accumulated plus aggregate/day-group calls; selected-device raw mapping | Stable HC ID→`step_records`; totals→summary | Raw ID changes/delete; all-origin denied read becomes zero; selected-device empty days omitted | Step summary/insights; raw lists and cross-midnight attribution. HC-003, SCORE-007, PERF-001 |
| `WeightRecord` | Optional bulk pages | HC ID+timestamp→`weight_records` | Delta family replace; full moved timestamp can retain old row | Latest-value carry-forward, BMI/insights. HC-001/003, CACHE-002 |
| `BodyFatRecord` | Optional bulk pages | HC ID+timestamp→`body_fat_records` | Same | Latest value/insights. HC-001/003, CACHE-002 |
| `BloodPressureRecord` | Optional bulk pages | HC ID+timestamp→`blood_pressure_records` | Same | Latest mmHg/history/insights. HC-001/003, CACHE-002 |
| `OxygenSaturationRecord` | Optional bulk pages | HC ID+timestamp→`oxygen_saturation_records` | Same; authorized sleep-window averages | Sleep-day context/insights. HC-001/003, SCORE-004 |
| `BodyTemperatureRecord` | Optional bulk pages | HC ID+timestamp→`body_temperature_records` | Same; not `SkinTemperatureRecord` | Sleep temperature and baseline/insights. HC-001/003, CACHE-002 |
| `Vo2MaxRecord` | Optional bulk read | Stable ID→`vo2_max_records` | Upsert only; missing token/deletion dispatch | Fitness summaries, up to wearable lookback; HC-004, SCORE-007 |
| `RestingHeartRateRecord` | Not imported | No direct record table | RHR derived from sleep HR instead | Clarify privacy copy; do not add permission merely because RHR is displayed. DOC-001 |
| Calories/energy, skin temperature | No supported import path identified | None | Not applicable | No speculative permissions or new features |

Timestamp precision is currently milliseconds. Do not claim nanosecond-perfect replay or silently alter stable keys; characterize same-parent samples collapsing to the same millisecond if the provider can supply them. Sleep scoring uses stored scoring zone and preserved session offsets; complete resync relinking remains full-range and must retain sleep precedence and stable tie-breaking. Multiple HC source IDs represent distinct records even when physiologically duplicate; ordinary 1.1.0 raw reads do not resolve the product's source-priority decision (OD-4).

## 7. Large-Dataset Analysis

Required design scenario: **more than 1,000,000 Health Connect HR parent records in a 30-day window**, separately from one million flattened samples. Include dense multi-sample parent records, multiple origins/devices, multi-year and ten-year resync windows, unlimited local retention, repeated unchanged imports, and historical partial corrections. “Unlimited” currently disables local deletion; the resync horizon remains 3,650 days. Test local data beyond that horizon without assuming it may be deleted.

Let R be parent records in a chunk, N flattened samples, P samples in a fetched page, S sessions, B persisted buckets, D recomputed days, W historical workouts, and A the maximum simultaneously overlapping sessions. Complexity below describes inspected loops, not measured wall time.

| Hot path | Current time / memory behavior | Actual bottleneck | Target |
|---|---|---|---|
| HC paging/transform | O(N + ΣP log P + repeated S log S); O(R+P+S) live data including seen IDs | Parent page can contain large nested payload; copies and retained IDs | O(Pbuffer+S) application buffers, seen IDs in indexed staging; tune explicit page size; no unsupported promise to bound SDK payload of one record |
| Source lookup + sample upsert | O(R) source transactions plus indexed O(N log N) writes | Per-parent transaction overhead, repeated lookup, no-op observation churn | Batched lookup/insert/sample writes; O(ceil(R/batch)+ceil(N/batch)) transactions, stable keys and changed-row gating |
| Deletion reconciliation | Whole-chunk materialization; source-range scan potentially O(total sources) per chunk | Bad metadata, missing index, large binding lists, individual deletes | Indexed staged anti-join and keyset deletion in bounded commits; retain journal and completeness evidence |
| Session link sweep | Sort S; approximately O(N×A+S log S), plus keyset SQL | `active.removeAll` scans active overlaps; 5,000-row pages already bound sample memory | Preserve sweep; measure adversarial overlap, do not call it strictly O(N) for arbitrary overlapping sessions; optimize only if A matters |
| Workout metrics reconcile | Batches of 20; repeated filtering of batch HR, then metrics sorts | Long workouts can still produce large batch sample lists | Keyed/ordered samples for needed workouts, bounded session batches; no change in gap/zone semantics |
| Hot→warm rollup | Day materialization/grouping O(Nday); per-minute sorts; writer held through computation | Arbitrarily dense day; partial-minute replacement defect | Bounded row/group streaming, complete-minute atomic publish; exact bounded BPM histogram is viable for plausible integer 30–230 BPM |
| Baseline history | Repeated O(Nwindow) projection and sort per target; warm N reconstructed from B; per-session warm queries | Repeated night inputs despite cached sessions | Compute each required night's small sufficient statistics once per generation/window; batch queries; no raw expansion for mean |
| Morning fatigue during rebuild | Repeated workout-prefix queries/sums, potentially O(D×W) | Separate morning calculation bypasses end-of-day context | Ascending independent morning cursor plus end-of-day cursor; O(W+D) recurrence after ordered fetch, bounded seed streaming |
| Backup | Mostly keyset-paged but O(R) source list; no snapshot | Metadata heap, snapshot consistency, WAL pinning | Source keyset pages, coherent snapshot, measured WAL/storage budget |
| Chart timeline | O(Nwindow) entity/domain lists; warm reconstructed to original count; sorted merge on invalidation | Display resolution unrelated to pixel count; `distinctUntilChanged` occurs after reads/maps | Query bounded display resolution, retain explicit approximation metadata; score queries remain separate |

The existing 500-row persistence, 5,000-row reconcile, 20-workout grouping and 30-day adaptive scan are starting points, not targets proven optimal. Apply caps by rows/samples and transaction duration, not only calendar days. A one-day adaptive floor still includes overnight overlap and can remain too dense; resumable sub-day/record processing should progress without repeatedly exhausting the same window budget.

Success means bounded application working sets across growing histories, no OOM/ANR, cancellation at page/batch boundaries, durable progress across process death, identical reference scores within the same tier/configuration, and indexed targeted work. Record device-specific throughput, peak heap/PSS, allocation rate, writer duration, WAL bytes and battery/job duration before selecting numeric budgets. No invented milliseconds-per-record or universal memory ceiling is part of this plan.

## 8. Target Architecture

Keep existing modules. `core:model` owns small app-independent records and operation contracts; `core:healthconnect` owns SDK access/conversion and read preparation; `core:database` owns transactional replacement, provenance/tier coverage, dirty work and queries; `core:scoring` owns mathematical functions and input policy; app owns WorkManager/platform lifecycle and Hilt composition; features own presentation StateFlow. No additional Gradle module is required.

Introduce only boundaries with demonstrated ownership problems: (1) typed read completeness, (2) a shared mutation/maintenance coordinator, (3) a durable mutation/dirty-work store, and (4) explicit canonical scoring inputs/results. Illustrative contracts, with exact names finalized in their package:

```kotlin
// Read outcomes never grant deletion authority by returning an empty list after a failure.
sealed interface ReadOutcome<out T> {
    data class Available<T>(val data: T) : ReadOutcome<T>
    data object Denied : ReadOutcome<Nothing>
    data object Unsupported : ReadOutcome<Nothing>
}
// Conceptual transaction: replaceSource(preparedPayload) + appendDirty(oldExtent, newExtent).
// Conceptual job identity: runId + immutable range/zone + source/config/algorithm revision.
// Conceptual publish: commit computed day and acknowledge its covered dirty revision together.
```

Transient failures propagate as typed errors/exceptions and cancellation remains cancellation. Permission outcomes attach to the actual read, not a later recheck. SDK read/retry/enrichment happens outside Room transactions. The database owns bounded commits and generation verification; full replacement of a large source may use staging followed by publication. Raw and warm data for an interval have one authoritative coverage identity.

DI scopes: one application-scoped mutation coordinator, HC provider/client boundary, database, stores and scheduler. Run-scoped immutable preferences/zone/instant/configuration and mutable walk-forward cursors must not become singleton state. Reuse existing `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`, `@ApplicationScope` and `Clock` bindings. Hilt-assisted workers remain the lifecycle boundary. Private stateless assemblers can remain privately constructed.

Invalidation starts with old/new source extents mapped into scoring days and overlapping sleep/workout sessions. A sleep day may precede the source's nominal date under the existing supplemental policy; carry the resolved dependent day rather than guessing only timestamp dates. Direct outputs, baseline-derived workout inputs, rolling load, latest-value vitals, prior-day flags and morning examples contribute to the closure. For now, stateful changes repair the retained suffix. A day publishes complete computed fields or a preserved unavailable prior generation; it never stamps mixed old/new fields as current. Future widgets must consume this committed projection, but no widget work is added here.

Compose continues collecting lifecycle-aware StateFlow. Expose domain availability/generation and bounded chart series; keep ephemeral controls local. Do not replace existing M3 components, shapes, colors or navigation for aesthetic reasons. Any new state label belongs in `app/src/main/res/values/strings.xml`.

## 9. Phased Implementation Roadmap

The early phase deliberately introduces only the persistence/coordination seams required for safe correctness fixes; broader ownership cleanup remains Phase 3. Every implementation package updates the applicable documentation in the same change. Schema migrations are additive from v19 and serialized as S1–S3 in section 12; choose actual version numbers against HEAD when implementation begins.

### Phase 0 — Baseline and Safety Rails

Objective/findings: capture the identified failures and present pipeline cost (HC-001/002/005, DB-001/002, CACHE-001/002, SCORE-001–007, PERF-001–003, SEC-002–004). Steps: run existing targeted checks; add failing behavioral fixtures for each correctness repair as its first commit step; extend current million-row fixtures to parent-record distributions and obtain reference snapshots. Files: existing tests under matching core/app packages; `database-benchmark/.../{DatabaseBenchmarkFixture,ScoringWalkForwardBenchmark}.kt`, `benchmark/BASELINE.md`. Prerequisites: isolated test databases and non-production app variant. Schema/API: none. Migration/rollback: fixtures and measurements only, reversible independently. Risk: mistaking an old golden for intended behavior; mark known failures and compare to explicit invariants. Validation/completion: reproducible failure cases and measured baseline with device/version/dataset metadata; no general coverage target. Work package WP-01.

### Phase 1 — Correctness and Data Integrity

Objective/findings: HC-001–006, DB-001/002, CACHE-001, SCORE-001–007, ARCH-001's safety seam, SEC-001–005. Ordered steps: safe diagnostics/backup guards; atomic mutation+dirty journal and maintenance seam; authoritative source replacement and complete resume reconciliation; permission/type lifecycle; frozen-run identity; scoring assembly/calibration/window repairs; tier-correctness repair and recoverable backup operations. Files: ingestion stores/DAOs, sync orchestrators, baseline/sleep/workout assemblers, workers, backup services and schemas. Prerequisites: corresponding WP-01 fixtures; OD-1/2/3/4/6 only gate their semantic branches. Schema/API: S1 journal/provenance, S2 tier coverage, typed read/result APIs. Migration: additive metadata and legacy quality state, versioned score repair, never blanket-clear valid raw data. Rollback: forward fix or disable new publication while retaining last good generation; no destructive DB downgrade. Risks: score changes, old unavailable provider history, lock ordering, partial SAF publication. Validation/completion: each confirmed defect's acceptance criteria pass; interrupted operations converge and data remains recoverable. WP-02–17 below.

### Phase 2 — Health Connect and Database Scalability

Objective/findings: PERF-001/003, HC-002/005/006, DB-001/002. Steps: persist scan staging and remove heap ID sets; bulk source resolution; bounded payload transform/write/delete; streaming complete-minute rollup and source backup paging; explain-plan-driven indexes; bounded worker continuation and a single retry budget. Files: `HealthIngestionCoordinator`, readers/mappers, source/HR/HRV DAOs, rollup/backup writer, checkpoints, benchmark module. Prerequisites: Phase-1 mutation and tier contracts. Schema/API: S3 run/type/seen-ID staging and proven indexes, versioned checkpoint format. Migration/backfill: staging starts empty; no physiological-data rewrite for indexes. Rollback: retain durable scan/job state and replay incomplete scans with the previous safe bounded path. Risks: temporary disk/WAL, provider page-token rejection, retry amplification. Validation/completion: million-parent and nested-sample workloads meet section 11 bounds; no remote I/O under a writer transaction. WP-18/19.

### Phase 3 — Architecture and Dependency Injection

Objective/findings: ARCH-001, DI-001, DOC-001. Steps: finish moving mutex/generation ownership to the shared coordinator; standardize provider injection and run time context; remove obsolete provider paths after usage checks; clarify loader/assembler responsibilities while touching affected files. Files: Hilt modules, facade/readers, maintenance workers, restore orchestration, baseline providers. Prerequisites: Phase-1 working safety seam. Schema/API: no additional schema; replace internal constructors/ports incrementally. Migration/rollback: bind old adapters to the new owner first, migrate one caller at a time, then remove duplicate locks; revert adapters without restoring competing ownership. Risks: startup HC availability, DI initialization, deadlocks. Validation/completion: all mutation paths share documented ownership; historical calculations use one time/config snapshot; no new Android dependency in mathematical code. JVM conversion remains deferred under its separate plan. WP-20.

### Phase 4 — Incremental Recalculation and Performance

Objective/findings: CACHE-002, PERF-002, SCORE-005/006, DB-002. Steps: implement conservative retained-suffix invalidation; batch/precompute night statistics; introduce independent morning/end fatigue cursors; cache only with revision/config/tier keys; narrow recomputation using dependency-equivalence fixtures; publish summaries in bounded durable units while controlling observation frequency. Files: `ScoreInvalidation`, workers, scoring history/day loaders, walk-forward contexts, recommendation loaders and relevant DAOs. Prerequisites: corrected scoring and source/tier identities. Schema/API: start with run-scoped caches; a persistent nightly cache requires separate measured justification and migration review. Migration/rollback: regenerate derived state from retained inputs; fall back to corrected reference calculations, not old stale caches. Risks: rounding/order changes and hidden transitive dependencies. Validation/completion: incremental equals full corrected replay, no repeated complete workout-prefix or raw-night scans, cancellation preserves resume progress. WP-21/22.

### Phase 5 — Compose and Long-Term Maintainability

Objective/findings: UI-001, PERF-002/003's chart consumers, DOC-001. Steps: correct backup query identity/state; use bounded-resolution chart data only where measurements show large raw materialization; surface unavailable/approximate state consistently; reconcile documentation registry. Files: `LocalBackupViewModel`, HR repository/timeline DTOs and affected sleep/workout/vital state factories, strings and docs. Prerequisites: stable domain read/result contracts; directory fix can ship independently earlier. Schema/API: no schema; additive chart-resolution/availability fields if needed. Migration/rollback: no preference migration, preserve existing lifecycle/M3 patterns; revert a chart optimization without affecting score queries. Risks: accidental loss of timeline spikes or accessibility semantics. Validation/completion: correct directory actions and affected chart performance/accessibility checks; all documentation and final repository gates pass. WP-23/24.

## 10. Ordered Work Packages

Validation shorthand: **U-S** = `./gradlew :core:scoring:testDebugUnitTest`; **U-H** = `./gradlew :core:healthconnect:testDebugUnitTest`; **U-D** = `./gradlew :core:database:testDebugUnitTest`; **U-A** = `./gradlew :app:testDebugUnitTest`; **U-M** = `./gradlew :core:model:testDebugUnitTest`; **U-SET** = `./gradlew :feature:settings:testDebugUnitTest`. Use `--tests` to select the named behavior fixture during development, then run the affected module command. **DB-device** = `./gradlew :database-benchmark:connectedBenchmarkAndroidTest` with a dedicated device and isolated fixture; **UI-device** = `./gradlew :benchmark:connectedBenchmarkAndroidTest`. Confirm variant task names with `./gradlew :database-benchmark:tasks --all` if they change; do not install/uninstall the production package to make a benchmark run.

Each package is a coherent review unit. Checkbox sequence means implementation order within that package, not permission to implement during this planning task. New test names below describe required additions in mirrored packages; existing named source files are exact references from section 2.

### WP-01 — Characterize correctness and current pipeline cost

- [ ] Purpose/IDs: minimal safety rails for all confirmed High findings and PERF-001–003. Extend existing sync/scoring/backup fixtures with record correction, interrupted deletion, future-night, frozen-phase, partial-minute and malformed-archive cases; keep intended reference values separate from known-bug snapshots. Extend `DatabaseBenchmarkFixture` and `ScoringWalkForwardBenchmark` to current schema/parent records and actual scoring. Record source/config/tier checksums and query/transaction counts without health-value logging. Dependencies: none. Acceptance: every High correctness repair has a reproducible fixture; baseline numbers state environment and limitations. Validation: U-S/U-H/U-D/U-A as relevant, DB-device for measured baseline; no broad coverage work.

### WP-02 — Make release diagnostics safe at every sink

- [ ] Purpose/IDs: SEC-001, DOC-001. Change `SecureFileLogSink`, `CrashReportFormatter`, application logger wiring and report exports: define approved fields/reason codes, sanitize before dispatch, cover nested exceptions, retire old unsafe cache slots, update privacy/About strings. Dependencies: none. Acceptance: the SEC-001 synthetic payload corpus is absent from all release destinations while frames remain useful. Validation: targeted logger/crash formatter tests (U-M/U-A), release variant logcat inspection; no schema.

### WP-03 — Guard restore inventory and replacement semantics

- [ ] Purpose/IDs: SEC-004. Modify `LocalRestoreManager`, `RestoreBatchLoader`, `RestoreVitalsLoader` and schema-aware backup manifest helpers: inventory required tables by supported backup version, validate counts/FKs, centrally clear replacement tables inside rollback-capable application, reject incomplete required payloads. Dependencies: WP-01. Acceptance: malformed/missing-required archives leave original DB intact; optional legacy omissions yield explicit empty tables. Validation: targeted restore tests U-A plus Room transaction/FK fixture; no production schema change. Full workflow concurrency follows WP-04/16.

### WP-04 — Add durable dirty work and a shared maintenance seam

- [ ] Purpose/IDs: CACHE-001, ARCH-001. Add `MODEL/domain/sync/HealthMutationCoordinator.kt` contract, `DB/data/local/RoomDirtyRangeStore.kt`, and `SCHEMA/data/local/{entity/DirtyRangeEntity,dao/DirtyRangeDao}.kt`; wire singleton ownership in app DI. Add S1 migration in `DB/data/local/migration/`, export schema, record dirty extents in the same transaction as source mutations; startup/scheduler drains pending work. Define lock order and maintenance recovery before migrating callers. Dependencies: WP-01. Acceptance: committed deletion remains dirty after kill and after replay finds no source; completed revision acknowledgment is atomic with summary publication. Validation: U-H/U-D/U-M, migrated Room interruption fixture. Rollback: keep journal readable and stop new publication, never drop pending rows.

### WP-05 — Repair source metadata and stable-ID payload replacement

- [ ] Purpose/IDs: HC-001, DB-001. Extend source metadata within S1 (or the next additive migration if S1 shipped), update DTO/mappers and `RoomHealthIngestionStore`, HR/HRV/vital DAOs. Carry opaque ID/bounds, batch-backfill raw child extents, update values and reconcile removed/moved samples atomically, append old/new dirty ranges. First small commit may fix numeric UPSERT columns; completion requires full payload parity. Dependencies: WP-04. Acceptance: full/delta/clean imports converge on numeric edits, empty sample arrays and timestamp moves; source FK remains stable; unknown legacy metadata is non-destructive. Validation: U-H/U-D, indexed Room reference-replacement fixtures.

### WP-06 — Make resume reconciliation complete before promoting tokens

- [ ] Purpose/IDs: HC-002, CACHE-001. Change coordinator/resync/checkpoint handling to restart incomplete type scans as the initial safe implementation; preserve complete-source replacement and journaled dates. Record explicit scan-complete state; refuse phase/token advancement when coverage is partial. Dependencies: WP-05. Acceptance: pre-baseline deletion converges after HR/HRV page interruption; rejection of provider page token safely restarts that type. Validation: U-H with fault injection before/after every page checkpoint. Durable seen-ID optimization follows WP-18.

### WP-07 — Implement permission-aware read/token lifecycle

- [ ] Purpose/IDs: HC-003. Introduce read outcomes in `MODEL/domain/repository/HealthConnectRepository.kt`; adapt repository/readers, `HealthChangeSynchronizerImpl`, `HealthChangeTokenStoreImpl`, `StepCountFetcher` and permissions UI strings. Check 1.1.0 feature availability, suspend denied token types and preserve unavailable totals; make authorized empty results explicit in both source modes. Dependencies: WP-04; coordinate signatures with WP-05/06. Acceptance: repeated revoke/regrant produces no worker loop, no false zero/prune, and correct recovery UI. Validation: U-H/U-M, fake-provider TOCTOU fixture and background/history feature matrix on device.

### WP-08 — Complete supported-type correction and enrichment updates

- [ ] Purpose/IDs: HC-004. Add VO2 registry/token/delete/dirty handling across `HealthDataType`, coordinator/change dispatcher, ingestion store, `Vo2MaxRecordDao`, source selection and strings. Implement distance/elevation dirty-range tracking and affected-workout refresh under OD-4's selected policy. Dependencies: WP-05/07. Acceptance: historical VO2 deletion disappears after full/delta sync; interval-only edits change overlapping workout totals. Validation: U-H/U-D/U-M and independent interval-change fixtures. Migration: preserve old type names; add nullable interval identity metadata only if needed for deletion provenance, with indexed bounds and backup support.

### WP-09 — Prepare exercise enrichment before transactional upsert

- [ ] Purpose/IDs: HC-006. Split `HealthChangeSynchronizerImpl` into remote preparation and local application functions; update existing workout rows in place, reconcile route children from typed outcomes, use the same store for bulk and delta. Dependencies: WP-05/07. Acceptance: unavailable route retains previously imported points; confirmed delete removes them; no provider call under transaction. Validation: U-H/U-D with transaction-active assertion around fake HC and route-state fixtures. Schema: none beyond shared metadata.

### WP-10 — Persist immutable historical job identity

- [ ] Purpose/IDs: HC-005, DI-001. Version `ResyncCheckpoint`/store, `FullHistoricalResyncUseCase`, `ResyncRangeUseCase` and worker inputs with run/range/zone/config identity and baseline tokens. Continue fixed run across midnight; changed config restarts only required downstream phases; replace ambient clock reads in run construction. Dependencies: WP-04/06/07. Acceptance: phase-by-phase midnight/DST/settings retry matches uninterrupted run; recompute-only never commits HC tokens or sync timestamps. Validation: U-H and worker U-A fixtures; provider token rejection device check. Migration: discard/replay legacy incomplete checkpoints without deleting data.

### WP-11 — Correct historical baseline membership and fallback boundaries

- [ ] Purpose/IDs: SCORE-001/006. Fix both RHR backfill selectors in `BaselineComputer`, align requested-date fallback bounds in reachable providers, document distinct RHR references, remove unused fallback providers only after consumer scan. Dependencies: WP-01; OD-5 gates any displayed-ratio semantic change. Acceptance: batch extent/future nights cannot affect D; current-night RHR inclusion matches the existing live contract. Validation: U-S/U-D plus independent per-day baseline reference; fixed-zone DST case. Schedule versioned repair through WP-04 rather than thawing snapshots during every startup.

### WP-12 — Unify calibration eligibility, phase and frozen metadata

- [ ] Purpose/IDs: SCORE-002. Update `ComputeSleepMetricsUseCase`, `CalibrationGate`, `PhaseCalculator`, `ComputeHistoricalBaselinesUseCase`, `SleepBaselineMetrics` and `AssembleDailySummaryUseCase`; distinguish statistical-window counts from maturity, reuse snapshot phase/count and set booleans explicitly. Dependencies: WP-11, OD-2. Acceptance: <7 calibration, documented mature boundary, sparse histories and repeat frozen invocation agree across summary/diagnostic/flags; backfill does not prematurely bypass eligibility. Validation: U-S/U-D reference fixtures for 6/7,29/30,59/60. Migration: repair metadata and affected outputs once; unknown legacy observations remain explicit.

### WP-13 — Publish absent/unavailable/complete daily outputs consistently

- [ ] Purpose/IDs: SCORE-003, CACHE-001. Change `BaseSummaryAssembler`, `ReadinessSummaryCoordinator`, `FinalSummaryAssembler`, `ScoringRepositoryImpl.applyRecommendation` and day persistence to return explicit assembly status. Clear derived sleep/recommendation fields on authoritative absence; on failure retain the prior complete generation and pending dirty work. Dependencies: WP-04/07/12. Acceptance: sleep deletion clears all dependents, while compute failure cannot publish mixed old/new readiness. Validation: U-D/U-S with deletion and failure at each assembler boundary; no raw schema changes.

### WP-14 — Separate whole-day sleep from core recovery inputs

- [ ] Purpose/IDs: SCORE-004. Add an explicit core recovery input alongside sleep-day totals; update `ReadinessSummaryCoordinator`, `SleepModifierResolver`, `SleepNadirAnalyzer` and stage repository query for all canonical core IDs. Carry previous-core offset evidence through frozen replay. Dependencies: WP-12/13. Acceptance: nap-only change leaves core nadir/restoration unchanged; equivalent merged sessions include all fragmentation; frozen travel night is stable. Validation: U-S/U-D biphasic, overlap, stage-less and offset fixtures. Schema: none; versioned derived repair and synchronized methodology copy.

### WP-15 — Canonicalize workout scoring and point-in-time boundaries

- [ ] Purpose/IDs: SCORE-005/007. Thread frozen config/hrMax and canonical model result through `ComputeDailyTrimpUseCase`, `ComputeWorkoutLoadMetricsUseCase`, `GetWorkoutDisplayMetricsUseCase`, day loaders, fatigue/examples. Replace VO2 inclusive upper bound in DAO/prefetch, define stable tie ordering, retain step interval end and implement OD-6. Dependencies: WP-05/08/11/12; OD-3 for missing HR. Acceptance: selected-model TRIMP agrees across surfaces and persisted contributions; next-midnight VO2 excluded; selected-device empty days clear and step totals conserve chosen attribution. Validation: U-S/U-D/U-H with all models, zero duration, absent/warm HR, midnight/DST. Schema: no new columns if canonical `modelTrimp` metadata is sufficient; otherwise add nullable revision/quality fields with lazy backfill and backup compatibility.

### WP-16 — Serialize restore and make backup rotation/snapshot recoverable

- [ ] Purpose/IDs: ARCH-001, SEC-002/003/004. Move password rotation from `LocalBackupViewModel` to service ownership, journal publication/credential phases, stage/verify archives, and coordinate restore DB/preferences/cache/scheduler transitions. Make `BackupStreamWriter` export a coherent data generation and explicit preference snapshot; keep compression/publication outside a writer transaction. Dependencies: WP-03/04/05; OD-1 only for tier metadata serialization. Acceptance: every failure/kill boundary leaves originals recoverable; concurrent mutations produce a restorable consistent archive; restored data never gets scores using pre-restore settings. Validation: U-A/U-SET plus file/SAF and concurrent Room fixtures, DB-device snapshot/WAL measurement. Migration: version operation journals/optional archive manifest fields; preserve old ZIP readers.

### WP-17 — Establish authoritative minute coverage and legacy warm handling

- [ ] Purpose/IDs: DB-002, CACHE-001, SCORE-005. Implement complete-minute cutoff first; add S2 coverage/source-contribution metadata per OD-1, migrate legacy buckets as unknown provenance, stage and publish refreshed intervals atomically, update raw/warm reads and relinking to select one generation. Dependencies: WP-04/05/09/10; OD-1. Acceptance: partial-minute repeated rollup, historical full reimport and rolled-source delete preserve correct single coverage and invalidation; legacy data is retained when HC is unavailable. Validation: U-D, Room migration/interrupt fixtures, within-tier score parity. Rollback: retain old buckets/read compatibility until new coverage verified; never infer lost raw samples from a sketch.

### WP-18 — Bound scan identity, payload and deletion processing

- [ ] Purpose/IDs: PERF-001, HC-002/005/006, DB-001. Add S3 indexed seen-ID staging and anti-join deletion; batch source lookups/inserts with sample writes; avoid unused ID sets; cap transformation buffers and SQL bindings; unify bounded read retry budget, keeping Changes/page/window retry ownership explicit. Preserve full-range session sweep and its stable ties. Dependencies: WP-05–10/17. Acceptance: million-parent live heap plateaus by buffer settings; no per-parent transaction, unsafe binding list, or remote call inside SQL transaction; suspended jobs resume. Validation: U-H/U-D and DB-device current-schema ingest/replay benchmark, provider quota/cancellation fixture. Rollback: WP-06 complete-chunk replay remains safe fallback.

### WP-19 — Stream rollup and source metadata export

- [ ] Purpose/IDs: PERF-003, SEC-003/005. Replace day-wide rollup materialization with ordered complete-bucket paging/histograms and generation-checked publication; page source export by `id`; clean only metadata unreferenced by raw/warm/pending work. Move pruning after verified archive creation and stream encrypted output or clean dedicated staging at startup. Dependencies: WP-16/17/18. Acceptance: dense single-day rollup/million-source backup have bounded heap; kill leaves no orphan plaintext on restart and never removes the last good archive. Validation: U-D/U-A, DB-device memory/WAL, file/SAF disk-full fixtures. Migration: source GC considers all new FKs/journals before deleting.

### WP-20 — Finish provider/time/maintenance ownership migration

- [ ] Purpose/IDs: ARCH-001, DI-001, DOC-001. Bind all readers to one provider seam, remove `clientOverride`, pass run instant/zone/retention into fatigue and workers, migrate remaining cleanup/rollup/startup participants to the shared owner and remove duplicate acquisition paths. Extract responsibilities only in touched oversized files, retaining private stateless helpers. Dependencies: WP-04/10/16/17. Acceptance: fixed-run replay has no ambient time reads; one lock order and provider lifecycle; unavailable HC startup remains supported. Validation: U-H/U-D/U-A, architecture import checks and Hilt assembleDebug. No module conversion or additional schema.

### WP-21 — Make invalidation a durable dependency closure

- [ ] Purpose/IDs: CACHE-002, SCORE-005/006/007. Extend `ScoreInvalidation` with reason/metric rules; track every deleted type, actual scoring-zone extents and source old/new span; initially repair retained suffix for baseline/workout/fatigue/latest-value changes, merge recommendation fan-out, acknowledge revisions only after complete repair. Dependencies: WP-04/11–15/17/20. Acceptance: incremental equals full corrected replay through today for D→baseline→workout→load propagation and sparse histories. Validation: U-M/U-D/U-H, bounded long-history reference fixture. Migration: existing pending entries upgrade conservatively; no finite fatigue cutoff introduced.

### WP-22 — Eliminate repeated scoring histories with revision-aware run contexts

- [ ] Purpose/IDs: PERF-002, CACHE-002. Batch warm session projections, SQL-weighted averages, per-night statistics and reusable config-keyed windows; add an independent ascending morning fatigue cursor; reuse previous-six-day RAS inputs while preserving chronological visibility. Measure and bound summary transactions, preserving read-your-writes and durable acknowledgment. Dependencies: WP-11–15/17/21. Acceptance: per-night samples/workout prefixes processed once per appropriate run window; optimized/full references agree and canceled publication rolls back only its bounded unit. Validation: U-S/U-D, DB-device full rebuild versus unchanged/sparse correction benchmarks. Migration: run-scoped caches only by default; persistent cache requires a separate versioned schema/rollback proposal backed by measurements.

### WP-23 — Fix backup listing identity and measured chart materialization

- [ ] Purpose/IDs: UI-001, PERF-002/003. Key `LocalBackupViewModel` listing by directory+refresh, cancel stale requests and bind actions to returned identity; expose service rotation state. In `HeartRateRepositoryImpl` and only affected chart loaders, provide display-resolution aggregates rather than reconstructing original sample count, preserving spikes/quality labels appropriate to the chart. Dependencies: directory fix none; chart portion WP-17/22 and benchmark evidence. Acceptance: A→B race cannot expose stale actions; historical chart remains usable under concurrent ingestion; no calculation uses display downsampling. Validation: U-SET/U-D, affected Compose state tests and UI-device frame/memory comparison; accessibility labels/loading/error semantics preserved.

### WP-24 — Reconcile documentation and run final release gates

- [ ] Purpose/IDs: DOC-001 and all implemented findings. Audit updated `DATA_FLOW.md`, About/site/tooltips/onboarding, privacy/backup docs and new migration/run contracts; fix residual registry/version contradictions, rerun documentation drift/presence tests, record measured outcomes and unresolved rollout limitations. Dependencies: all enabled work packages. Acceptance: every implemented finding has evidence and matching documentation; deferred semantic decisions are explicitly excluded from release rather than silently guessed. Validation: required repository gates below, benchmark reports, `codegraph index` for new files and `codegraph sync` after structural changes. No implementation commits are created by this planning task.

## 11. Performance Validation Plan

Use existing benchmark modules and an isolated debug-signed benchmark app/database. Existing `DatabaseBenchmarkFixture.HEART_RATE_ROWS = 1_000_000` and v6/v7 ingest/migration benchmarks measure an older storage transition; extend them to current v19 and proposed schema. `ScoringWalkForwardBenchmark` uses sparse sleep HR scaffolding and must not be cited as dense end-to-end proof. `benchmark/BASELINE.md` contains partial physical-device results and unresolved historical startup notes; reproduce only issues relevant to changed ownership/UI, rather than treating the old notes as current defects.

| Measurement | Fixture and method | Required result / package |
|---|---|---|
| HC read/transform | Fake 1.1.0 adapter: >1m one-sample parents; >1m nested samples; page boundary extremes; overlapping origins; real provider spot-check | Separate IPC and transform time, bounded buffers, complete ID reconciliation, cancellation/retry progress. WP-18 |
| Room upsert/replay | SQLCipher file DB, current schema, fresh/identical/value-edited/time-moved/deleted records | Report transactions, statements, changed rows, WAL and lock time; identical replay stable and source operations batched. WP-05/18 |
| Query plans | `EXPLAIN QUERY PLAN` for source range/anti-join, sample keyset, warm session/time, latest VO2 and cleanup | Intended index used; no unjustified full source scan per chunk or temp sort on high-volume cursor; compare index storage/write cost before keeping additions. WP-18/19 |
| Rollup | Dense day, multi-device same minute, partial cutoff, legacy sketch, reimport overlap | Bounded row/group memory; complete atomic bucket publication; sample coverage parity and measured approximation error. WP-17/19 |
| Baseline/HR aggregation | Dense 30/56/60-day sleep windows, core splits, hot/warm/mixed, multiple origins | Queries/allocations scale with changed nights, not target-days×raw-window; exact reference percentile/mean for chosen tier. WP-22 |
| Incremental correction | Insert/update/delete on early/middle/recent date; sleep→RHR→workout→load; no-HR vitals history | Final retained suffix equals full corrected rebuild; outside proven dependency closure unchanged; dirty work survives all kill boundaries. WP-21 |
| Historical rebuild | One/three/ten years, plus local rows older than resync cap; dense 30-day bursts and sparse years | Progress across midnight and process restart; no full-history raw materialization; report per-phase CPU/memory/query/WAL; same run snapshot throughout. WP-10/18/22 |
| Fatigue | Many retained workouts, morning and end-of-day evaluation, boundary-straddling workout | Independent cursors match direct sum/reference, including exact same timestamps and chosen floating-point tolerance. WP-22 |
| Backup/restore | Million source rows; concurrent rollup/sync; old/current formats; file/SAF failure and disk exhaustion | Recoverable snapshot, checked inventory/counts/FKs, bounded heap, no last-backup loss or surviving plaintext staging after restart. WP-16/19 |
| Worker/platform | API 26/27 unavailable-HC state; supported APK provider and framework HC; target-37 device; permission/feature combinations; forced stop/cancellation/quota pressure | No ANR or endless same-window retry, coherent progress/notification, no lost dirty work. Distinguish WorkManager stop from app force-stop, which delays work until system/user permits it. WP-07/10/18 |
| Compose affected paths | Historical dense chart, concurrent page commits, rapid backup directory switch | Measure frame time/recomposition/query count and peak heap; no stale actions; preserve focus/content descriptions. WP-23 |

Do not log source values or identifiers for measurements. Record counts, durations, sizes, algorithm/tier/config revision and fixture seed only. Compare medians/tails across repeated runs on the same device/build; record thermal/compilation conditions. Set numeric regression budgets from Phase-0 measurements before merging an optimization. Unit reference comparisons remain zero-Android where possible; Room/WorkManager/provider lifecycle/migration behavior requires targeted instrumented or existing appropriate framework fixtures, not mocked SQL correctness.

## 12. Migration and Compatibility Risks

| Change | Upgrade/backfill/storage/query plan | Compatibility and rollback |
|---|---|---|
| S1: source metadata + dirty journal | Add nullable origin/bounds/revision/completeness to source metadata; add indexed dirty-work table. Backfill actual bounds from raw children in resumable batches; unknown warm-only lineage stays unknown. Storage O(parent records + pending dirty ranges); index actual type/range predicates. | Preserve integer FKs and existing IDs; no destructive fallback migration. New fields need defaults in backup decoders. Older binary may not open the new DB: rollback means compatible forward release or tested backup restore, not automatic downgrade. |
| S2: authoritative tier coverage | Add coverage/generation and, if OD-1 chooses it, per-source contribution table/indexes. Mark legacy buckets explicitly; retain old columns until new readers verify coverage. Storage grows with source×minute contributions; measure worst one-record-per-sample input before approving. | Percentile sketches cannot reconstruct missing lineage or exact samples. Legacy rows remain approximate; only complete authorized refresh replaces coverage. A rejected migration rolls back SQL; an interrupted backfill resumes before enabling new publication. |
| S3: scan staging and indexes | Add `(runId,chunkId,type,sourceId)` seen-ID key and bounded anti-join indexes; stage per complete source. Clear only abandoned/completed generations. Index cost proportional to staged identities; benchmark peak disk/WAL and binding limits. | Bump checkpoint protocol; old scan checkpoints replay safely. Never discard pending invalidations or promote a token while discarding staging. Removing an index later is a forward migration, not a schema-baseline edit. |
| Optional canonical result metadata | If existing modelTrimp/frozen summary fields cannot encode quality/revision, add nullable result revision/quality with lazy chronological backfill. No persistent nightly cache by default. | Legacy missing metadata yields unknown/recompute, not zero. Old backup decoders receive defaults; a feature-disabled reader must still preserve fields. |
| Tokens/checkpoints | Version per-type permission state, immutable job identity, baseline tokens and scan-complete markers. Treat tokens as opaque; expired/rejected values trigger safe re-read, never local deletion by themselves. | Room and DataStore are not one atomic transaction. Journaled raw/derived progress is authoritative; stale token replay is safe. Clear/suspend only affected denied types; capture new baseline before recovery scan. |
| Scoring repair | Assign algorithm/repair revision, record affected retained suffix, invalidate frozen fields deliberately, recompute ascending with consistent config/tier. Keep last valid published generation during incomplete repair. | Users may see corrected historical scores. Release notes distinguish bug fixes from model changes; no silent coefficient edits. Do not restore bad formulas to avoid score changes. |
| Backup operation journals | Version rotation/publication and restore-maintenance records; persist protected old/new credential recovery state only as long as needed; verify archives before replacing originals. | Existing ZIPs remain readable. SAF is not POSIX; publication needs verified copy/manifest semantics. After failed settings restore retain explicit recovery state and prevent mixed-generation work until resolved. |

Preserve the existing fail-closed external v7 migration/readiness path and registered v7→v19 migrations; do not insert new large backfills into startup on the main thread. Test supported installed-version upgrade chains using exported schemas and nonempty source/session/route/bucket data. Never uninstall `app.readylytics.health` to solve a migration or signing conflict.

Release sequencing: ship diagnostic/restore safety guards first; ship S1 readers/journal and bounded replacements next; enable S2 publication only after migration/legacy-policy validation; complete one versioned score repair after input correctness is stable; then enable optimized contexts with corrected reference fallback. Do not repeatedly rescore once per intermediate formula-independent refactor. All new schema changes require enough temporary space for migration and retained backups; low space must leave the old valid database recoverable.

## 13. Documentation Updates

- Every ingestion/store/worker/schema/scoring coordinator change updates `internal-docs/DATA_FLOW.md` in the same package: actual v19→new version, source identity, complete/partial reads, run/checkpoint protocol, dirty journal, transaction boundaries, tier coverage and reader ownership. Replace nonexistent mapper paths; keep formulas in pure Kotlin.
- Any scoring phase, baseline/display, threshold/profile, or explanation change updates `ABOUT.md`, `docs/about.md`, relevant `DATA_FLOW.md` sections and `about_*`/`tooltip_*` in `app/src/main/res/values/strings.xml` together. Check onboarding and recommendation/AI prompt explanations affected by calibration. Explain confirmed score repairs and within-tier versus cross-tier determinism.
- Permission/data collection, retention, route enrichment, backup, restore, logging and export behavior updates `docs/index.md`, `docs/about.md`, `docs/privacy.md`, and `docs/backup-and-data.md` as applicable. Clarify derived RHR versus direct HC record permission and true unlimited local retention versus bounded historical import. Do not reintroduce cloud/Drive/OAuth claims.
- Update `benchmark/BASELINE.md` with measured current results and dataset shape; mark stale results as historical rather than overwriting them with estimates. Document migrations and operational recovery alongside DATA_FLOW or the implementation package's existing documentation.
- Preserve the separate JVM migration plan and its evidence gate. Issue templates/email mirrors are unchanged by this plan; if implementation edits those prompts, update `.github/ISSUE_TEMPLATE/{bug_report,feature_request}.md` and matching `report_email_*_template` strings together.

## 14. Open Decisions

These are implementation gates only where semantics cannot be settled from source/docs. They do not block committing this plan or the independent confirmed repairs.

| ID | Question and why it matters | Options / justified default | Affected work |
|---|---|---|---|
| OD-1 | What correction guarantee should legacy warm-only history provide when HC can no longer supply the original source data? | Future per-source contributions permit precise invalidation but increase storage; complete interval refresh uses less lineage but requires accessible HC; legacy sketches cannot recover either exact source membership or raw values. Default: preserve legacy data as approximate/unknown, use complete authorized refresh where available, and benchmark future per-source lineage before choosing it. | DB-002, WP-17/19, S2 |
| OD-2 | Does MATURE count all eligible historical sleep days or a bounded/recent eligibility window, and when should legacy premature snapshots be repaired? | Documentation's 60-observation phase cannot be reached through a 56-day count. Default: distinct cumulative eligible-day maturity, bounded statistical windows, frozen count/phase; confirm gaps/retention treatment before metadata repair. | SCORE-002, WP-12 |
| OD-3 | What is canonical workout load when raw/warm HR is unavailable but stored avgHr/legacy zone TRIMP exists? | Use stored avgHr with explicit quality; report unavailable; retain a known prior selected-model result. Never silently label legacy zone TRIMP as Banister/Cheng/iTRIMP. Default: preserve a validated canonical result, otherwise explicit unavailable until policy chosen. | SCORE-005, WP-15 |
| OD-4 | How should independent distance/elevation changes and physiologically duplicate origins be resolved? | Track interval identities/change ranges, or document periodic enrichment refresh; source priority and same-device duplicates require product rules. Default: complete own-type change/deletion tracking, preserve stable HC IDs and current device selection; do not invent cross-device averaging/deduplication or expose new source controls automatically. | HC-004, DB-001, WP-08/18 |
| OD-5 | Is displayed resting-HR ratio intentionally based on a different population/window than adaptive RHR used for scoring/TRIMP? | Retain and label both references, or unify them after agreement. Default: bound historical queries now and preserve distinct quantities until intent is confirmed. | SCORE-006, WP-11/15 |
| OD-6 | How should a selected-device steps interval crossing midnight be attributed without per-step timestamps? | Start-day attribution, proportional elapsed-time distribution with explicit approximation, or provider aggregation where its origin filter satisfies the requested selection. Default: preserve interval data and expose/test the chosen policy; do not claim exact per-day attribution from interval totals. Clarify elapsed versus calendar 30-day VO2 lookback at DST at the same time. | SCORE-007, WP-15 |

Operational questions answered by evidence rather than product approval: runtime feature availability is probed; API page-token durability is tested with safe restart fallback; benchmark batch sizes are selected from measurements; actual migration version numbers follow repository HEAD. The separate pure-JVM migration remains blocked on its documented toolchain experiment and does not block this roadmap.

## 15. Definition of Done

- [ ] Every implemented finding has a linked work package, acceptance evidence and migration/recovery note; suspected issues remain labelled unless reproduced. All High findings are either repaired or explicitly excluded from release with a concrete gate.
- [ ] Domain math remains Android-independent; features consume Room-backed domain state; ownership and lock order are documented; existing Hilt/dispatcher/clock seams are used consistently. No speculative new module or UI redesign.
- [ ] Current-day pull-to-refresh and durable historical resync remain distinct; history respects `RetentionBounds`; permission/feature loss is not interpreted as empty data; updates/deletions converge after interruption and token expiry.
- [ ] Million-parent and dense-sample workloads demonstrate bounded application memory, batched transactions, supported query indexes, no ANR/OOM, resumable progress and measured CPU/WAL/storage effects; no universal performance claim without device evidence.
- [ ] Schema upgrades preserve existing user data, source FKs, legacy backups and warm-history quality; failed migration/restore/rotation is recoverable; no blanket data deletion or production-app uninstall.
- [ ] Scoring fixtures prove no future leakage, consistent calibration/frozen phase, complete absence clearing, core/supplemental separation, canonical TRIMP across consumers and precise day boundaries. Heuristic formulas, weights and units are preserved except an explicitly reviewed product change.
- [ ] Dirty work commits with raw mutations; dependency closure covers retained future effects; optimized incremental and full corrected rebuilds agree within explicit same-tier precision. Warm approximation never disguises duplicate/stale coverage.
- [ ] Release logs/crash exports follow safe-field policy; backup snapshots restore consistently; failed creation retains recovery points; plaintext staging is cleaned; external sharing remains user-initiated and the app remains offline.
- [ ] Affected Compose states use lifecycle-aware collection, correct query identity and bounded chart data; loading/error/approximation labels are resources and accessible. No broad test-coverage initiative was added.
- [ ] DATA_FLOW, About/site/tooltips/onboarding/privacy/backup documentation agrees with implementation; required scoring documentation drift/presence tests pass; new file paths are indexed and structural changes synchronized.
- [ ] At implementation completion run the mandatory sequence: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`. Resolve detekt issues in touched files structurally; any new suppression/baseline acceptance requires explicit human approval under AGENTS.md. Run the relevant device benchmarks/migration checks separately and report failures rather than calling an unmeasured plan validated.

Planning deliverable validation: this document changes no production code and creates no implementation commit. Its own completion requires stable finding/roadmap references, resolvable current file references, all fifteen sections, explicit schema/rollback coverage, and a clean diff limited to this planning document. Runtime validations above are work to execute with the implementation, not results claimed by this audit.
