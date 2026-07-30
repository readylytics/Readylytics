# Dashboard Card Visualization Modes Design

## Scope

Readylytics will let users choose a visualization mode independently for
semantically compatible dashboard metric cards. The initial modes are Gauge,
Bar, and Value. Trend is out of scope, but the model and renderer boundary must
allow it to be added without changing persistence or metric calculations.

Mode selection is available only in dashboard edit mode. Normal mode retains
the existing clean card appearance. Changes preview immediately through the
existing pending edit configuration and participate in the existing Save and
Cancel transaction.

Two cards are explicit exceptions:

- Steps remains its current fixed Bar implementation, including the current
  `stepGoal / 0.75` normalization.
- Insights remains its current custom non-metric implementation.

Neither exception exposes a visualization selector.

## Verified Existing Architecture

The dashboard route collects `DashboardViewModel.uiState` with
`collectAsStateWithLifecycle`. `DashboardViewModel` combines date-dependent
summary data, card-management state, heart-rate aggregates, and real-time sync
state into an immutable `DashboardUiState`.

`GetDashboardDataUseCase` prepares the current string-based `CardData` map.
`DashboardCardFactory.buildCardDataMap` converts that data and several bespoke
state fields into a map of card IDs to composable lambdas.
`ReorderableCardGrid` filters visible/renderable cards, lays out Steps and
Insights full width, pairs other cards, and manages live drag order with stable
`CardId` values.

Card edit state is transactional. `CardManagementDelegate` copies the current
`List<CardConfiguration>` into `pendingConfigs` on entry. Visibility and
reorder changes update that pending list. Save persists the complete list
through `CardConfigurationRepository`; Cancel drops it. The dashboard displays
the pending list when present.

`CardConfiguration` currently contains stable `CardId`, visibility, and
position. It is persisted in a dedicated Proto DataStore. The repository
appends newly introduced default cards, maps a legacy `PAI_DAILY` ID to
`RAS_DAILY`, and filters unknown card IDs. The same domain configuration is
included in encrypted local backup and restore. A legacy JSON card preference
can also migrate into the proto.

Current visualization defaults are heterogeneous:

- Sleep Score and Readiness use `M3ScoreGaugeCard`.
- Steps uses a bespoke full-width `LinearProgressIndicator` card.
- Most other metrics use `MetricCard` Value presentations.
- Circadian Consistency and Heart Rate have focused card wrappers.
- Insights is a custom non-metric card.

The current Steps progress calculation is performed in composition and scales
against `stepGoal / 0.75`. The current gauge also calculates progress and
contains fallback threshold logic inside the composable. These patterns must
not be copied into new renderers.

The repository has dashboard JVM tests, DataStore/serializer tests, Compose
instrumentation tests, drag-controller tests, and dashboard recomposition
tests. It does not have screenshot or golden-image test infrastructure.

## Architecture

### Persisted Mode

Introduce:

```kotlin
enum class DashboardCardDisplayMode {
    GAUGE,
    BAR,
    VALUE,
}
```

Extend the existing edit transaction model:

```kotlin
data class CardConfiguration(
    val cardId: CardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
)
```

`requestedDisplayMode == null` means "use this card's legacy default." This
allows old DataStore records and old backups to preserve each card's current
visualization without a global default or destructive migration.

### Card Catalog

Add a focused `DashboardCardCatalog` keyed by `CardId`. Each entry declares:

```kotlin
data class DashboardCardSpec(
    val cardId: CardId,
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: Set<DashboardCardDisplayMode>,
)
```

The catalog resolves a requested mode to a safe render mode. Its default must
always be structurally supported. It contains no composable or metric
calculation logic.

Structurally unsupported known modes remain persisted but render with the
card's catalog default. Temporarily unavailable modes are different: they
remain selected and render their own grey unavailable presentation.

### Prepared Metric Presentation

Replace string-only renderer input with a strongly typed prepared model:

