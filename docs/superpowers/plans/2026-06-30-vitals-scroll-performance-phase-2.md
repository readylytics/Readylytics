# Vitals Scroll Performance Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce remaining Vitals recomposition and chart-list rebuilding after phase 1 by separating render-state data flows and composition scopes.

**Architecture:** Phase 1 remains intact. Build selection-bound chart content, presentation thresholds, and loading state through independent structurally distinct flows; combine only their immutable results into `VitalsUiState`. Move baselines into nested presentation state so sync and preference changes reuse existing chart-list instances.

**Tech Stack:** Kotlin, Coroutines Flow, Jetpack Compose, Hilt ViewModel, JUnit, coroutine test utilities already used by feature tests

---

## Activation Gate

Use this plan only when phase 1 is merged, all phase-1 code checks pass, and user separately reports loaded Vitals scrolling remains insufficient. Do not treat this gate as performance verification or claim measured frame-time improvement. Loading-only stutter needs separate scope.

## File Structure

- Create `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`: pure chart-series and zone-state builders.
- Create `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt`: output-equivalence and boundary tests.
- Create `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModelTest.kt`: selective-emission flow tests.
- Modify `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`: separated flows and unified baselines.
- Modify `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`: single state collection and nested render-state reads.

### Task 1: Extract Pure Vitals State Factories

**Files:**
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`
- Create: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt`

- [ ] **Step 1: Write failing series-equivalence tests**

Test 7-day padding, missing metrics, sorted offsets, SpO2 rounding, and zone derivation:

```kotlin
class VitalsStateFactoryTest {
    @Test
    fun `series builder pads and sorts each metric independently`() {
        val start = LocalDate.of(2026, 6, 1)
        val summaries =
            listOf(
                summary(date = start.plusDays(2), hrv = 42, rhr = 51, spo2 = 96.6),
                summary(date = start, hrv = 40, rhr = null, spo2 = 94.4),
            )

        val result = buildVitalsChartSeries(summaries, start, rangeDays = 7)

        assertEquals(7, result.hrv.size)
        assertEquals(40f, result.hrv[0].value)
        assertEquals(42f, result.hrv[2].value)
        assertNull(result.rhr[0].value)
        assertEquals(51f, result.rhr[2].value)
        assertEquals(94f, result.spo2[0].value)
        assertEquals(97f, result.spo2[2].value)
    }

    @Test
    fun `zone state retains baselines and thresholds`() {
        val result = buildVitalsPresentationState(
            baselines = Baselines(hrv = 50f, rhr = 48),
            hrvOptimalThreshold = 0.9f,
            hrvWarningThreshold = 0.8f,
            rhrOptimalThreshold = 1.05f,
            rhrWarningThreshold = 1.15f,
        )

        assertEquals(50f, result.baselineHrv)
        assertEquals(48, result.baselineRhr)
        assertEquals(0.9f, result.hrvOptimalThreshold)
        assertEquals(1.15f, result.rhrWarningThreshold)
        assertNotNull(result.hrvZoneBands)
        assertNotNull(result.rhrZoneBands)
        assertNotNull(result.spo2ZoneBands)
    }
}
```

Add this exact test factory below test methods:

```kotlin
private fun summary(
    date: LocalDate,
    hrv: Int? = null,
    rhr: Int? = null,
    spo2: Double? = null,
): DailySummary =
    DailySummary(
        date = date,
        nocturnalHrv = hrv,
        restingHeartRate = rhr,
        avgSleepingSpo2 = spo2?.toFloat(),
        isCalibrating = false,
    )
```

- [ ] **Step 2: Run focused test and confirm missing-symbol failure**

```powershell
.\gradlew :feature:vitals:testDebugUnitTest --tests "*VitalsStateFactoryTest"
```

Expected: compilation fails for missing factory types/functions.

- [ ] **Step 3: Implement immutable factory outputs**

Create these types and functions, moving existing ViewModel formulas unchanged:

