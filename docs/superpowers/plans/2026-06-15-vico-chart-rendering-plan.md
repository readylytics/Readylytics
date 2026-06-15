# Vico Chart Rendering & State Preservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify the container architecture of all detail/dashboard screens hosting Vico charts from `LazyColumn` to a standard scrollable container (`Column` + `verticalScroll`). This natively eliminates the repetitive entry animation glitch while scrolling, ensures smooth scrolling, and preserves chart scroll/zoom/tooltip states.

**Architecture:** 
1. Replace `rememberLazyListState()` with `rememberScrollState()`.
2. Swap out `LazyColumn` for `Column` with `Modifier.verticalScroll()`.
3. Remove layout `item(key = ...)` wrappers, placing layouts directly as `Column` children.
4. Pass `scrollState.isScrollInProgress` for the chart's `parentScrollInProgress` parameter to ensure active vertical scrolls clear the chart's tooltip overlay.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Vico Charting Library (v3.2.2).

---

## Scope Check

This plan covers all screens in the application that render Vico charts within vertical scroll lists. We will migrate these screens one-by-one, verifying that they build and run correctly.

Screens to migrate:
1. **Physiological Vitals Screen** (`VitalsScreen.kt`)
2. **Sleep Screen** (`SleepScreen.kt`)
3. **Blood Pressure Detail Screen** (`BloodPressureDetailScreen.kt`)
4. **Workouts Screen** (`WorkoutsScreen.kt`)
5. **Remaining Detail Screens:**
   - `HeartRateDetailScreen.kt`
   - `StepDetailScreen.kt`
   - `WeightDetailScreen.kt`
   - `BodyFatDetailScreen.kt`
   - `WorkoutDetailScreen.kt`

---

## File Structure & Proposed Changes

### 1. Physiological Vitals Screen

* **File:** `app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt`
  * Replace `LazyColumn` with a standard `Column` + `Modifier.verticalScroll()`.
  * Replace `rememberLazyListState()` with `rememberScrollState()`.
  * Pass `scrollState.isScrollInProgress` to `parentScrollInProgress` on all three trend charts.

### 2. Sleep Screen

* **File:** `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepScreen.kt`
  * Replace `LazyColumn` with `Column` + `Modifier.verticalScroll()`.
  * Replace `rememberLazyListState()` with `rememberScrollState()`.
  * Pass `scrollState.isScrollInProgress` to `parentScrollInProgress` on `SleepTrendCard`.

### 3. Blood Pressure Detail Screen

* **File:** `app/src/main/kotlin/app/readylytics/health/ui/bloodpressure/BloodPressureDetailScreen.kt`
  * Replace `LazyColumn` with `Column` + `Modifier.verticalScroll()`.
  * Replace `rememberLazyListState()` with `rememberScrollState()`.
  * Pass `scrollState.isScrollInProgress` to `parentScrollInProgress` on `BloodPressureSplitChart`.

### 4. Workouts Screen

* **File:** `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutsScreen.kt`
  * Replace `LazyColumn` with `Column` + `Modifier.verticalScroll()`.
  * Replace `rememberLazyListState()` with `rememberScrollState()`.
  * Remove the custom `rememberSaveable` block caching `LazyListState`.
  * Pass `scrollState.isScrollInProgress` to `parentScrollInProgress` on `WorkoutStatsSection`.

### 5. Remaining Detail Screens

* **Files:**
  * `app/src/main/kotlin/app/readylytics/health/ui/heartrate/HeartRateDetailScreen.kt`
  * `app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt`
  * `app/src/main/kotlin/app/readylytics/health/ui/weight/WeightDetailScreen.kt`
  * `app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailScreen.kt`
  * `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutDetailScreen.kt`
  * Apply the same layout migration from `LazyColumn` to `Column` + `Modifier.verticalScroll()` on each screen.

---

## Implementation Tasks

- [ ] **Task 1: Migrate VitalsScreen**
  - Modify [VitalsScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt).
  - Test compiling of the vitals module.

- [ ] **Task 2: Migrate SleepScreen**
  - Modify [SleepScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepScreen.kt).
  - Test compiling of the sleep module.

- [ ] **Task 3: Migrate BloodPressureDetailScreen**
  - Modify [BloodPressureDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/bloodpressure/BloodPressureDetailScreen.kt).
  - Test compiling.

- [ ] **Task 4: Migrate WorkoutsScreen**
  - Modify [WorkoutsScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutsScreen.kt).
  - Test compiling.

- [ ] **Task 5: Migrate Remaining Detail Screens**
  - Modify detail screen files:
    - [HeartRateDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/heartrate/HeartRateDetailScreen.kt)
    - [StepDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt)
    - [WeightDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/weight/WeightDetailScreen.kt)
    - [BodyFatDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailScreen.kt)
    - [WorkoutDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutDetailScreen.kt)
  - Test compiling of all modified modules.

- [ ] **Task 6: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for any coding standards or layout/build issues.
