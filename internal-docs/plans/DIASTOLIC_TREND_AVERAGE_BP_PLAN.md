# Add Diastolic Trend Average to Blood Pressure Details

**Purpose of this document**: a self-contained implementation plan for adding a diastolic month-over-month trend average to the Blood Pressure detail screen, written so a coding agent can execute it with no other context than this file and a checkout of this repo.

## Context

The Blood Pressure detail screen's trend chart shows a "— Systolic / — Diastolic" legend, but the average/trend summary line beneath the chart ("Aug Avg: 122 mmHg ↑ 6 vs Jul") only ever reflects **systolic**. Diastolic is fully computed as a daily series already (it drives the lower half of the split chart) but is never fed into the period-average/trend calculation, so users can't see whether their diastolic pressure is trending up or down month-over-month. The requested end state, confirmed with the user (they picked "header + two labeled lines" over "two full repeated rows"):

```
Aug Avg:
●  Systolic:  122 mmHg  ↑ 6
●  Diastolic:  80 mmHg  ↓ 2
vs Jul
```

## Repo layout / module map (for an agent with zero prior context)

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TrendPeriodAggregation.kt` — pure-Kotlin bucketing + `PeriodAverageSummary` builder, metric-agnostic.
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/ScoreDeltaFormatter.kt` — `DeltaDirection`, `DeltaOutcome`, `assessDeltaOutcome`, `formatRoundedScoreDelta`.
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/PeriodAverageSummaryRow.kt` — existing single-metric summary row composable (the one currently used, systolic-only).
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt` — contains `internal fun formatTrendTooltipValue(...)` (line ~460), module-internal, reusable from any file inside the `core-ui` Gradle module.
- `core/ui/src/main/res/values/strings.xml` — has `label_avg` (l.96), `period_summary_vs` (l.97), `period_label_quarter` (l.98), `unit_mmHg` (l.84). All reusable as-is.
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModel.kt` — builds `dailySystolic`/`dailyDiastolic` and the (currently systolic-only) `periodSummary`.
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailScreen.kt` — renders the trend chart + summary row.
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureSplitChart.kt` — the split chart + its legend (systolic = `MaterialTheme.colorScheme.primary`, diastolic = `MaterialTheme.colorScheme.tertiaryContainer`); legend currently hardcodes `"Systolic"`/`"Diastolic"` string literals instead of `stringResource(...)`.
- `feature/vitals/src/main/res/values/strings.xml` — has `label_systolic` (l.62) = "Systolic", `label_diastolic` (l.65) = "Diastolic" (already used for the metric cards at the top of the screen, not yet used in the legend).
- `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModelTest.kt` — existing unit tests, mockk + coroutines-test.

## Step 1 — New composable: `core/ui/.../components/PeriodAverageSummaryGroup.kt` (new file)

Not BP-specific: any screen with two co-plotted series sharing one period header can reuse it later (e.g. Sleep's stacked-bar+line chart has the same one-summary-line gap).

Current single-metric composable for reference (`PeriodAverageSummaryRow.kt`, unchanged, do not modify):

```kotlin
@Composable
fun PeriodAverageSummaryRow(
    summary: PeriodAverageSummary,
    unit: String,
    decimalPlaces: Int,
    modifier: Modifier = Modifier,
    direction: DeltaDirection = DeltaDirection.HIGHER_IS_BETTER,
) {
    val average = summary.average ?: return
    val valueText = formatTrendTooltipValue(value = average, decimalPlaces = decimalPlaces, hideUnit = false, unit = unit)
    val currentRounded = average.roundToInt()
    val previousRounded = summary.previousAverage?.roundToInt()
    val deltaText = formatRoundedScoreDelta(currentRounded = currentRounded, previousRounded = previousRounded).resolveOrNull() ?: return
    val statusColors = LocalStatusColors.current
    val deltaColor = when (assessDeltaOutcome(currentRounded, previousRounded, direction)) {
        DeltaOutcome.IMPROVED -> statusColors.optimal
        DeltaOutcome.WORSENED -> statusColors.warning
        DeltaOutcome.NEUTRAL -> statusColors.neutral
        null -> statusColors.neutral
    }
    val quarterTemplate = stringResource(R.string.period_label_quarter)
    val periodLabel = periodLabelFor(summary.granularity, summary.periodStartDate) { quarter -> String.format(Locale.getDefault(), quarterTemplate, quarter) }
    val previousLabel = periodLabelFor(summary.granularity, summary.previousPeriodStartDate) { quarter -> String.format(Locale.getDefault(), quarterTemplate, quarter) }
    val avgLabel = stringResource(R.string.label_avg)
    val previousLabelText = stringResource(R.string.period_summary_vs, previousLabel)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(text = "$periodLabel $avgLabel: $valueText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = deltaText, style = MaterialTheme.typography.bodySmall, color = deltaColor)
        Text(text = previousLabelText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

New file, `PeriodAverageSummaryGroup.kt`, same package (`app.readylytics.health.core.ui.components`):

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.DeltaOutcome
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.assessDeltaOutcome
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.resolveOrNull
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One labeled series (e.g. "Systolic", tinted to match its chart legend swatch) feeding
 * into [PeriodAverageSummaryGroup].
 */
data class LabeledPeriodAverage(
    val label: String,
    val color: Color,
    val summary: PeriodAverageSummary,
)

/**
 * Two-metric variant of [PeriodAverageSummaryRow]: one shared period header
 * (e.g. "Aug Avg:") followed by one labeled, color-coded line per metric, and a single
 * trailing "vs Jul" caption. Renders nothing if either metric has no average yet.
 */
@Composable
fun PeriodAverageSummaryGroup(
    primary: LabeledPeriodAverage,
    secondary: LabeledPeriodAverage,
    unit: String,
    decimalPlaces: Int,
    modifier: Modifier = Modifier,
    direction: DeltaDirection = DeltaDirection.NEUTRAL,
) {
    val primaryAverage = primary.summary.average ?: return
    val secondaryAverage = secondary.summary.average ?: return

    val quarterTemplate = stringResource(R.string.period_label_quarter)
    fun periodLabel(
        summary: PeriodAverageSummary,
        date: LocalDate?,
    ) = periodLabelFor(summary.granularity, date) { quarter ->
        String.format(Locale.getDefault(), quarterTemplate, quarter)
    }

    val periodLabel = periodLabel(primary.summary, primary.summary.periodStartDate)
    val previousLabel = periodLabel(primary.summary, primary.summary.previousPeriodStartDate)
    val avgLabel = stringResource(R.string.label_avg)
    val previousLabelText = stringResource(R.string.period_summary_vs, previousLabel)
    val statusColors = LocalStatusColors.current

    @Composable
    fun MetricRow(
        metric: LabeledPeriodAverage,
        average: Float,
    ) {
        val valueText = formatTrendTooltipValue(value = average, decimalPlaces = decimalPlaces, hideUnit = false, unit = unit)
        val currentRounded = average.roundToInt()
        val previousRounded = metric.summary.previousAverage?.roundToInt()
        val deltaText =
            formatRoundedScoreDelta(currentRounded = currentRounded, previousRounded = previousRounded).resolveOrNull()
        val deltaColor =
            when (assessDeltaOutcome(currentRounded, previousRounded, direction)) {
                DeltaOutcome.IMPROVED -> statusColors.optimal
                DeltaOutcome.WORSENED -> statusColors.warning
                DeltaOutcome.NEUTRAL -> statusColors.neutral
                null -> statusColors.neutral
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Box(modifier = Modifier.size(width = 12.dp, height = 2.dp).background(metric.color))
            Text(text = "${metric.label}: $valueText", style = MaterialTheme.typography.bodySmall, color = metric.color)
            if (deltaText != null) {
                Text(text = deltaText, style = MaterialTheme.typography.bodySmall, color = deltaColor)
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(text = "$periodLabel $avgLabel:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricRow(primary, primaryAverage)
        MetricRow(secondary, secondaryAverage)
        Text(text = previousLabelText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

`formatTrendTooltipValue` is `internal` in `TrendCharts.kt` — visible here because this new file lives in the same Gradle module (`core-ui`). Run `ktlintFormat` after pasting to fix import ordering/wrapping to house style.

## Step 2 — `BloodPressureDetailViewModel.kt`

Current (`feature/vitals/.../bloodpressure/BloodPressureDetailViewModel.kt`):

```kotlin
data class BloodPressureDetailUiState(
    val latestSystolic: Int? = null,
    val latestDiastolic: Int? = null,
    val latestDate: LocalDate? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailySystolic: List<DailyDataPoint> = emptyList(),
    val dailyDiastolic: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val periodSummary: PeriodAverageSummary? = null,
    // ...unchanged fields...
)
```

```kotlin
                    val periodSummary =
                        if (range.granularity == TrendGranularity.DAILY) {
                            null
                        } else {
                            buildPeriodAverageSummary(dailySystolic, range.granularity, startDate)
                        }
```

```kotlin
                    BloodPressureDetailUiState(
                        // ...
                        periodSummary = periodSummary,
                        // ...
                    )
```

Change to:

```kotlin
data class BloodPressureDetailUiState(
    val latestSystolic: Int? = null,
    val latestDiastolic: Int? = null,
    val latestDate: LocalDate? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailySystolic: List<DailyDataPoint> = emptyList(),
    val dailyDiastolic: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val systolicPeriodSummary: PeriodAverageSummary? = null,
    val diastolicPeriodSummary: PeriodAverageSummary? = null,
    // ...unchanged fields...
)
```

```kotlin
                    val systolicPeriodSummary =
                        if (range.granularity == TrendGranularity.DAILY) {
                            null
                        } else {
                            buildPeriodAverageSummary(dailySystolic, range.granularity, startDate)
                        }
                    val diastolicPeriodSummary =
                        if (range.granularity == TrendGranularity.DAILY) {
                            null
                        } else {
                            buildPeriodAverageSummary(dailyDiastolic, range.granularity, startDate)
                        }
```

```kotlin
                    BloodPressureDetailUiState(
                        // ...
                        systolicPeriodSummary = systolicPeriodSummary,
                        diastolicPeriodSummary = diastolicPeriodSummary,
                        // ...
                    )
```

(Rename is safe: grep confirmed `periodSummary` on this specific state class has no consumers outside this file and `BloodPressureDetailScreen.kt`; other vitals screens — weight/steps/bodyfat — each own an independent state class also happening to be named `periodSummary`.)

## Step 3 — `BloodPressureDetailScreen.kt`

Current block (inside the `TrendCard { ... }` body, right after `BloodPressureSplitChart(...)`):

```kotlin
                    uiState.periodSummary?.let { summary ->
                        PeriodAverageSummaryRow(
                            summary = summary,
                            unit = stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg),
                            decimalPlaces = 0,
                            direction = DeltaDirection.NEUTRAL,
                        )
                    }
```

Replace with:

```kotlin
                    val systolicSummary = uiState.systolicPeriodSummary
                    val diastolicSummary = uiState.diastolicPeriodSummary
                    if (systolicSummary != null && diastolicSummary != null) {
                        PeriodAverageSummaryGroup(
                            primary =
                                LabeledPeriodAverage(
                                    label = stringResource(R.string.label_systolic),
                                    color = MaterialTheme.colorScheme.primary,
                                    summary = systolicSummary,
                                ),
                            secondary =
                                LabeledPeriodAverage(
                                    label = stringResource(R.string.label_diastolic),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    summary = diastolicSummary,
                                ),
                            unit = stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg),
                            decimalPlaces = 0,
                        )
                    }
```

Update the import line `import app.readylytics.health.core.ui.components.PeriodAverageSummaryRow` to also bring in the new symbols:

```kotlin
import app.readylytics.health.core.ui.components.LabeledPeriodAverage
import app.readylytics.health.core.ui.components.PeriodAverageSummaryGroup
```

(`PeriodAverageSummaryRow` import can be removed if nothing else in this file uses it — check before deleting.) `R.string.label_systolic`/`label_diastolic` here resolve to the feature/vitals resources already used for the metric cards at the top of this same screen (lines ~153/183) — no new strings needed. `DeltaDirection` import may become unused after this change — remove if so.

## Step 4 — `BloodPressureSplitChart.kt` (drive-by string externalization fix)

Current (lines ~194–221):

```kotlin
                // Systolic legend box
                Box(
                    modifier =
                        Modifier
                            .size(width = 12.dp, height = 2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(
                    text = "Systolic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(MaterialTheme.spacing.large))
                // Diastolic legend box
                Box(
                    modifier =
                        Modifier
                            .size(width = 12.dp, height = 2.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(
                    text = "Diastolic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
```

Change the two `Text(text = "Systolic", ...)` / `Text(text = "Diastolic", ...)` literals to `stringResource(...)`. This file already imports `app.readylytics.health.core.ui.R` aliased as `R` (line 29), so add a second aliased import for the feature/vitals resources:

```kotlin
import app.readylytics.health.feature.vitals.R as VitalsR
```

```kotlin
                Text(
                    text = stringResource(VitalsR.string.label_systolic),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
```
```kotlin
                Text(
                    text = stringResource(VitalsR.string.label_diastolic),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
```

## Step 5 — Tests: `BloodPressureDetailViewModelTest.kt`

Extend the existing test `` `twelve month range buckets systolic and diastolic into quarterly points` `` (around line 234) with additional assertions after the existing ones:

```kotlin
            assertEquals(120, state.systolicPeriodSummary?.average?.roundToInt())
            assertEquals(80, state.diastolicPeriodSummary?.average?.roundToInt())
```

(Adjust to the actual expected latest-bucket averages given the test's fixture data — the existing test already documents "Two populated quarters: systolic Q1 avg 120, Q2 120; diastolic Q1 avg 80, Q2 80", so both period summaries' `average` should resolve to the Q2 (latest) bucket value: 120 and 80 respectively, with `previousAverage` from Q1 also 120/80.)

Add a new test mirroring the existing null-`periodSummary` behavior for daily granularity:

```kotlin
    @Test
    fun `daily granularity range has null period summaries`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.SEVEN_DAYS && !it.isLoading }
            assertNull(state.systolicPeriodSummary)
            assertNull(state.diastolicPeriodSummary)
        }
```

(Adjust the `first { ... }` predicate as needed to reach a settled, non-loading state — follow the same `uiState.first { ... }` idiom already used throughout this test file, e.g. `it.latestSystolic != null` or `it.historyItems.isNotEmpty()`.)

## Verification checklist (for the implementing agent)

1. `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` — must pass (project's mandatory pre-commit step, see root `CLAUDE.md`).
2. `./gradlew lintRelease` — run at the end per project convention.
3. `codegraph index` after adding `PeriodAverageSummaryGroup.kt` (new file — project file-lifecycle rule in root `CLAUDE.md`).
4. Manual/device check: `./gradlew installDebug`, open a Blood Pressure detail screen with ≥2 months of history, select the 180D range, and confirm:
   - The block renders as a header ("Aug Avg:") + one colored line per metric + a trailing "vs Jul" caption.
   - Colors match the legend directly above (systolic = primary, diastolic = tertiaryContainer).
   - 7D/30D (daily granularity) show no trend block at all, same as before this change.
   - The chart legend labels ("Systolic"/"Diastolic") render identically to before (now via string resource instead of literal).
