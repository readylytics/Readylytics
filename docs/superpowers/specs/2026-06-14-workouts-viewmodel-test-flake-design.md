# Design Specification: Test Stability — `WorkoutsViewModelTest` Flake (Phase 7)

- **Date:** 2026-06-14
- **Status:** Draft (Pending Review)
- **Phase:** 7 (Test Stability)
- **Reference:** `SCORING_ENGINE_REMEDIATION_PLAN.md` (§14)

---

## 1. Problem Statement & Context

During the execution of the full unit test suite (`testDebugUnitTest`), `WorkoutsViewModelTest."initial page is 1"` and potentially other tests intermittently fail with:
```
kotlinx.coroutines.test.UncaughtExceptionsBeforeTest: There were uncaught exceptions
before the test started.
```

### Root Cause
In `setUp()`, the tests build a real `SelectedDateRepository` passing a `CoroutineScope(testDispatcher)`. 
In `SelectedDateRepository.init`:
```kotlin
init {
    appScope.launch {
        earliestDate.collect { ... }
    }
}
```
This coroutine runs eagerly on `testDispatcher`'s `TestCoroutineScheduler`. However, individual tests use the `runTest { ... }` block, which creates its own separate `TestCoroutineScheduler`. The test body's scheduler does not drive the repository's eager collector coroutine. Consequently:
1. `tearDown()` cancels `appScope` and calls `testDispatcher.scheduler.advanceUntilIdle()`.
2. A `CancellationException` or related asynchronous exception gets thrown on the dispatcher.
3. This exception is reported against the shared uncaught-exception handler of the dispatcher.
4. Because the dispatcher is shared/reused, the uncaught exception surfaces at the beginning of the *next* test run, causing it to flake out with `UncaughtExceptionsBeforeTest`.

---

## 2. Selected Approach: Mock SelectedDateRepository via MockK

We will mock `SelectedDateRepository` in the ViewModel tests rather than constructing the real class. This completely eliminates the eager coroutine collection side effects.

### Mock Design
For all tests using `SelectedDateRepository`, we will mock the behavior using MockK. For tests where date navigation or updates are triggered, we will back the properties with a `MutableStateFlow` to simulate real updates.

```kotlin
// Setup in tests
private val selectedDateFlow = MutableStateFlow(LocalDate.now())
private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

selectedDateRepo = mockk {
    every { selectedDate } returns selectedDateFlow
    every { earliestDate } returns earliestDateFlow
    coEvery { updateSelectedDate(any()) } answers {
        selectedDateFlow.value = firstArg()
    }
    coEvery { selectPreviousDay() } answers {
        selectedDateFlow.value = selectedDateFlow.value.minusDays(1)
    }
    coEvery { selectNextDay() } answers {
        selectedDateFlow.value = selectedDateFlow.value.plusDays(1)
    }
}
```

---

## 3. Impacted Files

The following test files instantiate `SelectedDateRepository` directly and will be updated:
1. `app/src/test/kotlin/app/readylytics/health/ui/workouts/WorkoutsViewModelTest.kt`
2. `app/src/test/kotlin/app/readylytics/health/ui/bloodpressure/BloodPressureDetailViewModelTest.kt`
3. `app/src/test/kotlin/app/readylytics/health/ui/bodyfat/BodyFatDetailViewModelTest.kt`
4. `app/src/test/kotlin/app/readylytics/health/ui/heartrate/HeartRateDetailViewModelTest.kt`
5. `app/src/test/kotlin/app/readylytics/health/ui/steps/StepDetailViewModelTest.kt`
6. `app/src/test/kotlin/app/readylytics/health/ui/weight/WeightDetailViewModelTest.kt`

> [!NOTE]
> `SelectedDateRepositoryTest.kt` is the test class for the repository itself. It *must* keep instantiating the real repository to test its functionality correctly. It already manages the test scope and dispatcher explicitly.

---

## 4. Implementation Steps

For each of the 6 ViewModel unit test files:
1. **Remove** the `appScope` (or `testScope`) construction.
2. **Remove** the call to `appScope.cancel()` in `tearDown()`.
3. **Replace** the instantiation of `SelectedDateRepository(...)` with a MockK mock.
4. **Define** backing flows `selectedDateFlow` and `earliestDateFlow` in the test class.
5. **Configure** mock calls to update/navigate the date flow correctly.

---

## 5. Verification Plan

1. **Pre-commit checks:** Run `./gradlew ktlintFormat` to keep formatting aligned.
2. **Unit Tests:** Execute `./gradlew testDebugUnitTest` to verify all tests pass.
3. **Flake/Stability validation:** Run the suite multiple times to ensure the flakiness is fully eliminated.