```kotlin
@Immutable
data class VitalsChartSeries(
    val hrv: List<DailyDataPoint>,
    val rhr: List<DailyDataPoint>,
    val spo2: List<DailyDataPoint>,
)

@Immutable
data class VitalsPresentationState(
    val baselineHrv: Float?,
    val baselineRhr: Int?,
    val hrvZoneBands: List<ZoneBand>?,
    val rhrZoneBands: List<ZoneBand>?,
    val spo2ZoneBands: List<ZoneBand>,
    val hrvOptimalThreshold: Float,
    val hrvWarningThreshold: Float,
    val rhrOptimalThreshold: Float,
    val rhrWarningThreshold: Float,
) {
    companion object {
        fun empty(): VitalsPresentationState =
            VitalsPresentationState(
                baselineHrv = null,
                baselineRhr = null,
                hrvZoneBands = null,
                rhrZoneBands = null,
                spo2ZoneBands = spo2ZoneBands(),
                hrvOptimalThreshold = 0.9f,
                hrvWarningThreshold = 0.8f,
                rhrOptimalThreshold = 1.05f,
                rhrWarningThreshold = 1.15f,
            )
    }
}

internal fun buildVitalsChartSeries(
    summaries: List<DailySummary>,
    startDate: LocalDate,
    rangeDays: Int,
): VitalsChartSeries {
    fun points(value: (DailySummary) -> Float?): List<DailyDataPoint> =
        summaries.mapNotNull { summary ->
            value(summary)?.let {
                DailyDataPoint(ChronoUnit.DAYS.between(startDate, summary.date).toInt(), it)
            }
        }.sortedBy(DailyDataPoint::dayOffset).padToRange(rangeDays)

    return VitalsChartSeries(
        hrv = points { it.nocturnalHrv?.toFloat() },
        rhr = points { it.restingHeartRate?.toFloat() },
        spo2 = points { it.avgSleepingSpo2?.roundToInt()?.toFloat() },
    )
}
```

Add exact presentation builder:

```kotlin
internal fun buildVitalsPresentationState(
    baselines: Baselines,
    hrvOptimalThreshold: Float,
    hrvWarningThreshold: Float,
    rhrOptimalThreshold: Float,
    rhrWarningThreshold: Float,
): VitalsPresentationState {
    val hrvBands =
        baselines.hrv?.let { baseline ->
            hrvZoneBands(
                optimalMin = hrvOptimalThreshold * baseline,
                neutralMin = hrvWarningThreshold * baseline,
                warningMin = (2f * hrvWarningThreshold - 1f) * baseline,
            )
        }
    val rhrBands =
        baselines.rhr?.toFloat()?.let { baseline ->
            rhrZoneBands(
                optimalMax = rhrOptimalThreshold * baseline,
                neutralMax = rhrWarningThreshold * baseline,
                warningMax = rhrWarningThreshold * 1.3f * baseline,
            )
        }
    return VitalsPresentationState(
        baselineHrv = baselines.hrv,
        baselineRhr = baselines.rhr,
        hrvZoneBands = hrvBands,
        rhrZoneBands = rhrBands,
        spo2ZoneBands = spo2ZoneBands(),
        hrvOptimalThreshold = hrvOptimalThreshold,
        hrvWarningThreshold = hrvWarningThreshold,
        rhrOptimalThreshold = rhrOptimalThreshold,
        rhrWarningThreshold = rhrWarningThreshold,
    )
}
```

- [ ] **Step 4: Run focused tests**

```powershell
.\gradlew :feature:vitals:testDebugUnitTest --tests "*VitalsStateFactoryTest"
```

Expected: all factory tests pass.

- [ ] **Step 5: Commit pure state factories**

```powershell
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactoryTest.kt
git commit -m "refactor(vitals): isolate render-state factories"
```

### Task 2: Separate ViewModel Flow Invalidations

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`
- Create: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModelTest.kt`

- [ ] **Step 1: Add baseline fields to `VitalsUiState` tests**

Write ViewModel tests proving:

```kotlin
@Test
fun `sync change preserves structurally equal chart series`() = runTest {
    val before = viewModel.uiState.first { !it.isLoading }
    syncing.value = true
    val during = viewModel.uiState.first { it.isLoading }

    assertSame(before.chartSeries.hrv, during.chartSeries.hrv)
    assertSame(before.chartSeries.rhr, during.chartSeries.rhr)
    assertSame(before.chartSeries.spo2, during.chartSeries.spo2)
}

@Test
fun `preference emission updates presentation without rebuilding chart series`() = runTest {
    val before = viewModel.uiState.first { it.presentation.hrvOptimalThreshold == 0.9f }
    settingsRepo.emitHrvThresholds(optimal = 0.95f, warning = 0.85f)
    val after = viewModel.uiState.first { it.presentation.hrvOptimalThreshold == 0.95f }

    assertSame(before.chartSeries.hrv, after.chartSeries.hrv)
    assertSame(before.chartSeries.rhr, after.chartSeries.rhr)
    assertSame(before.chartSeries.spo2, after.chartSeries.spo2)
}
```

