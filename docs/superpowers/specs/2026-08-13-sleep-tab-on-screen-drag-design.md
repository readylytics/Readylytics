# Sleep Tab On-Screen Drag-and-Drop Design Specification

**Date:** 2026-08-13
**Status:** Proposed
**Author:** Readylytics Team

## 1. Overview & Objectives

The Sleep tab customization feature (docs/superpowers/specs/2026-08-13-sleep-tab-customization-design.md) currently reorders layout items only through up/down arrow buttons inside the manage bottom sheet. This spec extends it to match the Vitals tab (PR #211): **edit mode enables on-screen drag-and-drop** for all three Sleep sections.

Users will be able to, in edit mode:

1. Long-press a drag handle and drag **top cards** (Sleep Score, Sleep Duration Gauge, Sleep Architecture Bar, Hypnogram Timeline, Sleep HR Chart) to reorder them; drop onto a delete zone to hide.
2. Drag **metric grid cards** (Circadian Consistency, Sleep Efficiency, Deep Sleep %, REM Sleep %, Nap Duration, Nap Count) to reorder/hide.
3. Drag the **Sleep Duration & Schedule trend chart** to a hide drop zone (single chart, so reorder is a no-op but the interaction stays consistent).
4. Reorder is drag-only — the manage bottom sheet's up/down arrow controls are removed for parity with Vitals.

## 2. Approach: Generalize `core/ui` Reorderable Components

`DragController<T>` is already fully generic. `ReorderableCardGrid` and `ReorderableChartList` are hardwired to dashboard/vitals domain types. Extract the type-agnostic mechanics into two generic composables and make the existing components thin typed wrappers.

### 2.1 `ReorderableItem` interface (core/model)

New file `core/model/src/main/kotlin/app/readylytics/health/domain/layout/ReorderableItem.kt`:

```kotlin
interface ReorderableItem<Id> {
    val id: Id
    val isVisible: Boolean
    val position: Int
}
```

Implemented as an override getter on these configs (all already carry `isVisible` + `position`; only the id accessor is new):

- `CardConfiguration` (`id = cardId`)
- `VitalsChartConfiguration` (`id = chartId`)
- `SleepTopCardConfiguration` (`id = cardId`)
- `SleepChartConfiguration` (`id = chartId`)
- `SleepMetricCardConfiguration` (`id = cardId`)

Pure accessor — no serialization or proto impact.

### 2.2 Generic composables (core/ui)

New `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGrid.kt` and `ReorderableList.kt`, extracted from the existing `ReorderableCardGrid`/`ReorderableChartList` mechanics (slot bounds in root-local space, handle-bounds gated drag START, root-Column `pointerInput(Unit)`, drag visuals, delete/hide drop zone).

- `ReorderableGrid<Id : Any, Config : ReorderableItem<Id>>`
  - Params: `items: List<Config>`, `dataMap: Map<Id, @Composable (Config) -> Unit>`, `isEditing: Boolean`, `onItemReorder: (List<Config>) -> Unit`, `onItemDropToRemove: (Id) -> Unit`, `fullWidthIds: Set<Id>`, `fixedHeightIds: Set<Id>`, `controller: DragController<Id>? = null`, `modifier`.
  - `fixedHeightIds` cards render inside a `MaterialTheme.dimens.cardHeight` box (replaces the dashboard-only `CardId.SLEEP_SCORE`/`READINESS` special case).
  - Paired-row layout: consecutive non-`fullWidthIds` cards pair into a row; a lone one renders half-width with a spacer. This reproduces the current Sleep gauge-pairing while remaining order-driven.
  - Delete drop zone shown only when `isEditing` (remove semantic, like Vitals cards).
- `ReorderableList<Id : Any, Config : ReorderableItem<Id>>`
  - Params: `items`, `dataMap`, `isEditing`, `onItemReorder`, `onItemHide: (Id) -> Unit`, `controller: DragController<Id>? = null`, `modifier`.
  - Single full-width column; hide drop zone (VisibilityOff) shown only when `isEditing`.

### 2.3 Thin typed wrappers (unchanged public API)

- `ReorderableCardGrid` → delegates to `ReorderableGrid` with `CardId`/`CardConfiguration`, `FULL_WIDTH_CARDS`, `fixedHeightIds = {SLEEP_SCORE, READINESS}`. Signature unchanged → `DashboardScreen`, `VitalsScreen`, `VitalsTrendSection` are untouched.
- `ReorderableChartList` → delegates to `ReorderableList` with `VitalsChartId`/`VitalsChartConfiguration`. Signature unchanged.

## 3. Sleep Screen Wiring

`SleepScreen.kt`:

- **Top cards:** replace the `while`-loop gauge logic and inline `RenderTopCard` with `ReorderableGrid` over `SleepTopCardConfiguration`. `fullWidthIds = {SLEEP_BREAKDOWN_BAR, SLEEP_STAGES_TIMELINE, SLEEP_HR_CHART}`, `fixedHeightIds = {SLEEP_SCORE, SLEEP_DURATION_GAUGE}`, `isEditing = uiState.isManagingSleepTopCards`, `onItemReorder = onReorderSleepTopCards`, `onItemDropToRemove = onToggleSleepTopCardVisibility(id, false)`. Data map reuses the existing `RenderTopCard` bodies (including loading skeletons), keyed by `SleepTopCardId`. Since the map value type is `@Composable (SleepTopCardConfiguration) -> Unit`, the render bodies must capture `uiState` and `singleSessionVisual` at the call site (the map is `remember`ed per current screen state).
- **Metric grid:** replace `MetricsGrid`/`MetricsGridSkeleton` chunked rows with `ReorderableGrid` over `SleepMetricCardConfiguration`. No full/fixed-width sets, `isEditing = uiState.isManagingSleepMetricCards`, `onItemReorder = onReorderSleepMetricCards`, `onItemDropToRemove = onToggleSleepMetricCardVisibility(id, false)`. Skeleton stays for `isLoading`.
- **Trend chart:** wrap the `SleepTrendCard` block (and its skeleton) in `ReorderableList` over `SleepChartConfiguration`. `isEditing = uiState.isManagingSleepCharts`, `onItemReorder = onReorderSleepCharts`, `onItemHide = onToggleSleepChartVisibility(id, false)`. Range-selector row stays above; section still gates on the chart being visible.

`SleepManagementBottomSheet.kt`:

- Remove `onTopCardReordered`, `onChartReordered`, `onMetricCardReordered` params and the move-up/down arrow leading-content blocks in all three item composables. Sheet retains visibility toggles, display-mode pickers, and reset (matching `VitalsManagementBottomSheet`).

## 4. Data Flow & Persistence

Unchanged from the customization feature:

- Drag end → existing `onReorderSleep*` VM callbacks → delegate `onReorder` → `pendingConfigs` (set while editing) → `SleepFlowIntermediate` (`pending ?: configs`) → live grid preview.
- FAB Done → `saveChanges` → `persistTrigger` → DataStore (`SleepLayoutRepositoryImpl`).
- Drop-to-hide → `onToggleSleep*Visibility(id, false)` → pending → persisted on save.

## 5. Strings

No new strings (reuse `accessibility_drag_to_reorder` and existing drop-zone strings). Remove now-unused `sleep_management_move_up` and `sleep_management_move_down` from `feature/sleep/src/main/res/values/strings.xml`.

## 6. Testing & Verification

1. Existing reorder tests must pass after wrapper migration: `ReorderableCardGridThresholdTest`, `CardConfigurationsListTest`, `DragControllerTest` (mechanics unchanged).
2. `SleepManagementBottomSheetTest`: update to assert the reorder callbacks/arrows are absent.
3. `SleepViewModelLayoutManagementTest`: confirm/extend coverage of `onReorderSleep*` → pending → uiState preview.
4. Pre-commit: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
5. Final: `./gradlew lintRelease`.

## 7. Documentation

No `internal-docs/DATA_FLOW.md` change: on-screen drag is presentation-layer; the Sleep layout persistence pipeline was already documented (commit `deb88024`).
