# MyHealthStatus Phase 2-4 Comprehensive Remediation Plan

**Project:** MyHealthStatus (Offline-First Sports Health Analytics)  
**Created:** 2026-05-23  
**Phase 1 Status:** Complete (12/12 stories, 683 tests, 25%+ coverage, lint clean, build passing)  
**Scope:** Phases 2-4 (Weeks 9-44, Advanced Architecture & Scaling)

---

## Executive Summary

Phase 1 established quality gates (lint, tests, migrations, coverage).  
Phases 2-4 scale architecture from monolithic to modular, coverage 25% → 70%, build time < 10s.

**Critical Success Factors:**
- BaseViewModel pattern unifies 22 ViewModels (Phase 2, T3.3)
- Module extraction (domain, data, design, feature) breaks monolith (Phase 3)
- APM + benchmarking detect regressions pre-commit (Phase 4)

---

## PHASE 2: Domain Architecture & Error Handling (Weeks 9-20, 8 weeks, 69 SP)

### Rationale
Phase 1 left ViewModels with repetitive error handling, inconsistent dispatcher usage, untested domain layers.  
Phase 2 extracts domain logic, establishes error patterns, unifies ViewModel state management.

### Success Gate
- All 22 ViewModels inherit BaseViewModel
- 5 domain services with 80% unit test coverage
- Dispatcher consistency: testDispatchers in tests, Default+IO in prod
- 40%+ instruction coverage (vs 25% Phase 1)
- Build incremental ≤ 10s
- Zero ErrorHandlingException in e2e tests

---

### Sprint 2A (Weeks 9-10, 17 SP)

#### T3.1: Create Domain Service Layer (5 services, 250+ tests)
**Effort:** 5 SP | **Priority:** CRITICAL

Extract: HealthMetricsService, BmiService, PreferenceService, ValidationService, DateRangeService.

**AC:**
1. Five service files in `domain/service/` (pure Kotlin)
2. 50+ unit tests per service (250+ total)
3. 100% function coverage
4. Services injected into ViewModels (not singletons)
5. Verify: `./gradlew testDebugUnitTest` passes, `lsp_diagnostics` → 0 errors

**Files:** 5 service + 5 test files

---

#### T3.2: Establish Error Handling Pattern (Result<T>)
**Effort:** 4 SP | **Priority:** CRITICAL

**AC:**
1. `domain/model/Result.kt` with Success/Failure cases
2. 5 services return Result<T>
3. 22 ViewModels handle Result.Failure → setError()
4. 20+ Result handling tests (map, flatMap, recovery)
5. UI displays errors without raw exceptions

**Files:** Result.kt + updated services + ViewModels

---

#### T3.3: Create BaseViewModel + Unify State Management
**Effort:** 8 SP | **Priority:** CRITICAL

**AC:**
1. `BaseViewModel<UiState>` with loading, error, scope lifecycle
2. All 22 ViewModels inherit BaseViewModel
3. Boilerplate reduced 40% (avg 150 → 90 lines/ViewModel)
4. onCleared() cancels all scope work automatically
5. 100% error states use setError()

**Files:** BaseViewModel.kt + 22 ViewModel changes + lifecycle tests

---

### Sprint 2B (Weeks 11-12, 22 SP)

#### T3.4: ViewModel Refactor 1-3 (Dashboard, Sleep, Weight)
**Effort:** 6 SP | **Priority:** HIGH

- DashboardViewModel: 180 → 100 lines
- SleepDetailViewModel: 160 → 90 lines
- WeightDetailViewModel: 150 → 85 lines
- All 51+ existing tests pass
- 5+ new integration tests per ViewModel

---

#### T3.5: ViewModel Refactor 4-5 + Dispatcher Standardization
**Effort:** 8 SP | **Priority:** HIGH

- BodyFatDetailViewModel, ReadinessViewModel refactored (like T3.4)
- All 22 ViewModels use consistent dispatchers:
  - IO ops: Dispatchers.IO
  - UI: Dispatchers.Main
  - CPU: Dispatchers.Default
- Tests inject testDispatchers globally
- `./gradlew testDebugUnitTest --parallel` → 0 timeouts

---

### Sprint 2C (Weeks 13-16, 22 SP)

#### T3.6: Logging + PII Redaction
**Effort:** 5 SP | **Priority:** HIGH

**AC:**
1. `PiiRedactionFilter.kt` with regex patterns (email, numbers, dates)
2. Timber integrated with filter in Application onCreate()
3. 22 ViewModels + 5 services use Timber.i/e (replace printStackTrace)
4. 20+ unit tests verify redaction (no email, birthdate, biometrics visible)
5. E2E: logs clean in prod build

---

#### T3.7: Domain Service Tests Expanded + Migrations
**Effort:** 8 SP | **Priority:** MEDIUM

**AC:**
1. Service tests: 250+ → 300+ cases (edge cases, baselines)
2. Migration tests: v22 → v23 (no data loss, backward compatible)
3. Stress tests: 1000 records, verify baseline calculation < 100ms
4. All 300+ tests pass; 100% function coverage

---

#### T3.8: Instrumented Tests for Repository + VM Interaction
**Effort:** 9 SP | **Priority:** HIGH

**AC:**
1. 40+ instrumented tests:
   - Dashboard (10), Sleep (8), Weight (8), BodyFat (8), Readiness (6)
