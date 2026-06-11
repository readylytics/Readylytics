# Sleep Trend Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a dual-axis sleep trend chart on the Sleep tab showing bedtime/wake-up window as stacked columns and total duration as a line, aligned with the ACWR chart.

**Tech Stack:** Kotlin, Jetpack Compose, Vico 2.x, Hilt, JUnit, MockK.

---

## File Structure

- Create `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendChart.kt`
  - Implement `SleepTrendCard`, `SleepTrendSkeleton`, and actual Vico chart.
- Create `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendMarkerListener.kt`
  - Replicate custom marker visibility listener for tooltip and touch interaction.
- Create `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendOverlay.kt`
  - Implement selection halos and touch feedback.
- Modify `app/src/main/res/values/strings.xml`
  - Add localized string resources for the trend title, legends, formats.
- Modify `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepViewModel.kt`
  - Expose state flows for the historical ranges (7D, 30D, 180D) relative to the Noon baseline.
- Modify `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepScreen.kt`
  - Integrate the trend skeleton and card into the screen's LazyColumn.
- Create `app/src/test/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepViewModelTest.kt`
  - Add unit tests for SleepViewModel trend range updates and mapping logic.

---

### Task 1: Add Localized Resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add new strings**
  Insert the following string resources into `strings.xml`:
  ```xml
  <!-- Sleep Trend Chart -->
  <string name="sleep_trend_title">Sleep Trend</string>
  <string name="sleep_trend_legend_window">Sleep window</string>
  <string name="sleep_trend_legend_duration">Sleep duration</string>
  <string name="sleep_trend_tooltip_duration_format">Duration: %1$.1fh</string>
  <string name="sleep_trend_tooltip_bedtime_format">Bedtime: %1$s - %2$s</string>
  ```

---

### Task 2: Update ViewModel and State

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepViewModel.kt`

- [ ] **Step 1: Update SleepUiState**
  Add the trend-related fields:
  ```kotlin
  val selectedTrendRange: TimeRange = TimeRange.SEVEN_DAYS,
  val trendStartOffsetPoints: List<DailyDataPoint> = emptyList(),
  val trendDurationSpanPoints: List<DailyDataPoint> = emptyList(),
  val trendActualDurationPoints: List<DailyDataPoint> = emptyList(),
  val trendRangeStartMs: Long = 0,
  ```

- [ ] **Step 2: Add range selection and mapping flow**
  1. Add `private val selectedTrendRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)` in the ViewModel.
  2. Combine this range flow with `selectedDateRepository.selectedDate` to observe and map the trend data.
  3. Query `sleepSessionRepository.observeSince(rangeStartMs)`.
  4. Perform the Noon baseline transformation:
     - Shift baseline to Noon: `targetDate.minusDays(1).atTime(12, 0)`.
     - Calculate start offset (hours since Noon).
     - Calculate span (hours between start and end).
     - Calculate actual sleep duration (in hours, e.g., `(durationMinutes - awakeMinutes) / 60f`).
     - Map to lists of `DailyDataPoint` and pad to range.
  5. Feed these mapped points back into `uiState`.
  6. Add function `fun onTrendRangeSelected(range: TimeRange)` to update the trend range flow.

---

### Task 3: Create Sleep Trend Components

**Files:**
- Create: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendOverlay.kt`
- Create: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendMarkerListener.kt`
- Create: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepTrendChart.kt`

- [ ] **Step 1: Implement SleepTrendOverlay**
  Create `SleepTrendOverlay.kt` to draw the selection halo around the selected data points, duplicating the visual feedback of `AcwrChartOverlay.kt`.

- [ ] **Step 2: Implement SleepTrendMarkerListener**
  Create `SleepTrendMarkerListener.kt` to map Vico marker updates to a selected state containing sleep bedtime/duration details.

- [ ] **Step 3: Implement SleepTrendChart**
  Create `SleepTrendChart.kt` featuring:
  - `SleepTrendCard` layout.
  - Range selection segmented buttons.
  - Vico stacked column and line dual-axis configuration:
    - Stacked Column: Series 0 (transparent start offset) + Series 1 (solid primary sleep span).
    - Line: Series 0 (tertiary line for actual duration).
  - Left axis formatter translating hour offsets back to human-readable times (e.g. `10.0` -> `10:00 PM`).
  - Right axis formatter displaying duration.
  - `SleepTrendSkeleton` displaying a clean skeleton of the buttons and card.

---

### Task 4: Integrate and Connect in SleepScreen

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepScreen.kt`

- [ ] **Step 1: Embed components in SleepScreen**
  Insert the trend card (or skeleton) between the hypnogram and the metrics grid:
  ```kotlin
  item(key = "sleep_trend") {
      if (uiState.isLoading) {
          SleepTrendSkeleton(modifier = Modifier.padding(horizontal = 16.dp))
      } else {
          SleepTrendCard(
              selectedRange = uiState.selectedTrendRange,
              startOffsetPoints = uiState.trendStartOffsetPoints,
              durationSpanPoints = uiState.trendDurationSpanPoints,
              actualDurationPoints = uiState.trendActualDurationPoints,
              rangeStartMs = uiState.trendRangeStartMs,
              onRangeSelected = viewModel::onTrendRangeSelected,
              parentScrollInProgress = listState.isScrollInProgress,
              modifier = Modifier.padding(horizontal = 16.dp),
          )
      }
  }
  ```

---

### Task 5: Add Unit Tests

**Files:**
- Create: `app/src/test/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepViewModelTest.kt`

- [ ] **Step 1: Write ViewModel tests**
  1. Add tests verifying `onTrendRangeSelected` updates state.
  2. Verify mapping of sleep start/end times relative to the Noon baseline.
  3. Verify correct padding of days with missing data.

---

### Task 6: Verify Build & Execution

- [ ] **Step 1: Format and run unit tests**
  Run:
  ```powershell
  ./gradlew ktlintFormat && ./gradlew testDebugUnitTest
  ```
