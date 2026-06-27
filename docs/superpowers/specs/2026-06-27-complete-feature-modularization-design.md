# Complete Feature Modularization Design

**Date:** 2026-06-27
**Status:** Approved design; implementation planning pending
**Source roadmap:** `docs/superpowers/plans/2026-06-26-phase-4-long-term-hardening.md`, Task 7
**Source audit:** `.omc/plans/audit-phase-2-remediation.md`

## Goal

Replace Task 7's dashboard-only pilot with complete feature modularization. Move every user-facing flow into a cohesive `:feature:*` module, leave `:app` as the Android assembly and composition shell, and enforce dependency boundaries through Gradle and automated architecture tests.

This work changes build topology and code ownership only. It must not change health-data ingestion, Room single-source-of-truth behavior, scoring formulas, user-visible behavior, navigation semantics, backup/restore behavior, permission recovery, or sync/recalculation contracts.

## Current State

Current Gradle topology contains `:app`, `:core:model`, `:core:scoring`, `:core:database`, and `:core:healthconnect`. Convention build logic already exists. No `:feature:*` module exists.

Presentation code remains concentrated in `:app`: 161 Kotlin files across 21 `ui` packages. Shared theme, common presentation types, and reusable components are app-local. Feature ViewModels also depend on app-local infrastructure classes, including the concrete DataStore-backed settings repository. Top-level navigation directly imports every screen route.

These conditions make a dashboard-only move unsafe: a feature module cannot depend on app-local UI or infrastructure without creating a reverse edge or dependency cycle.

## Accepted Decisions

- Modularize all user-facing flows, not a pilot subset.
- Use eight cohesive feature modules instead of one module per screen.
- Feature modules own routes, screens, UI models, ViewModels, previews, feature resources, and presentation tests.
- Keep infrastructure implementations, workers, Android entry points, and DI composition in `:app` unless later infrastructure modularization is separately approved.
- Move required pure contracts and reusable domain types into suitable `:core:*` modules.
- Rename moved packages to feature-specific namespaces such as `app.readylytics.health.feature.dashboard` during each feature migration.
- Use a foundations-first strangler sequence. Keep `:app` assembling after every PR.
- Write a standalone implementation plan and replace original Task 7 body with a short pointer and sequencing gate.

## Target Module Graph

```text
:app
 ├─> :feature:dashboard
 ├─> :feature:sleep
 ├─> :feature:workouts
 ├─> :feature:vitals
 ├─> :feature:insights
 ├─> :feature:settings
 ├─> :feature:onboarding
 └─> :feature:about

:feature:* ─> :core:ui ─> :core:designsystem
           ├> :core:model
           └> :core:scoring only where domain use cases require it

:app ─> :core:database
     └> :core:healthconnect
```

Forbidden edges:

- `:feature:*` to `:app`
- `:feature:*` to another feature
- `:feature:*` to `:core:database` or `:core:healthconnect`
- `:core:*` to `:feature:*` or `:app`
- `:core:designsystem` to domain modules

## Module Responsibilities

### `:app`

Owns `Application`, activities, top-level `NavHost`, adaptive scaffold, global sync/recalculation banner, deep-link mapping, workers, infrastructure implementations, Android service integration, and Hilt composition. It imports feature entry points and supplies navigation callbacks. It contains no feature screen or feature ViewModel after migration.

### `:core:designsystem`

Owns Material 3 theme, color roles, spacing, typography, shapes, stateless primitives, and design-system previews. It may depend on Compose and Material libraries but not project domain modules.

### `:core:ui`

Owns generic presentation models, loading/error patterns, date/range display utilities, and components shared by multiple features. Shared APIs accept presentation data where practical. Components coupled to scoring semantics or one feature remain with their owning feature.

### Feature modules

- `:feature:dashboard`: dashboard route, screen, card composition, card management presentation, and Dashboard ViewModel.
- `:feature:sleep`: sleep route, score/history presentation, and Sleep ViewModel.
- `:feature:workouts`: workout list, workout detail, related components, and ViewModels.
- `:feature:vitals`: vitals overview plus heart-rate, blood-pressure, weight, body-fat, and steps detail flows.
- `:feature:insights`: insight presentation, detail UI, presentation mapping, and related tests.
- `:feature:settings`: all settings sections plus backup, security, resync, and sync settings presentation.
- `:feature:onboarding`: permission rationale, privacy, restore, and onboarding flow presentation.
- `:feature:about`: about, legal, feedback, license presentation, and About ViewModel.

