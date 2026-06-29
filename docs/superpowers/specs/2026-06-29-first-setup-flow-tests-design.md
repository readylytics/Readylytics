# First Setup Flow Tests Design

**Date:** 2026-06-29  
**Status:** draft for review  
**Scope:** design for new first-setup flow coverage spanning onboarding persistence, Health Connect permission handoff, settings re-read, and separate dummy-ingestion flow tests.

## Goal

Add stable first-setup flow coverage without collapsing into one giant brittle test.

New coverage should prove:

1. onboarding saves birthday, height, and related profile data correctly
2. Health Connect permission handoff logic behaves correctly for granted and denied paths
3. settings view models read persisted onboarding values correctly after setup
4. dummy Health Connect ingestion can be tested in a separate flow using deterministic seeded data

New coverage should **not**:

1. replace all existing tests up front
2. depend on Compose widget rendering for core persistence assertions
3. mix onboarding persistence, permission routing, ingestion, and scoring into one mega test
4. duplicate benchmark or connected-test gate work from `docs/superpowers/plans/2026-06-19-connected-test-and-benchmark-gates.md`

## Why Separate Flows

Recent modularization churn means broad deletion-first testing would hide regressions instead of isolating them. Small flow tests with shared harness give better failure signals:

1. profile persistence flow fails when onboarding save logic or shared state wiring breaks
2. permission flow fails when Health Connect handoff or state transitions break
3. ingestion flow fails when sync trigger or seeded data plumbing breaks
4. settings reopen flow fails when downstream read models drift from persisted state

## Constraints From Existing Test Docs

This design extends local strategy from:

1. `internal-docs/TEST_STRATEGY.md`
2. `internal-docs/TEST_IMPLEMENTATION_PLAN.md`

Important constraints carried into this design:

1. HC SDK runtime-backed queries belong in `androidTest`, not plain JVM unit tests
2. pure orchestration, persistence, and fake-port flow tests should stay in normal unit-test source sets whenever Android runtime not needed
3. two test styles already accepted in repo:
   - `runTest {}` for suspend and orchestration tests
   - Compose rule based tests for isolated UI behavior
4. Hilt is not universal baseline for every new test; add Hilt-backed instrumentation only when real injected graph is required
5. test sizes matter:
   - `@SmallTest` for fast pure logic or harness tests
   - `@MediumTest` for broader flow tests or light seeded integration
   - `@LargeTest` only for long seeded scenarios such as 42-day integration
6. seeded data must stay deterministic and math-derived, not magic-number driven
7. idempotency and no-duplication checks are explicit strategy goals and should shape ingestion-flow assertions

## Recommended Architecture

Use **port-level integration-style unit tests** backed by reusable in-memory harness.

Why this path:

1. real view models can run without Android UI shell
2. fake ports keep tests deterministic and fast
3. same harness can power multiple flows
4. failures stay close to behavioral contract under test

Do **not** start with Robolectric or full navigation flow. Add that later only if text-only flow coverage misses real UI wiring regressions.

## Test Layer Split

### 1. JVM Flow Tests

Place new first-setup persistence and permission-handoff tests in unit-test source sets where possible.

Use real production classes:

1. `OnboardingViewModel`
2. relevant settings view models such as `PhysiologySettingsViewModel`, `UISettingsViewModel`, or `SettingsViewModel`-adjacent readers as needed

Use fake or in-memory ports for:

1. `PhysiologySettings`
2. `DisplaySettings`
3. `DeviceSettings`
4. `UserPreferencesReader`
5. sync trigger / refresh adapter interfaces where needed

These tests should verify orchestration and state propagation, not Android framework behavior.

### 2. Android Instrumentation Flow Tests

Only dummy-ingestion flows that need real Health Connect provider behavior, seeded records, or Android runtime-backed HC classes should move to `androidTest`.

This follows `TEST_STRATEGY.md` guidance:

1. real HC provider on Android 16 / API 36 emulator
2. deterministic seeder usage
3. math-derived expected values
4. explicit separation between unit and instrumented layers

If ingestion flow can be proven against fake ingestion ports alone, keep it in JVM tests first. Add HC-backed `androidTest` only for contracts that cannot be trusted with fakes.

## Shared Harness Design

Create reusable in-memory scenario harness.

Harness responsibilities:

1. hold mutable in-memory `UserPreferences` state
2. expose read side through `UserPreferencesReader.userPreferences`
3. implement write ports used by onboarding/settings view models
4. model Health Connect permission state: `unknown`, `granted`, `denied`
5. capture sync or ingestion trigger requests
6. provide deterministic coroutine dispatcher and test scope

Harness should be small, explicit, and purpose-built. Do not reimplement app graph or add fake behavior unrelated to setup flows.

### Harness Components

Recommended pieces:

1. `InMemoryUserPreferencesStore`
2. `FakePhysiologySettings`
3. `FakeDisplaySettings`
4. `FakeDeviceSettings`
5. `FakeHealthConnectPermissionGateway` or equivalent fake around current permission-facing port
6. optional `FakeHealthDataRefresh` / `FakeHistoricalResyncController` / fake sync adapter for ingestion handoff

### Harness Rules

1. one shared state source per test
2. no hidden default mutations
3. every fake records calls for assertion
4. every fake can be initialized with explicit starting state
5. no production logic duplication except minimal state bookkeeping

## Proposed Flow Tests

### Flow 1: `FirstSetupProfilePersistenceFlowTest`

Purpose: verify onboarding save persists profile values and settings-side consumers read same values later.

Assertions:

