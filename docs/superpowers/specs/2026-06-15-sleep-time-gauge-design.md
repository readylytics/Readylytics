# Sleep Time Gauge Design

## Goal

Add a second gauge to the Sleep tab so users can see actual sleep time at a glance next to Sleep Score.

## User Decisions

- Use actual asleep time, not total session duration.
- Align the Sleep tab top gauge row with Workouts, Vitals, and Dashboard visual patterns.
- Sleep Time gauge goal maps to 50% fill.
- Gauge can continue filling after the goal and reaches full at 2x the configured sleep goal.

## Current Context

`SleepScreen` currently shows one centered `M3ScoreDial` for Sleep Score. Workouts already uses a two-dial row with weighted centered dials. Vitals uses the same side-by-side dial concept.

`SleepViewModel` already injects `SettingsRepository`, but `SleepUiState` does not yet expose `goalSleepHours`. `SettingsRepository.userPreferences` contains the configured sleep goal.

Existing sleep duration status logic lives in `DailySummary.sleepDurationStatus(goalMinutes)` and is already used by the dashboard sleep-duration card.

## Proposed Design

Replace the single centered Sleep Score dial block with a two-dial row:

- Left: Sleep Score
- Right: Sleep Time

Use the same layout style as `WorkoutStatsSection`:

- `Row`
- horizontal padding of 16dp
- 8dp spacing
- each dial uses `Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally)`
- skeleton state shows two `ScoreDialSkeleton` instances

## Sleep Time Value

Sleep Time displays actual asleep time:

```text
actualSleepMinutes = latestSession.durationMinutes - latestSession.awakeMinutes
```

If no sleep session exists, display the app's existing unavailable value (`—`).

Display format should be compact and stable for the dial center:

```text
7h 45m
8h
45m
```

## Gauge Fill

Sleep Time gauge fill uses the configured sleep goal:

```text
goalMinutes = (goalSleepHours * 60).toInt()
progress = actualSleepMinutes / (goalMinutes * 2f)
```

Clamp progress to `0f..1f`.

This means:

- 0 sleep minutes = 0% fill
- goal sleep minutes = 50% fill
- 2x goal sleep minutes = 100% fill

Because `M3ScoreDial` already supports `maxScore`, pass a normalized score with `maxScore = 1f`, or pass raw minutes with `maxScore = goalMinutes * 2f`. Raw minutes is easier to inspect in tests.

## Status And Color

Reuse existing duration status logic:

```kotlin
summary.sleepDurationStatus(goalMinutes)
```

This keeps Sleep Time gauge color consistent with the dashboard sleep-duration card and avoids creating new scoring/status thresholds.

If summary or goal is unavailable, use `MetricStatus.CALIBRATING`.

## Data Flow

Add `goalSleepHours` to `SleepUiState`.

In `SleepViewModel`, include `settingsRepo.userPreferences` in the existing `combine` that builds `SleepUiState`, then copy `prefs.goalSleepHours` into state.

No Health Connect access changes. No Room schema changes. No scoring formula changes.

## Strings

Add new string resources for:

- Sleep Time label
- Sleep Time tooltip

Also move the existing hardcoded Sleep Score label and tooltip text in `SleepScreen` to `strings.xml` while touching the same UI block.

## Testing

Extract small pure helper functions for gauge math and display formatting, then add focused tests:

- actual sleep calculation subtracts awake minutes
- goal sleep maps to 50% gauge fill
- 2x goal maps to full gauge fill
- over-2x goal clamps to full
- no session displays unavailable state

Run:

```powershell
.\gradlew testDebugUnitTest
.\gradlew lint
```

## Out Of Scope

- Changing sleep score formulas
- Changing dashboard card order or dashboard layout
- Changing Health Connect ingestion
- Adding a separate sleep-time settings control
- Reworking `M3ScoreDial` visuals beyond what the second dial needs
