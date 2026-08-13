# Manage-Cards Shared Component Design Specification

**Date:** 2026-08-13
**Status:** Proposed
**Author:** Readylytics Team

## 1. Overview & Objectives

Three tabs currently render their own "manage cards" bottom sheet with near-identical structure but duplicated code and a behavioral inconsistency:

- **Dashboard** — `CardManagementBottomSheet` (1 section, no tabs; rows: checkbox + shared display-mode dropdown).
- **Vitals** — `VitalsManagementBottomSheet` (2 tabs: Cards / Diagrams).
- **Sleep** — `SleepManagementBottomSheet` (3 tabs: Top Cards / Charts / Metric Cards; **its own private** display-mode dropdown that offers a "Default" option, unlike dashboard/vitals).

This spec extracts the shared structure into reusable components so a future tab can add the same "manage cards" experience with minimal copy-paste. It covers four shared pieces:

1. One generic `ManagementBottomSheet` in `core/ui` (section/item model).
2. One shared display-mode dropdown, unified to **"Default + supported modes"** everywhere.
3. A shared `ModeSpec` + `resolveRequestedMode` (replacing the two parallel catalog spec types and duplicated resolution logic).
4. Centralized display-mode type conversions and a shared edit-mode screen scaffold.

Per-item controls are fixed: a visibility checkbox and an optional display-style dropdown (shown only for mode-capable items). No extensible content slots.

## 2. Approach

Config-agnostic section/item model + a single generic sheet. The sheet consumes a `List<ManagementSection>`, where each item already carries its resolved label, visibility, mode info, and bound callbacks — so the sheet needs no knowledge of `CardId`, `SleepTopCardId`, `VitalsChartId`, or any catalog. Each feature keeps a thin wrapper that maps its config lists into sections.

No changes to the reorderable grid/list components or the drag controller; those are already shared.

## 3. Shared Display-Mode Dropdown (core/ui)

Extend `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelector.kt` to be nullable:

```kotlin
@Composable
fun DisplayModeDropdownSelector(
    selectedMode: DashboardCardDisplayMode?,      // null renders "Default"
    supportedModes: List<DashboardCardDisplayMode>,
    onModeSelected: (DashboardCardDisplayMode?) -> Unit, // null = reset to legacy default
    modifier: Modifier = Modifier,
)
```

- Options rendered in order: `null` ("Default"), then each `supportedModes` entry.
- Add core/ui string `mode_default` = "Default".
- Sleep deletes its private `DisplayModeDropdownSelector` (currently in `SleepManagementBottomSheet.kt`) and uses this one; dashboard/vitals switch to the nullable signature.

## 4. Shared ModeSpec + Requested-Mode Resolution (core/model)

The two catalogs currently define structurally identical spec types (`DashboardCardSpec` with a `cardId` field, `SleepCardSpec` without) and duplicate the same requested→effective mode resolution. Replace with a single shared type + helper.

New `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/ModeSpec.kt`:

```kotlin
data class ModeSpec(
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

fun ModeSpec.resolveRequestedMode(requested: DashboardCardDisplayMode?): DashboardCardDisplayMode =
    if (requested != null && supportedModes.contains(requested)) requested else legacyDefaultMode
```

- `DashboardCardCatalog`: drop `DashboardCardSpec`; `spec(cardId)` returns `ModeSpec?`; `requestedMode(configuration)` becomes `spec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode) ?: DashboardCardDisplayMode.VALUE`.
- `SleepCardCatalog`: drop `SleepCardSpec`; `topCardSpec`/`metricCardSpec` return `ModeSpec?`; `requestedTopCardMode`/`requestedMetricCardMode` use `resolveRequestedMode`.
- `DashboardToUniversalMapper.toUniversalSpec()` currently reads `spec.cardId` for the delta-pill flag; refactor to `ModeSpec.toUniversalSpec(usesDeltaPill: Boolean)` with the factory passing `cardId.usesDeltaPill()`. `usesDeltaPill()` stays in the dashboard module.
- `SleepLayoutRenderers.supportedModes(spec)` helper switches its parameter from `SleepCardSpec?` to `ModeSpec?`.
- Update `DashboardCardCatalogTest` and `SleepCardCatalogTest` accordingly.

## 5. Display-Mode Type Conversions (core/ui)

`DashboardCardDisplayMode ↔ UniversalCardDisplayMode` conversions are currently defined three times (`DashboardToUniversalMapper.kt`, `VitalsCardFactory.kt`, `SleepLayoutRenderers.kt`). Centralize once.

New `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/DisplayModeMappers.kt`:

```kotlin
fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode = ...
fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode = ...
```

Delete the private copies in the three feature files; `DashboardToUniversalMapper.kt` keeps `toUniversalSpec` and `usesDeltaPill` (CardId-specific). The mappers are `public` so feature modules can import them.

## 6. Shared Sheet + Model (core/ui)

New file `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheet.kt`:

```kotlin
data class ManagementItem(
    val key: String,                    // stable LazyColumn key
    val label: String,                  // resolved display name
    val isVisible: Boolean,
    val supportedModes: List<DashboardCardDisplayMode>, // empty = no dropdown (charts)
    val requestedMode: DashboardCardDisplayMode?,       // null = "Default"
    val onVisibilityChanged: (Boolean) -> Unit,
    val onDisplayModeChanged: (DashboardCardDisplayMode?) -> Unit,
)

data class ManagementSection(
    val title: String,
    val items: List<ManagementItem>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementBottomSheet(
    title: String,
    sections: List<ManagementSection>,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
)
```

