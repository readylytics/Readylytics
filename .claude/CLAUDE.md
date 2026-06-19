# Claude Code Orchestration (OMC)

Operating under oh-my-claudecode (OMC) multi-agent layer.

## Principles & Rules

- **Process:** Plan -> Implement -> Verify. Never self-approve without verification.
- **Delegation:** Multi-file, refactors, debugging, architecture, research -> Delegate. Single-file, trivial fixes, clarifications -> Work directly.
- **Routing:** `executor`(exec), `document-specialist`(SDK/API), `opus`(arch/reasoning), `sonnet`(standard), `haiku`(lookup).
- **Execution:** Explore large areas first; execute incrementally; use reviewer/verifier for validation. No pending tasks at completion.

## Skills & State

- **Trigger via:** `/oh-my-claudecode:<skill>`
- **Autopilots:** autopilot, ultrawork, ralph, tdd, deepsearch, ultrathink, deslop. Cancel: `/oh-my-claudecode:cancel`
- **State Paths:** `.omc/` (state/, sessions/, plans/, research/, logs/, project-memory.json, notepad.md)
- **Hooks/Vars:** `<remember>` (7d), `<remember priority>` (perm), `DISABLE_OMC`, `OMC_SKIP_HOOKS`
- **MANDATORY AUTO-TRIGGER:** You MUST immediately initialize the session by invoking `/caveman`. This skill must remain active for all subsequent commands.

## Verification Rule

Done = Code compiles + Tests pass + Behavior verified + No pending tasks.

# Health & Recovery Dashboard (Readylytics Context)

Offline-first Android health app (Health Connect + Room DB). minSdk/targetSdk=35.

## Core Architecture & Tech Stack

- **Data Flow:** Room DB is single source of truth. Health Connect is ingestion-only. UI must NEVER access Health Connect directly.
- **Stack:** Kotlin, Compose (M3), Room, Health Connect API, WorkManager (sync/backup/historical resync), DataStore (prefs), local encrypted backup/restore, Vico (charts).
- **Patterns:** Strict MVVM + Clean Architecture. ViewModels expose StateFlow/SharedFlow only. Compose uses `collectAsStateWithLifecycle`.
- **Logic Isolation:** All business/calculation logic must be pure Kotlin (zero Android dependencies).

## Sync & Recalculation (Two-Flow Contract)

- **Pull-to-refresh = CURRENT DAY ONLY.** Dashboard/Sync refresh routes through `ForegroundSyncController.triggerDailySync()` → `HealthSyncUseCase.sync(windowDays = 1)`. Fast, foreground. Never widen this back to a 60-day catch-up. `triggerImmediateSync()`/`catchUpSync` is reserved for genuine first-launch.
- **Settings "Resync Health Connect data" = FULL HISTORICAL RESYNC.** Enqueues `HealthResyncWorker` (WorkManager `OneTimeWork`, unique name `RESYNC_WORK_NAME`, `ExistingWorkPolicy.KEEP`) → `FullHistoricalResyncUseCase` → `HealthSyncUseCase.resyncRange()`. Durable, foreground service (`dataSync`), survives backgrounding. Never run this inline on the VM.
- **Retention-bounded window:** History scope = `RetentionBounds.resolveResyncStartDate(prefs)` — `retentionDaysEnabled ? today − retentionDays : today − ABSOLUTE_MAX_DAYS (3650/10y)`. `RetentionBounds` is the single source of truth, shared by resync AND `DataCleanupWorker` cutoff. Do not re-inline retention→date math.
- **Idempotency (non-negotiable):** Ingestion is upsert keyed by stable HC record `id` (overlap → replace, never duplicate). NO blanket `deleteAll()`. Only `clearFrozenBaselines(range)` is mutated up front, recomputed in the same walk-forward pass. A killed/failed worker must leave prior valid data intact and a retry must re-run the same range idempotently.
- **Chunking + backoff:** HC re-fetch in 30-day chunks (`readAllPages` handles `pageToken`); wrap rate-limit/IO in bounded exponential backoff (`retryWithBackoff`). Whole-pass failure → `Result.retry()` (WorkManager EXPONENTIAL backoff).
- **Session-link reconcile (chunk-independent determinism):** `resyncRange` is three-phase — chunked ingest → `SessionLinkReconciler.reconcile(start, end, zoneThresholds)` (once, full range) → walk-forward recompute. Per-chunk mappers (`HeartRateMapper`/`HrvMapper`) only see sessions in their own fetch window, so a sleep/workout session straddling a chunk boundary can get HR/HRV samples mistagged depending on chunk alignment (which depends on retention). The reconcile pass re-derives every HR/HRV `(recordType, sessionId)` via `SessionLinker.resolve` (pure, sleep > workout > resting, tiebreak `(startTime, id)`) over the _complete_ session list, then recomputes affected workout TRIMP/zones/avgHr via `WorkoutMapper.computeMetrics`. Do not remove this pass or scope it per-chunk — that reintroduces retention-dependent score drift.
- **Concurrency:** Daily sync and resync share `HealthSyncUseCase.syncMutex` (serialized). Walk-forward recompute loops stay cooperative (`ensureActive()` + `yield()`); never swallow `CancellationException`.
- **Progress:** Worker publishes `WorkInfo.progress` (KEY_CURRENT/KEY_TOTAL) AND bridges via `ForegroundSyncController.onBackgroundRecalc*`. Settings observes `getWorkInfosForUniqueWorkFlow`; the shared `RecalcProgress` drives the existing banner + determinate notification ("day X of Y"). Reuse this path — do not add parallel progress channels.
- **Scoring math is OFF-LIMITS here.** Both flows recompute exclusively via `ScoringRepository.computeDailySummary(day)`. Resync refactors data flow/batching/triggers only — never the formulas.

