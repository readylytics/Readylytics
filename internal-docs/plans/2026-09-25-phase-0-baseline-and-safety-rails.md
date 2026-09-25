# Phase 0 — Baseline and Safety Rails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the recorded before-measurements and the documentation guard that every later remediation phase is judged against, changing no production behavior.

**Architecture:** Two independent strands. Strand A is a JVM unit test in `:app` that fails the build when `internal-docs/DATA_FLOW.md` cites a source path that does not exist — plus the corrections that make it green. Strand B extends the existing instrumented `:database-benchmark` module (`com.android.test`, SQLCipher-backed, real Room v23 schema) with scale-point fixtures, query-plan capture, result-set-size instrumentation, and six measurements, all appended to `benchmark/BASELINE.md` as one dated section.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Room 2.8.5, SQLCipher 4.19.0, `androidx.benchmark.junit4` (`BenchmarkRule`, `measureRepeated`), JUnit4 + `kotlin.test`, `com.android.test` module with `targetProjectPath = ":app"`.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` — §9 Phase 0, work packages WP-01 and WP-02, success criteria §7.3, measurement list §11. Read §7.3 and §11 before starting; this plan implements their "before" half.

---

## Global Constraints

- **Plan location deviates from the skill default deliberately.** The skill's default is `docs/superpowers/plans/`. `.gitignore:53` excludes `docs/superpowers/` entirely, so a plan there cannot be committed or reviewed. This plan and its spec live in `internal-docs/plans/`, which is tracked.
- **No production behavior changes in Phase 0.** Only `:database-benchmark` sources, one new `:app` unit test, `internal-docs/DATA_FLOW.md` path corrections, and `benchmark/BASELINE.md`. If a task tempts you to edit `:core:*` or `:feature:*` production code, stop — it belongs to a later phase.
- **A connected device or emulator is required** for every Strand B task. `:database-benchmark` is a `com.android.test` module whose `debug` variant is disabled (`androidComponents { beforeVariants(selector().withBuildType("debug")) { it.enable = false } }`), so the only runnable variant is `benchmark`. Command: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest`.
- **`:database-benchmark` sources live in `src/main/kotlin`**, not `src/test` or `src/androidTest` — that is how `com.android.test` modules are laid out. New files go in `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/`.
- **`:database-benchmark` applies ktlint only, not detekt** (see its `build.gradle.kts` `plugins` block). Its gate is `./gradlew :database-benchmark:ktlintCheck`. The `:app` test in Strand A is covered by the full repo gate.
- **Mandatory pre-commit gate** (from `.claude/CLAUDE.md`), run before every commit that touches a detekt-covered module:
  `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
- **Never add a detekt baseline entry or `@Suppress`.** If a new file trips a detekt rule, restructure it.
- **Run `codegraph index` after any task that creates a file**, per `.claude/CLAUDE.md`'s File Lifecycle rule.
- **`benchmark/BASELINE.md` is append-only.** Its own header says: *"Do not overwrite this entry — instead, append a new dated section after each relevant item lands."* Every measurement in this plan lands in **one** new dated section, appended at the end of the file.
- **Never log health values, record ids, device names, or bound SQL arguments** from benchmark instrumentation. The existing `CountingQueryCallback` deliberately counts without recording `sqlQuery` or `bindArgs`; keep that property.
- **Fixture determinism.** `HealthParentFixture` is seeded purely by parent/sample index — no randomness. Every new fixture must keep that property so a rerun is byte-identical.

---

## Review Focus

Five failure modes the spec implies but that no measurement task would otherwise exercise. Each line's test is added to the task that owns the code.

1. **No device attached.** `connectedBenchmarkAndroidTest` with no device passes vacuously in some CI shapes — a green build would then be read as "measurements recorded" when nothing ran. Task 11 asserts every expected metric key is present in the recorded section rather than trusting the build result.
2. **`.kt` tokens in illustrative prose.** `DATA_FLOW.md` contains code blocks and abbreviated paths (35 of its 268 backticked `.kt` tokens use `...` elision). A naive existence check would fail on notation, not on drift. Task 1 restricts the strict check to repo-rooted, non-elided tokens and pins that restriction with its own test.
3. **1M-row fixture exhausting device storage or the instrumentation timeout.** A 1M-row SQLCipher database plus a template copy per iteration can exceed both. Task 3 measures and asserts the on-disk template size before any benchmark depends on it, so the failure is a clear assertion rather than an opaque timeout.
4. **`QueryCallback` cannot observe result-set size.** It receives SQL and bind args only. Assuming otherwise would silently produce a "max result set" number that is actually a statement count. Task 4 records sizes at DAO call sites and asserts the two instruments disagree on a fixture where they must.
5. **An overwritten `BASELINE.md`.** The file's convention is append-only, and a careless edit loses the July 2026 reference numbers. Task 11 asserts the pre-existing `## M2 Initial Baseline` heading still exists after the append.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt` | **Create.** Fails the build when `DATA_FLOW.md` cites a non-existent source path. Two checks: strict repo-rooted, and module-relative suffix resolution. |
| `internal-docs/DATA_FLOW.md` | **Modify.** Correct 7 stale repo-rooted paths and 3 dead module-relative paths. |
| `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineScalePoints.kt` | **Create.** The three scale points (250k/500k/1M) as named constants plus the fixture-shape helpers that build them, so every measurement task uses identical inputs. |
| `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/QueryPlanRecorder.kt` | **Create.** Runs `EXPLAIN QUERY PLAN` against a live SQLCipher `HealthDatabase` and returns the plan rows as text. |
| `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/MaxResultSizeRecorder.kt` | **Create.** Records the largest list size returned by an instrumented DAO call, which `RoomDatabase.QueryCallback` structurally cannot observe. |
| `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/ChangesPageFixture.kt` | **Create.** The `CHANGES-1K` fixture: 1,000 upsertions + 200 deletions, used to size `DEFAULT_CHANGES_APPLY_BUDGET_MS` (spec OD-6). |
| `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt` | **Create.** The six Phase 0 measurements, each emitting one `Phase0Metrics` log line. Separate from `HealthPipelineBaselineBenchmark` so the existing stage measurements stay untouched. |
| `benchmark/BASELINE.md` | **Modify (append only).** One new dated section carrying every recorded number. |

New files are deliberately small and single-purpose: the recorders are reused by several measurements, and keeping them out of the benchmark class stops `Phase0BaselineBenchmark` from growing past the repo's 400-line target.

---

## Task 1: DATA_FLOW path-reference guard (repo-rooted paths)

**Files:**
- Create: `app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt`
- Modify: `internal-docs/DATA_FLOW.md` (7 stale paths)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `DataFlowPathReferenceTest.readRepoFile(pathFromRepoRoot: String): String` — private; Task 2 extends the same class rather than duplicating the helper.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt`. The `readRepoFile` helper is copied verbatim from `WorkoutRecommendationDocumentationDriftTest` — unit tests in this repo run with the working directory at either the repo root or a module directory depending on invocation, so the three-candidate probe is required.

```kotlin
package app.readylytics.health.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `.claude/CLAUDE.md` treats a stale `internal-docs/DATA_FLOW.md` as a broken build. That rule is
 * only enforceable if drift is detectable, and the most common drift — a file moved to another
 * module while the doc keeps citing its old path — is mechanically checkable. This test fails when
 * DATA_FLOW.md cites a repo-rooted source path that does not exist.
 *
 * Deliberately restricted to **repo-rooted, non-elided** tokens. DATA_FLOW.md also uses two other
 * notations that are not drift: abbreviated paths containing `...` (e.g.
 * `core/healthconnect/.../domain/sync/VitalsInputMapper.kt`), and module-relative short forms
 * (e.g. `ui/sync/SyncViewModel.kt`). The first is unresolvable by construction; the second is
 * covered by [moduleRelativePathsResolve] in Task 2.
 */
