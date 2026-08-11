# Historical Baseline for Long-Term Trends (180D / 360D) — Plan

**Status:** PLAN — awaiting approval. No implementation code has been written.
**Branch:** `claude/historical-baseline-longterm-442dib`
**Scope:** RHR and HRV trend charts in `feature/vitals` (`VitalsTrendSection`), their backing
aggregation (`feature/vitals/.../overview/VitalsStateFactory.kt`), the shared chart component
(`core/ui/.../components/TrendCharts.kt`), and the shared zone-band assessment functions
(`core/model/.../domain/model/VitalAssessment.kt`).
**Explicitly out of scope** (confirmed with the requester): SpO2 (hardcoded `95f` baseline
constant), body temperature (separate `BodyTemperatureBaselineProvider`, a 14-day rolling
average, not the scoring-engine's frozen per-day baseline), and Weight/BodyFat/Steps/Blood
Pressure charts (their "baseline" is a window average, a user goal, or a fixed clinical
constant — not the scoring-engine baseline this task is about).

This document is self-contained: it does not assume the reader has seen any prior
conversation. It cites exact files, functions, and current code so it can be handed directly
to an implementer.

**§9 addendum** extends scope beyond RHR/HRV: it changes the shared `TrendGranularity` bucket
size for the 360D range, which is infrastructure also used by Weight, Steps, BodyFat, Blood
Pressure, Sleep, and Workouts (ACWR) trend charts — not just the vitals baseline work above.

---

## 1. Problem

`app.readylytics.health` (Readylytics) computes and freezes a rolling physiological baseline
for resting heart rate (RHR) and heart-rate variability (HRV) once per calendar day, as part
of the sync/resync walk-forward recompute (see `.claude/CLAUDE.md`, "Domain Rules & Engine" and
"Sync & Recalculation"). That frozen, point-in-time-correct value is what the Vitals screen's
RHR and HRV trend charts draw as a horizontal "Baseline" reference line, via the shared
`TrendChart` composable.

The bug: regardless of which time range is selected — 7D, 30D, 180D, or 360D — the chart
always draws **today's current baseline value** as a single flat line across the *entire*
chart width. For 180D/360D this is materially misleading: a chart showing January through
December overlays December's (or today's) baseline value across January's data, even though
the baseline actually computed and frozen back in January may have been substantially
different. The RHR/HRV background zone bands (colored reference stripes) have the identical
problem — their thresholds are scaled off that same "today" value.

### Current code (today's single baseline, flat across the whole chart)

`feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`
(HRV chart, lines 57–73; RHR chart, lines 94–110):

```kotlin
TrendChart(
    points = chartSeries.hrv,
    rangeStartMs = chartInputs.rangeStartMs,
    rangeDays = chartInputs.selectedRange.days,
    metricName = stringResource(CoreUiR.string.label_hrv),
    baselineUnit = stringResource(CoreUiR.string.unit_ms),
    modifier = Modifier.testTag("HrvTrendChart"),
    baseline = presentation.hrv.baseline?.toFloat(),      // <- single scalar, "today"
    showBaseline = presentation.hrv.baseline != null,
    scrollState = chartScrollState,
    zoomState = chartZoomState,
    zoneBands = presentation.hrv.zoneBands,                // <- also derived from "today"
    parentScrollInProgress = parentScrollInProgress,
    granularity = chartInputs.selectedRange.granularity,
    periodSummary = chartSeries.hrvPeriodSummary,
    deltaDirection = DeltaDirection.HIGHER_IS_BETTER,
)
```

`presentation.hrv`/`presentation.rhr` (`VitalsPresentationState`) is built once in
`VitalsStateFactory.buildVitalsPresentationState` (same file below) from **only the currently
selected day's** `DailyMetrics` — it does not vary with the selected `TimeRange` at all.

`core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt`
draws that single scalar as one Vico `HorizontalLine` decoration spanning the full chart
(lines 158–161, 297–311):

```kotlin
val calculatedBaseline = requireNotNull(renderData.calculatedBaseline)
// Use provided baseline if available, otherwise fall back to calculated baseline
val baselineValue = baseline ?: calculatedBaseline
...
val baselineLineComponent = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp)
val decorations =
    remember(zoneBandDecoration, shouldShowBaseline, baselineValue, baselineLineComponent) {
        listOfNotNull(
            zoneBandDecoration,
            if (shouldShowBaseline) {
                HorizontalLine(
                    y = { baselineValue.toDouble() },   // constant across the full X range
                    line = baselineLineComponent,
                )
            } else {
                null
            },
        )
    }
```

## 2. Goal

For **7D/30D**: no change.

For **180D/360D**: replace the flat "today" baseline line with a **historical baseline line**
built from the stored per-day frozen baseline values across the displayed date range,
aggregated the same way the metric itself is bucketed (monthly average for 180D, quarterly
average for 360D — see `TrendGranularity` below), so the line's shape reflects how the
baseline actually drifted over that period. Missing days/buckets must be dropped, never
fabricated. The RHR/HRV background zone bands must use a historical baseline (the average
across the whole displayed window), not today's value. The legend must read distinctly
("Historical baseline") so it's clear this isn't the live current baseline.

---

## 3. Existing infrastructure to reuse (do not duplicate)

