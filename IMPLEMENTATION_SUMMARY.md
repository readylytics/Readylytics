# Circadian Thresholds Implementation Summary

## What Has Been Implemented (Phase 1-8 Complete)

### ✅ Phase 1: Pure Domain Objects
- `Phase.kt` - Enum for tenure-based phases (CALIBRATION, PROVISIONAL, MATURE)
- `RestorationWeights.kt` - HRV/RHR restoration weights with normalized percentages
- `SleepArchitectureTargets.kt` - Sealed class hierarchy for age-banded sleep targets with gender adjustment
- `EmergencyFlagThresholds.kt` - Thresholds for overreaching and illness detection
- `CircadianConsistencyConfig.kt` - Circadian threshold and evaluation window config
- `AuditTrail.kt` - Config hash, phase name, and applied date for audit trail

### ✅ Phase 2: Factories
- `PhaseCalculator.kt` - Maps days-since-install to phase (DRY, single responsibility)
- `SleepArchitectureTargetFactory.kt` - Age/gender → sleep targets mapping (centralized logic)
- `AuditTrailFactory.kt` - Creates audit trail with phase calculation
- `ScoringConfigFactory.kt` - Creates ScoringConfig from UserPreferences (dependency injection ready)

### ✅ Phase 3: ScoringConfig Composition
- `ScoringConfig.kt` - Lightweight composition of all config components

### ✅ Phase 4: Strategy Pattern
- `CircadianConsistencyStrategy.kt` - Interface for extensible circadian logic
- `RegularUserCircadianStrategy.kt` - 14-day rolling anchor for regular users
- `ShiftWorkerCircadianStrategy.kt` - Disabled standard threshold for shift workers
- `CircadianStrategyFactory.kt` - Returns correct strategy by profile

### ✅ Phase 5: ScoringCalculator Updates
- Updated `ScoringCalculator.kt` interface with optional config parameters
- Added optional `sleepTargets` to `computeArchSubScore()` and `computeSleepScore()`
- Added optional `restorationWeights` to `computeRestorationSubScore()`
- Added optional `emergencyFlags` to `computeRecoveryFlags()`
- All updates maintain backward compatibility with default fallback values

### ✅ Phase 6: Validators
- `CircadianThresholdValidator.kt` - Validates threshold range (0-90 minutes)
- `ScoringConfigValidator.kt` - Validates config invariants

### ✅ Phase 7: Preferences Interface (Interface Segregation)
- `CircadianThresholdPreferences.kt` - Narrow interface for circadian threshold concerns
- `DataStoreCircadianThresholdPreferences.kt` - DataStore adapter implementation

### ✅ Phase 8: ComputeSleepMetricsUseCase Integration
- Injected `ScoringConfigFactory` dependency
- Create `ScoringConfig` at calculation start with profile-based defaults
- Pass config components to all calculator methods
- Log audit trail (config hash + phase name)
- Embed audit fields in `ReadinessResult.Diagnostics`
- Updated `ReadinessResult.Diagnostics` with `configHashCode` and `phaseName` fields

### ✅ Phase 9: DI Module
- Updated `ScoringModule.kt` with `CircadianThresholdPreferences` binding
- Converted `ScoringConfigFactory` to injectable @Singleton class

## Profile-Based Defaults Implemented

### Circadian Consistency Thresholds
- **Athlete:** 20 minutes
- **Active:** 30 minutes
- **General:** 30 minutes
- **Sedentary:** 45 minutes
- **Shift Worker:** Int.MAX_VALUE (disabled, uses within-week logic)

### Restoration Weights (HRV/RHR)
- **Athlete:** 70% HRV / 30% RHR
- **Active:** 60% HRV / 40% RHR
- **General:** 50% HRV / 50% RHR
- **Sedentary:** 50% HRV / 50% RHR
- **Shift Worker:** 50% HRV / 50% RHR

### Sleep Architecture Targets (Age-Banded)
- **18-29:** Deep 20%, REM 22%
- **30-49:** Deep 18%, REM 21%
- **50-59:** Deep 16%, REM 20% (+ 2% additional deep for females)
- **60+:** Deep 12%, REM 18%

### Emergency Flag Thresholds
- **OVERREACHING:** Z_HRV > +1.5 AND Z_RHR < -2.0 for 2+ consecutive nights → Readiness capped at 70
- **ILLNESS:** Z_HRV < -1.5 AND RHR elevated (>baseline + 5bpm) for 2+ consecutive nights → Readiness capped at 50

