# Sleep Trend Chart: Main Sleep and Nap Reporting

## Goal

Fix the sleep trend chart so each scoring day reports the canonical main sleep, all counted sleep in the total duration, and supplemental naps in the tooltip.

## Behavioral contract

- Main sleep is the scoring layer's canonical core cluster.
- Sessions belong to the day assigned by the active `SleepDayPolicy`, not simply the local calendar date on which they end.
- Total sleep duration is canonical core sleep plus all counted supplemental naps.
- The chart window shows only the canonical core interval.
- The duration line shows total counted sleep, including naps.
- The tooltip bedtime uses the canonical core start and end.
- Naps are tooltip-only for now and are listed as separate bullet points with start, end, and duration.
- A nap-only day remains visible with total duration and nap details; bedtime is unavailable.
- Existing chart legends, scrolling, zooming, selection, and overlay behavior remain unchanged.

Example tooltip content:

```text
Duration: 8h 05m
Bedtime: 11:42 PM – 7:10 AM
Naps:
• 2:00 PM – 2:35 PM (35m)
• 5:10 PM – 5:45 PM (35m)
01.08
```

## Data flow and boundaries

The sleep feature will consume a focused, pure-Kotlin chart projection built from the existing `SleepDayAggregator` and active `SleepDayPolicy`.

1. Load all sessions relevant to the selected trend range, including sessions that begin before the range but are assigned to a scoring day inside it.
2. Aggregate sessions using the existing scoring policy. This preserves canonicalization, core-cluster selection, overnight merging, minimum-duration filtering, and scoring-day assignment.
3. Project each aggregate into a trend-day model containing:
   - optional canonical core start and end;
   - total counted sleep duration;
   - ordered supplemental nap intervals;
   - whether canonical core sleep exists.
4. Map the projection into the existing chart inputs:
   - core interval for the sleep-window bar;
   - total duration for the duration line;
   - nap intervals in selection state for tooltip rendering.

The chart must not choose a session with `firstOrNull()`. Scoring formulas, ingestion, Room schema, and Health Connect behavior are out of scope.

## UI and strings

The existing Compose/Vico chart remains the rendering surface. New tooltip labels and the unavailable-bedtime text must be added to `feature/sleep/src/main/res/values/strings.xml`; no user-facing text is hardcoded in Kotlin.

The tooltip keeps total duration as its primary value, followed by main bedtime, optional nap bullets, and the date. Nap-only days show a localized unavailable value (`Bedtime: —`) instead of a bedtime range.

## Testing

Add pure unit coverage for:

- multiple sessions assigned to one scoring day;
- canonical core selection and merged overnight segments;
- total duration including naps;
- nap ordering and tooltip formatting;
- sessions around the scoring-day cutoff;
- nap-only days;
- no-data days;
- preservation of existing chart selection behavior.

Existing sleep trend and ViewModel tests should be updated rather than bypassed. No Android dependency is required for the projection or calculation tests.

## Scope and follow-up

This change does not render naps as additional chart bars. A future design can add that visualization independently if needed.
