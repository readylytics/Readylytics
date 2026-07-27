# Implementation Plan — F3, F11, F12

**Parent plan:** `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` (items F3, F11, F12).
**Status:** Approved design — ready to implement.
**Order:** F3 → F11 → F12, one commit each.
**Audience:** A coding agent with no other context. Everything needed is in this document plus the
referenced source files.

---

## 0. Drift corrections against the parent plan

The parent plan's line anchors and code assumptions have drifted. Corrections verified against
source on 2026-07-27:

| Parent plan says | Reality |
|---|---|
| F3/F11 affect `ChartDefaults.kt` consumers in `TrendCharts.kt` | The formatter and item placer are consumed by **five** chart files: `core/ui/.../TrendCharts.kt:172,315`, `feature/vitals/.../bloodpressure/BloodPressureTrendChart.kt:162,320`, `feature/vitals/.../bloodpressure/SingleBloodPressureChart.kt:163,278`, `feature/sleep/.../SleepTrendChart.kt:335,461`, `feature/workouts/.../AcwrChart.kt:264,373`. All five benefit with zero edits because both public signatures are preserved. |
| F11: `getLabelValues`/`getLineValues` each call `calculateValues` once per draw pass ("duplicate call per frame") | Vico calls the placer **three** times per frame: `HorizontalAxis.drawOverLayers` calls `getLabelValues` **and** `getLineValues` (`HorizontalAxis.kt:184-185`), and `drawUnderLayers` calls `getLineValues ?: getLabelValues` (`:294-295`). The single-entry cache collapses 3 → 1. |
| F12: add the pre-warm as the first statement of `HealthDashboardApplication.onCreate`'s existing `appScope.launch` at `:88` | That launch no longer exists. Startup work now runs through `DatabaseReadyStartupCoordinator.observe(...)` → `DatabaseReadyStartupInitializer.initializeIfReady(...)`, which **returns early unless `readiness == DatabaseReadiness.Ready`**. Putting the pre-warm there would gate it behind the DB-migration readiness check and defeat its purpose. |
| F12 step 2: "inspect the three migrations' `shouldMigrate`; make them cheap if not" | **Already satisfied — no code change.** See §4.2. |
| F3/F11 acceptance: measure with M2 journey (b) | M2 has landed (`benchmark/.../ScrollBenchmark.kt` with `vitalsFling`, `vitalsChartPanAndZoom`, `dashboardVitalsTabSwitch`; `StartupBenchmark.kt` with `coldStart`/`warmStart`/`hotStart`). No device is available for this batch, so benchmarks are **optional follow-up**, not a gate. See §5. |

## 1. Constraints binding every commit here

1. **Charts stay composed.** No lazification, no visible chart recreation on scroll-back. None of
   these three items change composition structure, so this is preserved by construction — still
   spot-check manually after F3 and F11.
2. **Output must be byte-identical.** F3 and F11 are pure caching. Axis label strings and tick
   value lists must match the current implementation exactly, for every input. This is enforced by
   golden tests that keep the *current* implementation as the reference (§2.3, §3.3).
3. **Load-bearing intent comments are mandatory** for every caching change (repo rule). Each cache
   introduced below ships with a comment explaining why it is safe.
4. **Pre-commit, every commit:** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
   `./gradlew lintRelease` once after the final commit.
5. **File lifecycle:** run `codegraph index` after creating the two new source files.
6. **No `DATA_FLOW.md` update required.** None of F3/F11/F12 touches the ingestion pipeline, Room
   schema/DAOs, scoring coordinators, or scoring formulas. (Confirm this claim still holds at
   implementation time; if the diff grows to touch any of those, the update becomes mandatory.)
7. **No new user-facing strings** are introduced by any of the three items.

---

## 2. F3 — Cache axis label strings in `rememberDayOffsetFormatter`

**Priority:** Critical. **Effort:** S. **Commit 1 of 3.**

### 2.1 Problem (verified)

`core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ChartDefaults.kt:48-65`:

```kotlin
@Composable
fun rememberDayOffsetFormatter(rangeStartMs: Long): CartesianValueFormatter =
    remember(rangeStartMs) {
        val formatter = DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault())
        CartesianValueFormatter { _, value, _ ->
            Instant.ofEpochMilli(rangeStartMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .plusDays(value.toLong())
                .format(formatter)
        }
    }
```

The formatter *object* is remembered; its body is not. Every visible axis label on every draw pass
allocates an `Instant`, a `ZonedDateTime`, a `LocalDate`, a second `LocalDate` from `plusDays`, and
a `String`. Three Vitals charts share one scroll/zoom state, so one horizontal drag drives roughly
3 charts × ~6 labels × every frame. This is the largest per-frame allocation source during chart
pan/zoom.

