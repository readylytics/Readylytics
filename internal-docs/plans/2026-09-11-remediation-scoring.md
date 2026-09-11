# Historical Scoring Input Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair historical membership, calibration, absence and input consistency without changing scoring coefficients.

**Architecture:** Keep pure scoring helpers and Room-backed orchestration. Carry immutable run configuration and explicit availability through day assembly; stage all derived writes for P2 atomic publication.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-11–15; SCORE-001–007. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

## Global Constraints

- "Do not use restricted experimental deduplication internals or require an SDK upgrade for this plan."
- "Transient failures propagate as typed errors/exceptions and cancellation remains cancellation."
- "SDK read/retry/enrichment happens outside Room transactions."
- "Never uninstall `app.readylytics.health` to solve a migration or signing conflict."
- Repository floors: minSdk=26, compile/targetSdk=37; Health Connect 1.1.0; current Room schema 19 at inspected HEAD `93c468b2`.
- Room is the UI source of truth; calculations remain pure Kotlin. Preserve current-day refresh, `RetentionBounds`, full-range session reconciliation, WorkManager `KEEP`, and existing progress channels.
- Preserve formulas, coefficients, profiles and sleep/source-selection policies. Decision-gated proposals below are not permission to change product semantics.
- Follow the index's documentation matrix, mandatory pre-commit commands, migration ledger, file limits (target ≤400, hard ≤800), and indexing rules. No new suppressions or baseline edits.

---

### Task C1: Bound historical RHR and fallback queries (WP-11)

**Files and responsibilities:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputer.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/RhrBaselineProvider.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/HrvBaselineProvider.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringHistoryRepositoryImpl.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/HistoricalRhrWindow.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/HistoricalRhrWindowTest.kt` — new behavior test
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/BaselineComputerBackfillEquivalenceTest.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/RhrBaselineProviderTest.kt`

**Interfaces:** Produces internal pure `historicalRhrWindow(days: List<HistoricalSleepDay>, scoreDay: LocalDate): List<HistoricalSleepDay>`. Current adaptive RHR includes D when eligible; HRV still excludes current core IDs. Do not replace both populations with the same selector. OD-5 unification is excluded.

- [ ] **Step 1: Add independent membership assertions.**

```kotlin
@Test
fun `RHR includes D but excludes future nights and expired history`() {
    val d = LocalDate.of(2026, 3, 29)
    val lower = d.minusDays(ScoringConstants.BASELINE_DAYS)
    fun night(day: LocalDate) = HistoricalSleepDay(day, listOf(day.toString()), 45f, 50f, 52, true)
    val input = listOf(night(lower.minusDays(1)), night(lower), night(d), night(d.plusDays(1)))
    assertEquals(listOf(lower, d), historicalRhrWindow(input, d).map { it.scoreDay })
}
```

In existing DAO-backed backfill equivalence fixture add sharply different future nights and compare D's RHR mean/sigma for batch [D] and [D,D+20]. Expected same values; use independent per-day bounded selectors, not two calls to the same batch helper. Repeat sparse HRV/RHR, D boundary and Europe/Berlin DST.

- [ ] **Step 2: Run targeted core scoring/database tests red.**

```bash
./gradlew :core:scoring:testDebugUnitTest --tests '*HistoricalRhrWindowTest' --tests '*RhrBaselineProviderTest'
./gradlew :core:database:testDebugUnitTest --tests '*BaselineComputerBackfillEquivalenceTest'
```

- [ ] **Step 3: Implement the selector and use it in both RHR backfill branches.**

```kotlin
internal fun historicalRhrWindow(days: List<HistoricalSleepDay>, scoreDay: LocalDate)
    : List<HistoricalSleepDay> {
    val first = scoreDay.minusDays(ScoringConstants.BASELINE_DAYS)
    return days.filter { it.scoreDay >= first && it.scoreDay <= scoreDay }
}
```

In `computeDayBackfillBaseline`, derive `rhrDays` once and use it for nadirs and percentile sigma history. Preserve `canContributeToBaseline` filtering exactly where live nadirs apply it; do not silently add that filter to the distinct display-percentile population. Keep median/default/stddev math unchanged. Apply the same selector in live `computeAdaptiveBaselineRhrBpmBetween`/RHR history assembly after resolving the requested score day so live and backfill share membership policy; keep their eligibility filters distinct. `priorSleepDays` for HRV remains strictly `< scoreDay`.

