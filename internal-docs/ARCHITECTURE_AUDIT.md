# Readylytics — Principal-Level Architecture Audit

> **Scope:** Production-grade, staff+ architecture / performance / security / Compose audit of
> the Readylytics offline-first health & recovery dashboard (Kotlin, Jetpack Compose M3,
> Room + SQLCipher, Health Connect ingestion, WorkManager, Hilt, Proto DataStore).
> **Posture:** Pragmatic and convention-respecting — honors the existing CLAUDE.md / OMC
> contracts (two-flow sync, "scoring math is off-limits", load-bearing `DATA_FLOW.md`).
> **Method:** Three parallel codebase explorations (build/DI/architecture; data/sync/security;
> UI/ViewModel/scoring/tests) plus direct verification of three load-bearing claims
> (`SqlCipherKeyManager`, `app/build.gradle.kts` SDK levels, `LocalRestoreManager` transaction
> scope).
>
> **Status:** Audit document only — no source/build/test changes accompany this file.

---

## Executive Summary

Readylytics is a single-module, Clean-Architecture/MVVM Android app on a modern stack
(Kotlin 2.3, AGP 9.2, KSP, Compose BOM 2026.06, Room 2.8 + SQLCipher 4.16, Hilt 2.59,
WorkManager 2.11, type-safe Navigation-Compose, Proto DataStore). Engineering maturity is
high: encrypted DB at rest, encrypted local backups (AES-256), durable/idempotent historical
resync with checkpointing and cross-chunk session reconciliation, structured concurrency with
disciplined `CancellationException` handling, ~163 unit tests plus a macrobenchmark /
instrumentation suite, strict lint (`warningsAsErrors=true`), and JaCoCo coverage gates
(scoring 80%, sync 70%, workers 60%).

This audit found **0 Critical**, **~5 Major**, and a set of Minor / hardening items. The
dominant themes are:

1. one orchestration **god-class** (`HealthSyncUseCase`, 861 LOC);
2. **documentation drift** in the load-bearing governance docs (CLAUDE.md states
   `minSdk/targetSdk=35`; the build is `minSdk=26, targetSdk=37, compileSdk=37`);
3. a **single-module build / team-scale ceiling**;
4. a narrow **restore atomicity** gap (preferences restored outside the DB transaction); and
5. **performance observability gaps** (no Baseline Profile, no recomposition-count guards).

None block release; all are addressable incrementally with low regression risk. This is a
*mature, production-grade* codebase — the work ahead is hardening and scale-prep, not rescue.

| Metric | Score |
| --- | --- |
| Production Readiness | **8 / 10** |
| Long-Term Maintainability | **8 / 10** |

---

## Critical Issues

**None.** No happy-path data loss, no plaintext PII at rest, no exported-component exposure,
no main-thread DB/IO, and no unstructured concurrency (`GlobalScope`) were found. The two
items most often mis-classified as critical were verified as already mitigated:

- **Restore "data loss" — verified mitigated.** The `deleteAll()` calls and the streaming
  parse both run **inside** `healthDatabase.withTransaction { }`
  (`LocalRestoreManager.kt:147-185`); a mid-parse failure rolls the transaction back. This is
  demoted to a Major **atomicity-boundary** issue (preferences are restored *after* the commit
  at line 155) — not data loss.
- **"Plaintext" DB key — verified sound.** The SQLCipher key is a 256-bit random key wrapped
  by an AndroidKeyStore AES/GCM key and stored Base64 in `MODE_PRIVATE` SharedPreferences
  (`SqlCipherKeyManager.kt`), with plaintext ByteArrays zeroed after use. Hardening
  opportunities only (see m5).

---

## Major Issues

Each finding carries: Severity · Root Cause · Impact · Recommended Fix · Migration Strategy ·
Risk · Refactor Complexity · Regression Risk · Prerequisites · Timing.

### M1 — `HealthSyncUseCase` god-class (861 LOC, multi-responsibility)
- **Severity:** Major
- **Root cause:** Foreground daily sync, Health Connect ingestion, 30-day chunked resync,
  checkpoint/resume, the session-reconciliation trigger, and per-day scoring recompute all
  live in one class.
