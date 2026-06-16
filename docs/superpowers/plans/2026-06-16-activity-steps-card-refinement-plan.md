# Readylytics Activity Score and Steps Card Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up the UI of the Readylytics Activity Score (RAS) card on the Workouts tab and the Daily Steps card on the Dashboard by renaming titles, formatting values, and removing status labels/suffixes.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android Resources (Strings xml).

---

## Scope Check

This plan covers three target areas of the codebase:
1. Android resources (`strings.xml`) for the title update.
2. The custom `RasWeeklyBar.kt` component for current score formatting and status label removal.
3. The custom `StepsBar.kt` component for step count formatting and status label removal.

---

## File Structure & Proposed Changes

### 1. Android String Resources
- **File:** `app/src/main/res/values/strings.xml`
- **Changes:** Change `<string name="workout_stats_ras_title">RAS</string>` to `<string name="workout_stats_ras_title">Readylytics Activity Score</string>`.

### 2. RAS Card Component
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt`
- **Changes:**
  - Remove `" RAS"` suffix from total RAS value text.
  - Delete the status label `Text(text = status.name, ...)` from the layout.

### 3. Steps Card Component
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
- **Changes:**
  - Remove `" steps"` suffix from steps value text: replace `"${count.formatSteps()} steps"` with `count.formatSteps()`, and `"-- steps"` with `"--"`.
  - Delete the status label `Text(text = if (stepCount != null) status.name else "NO DATA", ...)` from the layout.

---

## Implementation Tasks

- [ ] **Task 1: Update String Resources**
  - Edit [strings.xml](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/res/values/strings.xml) at line 867.
  
- [ ] **Task 2: Refine RAS Bar UI**
  - Edit [RasWeeklyBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/RasWeeklyBar.kt) at lines 112–125.

- [ ] **Task 3: Refine Steps Bar UI**
  - Edit [StepsBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt) at lines 196–209.

- [ ] **Task 4: Build Verification & Formatting**
  - Run `./gradlew ktlintFormat` to auto-format files.
  - Run `./gradlew testDebugUnitTest` to verify unit tests pass.
  - Run `./gradlew lint` to verify build standards.