- [ ] **Step 4: Bound reachable fallback reads to the requested scoring date.**

Audit consumers with `rg 'RhrBaselineProvider|HrvBaselineProvider|getRoundedRhrBaseline|getPreciseRhrBaseline'`. Change reachable unbounded `getSince`-style fallbacks to the repository's explicit range methods with D's scoring-zone end. Do not use today's Clock or system zone. Remove a provider only if both DI and constructor/test consumer searches prove it unused; otherwise retain bounded adapter. Current-night inclusion follows the existing live contract, verified before altering it.

- [ ] **Step 5: Synchronize scoring documentation and commit.**

Update all four scoring documentation surfaces from the index, clarifying historical bounds and distinct RHR references. Run drift/presence tests and shared gates. Add repair intent through P2; do not thaw every frozen day on startup. Index new helper; message: `fix: bound historical resting heart rate inputs`.

### Task C2: Separate maturity from statistical windows (WP-12, OD-2 gate)

**Files and responsibilities:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeSleepMetricsUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeHistoricalBaselinesUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/SleepBaselineMetrics.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/AssembleDailySummaryUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/components/PhaseCalculator.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/CalibrationGate.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ScoringHistoryRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringHistoryRepositoryImpl.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/CalibrationStateResolver.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/CalibrationStateResolverTest.kt` — new behavior test
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeSleepMetricsUseCaseTest.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeHistoricalBaselinesUseCaseTest.kt`

**Interfaces:** Gate OD-2 before cumulative-history behavior. Produces `CalibrationState(observationCount: Int?, phase: Phase?, isCalibrating: Boolean)` and `resolveCalibrationState(liveCount: Int?, frozenCount: Int?, frozenPhase: Phase?): CalibrationState`. Unknown legacy metadata is explicit; no zero substituted for an unknown count. Persist existing `baselineObservationCount`/`snapshotCalibrationPhase`; add metadata only if these cannot encode unknown compatibly.

- [ ] **Step 1: Test actual phase boundaries and frozen replay.**

```kotlin
@Test
fun `phase boundaries preserve current constants`() {
    val expected = mapOf(6 to Phase.CALIBRATION, 7 to Phase.EARLY_BASELINE,
        20 to Phase.EARLY_BASELINE, 21 to Phase.MATURING,
        29 to Phase.MATURING, 30 to Phase.MATURING,
        59 to Phase.MATURING, 60 to Phase.MATURE)
    expected.forEach { (count, phase) ->
        assertEquals(phase, resolveCalibrationState(count, null, null).phase)
        assertEquals(count < 7, resolveCalibrationState(count, null, null).isCalibrating)
    }
    assertEquals(Phase.MATURE, resolveCalibrationState(0, 60, Phase.MATURE).phase)
}
```

Integration fixture: seven eligible days transition summary/diagnostic/flags from calibrating to calibrated. Invalid current night must not be counted just because a session exists. Recompute a frozen mature day twice with no live history loaded and assert identical count/phase/flags. Cover gaps and two sessions assigned to one score day (count once).

- [ ] **Step 2: Run calibration/sleep metrics/backfill tests red.**

- [ ] **Step 3: Implement explicit state resolution without changing thresholds.**

```kotlin
data class CalibrationState(val observationCount: Int?, val phase: Phase?, val isCalibrating: Boolean)
fun resolveCalibrationState(liveCount: Int?, frozenCount: Int?, frozenPhase: Phase?): CalibrationState {
    val frozen = frozenCount != null || frozenPhase != null
    val count = if (frozen) frozenCount else liveCount
    require(count == null || count >= 0)
    val derived = count?.let(PhaseCalculator::calculatePhase)
    if (frozenPhase != null && derived != null && frozenPhase != derived) {
        return CalibrationState(null, null, true)
    }
    val phase = frozenPhase ?: derived
    return CalibrationState(count, phase, phase == null || phase == Phase.CALIBRATION)
}
```

Pass `liveCount=null` for a frozen day lacking reliable count; preserve validated frozen phase and queue one metadata repair. Do not infer a trusted phase merely from a freeze timestamp. Detect incompatible frozen count/phase and treat metadata as needing repair, not as permission to mark mature. Set `isCalibrating` explicitly in every summary branch, including `AssembleDailySummaryUseCase.assembleCalibrated`, and pass identical state into diagnostics/config/flags.

