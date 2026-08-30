# Residual Fatigue UI & Visualizations Design Specification

**Date:** 2026-08-29  
**Status:** Approved  
**Scope:** Dashboard Stats Card (`CardId.RESIDUAL_FATIGUE`) and Workouts 24-hour Residual Fatigue Curve Chart (`WorkoutChartId.RESIDUAL_FATIGUE_CURVE`).

---

## 1. Executive Summary

This specification defines the UI presentation and visualization layer for Residual Fatigue in Readylytics:
1. **Dashboard Stats Card:** A customizable, default-hidden stats card on the main Dashboard displaying the current day's Residual Fatigue snapshot as a Gauge, Bar, or Value (0–100 scale, color-classified).
2. **24-Hour Workouts Curve Chart:** A customizable, default-hidden Cartesian line chart on the Workouts tab displaying the continuous exponential decay curve over the selected 24-hour day (midnight to midnight) with 15-minute sampling and exact workout impulse peaks.

---

## 2. Architecture & Data Flow

```mermaid
graph TD
    subgraph Room DB
        DS[daily_summaries: residualFatigue]
        WR[workout_records: modelTrimp, endTime]
    end

    subgraph Dashboard Tab
        DS --> DMPF[DashboardMetricPresentationFactory]
        DMPF --> UMC[UniversalMetricCard: CardId.RESIDUAL_FATIGUE]
    end

    subgraph Workouts Tab
        WR -->|getCanonicalFatigueSeed| UCRF[Generate24hResidualFatigueCurveUseCase]
        Prefs[UserPreferences: halfLife, gain] --> UCRF
        UCRF --> WVM[WorkoutsViewModel / WorkoutsUiState]
        WVM --> RFC[ResidualFatigueCurveChart: WorkoutChartId.RESIDUAL_FATIGUE_CURVE]
    end
```

### 2.1 Principles
- **Room as Single Source of Truth:** Visualizations never query Health Connect directly.
- **On-Demand Timeline Computation:** The 24-hour curve is generated in pure Kotlin on-demand by sampling the active exponential decay model over canonical workout records. No intermediate 15-minute samples are persisted in Room.
- **Phase 1 Shadow Isolation:** Visualizations display the computed fatigue values for user insight only; Readiness, Load Score, and recommendations remain untouched.

---

## 3. Dashboard Stats Card (`CardId.RESIDUAL_FATIGUE`)

### 3.1 Card Configuration & Defaults
- **Enum Entry:** `CardId.RESIDUAL_FATIGUE`
- **Default Visibility:** `isVisible = false` (hidden by default in `SettingsDefaults.DEFAULT_DASHBOARD_CARDS`).
- **Supported Display Modes:** `listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE)`
- **Default Display Mode:** `DashboardCardDisplayMode.GAUGE`

### 3.2 Visual & Status Presentation
- **Scale:** `UniversalMetricVisual.Score(rawValue = residualFatigue, minValue = 0f, maxValue = 100f)`
  - Values $> 100$ are displayed accurately in text and visually clamped to 100% fill on Gauge/Bar.
- **Status Classification:**
  - $\text{fatigue} < 30$: `MetricStatus.OPTIMAL` (Low fatigue)
  - $30 \le \text{fatigue} \le 70$: `MetricStatus.NEUTRAL` (Moderate fatigue)
  - $\text{fatigue} > 70$: `MetricStatus.WARNING` (High fatigue)
  - When `residualFatigue == null` or fatigue is disabled in preferences: `MetricStatus.NO_DATA` / `UniversalMetricUnavailableReason.MISSING_VALUE`.
- **Text & Copy:**
  - **Title:** `R.string.card_residual_fatigue_title` ("Residual Fatigue")
  - **Secondary Text:** `R.string.card_residual_fatigue_secondary` (e.g. `"Half-life: 24h"`)
  - **Tooltip:** `R.string.tooltip_residual_fatigue`
- **Interaction:** Tapping the card invokes `onNavigateToWorkouts()`.

---

## 4. Workouts Residual Fatigue Section & Multi-Range Curve Chart (`WorkoutChartId.RESIDUAL_FATIGUE_CURVE`)

