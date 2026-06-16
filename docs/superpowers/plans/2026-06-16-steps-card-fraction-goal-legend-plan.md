# Steps Card Fraction and Goal Legend Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify the Steps dashboard card to show progress as a fraction (`current / goal`) at the top right, remove steps count under the steps bar, and label the baseline steps line on the detail screen chart as "Goal".

**Tech Stack:** Kotlin, Jetpack Compose (Material 3).

---

## Scope Check

This plan covers 4 files:
1. `strings.xml` (string resources)
2. `StepsCard.kt` (top-right fraction display)
3. `StepsBar.kt` (removal of steps number below the bar)
4. `StepDetailScreen.kt` (passing the "Goal" baseline label to TrendChart)

---

## File Structure & Proposed Changes

### 1. String Resources
- **File:** `app/src/main/res/values/strings.xml`
- **Changes:** Add `<string name="label_goal">Goal</string>` and `<string name="steps_fraction_display">%1$s / %2$s</string>`.

### 2. StepsCard
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt`
- **Changes:** Format current steps and goal and display them as a fraction at the top right.

### 3. StepsBar
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
- **Changes:** Remove the bottom `Row` in `StepsBar` completely.

### 4. StepDetailScreen
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt`
- **Changes:** Pass `baselineLabel = stringResource(R.string.label_goal)` inside the `TrendChart` call.

---

## Implementation Tasks

- [ ] **Task 1: Update String Resources**
  - Edit [strings.xml](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/res/values/strings.xml).

- [ ] **Task 2: Update StepsCard Top-Right Display**
  - Edit [StepsCard.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt).

- [ ] **Task 3: Update StepsBar UI**
  - Edit [StepsBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt).

- [ ] **Task 4: Update StepDetailScreen Chart Legend**
  - Edit [StepDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/steps/StepDetailScreen.kt).

- [ ] **Task 5: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for code standard compliance.
