# Vitals Scroll Performance Design

## Goal

Reduce visible Vitals overview scroll stutter while keeping all three charts composed and rendered directly inside the existing `Column.verticalScroll` hierarchy.

## Constraints

- Do not add `LazyColumn`, lazy loading, viewport-based composition, or deferred chart rendering.
- Keep HRV, resting-heart-rate, and SpO2 charts synchronized through shared Vico scroll and zoom state.
- Use code-only verification. Do not add Macrobenchmark or frame-timing work.
- Preserve chart gestures, tooltips, zone bands, baselines, and current visual output.

## Phase 1: Targeted Hot-Path Fix

Remove work known to run during otherwise ordinary scrolling:

1. Start tooltip halo animation only while a selected point needs a halo. Idle charts must schedule no tooltip animation frames.
2. Ignore Vico marker callbacks while parent vertical scrolling is active. Parent scroll transitions still clear any visible tooltip.
3. Pre-index valid chart points by `dayOffset` and reuse precomputed median/bounds inputs instead of scanning points inside each marker callback or model update.
4. Avoid equivalent snapshot-state assignments for tooltip and selected-point state.

This phase changes shared chart internals only. Vitals retains eager composition of all charts.

## Phase 2: Conditional Render-State Refactor

Run only if phase 1 lands and same-device manual scrolling still visibly stutters with loaded data and no active tooltip.

1. Split Vitals data derivation so summary database emissions rebuild chart series, preference emissions rebuild threshold/zone state, and sync emissions update loading state without rebuilding chart lists.
2. Move baselines into `VitalsUiState`, eliminating the route's second baseline collection and duplicate screen invalidation path.
3. Apply structural `distinctUntilChanged` boundaries to chart series and presentation state.
4. Split gauge and trend sections into focused composables so unrelated state changes can skip chart composition.

## Verification

Phase 1 uses pure unit tests for chart render-data preparation, marker guard tests, Robolectric Compose lifecycle tests proving idle overlays do not compose animated halos, and existing `core:ui`/Vitals tests. Phase 2 adds pure state-factory tests and ViewModel flow tests proving unchanged chart values, ranges, baselines, zones, and selective emissions. Both phases finish with formatting, unit tests, and lint. Passing checks prove behavior and architecture only; they do not support measured frame-time claims.

## Rejected Approaches

- Lazy composition or viewport gating: violates direct-render constraint.
- Removing `graphicsLayer {}` blindly: layer isolation may help scrolling; no profiling evidence supports removal.
- Disabling chart interaction or animation globally: unnecessary behavior regression.
- Macrobenchmark acceptance gate: explicitly excluded in favor of code-only work.
