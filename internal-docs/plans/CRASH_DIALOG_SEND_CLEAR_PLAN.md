# Crash Report Dialog: Segmented Channel Switch + Dedicated Send/Clear

## Context

The crash-report prompt shown on app start after an unexpected close currently uses a
3-button `AlertDialog` where each button fires an action immediately on tap: "Email
(private)" (confirmButton), and a `Row` of "GitHub Issue (public)" + "Not now"
(dismissButton). There is no explicit "choose channel, then send" step, and no way to
permanently discard a crash log without also sending it — "Not now" only hides the
dialog for the current app session; the log file stays on disk and the dialog reappears
on the next launch.

Goal: restructure the dialog to match the segmented-switch pattern already used
elsewhere in the app (e.g. strain load source picker in Settings, and the Email/GitHub
channel picker in the Settings bug-report dialog), and add a dedicated **Send** button
(acts on whichever channel is currently selected) and a dedicated **Clear** button
(deletes the crash log immediately, no confirmation, no report sent — so the dialog
stops reappearing).

Confirmed product decisions:
- **Clear** deletes the log and closes the dialog immediately. No confirmation step.
- **Email is pre-selected** by default in the segmented switch. **Send is always
  enabled** (no disabled state needed — a channel is always selected).
- **"Not now" is kept** as a third action, distinct from Clear: "Not now" is a
  session-only dismiss (log persists, dialog reappears next launch); "Clear" is a
  permanent discard (log deleted now). These are not redundant — do not merge them.

## Current code (before this change)

### `app/src/main/kotlin/app/readylytics/health/ui/crashreport/CrashReportPrompt.kt`

```kotlin
package app.readylytics.health.ui.crashreport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.crashreport.CrashReportFileExport
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildCrashReportShareIntent
import app.readylytics.health.crashreport.buildGithubIssueIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent

@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel = hiltViewModel()) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingOversized by remember { mutableStateOf<GithubIssueIntentResult.Oversized?>(null) }
    var showOversizedDialog by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val oversized = pendingOversized
            pendingOversized = null
            if (uri == null || oversized == null) return@rememberLauncherForActivityResult
            val filename =
                CrashReportFileExport
                    .writeReport(context, uri, oversized.fullReport)
                    .getOrElse { oversized.suggestedFilename }
            context.startActivity(buildOversizedFallbackIntent(context, oversized, filename))
            viewModel.consumeReport()
        }

    if (showPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.crash_report_dialog_title)) },
            text = { Text(stringResource(R.string.crash_report_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(buildCrashReportShareIntent(context, viewModel.reportFile()))
                    viewModel.consumeReport()
                }) {
                    Text(stringResource(R.string.crash_report_dialog_send_email))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        when (val result = buildGithubIssueIntent(context, viewModel.reportText())) {
                            is GithubIssueIntentResult.Ready -> {
                                context.startActivity(result.intent)
                                viewModel.consumeReport()
                            }
                            is GithubIssueIntentResult.Oversized -> {
                                pendingOversized = result
                                showOversizedDialog = true
                            }
                        }
                    }) {
                        Text(stringResource(R.string.crash_report_dialog_send_github))
                    }
                    TextButton(onClick = viewModel::dismiss) {
                        Text(stringResource(R.string.crash_report_dialog_dismiss))
                    }
                }
            },
        )
    }

    OversizedReportDialog(
        isShown = showOversizedDialog,
        onDismiss = {
            showOversizedDialog = false
            pendingOversized = null
        },
        onSaveFile = { filename ->
            showOversizedDialog = false
            saveLauncher.launch(filename)
        },
        suggestedFilename = pendingOversized?.suggestedFilename ?: "",
    )
}
```

### `app/src/main/kotlin/app/readylytics/health/ui/crashreport/CrashReportViewModel.kt`

```kotlin
package app.readylytics.health.ui.crashreport

import androidx.lifecycle.ViewModel
import app.readylytics.health.domain.crashreport.CrashReportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CrashReportViewModel
    @Inject
    constructor(
        private val crashReportStore: CrashReportStore,
    ) : ViewModel() {
        private val _showPrompt = MutableStateFlow(crashReportStore.hasReport())
        val showPrompt: StateFlow<Boolean> = _showPrompt.asStateFlow()

        private val _hasReport = MutableStateFlow(crashReportStore.hasReport())
        val hasReport: StateFlow<Boolean> = _hasReport.asStateFlow()

        fun reportFile(): File = crashReportStore.reportFile()

        fun reportText(): String = crashReportStore.read().orEmpty()

        fun dismiss() {
            _showPrompt.value = false
        }

        fun consumeReport() {
            crashReportStore.delete()
            _showPrompt.value = false
            _hasReport.value = false
        }
    }
```

