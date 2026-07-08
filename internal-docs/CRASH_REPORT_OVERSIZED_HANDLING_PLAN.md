# Follow-up plan: oversized crash reports in GitHub issue URLs

Status: **planned, not implemented.** This document captures a design for a
future change; no code in this repo implements it yet.

## Problem

`CrashReportShareIntent.kt` builds GitHub "new issue" deep links
(`github.com/readylytics/Readylytics/issues/new?...`) with the crash report
text embedded as a `body` query parameter. Browsers and GitHub reject URLs
above roughly 8000 characters, so the file caps the raw crash text at
`MAX_GITHUB_ISSUE_REPORT_LENGTH = 2000` chars before it's included, silently
dropping the rest. This heuristic is a guess: percent-encoding can expand
text 2-3x, and it doesn't account for the surrounding template/device-info
overhead, so some reports get truncated well before they'd actually need to
be. Two call sites do this today:

- `buildGithubIssueBody` — used by `CrashReportPrompt.kt`'s immediate
  post-crash dialog.
- `buildBugReportEmailBody` — used by the Settings "Report Bug or Crash"
  button, for both its email and GitHub-issue-link paths.

## Proposed design

1. **Measure the real URL length instead of guessing from raw text length.**
   Build the candidate `Uri` (title + full, untruncated body) and check
   `uri.toString().length` against a `GITHUB_ISSUE_URL_MAX_LENGTH = 8000`
   constant. Reports that are longer than 2000 raw characters but still
   encode under the real limit stop being truncated for no reason.

2. **When the full body's URL would exceed the limit, don't truncate inline
   — save the full report to a file instead**, and point the user at it from
   the issue body:
   - Let the user pick a save location via the Storage Access Framework:
     `ActivityResultContracts.CreateDocument("text/plain")`. This needs **no
     runtime storage permission at any API level** — SAF grants access via
     the system file picker's returned `content://` URI, so there's no
     legacy `WRITE_EXTERNAL_STORAGE` request to add even on this app's
     minSdk 26. This mirrors the SAF pattern already used for local backups:
     `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())`
     in `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/backup/LocalBackupSettings.kt`.
   - Write the crash text with
     `context.contentResolver.openOutputStream(uri)`, mirroring
     `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`'s
     backup-file write.
   - Open the GitHub issue intent with a body containing device info plus a
     note that crash details were saved to `<filename>` and asking the user
     to attach that file to the issue, instead of embedding truncated text.

3. **Architecture change:** "open GitHub issue" moves from a single
   synchronous `context.startActivity(...)` call to a two-step async flow
   (launch SAF picker → write file → open browser). This needs an
   `ActivityResultLauncher` at both call sites — `MainNavHost.kt` (the
   Settings "Report Bug or Crash" button) and `CrashReportPrompt.kt` (the
   immediate crash dialog) — following the same launcher pattern as
   `LocalBackupSettings.kt`.

## Scope

Apply to both existing GitHub-crash-report flows (`CrashReportPrompt.kt` and
the Settings "Report Bug or Crash" button), since both share the same
truncation heuristic and would benefit identically.

## Out of scope for this document

- No code changes are included with this plan — implementation is future
  work.
- Small/typical reports (the common case) are unaffected either way; this
  only changes behavior once a report's real encoded URL would exceed the
  browser/GitHub limit.
