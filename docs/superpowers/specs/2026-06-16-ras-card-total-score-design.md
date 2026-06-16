# Design Spec: RAS Card Total Score Display and Bottom Value Removal

## Context
Refining the Readylytics Activity Score (RAS) card on the Workouts tab to display the 7-day rolling total score at the top right (replacing the daily earned points text) and remove the redundant value display from below the progress bar.

## Requirements
1. In the top-right corner of the RAS card, display the current total 7-day score (e.g. `65`) instead of the daily earned score (e.g. `+23 today`).
2. Completely remove the score text display below the weekly bar in the RAS card.

## Detailed Design & Changes

### 1. RAS Card Top-Right display
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`
* **Changes**:
  * Replace `uiState.latestMetrics?.rasDayScoreRounded` and its daily earned text with `uiState.latestMetrics?.rasRounded` displaying the total score:
    ```diff
    -                                uiState.latestMetrics?.rasDayScoreRounded?.let { earned ->
    -                                    if (earned > 0) {
    -                                        Text(
    -                                            text = stringResource(R.string.ras_earned_today, earned),
    -                                            style = MaterialTheme.typography.labelSmall,
    -                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
    -                                        )
    -                                    }
    -                                }
    +                                uiState.latestMetrics?.rasRounded?.let { total ->
    +                                    Text(
    +                                        text = total.toString(),
    +                                        style = MaterialTheme.typography.bodySmall,
    +                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
    +                                    )
    +                                }
    ```

### 2. Remove bottom value display from RAS Bar
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt`
* **Changes**:
  * Remove the bottom `Row` and `Spacer(Modifier.height(8.dp))` in `RasWeeklyBar`.

## Verification Plan
1. Compile the project and run unit tests: `./gradlew testDebugUnitTest`.
2. Format code: `./gradlew ktlintFormat`.
3. Check code styles and build issues: `./gradlew lint`.