### `app/src/main/res/values/strings.xml` (relevant section, ~lines 58-69)

```xml
<string name="crash_report_email_address">readylytics@gmail.com</string>
<string name="crash_report_title">Readylytics crash report</string>
<string name="crash_report_email_body">A crash report is attached. Feel free to add any details
    about what you were doing when the app crashed.</string>
<string name="crash_report_chooser_title">Send crash report</string>
<string name="crash_report_dialog_title">Readylytics closed unexpectedly</string>
<string name="crash_report_dialog_body">Send a report to help fix it? It only contains the
    error details and basic device info, never your health data. Email is private; a GitHub
    issue is posted publicly.</string>
<string name="crash_report_dialog_send_email">Email (private)</string>
<string name="crash_report_dialog_send_github">GitHub Issue (public)</string>
<string name="crash_report_dialog_dismiss">Not now</string>
```

### `app/src/test/kotlin/app/readylytics/health/ui/crashreport/CrashReportViewModelTest.kt`

```kotlin
package app.readylytics.health.ui.crashreport

import app.readylytics.health.domain.crashreport.CrashReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashReportViewModelTest {
    @Test
    fun showPromptReflectsHasReportAtConstructionTime() {
        val withReport = CrashReportViewModel(FakeCrashReportStore(hasReport = true))
        val withoutReport = CrashReportViewModel(FakeCrashReportStore(hasReport = false))

        assertTrue(withReport.showPrompt.value)
        assertTrue(withReport.hasReport.value)
        assertFalse(withoutReport.showPrompt.value)
        assertFalse(withoutReport.hasReport.value)
    }

    @Test
    fun dismissHidesPromptButKeepsReport() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportViewModel(store)

        viewModel.dismiss()

        assertFalse(viewModel.showPrompt.value)
        assertTrue(viewModel.hasReport.value)
        assertEquals(0, store.deleteCallCount)
    }

    @Test
    fun consumeReportHidesPromptAndDeletesReport() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportViewModel(store)

        viewModel.consumeReport()

        assertFalse(viewModel.showPrompt.value)
        assertFalse(viewModel.hasReport.value)
        assertEquals(1, store.deleteCallCount)
    }

    private class FakeCrashReportStore(
        private var hasReport: Boolean,
    ) : CrashReportStore {
        var deleteCallCount = 0
            private set

        override fun hasReport(): Boolean = hasReport

        override fun write(report: String) {
            hasReport = true
        }

        override fun read(): String? = null

        override fun delete() {
            deleteCallCount++
            hasReport = false
        }

        override fun reportFile(): File = File("dummy")
    }
}
```

### Reused enum: `core/model/src/main/kotlin/app/readylytics/health/domain/githubissue/ReportChannel.kt`

```kotlin
package app.readylytics.health.domain.githubissue

enum class ReportChannel {
    EMAIL,
    GITHUB,
}
```

`app` module already depends on `:core:model`, so this enum can be imported directly
into `CrashReportPrompt.kt` — do not create a duplicate enum.

### Reference pattern: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/IssueReportDialog.kt`

This is the Settings "report a bug / request a feature" dialog. It already implements
the exact same Email-vs-GitHub choice via a segmented switch, followed by a single
submit button. Use this as the structural template (excerpt, not to be modified):

```kotlin
var selectedChannel by remember(reportType) { mutableStateOf(ReportChannel.EMAIL) }
...
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    ReportChannel.entries.forEachIndexed { index, channel ->
        SegmentedButton(
            selected = selectedChannel == channel,
            onClick = { selectedChannel = channel },
            shape =
                SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ReportChannel.entries.size,
                ),
            label = {
                Text(
                    text =
                        when (channel) {
                            ReportChannel.EMAIL ->
                                stringResource(R.string.settings_issue_dialog_send_email)
                            ReportChannel.GITHUB ->
                                stringResource(R.string.settings_issue_dialog_send_github)
                        },
                )
            },
        )
    }
}
```

The same `SingleChoiceSegmentedButtonRow` + `SegmentedButton` +
`SegmentedButtonDefaults.itemShape(index, count)` structure is also used in
`LoadSourcePicker` in
`feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/LoadSourcesSettings.kt`
(the strain load source switch) — the canonical segmented-switch example in this
codebase.

## Required changes

### 1. `CrashReportViewModel.kt` — add `clearReport()`

