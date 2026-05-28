<!-- OMC:START -->
<!-- OMC:VERSION:4.13.4 -->

# oh-my-claudecode - Intelligent Multi-Agent Orchestration

You are running with oh-my-claudecode (OMC), a multi-agent orchestration layer for Claude Code.
Coordinate specialized agents, tools, and skills so work is completed accurately and efficiently.

<operating_principles>

- Delegate specialized work to the most appropriate agent.
- Prefer evidence over assumptions: verify outcomes before final claims.
- Choose the lightest-weight path that preserves quality.
- Consult official docs before implementing with SDKs/frameworks/APIs.
  </operating_principles>

<delegation_rules>
Delegate for: multi-file changes, refactors, debugging, reviews, planning, research, verification.
Work directly for: trivial ops, small clarifications, single commands.
Route code to `executor` (use `model=opus` for complex work). Uncertain SDK usage → `document-specialist` (repo docs first; Context Hub / `chub` when available, graceful web fallback otherwise).
</delegation_rules>

<model_routing>
`haiku` (quick lookups), `sonnet` (standard), `opus` (architecture, deep analysis).
Direct writes OK for: `~/.claude/**`, `.omc/**`, `.claude/**`, `CLAUDE.md`, `AGENTS.md`.
</model_routing>

<skills>
Invoke via `/oh-my-claudecode:<name>`. Trigger patterns auto-detect keywords.
Tier-0 workflows include `autopilot`, `ultrawork`, `ralph`, `team`, and `ralplan`.
Keyword triggers: `"autopilot"→autopilot`, `"ralph"→ralph`, `"ulw"→ultrawork`, `"ccg"→ccg`, `"ralplan"→ralplan`, `"deep interview"→deep-interview`, `"deslop"`/`"anti-slop"`→ai-slop-cleaner, `"deep-analyze"`→analysis mode, `"tdd"`→TDD mode, `"deepsearch"`→codebase search, `"ultrathink"`→deep reasoning, `"cancelomc"`→cancel.
Team orchestration is explicit via `/team`.
Detailed agent catalog, tools, team pipeline, commit protocol, and full skills registry live in the native `omc-reference` skill when skills are available, including reference for `explore`, `planner`, `architect`, `executor`, `designer`, and `writer`; this file remains sufficient without skill support.
</skills>

<verification>
Verify before claiming completion. Size appropriately: small→haiku, standard→sonnet, large/security→opus.
If verification fails, keep iterating.
</verification>

<execution_protocols>
Broad requests: explore first, then plan. 2+ independent tasks in parallel. `run_in_background` for builds/tests.
Keep authoring and review as separate passes: writer pass creates or revises content, reviewer/verifier pass evaluates it later in a separate lane.
Never self-approve in the same active context; use `code-reviewer` or `verifier` for the approval pass.
Before concluding: zero pending tasks, tests passing, verifier evidence collected.
</execution_protocols>

<hooks_and_context>
Hooks inject `<system-reminder>` tags. Key patterns: `hook success: Success` (proceed), `[MAGIC KEYWORD: ...]` (invoke skill), `The boulder never stops` (ralph/ultrawork active).
Persistence: `<remember>` (7 days), `<remember priority>` (permanent).
Kill switches: `DISABLE_OMC`, `OMC_SKIP_HOOKS` (comma-separated).
</hooks_and_context>

<cancellation>
`/oh-my-claudecode:cancel` ends execution modes. Cancel when done+verified or blocked. Don't cancel if work incomplete.
</cancellation>

<worktree_paths>
State: `.omc/state/`, `.omc/state/sessions/{sessionId}/`, `.omc/notepad.md`, `.omc/project-memory.json`, `.omc/plans/`, `.omc/research/`, `.omc/logs/`
</worktree_paths>

## Setup

Say "setup omc" or run `/oh-my-claudecode:omc-setup`.

<!-- OMC:END -->

# Health & Recovery Dashboard - Project Context for Claude

## 📱 Project Overview

This is an offline-first, Android-native health and recovery tracking app. It acts as an advanced sports science tool that reads biometric data (Sleep, HRV, RHR, Workouts) from Google Health Connect, calculates advanced metrics (TRIMP, Strain Ratio, Sleep Score), and stores data locally using Room (SQLite).

The UI follows a strict "Material You" (Material 3) aesthetic: dynamic dark mode first, using semantic color roles (Success, Error, Tertiary) to handle health status indicators natively with Jetpack Compose and Vico for charting.

## 🛠 Tech Stack & Environment

- **Language:** Kotlin
- **IDE:** Android Studio
- **SDK Target & Minimum:** `minSdk = 35` (Android 15), `targetSdk = 35`
- **UI Toolkit:** Jetpack Compose (Material 3)
- **Local Database:** Room Database (SQLite)
- **Data Source:** Google Health Connect API
- **Background Work:** WorkManager (for daily sync and Google Drive backups)
- **Cloud Backup:** Google Drive API (AppData scope)
- **Charting:** Vico (Jetpack Compose native)
- **Local State:** DataStore (Preferences)

## 🏗 Architecture & Code Style

- **Pattern:** MVVM (Model-View-ViewModel) + Clean Architecture principles.
- **State Management:** Use `StateFlow` and `SharedFlow` in ViewModels. Compose UI should observe state using `collectAsStateWithLifecycle()`.
- **Offline-First:** All UI must be driven by the local Room database (Single Source of Truth). Health Connect is purely an ingestion source.
- **Algorithmic Engine:** Keep the math formulas (Sleep Score, Load Score, Baselines) in a dedicated, pure-Kotlin repository layer that is easily testable and decoupled from the Android framework.

## 🔗 References & Implementation Hints