2. Room.inMemoryDatabaseBuilder + fake HealthConnect
3. Happy path + error paths (missing perms, no data, timeout)
4. All 40+ pass; `connectedDebugAndroidTest` green

---

### Phase 2 Gate
- 600+ tests pass ✓
- 40%+ coverage ✓
- Build ≤ 10s ✓
- Lint 0 errors ✓

---

## PHASE 3: Module Extraction (Weeks 21-32, 12 weeks, 76 SP)

### Rationale
Phase 2 unified architecture. Phase 3 breaks monolith into 6 modules (domain, data, design, dashboard, settings, onboarding).

### Success Gate
- 6 feature modules created
- 0 circular dependencies
- `./gradlew build --parallel` ≤ 7s
- 60%+ coverage
- Each module independently testable

---

### Module Structure
```
:app (main)
├─ :core:domain (services, validation, interfaces)
├─ :core:data (repositories, Room, HealthConnect)
├─ :core:design (Material 3, reusable components)
├─ :feature:dashboard (DashboardScreen, ViewModel, use cases)
├─ :feature:settings (SettingsScreen, ViewModels)
└─ :feature:onboarding (OnboardingScreen, flow)
```

### T4.1: Extract :core:domain
**Effort:** 12 SP | **Priority:** CRITICAL

- Move: 5 services, models, validation, exceptions
- No Android deps (pure Kotlin + Coroutines)
- 300+ domain tests pass without modification
- Zero circular dependencies

---

### T4.2: Extract :core:data
**Effort:** 14 SP | **Priority:** CRITICAL

- Move: DAOs, entities, repositories, migrations, HealthConnect, preferences
- Depends on :core:domain only
- 70+ integration tests pass
- Room migrations verified (all versions forward-compatible)

---

### T4.3: Extract :core:design
**Effort:** 10 SP | **Priority:** HIGH

- Move: theme, components (M3ScoreDial, TrendCard, etc.), icons
- Compose-only; depends on :core:domain + Compose framework
- All Compose previews render without error
- Lint green

---

### T4.4-4.5: Extract :feature:dashboard + :feature:settings
**Effort:** 24 SP | **Priority:** HIGH

- Dashboard: DashboardScreen, ViewModel, use cases (50+ tests)
- Settings: SettingsScreen, ViewModels (40+ tests)
- Both depend on :core modules + :app (nav only)
- Zero circular dependencies

---

### T4.6: Extract :feature:onboarding
**Effort:** 8 SP | **Priority:** MEDIUM

- Move: OnboardingScreen, ViewModel
- Cold start testable in isolation
- 10+ onboarding tests pass

---

### Phase 3 Gate
- 6 modules created ✓
- 0 circular dependencies ✓
- `./gradlew build --parallel` ≤ 7s ✓
- 60%+ coverage ✓
- All 700+ tests pass ✓

---

## PHASE 4: Production Readiness (Weeks 33+, ongoing)

### Success Gate
- 70%+ coverage
- < 100ms cold start
- < 50ms warm start
- 0 ANR crashes
- Feature flags active
- Crashlytics integrated
- Build ≤ 7s

### Key Stories

#### T5.1-5.7: Performance Monitoring (APM) + Benchmarking (20 SP)
- Firebase Performance Monitoring
- Custom traces (sleep score < 50ms, baseline < 100ms)
- Jetpack Microbenchmark (5+ tests)
- Baseline metrics (cold start, memory, battery)

#### T5.8-5.11: Feature Flags + Experiments (8 SP)
- Firebase Remote Config
- 3 features gated: sleep algorithm, HealthConnect sync, dashboard customization
- Fallback behavior tested

#### T5.12-5.15: Crash Reporting (8 SP)
- Crashlytics configured
- Custom error tracking (Result.Failure → Crashlytics)
- Breadcrumbs + logs for repro
- Error dashboard (top 10 crashes, trends)

#### T5.16+: Continuous Monitoring (5+ SP)
- Weekly: crash trends
- Monthly: performance benchmarks
- Quarterly: engagement analysis

---

## Resource Allocation

| Phase | Weeks | SP | Team |
|-------|-------|----|----|
| Phase 1 | 1-8 | 30 | ✓ Complete |
| **Phase 2** | **9-20** | **69** | Opus + Sonnet |
| **Phase 3** | **21-32** | **76** | 2 Opus + 2 Sonnet |
| **Phase 4** | **33+** | **20+** | Sonnet + Haiku |
| **Total** | **33+ weeks** | **195+ SP** | 4-6 FTE |

---

## Key Patterns & Lessons

1. **Service Layer:** Pure Kotlin services decouple domain. Enables reuse, testing, multi-platform.
2. **Result<T> Pattern:** Type-safe error handling. Composable with flatMap; no try/catch boilerplate.
3. **BaseViewModel:** Centralizes lifecycle, dispatcher, errors. 40% boilerplate reduction.
4. **Module Extraction:** Strict dependency order (domain → data → design → features). Prevents cycles, enables parallel builds.
5. **PII Redaction:** Timber filter at single point. Prevents accidental leaks.
6. **Test Pyramid:** Unit (fast), Integration (Room/HealthConnect), E2E (UI flow). All required.

---

**Status:** Ready for Phase 2 Kickoff  
**Date:** 2026-05-23