class DataFlowPathReferenceTest {
    private val dataFlowMd = readRepoFile("internal-docs/DATA_FLOW.md")

    /** Top-level directories that make a path unambiguously repo-rooted. */
    private val repoRootedPrefixes =
        listOf("app/", "core/", "feature/", "build-logic/", "buildSrc/", "benchmark/", "database-benchmark/")

    private val backtickedKotlinPaths: List<String>
        get() = Regex("`([A-Za-z0-9_\\-./]+\\.kt)`").findAll(dataFlowMd).map { it.groupValues[1] }.distinct().toList()

    @Test
    fun `repo rooted kotlin paths in DATA_FLOW exist`() {
        val checked =
            backtickedKotlinPaths
                .filter { path -> repoRootedPrefixes.any { path.startsWith(it) } }
                .filterNot { it.contains("...") }
        assertTrue(checked.size > 100, "expected DATA_FLOW.md to cite many repo-rooted paths, found ${checked.size}")

        val missing = checked.filterNot { repoFileExists(it) }.sorted()
        assertTrue(
            missing.isEmpty(),
            "DATA_FLOW.md cites ${missing.size} source path(s) that do not exist:\n" +
                missing.joinToString("\n") { "  $it" },
        )
    }

    /** Guards Review Focus item 2: elided and module-relative notations must not enter the strict check. */
    @Test
    fun `strict check ignores elided and module relative notations`() {
        val elided = backtickedKotlinPaths.filter { it.contains("...") }
        assertTrue(elided.isNotEmpty(), "fixture assumption broken: DATA_FLOW.md no longer uses elided paths")
        assertTrue(
            elided.none { path -> repoRootedPrefixes.any { path.startsWith(it) } && !path.contains("...") },
            "elided paths must never reach the strict existence check",
        )
        val moduleRelative =
            backtickedKotlinPaths.filterNot { path -> repoRootedPrefixes.any { path.startsWith(it) } }
        assertTrue(moduleRelative.isNotEmpty(), "fixture assumption broken: no module-relative paths found")
    }

    private fun repoFileExists(pathFromRepoRoot: String): Boolean =
        listOf(File(pathFromRepoRoot), File("../$pathFromRepoRoot"), File("../../$pathFromRepoRoot"))
            .any { it.exists() }

    private fun readRepoFile(pathFromRepoRoot: String): String {
        val candidates =
            listOf(
                File(pathFromRepoRoot),
                File("../$pathFromRepoRoot"),
                File("../../$pathFromRepoRoot"),
            )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "could not locate $pathFromRepoRoot from working dir ${File(".").absolutePath}")
        return file.readText()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataFlowPathReferenceTest*'`

Expected: `repo rooted kotlin paths in DATA_FLOW exist` FAILS listing exactly these 7 paths:

```
  app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt
  app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/BloodPressureDataMapper.kt
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/BodyFatDataMapper.kt
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/OxygenSaturationDataMapper.kt
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/WeightDataMapper.kt
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailyRecomputeSupport.kt
```

`strict check ignores elided and module relative notations` PASSES.

If the failure list differs from these 7, **stop and report** — the doc changed since this plan was written, and the corrections in Step 3 may no longer be right.

- [ ] **Step 3: Correct the 7 stale paths in `internal-docs/DATA_FLOW.md`**

Three moved files — replace every occurrence of the old path with the new one:

| Old (in DATA_FLOW.md) | New (actual location) |
|---|---|
| `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt` | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/migration/DatabaseReadinessGate.kt` |
| `app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt` | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/security/SqlCipherKeyManager.kt` |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailyRecomputeSupport.kt` | `core/database/src/main/kotlin/app/readylytics/health/core/database/domain/sync/DailyRecomputeSupport.kt` |

Four vitals mappers that no longer exist as separate files. Their mapping was consolidated; verify with
`grep -rn "toWeightInput\|toBodyFatInput\|toBloodPressureInput\|toOxygenSaturationInput" --include=*.kt core | grep -v build`
before editing, then replace the four citations with the two files that actually own vitals mapping today:

- `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/VitalsInputMapper.kt` (bulk read path)
- `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeVitalsInputMappers.kt` (changes path)

Rewrite the surrounding sentence so it reads naturally with two files instead of four — do not leave a list of four names pointing at two paths.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataFlowPathReferenceTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
Expected: all green. Then `codegraph index` (new file created).

- [ ] **Step 6: Commit**

```bash
git add app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt internal-docs/DATA_FLOW.md
git commit -m "test(docs): fail the build on stale DATA_FLOW.md source paths"
```

---

## Task 2: Extend the guard to module-relative path citations

**Files:**
- Modify: `app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt`
- Modify: `internal-docs/DATA_FLOW.md` (3 dead citations)

**Interfaces:**
- Consumes: `DataFlowPathReferenceTest` from Task 1 — same class, same `readRepoFile` helper.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Add to `DataFlowPathReferenceTest`. A module-relative citation such as `ui/sync/SyncViewModel.kt` is resolved by suffix match against the tracked source tree, because the doc omits the module and package prefix.

```kotlin
    /**
     * Module-relative citations (`ui/sync/SyncViewModel.kt`) omit the module and package prefix, so
     * they are resolved by suffix match instead of direct existence. A citation that matches nothing
     * is a reference to a file that has been renamed or deleted.
     */
    @Test
    fun `module relative kotlin paths in DATA_FLOW resolve to a source file`() {
        val moduleRelative =
            backtickedKotlinPaths
                .filterNot { path -> repoRootedPrefixes.any { path.startsWith(it) } }
                .filterNot { it.contains("...") }
                .filter { it.contains('/') }

        val sourceRoots = listOf("app", "core", "feature").map { resolveRepoDir(it) }
        val allSources =
            sourceRoots.flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filterNot { it.path.contains("/build/") }
                    .map { it.invariantSeparatorsPath }
                    .toList()
            }

        val unresolved = moduleRelative.filter { rel -> allSources.none { it.endsWith("/$rel") } }.sorted()
        assertTrue(
            unresolved.isEmpty(),
            "DATA_FLOW.md cites ${unresolved.size} module-relative path(s) that match no source file:\n" +
                unresolved.joinToString("\n") { "  $it" },
        )
    }

    private fun resolveRepoDir(name: String): File =
        listOf(File(name), File("../$name"), File("../../$name")).first { it.isDirectory }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataFlowPathReferenceTest*'`

Expected: `module relative kotlin paths in DATA_FLOW resolve to a source file` FAILS listing exactly:

```
  ui/components/InsightCard.kt
  ui/components/M3ScoreGaugeCard.kt
  ui/components/MetricCard.kt
```

The other seven module-relative citations (`data/preferences/PhysiologyProfile.kt`, `domain/recommendation/WorkoutRecommendation.kt`, `domain/sync/ReadRetryBudget.kt`, `domain/validation/SettingsValidators.kt`, `ui/components/TrendCharts.kt`, `ui/scaffold/MainScaffold.kt`, `ui/sync/SyncViewModel.kt`) resolve and must not appear.

- [ ] **Step 3: Correct the 3 dead citations**

All three are in two component tables. Exact locations and the resolution for each:

