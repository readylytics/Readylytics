# Design Spec: Steps Card Fraction Placement below the Bar

## Context
Refining the Daily Steps card layout to show the progress fraction (`current_steps / goal_steps`) on the right side below the steps progress bar instead of at the top-right of the card.

## Requirements
1. Remove the steps count progress fraction from the top-right of the Steps card title row.
2. Render the steps count progress fraction (e.g. `8,500 / 10,000` or `-- / 10,000`) right-aligned underneath the steps progress bar.

## Detailed Design & Changes

### 1. Steps Card Title Row
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt`
* **Changes**:
  * Remove fraction formatting logic and the right-hand `Text` component from the title `Row`.
    ```diff
    -                val formattedCount = stepCount?.let {
    -                    java.text.NumberFormat.getNumberInstance().format(it)
    -                } ?: "--"
    -                val formattedGoal = java.text.NumberFormat.getNumberInstance().format(stepGoal)
    -                Text(
    -                    text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
    -                    style = MaterialTheme.typography.bodySmall,
    -                    color = MaterialTheme.colorScheme.onSurfaceVariant,
    -                )
    ```

### 2. Steps Bar Bottom Row
* **File**: `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
* **Changes**:
  * Reintroduce a bottom `Row` in the `StepsBar` composable.
  * Use a spacer to right-align the fraction text, formatted as `current / goal`:
    ```diff
    +        Spacer(Modifier.height(6.dp))
    +        Row(modifier = Modifier.fillMaxWidth()) {
    +            Spacer(Modifier.weight(1f))
    +            val formattedCount = stepCount?.let {
    +                java.text.NumberFormat.getNumberInstance().format(it)
    +            } ?: "--"
    +            val formattedGoal = java.text.NumberFormat.getNumberInstance().format(stepGoal)
    +            Text(
    +                text = stringResource(R.string.steps_fraction_display, formattedCount, formattedGoal),
    +                style = MaterialTheme.typography.labelSmall,
    +                fontWeight = FontWeight.Bold,
    +                color = if (stepCount != null) fillColor else MaterialTheme.colorScheme.onSurfaceVariant,
    +            )
    +        }
    ```

## Verification Plan
1. Compile the project and verify local unit tests: `./gradlew testDebugUnitTest`.
2. Format changed code: `./gradlew ktlintFormat`.
3. Check code styles and build issues: `./gradlew lint`.
