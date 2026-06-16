# Steps Card Fraction Placement below the Bar Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the steps progress fraction from the top right of the Steps card, and place it right-aligned underneath the steps progress bar instead.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3).

---

## Scope Check

This plan covers 2 Kotlin source files:
1. `StepsCard.kt` (removal of top-right text)
2. `StepsBar.kt` (addition of right-aligned fraction text underneath the progress bar)

---

## File Structure & Proposed Changes

### 1. StepsCard
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt`
- **Changes:** Remove the progress fraction text and formatting logic from the top `Row`.

### 2. StepsBar
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
- **Changes:** Add a bottom `Row` rendering the `current / goal` steps count fraction, aligned to the right.

---

## Implementation Tasks

- [ ] **Task 1: Remove top-right text from StepsCard**
  - Edit [StepsCard.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsCard.kt).

- [ ] **Task 2: Add fraction display underneath the progress bar in StepsBar**
  - Edit [StepsBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt).

- [ ] **Task 3: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for compliance.