| Line | Cited as | Resolution |
|---|---|---|
| `DATA_FLOW.md:2493` | `ui/components/InsightCard.kt` | The file exists, but in a **feature** module, not `core:ui`: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/InsightCard.kt`. Cite it repo-rooted so the Task 1 strict check covers it. |
| `DATA_FLOW.md:2269` | `ui/components/MetricCard.kt`, `MetricTooltip.kt` | `MetricTooltip.kt` still exists at `core/ui/.../components/MetricTooltip.kt`. `MetricCard.kt` does not; the metric card is now `core/ui/.../components/metriccard/UniversalMetricCard.kt`. Update both citations and the row's component name from `MetricCard` to `UniversalMetricCard`. |
| `DATA_FLOW.md:2268` and `:2494` | `ui/components/M3ScoreGaugeCard.kt` | No file of that name exists anywhere. The nearest surviving components are `core/ui/.../components/M3MetricGauge.kt` (12.3 KB), `M3MetricGaugeConfig.kt` and `M3MetricBar.kt`. **Read `M3MetricGauge.kt` before editing** and confirm it provides the "soft arc gauge with comparison delta pill" the rows describe. If it does, repoint both rows at it and rename the component column. If it does not, delete both rows — a table row pointing at an unrelated file is worse than no row. |

Do not repoint any citation at a file whose contents you have not read.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataFlowPathReferenceTest*'`
Expected: PASS, all three tests.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/kotlin/app/readylytics/health/docs/DataFlowPathReferenceTest.kt internal-docs/DATA_FLOW.md
git commit -m "test(docs): resolve module-relative DATA_FLOW.md citations"
```

---

## Task 3: Scale points and fixture size guard

**Files:**
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineScalePoints.kt`
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthPipelineBaselineBenchmark.kt` (extend `verifyFixtureDatasetShapes`)

**Interfaces:**
- Consumes: `HealthParentFixture.pages(parentCount: Int, samplesPerParent: Int, pageSize: Int): Sequence<List<DomainHeartRateRecord>>` — already exists, already supports >1M, deterministic by index.
- Produces:
  - `BaselineScalePoints.SAMPLE_COUNTS: List<Int>` = `listOf(250_000, 500_000, 1_000_000)`
  - `BaselineScalePoints.densePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>>`
  - `BaselineScalePoints.sparsePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>>`
  - `BaselineScalePoints.PAGE_SIZE: Int` = `1_000` (matches Health Connect's documented default page size)

- [ ] **Step 1: Write the failing test**

Extend `HealthPipelineBaselineBenchmark.verifyFixtureDatasetShapes` — it already asserts the 1M shapes, so this adds the three scale points and the Review-Focus-3 storage guard.

```kotlin
    /** Step 1 shape assertions validating parent distribution vs sample distribution. */
    @Test
    fun verifyFixtureDatasetShapes() {
        val parents = HealthParentFixture.pages(1_000_001, 1, 1000)
        assertEquals(1_000_001, parents.sumOf { it.size })
        val dense = HealthParentFixture.pages(1001, 1000, 100)
        assertEquals(1_001_000, dense.sumOf { page -> page.sumOf { it.samples.size } })

        // Phase 0: each scale point must produce exactly its nominal sample count in both shapes,
        // so a measurement at 250k/500k/1M is comparing like with like.
        for (total in BaselineScalePoints.SAMPLE_COUNTS) {
            assertEquals(
                total,
                BaselineScalePoints.densePages(total).sumOf { page -> page.sumOf { it.samples.size } },
            )
            assertEquals(
                total,
                BaselineScalePoints.sparsePages(total).sumOf { page -> page.sumOf { it.samples.size } },
            )
        }
    }

    /**
     * Review Focus 3: a 1M-row SQLCipher template plus one copy per benchmark iteration can exhaust
     * device storage or the instrumentation timeout. Measure the template once and fail with a clear
     * message rather than letting a later benchmark die opaquely.
     */
    @Test
    fun verifyLargestFixtureFitsOnDevice() =
        runBlocking {
            val template =
                fixture.createTemplate("scale-guard", useSqlCipher = true) { database ->
                    val guardStore = ScoringBenchmarkHelper.createRoomHealthIngestionStore(
                        database,
                        RoomTransactionRunner(database),
                    )
                    BaselineScalePoints.densePages(BaselineScalePoints.SAMPLE_COUNTS.max()).forEach { page ->
                        guardStore.replaceHeartRateSources(
                            HeartRateMapper.mapToInputs(page, emptyList(), emptyList()),
                        )
                    }
                }
            val bytes = template.file.length()
            Log.i("Phase0Metrics", "METRIC=fixture_template_bytes, SCALE=1000000, VALUE=$bytes")
            assertTrue(
                "1M template is ${bytes / 1_000_000}MB; free space or timeout budget must be re-checked",
                bytes in 1..2_000_000_000,
            )
            fixture.delete(template)
        }
```

Create `BaselineScalePoints.kt`:

```kotlin
package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord

/**
 * The three scale points every Phase 0 measurement is taken at. Peak memory is expected to be flat
 * across these three; wall time is expected to be roughly linear. A measurement taken at only one
 * scale cannot distinguish those two, which is the whole point of having three.
 *
 * Both shapes produce exactly `totalSamples` samples inside `HealthParentFixture`'s fixed 30-day
 * window, so they are directly comparable:
 *  - [densePages] — few parents, many nested samples each (transform-buffer / rollup stress).
 *  - [sparsePages] — one sample per parent (per-parent source-lookup stress).
 */
object BaselineScalePoints {
    val SAMPLE_COUNTS: List<Int> = listOf(250_000, 500_000, 1_000_000)

    /** Matches Health Connect 1.1.0's documented default `ReadRecordsRequest` page size. */
    const val PAGE_SIZE: Int = 1_000

    private const val SAMPLES_PER_DENSE_PARENT = 1_000

    fun densePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>> {
        require(totalSamples % SAMPLES_PER_DENSE_PARENT == 0) {
            "totalSamples must be a multiple of $SAMPLES_PER_DENSE_PARENT"
        }
        return HealthParentFixture.pages(
            parentCount = totalSamples / SAMPLES_PER_DENSE_PARENT,
            samplesPerParent = SAMPLES_PER_DENSE_PARENT,
            pageSize = PAGE_SIZE,
        )
    }

