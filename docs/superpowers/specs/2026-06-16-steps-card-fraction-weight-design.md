# Design Spec: Steps Card Fraction Font Weight Adjustment

## Context
Refining the Daily Steps progress fraction display to remove the bold font weight, making the text presentation more consistent with regular detail labels.

## Requirements
1. Remove `fontWeight = FontWeight.Bold` from the steps progress fraction `Text` component.

## Detailed Design & Changes

### 1. Steps Bar Font Weight
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
* **Changes**:
  * Remove `fontWeight = FontWeight.Bold` from the progress fraction `Text` component (line 204).
    ```diff
    -            Text(
    -                text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
    -                style = MaterialTheme.typography.labelSmall,
    -                fontWeight = FontWeight.Bold,
    -                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
    -            )
    +            Text(
    +                text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
    +                style = MaterialTheme.typography.labelSmall,
    +                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
    +            )
    ```

## Verification Plan
1. Compile the project and verify local unit tests: `./gradlew testDebugUnitTest`.
2. Format changed code: `./gradlew ktlintFormat`.
3. Check code styles and build issues: `./gradlew lint`.