1. birthday stored correctly
2. height stored correctly
3. gender / physiology profile / unit system stored when included in scenario
4. downstream settings VM state reflects persisted values after fresh subscription

Non-goals:

1. Compose widget assertions
2. Health Connect record ingestion
3. full score computation

Expected classification: `@SmallTest` or ordinary fast JVM test.

### Flow 2: `FirstSetupHealthConnectPermissionFlowTest`

Purpose: verify onboarding permission handoff logic for granted and denied outcomes.

Assertions:

1. granted path records correct permission state or next-step action
2. denied path records correct fallback or recovery state
3. expected port calls happen exactly once
4. no unrelated persistence drift

Non-goals:

1. Android permission dialog mechanics
2. OS-level permission APIs

Expected classification: `@SmallTest` or `@MediumTest` depending on breadth.

### Flow 3: `SettingsReopenAfterSetupFlowTest`

Purpose: prove persisted setup values are visible when settings logic starts later from saved state alone.

Assertions:

1. no onboarding logic rerun required
2. birthday displayed via settings state correctly
3. height displayed via settings state correctly
4. defaults do not overwrite saved values

Expected classification: `@SmallTest`.

### Flow 4: `FirstSetupDummyIngestionFlowTest`

Purpose: verify separate post-setup ingestion path using deterministic dummy Health Connect dataset.

Two allowed variants:

1. JVM fake-ingestion version for orchestration-only contract
2. `androidTest` seeded-HC version when real Health Connect interaction must be proven

Assertions should stay narrow:

1. setup hands off correctly to ingestion trigger
2. deterministic dummy dataset writes expected persisted records or summary-ready state
3. repeat run stays idempotent where contract requires it
4. no duplicate records after repeated seed / trigger sequence

Do **not** assert full scoring formulas here unless test explicitly targets scoring engine contract.

## Dummy Data Strategy

Do not couple setup-flow tests to benchmark fixtures or giant connected-test datasets.

Use minimal deterministic dataset shaped by `TEST_STRATEGY.md` principles:

1. no magic numbers without derivation or fixture explanation
2. repeatable across runs
3. small enough for targeted failures

Recommended minimal dataset for setup-adjacent ingestion:

1. one sleep session
2. one workout session
3. heart-rate samples tied to relevant session window
4. optional HRV sample only if flow contract requires readiness/baseline field population

If real HC-backed test needed, seed via existing deterministic seeder path instead of inventing ad-hoc randomized records.

## Source-Set Placement

### Unit-test placement

Best home for new setup-flow tests:

1. feature-local test package near onboarding and settings tests, if harness remains local
2. shared `testFixtures` style support package only if multiple modules truly reuse same harness

Default recommendation: start local, extract later if duplication appears.

If feature-module test wiring blocks direct reuse of one harness across modules, a tiny module-local harness that mirrors the same in-memory preference model is acceptable. Prefer documenting that constraint over forcing new cross-module test dependencies for this work.

### androidTest placement

If real seeded Health Connect instrumentation becomes necessary, align with current strategy layout:

1. `app/src/androidTest/.../integration/` for seeded ingestion state tests
2. `app/src/androidTest/.../ui/` only for Compose UI visibility or rendered-state assertions

## Assertion Rules

Each test should assert one behavioral contract.

Good assertions:

1. persisted raw state
2. downstream read-model state
3. port call counts / parameters
4. idempotent repeat behavior

Avoid:

1. asserting every settings field in one test
2. mixing persistence and scoring and UI visibility in same case
3. asserting implementation details that can change without behavior change

## Migration Strategy

Do not delete current tests at start.

Order:

1. build shared in-memory harness
2. add profile persistence flow
3. add permission flow
4. add settings reopen flow
5. add dummy-ingestion flow
6. inspect existing onboarding/settings tests for overlap
7. remove only tests proven broken, redundant, or superseded

This preserves existing guardrails while new flow coverage settles.

## Risks

### Risk 1: Hidden Android dependency in “unit” flow

Mitigation:

1. keep first pass on pure view model + port boundaries
2. if framework dependency leaks in, move only affected test to Robolectric or `androidTest`, not whole suite

### Risk 2: Dummy ingestion fixture drifts from seeded strategy

Mitigation:

1. reuse deterministic seeder concepts and value derivation
2. document fixture meaning inline
3. avoid benchmark-specific payload coupling

### Risk 3: Harness becomes fake app graph

Mitigation:

1. keep only ports touched by setup flows
2. no unnecessary dependency emulation
3. extract common helpers only after second or third consumer appears

### Risk 4: Permission flow test duplicates UI tests

Mitigation:

1. keep permission flow at orchestration boundary
2. reserve Compose/UI tests for visible messaging or route-state assertions only

## Verification Plan

Before implementation complete, expect verification at two levels.

### Fast lane

1. new JVM flow tests
2. existing related onboarding/settings unit tests

### HC-backed lane if needed

1. Android 16 / API 36 emulator
2. deterministic seeded `androidTest` runs for ingestion-specific cases
3. Hilt wiring only if real injected graph required

All implementation should still honor repo rule:

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest`
3. `./gradlew lint`

Additional `connectedDebugAndroidTest` runs required only if this work adds or changes instrumented seeded ingestion coverage.

## Recommendation

Implement first-setup flow coverage with **shared in-memory harness + multiple small port-level integration tests**. Keep seeded Health Connect instrumentation as separate second lane for ingestion contracts that truly need Android runtime.

This gives:

1. fast feedback
2. cleaner failures
3. lower rewrite risk
4. compatibility with existing test strategy and implementation plan
