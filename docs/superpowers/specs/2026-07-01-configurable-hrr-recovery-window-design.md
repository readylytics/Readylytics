# Configurable Heart Rate Recovery Match Window

## Goal

Make workout heart-rate recovery (HRR) resolve reliably for watches that record sparse post-workout samples, while allowing users to tune sample-matching tolerance.

HRR remains a display-time workout metric. This change does not alter stored scores, HRR fitness thresholds, ingestion, or scoring formulas.

## Problem

`WorkoutDetailViewModel` currently fetches heart-rate data only through workout end plus three minutes. The three-minute HRR target therefore sits on the query boundary, leaving no data for the positive side of its tolerance window.

`RecoveryMetricsMapper` also uses a fixed ±15-second tolerance. Watches that record approximately once per minute can place the nearest sample as much as 30 seconds from an exact one-, two-, or three-minute target. Existing data then fails matching and displays N/A.

## Chosen Approach

Store an HRR tolerance preference, defaulting to 30 seconds with an allowed range of 15–60 seconds. Thread this value explicitly from preferences through `WorkoutDetailViewModel` into `RecoveryMetricsMapper`. Extend both Health Connect and Room query windows through workout end plus three minutes plus configured tolerance.

Rejected alternatives:

- Keep a hardcoded 30-second tolerance: smaller change, but does not satisfy configurability and would require another migration later.
- Infer tolerance from each workout's sample cadence: adaptive, but less predictable, harder to explain, and unnecessary for known consumer-watch behavior.

## Architecture and Data Flow

`UserPreferences.hrrToleranceSeconds` flows through:

1. `user_preferences.proto` persistence.
2. Proto-to-domain preference mapping.
3. Settings repository and display-settings port.
4. Advanced settings state, event handling, and slider.
5. `WorkoutDetailViewModel`, which snapshots preferences while loading workout details.
6. `RecoveryMetricsMapper`, which receives tolerance as an explicit argument.

`WorkoutDetailViewModel` derives:

```text
recoveryWindowEnd = workoutEnd + 3 minutes + hrrToleranceSeconds
```

Both Health Connect and Room heart-rate reads use this upper bound. Chart data, workout samples, and end-HR selection remain clamped at workout end, preventing post-workout samples from affecting workout metrics.

`RecoveryMetricsMapper` finds the nearest sample to each target at workout end plus one, two, and three minutes. A sample is accepted when its absolute offset is less than or equal to configured tolerance. Existing deterministic ordering remains tie-break behavior when two samples have equal offsets.

Missing end HR or missing accepted samples continues producing null values, displayed as N/A.

## Preference Contract

- Domain field: `hrrToleranceSeconds: Int`.
- Default: 30 seconds in `SettingsDefaults`.
- Allowed range: 15–60 seconds.
- Proto zero/unset: map to default 30 seconds.
- Persisted nonzero out-of-range values: clamp to 15–60 seconds.
- Setter input: clamp to 15–60 seconds before persistence.
- UI events: validate with `HrrToleranceRule` through `SettingsValidators`; reject invalid values.

`ScoringConstants.Workout.HRR_TOLERANCE_SECONDS` is removed. HRR optimal thresholds of 18 bpm at one minute and 35 bpm at two minutes remain unchanged.

## Settings UI

Advanced settings gains a `ThresholdSliderItem` named “Recovery match window.”

- Range: 15–60 seconds.
- Increment: 5 seconds (`valueRange = 15f..60f`, `steps = 8`).
- Display format: `<value> s`.
- Tooltip: explains ± matching around each minute target, recommends increasing tolerance for watches that record post-workout HR infrequently, and states default 30 seconds.

Slider events persist through `DisplaySettings.updateHrrToleranceSeconds`. No health-data refresh or score recalculation runs because HRR is recomputed when workout details are opened.

## Backup and Restore

Inspect backup models and mappers during implementation. If preference fields are enumerated explicitly, add `hrrToleranceSeconds` to backup, restore, and round-trip coverage. If backup serializes the preference proto or domain object wholesale, no separate field wiring is needed.

Restored values follow the same zero/default and nonzero/clamping rules as normal persistence.

## Validation and Error Handling

Validation exists at both boundaries:

- UI event validation rejects values outside 15–60 seconds.
- Persistence mapping and setters normalize stored or restored values defensively.

No new user-facing error state is needed. Repository read failures retain existing workout-detail error behavior. Sparse or absent recovery samples remain valid data absence and produce N/A.

## Test Design

### Workout mapper

- Update existing mapper calls with explicit tolerance.
- Exact one-, two-, and three-minute samples remain accepted.
- Samples 20–25 seconds from targets resolve under 30-second tolerance.
- Samples more than 30 seconds from targets remain null.
- A custom 10-second mapper tolerance rejects a sample 20 seconds from target, proving mapper honors its argument independently of UI range validation.
- Boundary samples exactly tolerance seconds away are accepted.

### Workout detail ViewModel

- Preferences emit `hrrToleranceSeconds = 30`.
- Captured repository upper bound equals workout end plus three minutes plus 30 seconds.
- Preference value is passed into recovery mapping, demonstrated by a sparse post-workout sample resolving under 30 seconds.
- Post-workout samples do not affect chart data or end HR.

### Preferences and settings

- Proto zero maps to 30.
- Nonzero persisted values below and above range clamp to 15 and 60.
- Setter clamps before persistence.
- Valid settings event invokes setter.
- Invalid settings event is rejected and does not invoke setter.
- No score refresh is triggered.
- Backup round-trip test is added when backup fields are explicit.

### Verification

Run:

```text
./gradlew ktlintFormat
./gradlew :feature:workouts:testDebugUnitTest :core:model:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.docs.DocumentationDriftTest"
./gradlew lint
```

Manual verification: open a workout with post-workout watch samples and confirm one-, two-, and three-minute HRR values display. Change recovery match window in Settings → Advanced, reopen workout, and confirm matching reflects new tolerance.

## Documentation and Indexing

Update in same implementation change:

- `internal-docs/DATA_FLOW.md`: query-window and nearest-sample rules.
- `internal-docs/HRR_RECOVERY_FIX_PLAN.md`: configurable design replacing hardcoded 30-second plan.
- `docs/customization.md`: advanced recovery match window control.
- Settings string resources: label and tooltip.

No `ABOUT.md`, `docs/about.md`, About strings, or scoring-documentation changes are required because matching tolerance is not a scoring formula, threshold, coefficient, or score explanation.

Creating `HrrToleranceRule.kt` requires `codegraph index` after implementation. Structural movement is not planned, so `codegraph sync` is unnecessary unless implementation introduces directory changes.

## Scope Boundaries

Included: HRR fetch range, matching tolerance, preference persistence, advanced UI, validation, backup compatibility, tests, and related documentation.

Excluded: HRR clinical thresholds, workout scoring, Health Connect ingestion, chart behavior, historical recomputation, new background work, and automatic cadence inference.