## Shared-Code Classification

Before feature moves, classify every file under current `ui/common`, `ui/components`, and `ui/theme`:

1. Move design tokens and domain-free primitives to `:core:designsystem`.
2. Move genuinely cross-feature presentation helpers and generic components to `:core:ui`.
3. Move feature-specific components to their owner even when currently stored in a shared package.
4. Replace domain-heavy shared parameters with narrow presentation models only when this reduces coupling without duplicating domain rules.
5. Keep scoring calculations in `:core:scoring`; never copy formulas into presentation modules.

Classification must be explicit in the implementation plan. A compile error must not be resolved by making `:core:ui` a dumping ground or by adding a forbidden dependency.

## Contract Decoupling

Feature ViewModels must compile without app infrastructure. Existing concrete dependencies require narrow pure contracts before extraction.

Required contract areas include:

- observing user preferences;
- updating physiology/profile settings;
- updating display settings;
- updating sync and retention settings;
- selected-date observation and mutation;
- foreground sync state and trigger operations;
- backup/restore operations exposed to presentation;
- permission/recovery state needed by onboarding and settings.

Prefer focused interfaces over exposing the broad concrete DataStore repository. Put reusable domain contracts in `:core:model` or the existing core module that owns their domain. Keep Android/DataStore/WorkManager implementations in `:app`, bound through Hilt. Preserve existing runtime behavior and public StateFlow/SharedFlow semantics.

## Navigation Design

`:app` remains navigation composition root. Each feature exports route-level Composables and typed arguments needed by app navigation. Features receive callbacks for cross-feature navigation and never import another feature destination.

Ownership rules:

- Workouts owns workout list/detail presentation and detail argument handling.
- Vitals owns its overview and all grouped metric detail presentation.
- Dashboard emits navigation intents through callbacks.
- Settings emits About navigation through a callback.
- Onboarding exports its flow entry and completion callbacks.
- App shell owns tab selection, transition policy, bottom-bar visibility, deep links, and root graph composition.

Navigation arguments must be validated before repository access. Invalid arguments produce controlled error/back behavior instead of crashes.

## Data Flow and Runtime Invariants

```text
Health Connect → ingestion/core infrastructure → Room
Room/DataStore → core contracts → feature ViewModel
feature ViewModel → StateFlow/SharedFlow → Compose route/screen
```

- Room remains single source of truth.
- Health Connect remains ingestion-only.
- Features never access DAOs, Health Connect clients, DataStore, workers, or concrete repositories.
- Scoring continues through existing scoring repositories/use cases with no formula changes.
- Compose continues using `collectAsStateWithLifecycle`.
- Pull-to-refresh remains current-day-only through `triggerDailySync()`.
- Historical resync remains WorkManager-backed and retains shared progress reporting.
- `CancellationException` is always rethrown.

## Resources

Feature-owned strings, drawables, and other resources move with their feature. Shared resources move to `:core:designsystem` or `:core:ui`. App retains shell/global integration resources.

Resource names receive feature or shared prefixes where collision risk exists. String moves do not authorize copy changes. Any separate copy change must follow documentation synchronization requirements for About, tooltips, onboarding, scoring explanations, privacy, and website content.

## Migration Strategy

### Foundation sequence

1. Capture baseline module graph, source/resource ownership, test ownership, coverage, and build timings.
2. Add Gradle and source architecture tests for target dependency rules.
3. Extend convention build logic with Compose-feature defaults while keeping dependencies explicit.
4. Extract `:core:designsystem`.
5. Extract `:core:ui` using reviewed shared-code classification.
6. Introduce narrow contracts and Hilt bindings needed by feature ViewModels.

### Feature sequence

1. `:feature:about`
2. `:feature:insights`
3. `:feature:sleep`
4. `:feature:workouts`
5. `:feature:vitals`
6. `:feature:dashboard`
7. `:feature:settings`
8. `:feature:onboarding`

Sequence moves low-coupling features first, validates list/detail and grouped-flow patterns before dashboard, and leaves platform-heavy settings/onboarding integration until boundaries are stable.

