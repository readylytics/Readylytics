# Onboarding: retention slider + loading-screen log visibility

## Context

A Pixel 9 Pro / Android 17 user reports onboarding is "still stuck" even after PR #144 (`44d03da`), which fixed three structural sync bugs (checkpoint mismatch, swallowed `CancellationException`, nav-state destruction). Investigation confirms none of those fixes add a timeout/watchdog around the actual Health Connect I/O — if the walk-forward recompute or an HC call genuinely stalls, `FinishingSetupScreen` (the onboarding loading screen) spins on an indeterminate `CircularProgressIndicator` forever with no escape hatch (the retry/skip screen only triggers on `SyncUiState.Error`, never on a stall). Rather than guess at a watchdog fix blind, the user wants to see what's actually happening: live log output plus a way to pull logs off the device for diagnosis. Separately, the user wants the same data-retention slider that exists in Settings surfaced during onboarding, defaulting to 12 months but adjustable down to 3 months, so the first historical sync doesn't default to a potentially very large backfill window on a slow/first-run device.

Three changes, same PR:
1. Add a retention slider step to onboarding (reusing Settings' slider logic/UI, default 12 months).
2. Add a live log panel + "download logs" button to the onboarding loading screen (`FinishingSetupScreen`).
3. Add a "Continue in background" button on that same loading screen so a stuck/slow sync no longer hard-blocks the user from using the app.

This does **not** attempt to add a sync watchdog/timeout — that's a separate, riskier change to the sync state machine and out of scope for what was asked. The log panel plus the background-continue button are what let the user (and us) work around a stall without waiting on a fix.

Decisions confirmed with the user: the onboarding retention step always keeps retention enabled (slider only, no unlimited/10-year toggle) and defaults to 12 months; "download logs" uses the existing system share-sheet pattern, not a direct save to Downloads.

## Part 1 — Retention slider in onboarding

**Extract the reusable slider out of Settings.**
`feature/settings/src/main/kotlin/.../data/DataSettings.kt` (`DataManagementSection`, lines ~154-224) currently inlines the retention Switch + Slider + description directly. `core/ui` already has an established precedent for this exact kind of extraction — `core/ui/src/main/kotlin/.../core/ui/components/settings/` holds shared field composables (`BirthdayDatePickerField`, `HeightInputField`, `PhysiologyProfilePicker`, `UnitSystemSelector`) used by both onboarding and settings. `feature/onboarding` and `feature/settings` both depend on `core:ui` transitively via `readylytics.compose-feature-conventions`, so this is the correct home.

- Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/settings/RetentionSlider.kt` with signature roughly:
  ```kotlin
  @Composable
  fun RetentionSlider(
      enabled: Boolean,
      retentionDays: Int,
      onEnabledChanged: (Boolean) -> Unit,
      onRetentionDaysChanged: (Int) -> Unit,
      modifier: Modifier = Modifier,
      showEnableToggle: Boolean = true,
  )
  ```
  Port the exact logic from `DataSettings.kt`: `retentionMonths` local float state derived via `remember(retentionDays) { round(retentionDays / 30f) }`, `Slider(valueRange = 3f..60f, steps = 18)`, commit only in `onValueChangeFinished` (→ `onRetentionDaysChanged(months.toInt() * 30)`), plural label via `pluralStringResource`. Keep bounds identical to `RetentionDaysRule` (90–1800 days / 3–60 months) — do not narrow the range; the user only asked to be able to reach 3 months, which is already the existing min.
  - `showEnableToggle` lets onboarding hide the enable/disable `Switch` if we decide retention should always be enabled during onboarding (recommended — see below) while settings keeps it.
- Move the four string resources this needs (`settings_retention_enabled_label`, `settings_retention_period_label`, `settings_retention_description`, plural `settings_retention_months`) from `feature/settings/src/main/res/values/strings.xml` into `core/ui/src/main/res/values/strings.xml`, and update `DataSettings.kt`'s call site to use the shared `RetentionSlider` composable instead of its inline block (deletes ~65 lines from `DataSettings.kt`, which is already at 460 lines — this also helps the file-size guidance in CLAUDE.md).

**New onboarding step.**
- Add a new screen/step in `feature/onboarding/src/main/kotlin/.../OnboardingScreen.kt`'s step state machine, inserted after `ProfileSetupScreen` (step 1) and before the Health Connect permission request currently triggered by "Grant Access". Renumber `RestoreBackupScreen` accordingly. New screen e.g. `RetentionSetupScreen` (put it in its own file, not squeezed into the already-471-line `OnboardingScreen.kt` — see file-size note below), hosting `RetentionSlider(enabled = true, retentionDays = ..., showEnableToggle = false, ...)` with a short heading/description ("How far back should we sync?") and a Continue button.
- Default value: `SettingsDefaults.RETENTION_DAYS` (360 = 12 months), retention enabled = true. This matches "defaulting to 12 months but user can slide to 3 months" — reusing the existing full 3–60 month range means the user can also go up, which is fine; the ask was specifically that going down to 3 months must be possible, which the existing bounds already guarantee.
- Persist the choice through the existing port: `OnboardingViewModel` (`feature/onboarding/.../OnboardingViewModel.kt`) already injects `private val displaySettings: DisplaySettings`, the exact interface `UISettingsViewModel` uses for `updateRetentionDaysEnabled`/`updateRetentionDays`. Add:
  ```kotlin
  fun saveRetention(retentionDays: Int, onComplete: () -> Unit) {
      viewModelScope.launch {
          displaySettings.updateRetentionDaysEnabled(true)
          displaySettings.updateRetentionDays(retentionDays)
          onComplete()
      }
  }
  ```
  Validate with `SettingsValidators.RETENTION_DAYS_RULE` first, mirroring `UISettingsViewModel` lines ~98-105.
- Wire the new step's Continue button through `OnboardingRoute.kt` so retention is persisted **before** the HC permission launcher fires (i.e. before `onPermissionsGranted()` → `HealthSyncUseCase.catchUpSync()` runs). `RetentionBounds.resolveResyncStartDate(prefs)` is read at sync time, so persisting first means the very first catch-up sync honors the onboarding selection immediately, not just future settings changes.

## Part 2 — Live log panel + download button on the loading screen

**What's already there (reuse, don't rebuild):**
- `LogcatCaptureStore` interface (`core/model/.../domain/logcat/LogcatCaptureStore.kt`): `suspend fun capture(durationMinutes: Int): String?` + `fun captureFile(): File`. Already a transitive dependency of `feature/onboarding` via `core:model`, and its impl (`LogcatCaptureStoreImpl`, bound in `:app`) is resolved by Hilt regardless of which gradle module injects it — `OnboardingViewModel` already does exactly this pattern with `DisplaySettings`/`PhysiologySettings`.
- Debug builds: `capture()` runs `logcat -d` directly. Release builds: reads from `SecureFileLogSink.readLogsDecrypted()` (encrypted rotating log). Both paths already work today via `LogcatCaptureViewModel` (used in Settings' `IssueReportDialog`).
- `FileProvider` (`${applicationId}.fileprovider`) + `file_paths.xml` (`logcat_capture/` cache-path) already configured in `AndroidManifest.xml`.
- `CrashReportShareIntent.kt` (`app/src/main/kotlin/.../crashreport/`) already has the exact `FileProvider.getUriForFile` + `Intent.ACTION_SEND` + `EXTRA_STREAM` + chooser pattern (`buildCrashReportShareIntent`).

**Live log text (new, small):**
- `LogcatCaptureViewModel` itself lives in `:app` (package `app.readylytics.health.ui.logcat`), which `feature/onboarding` cannot depend on (features don't depend on `:app`). So add a small new ViewModel inside `feature/onboarding`, e.g. `OnboardingLogViewModel`, injecting `LogcatCaptureStore` directly (same cross-module DI pattern already used by `OnboardingViewModel`):
  ```kotlin
  @HiltViewModel
  class OnboardingLogViewModel @Inject constructor(
      private val logcatCaptureStore: LogcatCaptureStore,
  ) : ViewModel() {
      private val _logText = MutableStateFlow<String?>(null)
      val logText: StateFlow<String?> = _logText.asStateFlow()

      fun startPolling() {
          viewModelScope.launch {
              while (isActive) {
                  _logText.value = logcatCaptureStore.capture(durationMinutes = 3)
                  delay(2000)
              }
          }
      }
  }
  ```
  Start polling only while `FinishingSetupScreen` is on screen (`LaunchedEffect(Unit) { viewModel.startPolling() }`), tail the last ~40 lines into a scrollable `Text`/`LazyColumn` inside a collapsible M3 `Card` (`surfaceContainerHigh`, `MaterialTheme.shapes.large`, per CLAUDE.md styling rules), auto-scrolled to the bottom on update. Default collapsed with a "Show log output" toggle so the loading screen isn't cluttered when things are working normally.
- Since `OnboardingScreen.kt` is already 471 lines, put `FinishingSetupScreen` (plus this new log panel) in its own new file, e.g. `FinishingSetupScreen.kt`, rather than growing the existing file further.

**Download button (reuse the existing share pattern, wired from `:app` where that pattern already lives):**
- `AppNavHost.kt` already hoists `logcatCaptureViewModel: LogcatCaptureViewModel = hiltViewModel()` and already passes an `onReportIssue` callback into `OnboardingRoute` for the error path only (`SyncErrorScreen`). Mirror that: add `onDownloadLogs: () -> Unit = {}` to `OnboardingRoute`'s params, threaded down to `FinishingSetupScreen`'s new download button, wired in `AppNavHost.kt` as:
  ```kotlin
  onDownloadLogs = {
      scope.launch {
          val file = logcatCaptureViewModel.captureFile()
          context.startActivity(buildLogFileShareIntent(context, file))
      }
  }
  ```
- Add a small new function alongside `buildCrashReportShareIntent` in `CrashReportShareIntent.kt`, e.g. `buildLogFileShareIntent(context, file)`, using generic `ACTION_SEND` + `type = "text/plain"` (not the email-specific `message/rfc822` used by crash reports) so the system share sheet offers Drive/Files/email/etc. — this is the standard Android "download/export" affordance and matches the app's existing crash-report UX exactly.
- If `capture()`/`captureFile()` yields no content (e.g. logcat inaccessible on some OEM), show a short inline message ("No logs available yet") instead of launching an empty share sheet.

## Part 3 — "Continue in background" button on the loading screen

**Confirmed feasible and low-risk by reading `AppNavHost.kt` directly:**
- `SyncViewModel` is instantiated at the top of `AppNavHost` (`viewModel: SyncViewModel = hiltViewModel()`, line 46) — i.e. scoped above both the `MainShell` and `Onboarding` `composable<>` destinations, not inside either one's back-stack entry. So the underlying sync coroutine already survives navigating between those two destinations; nothing in the sync layer needs to change.
- The only thing currently preventing this is a single explicit gate in the `LaunchedEffect(uiState, userPrefs)` block (`AppNavHost.kt` line 95): `SyncUiState.SyncingCatchUp -> Unit // Gated`. No navigation happens while syncing — that's the whole block.
- `MainScaffold.kt` (lines 54-55, 148-149) already collects `syncViewModel.isSyncing`/`syncViewModel.recalcProgress` and renders `RecalcProgressBanner` whenever `recalcProgress.total > 0` — the **same** shared state onboarding's `FinishingSetupScreen` reads. So once navigation to `MainShell` is allowed mid-sync, progress is automatically visible there with no new banner to build.
- `DashboardViewModel.kt` already has `_errorMessage`/`errorMessage` with `R.string.error_sync_failed` (line ~311) for a failed sync, so a failure occurring after the user has backgrounded onboarding isn't silently lost either.

**Implementation:**
- Add a button to `FinishingSetupScreen` (new file, see Part 2), e.g. "Continue in background" / "Use app now", wired via a new `onContinueInBackground: () -> Unit` callback threaded through `OnboardingRoute` up to the `composable<AppDestination.Onboarding>` block in `AppNavHost.kt`.
- In `AppNavHost.kt`, add `var syncBackgrounded by rememberSaveable { mutableStateOf(false) }` and change the gate:
  ```kotlin
  SyncUiState.SyncingCatchUp -> {
      if (syncBackgrounded && currentDest?.hasRoute<AppDestination.MainShell>() != true) {
          navController.navigate(AppDestination.MainShell) {
              popUpTo(AppDestination.Onboarding) { inclusive = true; saveState = true }
              restoreState = true
          }
      }
  }
  ```
  (mirrors the existing `PermissionsGranted` branch exactly). The new callback sets `syncBackgrounded = true`.
- Only show the button once an actual sync is in progress with real permissions granted (i.e. on `FinishingSetupScreen`, not on `PermissionsRequiredScreen`/`SyncErrorScreen`) — those already have their own explicit actions (grant/retry/skip).
- No changes needed in `MainScaffold`/`DashboardViewModel` — both already react correctly to the shared sync state.

## New strings

Add to `feature/onboarding/src/main/res/values/strings.xml`: retention step heading/description/continue button, `onboarding_view_logs` / `onboarding_hide_logs`, `onboarding_download_logs`, `onboarding_logs_empty`. Add the four moved retention strings + plural to `core/ui/src/main/res/values/strings.xml` (delete from `feature/settings/strings.xml`).

## Verification

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease` at the end.
- Manual, via `/run` or `installDebug` on emulator/device:
  - Fresh install → onboarding → confirm new retention step appears after profile setup, default shows "12 months", drag down to "3 months" and back, confirm it persists (check DataStore-backed value, e.g. via Settings screen after onboarding completes — should show the same value).
  - Confirm the first catch-up sync's fetched date range reflects the chosen retention (e.g. via the log panel / logcat, `RetentionBounds.resolveResyncStartDate` should resolve to `today - retentionDays`).
  - On the loading screen, expand "Show log output", confirm it updates roughly every ~2s with real log lines (works in debug via `logcat -d`); tap "Download logs" and confirm a share sheet opens with a non-empty attached file.
  - Tap "Continue in background" mid-sync: confirm the app navigates to `MainShell`/dashboard immediately, `RecalcProgressBanner` shows the same in-flight progress, and the sync completes normally in the background (verify final data matches what a normal blocking onboarding sync would produce).
  - Confirm `DataSettings.kt` in Settings still renders/functions identically after extracting `RetentionSlider` into `core/ui` (no behavior change there).
- No test coverage exists for Compose UI screens per current repo conventions (unit tests are for pure Kotlin domain logic) — this is a UI-only change plus a straightforward DataStore write reusing an already-tested port, so no new unit tests are expected beyond existing `RetentionDaysRule`/`RetentionBounds` coverage.