- **Impact:** Hardest file in the codebase to change safely; a merge hotspot; it is the exact
  code the CLAUDE.md two-flow contract most needs to keep legible.
- **Long-term consequence:** Onboarding friction and a standing regression magnet as the sync
  flows evolve.
- **Recommended fix:** Extract collaborators along the existing seams — `DailySyncUseCase`,
  `ResyncRangeUseCase`, `HealthIngestionCoordinator` — while keeping `syncMutex` ownership and
  the reconcile→walk-forward ordering intact. Do **not** alter scoring formulas or widen the
  daily window (CLAUDE.md contract).
- **Migration strategy:** Pure extract-class refactor behind unchanged public methods; lean on
  the existing determinism / regression tests as the safety net.
- **Risk:** Medium · **Complexity:** Large · **Regression risk:** Medium (sync is load-bearing)
- **Prerequisites:** Characterization tests around `sync` / `resyncRange` first.
- **Timing:** Short → Mid-term.

### M2 — Governance / documentation drift (load-bearing docs are stale)
- **Severity:** Major — the project itself declares "treat a stale `DATA_FLOW.md` as a broken
  build", so drift in the central governance doc is a first-class defect here.
- **Root cause:** `CLAUDE.md` states `minSdk/targetSdk=35`; the actual build is
  `minSdk=26, targetSdk=37, compileSdk=37` (`app/build.gradle.kts:74, 87-88`).
- **Impact:** `minSdk=26` means Health Connect availability is **not** guaranteed (HC requires
  API 28+ as an app, and is in-platform only on API 34+). The `Unavailable` route is therefore
  load-bearing and must remain tested — a doc claiming `minSdk=35` hides this entirely.
- **Recommended fix:** Reconcile `CLAUDE.md`, the `docs/` site, and the version table to the
  real SDK floor/target; add a tiny test or lint rule asserting documented-vs-actual SDK so the
  drift cannot silently recur.
- **Migration strategy:** Docs + one assertion; no code-behavior change.
- **Risk:** Low · **Complexity:** Small · **Regression risk:** Low
- **Prerequisites:** None.
- **Timing:** Immediate (quick win).

### M3 — Single-module build & team-scale ceiling
- **Severity:** Major (latent)
- **Root cause:** The entire app is one Gradle module (`:app`, ~47k LOC main / ~26k LOC test).
- **Impact:** Whole-app recompilation per change; Clean-Architecture boundaries are enforced by
  convention only (nothing prevents a UI class importing a DAO); parallel-team friction as
  headcount grows.
- **Long-term consequence:** CI time and merge contention scale super-linearly with features.
- **Recommended fix:** Incremental modularization. Start by extracting the pure, Android-free
  `:core:scoring` (`domain/scoring/**` already has zero `android.*` imports) and `:core:model`,
  then `:core:database` and `:core:healthconnect`; feature modules last.
- **Migration strategy:** Strangler pattern — one module per PR, with `:app` still assembling
  green at every step.
- **Risk:** Medium · **Complexity:** Massive (full) / Medium (first two core modules)
- **Regression risk:** Medium · **Prerequisites:** Version catalog already present (good).
- **Timing:** Mid → Long-term.

### M4 — Restore atomicity boundary (DB vs preferences)
- **Severity:** Major (narrow)
- **Root cause:** DB tables are wiped and reloaded inside a Room transaction (safe), but
  `restorePreferences(...)` runs *after* that commit (`LocalRestoreManager.kt:155`), followed by
  `SuccessRequiresRestart`. A crash or failure between DB commit and preferences restore leaves
  restored health data paired with stale preferences (baselines / profile / zones) until the
  next edit.
- **Impact:** Transient score miscalculation on a restored device; low frequency but
  user-visible if hit.
- **Recommended fix:** Restore (or stage) preferences within the same atomic unit, or restore
  preferences **first** and recompute baselines after the DB load; add an end-to-end
  "crash-after-DB-commit" test.
