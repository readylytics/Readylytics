# Universal Metric Card Design

## Context
Currently, the `DashboardMetricCard` lives inside the `feature/dashboard` module and provides a polished UI with multiple rendering modes (Gauge, Bar, Value). Other tabs (Sleep, Vitals, Workouts) use older, redundant cards (`M3ScoreGaugeCard`, `MetricCard`, `CircadianConsistencyCard`) from the `core/ui` module. We want to reduce code duplication (LoC) and ensure a consistent visual language by reusing the dashboard card across all tabs.

## Architecture & Data Flow

### 1. Extraction to Core UI
- **Models**: We will extract `DashboardMetricPresentation`, `DashboardMetricVisual`, and `DashboardCardDisplayMode` from `feature/dashboard` into `core/ui/components/metriccard`. They will be renamed to `UniversalMetricPresentation`, `UniversalMetricVisual`, and `UniversalCardDisplayMode`.
- **Card Spec**: We will decouple the card from the dashboard's domain logic by creating a lightweight `UniversalMetricCardSpec(val supportedModes: List<UniversalCardDisplayMode>, val usesDeltaPill: Boolean)` in `core/ui`, rather than depending on `DashboardCardSpec` and its `DashboardCardId`.
- **UI Component**: We will move `DashboardMetricCard.kt` and its rendering components (gauge, bar, value) into `core/ui/components/metriccard/UniversalMetricCard.kt`.

### 2. Integration in Features
- **Dashboard Tab**: The `DashboardMetricPresentationFactory` will be updated to output the new `UniversalMetricPresentation`. The `DashboardScreen` will map its `DashboardCardSpec` to `UniversalMetricCardSpec` for rendering.
- **Other Tabs (Sleep, Vitals, Workouts)**:
  - We will replace usages of `M3ScoreGaugeCard`, `MetricCard`, and `CircadianConsistencyCard` with `UniversalMetricCard`.
  - ViewModels or mapping layers in these features will construct `UniversalMetricPresentation` instances from their state (e.g. `SleepUiState`, `VitalsUiState`).
  - These tabs will render the card with `isEditing = false` and a hardcoded `requestedMode` (e.g. `GAUGE` or `VALUE`).

### 3. Cleanup
- Complete removal of `M3ScoreGaugeCard.kt`, `MetricCard.kt`, `CircadianConsistencyCard.kt` from `core/ui`, alongside any unused skeleton components.

## Testing & Verification
- Unit tests in `feature/dashboard` (e.g. `DashboardMetricPresentationFactoryTest`) will need their imports and class references updated to the new `Universal` equivalents.
- Visual regressions can be tested via existing composable previews by updating them to use `UniversalMetricCard`.
