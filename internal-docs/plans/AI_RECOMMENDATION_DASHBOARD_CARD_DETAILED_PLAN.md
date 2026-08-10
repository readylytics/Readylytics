# AI Recommendation Dashboard Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an offline-first, user-manageable dashboard card that copies a stable setup prompt and a populated daily training prompt for use in an external AI chat application.

**Architecture:** Keep prompt data assembly and formatting in pure Kotlin under `core/scoring`. `GetDailyPromptDataUseCase` reads persisted `DailySummary` records, bounded workouts, preferences, and existing workout display metrics; `DailyPromptFormatter` emits stable English prompt text matching the approved prompt documents. The dashboard ViewModel owns asynchronous generation, while Compose owns clipboard and snackbar side effects.

**Tech Stack:** Kotlin, coroutines, `java.time`, Hilt, Room-backed repositories, DataStore preferences, Jetpack Compose Material 3, `StateFlow`, MockK/JUnit, Gradle/Ktlint, codegraph.

## Global Constraints

- No in-app AI provider, network client, API key, account, or LLM integration.
- Preserve Clean Architecture and zero Android dependencies in pure domain logic.
- Room DB remains the source of truth; do not read Health Connect from this feature.
- Do not recalculate scoring or alter scoring formulas; use persisted summaries and `GetWorkoutDisplayMetricsUseCase`.
- The prompt formatter stays in `core/scoring` and emits stable English prompt structure; only dashboard card UI copy is localized.
- Every dashboard-visible UI string belongs in `feature/dashboard/src/main/res/values/strings.xml`.
- Add `AI_RECOMMENDATION` to defaults so existing `CardConfigurationRepositoryImpl` appends it once, visibly, at the end of saved configurations.
- Prompt structure must stay synchronized with `internal-docs/ai-recommendations/BASE_SYSTEM_PROMPT.md` and `DAILY_PROMPT_TEMPLATE.md`.
- Preserve M3 card styling: `OutlinedCard`, `MaterialTheme.shapes.large`, existing tonal colors, dimensions, and spacing.
- Preserve cancellation: never convert `CancellationException` into an error state.
- Run `codegraph index` after new source files are created.
- Do not commit generated build output, secrets, or unrelated worktree changes.

## File Map

### Existing files to modify

- `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`: expose the bounded workout-range query.
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`: delegate and map range results.
- `core/model/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringConstants.kt`: add the three-month AI lookback constant.
- `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt`: add `CardId.AI_RECOMMENDATION`.
- `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardIdExtensions.kt`: add the exhaustive `displayName()` branch.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt`: add the exhaustive `displayNameResId` branch.
- `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`: append the visible card to default dashboard configurations.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardEvent.kt`: add prompt-copy events.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`: inject the orchestration use case and expose one-shot prompt/error state.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`: register and render the card.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`: bridge ViewModel state to clipboard and snackbar callbacks.
- `feature/dashboard/src/main/res/values/strings.xml`: add card UI copy and the static setup prompt, with a source comment.
- Relevant existing dashboard/card/preference tests: update expected card counts/catalog/defaults and add append behavior assertions.

### New production files

- `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptData.kt`: pure prompt data structures.
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/RecoveryFlagGlossary.kt`: pure recovery-flag explanations.
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCase.kt`: pure workout-history aggregation.
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatter.kt`: pure stable-English prompt formatter.
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCase.kt`: injected repository/preferences orchestrator.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/AiRecommendationCard.kt`: Compose card.

### New/modified tests

- `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCaseTest.kt`: pure aggregation boundaries.
- `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatterTest.kt`: complete prompt rendering.
- `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/RecoveryFlagGlossaryTest.kt`: exhaustive glossary coverage.
- `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCaseTest.kt`: orchestration and source selection.
- `core/database/src/test/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImplTest.kt`: range delegation/mapping.
- `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt`: catalog/default registration.
- `app/src/test/kotlin/app/readylytics/health/data/preferences/CardConfigurationRepositoryTest.kt`: existing-install append-once behavior.
- `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt`: request/success/error/clear behavior.
- `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardScreenTest.kt`: card rendering and callback wiring.

---

### Task 1: Add the bounded workout repository contract

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImplTest.kt`

**Interfaces:**
- Consumes: existing `WorkoutDao.getWorkoutsInRange(fromMs: Long, toMs: Long)` and `WorkoutData` mapping.
- Produces: `suspend fun WorkoutRepository.getInRange(fromMs: Long, toMs: Long): List<WorkoutData>` for `GetDailyPromptDataUseCase` and pattern aggregation callers.

- [ ] **Step 1: Write the failing contract/implementation test**

Use the repository implementation test pattern already used in the database module. Mock the DAO to return one `WorkoutRecordEntity` and assert that `getInRange(100L, 200L)` returns one mapped `WorkoutData` with the same id, timestamps, exercise type, duration, zones, TRIMP, average HR, and device name. Verify the DAO receives exactly `100L` and `200L`.