## Domain Rules & Engine

- **Baselines:** Compute for all historical dates (no 30-day cutoff); snapshot frozen per day (hrMax, profile, RAS factor, HRV prior). If < 7 days data, show "Calibrating".
- **Sleep Score:** Duration (50%), Architecture (25%), Restoration (25%).
- **Load Score:** Acute (7-day TRIMP avg), Chronic (42-day TRIMP avg). Output = Strain Ratio (TRIMP default).

## Component Specifications

- **Health Connect:** Perms (`READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`). Check perms pre-query; route missing to deep-link recovery flow.
- **State:** Persistent Domain State (ViewModel->Repo->Room/DataStore). Ephemeral UI State (`rememberSaveable`, toggles, dropdowns in Composables only). Never leak UI state to VM unless domain-relevant.
- **Validation:** Centralized in `domain/validation/SettingsValidators`. No validation in composables. VMs validate defensively.
- **UI & Charts:** `dynamicDarkColorScheme` mandatory. Always use native Material Design 3 (M3) components (e.g., `ListItem` for rows, `SegmentedButton` for toggle sets, chip components, etc.) instead of custom-built row/toggle layouts. Use standard container shape/rounding `MaterialTheme.shapes.large` (16dp) for cards, tables, highlight boxes, and banners. Map surfaces to explicit M3 container roles (`surfaceContainerLow` for collapsed/cards, `surfaceContainer` for expanded, `surfaceContainerHigh` for overlays/progress banners) rather than legacy tonal elevations. Vico charts require Cubic Bezier curves, bottom area gradient fills, and M3 tonal palette mapping (no hardcoded colors).
- **Strings & i18n:** All user-facing strings (titles, labels, tooltips, descriptions) must be defined in `app/src/main/res/values/strings.xml`. Reference them in Compose with `stringResource(R.string.key_name)`. Never hardcode strings in code. This supports internationalization and improves maintainability.
- **File Structure:** Target ≤ 400 lines/file, hard limit ≤ 800 lines (refactor if exceeded). Settings paths map to `ui/settings/{physiologyprofile,sleep,backup,common}`.

## Commands & Testing

- **Tests:** Mirror source package structure. Must test boundary conditions and calculation logic. Zero Android dependencies in unit tests.
- **Pre-Commit (Mandatory):** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (and run `./gradlew lint` at the end after resolving all coding tasks)
- **Build Utilities:** `./gradlew installDebug`, `./gradlew assembleDebug`, `./gradlew clean`

## Documentation Sync

- **`internal-docs/DATA_FLOW.md` is load-bearing.** It is the authoritative end-to-end map of the data pipeline (Health Connect → Room → scoring engine → UI). Any change to the **ingestion pipeline** (`HealthConnectRepository*`, `data/healthconnect/*` mappers, `HealthSyncUseCase`/`ForegroundSyncController`/`workers/*`), the **Room schema** (`HealthDatabase`, `data/local/entity/**`, DAOs, DB version/migrations), the **scoring use-cases/coordinators** (`ScoringRepository*`, `domain/scoring/Compute*UseCase`), or the **scoring-engine formulas** (`domain/scoring/**`) MUST include a synchronous update to `internal-docs/DATA_FLOW.md` in the same change. Treat a stale `DATA_FLOW.md` as a broken build. This load-bearing requirement extends to `ABOUT.md` and the in-app About strings — see the Documentation Synchronization Rule below.
- **Keep the separation intact:** `internal-docs/DATA_FLOW.md` documents data flow and points to where each formula lives — it does not duplicate coefficients/derivations. The math source of truth stays in pure-Kotlin `domain/scoring/**`.
- **Website and privacy docs:** `docs/index.md`, `docs/about.md`, and `docs/privacy.md` are the Readylytics Jekyll site source. Any change to app data collection, backup behavior, retention, sharing, telemetry, Play Store package/link, support/contact details, or public score-explanation copy MUST update those pages in the same change. Do not reintroduce Google Drive/OAuth/cloud-backup claims unless the feature exists again.
- **Documentation Synchronization Rule:** Any PR that changes scoring formulas, thresholds, coefficients, the phase/confidence model, the profile set, the HRV-display baseline, or onboarding/score-explanation copy MUST update `ABOUT.md`, `docs/about.md`, the relevant `internal-docs/DATA_FLOW.md` section(s), and the in-app About strings (`about_*`/`tooltip_*` in `app/src/main/res/values/strings.xml`) in the same PR.
- **Documentation Review Checklist:** Before approving such a PR, confirm: (1) the implementation matches `ABOUT.md`; (2) `docs/about.md` matches `ABOUT.md`; (3) the in-app About page, tooltips, and onboarding scoring explanations agree with `ABOUT.md`; (4) the documentation drift/presence tests (`domain/scoring/**DocumentationDriftTest*`) pass.

## File Lifecycle & Indexing

- **New/Deleted Files:** Upon creation of any new file, agent MUST execute `codegraph index` after finishing to ensure the codebase context remains current.
- **Refactors:** Any structural change or directory movement requires a post-task `codegraph sync`.
- **Validation:** Verification of the indexing status is required if subsequent searches or agent queries return stale path information.