`DateFormatUtils.DATE_FORMAT_SHORT` is `"dd.MM"` (`core/ui/.../common/DateFormatUtils.kt:10`).

### 2.2 Change

**New file:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DayOffsetLabelCache.kt`

```kotlin
package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DateFormatUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Memoizes x-axis day labels for a chart whose x-domain is day offsets from [rangeStartMs].
 *
 * Safe to cache without bounds: the horizontal item placer only ever emits day offsets in
 * 0..rangeDays-1 (<= ~180 entries), Vico measures and draws on a single thread, and the cache
 * instance lives and dies with the caller's remember(rangeStartMs) scope, so a range change
 * discards it. Output is byte-identical to formatting on every call -- non-integral values
 * already truncate through toLong().
 *
 * Zone and locale are captured once, for the lifetime of the caller's remember(rangeStartMs)
 * scope. The prior code re-read ZoneId.systemDefault() per label, but rangeStartMs and the
 * plotted day offsets are computed upstream against a fixed zone, so a mid-composition zone
 * change produced labels that disagreed with the data. Freezing both here keeps them consistent.
 */
internal class DayOffsetLabelCache(
    rangeStartMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) {
    private val baseDate = Instant.ofEpochMilli(rangeStartMs).atZone(zone).toLocalDate()
    private val formatter = DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, locale)
    private val cache = HashMap<Long, String>()

    fun label(value: Double): String {
        val offset = value.toLong()
        return cache.getOrPut(offset) { baseDate.plusDays(offset).format(formatter) }
    }
}
```

**Edit** `ChartDefaults.kt:48-65` — signature and return type unchanged:

```kotlin
@Composable
fun rememberDayOffsetFormatter(rangeStartMs: Long): CartesianValueFormatter =
    remember(rangeStartMs) {
        val labels = DayOffsetLabelCache(rangeStartMs)
        CartesianValueFormatter { _, value, _ -> labels.label(value) }
    }
```

Drop the now-unused `Instant` / `ZoneId` / `DateTimeFormatter` / `Locale` / `DateFormatUtils`
imports from `ChartDefaults.kt` if nothing else there uses them (`ktlint` will flag leftovers).

### 2.3 Behavior-preservation notes

- `Locale.getDefault()` is read at exactly the same moment as today (inside `remember(rangeStartMs)`,
  at formatter construction) — no change.
- **`ZoneId.systemDefault()` timing does change**, and this is a deliberate, maintainer-approved
  deviation from the byte-identity constraint (ruling 2026-07-27, Task 1 review). The current code
  re-reads the zone *inside* the formatter lambda, i.e. on every label on every draw pass; the cache
  resolves it once at construction. Rationale for accepting: `rangeStartMs` and the plotted day
  offsets are computed upstream against a fixed zone, so re-reading the zone per label only produced
  labels that disagreed with the plotted data. Freezing zone and locale together is the consistent
  behavior. The class comment must state this explicitly, and a test must pin the default-argument
  construction path (the one production uses).
- `plusDays` on a `LocalDate` is DST-immune, so the day arithmetic is unchanged.
- `value.toLong()` truncates toward zero, same as today.

### 2.4 Tests

**New file:** `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DayOffsetLabelCacheTest.kt`
(plain JVM test; `core/ui` already has `junit` + `kotlin("test")` — see `core/ui/build.gradle.kts`).

Required cases:

1. **Golden vs. reference.** Keep the current inline expression as a private reference function in
   the test. For each `rangeStartMs` fixture and each offset `0.0..179.0`, assert
   `cache.label(v) == reference(rangeStartMs, v)`. Fixtures: a mid-month date, a month boundary,
   a year boundary, and a leap-day-crossing start.
2. **Non-integral truncation.** `label(3.0) == label(3.7) == label(3.999)`; `label(-0.5) == label(0.0)`.
3. **Caching proven.** Two calls with the same value return the *same* `String` instance
   (`assertSame`), and a different value returns a different instance.
4. **Determinism.** Pass `ZoneId.of("UTC")` + `Locale.US` explicitly in most cases; add one case
   with `ZoneId.of("Europe/Berlin")` and a `rangeStartMs` inside the DST-transition week asserting
   labels still advance one calendar day per offset.

### 2.5 Acceptance

- New unit tests green; `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` green.
- Manual: install debug, open Vitals / Sleep / Workouts / Blood pressure; axis labels identical to
  before for the 7 / 30 / 90 / 180-day ranges; pan and zoom feel no worse; scroll-back does not
  visibly recreate a chart.

---

## 3. F11 — Cache and de-allocate `itemPlacerForRangeDays`

**Priority:** High. **Effort:** S. **Commit 2 of 3.**

### 3.1 Problem (verified)

`ChartDefaults.kt:98-193`. The anonymous `HorizontalAxis.ItemPlacer` delegates everything to
`basePlacer` except `getLabelValues` (`:179`) and `getLineValues` (`:186`), which both call the
private `calculateValues(visibleXRange)`. That function, per call, allocates a `mutableListOf`,
walks the whole `0..rangeDays-1` domain by `spacing`, allocates a filtered list, converts
`toMutableList`, may insert/remove, then allocates again through `.sorted()` (`:176`).

Called three times per frame (see §0). The placer instance itself is already remembered per
`rangeDays` by every call site, so instance-level fields are a valid place to cache.

### 3.2 Change

**New file:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DayOffsetTickCalculator.kt`