```kotlin
coEvery { dao.getWorkoutsInRange(100L, 200L) } returns listOf(entity)

val result = repository.getInRange(100L, 200L)

assertEquals(listOf(expectedWorkout), result)
coVerify(exactly = 1) { dao.getWorkoutsInRange(100L, 200L) }
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run the exact module test task selected by the repository test location, for example:

```bash
./gradlew :core:database:test --tests '*WorkoutRepositoryImplTest'
```

Expected: compilation failure because `WorkoutRepository` has no `getInRange` method and `WorkoutRepositoryImpl` has no implementation.

- [ ] **Step 3: Add the interface method**

Add this method after `getEarliestWorkoutTimestamp()`:

```kotlin
suspend fun getInRange(fromMs: Long, toMs: Long): List<WorkoutData>
```

- [ ] **Step 4: Add the thin implementation delegation**

Implement the method in `WorkoutRepositoryImpl` using the existing private `mapToDomain` mapper:

```kotlin
    dao.getWorkoutsInRange(fromMs, toMs).map(::mapToDomain)
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
./gradlew :core:database:test --tests '*WorkoutRepositoryImplTest'
```

Expected: PASS, with no DAO or schema changes.

- [ ] **Step 6: Commit the isolated repository change**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt core/database/src/test
git commit -m "feat: expose bounded workout repository query"
```

### Task 2: Define pure daily-prompt data and recovery glossary

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptData.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/RecoveryFlagGlossary.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/RecoveryFlagGlossaryTest.kt`

**Interfaces:**
- Consumes: `DailySummary`, `RecoveryFlag`, `WorkoutData`, `WorkoutDisplayMetrics`, `UserPreferences` values supplied by later orchestration.
- Produces: immutable prompt data types consumed by `DailyPromptFormatter` and `GetDailyPromptDataUseCase`.

- [ ] **Step 1: Write glossary tests before implementation**

Test every enum value, including legacy values, and assert each returns nonblank stable English text. Also assert the set of glossary keys equals `RecoveryFlag.entries.toSet()` so adding a new enum requires updating the glossary test/implementation.

```kotlin
@Test
fun `every recovery flag has a nonblank glossary entry`() {
    assertEquals(RecoveryFlag.entries.toSet(), RecoveryFlagGlossary.entries.keys)
    RecoveryFlag.entries.forEach { flag ->
        assertTrue(RecoveryFlagGlossary.explain(flag).isNotBlank())
    }
}
```

- [ ] **Step 2: Run the glossary test and verify it fails**

Run:

```bash
./gradlew :core:scoring:test --tests '*RecoveryFlagGlossaryTest'
```

Expected: compilation failure because the package and glossary do not exist.

- [ ] **Step 3: Define the prompt data model**

Model the template’s Sections A-G without Android/resource types. Use nullable fields for unavailable source values and explicit list types for repeated blocks. The minimum public types are:

```kotlin
    val date: LocalDate,
    val physiologyProfile: String?,
    val calibrationPhase: String?,
    val baselineObservationCount: Int?,
    val isCalibrating: Boolean,
    val activeTrainingLoadSource: String,
    val everydayLoadConfidence: String?,
    val today: TodayPromptData,
    val yesterdaySleep: YesterdaySleepPromptData?,
    val yesterdayWorkouts: List<YesterdayWorkout>,
    val loadState: LoadStatePromptData,
    val activeRecoveryFlags: List<RecoveryFlagPrompt>,
    val workoutPattern: WorkoutPatternSummary,
)

data class YesterdayWorkout(
    val workout: WorkoutData,
    val modelTrimp: Float?,
    val roundedGainedStrain: String?,
    val preciseGainedStrain: String?,
    val loadClassification: String?,
    val intensity: String?,
)

data class ExerciseTypePattern(
    val exerciseType: String,
    val frequencyPerWeek: Float,
    val averageTrimp: Float?,
    val averageDurationMinutes: Float?,
    val averageLoadClassification: String?,
    val preferredDaysOfWeek: List<String>,
)

data class WorkoutPatternSummary(
    val lookbackMonths: Int,
    val totalWorkoutsInWindow: Int,
    val exerciseTypeBreakdown: List<ExerciseTypePattern>,
    val restDaysPerWeekAverage: Float,
    val mostRecentRestDayGapDays: Int,
    val currentConsecutiveTrainingDayStreak: Int,
)

data class TodayPromptData(
    val readinessScore: Float?,
    val restorationScore: Float?,
    val hrvBaseline: Int?,
    val hrvMuMssd: Float?,
    val hrvSigmaMssd: Float?,
    val restingHeartRate: Int?,
    val restingHrRatio: Float?,
    val rhrSigma: Float?,
    val nocturnalHrv: Int?,
    val zLnHrv: Float?,
    val zRhr: Float?,
    val baselineCalculatedAtDate: LocalDate?,
)

data class YesterdaySleepPromptData(
    val sleepScore: Float?,
    val sleepDurationMinutes: Int?,
    val deepSleepPercent: Float?,
    val remSleepPercent: Float?,
    val supplementalSleepDurationMinutes: Int?,
    val napCount: Int?,
    val avgSleepingSpo2: Float?,
)

