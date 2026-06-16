# RAS Card Total Score Display and Bottom Value Removal Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify the RAS card on the Workouts tab to show the 7-day rolling total score in the top right, and remove the bottom score value from the progress bar.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3).

---

## Scope Check

This plan covers 2 Kotlin source files:
1. `WorkoutStatsSection.kt` (top-right total score display)
2. `RasWeeklyBar.kt` (removal of score value below the weekly bar)

---

## File Structure & Proposed Changes

### 1. WorkoutStatsSection
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`
- **Changes:** Replace the daily earned score text component with the current 7-day total score text component.

### 2. RasWeeklyBar
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt`
- **Changes:** Remove the bottom `Row` and trailing `Spacer` from `RasWeeklyBar` completely.

---

## Implementation Tasks

- [ ] **Task 1: Update Workout Stats RAS Card Top-Right Display**
  - Edit [WorkoutStatsSection.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt).

- [ ] **Task 2: Remove bottom value display from RAS Bar**
  - Edit [RasWeeklyBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt).

- [ ] **Task 3: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for compliance.
