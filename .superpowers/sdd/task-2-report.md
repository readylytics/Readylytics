# Task 2: Seam Relocation & Decoupled Ingestion & Pure Mappers (ARCH-001 / PERF-004) - Report

## What was implemented
1. **Extracted zoneThresholds and computeMetrics**:
   - Relocated workout metrics calculations to a new pure-domain helper object `ZoneThresholds` in a new file [ZoneThresholds.kt](file:///Users/grl3lb/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/heartrate/ZoneThresholds.kt).
   - Created pure-domain `WorkoutMetrics` data class to hold workout duration, zone minutes (Edward-style weights), trimp, and average heart rate.

2. **Decoupled mappers and inputs**:
   - Created new pure domain mappers under `core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/`:
     - [SleepDataMapper.kt](file:///Users/grl3lb/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/SleepDataMapper.kt): maps `DomainSleepSessionRecord` straight to `SleepSessionInput` and `SleepStageInput`.
     - [WorkoutMapper.kt](file:///Users/grl3lb/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/WorkoutMapper.kt): maps `DomainExerciseSessionRecord` straight to `WorkoutInput` (with dynamically calculated elapsed duration minutes).
     - [HeartRateMapper.kt](file:///Users/grl3lb/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/HeartRateMapper.kt): maps `DomainHeartRateRecord` samples straight to `HeartRateInput` using `SessionLinkSweep`.
     - [HrvMapper.kt](file:///Users/grl3lb/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/HrvMapper.kt): maps `DomainHrvRecord` samples straight to `HrvInput` using `SessionLinkSweep`.
   - Deleted the old, redundant, database-dependent mappers under `core/healthconnect` and `core/model` (e.g. `app.readylytics.health.data.healthconnect.*`).

3. **Decoupled HealthIngestionCoordinator**:
   - Modified `HealthIngestionCoordinator` to use the new pure domain mappers, mapping all fetched DTOs straight to inputs and performing device filtering directly on the inputs.
   - Removed all dependencies on Room database entities (no `SleepSessionEntity`, `WorkoutRecordEntity`, etc. references), completely avoiding database entity allocations during ingestion.

4. **Updated Room layer and Reconciler**:
   - Modified `RoomHealthIngestionStore` to construct Room entities from the input data structures using its private `.toEntity()` extensions.
   - Modified `SessionLinkReconcilerImpl` to perform recomputations using the pure-domain `ZoneThresholds.computeMetrics` by mapping Room `HeartRateRecordEntity` database objects to the domain `DomainHeartRateSample` types.
   - Updated `HealthChangeSynchronizerImpl` to map incoming change DTOs straight to domain inputs and then convert them to database entities via helper conversions.

5. **Updated and Migrated Unit Tests**:
   - Created `ZoneThresholdsTest` under `core/model` to verify the relocated metrics calculation logic.
   - Migrated `WorkoutMapperIntensityTest` to `core/model` testing the `ZoneThresholds` class.
   - Migrated/updated `WorkoutMapperTest` and `HeartRateMapperTest` in the `app` module to test basic input mapping and the relocated metrics computations.
   - Updated `SessionLinkReconcilerTest` and `GoldenFixtureWalkForwardTest` to use the relocated `ZoneThresholds` logic.

---

## What was tested & Test results

- Run command: `./gradlew testDebugUnitTest`
- Result: **SUCCESSFUL** (All 782 unit tests compiled and passed cleanly).
- Verification Command: `./gradlew lintRelease`
- Result: **SUCCESSFUL** (Clean build, no lint errors).

---

## TDD Evidence

### RED Step
- **Command run:**
  `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.heartrate.ZoneThresholdsTest"`
- **Failing Output:**
  ```
  e: file:///Users/grl3lb/git/Readylytics/core/model/src/test/kotlin/app/readylytics/health/domain/heartrate/ZoneThresholdsTest.kt:17:26 Unresolved reference 'ZoneThresholds'.
  e: file:///Users/grl3lb/git/Readylytics/core/model/src/test/kotlin/app/readylytics/health/domain/heartrate/ZoneThresholdsTest.kt:18:23 Unresolved reference 'ZoneThresholds'.
  
  FAILURE: Build failed with an exception.
  ```
- **Why failure was expected:**
  The `ZoneThresholdsTest` was written referencing `ZoneThresholds.zoneThresholds()` and `ZoneThresholds.computeMetrics(...)` before the files or class implementation existed.

### GREEN Step
- **Command run:**
  `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.heartrate.ZoneThresholdsTest"`
- **Passing Output:**
  ```
  BUILD SUCCESSFUL in 1s
  15 actionable tasks: 2 executed, 13 up-to-date
  Configuration cache entry reused.
  ```

---

## Files changed

1. **Created**:
   - `core/model/src/main/kotlin/app/readylytics/health/domain/heartrate/ZoneThresholds.kt`
   - `core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/SleepDataMapper.kt`
   - `core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/WorkoutMapper.kt`
   - `core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/HeartRateMapper.kt`
   - `core/model/src/main/kotlin/app/readylytics/health/domain/sync/mappers/HrvMapper.kt`
   - `core/model/src/test/kotlin/app/readylytics/health/domain/heartrate/ZoneThresholdsTest.kt`
   - `core/model/src/test/kotlin/app/readylytics/health/domain/heartrate/WorkoutMapperIntensityTest.kt`

2. **Modified**:
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthIngestionCoordinator.kt`
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`
   - `core/database/src/main/kotlin/app/readylytics/health/data/local/SessionLinkReconcilerImpl.kt`
   - `core/model/src/test/kotlin/app/readylytics/health/data/healthconnect/WorkoutMapperTest.kt` (Emptied/relocated)
   - `app/src/test/kotlin/app/readylytics/health/data/healthconnect/HeartRateMapperTest.kt`
   - `app/src/test/kotlin/app/readylytics/health/data/healthconnect/WorkoutMapperTest.kt`
   - `app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/GoldenFixtureWalkForwardTest.kt`
   - `app/src/test/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconcilerTest.kt`

3. **Deleted**:
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/SleepDataMapper.kt`
   - `core/model/src/main/kotlin/app/readylytics/health/data/healthconnect/WorkoutMapper.kt`
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HeartRateMapper.kt`
   - `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HrvMapper.kt`

---

## Self-review findings & Concerns

- **Completeness**: All required files were successfully deleted, created, or modified according to the instructions. The ingestion pipeline has been completely decoupled from database entities and maps directly to inputs.
- **Quality**: The code maintains pure Kotlin patterns without Android/Room dependencies in the core domain models. 
- **Verification**: Built the codebase using `gradlew testDebugUnitTest` and `gradlew lintRelease` to ensure both runtime correctness and lint cleanliness.
