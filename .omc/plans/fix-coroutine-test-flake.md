# Follow-up: Fix `UncaughtExceptionsBeforeTest` flake in testDebugUnitTest

## Issue summary
CI's `validate-and-build` job intermittently fails `./gradlew testDebugUnitTest`
with `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`, hitting a different,
unrelated test class on each run (observed: `WorkoutsViewModelTest` x2,
`StepDetailViewModelTest` x1 — always 1 of ~1378 tests).

**Root cause**: `WorkoutsViewModel.uiState` wraps its pipeline in
`.flowOn(Dispatchers.Default)` (line 334) and uses `.flowOn(Dispatchers.IO)`
for a sub-flow (line 159). These are real background thread pools, not
controlled by the tests' `StandardTestDispatcher`/`TestCoroutineScheduler`.
`testScheduler.advanceUntilIdle()` does not wait for work on these real
dispatchers. When `tearDown()` cancels `viewModelScope` while a
`Dispatchers.Default`/`IO` thread is mid-execution (calling MockK stubs that
the next test's `@Before` is concurrently recreating), a real (non-cancellation)
exception can be thrown with no `CoroutineExceptionHandler` in context. This
becomes a JVM-wide uncaught exception that kotlinx-coroutines-test attributes
to whichever test calls `runTest` next in that fork — explaining the
"different victim each run" pattern.

Same `.flowOn(Dispatchers.Default/IO)` pattern (latent risk, not yet observed
failing) exists in:
- `SleepViewModel.kt`
- `VitalsViewModel.kt`
- `DashboardViewModel.kt`
- `LocalBackupViewModel.kt`

## Proposed fix
Inject `CoroutineDispatcher`s via Hilt qualifiers (`@DefaultDispatcher`,
`@IoDispatcher`) instead of referencing `Dispatchers.Default`/`Dispatchers.IO`
directly in ViewModels. Provide real dispatchers in the app module and
`StandardTestDispatcher`/`UnconfinedTestDispatcher` (the test's existing
`testDispatcher`) in unit tests, so all coroutine work is driven by the
virtual test scheduler.

## Scope
1. Add `@DefaultDispatcher`/`@IoDispatcher` qualifier annotations + Hilt
   provider module (likely `di/DispatchersModule.kt`).
2. Update constructors of: `WorkoutsViewModel`, `SleepViewModel`,
   `VitalsViewModel`, `DashboardViewModel`, `LocalBackupViewModel` to take
   injected dispatchers, replacing `Dispatchers.Default`/`Dispatchers.IO` in
   `.flowOn(...)` calls.
3. Update the corresponding `*ViewModelTest` files to pass `testDispatcher`
   for the new constructor params.
4. Re-run `./gradlew testDebugUnitTest` repeatedly (or with increased
   `--rerun-tasks`) to confirm the flake no longer reproduces.

## Out of scope
- No changes to scoring math/formulas (`domain/scoring/**`).
- No changes to production threading behavior beyond making the dispatcher
  swappable (default providers should still map to `Dispatchers.Default`/`IO`).
