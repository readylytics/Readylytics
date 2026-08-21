# Detekt Tier 4 — Needs a Decision, Not a Fix

**Status:** not started · **Created:** 2026-08-21 · **Owner:** unassigned
**Parent:** `DETEKT_BASELINE_BURNDOWN.md` §6, Tier 4
**Scope:** 30 baseline entries across 3 rules. Each requires a judgement call about whether the current code is correct-by-design or genuinely wrong.

---

## Rules in scope

| Entries | Rule | Why it needs a decision |
|--------:|------|------------------------|
| 23 | `SwallowedException` | Many broad catches are intentional. CancellationException rethrow policy is Konsist-owned. |
| 5 | `InvalidPackageDeclaration` | Package doesn't match directory. Survived package alignment — needs manual fix or relocation. |
| 2 | `ThrowsCount` | Functions with multiple throw sites. May be correct control flow. |

---

## `SwallowedException` (23 entries)

Each entry is a `catch` block that catches an exception and does not rethrow it. Detekt flags this because silently swallowing exceptions hides bugs. However, many of these are **correct by design** — log sinks, graceful degradation, worker retry logic.

**Before fixing any of these, read the surrounding code and comments.** Several carry explicit rationale.

### Entries by module

#### `core/healthconnect` (6 entries)

| File | Exception | Assessment |
|------|-----------|------------|
| `DailySyncUseCase.kt` | `HealthConnectWindowTimeoutException` | Likely intentional — timeout during sync window should degrade gracefully, not crash. **Decision:** Log and continue, or propagate? |
| `HealthConnectRepositoryImpl.kt` | `Exception` (broad) | Broad catch in HC read path. **Decision:** Narrow to specific HC exceptions, or keep as defensive boundary? |
| `HealthConnectRepositoryImpl.kt` | `HealthConnectPermissionRevokedException` | Permission revoked mid-read. **Decision:** Likely correct — return empty rather than crash. |
| `HealthConnectRepositoryImpl.kt` | `SecurityException` | Security exception during HC access. **Decision:** Same as above — graceful degradation. |
| `HealthConnectRepositoryImpl.kt` | `UnsupportedOperationException` | Device doesn't support an HC feature. **Decision:** Correct — return empty. |
| `ResyncRangeUseCase.kt` | `HealthConnectWindowTimeoutException` | Same as DailySyncUseCase. |

#### `core/model` (1 entry)

| File | Exception | Assessment |
|------|-----------|------------|
| `ErrorBoundary.kt` | `Exception` | Error boundary by definition swallows exceptions. **Decision:** Almost certainly correct — suppress with rationale. |

#### `app` (12 entries)

| File | Exception | Assessment |
|------|-----------|------------|
| `BirthdayCheckWorker.kt` | `Exception` | Worker catch-all. **Decision:** Should log, return `Result.failure()`, not crash. Likely correct. |
| `CrashReportShareIntent.kt` | `PackageManager.NameNotFoundException` | Package not found while building share intent. **Decision:** Correct — graceful fallback. |
| `DataCleanupWorker.kt` | `Exception` | Worker catch-all. Same pattern as BirthdayCheckWorker. |
| `DataRollupWorker.kt` | `Exception` | Worker catch-all. Same pattern. |
| `DatabaseKeyRotatorTest.kt` | `IllegalStateException` | Test intentionally catches expected exception. **Decision:** Use `assertThrows` instead. |
| `DocumentationDriftTest.kt` | `Throwable` | Test catches to provide better error messages. **Decision:** Use `assertDoesNotThrow` or similar. |
| `EncryptionManager.kt` | `Exception` | Crypto initialization failure. **Decision:** Should this propagate? Currently wraps and rethrows as RuntimeException (overlaps with Tier 2 `TooGenericExceptionThrown`). |
| `LocalBackupManager.kt` | `Exception` | Backup failure. **Decision:** Correct — backup failure should not crash. But should propagate as `Result.failure`. |
| `LogcatCaptureStoreImpl.kt` | `IOException` | Log capture IO failure. **Decision:** Correct — log capture is best-effort. |
| `SyncViewModel.kt` | `HealthConnectPermissionRevokedException` | Permission revoked during foreground sync. **Decision:** Correct — shows permission-revoked UI state instead of crashing. |
| `UserPreferencesMapper.kt` | `Exception` | Preferences deserialization. **Decision:** Correct — corrupt prefs should fall back to defaults, not crash. |
| `UserUseCase.kt` | `Exception` | User data loading. **Decision:** Review — may be too broad. |

#### `feature/settings` (3 entries)

| File | Exception | Assessment |
|------|-----------|------------|
| `CustomColorPicker.kt` | `Exception` | Color parsing from user input. **Decision:** Correct — invalid hex should show error, not crash. |
| `DataSourceSettingsViewModel.kt` | `Exception` | Settings load failure. **Decision:** Review breadth of catch. |
| `PhysiologySettingsViewModel.kt` | `Exception` | Settings save failure. **Decision:** Review breadth of catch. |

#### `feature/dashboard` (1 entry)

