# Customizable Vitals Tab — Verified Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Dashboard's full customization capability (variable card grid, gauge/bar/value switching, drag-reorder, hide via checkbox, draft-then-save/discard Customize flow, unified Settings global display-mode switch) to the Vitals tab's top cards (HRV, RHR, + new SpO2/Body Temperature) and its four trend charts, per the approved source plan.

**Architecture:** `feature/vitals` and `feature/dashboard` are independent feature modules that both depend on `core/model` (pure-Kotlin domain) and `core/ui` (shared composables) via the `readylytics.compose-feature-conventions` Gradle plugin — they do **not** depend on each other. Therefore every shared piece (delegates, drag mechanics, the reorderable chart list) is extracted into `core/model` / `core/ui`, and feature-vitals-only surfaces (extensions, new composables) are written inside `feature/vitals`. Persistence mirrors the existing `CardConfiguration` Proto DataStore stack exactly, with a second store for the Vitals layout.

**Tech Stack:** Kotlin, Jetpack Compose/M3, Proto DataStore (second store: `vitals_layout_configurations.pb`), Hilt, coroutines, Room (untouched), pure-Kotlin domain logic (zero Android deps).

---

## 0. Relationship to the source plan

The approved design lives in `internal-docs/plans/VITALS_TAB_CUSTOMIZATION_PLAN.md` (1812 lines; quote as `SOURCE §N`). It specifies current behavior with line numbers and target code for most pieces. This plan **resolves every open question the source plan left to "confirm/decide at implementation time"** using facts verified in the actual repo, and turns the whole feature into an ordered, test-driven task list. Where this plan quotes code it is the resolved final form; where it says "copy from `SOURCE §N`" the exact code block already exists there and must be reproduced verbatim (the source plan is self-contained). The source plan's `§2` current-behavior inventory remains authoritative for line numbers of pre-change code.

**Status note from source plan:** the source plan predates the dashboard flow merging Dashboard-specific lookups (last-sleep-session, `filteredForPermission()`, `pendingConfiguration ?: ...` merge); that merged behavior is already in the live code and is what this plan's Vitals read-side merge mirrors.

---

## 1. Verified facts (from exploration — trust these, do not re-derive)

### 1.1 Module dependency graph (confirmed)

| Module | Can see | Relevant consequence |
|---|---|---|
| `feature/vitals` | `core/model`, `core/ui` (and anything below them) | Cannot reference `feature/dashboard` types or `feature/dashboard` res. |
| `feature/dashboard` | `core/model`, `core/ui` | Loses nothing when shared code moves down. |
| `core/model` | pure Kotlin (`kotlinx-coroutines` Flow types already used — `CardConfigurationRepository` exposes `Flow`) | Target home for both management delegates. |
| `core/ui` | `core/model` | Target home for `DragController<T>`, `ReorderableChartList`, `EditModeFab`. |

`build-logic/src/main/kotlin/readylytics.compose-feature-conventions.gradle.kts` injects `:core:model` + `:core:ui` into every feature module — confirm the dependency list there before relying on it.

### 1.2 Consequences of the module graph (resolves SOURCE §5.3/5.4/5.6/6.2/6.7 open questions)

| Item | Verified decision |
|---|---|
| `CardManagementDelegate.kt` | **Move** to `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt` (same package — `feature/dashboard` imports stay valid). Goes `internal`→`public`. Pure-Kotlin move, no logic change. |
| `VitalsChartManagementDelegate` | Lives in **`core/model`**, package `app.readylytics.health.domain.vitals` (alongside the new Vitals domain types). |
| `CardId.displayNameResId` (20-branch, in `feature/dashboard/.../CardIdExtensionsUi.kt`) | **Duplicate a 4-branch local extension in `feature/vitals`** (new file `VitalsCardIdExtensions.kt`). Mapping to the 4 Vitals CardIds reuses existing `feature/vitals`/`core/ui` strings (HRV→`label_hrv_rmssd`, RHR→`label_resting_heart_rate`, SpO2→`label_oxygen_saturation`, BodyTemp→`label_body_temperature`). Do **not** relocate the full 20-branch extension or its ~20 `card_title_*` strings to shared res — too large a surface for this feature. |
| `DashboardToUniversalMapper.kt` (`internal` to `feature/dashboard`) | **Duplicate the 6-line 1:1 enum mapping** as `private` functions in the new `feature/vitals/.../VitalsCardFactory.kt` (exact code in SOURCE §6.2). |
| `EditModeFab.kt` (`feature/dashboard`) | **Move to `core/ui`** (package `app.readylytics.health.core.ui.components`) so `feature/vitals` can use it. Relocate its two string deps, `action_done_editing` / `action_cancel_editing`, from `feature/dashboard` res into `core/ui` res, and update `feature/dashboard`'s reference (`DashboardScreen.kt`) to `CoreUiR.string.*`. Run `codegraph sync` after both moves. |

