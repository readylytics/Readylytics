# Residual Fatigue UI & Visualizations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a customizable, default-hidden Residual Fatigue stats card to the Dashboard and a customizable, default-hidden 24-hour Residual Fatigue exponential decay curve chart to the Workouts dashboard.

**Architecture:** The Dashboard card consumes `DailySummary.residualFatigue` from Room DB via `DashboardMetricPresentationFactory` (0..100 scale, Gauge/Bar/Value, color-classified). The Workouts 24-hour curve is generated on-demand by `Generate24hResidualFatigueCurveUseCase` sampling 96 quarter-hour grid points plus exact workout completion timestamps across the selected day, rendered using a Vico Cartesian Line Chart with Cubic Bézier curves and interactive touch tooltips.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Vico (Cartesian Charts), Room DB, Proto DataStore, JUnit4, MockK, Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-29-residual-fatigue-ui-visualizations-design.md`

## Global Constraints

- Room remains the single source of truth; visualizations must NEVER query Health Connect directly.
- The 24-hour timeline is calculated on-demand in memory; no intermediate 15-minute samples are persisted in Room.
- Phase 1 shadow isolation: visualizations present computed fatigue for user insight only; Readiness, Load Score, and recommendations remain untouched.
- UI & Charts: Always use native Material Design 3 (M3) components (`ListItem`, `Card`, `Button`). Standard container shape `MaterialTheme.shapes.large` (16dp). Map surfaces to explicit M3 container roles (`surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`). Vico charts require Cubic Bézier curves, bottom area gradient fills, and M3 tonal palette mapping (no hardcoded colors).
- Strings & i18n: All user-facing strings must be defined in `res/values/strings.xml` and referenced via `stringResource(R.string.*)`. No hardcoded strings in code.
- File Structure: Target ≤ 400 lines/file, hard limit ≤ 800 lines (refactor if exceeded).
- Detekt Discipline: New implementations must never add new detekt issues or suppressions.
- Any scoring-pipeline or presentation data-flow change updates `internal-docs/DATA_FLOW.md` synchronously.
- Pre-Commit Verification: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease`.
- Run `codegraph index` after creating new files and `codegraph sync` after structural refactors.

---

### Task 1: Add `CardId.RESIDUAL_FATIGUE` to Core Domain, Catalog & Defaults

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardId.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardIdExtensions.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/DashboardCardCatalog.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt`
- Modify: `feature/dashboard/src/main/res/values/strings.xml`
- Modify: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/dashboard/DashboardCardCatalogTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/preferences/CardConfigurationRepositoryTest.kt`

**Interfaces:**
- Consumes: `CardId`, `CardConfiguration`, `ModeSpec`, `DashboardCardDisplayMode`.
- Produces: `CardId.RESIDUAL_FATIGUE` entry in `CardId`, registered in `DashboardCardCatalog.specs` with all modes supported (`GAUGE`, `BAR`, `VALUE`), default mode `GAUGE`, display name mappings, and appended to `SettingsDefaults.DEFAULT_DASHBOARD_CARDS` with `isVisible = false`.

- [ ] **Step 1: Write failing domain and repository tests**

```kotlin
@Test
fun `DashboardCardCatalog registers RESIDUAL_FATIGUE spec with all display modes`() {
    val spec = DashboardCardCatalog.spec(CardId.RESIDUAL_FATIGUE)
    assertNotNull(spec)
    assertEquals(DashboardCardDisplayMode.GAUGE, spec?.defaultMode)
    assertEquals(
        listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
        spec?.supportedModes,
    )
}

@Test
fun `SettingsDefaults contains RESIDUAL_FATIGUE card hidden by default`() {
    val config = SettingsDefaults.DEFAULT_DASHBOARD_CARDS.firstOrNull { it.cardId == CardId.RESIDUAL_FATIGUE }
    assertNotNull(config)
    assertFalse(requireNotNull(config).isVisible)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*DashboardCardCatalogTest*' :app:testDebugUnitTest --tests '*CardConfigurationRepositoryTest*'`
Expected: FAIL with `Unresolved reference: RESIDUAL_FATIGUE`.

