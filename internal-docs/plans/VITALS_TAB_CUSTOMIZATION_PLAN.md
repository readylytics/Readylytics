# Customizable Vitals Tab — Plan

**Status:** PLAN — awaiting approval. No implementation code has been written.
**Date:** 2026-08-13
**Branch:** `claude/vitals-tab-customization-1mza20`
**Scope:** `feature/vitals` (primary), `feature/dashboard` (shared-component generalization, no behavior change), `feature/settings` (global display-mode switch), `core/model`, `core/ui`, `app` (Proto DataStore, Hilt DI, local backup/restore).

This document is self-contained: it does not assume the reader has access to any prior conversation. It restates the goal, documents the exact current behavior of the affected code (with file paths and line numbers as of this writing), states every product decision that was already clarified with the user, and specifies the implementation in enough detail to execute without further design discussion. Where existing code is quoted, it reflects the state of the repository on branch `claude/vitals-tab-customization-1mza20` at the time this plan was written.

---

## 1. Goal

Readylytics is an offline-first Android health app (Kotlin, Jetpack Compose/Material 3, Room, Health Connect, WorkManager, DataStore, MVVM + Clean Architecture, multi-module Gradle). It has a Dashboard tab and a Vitals tab, among others. The Dashboard tab already supports full user customization: cards can be shown/hidden, dragged into a new order, and switched between gauge/bar/value display modes, with a draft-then-save-or-discard editing flow. The Vitals tab does not — its two top-row cards (HRV, RHR) and its four trend charts below them (HRV, RHR, SpO2, Body Temperature) are hardcoded in source, in a fixed order, with no way to hide, reorder, or change how they render.

**The request:** bring the same customization capability to the Vitals tab, reusing the Dashboard's existing pattern (a "Customize" button, an add/hide picker, drag-and-drop reordering, and Save/Discard), with these specifics:

1. The two top cards (currently HRV and RHR) become customizable: the user can additionally show SpO2 and/or Body Temperature there. HRV and RHR remain the defaults.
2. The top cards can be switched between gauge, bar, and value display modes, exactly like Dashboard cards.
3. The existing global "apply this display mode to every card" switch in Settings must also affect the Vitals top cards (today it only affects Dashboard cards).
4. The four trend charts below the top cards can be reordered and individually hidden.
5. **Constraint:** the *page-level* structure must stay fixed — the top-cards section must always render above the trend-charts section. Reordering/hiding only ever happens *within* each of the two sections; an item can never move from one section to the other, and the two sections can never swap places.

### 1.1 Decisions already clarified with the user (do not re-litigate these)

These three questions were asked and answered before this plan was written; they are binding:

| Question | Decision |
|---|---|
| Should the top row be a fixed 2-slot layout, or a variable grid (1–4 visible cards, add/hide + drag-reorder, matching Dashboard exactly)? | **Variable grid** — full parity with the Dashboard's `ReorderableCardGrid` pattern: checkbox add/hide, drag-and-drop reorder, wraps to a second row of 2 when 3–4 cards are visible. |
| Must each section (top cards, trend charts) keep at least one item visible? | **No minimum-visible constraint.** A user may hide every card and/or every chart in a section. No forced-visible guard is required anywhere in this feature. |
| Should the existing Settings global display-mode switch (gauge/bar/value, today Dashboard-only) become unified across both tabs, or should Vitals get its own separate switch? | **Unify into one switch.** Applying a mode in Settings updates both Dashboard cards and Vitals top cards in a single action, using the existing `DashboardCardCatalog.applyGlobalDisplayMode` logic against both configuration lists. |

---

## 2. Current behavior (before this change)

### 2.1 Module and file map

Vitals feature module: `feature/vitals`, package `app.readylytics.health.feature.vitals`.

| File | Role |
|---|---|
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt` | Top-level `VitalsRoute` + `VitalsScreen` composables — the tab itself |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsGaugeRow.kt` | The two top gauge cards (HRV, RHR) |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt` | The four Vico trend charts below the cards |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt` | Pure state-building functions: `VitalsChartSeries`, `VitalsPresentationState`, `buildVitalsChartSeries`, `buildVitalsPresentationState` |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt` | ViewModel + `VitalsUiState` |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/UniversalVitalsMetricCard.kt` | Vitals-specific wrapper around the shared `UniversalMetricCard`, currently **hardcoded to GAUGE mode only** |

Nav wiring: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHost.kt` (~line 348): `composable<TabDestination.Vitals> { VitalsRoute(...) }`.

### 2.2 `VitalsScreen.kt` — current structure

`VitalsScreen` renders, top to bottom, inside a `Column` with a fixed header (`ScreenHeaderSection` + `DateSwitcher`) and a scrollable body:

```kotlin
Column(
    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
        .padding(top = MaterialTheme.spacing.pageSectionGapSmall, bottom = MaterialTheme.spacing.pageBottom),
) {
    VitalsGaugeRow(
        isLoading = uiState.isLoading,
        presentation = uiState.presentation,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToRhr = onNavigateToRhr,
    )
    SectionHeader(title = stringResource(R.string.label_physiological_trends), enabled = !uiState.isLoading)
    SingleChoiceSegmentedButtonRow(...) { /* TimeRange picker: 7/30/90 days etc. */ }
    VitalsTrendSection(
        chartInputs = uiState.chartInputs(),
        chartScrollState = chartScrollState,
        chartZoomState = chartZoomState,
        parentScrollInProgress = { scrollState.isScrollInProgress },
    )
    StatusLegend()
}
```

A single shared `VicoScrollState`/`VicoZoomState` pair (`ChartDefaults.rememberChartState(rangeDays = ..., key = "vitals-${uiState.selectedRange}")`) keeps all trend charts panning/zooming in sync; this must keep working after charts become reorderable.

### 2.3 `VitalsGaugeRow.kt` — current top-row cards

A plain `Row` with exactly two `UniversalVitalsMetricCard` calls, **hardcoded order: RHR first, then HRV**, both `Modifier.weight(1f)`:

- **RHR card:** `rawValue` is a custom dial-fill fraction computed from `RHR_DIAL_FLOOR = 30` and `RHR_BASELINE_FILL = 0.5f`: `((currentRhr - 30) / (baselineRhr - 30) * 0.5f).coerceIn(0f, 1f)`. `maxValue = 1f`. Value text is the raw current RHR in bpm. Status/delta come from `presentation.rhr: PersonalBaselineAssessment`.
- **HRV card:** `rawValue = currentHrv`, `maxValue = if (baselineHrv > 0) baselineHrv * 2f else 150f`. Status/delta come from `presentation.hrv: PersonalBaselineAssessment`.

Both cards call `UniversalVitalsMetricCard(...)` with `onClick = onNavigateToRhr` / `onNavigateToHrv` respectively (navigates to the RHR/HRV detail screens).

### 2.4 `UniversalVitalsMetricCard.kt` — current (GAUGE-only) wrapper

```kotlin
@Composable
internal fun UniversalVitalsMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    rawValue: Float?,
    maxValue: Float,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    UniversalMetricCard(
        presentation = UniversalMetricPresentation(
            title = title, valueText = valueText, unitText = unitText, secondaryText = secondaryText,
            status = status, tooltip = tooltip,
            accessibilityDescription = "$title: $valueText $unitText",
            visual = UniversalMetricScalePreparer.score(rawValue, 0f, maxValue),
        ),
        specification = UniversalMetricCardSpec(
            supportedModes = listOf(UniversalCardDisplayMode.GAUGE), // <-- hardcoded, only mode
            usesDeltaPill = secondaryText != null,
        ),
        requestedMode = UniversalCardDisplayMode.GAUGE, // <-- hardcoded
        modifier = modifier,
        onClick = onClick,
    )
}
```

There is no `isEditing`/`onModeSelected` plumbing at all — this file must be generalized (Section 5.7).

### 2.5 `VitalsTrendSection.kt` — current trend charts

A plain `Column` with **four hardcoded, unconditional `TrendCard`/`TrendChart` blocks, in this fixed order**, each wrapped in `CardLoader`/`SkeletonCard`:

1. **HRV Trend** — `points = chartSeries.hrv`, testTag `"HrvTrendChart"`, title `R.string.label_hrv_rmssd`
2. **Resting HR Trend** — `points = chartSeries.rhr`, testTag `"RestingHeartRateTrendChart"`, title `R.string.label_resting_heart_rate`
3. **SpO2 Trend** — `points = chartSeries.spo2`, testTag `"OxygenSaturationTrendChart"`, title `R.string.label_oxygen_saturation`, fixed baseline 95%, `minYOverride = 90.0`, `maxYOverride = 100.0`
4. **Body Temperature Trend** — `points = chartSeries.bodyTemp`, testTag `"BodyTemperatureTrendChart"`, title `CoreUiR.string.label_body_temperature`, unit depends on `presentation.bodyTempUnitSystem`

All four share the single `chartScrollState`/`chartZoomState` passed in from `VitalsScreen`. Order today is purely "the order these four `CardLoader` blocks appear in the `Column`" — there is no data-driven ordering or visibility mechanism of any kind.

### 2.6 `VitalsStateFactory.kt` — current state shapes

```kotlin
data class VitalsChartSeries(
    val hrv: List<DailyDataPoint>, val rhr: List<DailyDataPoint>,
    val spo2: List<DailyDataPoint>, val bodyTemp: List<DailyDataPoint>,
    val hrvPeriodSummary: PeriodAverageSummary? = null, /* ...rhr/spo2/bodyTemp period summaries... */
    val historicalRhrBaseline: List<DailyDataPoint> = emptyList(), /* ...hrv baseline, zone bands, bucket zone bands... */
)

data class VitalsPresentationState(
    val hrv: PersonalBaselineAssessment,
    val rhr: PersonalBaselineAssessment,
    val spo2: Spo2Assessment,
    val baselineBodyTemp: Float?,       // baseline only — NOT a current-value assessment
    val bodyTempUnitSystem: UnitSystem,
)
```

Note: `spo2: Spo2Assessment` already carries a *current* SpO2 value + status (`value: Float?`, `status: MetricStatus`, `zoneBands`), built via `assessSpo2(summary?.avgSleepingSpo2)` — this is directly reusable for a new SpO2 top card. Body temperature, however, only has a *baseline* here (`baselineBodyTemp: Float?`), not a current-value+status assessment — this plan adds one (Section 5.1).

`VitalsUiState` (in `VitalsViewModel.kt`) is:

```kotlin
data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = ...,
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
)
```

`VitalsViewModel` constructor currently injects: `DailySummaryRepository`, `DailyMetricsRepository`, `UserPreferencesReader` (as `settingsRepo`), `SelectedDateStore`, `ForegroundSyncGateway`, `SavedStateHandle`, `BodyTemperatureBaselineProvider`, `@IoDispatcher CoroutineDispatcher`. It does **not** inject `HealthConnectRepository` or any card-configuration repository today — both must be added (Section 6.6).

### 2.7 SpO2 / Body Temperature already exist end-to-end in the data/domain layers

No new ingestion or domain-scoring work is needed — this feature is UI/customization-layer only. Confirmed already present:

- Room entities/DAOs: `core/model/.../data/local/entity/OxygenSaturationRecordEntity.kt`, `BodyTemperatureRecordEntity.kt`; DAOs `OxygenSaturationRecordDao.kt`, `BodyTemperatureRecordDao.kt`.
- Health Connect mappers: `core/healthconnect/.../data/mapper/OxygenSaturationDataMapper.kt`, `BodyTemperatureDataMapper.kt`.
- Permissions: `READ_OXYGEN_SATURATION` / `READ_BODY_TEMPERATURE` already declared in `AndroidManifest.xml`, listed in `PermissionBullets.kt`, and checked via `HealthConnectRepository.hasOxygenSaturationPermission()` / `hasBodyTemperaturePermission()` (already used by `CardManagementDelegate`, see Section 2.9).
- Aggregated fields on `DailySummary` (`core/model/.../domain/model/DailySummary.kt`): `avgSleepingSpo2: Float?`, `avgSleepingBodyTemp: Float?` (Celsius; converted for display via `UnitConverter.celsiusToDisplayTemperature`).
- Baseline service already injected in `VitalsViewModel`: `BodyTemperatureBaselineProvider` / `BodyTemperatureBaselineCalculator` (`core/model/.../domain/service/`).
- `assessSpo2(value: Float?): Spo2Assessment` already exists in `core/model/.../domain/model/VitalAssessment.kt` and is already used in `VitalsStateFactory`.
- **Both `CardId.OXYGEN_SATURATION` and `CardId.BODY_TEMPERATURE` already exist and are already full Dashboard citizens** — they have `DashboardCardSpec` entries, are in `DEFAULT_DASHBOARD_CARDS`, are rendered via `DashboardCardFactory`'s `ConfigurableMetricCard`, and clicking either card on the Dashboard already navigates to Vitals (`onNavigateToVitals`) — this confirms Vitals is meant to be their natural home screen.

### 2.8 The Dashboard's existing customization pattern (the pattern being reused)

This is documented in full because the entire Vitals feature is built by reusing/generalizing these exact pieces.

**Entry point** — no separate screen; toggled by local state in `DashboardScreen.kt` (`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`):

```kotlin
var showCardManagement by rememberSaveable { mutableStateOf(false) }
```

A `FilledTonalButton` labeled `R.string.action_customize` appears at the bottom of the `LazyColumn`, only `if (!uiState.isManagingCards)`. Clicking it sets `showCardManagement = true` and calls `viewModel::toggleCardManagement`, which simultaneously (a) opens a `ModalBottomSheet` (`CardManagementBottomSheet`, add/hide) and (b) puts the on-screen `ReorderableCardGrid` into `isEditing = true` (drag reorder / remove / display-mode change). While editing, an `EditModeFab` (Done/Cancel) floats bottom-end.

**Domain model** (`core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/`):

`CardConfiguration.kt`:
```kotlin
@Serializable
enum class CardId {
    SLEEP_SCORE, READINESS, STEPS, HRV, SLEEP_RHR, SLEEP_DURATION, SLEEP_ARCHITECTURE,
    STRAIN_RATIO, RAS_DAILY, CIRCADIAN_CONSISTENCY, RESTING_HR, RECOVERY_INDEX,
    ACUTE_CHRONIC_RATIO, SLEEP_EFFICIENCY, HEART_RATE, WEIGHT, BODY_FAT, BLOOD_PRESSURE,
    OXYGEN_SATURATION, AI_RECOMMENDATION, BODY_TEMPERATURE, INSIGHTS,
}

