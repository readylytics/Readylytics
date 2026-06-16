# Steps Card Fraction Font Weight Adjustment Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the bold font weight style from the steps fraction text inside `StepsBar.kt`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3).

---

## Scope Check

This plan covers 1 Kotlin source file:
1. `StepsBar.kt` (font weight change)

---

## File Structure & Proposed Changes

### 1. StepsBar
- **File:** `app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt`
- **Changes:** Remove `fontWeight = FontWeight.Bold` from the fraction `Text` component.

---

## Implementation Tasks

- [ ] **Task 1: Remove bold weight from StepsBar fraction**
  - Edit [StepsBar.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/kotlin/app/readylytics/health/ui/components/StepsBar.kt).

- [ ] **Task 2: Verification & Pre-Commit Clean Checks**
  - Run `./gradlew ktlintFormat` to format files.
  - Run `./gradlew testDebugUnitTest` to verify all unit tests pass.
  - Run `./gradlew lint` to check for compliance.
