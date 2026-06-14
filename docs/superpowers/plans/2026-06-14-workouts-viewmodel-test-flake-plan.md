# Implementation Plan: Test Stability — `WorkoutsViewModelTest` Flake (Phase 7)

- **Date:** 2026-06-14
- **Phase:** 7 (Test Stability)
- **Status:** Proposed
- **Design Reference:** `docs/superpowers/specs/2026-06-14-workouts-viewmodel-test-flake-design.md`

This plan details the step-by-step changes to mock `SelectedDateRepository` across 6 ViewModel unit tests, resolving coroutine scheduler leaks and test flakiness.

---

## Pre-Commit Verification (Baseline)
Run the unit test suite before making any changes to verify the baseline:
```bash
./gradlew testDebugUnitTest
```

---

## Step 1: Update `WorkoutsViewModelTest`

Modify `app/src/test/kotlin/app/readylytics/health/ui/workouts/WorkoutsViewModelTest.kt`:
1. Remove `appScope` declaration, initialization, and cleanup.
2. Replace real `SelectedDateRepository` construction with a MockK mock.
3. Hook up `selectedDateFlow` and `earliestDateFlow` to the mock's properties and update methods.

### Verification
Run `WorkoutsViewModelTest` specifically to verify correctness:
```bash
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.workouts.WorkoutsViewModelTest"
```

---

## Step 2: Update Detail ViewModel Tests

Update the remaining 5 ViewModel unit test files using the same mocking pattern:
- `BloodPressureDetailViewModelTest.kt`
- `BodyFatDetailViewModelTest.kt`
- `HeartRateDetailViewModelTest.kt`
- `StepDetailViewModelTest.kt`
- `WeightDetailViewModelTest.kt`

For each file:
1. Declare:
   ```kotlin
   private val selectedDateFlow = MutableStateFlow(LocalDate.now())
   private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
   ```
2. Setup the mock `SelectedDateRepository` in `setUp()`:
   ```kotlin
   selectedDateRepo = mockk {
       every { selectedDate } returns selectedDateFlow
       every { earliestDate } returns earliestDateFlow
       coEvery { updateSelectedDate(any()) } answers {
           selectedDateFlow.value = firstArg()
       }
   }
   ```
3. Remove unused `appScope` or `testScope` references and `cancel()` calls in `setUp()` and `tearDown()`.

### Verification
Run each modified test specifically to verify they all pass:
```bash
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.bloodpressure.BloodPressureDetailViewModelTest"
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.bodyfat.BodyFatDetailViewModelTest"
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.heartrate.HeartRateDetailViewModelTest"
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.steps.StepDetailViewModelTest"
./gradlew testDebugUnitTest --tests "app.readylytics.health.ui.weight.WeightDetailViewModelTest"
```

---

## Step 3: Clean up and Format

1. Format the codebase:
   ```bash
   ./gradlew ktlintFormat
   ```
2. Run the entire test suite 10 times consecutively (via a simple loop or repeated execution) to verify that the flakiness is fully resolved:
   ```bash
   ./gradlew testDebugUnitTest
   ```
