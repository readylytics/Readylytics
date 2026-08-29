# Final Fix Report — Residual Fatigue Task 2 Completion

## Scope

Completed the two final-review findings from the residual-fatigue Task 2 completion wave.

Implementation commit: `7dc12204 fix(scoring): align canonical fatigue backfill boundaries`

## Finding 1 — stored scoring-zone boundary

Added `RetentionBounds.HistoricalWindow`, resolved from one instant in the stored scoring zone. It keeps the
recompute dates and the exact start-of-day epoch together. Startup's canonical-TRIMP backfill gate now uses
`HistoricalWindow.startTimeMs`; full resync, first-launch catch-up, and cleanup derive from the same boundary.
The retained/recomputed inclusion rule is `workout.startTime >= startTimeMs`; cleanup deletes the complementary
`startTime < startTimeMs` set.

The app regression test sets the device system zone to `Etc/GMT+12` while the stored scoring zone is
`Pacific/Kiritimati`, captures the DAO gate's cutoff, and proves that it is a stored-scoring-zone boundary rather
than system-zone midnight. `RetentionBoundsTest` verifies that the cleanup cutoff equals the same historical
window instant.

## Finding 2 — canonical TRIMP provenance for malformed restored workouts

Removed `storedTrimp` from the canonical `ComputeWorkoutTrimpUseCase` interface and daily computation input.
When there are no usable in-range HR samples and `endTime <= startTime`, the selected-model computation now
canonicalizes to `0f`; it cannot return a persisted Edwards-style `trimp` value. The daily pass persists that zero
as `modelTrimp`, so the startup backfill gate converges and residual fatigue receives no non-canonical impulse.

The repository-level production-path regression creates equal and reversed timestamp rows with `trimp = 80f` and
`modelTrimp = null`, runs two daily passes, and asserts persisted canonical `modelTrimp = 0f`, zero fatigue, and no
Edwards fallback.

## Changed files

- `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- `app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerScoringVersionTest.kt`
- `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DailyTrimpComputer.kt`
- `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ResidualFatigueCanonicalTrimpTest.kt`
- `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ResidualFatigueWalkForwardTestBase.kt`
- `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImplTest.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/FullHistoricalResyncUseCase.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthSyncUseCase.kt`
- `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RetentionBounds.kt`
- `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/util/RetentionBoundsTest.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeDailyTrimpUseCase.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutLoadMetricsUseCase.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutTrimpUseCase.kt`
- `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeDailyTrimpUseCaseTest.kt`
- `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutLoadMetricsUseCaseTest.kt`
- `internal-docs/DATA_FLOW.md`

## Verification commands and outputs

Focused regressions:

```text
./gradlew :core:model:testDebugUnitTest --tests '*RetentionBoundsTest*'
BUILD SUCCESSFUL in 28s

./gradlew :core:scoring:testDebugUnitTest --tests '*ComputeWorkoutTrimpUseCaseTest*'
BUILD SUCCESSFUL in 17s

./gradlew :core:database:testDebugUnitTest --tests '*ResidualFatigueCanonicalTrimpTest*'
TEST-app.readylytics.health.core.database.data.repository.ResidualFatigueCanonicalTrimpTest.xml:
tests="4" skipped="0" failures="0" errors="0"

./gradlew :core:healthconnect:testDebugUnitTest --tests '*FullHistoricalResyncUseCaseTest*'
BUILD SUCCESSFUL in 12s

./gradlew :app:testDebugUnitTest --tests '*DatabaseReadyStartupInitializerScoringVersionTest*'
BUILD SUCCESSFUL in 28s
```

Required gates:

```text
./gradlew ktlintFormat
BUILD SUCCESSFUL in 1s

./gradlew detekt
BUILD SUCCESSFUL in 3s

./gradlew assembleDebug
BUILD SUCCESSFUL in 12s

./gradlew testDebugUnitTest --quiet
exit 0

./gradlew lintRelease --quiet
exit 0

git diff --check
exit 0; no output

git diff --exit-code -- core/database/src/test/resources/golden/
exit 0; no output
```

`git diff --stat main...HEAD -- core/database/src/test/resources/golden/` still shows fixture changes from older
branch commits (`cde4d97e`, `96e30bab`, and `89e0b55f`); this fix wave does not modify any golden fixture.

## Concerns

- No functional concerns remain. Phase 1 remains shadow-only and residual fatigue stays workout-only and
  canonical-`modelTrimp`-only.
- The initial non-elevated Gradle invocation hit the sandbox restriction on the shared Gradle cache lock; all
  subsequent required verification commands used the approved elevated Gradle execution and completed successfully.