`internal class DayOffsetTickCalculator(private val rangeDays: Int)` with:

- `private val candidatesBySpacing = HashMap<Int, DoubleArray>()` — lazily filled. The candidate
  walk (`ChartDefaults.kt:137-143`: `0.0, spacing, 2*spacing, … <= rangeDays - 1`) depends only on
  `spacing` and `rangeDays`, so it is computed once per spacing bucket instead of per call.
- `private val zoomedOutValues: DoubleArray?` — precomputed once from `rangeDays`
  (`30 -> [0,6,12,18,24,29]`, `180 -> [0,36,72,108,144,179]`, else `null`), matching
  `ChartDefaults.kt:112-116`.
- Single-entry result cache: `private var lastStart = Double.NaN`, `private var lastEnd = Double.NaN`,
  `private var lastResult: List<Double> = emptyList()`. Compare the incoming range's `start` and
  `endInclusive` against the stored doubles (NaN never compares equal, so the initial state always
  misses). On a hit, return `lastResult` — the *same* instance.
- `fun values(visibleXRange: ClosedFloatingPointRange<Double>): List<Double>` reproducing the
  current logic exactly, in this order:
  1. `visibleDays = endInclusive - start`.
  2. Zoomed-out branch when `visibleDays > rangeDays - 2.0` **and** `zoomedOutValues != null`:
     filter by `it in (start - 0.01)..(endInclusive + 0.01)`. Note the current code only takes this
     branch when the precomputed list exists; when it is `null` it falls through to the general
     path. Preserve that.
  3. Spacing table (`ChartDefaults.kt:125-135`), verbatim thresholds.
  4. Filter the cached candidate array into an `ArrayList<Double>` with the same `± 0.01` buffer.
     Candidates are generated ascending, so the filtered result is already ascending.
  5. First-day insertion: if `0.0 in visibleXRange && !contains(0.0)`, insert at index 0
     (`:152-155`).
  6. `maxVal` handling (`:157-174`): `minSeparation` table verbatim; drop the last element when
     `maxVal - lastValue < minSeparation`; append `maxVal`.
  7. **Return without `.sorted()`.** Justification: candidates ascending + `0.0` prepended (and
     `0.0` is `<=` every candidate) + `maxVal` appended (and `maxVal` is `>=` every candidate,
     since candidates are capped at `maxVal`) ⇒ already ascending. The golden test in §3.3 is what
     actually proves this; do not skip it.
- Store the result into `lastStart`/`lastEnd`/`lastResult` before returning.

**Edit** `ChartDefaults.kt:98-193` — signature unchanged:

```kotlin
fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer {
    val basePlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true)
    // Per-instance caches are safe here: Vico measures and draws on a single thread, this placer
    // is scoped to one chart via remember(rangeDays) at every call site, and Vico only iterates
    // the returned list (HorizontalAxis.kt:184-185 and :294-295 in Vico 3.2.3) -- it never mutates
    // it, so handing the same cached instance to getLabelValues, getLineValues, and consecutive
    // frames is safe. A single frame asks three times with the same visible range.
    val ticks = DayOffsetTickCalculator(rangeDays)
    return object : HorizontalAxis.ItemPlacer by basePlacer {
        override fun getLabelValues(...) = ticks.values(visibleXRange)
        override fun getLineValues(...) = ticks.values(visibleXRange)
    }
}
```

`ChartDefaults.kt` drops from 194 lines to roughly 100 — well inside the 400-line target.

### 3.3 Tests

**New file:** `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DayOffsetTickCalculatorTest.kt`

