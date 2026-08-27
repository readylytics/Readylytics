# Plan: Historical baseline overlay for 7D/30D Vitals charts

This plan is self-contained: everything needed to execute it is below, and no other
document is required. No code has been written yet — this is planning only.

---

## 1. Context

The Vitals tab's range selector (`VitalsScreen.kt`) already offers four time ranges —
7D, 30D, 180D, 360D — via `TimeRange.entries`. But the "historical baseline" overlay on
the HRV/RHR trend charts — a muted line tracing each day's *frozen* baseline over time,
plus zone-shading that flows to follow that baseline's drift — currently only renders at
180D/360D. At 7D/30D the chart instead shows a flat "today's baseline" reference line and
zone bands based on today's live baseline; there is no way to see how the baseline itself
has moved day-to-day within a short window.

The user asked to bring the drift-tracking overlay (line **and** flowing zone bands) to
7D and 30D as well, so short-range views show baseline movement, not just a snapshot.

This was confirmed directly with the user, no assumptions:

- Both the baseline line **and** the flowing zone-band shading must appear at 7D/30D —
  full visual parity with 180D/360D, just at finer granularity.
- Bucketing applies to the **overlay only**. The raw HRV/RHR sample points at 7D/30D stay
  exactly one point per day, unchanged.
  - **7D:** one overlay point per day — that day's own frozen baseline value, unaveraged.
  - **30D:** **non-overlapping 2-day buckets** (day1+day2 → one point, day3+day4 → next
    point, … → 15 points across 30 days). This is a fixed bucketing, **not** a rolling/
    moving average.
- Scope is **HRV and RHR only**. SpO2 (fixed 95% line) and Body Temp (live baseline) have
  no baseline-overlay concept today and are out of scope.
- Missing/un-frozen days inside a bucket follow the same convention already used at
  180D/360D: average only the frozen days present in that bucket; a bucket with zero
  frozen days is omitted entirely (no fabricated value, no interpolation).