data class LoadStatePromptData(
    val acuteLoad: Float?,
    val chronicLoad: Float?,
    val strainRatio: Float?,
    val loadScore: Float?,
    val totalRasWorkoutOnly: Float?,
    val totalRasEverydayHr: Float?,
    val everydayCoverageMinutes: Int?,
)

data class RecoveryFlagPrompt(
    val flagName: RecoveryFlag,
    val plainEnglishGloss: String,
)
```

The implementer may split nested data classes into focused files only if the package remains pure and the public names remain unchanged. Map every `DailySummary` field needed by the template explicitly rather than passing `DailySummary` itself into the formatter.

- [ ] **Step 4: Implement the exhaustive glossary**

Create a pure object with `val entries: Map<RecoveryFlag, String>` and:

```kotlin
fun explain(flag: RecoveryFlag): String = entries.getValue(flag)
```

Use the drafted meanings from Section F of `DAILY_PROMPT_TEMPLATE.md`, including stable explanations for legacy `OVERREACHING` and `STAGES_MISSING`.

- [ ] **Step 5: Run the glossary test and verify it passes**

Run the same focused Gradle test. Expected: PASS.

- [ ] **Step 6: Commit the pure data contract**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/RecoveryFlagGlossaryTest.kt
git commit -m "feat: define AI recommendation prompt data"
```

### Task 3: Implement workout-pattern aggregation

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringConstants.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCase.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCaseTest.kt`

**Interfaces:**
- Consumes: `List<WorkoutData>`, `today: LocalDate`, and `lookbackMonths: Int`.
- Produces: `ComputeWorkoutPatternSummaryUseCase.execute(workouts: List<WorkoutData>, today: LocalDate, lookbackMonths: Int = ScoringConstants.AiRecommendation.LOOKBACK_MONTHS): WorkoutPatternSummary`.

- [ ] **Step 1: Add the tunable constant test expectation**

Add a test assertion that the default lookback is three months and that the use case output reports the same value:

```kotlin
assertEquals(3, ScoringConstants.AiRecommendation.LOOKBACK_MONTHS)
assertEquals(3, useCase.execute(emptyList(), LocalDate.of(2026, 8, 9)).lookbackMonths)
```

- [ ] **Step 2: Write boundary tests for the aggregation**

Cover these exact cases in `ComputeWorkoutPatternSummaryUseCaseTest`:

```kotlin
@Test
fun `empty history produces zero counts and zero streak`() {
    val result = useCase.execute(emptyList(), LocalDate.of(2026, 8, 9))
    assertEquals(0, result.totalWorkoutsInWindow)
    assertEquals(0, result.currentConsecutiveTrainingDayStreak)
}

@Test
fun `workouts outside lookback window are excluded`() {
    val result = useCase.execute(listOf(workoutOn(LocalDate.of(2026, 5, 8))), LocalDate.of(2026, 8, 9), 3)
    assertEquals(0, result.totalWorkoutsInWindow)
}

@Test
fun `multiple exercise types have independent averages and preferred weekdays`() {
    val result = useCase.execute(listOf(runOnMonday, runOnWednesday, cycleOnTuesday), LocalDate.of(2026, 8, 9))
    assertEquals(listOf("Run", "Cycle"), result.exerciseTypeBreakdown.map { it.exerciseType })
    assertEquals(listOf("Monday", "Wednesday"), result.exerciseTypeBreakdown.first().preferredDaysOfWeek)
}

@Test
fun `rest gap and current training streak walk calendar days from today`() {
    val result = useCase.execute(listOf(workoutOn(LocalDate.of(2026, 8, 7)), workoutOn(LocalDate.of(2026, 8, 8))), LocalDate.of(2026, 8, 9))
    assertEquals(0, result.mostRecentRestDayGapDays)
    assertEquals(0, result.currentConsecutiveTrainingDayStreak)
}

@Test
fun `all days without workouts produce full rest-day average`() {
    val result = useCase.execute(emptyList(), LocalDate.of(2026, 8, 9))
    assertEquals(7f, result.restDaysPerWeekAverage, 0.001f)
}
```

Use UTC or an explicit `ZoneId` only to construct epoch timestamps in fixtures; the use case itself receives `WorkoutData` epoch values and must convert consistently using the project’s existing scoring-zone convention or a documented UTC fixture convention. Assert values with a float delta.

- [ ] **Step 3: Run the focused tests and verify failure**

Run:

```bash
./gradlew :core:scoring:test --tests '*ComputeWorkoutPatternSummaryUseCaseTest'
```

Expected: compilation failure because the constant and use case do not exist.

- [ ] **Step 4: Add the constant without changing scoring values**

Inside the existing `ScoringConstants` object add a nested namespace:

```kotlin
object AiRecommendation {
    const val LOOKBACK_MONTHS = 3
}
```

- [ ] **Step 5: Implement the bounded aggregation**

Implement these rules exactly:

- Calculate `windowStart = today.minusMonths(lookbackMonths.toLong())` and include workouts whose local calendar date is on or after `windowStart` and on or before `today`.
- Group included workouts by `exerciseType`.
- For each group calculate count per `lookbackMonths * averageDaysPerMonth / 7` using the project’s existing calendar convention; do not invent a second persisted metric. The plan’s implementation should use `lookbackMonths * 30.4375 / 7` and round only at formatting time.
- Average raw `trimp` and `durationMinutes`; these `WorkoutData` fields are non-null, so an included workout always contributes numeric values.
- Preferred weekdays are the group’s weekdays sorted by descending count, then Monday-to-Sunday order for ties.
- Treat a calendar day as a training day if at least one included workout starts on that date.
- `restDaysPerWeekAverage` is rest-day count divided by window day count, multiplied by seven.
- `mostRecentRestDayGapDays` is zero when today is a rest day; otherwise count consecutive training days backward from today until the first rest day, bounded by the window.
- `currentConsecutiveTrainingDayStreak` follows the same backward walk and is zero when today is a rest day.
- Return deterministic exercise-type ordering: descending workout count, then case-insensitive exercise type.

- [ ] **Step 6: Run focused tests and verify pass**

Run the same focused test command. Expected: PASS.

- [ ] **Step 7: Commit the aggregation**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringConstants.kt core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCase.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/ComputeWorkoutPatternSummaryUseCaseTest.kt
git commit -m "feat: summarize workout patterns for recommendations"
```

