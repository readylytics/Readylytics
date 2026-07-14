# Performance Optimization Plan

**Status:** Approved plan — not yet implemented. Each work item below is scoped to land as one
independent, production-ready commit.
**Origin:** Full static performance audit (July 2026) of the UI/Compose/Vico layer, the
state/ViewModel/Flow layer, and the Room/ingestion/workers/startup/logging/build layers. Every
finding was verified against source; line anchors were correct at the time of writing — re-locate
by symbol name if lines have drifted.
**Audience:** A coding agent with no other context. Everything needed to implement each item is in
this document plus the referenced source files.

---

## 1. Non-negotiable constraints

1. **Charts stay composed.** The app intentionally keeps all visible Vico charts fully composed
   and rendered (tab screens are `Column` + `verticalScroll`, not `LazyColumn`, except Dashboard).
   Do NOT lazify, virtualize, placeholder, destroy-off-viewport, or otherwise cause a chart to be
   visibly recreated when the user scrolls back. Several items below *strengthen* this guarantee
   (notably F1). After every UI change, manually verify scroll-back never visibly recreates a chart.
2. **Scoring math is off-limits.** No item may change formulas, thresholds, or coefficients in
   `core/scoring` / `domain/scoring/**`. Items touching scoring *coordinators* change only when/how
   data flows, never what is computed.
3. **No functional regressions.** Except the two explicitly approved UX changes (F1 and F2's DEBUG
   filtering — see §3), all outputs must be structurally identical before/after.

## 2. Repo rules binding every commit

