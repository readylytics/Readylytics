# Design Spec: Daily Steps Card Fraction and Detail Chart Goal Legend

## Context
Refining the Daily Steps card on the Dashboard and the Steps Detail screen chart legends to make goals and current counts clearer.

## Requirements
1. On the Daily Steps dashboard card:
   * Remove the top-right "Goal: 10,000" label.
   * In its place, display a progress fraction format: `current_steps / goal_steps` (e.g. `8,500 / 10,000` or `-- / 10,000` if null).
   * Completely remove the steps count number display from below the progress bar.
2. On the Steps Detail screen chart:
   * Rename the "Baseline" legend below the chart to "Goal".

## Detailed Design & Changes

### 1. String Resources
* **File**: `app/src/main/res/values/strings.xml`
* **Changes**: Add two new strings:
  ```xml
  <string name="label_goal">Goal</string>
  <string name="steps_fraction_display">%1$s / %2$s</string>
  ```

### 2. Steps Card top-right fraction
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt`
* **Changes**:
  * Format the count and goal.
  * Render the top-right text as a fraction:
    ```diff
    -                Text(
    -                    text =
    -                        stringResource(
    -                            R.string.steps_goal_display,
    -                            java.text.NumberFormat
    -                                .getNumberInstance()
    -                                .format(stepGoal),
    -                        ),
    -                    style = MaterialTheme.typography.bodySmall,
    -                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    -                )
    +                val formattedCount = stepCount?.let {
    +                    java.text.NumberFormat.getNumberInstance().format(it)
    +                } ?: "--"
    +                val formattedGoal = java.text.NumberFormat.getNumberInstance().format(stepGoal)
    +                Text(
    +                    text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
    +                    style = MaterialTheme.typography.bodySmall,
    +                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    +                )
    ```

### 3. Steps Bar bottom row removal
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
* **Changes**:
  * Remove the entire bottom `Row` in the `StepsBar` composable so there is no text under the bar anymore.

### 4. Step Detail Screen Chart baseline label
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt`
* **Changes**:
  * Add the `baselineLabel` argument to `TrendChart`:
    ```diff
    -                    TrendChart(
    -                        points = uiState.dailySteps,
    -                        rangeStartMs = uiState.rangeStartMs,
    -                        rangeDays = uiState.selectedRange.days,
    -                        metricName = "Steps",
    -                        baselineUnit = "steps",
    -                        baseline = uiState.stepGoal.toFloat(),
    -                        scrollState = chartScrollState,
    -                        zoomState = chartZoomState,
    -                        parentScrollInProgress = scrollState.isScrollInProgress,
    -                    )
    +                    TrendChart(
    +                        points = uiState.dailySteps,
    +                        rangeStartMs = uiState.rangeStartMs,
    +                        rangeDays = uiState.selectedRange.days,
    +                        metricName = "Steps",
    +                        baselineUnit = "steps",
    +                        baseline = uiState.stepGoal.toFloat(),
    +                        baselineLabel = stringResource(R.string.label_goal),
    +                        scrollState = chartScrollState,
    +                        zoomState = chartZoomState,
    +                        parentScrollInProgress = scrollState.isScrollInProgress,
    +                    )
    ```

## Verification Plan
1. Compile and run unit tests: `./gradlew testDebugUnitTest`.
2. Clean formatting: `./gradlew ktlintFormat`.
3. Check code styles and build issues: `./gradlew lint`.