1. **Golden vs. reference (the load-bearing test).** Paste the *current* `calculateValues` body
   into the test file verbatim as `private fun referenceValues(rangeDays: Int, visibleXRange: …)`.
   Assert element-identical output over the cross product of:
   - `rangeDays ∈ {7, 30, 90, 180}`;
   - visible ranges: full domain; `rangeDays - 2.0` exactly (the zoomed-out branch boundary) and
     just under/over it; half, quarter, and eighth of the domain at the start, middle, and end;
     a sub-day window (`5.0..5.4`); a window straddling `0.0`; a window straddling `maxVal`;
     windows offset by exactly the `0.01` buffer on each edge; and a window extending past
     `maxVal`.
2. **Cache hit returns the identical instance.** Same range twice → `assertSame`.
3. **Cache miss on a changed range.** Different range → recomputed, and equal to the reference.
4. **Alternating ranges.** A→B→A must still return correct values for A (guards against a
   half-updated cache).
5. **Spacing-candidate reuse.** Repeated calls at the same spacing bucket produce identical output
   (guards against accidentally mutating a cached `DoubleArray`).

### 3.4 Acceptance

- New unit tests green; pre-commit commands green.
- Manual: pan and zoom each chart at 7 / 30 / 90 / 180 days; tick and label positions unchanged;
  no chart recreation on scroll-back.

---

## 4. F12 — Pre-warm the first DataStore read

**Priority:** High. **Effort:** S. **Commit 3 of 3.**

### 4.1 Problem (verified)

`MainActivity.kt:136-139` keeps the splash on screen until the first `userPreferences` emission
(bounded by `SPLASH_MAX_WAIT_MS = 2000`, `:45`). That is deliberate theme-flash prevention and must
stay. But the read is only *triggered* by composition (`:128`), so proto-store load — plus the
three `DataMigration`s registered on it (`DataStoreModule.kt:104-282`) — is serialized *after*
Activity startup instead of overlapping it.

### 4.2 What is NOT changing (verified, no work required)

The parent plan's step 2 asks to make the migrations' `shouldMigrate` cheap. They already are:

| Migration | `shouldMigrate` | Cost |
|---|---|---|
| Legacy preferences → proto (`DataStoreModule.kt:106-247`) | `oldFile.exists()` | One `File.exists()` syscall. The expensive part — opening a second `PreferenceDataStore` (`:113-117`) — lives inside `migrate()`, which only runs when the legacy file exists. |
| Seed `scoringZoneId` (`:252-265`) | `currentData.scoringZoneId.isBlank()` | In-memory proto field check. |
| Canonicalize removed profiles (`:269-281`) | Two enum comparisons | In-memory. |

Also unchanged: `SPLASH_MAX_WAIT_MS`, the `setKeepOnScreenCondition` predicate, and the
`DatabaseReadyStartupCoordinator` gating.

### 4.3 Change

**Ruling (2026-07-27, maintainer):** extract the pre-warm into its own testable class rather than
inlining it in `onCreate`, so F12 ships with real unit coverage. This supersedes the earlier
"inline it" decision.

**New file:** `app/src/main/kotlin/app/readylytics/health/PreferencesPrewarmer.kt` — sibling of
`DatabaseReadyStartupInitializer.kt`, same package (`app.readylytics.health`), same
`Lazy<...>`-injection style so it mirrors the existing startup class and its test.

```kotlin
package app.readylytics.health

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.util.logE
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Pulls the first user-preferences emission off the first-frame critical path.
 *
 * MainActivity's splash keep-condition blocks the first frame until userPreferences emits, and
 * that read is otherwise triggered only by composition -- serializing the proto-store load (and
 * its one-time DataMigrations) after Activity startup instead of overlapping it. DataStore caches
 * after the first read, so the Activity's collection then resolves immediately.
 *
 * Deliberately NOT part of DatabaseReadyStartupInitializer: that work waits for
 * DatabaseReadiness.Ready, and gating the pre-warm on the DB migration would defeat its purpose.
 */
internal class PreferencesPrewarmer(
    private val settingsRepository: Lazy<SettingsRepository>,
) {
    suspend fun prewarm() {
        try {
            settingsRepository.get().userPreferences.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fire-and-forget: the Activity's own collection is the authoritative read, and the
            // splash timeout already bounds a stalled store. A failure here must not crash startup.
            logE(TAG) { "User preferences pre-warm failed: ${e.message}" }
        }
    }

    private companion object {
        const val TAG = "HealthDashboardApplication"
    }
}
```

Use whichever `logE` overload matches the existing call sites in this package
(`DatabaseReadyStartupInitializer.kt:43` uses `logE(TAG, e) { "..." }`) — prefer passing the
throwable through if that overload exists.