- Pre-commit (mandatory): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`. Run
  `./gradlew lintRelease` once after the final commit of a batch.
- `internal-docs/DATA_FLOW.md` MUST be updated in the same commit for any change to the ingestion
  pipeline, Room schema/DAOs, or scoring coordinators. In this plan that binds **F7** (and **F10**
  if a new DAO method is added). A stale DATA_FLOW.md is treated as a broken build.
- File size: target ≤400 lines, hard limit ≤800. Extractions go into new/sibling files (noted per
  item). After creating new files run `codegraph index`; after moves run `codegraph sync`.
- User-facing strings live in `app/src/main/res/values/strings.xml` (`stringResource(...)`).
- Load-bearing intent comments are house style: every caching change (F3, F11, F2) must include a
  short comment explaining why the cache is safe (see the existing example at
  `core/ui/.../components/ChartDefaults.kt` near the item-placer docs).

## 3. Product decisions already made by the maintainer (do NOT re-ask)

1. **Sync UX (enables F1):** Adopt Dashboard's semantics on Vitals/Sleep/Workouts — skeletons only
   when no data exists yet; routine syncs keep content (and charts) visible with a lightweight
   refresh indicator instead of skeletons.
2. **Release logging (enables F2 part 2):** Add a `DEBUG` log level, map `logD` to it, and filter
   DEBUG out of the release file sink. INFO/WARN/ERROR stay in the encrypted diagnostic file.
3. **Empty `graphicsLayer {}` wrappers (F19):** Not knowingly intentional — benchmark removal and
   keep whichever variant measures better.
4. **Startup baseline backfill:** Approved guarding it to run only when inputs changed — but this
   finding is OWNED by the architecture plan (SCORE-003, see §4); carry the approval there.

## 4. Dedup register — findings owned by the architecture remediation plan

`internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` (branch
`claude/new-session-y0zdys`) already owns three findings this audit independently confirmed.
**Do not implement them from this plan:**

| Confirmed here | Owned by | Note |
|---|---|---|
| App-start baseline backfill wipes/recomputes ALL frozen baselines on every launch (`HealthDashboardApplication.kt:88-97` → `BackfillHistoricalBaselinesUseCase.execute()`: `wipeDerivedBaselines()` + unbounded `getAllSummaries()` + full rewrite = two whole-table invalidation storms racing first-frame dashboard queries) | **SCORE-003** | That plan also covers the unsynchronized race with the sync mutex and the retention/freeze decision (OD-4). Maintainer decision 3-above ("guard it with a persisted input fingerprint") is input to SCORE-003 remediation step 2. |
| Dashboard observes raw full-row HR entities for the whole day; every 5,000-row ingest batch re-triggers the day-sized `SELECT *` (`DashboardFlowIntermediate.kt:196-238`) | **PERF-005** | Its remediation (SQL aggregate observable + debounce) supersedes a projection-only fix. |
| Redundant index on `daily_summaries.dateMidnightMs` duplicating the PK (`DailySummaryEntity.kt:18` vs `:21`; also `DatabaseMigrations.kt:96`) | **DB-002** | Piggyback on the next schema bump. Adjacent note for that work: `workout_records.endTime` is unindexed but filtered in `WorkoutDao.getOverlapping` — add only if query profiling justifies it. |

Cross-references (complementary, NOT duplicates — both may land):
- **F7** here batches the **daily-sync** upsert loop's transactions; the architecture plan's
  PERF-002 restructures the **resync walk-forward's** query complexity. Different code paths;
  coordinate if PERF-002 lands first.
- **F18** should reuse the `@DefaultDispatcher` qualifier the architecture plan's DI-001
  introduces, if it has landed; otherwise use the existing `@IoDispatcher`.
- **M2**'s frame benchmarks should share module setup/fixtures with the Macrobenchmark scaffolding
  the architecture plan's Phase 0 creates for ingest/reconcile/recompute.

## 5. Architecture snapshot (context for a fresh agent)

- Multi-module: `app`, `core/{model,database,healthconnect,designsystem,scoring,ui}`,
  `feature/{dashboard,vitals,sleep,workouts,insights,settings,onboarding,about}`, `benchmark`.
  Kotlin 2.3.21 (strong skipping ON by default), Compose BOM 2026.06.00, Vico 3.2.3, Room 2.8.4,
  Hilt, WorkManager, DataStore (proto), SQLCipher+Tink for at-rest encryption.
- Room is the single source of truth; UI observes DAO `Flow`s via repositories; ViewModels build
  one `UiState` per screen with `combine(...)` → `stateIn(WhileSubscribed(5_000))`; Compose
  collects with `collectAsStateWithLifecycle` (100 % — no plain `collectAsState` in production).
- Foreground sync fires on every app resume: `MainActivity` `ON_RESUME` →
  `SyncViewModel.onAppForeground()` → `ForegroundSyncController.evaluateAndSync` →
  `HealthSyncUseCase.sync(windowDays=1..8)` behind a shared `Mutex`.
  `ForegroundSyncController.isSyncing: Flow<Boolean>` toggles true→false around each sync.
- Every top-level tab except Dashboard is `Column` + `verticalScroll`; Vitals renders **3 Vico
  `CartesianChartHost` charts (`TrendChart`) + 2 Canvas gauges, all always composed**. Dashboard is
  a keyed `LazyColumn` and already implements the two patterns this plan replicates elsewhere:
  - *Sync-state split:* heavy transform isolated from `isSyncing` (`DashboardViewModel.kt:104-115`),
    `isComputingMetrics = isSyncing && summary == null` (`:106-108`).
  - *Slice memoization:* `DashboardUiState.cardInputs()` remember-key (`DashboardViewModel.kt:391-425`).

### Verified-good (do NOT "optimize" these)
Chart `CartesianChartModelProducer` created once per chart (`remember`), data pushed via
`runTransaction` inside `LaunchedEffect(data)` only; Vico formatters/layers/fills/decorations
remembered with correct keys; the marker-visibility listener uses the `rememberUpdatedState`
pattern (fix for Vico issue #1054 pinch stutter); Dashboard LazyColumn stable string keys;
`hrZoneColors()` memoized; `staticCompositionLocalOf` for spacing/colors; scoring
`computeDailySummary` never runs on a UI collection path (Mutex + background dispatcher); WAL +
`PRAGMA synchronous=NORMAL` + `setQueryCoroutineContext(Dispatchers.IO)`; 5,000-row chunked bulk
`@Upsert` with `ensureActive()`/`yield()`; shared sync mutex; lazy Health Connect client;
on-demand WorkManager init; R8 + resource shrinking in release; Room schema export; no widgets;
no runtime logcat reader in release.

---

## 6. Work items

Priorities: **Critical** = noticeable frame-pacing/scrolling/startup/responsiveness win.
**High** = meaningful with moderate effort. **Medium** = smaller gains. **Low** = micro/cleanup.
Effort: S (<½ day), M (½–2 days), L (>2 days).

---

### M1. Compose compiler metrics + stability configuration file — High, Effort S

- **Location:** `build-logic/src/main/kotlin/readylytics.compose-library-conventions.gradle.kts`
  (and the app module's compose setup in `app/build.gradle.kts`); new file
  `compose_compiler_config.conf` at the repo root.
- **Problem:** No `composeCompiler { }` block exists anywhere in the build — no
  metrics/reports destinations and no stability configuration file. Composables taking
  `java.time.LocalDate`/`Instant` or domain models are inferred unstable, and there is no way to
  measure which composables skip.
- **Remediation steps:**
  1. Create `compose_compiler_config.conf` at the repo root. Seed it ONLY with types verified
     immutable: `java.time.LocalDate`, `java.time.Instant`, `java.time.LocalTime`, plus
     domain-model classes you have personally verified are val-only (start conservative; expand
     later using the reports).
  2. In the compose conventions plugin, configure the `composeCompiler` extension:
     `stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))`
     unconditionally; add `metricsDestination`/`reportsDestination` pointing at
     `layout.buildDirectory.dir("compose-metrics")` gated behind a Gradle property
     (`-PenableComposeReports`) so normal builds are unaffected.
  3. Apply the same to the app module if it configures Compose independently of the convention
     plugin.
- **Why first:** every later item's skippability claim becomes verifiable; wrong entries in the
  config can cause stale UI, hence the conservative seed.
- **Acceptance criteria:** `./gradlew :feature:vitals:assembleRelease -PenableComposeReports`
  produces classes/composables reports; a composable taking `LocalDate` is reported skippable.
- **Verification:** pre-commit commands; inspect generated `*-composables.txt`.

### M2. Frame-timing macrobenchmarks + `profileable` — High, Effort M

- **Location:** new `benchmark/src/main/kotlin/.../ScrollBenchmark.kt` (next to the existing
  `StartupBenchmark.kt`, which only has `StartupTimingMetric` tests); `app/build.gradle.kts` /
  manifest for the benchmark build type.
- **Problem:** No `FrameTimingMetric` coverage of the chart screens this plan targets; the
  benchmark build type is not `profileable`, limiting trace fidelity on a non-debuggable build.
- **Remediation steps:**
  1. Add `<profileable android:shell="true"/>` (or the DSL equivalent) for the benchmark build
     type (`benchmark` initWith(release), debug-signed — already exists in `app/build.gradle.kts`).
  2. Add `ScrollBenchmark.kt` with `MacrobenchmarkRule` + `FrameTimingMetric` and three journeys:
     (a) open Vitals tab, vertical fling to bottom and back, twice;
     (b) horizontal Vico pan + pinch-zoom on a 30-day chart (`UiObject2` gestures on the chart
     bounds);
     (c) tab switch Dashboard↔Vitals ×3.
  3. Deterministic data: the app is offline-first — seed via a debug-only seeding hook or a
     pre-populated DB fixture. Coordinate with (and do not duplicate) the Macrobenchmark
     scaffolding planned by the architecture plan's Phase 0.
  4. Record baseline P50/P90/P99 `frameDurationCpuMs` numbers in the benchmark module's README (or
     a `BASELINE.md`) before any other item lands.
- **Acceptance criteria:** all three journeys run green on a device/emulator and produce frame
  metrics; baseline numbers recorded.
- **Verification:** `./gradlew :benchmark:connectedBenchmarkAndroidTest` (device required).

---

### F1. Stop routine syncs from destroying and rebuilding all charts — **Critical, Effort M** *(approved decision 1)*

- **Location:**
  - `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt:158`
    (`isLoading = isSyncing` inside the final `combine` at `:145-160`);
  - `feature/sleep/.../SleepViewModel.kt:249` and `feature/workouts/.../WorkoutsViewModel.kt:401`
    (same pattern);
  - consumers: `VitalsScreen.kt:99` (`ScreenHeaderSection(isLoading = uiState.isLoading)`) and
    `CardLoader(isLoading = uiState.isLoading, ...)` at `:127, :276, :313, :350` — the latter three
    wrap the three Vico `TrendChart` cards;
  - `core/ui/.../common/CardLoader.kt:22-26` is literally
    `if (isLoading) skeleton() else content()` — two different call sites.
- **Problem:** Foreground sync runs on every app resume (`MainActivity.kt:105-113`). Each sync
  flips `isSyncing` true→false, so `CardLoader` **disposes the chart composables (and gauges),
  shows skeletons, then rebuilds all three Vico charts from scratch** — at least one full
  teardown+rebuild cycle per resume, on Vitals, Sleep, and Workouts. This is the single largest
  recurring composition cost in the app and directly violates the charts-stay-composed intent.
- **Remediation steps (per tab; Vitals shown, mirror on Sleep/Workouts):**
  1. In `VitalsUiState`, split the single flag: keep `isLoading` but redefine its source as
     *initial* load only, and add `isRefreshing: Boolean`. Follow Dashboard's precedent
     (`DashboardViewModel.kt:106-108`): `isLoading = isSyncing && latestSummary == null`
     (i.e. no data yet), `isRefreshing = isSyncing`.
  2. `CardLoader(...)` call sites keep receiving `uiState.isLoading` (now "initial only") —
     unchanged code, changed semantics. `ScreenHeaderSection`/date-switcher disable and any
     refresh indicator switch to `uiState.isRefreshing`.
  3. Sleep: `SleepViewModel.kt:249`; Workouts: `WorkoutsViewModel.kt:401` — same split. Choose the
     "no data yet" predicate per tab (e.g. Sleep: `sleepSession == null && trend lists empty`;
     Workouts: `recentWorkouts.isEmpty() && dailyTrimp.isEmpty()` on first emission). Keep it
     simple and conservative: skeletons must still appear on a true first launch.
  4. Coordinate with F4/F9 if they land in the same window (they touch the same combine blocks).
- **Expected:** Eliminates ~6 Vico chart construction/teardown cycles per app resume across tabs;
  removes the resume-time jank burst.
- **Risk:** Low-Medium. Visible UX change (approved): users no longer see skeletons during routine
  syncs. Verify the first-ever-launch skeleton path manually (fresh install → skeletons until
  first sync completes).
- **Acceptance criteria:** with data present, toggling a foreground sync produces **zero**
  `TrendChart` recompositions attributable to `isLoading` (Layout Inspector recomposition counts);
  fresh-install first launch still shows skeletons.

### F2. `SecureFileLogSink`: append/rotate instead of decrypt-everything-rewrite-everything; add DEBUG level — **Critical, Effort M-L** *(approved decision 2)*

- **Location:** `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt` —
  `log()` `:50-73` (logcat + coroutine buffer), flush trigger `pendingLogs.size >= 5 || 2s` `:91`,
  `flush()`→`persistLogs()` `:105-129`, `readAllLogs()` `:131-136`, `writeChunks()` `:160-174`,
  `retainWithinTotalCapacity()` `:194-201` (`String.byteSize()`/`toByteArray` full passes `:274`).
  Installed as the global sink in release at `HealthDashboardApplication.kt:154` (`installAndroidLogSink()`
  `:62-63`). Log facade: `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt`
  — `LogLevel` enum has only `INFO, WARN, ERROR` (`:3`); `logD` maps to `INFO` (`:70`); the default
  `DomainLogSink.isLoggable` returns `true` (`:8`) and `SecureFileLogSink` does not override it.
- **Problem:** Every flush (≥5 lines or 2 s) reads back and Tink-AEAD-**decrypts all existing log
  slots** (maxFileSize 2 MB × 3 slots = up to 6 MB), concatenates into one String, does another
  full `toByteArray` pass for capacity trimming, re-partitions, then **re-encrypts and rewrites
  every slot**. Sync paths are chatty (`logD` per day in `DailySyncUseCase.kt:151,155`; per
  5,000-row batch in `RoomHealthIngestionStore.kt:86-101`; ~a dozen lines per sync in
  `ForegroundSyncController`), so a single sync triggers multiple multi-MB crypto+IO+allocation
  cycles concurrent with UI. Because `isLoggable` defaults true, **all** of this happens in release.
- **Remediation — commit 1 (rotation):**
  1. Pre-check: confirm `TinkSecureFileStore` (the `secureFileStore` impl) binds no path-derived
     associated data — encrypted files must survive a rename. If it does bind the path, rotation
     re-encrypts only the single rotated file instead of renaming.
  2. Keep the **active slot's plaintext in memory**: a `StringBuilder` + byte counter, bounded by
     `maxFileSize`; hydrate once lazily (first flush after process start) from
     `readFileContent(logFile)`.
  3. On flush: append pending lines to the buffer, encrypt-write **only** `prod_logs.txt`. Never
     touch `.1`/`.2` backups on a normal flush.
  4. When the byte counter exceeds `maxFileSize`: rotate by rename — delete
     `prod_logs.txt.<maxBackups>`, rename `.1→.2`, `prod_logs.txt→.1`; start a fresh buffer. Total
     capacity bound `(maxBackups+1)×maxFileSize` is preserved structurally by rotation, so
     `retainWithinTotalCapacity` disappears (or becomes a rotation-time assertion).
  5. Raise the flush threshold (e.g. ≥64 lines or 5 s) — safe now that flush cost is small; the
     durability window (pending lines lost on process death) is the same order as today's.
  6. Public read path `readLogsDecrypted()` (`:294-298`) must keep returning the same
     chronological concatenation (backups oldest-first + active buffer). Extend the existing unit
     tests: rotation boundary, capacity bound, ordering, hydrate-then-append, crash-window.
- **Remediation — commit 2 (DEBUG level):**
  1. In `AppLog.kt`: add `DEBUG` to `LogLevel`; change `logD` (`:66-71`) to log `LogLevel.DEBUG`.
  2. `SecureFileLogSink`: handle `DEBUG` in the logcat `when` (`Log.d`), and override
     `isLoggable(level, tag) = level != LogLevel.DEBUG` (release sink only). The debug-build sink
     (`installAndroidLogSink` picks per build type — see `HealthDashboardApplication.kt:62-63,154`)
     keeps logging DEBUG to logcat.
  3. Sweep other `DomainLogSink` implementations for exhaustive `when(level)` compilation errors —
     that is the complete call-site checklist (the enum addition makes the compiler find them).
- **Expected:** Per-flush cost drops from ~12 MB crypto + multi-MB string churn to one ≤2 MB
  encrypt, with far fewer flushes; DEBUG filtering removes the sync chatter from the release file
  entirely. Biggest sustained release-build responsiveness win during sync.
- **Risk:** Medium. Must preserve: chronological ordering, capacity bound, decryptability of
  pre-existing files (hydration reads the old format — same content model, so no migration
  needed), and crash durability no worse than today.
- **Acceptance criteria:** unit tests green incl. new rotation/capacity/ordering tests; manual:
  export diagnostics after a sync in a release build → file decrypts, contains INFO/WARN/ERROR but
  no DEBUG lines; a profile/systrace during sync no longer shows multi-MB Tink work per 2 s.

### F3. Cache axis label strings in `ChartDefaults.rememberDayOffsetFormatter` — **Critical, Effort S**

- **Location:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ChartDefaults.kt:48-65`
  (the `CartesianValueFormatter` created inside `remember(rangeStartMs)`).