Rendering (matching the current sheets' visual structure):

- `ModalBottomSheet` → `Column(padding)`:
  1. Header `Row`: title (`headlineSmall`) + reset `IconButton` (`RestartAlt`).
  2. `PrimaryTabRow` + `Tab`s **only when `sections.size > 1`** (dashboard's single section renders no tabs), followed by a `Spacer`. `ManagementSection.title` is used solely as tab label text, so it is unused for a single-section sheet.
  3. `LazyColumn(Modifier.weight(1f, fill = false))` of `sections[selectedTabIndex].items` (or `sections[0]` when a single section). Each row is a private `ManagementRow`: `ListItem` with `headlineContent = label`, `supportingContent = DisplayModeDropdownSelector` when `supportedModes.isNotEmpty()`, `trailingContent = Checkbox(checked = isVisible)`.
  4. `Button` "Done" → `onDismiss`.

## 7. Edit-Mode Screen Scaffold (core/ui)

The `rememberModalBottomSheetState()` + `showXxxManagement by rememberSaveable` + dismiss lambda is repeated across Dashboard/Vitals/Sleep screens. Extract a small state holder.

New `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManageLayoutState.kt`:

```kotlin
class ManageLayoutState(
    val sheetState: SheetState,
    val isManageOpen: Boolean,
    val openManage: () -> Unit,
    val closeManage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberManageLayoutState(): ManageLayoutState
```

`closeManage` launches `sheetState.hide()` and clears the flag. The three screens replace their local `sheetState`/`show`/dismiss-lambda with `rememberManageLayoutState()`; the `if (state.isManageOpen) XxxManagementBottomSheet(...)` and `EditModeFab(onManageClick = state.openManage, ...)` calls remain (they differ per screen and already use the shared `EditModeFab`).

## 8. Feature Wrappers (thin builders)

Each existing sheet becomes a thin `@Composable` that builds sections and delegates to `ManagementBottomSheet`. Public signatures stay identical except for the display-mode callback widening described in §9.

- `CardManagementBottomSheet` (dashboard): 1 section from `cards.sortedBy { position }`; label = `stringResource(card.cardId.displayNameResId)`, `supportedModes = DashboardCardCatalog.spec(card.cardId)?.supportedModes.orEmpty()`, `requestedMode = card.requestedDisplayMode`; callbacks bind `card.cardId`.
- `VitalsManagementBottomSheet`: 2 sections — cards (`CardConfiguration` via `DashboardCardCatalog`) and charts (`VitalsChartConfiguration`, no mode).
- `SleepManagementBottomSheet`: 3 sections — top cards (`SleepCardCatalog.topCardSpec`), charts (no mode), metric cards (`SleepCardCatalog.metricCardSpec`).

## 9. Nullable Display-Mode Plumbing (dashboard/vitals)

To support "Default" (reset) on dashboard/vitals, widen the display-mode callbacks to nullable. `DashboardCardCatalog.requestedMode` / `SleepCardCatalog.requested*Mode` (now backed by `resolveRequestedMode`) already resolve null → legacy default at render time, so no renderer changes are needed.

- `CardManagementEvent.DisplayModeChanged.mode: DashboardCardDisplayMode` → `DashboardCardDisplayMode?`.
- `CardManagementDelegate.onDisplayModeChanged(cardId, mode)` → nullable; handler body unchanged (`copy(requestedDisplayMode = mode)` already handles null).
- `DashboardViewModel.onCardDisplayModeChanged` and `VitalsViewModel.onVitalsCardDisplayModeChanged` params → nullable.
- `DashboardScreen.onCardDisplayModeChanged` and `VitalsScreen.onVitalsCardDisplayModeChanged` params → `(CardId, DashboardCardDisplayMode?) -> Unit`.
- `DashboardCardFactory.buildCardDataMap`/`ConfigurableMetricCard` and `VitalsCardFactory.buildVitalsCardDataMap` callback param types → nullable (the on-card three-dot menu keeps emitting non-null; only the sheet emits null).

Sleep delegates are already nullable; no change.

## 10. Strings

- Add core/ui `mode_default` = "Default".
- Remove now-unused sleep strings `sleep_management_display_mode_label`, `sleep_management_display_mode_default`, `sleep_management_display_mode_gauge`, `sleep_management_display_mode_bar`, `sleep_management_display_mode_value`.

## 11. Testing & Verification

1. New Robolectric Compose test `ManagementBottomSheetTest` (core/ui): tabs appear only when >1 section; dropdown present for mode-capable items and absent for charts; checkbox invokes `onVisibilityChanged`; "Default" option invokes `onDisplayModeChanged(null)`; Done → `onDismiss`; reset icon → `onResetToDefaults`.
2. `SleepManagementBottomSheetTest` shrinks to a thin-wrapper behavior test (sections/keys/callback binding), or is removed in favor of the core/ui test.
3. Delegate/viewmodel tests updated for nullable mode (e.g. `CardManagementDelegate` reset-to-default path; `VitalsViewModelLayoutManagementTest` / `DashboardViewModel` mode tests).
4. `ModeSpec.resolveRequestedMode` unit tests (or fold into `DashboardCardCatalogTest`/`SleepCardCatalogTest`, which are updated for the `ModeSpec` rename).
5. Pre-commit: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
6. Final: `./gradlew lintRelease`.

## 12. Documentation

No `internal-docs/DATA_FLOW.md` change: this is presentation-layer UI refactoring; persistence and mode resolution are unchanged. Display-mode "Default" semantics (null → legacy default) are already documented in `DashboardCardCatalog`/`SleepCardCatalog`.