Follow existing feature pattern: `StandardTestDispatcher`, `Dispatchers.setMain` in `@Before`, `Dispatchers.resetMain` in `@After`, and MockK for repository/provider dependencies. Create ViewModel with this complete setup:

```kotlin
private val testDispatcher = StandardTestDispatcher()
private val summaries = MutableStateFlow<List<DailySummary>>(emptyList())
private val selectedDateFlow = MutableStateFlow(LocalDate.now())
private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
private val syncing = MutableStateFlow(false)
private val settingsRepo = FakeUserPreferencesReader()

private val dailySummaryRepository = mockk<DailySummaryRepository> {
    every { observeSince(any()) } returns summaries
    every { observeByDate(any()) } returns MutableStateFlow(null)
}
private val dailyMetricsRepository = mockk<DailyMetricsRepository>(relaxed = true)
private val selectedDateStore = mockk<SelectedDateStore> {
    every { selectedDate } returns selectedDateFlow
    every { earliestDate } returns earliestDateFlow
}
private val foregroundSyncGateway = mockk<ForegroundSyncGateway> {
    every { isSyncing } returns syncing
}
private val hrvBaselineProvider = mockk<HrvBaselineProvider> {
    coEvery { getRoundedHrvBaseline(any()) } returns 50
}
private val rhrBaselineProvider = mockk<RhrBaselineProvider> {
    coEvery { getRoundedRhrBaseline(any()) } returns 48
}

private fun createViewModel() =
    VitalsViewModel(
        dailySummaryRepository = dailySummaryRepository,
        dailyMetricsRepository = dailyMetricsRepository,
        settingsRepo = settingsRepo,
        selectedDateRepository = selectedDateStore,
        foregroundSyncController = foregroundSyncGateway,
        savedStateHandle = SavedStateHandle(),
        hrvBaselineProvider = hrvBaselineProvider,
        rhrBaselineProvider = rhrBaselineProvider,
        ioDispatcher = testDispatcher,
    )

@Before
fun setUp() {
    Dispatchers.setMain(testDispatcher)
}

@After
fun tearDown() {
    Dispatchers.resetMain()
}
```

Back fake preferences with mutable state so threshold-only emissions are explicit:

```kotlin
private class FakeUserPreferencesReader : UserPreferencesReader {
    private val preferences = MutableStateFlow(UserPreferences())
    override val userPreferences: Flow<UserPreferences> = preferences

    fun emitHrvThresholds(optimal: Float, warning: Float) {
        preferences.value =
            preferences.value.copy(
                hrvOptimalThreshold = optimal,
                hrvWarningThreshold = warning,
            )
    }
}
```

- [ ] **Step 2: Run focused ViewModel tests and confirm failure**

Expected: tests fail because baselines are outside `VitalsUiState` and broad combine rebuilds lists.

- [ ] **Step 3: Build distinct content and presentation flows**

Replace chart-list, zone, threshold, and baseline fields in `VitalsUiState` with `chartSeries` and `presentation` fields:

```kotlin
data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = VitalsChartSeries(emptyList(), emptyList(), emptyList()),
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
)
```

Use `VitalsPresentationState.empty()` defined in Task 1 for initial state.

Create selection-bound content once per summary emission, independent from preferences, baselines, and sync state:

```kotlin
private data class VitalsSelection(val range: TimeRange, val date: LocalDate)

private data class VitalsContentState(
    val latestSummary: DailySummary?,
    val chartSeries: VitalsChartSeries,
    val selection: VitalsSelection,
    val rangeStartMs: Long,
)

private val selectionFlow =
    combine(_selectedRange, selectedDateRepository.selectedDate, ::VitalsSelection)
        .distinctUntilChanged()

private val contentFlow =
    selectionFlow.flatMapLatest { selection ->
        val fromMs = selection.range.fromMs(selection.date)
        val startDayMs = fromMs.truncateToDayMs()
        val zoneId = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()
        val selectedMidnightMs = selection.date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val latestFlow =
            if (selection.date == LocalDate.now(zoneId)) {
                val todayMs = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
                dailySummaryRepository.observeSince(todayMs).map { it.firstOrNull() }
            } else {
                dailySummaryRepository.observeByDate(selectedMidnightMs)
            }

        combine(latestFlow, dailySummaryRepository.observeSince(fromMs)) { latest, summaries ->
            VitalsContentState(
                latestSummary = latest,
                chartSeries = buildVitalsChartSeries(summaries, startDate, selection.range.days),
                selection = selection,
                rangeStartMs = startDayMs,
            )
        }.distinctUntilChanged()
    }
        .flowOn(ioDispatcher)

private val presentationFlow =
    combine(settingsRepo.userPreferences, baselinesFlow) { prefs, baselines ->
        buildVitalsPresentationState(
            baselines = baselines,
            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
            hrvWarningThreshold = prefs.hrvWarningThreshold,
            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
            rhrWarningThreshold = prefs.rhrWarningThreshold,
        )
    }.distinctUntilChanged()
        .flowOn(ioDispatcher)
```

