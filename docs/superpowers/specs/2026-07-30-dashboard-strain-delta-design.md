# Dashboard strain-delta reuse

## Goal

Show the Dashboard Strain card's daily increase using exactly the same calculation and formatting source as the Workouts tab.

## Design

Extract the existing mode-specific daily-increase calculation from `WorkoutsViewModel` into a pure shared helper. Its inputs remain the existing selected date, tenure, load-source mode, workout gains, and ATL/CTL values. It returns the existing nullable increase: no value before seven days of tenure; the sum of already-rounded workout gains for workout-only mode; or the non-negative with/without-day strain-ratio difference for everyday-HR mode.

The Workouts ViewModel calls this helper without behavior changes. Dashboard obtains the same input data through its existing dashboard data flow and passes the resulting value to the metric-presentation factory. The factory formats it with the existing `delta_up_format`, `delta_up`, and `delta_no_change` resources, matching Workouts. The renderer already presents `secondaryText` as a Gauge or Bar delta pill.

## Constraints

- No scoring, baseline, normalization, or Health Connect changes.
- No duplicate calculation or new threshold.
- Preserve the existing null behavior and `0.005f` display cutoff.
- Add pure helper tests and Dashboard presentation coverage for positive, zero, and unavailable values.

## Validation

Run focused helper, dashboard, and workouts tests; then formatting, full unit tests, and release lint.
