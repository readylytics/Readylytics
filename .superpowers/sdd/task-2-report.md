# Task 2: Weight/BloodPressure/BodyFat domain models + data-layer mappers (ARCH-005)

## Implementation

- Added pure Kotlin `WeightRecord`, `BloodPressureRecord`, and `BodyFatRecord` domain models using `Instant` for time.
- Added database-layer bidirectional mappers for all three Room entities and domain models.
- Changed the three repository interfaces and implementations to expose domain records while keeping Room entities behind `core/database`.
- Updated the three vitals ViewModels and their tests for the domain records' `time: Instant` field.
- Aliased the three Health Connect record imports in `HealthChangeSynchronizerImpl` to disambiguate them from the new same-named domain records.

## TDD evidence

- RED: `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.data.mapper.VitalsRecordMappersTest"` failed as expected with unresolved domain record and mapper references before implementation.
- GREEN: the same focused command passed all three mapper round-trip tests after implementation.

## Verification evidence

- `./gradlew :feature:vitals:testDebugUnitTest` passed.
- `./gradlew ktlintFormat testDebugUnitTest` passed the whole repository (591 Gradle tasks).
- `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.CleanArchTest"` passed.
- `git diff --check` passed during final audit.
- `lintRelease` was started by the original implementer but interrupted; the task brief did not require it.

## Files changed

Created:

- `core/model/src/main/kotlin/app/readylytics/health/domain/model/VitalsRecords.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/mapper/VitalsRecordMappers.kt`
- `core/database/src/test/kotlin/app/readylytics/health/data/mapper/VitalsRecordMappersTest.kt`

Required interface/implementation changes:

- `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WeightRepository.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/repository/BloodPressureRepository.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/repository/BodyFatRepository.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/WeightRepositoryImpl.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/BloodPressureRepositoryImpl.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/BodyFatRepositoryImpl.kt`

Compiler-forced call-site changes:

- `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/weight/WeightDetailViewModel.kt`
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureDetailViewModel.kt`
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bodyfat/BodyFatDetailViewModel.kt`
- Corresponding `WeightDetailViewModelTest.kt`, `BloodPressureDetailViewModelTest.kt`, and `BodyFatDetailViewModelTest.kt` files.

## Extra-file audit

- `HealthChangeSynchronizerImpl`: only aliases Health Connect's `WeightRecord`, `BodyFatRecord`, and `BloodPressureRecord` and applies those aliases at type checks/class tokens. This is required by the new same-named domain types and is behavior-equivalent.
- Vitals ViewModels: only replace entity `timestampMs` reads with domain `time`, converting to epoch milliseconds where existing UI models still require `Long`. IDs, measurements, sorting, grouping, date conversion, prior-record lookup, and display calculations remain field-for-field equivalent.
- Vitals tests: import domain records and use small test-only compatibility factory functions preserving the former entity constructor arguments exactly, so existing test intent and data remain unchanged.

## Self-review and concerns

- Domain models contain no Android, Room, or data-layer dependencies.
- Mapper conversions preserve every entity field in both directions, including nullable `deviceName`.
- Repository implementations map list, flow, latest, date-latest, and previous results consistently; blood pressure correctly has no `getPrevious` contract.
- No ingestion behavior, Room schema, scoring formula, or user-facing text changed, so no documentation synchronization update is triggered.
- No correctness concerns found. `lintRelease` is the only uncompleted broader check and was explicitly not required for Task 2.

## Codegraph indexing

- `codegraph index` completed successfully after the three new files were created: 868 files indexed.