- **Migration strategy:** Localized to `LocalRestoreManager`; covered by a new restore test.
- **Risk:** Low · **Complexity:** Small · **Regression risk:** Low · **Prerequisites:** None.
- **Timing:** Short-term.

### M5 — Performance observability gaps
- **Severity:** Major (preventive)
- **Root cause:** No Baseline Profile / Startup Profile is shipped, and recomposition counts are
  asserted nowhere despite sophisticated manual flow-composition in `DashboardViewModel`.
- **Impact:** Silent jank / startup regressions can land unnoticed; the careful
  `distinctUntilChanged` / `@Immutable` work has no guardrail to protect it over time.
- **Recommended fix:** Ship a Baseline Profile (Macrobenchmark `BaselineProfileRule`); add
  Compose recomposition-count tests for the dashboard and the heavy chart components; wire the
  startup macrobenchmark into CI as a tracked metric.
- **Migration strategy:** Additive (new benchmark module/tests + generated profile); no behavior
  change.
- **Risk:** Low · **Complexity:** Medium · **Regression risk:** Low · **Prerequisites:** None.
- **Timing:** Short → Mid-term.

---

## Minor Issues

- **m1 — Domain → persistence leak.** `BaselineComputer` injects Room DAOs and exposes `suspend`
  history functions. `androidx.room` is not `android.*`, so the CLAUDE.md "zero Android deps"
  rule is technically met, but it couples pure scoring to persistence. *Fix:* introduce thin
  domain repository ports (`RhrHistorySource`, etc.) backed by DAOs in `data`. Complexity Medium,
  regression Low. Mid-term (pairs naturally with the `:core:scoring` extraction in M3).
- **m2 — Non-adaptive themed colors.** `LocalStatusColors` / `LocalExtendedColors`
  (`Theme.kt`, `Color.kt`) hold hardcoded defaults that do not track dynamic color or dark mode.
  *Fix:* derive from `ColorScheme` roles or supply per-theme values. Small / Low.
- **m3 — Hardcoded units & debug English strings.** `baselineUnit = "steps" / "ms"` appear in
  detail screens; `debugEnglish` insight copy lives in `InsightDetailResourceSpec`. *Fix:* move
  units into `strings.xml`; strip or guard debug copy out of release. Small / Low.
- **m4 — Accessibility unevenness.** Charts are exemplary (semantics + custom actions + tests),
  but some functional icons use `contentDescription = null`, there are no contrast tests, and
  forms / onboarding / navigation lack a11y tests. *Fix:* a11y sweep + Espresso/Compose a11y
  checks. Medium / Low.
- **m5 — SQLCipher key hardening.** The KeyStore key is not user-auth-bound and not
  StrongBox-backed, and there is no rotation path. Acceptable for an offline app with no app
  lock, but document the threat model and consider StrongBox-when-available plus optional
  lock binding. Small / Low.
- **m6 — Build speed.** `ksp.incremental` is disabled in `gradle.properties`. *Fix:* trial
  re-enable and measure. Small / Low.
- **m7 — Coverage gate asymmetry.** Strong per-package gates, but a 25% overall instruction
  floor; mappers/converters and some UI-state transitions are thin. Raise the floor gradually.

---

## Security Findings

The baseline is **strong**:

- SQLCipher AES-256 database (KeyStore-wrapped key, automatic plaintext→cipher migration on
  first launch, plaintext memory zeroing).
- AES-256 encrypted ZIP backups with a KeyStore-protected backup password; atomic temp-then-move
  writes.
- `android:allowBackup="false"`, `dataExtractionRules` / `fullBackupContent` configured,
  `networkSecurityConfig` enforced.
- Release signing via environment variables (no secrets in VCS); a Gradle task fails release
  artifacts when signing inputs are missing.
- Minimal exported surface (launcher activity + Health Connect rationale alias); least-privilege
  Health Connect read permissions with runtime checks and graceful degradation for optional
  data types.

Hardening opportunities (all Minor):

