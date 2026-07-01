# HRR recovery matching fix plan

## Scope

This plan documents the repo-facing HRR recovery matching behavior for workout detail screens and the rationale behind the configurable recovery match window.

## Root causes

- Post-workout HRR matching originally assumed a hardcoded `30` second tolerance around the 1-, 2-, and 3-minute marks.
- That fixed window worked for dense heart-rate streams but under-matched recovery samples from watches that log less frequently after workouts.
- The old approach also obscured where the tolerance came from because the workout-detail pipeline, settings pipeline, backup/restore path, and tests did not share one user-facing explanation.

## Rejected approach

- Rejected: keeping the old hardcoded `30` second matching window.
- Reason: it locked sparse post-workout heart-rate streams into one tradeoff, made stricter matching impossible for users who want tighter HRR capture, and left the behavior under-documented across repo docs.

## Final design: configurable 15-60 second window

- HRR matching uses a configurable `hrrToleranceSeconds` preference with supported bounds `15..60`.
- Default remains `30` seconds.
- `WorkoutDetailViewModel` extends post-workout heart-rate reads through `workout end + 3 minutes + hrrToleranceSeconds` for both:
  - Health Connect heart-rate samples
  - Room heart-rate samples
- `RecoveryMetricsMapper` evaluates the 1-, 2-, and 3-minute HRR targets independently and selects the nearest sample inside an inclusive `±hrrToleranceSeconds` window for each target.
- The chart timeline and end-of-workout HR remain clamped to samples at or before the workout end, so the broader recovery read only affects HRR matching.

## Preference pipeline

- Runtime default: `SettingsDefaults.HRR_TOLERANCE_SECONDS = 30`.
- Domain model: `UserPreferences.hrrToleranceSeconds`.
- Persistence:
  - serializer stores the field in DataStore proto
  - mapper normalizes `0` to the default and clamps persisted values to `15..60`
- Settings UI:
  - exposed in Advanced Settings as **Recovery match window**
  - slider range `15..60` seconds
- Backup / restore:
  - exported in local backups as `hrrToleranceSeconds`
  - restored values are clamped back into `15..60`
- Workout detail consumption:
  - `WorkoutDetailViewModel` reads the current preference
  - passes it into the recovery fetch window and `RecoveryMetricsMapper`

## Tests

- `WorkoutDetailViewModelTest`
  - verifies heart-rate reads extend through workout end plus 3 minutes plus tolerance
  - verifies sparse post-workout samples within tolerance can populate HRR values
- `WorkoutMapperTest`
  - verifies nearest-sample selection inside the tolerance window
  - verifies samples outside the window are excluded
  - verifies 1/2/3-minute recovery targets map independently
- `SettingsRepositoryTest`
  - verifies default is `30`
  - verifies persisted values clamp to `15..60`
  - verifies serializer round-trip preserves supported values
- Backup tests
  - verify backup export includes `hrrToleranceSeconds`
  - verify restore imports and clamps the value

## Verification commands

- `.\gradlew :app:testDebugUnitTest --tests "app.readylytics.health.docs.DocumentationDriftTest"`
- Optional deeper spot checks when touching implementation again:
  - `.\gradlew :feature:workouts:testDebugUnitTest --tests "app.readylytics.health.feature.workouts.WorkoutDetailViewModelTest"`
  - `.\gradlew :feature:workouts:testDebugUnitTest --tests "app.readylytics.health.feature.workouts.mappers.WorkoutMapperTest"`
  - `.\gradlew :app:testDebugUnitTest --tests "app.readylytics.health.data.preferences.SettingsRepositoryTest"`

## Notes

- This branch did not previously contain an `internal-docs/HRR_RECOVERY_FIX_PLAN.md` file; this document is new in this task.