- [ ] **Step 3: Implement domain card ID and defaults**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardId.kt`:
```kotlin
enum class CardId {
    SLEEP_SCORE,
    READINESS,
    STEPS,
    HRV,
    SLEEP_RHR,
    SLEEP_DURATION,
    SLEEP_ARCHITECTURE,
    STRAIN_RATIO,
    RAS_DAILY,
    CIRCADIAN_CONSISTENCY,
    RESTING_HR,
    RECOVERY_INDEX,
    ACUTE_CHRONIC_RATIO,
    SLEEP_EFFICIENCY,
    HEART_RATE,
    WEIGHT,
    BODY_FAT,
    BLOOD_PRESSURE,
    OXYGEN_SATURATION,
    AI_RECOMMENDATION,
    BODY_TEMPERATURE,
    INSIGHTS,
    RESIDUAL_FATIGUE,
}
```

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardIdExtensions.kt`:
```kotlin
CardId.RESIDUAL_FATIGUE -> "Residual Fatigue"
```

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/DashboardCardCatalog.kt`:
```kotlin
CardId.RESIDUAL_FATIGUE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
```

In `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`:
```kotlin
CardConfiguration(CardId.RESIDUAL_FATIGUE, isVisible = false, position = 19),
```

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt`:
```kotlin
CardId.RESIDUAL_FATIGUE -> R.string.card_residual_fatigue_title
```

In `feature/dashboard/src/main/res/values/strings.xml`:
```xml
<string name="card_residual_fatigue_title">Residual Fatigue</string>
<string name="card_residual_fatigue_secondary">Half-life: %1$dh</string>
<string name="tooltip_residual_fatigue">Residual training fatigue accumulated from workouts, decaying exponentially based on your configured half-life.</string>
<string name="semantics_card_residual_fatigue">Residual Fatigue %1$s, status %2$s</string>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*DashboardCardCatalogTest*' :app:testDebugUnitTest --tests '*CardConfigurationRepositoryTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardId.kt core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/CardIdExtensions.kt core/model/src/main/kotlin/app/readylytics/health/core/model/domain/dashboard/DashboardCardCatalog.kt core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt feature/dashboard/src/main/res/values/strings.xml core/model/src/test/kotlin/app/readylytics/health/core/model/domain/dashboard/DashboardCardCatalogTest.kt app/src/test/kotlin/app/readylytics/health/data/preferences/CardConfigurationRepositoryTest.kt
git commit -m "feat: add CardId.RESIDUAL_FATIGUE domain definition and defaults"
```

---

### Task 2: Implement Dashboard Metric Presentation & Card Mapping

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactoryTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationLayoutTest.kt`

**Interfaces:**
- Consumes: `DailySummary.residualFatigue`, `UserPreferences.residualFatigueEnabled`, `UserPreferences.residualFatigueHalfLifeHours`, `UniversalMetricPresentation`.
- Produces: `DashboardMetricPresentationFactory.build()` maps `CardId.RESIDUAL_FATIGUE` into `UniversalMetricPresentation` with 0..100 Score visual, color-classified status (<30 Optimal, 30..70 Neutral, >70 Warning), and wire `DashboardCardFactory` to `UniversalMetricCard` with `onClick = onNavigateToWorkouts`.

- [ ] **Step 1: Write failing presentation factory tests**

```kotlin
@Test
fun `build presents residual fatigue with score visual and optimal status when below 30`() {
    val summary = dailySummary(residualFatigue = 18.5f)
    val preferences = userPreferences(residualFatigueEnabled = true, residualFatigueHalfLifeHours = 24f)
    val map = factory.build(summary = summary, preferences = preferences, lastSleepSession = null, circadianResult = null, heartRateSummary = null)
    
    val presentation = map[CardId.RESIDUAL_FATIGUE]
    assertNotNull(presentation)
    assertEquals("18.5", presentation?.valueText)
    assertEquals(MetricStatus.OPTIMAL, presentation?.status)
    assertEquals("Half-life: 24h", presentation?.secondaryText)
    assertTrue(presentation?.visual is UniversalMetricVisual.Score)
    val score = presentation?.visual as UniversalMetricVisual.Score
    assertEquals(18.5f, score.rawValue)
    assertEquals(0f, score.minValue)
    assertEquals(100f, score.maxValue)
}