1. KeyStore key is not user-auth- or StrongBox-bound and has no rotation path [m5].
2. Health data is logged at DEBUG — confirm the production log sink is a no-op / PII-scrubbed.
3. No audit trail for backup / restore / key-lifecycle events.
4. The backup password is recoverable on a device whose KeyStore is compromised — a by-design
   tradeoff that should be documented in the threat model.

---

## Performance Findings

Strengths:

- All DAO access is `suspend` / `Flow`, off the main thread on `Dispatchers.IO`.
- Appropriate composite indexes (`HeartRate(sessionId, recordType, bpm)`,
  `Sleep(startTime, endTime)`).
- N+1 avoided via batch queries (e.g. `getSleepHrSamplesForSessions`).
- `Semaphore(4)` caps Health Connect IPC fan-out to avoid rate limits.
- Backup / restore stream in 100–500-row pages; WAL journal mode + `synchronous=NORMAL`.

Gaps (see **M5**): no Baseline Profile, no recomposition guards, startup not tracked as a CI
metric. The bounded `map { }` allocations in `upsertAll` are fine at current batch sizes.

---

## Architecture Assessment

Clean layering (`data` / `domain` / `ui` / `workers` / `di`) is applied consistently;
dependency direction is correct (UI → domain → data via interfaces); Hilt modules are
well-segmented (10 modules by concern); navigation is type-safe (Kotlinx-serialization routes).

Primary structural debts are **M1** (god-class) and **M3** (no module-enforced boundaries).
There is a light boundary smell in domain scoring depending on DAOs [m1]. ViewModels are mostly
lean and expose immutable `StateFlow`; the only notable orchestration creep is settings
ViewModels calling `scoringRepository.computeAndPersistDailySummary()` directly — acceptable,
but worth watching so it does not spread.

No circular dependencies, no shared mutable-state hazards, and no `GlobalScope` were found.

---

## Compose & UI Assessment

Strengths: `collectAsStateWithLifecycle` everywhere; deliberate state hoisting; `@Immutable`
UI-state classes; a single `remember`-key inputs object on the dashboard to suppress
recompositions; stable method-reference callbacks; Material 3 components (`ListItem`,
`SegmentedButton`, `NavigationBar`); dynamic color + dark mode; ~721 `stringResource` usages
(~98% i18n coverage).

Gaps: non-adaptive `CompositionLocal` colors [m2]; a few hardcoded units / debug strings [m3];
thin `derivedStateOf` usage (compensated by deriving in ViewModels); and no recomposition
measurement [M5]. Vico charts follow the M3 brief but their colors are largely boilerplate
rather than scheme-derived.

---

## Testing Assessment

~163 unit tests, ~26k test LOC (~55% of main code), with correct coroutine testing (`runTest`
+ test dispatchers + `advanceUntilIdle`), strong scoring / determinism / regression suites,
ViewModel coverage via MockK, and an instrumentation / macrobenchmark layer (startup, render,
battery, sync-memory, chart accessibility).

Gaps: recomposition-count tests, mapper/converter edge cases, isolated pure-math tests
(Z-score / EMA), a restore crash-boundary test [M4], and broader a11y coverage [m4]. Raise the
25% overall instruction floor over time [m7].

---

## Technical Debt Assessment

Debt is **concentrated and manageable**, not systemic: one god-class (M1), governance doc drift
(M2), a monolith build (M3), a narrow restore-atomicity edge (M4), performance observability
(M5), plus cosmetic / i18n / a11y polish. There are no pervasive anti-patterns, no circular
dependencies, no shared mutable-state hazards, and no `GlobalScope`. In short: the debt is
"scale & hardening", not "stabilize".

---

## Refactor & Remediation Roadmap

### Phase 1 — Critical Stabilization (Immediate, low-risk quick wins)
**Goals:** truth-up governance, close the narrow correctness edge, add performance guardrails.
- M2 doc / SDK reconciliation (+ a doc-vs-actual SDK assertion).
- M4 restore atomicity fix + crash-after-commit test.
- M5 (start): ship a Baseline Profile; add a dashboard recomposition-count test.
- m3 string / units cleanup; confirm the release log sink is PII-safe.