### 1.3 String locations (verified — overrides SOURCE §9's "app/src/main/res" assumption)

- `feature/dashboard/src/main/res/values/strings.xml`: `action_customize`, `action_done`, `action_done_editing`, `action_cancel_editing`, `action_reset_to_defaults`, `manage_cards`, mode strings `mode_gauge`/`mode_bar`/`mode_value`, etc.
- `feature/vitals/src/main/res/values/strings.xml`: `label_hrv_rmssd`, `label_resting_heart_rate`, `label_oxygen_saturation`.
- `core/ui/src/main/res/values/strings.xml`: `action_delete_drop_zone`, `accessibility_drag_to_reorder`, `label_body_temperature`.
- New strings for this feature MUST go into the module that uses them (`feature/vitals` res for Vitals-only UI, `core/ui` res for any shared component). Note this deviates from AGENTS.md's literal "`app/src/main/res/values/strings.xml`" line, which describes the app module; the live codebase puts feature strings in feature res and the build resolves them per-module — follow the live code.

### 1.4 `bodyTempStatus` semantics (resolves SOURCE §5.1 TODO)

Live `DashboardMetricPresentationFactory.kt` (~lines 91-99):
```kotlin
private fun bodyTempStatus(value: Float?, baseline: Float?, thresholdCelsius: Float): MetricStatus =
    when {
        value == null -> MetricStatus.CALIBRATING
        baseline == null -> MetricStatus.NEUTRAL
        bodyTemperatureBaselineCalculator.isElevated(value, baseline, thresholdCelsius) -> MetricStatus.WARNING
        else -> MetricStatus.NEUTRAL
    }
```
`isElevated` is `abs(todayCelsius - baselineCelsius) >= thresholdCelsius`. The extraction to `core/model` MUST use `abs(...) >= thresholdCelsius` verbatim. `bodyTemperatureBaselineCalculator` is referenced only at dashboard-factory lines ~45 and ~99; after extraction it becomes constructor-unused → remove the field and its Hilt/fixture wiring in the factory and its unit tests.

### 1.5 Settings display-mode switch UI (resolves SOURCE §7 "copy check")

The switch lives in **`feature/settings`**, not `feature/dashboard`:
- `feature/settings/.../DashboardCardsSettings.kt` — `DropdownPreferenceItem` (VALUE/GAUGE/BAR) + Apply/Reset buttons (`dashboard_cards_global_mode_*` strings) + `GlobalDisplayModeConfirmDialog` ("don't show again").
- `feature/settings/.../DashboardCardsSettingsViewModel.kt` — `applyGlobalMode`/`resetAllModes` over `CardConfigurationRepository` + `DisplaySettings.updateLastGlobalDisplayMode`.
- ViewModel already imports `DashboardCardDisplayMode` from `core/model` → `feature/settings` has `core/model` on classpath, so adding `VitalsLayoutRepository` is safe.
- Copy check: the confirm dialog body and the section label currently say "dashboard cards". Change copy to mention both tabs (e.g. "Dashboard and Vitals cards") and update the accompanying `dashboard_cards_*` strings in `feature/settings` res (verify live wording first).

### 1.6 Backup schema (resolves SOURCE §8)

- `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt:93` — `data class UserPreferencesBackup(..., val dashboardCards: List<CardConfiguration>? = null /* :156 */)`.
- `LocalBackupManager.kt:599` — writes `dashboardCards = cards` into the payload.
- `LocalRestoreManager.kt:663` — `backup.dashboardCards?.let { ... }` restores (absent field must default back to defaults — a `null`/absent field is tolerated).
- Add sibling nullable fields `vitalsCards: List<CardConfiguration>?` and `vitalsCharts: List<VitalsChartConfiguration>?` on `UserPreferencesBackup`, write them in `LocalBackupManager.writePreferences()`, restore them in `LocalRestoreManager`, always NOP when the field is absent/null (old backups keep working).

### 1.7 `VitalsPresentationState` body-temp call sites (resolves SOURCE §5.2 search)

Old field `baselineBodyTemp: Float?` is produced in `VitalsStateFactory.kt` (~79,104) and consumed in `VitalsTrendSection.kt` (~204-207) for the Body Temperature chart's `baseline`/`showBaseline`/`baselineUnavailableLabel`. Grep the whole `feature/vitals` module (incl. tests) for `baselineBodyTemp` before removing the field — no other call sites exist per exploration, but confirm at implementation time.

