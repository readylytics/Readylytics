# Comprehensive Implementation Plan: Material You Health & Recovery Dashboard (Android 15)

This document serves as the primary technical specification for building the Android Health & Recovery Dashboard. It integrates advanced biometric scoring frameworks, Material 3 design principles, and Google Health Connect.

## 👥 Assigned Agents & Skills
- **android-ninja**: Android 15 / Compose architecture specialist.
- **ui-designer**: Material 3 / Dynamic Color / Vico charting expert.
- **math-specialist**: Precision scoring logic and statistical median calculations.
- **database-optimizer**: Room, SQLite performance, and timeframe-specific queries.
- **api-designer**: Health Connect and Google Drive AppData integration.
- **kotlin-expert**: Idiomatic Kotlin, Coroutines, and Flow management.

---

## Phase 1: Project Initialization & CI Pipeline
**Goal:** Establish the codebase foundation, formatting standards, and continuous integration to enforce code quality from day one.
- **Context:** Catching build and formatting errors early keeps the project clean for Claude Code to work efficiently.
- **Actions:**
  - **Formatting & Linting:** Integrate `Ktlint` and standard Android Linting into the Gradle build.
  - **GitHub Actions (CI):** Create a `.github/workflows/ci.yml` file that triggers on every push and pull request. 
  - **Pipeline Steps:** The workflow must run `./gradlew lint`, the formatting check, and `./gradlew assembleDebug` to ensure the app builds successfully.
- **Assigned Agents:** `android-ninja`, `kotlin-expert`.

---

## Phase 2: Local Database & User Preferences
**Goal:** Establish a robust, offline-first data layer and initialize user-specific targets.
- **Context:** The app must be functional without an internet connection. Room acts as the Single Source of Truth.
- **Actions:**
  - **DataStore:** Setup a repository for:
    - `goal_sleep_time` (default: 8h).
    - `manual_baseline_overrides` for HRV and RHR.
    - `sync_preference` (Enum: NEVER, ALWAYS, BY_TIME - default: BY_TIME).
    - `sync_interval_hours` (Int: 1 to 24 - default: 1).
    - `last_sync_timestamp` (Long: to track when Health Connect was last successfully polled).
  - **Room Database:** - `SleepSession`: Timestamps, duration, efficiency, stages (Deep/REM %), scores.
    - `HeartRateRecord` & `HRVRecord`: High-frequency biometric samples.
    - `WorkoutRecord`: Type, duration, heart rate zone durations (Z1-Z5), and calculated TRIMP.
    - `DailySummary`: Pre-computed aggregates for the Dashboard.
  - **DAOs:** Must support queries for specific rolling windows: 7-day, 30-day (for baselines), and 42-day (for Chronic Training Load).
- **Assigned Agents:** `database-optimizer`, `android-ninja`.

---