| File | Exception | Assessment |
|------|-----------|------------|
| `GetWorkoutMetricsUseCase.kt` | `Exception` | Metrics computation failure. **Decision:** Should degrade gracefully — likely correct. |

### Recommended resolution strategy

1. **Suppress with rationale** for catches that are correct by design: `ErrorBoundary.kt`, all Worker catch-alls, `CrashReportShareIntent.kt`, HC permission/security exceptions, `CustomColorPicker.kt`, `LogcatCaptureStoreImpl.kt`, `UserPreferencesMapper.kt`.
2. **Narrow the catch type** for overly broad catches: `HealthConnectRepositoryImpl.kt` (broad `Exception`), `DataSourceSettingsViewModel.kt`, `PhysiologySettingsViewModel.kt`, `UserUseCase.kt`.
3. **Refactor to use test assertions** for test files: `DatabaseKeyRotatorTest.kt` (use `assertThrows`), `DocumentationDriftTest.kt` (use `assertDoesNotThrow`).
4. **Review and decide** for ambiguous cases: `EncryptionManager.kt`, `LocalBackupManager.kt`, `SyncViewModel.kt`.

**Important:** The `CancellationException` rethrow rule is **Konsist-owned** (`CleanArchTest.kt`). Any catch block in a `suspend` function that catches `Exception` or `Throwable` **must** rethrow `CancellationException`. Verify this before narrowing or suppressing.

---

## `InvalidPackageDeclaration` (5 entries)

These files have a `package` declaration that doesn't match their directory path. The package alignment refactor already landed — these 5 survived because they are genuine mismatches that weren't part of the bulk rename.

| Module | File | Declared package | Decision needed |
|--------|------|-----------------|-----------------|
| `core/ui` | `DateSwitcher.kt` | `app.readylytics.health.core.ui.dashboard` | File is in `core/ui` root but declares a `dashboard` subpackage. **Move to `core/ui/dashboard/`** or fix the package. |
| `core/ui` | `HeightInputField.kt` | `app.readylytics.health.core.ui.settings` | Declares `settings` subpackage. **Move to `core/ui/settings/`** or fix. |
| `core/ui` | `UnitSystemSelector.kt` | `app.readylytics.health.core.ui.settings.common` | Declares `settings.common` subpackage. **Move to `core/ui/settings/common/`** or fix. |
| `core/scoring` | `SleepMetricsHelpersTest.kt` | `app.readylytics.health.core.scoring.domain.scoring.sleep` | Test declares `domain.scoring.sleep` subpackage — leftover from pre-alignment namespace. **Fix the package declaration** to match current directory. |
| `feature/settings` | `ValidatingTextField.kt` | `app.readylytics.health.feature.settings` | May be in a subdirectory but declares root. **Check actual path and align.** |

**Strategy:**
- For the `core/ui` files: these are UI components used from specific features. Moving them into subdirectories is clean. Create the directories, move the files, update imports across the codebase.
- For `SleepMetricsHelpersTest.kt`: fix the package declaration — it's a stale namespace.
- For `ValidatingTextField.kt`: check the actual file path first.

**Caution:** Moving files changes imports everywhere. Use IDE refactoring if possible, or grep all imports after moving. Run full `./gradlew detekt testDebugUnitTest` after.

---

## `ThrowsCount` (2 entries)

Functions with more throw/error sites than detekt allows (default threshold: 2).

| Module | File | Function |
|--------|------|----------|
| `core/healthconnect` | `HealthConnectRepositoryImpl.kt` | `readAllPagesStreaming` — generic paged HC reader with multiple error paths. |
| `app` | `SyncViewModel.kt` | `onAppForeground` — foreground sync trigger with permission/state checks. |

**Decision needed:**
- `readAllPagesStreaming`: Multiple throw sites may reflect genuinely different error conditions (rate limit, permission, IO). **Options:** (a) Extract precondition checks into a `validateReadState()` that throws, reducing throw count in the main function. (b) Suppress with rationale.
- `onAppForeground`: Multiple state checks each with their own failure mode. **Options:** Same as above.

---

## Execution plan

1. **Audit each `SwallowedException` entry** — read the catch block and surrounding context, categorize as "correct / narrow / refactor".
2. **Batch the suppressions** — add `@Suppress("SwallowedException")` with a one-line comment for each correct-by-design catch. One commit.
3. **Narrow the broad catches** — change `catch (e: Exception)` to specific types where possible. Separate commit per file.
4. **Fix `InvalidPackageDeclaration`** — move files or fix packages. One commit.
5. **Decide `ThrowsCount`** — suppress or extract preconditions. One commit.

---

## Constraints

All constraints from `DETEKT_BASELINE_BURNDOWN.md` §7 apply. Additionally:
- **CancellationException rethrow policy is Konsist-owned** — do not break it.
- `SwallowedException` suppressions must carry a rationale comment.
- File moves require import updates across the codebase and `codegraph sync`.
- Unit test count must not drop below 3,082.
- `./gradlew ktlintCheck detekt testDebugUnitTest` must pass.
- Run `./gradlew lintRelease` at the end.
