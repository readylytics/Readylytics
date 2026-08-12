# Task 3: Paginate blood-pressure history

## Overview
Successfully paginated the blood pressure history in the UI and view model. We updated `BloodPressureDetailViewModel` to calculate pages and load data using the new database queries (`countByDateRange`, `getByDateRangePaged`) introduced in Task 2. The UI (`BloodPressureHistorySection` and `BloodPressureDetailScreen`) was modified to use the `PaginationControls` component built in Task 1.

## Changes Made
- **ViewModel state & logic**: Added `currentPage` and `totalPages` to `BloodPressureDetailUiState`. Modified `BloodPressureDetailViewModel` to compute pagination state internally based on `currentPageFlow`, replacing direct full range fetches with paginated fetching using the repository. Range updates and date updates seamlessly reset the page back to 1.
- **UI Update**: `BloodPressureHistorySection` now renders `PaginationControls` using the pagination state exposed by the state flow, delegating onNextPage and onPreviousPage events via the `BloodPressureDetailScreen`.
- **Testing framework fix**: Changed the way we react to `selectedDate` changes inside the ViewModel to eliminate long-running, non-terminating coroutines that previously caused the JUnit tests to fail with `UncompletedCoroutinesError`.
- **Unit Tests**: Wrote comprehensive tests for the pagination behaviors in `BloodPressureDetailViewModelTest`, including bounds checking (clamping), partial page fetching, and page resetting on condition changes.

## Verification
- **Compilation**: Verified via `./gradlew kspDebugKotlin compileDebugKotlin`.
- **Testing**: Run `./gradlew :feature:vitals:testDebugUnitTest --tests '*BloodPressureDetailViewModelTest'`. All 22 tests pass. 
- **Linter**: Verified code formatting using `./gradlew ktlintFormat`.

Task 3 is complete and ready for the final integration.
