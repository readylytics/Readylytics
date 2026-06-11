# Sleep Trend Chart Spec

A dual-axis stacked column and line overlay chart for the Sleep tab to display sleep windows (start and end times) and overall sleep duration over 7D, 30D, and 180D ranges. This chart aligns visually and functionally with the ACWR (Training Load) chart on the Workouts tab.

---

## 1. Objectives & Requirements

1. **Dual-Axis Visualization:**
   * **Left Y-Axis (Time of Day):** Plots the bedtime and wake-up times as a floating vertical bar. Uses a baseline of 12:00 PM (Noon) on the day before the sleep session ends to guarantee positive offsets for stacked columns.
   * **Right Y-Axis (Sleep Duration):** Plots the actual sleep duration (in hours) as a line chart overlay.
   * **X-Axis:** Displays dates matching the selected range (7D, 30D, or 180D).
2. **ACWR Chart Alignment:**
   * Uses similar typography, spacing, cards, and M3 container rules.
   * Employs custom legend items below the chart.
   * Integrates an interactive tooltip overlay with an animated halo effect on selection, matching the ACWR chart selection experience.
3. **Responsive Time Ranges:**
   * Supported ranges: 7 days, 30 days, and 180 days.
   * Leverages the existing `TimeRange` enum.
4. **Adapted Loading Skeleton:**
   * Displays skeleton selectors for the range buttons and a matching skeleton card for the chart container during data load or synchronization.

---

## 2. Architecture & Data Flow

### A. Data Fetching & ViewModel
* We will query the `SleepSessionRepository` for historical sleep sessions in the selected range: `selectedDate.minusDays(range.days - 1)` to `selectedDate`.
* We group these sessions by wake-up date (`LocalDate`).
* We calculate the offsets relative to the Noon baseline:
  ```kotlin
  val zoneId = ZoneId.systemDefault()
  val targetDate = sessionEndDate
  val baselineMs = targetDate.minusDays(1).atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()

  val startOffsetHours = (session.startTime - baselineMs) / 3_600_000f
  val endOffsetHours = (session.endTime - baselineMs) / 3_600_000f
  val sleepSpanHours = endOffsetHours - startOffsetHours
  val actualDurationHours = (session.durationMinutes - session.awakeMinutes) / 60f
  ```
* These metrics are padded using `padToRange` so days with missing data display correctly as gaps.

### B. UI Component Architecture
* **`SleepTrendChart`:** Main card component containing the title, segmented button row, custom legend row, Vico chart host, and tooltip overlay.
* **`SleepChartOverlay`:** Custom canvas overlay that draws selection animations (halo, touch pulse) similar to `AcwrChartOverlay`.
* **`SleepMarkerListener`:** Listens to Vico touch events, determines the closest data point, and updates the local state for tooltips.

---

## 3. Visual Specifications & Alignment

| Property | ACWR Chart | Sleep Trend Chart |
| :--- | :--- | :--- |
| **Card Shape** | `MaterialTheme.shapes.large` (16dp) | `MaterialTheme.shapes.large` (16dp) |
| **Chart Height** | 220.dp | 220.dp |
| **Left Layer** | Column (TRIMP) | Stacked Column (Sleep Start & Span) |
| **Right Layer** | Line (Strain Ratio) | Line (Sleep Duration) |
| **Marker Touch** | Custom listener / halo overlay | Custom listener / halo overlay |
| **Left Color** | `MaterialTheme.colorScheme.outline` | Series 0: Transparent<br>Series 1: `MaterialTheme.colorScheme.primary` |
| **Right Color** | `MaterialTheme.colorScheme.primary` | `MaterialTheme.colorScheme.tertiary` |

---

## 4. Proposed Changes

### A. Core Strings (`app/src/main/res/values/strings.xml`)
```xml
<string name="sleep_trend_title">Sleep Trend</string>
<string name="sleep_trend_legend_window">Sleep window</string>
<string name="sleep_trend_legend_duration">Sleep duration</string>
<string name="sleep_trend_tooltip_duration_format">Duration: %1$.1fh</string>
<string name="sleep_trend_tooltip_bedtime_format">Bedtime: %1$s - %2$s</string>
```

### B. ViewModel & UiState (`ui/sleep/SleepViewModel.kt`)
Add fields to `SleepUiState`:
```kotlin
val selectedTrendRange: TimeRange = TimeRange.SEVEN_DAYS
val trendStartOffsetPoints: List<DailyDataPoint> = emptyList()
val trendDurationSpanPoints: List<DailyDataPoint> = emptyList()
val trendActualDurationPoints: List<DailyDataPoint> = emptyList()
val trendRangeStartMs: Long = 0
```
Add range toggle logic:
```kotlin
fun onTrendRangeSelected(range: TimeRange) {
    // updates the selected trend range flow
}
```

### C. Screens & Components (`ui/sleep/SleepScreen.kt`)
Integrate the trend chart component in `SleepScreen`'s `LazyColumn` between the hypnogram and the metrics grid, referencing `SleepTrendSkeleton` when `uiState.isLoading` is true.

---

## 5. Verification Plan

1. **Unit Tests:**
   * Add tests in `SleepViewModelTest.kt` verifying correct mapping of sleep start/end times relative to the shifted Noon baseline.
   * Verify date padding works correctly for days with missing sleep sessions.
2. **UI Verification:**
   * Build the project: `./gradlew assembleDebug`
   * Run tests: `./gradlew testDebugUnitTest`
   * Verify skeleton screens load correctly.