Add a `clearReport()` method next to `consumeReport()`. Both delegate to a shared
private helper so "sent" vs. "cleared" remain distinct call sites (useful for tests and
any future divergence, e.g. analytics), while sharing the delete+hide implementation:

```kotlin
fun consumeReport() = hideAndDeleteReport()

fun clearReport() = hideAndDeleteReport()

private fun hideAndDeleteReport() {
    crashReportStore.delete()
    _showPrompt.value = false
    _hasReport.value = false
}
```

`dismiss()` stays exactly as it is today — still backs "Not now" (session-only hide, no
delete).

### 2. `strings.xml` — repurpose two existing keys, add two new ones

In `app/src/main/res/values/strings.xml`, in the block starting at
`crash_report_dialog_title`:

| Key | Old value | New value | Notes |
|---|---|---|---|
| `crash_report_dialog_send_email` | `Email (private)` | `Email` | repurposed from a full button label to a segmented-button label; the privacy nuance already lives in `crash_report_dialog_body`, so it doesn't need repeating here |
| `crash_report_dialog_send_github` | `GitHub Issue (public)` | `GitHub Issue` | same repurposing |
| `crash_report_dialog_dismiss` | `Not now` | *(unchanged)* | still used for the "Not now" action |
| `crash_report_dialog_title` | — | *(unchanged)* | |
| `crash_report_dialog_body` | — | *(unchanged)* | |
| `crash_report_dialog_send` | *(new)* | `Send` | new confirmButton label |
| `crash_report_dialog_clear` | *(new)* | `Clear` | new destructive-action label |

Do **not** reuse Settings' `settings_issue_dialog_send_email` /
`settings_issue_dialog_send_github` strings — those belong to a distinct, separately
owned flow (the manual bug/feature-report dialog in Settings). Sharing keys would let
unrelated wording changes in that flow silently affect this one. Keep `crash_report_*`
as its own local, appropriately-scoped set of keys, per this codebase's existing
per-flow string-prefix convention.

### 3. `CrashReportPrompt.kt` — new dialog structure