- **Problem:** The formatter *object* is remembered, but its lambda body executes
  `Instant.ofEpochMilli(rangeStartMs).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(value.toLong()).format(formatter)`
  **per visible axis label per draw pass**. The three Vitals charts share one scroll/zoom state, so
  a single horizontal drag drives ~3 charts × ~6 labels × every frame → ZonedDateTime/LocalDate/
  String allocation churn → GC pressure → dropped frames. Biggest per-frame allocation source
  during chart pan/zoom.
- **Remediation steps:**
  1. Inside the existing `remember(rangeStartMs)` block: hoist
     `val baseDate = Instant.ofEpochMilli(rangeStartMs).atZone(ZoneId.systemDefault()).toLocalDate()` once.
  2. Add `val labelCache = HashMap<Long, String>()` and make the formatter body
     `labelCache.getOrPut(value.toLong()) { baseDate.plusDays(it).format(formatter) }`.
  3. Comment why this is safe: the x-domain is bounded day offsets (≤ ~180 entries), draw happens
     on a single thread, and the cache lives/dies with the `remember(rangeStartMs)` scope so a
     range change discards it. Output strings are byte-identical to today (non-integral `value`
     already truncates via `toLong()`).
- **Expected:** Near-total elimination of date/string allocation on the chart draw path;
  measurable P90 frame improvement in M2 journey (b).