### Task 4: Implement stable daily-prompt formatting

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatter.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatterTest.kt`
- Reference only: `internal-docs/ai-recommendations/DAILY_PROMPT_TEMPLATE.md`

**Interfaces:**
- Consumes: `DailyPromptData` from Task 2.
- Produces: `DailyPromptFormatter.format(data: DailyPromptData): String` with no Android/resource dependency.

- [ ] **Step 1: Write formatter tests from the template contract**

Construct a fully populated fixture and assert the formatted result contains each section heading, every representative value, every workout block, every exercise pattern, and the final Task H instruction. Add separate tests for:

```kotlin
@Test
fun `format includes all populated sections and repeated blocks`() {
    val text = DailyPromptFormatter.format(populatedPromptData())
    listOf("## A.", "## B.", "## C.", "## D.", "## E.", "## F.", "## G.", "## H.", "Run", "OVERREACHING").forEach {
        assertTrue(text.contains(it), "Missing $it")
    }
}

@Test
fun `format renders explicit unavailable values rather than unresolved tokens`() {
    val text = DailyPromptFormatter.format(emptyPromptData())
    assertTrue(text.contains("insufficient data"))
    assertFalse(text.contains("{{"))
    assertFalse(text.contains("}}"))
}

@Test
fun `format renders calibration and missing flags without numeric fabrication`() {
    val text = DailyPromptFormatter.format(calibratingPromptData())
    assertTrue(text.contains("CALIBRATING"))
    assertTrue(text.contains("insufficient data"))
}

@Test
fun `format renders no-workout and no-active-flag cases`() {
    val text = DailyPromptFormatter.format(emptyPromptData())
    assertTrue(text.contains("no workouts yesterday"))
    assertTrue(text.contains("no active recovery flags"))
}
```

Assert that the output contains no `{{`, `}}`, `#each`, or `/each` tokens.

- [ ] **Step 2: Run the formatter tests and verify failure**

Run:

```bash
./gradlew :core:scoring:test --tests '*DailyPromptFormatterTest'
```

Expected: compilation failure because `DailyPromptFormatter` does not exist.

- [ ] **Step 3: Implement the formatter as pure Kotlin**

Use a raw multiline string or a `StringBuilder`. Keep the exact stable English headings and field names from `DAILY_PROMPT_TEMPLATE.md`, including Sections A-H. Interpolate values directly and render nulls with one consistent explicit token such as `insufficient data`; render empty lists with `none active`/`none recorded` statements. Render numeric floats through deterministic formatting helpers local to this pure file; do not use Android or locale-sensitive resource APIs.

The formatter must include:

- Today’s date and profile/calibration/load-source fields.
- Readiness, restoration, baselines, z-scores, and baseline date.
- Yesterday’s sleep and supplemental-sleep fields.
- Zero or more yesterday workout blocks with selected-model TRIMP, zones, gained strain, classification, and intensity.
- Current ATL, CTL, ratio, load score, RAS context, and everyday coverage.
- Every active recovery flag with its glossary text, or an explicit no-flags marker.
- Workout-pattern totals, breakdown, rest-day values, and current streak.
- The Section H instruction that asks for the exact base-prompt output contract.

- [ ] **Step 4: Run focused tests and verify pass**

Run the same formatter test command. Expected: PASS.

- [ ] **Step 5: Compare output structure against both source prompt documents**

Manually compare headings, field labels, ordering, and safety wording with `DAILY_PROMPT_TEMPLATE.md` and `BASE_SYSTEM_PROMPT.md`. Do not copy Android resource references into this formatter.

- [ ] **Step 6: Commit the formatter**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatter.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/DailyPromptFormatterTest.kt
git commit -m "feat: format daily AI recommendation prompt"
```

### Task 5: Add the daily-prompt orchestration use case

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCase.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCaseTest.kt`

