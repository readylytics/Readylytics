# Dashboard Visualization Refinement Design

## Goal

Restore the established Readylytics dashboard hierarchy while refining shared Value, Gauge, and Bar rendering. The work preserves all scoring, normalization, supported modes, card geometry, ordering, and Daily Steps.

## Evidence and root causes

- The Heart Rate presentation uses `avgBpm` as its primary value, discarding the already-available daily min/max range.
- The generic Value renderer applies one vertically stretched layout to every metric and renders secondary information as plain text, so original per-metric hierarchy and trend pills are lost.
- Gauge and Bar modes replace a status-tinted container with `surfaceContainerHighest`, making status-colored cards such as Strain/RAS inconsistent across modes.
- The Bar renderer's weighted spacer separates the value from its track and makes secondary content vulnerable to clipping.
- Gauge titles are limited to one line and gauge values use reduced manual typography, causing compact-card title/icon collisions and weak value emphasis.
- Percentage values are not consistently carried as formatted visible text; Circadian Consistency currently emits a bare number.

## Architecture

`DashboardMetricCard` remains the fixed-size Material 3 card shell and continues to select only the modes in `DashboardCardCatalog`. It will resolve the card background/content colors from the existing metric status system for every render mode; Gauge and Bar retain one status-derived active fill and the shared theme-aware neutral track.

The presentation factory becomes the single formatting boundary. It provides Heart Rate as a precomputed `min–max` primary value with `bpm · avg N` secondary text, and includes `%` in visible percentage values. It does not calculate metrics, normalize data, or create colors.

The renderers use shared spacing and a small, explicit Value-layout selection rather than a one-size-fits-all layout. Value cards retain original hierarchy: value dominant, native units in their established position, and existing trend/delta pills where the main implementation used them. Gauge and Bar cards retain their shared visualization components; only typography, title wrapping, and spacing change.

## Components

- `DashboardMetricPresentationFactory`: formats existing values, units, range/average secondary text, and accessibility descriptions.
- `DashboardMetricCard`: shared two-line-capable title/info row, existing 48 dp tooltip target, and mode-independent status container treatment.
- `DashboardValueRenderer`: restores selected metric-aware layouts and the shared non-clipping secondary-pill component.
- `DashboardGaugeRenderer`: restores dominant centered typography and supports two-line titles through the shell.
- `DashboardBarRenderer`: groups title/value/unit/bar/secondary elements with shared vertical spacing; no values move inside tracks.

## Constraints

- Use only existing Readylytics internal status colors and Material theme colors.
- Do not add thresholds, status labels, calculations, normalization, baselines, Health Connect work, modes, card-size changes, or Daily Steps changes.
- Keep visible status out of cards; retain status in accessibility descriptions.
- Ensure long titles, pills, and secondary content remain inside fixed card bounds at larger font scales.

## Validation

Add focused unit tests for Heart Rate range formatting, percentage formatting, and accessibility text. Add Compose tests for Value pills, Bar value/unit placement, Gauge title/icon layout, and status container/color continuity. Run dashboard tests plus repository formatting, unit tests, release lint, and debug assembly.

## Remaining limitation

Without emulator screenshot baselines, font-scale and light/dark verification is exercised through layout/semantics tests and theme-aware code paths; final pixel-level confirmation still benefits from manual device review.