- **Risk:** Very low.
- **Acceptance criteria:** axis labels unchanged for 7/30/90/180-day ranges (screenshot compare or
  unit test on the lambda if extracted); M2 journey (b) P90 does not regress.

### F4. Workouts: take `isSyncing` out of the pipeline-restarting params — **Critical, Effort M**

- **Location:** `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt:122-415`.
  `isSyncing` is a field of `CombinedParams` (`:126-128`); the params flow goes through
  `distinctUntilChanged` (`:137`) — which a sync toggle survives, being a real value change — then
  `flatMapLatest` (`:138`).
- **Problem:** Each sync toggles `isSyncing` at least twice; each toggle **cancels and restarts
  the entire inner pipeline**: `getEarliestWorkoutTimestamp()` (`:143`), three Room `observeSince`
  subscriptions (`:182-184`), CTL/ATL EMA series computation (`:231-242`), the with/without-strain
  EMA recompute (`:367-378`), and the N+1 per-workout HR query loop (`:293-303`, see F10). During
  a resume-sync the Workouts tab therefore re-runs dozens of queries + EMA math twice for nothing.
- **Remediation steps:**
  1. Remove `isSyncing` from `CombinedParams` (params become range/date/page). The `scan`-based
     page-reset logic (`:129-136`) never keyed on it, so it is unaffected.
  2. Let the existing heavy pipeline produce an intermediate content value (either a new
     `WorkoutsContentState`, or the current `WorkoutsUiState` with a placeholder `isLoading`).
  3. Merge sync state afterwards, mirroring `DashboardViewModel.kt:104-110`:
     `heavyFlow.combine(foregroundSyncController.isSyncing) { content, syncing -> content.copy(/* per F1: isRefreshing = syncing; isLoading = syncing && content-empty */) }.distinctUntilChanged()`
     then `stateIn` as today.
  4. Coordinate flag semantics with F1 (same file/flow).
- **Expected:** ≥2 full pipeline re-runs per sync eliminated. No staleness: Room invalidation
  already re-emits the `observeSince` flows on real data changes.
- **Risk:** Low.
- **Acceptance criteria:** unit test (or logging assertion in a VM test) that a
  `isSyncing` toggle with unchanged data produces no new inner-pipeline execution and at most one
  cheap `copy` emission; UI behavior otherwise identical.

### F5. Isolate Vitals screen recomposition (chart-inputs slice + hoisted delta strings) — **Critical, Effort M**

- **Location:** `feature/vitals/.../overview/VitalsScreen.kt` — `uiState` collected at `:57`,
  destructured/read at `:93-96`; RHR/HRV delta strings built inline in composition at `:177-191`
  and `:214-228` (multiple `stringResource` calls + concatenation per recomposition); the three
  `TrendChart`s at `:293, :330, :366`.
- **Problem:** One god-`VitalsUiState` is read at the top of the screen; any field change
  recomposes the entire scroll column including the three chart subtrees. Even after F1, changes
  to `isRefreshing`/gauge fields would still churn the chart subtrees without isolation.
