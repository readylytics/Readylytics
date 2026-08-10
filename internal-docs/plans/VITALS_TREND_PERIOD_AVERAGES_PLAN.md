# Monthly/Quarterly Trend Averages for Vitals (+ Sleep/Load)

## Context

The Vitals screen's HRV and RHR cards (and SpO2/Body Temp, which share the same chart component) currently only show raw daily values over 7D/30D/180D, with a single rolling "Baseline" reference line. There's no way to see "am I trending up/down over the last few months" without eyeballing a noisy daily scatter. The user wants monthly/quarterly averages surfaced **without adding a new tab or extra chrome** — reusing the existing 7D/30D/180D range selector rather than adding a separate toggle, and eventually extending the same pattern to Sleep and Load/Strain trend charts.

Decisions made with the user:
- **Mechanism**: extend the existing range selector itself — no new toggle. Add a **360D** option. `180D` renders **monthly** averaged points; the new `360D` renders **quarterly** averaged points. `7D`/`30D` stay exactly as today (raw daily points).
- **Content**: a simple **period average + delta** summary (latest bucket's average vs. the prior bucket), not a bar chart or sparkline — the chart itself becomes the bucketed line/dot view; the delta is a compact stat line, styled like the existing day-over-day delta arrows used elsewhere in the app.
- **Zone-band context**: the existing colored recovery zone bands (red/amber/green) must still render behind the monthly/quarterly points — these are value-based, not date-based, so this is "free" as long as we don't bypass the existing zone-band decoration.
- **Scope**: HRV + RHR first-class; SpO2/Body Temp get it automatically (shared chart component). Also extend the *pattern* (aggregation + summary row) to the Sleep score trend chart and the Load/Strain (ACWR) chart, whose chart implementations are bespoke, not the shared component.
- **No new settings** — behavior is automatic based on which range is selected.

## Current architecture (from research)

- `core/ui/.../common/TimeRange.kt` — shared enum: `SEVEN_DAYS(7, "7D")`, `THIRTY_DAYS(30, "30D")`, `SIX_MONTHS(180, "180D")`. Used by Vitals, Sleep, and Workouts range selectors alike.
- `core/ui/.../common/DailyDataPoint.kt` — `DailyDataPoint(dayOffset, value)` plus `List<DailyDataPoint>.padToRange(rangeDays)`. Pure Kotlin, already unit-tested from feature modules without Android deps.
- `core/ui/.../components/TrendCharts.kt` — `TrendChart(points, rangeStartMs, rangeDays, ...)`: draws the Vico line, the `zoneBands` decoration, the baseline `HorizontalLine`, and the `BaselineLegend` caption row underneath. This is the component the HRV/RHR/SpO2/BodyTemp cards all go through (via `VitalsTrendSection.kt` + `TrendCard.kt`).
- `feature/vitals/.../overview/VitalsStateFactory.kt` — `buildVitalsChartSeries(summaries, startDate, rangeDays, unitSystem)` turns `DailySummary` rows into per-day `DailyDataPoint` lists (one per metric), padded to the range. This is where "which points feed the chart" is decided today.
- `core/ui/.../common/ScoreDeltaFormatter.kt` — `formatRoundedScoreDelta(...)`, the existing up/down-arrow delta formatter already reused by Dashboard/Sleep/Workouts cards. Reuse this for styling the new period-over-period delta consistently.
- **Sleep**: `feature/sleep/.../SleepTrendChart.kt` (bespoke dual-layer Vico chart: stacked-bar sleep window + line/area actual duration) — does **not** use `TrendChart`, has no zone bands/baseline today.
- **Load/Strain**: `feature/workouts/.../AcwrChart.kt` (`AcwrChartCard`, bespoke combo chart) — also does **not** use `TrendChart`.
- Both Sleep and Workouts already share the same `TimeRange` enum and `SingleChoiceSegmentedButtonRow` selector pattern, so adding `360D` to the enum surfaces it there too (gated per-screen by whether that screen wires the new branch).

## Proposed design

### 1. Extend `TimeRange`
Add `TWELVE_MONTHS(360, "360D")` to `core/ui/.../common/TimeRange.kt`. Add a `granularity` mapping (new small enum `TrendGranularity { DAILY, MONTHLY, QUARTERLY }`) so call sites can branch without hardcoding day thresholds:
`SEVEN_DAYS`/`THIRTY_DAYS` → `DAILY`, `SIX_MONTHS` → `MONTHLY`, `TWELVE_MONTHS` → `QUARTERLY`.

### 2. Pure-Kotlin bucketing utility (new file, `core/ui/.../common/TrendPeriodAggregation.kt`, unit-tested like `padToRange`)
- `List<DailyDataPoint>.bucketBy(granularity: TrendGranularity, startDate: LocalDate): List<DailyDataPoint>` — groups the (unpadded, real) daily points by calendar month or quarter, averages the present values per bucket, and emits one `DailyDataPoint` per bucket positioned at that bucket's midpoint day-offset (so it still composes into `TrendChart`'s existing day-offset x-axis with no structural change to the chart's coordinate system).
- `data class PeriodAverageSummary(val periodLabel: String, val average: Float?, val previousAverage: Float?)` plus a small builder that takes the bucketed list and returns the latest-bucket-vs-prior-bucket summary (null-safe: no summary if fewer than 2 buckets have data).
- Apply this in `VitalsStateFactory.buildVitalsChartSeries`: when `range.granularity != DAILY`, bucket each metric's point list before padding/returning it, instead of (or via a parallel path from) today's per-day loop. Mirror the existing `VitalsStateFactoryTest.kt` conventions for new tests (boundary cases: partial current-period bucket, gaps, single-day-in-range months).

### 3. `TrendChart` / `BaselineLegend` changes (`core/ui/.../components/TrendCharts.kt`)
- Add an optional `periodSummary: PeriodAverageSummary?` param. When non-null, render a compact summary row directly below the existing `BaselineLegend` (e.g. "Aug avg: 44 ms ▲3 vs Jul"), using `formatRoundedScoreDelta` for the arrow/color so it matches the delta styling used elsewhere in the app. Zero changes needed for zone bands — `zoneBandDecoration` is value-based and keeps applying automatically to the bucketed points.
- The x-axis label formatter (currently `ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)`, which resolves a day-offset to a date and formats it) needs a granularity-aware variant so monthly buckets show month abbreviations ("Feb", "Mar") and quarterly buckets show quarter labels ("Q1", "Q2") instead of `dd.MM`. Same for the axis tick placer (`ChartDefaults.itemPlacerForRangeDays`) — with only ~12 or ~4 points instead of up to 180/360 daily ones, it should place one tick per bucket rather than spacing across the full day range. Locate `ChartDefaults` (same package) and add a granularity-aware formatter/placer pair alongside the existing ones, selected in `VitalsTrendSection.kt`/`TrendChart` call sites based on `selectedRange.granularity`.

### 4. Wire it up in Vitals
- `VitalsTrendSection.kt`: pass the new `periodSummary` (computed from the already-bucketed `VitalsChartSeries`) into each `TrendChart` call for HRV/RHR (and SpO2/BodyTemp get it automatically since they go through the same card-building code).
- Update the range selector (`VitalsScreen.kt`) — it already iterates `TimeRange.entries` via `SingleChoiceSegmentedButtonRow`/`SegmentedButton`, so adding `TWELVE_MONTHS` to the enum should surface "360D" with no extra layout work, per the M3 native-component convention in CLAUDE.md.

### 5. Extend the pattern to Sleep and Load (second phase, same underlying utilities)
Because `SleepTrendChart` and `AcwrChart` are bespoke (not `TrendChart`), this is a per-screen adaptation, not a free win:
- Reuse `bucketBy`/`PeriodAverageSummary` from step 2 to aggregate the series each chart already builds (sleep duration/window; ACWR ratio) when their local `TimeRange` selection has `granularity != DAILY`.
- Add the same compact summary row (extract it as a small reusable composable, e.g. `PeriodAverageSummaryRow`, out of step 3's inline rendering so both `TrendChart` and the two bespoke charts can call it) beneath `SleepTrendChart`/`AcwrChartCard`.
- Their chart *shape* (stacked-bar+line, ACWR combo) stays as-is — only the underlying data granularity and the added summary row change. Axis label/tick handling will need the equivalent granularity-aware treatment as step 3, done locally in each chart's own Vico setup since they don't share `ChartDefaults` axis code with `TrendChart`.
- Recommend doing Vitals first (steps 1-4) as a complete, shippable slice, then Sleep/Load as a follow-up once the aggregation utility and summary-row component are proven out — rather than one large cross-cutting change.

## Verification
- New unit tests for `bucketBy`/`PeriodAverageSummary` (pure Kotlin, mirrors `padToRange`'s test style) covering: even month/quarter boundaries, partial trailing (current) bucket, months with zero data, single-bucket case (no delta).
- Extend `VitalsStateFactoryTest.kt` with cases for `range = SIX_MONTHS`/`TWELVE_MONTHS` asserting bucketed (not daily) output.
- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease` at the end per project convention.
- Manual verification: `./gradlew installDebug`, open Vitals, select 180D and confirm monthly dots + "Aug avg: X ▲/▼Y vs Jul" line render with zone bands still visible; select the new 360D and confirm quarterly dots + quarter labels/summary; confirm 7D/30D are pixel-identical to before.