**Interfaces:**
- Consumes: `DailySummaryRepository`, `WorkoutRepository.getInRange`, `UserPreferencesReader`, `GetWorkoutDisplayMetricsUseCase`, `ComputeWorkoutPatternSummaryUseCase`, `RecoveryFlagGlossary`, and `LocalDate`.
- Produces: `@Inject class GetDailyPromptDataUseCase` with `suspend fun execute(today: LocalDate): DailyPromptData`.

- [ ] **Step 1: Write orchestration tests with mocks**

Test that the use case:

```kotlin
@Test
fun `execute reads today and yesterday persisted summaries`() = runTest {
    useCase.execute(today)
    coVerify { dailySummaryRepository.getByDate(todayMidnight) }
    coVerify { dailySummaryRepository.getByDate(yesterdayMidnight) }
}

@Test
fun `execute queries yesterday and lookback workouts with bounded epoch ranges`() = runTest {
    useCase.execute(today)
    coVerify { workoutRepository.getInRange(yesterdayMidnight, todayMidnight) }
    coVerify { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) }
}

@Test
fun `execute selects configured training-load source and maps recovery flags`() = runTest {
    every { preferences.strainLoadSourceMode } returns LoadSourceMode.EVERYDAY_HEART_RATE
    val result = useCase.execute(today)
    assertEquals("Everyday heart-rate load", result.activeTrainingLoadSource)
    assertEquals(RecoveryFlag.ILLNESS_ONSET, result.activeRecoveryFlags.single().flagName)
}

@Test
fun `execute reuses display metrics for every yesterday workout`() = runTest {
    useCase.execute(today)
    coVerify(exactly = yesterdayWorkouts.size) {
        getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any())
    }
}

@Test
fun `execute preserves null summaries and empty workout lists`() = runTest {
    coEvery { dailySummaryRepository.getByDate(any()) } returns null
    coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()
    val result = useCase.execute(today)
    assertTrue(result.yesterdayWorkouts.isEmpty())
    assertNull(result.yesterdaySleep)
}
```

Mock `DailySummaryRepository.getByDate`, `WorkoutRepository.getInRange`, `UserPreferencesReader.userPreferences`, and `GetWorkoutDisplayMetricsUseCase.execute`. Verify no `ScoringRepository.computeDailySummary` call is introduced.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
./gradlew :core:scoring:test --tests '*GetDailyPromptDataUseCaseTest'
```

Expected: compilation failure because the use case does not exist.

- [ ] **Step 3: Implement the injected constructor and date-window logic**

Use this constructor shape:

```kotlin
@Inject
constructor(
    private val dailySummaryRepository: DailySummaryRepository,
    private val workoutRepository: WorkoutRepository,
    private val preferencesReader: UserPreferencesReader,
    private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
    private val patternSummaryUseCase: ComputeWorkoutPatternSummaryUseCase,
)
```

Read preferences once, get its scoring zone, and derive midnight epoch values using `LocalDate.atStartOfDay(zoneId).toInstant().toEpochMilli()`. Fetch:

- today summary by today midnight;
- yesterday summary by yesterday midnight;
- yesterday workouts using `[yesterdayMidnight, todayMidnight)`;
- pattern workouts using `[today.minusMonths(LOOKBACK_MONTHS).atStartOfDay(zone), tomorrowMidnight)`.

Use the same bounded range convention as `WorkoutDao.getWorkoutsInRange`; do not query an unbounded history.

Before building workout display metrics, load the bounded historical summaries needed by the existing 42-day calculation:

```kotlin
val historicalStart =
    yesterday
        .minusDays(ScoringConstants.CHRONIC_DAYS)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
val historicalSummaries = dailySummaryRepository.getSince(historicalStart)
```

Pass this same list to every yesterday-workout display-metric call so gained strain is deterministic and does not perform one repository query per workout.

- [ ] **Step 4: Map persisted summaries into prompt sections**

Use `LoadSourceSelector` with `preferences.strainLoadSourceMode` for readiness, TRIMP, ATL, CTL, strain ratio, and load score. Include both RAS source totals as informational context. Copy the active source name and everyday confidence from preferences/summary. Map today’s diagnostics and baseline fields directly. Map yesterday’s sleep fields from yesterday’s summary and leave the block explicitly unavailable if no summary exists.

- [ ] **Step 5: Build workout blocks and pattern data**

For each yesterday workout, call:

```kotlin
getWorkoutDisplayMetricsUseCase.execute(
    workout = workout,
    preferences = preferences,
    historicalSummaries = historicalSummaries,
)
```

Use returned `preciseTrimp`, `computedTrimp`, `gainedStrain`, `gainedStrainDisplay`, and `classification`. Map `classification.finalLoad.name` to `loadClassification` and `classification.intensity?.name` to `intensity`; do not invent a new scoring formula. Map all zone fields from `WorkoutData`.

For pattern data, call `patternSummaryUseCase.execute(patternWorkouts, today)` and map all recovery flags through `RecoveryFlagGlossary` into `RecoveryFlagPrompt` values.

- [ ] **Step 6: Run focused tests and verify pass**

Run the same focused test command. Expected: PASS.

- [ ] **Step 7: Commit the orchestration**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCase.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/airecommendation/GetDailyPromptDataUseCaseTest.kt
git commit -m "feat: assemble daily AI recommendation data"
```

