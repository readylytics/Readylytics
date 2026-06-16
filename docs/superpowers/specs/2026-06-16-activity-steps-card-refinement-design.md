# Design Spec: Readylytics Activity Score and Steps Card UI Refinement

## Context
Refining the UI on the Workouts tab (RAS Card) and Dashboard (Daily Steps Card) to make them cleaner, simpler, and more aligned with a minimalist presentation style.

## Requirements
1. Update the RAS card title on the Workout tab to display the full name "Readylytics Activity Score" instead of the abbreviation "RAS".
2. Format the current RAS score below the bar to show only the number (e.g. `65` instead of `65 RAS`).
3. Remove the status labels (e.g., `OPTIMAL`, `WARNING`, `NEUTRAL`, `POOR`, `CALIBRATING`, `NO DATA`) from both the RAS card and the Daily Steps card.
4. Format the current steps count below the steps bar to show only the number (e.g. `10,000` instead of `10,000 steps`).
5. Ensure the remaining text values (the numbers) remain left-aligned under their respective progress bars.

## Detailed Design & Changes

### 1. Title Update (i18n String)
We will modify the string resource to display the full name, ensuring we adhere to the rule of defining all user-facing strings in `strings.xml`.
* **File**: `app/src/main/res/values/strings.xml`
* **Target Line**: Line 867
* **Change**:
  ```diff
  -    <string name="workout_stats_ras_title">RAS</string>
  +    <string name="workout_stats_ras_title">Readylytics Activity Score</string>
  ```

### 2. Readylytics Activity Score Card Refinement
We will update `RasWeeklyBar.kt` to remove the `" RAS"` suffix and the status text label.
* **File**: [RasWeeklyBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt)
* **Changes**:
  * Modify the `Text` component for the total RAS value:
    ```diff
    -                text = "${totalRas.roundToPercentInt()} RAS",
    +                text = "${totalRas.roundToPercentInt()}",
    ```
  * Delete the `Text` displaying `status.name`.
  * The parent `Row` with `horizontalArrangement = Arrangement.SpaceBetween` will naturally keep the single remaining child left-aligned.

### 3. Daily Steps Card Refinement
We will update `StepsBar.kt` to remove the `" steps"` suffix and the status text label.
* **File**: [StepsBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt)
* **Changes**:
  * Modify the `Text` component for the steps count value:
    ```diff
    -                text = if (stepCount != null) "${count.formatSteps()} steps" else "-- steps",
    +                text = if (stepCount != null) count.formatSteps() else "--",
    ```
  * Delete the `Text` displaying `status.name` / `"NO DATA"`.
  * Keep the current value left-aligned.

## Verification Plan
1. Run local unit tests using `./gradlew testDebugUnitTest` to verify we didn't break compilation or any existing tests.
2. Build the app using `./gradlew assembleDebug` or check compilation to ensure no syntax errors.