### 1.8 `CardConfiguration` / the async-merge pattern (SOURCE §2.8, confirmed live)

- `CardConfiguration` and `CardId` live in `core/model` (`app.readylytics.health.domain.dashboard`).
- `DashboardCardCatalog` (core/model) already exposes `spec(CardId)` and `applyGlobalDisplayMode`/`resetAllDisplayModes`, both generic over `List<CardConfiguration>` — reused as-is, no changes.
- Live Dashboard merge: `createDashboardCardStateFlow` combines `isManaging`, `pendingConfigs`, committed repo flow, permission grants; UI reads `pendingConfiguration ?: cardConfiguration`. Copy this shape for Vitals (minus the last-sleep-session lookup).
- GAUGE is the mode used by Vitals today; SpO2/BodyTemp Dashboard cards are `requiresHealthConnect`, so gone if permission revoked — mirror that in the Vitals read-side merge.

---

## 2. Task order (do not reorder)

Task 1 builds the foundation both features will call; Tasks 2-3 build persistence; Tasks 4-5 generalize shared components (feature/dashboard behavior must stay byte-identical); Task 6 builds the Vitals UI; Tasks 7-9 finish wiring (Settings, backup, strings); Task 10 tests; Task 11 verifies. Commit after **every task** (frequent commits).

---

## Task 1: Extract body-temperature assessment to `core/model`

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/VitalAssessment.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/model/VitalAssessmentTest.kt` (create/extend)
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`

- [ ] **Step 1: Write the failing tests** for `bodyTemperatureStatus` (boundary == threshold, below, above, null value, null baseline) and `assessBodyTemperature` (unit conversion display value):

```kotlin
@Test fun `bodyTemperatureStatus warns when abs difference equals threshold`() {
    assertEquals(MetricStatus.WARNING, bodyTemperatureStatus(value = 37.5f, baseline = 36.5f, thresholdCelsius = 1.0f))
}
@Test fun `bodyTemperatureStatus distinct when value below baseline by threshold`() {
    assertEquals(MetricStatus.WARNING, bodyTemperatureStatus(value = 36.5f, baseline = 37.5f, thresholdCelsius = 1.0f))
}
@Test fun `bodyTemperatureStatus neutral below threshold`() {
    assertEquals(MetricStatus.NEUTRAL, bodyTemperatureStatus(value = 37.0f, baseline = 36.5f, thresholdCelsius = 1.0f))
}
@Test fun `bodyTemperatureStatus calibrating on null value`() {
    assertEquals(MetricStatus.CALIBRATING, bodyTemperatureStatus(value = null, baseline = 36.5f, thresholdCelsius = 1.0f))
}
@Test fun `bodyTemperatureStatus neutral on null baseline`() {
    assertEquals(MetricStatus.NEUTRAL, bodyTemperatureStatus(value = 37.0f, baseline = null, thresholdCelsius = 1.0f))
}
@Test fun `assessBodyTemperature converts to display units`() {
    val a = assessBodyTemperature(valueCelsius = 37.0f, baselineCelsius = 36.5f, thresholdCelsius = 1.0f, unitSystem = UnitSystem.IMPERIAL)
    assertEquals(MetricStatus.NEUTRAL, a.status)
    assertEquals(98.6f, a.value!!, 0.1f)
    assertEquals(97.7f, a.baseline!!, 0.1f)
}
```
Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.model.VitalAssessmentTest"` — expect FAIL (symbols not defined).