```kotlin
data class DashboardMetricPresentation(
    val title: String,
    val valueText: String,
    val unitText: String,
    val secondaryText: String?,
    val status: MetricStatus,
    val tooltip: String,
    val accessibilityDescription: String,
    val visual: DashboardMetricVisual,
)

sealed interface DashboardMetricVisual {
    data class Score(/* value, bounds, thresholds, normalized marker */) :
        DashboardMetricVisual

    data class Goal(/* value, target, progress, marker, availability */) :
        DashboardMetricVisual

    data class PersonalBaseline(
        /* value, baseline, ratio bands, axis, marker, availability */
    ) : DashboardMetricVisual

    data class ReferenceRange(
        /* value, axis, desired bands, marker, availability */
    ) : DashboardMetricVisual

    data object ValueOnly : DashboardMetricVisual
}
```

The concrete fields must retain both real numeric values and prepared visual
geometry. A pure-Kotlin presentation preparer computes normalization,
threshold bands, target and baseline positions, clamping, availability,
formatted secondary context, and accessibility text before composition.

Gauge and Bar consume the same `DashboardMetricVisual`; they may not derive
metric ranges or statuses independently. Value consumes the same primary and
secondary presentation fields.

### Shared Container And Renderers

Add a shared `DashboardMetricCard` that owns:

- Stable card dimensions and M3 `MaterialTheme.shapes.large`.
- Explicit M3 container color roles.
- Title and upper-right corner control.
- Normal-mode tooltip.
- Edit-mode display-mode menu.
- Click behavior.
- Loading/unavailable treatment.
- Combined accessibility semantics.

It delegates only the card body to focused Gauge, Bar, and Value renderers.
This is a direct `when` dispatch over the enum, not a renderer registry or
plugin framework.

The existing gauge drawing may be extracted and reused where its score arc is
semantically correct. Goal, baseline, and reference-range gauges must use
their prepared markers and bands rather than pretending every metric is a
higher-is-better score.

## Supported Modes And Defaults

| Card | Visual semantics | Supported modes | Legacy default |
| --- | --- | --- | --- |
| Sleep Score | Score, 0-100 | Gauge, Bar, Value | Gauge |
| Readiness | Score, 0-100 | Gauge, Bar, Value | Gauge |
| Steps | Existing bespoke goal bar | Fixed | Bar |
| HRV | Personal baseline | Gauge, Bar, Value | Value |
| Sleep RHR | Personal baseline | Gauge, Bar, Value | Value |
| Sleep Duration | Configured goal | Gauge, Bar, Value | Value |
| RAS | Score/target, 0-100 with numeric overflow | Gauge, Bar, Value | Value |
| Resting HR | Personal baseline | Gauge, Bar, Value | Value |
| Circadian Consistency | Score, 0-100 | Gauge, Bar, Value | Value |
| Strain Ratio | Existing desirable reference range | Gauge, Bar, Value | Value |
| Sleep Efficiency | Score, 0-100 | Gauge, Bar, Value | Value |
| Oxygen Saturation | Bounded threshold scale | Gauge, Bar, Value | Value |
| Heart Rate | Multi-value daily range | Value only | Value |
| Weight | BMI reference axis | Gauge, Bar, Value | Value |
| Body Fat | Profile/gender reference axis | Gauge, Bar, Value | Value |
| Blood Pressure | Two-dimensional value | Value only | Value |
| Insights | Custom non-metric card | Fixed | Existing |

Value-only cards do not show a redundant mode selector.

## Metric Semantics

### Scores

Score visuals use their actual bounded range and the same threshold
definitions that produce their `MetricStatus`. Gauge and Bar read the same
prepared thresholds and normalization. Values outside the visual range clamp
only the marker; the real value remains displayed and announced.

### Goals

Goal visuals use the real configured target and include a visible target
marker where useful. Above-target values remain numerically correct. A missing
or invalid target does not silently substitute a generic target.

