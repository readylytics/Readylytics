# Card Title Typography and Color Standardization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize all card, chart, and diagram titles in the application to use `titleMedium` and `onSurfaceVariant` for visual uniformity.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3).

---

## Scope Check

This plan covers 4 Kotlin source files:
1. `WorkoutStatsSection.kt` (for RAS Card and ACWR Card)
2. `TrendCharts.kt` (for TrendCard)
3. `InsightCard.kt` (for InsightCard)
4. `HistoryCardLayout.kt` (for HistoryCardLayout)

---

## File Structure & Proposed Changes

### 1. WorkoutStatsSection
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`
- **Changes:**
  - Update RAS card title style to `titleMedium` and color to `onSurfaceVariant`.
  - Update ACWR card title style to `titleMedium` and color to `onSurfaceVariant`.

### 2. TrendCard
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/TrendCharts.kt`
- **Changes:**
  - Update TrendCard title style to `titleMedium` and color to `onSurfaceVariant`.

### 3. InsightCard
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/InsightCard.kt`
- **Changes:**
  - Update InsightCard title style to `titleMedium` and color to `onSurfaceVariant`.

### 4. HistoryCardLayout
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/HistoryCardLayout.kt`
- **Changes:**
  - Update HistoryCardLayout title style to `titleMedium` and color to `onSurfaceVariant`.

---

## Implementation Tasks

- [ ] **Task 1: Update Workout Stats Card Titles**
  - Edit [WorkoutStatsSection.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt).

- [ ] **Task 2: Update TrendCard Title**
  - Edit [TrendCharts.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/TrendCharts.kt).

- [ ] **Task 3: Update InsightCard Title**
  - Edit [InsightCard.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/InsightCard.kt).

- [ ] **Task 4: Update HistoryCardLayout Title**
  - Edit [HistoryCardLayout.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/HistoryCardLayout.kt).

- [ ] **Task 5: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for linter standard warnings.
