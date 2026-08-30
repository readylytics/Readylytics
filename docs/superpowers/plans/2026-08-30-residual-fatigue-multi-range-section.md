# Residual Fatigue Multi-Range Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encapsulate the Workouts Residual Fatigue chart in a dedicated `ResidualFatigueSection` component with a 1D / 3D / 7D range selector and adaptive X-axis/tooltip formatting powered by a generalized multi-day sampling domain engine.

**Architecture:** `GenerateResidualFatigueCurveUseCase` in `core:scoring` generalizes 24-hour evaluation to multi-day intervals (`startDate..endDate`), sampling 96 quarter-hour points per day plus exact workout impulse timestamps. `WorkoutsViewModel` manages the ephemeral `FatigueCurveRange` (`ONE_DAY` default, `THREE_DAYS`, `SEVEN_DAYS`). `ResidualFatigueSection` in `feature:workouts` presents a `SectionHeader`, M3 `SingleChoiceSegmentedButtonRow`, and `ResidualFatigueCurveChart` with dynamic X-axis labels and date-aware scrubber tooltips.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Vico (Cartesian Charts), Room DB, JUnit4, MockK, Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-29-residual-fatigue-ui-visualizations-design.md`

## Global Constraints

- Room remains the single source of truth; visualizations must NEVER query Health Connect directly.
- The multi-day timeline is calculated on-demand in memory; no intermediate 15-minute samples are persisted in Room.
- Phase 1 shadow isolation: visualizations present computed fatigue for user insight only; Readiness, Load Score, and recommendations remain untouched.
- UI & Charts: Always use native Material Design 3 (M3) components (`ListItem`, `Card`, `SegmentedButton`). Standard container shape `MaterialTheme.shapes.large` (16dp). Map surfaces to explicit M3 container roles (`surfaceContainerLow`, `surfaceContainer`). Vico charts require Cubic Bézier curves, bottom area gradient fills, and M3 tonal palette mapping (no hardcoded colors).
- Strings & i18n: All user-facing strings must be defined in `res/values/strings.xml` and referenced via `stringResource(R.string.*)`. No hardcoded strings in code.
- File Structure: Target ≤ 400 lines/file, hard limit ≤ 800 lines (refactor/extract helpers if exceeded).
- Detekt Discipline: New implementations must never add new detekt issues or suppressions.
- Any presentation data-flow change updates `internal-docs/DATA_FLOW.md` synchronously.
- Pre-Commit Verification: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease`.
- Run `codegraph index` after creating new files and `codegraph sync` after structural refactors.

---

### Task 1: Add `FatigueCurveRange` Model and Generalize `GenerateResidualFatigueCurveUseCase` for Multi-Day Sampling

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurveRange.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurvePoint.kt`
- Rename & Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCase.kt` $\rightarrow$ `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GenerateResidualFatigueCurveUseCase.kt`
- Rename & Modify: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCaseTest.kt` $\rightarrow$ `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/GenerateResidualFatigueCurveUseCaseTest.kt`

**Interfaces:**
- Consumes: `FatigueWorkoutInput(workoutId, endTimeMs, trimp)`, `ResidualFatigueConfig(halfLifeHours, gain, enabled)`.
- Produces:
  - `enum class FatigueCurveRange(val days: Int, val label: String) { ONE_DAY(1, "1D"), THREE_DAYS(3, "3D"), SEVEN_DAYS(7, "7D") }`
  - `data class FatigueCurvePoint(val timestampMs: Long, val timeMinutesFromStart: Float, val fatigueValue: Float)`
  - `GenerateResidualFatigueCurveUseCase.execute(startDate: LocalDate, endDate: LocalDate, zoneId: ZoneId, config: ResidualFatigueConfig, retainedWorkouts: List<FatigueWorkoutInput>): List<FatigueCurvePoint>`
  - `GenerateResidualFatigueCurveUseCase.evaluateAt(timestampMs: Long, config: ResidualFatigueConfig, retainedWorkouts: List<FatigueWorkoutInput>): Float`

- [ ] **Step 1: Write failing unit tests for multi-day range curve generation**

In `GenerateResidualFatigueCurveUseCaseTest.kt`:
```kotlin
@Test
fun `execute samples 96 points per day across 3-day range`() {
    val startDate = LocalDate.of(2026, 8, 27)
    val endDate = LocalDate.of(2026, 8, 29)
    val zone = ZoneId.of("UTC")
    val config = ResidualFatigueConfig(halfLifeHours = 24f, gain = 1f, enabled = true)
    val workouts = emptyList<FatigueWorkoutInput>()

    val curve = useCase.execute(startDate, endDate, zone, config, workouts)
    // 3 days * 96 points/day = 288 points
    assertEquals(288, curve.size)
    assertEquals(0f, curve.first().timeMinutesFromStart, 0.01f)
    assertEquals((3 * 24 * 60 - 15).toFloat(), curve.last().timeMinutesFromStart, 0.01f)
}