- [ ] **Step 4: Supply distinct cumulative eligible-day counts.**

Implement a bounded chronological scan of eligible sleep-day summaries through D under the existing `validateNight` policy, deduplicating canonical score-day IDs. Add a domain port `suspend fun countEligibleSleepDaysThrough(endDay: LocalDate, zoneId: ZoneId): Int?`; unknown reconstruction returns null. Keep HRV mu/sigma windows unchanged; their size cannot serve as maturity count. Include the current day only when it satisfies existing eligibility and as-of/morning visibility. A retained frozen count is evidence; missing older raw history is not evidence for inventing observations.

Thread this count through live, backfill, morning `CalibrationGate`, `SleepBaselineMetrics.resolveCalibrationSnapshots`, and frozen `resolveBaselineWindow`. Scope the scan/cache to H6 run generation to avoid future leakage. Record OD-2's chosen gaps/retention semantics in all documentation surfaces before enabling repair.

- [ ] **Step 5: Verify counts/flags and commit.**

Run pure and database integration tests, documentation drift/presence suites, shared checks and index. Message: `fix: preserve calibrated phase across frozen replay`. Do not claim threshold changes; the existing 20/21 transition is preserved.

### Task C3: Publish authoritative absence without mixed generations (WP-13)

**Files and responsibilities:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/BaseSummaryAssembler.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ReadinessSummaryCoordinator.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DirtySummaryPublisher.kt` — created by an earlier task in this set
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/DayAssembly.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/FreshDaySummary.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/FreshDaySummaryTest.kt` — new behavior test
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImplTest.kt`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DirtyMutationRecoveryInstrumentedTest.kt` — new behavior test

**Interfaces:** Produces:

```kotlin
sealed interface DayAssembly {
    data class Computed(val summary: DailySummary) : DayAssembly
    data class Absent(val summary: DailySummary) : DayAssembly
    data class Unavailable(val reason: String) : DayAssembly
}
```

`reason` contains a fixed internal safe reason code. Absent means confirmed missing required source input in Room at the captured authoritative generation; denied HC does not remove local input. Computed/Absent publish full derived candidates; Unavailable preserves the previous complete day and dirty ticket.

- [ ] **Step 1: Test deletion and failure independently.**

```kotlin
@Test
fun `fresh day does not retain deleted sleep`() {
    val previous = DailySummary(date = LocalDate.of(2026, 1, 1), sleepScore = 90f,
        sleepDurationMinutes = 480, nocturnalHrv = 55)
    val fresh = freshDaySummary(previous.date, previous)
    assertNull(fresh.sleepScore)
    assertNull(fresh.sleepDurationMinutes)
    assertNull(fresh.nocturnalHrv)
}
```

In `ScoringRepositoryImplTest`, delete the only sleep on a calibrated date, compute/publish twice and assert no old sleep/restoration/readiness/recommendation payload. Inject a failure in each base/readiness/final/recommendation stage and verify old complete summary, canonical workout values and dirty ticket remain unchanged.

- [ ] **Step 2: Run fresh-day and repository fixtures red.**

- [ ] **Step 3: Build a fresh candidate from owned inputs, not old derived fields.**

```kotlin
fun freshDaySummary(date: LocalDate, previous: DailySummary?): DailySummary =
    DailySummary(date = date).copy(
        stepCount = previous?.stepCount,
        baselineCalculatedAtDate = previous?.baselineCalculatedAtDate,
        hrMax = previous?.hrMax,
        snapshotProfile = previous?.snapshotProfile,
        snapshotCalibrationPhase = previous?.snapshotCalibrationPhase,
        hrvSigmaPrior = previous?.hrvSigmaPrior,
        rasScalingFactor = previous?.rasScalingFactor,
        baselineObservationCount = previous?.baselineObservationCount,
        hrvMuMssd = previous?.hrvMuMssd,
        hrvSigmaMssd = previous?.hrvSigmaMssd,
        rhrBpm = previous?.rhrBpm,
        rhrSigma = previous?.rhrSigma,
    )
```

Copy frozen fields only when P2/H6 declares that snapshot still valid; a source deletion invalidating those inputs supplies no frozen previous metadata. Rebuild body metrics/load independently. On absent sleep, explicitly produce no-data sleep-related diagnostics/contributions and clear recommendation payload; do not clear valid independent steps/vitals/load. Resource-based availability state belongs in domain/UI state, not raw exception text.