    fun sparsePages(totalSamples: Int): Sequence<List<DomainHeartRateRecord>> =
        HealthParentFixture.pages(
            parentCount = totalSamples,
            samplesPerParent = 1,
            pageSize = PAGE_SIZE,
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.HealthPipelineBaselineBenchmark`

Expected before creating `BaselineScalePoints.kt`: compilation failure, `Unresolved reference: BaselineScalePoints`. Create the file, re-run; both tests should then pass. If `verifyLargestFixtureFitsOnDevice` fails on storage, reduce `SAMPLE_COUNTS` to what the device holds and **record the reduction in the BASELINE.md section in Task 11** — do not silently measure a smaller dataset than the spec's target.

- [ ] **Step 3: Confirm determinism**

Run the same command twice. `fixture_template_bytes` must be identical across runs. A difference means the fixture is not deterministic and every later comparison is invalid — stop and fix before proceeding.

- [ ] **Step 4: Lint**

Run: `./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck`
Expected: PASS. Then `codegraph index`.

- [ ] **Step 5: Commit**

```bash
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/BaselineScalePoints.kt \
        database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthPipelineBaselineBenchmark.kt
git commit -m "test(benchmark): add 250k/500k/1M scale points and fixture size guard"
```

---

## Task 4: Result-set size instrumentation

**Files:**
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/MaxResultSizeRecorder.kt`

**Interfaces:**
- Consumes: `CountingQueryCallback` (exists in `HealthPipelineBaselineBenchmark.kt`, implements `RoomDatabase.QueryCallback`, counts statements only).
- Produces:
  - `class MaxResultSizeRecorder` with `val maxSize: Int`, `val totalRows: Long`, `fun reset()`, and `suspend fun <T> record(label: String, block: suspend () -> List<T>): List<T>`
  - `val MaxResultSizeRecorder.largestLabel: String?`

- [ ] **Step 1: Write the failing test**

`RoomDatabase.QueryCallback.onQuery(sqlQuery, bindArgs)` receives the statement and its bind arguments — **not** the result. Result-set size therefore has to be recorded where the list is returned. This test pins that distinction so nobody later "simplifies" the two instruments into one.

Create `MaxResultSizeRecorder.kt` with the test alongside it:

```kotlin
package app.readylytics.health.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the largest list a instrumented DAO call returned. `RoomDatabase.QueryCallback` cannot
 * supply this — it observes the SQL statement and its bind arguments, never the result — so a
 * "maximum result-set size" criterion needs its own instrument at the call site.
 *
 * Records sizes and labels only; never a row, a value, or a bound argument.
 */
class MaxResultSizeRecorder {
    private val max = AtomicLong(0)
    private val total = AtomicLong(0)

    @Volatile
    var largestLabel: String? = null
        private set

    val maxSize: Int get() = max.get().toInt()
    val totalRows: Long get() = total.get()

    fun reset() {
        max.set(0)
        total.set(0)
        largestLabel = null
    }

    suspend fun <T> record(
        label: String,
        block: suspend () -> List<T>,
    ): List<T> {
        val result = block()
        total.addAndGet(result.size.toLong())
        synchronized(this) {
            if (result.size > max.get()) {
                max.set(result.size.toLong())
                largestLabel = label
            }
        }
        return result
    }
}

@RunWith(AndroidJUnit4::class)
class MaxResultSizeRecorderTest {
    @Test
    fun recordsLargestResultAndItsLabel() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            recorder.record("small") { List(3) { it } }
            recorder.record("large") { List(41) { it } }
            recorder.record("medium") { List(7) { it } }

            assertEquals(41, recorder.maxSize)
            assertEquals(51L, recorder.totalRows)
            assertEquals("large", recorder.largestLabel)
        }

    /**
     * Review Focus 4: statement count and result-set size measure different things. Three calls
     * returning 3, 41 and 7 rows are three statements but a 41-row maximum — a fixture where the
     * two instruments must disagree.
     */
    @Test
    fun statementCountAndResultSizeAreDifferentInstruments() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            val callback = CountingQueryCallback()
            repeat(3) { callback.onQuery("SELECT 1", emptyList()) }
            recorder.record("a") { List(3) { it } }
            recorder.record("b") { List(41) { it } }
            recorder.record("c") { List(7) { it } }

            assertEquals(3L, callback.statementCount)
            assertEquals(41, recorder.maxSize)
            assertNotEquals(callback.statementCount, recorder.maxSize.toLong())
        }

    @Test
    fun resetClearsAllState() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            recorder.record("x") { List(9) { it } }
            recorder.reset()
            assertEquals(0, recorder.maxSize)
            assertEquals(0L, recorder.totalRows)
            assertEquals(null, recorder.largestLabel)
        }
}
```

- [ ] **Step 2: Run test to verify it fails, then passes**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.MaxResultSizeRecorderTest`

Expected on a first run with only the test class written: compilation failure. With the recorder written as above: all three tests PASS.

- [ ] **Step 3: Lint**

Run: `./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck`
Expected: PASS. Then `codegraph index`.

- [ ] **Step 4: Commit**

```bash
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/MaxResultSizeRecorder.kt
git commit -m "test(benchmark): record max DAO result-set size separately from statement count"
```

---

## Task 5: EXPLAIN QUERY PLAN capture

**Files:**
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/QueryPlanRecorder.kt`

**Interfaces:**
- Consumes: `CurrentSchemaBenchmarkFixture.createTemplate(suffix, useSqlCipher, seed)` and `CurrentSchemaFixtureInstance.database: HealthDatabase`. Raw SQL access via `database.openHelper.writableDatabase.query(sql)` — the same call `CurrentSchemaBenchmarkFixture.checkpointWal` already uses against a SQLCipher-backed helper.
- Produces: `QueryPlanRecorder.plan(database: HealthDatabase, sql: String): String` — newline-joined `detail` column of each `EXPLAIN QUERY PLAN` row.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.readylytics.health.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures `EXPLAIN QUERY PLAN` output for the hot queries §7.3 criterion 7 requires an index on.
 * Recorded as text in `benchmark/BASELINE.md` so a later phase can show the plan did not regress.
 */
object QueryPlanRecorder {
    fun plan(
        database: HealthDatabase,
        sql: String,
    ): String {
        val rows = mutableListOf<String>()
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                rows += cursor.getString(detailIndex)
            }
        }
        return rows.joinToString("\n")
    }
}

@RunWith(AndroidJUnit4::class)
class QueryPlanRecorderTest {
    private lateinit var fixture: CurrentSchemaBenchmarkFixture
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
        database = fixture.createDatabase("query-plan.db", useSqlCipher = true)
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    @Test
    fun heartRateRangeScanUsesAnIndex() {
        val plan =
            QueryPlanRecorder.plan(
                database,
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 AND timestampMs <= 1 " +
                    "ORDER BY timestampMs ASC, sourceRecordRef ASC",
            )
        assertTrue("plan was empty", plan.isNotBlank())
        assertTrue("expected an index scan, got:\n$plan", plan.contains("USING INDEX"))
        assertTrue("unexpected temp b-tree sort, got:\n$plan", !plan.contains("USE TEMP B-TREE"))
    }