@Serializable
data class CardConfiguration(
    val cardId: CardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
)
```

`DashboardCardDisplayMode.kt`: `enum class DashboardCardDisplayMode { GAUGE, BAR, VALUE }` plus a tolerant nullable serializer (unknown/missing → null).

`DashboardCardCatalog.kt` (full file, quoted because this plan reuses it verbatim, unmodified):
```kotlin
data class DashboardCardSpec(
    val cardId: CardId,
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

object DashboardCardCatalog {
    fun spec(cardId: CardId): DashboardCardSpec? = specs[cardId]

    fun requestedMode(configuration: CardConfiguration): DashboardCardDisplayMode {
        val spec = spec(configuration.cardId) ?: return DashboardCardDisplayMode.VALUE
        val requested = configuration.requestedDisplayMode
        return if (requested != null && spec.supportedModes.contains(requested)) requested else spec.legacyDefaultMode
    }

    fun applyGlobalDisplayMode(configurations: List<CardConfiguration>, mode: DashboardCardDisplayMode): List<CardConfiguration> =
        configurations.map { config ->
            val supported = spec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun resetAllDisplayModes(configurations: List<CardConfiguration>): List<CardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    private val ALL_MODES = listOf(GAUGE, BAR, VALUE)
    private val specs: Map<CardId, DashboardCardSpec> = mapOf(
        // ...
        CardId.HRV to DashboardCardSpec(CardId.HRV, VALUE, ALL_MODES),
        CardId.RESTING_HR to DashboardCardSpec(CardId.RESTING_HR, VALUE, ALL_MODES),
        CardId.OXYGEN_SATURATION to DashboardCardSpec(CardId.OXYGEN_SATURATION, VALUE, ALL_MODES),
        CardId.BODY_TEMPERATURE to DashboardCardSpec(CardId.BODY_TEMPERATURE, VALUE, ALL_MODES),
        // ...
    )
}
```

**Critical confirmed fact:** all four `CardId`s this plan needs for the Vitals top row — `HRV`, `RESTING_HR`, `OXYGEN_SATURATION`, `BODY_TEMPERATURE` — already have `supportedModes = ALL_MODES` (GAUGE, BAR, VALUE) in this catalog. **No catalog changes are needed.** This is also why the global-mode-switch unification (Section 7) is nearly free: `applyGlobalDisplayMode`/`resetAllDisplayModes` are already generic over any `List<CardConfiguration>`.

`CardConfigurationRepository.kt`:
```kotlin
interface CardConfigurationRepository {
    fun dashboardCardConfigurations(): Flow<List<CardConfiguration>>
    suspend fun updateDashboardCardConfigurations(cards: List<CardConfiguration>)
}
```

`CardIdExtensions.kt` — plain-string `CardId.displayName()` (non-Compose contexts). The Compose string-resource version, `CardId.displayNameResId: Int` (a `@get:StringRes` extension `when` over every `CardId`), lives in `feature/dashboard/.../CardIdExtensionsUi.kt`.

**Persistence** (Proto DataStore, module `app`, package `app.readylytics.health.data.preferences`):

`app/src/main/proto/card_configurations.proto` (full file):
```proto
syntax = "proto3";
package app.readylytics.health.data.preferences;
option java_package = "app.readylytics.health.data.preferences";
option java_multiple_files = true;
option java_outer_classname = "CardConfigurationsProtoFile";

message CardConfigurationProto {
    string card_id = 1;
    bool is_visible = 2;
    int32 position = 3;
    string requested_display_mode = 4;
}
message CardConfigurationsProto {
    repeated CardConfigurationProto dashboard_cards = 1;
}
```

`CardConfigurationMapper.kt` (proto ↔ domain, tolerant enum parsing, legacy rename shim `"PAI_DAILY" -> RAS_DAILY`), `CardConfigurationsSerializer.kt` (DataStore `Serializer<CardConfigurationsProto>`, `defaultValue` built from `SettingsDefaults.DEFAULT_DASHBOARD_CARDS`), `CardConfigurationRepositoryImpl.kt` (`@Singleton`, backed by `DataStore<CardConfigurationsProto>`; on every read, **merges in any `DEFAULT_DASHBOARD_CARDS` entries missing from stored data**, so a newly-added `CardId` auto-appears appended at the end with no migration step needed).

`di/DataStoreModule.kt` (full relevant provider, quoted verbatim as the template for the new Vitals provider):
```kotlin
@Provides
@Singleton
fun provideCardConfigurationsDataStore(@ApplicationContext context: Context): DataStore<CardConfigurationsProto> =
    DataStoreFactory.create(
        serializer = CardConfigurationsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { CardConfigurationsSerializer.defaultValue },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        migrations = listOf(/* legacy Preferences-DataStore migration — NOT needed for the new Vitals store, this is a brand-new feature with no legacy format */),
        produceFile = { context.dataStoreFile("card_configurations.pb") },
    )
```

`di/RepositoryModule.kt` (~line 134):
```kotlin
@Binds
@Singleton
abstract fun bindCardConfigurationRepository(
    impl: app.readylytics.health.data.preferences.CardConfigurationRepositoryImpl,
): app.readylytics.health.domain.dashboard.CardConfigurationRepository
```

`SettingsDefaults.kt` (bottom of file): `val DEFAULT_DASHBOARD_CARDS: List<CardConfiguration> = listOf(...)` — the canonical default order/visibility for every `CardId`. Also `val LAST_GLOBAL_DISPLAY_MODE: DashboardCardDisplayMode? = null`.

**Draft/commit state machine** — `CardManagementDelegate.kt` (`feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`), quoted in full because Section 5.3 changes it:

```kotlin
data class CardManagementState(val isManagingCards: Boolean = false, val pendingConfigs: List<CardConfiguration>? = null)

sealed interface CardManagementEvent {
    data class EnterEditMode(val currentConfigs: List<CardConfiguration>) : CardManagementEvent
    data object SaveChanges : CardManagementEvent
    data object CancelChanges : CardManagementEvent
    data object ResetToDefaults : CardManagementEvent
    data class ToggleVisibility(val currentConfigs: List<CardConfiguration>, val cardId: CardId, val visible: Boolean) : CardManagementEvent
    data class ReorderCards(val currentConfigs: List<CardConfiguration>, val newOrder: List<CardConfiguration>) : CardManagementEvent
    data class DisplayModeChanged(val cardId: CardId, val mode: DashboardCardDisplayMode) : CardManagementEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class CardManagementDelegate(
    private val cardConfigRepository: CardConfigurationRepository,   // <-- Section 5.3 replaces this
    private val scope: CoroutineScope,
    private val hasBodyTemperaturePermission: suspend () -> Boolean = { true },
    private val hasStepsPermission: suspend () -> Boolean = { true },
    private val hasWeightPermission: suspend () -> Boolean = { true },
    private val hasBodyFatPermission: suspend () -> Boolean = { true },
    private val hasBloodPressurePermission: suspend () -> Boolean = { true },
    private val hasOxygenSaturationPermission: suspend () -> Boolean = { true },
) {
    private val _isManagingCards = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<CardConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<CardConfiguration>?>(null)

    private suspend fun persistConfigs(configs: List<CardConfiguration>) {
        var toPersist = configs
        if (!hasBodyTemperaturePermission()) toPersist = toPersist.filter { it.cardId != CardId.BODY_TEMPERATURE }
        if (!hasStepsPermission()) toPersist = toPersist.filter { it.cardId != CardId.STEPS }
        if (!hasWeightPermission()) toPersist = toPersist.filter { it.cardId != CardId.WEIGHT }
        if (!hasBodyFatPermission()) toPersist = toPersist.filter { it.cardId != CardId.BODY_FAT }
        if (!hasBloodPressurePermission()) toPersist = toPersist.filter { it.cardId != CardId.BLOOD_PRESSURE }
        if (!hasOxygenSaturationPermission()) toPersist = toPersist.filter { it.cardId != CardId.OXYGEN_SATURATION }
        cardConfigRepository.updateDashboardCardConfigurations(toPersist)   // <-- the ONE call site Section 5.3 generalizes
    }

    init { scope.launch { persistTrigger.filterNotNull().collect { configs -> persistConfigs(configs) } } }

    val state: StateFlow<CardManagementState> = combine(_isManagingCards, _pendingConfigs) { isManaging, pending ->
        CardManagementState(isManagingCards = isManaging, pendingConfigs = pending)
    }.stateIn(scope, SharingStarted.Lazily, CardManagementState())

    val isManagingCards: StateFlow<Boolean> = _isManagingCards.asStateFlow()
    val pendingConfigs: StateFlow<List<CardConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: CardManagementEvent) {
        when (event) {
            is CardManagementEvent.EnterEditMode -> { _pendingConfigs.value = event.currentConfigs; _isManagingCards.value = true }
            CardManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManagingCards.value = false; _pendingConfigs.value = null
            }
            CardManagementEvent.CancelChanges -> { _isManagingCards.value = false; _pendingConfigs.value = null }
            CardManagementEvent.ResetToDefaults -> { _pendingConfigs.value = SettingsDefaults.DEFAULT_DASHBOARD_CARDS }  // <-- Section 5.3 parameterizes this
            is CardManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = toggleCardVisibility(base, event.cardId, event.visible)
            }
            is CardManagementEvent.ReorderCards -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = reorderCards(base, event.newOrder)
            }
            is CardManagementEvent.DisplayModeChanged -> {
                val base = _pendingConfigs.value ?: return
                require(base.any { it.cardId == event.cardId })
                _pendingConfigs.value = base.map { if (it.cardId == event.cardId) it.copy(requestedDisplayMode = event.mode) else it }
            }
        }
    }

    // toggleCardVisibility / reorderCards private helpers: standard filter+map over CardConfiguration,
    // reorderCards re-indexes `position` 0..n and appends any cards NOT present in newOrder (hidden cards) at the end.

    // Legacy convenience facade (all delegate to onEvent): enterEditMode(), saveChanges(), cancelChanges(),
    // onToggleCardVisibility(currentConfigs, cardId, visible), onReorderCards(currentConfigs, newOrder), onResetToDefaults()
}
```

**Reading side (merge pending/committed)** — `DashboardFlowIntermediate.kt` (`feature/dashboard/.../DashboardFlowIntermediate.kt`), function `createDashboardCardStateFlow`, quoted because Section 6.6 mirrors it exactly for Vitals:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
fun createDashboardCardStateFlow(
    selectedDate: Flow<LocalDate>,
    cardManagementDelegate: CardManagementDelegate,
    cardConfigRepository: CardConfigurationRepository,
    dailySummaryRepository: DailySummaryRepository,
    healthConnectRepository: HealthConnectRepository,
): Flow<DashboardCardState> {
    val permissionGrants: Flow<List<Boolean>> = combine(
        flow { emit(healthConnectRepository.hasBodyTemperaturePermission()) },
        flow { emit(healthConnectRepository.hasStepsPermission()) },
        flow { emit(healthConnectRepository.hasWeightPermission()) },
        flow { emit(healthConnectRepository.hasBodyFatPermission()) },
        flow { emit(healthConnectRepository.hasBloodPressurePermission()) },
        flow { emit(healthConnectRepository.hasOxygenSaturationPermission()) },
    ) { results -> results.toList() }

    return combine(
        cardManagementDelegate.isManagingCards,
        cardManagementDelegate.pendingConfigs,
        cardConfigRepository.dashboardCardConfigurations(),
        selectedDate.flatMapLatest { /* last sleep session lookup — Dashboard-specific, NOT needed for Vitals */ },
        permissionGrants,
    ) { isManaging, pendingConfig, cardConfig, session, grants ->
        fun List<CardConfiguration>.filteredForPermission(): List<CardConfiguration> {
            var list = this
            if (!grants[0]) list = list.filter { it.cardId != CardId.BODY_TEMPERATURE }
            if (!grants[5]) list = list.filter { it.cardId != CardId.OXYGEN_SATURATION }
            // ...steps/weight/bodyFat/bloodPressure filters, not relevant to Vitals' 4-card set...
            return list
        }
        DashboardCardState(
            isManagingCards = isManaging,
            cardConfiguration = cardConfig.filteredForPermission(),
            lastSleepSession = session,
            pendingConfiguration = pendingConfig?.filteredForPermission(),
        )
    }
}
```

