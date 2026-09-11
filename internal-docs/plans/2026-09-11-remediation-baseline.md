# Phase 0 Baseline and Safety Rails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture reproducible correctness failures and current pipeline costs before changing production behavior.

**Architecture:** Reuse existing sync, scoring, database and backup fixtures. Separate intended reference assertions from current broken outputs, and extend benchmark data through the actual current-schema ingestion/scoring path.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, §9 Phase 0; WP-01; §§7, 11. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

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

### Task B1: Establish the correctness evidence inventory

**Files and responsibilities:**
- Modify: `benchmark/BASELINE.md` — Record commands, current HEAD and known failure evidence
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStoreTest.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/BaselineComputerBackfillEquivalenceTest.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncCheckpointResumeTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreValidationTest.kt`

**Interfaces:** Consumes current test fixtures and existing `persistHeartRateSamples(List<HeartRateInput>)`, `computeBackfillBaselines`, `ResyncCheckpoint`, `LocalRestoreManager.validate/applyRestore`. Produces failure evidence keyed by finding ID and a corrected-reference assertion in the owning repair task; no new production interface.

- [ ] **Step 1: Record a clean baseline without modifying production code.**

```bash
git status --short
git rev-parse HEAD
./gradlew :core:model:testDebugUnitTest :core:scoring:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:database:testDebugUnitTest :app:testDebugUnitTest :feature:settings:testDebugUnitTest
```

Expected: real test results recorded by module. Distinguish pre-existing failures/toolchain blockers from a regression. Do not say the audit already ran these checks.

- [ ] **Step 2: Add the first HC-001 regression to the existing store fixture.**

```kotlin
@Test
fun `same source and timestamp replaces bpm`() = runTest {
    val sample = HeartRateInput("source_1000", 1000L, 60, "RESTING", null, "watch")
    store.persistHeartRateSamples(listOf(sample))
    store.persistHeartRateSamples(listOf(sample.copy(beatsPerMinute = 81)))
    assertEquals(81, database.heartRateDao().getByTimeRange(1000L, 1001L).single().beatsPerMinute)
}
```

Use the fixture's existing `store`/`database`; don't add a mock DAO. HRV partner case changes `rmssdMs` from 40f to 64f at the identical key in its corresponding fixture.

- [ ] **Step 3: Run the regression and retain its failure for P3.**

```bash
./gradlew :core:database:testDebugUnitTest --tests '*RoomHealthIngestionStoreTest'
```

Expected on inspected HEAD: intended BPM 81 versus retained BPM 60. Keep the unmerged failing test with the P3 repair. Baseline evidence records the assertion and result; a passing main branch is not traded for a red characterization commit.

- [ ] **Step 4: Build the following reproduction inventory using the exact child-plan tests.**

| Findings | Owning task and assertion / failure injection |
|---|---|
| HC-001, DB-001 | P3: same ID numeric edit, remove one/all samples, timestamp move, opaque underscore ID; compare clean import and source FK |
| HC-002, HC-005 | H1/H6: delete before baseline token; interrupt HR and HRV pages independently; retry across midnight/DST/config changes |
| HC-003 | H2: grant→revoke→repeat→regrant, denied after first page; retain totals and suppress pruning |
| HC-004, HC-006 | H3–H5: historical VO2 delete, independent interval edits, unreadable route, assertion that provider is outside transaction |
| DB-002 | T1–T3: cutoff inside a minute, reimport warm history, rolled-source delete/relink, interrupted publication |
| CACHE-001/002, ARCH-001 | P2/C3/R3: kill after raw deletion and before scoring/token/enqueue; retained suffix on HR-absent history; restore concurrent with scoring |
| SCORE-001/006 | C1: future nights and wider batch do not alter D; live versus backfill and fixed-zone DST |
| SCORE-002/003 | C2/C3: 6/7, 29/30, 59/60 eligible observations; frozen replay, deleted only sleep, assembler failure |
| SCORE-004/005/007 | C4–C6: supplemental nap, split core/stages, all TRIMP models/frozen hrMax, next-midnight VO2 and steps intervals |
| SEC-001 | S1: synthetic private payload absent at every release sink including nested exception messages |
| SEC-002/003/004/005 | S2/S3/R1–R3: incomplete inventory, count/FK mismatch, interrupted rotation/export/restore, failure with one old backup |
| PERF-001/002/003 | B2: parent distribution distinct from sample count, real store/scoring, rollup and export stage costs |

These are specific behavioral fixtures, not a broad test-coverage initiative. Implement each red fixture immediately before its linked repair; B1 closes only when its reproduction evidence is recorded, not because a test file name exists.

- [ ] **Step 5: Record the baseline and commit the green evidence-only change.**

Add this evidence format to `benchmark/BASELINE.md`; fill measurements from actual output and write `not run: <specific reason>` when inaccessible:

```text
Finding | HEAD | command/test | intended invariant | observed outcome | repair task
HC-001 | 93c468b2 | RoomHealthIngestionStoreTest | replacement BPM=81 | assertion output from local run | P3
```

Keep failing tests on their repair branch. Run the shared commit checks for code-bearing commits. Suggested message: `test: characterize health data repair failures`.

### Task B2: Measure current-schema parent and sample distributions

**Files and responsibilities:**
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/databasebenchmark/data/migration/DatabaseBenchmarkFixture.kt` — Preserve v6/v7 migration fixtures; add a separate current-schema entry point
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/ScoringWalkForwardBenchmark.kt` — Exercise dense current store and actual scoring with existing constructor wiring
- Modify: `benchmark/BASELINE.md` — Reproducible measurements and numeric optimization budgets
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthParentFixture.kt` — Lazy synthetic parent generator independent of Android
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthPipelineBaselineBenchmark.kt` — Device benchmark around real current-schema pipeline

**Interfaces:** Consumes existing `DomainHeartRateRecord`, `DomainHeartRateSample`, current `RoomHealthIngestionStore` and `ScoringRepositoryImpl`. Produces `HealthParentFixture.pages(parentCount: Int, samplesPerParent: Int, pageSize: Int): Sequence<List<DomainHeartRateRecord>>` and measured baseline reports. Benchmark fixture files stay within 400 lines; extract current-schema setup instead of changing the v7 migration driver to pretend it supports v19.

- [ ] **Step 1: Define independent datasets and counts before measuring.**

```kotlin
object HealthParentFixture {
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private val start = java.time.Instant.parse("2026-01-01T00:00:00Z")