- **Paiesque Repository:** Implementation hints, especially regarding Health Connect data parsing, heart rate zones, and workout calculation logic, can be taken from the reference repository: [https://codeberg.org/ojrandom/paiesque](https://codeberg.org/ojrandom/paiesque). Use this as a guide for handling raw biometric data flows.

## 📏 Compose & UI Guidelines

- **Theme:** Enforce a Material 3 aesthetic with `dynamicDarkColorScheme`.
- **Components:** Create reusable components for recurring elements (e.g., `M3ScoreDial`, `M3DataCard`, `M3Tooltip`).
- **Shapes:** Use rounded corners, primarily `RoundedCornerShape(16.dp)` for cards.
- **Charts:** When using Vico, implement smooth bezier curves for line charts with translucent gradient fills below the line, mapped to M3 Primary/Secondary/Tertiary tonal palettes.

## ⚙️ Core Business Rules

- **Baselines:** Always calculate physiological baselines (HRV, RHR) using the **Median** over the past 30 days. If < 7 days of data exist, UI must show a "Calibrating" state (greyed out / SurfaceVariant).
- **Sleep Score:** Weighted by Duration (50%), Architecture (25%), and Restoration (25%). Depends on a user-defined Goal Sleep Time ($G_{tst}$).
- **Load Score:** Based on Acute:Chronic Workload Ratio (Strain Ratio) matching 7-day average TRIMP vs. 42-day average TRIMP.
- **Foreground Sync:** The app should conditionally sync with Health Connect upon returning to the foreground based on user preferences (Never, Always, By Time interval up to 24h).
- **Health Connect Permissions:** Must strictly handle permissions (`READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`) before querying. Provide a UX to deep-link to system settings if permissions are missing.

## ⌨️ Common Build Commands

- Run app: `./gradlew installDebug`
- Build APK: `./gradlew assembleDebug`
- Clean project: `./gradlew clean`
- Run unit tests: `./gradlew testDebugUnitTest`

## 🔧 Pre-Commit Checks

**MANDATORY before any commit:**
- Format all Kotlin code: `./gradlew ktlintFormat`
- This ensures consistent code style across the codebase
- Agents must run this step as part of commit preparation

## 📋 Settings Architecture (Phases 1–8 Refactoring)

### Separation of Concerns Pattern

**Validation Layer (Domain)**

- All validation rules in `domain/validation/` (ValidationRule interface, IntRangeRule, ValidationResult)
- SettingsValidators.kt defines canonical rules (BIRTHDAY_DAY_RULE, HRV_BASELINE_RULE, etc.)
- Example: `SettingsValidators.BIRTHDAY_DAY_RULE.validate(input: String): ValidationResult`

**State Management Layer (ViewModel)**

- ViewModels expose state via StateFlow (PhysiologySettingsViewModel, SleepSettingsViewModel, etc.)
- ViewModels accept events via onEvent(event: SettingsEvent) sealed interface
- ViewModels validate events defensively before persisting
- UI-only state (section toggles) managed locally with rememberSaveable in composables

**Rendering Layer (Composable)**

- Validate input before firing events (client-side feedback)
- Display errors from validation result (domain layer)
- Remain simple: bind state → render, handle events → fire validated events
- No validation logic in composables; all rules from domain layer

### Code Organization Standards

File size: target ≤ 400 lines, hard limit 800 lines  
Test structure mirrors source: src/test/java/…/<same-package>/<test-file>

Module organization:

```
ui/settings/
├── physiologyprofile/        [Gender, birthday, heart rate zones]
├── sleep/                     [Sleep goals, HRV/RHR baselines]
├── cloud/                     [Google Drive backup/sync]
├── common/                    [SettingsConstants, ValidatingTextField]
├── SettingsScreen.kt          [Root, navigation]
├── SettingsEvent.kt           [Sealed interface]
├── *SettingsViewModel.kt      [State, events]
```

Constants & Naming:

- Physiological (domain-level, immutable): MIN_HEART_RATE=1, MAX_HEART_RATE=220, MIN_HRV_MS=1, MAX_HRV_MS=500, MIN_RHR_BPM=30, MAX_RHR_BPM=100
- UI Layout (module-scoped): HORIZONTAL_PADDING=16.dp, VERTICAL_SPACER=8.dp in SettingsConstants.kt

### Validation Pattern (Canonical)

For numeric/bounded inputs:

```kotlin
private val rule = SettingsValidators.BIRTHDAY_DAY_RULE
val validation = rule.validate(userInput)

// Render error state
OutlinedTextField(
    isError = userInput.isNotEmpty() && validation is ValidationResult.Invalid,
    supportingText = {
        if (validation is ValidationResult.Invalid) Text(validation.message)
    },
)

// Validate before firing event
onValueChange = { newValue ->
    userInput = newValue
    rule.validate(newValue).let { result ->
        if (result is ValidationResult.Valid) {
            onEvent(SettingsEvent.Updated(result.parsedValue))
        }
    }
}
```

### State Management Hierarchy

**Domain State** (ViewModel → SettingsRepository → DataStore): appTheme, paiScalingFactor, stepGoal, birthday, gender, heartRateZones, sleep goals, HRV/RHR baselines. Persists to disk.

**UI-Only State** (Composable local via rememberSaveable): Section toggles, dropdown expanded, search query. Persists across recompositions; cleared on process death.

### Code Quality Checklist (Phase 8)

- [ ] No hardcoded validation thresholds (all via ValidationRule)
- [ ] No string error messages in composables (all from ValidationResult)
- [ ] Numeric inputs validated before event firing
- [ ] No duplicate validation logic (use extracted helper composables)
- [ ] ViewModels include defensive validation
- [ ] Tests cover valid/invalid boundaries for all inputs
- [ ] No dead code; all public functions have callers
- [ ] Files ≤ 400 lines target (800 max)