`DashboardViewModel.kt` then does, at the composition point:
```kotlin
cardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfiguration
```
— **this is the exact "draft-if-editing, else committed" merge rule** Section 6.6 replicates for Vitals. `DashboardViewModel` also constructs the delegate:
```kotlin
private val cardManagementDelegate = CardManagementDelegate(
    cardConfigRepository, viewModelScope,
    hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
    hasStepsPermission = { healthConnectRepository.hasStepsPermission() },
    hasWeightPermission = { healthConnectRepository.hasWeightPermission() },
    hasBodyFatPermission = { healthConnectRepository.hasBodyFatPermission() },
    hasBloodPressurePermission = { healthConnectRepository.hasBloodPressurePermission() },
    hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
)
val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards
```
and exposes forwarding methods `toggleCardManagement()` (Done semantics: enter edit mode if not editing, else save), `onCancelCardManagement()`, `onToggleCardVisibility(cardId, visible)`, `onReorderCards(newOrder)`, `onResetToDefaults()`, `onCardDisplayModeChanged(cardId, mode)`.

**Drag-and-drop UI** — `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGrid.kt` (full mechanics described; Section 5.4/5.5 depend on this exactly):

```kotlin
private val FULL_WIDTH_CARDS = setOf(CardId.STEPS, CardId.INSIGHTS, CardId.AI_RECOMMENDATION)

@Immutable data class CardConfigurationsList(val items: List<CardConfiguration>)
@Immutable data class CardDataMap(val map: Map<CardId, @Composable (CardConfiguration) -> Unit>)

@Composable
fun ReorderableCardGrid(
    cardConfigurations: CardConfigurationsList,
    cardDataMap: CardDataMap,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController? = null,
) { /* ... */ }
```

Mechanics: cards render in a `Column` of `Row`s (pairs of half-width cards) or full-width `Box`es for ids in `FULL_WIDTH_CARDS`. A long-press (`detectDragGesturesAfterLongPress`) on the root `Column` starts a drag if the press falls within a 48dp drag-handle `Box`'s recorded bounds (drag detection lives on the *root*, not the handle, so an in-progress drag survives the dragged card being re-parented into a different `leftCard`/`rightCard`/full-width slot mid-gesture). A bottom "delete drop zone" `Surface` appears only in edit mode; dropping a card there calls `onCardRemove`, otherwise `onCardReorder` is called with re-indexed `position` values. All slot bounds are recorded in one shared root-local coordinate space.

`DragController.kt` (`core/ui/.../components/reorder/DragController.kt`), full file — the pure `@Stable` state holder driving the above, **currently hardcoded to `CardId`** as its item-key type:

```kotlin
@Stable
class DragController(initialOrder: List<CardId>) {   // <-- Section 5.5 genericizes this to <T : Any>
    var pendingOrder: List<CardId> by mutableStateOf(initialOrder); private set
    var draggedCardId: CardId? by mutableStateOf(null); private set
    var dragOffset: Offset by mutableStateOf(Offset.Zero); private set
    val slotBounds: SnapshotStateMap<CardId, Rect> = mutableStateMapOf()
    var hoveringDeleteZone: Boolean by mutableStateOf(false); private set

    fun updateSlotBounds(id: CardId, rect: Rect) { slotBounds[id] = rect }
    fun onDragStart(id: CardId) { draggedCardId = id; dragOffset = Offset.Zero; hoveringDeleteZone = false }
    fun onDrag(delta: Offset, deleteZoneTop: Float?) { /* 2-D hit test + offset pre-compensation, see full listing above in Section 2.8 excerpt */ }
    fun onDragEnd(): DragEndResult { /* returns draggedId, finalOrder, delete flag; resets state */ }
    fun syncFromUpstream(newOrder: List<CardId>) { if (draggedCardId == null) pendingOrder = newOrder }
}
data class DragEndResult(val draggedId: CardId?, val finalOrder: List<CardId>, val delete: Boolean)
```

(The full `onDrag`/`onDragEnd` bodies were read in full during research; they contain no `CardId`-specific logic beyond the type parameter itself — every operation is generic map/list/hit-test logic. This is what makes genericizing safe.)