Combine outputs without rebuilding chart series:

```kotlin
val uiState =
    combine(
        contentFlow,
        presentationFlow,
        foregroundSyncController.isSyncing.distinctUntilChanged(),
    ) { content, presentation, isSyncing ->
        VitalsUiState(
            latestSummary = content.latestSummary,
            chartSeries = content.chartSeries,
            presentation = presentation,
            selectedRange = content.selection.range,
            selectedDate = content.selection.date,
            rangeStartMs = content.rangeStartMs,
            isLoading = isSyncing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VitalsUiState(isLoading = true),
    )
```

- [ ] **Step 4: Run ViewModel and factory tests**

```powershell
.\gradlew :feature:vitals:testDebugUnitTest --tests "*VitalsViewModelTest" --tests "*VitalsStateFactoryTest"
```

Expected: all tests pass and identity assertions confirm no chart-list rebuild on sync/baseline-only changes.

- [ ] **Step 5: Commit separated flows**

```powershell
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModelTest.kt
git commit -m "perf(vitals): isolate chart-series invalidations"
```

### Task 3: Use Unified Render State in Compose

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`

- [ ] **Step 1: Remove duplicate baseline collection**

In `VitalsRoute`, collect only `uiState` and `earliestDate`. Remove `baselinesFlow.collectAsStateWithLifecycle()` and remove `baselineHrv`/`baselineRhr` parameters from `VitalsScreen` and its call.

- [ ] **Step 2: Bind gauges to presentation state**

At top of `VitalsScreen`, create local stable references:

```kotlin
val chartSeries = uiState.chartSeries
val presentation = uiState.presentation
val baselineHrv = presentation.baselineHrv
val baselineRhr = presentation.baselineRhr
```

Keep existing gauge formulas unchanged, replacing threshold reads as follows:

```kotlin
optimalThreshold = presentation.rhrOptimalThreshold
warningThreshold = presentation.rhrWarningThreshold
```

and:

```kotlin
optimalThreshold = presentation.hrvOptimalThreshold
warningThreshold = presentation.hrvWarningThreshold
```

- [ ] **Step 3: Bind charts to stable nested state**

Replace only point and zone-band arguments in existing three direct `TrendChart` calls:

```kotlin
points = chartSeries.hrv
zoneBands = presentation.hrvZoneBands

points = chartSeries.rhr
zoneBands = presentation.rhrZoneBands

points = chartSeries.spo2
zoneBands = presentation.spo2ZoneBands
```

Keep all three `TrendChart` calls, shared chart scroll/zoom state, `Column.verticalScroll`, and deferred `{ scrollState.isScrollInProgress }` callbacks unchanged.

- [ ] **Step 4: Compile and run Vitals tests**

```powershell
.\gradlew :feature:vitals:compileDebugKotlin :feature:vitals:testDebugUnitTest
```

Expected: compile and tests pass; route has one fewer collected state flow.

- [ ] **Step 5: Commit unified state binding**

```powershell
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt
git commit -m "perf(vitals): consume unified render state"
```

### Task 4: Final Verification and Index Sync

**Files:**
- Verify all phase 2 files

- [ ] **Step 1: Format and run mandatory unit suite**

```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
```

Expected: both commands succeed.

- [ ] **Step 2: Run lint**

```powershell
.\gradlew lint
```

Expected: lint succeeds with no new findings.

- [ ] **Step 3: Refresh structural code index**

Phase 2 adds files and moves Vitals composition responsibilities:

```powershell
codegraph sync
codegraph index
```

Expected: sync and index complete; searches resolve `VitalsChartSeries` and `VitalsPresentationState` at new paths.

- [ ] **Step 4: Review final diff against constraints**

```powershell
git diff --check
git diff --stat
rg -n "LazyColumn|items\(" feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview
```

Expected: no whitespace errors; no lazy container introduced; all three direct `TrendChart` calls remain present.

- [ ] **Step 5: Commit verification-only formatting changes if produced**

```powershell
git add feature/vitals core/ui
git commit -m "style: format vitals performance refactor"
```

Skip this commit when `ktlintFormat` produces no diff.
