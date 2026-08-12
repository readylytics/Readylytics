# History Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Room-backed `LIMIT/OFFSET` pagination to blood-pressure and workout history while sharing one pagination control composable.

**Architecture:** Keep pagination orchestration in each feature ViewModel and add feature-specific paged/count methods to each DAO and repository. Extract only the visual/navigation row into `core:ui`; do not introduce a generic paginated repository abstraction. Workout chart/scoring data remains separate from paged history data, with a page-independent current-day query preserving the existing daily strain-increase calculation.

**Tech Stack:** Kotlin, Room, Coroutines/Flow, Jetpack Compose Material 3, Hilt, JUnit, MockK, Compose UI tests.

## Global Constraints

- Room DB remains the single source of truth; UI must not access Health Connect directly.
- All user-facing strings must be in resource XML and referenced with `stringResource`.
- Preserve the two-flow sync contract and do not change ingestion or scoring formulas.
- Use half-open time windows: `fromMs <= timestamp < toMs`.
- Use deterministic newest-first ordering: timestamp descending, then stable record `id` descending.
- Keep files at or below the project’s 400-line target and hard 800-line limit.
- Mandatory verification is `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, followed by `./gradlew lintRelease` after coding work.
- Run `codegraph index` after creating a new file and `codegraph sync` after structural movement.

---

### Task 1: Add the shared pagination controls

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/PaginationControls.kt`
- Modify: `core/ui/src/main/res/values/strings.xml`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutListSection.kt`
- Test: `core/ui/src/androidTest/kotlin/app/readylytics/health/core/ui/components/PaginationControlsTest.kt`

**Interfaces:**
- Consumes: `currentPage: Int`, `totalPages: Int`, `onPreviousPage: () -> Unit`, and `onNextPage: () -> Unit`.
- Produces: `@Composable fun PaginationControls(...)`, hidden when `totalPages <= 1`, with navigation enabled only at valid page boundaries.

- [ ] **Step 1: Write the failing Compose tests**

Test the public behavior with the existing Compose test conventions:

```kotlin
@Test
fun `controls are hidden for a single page`() {
    composeRule.setContent {
        PaginationControls(
            currentPage = 1,
            totalPages = 1,
            onPreviousPage = {},
            onNextPage = {},
        )
    }

    composeRule.onNodeWithText("Page 1 of 1").assertDoesNotExist()
}

@Test
fun `previous and next buttons follow page boundaries`() {
    composeRule.setContent {
        PaginationControls(
            currentPage = 2,
            totalPages = 3,
            onPreviousPage = {},
            onNextPage = {},
        )
    }

    composeRule.onNodeWithContentDescription("Previous page").assertIsEnabled()
    composeRule.onNodeWithContentDescription("Next page").assertIsEnabled()
}

@Test
fun `callbacks are dispatched by buttons`() {
    var previous = 0
    var next = 0
    composeRule.setContent {
        PaginationControls(
            currentPage = 2,
            totalPages = 3,
            onPreviousPage = { previous++ },
            onNextPage = { next++ },
        )
    }

    composeRule.onNodeWithContentDescription("Previous page").performClick()
    composeRule.onNodeWithContentDescription("Next page").performClick()
    assertEquals(1, previous)
    assertEquals(1, next)
}
```

Use resource-backed text/content descriptions in production; the test strings above represent the localized default values.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :core:ui:connectedDebugAndroidTest --tests '*PaginationControlsTest'
```

Expected: FAIL because `PaginationControls` and its resources do not exist yet.

- [ ] **Step 3: Implement the shared composable**

Move the workout row’s exact Material 3 layout into `PaginationControls.kt`, using the existing spacing extensions and mirrored arrow icons. Add shared resources such as:

```xml
<string name="pagination_page_info">Page %1$d of %2$d</string>
<string name="pagination_button_previous">Previous page</string>
<string name="pagination_button_next">Next page</string>
```

Replace the inline row in `WorkoutListSection` with `PaginationControls` and remove the now-unused pagination imports. Keep workout-specific history strings such as BPM formatting in the workouts module.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same focused Compose test command. Expected: PASS.

