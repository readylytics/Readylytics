# CLAUDE.md - Huawei Health Dashboard

## Overview

A native Android application acting as a personalized health dashboard. It pulls data directly from the user's Huawei wearable via the Huawei Health Kit SDK (HRV, SpO2, Sleep, RHR, Temp) and displays actionable insights with historical baseline-deviation color coding.

## Tech Stack & Architecture

- **Platform:** Android (Kotlin)
- **Architecture:** MVVM (Model-View-ViewModel) with a "Single Source of Truth" Repository pattern.
- **UI Framework:** Jetpack Compose.
- **Local Database:** Room (SQLite) for caching daily aggregations.
- **Huawei SDKs:** HMS Core Account Kit (Authentication) & Health Kit (Data ingestion).
- **Charting:** Vico or Compose Canvas.
- **Preferences:** Preferences DataStore (for Theme and basic settings).

## Code Style & Conventions

- **Kotlin:** Use strict typing, Coroutines, and Kotlin Flows for asynchronous data handling. No deprecated standard Java Threading.
- **UI:** Maintain stateless composables where possible. State must be hoisted to ViewModels.
- **Data Layer:** The Repository is the absolute source of truth. Always query the Room DB for data first, calculate missing days based on timestamp checks (11:59:59 PM), and ONLY hit the Huawei Health SDK for the exact missing days.
- **Styling:** Use semantic color naming (e.g., `color_positive_trend` for Green, `color_negative_trend` for Red) rather than hardcoded Hex values, to smoothly support the System/Light/Dark Mode toggle.

## Custom Agents & Delegation

This project utilizes specialized agents located in `.claude/agents/`. When working on specific steps, assume the persona or delegate to the appropriate agent profile:

- **`@security-auditor.md`**:
  - **Use for:** Step 1 (Authentication).
  - **Responsibilities:** Ensure the HMS Account Kit integration handles tokens securely, requests the principle of least privilege for scopes, and validates that exported data (Step 6) is handled safely.
- **`@kotlin-specialist.md`**:
  - **Use for:** Step 2 (Data Ingestion) & Step 5 (Local Caching).
  - **Responsibilities:** Architect the Room Database entities, construct the Timestamp/Delta sync logic, and ensure robust Coroutine/Flow implementations for the `DataController` responses.
- **`@mobile-app-developer.md` / `@mobile-developer.md`**:
  - **Use for:** Step 3 (Dashboard), Step 4 (Trend Views), & Step 6 (Settings UI).
  - **Responsibilities:** Build out the Jetpack Compose UI, implement the MetricEvaluator color-coding logic, integrate the charting library, and handle the Android Storage Access Framework (SAF) for data export/import.

## Commands

- **Build Debug APK:** `./gradlew assembleDebug`
- **Run Unit Tests:** `./gradlew testDebugUnitTest`
- **Clean Project:** `./gradlew clean`

## Agent Workflow Instructions

1. **Follow the Step Plans:** The project is broken down into specific implementation markdown files (e.g., `01_Step_1_Authentication.md`, `02_Step_2_Data_Ingestion.md`, etc.).
2. **Sequential Execution:** You must work step-by-step. Do not proceed to the next step until the current step is fully implemented, builds successfully, and is validated.
3. **Data Verification:** When implementing the Huawei SDK fetching logic, prioritize logging (`Log.d()`) the raw JSON/SampleSet payloads to verify data pipelines before attempting to build complex Compose UIs.
4. **Baseline Math:** When calculating the 30-day dynamic baseline for the dashboard, always filter out `null` or invalid days from the Room database before calculating the average.