- [ ] **Step 4: Carry assembly status into atomic publication.**

Replace `assemble(...) ?: previous` in recommendation application with explicit statuses. No candidate writes before complete day assembly. `DirtySummaryPublisher` accepts a candidate plus staged workout updates, and only Computed/Absent may enter its generation-checked transaction. Unavailable leaves the entire prior complete day, not a new load with old readiness. Successful absence advances dirty work; failure does not. Update walk-forward contexts after commit only.

- [ ] **Step 5: Verify rollback/read-your-writes, documentation and commit.**

Run deletion twice, transient failure at every assembler boundary, cancellation after staging, and P2 real transaction publication tests. DATA_FLOW documents owned/copied fields and status semantics; update About/tooltips where no-data explanation changes. Shared gates/index; message: `fix: clear absent daily outputs atomically`.

### Task C4: Separate core recovery from total sleep (WP-14)

**Files and responsibilities:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ReadinessSummaryCoordinator.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepModifierResolver.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepNadirAnalyzer.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeSleepMetricsUseCase.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/SleepSessionRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/SleepSessionRepositoryImpl.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SleepStageDao.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/CoreRecoveryInput.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/CoreRecoveryInputTest.kt` — new behavior test
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepModifierResolverTest.kt`
- Create: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/BiphasicRecoveryScopeTest.kt` — new behavior test

**Interfaces:** `CoreRecoveryInput` owns current canonical core IDs/window and offset evidence, distinct from whole-day duration/architecture. Add `suspend fun getSessionStages(sessionIds: List<String>): List<SleepStageData>` beside the existing single-ID port; implement a bounded multi-ID DAO query.

```kotlin
data class CoreRecoveryInput(
    val sessionIds: Set<String>,
    val window: RecoveryWindow,
    val endZoneOffsetSeconds: Int?,
    val previousCoreEndZoneOffsetSeconds: Int?,
)
```

- [ ] **Step 1: Add nap/core equivalence fixtures.**

Use existing `SleepDayAggregator` fixtures for a 00:00–06:00 core and a 15:00–16:00 supplemental nap. Fixed HR nadir time/core stages must yield identical core nadir/restoration/fragmentation with/without nap, while whole-day duration increases by 60 minutes. Split the core into allowed merge segments with stages on both and compare with equivalent merged core. Include overlap canonicalization, stage-less nights and timezone offset change.

```kotlin
assertEquals(withoutNap.recoveryWindow, withNap.recoveryWindow)
assertEquals(withoutNap.totalDurationMinutes + 60, withNap.totalDurationMinutes)
```

Bind these aggregates from the fixture before checking `SleepNadirAnalyzer`/modifier outputs; aggregate equality alone does not test the repaired call path.

- [ ] **Step 2: Run core-input/modifier/biphasic tests red.**

- [ ] **Step 3: Thread explicit core input through recovery computation.**

Build `CoreRecoveryInput` from `aggregate.coreCluster.segments` and `aggregate.recoveryWindow`, not the synthetic total-duration session. Keep totalDurationMinutes/architectureTotals for duration/architecture score. For nadir, pass core start and coreSleepDurationMinutes to the unchanged `ScoringCalculator.isLateNadir` formula; preserve the existing sleep-duration convention. Use core offsets for timezone-jump suppression.

```kotlin
val late = minHrTimestamp != null && scoringCalculator.isLateNadir(
    minHrTimestamp, core.window.startTimeMs, core.window.coreSleepDurationMinutes,
)
```

`SleepModifierResolver` fetches all canonical core IDs, deduplicates stage intervals by session/type/start/end and orders by start/end/stable ID before existing fragmentation logic. Clip/canonicalize overlaps according to existing sleep policy; do not count supplemental stage rows or invent a new gap threshold.

- [ ] **Step 4: Preserve previous-core offset evidence on frozen replay.**

Frozen baseline optimization must not erase historical sessions needed solely to identify previous core offset. Fetch the nearest prior canonical core ending before current core independently of baseline statistics, with stable ties and as-of bounds; pass its offset explicitly. No schema is needed because original session offsets already persist. Never use later naps as prior-core travel evidence.

- [ ] **Step 5: Run reference fixtures and synchronized docs, then commit.**

Use all four scoring doc surfaces and drift tests; keep profile weights/nap cutoff/core merge unchanged. Run shared gates/index; message: `fix: isolate core sleep recovery inputs`.

### Task C5: Canonical workout input/result across all consumers (WP-15, OD-3 gate)

**Files and responsibilities:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeDailyTrimpUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutLoadMetricsUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DailyTrimpComputer.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ResidualFatigueComputer.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WorkoutRepository.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/WorkoutRecordEntity.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/CanonicalWorkoutResult.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/CanonicalWorkoutResolver.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration20To21.kt`
- Create: `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/21.json`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/CanonicalWorkoutResolverTest.kt` — new behavior test
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/WorkoutModelTrimpIngestionDeterminismTest.kt`