@Test
fun `execute samples 672 points across 7-day range with workout spikes across multiple days`() {
    val startDate = LocalDate.of(2026, 8, 23)
    val endDate = LocalDate.of(2026, 8, 29)
    val zone = ZoneId.of("UTC")
    val config = ResidualFatigueConfig(halfLifeHours = 24f, gain = 1f, enabled = true)
    val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val workout1 = FatigueWorkoutInput("w1", startMs + 36 * 3600 * 1000L, 50f) // Day 2 noon
    val workout2 = FatigueWorkoutInput("w2", startMs + 100 * 3600 * 1000L, 40f) // Day 5 4am

    val curve = useCase.execute(startDate, endDate, zone, config, listOf(workout1, workout2))
    // 7 * 96 = 672 grid points + 2 exact timestamps = 674 points
    assertEquals(674, curve.size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*GenerateResidualFatigueCurveUseCaseTest*'`
Expected: FAIL with compilation/reference errors.

- [ ] **Step 3: Implement `FatigueCurveRange` and `GenerateResidualFatigueCurveUseCase`**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurveRange.kt`:
```kotlin
package app.readylytics.health.core.model.domain.workouts

enum class FatigueCurveRange(val days: Int, val label: String) {
    ONE_DAY(1, "1D"),
    THREE_DAYS(3, "3D"),
    SEVEN_DAYS(7, "7D");
}
```

In `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GenerateResidualFatigueCurveUseCase.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeSet
import javax.inject.Inject
import kotlin.math.pow

class GenerateResidualFatigueCurveUseCase @Inject constructor() {
    companion object {
        const val SAMPLES_PER_DAY = 96
        const val STEP_MINUTES = 15
        const val MILLIS_PER_MINUTE = 60 * 1000L
        const val MILLIS_PER_HOUR = 3600 * 1000.0
    }

    fun execute(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
        config: ResidualFatigueConfig,
        retainedWorkouts: List<FatigueWorkoutInput>,
    ): List<FatigueCurvePoint> {
        val startZdt = startDate.atStartOfDay(zoneId)
        val rangeStartMs = startZdt.toInstant().toEpochMilli()
        val rangeEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate.plusDays(1)).toInt()

        val sampleTimes = TreeSet<Long>()
        val totalGridPoints = totalDays * SAMPLES_PER_DAY
        for (i in 0 until totalGridPoints) {
            sampleTimes.add(rangeStartMs + i * STEP_MINUTES * MILLIS_PER_MINUTE)
        }
        for (w in retainedWorkouts) {
            if (w.endTimeMs in rangeStartMs until rangeEndMs) {
                sampleTimes.add(w.endTimeMs)
            }
        }

        if (!config.enabled || config.halfLifeHours <= 0f) {
            return sampleTimes.map { t ->
                val minutesFromStart = ((t - rangeStartMs) / MILLIS_PER_MINUTE).toFloat()
                FatigueCurvePoint(timestampMs = t, timeMinutesFromStart = minutesFromStart, fatigueValue = 0f)
            }
        }

        val sortedWorkouts = retainedWorkouts.filter { it.trimp > 0f }.sortedWith(
            compareBy<FatigueWorkoutInput> { it.endTimeMs }.thenBy { it.workoutId }
        )

        val halfLifeMs = config.halfLifeHours * MILLIS_PER_HOUR
        val gain = config.gain

        return sampleTimes.map { t ->
            var sum = 0.0
            for (w in sortedWorkouts) {
                if (w.endTimeMs <= t) {
                    val deltaMs = t - w.endTimeMs
                    sum += gain * w.trimp * 2.0.pow(-deltaMs / halfLifeMs)
                } else {
                    break
                }
            }
            val minutesFromStart = ((t - rangeStartMs) / MILLIS_PER_MINUTE).toFloat()
            FatigueCurvePoint(
                timestampMs = t,
                timeMinutesFromStart = minutesFromStart,
                fatigueValue = sum.toFloat(),
            )
        }
    }

    fun evaluateAt(
        timestampMs: Long,
        config: ResidualFatigueConfig,
        retainedWorkouts: List<FatigueWorkoutInput>,
    ): Float {
        if (!config.enabled || config.halfLifeHours <= 0f) return 0f
        val halfLifeMs = config.halfLifeHours * MILLIS_PER_HOUR
        val gain = config.gain
        var sum = 0.0
        for (w in retainedWorkouts) {
            if (w.endTimeMs <= timestampMs && w.trimp > 0f) {
                val deltaMs = timestampMs - w.endTimeMs
                sum += gain * w.trimp * 2.0.pow(-deltaMs / halfLifeMs)
            }
        }
        return sum.toFloat()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*GenerateResidualFatigueCurveUseCaseTest*'`
Expected: PASS.

- [ ] **Step 5: Index and commit**

```bash
codegraph index
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurveRange.kt core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurvePoint.kt core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GenerateResidualFatigueCurveUseCase.kt core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/GenerateResidualFatigueCurveUseCaseTest.kt
git commit -m "feat: generalize GenerateResidualFatigueCurveUseCase for multi-day ranges"
```

---

### Task 2: Implement `ResidualFatigueSection.kt` and Adaptive Chart Formatting in `feature/workouts`

**Files:**
- Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueSection.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChart.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsStateFactory.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsUseCases.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsChartFactory.kt`
- Modify: `feature/workouts/src/main/res/values/strings.xml`
- Create: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueSectionTest.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChartTest.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt`

**Interfaces:**
- Consumes: `GenerateResidualFatigueCurveUseCase`, `FatigueCurveRange`, `WorkoutsUiState.selectedFatigueRange`, `WorkoutsUiState.residualFatigueCurve`.
- Produces:
  - `ResidualFatigueSection` composable with `SectionHeader`, `SingleChoiceSegmentedButtonRow(1D, 3D, 7D)`, and `ResidualFatigueCurveChart`.
  - Adaptive X-axis and tooltip formatting in `ResidualFatigueCurveChart`.
  - `onFatigueRangeSelected(range: FatigueCurveRange)` in `WorkoutsViewModel`.

- [ ] **Step 1: Write failing ViewModel and Section unit tests**

```kotlin
@Test
fun `workoutsViewModel updates fatigue curve when fatigue range changes to 3D or 7D`() = runTest {
    viewModel.onDateSelected(LocalDate.of(2026, 8, 29))
    assertEquals(FatigueCurveRange.ONE_DAY, viewModel.uiState.value.selectedFatigueRange)
    
    viewModel.onFatigueRangeSelected(FatigueCurveRange.THREE_DAYS)
    assertEquals(FatigueCurveRange.THREE_DAYS, viewModel.uiState.value.selectedFatigueRange)
    assertEquals(288, viewModel.uiState.value.residualFatigueCurve.size)
    
    viewModel.onFatigueRangeSelected(FatigueCurveRange.SEVEN_DAYS)
    assertEquals(FatigueCurveRange.SEVEN_DAYS, viewModel.uiState.value.selectedFatigueRange)
    assertEquals(672, viewModel.uiState.value.residualFatigueCurve.size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest*'`
Expected: FAIL with `Unresolved reference: selectedFatigueRange` / `onFatigueRangeSelected`.

- [ ] **Step 3: Implement `ResidualFatigueSection.kt` and adapt `ResidualFatigueCurveChart.kt`**

1. In `WorkoutsStateFactory.kt`:
   Add `val selectedFatigueRange: FatigueCurveRange = FatigueCurveRange.ONE_DAY` to `WorkoutsUiState`.
2. In `WorkoutsViewModel.kt`:
   - Manage `_selectedFatigueRange = MutableStateFlow(FatigueCurveRange.ONE_DAY)`
   - Add `fun onFatigueRangeSelected(range: FatigueCurveRange) { _selectedFatigueRange.value = range }`
   - Combine `selectedDate`, `_selectedFatigueRange`, `userPreferences`, and `WorkoutRepository.getCanonicalFatigueSeed` to compute `residualFatigueCurve` across `selectedDate.minusDays(range.days - 1L)..selectedDate`.
3. In `ResidualFatigueSection.kt`:
   ```kotlin
   @Composable
   fun ResidualFatigueSection(
       uiState: WorkoutsUiState,
       onRangeSelected: (FatigueCurveRange) -> Unit,
       parentScrollInProgress: () -> Boolean,
       modifier: Modifier = Modifier,
   ) {
       Column(modifier = modifier) {
           Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
           SectionHeader(
               title = stringResource(R.string.chart_residual_fatigue_curve_title),
               tooltip = stringResource(R.string.chart_residual_fatigue_curve_description),
               enabled = !uiState.isLoading,
           )
           Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
           SingleChoiceSegmentedButtonRow(
               modifier = Modifier
                   .fillMaxWidth()
                   .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
           ) {
               FatigueCurveRange.entries.forEachIndexed { index, range ->
                   SegmentedButton(
                       selected = uiState.selectedFatigueRange == range,
                       onClick = { onRangeSelected(range) },
                       enabled = !uiState.isLoading,
                       shape = SegmentedButtonDefaults.itemShape(index = index, count = FatigueCurveRange.entries.size),
                       label = { Text(range.label) },
                   )
               }
           }
           Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
           ResidualFatigueCurveChart(
               points = uiState.residualFatigueCurve,
               range = uiState.selectedFatigueRange,
               isLoading = uiState.isLoading,
               parentScrollInProgress = parentScrollInProgress,
               modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
           )
       }
   }
   ```
4. In `ResidualFatigueCurveChart.kt`:
   - Update X-axis label item placer and value formatter:
     - When `range == FatigueCurveRange.ONE_DAY`: 4-hour tick placer (`00:00`, `04:00`, `08:00`, `12:00`, `16:00`, `20:00`, `24:00`).
     - When `range == FatigueCurveRange.THREE_DAYS`: 24-hour tick placer displaying day abbreviated name (`Mon`, `Tue`, `Wed`).
     - When `range == FatigueCurveRange.SEVEN_DAYS`: 24-hour tick placer displaying day-of-week (`Mon`, `Tue`, `Wed`, `Thu`, `Fri`, `Sat`, `Sun`).
   - Update touch scrubber tooltip:
     - 1D: `"HH:mm • Fatigue: %.1f"`
     - 3D/7D: `"EEE, MMM d, HH:mm • Fatigue: %.1f"`
5. In `WorkoutsChartFactory.kt`:
   Map `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` to `ResidualFatigueSection(uiState = uiState, onRangeSelected = onFatigueRangeSelected, parentScrollInProgress = parentScrollInProgress)`.

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `./gradlew :feature:workouts:testDebugUnitTest :feature:workouts:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Index and commit**

```bash
codegraph index
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueSection.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChart.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsStateFactory.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsUseCases.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsChartFactory.kt feature/workouts/src/main/res/values/strings.xml feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueSectionTest.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChartTest.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt
git commit -m "feat: implement ResidualFatigueSection with 1D/3D/7D range selector and adaptive chart"
```

---

### Task 3: Synchronize DATA_FLOW.md, Strings & Execute Quality Verification Gates

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `ABOUT.md`
- Modify: `docs/about.md`
- Modify: `docs/customization.md`

**Interfaces:**
- Consumes: Completed `ResidualFatigueSection`, `FatigueCurveRange`, and `GenerateResidualFatigueCurveUseCase` from Tasks 1–2.
- Produces: Synchronized data flow documentation, passing documentation drift tests, and passing pre-commit quality gates.

- [ ] **Step 1: Update DATA_FLOW.md & Jekyll docs**

Document:
1. `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` presentation via `ResidualFatigueSection` with `1D / 3D / 7D` range selection (`FatigueCurveRange`).
2. `GenerateResidualFatigueCurveUseCase` multi-day sampling pipeline across `startDate..endDate`.

- [ ] **Step 2: Run documentation drift tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*DocumentationDriftTest*'`
Expected: PASS.

- [ ] **Step 3: Run full mandatory verification gates**

Run:
```bash
./gradlew ktlintFormat
./gradlew detekt
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintRelease
```
Expected: All gates PASS with 0 violations and 0 suppressions.

- [ ] **Step 4: Synchronize codegraph**

Run:
```bash
codegraph sync
```
Expected: Synchronization completed.

- [ ] **Step 5: Commit documentation updates**

```bash
git add internal-docs/DATA_FLOW.md ABOUT.md docs/about.md docs/customization.md
git commit -m "docs: document ResidualFatigueSection and 1D/3D/7D range selector"
```

---

## Completion Criteria

- `FatigueCurveRange` (`ONE_DAY`, `THREE_DAYS`, `SEVEN_DAYS`) is defined and selectable via native M3 `SegmentedButton` in `ResidualFatigueSection`.
- `GenerateResidualFatigueCurveUseCase` accurately evaluates continuous exponential decay across single-day (1D) and multi-day (3D, 7D) windows with 96 points/day plus exact workout impulses.
- `ResidualFatigueCurveChart` adapts X-axis tick placer and touch scrubber formatting according to the active range (`HH:mm` for 1D, date + time for 3D/7D).
- Phase 1 shadow isolation is preserved (Readiness, Load Score, recommendations are 100% unaltered).
- All strings are localized in `strings.xml`.
- Documentation in `internal-docs/DATA_FLOW.md`, `ABOUT.md`, and Jekyll docs is synchronized.
- Mandatory formatting, analysis, build, unit-test, release-lint, and codegraph gates pass cleanly.
