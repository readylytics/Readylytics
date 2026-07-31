# Dashboard Visualization Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore established Readylytics Value/Gauge/Bar hierarchy, formatting, pills, and status treatment without changing metric logic or supported modes.

**Architecture:** Keep `DashboardMetricCard` as the fixed M3 shell. Presentation factories format existing data; shared renderers apply metric-aware Value variants and common Gauge/Bar layout/color behavior. `DashboardCardCatalog` remains untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Compose UI tests, JUnit, MockK.

## Global Constraints

- Do not change calculations, normalization, baselines, Health Connect, ordering, visibility, card dimensions/grid positions, or supported modes.
- Daily Steps is unchanged.
- Use only existing Readylytics status colors and Material theme colors; no thresholds, mappings, or visible status labels.
- Preserve accessibility descriptions with value, unit, status, delta, and range.

---

### Task 1: Restore presentation formatting and status-container inputs

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactoryTest.kt`

**Interfaces:**
- `DashboardMetricPresentation.valueText` contains the visible formatted value, including `%` only for percentage metrics.
- Heart Rate uses `HeartRateDaySummary.minBpm`, `maxBpm`, and `avgBpm`: value `"$min–$max"`, unit/secondary `"bpm · avg $avg"`.

- [ ] Write failing tests for Heart Rate `45–147` plus `bpm · avg 84`, Circadian `95%`, and fractional/current Sleep Efficiency percent display.
- [ ] Run `./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardMetricPresentationFactoryTest'` and confirm RED.
- [ ] Implement formatting only in the factory; retain existing raw visuals, statuses, and accessibility semantics.
- [ ] Run the same test target and confirm GREEN.
- [ ] Commit: `fix(dashboard): restore metric display formatting`.

### Task 2: Restore shared card hierarchy, Value pills, and backgrounds

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`

**Interfaces:**
- Shared title row allows two lines in all modes, reserves the tooltip/menu target, and keeps the 48 dp interactive target.
- Value renderer accepts card identity/layout selection and uses `DashboardMetricDeltaPill` only for cards with existing delta text; Sleep Duration retains plain range text.
- Every mode resolves the same status-derived card container/content pair; active Gauge/Bar fill remains `status.gaugeColor()` and the track remains `metricVisualizationTrackColor()`.

- [ ] Write failing Compose tests for two-line Gauge title plus visible info action, Value delta-pill tag inside bounds, and matching Strain/RAS status container across modes.
- [ ] Run `./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest'` and confirm RED.
- [ ] Replace one-line/non-Value title restriction, remove mode-specific neutral container override, and add bounded Value layout variants for standard, range/duration, and delta content.
- [ ] Run the focused regression target and confirm GREEN.
- [ ] Commit: `fix(dashboard): restore card hierarchy and status treatment`.

### Task 3: Refine shared Gauge and Bar spacing/typography

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`

- [ ] Write failing tests asserting Bar value/unit are outside `DASHBOARD_BAR_TAG`, secondary content follows the track, and Gauge value/unit stay readable for HRV and long titles.
- [ ] Run the focused regression target and confirm RED.
- [ ] Use existing spacing tokens to replace the Bar weighted spacer with explicit title/value/bar/secondary spacing; use dominant theme Gauge typography and smaller associated unit typography. Do not alter bar height, card height, colors, or Daily Steps.
- [ ] Run the focused regression target and confirm GREEN.
- [ ] Commit: `fix(dashboard): balance gauge and bar metric hierarchy`.

### Task 4: Full verification

- [ ] Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease && ./gradlew assembleDebug`.
- [ ] Run `codegraph index` for modified/new files and inspect `git diff --check`.
- [ ] Manually verify light/dark and normal/large-font layouts for Heart Rate, HRV, Resting HR, Sleep duration, Circadian, Strain/RAS; confirm Daily Steps and supported mode catalog are unchanged.
