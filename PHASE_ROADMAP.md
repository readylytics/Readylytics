# Master Roadmap: Phases 3–9

**Status:** Phases 1–3 complete. Phase 4 complete. Phase 5.1 & 5.2 complete (PR#18 fixes merged). Phase 5.3 pending.

---

## PHASE 3: Large File Refactoring — UI Layer

**Duration:** 4–6 weeks | **Effort:** 40–60h | **Risk:** Medium

**Objective:** Split oversized UI files (>400 lines). All files ≤400 target.

### 3.1 WorkoutsScreen.kt (599 → 300 lines)

- Extract `WorkoutListSection.kt` (~180 lines)
- Extract `WorkoutStatsSection.kt` (~150 lines)
- Create `WorkoutDetailViewModel.kt`
- Refactor parallel `WorkoutDetailScreen.kt` (431 → 300 lines)
- Add 8 unit tests
- **Success:** All ≤400, validation in ViewModel

### 3.2 AboutScreen.kt (505 → 400 lines)

- Extract `AppInfoSection.kt` (~80 lines)
- Extract `ContributorsSection.kt` (~120 lines)
- Extract `LicenseSection.kt` (~100 lines)
- Extract `FeedbackSection.kt` (~80 lines)
- Add `AboutViewModel`
- Add 5 tests

### 3.3 WorkoutDetailScreen.kt (431 → 300 lines)

- Extract `WorkoutMetricsDisplay.kt` (~100 lines)
- Extract `WorkoutEditForm.kt` (~120 lines)
- Extract `TrimpBreakdownChart.kt` (~80 lines)
- Defensive validation in ViewModel
- Add 6 tests

**Risks:** Interdependent state flows | **Mitigation:** Extract in order, test after each

### 3.4 WorkoutDetailViewModel.kt Data Transformations (210 → 70 lines)

Extract heavy transformation logic from ViewModel:

- Create `mappers/ChartDataMapper.kt` (~60 lines) — converts HeartRatePoint samples → chart coordinates
- Create `mappers/RecoveryMetricsMapper.kt` (~50 lines) — computes HRR (1/2/3 min drops)
- Create `mappers/DailyPaiBreakdownMapper.kt` (~40 lines) — builds 7-day PAI breakdown
- ViewModel becomes pure orchestrator: load data → map → update state
- Add 8 tests (mapper unit tests + integration)

**Risks:** State flow interdependencies | **Mitigation:** Mappers pure functions, no side effects

**Result:** ✅ COMPLETE — ViewModel reduced to 70 lines (pure orchestrator). 3 mappers + 8 tests. All files ≤60 lines.

---

## PHASE 4: Domain Scoring Refactor

**Duration:** 3–5 weeks | **Effort:** 30–50h | **Risk:** High

**Objective:** Apply Strategy pattern to monolithic ScoringCalculator. 100% coverage per strategy.

### 4.1 ScoringCalculator.kt (567 → 100 lines)

- Create `strategies/SleepScoringStrategy.kt` (~120 lines)
- Create `strategies/PaiScoringStrategy.kt` (~100 lines)
- Create `strategies/LoadScoringStrategy.kt` (~100 lines)
- Create `strategies/ScoringStrategy.kt` (interface)
- Create `CompositeScoringCalculator.kt` (~100 lines, orchestrator)
- Behavior preservation (formulas unchanged)
- Add 15 tests (3+ per strategy)

### 4.2 ComputeSleepMetricsUseCase.kt (454 → 80 lines)

- Extract `sleep/SleepDurationCalculator.kt` (~80 lines)
- Extract `sleep/SleepArchitectureCalculator.kt` (~100 lines)
- Extract `sleep/SleepRestorationCalculator.kt` (~80 lines)
- Extract `sleep/SleepCompositeCalculator.kt` (~80 lines, orchestrator)
- Single responsibility per calculator
- Add 12 tests

### 4.3 Domain Validation Completeness

- Extract all domain bounds checks → validators
- Add 8–12 new validators (TRIMP bounds, HRV ranges, zones)
- Property-based generative tests
- 100% coverage goal

**Risks:** Core business logic, many dependents | **Mitigation:** Facade pattern, integration tests

---

## PHASE 5: Data Layer Refactoring

**Duration:** 2–4 weeks | **Effort:** 25–40h | **Risk:** Medium-High

**Objective:** Modularize monolithic SettingsRepository & HealthDatabase. Facade maintains backward compatibility.

### 5.1 SettingsRepository.kt (430 → 60 lines facade)

- Create `PhysiologyPreferences.kt` (~80 lines)
- Create `SleepPreferences.kt` (~60 lines)
- Create `ThresholdPreferences.kt` (~70 lines)
- Create `UIPreferences.kt` (~50 lines)
- Create `SyncPreferences.kt` (~50 lines)
- Create `CloudPreferences.kt` (~40 lines)
- Create `SettingsRepositoryFacade.kt` (~60 lines, unified interface)
- Single source of truth (DataStore) preserved
- Add 10 tests

### 5.2 HealthDatabase.kt (474 lines) — Code-only reorganization

- Create `schema/UserProfileDao.kt` (new grouping)
- Create `schema/HealthMetricsDao.kt` (consolidate sleep/HR/HRV)
- Create `schema/WorkoutTracking.kt` (workouts + TRIMP)
- Create `schema/Indices.kt` (all database indices)
- Create `HealthDatabaseManager.kt` (connection, migration)
- No schema changes, no data migration
- Add 5 tests

### 5.3 Query Optimization Audit

- Profile all DAOs (find N+1 patterns)
- Add missing indices (foreign keys, date ranges)
- Benchmark queries
- Add 8 integration tests
- Target: <100ms median query time

**Risks:** Core data layer | **Mitigation:** Facade pattern, backward-compatible interface

---

## PHASE 6: Local Backup & Restore (MVP Simplification)

**Duration:** 1–2 weeks | **Effort:** 12–18h | **Risk:** Low

**Objective:** File-based backup/restore. No cloud, auth, token lifecycle.

### 6.1 LocalBackupManager.kt (~80 lines)

- Serialize DB → JSON to app files directory
- Timestamp backups (YYYY-MM-DD_HHmmss format)
- Compression optional (defer to Phase 8)
- Delete old backups (>7 days)
- Add 4 tests

### 6.2 BackupScheduler.kt (~60 lines)

- WorkManager periodic task (daily, battery constraint)
- Trigger on-demand backup from UI
- No network, no auth needed
- Add 3 tests

### 6.3 RestoreFlow.kt (~60 lines)

- User selects backup file from directory
- Validate integrity (row counts, schema version check)
- Atomic restore or rollback on error
- User confirmation before restore
- Add 6 tests

**Risks:** File I/O errors, data loss on corrupt backup | **Mitigation:** Checksums, rollback support, user confirmation

---

## PHASE 7: Test Coverage Hardening

**Duration:** 2–3 weeks | **Effort:** 20–35h | **Risk:** Low | **Target:** 80%+ coverage

**Current State:** 41 unit tests, 4 Android tests | **Target:** 100+ unit tests, 20+ integration tests

### 7.1 Unit Tests (+50 tests)

- All domain validators: +10
- All ViewModels (event paths, error cases): +15
- All use cases (happy + error): +15
- Repository queries (correctness): +8
- Target: 100% line coverage for core domain

### 7.2 Integration Tests (+15 tests)

- SettingsRepository → DataStore → ViewModel: +4
- HealthSyncUseCase → DB → Dashboard: +4
- CloudBackup → DriveAPI → Restore: +4
- Scoring → DB → UI: +3

### 7.3 E2E Critical Paths (5 flows)

1. Launch → onboarding → baseline sync
2. Change HR zones → recalculate PAI
3. Enable backup → backup now → restore
4. Multi-device sync → dedup
5. Settings change → Health Connect resync

### 7.4 Performance Regression Tests

- Scoring: <500ms per 60-day window
- Dashboard render: <16ms (60fps)
- Settings update: <100ms
- Sync: <2s

**Success Criteria:** 80%+ coverage, 60+ unit tests, 15+ integration tests, 5 E2E flows

---

## PHASE 8: Performance Optimization

**Duration:** 2–4 weeks | **Effort:** 25–40h | **Risk:** Medium

### 8.1 Memory Profiling

- Profile low-end devices (API 35 minimum)
- Find leaks in Compose
- Optimize bitmap caching (charts)
- Reduce DB query overhead
- Target: <120MB heap

### 8.2 Render Performance

- Profile Compose recomposition counts
- Eliminate unnecessary state updates
- Add remember/memoization
- Profile Vico chart rendering
- Target: 60 FPS all interactions

### 8.3 Startup Performance

- Profile cold start (launch → usable)
- Lazy-load heavy modules
- Pre-compute baselines on background
- Cache frequently-used data
- Target: <2s startup

### 8.4 Battery Optimization

- Optimize sync frequency
- Use WorkManager constraints (charging, wifi)
- Reduce location polling
- Profile battery drain
- Target: <2% drain per day (idle)

### 8.5 Caching Strategy Audit

- Review all cache TTLs
- Add LRU caching where needed
- Memory-efficient data structures
- Query result caching
- Target: 80%+ cache hit rate

**Success Criteria:** 60 FPS, <2s startup, <120MB heap, 80%+ cache hit

---

## PHASE 9: Final Polish & Launch

**Duration:** 2 weeks | **Effort:** 15–25h | **Risk:** Low

### 9.1 Code Quality Checklist

- [ ] All files ≤400 lines (hard limit 800)
- [ ] 80%+ test coverage
- [ ] No validation in composables
- [ ] All ViewModels defensive
- [ ] CLAUDE.md patterns 100% enforced
- [ ] No actionable TODOs/FIXMEs
- [ ] Security audit passed
- [ ] Performance baselines met

### 9.2 Documentation

- KDoc for all public APIs
- Architecture guide (flows, patterns)
- Contributor onboarding
- Database schema docs
- Settings & preferences guide
- Scoring algorithm docs

### 9.3 Dependency Updates

- Jetpack Compose latest
- Room/DataStore updates
- Kotlin version bump
- Gradle plugin updates
- All tests passing

### 9.4 Release Prep

- Semantic version bump
- Changelog generation
- Build optimization flags
- ProGuard/R8 rules finalized
- Release notes drafted

**Success Criteria:** All code quality checklist items ✓, launch-ready, zero blockers

---

## SUMMARY TABLE

| Phase     | Focus              | Files     | Hours       | Risk     | Status       |
| --------- | ------------------ | --------- | ----------- | -------- | ------------ |
| 1         | Dashboard/Sync     | 6–8       | 40–50       | Med      | ✅ DONE      |
| 2         | Settings           | 15+       | 42–56       | Med      | ✅ DONE      |
| 3         | Large UI Refactor  | 10+       | 40–60       | Med-High | ✅ DONE      |
| 4         | Scoring Domain     | 8         | 30–50       | High     | ✅ DONE      |
| 5         | Data Layer         | 10+       | 25–40       | Med-High | ✅ DONE      |
| 6         | Cloud/Backup       | 6         | 20–30       | Med-High | 📋 PENDING   |
| 7         | Test Coverage      | +50 tests | 20–35       | Low      | 📋 PENDING   |
| 8         | Performance        | —         | 25–40       | Med      | 📋 PENDING   |
| 9         | Polish/Launch      | —         | 15–25       | Low      | 📋 PENDING   |
| **TOTAL** | **Complete Audit** | **65–80** | **257–386** | **Med**  | **6/9 IN WORK** |

---

**Total Remaining Effort:** 257–386 hours (~6–10 weeks at 40h/week)

**Key Dependencies:**

- Phase 3 → Phase 4 (tests enable domain refactoring)
- Phase 4 → Phase 7 (scoring strategies enable comprehensive tests)
- Phase 5 → Phase 7 (data layer clarity enables integration tests)
- Phase 7 → Phase 8 (coverage targets enable performance regression detection)
- Phases 3–8 → Phase 9 (all must pass quality checklist)