- **Remediation steps:**
  1. Add `@Immutable data class VitalsChartInputs(chartSeries, rangeStartMs, selectedRange, presentation, isCalibrating)`
     plus a `VitalsUiState.chartInputs(): VitalsChartInputs` extractor — put both in
     `VitalsStateFactory.kt` (which already holds `VitalsChartSeries`/`VitalsPresentationState`,
     both `@Immutable`) to respect the 400-line target. Model on
     `DashboardUiState.cardInputs()` (`DashboardViewModel.kt:391-425`).
  2. In `VitalsScreen`, pass only `chartInputs` into a new private `VitalsTrendSection(...)`
     composable that contains the three `TrendChart` cards; pass only the gauge-relevant fields
     into a private `VitalsGaugeRow(...)` (extracted from `:157-242`).
  3. Move delta-string building into `remember(currentRhr, baselineRhr) { ... }` /
     `remember(currentHrv, baselineHrv) { ... }` — hoist the needed `stringResource(...)` values
     into locals *before* the `remember` (composable calls are not allowed inside the lambda).
  4. Requires F8 (`@Immutable` on the state types) and benefits from M1's stability config so the
     sections actually skip.
- **Expected:** Sync/gauge-only changes no longer touch the chart subtrees; combined with F1+F4,
  resume-sync becomes near-free on Vitals.
- **Risk:** Low-Medium — mechanical extraction; verify with Layout Inspector recomposition counts.
- **Acceptance criteria:** compose compiler report (M1) shows `VitalsTrendSection` skippable;
  Layout Inspector shows 0 `TrendChart` recompositions when only gauge/refresh fields change.

### F7. Coalesce Room invalidation storms during daily sync — **High, Effort M**

- **Location:** `DailySyncUseCase.kt:142-162` (walk-forward loop: per-day score → per-day
  `dailySummaryDao.upsert` — one `daily_summaries` table invalidation per day); ingest batches at
  `RoomHealthIngestionStore.kt:80,92` invalidate `heart_rate_records`/`hrv_records` per 5,000-row
  transaction (leave those as-is — cooperative cancellation between chunks is deliberate).
- **Problem:** Room invalidates per table per **transaction**. A routine `windowDays=7..8` sync
  performs 7–8 separate upsert transactions on `daily_summaries`; each one re-runs **every**
  observed `daily_summaries` query in the app (Dashboard today-summary + 7-day RAS window, Vitals
  `observeSince`, Sleep, Workouts ×3) — each a full `SELECT *` over the ~100-column entity plus
  entity→domain mapping — while the user is looking at the UI (sync runs on resume). The
  `distinctUntilChanged` wrappers only suppress *downstream* emissions, not the SQL + mapping cost.
- **Remediation steps:**
  1. In the walk-forward loop, stop upserting per day. Buffer each day's computed summary
     (successes only — per-day failures are already logged-and-continued at `:151-155`, preserve
     exactly that semantics) and upsert the buffered list once after the loop inside a single
     `database.withTransaction { }` via the existing `RoomTransactionRunner`
     (`RoomTransactionRunner.kt:14` → `database.withTransaction`), or add a bulk
     `upsertAll(List<DailySummaryEntity>)` to `DailySummaryDao` (Room `@Upsert` supports lists).
  2. Scope: the **daily** sync path. If `resyncRange`'s recompute shares this loop
     (`DailyRecomputeSupport`), chunk its transactions (e.g. per 30 recomputed days) so a
     historical resync doesn't build one giant transaction; keep the checkpoint/progress semantics
     untouched.
  3. Confirm (it is true today — keep it true) that recalc progress comes from
     `ForegroundSyncController`/`WorkInfo.progress`, not from Room emissions, so batching does not
     change progress UX.
  4. **Update `internal-docs/DATA_FLOW.md`** (sync/recompute section) in the same commit; if a DAO
     method is added, document it there too.
  5. Cross-ref: complementary to the architecture plan's PERF-002 (resync walk-forward query
     complexity). Coordinate to avoid merge conflicts if it lands first.
- **Expected:** ~7× fewer observed-query re-runs per routine sync; directly reduces the repeated
  trend re-aggregation on Sleep/Dashboard during sync.
- **Risk:** Medium — transaction sizing (bounded: ≤8 rows on the daily path; chunked on resync);
  identical end-state must be asserted.
- **Acceptance criteria:** integration/unit test: syncing an N-day window performs exactly one
  `daily_summaries` write transaction (daily path); resulting rows byte-identical to the per-day
  implementation on a fixture; DATA_FLOW.md updated.

### F8. Annotate feature UI states `@Immutable` — **High, Effort S**

- **Location:** `VitalsUiState` (`VitalsViewModel.kt:37-45`), `SleepUiState`
  (`SleepViewModel.kt:45-65`), `WorkoutsUiState` (`WorkoutsViewModel.kt:62`),
  `WorkoutDetailUiState` (`WorkoutDetailViewModel.kt:35`), plus the new F5 slices. Precedent:
  `DashboardUiState` is already `@Immutable` (`DashboardViewModel.kt:349`); `VitalsChartSeries`,
  `VitalsPresentationState` (`VitalsStateFactory.kt:16,23`), `DailyDataPoint`, and
  `TrendChartRenderData` already are too.
- **Problem:** Plain data classes holding `List<>` are inferred **unstable**, so composables
  taking them cannot skip (strong skipping only lets referentially-equal unstable params skip, and
  each emission creates a new state object).
- **Remediation steps:** verify each class is val-only and never mutated post-construction, then
  add `androidx.compose.runtime.Immutable`. Do NOT introduce `kotlinx.collections.immutable` —
  the annotation is sufficient and dependency-free. Land **before** F5.
- **Expected:** Enables skipping for F5's sections and all chart-card callers.
- **Risk:** Low (annotation is a contract — the verification step is the work).
- **Acceptance criteria:** compose compiler report (M1) shows the annotated types stable and their
  consumer composables skippable.

### F9. Sleep: Dashboard-style split + `distinctUntilChanged` — **High, Effort M**

- **Location:** `feature/sleep/.../SleepViewModel.kt:96-270`. The 8-flow array-form `combine`
  (`:215-264`) includes `foregroundSyncController.isSyncing` as element 4 (`:219`); the combined
  flow has **no** `distinctUntilChanged`. The heavy trend aggregation (day loop over
  `range.days` building three `DailyDataPoint` lists, `:184-211`) lives in `trendSessionsFlow`'s
  upstream `.map` (`:172-213`).