### Task 6: Register the card and static prompt resources

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardIdExtensions.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`
- Modify: `feature/dashboard/src/main/res/values/strings.xml`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/preferences/CardConfigurationRepositoryTest.kt`

**Interfaces:**
- Consumes: existing defaults, `CardId` exhaustive `when` extensions, and DataStore append logic.
- Produces: `CardId.AI_RECOMMENDATION`, default visible configuration, `displayName()`/`displayNameResId` branches, and `R.string.ai_init_prompt` plus localized card labels.

- [ ] **Step 1: Add failing catalog/default tests**

Extend catalog tests to assert `AI_RECOMMENDATION` is represented and defaults contain exactly one visible entry. Extend repository tests with a stored list that omits the new card and assert the emitted list appends it at `maxPosition + 1`; call the flow/initializer twice and assert no duplicate.

```kotlin
val stored = listOf(CardConfiguration(CardId.SLEEP_SCORE, position = 4))
val result = repository.dashboardCardConfigurations().first()

assertEquals(CardId.AI_RECOMMENDATION, result.last().cardId)
assertTrue(result.last().isVisible)
assertEquals(5, result.last().position)
assertEquals(1, result.count { it.cardId == CardId.AI_RECOMMENDATION })
```

- [ ] **Step 2: Run focused tests and verify failure**

Run the existing model and app tests selected by their current class names. Expected: compilation failure for the missing enum value and its exhaustive `when` branches, plus the missing default entry.

- [ ] **Step 3: Add the enum, display-name branches, and default**

Add `AI_RECOMMENDATION` to `CardId`. Do **not** register it in `DashboardCardCatalog` — bespoke non-metric cards follow the `INSIGHTS` pattern (enum + defaults + `buildCardDataMap`, no catalog spec; `DashboardCardCatalog.spec` returns null). Add the exhaustive branches:

```kotlin
CardId.AI_RECOMMENDATION -> "AI Training Recommendation"
```

in `CardIdExtensions.displayName()` and

```kotlin
CardId.AI_RECOMMENDATION -> R.string.card_title_ai_recommendation
```

in `CardIdExtensionsUi.displayNameResId`, adding the `card_title_ai_recommendation` string in Task 6 Step 4. Add:

```kotlin
CardConfiguration(CardId.AI_RECOMMENDATION, isVisible = true, position = 17)
```

after the existing final default card, preserving current positions. The existing repository implementation will append it after any saved max position and will not duplicate it.

- [ ] **Step 4: Add localized UI strings and static setup prompt**

Add these resources to dashboard `strings.xml`:

```xml
<string name="card_title_ai_recommendation">AI Training Recommendation</string>
<string name="ai_recommendation_card_title">AI Training Recommendation</string>
<string name="ai_recommendation_card_body">Copy your Readylytics data into an AI chat app for a training recommendation.</string>
<string name="ai_recommendation_copy_setup_button">Copy Setup Prompt</string>
<string name="ai_recommendation_copy_daily_button">Copy Today&apos;s Prompt</string>
<string name="ai_recommendation_copied_snackbar">Prompt copied</string>
<string name="ai_recommendation_copy_setup_description">Copy the setup prompt</string>
<string name="ai_recommendation_copy_daily_description">Copy today&apos;s prompt</string>
<string name="ai_recommendation_copy_failed">Could not generate today&apos;s prompt</string>
```

Add the full contents of `BASE_SYSTEM_PROMPT.md` as `ai_init_prompt`, excluding the document title/status/source-note metadata and preserving the prompt contract. Add a concise XML comment immediately above it:

```xml
<!-- Source of truth: internal-docs/ai-recommendations/BASE_SYSTEM_PROMPT.md -->
```

- [ ] **Step 5: Update the catalog test and run focused tests**

Update `DashboardCardCatalogTest`: extend the `Insights is not in catalog` assertion to also assert `DashboardCardCatalog.spec(CardId.AI_RECOMMENDATION) == null`, and change `every default dashboard card except Insights has a catalog spec` to filter out both `INSIGHTS` and `AI_RECOMMENDATION`. Extend the existing `CardConfigurationRepositoryTest` append test if needed. Run the focused model/app tests. Expected: PASS, including existing-install append behavior.