**Add/hide picker** — `CardManagementBottomSheet.kt` (`feature/dashboard/.../CardManagementBottomSheet.kt`): a `ModalBottomSheet` containing a header (`R.string.manage_cards` title + a "Reset to defaults" `IconButton`, icon `Icons.Outlined.RestartAlt`, `R.string.action_reset_to_defaults`), a `LazyColumn` of `ListItem`s (each: `Text(stringResource(card.cardId.displayNameResId))` + trailing `Checkbox(checked = card.isVisible, onCheckedChange = ...)`, sorted by `position`), and a bottom `Button(R.string.action_done)` that just dismisses the sheet (it does not itself persist — persistence happens later via the FAB's Done).

**Done/Cancel FAB** — `EditModeFab.kt` (`feature/dashboard/.../EditModeFab.kt`), fully generic (no `CardId`/dashboard-specific types at all — reused as-is, unmodified, for Vitals):
```kotlin
@Composable
fun EditModeFab(isVisible: Boolean, onDoneClick: () -> Unit, onCancelClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = isVisible, enter = slideInVertically{it}+fadeIn(), exit = slideOutVertically{it}+fadeOut(), modifier = modifier) {
        Column(horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(onClick = onCancelClick) { Icon(Icons.Filled.Close, ...) }
            ExtendedFloatingActionButton(onClick = onDoneClick, icon = { Icon(Icons.Filled.Check, ...) }, text = { Text(stringResource(R.string.action_done)) })
        }
    }
}
```

**Universal card rendering primitive** — `core/ui/.../components/metriccard/UniversalMetricCard.kt` (already generic, GAUGE/BAR/VALUE, used unmodified by both Dashboard and the new Vitals wrapper):
```kotlin
@Composable
fun UniversalMetricCard(
    presentation: UniversalMetricPresentation,
    specification: UniversalMetricCardSpec,   // data class UniversalMetricCardSpec(val supportedModes: List<UniversalCardDisplayMode>, val usesDeltaPill: Boolean = false)
    requestedMode: UniversalCardDisplayMode,
    isEditing: Boolean = false,
    onModeSelected: (UniversalCardDisplayMode) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) { /* renders title row (+ a 3-dot DropdownMenu mode-switcher when isEditing && supportedModes.size > 1) then GAUGE/BAR/VALUE body via requestedMode */ }
```
The title-row mode-switcher (`UniversalDisplayModeMenu`) is already built-in: when `isEditing == true` and the card supports more than one mode, a `MoreVert` icon opens a `DropdownMenu` listing every `specification.supportedModes` entry; selecting one calls `onModeSelected(mode)`. **No new rendering code is needed for gauge/bar/value switching — only correct wiring of `supportedModes`/`requestedMode`/`isEditing`/`onModeSelected` into this existing composable.**

**Dashboard's per-card wrapper** — `DashboardCardFactory.kt`, the pattern Section 5.6/5.7 mirrors:
```kotlin
@Composable
private fun ConfigurableMetricCard(
    cardId: CardId,
    presentation: UniversalMetricPresentation?,
    configuration: CardConfiguration,
    isEditing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    skeleton: @Composable () -> Unit = { MetricCardSkeleton() },
) {
    CardLoader(isLoading = isLoading, skeleton = skeleton, content = {
        val spec = DashboardCardCatalog.spec(cardId)
        if (presentation != null && spec != null) {
            UniversalMetricCard(
                presentation = presentation,
                specification = spec.toUniversalSpec(),
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode -> onCardDisplayModeChanged(cardId, mode.toDashboardMode()) },
                onClick = if (isEditing) null else onClick,
            )
        }
    })
}
```
`toUniversalSpec()`/`toUniversalMode()`/`toDashboardMode()` live in `DashboardToUniversalMapper.kt` (`feature/dashboard`):
```kotlin
internal fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode = when (this) { GAUGE -> GAUGE; BAR -> BAR; VALUE -> VALUE }
internal fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode = when (this) { GAUGE -> GAUGE; BAR -> BAR; VALUE -> VALUE }
internal fun DashboardCardSpec.toUniversalSpec(): UniversalMetricCardSpec =
    UniversalMetricCardSpec(supportedModes = supportedModes.map { it.toUniversalMode() }, usesDeltaPill = cardId.usesDeltaPill())
internal fun CardId.usesDeltaPill(): Boolean = when (this) {
    SLEEP_SCORE, READINESS, HRV, SLEEP_RHR, RESTING_HR, STRAIN_RATIO, BODY_TEMPERATURE -> true
    else -> false
}
```
Note `HRV` and `RESTING_HR` (the two Vitals default cards) and `BODY_TEMPERATURE` already resolve `usesDeltaPill = true` here — Vitals' RHR/HRV cards already render a delta pill today (Section 2.3), so this mapping is consistent with existing Vitals behavior once reused.

**SpO2/Body-Temp presentation source (for reuse, not copy)** — `feature/dashboard/.../usecase/DashboardMetricPresentationFactory.kt`, the pattern Section 5.1/5.6 lift into a shared, reusable domain function:
```kotlin
// OXYGEN SATURATION (lines ~370-405)
val spo2Assessment = assessSpo2(summary?.avgSleepingSpo2)
val roundedSpo2 = spo2Assessment.value?.roundToInt()
val spo2ValueText = roundedSpo2?.let { "$it%" } ?: unavailableValueText
val spo2Visual = UniversalMetricScalePreparer.score(spo2Assessment.value, 80f, 100f)
map[CardId.OXYGEN_SATURATION] = UniversalMetricPresentation(
    title = ..., valueText = spo2ValueText, unitText = "", secondaryText = null,
    status = spo2Assessment.status, tooltip = ..., accessibilityDescription = ..., visual = spo2Visual,
)

// BODY TEMPERATURE (lines ~454-506)
val bodyTempCelsius = summary?.avgSleepingBodyTemp
val bodyTempDisplay = bodyTempCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) }
val bodyTempStatus = bodyTempStatus(bodyTempCelsius, bodyTempBaseline, preferences.bodyTempElevatedThresholdCelsius)  // <-- PRIVATE fn, Section 5.1 extracts this
val bodyTempValueText = bodyTempDisplay?.let { "%.1f".format(it) } ?: unavailableValueText
val bodyTempSecondaryText = when {
    bodyTempCelsius == null -> null
    bodyTempBaseline == null -> stringResource(body_temperature_calibrating)
    else -> { val d = bodyTempCelsius - bodyTempBaseline; val dd = UnitConverter.celsiusDeltaToDisplayDelta(d, unitSystem); "${if (dd>=0f) "+" else ""}%.1f°".format(dd) }
}
val bodyTempVisual = UniversalMetricScalePreparer.score(bodyTempDisplay, celsiusToDisplay(35.5f, unitSystem), celsiusToDisplay(39f, unitSystem))
map[CardId.BODY_TEMPERATURE] = UniversalMetricPresentation(title = ..., valueText = bodyTempValueText, unitText = bodyTempUnitLabel, secondaryText = bodyTempSecondaryText, status = bodyTempStatus, tooltip = ..., accessibilityDescription = ..., visual = bodyTempVisual)

private fun bodyTempStatus(value: Float?, baseline: Float?, thresholdCelsius: Float): MetricStatus = when {
    value == null -> MetricStatus.CALIBRATING
    baseline == null -> MetricStatus.CALIBRATING
    value - baseline >= thresholdCelsius -> MetricStatus.WARNING   // "elevated" flag, deliberately not OPTIMAL/POOR — see inline comment: "elevated body temperature is a deviation flag, not a good/bad score"
    else -> MetricStatus.NEUTRAL
}
```
(`bodyTempStatus`'s exact branch values should be re-read from the live file at implementation time — the important, confirmed fact is that it is a small, pure, `private` function taking `(value: Float?, baseline: Float?, thresholdCelsius: Float): MetricStatus`, with no Android/Compose dependency, currently trapped inside a `feature/dashboard`-only class.)

### 2.9 Settings global display-mode switch (the switch that must be unified)

`feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/DashboardCardsSettingsViewModel.kt` (full relevant logic):
```kotlin
@HiltViewModel
class DashboardCardsSettingsViewModel @Inject constructor(
    private val settingsReader: UserPreferencesReader,
    private val displaySettings: DisplaySettings,
    private val cardConfigurationRepository: CardConfigurationRepository,   // <-- Section 7 adds a second repository param here
) : ViewModel() {
    // ... noticeDismissed / currentGlobalMode / transientState / uiState wiring, a one-time confirmation dialog gated by
    // UserPreferences.bulkDisplayModeNoticeDismissed, driven by SettingsEvent.DashboardGlobalDisplayMode{ApplyRequested,ResetRequested,Confirmed,DialogDismissed} ...

    private suspend fun applyGlobalMode(mode: DashboardCardDisplayMode) {
        val current = cardConfigurationRepository.dashboardCardConfigurations().first()
        val updated = DashboardCardCatalog.applyGlobalDisplayMode(current, mode)
        cardConfigurationRepository.updateDashboardCardConfigurations(updated)
        displaySettings.updateLastGlobalDisplayMode(mode)
    }

    private suspend fun resetAllModes() {
        val current = cardConfigurationRepository.dashboardCardConfigurations().first()
        val updated = DashboardCardCatalog.resetAllDisplayModes(current)
        cardConfigurationRepository.updateDashboardCardConfigurations(updated)
        displaySettings.updateLastGlobalDisplayMode(null)
    }
}
```
Persistence: `lastGlobalDisplayMode: DashboardCardDisplayMode?` field on `UserPreferences` (`core/model/.../data/preferences/UserPreferences.kt`, default `SettingsDefaults.LAST_GLOBAL_DISPLAY_MODE = null`); written via `UIPreferences.updateLastGlobalDisplayMode()` (`app/.../data/preferences/UIPreferences.kt`) into the `UserPreferencesProto` DataStore's `lastGlobalDisplayMode` proto enum field. UI: `feature/settings/.../DashboardCardsSettings.kt`, a `DashboardCardsSettingsSection` composable with a `DropdownPreferenceItem` (Value/Gauge/Bar) + Apply/Reset buttons + a `GlobalDisplayModeConfirmDialog`.

### 2.10 Local backup/restore

`app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt` (constructor injects `CardConfigurationRepository`; `writePreferences()` calls `cardConfigurationRepository.dashboardCardConfigurations().first()` and serializes the result into the backup file) and the equivalent `LocalRestoreManager.kt` both round-trip Dashboard card configuration today. Section 8 adds the equivalent for the new Vitals repository.

---

## 3. Architecture decisions for this feature (reuse over new code)

1. **Reuse `CardId`/`CardConfiguration` for the Vitals top row**, rather than inventing a parallel id/config type. `HRV`, `RESTING_HR`, `OXYGEN_SATURATION`, `BODY_TEMPERATURE` already exist with full `ALL_MODES` catalog entries (Section 2.8) — zero catalog changes needed, and `DashboardCardCatalog.applyGlobalDisplayMode`/`requestedMode` work unmodified against a Vitals-scoped list.
2. **Persist the Vitals layout in its own Proto DataStore file**, separate from the Dashboard's — a user may legitimately want the same metric shown in a different order/mode/visibility on each tab. Structurally identical to `CardConfigurationRepositoryImpl` (Section 5.2).
3. **Generalize `CardManagementDelegate` minimally** instead of writing a second ~230-line copy of the draft/save/cancel/reset/mode-change state machine for Vitals cards. Its only two points of concrete coupling to "dashboard" are (a) one write call `cardConfigRepository.updateDashboardCardConfigurations(toPersist)` and (b) a hardcoded default list `SettingsDefaults.DEFAULT_DASHBOARD_CARDS` inside the `ResetToDefaults` branch. Both become constructor parameters (Section 5.3). This is a behavior-preserving refactor for Dashboard — verified by the existing `CardManagementDelegateTest` continuing to pass unmodified.
4. **Add a second, smaller delegate for the trend-chart section** (`VitalsChartManagementDelegate`), because charts have no display mode and use a different id space (`VitalsChartId`, not `CardId`) — reusing the generalized `CardManagementDelegate` here isn't type-safe (it's specific to `CardConfiguration`/`CardId`). This new delegate mirrors the same 6-event shape minus `DisplayModeChanged` and minus HC-permission filtering (charts aren't individually permission-gated the way optional metric cards are — SpO2/BodyTemp *cards* are permission-gated per Section 5.9, but their *trend charts* were already rendering unconditionally before this change and can keep doing so; hiding a chart is a pure user preference, not a permission gate).
5. **Genericize `DragController`** from `DragController` (hardcoded to `CardId`) to `DragController<T : Any>` (Section 5.5). `ReorderableCardGrid` keeps using `DragController<CardId>` — zero behavior change, confirmed by the existing `ReorderableCardGridThresholdTest` passing unmodified. Add a new single-column composable (`ReorderableChartList`, Section 5.5) reusing `DragController<VitalsChartId>` for the trend-chart section, since charts are always full-width (one per row) and don't need the paired-row layout branch `ReorderableCardGrid` has for Dashboard's mixed-width grid.
6. **Extract the Body Temperature status logic to the domain layer** (Section 5.1) so Dashboard and the new Vitals card factory don't duplicate the elevated-temperature threshold logic. This also satisfies the project's "avoid duplicating implementations" convention.
7. **Reuse `EditModeFab` completely unmodified** — it has no Dashboard-specific types.
8. **Generalize `UniversalVitalsMetricCard`** (Section 5.7) from its current GAUGE-only hardcoding to accept `supportedModes`/`requestedMode`/`isEditing`/`onModeSelected`, mirroring `ConfigurableMetricCard`'s call into `UniversalMetricCard` — no new rendering primitive needed, `UniversalMetricCard` already supports all three modes and already has the built-in mode-switcher dropdown (Section 2.8).

---

## 4. New domain model & persistence

All new files below live in `core/model` unless stated otherwise, in a new package `app.readylytics.health.domain.vitals` (mirroring the existing `app.readylytics.health.domain.dashboard` package).

### 4.1 `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartId.kt` (new file)

```kotlin
package app.readylytics.health.domain.vitals

import kotlinx.serialization.Serializable

@Serializable
enum class VitalsChartId {
    HRV_TREND,
    RHR_TREND,
    SPO2_TREND,
    BODY_TEMP_TREND,
}
```

### 4.2 `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartConfiguration.kt` (new file)

```kotlin
package app.readylytics.health.domain.vitals

import kotlinx.serialization.Serializable

@Serializable
data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    val isVisible: Boolean = true,
    val position: Int = 0,
)
```
(No display-mode field — trend charts have no gauge/bar/value concept.)

### 4.3 `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsLayoutRepository.kt` (new file)

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

### 4.4 `SettingsDefaults.kt` additions (existing file: `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`)

Add, near the existing `DEFAULT_DASHBOARD_CARDS`:

```kotlin
val DEFAULT_VITALS_CARDS: List<CardConfiguration> =
    listOf(
        CardConfiguration(CardId.RESTING_HR, isVisible = true, position = 0),
        CardConfiguration(CardId.HRV, isVisible = true, position = 1),
        CardConfiguration(CardId.OXYGEN_SATURATION, isVisible = false, position = 2),
        CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = false, position = 3),
    )

val DEFAULT_VITALS_CHARTS: List<VitalsChartConfiguration> =
    listOf(
        VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
        VitalsChartConfiguration(VitalsChartId.RHR_TREND, isVisible = true, position = 1),
        VitalsChartConfiguration(VitalsChartId.SPO2_TREND, isVisible = true, position = 2),
        VitalsChartConfiguration(VitalsChartId.BODY_TEMP_TREND, isVisible = true, position = 3),
    )
```

**Order rationale:** `DEFAULT_VITALS_CARDS` is ordered RHR-then-HRV to match the *current* hardcoded `VitalsGaugeRow` visual order exactly (Section 2.3 confirms RHR renders first today) — this avoids a silent layout shift for existing users on upgrade. `DEFAULT_VITALS_CHARTS` matches the current `VitalsTrendSection` order exactly (HRV, RHR, SpO2, BodyTemp — Section 2.5). SpO2 and Body Temperature top cards default to `isVisible = false`, matching the explicit product requirement ("keep RHR and HRV as default").

### 4.5 `app/src/main/proto/vitals_layout_configurations.proto` (new file)

```proto
syntax = "proto3";

package app.readylytics.health.data.preferences;

option java_package = "app.readylytics.health.data.preferences";
option java_multiple_files = true;
option java_outer_classname = "VitalsLayoutConfigurationsProtoFile";

message VitalsCardConfigurationProto {
    string card_id = 1;
    bool is_visible = 2;
    int32 position = 3;
    string requested_display_mode = 4;
}

message VitalsChartConfigurationProto {
    string chart_id = 1;
    bool is_visible = 2;
    int32 position = 3;
}

message VitalsLayoutConfigurationsProto {
    repeated VitalsCardConfigurationProto vitals_cards = 1;
    repeated VitalsChartConfigurationProto trend_charts = 2;
}
```

### 4.6 `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutMapper.kt` (new file)

Mirrors `CardConfigurationMapper.kt` exactly, but produces two mapping functions instead of one:

```kotlin
package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId

object VitalsLayoutMapper {
    // Card side: reuse CardConfigurationMapper's exact tolerant-parsing logic for card_id /
    // requested_display_mode (same enum types, same tolerant-unknown-value-drops-to-null rule).
    fun toProto(config: CardConfiguration): VitalsCardConfigurationProto =
        VitalsCardConfigurationProto.newBuilder()
            .setCardId(config.cardId.name)
            .setIsVisible(config.isVisible)
            .setPosition(config.position)
            .setRequestedDisplayMode(config.requestedDisplayMode?.name.orEmpty())
            .build()

    fun toDomain(proto: VitalsCardConfigurationProto): CardConfiguration? {
        val cardId = runCatching { CardId.valueOf(proto.cardId) }.getOrNull() ?: return null
        val mode = proto.requestedDisplayMode.takeIf { it.isNotEmpty() }
            ?.let { runCatching { DashboardCardDisplayMode.valueOf(it) }.getOrNull() }
        return CardConfiguration(cardId, proto.isVisible, proto.position, mode)
    }

    // Chart side: same tolerant-unknown-enum-drops-the-row rule, no display mode.
    fun toProto(config: VitalsChartConfiguration): VitalsChartConfigurationProto =
        VitalsChartConfigurationProto.newBuilder()
            .setChartId(config.chartId.name)
            .setIsVisible(config.isVisible)
            .setPosition(config.position)
            .build()

    fun toDomain(proto: VitalsChartConfigurationProto): VitalsChartConfiguration? {
        val chartId = runCatching { VitalsChartId.valueOf(proto.chartId) }.getOrNull() ?: return null
        return VitalsChartConfiguration(chartId, proto.isVisible, proto.position)
    }
}
```

Confirm the exact tolerant-parsing behavior of `CardConfigurationMapper.kt` at implementation time (in particular whether an unrecognized `card_id` drops the row or falls back to a default) and match it precisely, rather than inventing new semantics.

### 4.7 `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutConfigurationsSerializer.kt` (new file)

Mirrors `CardConfigurationsSerializer.kt`:

```kotlin
package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.readylytics.health.domain.preferences.SettingsDefaults
import java.io.InputStream
import java.io.OutputStream

object VitalsLayoutConfigurationsSerializer : Serializer<VitalsLayoutConfigurationsProto> {
    override val defaultValue: VitalsLayoutConfigurationsProto =
        VitalsLayoutConfigurationsProto.newBuilder()
            .addAllVitalsCards(SettingsDefaults.DEFAULT_VITALS_CARDS.map(VitalsLayoutMapper::toProto))
            .addAllTrendCharts(SettingsDefaults.DEFAULT_VITALS_CHARTS.map(VitalsLayoutMapper::toProto))
            .build()

    override suspend fun readFrom(input: InputStream): VitalsLayoutConfigurationsProto =
        try {
            VitalsLayoutConfigurationsProto.parseFrom(input)
        } catch (exception: com.google.protobuf.InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read VitalsLayoutConfigurationsProto.", exception)
        }

    override suspend fun writeTo(t: VitalsLayoutConfigurationsProto, output: OutputStream) = t.writeTo(output)
}
```
(Match the exact exception type/import used by `CardConfigurationsSerializer.kt` at implementation time — read that file directly rather than assuming.)

### 4.8 `app/src/main/kotlin/app/readylytics/health/data/preferences/VitalsLayoutRepositoryImpl.kt` (new file)

Mirrors `CardConfigurationRepositoryImpl.kt`'s "merge in missing defaults on every read" behavior, applied independently to each of the two lists:

```kotlin
package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.preferences.SettingsDefaults
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VitalsLayoutRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<VitalsLayoutConfigurationsProto>,
) : VitalsLayoutRepository {

    override fun vitalsCardConfigurations(): Flow<List<CardConfiguration>> =
        dataStore.data.map { proto ->
            mergeWithDefaults(
                stored = proto.vitalsCardsList.mapNotNull(VitalsLayoutMapper::toDomain),
                defaults = SettingsDefaults.DEFAULT_VITALS_CARDS,
                keyOf = CardConfiguration::cardId,
            )
        }

    override suspend fun updateVitalsCardConfigurations(cards: List<CardConfiguration>) {
        dataStore.updateData { current ->
            current.toBuilder()
                .clearVitalsCards()
                .addAllVitalsCards(cards.map(VitalsLayoutMapper::toProto))
                .build()
        }
    }

    override fun vitalsChartConfigurations(): Flow<List<VitalsChartConfiguration>> =
        dataStore.data.map { proto ->
            mergeWithDefaults(
                stored = proto.trendChartsList.mapNotNull(VitalsLayoutMapper::toDomain),
                defaults = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                keyOf = VitalsChartConfiguration::chartId,
            )
        }

    override suspend fun updateVitalsChartConfigurations(charts: List<VitalsChartConfiguration>) {
        dataStore.updateData { current ->
            current.toBuilder()
                .clearTrendCharts()
                .addAllTrendCharts(charts.map(VitalsLayoutMapper::toProto))
                .build()
        }
    }

    // Appends any default entry whose key isn't already present in `stored`, at the end
    // (position continues from stored.size), so a future new CardId/VitalsChartId auto-appears
    // for existing installs with no migration step -- exactly CardConfigurationRepositoryImpl's rule.
    private fun <T, K> mergeWithDefaults(stored: List<T>, defaults: List<T>, keyOf: (T) -> K): List<T> {
        val storedKeys = stored.map(keyOf).toSet()
        val missing = defaults.filter { keyOf(it) !in storedKeys }
        return stored + missing
    }
}
```

Read `CardConfigurationRepositoryImpl.kt` directly at implementation time to confirm the exact merge/`position`-renumbering behavior (in particular whether missing defaults get re-numbered positions continuing from the existing list, or keep their default-list position value) and match it exactly rather than inventing new semantics — consistency between the two repositories' upgrade behavior matters for predictability.

### 4.9 `di/DataStoreModule.kt` — new provider (existing file: `app/src/main/kotlin/app/readylytics/health/di/DataStoreModule.kt`)

Add, alongside `provideCardConfigurationsDataStore`:

```kotlin
@Provides
@Singleton
fun provideVitalsLayoutConfigurationsDataStore(
    @ApplicationContext context: Context,
): DataStore<VitalsLayoutConfigurationsProto> =
    DataStoreFactory.create(
        serializer = VitalsLayoutConfigurationsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { VitalsLayoutConfigurationsSerializer.defaultValue },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile("vitals_layout_configurations.pb") },
    )
```
No legacy-format `DataMigration` is needed — this is a brand-new feature with no prior on-disk representation.

### 4.10 `di/RepositoryModule.kt` — new binding (existing file: `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt`)

Add, alongside `bindCardConfigurationRepository`:

```kotlin
@Binds
@Singleton
abstract fun bindVitalsLayoutRepository(
    impl: app.readylytics.health.data.preferences.VitalsLayoutRepositoryImpl,
): app.readylytics.health.domain.vitals.VitalsLayoutRepository
```

### 4.11 Health Connect permission gating for the Vitals top cards

`OXYGEN_SATURATION` and `BODY_TEMPERATURE` must not be persisted as `isVisible = true` in the Vitals card list if the corresponding Health Connect permission isn't currently granted — exactly the rule `CardManagementDelegate.persistConfigs` already enforces for the Dashboard (Section 2.8). Wire the same `HealthConnectRepository.hasOxygenSaturationPermission()` / `hasBodyTemperaturePermission()` checks into the Vitals delegate construction (Section 6.6) and into the Vitals read-side merge flow (mirroring `createDashboardCardStateFlow`'s `filteredForPermission()`, Section 2.8).

---

## 5. Shared-component generalization

### 5.1 Extract Body Temperature assessment to the domain layer

**Problem:** `bodyTempStatus(value, baseline, thresholdCelsius)` (Section 2.8) is `private` inside `feature/dashboard/.../usecase/DashboardMetricPresentationFactory.kt`. `feature/vitals` cannot call it (feature modules do not depend on each other), and duplicating the threshold logic would violate the "avoid duplicating implementations" project convention.

**Fix:** add a public function next to `assessSpo2`/`assessHrv`/`assessRhr` in `core/model/src/main/kotlin/app/readylytics/health/domain/model/VitalAssessment.kt` (the exact current content of this file is quoted in full in Section 2.8's sibling research — it already exports `Spo2Assessment`/`assessSpo2` in this identical shape):

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
): BodyTemperatureAssessment {
    val status = bodyTemperatureStatus(valueCelsius, baselineCelsius, thresholdCelsius)
    return BodyTemperatureAssessment(
        value = valueCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
        baseline = baselineCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
        status = status,
    )
}

fun bodyTemperatureStatus(value: Float?, baseline: Float?, thresholdCelsius: Float): MetricStatus =
    // Move the exact current body of DashboardMetricPresentationFactory.bodyTempStatus() here
    // verbatim -- read that private function's current implementation directly from the live
    // file at implementation time and copy its exact branches (do not rederive the thresholds
    // from this plan's paraphrase in Section 2.8).
    TODO("copy verbatim from DashboardMetricPresentationFactory.bodyTempStatus()")
```

Then:
- `DashboardMetricPresentationFactory.kt` deletes its private `bodyTempStatus` and calls the new public `bodyTemperatureStatus(...)` (or the full `assessBodyTemperature(...)`) instead — **zero behavior change**, verified by the existing Dashboard body-temp-card unit tests continuing to pass unmodified.
- The new Vitals `VitalsCardFactory` (Section 6.2) calls the same function for its Body Temperature top card.
- `UnitConverter` and `UnitSystem` are already `core/model` types (confirmed: `VitalsStateFactory.kt` already imports `app.readylytics.health.domain.preferences.UnitSystem` and `app.readylytics.health.domain.util.UnitConverter` in `core/model`/adjacent modules) — no new cross-module dependency is introduced by this extraction.

### 5.2 `VitalsPresentationState` gains a body-temperature assessment field

Existing file: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt`.

```kotlin
data class VitalsPresentationState(
    val hrv: PersonalBaselineAssessment,
    val rhr: PersonalBaselineAssessment,
    val spo2: Spo2Assessment,
    val bodyTemp: BodyTemperatureAssessment,   // NEW — replaces the old bare `baselineBodyTemp: Float?` field
    val bodyTempUnitSystem: UnitSystem,
) {
    companion object {
        fun empty(): VitalsPresentationState = buildVitalsPresentationState(
            metrics = null, summary = null, prefs = UserPreferences(), bodyTemperatureBaselineCelsius = null,
        )
    }
}
```

**Migration note:** the old field was `val baselineBodyTemp: Float?` (already display-unit-converted). Every current call site that reads `presentation.baselineBodyTemp` (confirmed in Section 2.5: `VitalsTrendSection.kt`'s Body Temperature chart block uses `presentation.baselineBodyTemp` for its `baseline`/`showBaseline`/`baselineUnavailableLabel` params) must be updated to read `presentation.bodyTemp.baseline` instead. Search the whole `feature/vitals` module for `baselineBodyTemp` before removing the old field, to catch every call site (Section 2.5 documents the one known today; confirm there are no others, e.g. in tests).

`buildVitalsPresentationState(...)` (same file) is updated to construct the new field:
```kotlin
internal fun buildVitalsPresentationState(
    metrics: DailyMetrics?, summary: DailySummary?, prefs: UserPreferences, bodyTemperatureBaselineCelsius: Float? = null,
): VitalsPresentationState {
    // ...existing hrvAssessment/rhrAssessment/spo2Assessment construction, unchanged...
    val bodyTempAssessment = assessBodyTemperature(
        valueCelsius = summary?.avgSleepingBodyTemp,
        baselineCelsius = bodyTemperatureBaselineCelsius,
        thresholdCelsius = prefs.bodyTempElevatedThresholdCelsius,
        unitSystem = prefs.unitSystem,
    )
    return VitalsPresentationState(
        hrv = hrvAssessment, rhr = rhrAssessment, spo2 = spo2Assessment,
        bodyTemp = bodyTempAssessment, bodyTempUnitSystem = prefs.unitSystem,
    )
}
```
Confirm `prefs.bodyTempElevatedThresholdCelsius` is the exact `UserPreferences` field name used by `DashboardMetricPresentationFactory` (Section 2.8 quotes it as `preferences.bodyTempElevatedThresholdCelsius`) before wiring this call.

### 5.3 Generalize `CardManagementDelegate`

Existing file: `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`. This file should probably move to `core/model` (or a shared location both `feature/dashboard` and `feature/vitals` can depend on) since it is no longer Dashboard-specific after this change — confirm the module dependency graph allows `feature/vitals` to depend on wherever it currently sits (`feature/dashboard`'s `domain.dashboard` package); if `feature/vitals` cannot depend on `feature/dashboard` under the project's module boundaries, **move `CardManagementDelegate.kt` to `core/model` in the same package `app.readylytics.health.domain.dashboard`** as part of this change (a pure-Kotlin move, no logic change) so both feature modules can use it.

Constructor change:

```kotlin
class CardManagementDelegate(
    private val defaultConfigurations: List<CardConfiguration>,          // NEW — replaces hardcoded SettingsDefaults.DEFAULT_DASHBOARD_CARDS
    private val persist: suspend (List<CardConfiguration>) -> Unit,       // NEW — replaces `cardConfigRepository: CardConfigurationRepository`
    private val scope: CoroutineScope,
    private val hasBodyTemperaturePermission: suspend () -> Boolean = { true },
    private val hasStepsPermission: suspend () -> Boolean = { true },
    private val hasWeightPermission: suspend () -> Boolean = { true },
    private val hasBodyFatPermission: suspend () -> Boolean = { true },
    private val hasBloodPressurePermission: suspend () -> Boolean = { true },
    private val hasOxygenSaturationPermission: suspend () -> Boolean = { true },
)
```

Two call-site changes inside the class body:
```kotlin
// persistConfigs(): last line changes from
//   cardConfigRepository.updateDashboardCardConfigurations(toPersist)
// to
    persist(toPersist)

// onEvent(ResetToDefaults): changes from
//   _pendingConfigs.value = SettingsDefaults.DEFAULT_DASHBOARD_CARDS
// to
    _pendingConfigs.value = defaultConfigurations
```
Every other line of the class (event handling, `toggleCardVisibility`, `reorderCards`, the `state`/`isManagingCards`/`pendingConfigs` flows) is **unchanged**.

**Dashboard call-site update** (`feature/dashboard/.../DashboardViewModel.kt`, Section 2.8's excerpt):
```kotlin
private val cardManagementDelegate = CardManagementDelegate(
    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
    persist = cardConfigRepository::updateDashboardCardConfigurations,
    scope = viewModelScope,
    hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
    hasStepsPermission = { healthConnectRepository.hasStepsPermission() },
    hasWeightPermission = { healthConnectRepository.hasWeightPermission() },
    hasBodyFatPermission = { healthConnectRepository.hasBodyFatPermission() },
    hasBloodPressurePermission = { healthConnectRepository.hasBloodPressurePermission() },
    hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
)
```
This is the only Dashboard-side change required by this generalization — **zero behavior change**, and `CardManagementDelegateTest.kt` (`feature/dashboard/src/test/...`) should be updated to construct the delegate with a fake `persist` lambda and a literal `defaultConfigurations` list instead of a fake repository, with all existing assertions otherwise unchanged (confirming the refactor is behavior-preserving).

**New Vitals call site** (Section 6.6):
```kotlin
private val vitalsCardManagementDelegate = CardManagementDelegate(
    defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CARDS,
    persist = vitalsLayoutRepository::updateVitalsCardConfigurations,
    scope = viewModelScope,
    hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
    hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
    // hasSteps/hasWeight/hasBodyFat/hasBloodPressurePermission left at their default `{ true }` —
    // none of those CardIds are ever in the Vitals card list, so their filters are no-ops either way.
)
```

### 5.4 New `VitalsChartManagementDelegate`

New file: `feature/vitals/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartManagementDelegate.kt` (or `core/model` if module boundaries require it — apply the same placement rule as Section 5.3). Structurally mirrors the generalized `CardManagementDelegate` (Section 5.3) but scoped to `VitalsChartConfiguration`/`VitalsChartId`, with no display-mode event and no permission gating:

```kotlin
package app.readylytics.health.domain.vitals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VitalsChartManagementState(
    val isManagingCharts: Boolean = false,
    val pendingConfigs: List<VitalsChartConfiguration>? = null,
)

sealed interface VitalsChartManagementEvent {
    data class EnterEditMode(val currentConfigs: List<VitalsChartConfiguration>) : VitalsChartManagementEvent
    data object SaveChanges : VitalsChartManagementEvent
    data object CancelChanges : VitalsChartManagementEvent
    data object ResetToDefaults : VitalsChartManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<VitalsChartConfiguration>,
        val chartId: VitalsChartId,
        val visible: Boolean,
    ) : VitalsChartManagementEvent
    data class ReorderCharts(
        val currentConfigs: List<VitalsChartConfiguration>,
        val newOrder: List<VitalsChartConfiguration>,
    ) : VitalsChartManagementEvent
}

class VitalsChartManagementDelegate(
    private val defaultConfigurations: List<VitalsChartConfiguration>,
    private val persist: suspend (List<VitalsChartConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManagingCharts = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<VitalsChartConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<VitalsChartConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<VitalsChartManagementState> =
        combine(_isManagingCharts, _pendingConfigs) { managing, pending ->
            VitalsChartManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, VitalsChartManagementState())

    val isManagingCharts: StateFlow<Boolean> = _isManagingCharts.asStateFlow()
    val pendingConfigs: StateFlow<List<VitalsChartConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: VitalsChartManagementEvent) {
        when (event) {
            is VitalsChartManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManagingCharts.value = true
            }
            VitalsChartManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            VitalsChartManagementEvent.CancelChanges -> {
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            VitalsChartManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is VitalsChartManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = base.map {
                    if (it.chartId == event.chartId) it.copy(isVisible = event.visible) else it
                }
            }
            is VitalsChartManagementEvent.ReorderCharts -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.chartId }.toSet()
                val hidden = base.filter { it.chartId !in reorderedIds }
                _pendingConfigs.value = (event.newOrder + hidden).mapIndexed { index, config ->
                    config.copy(position = index)
                }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<VitalsChartConfiguration>) = onEvent(VitalsChartManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(VitalsChartManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(VitalsChartManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(VitalsChartManagementEvent.ResetToDefaults)
    fun onToggleChartVisibility(currentConfigs: List<VitalsChartConfiguration>, chartId: VitalsChartId, visible: Boolean) =
        onEvent(VitalsChartManagementEvent.ToggleVisibility(currentConfigs, chartId, visible))
    fun onReorderCharts(currentConfigs: List<VitalsChartConfiguration>, newOrder: List<VitalsChartConfiguration>) =
        onEvent(VitalsChartManagementEvent.ReorderCharts(currentConfigs, newOrder))
}
```

### 5.5 Genericize `DragController`; add `ReorderableChartList`

Existing file: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/DragController.kt`. Change the class declaration and every `CardId` occurrence to a generic type parameter:

```kotlin
@Stable
class DragController<T : Any>(initialOrder: List<T>) {
    var pendingOrder: List<T> by mutableStateOf(initialOrder); private set
    var draggedCardId: T? by mutableStateOf(null); private set
    var dragOffset: Offset by mutableStateOf(Offset.Zero); private set
    val slotBounds: SnapshotStateMap<T, Rect> = mutableStateMapOf()
    var hoveringDeleteZone: Boolean by mutableStateOf(false); private set

    fun updateSlotBounds(id: T, rect: Rect) { slotBounds[id] = rect }
    fun onDragStart(id: T) { draggedCardId = id; dragOffset = Offset.Zero; hoveringDeleteZone = false }
    fun onDrag(delta: Offset, deleteZoneTop: Float?) { /* body unchanged -- no CardId-specific logic exists in it, per Section 2.8 */ }
    fun onDragEnd(): DragEndResult<T> { /* body unchanged, generic return type */ }
    fun syncFromUpstream(newOrder: List<T>) { if (draggedCardId == null) pendingOrder = newOrder }
}
data class DragEndResult<T>(val draggedId: T?, val finalOrder: List<T>, val delete: Boolean)
```
Remove the `import app.readylytics.health.domain.dashboard.CardId` line (no longer needed in this file).

`ReorderableCardGrid.kt` updates every `DragController`/`DragEndResult` reference to `DragController<CardId>`/`DragEndResult<CardId>` (e.g. `remember { controller ?: DragController<CardId>(...) }`) — purely a type-argument addition, no logic change. Confirm with `./gradlew :core:ui:compileDebugKotlin` (or the full unit-test run in Section 9) that this compiles with zero behavior change, and that `ReorderableCardGridThresholdTest.kt` passes unmodified.

New file: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableChartList.kt` — a single-column counterpart to `ReorderableCardGrid`, reusing `DragController<VitalsChartId>`. It should reuse as much of `ReorderableCardGrid`'s proven mechanics as possible (long-press-after-delay drag detection on the root `Column`, drag-handle bounds gating drag *start*, a "hide" drop zone at the bottom mirroring the delete zone, root-local shared coordinate space for hit-testing) but with the paired-row layout branch removed — every item is full-width, one per row:

```kotlin
@Immutable data class ChartConfigurationsList(val items: List<VitalsChartConfiguration>)
@Immutable data class ChartDataMap(val map: Map<VitalsChartId, @Composable (VitalsChartConfiguration) -> Unit>)

@Composable
fun ReorderableChartList(
    chartConfigurations: ChartConfigurationsList,
    chartDataMap: ChartDataMap,
    isEditing: Boolean,
    onChartHide: (VitalsChartId) -> Unit,
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<VitalsChartId>? = null,
) {
    // Structure: single Column, one full-width Box per visible+renderable chart (sorted by
    // dragController.pendingOrder, itself synced from `chartConfigurations` filtered/sorted by
    // isVisible+position exactly like ReorderableCardGrid's configByCardId/displayableCards
    // pattern), each wrapped the same way ReorderableCardItem wraps a card: a 48dp drag-handle
    // Box (only when isEditing) + the chart content Box. A bottom "hide" drop zone Surface
    // (relabeled from "delete" to "hide" -- reusing R.string.action_delete_drop_zone's *mechanism*
    // but with new copy, since dropping here is a reversible visibility toggle, not a destructive
    // removal) appears only in edit mode and calls onChartHide on drop.
}
```
This file depends on `VitalsChartConfiguration`/`VitalsChartId` (from `core/model`), which is consistent with `ReorderableCardGrid.kt` already depending on `CardConfiguration`/`CardId` from the same module.

### 5.6 `VitalsChartId.displayNameResId` extension (new file)

New file: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsChartIdExtensions.kt`, mirroring `CardIdExtensionsUi.kt` exactly:

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.annotation.StringRes
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.core.ui.R as CoreUiR

@get:StringRes
val VitalsChartId.displayNameResId: Int
    get() = when (this) {
        VitalsChartId.HRV_TREND -> R.string.label_hrv_rmssd                       // reuse existing chart title string
        VitalsChartId.RHR_TREND -> R.string.label_resting_heart_rate              // reuse existing chart title string
        VitalsChartId.SPO2_TREND -> R.string.label_oxygen_saturation              // reuse existing chart title string
        VitalsChartId.BODY_TEMP_TREND -> CoreUiR.string.label_body_temperature    // reuse existing chart title string
    }
```
All four target strings already exist (Section 2.5 lists their exact resource ids as used by the current `VitalsTrendSection.kt` chart titles) — **no new strings.xml entries are needed for chart display names**, only reuse.

### 5.7 Generalize `UniversalVitalsMetricCard`

Existing file: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/UniversalVitalsMetricCard.kt`. New signature:

```kotlin
@Composable
internal fun UniversalVitalsMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    rawValue: Float?,
    maxValue: Float,
    supportedModes: List<UniversalCardDisplayMode>,          // NEW
    requestedMode: UniversalCardDisplayMode,                  // was hardcoded to GAUGE
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    usesDeltaPill: Boolean = secondaryText != null,           // NEW, defaults to old implicit behavior
    isEditing: Boolean = false,                                // NEW
    onModeSelected: (UniversalCardDisplayMode) -> Unit = {},   // NEW
    onClick: (() -> Unit)? = null,
) {
    UniversalMetricCard(
        presentation = UniversalMetricPresentation(
            title = title, valueText = valueText, unitText = unitText, secondaryText = secondaryText,
            status = status, tooltip = tooltip,
            accessibilityDescription = "$title: $valueText $unitText",
            visual = UniversalMetricScalePreparer.score(rawValue, 0f, maxValue),
        ),
        specification = UniversalMetricCardSpec(supportedModes = supportedModes, usesDeltaPill = usesDeltaPill),
        requestedMode = requestedMode,
        isEditing = isEditing,
        onModeSelected = onModeSelected,
        modifier = modifier,
        onClick = if (isEditing) null else onClick,   // matches ConfigurableMetricCard's rule: no navigation while editing
    )
}
```
Every existing caller of this composable (today: only `VitalsGaugeRow.kt`, Section 2.3) must be updated to pass `supportedModes`/`requestedMode` explicitly instead of relying on the old hardcoded GAUGE default — but per Section 6.1, `VitalsGaugeRow.kt`'s call sites are being relocated into the new `VitalsCardFactory.kt` anyway (Section 6.2), so this update happens as part of that relocation, not as a separate edit to a file that's about to be deleted.

---

## 6. Vitals feature changes

### 6.1 Delete `VitalsGaugeRow.kt`, relocate its logic into `VitalsCardFactory.kt`

The existing RHR/HRV gauge-fill math and delta-text formatting (Section 2.3: `RHR_DIAL_FLOOR`, `RHR_BASELINE_FILL`, the `hrvMax = baselineHrv * 2f` rule, the delta-pill string building) is **preserved exactly**, just moved from a hardcoded two-card `Row` into per-card entries of a new `Map<CardId, @Composable (CardConfiguration) -> Unit>` factory function, so `ReorderableCardGrid` can render 1–4 of these cards in any order/visibility combination.

### 6.2 New file: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt`

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Composable
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.feature.vitals.UniversalVitalsMetricCard
import kotlin.math.abs
// ...remaining imports mirror VitalsGaugeRow.kt's current imports (stringResource, spacing, deltaUp/Down/NoChange strings, unit strings)...

// Same GAUGE/BAR/VALUE <-> DashboardCardDisplayMode/UniversalCardDisplayMode mapping DashboardToUniversalMapper.kt
// already defines -- reuse that file if `feature/vitals` can depend on `feature/dashboard`'s
// DashboardToUniversalMapper.kt (module-boundary dependent, see note below), otherwise duplicate
// the 6-line 1:1 enum mapping locally (it is small enough that a light duplication here is
// acceptable if cross-feature-module dependency isn't allowed by the project's module graph --
// confirm the actual module dependency rule at implementation time before choosing).
private fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode = when (this) {
    DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
    DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
    DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
}
private fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode = when (this) {
    UniversalCardDisplayMode.GAUGE -> DashboardCardDisplayMode.GAUGE
    UniversalCardDisplayMode.BAR -> DashboardCardDisplayMode.BAR
    UniversalCardDisplayMode.VALUE -> DashboardCardDisplayMode.VALUE
}

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

fun buildVitalsCardDataMap(
    presentation: VitalsPresentationState,
    isEditing: Boolean,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): Map<CardId, @Composable (CardConfiguration) -> Unit> {
    val map = mutableMapOf<CardId, @Composable (CardConfiguration) -> Unit>()

    map[CardId.RESTING_HR] = { configuration ->
        // Move VitalsGaugeRow.kt's exact current RHR-card block here verbatim: rhrFill computation
        // (RHR_DIAL_FLOOR/RHR_BASELINE_FILL), rhrDelta string building (deltaUp/deltaDown/noChange +
        // bpmUnit), then call the generalized UniversalVitalsMetricCard with:
        //   supportedModes = DashboardCardCatalog.spec(CardId.RESTING_HR)!!.supportedModes.map { it.toUniversalMode() }
        //   requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode()
        //   isEditing = isEditing
        //   onModeSelected = { mode -> onCardDisplayModeChanged(CardId.RESTING_HR, mode.toDashboardMode()) }
        //   onClick = onNavigateToRhr
    }

    map[CardId.HRV] = { configuration ->
        // Same relocation for the HRV card block (hrvMax = baseline*2 or 150f fallback, hrvDelta
        // string building), wired to CardId.HRV the same way.
    }

    map[CardId.OXYGEN_SATURATION] = { configuration ->
        // NEW card. Build from presentation.spo2: Spo2Assessment (already has value/status/zoneBands --
        // Section 2.6). valueText = "${spo2.value?.roundToInt()}%" or the unavailable-value string
        // (mirror DashboardMetricPresentationFactory's SpO2 block, Section 2.8, exactly for value
        // formatting and the 80f..100f gauge scale). No onClick navigation target exists today for
        // an SpO2 detail screen from Vitals -- confirm with the user or default to no-op (null
        // onClick) if no such destination exists; do not invent a new navigation route beyond this
        // plan's scope.
    }

    map[CardId.BODY_TEMPERATURE] = { configuration ->
        // NEW card. Build from presentation.bodyTemp: BodyTemperatureAssessment (Section 5.1/5.2).
        // Mirror DashboardMetricPresentationFactory's Body Temperature block (Section 2.8) for
        // value formatting (%.1f), unit label (F/C from presentation.bodyTempUnitSystem), secondary
        // delta text, and the 35.5..39°C-converted gauge scale. Same onClick caveat as SpO2 above.
    }

    return map
}
```

**Open question to resolve at implementation time, not now:** do the new SpO2/Body-Temperature Vitals cards need an `onClick` navigation target? Today, Dashboard's `OXYGEN_SATURATION`/`BODY_TEMPERATURE` cards navigate to Vitals itself (`onNavigateToVitals`, Section 2.7) — that target doesn't make sense for a card that's already on the Vitals screen. If no SpO2/Body-Temp detail screen exists in the app today, these two cards should simply have `onClick = null` (non-navigable, matching how e.g. Dashboard's `AI_RECOMMENDATION` card has no navigation). Confirm by searching the nav graph (`MainNavHost.kt`) for any existing SpO2/BodyTemp detail route before implementing; do not build a new detail screen as part of this feature unless the user asks for one.

### 6.3 `VitalsScreen.kt` → `ReorderableCardGrid` replaces `VitalsGaugeRow`

```kotlin
val vitalsCardDataMap = remember(uiState.presentation, uiState.isManagingVitalsCards) {
    CardDataMap(
        buildVitalsCardDataMap(
            presentation = uiState.presentation,
            isEditing = uiState.isManagingVitalsCards,
            onNavigateToHrv = onNavigateToHrv,
            onNavigateToRhr = onNavigateToRhr,
            onCardDisplayModeChanged = onVitalsCardDisplayModeChanged,
        ),
    )
}
ReorderableCardGrid(
    cardConfigurations = CardConfigurationsList(uiState.vitalsCardConfigurations),
    cardDataMap = vitalsCardDataMap,
    isEditing = uiState.isManagingVitalsCards,
    onCardRemove = { cardId -> onToggleVitalsCardVisibility(cardId, false) },
    onCardReorder = onReorderVitalsCards,
    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
)
```
Placed exactly where `VitalsGaugeRow(...)` is called today (Section 2.2). Note `ReorderableCardGrid` already expects horizontal padding applied by the caller (Dashboard does the same, Section 2.8) — match that convention rather than double-padding.

### 6.4 `VitalsTrendSection.kt` becomes data-driven

The four existing `CardLoader { TrendCard { TrendChart(...) } }` blocks (Section 2.5) are each extracted into a small `@Composable` function keyed by `VitalsChartId`, e.g.:

```kotlin
@Composable
private fun HrvTrendChartBlock(chartInputs: VitalsChartInputs, scrollState: VicoScrollState, zoomState: VicoZoomState, parentScrollInProgress: () -> Boolean) {
    // exact current "Chart 1: HRV Trend" block body, unchanged
}
// ...RhrTrendChartBlock, Spo2TrendChartBlock, BodyTempTrendChartBlock, same pattern...

private fun chartBlockFor(chartId: VitalsChartId): @Composable (VitalsChartInputs, VicoScrollState, VicoZoomState, () -> Boolean) -> Unit =
    when (chartId) {
        VitalsChartId.HRV_TREND -> { inputs, s, z, p -> HrvTrendChartBlock(inputs, s, z, p) }
        VitalsChartId.RHR_TREND -> { inputs, s, z, p -> RhrTrendChartBlock(inputs, s, z, p) }
        VitalsChartId.SPO2_TREND -> { inputs, s, z, p -> Spo2TrendChartBlock(inputs, s, z, p) }
        VitalsChartId.BODY_TEMP_TREND -> { inputs, s, z, p -> BodyTempTrendChartBlock(inputs, s, z, p) }
    }
```

`VitalsTrendSection`'s top-level signature gains the chart configuration list and edit-mode plumbing, and its body becomes a call into the new `ReorderableChartList` (Section 5.5) instead of the current fixed `Column`:

```kotlin
@Composable
internal fun VitalsTrendSection(
    chartInputs: VitalsChartInputs,
    chartConfigurations: List<VitalsChartConfiguration>,   // NEW
    isEditing: Boolean,                                      // NEW
    onChartHide: (VitalsChartId) -> Unit,                    // NEW
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit, // NEW
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val chartDataMap = ChartDataMap(
        VitalsChartId.entries.associateWith { chartId ->
            { _: VitalsChartConfiguration -> chartBlockFor(chartId)(chartInputs, chartScrollState, chartZoomState, parentScrollInProgress) }
        },
    )
    ReorderableChartList(
        chartConfigurations = ChartConfigurationsList(chartConfigurations),
        chartDataMap = chartDataMap,
        isEditing = isEditing,
        onChartHide = onChartHide,
        onChartReorder = onChartReorder,
        modifier = modifier,
    )
}
```
The shared `chartScrollState`/`chartZoomState` pair keeps being passed straight through to whichever charts are actually rendered, regardless of their order — this is unaffected by the reordering mechanism since it's a shared, externally-owned Vico state pair, not per-chart state.

### 6.5 `VitalsScreen.kt` — add Customize button, bottom sheet, FAB

```kotlin
Box(modifier = modifier.fillMaxSize()) {   // wraps the existing Column, matching DashboardScreen.kt's Box+overlay pattern
    if (showVitalsManagement) {
        VitalsManagementBottomSheet(
            cardConfigurations = uiState.vitalsCardConfigurations,
            chartConfigurations = uiState.vitalsChartConfigurations,
            onCardVisibilityChanged = onToggleVitalsCardVisibility,
            onChartVisibilityChanged = onToggleChartVisibility,
            onResetToDefaults = onResetVitalsToDefaults,
            onDismiss = { scope.launch { sheetState.hide() }; showVitalsManagement = false },
            sheetState = sheetState,
        )
    }

    Column( /* the existing scrollable content Column from Section 2.2, now including: */ ) {
        // ...ScreenHeaderSection/DateSwitcher, ReorderableCardGrid (6.3), SectionHeader+TimeRange picker,
        // VitalsTrendSection (6.4), StatusLegend...

        if (!uiState.isManagingVitalsCards) {
            FilledTonalButton(
                onClick = { showVitalsManagement = true; onToggleVitalsCardManagement() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = ..., vertical = ...),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text(stringResource(R.string.action_customize)) }   // reuse the existing action_customize string
        }
    }

    EditModeFab(
        isVisible = uiState.isManagingVitalsCards,
        onDoneClick = onToggleVitalsCardManagement,
        onCancelClick = onCancelVitalsCardManagement,
        modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
    )
}
```

**One Customize button, one edit session, two sections edited together:** entering edit mode calls `onToggleVitalsCardManagement()`, which (Section 6.6) enters edit mode on **both** `vitalsCardManagementDelegate` and `vitalsChartManagementDelegate` simultaneously, and Done/Save commits both, Cancel discards both. This satisfies the "reuse the same Customize-button pattern" requirement while keeping the two sections' reordering strictly independent — `ReorderableCardGrid` only ever receives/returns `CardId`-keyed items (the 4 top-row candidates), `ReorderableChartList` only ever receives/returns `VitalsChartId`-keyed items (the 4 chart candidates); there is no code path by which an item from one list could end up in the other, or by which the two sections could swap screen position, because they are rendered by two structurally separate composables placed in a fixed order in `VitalsScreen`'s `Column` (top-cards grid always precedes the trends section in source order — this is a static layout fact, not a runtime-configurable one).

### 6.6 `VitalsViewModel.kt` changes

New constructor dependencies:
```kotlin
class VitalsViewModel @Inject constructor(
    // ...all 8 existing params, unchanged...
    private val vitalsLayoutRepository: VitalsLayoutRepository,       // NEW
    private val healthConnectRepository: HealthConnectRepository,     // NEW
) : ViewModel()
```

New delegates, constructed the same way Section 5.3/5.4 specify:
```kotlin
private val vitalsCardManagementDelegate = CardManagementDelegate(
    defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CARDS,
    persist = vitalsLayoutRepository::updateVitalsCardConfigurations,
    scope = viewModelScope,
    hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
    hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
)
private val vitalsChartManagementDelegate = VitalsChartManagementDelegate(
    defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CHARTS,
    persist = vitalsLayoutRepository::updateVitalsChartConfigurations,
    scope = viewModelScope,
)
```

New read-side merge flow, mirroring `createDashboardCardStateFlow` (Section 2.8) but simplified (no "last sleep session" lookup — that's Dashboard-specific, not needed here):
```kotlin
private val permissionGrants: Flow<Pair<Boolean, Boolean>> = combine(
    flow { emit(healthConnectRepository.hasBodyTemperaturePermission()) },
    flow { emit(healthConnectRepository.hasOxygenSaturationPermission()) },
) { bodyTemp, spo2 -> bodyTemp to spo2 }

private val vitalsCardStateFlow: Flow<VitalsCardState> = combine(
    vitalsCardManagementDelegate.isManagingCards,
    vitalsCardManagementDelegate.pendingConfigs,
    vitalsLayoutRepository.vitalsCardConfigurations(),
    permissionGrants,
) { isManaging, pending, committed, (bodyTempGranted, spo2Granted) ->
    fun List<CardConfiguration>.filteredForPermission(): List<CardConfiguration> {
        var list = this
        if (!bodyTempGranted) list = list.filter { it.cardId != CardId.BODY_TEMPERATURE }
        if (!spo2Granted) list = list.filter { it.cardId != CardId.OXYGEN_SATURATION }
        return list
    }
    VitalsCardState(isManaging, committed.filteredForPermission(), pending?.filteredForPermission())
}

private val vitalsChartStateFlow: Flow<VitalsChartState> = combine(
    vitalsChartManagementDelegate.isManagingCharts,
    vitalsChartManagementDelegate.pendingConfigs,
    vitalsLayoutRepository.vitalsChartConfigurations(),
) { isManaging, pending, committed -> VitalsChartState(isManaging, committed, pending) }
```
(`VitalsCardState`/`VitalsChartState` are small new private data classes local to `VitalsViewModel.kt` or a new `VitalsFlowIntermediate.kt`, mirroring `DashboardCardState`'s shape from `DashboardFlowIntermediate.kt`, Section 2.8.)

`VitalsUiState` gains:
```kotlin
data class VitalsUiState(
    // ...all existing fields, unchanged...
    val vitalsCardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingVitalsCards: Boolean = false,
    val vitalsChartConfigurations: List<VitalsChartConfiguration> = emptyList(),
)
```
populated the same "pending-if-editing-else-committed" way Dashboard does (Section 2.8):
```kotlin
vitalsCardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfiguration,
isManagingVitalsCards = cardState.isManagingCards,   // OR combine with chart-editing flag, see below
vitalsChartConfigurations = chartState.pendingConfiguration ?: chartState.cardConfiguration,
```

**Single combined edit-mode flag:** since Section 6.5 specifies one Customize button driving both sections together, `isManagingVitalsCards` (or a renamed `isManagingVitals` that's semantically "either section is being edited") should be `true` whenever *either* delegate is in edit mode — in practice both will always enter/exit together since they're only ever toggled by the same button, but the state should be derived as `cardIsManaging || chartIsManaging` (or asserted-equal) for defensiveness rather than assumed. Forwarding methods on `VitalsViewModel`:

```kotlin
fun toggleVitalsCardManagement() {
    if (vitalsCardManagementDelegate.isManagingCards.value) {
        vitalsCardManagementDelegate.saveChanges()
        vitalsChartManagementDelegate.saveChanges()
    } else {
        vitalsCardManagementDelegate.enterEditMode(uiState.value.vitalsCardConfigurations)
        vitalsChartManagementDelegate.enterEditMode(uiState.value.vitalsChartConfigurations)
    }
}
fun onCancelVitalsCardManagement() {
    vitalsCardManagementDelegate.cancelChanges()
    vitalsChartManagementDelegate.cancelChanges()
}
fun onToggleVitalsCardVisibility(cardId: CardId, visible: Boolean) =
    vitalsCardManagementDelegate.onToggleCardVisibility(uiState.value.vitalsCardConfigurations, cardId, visible)
fun onReorderVitalsCards(newOrder: List<CardConfiguration>) =
    vitalsCardManagementDelegate.onReorderCards(uiState.value.vitalsCardConfigurations, newOrder)
fun onVitalsCardDisplayModeChanged(cardId: CardId, mode: DashboardCardDisplayMode) =
    vitalsCardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
fun onToggleChartVisibility(chartId: VitalsChartId, visible: Boolean) =
    vitalsChartManagementDelegate.onToggleChartVisibility(uiState.value.vitalsChartConfigurations, chartId, visible)
fun onReorderCharts(newOrder: List<VitalsChartConfiguration>) =
    vitalsChartManagementDelegate.onReorderCharts(uiState.value.vitalsChartConfigurations, newOrder)
fun onResetVitalsToDefaults() {
    vitalsCardManagementDelegate.onResetToDefaults()
    vitalsChartManagementDelegate.onResetToDefaults()
}
```
All eight of these are wired from `VitalsRoute` into `VitalsScreen` exactly the way `DashboardRoute` wires its equivalents into `DashboardScreen` (Section 2.8's `DashboardRoute` excerpt is the template — add the equivalent parameters to `VitalsRoute`/`VitalsScreen`'s signatures).

### 6.7 New file: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsManagementBottomSheet.kt`

Mirrors `CardManagementBottomSheet.kt` (Section 2.8) structurally, but with two labeled sections in one `LazyColumn` instead of one flat list:

```kotlin
@Composable
fun VitalsManagementBottomSheet(
    cardConfigurations: List<CardConfiguration>,
    chartConfigurations: List<VitalsChartConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onChartVisibilityChanged: (VitalsChartId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.pageSectionGap)) {
            // Header row: title (new string, e.g. "Customize Vitals") + Reset-to-defaults IconButton
            // (Icons.Outlined.RestartAlt, reusing action_reset_to_defaults), same as CardManagementBottomSheet's header.

            LazyColumn {
                item { Text(stringResource(R.string.vitals_management_cards_section_title), style = MaterialTheme.typography.titleSmall) }  // NEW string, e.g. "Vitals Cards"
                items(cardConfigurations.sortedBy { it.position }, key = { "card_${it.cardId.name}" }) { card ->
                    ListItem(
                        headlineContent = { Text(stringResource(card.cardId.displayNameResId)) },
                        trailingContent = { Checkbox(checked = card.isVisible, onCheckedChange = { onCardVisibilityChanged(card.cardId, it) }) },
                    )
                }
                item { Text(stringResource(R.string.vitals_management_diagrams_section_title), style = MaterialTheme.typography.titleSmall) }  // NEW string, e.g. "Diagrams"
                items(chartConfigurations.sortedBy { it.position }, key = { "chart_${it.chartId.name}" }) { chart ->
                    ListItem(
                        headlineContent = { Text(stringResource(chart.chartId.displayNameResId)) },
                        trailingContent = { Checkbox(checked = chart.isVisible, onCheckedChange = { onChartVisibilityChanged(chart.chartId, it) }) },
                    )
                }
            }

            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(...)) {
                Text(stringResource(R.string.action_done))   // reuse existing string
            }
        }
    }
}
```

Note `CardId.displayNameResId` (Section 2.8) lives in `feature/dashboard/.../CardIdExtensionsUi.kt` today — if `feature/vitals` cannot depend on `feature/dashboard` under the project's module boundaries (confirm this at implementation time), this extension property must either move to a shared location (e.g. `core/ui` or `core/model`, alongside `CardId` itself) or be duplicated locally in `feature/vitals` scoped to just the 4 relevant `CardId` values. Prefer moving it to a shared location if the module graph allows, to avoid duplicating a 20-branch `when`.

---

## 7. Settings global display-mode switch — unification

Existing file: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/DashboardCardsSettingsViewModel.kt`. Add a second repository dependency and extend both private suspend functions (Section 2.9's full listing is the base to diff against):

```kotlin
@HiltViewModel
class DashboardCardsSettingsViewModel @Inject constructor(
    private val settingsReader: UserPreferencesReader,
    private val displaySettings: DisplaySettings,
    private val cardConfigurationRepository: CardConfigurationRepository,
    private val vitalsLayoutRepository: VitalsLayoutRepository,   // NEW
) : ViewModel() {
    // ...all existing state/event wiring unchanged...

    private suspend fun applyGlobalMode(mode: DashboardCardDisplayMode) {
        val currentDashboard = cardConfigurationRepository.dashboardCardConfigurations().first()
        cardConfigurationRepository.updateDashboardCardConfigurations(DashboardCardCatalog.applyGlobalDisplayMode(currentDashboard, mode))

        val currentVitals = vitalsLayoutRepository.vitalsCardConfigurations().first()
        vitalsLayoutRepository.updateVitalsCardConfigurations(DashboardCardCatalog.applyGlobalDisplayMode(currentVitals, mode))

        displaySettings.updateLastGlobalDisplayMode(mode)
    }

    private suspend fun resetAllModes() {
        val currentDashboard = cardConfigurationRepository.dashboardCardConfigurations().first()
        cardConfigurationRepository.updateDashboardCardConfigurations(DashboardCardCatalog.resetAllDisplayModes(currentDashboard))

        val currentVitals = vitalsLayoutRepository.vitalsCardConfigurations().first()
        vitalsLayoutRepository.updateVitalsCardConfigurations(DashboardCardCatalog.resetAllDisplayModes(currentVitals))

        displaySettings.updateLastGlobalDisplayMode(null)
    }
}
```
No changes are needed to `DashboardCardCatalog.applyGlobalDisplayMode`/`.resetAllDisplayModes` themselves (Section 2.8 confirms they're already generic over any `List<CardConfiguration>`) or to `DisplaySettings`/`UserPreferences.lastGlobalDisplayMode` (one shared "last applied mode" value for both tabs is the intended, already-clarified behavior).

**Copy check:** open `feature/settings/.../DashboardCardsSettings.kt` and its strings in `app/src/main/res/values/strings.xml` at implementation time. If any user-facing copy says "Dashboard cards" specifically (e.g. a section title, the confirmation dialog body text, or a "this will change how your dashboard cards look" sentence), update it to mention both tabs (e.g. "Dashboard and Vitals cards") since the switch's effect has changed. Do not assume the exact current wording without reading the live strings.

---

## 8. Local backup / restore integration

Existing files: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt` and the paired `LocalRestoreManager.kt`. `LocalBackupManager` already injects `CardConfigurationRepository` and, inside `writePreferences()` (Section 2.10), reads `cardConfigurationRepository.dashboardCardConfigurations().first()` and serializes it into the backup payload.

Add the equivalent for `VitalsLayoutRepository`:
- Inject `VitalsLayoutRepository` into `LocalBackupManager`'s constructor.
- In `writePreferences()` (or wherever the dashboard-cards serialization happens), also read `vitalsLayoutRepository.vitalsCardConfigurations().first()` and `vitalsLayoutRepository.vitalsChartConfigurations().first()`, and add them to the backup payload's schema (extend whatever `UserPreferencesBackup`-shaped data class the existing `cards` field lives on, or add sibling fields `vitalsCards`/`vitalsCharts`).
- Mirror the exact same addition in `LocalRestoreManager.kt`'s restore path (read the backup's vitals fields, call `vitalsLayoutRepository.updateVitalsCardConfigurations(...)` / `updateVitalsChartConfigurations(...)`).
- Read both files in full at implementation time before editing — the backup file format is user-facing durable state (restoring an old backup must not crash on a schema that predates this feature), so confirm the existing dashboard-cards backup/restore code's handling of "field absent in an older backup" (it should default to `DEFAULT_VITALS_CARDS`/`DEFAULT_VITALS_CHARTS`, consistent with how a fresh install behaves) before writing the Vitals equivalent.

---

## 9. Strings & i18n

Per project convention, every user-facing string goes in `app/src/main/res/values/strings.xml` and is referenced via `stringResource(R.string.key_name)` — no hardcoded strings in Compose code.

**New strings needed:**
- `vitals_management_cards_section_title` — e.g. "Vitals Cards" (Section 6.7's bottom-sheet section header)
- `vitals_management_diagrams_section_title` — e.g. "Diagrams" (Section 6.7's bottom-sheet section header)
- A "hide" label for the chart drop zone if `ReorderableChartList` (Section 5.5) uses different copy than `action_delete_drop_zone` (e.g. `action_hide_drop_zone`, "Drop here to hide") — needed because unlike the Dashboard's delete zone (which permanently removes a card from the grid until re-added via the sheet), a chart dropped in this zone is only hidden, which is exactly the same *mechanism* as the Dashboard's zone but arguably deserves distinct copy since "delete" implies destructive removal and this is a reversible visibility toggle. Decide at implementation time whether reusing `action_delete_drop_zone`'s exact copy is acceptable (the underlying operation — hide via drag, unhide via checkbox — is identical to how Dashboard's own delete zone actually behaves, since `onCardRemove` also only calls `onCardVisibilityChanged(cardId, false)`, Section 2.8's `DashboardScreen.kt` excerpt) or whether new copy is warranted.
- Confirm whether a bottom-sheet title string is needed (Dashboard's uses `R.string.manage_cards`, "Manage Cards" — decide whether to reuse this generic string for Vitals too, or add a Vitals-specific title, e.g. `vitals_manage_layout`).

**Strings already existing and reused as-is (no new entries needed):**
- `action_customize`, `action_done`, `action_reset_to_defaults`, `action_cancel_editing`, `action_done_editing`, `accessibility_drag_to_reorder` (Dashboard's existing customization strings — Section 2.8)
- `label_hrv_rmssd`, `label_resting_heart_rate`, `label_oxygen_saturation`, `label_body_temperature` (chart title strings, reused for `VitalsChartId.displayNameResId`, Section 5.6)
- `card_title_oxygen_saturation`, `card_title_body_temperature`, `tooltip_vitals_spo2`, `tooltip_vitals_body_temperature` (Dashboard's existing SpO2/Body-Temp card copy — confirm at implementation time whether reusing these verbatim reads naturally on the Vitals top cards too, or whether Vitals-specific copy is warranted; default to reuse unless it reads oddly)
- `mode_gauge`, `mode_bar`, `mode_value`, `menu_content_description_visualization_style`, `menu_item_description_mode`, `menu_item_description_mode_selected` (the mode-switcher dropdown's existing strings, `UniversalMetricCard.kt` / `UniversalDisplayModeMenu`, Section 2.8)

---

## 10. Testing plan

Mirror existing test file locations/patterns exactly:

| New/changed test | Location | Mirrors |
|---|---|---|
| `CardManagementDelegateTest.kt` — update construction to `persist`-lambda + literal `defaultConfigurations` | `feature/dashboard/src/test/.../domain/dashboard/CardManagementDelegateTest.kt` (existing file, update in place) | n/a — must keep passing unmodified in assertions |
| `VitalsChartManagementDelegateTest.kt` — new | `feature/vitals/src/test/.../domain/vitals/` (or wherever `CardManagementDelegate` ends up living, per Section 5.3's module-placement note) | `CardManagementDelegateTest.kt` |
| `VitalsLayoutRepositoryTest.kt` — new | `app/src/test/.../data/preferences/` | `CardConfigurationRepositoryTest.kt` |
| `VitalsLayoutConfigurationsSerializerTest.kt` — new | `app/src/test/.../data/preferences/` | `CardConfigurationsSerializerTest.kt` |
| `VitalsLayoutMapperTest.kt` — new | `app/src/test/.../data/preferences/` | `CardConfigurationMapperTest.kt` (if one exists — confirm) |
| `DragController` genericization — confirm existing test still passes unmodified, add a `VitalsChartId`-parameterized case | `core/ui/src/test/.../components/ReorderableCardGridThresholdTest.kt` | itself, plus a new equivalent for `ReorderableChartList` |
| `assessBodyTemperature`/`bodyTemperatureStatus` unit tests — new, zero Android deps | `core/model/src/test/.../domain/model/VitalAssessmentTest.kt` (existing file if present, or new) | existing `assessSpo2`/`assessHrv`/`assessRhr` tests in the same file |
| `DashboardMetricPresentationFactoryTest.kt` (or equivalent) — confirm body-temp-card assertions pass unmodified after the Section 5.1 extraction | `feature/dashboard/src/test/...` | itself (regression check, not a new test) |
| `VitalsViewModelTest.kt` — extend for the new pending/committed merge logic and the two new delegates | `feature/vitals/src/test/.../overview/VitalsViewModelTest.kt` (existing file, extend) | `DashboardViewModelTest.kt` |
| `VitalsStateFactoryTest.kt` — extend for `VitalsPresentationState.bodyTemp` | `feature/vitals/src/test/.../overview/VitalsStateFactoryTest.kt` (existing file, extend) | n/a |

Per project convention, all pure-Kotlin domain/delegate/repository tests must have zero Android dependencies, and should exercise boundary conditions (empty configuration lists, an unknown persisted `card_id`/`chart_id` string, reordering with a subset of ids, toggling visibility on a permission-gated card while the permission is denied).

---

## 11. Verification

1. **`./gradlew ktlintFormat && ./gradlew testDebugUnitTest`** — every new test in Section 10 plus the full existing suite must pass. Special attention to `CardManagementDelegateTest`, `DashboardViewModelTest`, `ReorderableCardGridThresholdTest`, and the Dashboard backup/restore tests, since these confirm the Section 5.3/5.5/8 generalizations are behavior-preserving for the Dashboard (nothing about Dashboard's user-visible behavior should change).
2. **`./gradlew installDebug`** and manually exercise on a device/emulator with Health Connect configured:
   - Vitals tab loads with default RHR+HRV cards visible (RHR first) and all four trend charts visible in the original order (HRV, RHR, SpO2, BodyTemp) — confirms the migration/default wiring introduces no visual regression for existing users.
   - Tap "Customize" → the bottom sheet shows both a "Vitals Cards" section (4 checkboxes) and a "Diagrams" section (4 checkboxes); checking SpO2/Body Temp makes them appear in the top grid; unchecking a chart removes it from the trend section below.
   - Drag-reorder within the top card grid, and independently within the diagram list; confirm the two sections never intermix and the top-cards-then-diagrams page order never changes.
   - While editing, open a top card's mode menu and switch it between gauge/bar/value; confirm the card re-renders correctly in each mode.
   - Tap the FAB's Cancel (X) → all pending visibility/order/mode changes revert to the last-saved state. Tap the FAB's Done (check) → changes persist; force-close and reopen the app to confirm the persisted state survives a process restart.
   - With Health Connect body-temperature or SpO2 permission revoked, confirm the corresponding card cannot be left visible in Vitals (mirrors the existing Dashboard permission-gating behavior) — revoke the permission from Health Connect's settings, reopen the app, and confirm the card silently drops out of the visible set rather than showing stale/blank data.
   - In Settings, use the existing global display-mode switch (apply "Bar", then "Gauge", then "Reset") and confirm both the Dashboard cards **and** the Vitals top cards that support the applied mode switch together in one action.
   - Create a local backup, then restore it (or restore onto a fresh install) and confirm the Vitals customization (visibility, order, modes) round-trips correctly.
3. **`./gradlew lintRelease`** after all coding tasks are resolved, per project convention (run last, once every other verification step is green).
4. **`codegraph index`** after any new file is created, and `codegraph sync` after any structural/directory move (e.g. relocating `CardManagementDelegate.kt` per Section 5.3, or deleting `VitalsGaugeRow.kt` per Section 6.1) — required by this repository's file-lifecycle convention.

---

## 12. Explicit non-goals / out of scope for this change

- No changes to the scoring engine, Health Connect ingestion pipeline, or any `domain/scoring/**` formula — this is a presentation/customization-layer feature only, so `internal-docs/DATA_FLOW.md` does not need updating (its synchronization rule is scoped to ingestion/scoring changes, neither of which occurs here).
- No new Health Connect permission types, mappers, or Room schema changes — SpO2 and Body Temperature ingestion already exists in full (Section 2.7).
- No new detail/navigation screens for SpO2 or Body Temperature unless a later decision (Section 6.2's open question) determines one is needed — default assumption is `onClick = null` for those two new top cards.
- No enforcement of a "must keep at least one card/chart visible" rule anywhere in this feature (explicitly decided against, Section 1.1).
- No change to the Vitals top row's fundamental 2-per-row visual grouping when more than 2 cards are visible — it wraps into a second row of 2, exactly like the Dashboard's existing `ReorderableCardGrid` layout algorithm, not a 4-across single row or any other grid shape.