    @Test
    fun emptyPlanIsNotSilentlyAccepted() {
        val plan = QueryPlanRecorder.plan(database, "SELECT 1")
        assertTrue("a constant select still yields a plan row", plan.isNotBlank())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.QueryPlanRecorderTest`

Expected: compilation failure before `QueryPlanRecorder` exists; PASS once written.

If `heartRateRangeScanUsesAnIndex` fails on an empty table, seed a handful of rows first — SQLite's planner can choose a scan for a table it knows is tiny. Add the seed via `fixture.createTemplate("query-plan", useSqlCipher = true) { db -> ... }` using the same `ScoringBenchmarkHelper.createRoomHealthIngestionStore` + `HeartRateMapper.mapToInputs` pattern as Task 3, and note in the test comment why the seed is required.

- [ ] **Step 3: Record plans for all four hot queries**

Add one test capturing and logging all four query plans from §7.3 criterion 7. Log each with `Log.i("Phase0Metrics", "METRIC=query_plan, QUERY=<name>, PLAN=<single-line plan>")`, replacing newlines with `" | "` so one metric occupies one logcat line:

1. `HeartRateDao.getVisibleByTimeRange` — the full `SELECT h.* FROM heart_rate_records h LEFT JOIN minute_coverage c ON ...` body, copied verbatim from `SCHEMA/data/local/dao/HeartRateDao.kt`.
2. `HeartRateDao.getVisibleByTypeAndTimeRange` — same, with the `h.recordType = ?` predicate.
3. `HeartRateDao.pagePlausibleSamplesForRollup` — verbatim.
4. `HeartRateDao.getKeysetPage` — verbatim.

Copy each SQL string from the DAO rather than retyping it; a paraphrased query produces a plan for a query the app never runs.

- [ ] **Step 4: Run and confirm four plans are logged**

Run the class again and capture logcat:
`adb logcat -d -s Phase0Metrics | grep query_plan`
Expected: exactly 4 lines, each with a non-empty `PLAN=`.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
codegraph index
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/QueryPlanRecorder.kt
git commit -m "test(benchmark): capture EXPLAIN QUERY PLAN for the four hot heart-rate queries"
```

---

## Task 6: Ingest and upsert measurements at three scale points

**Files:**
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt`

**Interfaces:**
- Consumes: `BaselineScalePoints` (Task 3), `MaxResultSizeRecorder` (Task 4), `CountingQueryCallback` and `CountingTransactionRunner` (existing, in `HealthPipelineBaselineBenchmark.kt`), `ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, transactionRunner)`, `HeartRateMapper.mapToInputs(records, sleepInputs, workoutInputs)`.
- Produces: the `Phase0Metrics` logcat contract consumed by Task 11:
  `METRIC=<name>, SCALE=<sampleCount>, DURATION_MS=<double>, STATEMENTS=<long>, TX=<long>, PEAK_HEAP_BYTES=<long>, MAX_RESULT=<int>`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.readylytics.health.benchmark

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Phase 0 "before" measurements. Kept separate from [HealthPipelineBaselineBenchmark] so the
 * existing seven-stage measurement stays comparable to its own July 2026 numbers.
 *
 * Every measurement runs at all three [BaselineScalePoints.SAMPLE_COUNTS] so §7.3's flat-memory
 * criterion is falsifiable: peak heap is expected to be flat, wall time roughly linear.
 */
@RunWith(AndroidJUnit4::class)
class Phase0BaselineBenchmark {
    private lateinit var fixture: CurrentSchemaBenchmarkFixture

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    @Test
    fun measureHeartRateUpsertAtEachScalePoint() =
        runBlocking {
            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val callback = CountingQueryCallback()
                val database = fixture.createDatabase("phase0-upsert-$total.db", true, callback)
                val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
                val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)

                callback.reset()
                txRunner.reset()
                val before = usedHeapBytes()
                var peak = before
                val (_, nanos) =
                    measured {
                        BaselineScalePoints.densePages(total).forEach { page ->
                            store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                            peak = maxOf(peak, usedHeapBytes())
                        }
                    }

                logPhase0Metric(
                    name = "hr_upsert",
                    scale = total,
                    durationMs = nanos / 1_000_000.0,
                    statements = callback.statementCount,
                    transactions = txRunner.transactionCount,
                    peakHeapBytes = peak - before,
                    maxResult = 0,
                )
                assertTrue("upsert must execute statements", callback.statementCount > 0)
                database.close()
            }
        }

    /**
     * Re-ingesting identical data must update zero rows — the `conflictTargetedUpsert` suppression
     * predicate. Recorded here as a before-number because PERF-102 must preserve it.
     */
    @Test
    fun measureIdempotentReIngest() =
        runBlocking {
            val total = BaselineScalePoints.SAMPLE_COUNTS.first()
            val callback = CountingQueryCallback()
            val database = fixture.createDatabase("phase0-reingest.db", true, callback)
            val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
            val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)

            BaselineScalePoints.densePages(total).forEach { page ->
                store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
            }
            val rowsAfterFirst = database.heartRateDao().count()

            callback.reset()
            val (_, nanos) =
                measured {
                    BaselineScalePoints.densePages(total).forEach { page ->
                        store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                    }
                }
            val rowsAfterSecond = database.heartRateDao().count()

            logPhase0Metric("hr_reingest", total, nanos / 1_000_000.0, callback.statementCount, 0, 0, 0)
            assertTrue(
                "re-ingest changed row count: $rowsAfterFirst -> $rowsAfterSecond",
                rowsAfterFirst == rowsAfterSecond,
            )
            database.close()
        }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    internal fun logPhase0Metric(
        name: String,
        scale: Int,
        durationMs: Double,
        statements: Long,
        transactions: Long,
        peakHeapBytes: Long,
        maxResult: Int,
    ) {
        Log.i(
            "Phase0Metrics",
            "METRIC=$name, SCALE=$scale, DURATION_MS=$durationMs, STATEMENTS=$statements, " +
                "TX=$transactions, PEAK_HEAP_BYTES=$peakHeapBytes, MAX_RESULT=$maxResult",
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails, then passes**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.Phase0BaselineBenchmark`

Expected first run: compilation failure if `BaselineScalePoints` or `MaxResultSizeRecorder` is missing. With Tasks 3 and 4 landed: PASS, and `adb logcat -d -s Phase0Metrics` shows four `hr_upsert`/`hr_reingest` lines.

**`usedHeapBytes()` is a coarse instrument.** `Runtime` deltas include GC timing noise. It is adequate for the flat-vs-linear question this phase asks and nothing more — do not quote it as an absolute figure in BASELINE.md without saying so.

- [ ] **Step 3: Verify the flat-memory expectation is actually testable**

Compare the three `hr_upsert` `PEAK_HEAP_BYTES` values. If they scale linearly with `SCALE`, that is itself a Phase 0 finding — record it and flag it for PERF-102, do not adjust the instrument to hide it.

- [ ] **Step 4: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
codegraph index
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt
git commit -m "test(benchmark): record HR upsert and re-ingest baselines at three scale points"
```

---

## Task 7: Workout heart-rate fetch measurement (PERF-101 before-number)

**Files:**
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt`

**Interfaces:**
- Consumes: `Phase0BaselineBenchmark.logPhase0Metric` (Task 6), `MaxResultSizeRecorder` (Task 4), `AuthoritativeHeartRateReader(heartRateDao, minuteBucketDao)` and its `rangeIn(startMs, endMs): AuthoritativeHrRange`.
- Produces: the `workout_hr_fetch` metric consumed by Task 11.

- [ ] **Step 1: Write the failing test**

This measures the exact shape PERF-101 identifies: one range read covering a 45-day cluster span, unfiltered by record type. `AuthoritativeHrRange.mergedSamples()` is `internal` to `:core:database`, so measure through the public reader and the raw list it returns.

```kotlin
    /**
     * PERF-101 before-number: one unfiltered range read across a 45-day cluster span, the shape
     * `fetchHeartRateSamplesByWorkout` produces at `CLUSTER_SPAN_GUARD_MS`. Measured at all three
     * scale points so the later fix can be shown to have decoupled cost from dataset size.
     */
    @Test
    fun measureUnfilteredRangeReadAtClusterSpan() =
        runBlocking {
            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val database = fixture.createDatabase("phase0-range-$total.db", useSqlCipher = true)
                val store =
                    ScoringBenchmarkHelper.createRoomHealthIngestionStore(
                        database,
                        RoomTransactionRunner(database),
                    )
                BaselineScalePoints.densePages(total).forEach { page ->
                    store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                }

                val reader = AuthoritativeHeartRateReader(database.heartRateDao(), database.minuteBucketDao())
                val recorder = MaxResultSizeRecorder()
                val before = usedHeapBytes()
                val (range, nanos) =
                    measured {
                        recorder.record("getVisibleByTimeRange") {
                            reader.rangeIn(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2).rawSamples
                        }
                    }
                val peak = usedHeapBytes()

                logPhase0Metric(
                    name = "workout_hr_fetch",
                    scale = total,
                    durationMs = nanos / 1_000_000.0,
                    statements = 0,
                    transactions = 0,
                    peakHeapBytes = peak - before,
                    maxResult = recorder.maxSize,
                )
                assertTrue("range read returned nothing at scale $total", range.isNotEmpty())
                assertTrue(
                    "PERF-101: this read is unbounded by design today — max result ${recorder.maxSize}",
                    recorder.maxSize >= total / 2,
                )
                database.close()
            }
        }
```

The final assertion deliberately **asserts the defect exists**. It is the characterization test PERF-101's fix must later invert. Write it exactly this way; a test that passes both before and after a fix proves nothing.

- [ ] **Step 2: Run to verify it captures the defect**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.Phase0BaselineBenchmark`

Expected: PASS at all three scales, with `MAX_RESULT` approximately equal to `SCALE`. If the 1M scale throws `OutOfMemoryError`, that **is** the measurement — record "OOM at 1M" in BASELINE.md and lower the assertion to the largest scale that completes, keeping a comment naming the OOM scale.

- [ ] **Step 3: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt
git commit -m "test(benchmark): characterize the unfiltered 45-day workout HR range read"
```

---

## Task 7b: End-to-end `ingestWindow` measurement at scale

**Files:**
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt`

**Context.** Spec §9 Phase 0 step 4 requires a baseline for *"`ingestWindow` over a dense 30-day chunk"*. The existing `HealthPipelineBaselineBenchmark.benchmarkCoordinatorEndToEndIngestion` measures exactly this shape but at `parentCount = 500, samplesPerParent = 10` — 5,000 samples, three orders of magnitude below the design target. Tasks 6 and 7 measure the store and the read in isolation; neither covers the coordinator's own per-window cost (bulk fetch, staging, slicing, deletion reconciliation). Without this task, §7.3 criterion 1's "peak heap during `ingestWindow` is flat" claim has no before-number.

**Interfaces:**
- Consumes: `HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging)` — note the **three**-argument constructor; `staging` is deliberately required with no default (a heap-backed store would reintroduce PERF-001). `BenchmarkFakeHealthConnectRepository(pagesSequence)` from `BenchmarkFakes.kt`. `RoomScanStagingStore` from `:core:database`.
- Produces: the `ingest_window` metric.

- [ ] **Step 1: Confirm the collaborators this module can supply**

`HealthPipelineBaselineBenchmark.benchmarkCoordinatorEndToEndIngestion` currently constructs `HealthIngestionCoordinator(fakeRepo, iterStore)` with two arguments. Verify against the current constructor:

```bash
grep -n "class HealthIngestionCoordinator" -A 12 \
  core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt
grep -rn "class RoomScanStagingStore" -A 8 \
  core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomScanStagingStore.kt
```

If the existing two-argument call no longer compiles against the current signature, that is a pre-existing break in the benchmark module — fix it in this task and say so in the commit message. Construct `RoomScanStagingStore` from the fixture database's `scanStagingDao()` / `scanTypeStateDao()`; do not substitute an in-memory staging store, which would measure a different thing.

- [ ] **Step 2: Write the failing test**

```kotlin
    /**
     * §9 Phase 0 step 4 / §7.3 criterion 1: `ingestWindow` over a dense 30-day chunk, at all three
     * scale points. Covers what Tasks 6 and 7 do not — the coordinator's own per-window cost
     * (concurrent bulk fetches, staging writes, sample-budget slicing, deletion reconciliation).
     *
     * `reconcileDeletions = true` so staging and the anti-join prune are inside the measurement;
     * the existing 5,000-sample benchmark passes `false` and therefore never exercised them.
     */
    @Test
    fun measureIngestWindowAtEachScalePoint() =
        runBlocking {
            val windowStart = Instant.parse("2026-01-01T00:00:00Z")
            val windowEnd = windowStart.plusSeconds(30L * 24 * 3600)
            val prefs = UserPreferences(scoringZoneId = zoneId.id)

            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val callback = CountingQueryCallback()
                val database = fixture.createDatabase("phase0-ingest-$total.db", true, callback)
                val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
                val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)
                val staging = RoomScanStagingStore(database.scanStagingDao(), database.scanTypeStateDao())
                val fakeRepo = BenchmarkFakeHealthConnectRepository(BaselineScalePoints.densePages(total))
                val coordinator = HealthIngestionCoordinator(fakeRepo, store, staging)

                callback.reset()
                txRunner.reset()
                val before = usedHeapBytes()
                var peak = before
                val (_, nanos) =
                    measured {
                        coordinator.ingestWindow(
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            prefs = prefs,
                            windowBudgetMs = 30L * 60_000L,
                            reconcileDeletions = true,
                        )
                        peak = maxOf(peak, usedHeapBytes())
                    }

                logPhase0Metric(
                    name = "ingest_window",
                    scale = total,
                    durationMs = nanos / 1_000_000.0,
                    statements = callback.statementCount,
                    transactions = txRunner.transactionCount,
                    peakHeapBytes = peak - before,
                    maxResult = 0,
                )
                assertTrue("ingestWindow persisted nothing at scale $total", database.heartRateDao().count() > 0)
                database.close()
            }
        }
```

The 30-minute `windowBudgetMs` overrides the 3-minute production default: this is a measurement of cost, and a `withTimeout` firing mid-measurement would record a truncated number rather than a real one. If even 30 minutes is exceeded, record that as the result — it is a Phase 0 finding about HC-105, not a reason to shrink the fixture.

- [ ] **Step 3: Run to verify**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.Phase0BaselineBenchmark`

Expected: PASS; three `ingest_window` lines. Compare `PEAK_HEAP_BYTES` across the three scales — this is the direct test of §7.3 criterion 1's flat-memory claim for the ingest path. If it scales with `SCALE`, record it: that is HC-105's magnitude, previously marked *suspected*, becoming measured.

- [ ] **Step 4: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt
git commit -m "test(benchmark): measure ingestWindow end-to-end at three scale points"
```

---

## Task 8: Walk-forward recompute measurement

**Files:**
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt`

**Interfaces:**
- Consumes: `ScoringBenchmarkHelper.createScoringRepository(database, zoneId)`, `ScoringBenchmarkHelper.seedCalibratedHistory(database, zoneId, targetDate, historyDays)`, and `ScoringRepository.computeAndPersistDailySummary(targetDate, steps)` — all already exercised by `HealthPipelineBaselineBenchmark.measurePipelineStagesSeparately`.
- Produces: the `walk_forward_recompute` metric.

- [ ] **Step 1: Write the failing test**

```kotlin
    /**
     * §11 measurement 7: a 365-day walk-forward. Scored day-by-day in ascending order, which is the
     * order both sync flows use — day N reads day N-1, so the loop cannot be parallelized or
     * reordered without changing results.
     */
    @Test
    fun measureWalkForwardRecomputeOverOneYear() =
        runBlocking {
            val historyDays = 365
            val targetDate = LocalDate.of(2026, 12, 31)
            val database = fixture.createDatabase("phase0-walkforward.db", useSqlCipher = true)
            ScoringBenchmarkHelper.seedCalibratedHistory(database, zoneId, targetDate, historyDays)
            val repository = ScoringBenchmarkHelper.createScoringRepository(database, zoneId)

            val before = usedHeapBytes()
            var peak = before
            val (_, nanos) =
                measured {
                    var day = targetDate.minusDays(historyDays.toLong())
                    while (!day.isAfter(targetDate)) {
                        repository.computeAndPersistDailySummary(day, steps = 8_000L)
                        peak = maxOf(peak, usedHeapBytes())
                        day = day.plusDays(1)
                    }
                }

            logPhase0Metric(
                name = "walk_forward_recompute",
                scale = historyDays,
                durationMs = nanos / 1_000_000.0,
                statements = 0,
                transactions = 0,
                peakHeapBytes = peak - before,
                maxResult = 0,
            )
            assertTrue("walk-forward produced no summaries", database.dailySummaryDao().getAllSummaries().isNotEmpty())
            database.close()
        }
```

Add `private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")` as a field on `Phase0BaselineBenchmark`, matching `HealthPipelineBaselineBenchmark`.

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.Phase0BaselineBenchmark`

Expected: PASS; one `walk_forward_recompute` line. If it exceeds the instrumentation timeout, reduce `historyDays` to 180, record the reduction in BASELINE.md, and note that §11 measurement 7's multi-year figure is therefore still outstanding.

- [ ] **Step 3: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt
git commit -m "test(benchmark): record a 365-day walk-forward recompute baseline"
```

---

## Task 9: CHANGES-1K fixture and measurement

**Files:**
- Create: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/ChangesPageFixture.kt`
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt`

**Interfaces:**
- Consumes: `HealthParentFixture.pages(...)` for the record shapes.
- Produces: `ChangesPageFixture.upsertionRecords(count: Int): List<DomainHeartRateRecord>` and `ChangesPageFixture.deletionIds(count: Int): List<String>` — the sizing input for `DEFAULT_CHANGES_APPLY_BUDGET_MS` (spec OD-6).

- [ ] **Step 1: Write the fixture and its shape test**