- [ ] **Step 2: Add to `VitalAssessment.kt`** (next to `Spo2Assessment`/`assessSpo2` — match that file's exact style; `UnitSystem`/`UnitConverter` are `core/model` types already used there):

```kotlin
data class BodyTemperatureAssessment(
    val value: Float?,        // display-unit value (already unit-converted by the caller)
    val baseline: Float?,     // display-unit baseline
    override val status: MetricStatus,
    override val zoneBands: List<ZoneBand>? = null,
) : VitalAssessment

fun assessBodyTemperature(
    valueCelsius: Float?,
    baselineCelsius: Float?,
    thresholdCelsius: Float,
    unitSystem: UnitSystem,
): BodyTemperatureAssessment = BodyTemperatureAssessment(
    value = valueCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
    baseline = baselineCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
    status = bodyTemperatureStatus(valueCelsius, baselineCelsius, thresholdCelsius),
)

fun bodyTemperatureStatus(value: Float?, baseline: Float?, thresholdCelsius: Float): MetricStatus =
    when {
        value == null -> MetricStatus.CALIBRATING
        baseline == null -> MetricStatus.NEUTRAL
        abs(value - baseline) >= thresholdCelsius -> MetricStatus.WARNING
        else -> MetricStatus.NEUTRAL
    }
```
(Imports: `kotlin.math.abs`; confirm `UnitConverter.celsiusToDisplayTemperature` is the exact name in `core/model` before using it — otherwise mirror how the dashboard factory converts units currently.)

- [ ] **Step 3: Run the tests** — expect PASS.

- [ ] **Step 4: Update `DashboardMetricPresentationFactory.kt`** — delete the private `bodyTempStatus`, call the new `bodyTemperatureStatus(...)`. Remove the now-unused `bodyTemperatureBaselineCalculator` constructor field and its Hilt/fixture wiring **only if** exploration's "used only at :45/:99" still holds after the edit (grep the file).

- [ ] **Step 5: Run Dashboard tests** `./gradlew :feature:dashboard:testDebugUnitTest` — the existing body-temp-card assertions must pass unmodified. Fix factory-test construction if the constructor param removal broke fixtures.

- [ ] **Step 6: Commit** (e.g. `feat(core): extract body-temperature assessment to domain`).

---

## Task 2: Vitals domain model in `core/model`

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartId.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartConfiguration.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsLayoutRepository.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`

- [ ] **Step 1:** Create `VitalsChartId.kt`:

```kotlin
package app.readylytics.health.domain.vitals

enum class VitalsChartId {
    HRV_TREND,
    RHR_TREND,
    SPO2_TREND,
    BODY_TEMP_TREND,
}
```

- [ ] **Step 2:** Create `VitalsChartConfiguration.kt`:

```kotlin
package app.readylytics.health.domain.vitals

data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    val isVisible: Boolean,
    val position: Int,
)
```

- [ ] **Step 3:** Create `VitalsLayoutRepository.kt` (matches `CardConfigurationRepository`'s breathing style — read that interface first):

```kotlin
package app.readylytics.health.domain.vitals

import app.readylytics.health.domain.dashboard.CardConfiguration
import kotlinx.coroutines.flow.Flow

interface VitalsLayoutRepository {
    fun vitalsCardConfigurations(): Flow<List<CardConfiguration>>
    suspend fun updateVitalsCardConfigurations(cards: List<CardConfiguration>)
    fun vitalsChartConfigurations(): Flow<List<VitalsChartConfiguration>>
    suspend fun updateVitalsChartConfigurations(charts: List<VitalsChartConfiguration>)
}
```

- [ ] **Step 4:** In `SettingsDefaults.kt`, next to `DEFAULT_DASHBOARD_CARDS`, add (confirm `CardConfiguration`'s constructor param names/order by reading `CardConfiguration.kt` first; defaults below assume `(cardId, position, isVisible, displayMode)`):

```kotlin
val DEFAULT_VITALS_CARDS = listOf(
    CardConfiguration(CardId.RESTING_HR, 0, isVisible = true, DashboardCardDisplayMode.GAUGE),
    CardConfiguration(CardId.HRV, 1, isVisible = true, DashboardCardDisplayMode.GAUGE),
    CardConfiguration(CardId.OXYGEN_SATURATION, 2, isVisible = false, DashboardCardDisplayMode.GAUGE),
    CardConfiguration(CardId.BODY_TEMPERATURE, 3, isVisible = false, DashboardCardDisplayMode.GAUGE),
)

val DEFAULT_VITALS_CHARTS = listOf(
    VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
    VitalsChartConfiguration(VitalsChartId.RHR_TREND, isVisible = true, position = 1),
    VitalsChartConfiguration(VitalsChartId.SPO2_TREND, isVisible = true, position = 2),
    VitalsChartConfiguration(VitalsChartId.BODY_TEMP_TREND, isVisible = true, position = 3),
)
```

- [ ] **Step 5: Compile** `./gradlew :core:model:compileDebugKotlin`, then **commit**.

---

## Task 3: Persistence — proto, mapper, serializer, repo, DI

**Files:**
- Create: `app/src/main/proto/vitals_layout_configurations.proto`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutMapper.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutConfigurationsSerializer.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/DataStoreModule.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt`

Mirror `card_configurations.proto` / `CardConfigurationMapper.kt` / `CardConfigurationsSerializer.kt` / `CardConfigurationRepositoryImpl.kt` exactly. The source plan quotes the full target code in `SOURCE §4.5-§4.10` — reproduce it verbatim there (`VitalsLayoutMapper` must be **tolerant** like `CardConfigurationMapper`: an unrecognized `card_id`/`chart_id` string should be handled the same way — read `CardConfigurationMapper.kt` first and match).