Steps is the approved temporary exception and is not migrated to this shared
goal calculation.

### Personal Baselines

HRV, Sleep RHR, and Resting HR show the current value relative to the stored or
configured personal baseline and existing ratio thresholds. The axis and
semantic description communicate baseline relationship; neither renderer may
use a generic completion fill that implies higher is always better.

### Reference Ranges

Strain Ratio, BMI-derived Weight, and profile-aware Body Fat use a marker and
desirable bands. They do not present distance along the axis as completion.

Weight always displays the actual weight and unit. BMI and its classification
are secondary context.

The BMI marker uses piecewise normalization:

- BMI 10 maps to 0%.
- BMI 21.7 maps to 50%.
- BMI 40 maps to 100%.

BMI category definitions and statuses become:

- Below 18.5: Underweight, Warning.
- 18.5 through 24.9: Healthy Weight, Optimal.
- 25.0 through 29.9: Overweight, Warning.
- 30.0 and above: Obesity, Poor.

Body Fat category ranges become:

| Category | Men | Women |
| --- | --- | --- |
| Essential | 2-5% | 10-13% |
| Athletic | 6-13% | 14-20% |
| Fitness | 14-17% | 21-24% |
| Acceptable | 18-24% | 25-31% |
| Obese | 25% and above | 32% and above |

Status mapping is:

- Below Essential: Warning.
- Essential: Neutral.
- Athletic and Fitness: Optimal.
- Acceptable: Neutral.
- Obese: Poor.

For continuous values, the exact intervals are:

- Men: below 2% is Warning, 2% to below 6% is Neutral, 6% to below
  18% is Optimal, 18% to below 25% is Neutral, and 25% or above is
  Poor.
- Women: below 10% is Warning, 10% to below 14% is Neutral, 14% to
  below 25% is Optimal, 25% to below 32% is Neutral, and 32% or above
  is Poor.

The profile-aware 50% marker is:

| Profile | Men | Women |
| --- | --- | --- |
| Athlete | 9.5% | 17% |
| Active | 15.5% | 22.5% |
| Sedentary | 19.5% | 26.5% |

The Body Fat visual axis uses piecewise normalization:

- Men: 2% maps to 0%, the profile midpoint maps to 50%, and 25% maps
  to 100%.
- Women: 10% maps to 0%, the profile midpoint maps to 50%, and 32% maps
  to 100%.
- Other, prefer not to say, or missing gender: 0% maps to 0%, 20% maps
  to 50%, and 40% maps to 100%.

For Other, prefer not to say, or missing gender, the fixed status bands are:

- 15% through 25%: Optimal.
- 10% through below 15%, and above 25% through 30%: Neutral.
- Below 10% or above 30%: Poor.

Boundary implementation must avoid overlap and have explicit tests for every
inclusive/exclusive edge.

These BMI and Body Fat classifications replace the existing
`HealthMetricsCalculator` behavior everywhere, not only on the dashboard.
They therefore require synchronized threshold documentation and in-app
explanation updates.

## Edit-Mode Interaction

In normal mode, a metric card's upper-right corner retains the existing
localized info tooltip. In edit mode, configurable cards replace it with a
48dp M3 `IconButton` that opens an M3 `DropdownMenu`.

Each menu entry has a localized icon and label. Structurally unsupported modes
are omitted. Temporarily unavailable modes remain listed but disabled. The
requested mode has selected semantics and a visible check indicator.

Selecting a mode updates the matching pending `CardConfiguration`, closes the
menu, and previews the renderer immediately. Card width, height, grid span,
position, and composition identity remain stable.

Normal navigation clicks remain disabled in edit mode. Save and Cancel use the
existing edit FAB and commit or discard mode, order, and visibility together.
The existing visibility bottom sheet remains responsible for visibility and
reset; dismissing it leaves edit mode active.