Each feature migration is an independent PR that:

1. Adds module and failing boundary/ownership tests.
2. Moves production sources, tests, previews, and resources.
3. Renames packages and updates imports.
4. Wires app navigation and Hilt composition.
5. Compiles and tests feature independently.
6. Assembles and verifies app.
7. Updates `internal-docs/DATA_FLOW.md` synchronously.
8. Runs required codegraph indexing/synchronization.
9. Removes obsolete app files and dependencies within same PR.

Temporary adapters are permitted only inside `:app`, must be named as compatibility adapters, must have an explicit removal task, and must be gone before final acceptance. Dependency cycles and feature-to-app dependencies are never permitted as temporary measures.

## Error Handling

- Preserve current domain `Result` and UI-state behavior.
- Convert infrastructure exceptions at repository boundaries.
- Feature modules consume typed failures, not Android/database exceptions.
- Preserve missing-permission recovery behavior.
- Stop a migration PR when a boundary cannot be satisfied; revise shared contracts instead of weakening dependency rules.
- Keep old implementation intact until replacement path passes tests, then remove old path in same feature PR.

## Testing Strategy

### Architecture tests

Use both Gradle dependency assertions and Konsist/source checks. Verify forbidden module edges, forbidden imports, convention-plugin application, package ownership, app-shell limits, and acyclic graph. Do not rely only on directory-existence tests.

### Test ownership

- Move ViewModel, mapper, and presentation unit tests with owning feature.
- Move feature-local Compose tests with owning feature.
- Keep root navigation, cross-feature journeys, Hilt integration, restore integration, and baseline-profile tests in `:app`.
- Keep scoring determinism tests in `:core:scoring`.
- Update JaCoCo aggregation and package coverage gates so moved code remains measured.
- Add route argument and callback contract tests.

### Per-PR verification

```powershell
.\gradlew ktlintFormat
.\gradlew :feature:about:testDebugUnitTest
.\gradlew :feature:about:lint
.\gradlew :feature:about:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
.\gradlew lint
codegraph index
codegraph sync
```

Replace `about` with the feature migrated by that PR.

Run affected connected tests for navigation, onboarding, settings, permissions, restore, or Hilt integration changes. Run release/R8 verification at foundation milestones and final completion.

## Completion Criteria

- Eight feature modules exist and compile independently.
- `:app` contains no feature screen or feature ViewModel.
- App navigation, process recreation, deep links, pull-to-refresh, resync progress, backup/restore, permissions, and scoring outputs remain unchanged.
- No forbidden Gradle edge or source import exists.
- Full unit, lint, architecture, documentation-drift, connected smoke, release, and R8 checks pass.
- Baseline-profile generator still covers critical journeys.
- Feature-only changes do not recompile unrelated feature modules.
- Before/after clean and incremental build timings are recorded; material unexplained regression blocks completion.
- Coverage aggregation includes all moved code and retains existing gates.
- `internal-docs/DATA_FLOW.md` documents final module graph and ownership.
- Original Phase 4 Task 7 points to standalone complete implementation plan.
- No compatibility adapter, obsolete package, duplicate resource, dead dependency, placeholder, or pilot-scoped implementation task remains.

## Out of Scope

- Scoring formula or threshold changes.
- Room schema changes caused solely by modularization.
- New user-facing features or copy changes.
- Dynamic feature delivery.
- Independent deployment of feature modules.
- Full infrastructure modularization beyond contracts required to compile features.
- UI redesign.

## Main Risks and Controls

- **Shared UI becomes oversized:** require file-by-file classification and domain-free/shared API review.
- **Hilt/KSP aggregation breaks:** compile each feature independently and app after every move.
- **Resources disappear or collide:** move resources with code, prefix names, and test required strings.
- **Coverage silently drops:** update aggregation in foundation work before feature moves.
- **Navigation behavior drifts:** keep app as composition root and add route contract/integration tests.
- **Concrete settings dependency leaks:** introduce narrow contracts before moving affected ViewModels.
- **Long-lived duplicate paths:** remove old path in same feature PR and prohibit unfinished compatibility layers at completion.
- **Documentation becomes stale:** update load-bearing data-flow documentation in every structural PR.
