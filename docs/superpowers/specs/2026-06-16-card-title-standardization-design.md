# Design Spec: Card & Diagram Title Typography and Color Standardization

## Context
Standardizing title styling across all cards, charts, and diagrams in the application to ensure visual consistency. Titles were previously a mix of `titleMedium` and `titleSmall` with different text colors. They will now consistently use `titleMedium` and the `onSurfaceVariant` color.

## Requirements
1. Standardize all card, chart, and diagram titles to use:
   * Typography Style: `MaterialTheme.typography.titleMedium`
   * Color: `MaterialTheme.colorScheme.onSurfaceVariant`
2. Update the following components:
   * **Readylytics Activity Score (RAS) Card** (Workouts tab)
   * **Training Load (ACWR) Card** (Workouts tab)
   * **Trend Card** (hosting sleep timeline, sleep duration, HRV, etc.)
   * **Insight Card** (Dashboard)
   * **History Card Layout** (list items)

## Detailed Design & Changes

### 1. RAS Card Title & ACWR Card Title
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`
* **Changes**:
  * For RAS Card title (line 177):
    ```diff
    -                                text = stringResource(R.string.workout_stats_ras_title),
    -                                style = MaterialTheme.typography.titleSmall,
    -                                color = MaterialTheme.colorScheme.onSurface,
    +                                text = stringResource(R.string.workout_stats_ras_title),
    +                                style = MaterialTheme.typography.titleMedium,
    +                                color = MaterialTheme.colorScheme.onSurfaceVariant,
    ```
  * For ACWR (Training Load) Card title (line 282):
    ```diff
    -                text = stringResource(R.string.acwr_training_load),
    -                style = MaterialTheme.typography.titleSmall,
    -                color = MaterialTheme.colorScheme.onSurface,
    +                text = stringResource(R.string.acwr_training_load),
    +                style = MaterialTheme.typography.titleMedium,
    +                color = MaterialTheme.colorScheme.onSurfaceVariant,
    ```

### 2. Trend Card Title
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/TrendCharts.kt`
* **Changes**:
  * For TrendCard title (line 79):
    ```diff
    -                Text(
    -                    text = title,
    -                    style = MaterialTheme.typography.titleSmall,
    -                    color = MaterialTheme.colorScheme.onSurface,
    -                )
    +                Text(
    +                    text = title,
    +                    style = MaterialTheme.typography.titleMedium,
    +                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    +                )
    ```

### 3. Insight Card Title
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/InsightCard.kt`
* **Changes**:
  * For InsightCard title (line 65):
    ```diff
    -                Text(
    -                    text = title,
    -                    style = MaterialTheme.typography.titleSmall,
    -                    color = MaterialTheme.colorScheme.onSurface,
    -                )
    +                Text(
    +                    text = title,
    +                    style = MaterialTheme.typography.titleMedium,
    +                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    +                )
    ```

### 4. History Card Layout Title
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/HistoryCardLayout.kt`
* **Changes**:
  * For HistoryCardLayout title (line 51):
    ```diff
    -                Text(
    -                    text = title,
    -                    style = MaterialTheme.typography.titleSmall,
    -                )
    +                Text(
    +                    text = title,
    +                    style = MaterialTheme.typography.titleMedium,
    +                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    +                )
    ```

## Verification Plan
1. Compile the project and run local unit tests with `./gradlew testDebugUnitTest`.
2. Format the files using `./gradlew ktlintFormat`.
3. Check code styles and build safety via `./gradlew lint`.