**Expected impact:** removes the only user-visible correctness edge; guards performance; fixes
governance drift. **Implementation order:** M2 → M4 → M5(start) → m3. **Risk:** Low.
**Rollout:** one small PR each; no migration required.

### Phase 2 — Architectural Refactor (Short → Mid-term)
**Goals:** tame the god-class, firm up boundaries, finish performance observability.
- M1: extract `DailySyncUseCase` / `ResyncRangeUseCase` / `HealthIngestionCoordinator` behind
  unchanged APIs, guarded by characterization tests; preserve the two-flow contract.
- m1: introduce domain history ports for `BaselineComputer`.
- M5 (finish): recomposition tests for heavy charts; startup metric in CI.
- m2: adaptive themed colors.

**Expected impact:** sync becomes legible and safely evolvable; domain purity tightened.
**Risk:** Medium (sync). **Rollout:** behind-API refactors landed incrementally, with the green
determinism tests as the gate; no feature flags needed.

### Phase 3 — Scalability Improvements (Mid-term)
**Goals:** enforce boundaries via modules; speed up builds; deepen tests.
- M3 first cut: extract `:core:model` + `:core:scoring` (already Android-free), then
  `:core:database` / `:core:healthconnect`.
- m6: re-trial KSP incremental; measure build wins post-split.
- m7: raise the coverage floor; add mapper / pure-math tests.

**Expected impact:** parallel-team velocity, faster CI, hard boundaries. **Risk:** Medium.
**Rollout:** strangler — one module per PR, `:app` stays green throughout.

### Phase 4 — Long-Term Hardening (Long-term)
**Goals:** enterprise-grade security & resilience.
- Feature modularization (per-feature modules) + convention plugins.
- m5: key hardening (StrongBox-when-available, optional app-lock binding, rotation) + a
  backup/restore audit trail.
- m4: full a11y program (contrast tests; forms / onboarding / nav coverage).
- Health Connect rate-limit-aware backoff loop; partial / staged restore UX.

**Expected impact:** future-proofing and audit readiness. **Risk:** Low–Medium per item.

---

## Highest ROI Improvements
1. **M2** doc / SDK reconciliation — Small, Immediate, kills governance debt.
2. **M4** restore atomicity — Small, removes the only user-visible correctness edge.
3. **M5** Baseline Profile + recomposition guards — Medium, protects all future UI work.
4. **M1** first extraction from `HealthSyncUseCase` — de-risks the hottest file.

## Highest Risk Areas
1. `HealthSyncUseCase` / resync pipeline — load-bearing, retention-dependent determinism; any
   change must preserve the reconcile→walk-forward ordering and `syncMutex`.
2. Restore path — destructive reload; protect the atomic boundary (M4).
3. SQLCipher key lifecycle — KeyStore-key invalidation makes the DB unreadable;
   `resetKeyAndDatabase` exists, but verify the recovery UX is tested.
4. Scoring engine — **off-limits** for formula changes per CLAUDE.md; refactors must be
   data-flow only.

## Recommended Refactor Order
M2 → M4 → M5(start) → m3 → M1 (extract with characterization tests) → m1 → M5(finish) →
M3 (`:core:scoring` / `:core:model` first) → m6 / m7 → Phase 4 hardening.

## Estimated Overall Refactor Complexity
**Medium overall.** Phase 1 is Small; M1 and M5 are Medium; M3 is Massive only if taken to full
feature-modularization (otherwise Medium for the first core modules). No big-bang rewrite is
warranted — the foundation is sound.

---

## Scores

| Dimension | Score | Rationale |
| --- | --- | --- |
| **Production Readiness** | **8 / 10** | Shippable today: encrypted, idempotent, well-tested, strict-lint. Held below 9–10 by the restore-atomicity edge (M4), perf-observability gaps (M5), and governance drift (M2). |
| **Long-Term Maintainability** | **8 / 10** | Clean layering, type-safe navigation, strong tests, version catalog. Held below 9–10 by the single god-class (M1) and convention-only (non-module-enforced) boundaries (M3). |