The spec's `CHANGES-1K` is 1,000 upsertions plus 200 deletions. Phase 0 measures only the **per-record cost of the write half**, not the full `HealthChangeSynchronizerImpl` path — that class needs a `HealthConnectClient`, which this module does not have. Measuring the per-record store cost is what actually sizes the budget, and it is honest about what it covers.

```kotlin
package app.readylytics.health.benchmark

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord

/**
 * The `CHANGES-1K` fixture from §11: 1,000 upsertions and 200 deletions.
 *
 * Phase 0 uses it to size OD-6's `DEFAULT_CHANGES_APPLY_BUDGET_MS` by measuring the **per-record
 * write cost** that `HealthChangeSynchronizerImpl.processChangesPage` pays once per change. It does
 * not drive `HealthChangeSynchronizerImpl` itself — that requires a `HealthConnectClient` this
 * module does not depend on — so the recorded number is a floor on the real phase cost, not the
 * whole of it. Record it that way in BASELINE.md.
 */
object ChangesPageFixture {
    const val UPSERTION_COUNT: Int = 1_000
    const val DELETION_COUNT: Int = 200

    fun upsertionRecords(count: Int = UPSERTION_COUNT): List<DomainHeartRateRecord> =
        HealthParentFixture.pages(parentCount = count, samplesPerParent = 1, pageSize = count).first()

    fun deletionIds(count: Int = DELETION_COUNT): List<String> =
        upsertionRecords(UPSERTION_COUNT).take(count).map { it.id }
}
```

Shape test, added to `Phase0BaselineBenchmark`:

```kotlin
    @Test
    fun changesFixtureHasTheDocumentedShape() {
        val upserts = ChangesPageFixture.upsertionRecords()
        val deletes = ChangesPageFixture.deletionIds()
        assertEquals(1_000, upserts.size)
        assertEquals(200, deletes.size)
        assertEquals(1_000, upserts.map { it.id }.distinct().size)
        assertTrue("deletions must target records the fixture actually created", upserts.map { it.id }.containsAll(deletes))
    }
```

- [ ] **Step 2: Write the measurement**

```kotlin
    /**
     * OD-6 sizing input: the per-record write cost `processChangesPage` pays 1,000 times, measured
     * one record at a time exactly as that loop does it today.
     */
    @Test
    fun measurePerRecordChangeApplyCost() =
        runBlocking {
            val callback = CountingQueryCallback()
            val database = fixture.createDatabase("phase0-changes.db", true, callback)
            val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
            val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)
            val records = ChangesPageFixture.upsertionRecords()

            callback.reset()
            txRunner.reset()
            val (_, nanos) =
                measured {
                    records.forEach { record ->
                        store.replaceHeartRateSources(
                            HeartRateMapper.mapToInputs(listOf(record), emptyList(), emptyList()),
                        )
                    }
                }

            logPhase0Metric(
                name = "changes_1k_per_record_write",
                scale = ChangesPageFixture.UPSERTION_COUNT,
                durationMs = nanos / 1_000_000.0,
                statements = callback.statementCount,
                transactions = txRunner.transactionCount,
                peakHeapBytes = 0,
                maxResult = 0,
            )
            assertTrue(
                "HC-103: one transaction per record today, expected >= 1000, got ${txRunner.transactionCount}",
                txRunner.transactionCount >= ChangesPageFixture.UPSERTION_COUNT,
            )
            database.close()
        }
```

The final assertion characterizes HC-103's per-record shape. Like Task 7's, it must invert when HC-103 is fixed.

- [ ] **Step 3: Run to verify**

Run: `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.Phase0BaselineBenchmark`
Expected: PASS; `changes_1k_per_record_write` logged with `TX >= 1000`.

- [ ] **Step 4: Lint and commit**

```bash
./gradlew :database-benchmark:ktlintFormat && ./gradlew :database-benchmark:ktlintCheck
codegraph index
git add database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/ChangesPageFixture.kt \
        database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/Phase0BaselineBenchmark.kt
git commit -m "test(benchmark): add CHANGES-1K fixture and per-record change-apply measurement"
```

---

## Task 10: Unblock the two stale macrobenchmark journeys

**Files:**
- Modify: `benchmark/BASELINE.md` (the stale blocker note only — the new dated section is Task 11)

**Interfaces:**
- Consumes: nothing from earlier tasks. This task is independent and may run in parallel with Tasks 3–9.
- Produces: real numbers for `dashboardVitalsTabSwitch` and `hotStart`, or newly-evidenced blocker text.

**Context.** `benchmark/BASELINE.md:17, 38, 46` records these two journeys as blocked by an SQLCipher multi-process key race. That race was fixed and verified in July 2026 (spec §14 OD-5): `SqlCipherKeyManager` holds a static `ReentrantLock` plus a cross-process advisory `FileLock` around both `getOrCreateDbKey()` and `validateKeyDecryption()`, with a durable `commit()` key write. The blocker text is stale documentation.

- [ ] **Step 1: Confirm the race fix is still green**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*SqlCipherKeyManagerCrossProcessRaceTest*'`
and `./gradlew :core:database:testDebugUnitTest --tests '*SqlCipherKeyManagerTest*'`
Expected: PASS. If either fails, **stop** — that is a regression in a shipped fix and outranks this plan.

- [ ] **Step 2: Run the two blocked journeys on a fresh install**

```bash
adb uninstall app.readylytics.health || true
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

A full uninstall first is required: the original failure only reproduced on a genuinely fresh install, so anything less does not exercise the condition that was blocked.

- [ ] **Step 3: Extract the two journeys' numbers**

Read `benchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark/connected/<device>/app.readylytics.health.benchmark-benchmarkData.json` and pull P50/P90/P99 for `dashboardVitalsTabSwitch` and the startup figure for `hotStart`.

- [ ] **Step 4: Replace the stale blocker text**

In `benchmark/BASELINE.md`:
- Line 17: replace *"`dashboardVitalsTabSwitch` and `hotStart` remain pending due to SQLCipher key race on tab navigation during clean install runs"* with a note that the race was fixed in July 2026 and these journeys were re-measured on `<date>`, `<device>`.
- Line 38: replace the three `Pending (blocked by SQLCipher race, see above)` cells with the measured P50/P90/P99.
- Line 46: replace `Pending (failed intermittently during benchmark run, see note above)` with the measured value.

**Do not delete the surrounding July 2026 section** — correct the cells in place and leave every other recorded number untouched. If a journey still fails, replace the cell with a description of the *new* observed failure and its logcat signature, not the old one.

- [ ] **Step 5: Commit**

```bash
git add benchmark/BASELINE.md
git commit -m "docs(benchmark): re-measure journeys unblocked by the 2026-07 SQLCipher key-race fix"
```

---

## Task 11: Append the dated BASELINE.md section

**Files:**
- Modify: `benchmark/BASELINE.md` (append one dated section)
- Create: `app/src/test/kotlin/app/readylytics/health/docs/Phase0BaselineCompletenessTest.kt`

**Interfaces:**
- Consumes: every `Phase0Metrics` logcat line from Tasks 3, 5, 6, 7, 8, 9, and the Task 10 journey numbers.
- Produces: the recorded before-state that WP-12, WP-13, WP-14, WP-15, WP-18 and WP-21 compare against.

- [ ] **Step 1: Write the failing test**

Review Focus 1 and 5: a green build must not be mistakable for recorded measurements, and the append must not clobber the existing reference numbers.

```kotlin
package app.readylytics.health.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Phase 0's deliverable is a recorded measurement set, not a passing build. This test fails if the
 * dated Phase 0 section is missing a metric, so an empty or partial benchmark run cannot be read as
 * a completed phase — and fails if the append clobbered the pre-existing July 2026 baselines.
 */
class Phase0BaselineCompletenessTest {
    private val baseline = readRepoFile("benchmark/BASELINE.md")