### 3.1 Where the frozen per-day baseline lives

`core/model/src/main/kotlin/app/readylytics/health/data/local/entity/DailySummaryEntity.kt` —
table `daily_summaries`, one row per calendar day, primary-keyed by `dateMidnightMs`. Relevant
columns (present since `MIGRATION_1_2`, `core/database/.../DatabaseMigrations.kt`):

```kotlin
@ColumnInfo(name = "hrv_mu_mssd")
val hrvMuMssd: Float? = null,      // ln-space HRV mean, frozen for the day

@ColumnInfo(name = "rhr_bpm")
val rhrBpm: Float? = null,         // frozen RHR baseline (precise float)

@ColumnInfo(name = "baseline_calculated_at_date")
val baselineCalculatedAtDate: LocalDate? = null,   // non-null = this row's baseline is frozen
```

There is **no separate "Baseline" table** — every day's own row already carries its own
baseline snapshot. `baselineCalculatedAtDate != null` is the freeze marker; a `null` day means
the baseline hasn't been computed for that date yet (e.g., still calibrating, or a gap).

### 3.2 Where "today's" baseline scalar is derived — reuse this, don't re-derive

`core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt`
(lines 84–111):

```kotlin
private fun deriveRhrBaselineRaw(
    summary: DailySummary,
    prefs: UserPreferences,
): Float? =
    acceptedRhrSnapshotRaw(summary)
        ?: prefs.rhrBaselineOverride

fun rhrBaselineRounded(
    summary: DailySummary,
    prefs: UserPreferences,
): Int? = deriveRhrBaselineRaw(summary, prefs)?.roundToInt()

private fun acceptedRhrSnapshotRaw(summary: DailySummary): Float? =
    summary.rhrBpm.takeIf { summary.baselineCalculatedAtDate != null }

/**
 * The HRV baseline rounded to whole ms, exactly as shown on the dashboard. Callers
 * comparing a day's HRV to its baseline (e.g. insight rules) must reuse this instead of
 * re-deriving the rounding independently, so "below baseline" always agrees with what
 * the UI displays.
 */
fun hrvBaselineRounded(
    summary: DailySummary,
    prefs: UserPreferences,
): Int? =
    summary.hrvMuMssd?.let { exp(it).roundToInt() }
        ?: prefs.hrvBaselineOverride?.roundToInt()
        ?: summary.hrvBaseline
```

Confirmed: neither function falls back to a hardcoded default (e.g.
`ScoringConstants.DEFAULT_RHR_BPM`, which only applies inside the live scoring path in
`ScoringRepositoryImpl.computeDailySummary`, not here). A day with `baselineCalculatedAtDate ==
null` and no user override yields `null` — exactly the "don't fabricate" behavior this task
requires. **This mapper is the single existing source of truth for a day's baseline display
value and must be reused, not re-implemented, for the historical series.**

### 3.3 Where the full-range data already gets fetched

`feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`
already receives `summaries: List<DailySummary>` spanning the *entire* selected chart window
(fetched upstream in `VitalsViewModel` via `dailySummaryRepository.observeSince(...)`, then
range-filtered) and builds the metric series with it:

```kotlin
internal fun buildVitalsChartSeries(
    summaries: List<DailySummary>,
    startDate: LocalDate,
    range: TimeRange,
    unitSystem: UnitSystem,
    endDate: LocalDate = startDate.plusDays(range.days.toLong() - 1),
): VitalsChartSeries {
    fun realPoints(value: (DailySummary) -> Float?): List<DailyDataPoint> =
        summaries
            .filter { it.date in startDate..endDate }
            .mapNotNull { summary ->
                value(summary)?.let {
                    DailyDataPoint(ChronoUnit.DAYS.between(startDate, summary.date).toInt(), it)
                }
            }.sortedBy(DailyDataPoint::dayOffset)

    val (hrvPoints, hrvSummary) =
        realPoints { it.nocturnalHrv?.toFloat() }
            .aggregateByRange(range.granularity, startDate, endDate, range.days)
    val (rhrPoints, rhrSummary) =
        realPoints { it.restingHeartRate?.toFloat() }
            .aggregateByRange(range.granularity, startDate, endDate, range.days)
    // ... spo2, bodyTemp identical shape ...

    return VitalsChartSeries(
        hrv = hrvPoints,
        rhr = rhrPoints,
        // ...
    )
}
```

**No new Room query, DAO method, or repository method is needed.** `summaries` already
contains every day's `rhrBpm`/`hrvMuMssd`/`baselineCalculatedAtDate` columns for the whole
displayed range.

### 3.4 Where bucket aggregation (monthly/quarterly averaging) already lives — reuse this

`core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregation.kt`:

```kotlin
enum class TrendGranularity { DAILY, MONTHLY, QUARTERLY }   // (defined in TimeRange.kt)

// TimeRange.kt:
// SEVEN_DAYS(7, "7D", TrendGranularity.DAILY)
// THIRTY_DAYS(30, "30D", TrendGranularity.DAILY)
// SIX_MONTHS(180, "180D", TrendGranularity.MONTHLY)
// TWELVE_MONTHS(360, "360D", TrendGranularity.QUARTERLY)

/**
 * Groups [DailyDataPoint]s by calendar month or quarter (per [granularity]), averages each
 * bucket's non-null values, rounds that average to [valueDecimalPlaces], and emits one point per
 * populated bucket positioned at that bucket's midpoint calendar day offset relative to [startDate].
 * Buckets with no data are omitted entirely. `DAILY` returns the original non-null points sorted by
 * day offset (no averaging).
 */
fun List<DailyDataPoint>.bucketBy(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate? = null,
    valueDecimalPlaces: Int = 0,
): List<DailyDataPoint> {
    val present = filter { it.value != null }
    if (granularity == TrendGranularity.DAILY) {
        return present.sortedBy(DailyDataPoint::dayOffset)
    }
    val buckets =
        present
            .groupBy { it.bucketStart(granularity, startDate) }
            .map { (bucketStart, points) -> Bucket(bucketStart, points) }
            .sortedBy(Bucket::start)
    return buckets.map { bucket ->
        val average =
            bucket.points.mapNotNull(DailyDataPoint::value).average().toFloat()
                .roundToDecimalPlaces(valueDecimalPlaces)
        DailyDataPoint(bucketMidpointOffset(bucket.start, granularity, startDate, endDate), average)
    }
}
```

This is exactly "average of the daily baseline values within each month/quarter, dropping
empty buckets rather than fabricating values" — already implemented and already unit-tested
(`core/ui/src/test/kotlin/.../common/TrendPeriodAggregationTest.kt`). **Reuse `bucketBy`
directly for the baseline series; do not write a parallel aggregation function.**

### 3.5 Where zone-band thresholds are computed from a baseline scalar

`core/model/src/main/kotlin/app/readylytics/health/domain/model/VitalAssessment.kt`
(full relevant excerpt):

```kotlin
fun assessHrv(
    value: Int?,
    baseline: Int?,
    optimalRatio: Float,
    warningRatio: Float,
): PersonalBaselineAssessment =
    assessPersonalBaseline(
        value = value,
        baseline = baseline,
        statusForRatio = { ratio -> hrvStatusFromRatio(ratio, optimalRatio, warningRatio) },
        zoneBandsForBaseline = { roundedBaseline ->
            hrvZoneBandsForThresholds(
                optimalMin = scaledThreshold(roundedBaseline, optimalRatio),
                warningMin = scaledThreshold(roundedBaseline, warningRatio),
                poorMin = roundedBaseline * hrvPoorRatio(warningRatio),
            )
        },
    )

fun assessRhr(
    value: Int?,
    baseline: Int?,
    optimalRatio: Float,
    warningRatio: Float,
): PersonalBaselineAssessment =
    assessPersonalBaseline(
        value = value,
        baseline = baseline,
        statusForRatio = { ratio -> rhrStatusFromRatio(ratio, optimalRatio, warningRatio) },
        zoneBandsForBaseline = { roundedBaseline ->
            rhrZoneBandsForThresholds(
                optimalMax = scaledThreshold(roundedBaseline, optimalRatio),
                warningMax = scaledThreshold(roundedBaseline, warningRatio),
                poorMax = roundedBaseline * rhrPoorRatio(warningRatio),
            )
        },
    )

// zoneBandsForBaseline is only invoked when baseline != null (assessPersonalBaseline,
// line ~95): zoneBands = positiveBaseline?.let(zoneBandsForBaseline)

internal fun rhrZoneBandsForThresholds(optimalMax: Double, warningMax: Double, poorMax: Double): List<ZoneBand> = /* ... */
internal fun hrvZoneBandsForThresholds(optimalMin: Double, warningMin: Double, poorMin: Double): List<ZoneBand> = /* ... */
private fun scaledThreshold(baseline: Int, ratio: Float): Double = /* BigDecimal-precise baseline * ratio */
private fun rhrPoorRatio(warningRatio: Float): Double = /* ... */
private fun hrvPoorRatio(warningRatio: Float): Double = /* ... */
```

**Important correction:** `rhrZoneBandsForThresholds`, `hrvZoneBandsForThresholds`,
`scaledThreshold`, `rhrPoorRatio`, and `hrvPoorRatio` are all `internal`/`private` to
`core/model`. `VitalsStateFactory` lives in the separate `feature/vitals` Gradle module and
**cannot call them today.** Rather than duplicating the threshold math in `feature/vitals`,
extract the existing `zoneBandsForBaseline` lambda bodies into two new **public** functions in
`VitalAssessment.kt`, and have `assessRhr`/`assessHrv` call them (net zero duplication):

```kotlin
// New public functions in VitalAssessment.kt:
fun rhrZoneBandsForBaseline(
    baseline: Int,
    optimalRatio: Float,
    warningRatio: Float,
): List<ZoneBand> =
    rhrZoneBandsForThresholds(
        optimalMax = scaledThreshold(baseline, optimalRatio),
        warningMax = scaledThreshold(baseline, warningRatio),
        poorMax = baseline * rhrPoorRatio(warningRatio),
    )

fun hrvZoneBandsForBaseline(
    baseline: Int,
    optimalRatio: Float,
    warningRatio: Float,
): List<ZoneBand> =
    hrvZoneBandsForThresholds(
        optimalMin = scaledThreshold(baseline, optimalRatio),
        warningMin = scaledThreshold(baseline, warningRatio),
        poorMin = baseline * hrvPoorRatio(warningRatio),
    )
```