**Edit** `HealthDashboardApplication.onCreate`, **immediately before** the existing
`appScope.launch { startupCoordinator.observe(...) }` (currently `:100-102`):

```kotlin
val preferencesPrewarmer = PreferencesPrewarmer(settingsRepo)
appScope.launch { preferencesPrewarmer.prewarm() }
```

Keep it ungated and above the coordinator launch. Do not route it through
`DatabaseReadyStartupCoordinator`.

### 4.4 Safety notes

- `appScope` is `SupervisorJob() + Dispatchers.IO` (`DataStoreModule.kt:37-43`), so
  `settingsRepo.get()` — which materializes `SettingsRepository` and its `DataStore` — happens off
  the main thread.
- The user-preferences `DataStore` is itself constructed with `scope = appScope`
  (`DataStoreModule.kt:95, 103`). Collecting its `data` from a coroutine in the same scope is the
  normal usage pattern and does not deadlock: DataStore serializes internally through its own
  actor, not through the collector's job.
- Failure is swallowed and logged. It must not crash startup: the Activity's own collection is
  still the authoritative read, and the splash timeout already bounds a stalled store.
- The pre-warm is fire-and-forget; nothing waits on it.

### 4.5 Tests

**New file:** `app/src/test/kotlin/app/readylytics/health/PreferencesPrewarmerTest.kt`. Mirror the
existing `DatabaseReadyStartupInitializerTest.kt` style: `mockk` for `SettingsRepository` and its
`Lazy`, `runTest`, `org.junit.Test`.

Required cases:

1. **Reads the first emission.** Fake `userPreferences` flow; assert `prewarm()` resolves the
   `Lazy` (`verify { settingsRepositoryLazy.get() }`) and collects exactly one value — a flow that
   emits then suspends forever must not hang `prewarm()`.
2. **Failure is swallowed.** A `userPreferences` flow that throws (and separately: a `Lazy.get()`
   that throws) must not propagate out of `prewarm()`.
3. **Cancellation propagates.** A flow that throws `CancellationException` (or a `prewarm()` call
   cancelled mid-collection) must rethrow, not be swallowed by the generic catch.

`HealthDashboardApplication.onCreate` itself remains untested — the wiring is two lines. Its
correctness is covered by the manual checks in §4.6.

### 4.6 Acceptance

- New unit tests green; `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` green.
- Manual, on a real install:
  1. Cold start in dark mode, then in light mode — no theme flash, splash behaves as before.
  2. Cold start with a non-default `AppTheme` set — correct theme on first frame.
  3. Fresh install (migrations pending / legacy file present if reproducible) — app starts, prefs
     correct, no crash.
  4. Cold start while a DB migration is pending — the migration screen still appears, and the
     pre-warm does not interfere.
- Optional, if a device becomes available: `StartupBenchmark.coldStart` P50 improves or holds.

---

## 5. Verification summary

Per commit: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
After the final commit: `./gradlew lintRelease`, then `codegraph index` (three new source files,
three new test files).

| Item | Automated | Manual |
|---|---|---|
| F3 | `DayOffsetLabelCacheTest` golden + caching + DST | Axis labels unchanged at 7/30/90/180 on all five chart screens; no chart recreation on scroll-back |
| F11 | `DayOffsetTickCalculatorTest` golden grid + cache behavior | Tick/label placement unchanged while panning and zooming; no chart recreation on scroll-back |
| F12 | `PreferencesPrewarmerTest` (first-emission read, failure swallowed, cancellation propagates) | Cold start theme correctness (dark/light/custom), fresh install, pending-DB-migration start |

No device is available for this batch, so `ScrollBenchmark.vitalsChartPanAndZoom` and
`StartupBenchmark.coldStart` are recorded here as **optional follow-up** measurements, not gates.
If a device becomes available, run them before and after the batch and append the numbers to this
document.

## 6. Commits

| # | Commit | Files |
|---|---|---|
| 1 | F3 — cache day-offset axis labels | new `DayOffsetLabelCache.kt`, new `DayOffsetLabelCacheTest.kt`, edit `ChartDefaults.kt` |
| 2 | F11 — cache item-placer tick values | new `DayOffsetTickCalculator.kt`, new `DayOffsetTickCalculatorTest.kt`, edit `ChartDefaults.kt` |
| 3 | F12 — pre-warm the first DataStore read | new `PreferencesPrewarmer.kt`, new `PreferencesPrewarmerTest.kt`, edit `HealthDashboardApplication.kt` |

After the batch, mark F3, F11, and F12 as implemented in
`internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`.
