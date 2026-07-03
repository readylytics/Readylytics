# Design Doc: Spacing Alignment Between Sleep, Vitals, and Workouts Charts

## Status: Approved

## Goal
Align the spacing between the TimeRange pickers (segmented buttons) and their corresponding trend charts across the Sleep screen, Vitals screen, and Workouts stats screen to a consistent `pageSectionGapSmall` (8.dp) to establish a tighter visual relationship.

---

## Detailed Modifications

### 1. Sleep Module (`feature/sleep`)
* **File:** `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
  * Modify the height of the Spacer between `SingleChoiceSegmentedButtonRow` and `SleepTrendCard`/`SleepTrendSkeleton` from `MaterialTheme.spacing.pageSectionGap` to `MaterialTheme.spacing.pageSectionGapSmall`.

### 2. Vitals Module (`feature/vitals`)
* **File:** `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`
  * Update `Spacer` heights before and after `SingleChoiceSegmentedButtonRow` from `MaterialTheme.spacing.small` to `MaterialTheme.spacing.pageSectionGapSmall`.
  * Update padding in `SingleChoiceSegmentedButtonRow`, `SkeletonCard`, and `TrendCard` from `MaterialTheme.spacing.medium` to `MaterialTheme.spacing.pageHorizontal`.

### 3. Workouts Module (`feature/workouts`)
* **File:** `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutStatsSection.kt`
  * Import `app.readylytics.health.core.designsystem.spacing`.
  * Update vertical height `Spacer` before and after `SectionHeader` from `8.dp` to `MaterialTheme.spacing.pageSectionGapSmall`.
  * Update horizontal padding on `SingleChoiceSegmentedButtonRow` from `16.dp` to `MaterialTheme.spacing.pageHorizontal`.
  * Update the vertical height `Spacer` between `SingleChoiceSegmentedButtonRow` and `CardLoader` (picker-to-chart spacer) from `16.dp` to `MaterialTheme.spacing.pageSectionGapSmall`.

---

## Verification Plan

### 1. Compilation
Verify compilation of the three touched modules:
```powershell
.\gradlew :feature:sleep:compileDebugKotlin :feature:vitals:compileDebugKotlin :feature:workouts:compileDebugKotlin
```

### 2. Formatting & Tests
```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
```