Then simplify `assessRhr`/`assessHrv`'s lambdas to `zoneBandsForBaseline = { roundedBaseline ->
rhrZoneBandsForBaseline(roundedBaseline, optimalRatio, warningRatio) }` (and the HRV
equivalent). This guarantees the "today" zone bands (used elsewhere, e.g. the gauge row) and
the new "historical" zone bands are always computed by the exact same formula — only the
`baseline` input differs.

---

## 4. Proposed changes

### 4.1 `feature/vitals/.../overview/VitalsStateFactory.kt`

Add `prefs: UserPreferences` as a new parameter to `buildVitalsChartSeries` (not currently
passed; needed to call `DailyMetricsMapper.rhrBaselineRounded`/`hrvBaselineRounded`, which
require it for the user-override fallback — same precedence as "today's" baseline).

Add two new baseline point-series, built with the same `realPoints` + `bucketBy` shape already
used for the metric series, gated to only populate for non-`DAILY` granularity so 7D/30D are
untouched:

```kotlin
val historicalRhrBaseline =
    if (range.granularity == TrendGranularity.DAILY) {
        emptyList()
    } else {
        realPoints { DailyMetricsMapper.rhrBaselineRounded(it, prefs)?.toFloat() }
            .bucketBy(range.granularity, startDate, endDate)
    }
val historicalHrvBaseline =
    if (range.granularity == TrendGranularity.DAILY) {
        emptyList()
    } else {
        realPoints { DailyMetricsMapper.hrvBaselineRounded(it, prefs)?.toFloat() }
            .bucketBy(range.granularity, startDate, endDate)
    }
```

Compute the whole-range average baseline (for zone-band thresholds) from the **raw per-day**
values (before bucketing, to avoid an average-of-monthly-averages bias):

```kotlin
val historicalRhrZoneBands =
    if (range.granularity == TrendGranularity.DAILY) {
        emptyList()
    } else {
        realPoints { DailyMetricsMapper.rhrBaselineRounded(it, prefs)?.toFloat() }
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.roundToInt()
            ?.let { rhrZoneBandsForBaseline(it, prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold) }
            ?: emptyList()
    }
