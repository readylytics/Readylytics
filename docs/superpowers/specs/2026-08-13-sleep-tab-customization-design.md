# Sleep Tab Layout Customization Design Specification

**Date:** 2026-08-13  
**Status:** Proposed  
**Author:** Antigravity / Readylytics Team  

---

## 1. Overview & Objectives

Following the pattern introduced in PR #211 for the Vitals tab, this specification details the design for customizable layouts on the **Sleep Screen**.

Users will be able to:
1. Reorder, hide/show, and customize display modes for components across 3 categories:
   - **Category 1 (Top Cards & Visuals):** Sleep Score Dial, Sleep Duration Gauge, Sleep Architecture Bar, Hypnogram Timeline, Sleep HR Chart.
   - **Category 2 (Duration & Schedule Chart):** Sleep Duration & Schedule Trend Chart.
   - **Category 3 (Metrics Grid):** Circadian Consistency, Sleep Efficiency, Deep Sleep %, REM Sleep %, Nap Duration, Nap Count.
2. Manage layout preferences via an **Edit Mode FAB** on the Sleep screen that opens a unified **Sleep Management Bottom Sheet**.
3. Persist layout preferences locally via DataStore.
4. Back up and restore layout configurations via `LocalBackupManager` and `LocalRestoreManager`.
5. Reset layout preferences back to defaults in Settings or directly inside the bottom sheet.

---

## 2. Architecture & Domain Models

### 2.1 Identifiers

In `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/`:

- **`SleepTopCardId`** (Enum):
  - `SLEEP_SCORE`
  - `SLEEP_DURATION_GAUGE`
  - `SLEEP_BREAKDOWN_BAR`
  - `SLEEP_STAGES_TIMELINE`
  - `SLEEP_HR_CHART`

- **`SleepChartId`** (Enum):
  - `SLEEP_DURATION_TREND`

- **`SleepMetricCardId`** (Enum):
  - `CIRCADIAN_CONSISTENCY`
  - `SLEEP_EFFICIENCY`
  - `DEEP_SLEEP`
  - `REM_SLEEP`
  - `NAP_DURATION`
  - `NAP_COUNT`

### 2.2 Configurations & Repository

- **`SleepTopCardConfiguration`**: Card ID + `visible` (Boolean) + `displayMode` (`UniversalCardDisplayMode`).
- **`SleepChartConfiguration`**: Chart ID + `visible` (Boolean).
- **`SleepMetricCardConfiguration`**: Metric Card ID + `visible` (Boolean) + `displayMode` (`UniversalCardDisplayMode`).

- **`SleepLayoutRepository`** Interface:
  - `val topCardsFlow: Flow<List<SleepTopCardConfiguration>>`
  - `val chartsFlow: Flow<List<SleepChartConfiguration>>`
  - `val metricCardsFlow: Flow<List<SleepMetricCardConfiguration>>`
  - `suspend fun setTopCards(cards: List<SleepTopCardConfiguration>)`
  - `suspend fun setCharts(charts: List<SleepChartConfiguration>)`
  - `suspend fun setMetricCards(cards: List<SleepMetricCardConfiguration>)`
  - `suspend fun resetToDefaults()`

---

## 3. Data Persistence & Backup / Restore

1. **Proto DataStore / DataStore Preferences**:
   - `sleep_layout_configurations.proto` schema storing ordered lists of top cards, charts, and metric cards.
   - `SleepLayoutRepositoryImpl` implementation following `VitalsLayoutRepositoryImpl`.
2. **Backup & Restore**:
   - `BackupModels.kt`: Add `SleepLayoutBackup` containing top cards, charts, and metric cards layout state.
   - `LocalBackupManager.kt`: Export `SleepLayout` to backup JSON.
   - `LocalRestoreManager.kt`: Import and restore `SleepLayout` settings.

---

## 4. Presentation & Management UI

### 4.1 Management Bottom Sheet
- `SleepManagementBottomSheet.kt`: A tabbed or sectioned bottom sheet:
  - **Top Cards Tab/Section**: Reorderable list, visibility toggles, mode picker for score/gauge.
  - **Trends Tab/Section**: Chart visibility toggle and reordering.
  - **Metrics Grid Tab/Section**: Reorderable grid/list of individual sleep metric cards with visibility toggles.
- Action controls: "Reset to Defaults" and "Done".

### 4.2 ViewModel Wiring
- `SleepViewModel.kt` will integrate layout management delegates:
  - `topCardManagementDelegate` (handling reorder, visibility, display mode)
  - `chartManagementDelegate` (handling reorder, visibility)
  - `metricCardManagementDelegate` (handling reorder, visibility)
- `SleepUiState` will include visible, ordered item lists to drive data-driven rendering of the `SleepScreen`.

---

## 5. Verification & Testing Strategy

1. **Domain & Data Unit Tests**:
   - `SleepLayoutRepositoryTest`: Verify default layouts, persistence, order preservation, and reset to defaults.
   - Backup / restore unit tests in `LocalBackupManagerTest` and `LocalRestoreValidationTest`.
2. **ViewModel Unit Tests**:
   - `SleepViewModelLayoutManagementTest`: Test state updates when cards/charts/metrics are reordered or toggled.
3. **UI / Integration Tests**:
   - Pre-commit check: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
   - Post-implementation check: `./gradlew lintRelease`.

---