**Interfaces:** Gate OD-3 missing-HR policy. New result metadata:

```kotlin
enum class WorkoutHrQuality { RAW, WARM_APPROXIMATE, VALIDATED_PRIOR, UNAVAILABLE }
data class CanonicalWorkoutResult(
    val workoutId: String, val endTimeMs: Long, val trimp: Float?,
    val quality: WorkoutHrQuality, val sourceRevision: Long,
    val scoringSnapshotId: String, val algorithmRevision: Int,
)
```

`CanonicalWorkoutResolver.resolve(input: CanonicalWorkoutInput): CanonicalWorkoutResult` wraps existing `ComputeWorkoutTrimpUseCase`. Define this input in `CanonicalWorkoutResolver.kt`; it has no independent formula coefficients:

```kotlin
data class WorkoutScoringIdentity(
    val sourceRevision: Long, val scoringSnapshotId: String, val algorithmRevision: Int,
)
data class WorkoutScoringContext(
    val prefs: UserPreferences, val rhrBaseline: Float, val frozenHrMax: Float,
    val identity: WorkoutScoringIdentity,
)
data class CanonicalWorkoutInput(
    val workoutId: String, val startMs: Long, val endMs: Long,
    val samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
    val quality: WorkoutHrQuality, val context: WorkoutScoringContext,
    val prior: CanonicalWorkoutResult?,
)
```

- [ ] **Step 1: Test all existing model enums against an independent shared-input reference.**

For every supported TRIMP model, use fixed workout [08:00,09:00], actual samples [120,140,160], stored avgHr=180, frozen hrMax=190 and current preference maxHr=210. Daily sum/card/detail/examples/fatigue must all use the same canonical result and ignore the conflicting stored/current values. Warm inputs preserve measured approximation quality. Zero-duration result must be finite under existing formula rules.

For no samples and no matching prior, assert `trimp==null`/UNAVAILABLE, not zero or legacy zone TRIMP. Matching prior with all revision keys equal is allowed under OD-3; source or settings mismatch rejects reuse.

```kotlin
assertEquals(canonical.trimp, display.preciseTrimp)
assertEquals(canonical.trimp, daily.canonicalWorkoutTrimps.single().trimp)
assertEquals(canonical.trimp, fatigueImpulses.single().trimp)
```

Each variable is an actual output of its existing consumer in the integration fixture, fed the same fixed canonical result. Exercise stale metadata and changed settings as separate cases.

- [ ] **Step 2: Run resolver/store/display tests red.**

- [ ] **Step 3: Implement the shared resolver using existing math.**

For nonempty valid raw/warm samples derive the exact same average/sample collection as daily scoring currently uses, invoke `ComputeWorkoutTrimpUseCase.execute` with frozen hrMax and captured preferences/RHR, then package getOrNull() into canonical metadata. On missing input validate every prior key before reuse; otherwise return unavailable. Cancellation/failure cannot silently select a different TRIMP model.

The scalar `modelTrimp` alone cannot prove revision/config/quality. Add nullable `modelTrimpSourceRevision`, `modelTrimpSnapshotId`, `modelTrimpAlgorithmRevision`, `modelTrimpQuality` columns in `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration20To21.kt`, with default null; register schema 21 and export `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/21.json`. Update domain mapper, DAO projection, exported schema and backup serializer/decoder together. Old rows remain unvalidated; don't relabel them as canonical automatically.

- [ ] **Step 4: Make consumers read the canonical result and stage publication.**