    private val requiredMetrics =
        listOf(
            "fixture_template_bytes",
            "hr_upsert",
            "hr_reingest",
            "ingest_window",
            "workout_hr_fetch",
            "walk_forward_recompute",
            "changes_1k_per_record_write",
            "query_plan",
        )

    @Test
    fun `phase 0 section records every required metric`() {
        val section = baseline.substringAfter("## Phase 0 Baseline", missingDelimiterValue = "")
        assertTrue(section.isNotBlank(), "benchmark/BASELINE.md has no '## Phase 0 Baseline' section")
        val absent = requiredMetrics.filterNot { section.contains(it) }
        assertTrue(absent.isEmpty(), "Phase 0 section is missing metrics: $absent")
    }

    @Test
    fun `phase 0 section records all three scale points`() {
        val section = baseline.substringAfter("## Phase 0 Baseline", missingDelimiterValue = "")
        for (scale in listOf("250,000", "500,000", "1,000,000")) {
            assertTrue(section.contains(scale), "Phase 0 section does not record the $scale scale point")
        }
    }

    /** Review Focus 5: BASELINE.md is append-only. */
    @Test
    fun `earlier baseline sections survive the append`() {
        assertTrue(baseline.contains("## M2 Initial Baseline"), "the M2 baseline section was removed")
        assertTrue(
            baseline.contains("## F14 cold-start compilation comparison"),
            "the F14 cold-start section was removed",
        )
    }

    private fun readRepoFile(pathFromRepoRoot: String): String {
        val candidates =
            listOf(
                File(pathFromRepoRoot),
                File("../$pathFromRepoRoot"),
                File("../../$pathFromRepoRoot"),
            )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "could not locate $pathFromRepoRoot from working dir ${File(".").absolutePath}")
        return file.readText()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*Phase0BaselineCompletenessTest*'`
Expected: `phase 0 section records every required metric` FAILS with *"benchmark/BASELINE.md has no '## Phase 0 Baseline' section"*. The append-only test PASSES already.

- [ ] **Step 3: Collect every metric from the device**

```bash
adb logcat -c
./gradlew :database-benchmark:connectedBenchmarkAndroidTest
adb logcat -d -s Phase0Metrics > /tmp/phase0-metrics.txt
wc -l /tmp/phase0-metrics.txt
```

Expected: at least 17 lines — 3 `hr_upsert`, 1 `hr_reingest`, 3 `ingest_window`, 3 `workout_hr_fetch`, 1 `walk_forward_recompute`, 1 `changes_1k_per_record_write`, 1 `fixture_template_bytes`, 4 `query_plan`. Fewer means a test was skipped; find out which before writing anything down.

- [ ] **Step 4: Append the dated section to `benchmark/BASELINE.md`**

Append at the **end** of the file. Fill every `<…>` from `/tmp/phase0-metrics.txt`; do not leave a placeholder.

```markdown
## Phase 0 Baseline — <YYYY-MM-DD>

Recorded for `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` Phase 0
(WP-01). Device: `<model>`, API `<level>`. Build type: `benchmark`. Fixture: `HealthParentFixture`,
deterministic by parent/sample index, 30-day window.

Peak-heap figures come from `Runtime.totalMemory() - Runtime.freeMemory()` deltas and include GC
timing noise. They are adequate for the flat-versus-linear question Phase 0 asks and are not
absolute allocation figures.

### Heart-rate upsert (`SourcePayloadWriter` → `HeartRateDao.upsertAll`)

| Samples | Duration (ms) | Statements | Transactions | Peak heap delta (bytes) |
|---:|---:|---:|---:|---:|
| 250,000 | <…> | <…> | <…> | <…> |
| 500,000 | <…> | <…> | <…> | <…> |
| 1,000,000 | <…> | <…> | <…> | <…> |

Statement count is the PERF-102 before-number: one statement per row today.

### `ingestWindow` end to end (dense 30-day chunk, `reconcileDeletions = true`)

| Samples | Duration (ms) | Statements | Transactions | Peak heap delta (bytes) |
|---:|---:|---:|---:|---:|
| 250,000 | <…> | <…> | <…> | <…> |
| 500,000 | <…> | <…> | <…> | <…> |
| 1,000,000 | <…> | <…> | <…> | <…> |

Peak heap flat across the three scales supports §7.3 criterion 1 for the ingest path; peak heap
scaling with sample count is HC-105's magnitude becoming measured.

### Idempotent re-ingest

| Samples | Duration (ms) | Statements | Row count changed |
|---:|---:|---:|---:|
| 250,000 | <…> | <…> | no |

### Unfiltered range read at 45-day cluster span (PERF-101)

| Samples | Duration (ms) | Max result set | Peak heap delta (bytes) |
|---:|---:|---:|---:|
| 250,000 | <…> | <…> | <…> |
| 500,000 | <…> | <…> | <…> |
| 1,000,000 | <…> | <…> | <…> |

### Walk-forward recompute

| Days | Duration (ms) | Peak heap delta (bytes) |
|---:|---:|---:|
| 365 | <…> | <…> |

### CHANGES-1K per-record write (OD-6 sizing input)

| Records | Duration (ms) | Statements | Transactions |
|---:|---:|---:|---:|
| 1,000 | <…> | <…> | <…> |

Covers the per-record write cost `processChangesPage` pays once per change. It does **not** drive
`HealthChangeSynchronizerImpl` itself, so it is a floor on the real phase cost. Size
`DEFAULT_CHANGES_APPLY_BUDGET_MS` with that caveat in view.

### Fixture footprint

| Samples | Template size on disk (bytes) |
|---:|---:|
| 1,000,000 | <…> |

### Query plans

| Query | Plan |
|---|---|
| `HeartRateDao.getVisibleByTimeRange` | `<…>` |
| `HeartRateDao.getVisibleByTypeAndTimeRange` | `<…>` |
| `HeartRateDao.pagePlausibleSamplesForRollup` | `<…>` |
| `HeartRateDao.getKeysetPage` | `<…>` |

### Deviations from the planned measurement set

<Record any scale point reduced, any measurement that OOM'd or timed out, and any journey that
still failed. If there were none, write "None." — do not delete this subsection.>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*Phase0BaselineCompletenessTest*'`
Expected: PASS, all three tests.

- [ ] **Step 6: Run the full gate**

Run: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease`
Expected: all green. Then `codegraph index`.

- [ ] **Step 7: Commit**

```bash
git add benchmark/BASELINE.md app/src/test/kotlin/app/readylytics/health/docs/Phase0BaselineCompletenessTest.kt
git commit -m "docs(benchmark): record the Phase 0 baseline measurement set"
```

---

## Phase 0 Completion Criteria

Taken from spec §9 Phase 0 and §7.3.

- [ ] `DataFlowPathReferenceTest` is green and fails when a cited path is removed (verify by temporarily renaming a file).
- [ ] `benchmark/BASELINE.md` contains one dated `## Phase 0 Baseline` section with every required metric at all three scale points.
- [ ] Four query plans recorded verbatim.
- [ ] The two previously-blocked macrobenchmark journeys carry real numbers, or a newly-evidenced blocker.
- [ ] `Phase0BaselineCompletenessTest` is green — a partial benchmark run cannot pass as a complete phase.
- [ ] Task 7's and Task 9's characterization assertions pass **as written**, i.e. they document the defects PERF-101 and HC-103 must later invert.
- [ ] `ingest_window` peak heap is recorded at all three scales, so §7.3 criterion 1's flat-memory claim is falsifiable for the ingest path and HC-105's magnitude is no longer merely suspected.
- [ ] No production source file outside `:database-benchmark`, the two new `:app` tests, `internal-docs/DATA_FLOW.md` and `benchmark/BASELINE.md` was modified.
- [ ] No detekt baseline entry added; no `@Suppress` added.
