# Implementation Plan: Readylytics Gauge Redesign (Soft Arc Metric Card)

* **Date**: 2026-06-18
* **Author**: Antigravity (Senior Android UI/UX Engineer)
* **Status**: Proposed

This document outlines the step-by-step plan to migrate the existing circular `M3ScoreDial` gauges to the new **Soft Arc Metric Card** design.

---

## 1. Current Gauge Composables & Usages

The current circular gauge is implemented in:
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreDial.kt`

It is used in **10 screens/components**:
1. **Dashboard** (`DashboardCardFactory.kt`): Sleep Score and Readiness.
2. **Vitals Screen** (`VitalsScreen.kt`): RHR and HRV side-by-side.
3. **Sleep Screen** (`SleepScreen.kt`): Sleep Score and Sleep Time side-by-side.
4. **Workouts Screen** (`WorkoutStatsSection.kt`): Strain Ratio and Readiness side-by-side.
5. **Blood Pressure Detail** (`BloodPressureDetailScreen.kt`): Systolic and Diastolic side-by-side.
6. **Body Fat Detail** (`BodyFatDetailScreen.kt`): Body Fat percent.
7. **Steps Detail** (`StepDetailScreen.kt`): Steps progress.
8. **Weight Detail** (`WeightDetailScreen.kt`): Weight measurement.

---

## 2. Proposed Reusable Component API (`M3ScoreGaugeCard`)

We will introduce a new component in `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreGaugeCard.kt`:

```kotlin
@Composable
fun M3ScoreGaugeCard(
    title: String,
    score: Float?,
    displayText: String,
    unitText: String,
    modifier: Modifier = Modifier,
    maxScore: Float = 100f,
    status: MetricStatus? = null,
    deltaText: String? = null,
    tooltipDescription: String? = null,
    onClick: () -> Unit = {},
)
```

### Key Internal Visual Layout
```kotlin
Card(
    onClick = onClick,
    modifier = modifier.height(156.dp),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    )
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Row for Title and Tooltip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (tooltipDescription != null) {
                MetricTooltip(description = tooltipDescription)
            }
        }
        
        // Central Area with Gauge & Text
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Soft Arc Custom Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Drawing math for semi-circle starting at 180f and sweeping 180f
            }
            
            // Large Centered Metric Value
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = displayText, style = MaterialTheme.typography.displaySmall)
                Text(text = unitText, style = MaterialTheme.typography.labelMedium)
            }
        }
        
        // Baseline Delta Chip
        if (deltaText != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = deltaText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
```

---

## 3. Exact Visual Changes Needed

1. **Card Frame**: Change from a borderless circle container (`130.dp` size) to a rounded rectangle Card (`156.dp` height, `surfaceContainerHighest` background).
2. **Arc shape**: Change from a $270^\circ$ circle arc to a $180^\circ$ flat-bottom dome arc.
3. **Caps & Dots**: Retain `StrokeCap.Round` on the progress arc, and add a filled circle of `4.dp` radius at the end of the active arc.
4. **Typography**: Align value to `displaySmall` (`32.sp`), and unit text to `labelMedium` (`12.sp`) directly below it.
5. **No Status Wording**: Ensure only value/unit and baseline chip are displayed; do not render status adjectives like "Optimal", "Poor", etc.

---

## 4. Files Affected

* `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreGaugeCard.kt` (New)
* `app/src/main/kotlin/app/readylytics/health/ui/common/SkeletonCard.kt` (Modified `ScoreDialSkeleton`)
* `app/src/main/kotlin/app/readylytics/health/ui/dashboard/DashboardCardFactory.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepUiState.kt` / `SleepViewModel.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepTimeGaugeData.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/bloodpressure/BloodPressureDetailScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailViewModel.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/weight/WeightDetailScreen.kt` (Modified)
* `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreDial.kt` (Deleted post-migration)

---

## 5. Step-by-Step Implementation Plan

### Phase 1: Component Creation & Skeleton Adapters
1. **Step 1.1**: Create `M3ScoreGaugeCard.kt` containing the drawing logic, Canvas math, and animations.
2. **Step 1.2**: Update `ScoreDialSkeleton` in `SkeletonCard.kt` to render as a large, rounded card of `156.dp` height with `MaterialTheme.shapes.large`.

### Phase 2: Expose Delta & Comparison Data
3. **Step 2.1**: Update `SleepTimeGaugeData.kt` to compute `deltaText` representing sleep time comparison to the goal.
4. **Step 2.2**: Update `BodyFatDetailViewModel.kt` to extract the second latest body fat record and calculate the delta percentage. Expose it in `BodyFatDetailUiState`.
5. **Step 2.3**: Update `SleepViewModel.kt` and `SleepUiState` to query yesterday's sleep score from the summaries database, exposing the delta to yesterday in the UI state.

### Phase 3: Screen Migration (Dashboard & Tabs)
6. **Step 3.1**: Update `DashboardCardFactory.kt` to replace `M3ScoreDial` with `M3ScoreGaugeCard` for Sleep Score and Readiness. Calculate delta to yesterday inline from `rasSummaries`.
7. **Step 3.2**: Update `VitalsScreen.kt` to replace `M3ScoreDial` with `M3ScoreGaugeCard` for RHR and HRV. Map precomputed baseline diffs.
8. **Step 3.3**: Update `SleepScreen.kt` to replace `M3ScoreDial` with `M3ScoreGaugeCard` for Sleep Score and Sleep Time.
9. **Step 3.4**: Update `WorkoutStatsSection.kt` to replace `M3ScoreDial` with `M3ScoreGaugeCard` for Strain Ratio and Readiness. Look up yesterday's values from `trimpSummaries`.

### Phase 4: Detail Screens Migration
10. **Step 4.1**: Update `BloodPressureDetailScreen.kt` to replace systolic/diastolic dials with `M3ScoreGaugeCard`s, computing difference to `120` and `80` respectively.
11. **Step 4.2**: Update `BodyFatDetailScreen.kt` to integrate the body fat card, passing the new delta value from `BodyFatDetailUiState`.
12. **Step 4.3**: Update `StepDetailScreen.kt` to use the step card, calculating delta compared to `stepGoal`.
13. **Step 4.4**: Update `WeightDetailScreen.kt` to use the weight card, passing the existing `deltaDisplay` value.

### Phase 5: Cleanup & Refactor Verification
14. **Step 5.1**: Delete the unused `M3ScoreDial.kt` component.
15. **Step 5.2**: Clean unused imports across files.
16. **Step 5.3**: Run pre-commit formatting and test verification (`./gradlew ktlintFormat && ./gradlew testDebugUnitTest`).

---

## 6. Risk Assessment & Mitigation

* **Risk**: Layout squishing or misalignment on smaller devices when twin cards are side-by-side in a `Row`.
  * *Mitigation*: Ensure each card inside a side-by-side Row uses `Modifier.weight(1f)` so they share horizontal space equally and scale down gracefully.
* **Risk**: Infinite loops or crash in Canvas when progress value is `null` or NaN.
  * *Mitigation*: Fallback to `0f` progress safely and handle null displays gracefully (`displayText = "—"`).
* **Risk**: Accidental scoring calculation changes.
  * *Mitigation*: Keep all delta math strictly bounded inside UI mappers or local ViewModel mappings, using simple subtractions without touching the database schema or sync logic.

---

## 7. Verification Plan

* **Dashboard Integration**: Verify cards are correctly formatted inside the `ReorderableCardGrid` and edit mode drag handle operates correctly.
* **Detail Views**: Validate BP, Body Fat, Steps, and Weight details cards match size and spacing of other elements on those pages.
* **Dark / Light Mode**: Confirm readability of the low-contrast inactive track (`surfaceVariant`) and active status colors under both themes.
* **Accessibility**: Verify TalkBack reads the custom content descriptions correctly (e.g. `RHR: 48 bpm, ↓ 3 bpm`).
* **Logic/Tests**: Run `./gradlew testDebugUnitTest` and ensure all repository, scoring, and UI model unit tests execute and pass successfully.
