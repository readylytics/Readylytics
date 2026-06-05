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

# Health & Recovery Dashboard (Kinvolt Context)

Offline-first Android health app (Health Connect + Room DB). minSdk/targetSdk=35.

## Core Architecture & Tech Stack

- **Data Flow:** Room DB is single source of truth. Health Connect is ingestion-only. UI must NEVER access Health Connect directly.
- **Stack:** Kotlin, Compose (M3), Room, Health Connect API, WorkManager (sync/backup/historical resync), DataStore (prefs), Google Drive API (AppData), Vico (charts).
- **Patterns:** Strict MVVM + Clean Architecture. ViewModels expose StateFlow/SharedFlow only. Compose uses `collectAsStateWithLifecycle`.
- **Logic Isolation:** All business/calculation logic must be pure Kotlin (zero Android dependencies).

## Domain Rules & Engine

- **Baselines:** Compute for all historical dates (no 30-day cutoff); snapshot frozen per day (hrMax, profile, PAI factor, HRV prior). If < 7 days data, show "Calibrating".
- **Sleep Score:** Duration (50%), Architecture (25%), Restoration (25%).
- **Load Score:** Acute (7-day TRIMP avg), Chronic (42-day TRIMP avg). Output = Strain Ratio (TRIMP default).

## Recalculation & Sync Patterns

- **Two flows (do NOT merge):** Pull-to-refresh = **current day only** (`sync(windowDays = 1)`, foreground via `ForegroundSyncController.triggerDailySync()`). Settings "Resync Health Connect data" = **full historical resync**, retention-bounded, durable via `HealthResyncWorker` (WorkManager). `triggerImmediateSync()` is first-launch/onboarding catch-up only.
- **No math changes on resync:** All recompute routes through the existing `ScoringRepository.computeDailySummary(day)` walk-forward. Resync refactors data flow/batching/threading/triggers only — never scoring or baseline formulas.
- **Idempotency (hard rule):** NEVER blanket `deleteAll()`. Ingest via upsert keyed by stable HC record `id` (overlap = idempotent replace). Only `clearFrozenBaselines(range)` is mutated up front, then recomputed in the same pass. A killed/failed worker must leave prior valid data intact; a retry re-runs the same range idempotently.
- **Retention = single source of truth:** `domain/util/RetentionBounds` resolves start date / cutoff from `UserPreferences` (enabled → `today - retentionDays`; disabled → `today - ABSOLUTE_MAX_DAYS` = 3650). Shared by `DataCleanupWorker` and `FullHistoricalResyncUseCase` — do not inline the rule elsewhere.
- **Chunked HC re-fetch:** Resync ingests in 30-day windows with bounded exponential backoff for rate-limit/IO. Recompute reads baseline windows from Room (local), not HC. Daily and resync flows share `HealthSyncUseCase.syncMutex` to serialize.
- **Durable worker conventions:** `@HiltWorker` + foreground service (`foregroundServiceType=dataSync`), determinate notification + `setProgressAsync`. Progress bridges to the existing `RecalcProgressBanner` via `ForegroundSyncController.onBackgroundRecalc*` and to Settings via `WorkInfo`. Enqueue `ExistingWorkPolicy.KEEP`; transient failure → `Result.retry()`.

## Component Specifications

- **Health Connect:** Perms (`READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`). Check perms pre-query; route missing to deep-link recovery flow.
- **State:** Persistent Domain State (ViewModel->Repo->Room/DataStore). Ephemeral UI State (`rememberSaveable`, toggles, dropdowns in Composables only). Never leak UI state to VM unless domain-relevant.
- **Validation:** Centralized in `domain/validation/SettingsValidators`. No validation in composables. VMs validate defensively.
- **UI & Charts:** `dynamicDarkColorScheme` mandatory. Use `M3ScoreDial`, `M3DataCard`, `M3Tooltip`. 16dp rounded corners default. Semantic colors only. Vico charts require Bezier curves, bottom gradient fills, and M3 tonal palette mapping (no hardcoded colors).
- **File Structure:** Target ≤ 400 lines/file, hard limit ≤ 800 lines (refactor if exceeded). Settings paths map to `ui/settings/{physiologyprofile,sleep,cloud,common}`.

## Commands & Testing

- **Tests:** Mirror source package structure. Must test boundary conditions and calculation logic. Zero Android dependencies in unit tests.
- **Pre-Commit (Mandatory):** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
- **Build Utilities:** `./gradlew installDebug`, `./gradlew assembleDebug`, `./gradlew clean`

## File Lifecycle & Indexing

- **New/Deleted Files:** Upon creation of any new file, agent MUST execute `codegraph index` after finishing to ensure the codebase context remains current.
- **Refactors:** Any structural change or directory movement requires a post-task `codegraph sync`.
- **Validation:** Verification of the indexing status is required if subsequent searches or agent queries return stale path information.
