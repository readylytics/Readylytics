# Health Data Remediation Phases 0 and 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plan WP-01 through WP-17 as reviewable correctness repairs with explicit evidence and recovery gates.

**Architecture:** Keep the existing module boundaries and scoring engine. Introduce only typed read completeness, transactional dirty work, shared maintenance ownership, immutable run identity and authoritative tier coverage. Independent diagnostics and restore guards can ship first.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, §§4, 8–10 (Phase 0/1; WP-01–17), 11–15. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

## Global Constraints

- "Do not use restricted experimental deduplication internals or require an SDK upgrade for this plan."
- "Transient failures propagate as typed errors/exceptions and cancellation remains cancellation."
- "SDK read/retry/enrichment happens outside Room transactions."
- "Never uninstall `app.readylytics.health` to solve a migration or signing conflict."
- Repository floors: minSdk=26, compile/targetSdk=37; Health Connect 1.1.0; current Room schema 19 at inspected HEAD `93c468b2`.
- Room is the UI source of truth; calculations remain pure Kotlin. Preserve current-day refresh, `RetentionBounds`, full-range session reconciliation, WorkManager `KEEP`, and existing progress channels.
- Preserve formulas, coefficients, profiles and sleep/source-selection policies. Decision-gated proposals below are not permission to change product semantics.
- Follow the index's documentation matrix, mandatory pre-commit commands, migration ledger, file limits (target ≤400, hard ≤800), and indexing rules. No new suppressions or baseline edits.

---

## Scope and status

Planning only. This change creates implementation instructions; it does not implement repairs, run runtime tests, claim baseline measurements, or authorize release. The source roadmap selected `internal-docs/plans/`; this set follows that convention. It intentionally splits independent subsystems into separate plans. Shared schema and API tasks are sequential dependencies, not parallel editing opportunities.

The original review used `0051abea`; this plan inspected HEAD `93c468b2`, schema 19. Source paths were resolved against HEAD. Existing comments and test names sometimes refer to older WP numbers. Treat the table below as the authoritative mapping for this set.

## Execution order and deliverables

| Order | Plan | Tasks / roadmap coverage | Independently reviewable result |
|---|---|---|---|
| 1 | [Baseline](2026-09-11-remediation-baseline.md) | B1–B2 / WP-01 | Reproduction fixtures and measured current-schema reference |
| 2 | [Safety](2026-09-11-remediation-safety.md) | S1–S3 / WP-02, WP-03, early SEC-005 | Safe release diagnostics, rollback-safe restore validation, retained recovery points |
| 3 | [Persistence](2026-09-11-remediation-persistence.md) | P1–P3 / WP-04–05 | Additive journal/provenance, maintenance ownership, authoritative payloads |
| 4 | [Sync lifecycle](2026-09-11-remediation-sync.md) | H1–H6 / WP-06–10 | Complete restart scans, permission lifecycle, type updates, prepared workouts, fixed runs |
| 5 | [Scoring](2026-09-11-remediation-scoring.md) | C1–C6 / WP-11–15 | Correct historical inputs, calibration, absence, core sleep, canonical workout results |
| 6 | [Recovery](2026-09-11-remediation-recovery.md) | R1–R3 / WP-16, remaining SEC-005 | Coherent export, recoverable rotation, restore maintenance recovery |
| 7 | [Tier coverage](2026-09-11-remediation-tiers.md) | T1–T3 / WP-17 | Complete-minute rollup and one visible generation, with legacy quality retained |

C1 can follow baseline independently; deployment of the historical repair waits for persistence. H2's typed read contract is defined before H1 is wired to it: execute H2, H1, H3, H4, H5, H6 within the sync plan. R1/R2 require P2/P3; R3 also requires S2. T1 can follow P2; T2/T3 require P3/H5/H6 and OD-1. Canonical workout publication C5 must be revalidated after T3 before release.

Do not ship successive automatic scoring-version bumps for C1–C6. Keep one pending repair revision, finish input/tier correctness, then enable one ascending retained-history repair with durable progress. Each intermediate code commit is testable; enabling a corrected public generation is a separate release gate.

## Decision ledger

Record the selected answer in the affected child plan before executing a gated branch. Elapsed time is not approval. Ungated fixtures and confirmed fixes continue independently.