## 2. Current architecture (as verified in the codebase)

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TimeRange.kt`:
  ```kotlin
  enum class TrendGranularity { DAILY, MONTHLY, EIGHT_WEEK }
  enum class TimeRange(val days: Int, val label: String, val granularity: TrendGranularity) {
      SEVEN_DAYS(7, "7D", TrendGranularity.DAILY),
      THIRTY_DAYS(30, "30D", TrendGranularity.DAILY),
      SIX_MONTHS(180, "180D", TrendGranularity.MONTHLY),
      TWELVE_MONTHS(360, "360D", TrendGranularity.EIGHT_WEEK),
  }
  ```
  The range selector chips (`VitalsScreen.kt:192-211`, `SingleChoiceSegmentedButtonRow` over
  `TimeRange.entries`) already expose all four ranges — no UI selector change is needed.

- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`,
  function `buildVitalsChartSeries` (~lines 133-283), is where the overlay is actually gated
  today:
  ```kotlin
  val rawRhrBaseline: List<DailyDataPoint>? =
      if (range.granularity == TrendGranularity.DAILY) {
          null
      } else {
          realPoints { summary -> DailyMetricsMapper.rhrBaselineRounded(summary, rhrBaselineOverride)?.toFloat() }
      }
  // same pattern for rawHrvBaseline via hrvBaselineRounded

  val historicalRhrBaseline = rawRhrBaseline?.bucketBy(range.granularity, startDate, endDate) ?: emptyList()
  // same for historicalHrvBaseline

  val historicalRhrBaselineAverage: Int? =
      rawRhrBaseline?.mapNotNull { it.value }?.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
  // same for HRV — this is the mean of every frozen per-day baseline across the WHOLE window,
  // never a mean of the bucketed averages

  val historicalRhrZoneBands: List<ZoneBand> =
      historicalRhrBaselineAverage?.let { rhrZoneBandsForBaseline(it, rhrOptimalThreshold, rhrWarningThreshold) } ?: emptyList()
  // same for HRV — the FLAT (single-band, full-width) zone-band variant

  val historicalRhrBucketZoneBands: List<BucketZoneBands> =
      if (range.granularity == TrendGranularity.DAILY) {
          emptyList()
      } else {
          historicalRhrBaseline.mapNotNull { bucket ->
              bucket.value?.roundToInt()?.let { baseline ->
                  val bucketStart = bucketStartForDate(startDate.plusDays(bucket.dayOffset.toLong()), range.granularity)
                  val startOffset = ChronoUnit.DAYS.between(startDate, bucketStart).toInt().coerceAtLeast(0)
                  val endOffset = (startOffset + bucketLengthDays(bucketStart, range.granularity)).coerceAtMost(rangeEndOffsetExclusive)
                  BucketZoneBands(startOffset, endOffset, rhrZoneBandsForBaseline(baseline, rhrOptimalThreshold, rhrWarningThreshold))
              }
          }
      }
  // same pattern for HRV — the FLOWING per-bucket zone-band variant (smooth polygon through
  // bucket midpoints, drawn by ZoneBandDecoration)
  ```
  `DailyMetricsMapper.rhrBaselineRounded`/`hrvBaselineRounded`
  (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/DailyMetricsMapper.kt:164-197`)
  read each day's already-frozen per-day baseline off the `DailySummary` Room entity — no
  day-count parameter of their own, and they do **not** need to change.

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregation.kt`
  holds the generic calendar-bucketing machinery: `bucketStartForDate`, `bucketLengthDays`,
  `bucketBy` (exhaustive `when` over `TrendGranularity`: `DAILY` is a pass-through/no-average
  branch, `MONTHLY` buckets by calendar month, `EIGHT_WEEK` buckets by 8-ISO-week "octad"),
  plus `allBucketOffsets`/`padBucketsToRange`/`periodLabelFor`/`rememberPeriodOrdinalLabel`,
  which also switch exhaustively over `TrendGranularity` and are used elsewhere for raw-point
  bucket padding and axis/period labels. The baseline-overlay path only ever calls `bucketBy`
  directly — it never touches `allBucketOffsets`, `padBucketsToRange`, `periodLabelFor`, or
  `rememberPeriodOrdinalLabel`.

- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`
  wires the computed series into the chart. HRV block (~114-158) and RHR block (~160-205) both
  contain:
  ```kotlin
  baseline = chartSeries.historicalHrvBaselineAverage?.toFloat() ?: presentation.hrv.baseline?.toFloat(),
  showBaseline = presentation.hrv.baseline != null,
  zoneBands = if (chartInputs.selectedRange.granularity == TrendGranularity.DAILY) {
      presentation.hrv.zoneBands
  } else {
      chartSeries.historicalHrvZoneBands
  },
  historicalBaseline = chartSeries.historicalHrvBaseline.takeIf { it.isNotEmpty() },
  bucketZoneBands = chartSeries.historicalHrvBucketZoneBands.takeIf { it.isNotEmpty() },
  granularity = chartInputs.selectedRange.granularity,
  ```
  (RHR block is the same shape, substituting `rhr`/`Rhr` names.)

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendChartDecoration.kt`:
  `hasHistoricalBaseline = !historicalBaseline.isNullOrEmpty()` suppresses the flat "today's
  baseline" `HorizontalLine` whenever a historical baseline series is present, so the chart
  draws the fluctuating line instead.

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ZoneBandDecoration.kt`,
  `drawUnderLayers` (lines 33-43) — **verified directly**:
  ```kotlin
  if (bucketZoneBands.isNullOrEmpty()) {
      drawFlat(context, zoneBands, bandColors, bounds, range)
  } else {
      drawSmoothPerBucket(context, bucketZoneBands, bounds, range)
  }
  ```
  `bucketZoneBands` **strictly takes precedence** over the flat `zoneBands` whenever non-empty.
  This means once `historicalRhrBucketZoneBands`/`historicalHrvBucketZoneBands` populate at
  7D/30D, the flowing per-bucket rendering wins automatically; the flat `zoneBands` argument
  remains a harmless defensive fallback only, exactly as it already is at 180D/360D.

- Only HRV and RHR have this baseline-overlay concept. SpO2 uses a fixed 95% line; Body Temp
  uses a live/current baseline. Neither is touched by this plan.

- Tests today:
  - `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt`:
    `` `DAILY range produces empty historical baseline series` `` (~347-364),
    `` `historical baseline averages only frozen days per bucket` `` (~366-407),
    `` `historical zone bands empty when no frozen baselines` ``,
    `` `historical baseline honors override when no frozen days` `` (~409-448) — all currently
    exercised with `TimeRange.SIX_MONTHS`.
  - `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TimeRangeTest.kt` asserts
    `SEVEN_DAYS`/`THIRTY_DAYS` map to `TrendGranularity.DAILY` — this must keep passing
    unmodified (raw-point/axis granularity is not changing).

- `internal-docs/DATA_FLOW.md`, lines 584-597 (§ Vitals trend chart), documents the current
  180D/360D-only overlay behavior in prose and needs a synchronous update per this repo's
  documentation-sync convention for changes to this data-flow area.

## 3. Chosen approach

**Do not add a new `TrendGranularity` enum case.** `TrendGranularity` is consumed by several
exhaustive `when`s in `TrendPeriodAggregation.kt` that have nothing to do with the baseline
overlay (axis/period labels, raw-point bucket-offset padding for 180D/360D, quarter/week
ordinal label templates). Adding a case would force a branch in every one of them purely to
satisfy exhaustiveness, almost all of which would be dead code for the new case — the opposite
of the minimal-blast-radius, no-premature-abstraction approach this codebase favors. Fixed-size
day bucketing (anchored at day-offset 0 relative to the selected window's `startDate`) is also a
structurally different rule than the calendar-anchored month/octad bucketing those functions
implement, so folding it into the same enum would muddy those functions' contracts.

Keeping `TimeRange.THIRTY_DAYS.granularity == DAILY` unchanged also preserves `TimeRangeTest` as
written, and — more importantly — preserves all raw-point and axis/tooltip-date-formatting
behavior at 7D/30D exactly as it is today, since those are driven by the same `granularity`
value and must stay one-point-per-day/DAILY-formatted per the user's requirement.

Instead: give the baseline-overlay computation in `VitalsStateFactory.kt` its own small, pure,
independent bucketing function, and branch on `TimeRange` itself (not `TrendGranularity`) to pick
which bucketing rule the overlay uses. The 180D/360D code path is left untouched — 100% code
reuse of the existing `bucketBy` + `bucketStartForDate`/`bucketLengthDays` logic for those two
ranges.

## 4. Exact changes

### 4a. `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregation.kt`

Add, immediately after `bucketBy` (so it can reuse the existing private `roundToDecimalPlaces`
extension without changing its visibility). No existing function or enum in this file is
modified.

```kotlin
/**
 * A non-overlapping, fixed-length day-bucket used only by the Vitals baseline overlay at 7D/30D.
 * Buckets are anchored at day-offset 0 (the caller's `startDate`) rather than at a calendar
 * boundary, so pairing is deterministic regardless of which real-world dates the window spans.
 */
data class FixedDayBucket(
    val startDayOffset: Int,
    val endDayOffsetExclusive: Int,
    val midpointDayOffset: Int,
    val value: Float,
)

/**
 * Groups [DailyDataPoint]s into non-overlapping [bucketSizeDays]-day windows anchored at day
 * offset 0, averaging each bucket's non-null values and rounding to [valueDecimalPlaces].
 * Buckets with zero non-null values are omitted entirely, mirroring [bucketBy]'s null-filtering
 * convention. [bucketSizeDays] == 1 yields one bucket per populated day (no averaging).
 * [rangeEndOffsetExclusive] clips the final bucket's end boundary to the selected window.
 */
fun List<DailyDataPoint>.bucketByFixedSize(
    bucketSizeDays: Int,
    rangeEndOffsetExclusive: Int,
    valueDecimalPlaces: Int = 0,
): List<FixedDayBucket> {
    require(bucketSizeDays >= 1) { "bucketSizeDays must be >= 1" }
    return filter { it.value != null }
        .groupBy { it.dayOffset / bucketSizeDays }
        .toSortedMap()
        .map { (bucketIndex, points) ->
            val startOffset = bucketIndex * bucketSizeDays
            val endOffsetExclusive = (startOffset + bucketSizeDays).coerceAtMost(rangeEndOffsetExclusive)
            val midpoint = startOffset + (endOffsetExclusive - startOffset - 1) / 2
            val average =
                points.mapNotNull(DailyDataPoint::value)
                    .average()
                    .toFloat()
                    .roundToDecimalPlaces(valueDecimalPlaces)
            FixedDayBucket(startOffset, endOffsetExclusive, midpoint, average)
        }
}
```

Correctness against the product rules:
- `bucketSizeDays = 1` (7D): `dayOffset / 1 == dayOffset`, so each frozen day is its own bucket;
  midpoint equals that same offset — unaveraged, one point per day.
- `bucketSizeDays = 2` (30D): offsets `{0,1}→bucket0`, `{2,3}→bucket1`, …, `{28,29}→bucket14` —
  exactly "day1+day2 averaged, day3+day4 averaged, …". The grouping key is `dayOffset /
  bucketSizeDays` relative to `startDate`, not `LocalDate.getDayOfMonth()`, so pairing is
  deterministic regardless of which calendar dates the window spans.
- A pair with only one frozen day still produces a bucket (single-value average); a pair with
  zero frozen days is omitted via `filter { it.value != null }` before grouping — matches the
  "omit buckets with zero frozen days" requirement exactly, reusing `bucketBy`'s own convention.
- `endDayOffsetExclusive` is always the full nominal `startOffset + bucketSizeDays` (clamped to
  the window), independent of how many days inside actually had data — same "always full nominal
  bucket width" behavior the existing 180D month-bucket test already asserts.

### 4b. `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`

- Add import for `bucketByFixedSize`; remove the `TrendGranularity` import once every
  `TrendGranularity.DAILY` check below is gone (otherwise ktlint's unused-import check fails).

- Add a private resolver:
  ```kotlin
  private fun baselineOverlayBucketSizeDays(range: TimeRange): Int? =
      when (range) {
          TimeRange.SEVEN_DAYS -> 1
          TimeRange.THIRTY_DAYS -> 2
          TimeRange.SIX_MONTHS, TimeRange.TWELVE_MONTHS -> null
      }
  ```
  `null` means "use the existing calendar-bucketed path unchanged." This `when` is over
  `TimeRange` (4 cases, all handled), lives only in this file, and has no effect on any other
  call site.

- Replace the `if (range.granularity == TrendGranularity.DAILY) null else ...` gates on
  `rawRhrBaseline`/`rawHrvBaseline` with unconditional computation (needed at all four ranges
  now, since the whole-window average must exist at 7D/30D too):
  ```kotlin
  val rawRhrBaseline: List<DailyDataPoint> =
      realPoints { summary -> DailyMetricsMapper.rhrBaselineRounded(summary, rhrBaselineOverride)?.toFloat() }
  val rawHrvBaseline: List<DailyDataPoint> =
      realPoints { summary -> DailyMetricsMapper.hrvBaselineRounded(summary, hrvBaselineOverride)?.toFloat() }
  ```
  (`DailyMetricsMapper.rhrBaselineRounded`/`hrvBaselineRounded` are unchanged — they already
  return `null` for un-frozen days regardless of range.)

- `historicalRhrBaselineAverage`/`historicalHrvBaselineAverage` keep their exact current
  formula — mean of every frozen per-day baseline across the whole window, never a mean of
  bucket averages — just drop the now-unneeded nullable safe-calls on the (now non-nullable)
  `rawRhrBaseline`/`rawHrvBaseline`. This formula now runs uniformly across all four ranges,
  including 7D/30D. `historicalRhrZoneBands`/`historicalHrvZoneBands` (the flat single-band
  fallback) are unchanged and now also populate at 7D/30D whenever any frozen day exists.

- Hoist `rangeEndOffsetExclusive`'s existing computation (`ChronoUnit.DAYS.between(startDate,
  endDate).toInt() + 1`) above both the RHR and HRV blocks so both can use it, and compute
  `val overlayBucketSizeDays = baselineOverlayBucketSizeDays(range)` once.

- Replace the four existing blocks (`historicalRhrBaseline`, `historicalHrvBaseline`,
  `historicalRhrBucketZoneBands`, `historicalHrvBucketZoneBands`) with a branch on
  `overlayBucketSizeDays`, for RHR (HRV mirrors exactly, substituting `hrv`/`Hrv` names,
  `hrvZoneBandsForBaseline`, `hrvOptimalThreshold`/`hrvWarningThreshold`):
  ```kotlin
  val historicalRhrBaseline: List<DailyDataPoint>
  val historicalRhrBucketZoneBands: List<BucketZoneBands>
  if (overlayBucketSizeDays != null) {
      val rhrBuckets = rawRhrBaseline.bucketByFixedSize(overlayBucketSizeDays, rangeEndOffsetExclusive)
      historicalRhrBaseline = rhrBuckets.map { DailyDataPoint(it.midpointDayOffset, it.value) }
      historicalRhrBucketZoneBands =
          rhrBuckets.map { bucket ->
              BucketZoneBands(
                  startDayOffset = bucket.startDayOffset,
                  endDayOffset = bucket.endDayOffsetExclusive,
                  bands = rhrZoneBandsForBaseline(bucket.value.roundToInt(), rhrOptimalThreshold, rhrWarningThreshold),
              )
          }
  } else {
      // 180D/360D — identical to the current implementation, just relocated into this branch.
      historicalRhrBaseline = rawRhrBaseline.bucketBy(range.granularity, startDate, endDate)
      historicalRhrBucketZoneBands =
          historicalRhrBaseline.mapNotNull { bucket ->
              bucket.value?.roundToInt()?.let { baseline ->
                  val bucketStart = bucketStartForDate(startDate.plusDays(bucket.dayOffset.toLong()), range.granularity)
                  val startOffset = ChronoUnit.DAYS.between(startDate, bucketStart).toInt().coerceAtLeast(0)
                  val endOffset = (startOffset + bucketLengthDays(bucketStart, range.granularity)).coerceAtMost(rangeEndOffsetExclusive)
                  BucketZoneBands(startOffset, endOffset, rhrZoneBandsForBaseline(baseline, rhrOptimalThreshold, rhrWarningThreshold))
              }
          }
  }
  ```
  The `else` branch is byte-for-byte the logic that exists today, just moved inside the branch
  instead of behind `if (range.granularity == TrendGranularity.DAILY) emptyList() else ...` —
  180D/360D behavior is unchanged. The rest of `buildVitalsChartSeries` (the closing
  `return VitalsChartSeries(...)`) is unchanged — it already just references these `val`s.

### 4c. `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`

The `zoneBands` selection ternary in both `HrvTrendChartBlock` and `RhrTrendChartBlock` must
switch on whether historical baseline data exists for the current range, not on granularity
(which stays `DAILY` at 30D under this approach):

```kotlin
zoneBands =
    if (chartSeries.historicalHrvBaseline.isEmpty()) {
        presentation.hrv.zoneBands
    } else {
        chartSeries.historicalHrvZoneBands
    },
```

(mirrored for RHR, substituting `historicalRhrBaseline`/`presentation.rhr.zoneBands`/
`historicalRhrZoneBands`). This reuses the same non-empty check already used two lines below for
`historicalBaseline = ...takeIf { it.isNotEmpty() }` and `bucketZoneBands =
...takeIf { it.isNotEmpty() }`, so all three stay consistent. `granularity =
chartInputs.selectedRange.granularity` is still passed straight through to `TrendChart`
unchanged, so axis/tooltip date formatting at 7D/30D is untouched. Remove the `TrendGranularity`
import from this file if it becomes unused after this edit (its only other use,
`chartInputs.selectedRange.granularity`, is a property access and needs no type import).

No other line in this file changes.

## 5. Test plan

### `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt`

1. Rename `` `DAILY range produces empty historical baseline series` `` →
   `` `SEVEN_DAYS range with unfrozen baseline produces empty historical baseline series` ``.
   Assertions unchanged — its fixture has zero frozen days (`baselineCalculatedAt = null`), so
   `rhrBaselineRounded`/`hrvBaselineRounded` return `null` regardless of range; the rename just
   reflects that it was never really testing a range-granularity gate, only "no frozen data →
   empty series," which remains true.
2. New: `` `SEVEN_DAYS range plots per-day historical baseline when frozen` `` — 7 daily
   `DailySummary`s spanning `start..start+6`, each frozen with a distinct `rhrBpm`/`hrvMuMssd`.
   Assert `historicalRhrBaseline.map { it.dayOffset } == (0..6).toList()` with each value equal
   to that day's own frozen baseline (unaveraged); `historicalRhrBucketZoneBands.size == 7` with
   `startDayOffset == i && endDayOffset == i + 1` for each `i`; `historicalRhrBaselineAverage ==`
   the rounded mean of the 7 values. Mirror for HRV.
3. New: `` `THIRTY_DAYS range averages historical baseline into 2-day buckets` `` — 30 daily
   frozen summaries spanning `start..start+29` with `start` deliberately crossing a month
   boundary (e.g. `LocalDate.of(2026, 1, 20)`), distinct values per day. Assert
   `historicalRhrBaseline.size == 15` with `dayOffsets == [0, 2, 4, ..., 28]`, each bucket's
   value equal to the average of that day-pair's two raw values; `historicalRhrBucketZoneBands`
   has boundaries exactly `[0,2), [2,4), ..., [28,30)`; `historicalRhrBaselineAverage` equals the
   rounded mean of all 30 raw values (construct the fixture so this provably differs from the
   mean of the 15 bucket averages, e.g. via test 4's omitted-pair scenario, to prove the average
   is computed off the raw series, not the bucketed one).
4. New: `` `THIRTY_DAYS bucket omits pair with zero frozen days` `` — one pair (e.g. offsets
   10-11) both unfrozen. Assert `historicalRhrBaseline.size == 14` with no entry at midpoint `10`.
5. New: `` `THIRTY_DAYS bucket averages partial pair with one frozen day` `` — one pair has
   exactly one frozen day. Assert that bucket's value equals the single frozen value exactly
   (not halved), while its `BucketZoneBands` still spans the full 2-day nominal width.
6. No changes to the existing 180D/360D tests (`historical baseline averages only frozen days
   per bucket`, `historical zone bands empty when no frozen baselines`, `historical baseline
   honors override when no frozen days`, `all four metrics bucket together with independent
   averages`) — they exercise the untouched `else` branch via `TimeRange.SIX_MONTHS`.

### `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregationTest.kt`

7. New pure-Kotlin tests for `bucketByFixedSize` (zero Android dependencies, per this repo's
   testing convention): `bucketSizeDays = 1` passthrough (no averaging); `bucketSizeDays = 2`
   pairing/averaging with correct `startDayOffset`/`endDayOffsetExclusive`/`midpointDayOffset`;
   an empty bucket (all-null pair) omitted entirely; a single-day partial bucket averaging to
   that lone value; `rangeEndOffsetExclusive` clipping the final bucket short; `valueDecimalPlaces`
   rounding matching `bucketBy`'s convention.

### `TimeRangeTest.kt`

No changes — `` `short ranges map to daily granularity` `` must keep passing unmodified,
confirming the chosen approach leaves `TrendGranularity`/`TimeRange` untouched.

## 6. Documentation sync

Update `internal-docs/DATA_FLOW.md` (~lines 584-597, the Vitals trend chart section). Replace
the current "At 180D/360D the RHR/HRV charts additionally plot a muted per-bucket historical
baseline line..." wording with prose stating the overlay now spans all four ranges at
range-appropriate granularity: 7D unbucketed (one point per frozen day), 30D via the new
`bucketByFixedSize` non-overlapping 2-day buckets (anchored at the window's `startDate`,
independent of `TrendGranularity`), 180D/360D unchanged via the existing calendar `bucketBy`
path. Keep the existing statement that the whole-range average is always the mean of every
frozen per-day baseline, never a mean of bucket averages, and update "the flat today-baseline
`HorizontalLine` is replaced by this line whenever historical baseline data is present" to note
this now applies "at any range," not just the averaged ones.

## 7. Verification (to run once implementation lands)

- `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`,
  then `./gradlew lintRelease` once all coding tasks are resolved (per this repo's mandatory
  pre-commit sequence).
- Run the new/updated unit tests directly: `./gradlew :feature:vitals:testDebugUnitTest` and
  `./gradlew :core:ui:testDebugUnitTest`.
- Manual check via `./gradlew installDebug`: open the Vitals tab, select 7D — confirm the
  HRV/RHR baseline line now fluctuates day-to-day (not flat) and the zone shading flows with it;
  select 30D — confirm 15 visible baseline segments across the month; select 180D/360D — confirm
  no visual change from before this change.

## 8. Critical files

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregation.kt`
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`
- `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt`
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregationTest.kt`
- `internal-docs/DATA_FLOW.md`