- [ ] **Step 6: Commit card identity and resources**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardIdExtensions.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardIdExtensionsUi.kt core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt feature/dashboard/src/main/res/values/strings.xml core/model/src/test app/src/test
git commit -m "feat: add AI recommendation dashboard card default and labels"
```

### Task 7: Implement the Compose card and dashboard factory wiring

**Files:**
- Create: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/AiRecommendationCard.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
- Test: `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardScreenTest.kt`

**Interfaces:**
- Consumes: localized resources, `onCopySetupPrompt: () -> Unit`, `onCopyDailyPrompt: () -> Unit`, and existing `buildCardDataMap` card configuration.
- Produces: `@Composable fun AiRecommendationCard(onCopySetupPrompt: () -> Unit, onCopyDailyPrompt: () -> Unit, modifier: Modifier = Modifier)` and a registered `cardMap[CardId.AI_RECOMMENDATION]` renderer.

- [ ] **Step 1: Write UI callback tests or static assertions**

Add an Android test that renders the card with test callbacks, asserts title/body and both button labels are visible, clicks each button, and verifies the corresponding callback exactly once. If the existing dashboard harness cannot isolate the new card, add a focused Compose test for `AiRecommendationCard` using the project’s existing Material test rule.

- [ ] **Step 2: Run the focused UI test and verify failure**

Run:

```bash
./gradlew :feature:dashboard:connectedDebugAndroidTest --tests '*AiRecommendationCard*'
```

If the module does not expose a connected test task, run the repository’s existing dashboard instrumentation task. Expected: compilation failure until the composable exists.

- [ ] **Step 3: Implement the card using existing M3 patterns**

Use `OutlinedCard`, `MaterialTheme.shapes.large`, `CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))`, and `BorderStroke(MaterialTheme.dimens.borderThin, MaterialTheme.colorScheme.outlineVariant)`. Render a leading recommendation icon, title, body, and a responsive button row. Use `Icons.Default.ContentCopy` with localized content descriptions. Use `OutlinedButton` or `TextButton` rather than a custom toggle/row control. Keep all visible text in `stringResource`.

- [ ] **Step 4: Register the card in `buildCardDataMap`**

Add a direct entry:

```kotlin
cardMap[CardId.AI_RECOMMENDATION] = {
    AiRecommendationCard(
        onCopySetupPrompt = onCopySetupPrompt,
        onCopyDailyPrompt = onCopyDailyPrompt,
    )
}
```

Thread `onCopySetupPrompt` and `onCopyDailyPrompt` through `buildCardDataMap` and the `DashboardScreen` call site. Do not route this card through `INSIGHTS` or `MainNavHost` slot injection. When `isEditing` is true, buttons may remain actionable because copying is not a card reorder operation; if the existing editing UX disables actions, apply that existing convention consistently.

- [ ] **Step 5: Run focused UI tests and verify pass**

Run the dashboard instrumentation task and verify both callbacks. Expected: PASS.

- [ ] **Step 6: Commit the card UI**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/AiRecommendationCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt feature/dashboard/src/androidTest
git commit -m "feat: render AI recommendation dashboard card"
```

### Task 8: Wire asynchronous daily copying through the ViewModel

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardEvent.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `GetDailyPromptDataUseCase.execute(today)` and `DailyPromptFormatter.format(data)`.
- Produces: `DashboardEvent.RequestDailyPromptCopy`, `DashboardViewModel.dailyPromptText: StateFlow<String?>`, `DashboardViewModel.clearDailyPromptText()`, and clipboard/snackbar behavior in the route.

- [ ] **Step 1: Add failing ViewModel tests**

Add a mocked use case and tests that:

```kotlin
@Test
fun `request daily prompt emits formatted text`() = runTest {
    coEvery { getDailyPromptDataUseCase.execute(today) } returns promptData
    viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
    advanceUntilIdle()
    assertEquals(expectedPrompt, viewModel.dailyPromptText.value)
}

@Test
fun `clear daily prompt returns state to null`() = runTest {
    viewModel.clearDailyPromptText()
    assertNull(viewModel.dailyPromptText.value)
}

@Test
fun `daily prompt failure exposes error and emits no text`() = runTest {
    coEvery { getDailyPromptDataUseCase.execute(today) } throws IOException("test")
    viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
    advanceUntilIdle()
    assertNull(viewModel.dailyPromptText.value)
    assertNotNull(viewModel.errorMessage.value)
}

@Test
fun `cancellation is rethrown`() = runTest {
    coEvery { getDailyPromptDataUseCase.execute(today) } throws CancellationException("cancelled")
    assertFailsWith<CancellationException> {
        viewModel.generateDailyPrompt(today)
    }
}
```

Use the ViewModel’s injected `Clock` to make `today` deterministic. Assert the use case receives `LocalDate.now(clock)`, not the currently selected historical dashboard date, because the card explicitly copies today’s prompt.

- [ ] **Step 2: Run focused ViewModel tests and verify failure**

Run the existing feature dashboard unit-test task with the class filter. Expected: compilation failure for the missing constructor dependency/event/state or failed assertions.

- [ ] **Step 3: Add the event and ViewModel state**

Add:

```kotlin
data object RequestDailyPromptCopy : DashboardEvent
```

Inject `GetDailyPromptDataUseCase`. Add:

```kotlin
private val _dailyPromptText = MutableStateFlow<String?>(null)
val dailyPromptText: StateFlow<String?> = _dailyPromptText.asStateFlow()

fun clearDailyPromptText() {
    _dailyPromptText.value = null
}

internal suspend fun generateDailyPrompt(today: LocalDate): String =
    DailyPromptFormatter.format(getDailyPromptDataUseCase.execute(today))
```

Handle the event with `viewModelScope.launch`, call `generateDailyPrompt(LocalDate.now(clock))`, and set the result. Catch ordinary exceptions for the existing error path, log them, and leave prompt text null; rethrow `CancellationException`.