- **Problem (precise):** a sync toggle re-runs the combine transform (unpacking +
  `buildSleepTimeGaugeData` + new `SleepUiState`) and emits a fresh state → whole-screen
  recomposition (+ chart teardown until F1). The heavy trend loop re-runs during sync via Room
  invalidation echoes on `observeSince` (addressed by F7), not via the toggle — don't mis-attribute.
- **Remediation steps:**
  1. Remove `isSyncing` from the inner combine; produce a `SleepContentState` (data class).
  2. Add `.distinctUntilChanged()` on the content flow — structural equality over ≤180-element
     lists is cheap and swallows invalidation echoes whose data didn't change.
  3. Merge sync state after: `.combine(foregroundSyncController.isSyncing) { c, s -> c.toUiState(...) }`
     with the F1 flag semantics.
  4. Bonus: the 8-arg array combine drops to ≤7 typed args — convert to the typed overload.
- **Expected:** Zero screen recompositions from sync ticks; echo emissions suppressed.
- **Risk:** Low.
- **Acceptance criteria:** VM unit test: `isSyncing` toggle with unchanged data → single cheap
  emission differing only in the sync flags; trend lists referentially reused when data unchanged.

### F10. Workouts: batch the N+1 heart-rate query loop — **High, Effort M**

- **Location:** `WorkoutsViewModel.kt:288-303` —
  `for (workout in recentWorkouts) heartRateRepository.getByTimeRange(workout.start, workout.end)`.
- **Problem:** One suspend DB query per workout, re-executed on every upstream emission (each
  `daily_summaries` upsert during sync until F7 lands, and twice per sync until F4 lands).
- **Remediation steps:**
  1. Compute `spanStart = min(startTimes)`, `spanEnd = max(endTimes)` over the workouts needing
     samples; fetch once with the existing `heartRateRepository.getByTimeRange(spanStart, spanEnd)`.
  2. Partition in memory: for each workout, `samples.filter { it.timestampMs in w.start..w.end }`
     (sort once by timestamp, then use binary search / two-pointer if the list is large). Matches
     current per-query semantics exactly, including overlapping workouts receiving shared samples.
  3. Guard the pathological case: if `spanEnd - spanStart` exceeds a threshold (e.g. 45 days),
     chunk the fetch per cluster of workouts (or fall back to per-workout) to avoid materializing
     a huge sample list.
  4. Keep the mapping into `ComputeWorkoutTrimpUseCase.HeartRateSample` identical.
  5. Unit-test: identical `WorkoutDisplayItem` output vs the loop implementation on fixtures with
     0, 1, N, and overlapping workouts. (No DAO change needed; if one is added anyway →
     DATA_FLOW.md.)
- **Expected:** N queries → 1 per emission.
- **Risk:** Low-Medium (mitigated by the span guard).

### F11. Cache/reduce allocations in `itemPlacerForRangeDays` — **High, Effort S**

- **Location:** `ChartDefaults.kt:98-193`. The anonymous `ItemPlacer`'s `getLabelValues` (`:179`)
  and `getLineValues` (`:186`) each call `calculateValues(...)` per draw pass; that function
  allocates `mutableListOf`, `filter`, `toMutableList`, and a final `.sorted()` (`:176`) every
  call. The placer instance itself is already remembered per `rangeDays` by callers
  (`TrendCharts.kt:313-315`).
- **Remediation steps:**
  1. Precompute the spacing-dependent candidate values once per placer instance (the `values`
     list at `:138-143` depends only on `spacing`/`rangeDays` — lazily fill a small
     `Map<Int, DoubleArray>` field).
  2. Add a single-entry result cache: fields `lastRange: ClosedFloatingPointRange<Double>?` +
     `lastResult: List<Double>`; if the incoming visible range equals `lastRange`, return
     `lastResult`. This collapses the duplicate label/line call per frame and makes static frames
     zero-work.
  3. Replace append-then-`sorted()` with in-order insertion of `maxVal`.
  4. Comment why plain fields are safe: draw is single-threaded; the placer is scoped to one chart
     via `remember(rangeDays)`. Output lists must be element-identical.
- **Expected:** Halves list churn during panning; zero allocation on static frames.
- **Risk:** Low.
- **Acceptance criteria:** unit test comparing old/new `calculateValues` outputs across range
  buckets (7/30/90/180) and representative visible ranges; M2 journey (b) non-regression.

### F12. Startup: pre-warm the first DataStore read + cheap migration short-circuit — **High, Effort S-M**

- **Location:** `MainActivity.kt:36` (`SPLASH_MAX_WAIT_MS = 2000`), `:82-95` (splash
  `setKeepOnScreenCondition` waits for the first `userPreferences` emission — intentional
  theme-flash prevention, KEEP the condition); `app/.../di/DataStoreModule.kt:104-282` (three
  chained `DataMigration`s on the user-prefs proto store, executed on the first `.data` read).
- **Problem:** The first frame is gated on the first DataStore read (bounded 2 s), but that read
  is only *triggered* by composition — DataStore load is serialized after activity startup instead
  of overlapping it. Migrations ride the same first read.
- **Remediation steps:**
  1. In `HealthDashboardApplication.onCreate`'s existing `appScope.launch` (`:88`), add
     `settingsRepo.userPreferences.first()` as the FIRST statement (before the backfill call), so
     the proto store (and any one-time migrations) load concurrently with Activity startup.
     DataStore caches after the first read, so the Activity's collection then resolves fast.
  2. Inspect the three migrations' `shouldMigrate`: they must be cheap flag/version checks on the
     post-migration steady state (the legacy branch that opens a second DataStore at `:117` must
     be skipped on migrated installs). Make them so if not.
  3. Do not change the splash condition or `SPLASH_MAX_WAIT_MS`.
- **Expected:** DataStore latency removed from the critical path; measurable in `StartupBenchmark`.
- **Risk:** Low.
- **Acceptance criteria:** `StartupBenchmark` cold-start P50 improves or holds; splash never shows
  the wrong theme (manual dark/light check).

### F13. Move SQLCipher key validation off the pre-frame main thread — **High, Effort M**