## Phase 3: Health Connect Sync Engine (Android 15) & Permissions UX
**Goal:** Seamless ingestion of system-level biometric data with robust permission and interval handling.
- **Context:** Android 15 treats Health Connect as a system service. Proper lifecycle observation and UX for permissions are critical.
- **Actions:**
  - **Permissions UX:** On app launch, check if `READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, and `READ_EXERCISE` are fully granted. If missing, show a polished onboarding explaining *why* the data is needed, with a button deep-linking directly to system Health Connect settings.
  - **Foreground Sync Logic:** When the app comes to the foreground, evaluate DataStore preferences:
    - **NEVER:** Do nothing (rely entirely on manual pull-to-refresh).
    - **ALWAYS:** Trigger a sync immediately.
    - **BY_TIME:** If current time minus `last_sync_timestamp` > `sync_interval_hours`, trigger the sync.
  - **Historical Pull:** On first successful permission grant, perform a 42-day "Catch-up" sync to establish immediate baselines.
  - **Processing:** - Parse `SleepStageRecord` to calculate $P_{deep}$ and $P_{rem}$ percentages.
    - Filter `HeartRateRecord` samples to find the **Nocturnal RHR**.
    - Map exercise heart rate samples into the 5-zone model to determine time-in-zone for TRIMP calculation.
- **Assigned Agents:** `api-designer`, `android-ninja`, `ui-designer`.

---

## Phase 4: The Scoring Engine (Mathematical Algorithms)
**Goal:** Implement the "Biometric Scoring Framework" with high precision.
- **Context:** This logic should reside in a pure-Kotlin `ScoringRepository` for unit testing.
- **1. Edwards' TRIMP Formula:**
  - $TRIMP = \sum_{i=1}^{5} (D_i \times W_i)$
  - Weights ($W_i$): Z1=1, Z2=2, Z3=3, Z4=4, Z5=5.
- **2. Strain Ratio (SR / ACWR):**
  - $SR = \frac{\text{7-day average TRIMP}}{\text{42-day average TRIMP}}$
- **3. Readiness / Load Score ($S_{load}$):**
  - Optimal ($0.8 \le SR \le 1.2$): 100
  - Fatigued ($1.2 < SR \le 1.5$): $100 - (SR - 1.2) \times 200$
  - Over-reached ($SR > 1.5$): 40
  - Under-trained ($SR < 0.8$): 80
- **4. Sleep Score ($SS$):**
  - $SS = (0.50 \times S'_{dur}) + (0.25 \times S'_{arch}) + (0.25 \times S'_{rest})$
  - $S'_{dur}$ = Total Sleep Time vs Goal and Efficiency.
  - $S'_{arch}$ = REM/Deep % vs 20% benchmarks.
  - $S'_{rest}$ = HRV Z-Score and RHR Ratio (Current Sleep RHR / 30-day Median Sleep RHR).
- **Assigned Agents:** `math-specialist`, `kotlin-expert`.

---

## Phase 5: Material 3 UI Architecture & Settings
**Goal:** Implement the "Material You" scaffold and adaptive theme.
- **Context:** Use `dynamicDarkColorScheme()` to pull colors from the system wallpaper.
- **Actions:**
  - **Scaffold:** Bottom Navigation (Dashboard, Sleep, Workouts, Settings).
  - **Settings UI Requirements:**
    - `goal_sleep_time` input.
    - Manual baseline overrides.
    - **Foreground Sync Options:** Dropdown (`Never`, `Always`, `By Time`) + Dynamic interval selector (1-24h).
    - Google Drive account status / manual backup button.
  - **Tooltips:** Implement a shared `MetricTooltip` component.
- **Assigned Agents:** `ui-designer`, `android-ninja`.

---

## Phase 6: Main Dashboard (Tab 1)
**Goal:** A simplified view of readiness and last night's recovery with interactive data refreshing.
- **Actions:**
  - **Pull-to-Refresh UX:** Wrap the dashboard in a `PullToRefreshBox`. Pulling down triggers an immediate sync (bypassing time preferences) and updates `last_sync_timestamp`.
  - **Dual Circular Dials:** Prominent visual for **Sleep Score** and **Load Score** at the top.
  - **Status Cards Grid (Values Only):** Below the dials, implement a 2-column grid of rounded cards for Sleep RHR, Sleep HRV, and Sleep Duration. 
    - **CRITICAL UX:** These cards must *only* display the title, the primary numeric value, and the unit. **Do NOT show any graphs, sparklines, or diagrams on these dashboard cards.**
  - **Dynamic Coloring:** Apply Material You semantic colors to the card text/backgrounds based on status:
    - `success` (or SuccessContainer) for optimal metrics.
    - `tertiary` for Fatigue/Warning states.
    - `error` for Over-reached/Poor states.
    - `SurfaceVariant` (Grey) for the "Calibrating" state (< 7 days data).
  - **Interaction:** Clicking any card routes the user to its respective Detail View.
- **Assigned Agents:** `ui-designer`, `android-ninja`.

---

## Phase 7: Sleep Details (Tab 2)
**Goal:** In-depth analysis of sleep architecture and autonomic recovery.
- **Actions:**
  - **Detail View UX:** A large hero section at the top displaying the current/latest value (e.g., latest Sleep Score or RHR). Below the hero section, display the full-width Vico chart area.
  - **Time Range Selectors:** Place pill-shaped segmented controls (`[7D | 30D | 180D]`) prominently above or below the chart to dynamically update the visible data window.
  - **Charts:** - **Architecture Graph:** Stacked horizontal bar representing time in Awake, Light, Deep, and REM stages.
    - **Restoration Trends:** Vico line charts for HRV and RHR Trends over the selected time ranges.
  - **Metric Grid:** Cards for Sleep Efficiency, Deep %, and REM % with info icons.
- **Assigned Agents:** `ui-designer`, `math-specialist`.

---

## Phase 8: Workout & Strain Details (Tab 3)
**Goal:** Tracking long-term training load and physiological strain.
- **Actions:**
  - **Detail View UX:** Similar to Sleep Details, show the latest Load Score and Strain Ratio prominently at the top.
  - **Time Range Selectors:** Segmented buttons (`[7D | 30D | 180D]`) controlling the main chart.
  - **Strain Chart (ACWR):** Dual-axis Vico chart overlaying Daily TRIMP (bars) with Chronic Load (line) spanning the selected time range.
  - **Workout History:** Scrollable list showing the TRIMP value and Intensity Badge.
- **Assigned Agents:** `ui-designer`, `math-specialist`.

---

## Phase 9: Cloud Backup & Google Drive
**Goal:** Automated synchronization and data safety.
- **Actions:**
  - **Auth:** Implement Google Sign-In with `Drive.SCOPE_APPFOLDER`.
  - **Sync Logic:** WorkManager to zip the Room `.db` file and upload to Drive weekly/on-demand.
  - **Restore:** Logic to download and replace the local database.
- **Assigned Agents:** `api-designer`, `android-ninja`.

---

## Phase 10: Unit Testing & Automated Test Pipeline
**Goal:** Ensure the mathematical scoring algorithms and data transformation layers are robust and regressions are prevented.
- **Context:** The health metrics are the core value of this app; they must be statistically accurate.
- **Actions:**
  - **Core Algorithm Tests:** Write comprehensive JUnit 5 / JUnit 4 tests for the `ScoringRepository`. Test TRIMP zone logic, ACWR strain calculation boundaries, and median baseline logic using mock datasets.
  - **Data Layer Tests:** Write test cases for the Room DAOs to ensure 7, 30, and 42-day rolling queries correctly filter timestamps.
  - **Pipeline Extension:** Update the GitHub Actions `.github/workflows/ci.yml` from Phase 1 to include `./gradlew testDebugUnitTest`.
- **Assigned Agents:** `math-specialist`, `kotlin-expert`, `android-ninja`.

---

## Appendix: Tooltip Glossary (UI Resource Reference)
| Metric | Tooltip Text |
|---|---|
| **Sleep Score** | A comprehensive rating of your rest based on total duration, restorative stages (Deep/REM), and heart rate recovery. |
| **Readiness Score** | A measure of how prepared your body is for stress today, balancing recent workout load against last night's recovery. |
| **Sleep Efficiency** | The percentage of time actually asleep while in bed. (Goal: >85%). |
| **Deep Sleep %** | Time in Slow Wave Sleep; responsible for tissue repair and growth hormone release. |
| **REM Sleep %** | Time in Rapid Eye Movement sleep; vital for memory and emotional processing. |
| **HRV Z-Score** | Your Heart Rate Variability compared to your unique 60-day normal range. |
| **RHR Ratio** | Comparison of nocturnal resting heart rate to your personal 30-day baseline. |
| **Strain Ratio** | The relationship between short-term fatigue (7-day) and long-term fitness (42-day). |
| **Daily TRIMP** | Total physiological stress of workouts, calculated by weighting time in heart rate zones. |