Target new imports (add to the existing import block):

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.githubissue.ReportChannel
```

(`Row`, `AlertDialog`, `Text`, `TextButton`, the activity-result launcher imports, and
the `crashreport` intent-builder imports already present in the file are unchanged and
stay.)

Add selection state next to the existing `pendingOversized`/`showOversizedDialog`
`remember` blocks:

```kotlin
var selectedChannel by remember { mutableStateOf(ReportChannel.EMAIL) }
```

(No `remember(key)` needed — unlike `IssueReportDialog`, this composable has no varying
input parameter; it's mounted once globally. Defaults to `EMAIL`, satisfying "Email
pre-selected".)

New `text` slot (adds the segmented row below the existing body copy):

```kotlin
text = {
    Column {
        Text(stringResource(R.string.crash_report_dialog_body))
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReportChannel.entries.forEachIndexed { index, channel ->
                SegmentedButton(
                    selected = selectedChannel == channel,
                    onClick = { selectedChannel = channel },
                    shape = SegmentedButtonDefaults.itemShape(index, ReportChannel.entries.size),
                    label = {
                        Text(
                            when (channel) {
                                ReportChannel.EMAIL -> stringResource(R.string.crash_report_dialog_send_email)
                                ReportChannel.GITHUB -> stringResource(R.string.crash_report_dialog_send_github)
                            },
                        )
                    },
                )
            }
        }
    }
}
```

New `confirmButton` slot (single "Send" that dispatches by `selectedChannel` — this is
a straight port of the existing Email-button and GitHub-button logic, just gated behind
the segmented selection instead of being two separate always-visible buttons; the
`Oversized` fallback path is untouched):

```kotlin
confirmButton = {
    TextButton(onClick = {
        when (selectedChannel) {
            ReportChannel.EMAIL -> {
                context.startActivity(buildCrashReportShareIntent(context, viewModel.reportFile()))
                viewModel.consumeReport()
            }
            ReportChannel.GITHUB -> {
                when (val result = buildGithubIssueIntent(context, viewModel.reportText())) {
                    is GithubIssueIntentResult.Ready -> {
                        context.startActivity(result.intent)
                        viewModel.consumeReport()
                    }
                    is GithubIssueIntentResult.Oversized -> {
                        pendingOversized = result
                        showOversizedDialog = true
                    }
                }
            }
        }
    }) {
        Text(stringResource(R.string.crash_report_dialog_send))
    }
}
```

New `dismissButton` slot (Row with Clear + Not now — same structural shape the file
already uses today, just with the destructive action added):

```kotlin
dismissButton = {
    Row {
        TextButton(
            onClick = viewModel::clearReport,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.crash_report_dialog_clear))
        }
        TextButton(onClick = viewModel::dismiss) {
            Text(stringResource(R.string.crash_report_dialog_dismiss))
        }
    }
}
```

This renders left-to-right as **Clear, Not now, Send** (M3 `AlertDialog` renders
`dismissButton` before `confirmButton`), mirroring the existing button adjacency
(previously GitHub, Not now, Email) — lowest-risk structural change. The destructive
"Clear" action uses the M3 `colorScheme.error` semantic role (not a hardcoded color,
per this codebase's M3 rules) and sits furthest from the primary "Send" action.

Everything below the `AlertDialog` block — the `saveLauncher`,
`pendingOversized`/`showOversizedDialog` state, and the `OversizedReportDialog(...)`
call — is **unchanged**. It remains reachable from Send when GitHub is selected and the
report body is oversized.

Expected resulting file size: ~140-150 lines (current file is 95 lines) — well within
the project's 400-line target / 800-line hard limit per `.claude/CLAUDE.md`.

### 4. `CrashReportViewModelTest.kt` — add a test for `clearReport()`

Add, mirroring the existing `consumeReportHidesPromptAndDeletesReport()` test:

```kotlin
@Test
fun clearReportHidesPromptAndDeletesReport() {
    val store = FakeCrashReportStore(hasReport = true)
    val viewModel = CrashReportViewModel(store)

    viewModel.clearReport()

    assertFalse(viewModel.showPrompt.value)
    assertFalse(viewModel.hasReport.value)
    assertEquals(1, store.deleteCallCount)
}
```

## Not changed

- `CrashReportStore` / `CrashReportStoreImpl` (`core/model`, `app`) — `delete()`
  already exists and is reused as-is; no interface changes needed.
- `OversizedReportDialog.kt` — no changes.
- `MainActivity.kt` mount point — `CrashReportPrompt()` call (currently at
  `MainActivity.kt:211`, right after `AppNavHost(...)`) is unchanged.
- `IssueReportDialog.kt` and `LoadSourcesSettings.kt` — reference patterns only, not
  modified.
- `CrashReportShareIntent.kt` (`buildCrashReportShareIntent`, `buildGithubIssueIntent`,
  `GithubIssueIntentResult`) — reused as-is, no changes.

## Why "Not now" is kept (not merged into "Clear")

"Not now" and "Clear" have different persistence semantics and are not redundant:
- **Not now** = defer the decision. Log stays on disk. Dialog reappears next app
  launch. (Today's `dismiss()` behavior — unchanged.)
- **Clear** = permanently discard the log right now. No report sent. Dialog gone until
  the next crash. (New `clearReport()` — no confirmation step, per product decision.)

Dropping "Not now" in favor of only "Clear" would silently change existing behavior
that some users may rely on (deciding later without losing the only crash artifact).
Since Clear is irreversible and has no confirmation step, conflating it with a soft
dismiss would be surprising. The vertical-space concern is minor: the `dismissButton`
slot already renders a `Row` with 2 buttons today, so adding a 3rd `TextButton` widens
the row rather than adding a new row of buttons; button labels are kept short (1-2
words) to fit.

## Verification steps

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest` — must include the new
   `clearReportHidesPromptAndDeletesReport` test passing.
3. `./gradlew lintRelease` (run after resolving all coding tasks, per project
   convention).
4. Manual/device check via `./gradlew installDebug`:
   - Trigger a crash (or use an existing debug crash-trigger path if one exists), then
     relaunch the app.
   - Dialog shows the segmented switch defaulting to "Email" selected.
   - Select "GitHub Issue", tap "Send" → opens the GitHub new-issue intent; log is
     cleared afterward (confirm dialog doesn't reappear on next relaunch).
   - Re-crash, tap "Send" with "Email" selected → opens the email share intent; log
     cleared afterward.
   - Re-crash, tap "Clear" → dialog closes immediately, no intent launched; relaunch
     the app and confirm the dialog does **not** reappear (log was deleted).
   - Re-crash, tap "Not now" → dialog closes; relaunch the app and confirm the dialog
     **does** reappear (log was NOT deleted).
   - If there's a way to force an oversized crash report, select GitHub + tap Send and
     confirm the `OversizedReportDialog` save-to-file fallback still triggers
     correctly.

## Files touched (summary)

- `app/src/main/kotlin/app/readylytics/health/ui/crashreport/CrashReportPrompt.kt`
- `app/src/main/kotlin/app/readylytics/health/ui/crashreport/CrashReportViewModel.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/app/readylytics/health/ui/crashreport/CrashReportViewModelTest.kt`