| Gate | Policy (OD-1 recorded; others: concrete proposed policy for review) | Blocks |
|---|---|---|
| OD-1 | **RECORDED DECISION (2026-09-17).** Adopted as proposed: preserve legacy warm rows as approximate/unknown; only a complete authorized interval refresh may replace them; no exact deletion promise for lost legacy lineage. Per-source, per-minute contributions are enabled on the measured evidence in `benchmark/BASELINE.md`'s DB-002 row (`OdOneContributionCostMeasurementTest`): 136.5 B/source-minute and 170.7 B/published minute for a dense parent, 78.7 B/source-minute and 978.5 B/published minute (5.7×) for one-source-per-sample, where the dominant cost is the `health_source_records` fan-out (~110 B/source) rather than the contribution row itself; sub-MB WAL and a single transaction per day-chunk in both distributions; a single-source delta-delete stays bounded (1–5 ms). Scope caveat: measured on host-JVM SQLite without SQLCipher, so absolute bytes are a floor. Implementation consequence: an ordinary hot→warm rollup quarantines `LEGACY_UNKNOWN` minutes (raw evidence retained, coverage untouched) instead of replacing or concatenating them. | T2/T3 schema and publication choice; corresponding archive fields |
| OD-2 | Maturity counts distinct cumulative eligible score days visible as of D; statistics keep their existing windows. Missing retained history is unknown, never fabricated; preserve a validated frozen count. Confirm treatment of gaps/retention before metadata repair. | C2 cumulative-count policy and score metadata repair |
| OD-3 | Reuse a persisted canonical result only with matching configuration, source and algorithm revisions. Otherwise missing HR is unavailable; never reinterpret legacy zone TRIMP as another model. | C5 missing-HR fallback and affected display copy |
| OD-4 | Track distance/elevation source IDs and old/new intervals through their own tokens; preserve origin-aware attribution and existing device selection. Do not add new source controls or physiological deduplication. | H4 interval provenance policy; H3 VO2 is independent |
| OD-5 | Preserve distinct displayed RHR ratio and adaptive RHR populations; fix only date bounds. | No block for C1; any unification is excluded |
| OD-6 | Proposed selected-device steps policy: retain interval endpoints and attribute the whole count to its start day; label interval approximation. Preserve existing elapsed 30-day VO2 lookback unless calendar-day semantics are explicitly selected. | C6 cross-midnight steps attribution and VO2 lookback semantics, not exclusive upper-bound fix |

## Shared contracts and migration ledger

P1 defines `DirtyRangeEntity`, `DirtyRangeDao`, and source metadata. P2 defines `HealthMutationCoordinator`, `DirtyRangeStore`, publication tickets, and startup drain. P3 defines complete source payloads. H2 defines `ReadOutcome`; H1 consumes its completion authority. H6 defines `HistoricalRunIdentity`. C3 defines `DayAssembly`; C5 defines `CanonicalWorkoutResult`. R2 defines backup operation journal phases. T2 owns coverage and contribution schema.

Reserve additive migrations against this inspected HEAD:

| Migration | Owning task | Contents / rollback |
|---|---|---|
| 19→20 (S1) | P1 | Source bounds/origin/revision/completeness; dirty journal and local mutation/maintenance state; preserve source integer IDs |
| No version bump | H4 | Proposed interval provenance fits S1 source metadata; a different accepted OD-4 design requires revising this ledger first |
| 20→21 | C5 | Nullable canonical result provenance; scalar modelTrimp at HEAD cannot prove source/config/quality validity |
| 21→22 (S2) | T2 | Coverage/contributions and unpublished refresh staging after OD-1 evidence |

Never edit a migration once shipped. If execution HEAD has advanced, reserve the next contiguous versions, update this ledger and every child plan before coding. Register forward migrations and exported JSON together; exercise installed supported upgrade chains and the existing fail-closed external v7 path. Do not add a destructive fallback or main-thread backfill. Restore decoders default new fields conservatively; legacy unknown metadata cannot authorize deletion. S3 seen-ID staging and performance indexes belong to Phase 2, not this set.

## Test and commit protocol for every task

Each numbered checkbox is one action; split an implementation step into local edits if it exceeds five minutes. Code blocks specify the critical test/assertion/contract or algorithm, with exact integration instructions for existing collaborators. Existing fixture setup stays in the named test class unless the task explicitly creates a helper. Imports follow the declared owning packages; use JUnit `@Test`, coroutine `runTest`, and `kotlin.test` assertions for new pure tests.