@Test
fun `build presents warning status when residual fatigue is above 70`() {
    val summary = dailySummary(residualFatigue = 85.0f)
    val preferences = userPreferences(residualFatigueEnabled = true, residualFatigueHalfLifeHours = 48f)
    val map = factory.build(summary = summary, preferences = preferences, lastSleepSession = null, circadianResult = null, heartRateSummary = null)
    
    val presentation = map[CardId.RESIDUAL_FATIGUE]
    assertNotNull(presentation)
    assertEquals(MetricStatus.WARNING, presentation?.status)
}

@Test
fun `build presents missing value when residual fatigue is disabled or null`() {
    val summary = dailySummary(residualFatigue = 50.0f)
    val preferences = userPreferences(residualFatigueEnabled = false)
    val map = factory.build(summary = summary, preferences = preferences, lastSleepSession = null, circadianResult = null, heartRateSummary = null)
    
    val presentation = map[CardId.RESIDUAL_FATIGUE]
    assertNotNull(presentation)
    assertEquals(MetricStatus.NO_DATA, presentation?.status)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardMetricPresentationFactoryTest*'`
Expected: FAIL with `AssertionError: Expected non-null value for CardId.RESIDUAL_FATIGUE`.

- [ ] **Step 3: Implement Presentation & Card Factory mapping**

In `DashboardMetricPresentationFactory.kt`:
```kotlin
private fun buildResidualFatiguePresentation(
    summary: DailySummary?,
    preferences: UserPreferences,
    unavailableValueText: String,
): UniversalMetricPresentation {
    val title = resourceProvider.getString(DashboardR.string.card_residual_fatigue_title)
    val tooltip = resourceProvider.getString(DashboardR.string.tooltip_residual_fatigue)
    val value = summary?.residualFatigue?.takeIf { preferences.residualFatigueEnabled }

    val status = when {
        value == null -> MetricStatus.NO_DATA
        value < 30f -> MetricStatus.OPTIMAL
        value <= 70f -> MetricStatus.NEUTRAL
        else -> MetricStatus.WARNING
    }

    val visual = UniversalMetricVisual.Score(
        rawValue = value,
        minValue = 0f,
        maxValue = 100f,
        markerFraction = value?.let { (it / 100f).coerceIn(0f, 1f) },
        unavailableReason = if (value == null) UniversalMetricUnavailableReason.MISSING_VALUE else null,
    )

    val valueText = value?.let { MetricFormatter.formatDecimal(it, 1) } ?: unavailableValueText
    val secondaryText = resourceProvider.getString(
        DashboardR.string.card_residual_fatigue_secondary,
        preferences.residualFatigueHalfLifeHours.roundToInt(),
    )
    val statusText = classificationText(status)
    val accessibility = if (value != null) {
        resourceProvider.getString(DashboardR.string.semantics_card_residual_fatigue, valueText, statusText)
    } else {
        unavailableDescription(title, UniversalMetricUnavailableReason.MISSING_VALUE)
    }

    return UniversalMetricPresentation(
        title = title,
        valueText = valueText,
        unitText = "",
        secondaryText = secondaryText,
        status = status,
        tooltip = tooltip,
        accessibilityDescription = accessibility,
        visual = visual,
    )
}
```

In `DashboardCardFactory.kt`:
```kotlin
CardId.RESIDUAL_FATIGUE -> { config: CardConfiguration ->
    val presentation = uiState.cardDataMap[CardId.RESIDUAL_FATIGUE]
    if (presentation != null) {
        UniversalMetricCard(
            presentation = presentation,
            specification = requireNotNull(DashboardCardCatalog.spec(CardId.RESIDUAL_FATIGUE)),
            requestedMode = DashboardCardCatalog.requestedMode(config),
            isEditing = isEditing,
            onModeSelected = { onCardDisplayModeChanged(CardId.RESIDUAL_FATIGUE, it.toDashboardDisplayMode()) },
            onClick = onNavigateToWorkouts,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardMetricPresentationFactoryTest*' :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationLayoutTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactoryTest.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationLayoutTest.kt
git commit -m "feat: implement Dashboard Residual Fatigue metric card presentation and navigation"
```

---

### Task 3: Implement 24-Hour Residual Fatigue Timeline Domain Engine

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCase.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurvePoint.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCaseTest.kt`

**Interfaces:**
- Consumes: `FatigueWorkoutInput(workoutId, endTimeMs, trimp)`, `ResidualFatigueConfig(halfLifeHours, gain)`, `ComputeResidualFatigueUseCase`.
- Produces: `Generate24hResidualFatigueCurveUseCase.execute(selectedDate, zoneId, config, retainedWorkouts): List<FatigueCurvePoint>`.

- [ ] **Step 1: Write failing unit tests for 24h curve sampling**

```kotlin
@Test
fun `execute samples 96 quarter-hour grid points across full 24h day`() {
    val date = LocalDate.of(2026, 8, 29)
    val zone = ZoneId.of("UTC")
    val config = ResidualFatigueConfig(halfLifeHours = 24f, gain = 1f)
    val workouts = emptyList<FatigueWorkoutInput>()

    val curve = useCase.execute(date, zone, config, workouts)
    assertEquals(96, curve.size)
    assertEquals(0f, curve.first().timeMinutesOfDay, 0.01f)
    assertEquals(23 * 60 + 45f, curve.last().timeMinutesOfDay, 0.01f)
    curve.forEach { assertEquals(0f, it.fatigueValue, 0.001f) }
}

@Test
fun `execute inserts exact workout end timestamps and captures spike and decay`() {
    val date = LocalDate.of(2026, 8, 29)
    val zone = ZoneId.of("UTC")
    val config = ResidualFatigueConfig(halfLifeHours = 24f, gain = 1f)
    val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val workoutEndMs = dayStartMs + 10 * 3600 * 1000L + 7 * 60 * 1000L // 10:07 AM
    val workouts = listOf(FatigueWorkoutInput("w1", workoutEndMs, 50f))

    val curve = useCase.execute(date, zone, config, workouts)
    // 96 grid points + 1 exact timestamp = 97 points
    assertEquals(97, curve.size)
    val pointAtWorkout = curve.first { it.timestampMs == workoutEndMs }
    assertEquals(607f, pointAtWorkout.timeMinutesOfDay, 0.01f)
    assertEquals(50f, pointAtWorkout.fatigueValue, 0.01f)

    // 24 hours later (next day midnight evaluation), value is decayed by 50%
    val nextDayMidnightMs = dayStartMs + 24 * 3600 * 1000L
    val decayed = useCase.evaluateAt(nextDayMidnightMs, config, workouts)
    assertEquals(25f, decayed, 0.01f)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*Generate24hResidualFatigueCurveUseCaseTest*'`
Expected: FAIL with `Unresolved reference: Generate24hResidualFatigueCurveUseCase`.

- [ ] **Step 3: Implement `FatigueCurvePoint` and `Generate24hResidualFatigueCurveUseCase`**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurvePoint.kt`:
```kotlin
package app.readylytics.health.core.model.domain.workouts

data class FatigueCurvePoint(
    val timestampMs: Long,
    val timeMinutesOfDay: Float,
    val fatigueValue: Float,
)
```

In `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCase.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.pow

class Generate24hResidualFatigueCurveUseCase @Inject constructor() {
    companion object {
        const val SAMPLES_PER_DAY = 96
        const val STEP_MINUTES = 15
        const val MILLIS_PER_MINUTE = 60 * 1000L
        const val MILLIS_PER_HOUR = 3600 * 1000.0
    }

    fun execute(
        selectedDate: LocalDate,
        zoneId: ZoneId,
        config: ResidualFatigueConfig,
        retainedWorkouts: List<FatigueWorkoutInput>,
    ): List<FatigueCurvePoint> {
        val startZdt = selectedDate.atStartOfDay(zoneId)
        val dayStartMs = startZdt.toInstant().toEpochMilli()
        val dayEndMs = startZdt.plusDays(1).toInstant().toEpochMilli()

        val sampleTimes = TreeSet<Long>()
        for (i in 0 until SAMPLES_PER_DAY) {
            sampleTimes.add(dayStartMs + i * STEP_MINUTES * MILLIS_PER_MINUTE)
        }
        for (w in retainedWorkouts) {
            if (w.endTimeMs in dayStartMs until dayEndMs) {
                sampleTimes.add(w.endTimeMs)
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
            val minutesOfDay = ((t - dayStartMs) / MILLIS_PER_MINUTE).toFloat()
            FatigueCurvePoint(
                timestampMs = t,
                timeMinutesOfDay = minutesOfDay,
                fatigueValue = sum.toFloat(),
            )
        }
    }

    fun evaluateAt(
        timestampMs: Long,
        config: ResidualFatigueConfig,
        retainedWorkouts: List<FatigueWorkoutInput>,
    ): Float {
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

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*Generate24hResidualFatigueCurveUseCaseTest*'`
Expected: PASS.

- [ ] **Step 5: Index new files and commit**

```bash
codegraph index
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/FatigueCurvePoint.kt core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCase.kt core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/Generate24hResidualFatigueCurveUseCaseTest.kt
git commit -m "feat: implement Generate24hResidualFatigueCurveUseCase"
```

---

### Task 4: Add `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` to Domain, Defaults & Management

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/WorkoutChartId.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensions.kt`
- Modify: `feature/workouts/src/main/res/values/strings.xml`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensionsTest.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelLayoutManagementTest.kt`

**Interfaces:**
- Consumes: `WorkoutChartId`, `WorkoutChartConfiguration`, `SettingsDefaults.DEFAULT_WORKOUT_CHARTS`.
- Produces: `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` registered in defaults (`isVisible = false`), display name string resource, and layout management test coverage.

- [ ] **Step 1: Write failing chart id extension and layout management tests**

```kotlin
@Test
fun `RESIDUAL_FATIGUE_CURVE has valid display name and is hidden by default`() {
    assertEquals(R.string.chart_residual_fatigue_curve_title, WorkoutChartId.RESIDUAL_FATIGUE_CURVE.displayNameResId)
    val defaultChart = SettingsDefaults.DEFAULT_WORKOUT_CHARTS.firstOrNull { it.chartId == WorkoutChartId.RESIDUAL_FATIGUE_CURVE }
    assertNotNull(defaultChart)
    assertFalse(requireNotNull(defaultChart).isVisible)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutChartIdExtensionsTest*'`
Expected: FAIL with `Unresolved reference: RESIDUAL_FATIGUE_CURVE`.

- [ ] **Step 3: Implement `WorkoutChartId.RESIDUAL_FATIGUE_CURVE`**

In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/WorkoutChartId.kt`:
```kotlin
enum class WorkoutChartId {
    ACWR_TRIMP,
    WEEKLY_TRAINING,
    ACTIVITY_VOLUME,
    TRAINING_MIX,
    RESIDUAL_FATIGUE_CURVE,
}
```

In `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`:
```kotlin
val DEFAULT_WORKOUT_CHARTS =
    listOf(
        WorkoutChartConfiguration(WorkoutChartId.ACWR_TRIMP, isVisible = true, position = 0),
        WorkoutChartConfiguration(WorkoutChartId.WEEKLY_TRAINING, isVisible = true, position = 1),
        WorkoutChartConfiguration(WorkoutChartId.ACTIVITY_VOLUME, isVisible = true, position = 2),
        WorkoutChartConfiguration(WorkoutChartId.TRAINING_MIX, isVisible = true, position = 3),
        WorkoutChartConfiguration(WorkoutChartId.RESIDUAL_FATIGUE_CURVE, isVisible = false, position = 4),
    )
```

In `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensions.kt`:
```kotlin
WorkoutChartId.RESIDUAL_FATIGUE_CURVE -> R.string.chart_residual_fatigue_curve_title
```

In `feature/workouts/src/main/res/values/strings.xml`:
```xml
<string name="chart_residual_fatigue_curve_title">Residual Fatigue (24h)</string>
<string name="chart_residual_fatigue_curve_description">24-hour continuous exponential decay curve showing workout fatigue spikes and decay throughout the day.</string>
<string name="chart_residual_fatigue_empty">No residual fatigue data available for this day.</string>
<string name="chart_residual_fatigue_tooltip_format">%1$s • Fatigue: %2$.1f</string>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutChartIdExtensionsTest*' :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelLayoutManagementTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/WorkoutChartId.kt core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensions.kt feature/workouts/src/main/res/values/strings.xml feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensionsTest.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelLayoutManagementTest.kt
git commit -m "feat: add WorkoutChartId.RESIDUAL_FATIGUE_CURVE domain and layout configuration"
```

---

### Task 5: Implement Vico 24-Hour Residual Fatigue Chart UI on Workouts Dashboard

**Files:**
- Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChart.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsUiState.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsChartFactory.kt`
- Create: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChartTest.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt`

**Interfaces:**
- Consumes: `Generate24hResidualFatigueCurveUseCase`, `WorkoutsUiState.residualFatigueCurve`, `WorkoutDao.getCanonicalFatigueSeed`.
- Produces: `ResidualFatigueCurveChart` composable registered in `WorkoutsChartFactory` under `WorkoutChartId.RESIDUAL_FATIGUE_CURVE`.

- [ ] **Step 1: Write failing ViewModel and Composable tests**

```kotlin
@Test
fun `workoutsViewModel loads 24h residual fatigue curve for selected day`() = runTest {
    // verify WorkoutsUiState.residualFatigueCurve is populated with points from Generate24hResidualFatigueCurveUseCase
    viewModel.onDateSelected(LocalDate.of(2026, 8, 29))
    val state = viewModel.uiState.value
    assertNotNull(state.residualFatigueCurve)
    assertEquals(96, state.residualFatigueCurve.size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest*'`
Expected: FAIL with `Unresolved reference: residualFatigueCurve`.

- [ ] **Step 3: Implement `ResidualFatigueCurveChart` and wire into `WorkoutsViewModel`**

1. Add `residualFatigueCurve: List<FatigueCurvePoint> = emptyList()` to `WorkoutsUiState`.
2. In `WorkoutsViewModel.kt`, inject `Generate24hResidualFatigueCurveUseCase` and `WorkoutDao`, and compute the 24h curve flow when selected date or workouts change.
3. In `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChart.kt`:
   - Build a Cartesian line chart using Vico `rememberLineCartesianLayer` with `LineCartesianLayer.LineProvider.series(...)` configured with Cubic Bézier curves (`pointConnector = PointConnector.cubic(...)`) and area gradient fill.
   - Dynamic Y-axis: bottom axis at 0, top axis at `max(100f, peak)`.
   - Horizontal X-axis formatted at 4-hour intervals (`00:00`, `04:00`, `08:00`, `12:00`, `16:00`, `20:00`, `24:00`).
   - Scrubber overlay showing time and formatted fatigue value.
   - Header with `chart_residual_fatigue_curve_title` and tooltip.
4. Register in `WorkoutsChartFactory.kt`:
```kotlin
WorkoutChartId.RESIDUAL_FATIGUE_CURVE -> { _: WorkoutChartConfiguration ->
    ResidualFatigueCurveChart(
        points = uiState.residualFatigueCurve,
        isLoading = uiState.isLoading,
        parentScrollInProgress = parentScrollInProgress,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest*' :feature:workouts:testDebugUnitTest --tests '*ResidualFatigueCurveChartTest*'`
Expected: PASS.

- [ ] **Step 5: Index new files and commit**

```bash
codegraph index
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChart.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsUiState.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsChartFactory.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/ResidualFatigueCurveChartTest.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt
git commit -m "feat: implement ResidualFatigueCurveChart on Workouts screen"
```

---

### Task 6: Polish Residual Fatigue Settings Header & Switch Layout

**Files:**
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/AdvancedResidualFatigueSection.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`
- Modify: `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `UIState.residualFatigueEnabled`, `SettingsEvent.ResidualFatigueEnabledChanged`, `MetricTooltip`.
- Produces: Polished `ResidualFatigueSubsection` with header info tooltip and simplified "Enabled" switch row without noisy descriptive text.

- [ ] **Step 1: Write/update Settings UI tests**

Verify that `ResidualFatigueEnabledChanged` continues to persist and trigger refresh cleanly while the UI structure uses the compact label.

- [ ] **Step 2: Update string resources**

In `feature/settings/src/main/res/values/strings.xml`:
```xml
<string name="advanced_residual_fatigue_enabled_label">Enabled</string>
<string name="advanced_residual_fatigue_info_tooltip">Tracks training fatigue that accumulates from workouts and decays exponentially. Configured half-life (24h default) and gain (1.0 default) are provisional product guardrails, not scientifically validated ranges.</string>
```

- [ ] **Step 3: Update `AdvancedResidualFatigueSection.kt`**

```kotlin
@Composable
fun ResidualFatigueSubsection(
    uiState: UIState,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.advanced_residual_fatigue_title),
                style = MaterialTheme.typography.labelLarge,
            )
            MetricTooltip(description = stringResource(R.string.advanced_residual_fatigue_info_tooltip))
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = {
                Switch(
                    checked = uiState.residualFatigueEnabled,
                    onCheckedChange = { onUIEvent(SettingsEvent.ResidualFatigueEnabledChanged(it)) },
                    enabled = controlsEnabled,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.advanced_residual_fatigue_enabled_label),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (uiState.residualFatigueEnabled) {
            ResidualFatigueControls(
                uiState = uiState,
                controlsEnabled = controlsEnabled,
                onUIEvent = onUIEvent,
            )
        }
    }
}
```

- [ ] **Step 4: Run settings unit tests**

Run: `./gradlew :feature:settings:testDebugUnitTest :feature:settings:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/AdvancedResidualFatigueSection.kt feature/settings/src/main/res/values/strings.xml feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/SettingsViewModelTest.kt
git commit -m "feat: polish residual fatigue settings header and switch layout"
```

---

### Task 7: Synchronize DATA_FLOW.md, Strings & Execute Final Quality Gates

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `ABOUT.md`
- Modify: `docs/about.md`
- Modify: `docs/customization.md`

**Interfaces:**
- Consumes: Completed Dashboard card, 24-hour Workouts curve, and Settings UI polish from Tasks 1–6.
- Produces: Synchronized data flow documentation, passing documentation drift tests, and passing pre-commit quality gates.

- [ ] **Step 1: Update DATA_FLOW.md & Jekyll docs**

Document:
1. `CardId.RESIDUAL_FATIGUE` presentation pipeline in Dashboard card catalog and metric factory.
2. `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` 24-hour on-demand sampling pipeline from `WorkoutDao.getCanonicalFatigueSeed` $\rightarrow$ `Generate24hResidualFatigueCurveUseCase` $\rightarrow$ `ResidualFatigueCurveChart`.
3. Re-verify shadow-only status and default-hidden visibility across both views.

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

- [ ] **Step 5: Commit documentation and final verification updates**

```bash
git add internal-docs/DATA_FLOW.md ABOUT.md docs/about.md docs/customization.md
git commit -m "docs: document Residual Fatigue dashboard card and 24h curve chart"
```

---

## Completion Criteria

- `CardId.RESIDUAL_FATIGUE` is registered, hidden by default, and presents Gauge/Bar/Value with 0..100 scale and color-classified status on the Dashboard.
- Tapping the Dashboard Residual Fatigue card navigates to the Workouts tab.
- `WorkoutChartId.RESIDUAL_FATIGUE_CURVE` is registered, hidden by default, and renders a smooth 24-hour Cubic Bézier curve with area gradient fill and touch scrubber on the Workouts tab.
- 24-hour timeline sampling accurately captures quarter-hour marks and exact workout completion impulses using canonical `modelTrimp` and active user preferences.
- Settings header for Residual Fatigue features an info icon tooltip with full description and a clean, concise "Enabled" toggle switch.
- Phase 1 shadow isolation is preserved (Readiness, Load Score, recommendations are 100% unaltered).
- All strings are localized in `strings.xml`.
- Documentation in `internal-docs/DATA_FLOW.md`, `ABOUT.md`, and Jekyll docs is synchronized.
- Mandatory formatting, analysis, build, unit-test, release-lint, and codegraph gates pass cleanly.