- [ ] **Step 4: Wire static setup copying in the route**

In the route composable, obtain `LocalClipboardManager.current` and add a callback that reads `stringResource(R.string.ai_init_prompt)`, calls `setText(AnnotatedString(prompt))`, and launches `snackbarHostState.showSnackbar(stringResource(R.string.ai_recommendation_copied_snackbar))`. Do not put clipboard access in the ViewModel.

- [ ] **Step 5: Wire one-shot daily copying in the route**

Collect `dailyPromptText` with `collectAsStateWithLifecycle()` and consume it in:

```kotlin
LaunchedEffect(dailyPromptText) {
    dailyPromptText?.let { prompt ->
        clipboardManager.setText(AnnotatedString(prompt))
        snackbarHostState.showSnackbar(copiedMessage)
        viewModel.clearDailyPromptText()
    }
}
```

Pass `onCopyDailyPrompt = { viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy) }` into the screen/factory. Keep the existing error snackbar effect separate so prompt success and error messages do not share state.

- [ ] **Step 6: Run focused ViewModel tests and verify pass**

Run the dashboard unit-test task. Expected: PASS, including state clearing and cancellation behavior.

- [ ] **Step 7: Commit the dashboard interaction wiring**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardEvent.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt feature/dashboard/src/test
git commit -m "feat: copy generated daily recommendation prompt"
```

### Task 9: Update dependent tests, documentation synchronization, and index

**Files:**
- Modify: affected dashboard/card catalog/preference tests identified by compilation.
- Modify: `internal-docs/DATA_FLOW.md` to document the persisted-summary/workout data path into the manual AI recommendation prompt export.
- Modify: `internal-docs/ai-recommendations/README.md` to change the status from design-only to implemented POC while explicitly preserving the no-network/manual-copy scope.

**Interfaces:**
- Consumes: all production tasks above.
- Produces: repository-wide tests that describe the new card and prompt data path, plus current codegraph indexes.

- [ ] **Step 1: Search for stale assumptions**

Run searches for exact assumptions that will change:

```bash
rg "CardId\.OXYGEN_SATURATION|DEFAULT_DASHBOARD_CARDS|dashboardCardsCount|prompt-content design only|No integration code exists" core feature app internal-docs
```

Update only assertions/comments that are invalid because of this feature. Do not change unrelated expected card behavior.

- [ ] **Step 2: Add documentation synchronization changes**

Add the new repository-to-dashboard prompt-export path to `internal-docs/DATA_FLOW.md` without duplicating scoring formulas. Update `internal-docs/ai-recommendations/README.md` from design-only to implemented POC, explicitly stating that copying to an external AI app is manual and no network/LLM integration exists. Keep `BASE_SYSTEM_PROMPT.md` and `DAILY_PROMPT_TEMPLATE.md` as the prompt-content sources of truth.

- [ ] **Step 3: Run formatting and all unit tests**

Run exactly:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
```

Expected: both commands PASS. Review the formatter diff after `ktlintFormat`; keep formatting-only changes limited to touched files.

- [ ] **Step 4: Run release lint**

Run:

```bash
./gradlew lintRelease
```

Expected: PASS with no missing resource, Compose, serialization, or dependency errors.

- [ ] **Step 5: Index newly created files**

Run:

```bash
codegraph index
```

Expected: successful indexing of all new production and test paths.

- [ ] **Step 6: Perform manual POC verification**

Run `./gradlew installDebug`, open the app, and verify:

1. A fresh/default dashboard contains the visible AI recommendation card.
2. An existing saved card configuration receives the card once at the end and it is visible.
3. “Copy Setup Prompt” pastes text matching the substantive contents of `BASE_SYSTEM_PROMPT.md`.
4. “Copy Today’s Prompt” produces no unresolved double-brace or template-loop tokens.
5. A day with workouts contains real workout and display-metric values.
6. A day without workouts contains an explicit rest-day marker.
7. A calibrating profile states limited calibration data without fabricated numeric targets.
8. Snackbar confirmation appears after each successful copy.

- [ ] **Step 7: Commit verification/doc updates**

```bash
git add internal-docs/DATA_FLOW.md internal-docs/ai-recommendations/README.md core feature app
git commit -m "test: verify AI recommendation dashboard POC"
```

## Final Review Checklist

- [ ] Every task has a focused test or explicit manual verification.
- [ ] No pure `core/scoring` file imports Android, Compose, resources, or dashboard modules.
- [ ] `DailyPromptFormatter` output contains all Sections A-H and no unresolved template tokens.
- [ ] `GetDailyPromptDataUseCase` reads persisted summaries and does not invoke score recomputation.
- [ ] The configured Training Load source drives the prompt’s readiness/load fields; RAS remains informational.
- [ ] Existing saved dashboard configurations append `AI_RECOMMENDATION` once, visibly, at the end.
- [ ] Setup and daily copy operations use the shared snackbar path.
- [ ] `CancellationException` is not swallowed.
- [ ] `./gradlew ktlintFormat`, `./gradlew testDebugUnitTest`, and `./gradlew lintRelease` pass.
- [ ] `codegraph index` completes after new files land.