- [ ] **Step 5: Commit the shared UI change**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/PaginationControls.kt \
  core/ui/src/main/res/values/strings.xml \
  core/ui/src/androidTest/kotlin/app/readylytics/health/core/ui/components/PaginationControlsTest.kt \
  feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutListSection.kt
git commit -m "refactor: share history pagination controls"
```

### Task 2: Add blood-pressure DAO and repository pagination

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/BloodPressureRecordDao.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/repository/BloodPressureRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/BloodPressureRepositoryImpl.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/data/repository/BloodPressureRepositoryImplTest.kt` (create)
- Test: `app/src/test/kotlin/app/readylytics/health/data/local/dao/OffsetPaginationTest.kt` (create)

**Interfaces:**
- Consumes: a selected-range half-open window and `limit`/`offset`.
- Produces:

```kotlin
suspend fun getByDateRangePaged(
    fromMs: Long,
    toMs: Long,
    limit: Int,
    offset: Int,
): List<BloodPressureRecord>

suspend fun countByDateRange(fromMs: Long, toMs: Long): Int
```

- [ ] **Step 1: Add failing repository delegation tests**

Use the existing DAO-proxy/fake style in repository tests. Configure paged entities and a count, then assert that the repository maps the entities and returns the count. Include an entity exactly at `toMs` and assert the DAO contract excludes it in the Room test.

- [ ] **Step 2: Run the focused database tests and verify failure**

```bash
./gradlew :core:database:testDebugUnitTest --tests '*BloodPressureRepositoryImplTest' --tests '*BloodPressureRecordDaoTest'
```

Expected: FAIL because the new DAO/repository methods are missing.

- [ ] **Step 3: Implement the Room and repository methods**

Add DAO queries equivalent to:

```kotlin
@Query("""
    SELECT * FROM blood_pressure_records
    WHERE timestampMs >= :fromMs AND timestampMs < :toMs
    ORDER BY timestampMs DESC, id DESC
    LIMIT :limit OFFSET :offset
""")
suspend fun getPagedByTimeRange(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<BloodPressureRecordEntity>

@Query("""
    SELECT COUNT(*) FROM blood_pressure_records
    WHERE timestampMs >= :fromMs AND timestampMs < :toMs
""")
suspend fun countByTimeRange(fromMs: Long, toMs: Long): Int
```

Map entities with `BloodPressureRecordMapper` in `BloodPressureRepositoryImpl`. Keep existing chart-range methods intact for chart calculations.

- [ ] **Step 4: Run focused tests and verify success**

Run the focused command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit the data-layer change**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/data/local/dao/BloodPressureRecordDao.kt \
  core/model/src/main/kotlin/app/readylytics/health/domain/repository/BloodPressureRepository.kt \
  core/database/src/main/kotlin/app/readylytics/health/data/repository/BloodPressureRepositoryImpl.kt \
  core/database/src/test/kotlin/app/readylytics/health/data/repository/BloodPressureRepositoryImplTest.kt \
  core/database/src/test/kotlin/app/readylytics/health/data/local/dao/BloodPressureRecordDaoTest.kt
git commit -m "feat: add paged blood pressure queries"
```

### Task 3: Paginate blood-pressure history in the ViewModel and screen

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModel.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureHistorySection.kt`
- Modify: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `BloodPressureRepository.getByDateRangePaged(...)` and `countByDateRange(...)`, plus `PaginationControls`.
- Produces: `historyItems`, `currentPage`, and `totalPages` in `BloodPressureDetailUiState`; `onPreviousPage`, `onNextPage`, and range-reset behavior in the ViewModel.

- [ ] **Step 1: Add failing ViewModel tests**

Add tests with more than 10 records that assert:

```kotlin
assertEquals(10, state.historyItems.size)
assertEquals(3, state.totalPages)
assertEquals(1, state.currentPage)

viewModel.onNextPage()
val pageTwo = viewModel.uiState.first { it.currentPage == 2 }
assertEquals(11_000L, pageTwo.historyItems.first().timestampMs)
```

