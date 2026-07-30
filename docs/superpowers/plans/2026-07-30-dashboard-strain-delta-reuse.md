# Dashboard Strain Delta Reuse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the Dashboard Strain card the same selected-day Strain increase that the Workouts tab shows.

**Architecture:** Keep the existing load math intact. Extract only the final selection of the daily increase into a pure core-scoring function, so the Workouts tab and Dashboard have one definition of the displayed delta. A small dashboard use case observes the selected-day inputs it needs and supplies the result to the existing presentation factory; the renderer continues to use the shared secondary-text pill.

**Tech Stack:** Kotlin, StateFlow, Compose Material 3, JUnit, MockK.

## Global Constraints

- Do not change scoring, normalization, baseline calculations, Health Connect processing, card dimensions, or supported visualization modes.
- Preserve the Workouts tab's seven-day tenure guard and its `0.005f` no-change display cutoff.
- In workout-only mode, sum the already-rounded per-workout gains supplied by `GetWorkoutDisplayMetricsUseCase`, exactly as the Workouts history does.
- In everyday-heart-rate mode, retain the existing current-day versus zero-current-day ATL/CTL strain-ratio calculation.
- Reuse the existing `delta_up_format`, `delta_up`, and `delta_no_change` resources. Do not add strings or a Strain-specific threshold.

---

### Task 1: Extract the pure daily-increase selection

**Files:**

- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/DailyStrainIncrease.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/DailyStrainIncreaseTest.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt:336-385`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt`

**Interfaces:**

- `calculateDailyStrainIncrease(dataTenureDays, loadSourceMode, workoutOnlyGains, strainRatioWithDay, strainRatioWithoutDay): Float?` returns `null` before seven days.
- Workout-only returns the sum of supplied, already-rounded gains.
- Everyday-heart-rate returns the non-negative difference when both supplied ratios exist; otherwise it returns `null`.

- [ ] **Step 1: Write failing core tests** for the tenure boundary, summed rounded workout gains, a positive everyday-heart-rate difference, and a negative difference clamped to zero.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*DailyStrainIncreaseTest'`

  Expected: compilation fails because `calculateDailyStrainIncrease` does not exist.

- [ ] **Step 3: Implement the pure helper** and replace the Workouts ViewModel's inline `todayStrainIncrease` branch with it. Keep the ViewModel's existing inputs, series construction, and selected-day filter unchanged.

- [ ] **Step 4: Run GREEN**

  Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*DailyStrainIncreaseTest' :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest'`

  Verify the existing workout-only and everyday-heart-rate ViewModel tests still pass unchanged.

---

### Task 2: Supply the shared input to Dashboard

**Files:**

- Create: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/ObserveDashboardStrainIncreaseUseCase.kt`
- Create: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/ObserveDashboardStrainIncreaseUseCaseTest.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt`

**Interfaces:**

- `ObserveDashboardStrainIncreaseUseCase.invoke(selectedDate, preferences): Flow<Float?>` observes the same source data used by Workouts without persisting a UI-only value.
- It uses `WorkoutRepository.getEarliestWorkoutTimestamp()` for the existing tenure definition, observes workouts and daily summaries from the same 48-day history window implied by the Workouts seven-day view plus 42-day chronic window, and uses the user's scoring zone.
- In workout-only mode it obtains the selected-day workouts' `gainedStrain` through `GetWorkoutDisplayMetricsUseCase`, so the summed rounded values match History rows.
- In everyday-heart-rate mode it builds the existing ATL/CTL series, derives the with-day and zero-day ratios, then delegates the final selection to `calculateDailyStrainIncrease`.
- `DashboardViewModel` combines this flow with its current basic/card/heart-rate inputs and passes the result downstream. It must not add a parallel persistence channel or change dashboard ordering/visibility.

- [ ] **Step 1: Write failing use-case tests** for the seven-day guard, workout-only sum from two selected-day display metrics, and everyday-heart-rate current-day versus zero-day calculation.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*ObserveDashboardStrainIncreaseUseCaseTest'`

  Expected: compilation fails because the observer does not exist.

- [ ] **Step 3: Implement the observer** using `WorkoutRepository`, `DailySummaryRepository`, `GetWorkoutDisplayMetricsUseCase`, `ScoringCalculator`, and the existing `LoadSourceSelector`/`ScoringConstants` APIs. Use `flatMapLatest` on the date/preferences pair, and keep data work off the main dispatcher.

- [ ] **Step 4: Integrate the observer with `DashboardViewModel`** and add a ViewModel test that verifies the result is forwarded to `GetDashboardDataUseCase`; do not invent a presentation field or duplicate factory work in the ViewModel.

- [ ] **Step 5: Run GREEN**

  Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*ObserveDashboardStrainIncreaseUseCaseTest' --tests '*DashboardViewModelTest'`

---

### Task 3: Format and render the Dashboard delta

**Files:**

- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/GetDashboardDataUseCase.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/GetDashboardDataUseCaseTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactoryTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`

**Interfaces:**

- `GetDashboardDataUseCase.invoke` and `DashboardMetricPresentationFactory.build` accept `todayStrainIncrease: Float?`.
- The Strain presentation assigns `secondaryText` as `↑ {MetricFormatter.formatStrain(increase)}` above `0.005f`, the existing no-change glyph at or below it, and `null` when unavailable.
- Existing `DashboardMetricDeltaPill` renders that secondary text for Gauge and Bar modes. Value mode retains its current secondary-text presentation. The Sleep-duration plain-text exception stays intact.

- [ ] **Step 1: Write failing presentation tests** for positive, no-change, and unavailable Strain deltas, plus a Bar regression asserting a positive Strain delta is outside the track in the shared pill.

- [ ] **Step 2: Run RED**

  Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardMetricPresentationFactoryTest' --tests '*DashboardVisualizationRegressionTest'`

  Expected: Strain secondary text is absent.

- [ ] **Step 3: Implement resource-backed formatting** through the existing resources and `MetricFormatter`; do not add a status label, marker, or Bar-specific treatment.

- [ ] **Step 4: Run GREEN**

  Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*GetDashboardDataUseCaseTest' --tests '*DashboardMetricPresentationFactoryTest' --tests '*DashboardVisualizationRegressionTest'`

---

### Task 4: Verify no visual or behavior drift

- [ ] **Step 1: Run focused cross-feature coverage**

  Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*DailyStrainIncreaseTest' :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest' :feature:dashboard:testDebugUnitTest`

- [ ] **Step 2: Run mandatory repository validation**

  Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease && ./gradlew assembleDebug`

- [ ] **Step 3: Review the final diff**

  Confirm the same displayed delta is sourced for the Dashboard and Workouts tabs, no scoring/formula source changed, and only existing shared Value/Gauge/Bar secondary rendering is used.

- [ ] **Step 4: Index new files**

  Run: `codegraph index`
