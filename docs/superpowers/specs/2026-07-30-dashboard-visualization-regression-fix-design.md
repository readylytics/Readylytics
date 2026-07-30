# Dashboard Visualization Regression Fix Design

**Date:** 2026-07-30

## Goal

Restore the dashboard Value and Gauge appearances from `main`, correct the new
Bar renderer, and enforce the same one-active-color/one-neutral-track principle
for shared gauges without changing card dimensions, supported modes,
normalization, scoring, baselines, ordering, or visibility.

## Root Causes

The visualization-mode foundation replaced the original mode-specific content
hierarchies with a shared title row followed by a centered content box.
Consequently, Value mode lost `MetricCard` typography, spacing, alignment,
status-aware container colors, and footer placement. Gauge mode lost the
original centered value/unit hierarchy and delta footer.

Gauge and Bar renderers consume the prepared classification bands as visible
colored future sections. Gauge also suppresses progress for personal-baseline
and reference-range visuals, while Bar draws a large marker dot and places its
value over the track. Both renderers use the theme primary color rather than
the existing metric status color resolver.

The shared `M3MetricGauge` accepts arbitrary colored segments and falls back to
hardcoded `Color.LightGray`. This makes segmented tracks easy to reintroduce
and prevents one consistent theme-aware neutral track across tabs.

During presentation extraction, several original status calculations,
secondary values, time ranges, and tooltip descriptions were not preserved.
This leaves renderers without the data needed to reproduce the original
hierarchy and metric color.

## Chosen Approach

Keep the existing persisted display modes, card catalog, typed presentations,
normalization, edit transaction, and stable card dimensions. Amend only the
presentation details and rendering layer.

The shared card remains the dispatch point, but its colors and content
hierarchy become mode-aware:

- Value uses the original `MetricCard` status-aware container/content colors,
  design-token padding, `displaySmall` primary typography, and footer
  hierarchy.
- Gauge uses the original neutral gauge-card container, title treatment,
  centered value/unit, and secondary delta footer.
- Bar uses the same neutral card role as Gauge, with the value and unit outside
  the track and secondary information below it.

This approach preserves composition stability while avoiding card-specific
rendering branches.

## Shared Gauge Contract

`M3MetricGauge` will expose progress and active color but will always draw one
complete neutral track using a Material-theme-derived neutral color. It will
then draw one continuous active arc from the start to the clamped normalized
position.

Colored threshold segments and baseline, target, and range markers will not be
part of the rendered gauge contract. Existing prepared bands and reference
fractions remain untouched because they are also used for normalization and
accessibility classification.

All existing `M3ScoreGaugeCard` callers across Dashboard, Sleep, Workouts, and
Vitals inherit the neutral-track correction.

## Color and Status

Gauge and Bar active colors resolve only through Readylytics'
`MetricStatus.gaugeColor()` implementation. No renderer-specific thresholds,
hardcoded colors, or future-section colors will be introduced.

Presentations for HRV, sleep RHR, resting heart rate, sleep duration, and RAS
will reuse the existing domain status extensions used on `main`. Sleep score
and Readiness retain their existing score classification. Status labels remain
in accessibility descriptions and are never rendered as visible text.

Unavailable metrics use existing theme content colors and retain accurate
display text. Visual progress may clamp to `[0, 1]`; raw displayed values do
not clamp.

## Content Rules

Value mode renders no Canvas, gauge, bar, marker, or track. Its primary value
uses the original large typography and established alignment. Unit or
secondary content uses the original footer hierarchy, with existing time
ranges and deltas retained where supplied.

Gauge mode renders the primary value centered inside the original gauge area,
the unit beneath it where applicable, and the existing delta footer where
supported.

Bar mode renders the primary value and unit before the bar. The bar has one
continuous active fill and one neutral remainder, with no bubbles, bands, or
markers. Existing secondary time ranges and deltas appear outside the bar.

Daily Steps remains its fixed bespoke Bar and no supported-mode definition is
changed.

## Material 3 and Accessibility

Cards continue using `MaterialTheme.shapes.large`,
`MaterialTheme.dimens.cardHeight`, theme typography, explicit M3 container
roles, and Readylytics design-token spacing. Clickable cards use the Material 3
clickable `Card` overload so ripple, focus, hover, pressed, and disabled
behavior follow the component contract.

The edit-only display-mode menu and tooltip retain at least 48 dp touch
targets. Normal mode exposes no visualization selector. The merged card
semantics retain the localized accessibility description, including status,
while visible descendants do not show classification labels.

Text uses bounded lines and overflow handling where needed. The original card
height remains fixed across modes, preventing dashboard reflow.

## Testing

Regression tests will be written before production changes and observed
failing for the expected reasons. Coverage will verify:

- Value typography hierarchy, units, secondary information, and absence of
  visual tracks.
- Gauge units and deltas, one active color, one neutral track, and no visible
  classifications or markers.
- Bar values and units outside the track, retained secondary information, and
  no classifications or marker bubbles.
- Stable card height and edit-only selector behavior.
- Existing mode support for Sleep score, Readiness, HRV, Sleep duration, RAS,
  resting heart rate, and Steps.
- Shared `M3ScoreGaugeCard` behavior used outside Dashboard.
- Accessibility descriptions retain status while status text is not visible.

Focused unit and Compose tests run first. Final verification runs
`./gradlew ktlintFormat`, `./gradlew testDebugUnitTest`, and
`./gradlew lintRelease`. Connected Dashboard tests run when a device or
emulator is available.

## Scope Boundaries

This change does not modify scoring formulas, metric normalization, baseline
calculations, Health Connect processing, retention, Room, card ordering,
visibility, persisted mode compatibility, or per-card supported modes.
Unrelated refactoring is excluded.