- **Location:** `MainActivity.kt:52-56` — synchronous `sqlCipherKeyManager.validateKeyDecryption()`
  on the main thread before `setContent`; its only consumer is the choice between
  `DatabaseRecoveryScreen` and the normal tree.
- **Problem:** Keystore + SQLCipher key material work blocks the main thread tens-to-hundreds of
  ms on every cold start.
- **Remediation steps:**
  1. Replace the synchronous call with `var dbKeyOk by mutableStateOf<Boolean?>(null)`; run the
     validation in `lifecycleScope.launch(Dispatchers.IO)` started in `onCreate`.
  2. Extend the existing `setKeepOnScreenCondition` to also wait for `dbKeyOk != null` (still
     bounded by `SPLASH_MAX_WAIT_MS`). Render recovery vs normal tree from the state — decision
     unchanged, just off-main.
  3. Define the timeout path explicitly: if the 2 s bound elapses with `dbKeyOk == null`, proceed
     optimistically; Room's open failure must then route to the recovery UI — **before finalizing,
     trace how key corruption currently manifests** (does `validateKeyDecryption` throw vs return
     false; does Room open failure surface anywhere?) and wire that path.
  4. Test both paths (valid key; corrupted key → recovery screen still appears).
- **Expected:** Removes a fixed main-thread block from every cold start.
- **Risk:** Medium — the recovery path is correctness-critical; conservative wiring + tests required.

### F14. Baseline Profile generation — **High, Effort M**

- **Location:** new `:baselineprofile` module (or extend `:benchmark`); `app/build.gradle.kts`;
  `gradle/libs.versions.toml`.
- **Problem:** `app` depends on `androidx.profileinstaller` but **no profile exists** — no
  `BaselineProfileRule`, no `baseline-prof.txt`, no `androidx.baselineprofile` plugin anywhere.
  Cold start and first chart render run fully JIT; the profileinstaller dependency is inert.
- **Remediation steps:**
  1. Add the `androidx.baselineprofile` Gradle plugin (version catalog + plugin block) and a
     generator module with a `BaselineProfileRule` test whose journey is: cold start → Dashboard
     rendered → visit each tab (Vitals/Sleep/Workouts/Insights/Settings) → one Vico horizontal
     scroll + zoom. This captures Compose, Vico, Room, and Tink hot paths.
  2. Wire `baselineProfile(project(":baselineprofile"))` in `app/build.gradle.kts`; check the
     generated profile in; document the regeneration cadence (e.g. once per release) in the repo
     docs (AGENTS.md-adjacent or the benchmark README).
  3. Land LAST among the perf items so the profile captures final code.
- **Expected:** Typical 15–30 % cold-start improvement; reduced first-interaction jank on chart
  screens.
- **Risk:** Low (stale profiles managed by documented cadence).
- **Acceptance criteria:** `StartupBenchmark` cold start improves with the profile installed vs
  `CompilationMode.None`; profile file checked in and installed in release builds.

### F15. Remember `zoneBandColors` — **Medium, Effort S**

- **Location:** `core/ui/.../components/TrendCharts.kt:225` calls `zoneBandColors(...)`
  (plain function, `ZoneBandUtils.kt:52-69`) un-remembered → a new `List<Color>` per recomposition
  per chart, which also churns the `remember(bands, colors, minY, maxY)` key comparison at `:226`.
- **Remediation:** add `@Composable fun rememberZoneBandColors(bands, ...) = remember(bands, extendedColors, primaryContainer, errorContainer) { zoneBandColors(...) }`
  in `ZoneBandUtils.kt` (mirroring the existing memoized `hrZoneColors()` at `:34`); grep for all
  `zoneBandColors(` call sites and convert them.
- **Expected:** Removes per-recomposition allocation on every chart. **Risk:** none.

### F17. Remember theme computations in `FitDashboardTheme` — **Medium, Effort S**

- **Location:** `core/designsystem/.../Theme.kt:87-212`. `matchingPreset` search, `colorScheme`
  (incl. `mcuColorScheme` seed generation, `fallbackLight/DarkScheme`, `.copy`, `harmonizeWith`),
  `semanticColors`, `baseExtended`, plus fresh `Spacing()`/`Dimens()` at `:204-205` run on **every**
  `FitDashboardTheme` recomposition. Downstream cascade is bounded (provided types are data
  classes with structural equality) — that is why this is Medium, not Critical — but the
  recomputation itself is waste at the composition root, and the app-level wrapper collects six
  preference flows.
- **Remediation:** wrap the computation in `remember(darkTheme, dynamicColor, customPrimaryColor, customSecondaryColor, customTertiaryColor, isCustomPaletteEnabled /*, context if dynamic*/) { ThemeHolder(colorScheme, extendedColors, statusColors) }`;
  `remember { Spacing() }` / `remember { Dimens() }`. Provided instances must be identical when
  keys are unchanged. **Risk:** Low.

### F18. `WorkoutDetailViewModel.loadWorkout` off `Main.immediate` — **Medium, Effort S**

- **Location:** `feature/workouts/.../WorkoutDetailViewModel.kt:74-163`. The single
  `viewModelScope.launch` has no dispatcher (only VM in the app without one injected); repo calls
  suspend to IO internally, but the in-lambda work — `(hcSamples + dbSamples).distinctBy{}.sortedBy{}`
  (`:101-104`), `ChartDataMapper.mapToChartData`, `RecoveryMetricsMapper`, sample maps/filters —
  runs on the main thread. Thousands of HR samples → visible stall when opening a workout detail.
- **Remediation:** inject a dispatcher (constructor pattern identical to `VitalsViewModel.kt:71`)
  and wrap the processing in `withContext(...)`, updating `_uiState` after. Reuse the
  `@DefaultDispatcher` qualifier from the architecture plan's DI-001 if it has landed; otherwise
  the existing `@IoDispatcher`. **Risk:** Low.

### F19. Benchmark removal of the empty `graphicsLayer {}` chart-card wrappers — **Medium, Effort S** *(decision 3)*

- **Location:** `VitalsScreen.kt:291, 328, 364`; also `feature/workouts/.../WorkoutStatsSection.kt:187, 280`.
- **Problem/unknown:** `Modifier.graphicsLayer { }` with an empty block promotes each chart card to
  its own hardware render layer — potentially helping vertical-scroll caching, potentially pure
  memory/bandwidth cost. The maintainer says it is not knowingly intentional.