`ComputeDailyTrimpUseCase` delegates per workout to the resolver and stages scalar+metadata updates. `ComputeWorkoutLoadMetricsUseCase` receives canonical result instead of recomputing from stored avgHr; propagate unavailable to its nullable display metrics/classification instead of a numeric fallback. `GetWorkoutDisplayMetricsUseCase` loads the matching frozen result/config. Recommendation examples and both morning/end-of-day fatigue paths consume the same canonical impulses; remove `COALESCE(modelTrimp, trimp)` fallback where it masquerades as the selected model.

A day with unresolved canonical contribution is unavailable for dependent outputs, preserving its prior complete published generation and dirty work under C3. No partial sum may claim complete zero load. Recompute changed workout→load→fatigue retained suffix through P2; retain existing infinite-tail fatigue math.

- [ ] **Step 5: Validate all surfaces, metadata upgrade and docs; commit.**

Run all models, hrMax changes, raw/warm/missing HR, invalid prior, zero-duration and source correction. Update all scoring doc surfaces, backup/schema docs and resource no-data labels. Run shared/migration gates. T3 rechecks same-tier agreement before release. Message: `fix: share canonical workout scoring inputs`.

### Task C6: Fix VO2 exclusive boundary and retain step intervals (WP-15, OD-6 branch)

**Files and responsibilities:**
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/Vo2MaxRecordDao.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardVo2MaxContext.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/StepsMapper.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/StepCountFetcher.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/sync/mappers/StepsMapperTest.kt`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/Vo2BoundaryInstrumentedTest.kt` — new behavior test

**Interfaces:** Rename DAO window upper parameter to `endExclusiveMs`; window is `[minTimestampMs,endExclusiveMs)`, ordered timestamp DESC then id DESC. `StepsMapper.StepEntry` gains `endTimeMs: Long`; start-day policy remains gated OD-6. Existing all-origin HC aggregate path stays authoritative in its supported mode.

- [ ] **Step 1: Test next-midnight exclusion and deterministic ties.**

Seed VO2 at D end−1ms, next midnight, and two rows sharing end−1ms with IDs a/b. Query/prefetch for D must select b and exclude next-midnight record. Repeat Berlin 23/25-hour days and the exact elapsed 30-day lower boundary. No `end−1` API convention.

```sql
SELECT * FROM vo2_max_records
WHERE timestampMs >= :minTimestampMs AND timestampMs < :endExclusiveMs
ORDER BY timestampMs DESC, id DESC LIMIT 1;
```

- [ ] **Step 2: Run VO2 instrumented/prefetch fixtures red, then replace inclusive upper predicates with this SQL and equivalent in-memory predicates.**

Keep the broad `getLatestUpTo` semantics for unrelated callers unless those callers pass a day end; migrate day-window callers to the explicit window method. Update WalkForwardVo2MaxContext tie ordering identically. Preserve elapsed 30-day lower window while OD-6 calendar choice is unresolved.

- [ ] **Step 3: Add a cross-midnight steps fixture before changing mapping.**

```kotlin
@Test
fun `selected steps preserve the interval and conserve count`() {
    val start = Instant.parse("2026-03-28T22:50:00Z")
    val end = Instant.parse("2026-03-28T23:10:00Z")
    val records = listOf(DomainStepsRecord("steps", start, end, 200, "watch"))
    val entries = StepsMapper.toStepEntries(records)
    assertEquals(end.toEpochMilli(), entries.single().endTimeMs)
    val totals = StepsMapper.sumByDay(entries, ZoneId.of("Europe/Berlin"))
    assertEquals(200L, totals.values.sum())
    assertEquals(200L, totals[LocalDate.of(2026, 3, 28)])
}
```

The attribution assertion executes only after OD-6 accepts start-day policy; endpoint preservation/conservation can proceed independently. Cover zero-duration, exact-midnight start/end, DST, multi-day interval and overlap reads without counting an ID twice.

- [ ] **Step 4: Preserve end time and explicit empty/unavailable totals.**

Map `endTimeMs=record.endTime.toEpochMilli()` and retain existing start-day `sumByDay` under the accepted policy. Fetch overlapping interval records but assign each stable ID only once to its start day and clip published totals to requested days. Available empty selected-device days produce zero through H2; unavailable stays absent/no-update. Add resource copy explaining interval-level approximation only where the user sees selected-device attribution.

- [ ] **Step 5: Run boundary/scoring/docs checks and commit.**

Update DATA_FLOW and the scoring/public data explanation surfaces; shared gates. Message: `fix: respect scoring day data boundaries`.