    fun pages(parentCount: Int, samplesPerParent: Int, pageSize: Int)
        : Sequence<List<DomainHeartRateRecord>> {
        require(parentCount > 0 && samplesPerParent > 0 && pageSize > 0)
        val slots = parentCount.toLong() * samplesPerParent
        require(slots <= WINDOW_MS)
        return (0 until parentCount).asSequence().map { parent ->
            DomainHeartRateRecord(
                id = "fixture_source_$parent",
                deviceName = "fixture-origin-${parent % 3}",
                samples = List(samplesPerParent) { sample ->
                    val offset = (parent.toLong() * samplesPerParent + sample) * WINDOW_MS / slots
                    DomainHeartRateSample(start.plusMillis(offset), 50 + (parent + sample) % 100)
                },
            )
        }.chunked(pageSize)
    }
}
```

Use imports from `core.model.domain.model`. Validate two shapes with assertions in the benchmark before timing:

```kotlin
val parents = HealthParentFixture.pages(1_000_001, 1, 1000)
assertEquals(1_000_001, parents.sumOf { it.size })
val dense = HealthParentFixture.pages(1001, 1000, 100)
assertEquals(1_001_000, dense.sumOf { page -> page.sumOf { it.samples.size } })
```

- [ ] **Step 2: Build and run the existing benchmark first.**

```bash
./gradlew :database-benchmark:tasks --all
./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.ScoringWalkForwardBenchmark
```

Use the `.macrobenchmark` app variant and dedicated named SQLCipher database. Keep seeding and assertion reads outside `measureRepeated`; run destructive benchmark repetitions on fresh fixture copies. Never uninstall production to resolve signing. An unavailable device blocks measurement completion, not fixture preparation.

- [ ] **Step 3: Feed generated pages through the current ingestion adapter/store.**

In `HealthPipelineBaselineBenchmark`, reuse the real dependency construction in `ScoringWalkForwardBenchmark`, feed parent pages through `HeartRateMapper` and `HealthIngestionCoordinator`, and keep complete-record boundaries. Seed real sleep sessions/stages/HRV/workouts so `computeAndPersistDailySummary` traverses calibrated scoring, not only a sparse no-data branch. Record separate spans with monotonic time:

```kotlin
suspend fun <T> measured(block: suspend () -> T): Pair<T, Long> {
    val started = android.os.SystemClock.elapsedRealtimeNanos()
    val result = block()
    return result to (android.os.SystemClock.elapsedRealtimeNanos() - started)
}
```

Measure provider-fake read, mapping, store, full-range relink, chronological scoring, rollup, and backup export separately. The fake measures adapter/CPU cost; real HC IPC is a separately labelled provider spot-check. Do not time SQL inserts and label them the ingestion pipeline.

- [ ] **Step 4: Verify outputs outside timing and run the dataset matrix.**

Run fresh import; identical replay twice; edited values; moved/deleted parent; HR/HRV page interruption; 30-day dense bursts inside 1/3/10-year sparse histories; and local dates older than the resync horizon with cleanup disabled. For each, compare ordered source/row/summary checksums under the same zone/config/tier. Use synthetic-only reference snapshots; don't export personal data into test artifacts.

Collect parent/sample counts, transactions/statements/changed rows, elapsed CPU/wall time, peak heap/PSS, allocation rate, WAL/database sizes, writer lock time and job duration. Instrument `TransactionRunner` and Room query callback with counters only; never log bound SQL values or source IDs. Record 500-sample writes and 5,000-row reconciliation as baseline settings, not proven optimal values.

- [ ] **Step 5: Publish reproducible results and a green benchmark commit.**

`benchmark/BASELINE.md` must state device model/API/provider/build/thermal/compilation conditions, date/HEAD, schema, seed, source/config/tier checksums, repetitions, median/tail values, and each unmeasured stage. Choose later regression budgets from those results; no invented universal latency/memory threshold. Keep existing old measurements labelled historical. Run shared checks, `codegraph index`, and `codegraph sync` if extracting fixture setup. Suggested message: `test: baseline current health data pipeline`.