### 4.1 Section Layout & Range Configuration
- **Placement:** Wrapped in `ResidualFatigueSection.kt` as an independent chart section inside `WorkoutsChartFactory`.
- **Header:** `SectionHeader` with `title = stringResource(R.string.chart_residual_fatigue_curve_title)` and info tooltip.
- **Range Selector:** M3 `SingleChoiceSegmentedButtonRow` offering `1D`, `3D`, and `7D` options (`FatigueCurveRange` enum), defaulting to `1D`.
- **Default Visibility:** `isVisible = false` (hidden by default in `SettingsDefaults.DEFAULT_WORKOUT_CHARTS`).

### 4.2 Mathematical Sampling Engine (`GenerateResidualFatigueCurveUseCase`)
- **Inputs:**
  - `startDate: LocalDate` (e.g. `selectedDate.minusDays(range.days - 1L)`)
  - `endDate: LocalDate` (e.g. `selectedDate`)
  - `scoringZoneId: ZoneId`
  - `config: ResidualFatigueConfig` (`halfLifeHours`, `gain`, `enabled`)
  - `retainedWorkouts: List<FatigueWorkoutInput>` (all canonical workouts through `endDate` dayEndMs)
- **Sampling Strategy:**
  - 96 quarter-hour grid points per day (every 15 minutes from 00:00 to 23:45 for each day in range).
  - Exact `endTimeMs` timestamps for every workout ending between `startDate` start-of-day and `endDate` end-of-day.
  - Merged and sorted chronologically: $t_0 < t_1 < \dots < t_N$ without duplicate timestamps.
- **Evaluation Formula:**
  $$F(t) = \sum_{i: \text{endTime}_i \le t} \text{gain} \times \text{trimp}_i \times 2^{-\frac{t - \text{endTime}_i}{\text{halfLifeHours} \times 3600 \times 1000}}$$
- **Output:** List of `FatigueCurvePoint(timestampMs: Long, timeMinutesFromStart: Float, fatigueValue: Float)`

### 4.3 Adaptive Vico Chart Presentation (`ResidualFatigueCurveChart.kt`)
- **Line Layer:** Cubic Bézier interpolation with smooth curve rendering.
- **Area Fill:** Gradient below the line from `MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)` to transparent.
- **Y-Axis:** Dynamic range starting at 0 and scaling to $\max(100f, \text{peakInRange})$.
- **Adaptive X-Axis:**
  - **1D (24h):** 4-hour interval time labels (`00:00`, `04:00`, `08:00`, `12:00`, `16:00`, `20:00`, `24:00`).
  - **3D (72h):** Day ticks / 12-hour markers (`Mon 00:00`, `Mon 12:00`, `Tue 00:00` or `Mon`, `Tue`, `Wed`).
  - **7D (168h):** Daily ticks displaying day of week (`Mon`, `Tue`, `Wed`, `Thu`, `Fri`, `Sat`, `Sun`).
- **Touch Scrubber Tooltip:**
  - **1D:** `"HH:mm • Fatigue: 38.4"`
  - **3D / 7D:** `"EEE, MMM d, HH:mm • Fatigue: 38.4"` (e.g. `"Fri, Aug 28, 14:30 • Fatigue: 38.4"`).
- **Container:** Native M3 `Card` with `MaterialTheme.shapes.large` (16dp) and `surfaceContainerLow` surface role.

---

## 5. Advanced Settings UI Polish

### 5.1 Header & Switch Layout
- In `AdvancedResidualFatigueSection.kt`:
  - **Header Row:** Add `MetricTooltip` alongside `advanced_residual_fatigue_title` containing the comprehensive explanation (`advanced_residual_fatigue_desc` / guardrails note).
  - **Switch ListItem:** Simplify the label to "Enabled" (`advanced_residual_fatigue_enabled_label`), removing the redundant long `supportingContent` block from the row so the toggle remains clean, compact, and aligned with standard settings rows.

---

## 6. Testing & Verification

1. **Unit Tests:**
   - `DashboardMetricPresentationFactoryTest`: Verify presentation, status classification thresholds (<30, 30-70, >70), secondary text, and missing value handling.
   - `Generate24hResidualFatigueCurveUseCaseTest`: Verify 15-minute grid generation, exact workout impulse insertion, decay accuracy at midnight boundaries, and empty history handling.
   - `DashboardCardCatalogTest` & `WorkoutChartIdExtensionsTest`: Verify catalog spec, supported modes, default visibility, and proto serialization.
   - `SettingsViewModelTest` / Compose verification: Verify clean header layout with info icon and simplified "Enabled" switch.
2. **Regression & Quality Gates:**
   - Pre-commit gates: `ktlintFormat`, `detekt`, `assembleDebug`, `testDebugUnitTest`, `lintRelease`.