- [ ] **Step 1:** Create `vitals_layout_configurations.proto`. The source plan's schema (`SOURCE §4.4-4.5`) has three messages (`VitalsCardConfigurationProto`, `VitalsChartConfigurationProto`, `VitalsLayoutConfigurationsProto` with repeated `vitalsCards` + `trendCharts` + a marker `version`). Reproduce exactly; run `./gradlew :app:generateDebugProto` afterward.
- [ ] **Step 2:** Create `VitalsLayoutMapper.kt` (from `SOURCE §4.6`; preserve the tolerant parse behavior — no invented semantics). `toProto(VitalsChartConfiguration)` must map `chart_id`, `is_visible`, `position`.
- [ ] **Step 3:** Create `VitalsLayoutConfigurationsSerializer.kt` — from `SOURCE §4.7` verbatim (defaultValue seeds `DEFAULT_VITALS_CARDS`/`DEFAULT_VITALS_CHARTS` through the mapper; corruption throw matches `CardConfigurationsSerializer`'s exact exception type).
- [ ] **Step 4:** Create `VitalsLayoutRepositoryImpl.kt` — from `SOURCE §4.8` verbatim: "merge in missing defaults on every read" per list, `mergeWithDefaults` appends missing default entries at the end (positions continue from `stored.size`), `updateVitalsCardConfigurations` clears+rewrites `vitalsCards`, `updateVitalsChartConfigurations` clears+rewrites `trendCharts`. Match the live `CardConfigurationRepositoryImpl` merge/position semantics exactly, whichever way they behave.
- [ ] **Step 5:** `DataStoreModule.kt` — add `provideVitalsLayoutConfigurationsDataStore` (`SOURCE §4.9`, `ReplaceFileCorruptionHandler { VitalsLayoutConfigurationsSerializer.defaultValue }`, file `vitals_layout_configurations.pb`, no legacy migration). `RepositoryModule.kt` — add `bindVitalsLayoutRepository` (`SOURCE §4.10`).
- [ ] **Step 6:** Write `VitalsLayoutRepositoryTest.kt` + `VitalsLayoutConfigurationsSerializerTest.kt` + `VitalsLayoutMapperTest.kt` mirroring the three `CardConfiguration*` tests in `app/src/test/.../data/preferences/` (material). Run `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.data.preferences.Vitals*"` — PASS.
- [ ] **Step 7:** Commit. Run `codegraph index` (new files).

---

## Task 4: Generalize `CardManagementDelegate` (move to `core/model`)

**Files:**
- Move: `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt` → `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`
- Update: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- Test: `feature/dashboard/src/test/.../domain/dashboard/CardManagementDelegateTest.kt`

- [ ] **Step 1: Move the file** (same package `app.readylytics.health.domain.dashboard`), remove the module-level `internal` visibility (make it public), change the constructor per `SOURCE §5.3`: `defaultConfigurations: List<CardConfiguration>`, `persist: suspend (List<CardConfiguration>) -> Unit`, `scope: CoroutineScope`, keep all permission-lambda params with `{ true }` defaults. Replace the two call-site lines per `SOURCE §5.3`: persist line → `persist(toPersist)`, reset line → `_pendingConfigs.value = defaultConfigurations`. Everything else unchanged.
- [ ] **Step 2:** Update `DashboardViewModel.kt` construction per `SOURCE §5.3` (pass `defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS`, `persist = cardConfigRepository::updateDashboardCardConfigurations`, keep all six permission lambdas). Zero behavior change.
- [ ] **Step 3:** Confirm the moved file compiles on its own (drop any `feature/dashboard`-only imports; there should be none — it's pure Kotlin). `./gradlew :core:model:compileDebugKotlin`.
- [ ] **Step 4:** Update `CardManagementDelegateTest.kt` construction to a fake `persist` lambda (collect writes into a list) + a literal `defaultConfigurations` list; **all assertions unchanged**. Per `CardManagementDelegateTest`'s current shape, this also means the test's module changes if the delegate test lives where the old file did (now `core/model` test package mirror). Run `./gradlew :core:model:testDebugUnitTest :feature:dashboard:testDebugUnitTest` — PASS.
- [ ] **Step 5: Commit.** Run `codegraph sync` (structural move).

---

## Task 5: `VitalsChartManagementDelegate` + drag mechanics (all `core/model` / `core/ui`)

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartManagementDelegate.kt` — reproduce `SOURCE §5.4` verbatim (state/event/delegate as quoted; no display-mode event, no permission gating).
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/DragController.kt` — genericize per `SOURCE §5.5`: `class DragController<T : Any>`, `pendingOrder: List<T>`, `draggedCardId: T?`, `slotBounds: SnapshotStateMap<T, Rect>`, generic method params/returns, `DragEndResult<T>`. Remove the `CardId` import. Bodies unchanged.
- Modify: `core/ui/.../components/ReorderableCardGrid.kt` — add `<CardId>` type arguments to every `DragController`/`DragEndResult` reference (e.g. `DragController<CardId>`). Purely a type-argument change.
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableChartList.kt`.

- [ ] **Step 1:** Create `VitalsChartManagementDelegate.kt` (SOURCE §5.4). Test first, TDD: create `core/model/src/test/.../domain/vitals/VitalsChartManagementDelegateTest.kt` mirroring `CardManagementDelegateTest` — target editor-mode entry/exit, toggle visibility pending-state, reorder (subset + hidden preserve), reset to defaults, save persists via the lambda. Run tests — PASS. Commit.
- [ ] **Step 2:** Genericize `DragController` (SOURCE §5.5). Verify no behavior change: `./gradlew :core:ui:compileDebugKotlin` and `ReorderableCardGridThresholdTest` passes unmodified. Add a `VitalsChartId`-parameterized case to that test (uses the pure part of the controller). Run tests — PASS. Commit. `codegraph index`.
- [ ] **Step 3:** Create `ReorderableChartList.kt` in `core/ui`. Reuse `ReorderableCardGrid`'s proven mechanics — read `ReorderableCardGrid.kt` / `ReorderableCardItem` in full first, then create a single-column counterpart (per SOURCE §5.5 spec): one full-width `Box` per visible+renderable chart ordered by `DragController<VitalsChartId>.pendingOrder`; 48dp drag-handle `Box` (only when `isEditing`) gating drag start; a bottom "hide" drop zone `Surface` in edit mode reusing the delete-zone mechanism but with copy "Drop here to hide" (string decision in Task 9); all items full-width, one per row. Signature and the two `@Immutable` wrapper types (`ChartConfigurationsList`/`ChartDataMap`) exactly as `SOURCE §5.5`. This file depends on `VitalsChartConfiguration`/`VitalsChartId` from `core/model` — consistent with `ReorderableCardGrid` depending on `CardConfiguration`/`CardId`.
- [ ] **Step 4:** `./gradlew :core:ui:compileDebugKotlin` — PASS. Commit. `codegraph index`.

---

## Task 6: Vitals feature (UI + ViewModel)

All files under `feature/vitals/...`. The source plan quotes the full target code in `SOURCE §6`. Reproduce verbatim; per-file detail:

- [ ] **Step 1: `VitalsCardIdExtensions.kt`** (new, `feature/vitals/.../overview/`): 4-branch `val CardId.displayNameResId: Int` mapping the four Vitals-relevant CardIds to the existing strings (see §1.2; `label_body_temperature` via `CoreUiR`). (Note: uses `feature/vitals` R, not the dashboard 20-branch extension.)
- [ ] **Step 2: Generalize `UniversalVitalsMetricCard.kt`** per `SOURCE §5.7` — add `supportedModes: List<UniversalCardDisplayMode>`, replace hardcoded GAUGE with `requestedMode`, add `usesDeltaPill: Boolean = secondaryText != null`, `isEditing: Boolean = false`, `onModeSelected: (...) -> Unit = {}`, and `onClick = if (isEditing) null else onClick`. No callers yet (VitalsGaugeRow is being deleted), so this compiles standalone. Commit.
- [ ] **Step 3: Delete `VitalsGaugeRow.kt`**, create `VitalsCardFactory.kt` per `SOURCE §6.1/6.2`: `buildVitalsCardDataMap(presentation, isEditing, onNavigateToHrv, onNavigateToRhr, onCardDisplayModeChanged)` returning `Map<CardId, @Composable (CardConfiguration) -> Unit>` with the four entries (RHR/HRV = exact relocated gauge-fill + delta logic; SpO2/BodyTemp = NEW cards built from `presentation.spo2`/`presentation.bodyTemp`, mirroring the dashboard factory's SpO2/BodyTemp card blocks for value formatting, gauge scale, delta text, unit). Include the two `private` `toUniversalMode`/`toDashboardMode` enum mappings from SOURCE §6.2. **SpO2/BodyTemp `onClick`:** search `MainNavHost.kt` for an existing SpO2/BodyTemp detail route; there is none → use `onClick = null` (non-navigable), matching the AI_RECOMMENDATION precedent. Commit.
- [ ] **Step 4: `VitalsStateFactory.kt`** — swap `baselineBodyTemp: Float?` for `bodyTemp: BodyTemperatureAssessment` (+ keep `bodyTempUnitSystem`), build it via `assessBodyTemperature(summary?.avgSleepingBodyTemp, bodyTemperatureBaselineCelsius, prefs.bodyTempElevatedThresholdCelsius, prefs.unitSystem)` per `SOURCE §5.2`. Confirm `prefs.bodyTempElevatedThresholdCelsius` is the field name. Grep+update every `baselineBodyTemp` call site (known: `VitalsTrendSection.kt` ~204-207 → `presentation.bodyTemp.baseline`). Commit.
- [ ] **Step 5: `VitalsTrendSection.kt` data-driven** per `SOURCE §6.4`: extract the four `CardLoader { TrendCard { TrendChart(...) } }` blocks into `private @Composable` functions + a `chartBlockFor(chartId)` dispatcher; add `chartConfigurations: List<VitalsChartConfiguration>`/`isEditing`/`onChartHide`/`onChartReorder` params; body becomes `ReorderableChartList(...)`. Commit.
- [ ] **Step 6: `VitalsViewModel.kt`** — add `VitalsLayoutRepository` + `HealthConnectRepository` constructor deps; build `vitalsCardManagementDelegate` (CardManagementDelegate, `DEFAULT_VITALS_CARDS`, persist = `updateVitalsCardConfigurations`, body-temp+spo2 permission lambdas) and `vitalsChartManagementDelegate` (VitalsChartManagementDelegate); add `vitalsCardStateFlow`/`vitalsChartStateFlow` merge flows exactly as `SOURCE §6.6` (including `filteredForPermission()`); extend `VitalsUiState` with `vitalsCardConfigurations`/`isManagingVitalsCards`/`vitalsChartConfigurations`; add the combined edit-mode flag (`cardIsManaging || chartIsManaging`) and the eight forwarding methods from `SOURCE §6.6`. Commit.
- [ ] **Step 7: `VitalsManagementBottomSheet.kt`** (new) per `SOURCE §6.7`: two-checkbox-section `LazyColumn` ("Vitals Cards" + "Diagrams") using `CardId.displayNameResId` (Task 6.1's extension) / `VitalsChartId.displayNameResId` (Task 5 already defined the chart extension in `SOURCE §5.6`), reset header, `action_done` button. Commit.
- [ ] **Step 8: `VitalsScreen.kt`** per `SOURCE §6.3/6.5`: replace the `VitalsGaugeRow(...)` call with `ReorderableCardGrid(...)` fed by `buildVitalsCardDataMap`; add the `Box` + `VitalsManagementBottomSheet` + `FilledTonalButton("Customize")` + `EditModeFab` overlay (from Task 5's `core/ui` move); add the new params to `VitalsRoute`/`VitalsScreen` signatures mirroring `DashboardRoute`'s wiring. Compile. Commit. `codegraph index` (new files) / `codegraph sync` (deleted `VitalsGaugeRow.kt`).

---

## Task 7: Settings global display-mode switch — unify

**Files:**
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/DashboardCardsSettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/DashboardCardsSettings.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml` (copy)

- [ ] **Step 1:** Add `VitalsLayoutRepository` constructor dep to `DashboardCardsSettingsViewModel`; extend `applyGlobalMode`/`resetAllModes` per `SOURCE §7` to ALSO read+write the Vitals card configs through `DashboardCardCatalog.applyGlobalDisplayMode`/`.resetAllDisplayModes`. No change to catalog or `DisplaySettings`. Commit.
- [ ] **Step 2:** Copy check on `DashboardCardsSettings.kt` + its strings: any "dashboard cards"-specific copy (section label, confirm-dialog body) → say "Dashboard and Vitals cards". Update the `dashboard_cards_*` strings in `feature/settings` res. `./gradlew :feature:settings:compileDebugKotlin`. Commit.

---

## Task 8: Local backup / restore integration

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt`

- [ ] **Step 1:** Add `vitalsCards`/`vitalsCharts` nullable fields to `UserPreferencesBackup` (mirror `dashboardCards`, `BackupModels.kt:156`).
- [ ] **Step 2:** `LocalBackupManager` — inject `VitalsLayoutRepository`; in `writePreferences()` (near `LocalBackupManager.kt:599`) also serialize `vitalsCardConfigurations().first()` + `vitalsChartConfigurations().first()`.
- [ ] **Step 3:** `LocalRestoreManager` — near `LocalRestoreManager.kt:663`, restore the two fields only when non-null (old backups silently keep defaults). Read both files in full first (durable on-disk format; absent-field handling must match the `dashboardCards` precedent exactly).
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.data.backup.*"` — existing tests must still pass. Commit. `codegraph index`.

---

## Task 9: Strings & i18n

**Files:**
- Modify: `feature/vitals/src/main/res/values/strings.xml` (new copy)
- Modify: `core/ui/src/main/res/values/strings.xml` (strings for the moved `EditModeFab` + optional hide-label)
- Modify: `feature/dashboard/src/main/res/values/strings.xml` (remove the two strings relocated to `core/ui`)

- [ ] **Step 1:** New strings (feature/vitals res): `vitals_management_cards_section_title` ("Vitals Cards"), `vitals_management_diagrams_section_title` ("Diagrams"), and — unless reusing `action_delete_drop_zone`'s copy in `ReorderableChartList` is acceptable per SOURCE §9's decision point — `action_hide_drop_zone` ("Drop here to hide") in `core/ui` res. Decide: the underlying operation (drag→hide, checkbox→unhide) is identical to Dashboard's own delete zone, so reusing the same copy is defensible; prefer **reuse** unless product review objects. If a bottom-sheet title differs from `manage_cards`, add `vitals_manage_layout` — default is reuse `manage_cards`.
- [ ] **Step 2:** Move `action_done_editing`/`action_cancel_editing` from `feature/dashboard` res to `core/ui` res; update `feature/dashboard`'s `EditModeFab` reference to `CoreUiR`. Confirm no other module referenced them via `feature/dashboard` R.
- [ ] **Step 3:** `./gradlew ktlintFormat`, `./gradlew :app:lintRelease` runs clean. Commit.

---

## Task 10: Tests

- [ ] **Step 1:** `feature/dashboard/.../CardManagementDelegateTest.kt` — new construction validated in Task 4; confirm unmodified assertions pass.
- [ ] **Step 2:** `feature/vitals/src/test/.../overview/VitalsViewModelTest.kt` (existing, extend) — pending/committed merge, permission-gated card falls out while denied, combined edit-mode flag, both delegates' forwarding methods. Mirror `DashboardViewModelTest.kt`.
- [ ] **Step 3:** `feature/vitals/src/test/.../overview/VitalsStateFactoryTest.kt` (extend) — `bodyTemp` field present on the built state with correct display units/status.
- [ ] **Step 4:** `core/ui/src/test/.../ReorderableCardGridThresholdTest.kt` — `VitalsChartId`-parameterized case (added in Task 5); new `ReorderableChartList` equivalent if the existing file covers a testable pure slice.
- [ ] **Step 5:** `app/src/test/.../data/preferences/VitalsLayout*Test.kt` (added in Task 3); `core/model/.../domain/vitals/VitalsChartManagementDelegateTest.kt` (added in Task 5).
- [ ] **Step 6:** `./gradlew testDebugUnitTest` (whole suite) — PASS. Commit if any test-only tweaks occurred.

---

## Task 11: Verification

- [ ] **Step 1:** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` — full suite green, special attention to `CardManagementDelegateTest`, `DashboardViewModelTest`, `ReorderableCardGridThresholdTest`, backup/restore tests (prove Dashboard behavior unchanged by Tasks 4/5/8).
- [ ] **Step 2:** `./gradlew installDebug`, manual on a device/emulator with Health Connect, executing every bullet in `SOURCE §11.2` (defaults load, Customize sheet, drag-reorder independence, mode menu, Cancel reverts / Done persists across restart, permission-revoked card drops out of Vitals visible set, Settings switch drives both tabs, backup/restore round-trip).
- [ ] **Step 3:** `./gradlew lintRelease`.
- [ ] **Step 4:** `codegraph index`/`sync` as triggered by each task above; verify with a follow-up codegraph query that no stale paths remain.
- [ ] **Step 5:** Open a PR (feature branch `claude/vitals-tab-customization-1mza20`) with the source plan + this plan linked; docs discipline: no scoring/ingestion/schema changes occur, so `internal-docs/DATA_FLOW.md`/`ABOUT.md`/website pages need **no** changes per the load-bearing rules.

---

## 12. Explicit non-goals (inherited from SOURCE §12, unchanged)

- No scoring-engine, ingestion-pipeline, Room-schema, or formula changes; no new Health Connect permissions.
- No new SpO2/BodyTemp detail screens — those two Vitals top cards get `onClick = null`.
- No "at least one visible" enforcement anywhere (explicitly decided against).
- Top-cards section always renders above the trends section; the four-in-grid layout wraps to a second row of 2 when 3-4 cards are visible — identical to Dashboard's `ReorderableCardGrid`, driven by DashboardCardCatalog only.