## SOLID & DRY Principles Applied

✅ **Single Responsibility:**
- Each class has one reason to change
- Factories handle creation, calculators handle math, strategies handle behavior

✅ **Open/Closed:**
- CircadianConsistencyStrategy extensible without modifying existing code
- SleepArchitectureTargets sealed class accepts new age ranges

✅ **Liskov Substitution:**
- All strategy implementations properly substitute parent interface

✅ **Interface Segregation:**
- `CircadianThresholdPreferences` contains only threshold concerns
- Clients depend on narrow interface, not full `UserPreferences`

✅ **Dependency Inversion:**
- All components depend on abstractions, not concrete implementations
- Factories injected via Hilt

✅ **DRY (No Duplication):**
- Phase calculation: `PhaseCalculator` (used by audit + factory)
- Age-banding: `SleepArchitectureTargetFactory` (one place)
- Profile→weight mapping: `ScoringConfigFactory` (one place)
- Circadian thresholds: Strategy objects (one place per profile type)

## Outstanding TODOs

### High Priority
1. **Install Date Tracking:**
   - Currently hardcoded to `LocalDate.ofEpochDay(0)` in `ComputeSleepMetricsUseCase`
   - Need to add `installDate` field to `UserPreferences` and track on first run
   - Required for proper phase calculation (CALIBRATION < 7 days, PROVISIONAL < 42 days, MATURE ≥ 42 days)

2. **Circadian Threshold Override Field:**
   - Currently bypassed (circadianOverride = null in ScoringConfigFactory call)
   - Need to add `circadianThresholdOverride: Int?` to `UserPreferences`
   - Add DataStore key to `UserPreferencesRepository`
   - Update `DataStoreCircadianThresholdPreferences` to read from preferences

3. **Circadian Consistency UI:**
   - Need settings screen component to allow user override (0-90 minute slider)
   - Show profile default in settings UI

### Medium Priority
4. **Shift Worker Within-Week Consistency Logic:**
   - Skeleton in place (`ShiftWorkerCircadianStrategy`)
   - Needs implementation: group sessions by day-of-week, compare across 4 weeks
   - Currently returns Int.MAX_VALUE (disables standard checking)

5. **Migration for New Database Columns:**
   - `ReadinessResult.Diagnostics` now has `configHashCode` and `phaseName`
   - Room will embed these as `diag_configHashCode` and `diag_phaseName` in `DailySummaryEntity`
   - May require database migration if schema is locked

### Low Priority
6. **Test Coverage:**
   - Unit tests for all factories and validators
   - Integration tests for ScoringConfigFactory with real preferences
   - Snapshot tests for config composition

7. **Documentation:**
   - Add code comments for non-obvious formulas
   - Update README with new profile system

## File Structure

```
domain/scoring/
├── ScoringConfig.kt (composition)
├── ScoringConfigFactory.kt (building config)
├── ScoringCalculator.kt (updated interface)
├── ScoringCalculatorImpl.kt (updated impl)
├── ComputeSleepMetricsUseCase.kt (updated orchestration)
├── components/ (new focused domain objects)
│   ├── Phase.kt
│   ├── RestorationWeights.kt
│   ├── SleepArchitectureTargets.kt
│   ├── EmergencyFlagThresholds.kt
│   ├── CircadianConsistencyConfig.kt
│   ├── AuditTrail.kt
│   ├── PhaseCalculator.kt
│   ├── SleepArchitectureTargetFactory.kt
│   └── AuditTrailFactory.kt

domain/circadian/
├── CircadianConsistencyStrategy.kt
├── RegularUserCircadianStrategy.kt
├── ShiftWorkerCircadianStrategy.kt
└── CircadianStrategyFactory.kt

domain/common/
├── CircadianThresholdValidator.kt
└── ScoringConfigValidator.kt

data/preferences/
├── CircadianThresholdPreferences.kt (interface)
└── DataStoreCircadianThresholdPreferences.kt (adapter)

di/
└── ScoringModule.kt (updated with new bindings)
```

## Next Steps for Completion

1. Add install date tracking to UserPreferences
2. Add circadian threshold override field to UserPreferences
3. Update UserPreferencesRepository with new keys and read/write logic
4. Implement shift worker within-week consistency logic
5. Create circadian threshold settings UI component
6. Add database migration for new columns
7. Write unit and integration tests
8. Build and test on device/emulator
