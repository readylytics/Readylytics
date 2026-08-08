# Shared Metric Status Classification Design

## Goal

Audit and consolidate classification logic used by dashboard, vitals, workout, and sleep surfaces so identical metrics receive identical `MetricStatus` values. Preserve existing metric thresholds and behavior, except for the explicitly confirmed strain-ratio boundary policy.

## Confirmed strain-ratio policy

The canonical strain-ratio bands are:

- `< 0.5` → `POOR`
- `0.5` through `< 0.8` → `WARNING`
- `0.8` through `1.3` → `OPTIMAL`
- `> 1.3` through `1.5` → `NEUTRAL`
- `> 1.5` through `2.0` → `WARNING`
- `> 2.0` → `POOR`

Therefore, exactly `1.3` is optimal, exactly `1.5` is neutral, and exactly `2.0` is warning. Missing values retain the caller's existing unavailable-state behavior.

## Architecture

`core/model` remains the pure-Kotlin source of truth for reusable status decisions. Existing domain assessment models and services remain authoritative where they already provide shared classification, including HRV, RHR, SpO₂, BMI, blood pressure, steps, RAS, sleep duration, deep sleep, REM sleep, and body temperature.

The consolidation will extract or reuse shared classifiers for:

- score status, used by Sleep Score, Readiness, and the Sleep-tab score;
- sleep-efficiency status, used by dashboard and the Sleep tab;
- circadian-consistency status, used by dashboard and the Sleep tab;
- strain-ratio status, used by dashboard and the Workout tab.

Feature presentation code will select and render a returned `MetricStatus`; it will not define threshold ladders. Intentionally fixed-neutral cards such as nap count and nap duration remain unchanged because they do not currently represent a threshold-based health classification.

## Targeted changes

- Remove the dashboard-private `strainStatus`, `scoreStatus`, `sleepEfficiencyStatus`, and `circadianStatus` implementations.
- Remove Sleep's private `sleepScoreStatus` implementation.
- Route dashboard and Sleep call sites through shared pure-Kotlin classifiers.
- Keep the Workout strain-ratio call site on the shared classifier and correct that classifier's boundary ordering/ranges.
- Leave vitals call sites and existing assessment services unchanged unless the audit identifies a duplicate implementation of the same metric during execution.

## Testing

Add or update pure-Kotlin boundary tests for every extracted classifier. Strain-ratio tests must cover values immediately below, at, and immediately above `0.5`, `0.8`, `1.3`, `1.5`, and `2.0`, plus missing/unavailable behavior where applicable.

Add feature-level parity assertions proving that dashboard, Workout, and Sleep use the same status for the same shared metric inputs. Preserve existing feature tests for vitals and all other metric thresholds; update only expectations that reflect the confirmed strain-ratio policy or the removal of a duplicate implementation.

## Documentation and scope constraints

No scoring formulas, load calculations, or persistence behavior change. If repository documentation or in-app explanatory strings explicitly describe any affected status thresholds, update those references synchronously according to the repository documentation rules. Do not add a second status channel, UI-specific threshold map, or parallel classifier registry.

## Acceptance criteria

1. A strain ratio of `1.37` is `NEUTRAL` on both dashboard and Workout.
2. Dashboard, Workout, and Sleep share the same classifier for the metrics listed above.
3. No feature-local duplicate threshold ladder remains for those shared metrics.
4. Existing thresholds and unavailable-state behavior remain unchanged except for the confirmed strain-ratio boundaries.
5. Pure-Kotlin boundary and cross-surface parity tests pass.