Also cover page reset after `onRangeSelected`, page reset after selected-date changes, last partial page, an empty range, and clamping when a later emission reduces the count.

- [ ] **Step 2: Run the focused ViewModel tests and verify failure**

```bash
./gradlew :feature:vitals:testDebugUnitTest --tests '*BloodPressureDetailViewModelTest'
```

Expected: FAIL because the state and page methods do not exist and the ViewModel still loads the full history list.

- [ ] **Step 3: Implement paged state and queries**

Add to `BloodPressureDetailUiState`:

```kotlin
val currentPage: Int = 1
val totalPages: Int = 1
```

Combine selected range, selected date, and a private `_currentPage = MutableStateFlow(1)`. Use the selected range’s start/end window for both the chart’s existing full-range query and the paged history query. Compute:

```kotlin
val totalPages = maxOf(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE)
val clampedPage = page.coerceIn(1, totalPages)
val offset = (clampedPage - 1) * PAGE_SIZE
```

Map only the returned page into `historyItems`, sort only through the SQL ordering, and add page navigation methods that enforce the same bounds as the UI. Reset `_currentPage` in `onRangeSelected` and whenever the selected date changes.

- [ ] **Step 4: Render the shared controls**

Pass `currentPage`, `totalPages`, and callbacks to `BloodPressureHistorySection`, then render `PaginationControls` below the cards.

- [ ] **Step 5: Run focused tests and verify success**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 6: Commit blood-pressure pagination**

```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModel.kt \
  feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureHistorySection.kt \
  feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModelTest.kt
git commit -m "feat: paginate blood pressure history"
```

