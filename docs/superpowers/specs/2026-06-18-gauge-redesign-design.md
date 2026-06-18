# Design Spec: Readylytics Gauge Redesign (Soft Arc Metric Card)

* **Date**: 2026-06-18
* **Author**: Antigravity (Senior Android UI/UX Engineer)
* **Status**: Proposed

---

## 1. Overview & Visual Target

The goal of this UI-only redesign is to replace the existing circular `M3ScoreDial` gauges across the application with a new, unified visual component: the **Soft Arc Metric Card**.

### Visual Target Layout
```text
RHR                                      ⓘ

        ╭────────────╮
      ╭─╯            ╰─╮

              48
             bpm

        ↓ 3 bpm
```

### Visual Requirements
1. **Card Container**:
   * Uses the same background color styling as the existing **NEUTRAL** metric card (`MaterialTheme.colorScheme.surfaceContainerHighest`).
   * Rounded corners using `MaterialTheme.shapes.large` (16dp).
   * Fixed height of `156.dp` with responsive width (matching standard `MetricCard` dimensions).
   * Padding consistent with other metric cards (`horizontal = 16.dp, vertical = 12.dp`).
2. **Header**:
   * Metric title on the top-left (e.g. `RHR`).
   * Info tooltip button on the top-right (opens the existing info dialog).
   * No left-side metric icon, and no extra status text in the header.
3. **Gauge Arc**:
   * Elegant, thin semi-circular arc (stroke width `6.dp` with rounded caps).
   * Active arc uses the existing value-based status color coding (`status.gaugeColor()`).
   * Inactive track is a subtle, low-contrast color (`MaterialTheme.colorScheme.surfaceVariant`).
   * A small filled dot (radius `4.dp`) drawn at the tip/endpoint of the active arc.
4. **Centered Value**:
   * Large centered value number (approx. `32.sp` display typography).
   * Unit placed directly below the value in smaller, secondary text.
5. **Baseline Chip**:
   * Subtle pill below the gauge displaying delta or comparison values (e.g., `↓ 3 bpm`, `↑ 0.5%`).
   * Hides completely if no delta value is available/passed.

---

## 2. Component API (`M3ScoreGaugeCard`)

We will define the new component in `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreGaugeCard.kt`:

```kotlin
package app.readylytics.health.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.domain.model.BaselineArrow
import app.readylytics.health.domain.model.MetricStatus

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

---

## 3. Visual Rendering & Canvas Math

The semi-circular gauge arc is drawn using custom `Canvas` operations:
* **Start Angle**: `180f` (representing the left edge at 9 o'clock).
* **Sweep Angle**: `180f` (sweeping clockwise to 3 o'clock).
* **Active Sweep**: `180f * progress` where `progress = ((score ?: 0f) / maxScore).coerceIn(0f, 1f)`.
* **Endpoint Dot Cartesian Coordinates**:
  ```kotlin
  val strokeWidthPx = 6.dp.toPx()
  val radius = (size.width - strokeWidthPx) / 2f
  val centerX = size.width / 2f
  val centerY = size.height // Anchored at the bottom center of the semi-circle bounding area

  val endAngle = 180f + (180f * progress)
  val endAngleRad = Math.toRadians(endAngle.toDouble())
  val dotX = centerX + radius * cos(endAngleRad).toFloat()
  val dotY = centerY + radius * sin(endAngleRad).toFloat()
  ```
* **Drawing Code**:
  1. `drawArc` for the background track (`MaterialTheme.colorScheme.surfaceVariant`).
  2. `drawArc` for the active progress (`status.gaugeColor()`).
  3. `drawCircle` at `(dotX, dotY)` with radius `4.dp` and color `status.gaugeColor()`.

---

## 4. Call-Site Integration & Delta Calculations

We will map baseline diffs and comparison values for each gauge:

| Tab / Screen | Metric / Gauge | Delta Calculation Logic | Formatted Delta Display |
| :--- | :--- | :--- | :--- |
| **Dashboard** | Sleep Score | Compare to yesterday: `today - yesterday` | `↑ 5` or `↓ 3` |
| **Dashboard** | Readiness | Compare to yesterday: `today - yesterday` | `↑ 4` or `↓ 2` |
| **Vitals Tab** | RHR | Compare to baseline: uses precomputed `rhrBaselineDiff`/`rhrBaselineArrow` | `↓ 3 bpm` or `↑ 2 bpm` |
| **Vitals Tab** | HRV | Compare to baseline: uses precomputed `hrvBaselineDiff`/`hrvBaselineArrow` | `↓ 5 ms` or `↑ 4 ms` |
| **Sleep Tab** | Sleep Score | Compare to yesterday: `today - yesterday` | `↑ 4` or `↓ 2` |
| **Sleep Tab** | Sleep Time | Difference to goal: `actualMinutes - goalMinutes` | `↓ 30m` or `↑ 15m` |
| **Workouts Tab**| Strain Ratio | Compare to yesterday: `today - yesterday` | `↑ 0.1` or `↓ 0.2` |
| **Workouts Tab**| Readiness | Compare to yesterday: `today - yesterday` | `↑ 5` or `↓ 3` |
| **BP Detail** | Systolic | Difference to optimal (120 mmHg): `systolic - 120` | `↑ 10 mmHg` or `↓ 5 mmHg` |
| **BP Detail** | Diastolic | Difference to optimal (80 mmHg): `diastolic - 80` | `↑ 5 mmHg` or `↓ 2 mmHg` |
| **Body Fat Detail**| Body Fat | Delta to last measurement: `latest - previous` | `↓ 0.5%` or `↑ 0.3%` |
| **Steps Detail** | Steps | Difference to goal: `actualSteps - stepGoal` | `↓ 1,200` or `↑ 13,000` |
| **Weight Detail**| Weight | Compare to previous weight (uses `deltaDisplay`) | `↓ 0.4 kg` or `↑ 0.6 lbs` |

---

## 5. Files Affected

1. **New Component**:
   * `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreGaugeCard.kt`
2. **Skeleton & Shimmers**:
   * `app/src/main/kotlin/app/readylytics/health/ui/common/SkeletonCard.kt` (Redesign `ScoreDialSkeleton` to be a large M3 rounded card)
3. **Screen Integrations**:
   * `app/src/main/kotlin/app/readylytics/health/ui/dashboard/DashboardCardFactory.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepScreen.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/bloodpressure/BloodPressureDetailScreen.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailScreen.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/weight/WeightDetailScreen.kt`
4. **ViewModels / UI States (to expose historical/delta values)**:
   * `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepUiState.kt` / `SleepViewModel.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepTimeGaugeData.kt`
   * `app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailViewModel.kt`
5. **Cleanups**:
   * Remove `app/src/main/kotlin/app/readylytics/health/ui/components/M3ScoreDial.kt` once deprecated.

---

## 6. Step-by-Step Implementation Plan

1. **Step 1**: Create `M3ScoreGaugeCard.kt` with the custom Canvas drawing logic for the soft arc (using math detailed in Section 3) and baseline delta chip support.
2. **Step 2**: Modify `ScoreDialSkeleton` in `SkeletonCard.kt` to match the rounded card shape and size (`height = 156.dp`).
3. **Step 3**: Update `SleepTimeGaugeData.kt` to precompute the diff to sleep goal and expose it in `SleepTimeGaugeData` as `deltaText`.
4. **Step 4**: Update `BodyFatDetailViewModel.kt` to expose the delta to the last measurement.
5. **Step 5**: Update `SleepViewModel.kt` (and `SleepUiState`) to retrieve and expose yesterday's sleep score for delta calculations.
6. **Step 6**: Integrate the new card into the Dashboard (`DashboardCardFactory.kt`). Update card wrapper bounds to prevent squishing.
7. **Step 7**: Integrate the new card into `VitalsScreen.kt`, `SleepScreen.kt`, and `WorkoutStatsSection.kt`, swapping the twin side-by-side circular gauges for the new cards.
8. **Step 8**: Integrate the new card into the detail screens (`BloodPressureDetailScreen.kt`, `BodyFatDetailScreen.kt`, `StepDetailScreen.kt`, `WeightDetailScreen.kt`).
9. **Step 9**: Verify compilation, clean up the retired `M3ScoreDial.kt`, and run verification tests.

---

## 7. Risk Assessment & Mitigation

* **Risk**: Card sizing squishing in grids.
  * *Mitigation*: Ensure callers pass `Modifier.weight(1f)` or `Modifier.fillMaxWidth()` rather than fixed `Modifier.size(130.dp)`. Adjust height bounds in `ReorderableCardGrid.kt` if necessary.
* **Risk**: No scoring changes constraint violated.
  * *Mitigation*: Strictly map from existing values and perform simple subtractions/lookups at the UI/ViewModel level. Under no circumstances will any scoring rules or database schemas be touched.

---

## 8. Verification Plan

* **Dashboard Verification**: Check reorderable grid item rendering, drag-and-drop previews, and edit mode visual layouts.
* **Detail Pages Verification**: Validate Systolic/Diastolic, Body Fat, Steps, and Weight layout integration.
* **Theme & Mode Verification**: Ensure correct background contrast in both light mode and dark mode.
* **Screen Size & Scale**: Validate using preview panels for small screen form factors, dynamic font scaling (SP sizes), and TalkBack readability.
* **Testing**: Run `./gradlew testDebugUnitTest` to verify all view model tests and layout constraints pass without regressions.