For a characterization, first run the intended invariant against baseline and record the failure. Keep it in the task branch and make it green with its repair before merging. Do not commit ignored tests, tests asserting broken behavior, or a permanently failing main branch. Baseline measurement-only commits can stay green independently.

During development run each named test, then the affected module. Before each implementation commit run:

```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

At implementation completion also run:

```bash
./gradlew lintRelease
```

If formatting alters unrelated files, inspect and restore only formatter-produced unrelated changes without overwriting user edits. Resolve touched-file detekt issues structurally. New pure tests must not import Android; real Room/SAF/WorkManager behavior belongs in instrumented fixtures or the repository's existing framework-test adapters. A mocked transaction runner does not prove rollback or FK integrity.

After creating files run `codegraph index`; after structural changes run `codegraph sync`. Run `git diff --check` and inspect staged files. Commit only the task's code, tests, schema, and synchronized documentation; suggested messages appear in each task. No commit is made by this planning deliverable.

## Documentation matrix

| Trigger | Files to change in the same task |
|---|---|
| Ingestion, stores, workers, schema, scoring coordinators | `internal-docs/DATA_FLOW.md`: actual version, source identity, transaction/lock boundaries, dirty acknowledgments, complete reads, checkpoint identity, tier ownership |
| Phase/baseline display/scoring explanation changes | `ABOUT.md`, `docs/about.md`, relevant `internal-docs/DATA_FLOW.md`, `about_*`/`tooltip_*` in `app/src/main/res/values/strings.xml`; audit onboarding and run existing documentation drift/presence tests |
| Permissions, diagnostics, retention, backup/restore/export | `docs/index.md`, `docs/about.md`, `docs/privacy.md`; `docs/backup-and-data.md` where relevant; in-app About/permission/backup strings in `app/src/main/res/values/strings.xml` |
| Benchmark evidence | `benchmark/BASELINE.md`: build/device/date/fixture seed/shape/tier/config/algorithm, medians/tails and limitations; retain historical results labelled historical |

Use exact resource keys selected from current strings, not hardcoded UI copy. Explain corrections as input/data-integrity fixes; do not duplicate formula coefficients in DATA_FLOW. No cloud, Drive/OAuth, new telemetry or permission claims.

## Final Phase-1 acceptance

- [ ] Link every WP-01–17 acceptance result to its test output/measurement and repair commit.
- [ ] Historical edits, removals, empty sources, moved timestamps, revoke/regrant and interrupted scans match a clean same-tier import/rebuild.
- [ ] Mutation→dirty append and daily summary/recommendation→acknowledgment each have real transaction interruption evidence; failures preserve prior valid outputs.
- [ ] Frozen phase/count/config, historical bounds, core-only recovery and all TRIMP consumers agree; no coefficient changes.
- [ ] Restore malformed/legacy/current archives, mixed operation failures and file/SAF publication retain recoverable data; migration chain and backup defaults pass.
- [ ] T3 validates all readers, source selection and full-range relinking; warm approximation is distinguished from stale/duplicate coverage.
- [ ] Mandatory build/unit/lint gates pass; device/provider gaps remain explicitly release-blocking where required.
- [ ] Phase-0 million-parent and nested-sample measurements are recorded. Do not claim bounded-memory optimization: WP-18–24 remain out of scope except explicitly named safety prerequisites.

## Plan self-review

Reviewed against inspected HEAD: 26 tasks across seven subsystem plans; all Modify paths resolve to existing files or explicitly created prerequisites, and every plan link resolves. The two slightly longer subsystem files remain below the 800-line hard limit. Planning checks validate document structure and references; runtime checks are still implementation work.

Coverage: B1/B2 cover Phase 0; S1/S2/P1–P3/H1–H6/C1–C6/R1–R3/T1–T3 cover all Phase-1 work packages. SEC-005 is deliberately pulled forward into S3/R1/R2 even though the roadmap also assigns optimization work to WP-19. Conservative retained-suffix invalidation is required now; narrowing and cursor optimizations stay in WP-21/22. No roadmap acceptance is silently declared fixed by scaffolding or an unresolved decision.