### Task 4: Add workout DAO and repository pagination

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`
- Modify: `core/database/src/test/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImplTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/local/dao/OffsetPaginationTest.kt`

**Interfaces:**
- Produces:

```kotlin
suspend fun getInRangePaged(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<WorkoutData>
suspend fun countInRange(fromMs: Long, toMs: Long): Int
```

- [ ] **Step 1: Add failing repository and DAO tests**

Extend `OffsetPaginationTest` with workout fixtures and verify half-open boundaries, newest-first order, equal-timestamp `id` tie-breaking, offset behavior, count results, and a final partial page. Ensure the tests distinguish descending order from the existing stale `getPaged` ascending query.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
./gradlew :core:database:testDebugUnitTest --tests '*WorkoutRepositoryImplTest' && \
  ./gradlew :app:testDebugUnitTest --tests '*OffsetPaginationTest'
```

Expected: FAIL until the range-paged API and corrected SQL ordering are implemented.

- [ ] **Step 3: Implement the range-paged workout API**

Add a half-open DAO query:

```kotlin
@Query("""
    SELECT * FROM workout_records
    WHERE startTime >= :fromMs AND startTime < :toMs
    ORDER BY startTime DESC, id DESC
    LIMIT :limit OFFSET :offset
""")
suspend fun getPagedInRange(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<WorkoutRecordEntity>
```

Add the matching count query and map results in `WorkoutRepositoryImpl`. Leave existing scoring/ingestion queries unchanged. Remove or stop using the old unbounded `getPaged` only after all callers/tests have migrated.

- [ ] **Step 4: Run focused tests and verify success**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit workout data-layer pagination**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt \
  core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt \
  core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt \
  core/database/src/test/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImplTest.kt
git commit -m "feat: add paged workout queries"
```

### Task 5: Migrate the workout ViewModel to database pagination

**Files:**
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsScreen.kt` only if callback wiring currently assumes an in-memory list

**Interfaces:**
- Consumes: `WorkoutRepository.getInRangePaged(...)`, `countInRange(...)`, and the shared `PaginationControls` already rendered by `WorkoutListSection`.
- Preserves: `WorkoutsUiState.currentPage`, `totalPages`, `recentWorkouts`, display-metric mapping, and daily strain-increase semantics.

- [ ] **Step 1: Add failing migration tests**

Update the test repository mock and add assertions that:

- a 25-record selected range produces three pages of 10/10/5;
- page 2 requests offset 10 and exposes only that page;
- changing range/date resets page 1;
- shrinking the repository count clamps the page;
- page-specific HR sample loading and display metrics run only for visible page rows;
- workout-only daily strain increase remains based on all required selected-day workouts, not page rows.

Use MockK verification for the exact paged calls and preserve existing assertions for classifications, TRIMP, and display strings.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
./gradlew :feature:workouts:testDebugUnitTest --tests '*WorkoutsViewModelTest'
```

Expected: FAIL because the ViewModel still observes and slices the full workout list.

- [ ] **Step 3: Replace the unbounded history dependency**

Keep the full daily-summary flows needed for charts and scoring. Replace `workoutRepository.observeSince(fetchFromMs)` as the history source with a page/count load scoped to `[displayFromMs, selectedDayEndMs)`, using:

```kotlin
val pageSize = 10
val totalItems = workoutRepository.countInRange(displayFromMs, selectedDayEndMs)
val totalPages = maxOf(1, (totalItems + pageSize - 1) / pageSize)
val clampedPage = page.coerceIn(1, totalPages)
val pageWorkouts = workoutRepository.getInRangePaged(
    displayFromMs,
    selectedDayEndMs,
    pageSize,
    (clampedPage - 1) * pageSize,
)
```

Map HR samples and `WorkoutDisplayItem` only for `pageWorkouts`. For `LoadSourceMode.WORKOUT_ONLY`, separately obtain and process the selected-day workouts required by `calculateDailyStrainIncrease`; do not derive that value from `recentItems` after pagination. Keep the `EVERYDAY_HEART_RATE` branch unchanged.

- [ ] **Step 4: Preserve reset and navigation behavior**

Keep `_currentPage` reset in `onRangeSelected`, `onDateSelected`, `onPreviousDay`, and `onNextDay`. Ensure emitted `currentPage` is the clamped page and that `onNextPage` compares against the latest `uiState.totalPages`.

- [ ] **Step 5: Run focused tests and verify success**

Run the command from Step 2. Expected: PASS, including the existing sync-toggle test; it must no longer expect `observeSince` to be the history source.

- [ ] **Step 6: Commit the workout ViewModel migration**

```bash
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt \
  feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt \
  feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsScreen.kt
git commit -m "perf: paginate workout history in database"
```

### Task 6: Update documentation and run full verification

**Files:**
- Review: `internal-docs/DATA_FLOW.md`
- Modify: `internal-docs/DATA_FLOW.md` only if its repository/data-flow description omits the new paged history reads
- Create/index any new test or source files introduced by earlier tasks

**Interfaces:**
- Consumes: completed shared UI, DAO, repository, and ViewModel changes.
- Produces: documentation synchronized with the implemented data flow and a verified branch.

- [ ] **Step 1: Review documentation impact**

Confirm that `internal-docs/DATA_FLOW.md` still accurately describes Health Connect → Room → repository → ViewModel → UI. If it names unbounded history reads or omits the history pagination boundary, update only the affected section and keep scoring formulas out of the document.

- [ ] **Step 2: Index new files**

Run:

```bash
codegraph index
```

If a file was moved structurally rather than merely extracted, run `codegraph sync` after the movement.

- [ ] **Step 3: Run formatting and unit tests**

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
```

Expected: both commands complete successfully with all existing and new tests passing.

- [ ] **Step 4: Run release lint**

```bash
./gradlew lintRelease
```

Expected: no new lint errors or resource issues.

- [ ] **Step 5: Inspect the final diff**

```bash
git diff --check
git status --short
```

Confirm no unrelated files changed, no hardcoded pagination strings were introduced, no Health Connect/UI boundary was crossed, and no scoring formulas were modified.

- [ ] **Step 6: Commit documentation and verification updates**

```bash
git add internal-docs/DATA_FLOW.md
git commit -m "docs: document paged history reads"
```

If the documentation review finds no required change, do not create an empty commit; report that the existing documentation remains accurate.