- **Remediation:** after M2 exists, run journey (a) with and without the wrappers; keep the
  better-measuring variant; either way add a load-bearing comment (or commit message) documenting
  the measured decision. **Risk:** Low.

### F20. Cache `ChartUtils` tooltip date formatter — **Low, Effort S**

- **Location:** `core/ui/.../common/ChartUtils.kt:11-24` — `getDateFormatter()` builds a
  `DateTimeFormatter` per `formatTooltipDate` call (marker taps only).
- **Remediation:** cache the formatter in a small map keyed on `Locale.getDefault()` (locale
  changes normally restart the process; keying is free insurance). **Risk:** Low.

### F22. `PeriodicHealthSyncWorker` constraints — **Low, Effort S** *(needs maintainer confirmation)*

- **Location:** `WorkerSchedulerImpl.kt:108-119` — the periodic sync request has **no
  Constraints**, so background syncs (real DB writes to observed tables) can start while the user
  is actively using the app on low battery. F7 removes most of the UI harm regardless.
- **Remediation:** optionally add `Constraints.Builder().setRequiresBatteryNotLow(true)`
  (precedent: `scheduleDataCleanupWorker` at `:126-131`). **Behavioral** (delays syncs on low
  battery) — confirm with the maintainer before landing. Do NOT add charging/idle constraints.

### F23. Build-time settings — **Low, Effort S/M**

- **Location:** `gradle.properties`. (a) `org.gradle.parallel` is commented out — uncomment
  (configuration cache already on; modules are decoupled). (b) `android.nonTransitiveRClass=false`
  — flipping to `true` needs sweeping R-class import fixes; do as its own commit with a full build
  + `lintRelease`. Build-time only; zero runtime effect.

### N1 / N2 — correctness/style bugs found during the audit (fix OUTSIDE perf commits)

- **N1:** `core/ui/.../M3ScoreGaugeCard.kt:60` — `val isClickable = onClick != {}` compares against
  a freshly allocated lambda and is therefore **always true**: every gauge is treated as
  clickable. Fix separately by changing the signature to `onClick: (() -> Unit)? = null` and
  `isClickable = onClick != null`.
- **N2:** hardcoded `baselineUnit = "bpm"` / `"%"` at `VitalsScreen.kt:335/371` violate the
  strings.xml rule — move to string resources in a separate cleanup.

---

## 7. Implementation order (one commit each; run pre-commit checks every time)

| # | Item | Gate/dependency |
|---|---|---|
| 1 | M1 compose metrics + stability config | — (measurement first) |
| 2 | M2 frame benchmarks + `profileable`; record baseline numbers | — |
| 3 | F2 log sink rotation, then F2 DEBUG level (2 commits) | — |
| 4 | F4 Workouts sync split | — |
| 5 | F10 N+1 HR batch | after F4 (so the win isn't masked by pipeline restarts) |
| 6 | F9 Sleep split + `distinctUntilChanged` | — |
| 7 | F8 `@Immutable` annotations | before F5 |
| 8 | F1 sync-UX / CardLoader semantics (approved) | coordinate with F4/F9 flag fields |
| 9 | F5 Vitals section extraction + hoisted delta strings | after F8 |
| 10 | F3 axis formatter cache | measure with M2 journey (b) |
| 11 | F11 item placer cache | measure with M2 journey (b) |
| 12 | F15 zone band colors | — |
| 13 | F7 sync transaction coalescing (+ DATA_FLOW.md) | — |
| 14 | F12 DataStore pre-warm | measure with StartupBenchmark |
| 15 | F13 key validation off-main | after tracing the corruption path |
| 16 | F14 baseline profile | LAST perf item (captures final code) |
| 17 | Remainder per appetite: F17, F18, F19 (benchmark-gated), F20, F22 (confirm first), F23 | — |

N1/N2 land as separate non-perf fixes. Run `./gradlew lintRelease` after the batch. New files →
`codegraph index`.

## 8. Verification matrix

- **Frame pacing:** M2 journeys before/after each UI item — expect P90/P99 `frameDurationCpu`
  drops on (a)/(b) after F3/F11/F5/F1 and on (c) after F5/F8.
- **Recomposition:** Layout Inspector on Vitals during a foreground sync — after F1+F5,
  `TrendChart` recomposition count stays 0 on sync toggles; M1 reports show extracted sections
  skippable.
- **Startup:** `StartupBenchmark` before/after F12/F13/F14.
- **Sync cost:** temporary debug counter on DAO-flow emissions before/after F7 — a routine sync
  must trigger each observed query once, not once per day.
- **Behavior preservation:** `./gradlew testDebugUnitTest` per commit; new unit tests for F2
  (rotation/capacity/ordering), F7 (single transaction, identical rows), F10 (identical
  `WorkoutDisplayItem`s), F11 (identical placer values); manual passes: first-launch skeletons
  (F1), corrupt-key recovery (F13), diagnostic-log export decrypts and excludes DEBUG (F2), theme
  flash absent (F12).
- **Charts-always-composed guarantee:** manual scroll-back check on Vitals/Sleep/Workouts after
  every UI commit — charts must never visibly recreate.

## 9. Expected outcomes (honest estimates)

- **Chart pan/zoom (Vitals):** majority of draw-path allocations removed (F3/F11/F15) — clearly
  measurable P90/P99 improvement expected.
- **Resume/sync-time jank:** largely eliminated (F1/F4/F9/F7/F2).
- **Vertical scroll (Vitals):** improved via recomposition isolation (F5/F8) and possibly F19.
  Caveat: gains depend on how much of today's stutter is recomposition vs. inherent raster cost of
  three live charts — M2 quantifies this before/after.
- **Startup:** F12/F13/F14 combined are substantial; the single biggest startup item (every-launch
  baseline backfill) is owned by the architecture plan's SCORE-003.
- **Memory:** steady-state allocation churn drops on the draw path (F3/F11/F15/F20), in logging
  (F2: multi-MB per-flush strings/byte arrays gone), and in the Workouts pipeline (F10).