// hrvZoneBandsForBaseline analogous, using prefs.hrvOptimalThreshold / prefs.hrvWarningThreshold
```

Add the four new fields to `VitalsChartSeries`:

```kotlin
@Immutable
data class VitalsChartSeries(
    val hrv: List<DailyDataPoint>,
    val rhr: List<DailyDataPoint>,
    val spo2: List<DailyDataPoint>,
    val bodyTemp: List<DailyDataPoint>,
    val hrvPeriodSummary: PeriodAverageSummary? = null,
    val rhrPeriodSummary: PeriodAverageSummary? = null,
    val spo2PeriodSummary: PeriodAverageSummary? = null,
    val bodyTempPeriodSummary: PeriodAverageSummary? = null,
    val historicalRhrBaseline: List<DailyDataPoint> = emptyList(),   // new
    val historicalHrvBaseline: List<DailyDataPoint> = emptyList(),   // new
    val historicalRhrZoneBands: List<ZoneBand> = emptyList(),        // new
    val historicalHrvZoneBands: List<ZoneBand> = emptyList(),        // new
)
```

`VitalsViewModel.kt` calls `buildVitalsChartSeries(...)` — find that call site and add
`prefs = inputs.preferences` (the `UserPreferences` value it already has in scope for the
adjacent `buildVitalsPresentationState(...)` call a few lines below).

### 4.2 `core/ui/.../components/TrendCharts.kt`

Add a new optional parameter to `TrendChart`:

```kotlin
@Composable
fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    metricName: String,
    baselineUnit: String,
    modifier: Modifier = Modifier,
    baseline: Float? = null,
    baselineLabel: String? = null,
    baselineUnavailableLabel: String? = null,
    baselineDecimalPlaces: Int = 0,
    // ... existing params unchanged ...
    historicalBaseline: List<DailyDataPoint>? = null,   // NEW
) {
```

Behavior when `historicalBaseline` is non-null and non-empty (only true for RHR/HRV at
180D/360D per §4.1's gating):

1. **Model:** add a second series to the existing `modelProducer.runTransaction { lineModel {
   ... } }` block (`TrendCharts.kt:190-199`) — Vico's `lineModel { }` builder supports multiple
   `series(x=, y=)` calls in one transaction, rendering one `LineCartesianLayer.Line` per
   series in declaration order:

   ```kotlin
   LaunchedEffect(renderData.validPoints, historicalBaseline) {
       modelProducer.runTransaction {
           lineModel {
               series(
                   x = renderData.validPoints.map(DailyDataPoint::dayOffset),
                   y = renderData.validPoints.map { requireNotNull(it.value).toDouble() },
               )
               if (!historicalBaseline.isNullOrEmpty()) {
                   series(
                       x = historicalBaseline.map(DailyDataPoint::dayOffset),
                       y = historicalBaseline.map { requireNotNull(it.value).toDouble() },
                   )
               }
           }
       }
   }
   ```

2. **Line style:** build a second `LineCartesianLayer.Line`, visually subdued relative to the
   primary line (`TrendCharts.kt:226-235` builds the primary `line` with `areaFill` + circular
   point markers) — the historical baseline line should have `baselineColor`
   (`MaterialTheme.colorScheme.onSurfaceVariant`, the same color already used for the flat
   line/legend swatch), a thin stroke, and **no** `areaFill` and **no** point markers, so it
   reads as a muted reference line rather than a second data series:

   ```kotlin
   val historicalBaselineLine =
       remember(baselineColor) {
           LineCartesianLayer.rememberLine(
               fill = LineCartesianLayer.LineFill.single(Fill(baselineColor)),
           )
       }
   ```
   (Exact Vico API for "no point markers" — omit `pointProvider`, which defaults to none —
   confirm against the installed Vico version's API before finalizing; this is the one
   genuinely new pattern in the codebase, see "Implementation risk" below.)

3. **`lineProvider`:** widen from `LineCartesianLayer.LineProvider.series(line)`
   (`TrendCharts.kt:284`) to `LineCartesianLayer.LineProvider.series(line,
   historicalBaselineLine)` when `historicalBaseline` is supplied, else keep the single-line
   provider unchanged.

4. **Decorations:** when `historicalBaseline` is supplied, drop the flat `HorizontalLine`
   decoration entirely (`TrendCharts.kt:298-311`) — the two are mutually exclusive. Keep
   `zoneBandDecoration` as-is (its bands now come from `historicalRhrZoneBands`/
   `historicalHrvZoneBands` passed in via the existing `zoneBands` parameter — no change needed
   inside `TrendChart` itself for that part, since `zoneBandDecoration` already just consumes
   whatever `zoneBands: List<ZoneBand>?` the caller passes).

5. **Legend:** `BaselineLegend` (`TrendCharts.kt:374-384`) currently shows the scalar
   `baselineValue`. For the historical case, pass the same whole-range-average value the
   caller used to build `historicalRhrZoneBands`/`historicalHrvZoneBands` (so the legend number
   and the zone-band shading always agree) via the existing `baseline: Float?` parameter —
   callers keep passing `baseline` as today (it becomes "the reference scalar for legend/zone
   math"), while `historicalBaseline` is purely the line-rendering data. Swap the label to a
   new string resource when `historicalBaseline` is non-null:

   ```kotlin
   val resolvedBaselineLabel =
       baselineLabel
           ?: if (!historicalBaseline.isNullOrEmpty()) {
               stringResource(R.string.label_historical_baseline)
           } else {
               stringResource(R.string.label_baseline)
           }
   ```

When `historicalBaseline` is `null` (the default): zero behavior change for every other
`TrendChart` caller (Weight, Steps, BodyFat, BloodPressure, Vitals SpO2/body-temp, and RHR/HRV
at 7D/30D).

**New string resource** — `core/ui/src/main/res/values/strings.xml` (alongside the existing
`label_baseline` at line 48):

```xml
<string name="label_historical_baseline">Historical baseline</string>
```

### 4.3 `feature/vitals/.../overview/VitalsTrendSection.kt`

For the HRV chart (lines 57–73) and RHR chart (lines 94–110), add:

```kotlin
historicalBaseline =
    chartSeries.historicalHrvBaseline.takeIf { it.isNotEmpty() },   // HRV chart
// and:
historicalBaseline =
    chartSeries.historicalRhrBaseline.takeIf { it.isNotEmpty() },   // RHR chart
```

and switch `baseline`/`zoneBands` to the historical values whenever they're populated (i.e. at
180D/360D), keeping today's values otherwise:

```kotlin
baseline =
    chartSeries.historicalHrvBaseline
        .takeIf { it.isNotEmpty() }
        ?.let { /* whole-range average computed in VitalsStateFactory; expose alongside
                   historicalRhrZoneBands/historicalHrvZoneBands, e.g. as a paired
                   Float? field, rather than recomputing here */ }
        ?: presentation.hrv.baseline?.toFloat(),
showBaseline = /* unchanged */,
zoneBands = chartSeries.historicalHrvZoneBands.takeIf { it.isNotEmpty() } ?: presentation.hrv.zoneBands,
```

**Implementation note:** §4.1's `VitalsChartSeries` needs to also expose the whole-range
average scalar itself (not just the derived `ZoneBand` list) so the call site above can pass it
as `baseline`. Simplest: add `historicalRhrBaselineAverage: Int?` /
`historicalHrvBaselineAverage: Int?` fields to `VitalsChartSeries` alongside the zone-band
fields, both computed from the same `average()` call in §4.1 (compute it once, derive both the
scalar field and the zone-band field from it — don't compute the average twice).

The SpO2 and body-temperature `TrendChart` calls (lines 131–152, 173–201) are untouched.

---

## 5. Missing-data / fallback behavior

- `bucketBy` already drops empty buckets rather than fabricating a value (§3.4) — an
  uncalibrated stretch of months simply produces gaps in the historical baseline line/legend,
  matching "handle gracefully, don't fabricate." This is the same mechanism the primary metric
  series already relies on, so no new gap-handling code is needed.
- If the entire selected range has zero frozen-baseline days (e.g. a brand-new install, still
  calibrating), `historicalRhrBaseline`/`historicalHrvBaseline` are empty lists →
  `TrendChart` falls through to its existing `baselineUnavailableLabel`/"Calibrating" path
  (already wired via `showBaseline`/`baselineUnavailableLabel`) rather than showing nothing or
  silently reverting to today's baseline.
- Zone bands: whole-range average `null` (no data) → `historicalRhrZoneBands`/
  `historicalHrvZoneBands` are empty lists → no background shading for that chart at that
  range. Never fall back to today's threshold.
- A user-set global `rhrBaselineOverride`/`hrvBaselineOverride` preference (if present) applies
  uniformly to every day via the reused mapper (§3.2), same as it does for "today's" baseline
  today — this makes the historical series flat under an override, which is expected/
  consistent behavior, not a new edge case introduced by this change.

---

## 6. Tests

- `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt` —
  new cases for `buildVitalsChartSeries`:
  - 7D/30D (`DAILY`) → `historicalRhrBaseline`/`historicalHrvBaseline`/
    `historicalRhrZoneBands`/`historicalHrvZoneBands` are all empty (old scalar path stays in
    control, verifying the gating in §4.1).
  - 180D (`MONTHLY`) → given `DailySummary` rows spanning two months with a mix of frozen
    (`baselineCalculatedAtDate != null`) and unfrozen days, assert the bucketed output equals
    the average of only the frozen days in each month, and that a month with zero frozen days
    is omitted from the list entirely (not present as zero, not fabricated).
  - Zone-band/average source: assert `historicalRhrZoneBands` is empty when the range has no
    frozen baseline data, and matches `rhrZoneBandsForBaseline(expectedAverage, ...)` otherwise
    (import from `core/model`).
- `core/model/src/test/kotlin/app/readylytics/health/domain/model/VitalAssessmentTest.kt`
  (or wherever `assessRhr`/`assessHrv` are currently tested) — add cases for the two new public
  functions `rhrZoneBandsForBaseline`/`hrvZoneBandsForBaseline`, asserting they produce the
  identical band boundaries `assessRhr`/`assessHrv` already produce for the same baseline/ratio
  inputs (regression-proofing the refactor in §3.5 that extracts the lambda bodies).
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregationTest.kt` —
  existing generic `bucketBy` coverage already validates the aggregation math being reused; add
  a test only if baseline-shaped input (sparse/gappy series) surfaces a case not already
  covered.
- `core/ui` — if the Vico multi-series wiring in §4.2 introduces any new pure helper (e.g. a
  function that decides which decorations/line providers to build given
  `historicalBaseline`), unit test that helper directly. No existing Compose UI test exercises
  `TrendChart`'s decoration wiring end-to-end, so don't block on adding one — but manually
  verify in the running app that: 180D/360D RHR and HRV charts show a wiggly muted line instead
  of a flat one, the flat line is fully gone for those ranges and metrics, 7D/30D are visually
  unchanged, the legend reads "Historical baseline" only at 180D/360D, and a fresh/uncalibrated
  account shows "Calibrating" rather than a fabricated flat line.

**Documentation Sync check (per `.claude/CLAUDE.md`):** this change touches only
visualization of already-frozen baseline columns in `core/ui`/`feature/vitals` — no scoring
formula, coefficient, use-case, or Room schema change — so `ABOUT.md`/`docs/about.md`/
`internal-docs/DATA_FLOW.md` updates are not mandated by the "Documentation Synchronization
Rule." Do a quick read of `internal-docs/DATA_FLOW.md`'s trend-chart section (if any) to check
it doesn't claim the chart shows "today's baseline" as a blanket statement; correct the wording
if it does.

---

## 7. Implementation risk

The one genuinely new pattern here is a **multi-series Vico line chart** — nothing in the
codebase today renders two independently-positioned line series in one `CartesianChartHost`
(the closest analog, `BloodPressureSplitChart.kt`, draws two separate charts, not two series in
one). Before wiring this into `VitalsTrendSection`, spike `lineModel { series(...);
series(...) }` + `LineCartesianLayer.LineProvider.series(line1, line2)` in isolation
(e.g. a throwaway `@Preview`) to confirm:
- two series with different/sparse x-domains render correctly against the shared
  `CartesianLayerRangeProvider.fixed(...)` range (`TrendCharts.kt:204-212`);
- a `Line` built without `pointProvider`/`areaFill` renders as a plain subdued stroke, not a
  crash or an invisible line;
- the existing marker/tooltip logic (`markerVisibilityListener`, `TrendCharts.kt:247-282`),
  which currently reads only from `renderData.pointByDayOffset` (the primary series), is
  unaffected by the second series being present — it should continue to report only the
  primary metric's value in the tooltip, which needs no behavior change, just confirmation the
  second series doesn't interfere with hit-testing.

---

## 8. Verification

1. `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` — new/updated unit tests above, plus
   full existing suite for regressions in `core/ui`, `core/model`, `feature/vitals`.
2. `./gradlew installDebug` — open Vitals, select the HRV and RHR cards (or the overview mini-
   charts), cycle through 7D → 30D → 180D → 360D, and confirm:
   - 7D/30D: pixel-identical to current behavior (flat line at today's baseline, legend reads
     "Baseline").
   - 180D/360D: a wiggly, muted historical-baseline line instead of a flat one; legend reads
     "Historical baseline: N ms/bpm"; background zone bands (if present) reflect the
     whole-range average, not today's value; a data-sparse or fresh-install account shows
     "Calibrating" rather than a fabricated line.
3. `./gradlew lintRelease` at the end, per `.claude/CLAUDE.md`.

---

## 9. Addendum: 360D bucket size — quarterly → 8-week (ISO week) buckets

**Why:** `TrendGranularity.QUARTERLY` (the 360D range's bucket size) produces only 4 data
points across a full year — too coarse for a meaningful trend line, including the historical
baseline line from §4. Reducing the bucket to 8 weeks yields ~6–7 points/year instead, while
staying large enough to average out day-to-day noise.

**Naming, agreed:**
- Chart x-axis ticks and the `PeriodAverageSummaryRow` period labels (the short form, same role
  "Q3" plays today): **`"Wk 9"`** — new string resource `label_week_short` = `"Wk %1$d"`
  (mirrors the existing `period_label_quarter` = `"Q%1$d"`, `core/ui/src/main/res/values/strings.xml:98`-ish).
- Tooltip (richer form, since an 8-week span is less immediately legible than a quarter):
  **`"Weeks 9–16"`** — new string resource `tooltip_week_range` = `"Weeks %1$d–%2$d"`.
- The week number is the real **ISO week-of-week-based-year** (`java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR`),
  not an arbitrary bucket index — see bucket-anchoring rationale below.

### 9.1 Enum: replace, don't add

`core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TimeRange.kt`:

```kotlin
enum class TrendGranularity { DAILY, MONTHLY, QUARTERLY }   // current
enum class TrendGranularity { DAILY, MONTHLY, EIGHT_WEEK }  // proposed

enum class TimeRange(val days: Int, val label: String, val granularity: TrendGranularity) {
    SEVEN_DAYS(7, "7D", TrendGranularity.DAILY),
    THIRTY_DAYS(30, "30D", TrendGranularity.DAILY),
    SIX_MONTHS(180, "180D", TrendGranularity.MONTHLY),
    TWELVE_MONTHS(360, "360D", TrendGranularity.EIGHT_WEEK),   // was QUARTERLY
}
```

`TWELVE_MONTHS`/360D is the **only** `TimeRange` that ever maps to this granularity, so this is
a straight rename/replace, not an addition — keeping `QUARTERLY` alongside `EIGHT_WEEK` would
leave permanently dead code. Because `TrendGranularity` is branched on via **exhaustive `when`
blocks** everywhere it's consumed, the Kotlin compiler will flag every remaining `QUARTERLY`
reference as a compile error the moment the enum value is renamed — this makes the refactor
mechanical and hard to leave incomplete.

### 9.2 Bucket anchoring: real calendar ISO weeks, not a rolling window

`bucketStartForDate` in `TrendPeriodAggregation.kt` currently derives each bucket from the date
alone (`date.withDayOfMonth(1)` for MONTHLY, calendar-quarter start for QUARTERLY) — bucket
membership is calendar-fixed: a given day always lands in the same bucket regardless of which
date range is currently being viewed. The 8-week buckets must keep this property (otherwise
"Wk 9" wouldn't correspond to a real ISO week 9, and a day's bucket would shift depending on
which day you happen to view the chart):

```kotlin
import java.time.temporal.IsoFields

private fun eightWeekBucketStart(date: LocalDate): LocalDate {
    val weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR)
    val isoWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val octadFirstWeek = ((isoWeek - 1) / 8) * 8 + 1
    return date
        .with(IsoFields.WEEK_BASED_YEAR, weekBasedYear.toLong())
        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, octadFirstWeek.toLong())
        .with(java.time.DayOfWeek.MONDAY)
}
```

**Partial trailing bucket:** an ISO week-based year has 52 or 53 weeks, neither divisible by 8,
so the last octad of each week-based-year is short (4 or 5 weeks = 28 or 35 days, not 56).
`bucketLengthDays` needs a real per-bucket calculation for `EIGHT_WEEK` (e.g. days until the
next week-based-year's week 1 Monday, capped at 56) rather than a fixed constant — this mirrors
how `MONTHLY`'s `bucketStart.lengthOfMonth()` already varies per calendar month, so it's not a
new category of edge case, just a new instance of the existing pattern. This also means
`bucketMidpointOffset` (which already clamps bucket midpoints to the selected date range) needs
no changes — it consumes whatever `bucketLengthDays` returns.

### 9.3 Consolidate the repeated label-callback boilerplate

Seven production files independently repeat the same pattern today (resolve the
`period_label_quarter` string resource, then build a `{ quarter -> String.format(...) }`
lambda) to pass into `periodLabelFor`: `core/ui/.../components/TrendCharts.kt:188`,
`core/ui/.../components/PeriodAverageSummaryRow.kt:67`, `core/ui/.../components/ChartDefaults.kt:80`,
`feature/vitals/.../bloodpressure/BloodPressureSplitChart.kt:119`,
`feature/vitals/.../bloodpressure/SingleBloodPressureChart.kt:177`,
`feature/workouts/.../AcwrChart.kt:168`, `feature/sleep/.../SleepTrendChart.kt:134`. Example of
the repeated shape (`PeriodAverageSummaryRow.kt:67-75`):

```kotlin
val quarterTemplate = stringResource(R.string.period_label_quarter)
val periodLabel =
    periodLabelFor(summary.granularity, summary.periodStartDate) { quarter ->
        String.format(Locale.getDefault(), quarterTemplate, quarter)
    }
```

Rather than bolting a second, near-identical `weekTemplate` lambda onto all seven sites,
extract one shared composable helper (new function in `core/ui/.../common/TrendPeriodAggregation.kt`
or an adjacent file) that resolves the correct template for whichever granularity it's given:

```kotlin
@Composable
fun rememberPeriodOrdinalLabel(granularity: TrendGranularity): (Int) -> String {
    val quarterTemplate = stringResource(R.string.period_label_quarter)   // "Q%1$d"
    val weekTemplate = stringResource(R.string.label_week_short)          // "Wk %1$d"
    val template = if (granularity == TrendGranularity.EIGHT_WEEK) weekTemplate else quarterTemplate
    return remember(template) { ordinal -> String.format(Locale.getDefault(), template, ordinal) }
}
```

Each of the seven call sites collapses to `val ordinalLabel = rememberPeriodOrdinalLabel(granularity)`
— a net reduction in code even though a new granularity is being added. `periodLabelFor` itself
gets an `EIGHT_WEEK` branch (replacing `QUARTERLY`) that extracts the real ISO week number via
`date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)` and calls the (renamed) `ordinalLabel` callback —
`quarterNumberFor` is deleted, since ISO week extraction replaces its one caller.

### 9.4 Tooltip week range ("Weeks 9–16")

Only three production sites call `formatTrendTooltipDate` for the actual tooltip text:
`TrendCharts.kt`, `BloodPressureSplitChart.kt`, `SingleBloodPressureChart.kt` (Sleep and
Workouts/ACWR have their own separate tooltip formatters — `SleepTrendTooltipFormatter.kt`'s
`quarterLabelFormat` field and ACWR's inline logic — and are **not** touched by this specific
richer-tooltip change; they'll keep showing the short "Wk 9" form in tooltips unless someone
separately decides to extend them, which is not required here).

Extend `formatTrendTooltipDate` with one more resolved-string parameter (mirroring how
`ordinalLabel` is already resolved by the caller and passed in) so it can build the range text
for `EIGHT_WEEK` using the bucket's real length:

```kotlin
fun formatTrendTooltipDate(
    granularity: TrendGranularity,
    date: LocalDate,
    ordinalLabel: (Int) -> String,
    weekRangeTemplate: String,   // new: resolved "Weeks %1$d–%2$d" from the caller
): String =
    when (granularity) {
        TrendGranularity.DAILY -> ChartUtils.formatTooltipDate(date)
        TrendGranularity.EIGHT_WEEK -> {
            val startWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val endWeek = startWeek + (bucketLengthDays(date, TrendGranularity.EIGHT_WEEK) / 7) - 1
            String.format(Locale.getDefault(), weekRangeTemplate, startWeek, endWeek)
        }
        else -> periodLabelFor(granularity, date, ordinalLabel)
    }
```

The three call sites add one line each: `val weekRangeTemplate = stringResource(R.string.tooltip_week_range)`.

### 9.5 Tests to update

- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TimeRangeTest.kt:49` —
  `TWELVE_MONTHS.granularity` assertion: `QUARTERLY` → `EIGHT_WEEK`.
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregationTest.kt` —
  replace the `QUARTERLY` cases (bucket boundaries at lines ~65/191, `quarterNumberFor` coverage
  at ~226–231, `periodLabelFor` quarterly formatting at ~235–241) with `EIGHT_WEEK` equivalents:
  real ISO-week octad boundaries, the partial trailing-bucket length (year-end short bucket),
  `periodLabelFor`'s "Wk N" output, and the new tooltip week-range helper's "Weeks N–M" output
  including the partial-bucket case (e.g. "Weeks 49–52").
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt:82`,
  `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/weight/WeightDetailViewModelTest.kt:316`,
  `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/bodyfat/BodyFatDetailViewModelTest.kt:408`,
  `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt:236`
  — each asserts `granularity == TrendGranularity.QUARTERLY`; update the expected value to
  `EIGHT_WEEK`.

### 9.6 Interaction with §3–§4 (RHR/HRV historical baseline)

**No baseline-specific code changes are needed.** `buildVitalsChartSeries`'s historical-baseline
series (§4.1) only branches on `granularity == TrendGranularity.DAILY` vs. not, delegating all
bucketing to `bucketBy(range.granularity, ...)` generically — once `TrendGranularity.EIGHT_WEEK`
exists and `bucketBy` handles it (§9.2), the 360D historical baseline line automatically gets
8-week resolution for free. The zone-band whole-range average (§4.1) doesn't depend on
granularity at all (it's a flat average over every raw day in the selected range), so it's
entirely unaffected by this addendum. Sequencing-wise, either half of this plan (§1–§8 baseline
work, or §9 granularity work) can be implemented first without blocking the other; implementing
§9 first means §4's manual verification pass already exercises 8-week buckets instead of
quarterly ones.
