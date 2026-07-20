# Phase 1 Implementation Plan — Correctness & Data Integrity

> Companion to `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`
> (§9 "Phase 1 — Correctness & Data Integrity", §10 WP-04..WP-11 + the settings-invalidation
> slice of WP-26). Records the product decisions taken for this phase's Open Decisions and the
> concrete implementation approach per work package, so the diffs can be reviewed against a plan
> rather than reconstructed from commit messages.

**Status at authoring time:** Phase 0 (WP-01/02/03 — golden fixture, benchmark scaffolding,
documentation-truth pass) is merged (`e7dabfd`, PR #153). All ten findings below were re-verified
directly against current code on `claude/architecture-health-scoring-phase-1-fzcq61` before this
plan was written; none had drifted out from under the remediation plan's description.

**Status as of this pass:** all of WP-04 through WP-11, plus the settings-invalidation slice of
WP-26, are implemented and committed on this branch (not yet merged). Per the environment
constraint below, none of it has been compiled or test-run locally — CI is the first real
verification pass. The two golden-fixture-affecting changes (WP-10's TRIMP unification, WP-11's
stage-less-night fix) are flagged inline in `GoldenFixtureWalkForwardTest`'s docstring rather than
regenerated here; that regeneration, its reviewed score-delta summary, and the still-unexported
Room schema JSON (`core/database/schemas/.../6.json`) are the first things a CI-capable pass must
do before this phase can be considered fully verified.

**Environment constraint (carried over from Phase 0):** this session has no working Gradle —
`./gradlew --version` fails fetching the wrapper distribution (403 from `services.gradle.org`,
egress-policy blocked), and the system's Gradle 8.14.3 cannot resolve this project's AGP 9.2.1.
Every change below is written and manually reviewed against the existing ktlint style, but **not
compiled or test-run locally**. CI must be the first real verification pass. In particular the
golden fixture (`app/src/test/resources/golden/scoring_walk_forward_golden.json`) cannot be
regenerated in this session (`-Dupdate.golden=true` needs a working test JVM) — see the WP-10/WP-11
notes below for how that gap is handled.

---

## Open Decisions — resolved for this phase

| # | Decision | Chosen | Rationale |
|---|---|---|---|
| OD-1 | Which TRIMP feeds workout-only ATL/CTL? | **(a) unify on the user-selected model** (Banister/Cheng/iTRIMP, whichever `prefs.trimpModel` currently is) | Matches ABOUT.md, the per-workout display, and the everyday-HR variant; the alternative would leave the Training Load Model setting affecting only part of the pipeline. |
| OD-2 | Stage-less sleep sessions | **(a) duration fallback + architecture-unavailable reweight** | Phase 0 found this isn't cosmetic: it throws inside sleep aggregation today and silently blanks a 57-day dashboard window in the golden fixture. Needs a fix regardless; this option also makes Duration scoring work for genuinely stage-less nights. |
| OD-3 | Steps deletion tracking | **(a) minimal `step_records` table** | More precise than window-invalidation; merges into the same v5→v6 migration as WP-10's `modelTrimp` column, so the added schema-migration risk is marginal. |
| OD-4 | Freeze semantics vs. retention | **(a) true freeze** | Matches DATA_FLOW.md's existing wording and the "identical SQLite + prefs ⇒ identical scores" determinism contract. |

---

## Work packages, in landing order

Each package is one commit (occasionally two, noted below). All packages get
`./gradlew ktlintFormat && ./gradlew testDebugUnitTest` treated as a CI gate, not a local one, per
the environment constraint above.

### WP-04 — Scoring day-key zone fixes (SCORE-002, CACHE-001 + its wider blast radius)

- `HealthIngestionStore.clearFrozenBaselines` (port, `core/model/.../domain/sync/HealthIngestionStore.kt`)
  and `RoomHealthIngestionStore.clearFrozenBaselines` (`core/database/.../data/local/RoomHealthIngestionStore.kt:105-114`):
  add a `zoneId: ZoneId` parameter, drop the internal `ZoneId.systemDefault()`. Both callers
  (`DailySyncUseCase.kt:140`, `ResyncRangeUseCase.kt:332`) already hold `prefs.scoringZone()` in
  scope as `zoneId` — thread it through.
- `DashboardViewModel.onEvent` (`DismissInsight`/`RestoreInsights`, lines 296-319 in
  `feature/dashboard/.../DashboardViewModel.kt`): replace `ZoneId.systemDefault()` with the scoring
  zone (inject `UserPreferences`/read `prefs.scoringZone()` the same way the rest of the view model
  already resolves prefs).
- `DashboardFlowIntermediate.createDashboardBasicInputsFlow` (`feature/dashboard/.../DashboardFlowIntermediate.kt:91` onward):
  found during research to have the *same* bug more broadly than CACHE-001's original framing —
  `today`, the summary-flow date key, the RAS window, and the dismissal read all resolve via
  `ZoneId.systemDefault()` while `DailySummaryEntity.dateMidnightMs` is written in the scoring zone.
  Fix all of these call sites in the same pass (they're one function).
- Scope decision: left `LocalDate.toMidnightEpochMilli`'s `zoneId: ZoneId = ZoneId.systemDefault()`
  default unchanged. `RasProvider`/`HrMaxProvider`/`StepDetailViewModel` rely on that default and
  aren't wired to read `UserPreferences` today; forcing an explicit zone there would mean injecting
  settings repositories into three more classes for call sites the remediation plan doesn't name as
  findings (only SCORE-002/CACHE-001/HC-007 are named in this class of bug). Fixed exactly those
  plus the one directly-adjacent bug found during research (`DashboardFlowIntermediate`, same
  dismissal/summary join as CACHE-001); left the wider ~20-site `atStartOfDay(ZoneId.systemDefault())`
  pattern as a documented follow-up rather than expanding Phase 1's blast radius unbounded.
- Test: unit test with scoring zone ≠ system zone asserting `clearFrozenBaselines` clears exactly
  the range the walk-forward will (re)write, and a dismissal round-trip test at a mismatched zone.

### WP-05 — Preferences snapshot through walk-forward (SCORE-004)

- `ScoringRepository`/`ScoringRepositoryImpl`: add
  `computeAndPersistDailySummary(day, steps, prefs: UserPreferences)` overload; the existing
  no-`prefs` overload keeps reading `settingsRepo.userPreferences.first()` for the daily-sync
  single-day and ad-hoc callers that don't have a walk-forward.
- `DailyRecomputeSupport.recomputeDay(day, steps, prefs)` new overload threading the snapshot down.
- `DailySyncUseCase.run` and `ResyncRangeUseCase.run` already hold `val prefs = ...` in scope before
  their recompute loops (lines ~66 / ~66 respectively) — switch their `recomputeSupport.recomputeDay(day, steps)`
  calls to the new overload passing that same `prefs` instance.
- Test: fake settings repo whose `userPreferences` flow changes mid-walk-forward; assert every
  recomputed day in one `run()` call used the snapshot taken at the start.

### WP-06 — Backfill serialized + incremental, true-freeze semantics (SCORE-003, OD-4)

- `HealthSyncUseCase`: expose the private `syncMutex` via a `suspend fun <T> withSyncLock(block: suspend () -> T): T`
  method (or equivalent), so it can be reused outside the three existing `sync`/`catchUpSync`/`resyncRange` entry points.
- `HealthDashboardApplication.onCreate`: wrap `backfillHistoricalBaselines.execute()` in
  `healthSyncUseCase.withSyncLock { ... }`.
- `BackfillHistoricalBaselinesUseCase.execute`: stop unconditionally calling
  `dailySummaryDao.wipeDerivedBaselines()` (the DAO method itself is deleted — no other caller).
  New contract (true freeze): filter to only rows where `baselineCalculatedAtDate == null` before
  handing them to `ComputeHistoricalBaselinesUseCase`; never wipe/rewrite a day that already has a
  frozen baseline. No separate watermark/hash tracking needed — `baselineCalculatedAtDate` already
  is the per-day frozen marker, so filtering on it directly gives incrementality (a second
  consecutive launch with no new unfrozen days is a 0-write no-op) for free.
- Test: stress test — launch-time backfill racing a concurrent `sync(windowDays=7)` on a seeded DB
  produces the same summaries as running them serially; second-launch no-op test; a day whose
  30-day baseline window crosses a retention cutoff keeps its already-frozen values.

### WP-07 — Error-modeling fixes (HC-008)

- `DailySyncUseCase.run`'s terminal catch (lines 185-189): add `logE(...)` with the throwable
  attached; add a `catch (e: HealthConnectPermissionRevokedException)` clause before the generic
  `Exception` catch that rethrows (mirrors `ResyncRangeUseCase.kt:373-376`) so
  `PeriodicHealthSyncWorker`'s already-correct `SecurityException`/`HealthConnectPermissionRevokedException`
  routing (`PeriodicHealthSyncWorker.kt:65-73`) is actually reachable through the daily-sync path
  instead of being masked into a generic `SYNC_ERROR`.
- `GetDashboardDataUseCase.invoke` catch-all (`feature/dashboard/.../GetDashboardDataUseCase.kt:54-67`):
  log `e` before returning `CARD_GENERATION_ERROR`.
- `HealthConnectRepositoryImpl.readWeightRecords`/body-fat/BP/SpO2 (lines 340-446): the
  permission-specific catches are fine and already logged; keep those. The final generic
  `catch (e: Exception) -> emptyList()` already calls `logE(..., e)` — leave the logging, but this
  is exactly the "transient IO indistinguishable from no-data" problem HC-008 flags: rethrow
  non-permission `Exception`s instead of swallowing them, so the caller's `retryWithBackoff` can act.
- `isTokenExpiredException`: leave as the documented last-resort string-match fallback (no typed
  exception exists from the HC client for this case); no change needed beyond confirming it stays
  isolated to one function (it already is).
- Test: throwing fake HC repository in a daily-sync unit test asserting
  `HealthConnectPermissionRevokedException` propagates rather than becoming `SYNC_ERROR`.

### WP-08 — Changes-path correctness + steps-deletion tracking (HC-004, HC-005/OD-3)

**HC-004 (link/metric correctness at write time):**
- `HealthChangeSynchronizerImpl.upsertRecord`: HR branch (lines 198-204) and HRV branch (205-211)
  currently pass `emptyList()` session spans into `HeartRateMapper.mapToEntities`/`HrvMapper.mapToEntities`.
  Before mapping, call `sleepSessionDao.getOverlapping(recordStart, recordEnd)` and
  `workoutDao.getOverlapping(recordStart, recordEnd)` (both already exist, same DAOs
  `SessionLinkReconcilerImpl.reconcile` uses) to build real `SessionSpan` lists for the record's own
  time range.
- Exercise branch (212-226): extract the recompute-from-stored-HR-rows logic already in
  `SessionLinkReconcilerImpl.recomputeWorkouts` (lines 127-160) into a small shared helper (e.g. a
  function on `WorkoutMapper` or a new tiny `WorkoutMetricsRecomputer`), and call it from
  `upsertRecord` after the session upsert so a workout upsert has non-zero metrics immediately
  instead of waiting for the next `DailySyncUseCase` reconcile pass.

**HC-005 / OD-3 (steps-deletion tracking via new table):**
- New entity `StepRecordEntity` (table `step_records`) in `core/model/.../data/local/entity/`:
  `id: String` (PK, the HC record id), `startTime: Long`, `endTime: Long`, `count: Long`,
  `deviceName: String?`. Purely a deletion-date-resolution + idempotent-upsert record — never read
  for scoring math.
- New `StepRecordDao` (`core/model/.../data/local/dao/`): `upsertAll`, `getById`, `deleteById`,
  a range/overlap query mirroring `WorkoutDao.getOverlapping`.
- `HealthIngestionStore`/`HealthIngestionBatch`: add a `stepRecords: List<StepRecordInput>` batch
  member (mirrors `WeightInput`/`BodyFatInput` shape); `RoomHealthIngestionStore.persist` upserts it
  alongside the existing batch members.
- `HealthIngestionCoordinator.ingestWindow`: today's steps handling only computes aggregates
  (`StepCountFetcher`) and never produces raw entities for the bulk-ingest batch — add a mapping
  from the read `DomainStepsRecord`s (already fetched for the specific-device path via
  `StepsMapper`) into `StepRecordInput` so bulk sync/resync also persists raw rows. The
  aggregate-based daily total computation is unchanged; this is purely additive.
- `HealthChangeSynchronizerImpl`:
  - `upsertRecord`'s STEPS branch (line ~256, currently a no-op comment): upsert into
    `step_records`.
  - `getAffectedDatesForDeletedRecord`'s STEPS branch (line 363, currently `emptySet()`): look up
    the row by id in `step_records` **before** it's deleted, resolve its real `(startTime, endTime)`
    into affected dates via the same `getDatesBetween` helper other branches use.
  - `deleteRecordLocal`'s STEPS branch (line ~379, currently `Unit`): delete the row from
    `step_records`.
- Test: HR/HRV/exercise upsert-path link-correctness unit tests (asserting immediate correct
  tagging/metrics, no dependency on a follow-up reconcile); a steps-upsert-then-delete test
  asserting the deleted record's date appears in the next sync outcome's `affectedDates` and the
  recomputed `stepCount` reflects the reduced total.

### WP-09 — Foreground window cap + resync escalation (HC-007's zone half, HC-009 cleanup)

- `ForegroundSyncController.evaluateAndSync`/`computeWindowDays`: cap the computed window at a
  named `MAX_INLINE_RECOMPUTE_DAYS` constant (promote the existing 7-day constant used elsewhere,
  e.g. from `DailySyncUseCase`, into a shared `ScoringConstants`-adjacent location); when
  `daysSince` exceeds the cap, run the capped sync and additionally enqueue
  `WorkerScheduler.scheduleResyncWorker()` for the remainder. Resolve dates via
  `prefs.scoringZone()` instead of `ZoneId.systemDefault()`.
- `HealthSyncUseCase.sync(windowDays: Int = 8, ...)`: remove the default parameter value; every
  call site must name its window explicitly (this is HC-009's cleanup, landing here since it's the
  same call surface WP-26 touches next). Delete `ForegroundSyncController.executeSync`'s now-provably-unreachable
  `else` branch.
- Test: unit test with `lastSyncTimestamp` 90 days old asserting `sync` is invoked with
  `windowDays ≤ MAX_INLINE_RECOMPUTE_DAYS` and the resync worker is scheduled for the remainder.

### WP-26 (partial) — Settings-invalidation scope + `recomputeRange` entry point (SCORE-007, HC-009)

Lands before WP-10 — SCORE-007's fix is a hard prerequisite for flipping the workout-only ATL/CTL
read to `modelTrimp` (a model/parameter switch must invalidate the *entire* retention-bounded
history, not 8 days, or the mixed-model EMA problem gets worse, not better).

- `HealthSyncUseCase`: add `recomputeRange(startDate, endDate, onProgress)`. Internally this must
  drive `ResyncRangeUseCase` starting at the RECONCILE phase (zone thresholds may have changed, e.g.
  HR-zone edits) through RECOMPUTE, skipping INGEST/PRUNE entirely (raw HC data is untouched by a
  settings change). `ResyncRangeUseCase.run` doesn't currently support entering mid-phase outside
  its own checkpoint-resume bookkeeping, so this needs a new internal path/parameter on
  `ResyncRangeUseCase`, not just a wrapper — reuse the existing RECONCILE/RECOMPUTE phase bodies,
  don't duplicate them.
- `HealthDataRefresh`/`HealthDataRefreshAdapter` (`core/model/.../domain/sync/FeatureSyncPorts.kt`,
  `app/.../domain/sync/HealthDataRefreshAdapter.kt`): the single undifferentiated
  `refreshAffectedWindow()` is called identically by every settings view model today. Split settings
  by invalidation scope:
  - **historical** (TRIMP model + its parameters, HR zones, hrMax source, RHR/HRV overrides,
    physiology profile) → `HealthDataRefresh.refreshHistorical()` → `recomputeRange` +
    `WorkerScheduler.scheduleResyncWorker()` (durable, foreground, surfaces the existing determinate
    progress banner).
  - **recent-window** (everything currently using the 8-day default for reasons other than the
    above) → keep the existing `refreshAffectedWindow()` behavior, but with the window named
    explicitly rather than defaulted.
  - Update call sites: `UISettingsViewModel`'s `TrimpModelChanged`/`BanisterMultiplierChanged`/
    `ChengBetaChanged`/`ItrimBChanged`/`ResetTrimpToProfileDefaults` and `HeartRateZonesViewModel`
    move to `refreshHistorical()`; `PhysiologySettingsViewModel`/`SyncSettingsViewModel` split
    per-event depending on which field changed.
- Test: fixture asserting that after a TRIMP-model switch and the resulting recompute-only pass,
  `daily_summaries` TRIMP/RAS/ATL/CTL columns are byte-identical to a fresh full resync under the
  new model; no EMA window ever mixes values computed under different model settings.

### WP-10 — TRIMP unification, v5→v6 migration (SCORE-001, SCORE-005, DB-002)

Migration merges with WP-08's `step_records` table (one schema bump, two findings):

- `DatabaseMigrations.kt`: add `MIGRATION_5_6`:
  - `ALTER TABLE workout_records ADD COLUMN modelTrimp REAL` (nullable, additive).
  - `CREATE TABLE step_records (...)` (WP-08).
  - `DROP INDEX index_daily_summaries_dateMidnightMs` (DB-002 — redundant with the
    `daily_summaries` PK).
  - Bump `HealthDatabase.DATABASE_VERSION = 6`; export `core/database/schemas/.../6.json`.
- `WorkoutRecordEntity`: add `modelTrimp: Float? = null`.
- `ScoringRepositoryImpl.computeDailySummary`'s per-workout loop (lines 165-196): it already
  computes `workoutTrimp` via `computeWorkoutTrimpUseCase.execute(...)` per workout — persist that
  value onto the corresponding `WorkoutRecordEntity.modelTrimp` in the same pass.
- `WorkoutDao.getTrimpPoints` (lines 50-57): change the query to select
  `COALESCE(modelTrimp, trimp) AS trimp` — reads transition automatically as the walk-forward
  lazily backfills `modelTrimp` for historical rows.
- `ScoringRepositoryImpl` lines 424-445 (SCORE-005): inject the freshly computed `dailyTrimpRaw`
  (today's model TRIMP, already computed at line 195) into the workout-only TRIMP-by-date series the
  same way `trimpEverydayHr` is already injected for today at line 445.
- `SessionLinkReconcilerImpl.recomputeWorkouts` (lines 127-160) cannot compute model TRIMP (no RHR
  baseline/hrMax/gender inputs available at that call site) — leave it writing zone-weighted
  `trimp` only; `modelTrimp` for those rows gets backfilled by the next full walk-forward recompute,
  consistent with the `COALESCE` transition contract.
- Rename the zone-weighted concept internally to "zone TRIMP" in code comments/docs where it's
  UI-only zone-minutes data; stop calling it "TRIMP" in user-facing or doc text once `modelTrimp` is
  the operative series.
- **Documentation Synchronization Rule applies**: update `ABOUT.md`, `docs/about.md`, in-app
  `about_*`/`tooltip_*` strings, and `internal-docs/DATA_FLOW.md` §2.3 in the same change.
- **Golden fixture**: this is one of the two changes expected to alter golden-fixture output (the
  plan requires "golden fixture updated in a separate reviewed commit that quantifies deltas").
  Regeneration needs `./gradlew testDebugUnitTest --tests "*.GoldenFixtureWalkForwardTest" -Dupdate.golden=true`,
  which needs working Gradle — **not run in this session**. The commit implementing WP-10 will leave
  the golden JSON as-is with a clear note that it's stale for the workout-only TRIMP series and must
  be regenerated (with the delta reviewed) once CI/Gradle access is available, exactly mirroring how
  Phase 0 handled the stage-less-night exception before this same fix lands.
- Per the remediation plan's migration-risk section: **land in its own release, not combined with
  WP-11** (HC-006) — keep as a separate commit at minimum.

### WP-11 — Stage-less sleep fallback (HC-006, OD-2)

- `SleepDataMapper.mapSleepSession`: when `stages` is empty, set
  `durationMinutes = (endTime - startTime)` instead of summing (absent) stage minutes, and flag the
  session via the existing suspicious-stage-reweight pathway so `ComputeSleepMetricsUseCase`
  reweights Architecture instead of scoring 0.
- Add a guard in `ScoringRepositoryImpl.resolveSleepAggregation` (or wherever sessions are handed to
  `SleepDaySegment`) before construction — `SleepDaySegment`'s constructor throws on
  `durationMinutes <= 0`, which is the actual root cause of the 57-day golden-fixture gap Phase 0
  found (not just "0-minute night," an unhandled exception that `DailyRecomputeSupport` swallows
  into a generic failure for every day whose lookback window includes the bad session).
- Update `ABOUT.md`/`docs/about.md`/in-app strings describing stage-less-session handling.
- **Golden fixture**: the second of the two expected-diff changes. `GoldenFixtureWalkForwardTest`
  currently tolerates exactly this characterized exception for its stage-less-night scenario
  (2024-06-30..2024-08-25 in the fixture) — once this fix lands, that tolerance should be removed
  and those ~57 days should turn into normally-scored rows. Same Gradle-access gap as WP-10: the
  JSON is not regenerated in this session; left with an explicit note for the next CI-capable pass.
- Land in a separate release from WP-10 per the plan's migration-risk guidance.

---

## Cross-cutting validation (once CI/Gradle access exists)

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` per package.
- `./gradlew lintRelease` at the end of the phase.
- Golden-fixture regeneration for WP-10 and WP-11, each as its own reviewed commit with a quantified
  score-delta summary (per the remediation plan's §12 migration-risk requirement).
- `DocumentationDriftTest` stays green throughout (it already guards `DATABASE_VERSION`,
  sleep-score weights, etc.).