The existing left drag indicator becomes the dedicated long-press drag handle.
The grid must no longer initiate drag from the entire card surface. Slot
measurement, haptics, live reorder preview, deletion zone, and pending-order
logic remain intact. This gives the menu button exclusive pointer ownership
and prevents menu/drag conflicts.

## Persistence And Compatibility

Add `string requested_display_mode = 4` to
`CardConfigurationProto`. Persist enum names rather than ordinals.

Mapping behavior:

- Missing or blank mode: map to `null`.
- Unknown mode string: map to `null`.
- Known mode: preserve it even if the current card does not support it.
- Unknown card ID: retain the current behavior and drop it.
- Legacy `PAI_DAILY`: retain the current mapping to `RAS_DAILY`.

Use a nullable tolerant Kotlin serializer for
`requestedDisplayMode`. It must decode a missing property or unknown enum
string as `null`. This applies to legacy JSON migration and encrypted backup
restore.

Backup export includes the requested mode with each card configuration.
Restore writes it through the existing card repository with order and
visibility. Old backups without the field restore with legacy defaults.

The proto addition is backward compatible and needs no Room migration or
destructive DataStore migration. Existing records resolve `null` against the
catalog. Newly added default cards continue to be appended by the repository.
Removed rendered cards should retain their `CardId` enum value for storage and
backup compatibility; the grid filters cards without render data.

Add `DisplayModeChanged(cardId, mode)` to `CardManagementEvent`. It updates only
the matching pending configuration. Save persists the entire pending list;
Cancel clears it without repository writes.

## Missing And Invalid Data

- Loading shows the existing fixed-size skeleton and never substitutes zero.
- A missing metric value after loading retains the selected layout, displays
  an em dash plus localized no-data context, and uses unavailable visuals.
- A missing or invalid goal (`<= 0`) disables Gauge and Bar. A previously
  selected Gauge or Bar remains selected and renders grey with the real value,
  no target marker, and localized target-unavailable semantics.
- A missing or immature baseline follows the same preservation rule and uses
  localized baseline-not-ready semantics. Availability derives from existing
  calibration state and stored/override baseline availability.
- Weight without valid height/BMI disables Gauge and Bar. A preserved visual
  selection renders grey while retaining the real weight.
- Historical missing fields remain missing rather than becoming zero. The
  repository has no per-metric completeness provenance, so the UI does not
  invent a cause.
- Out-of-axis markers clamp visually while actual numeric values remain visible
  and accessible.
- Unknown modes use the card's legacy default.
- Structurally unsupported known modes use the card's catalog default without
  rewriting storage.
- Temporarily unavailable selected modes never rewrite storage.

## Performance

Presentation preparation occurs in the existing ViewModel data flow on its
default dispatcher. Composables receive stable, immutable prepared models and
perform no range, baseline, target, BMI, Body Fat, or formatting calculations.

Change the card content boundary so the grid passes an individual
`CardConfiguration` to its card renderer. A mode-only change must not rebuild
the metric presentation map. Add keyed composition by stable `CardId` to
preserve identity through mode changes and reorder operations.

Keep dropdown expansion as local ephemeral state. Do not add a chart
dependency for Gauge or Bar. Use Canvas and native M3 primitives.

Switching renderers during edit mode must not replay the gauge's current
800ms entrance animation. Normal-mode data transitions retain current motion,
subject to Compose and the system motion-duration scale.

Preserve the existing optimization that prevents sync/recalculation progress
from rebuilding all card content. Tests must verify that changing one card's
mode does not recompose an unaffected sibling.

`DashboardCardFactory.kt` is already above the preferred 400-line target.
Split only code directly touched by this feature into focused presentation,
shared-shell, and renderer files. This is a feature-boundary extraction, not a
general dashboard refactor.

## Accessibility And Localization

All mode names, menu labels, unavailable messages, classifications, target and
baseline descriptions, units, and accessibility descriptions use string
resources. No new user-facing string is hardcoded in Kotlin.

