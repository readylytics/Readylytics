# History Pagination Design

## Goal

Add efficient pagination to blood-pressure history and migrate workout history to the same database-backed pagination pattern. Both tabs should use identical pagination controls and preserve their existing display behavior.

## Scope

In scope:

- Paginate blood-pressure records within the currently selected date range.
- Migrate workout history from in-memory slicing to Room `LIMIT/OFFSET` queries.
- Extract the existing workout pagination row into a shared common-UI composable.
- Add DAO, repository, ViewModel, and UI behavior coverage.

Out of scope:

- Changes to scoring formulas, ingestion, Health Connect behavior, or retention.
- Changes to the blood-pressure history card design.
- A generic paginated repository abstraction shared by unrelated record types.

## User-visible contract

Both history lists use the same behavior:

- 10 records per page.
- Newest records first.
- Previous and next outlined icon buttons.
- Page text in the form `Page X of Y`.
- Controls are hidden when there is only one page.
- The current page resets to page 1 when the selected date range or selected date changes.
- If records change and the current page becomes invalid, it is clamped to the last valid page.

Blood-pressure history is limited to records in the selected chart range. Workout history retains its current selected-range semantics.

## Architecture

### Shared UI

Extract the pagination row from `WorkoutListSection` into a common `PaginationControls` composable. It accepts the current page, total pages, and previous/next callbacks, and owns the shared layout, Material 3 controls, spacing, and enabled states. Workout and blood-pressure sections render this composable below their respective lists.

The composable must not depend on either feature module. User-facing labels and content descriptions remain localized through Android resources; no strings are hardcoded.

### Database and repositories

Add range-scoped paged retrieval and count operations to both repositories and their DAOs:

- Blood pressure: `getByDateRangePaged(fromMs, toMs, limit, offset)` and `countByDateRange(fromMs, toMs)`.
- Workouts: equivalent range-paged retrieval and count operations.

Queries use half-open ranges (`timestamp/startTime >= fromMs` and `< toMs`) and deterministic newest-first ordering. The primary sort is the record timestamp; `id` is the stable secondary sort key so equal timestamps cannot move between pages.

The existing repository/domain boundaries remain intact. No common `Page<T>` repository abstraction is introduced for this change.

### Blood-pressure ViewModel

The ViewModel includes current page and total page count in `BloodPressureDetailUiState`. Its selected-range pipeline obtains only the requested page from the repository and maps those records into `BloodPressureHistoryItem` values. Chart calculations continue to use all records in the selected range, because pagination applies only to the history list.

Changing range or selected date resets page 1. The total count determines `totalPages`, with an empty result represented as one logical page and an empty list. Page changes trigger the same existing state pipeline and loading behavior.

### Workout ViewModel

Replace in-memory history slicing with a repository query for the selected page and a count for the selected display range. Per-workout display metrics and heart-rate sample loading apply only to the page being displayed.

The ViewModel must preserve page-independent behavior. In particular, the workout-only daily strain-increase calculation must still use the required workouts for the selected day rather than only whichever rows happen to be visible on the current history page. This data may be loaded separately from the paged history query.

Existing page reset behavior for range/date changes remains, and the repository query must use the same newest-first ordering as the current UI.

## Data flow

1. The selected range/date and current page produce a display window and offset.
2. The feature ViewModel requests the page and total count from its repository.
3. The repository delegates to Room with the display window, limit `10`, and calculated offset.
4. The ViewModel maps the returned records to feature display items.
5. The common pagination controls expose navigation callbacks.
6. Navigation updates the current page, causing only the requested page to reload.

For workouts, the scoring/chart pipeline remains independent of history pagination. The additional current-day query exists only to preserve the daily strain-increase calculation.

## Consistency and error behavior

- Page loads use each feature's existing state pipeline; this change introduces no new full-screen loading behavior.
- A changing record count cannot leave the UI on an invalid page; the ViewModel clamps the page before emitting state.
- Empty ranges show no history rows and no pagination controls.
- Cancellation behavior remains cooperative; no new coroutine layer should swallow `CancellationException`.
- No ingestion or scoring behavior changes are permitted.

## Testing

Add or update tests for:

- DAO half-open boundary handling, newest-first deterministic ordering, counts, offsets, empty ranges, and final partial pages.
- Blood-pressure ViewModel page 1/page 2 behavior, range/date page reset, clamping, and empty history.
- Workout ViewModel pagination after migration, including display-metric mapping and preservation of page-independent daily strain-increase behavior.
- Shared pagination controls: hidden for one page, enabled/disabled navigation boundaries, and callback dispatch.
- Existing repository tests updated for the new paged methods.

## Documentation and validation

This change does not alter ingestion, schema, scoring, or formulas. Review `internal-docs/DATA_FLOW.md`; update it in the same change if its repository/query data-flow description needs to mention the paged history reads.

Before completion, run the project’s mandatory formatting and unit-test commands, followed by release lint after coding work is complete.