The shared container exposes one coherent card description that includes:

- Primary value and unit.
- Classification/status in text.
- Target and above/below-target relationship for goal metrics.
- Baseline or reference-range relationship for baseline/range metrics.
- No-data or unavailable reason where known.

The menu button and items expose selected state without relying on color.
Example descriptions include:

- "Sleep score 86 out of 100, excellent."
- "Sleep duration 6 hours 50 minutes, target 8 hours."
- "HRV 41 milliseconds, within personal range."
- "Visualization style: Bar, selected."

Touch targets are at least 48dp. Reference bands and statuses use marker
shapes/text descriptions in addition to color.

## Documentation Impact

Changing BMI and Body Fat thresholds requires the implementation change to
update all synchronized explanations in the same change:

- `ABOUT.md`
- `docs/about.md`
- Relevant sections of `internal-docs/DATA_FLOW.md`
- Relevant `about_*` and `tooltip_*` strings in
  `app/src/main/res/values/strings.xml`

The implementation must preserve agreement among these sources and pass the
existing documentation drift/presence tests. Public backup documentation must
also state that dashboard visualization preferences are included with card
configuration in encrypted local backups.

## Testing Strategy

Pure JVM tests cover:

- Every catalog legacy default matches the current visualization.
- Supported-mode matrices and safe structural fallback.
- Independent requested modes per card.
- Score, goal, personal-baseline, and reference-range normalization.
- Shared Gauge/Bar use of the same prepared visual data.
- Above-target and out-of-axis clamping without numeric truncation.
- Missing targets, baselines, height, and metric values.
- Exact BMI piecewise positions, categories, statuses, and boundaries.
- Exact Body Fat profile/gender positions, categories, statuses, and
  boundaries.
- Fixed-group Body Fat bands and boundaries.
- Revised calculator behavior everywhere it is consumed.

Persistence and delegate tests cover:

- Blank/missing proto mode uses the per-card legacy default.
- Each card stores a different mode and one update does not affect siblings.
- Proto and backup round trips.
- Old backup without mode.
- Unknown mode strings.
- Known but unsupported modes.
- Newly added and removed cards.
- Immediate pending preview.
- Save persists all pending changes.
- Cancel restores persisted state and writes nothing.

Compose instrumentation tests cover:

- Selector is absent in normal mode.
- Selector replaces the info icon only in edit mode.
- Value-only and fixed cards have no selector.
- Dropdown contains only structurally supported modes.
- Temporarily unavailable modes are visible and disabled.
- Current mode has selected semantics.
- Selection immediately changes only that card renderer.
- Grey unavailable Gauge/Bar retains the real numeric value.
- Card dimensions remain stable.
- Normal card click and info behavior remains intact.
- Menu interaction does not start drag.
- Dedicated drag handle preserves reorder, delete, and haptic behavior.
- Accessibility semantics include value, status, target/baseline relationship,
  unavailability, and selected mode.

Extend the existing dashboard recomposition coverage to verify that a
mode-only update does not recompose an unaffected sibling. Do not add a
screenshot framework solely for this feature.

## Out Of Scope

- Trend mode.
- Global visualization settings.
- Normal-mode mode controls.
- A renderer/plugin registry.
- Changes to score formulas.
- Correcting the approved fixed Steps normalization exception.
- New chart dependencies.

## Resolved Product Decisions

- Insights is fixed and non-configurable.
- Steps is fixed and remains unchanged for now.
- Cards expose only semantically valid modes.
- Strain Ratio, Weight, and Body Fat support Gauge and Bar.
- The selector is a dropdown in each configurable card's upper-right corner.
- The mode preference is preserved when prerequisites become unavailable.
- Previously selected unavailable visuals remain visible in grey.
- Configured modes participate in encrypted backup and restore.
- BMI and Body Fat classifications are updated everywhere for consistency.

## Unresolved Questions

None. All product decisions required by this design were resolved during
brainstorming.
