# Crash Report Dialog: Segmented Channel Switch + Dedicated Send/Clear

## Overview
Restructure the crash report prompt shown on app start after an unexpected close. The current 3-button immediate-action dialog will be replaced with a segmented-switch channel picker (Email vs. GitHub) alongside dedicated "Send", "Clear", and "Not now" buttons. 

## State Management
- **`CrashReportViewModel`**: Add a `clearReport()` method. Both `clearReport()` and the existing `consumeReport()` will delegate to a shared private `hideAndDeleteReport()` method. This keeps the semantic actions distinct for analytics/tests while sharing the deletion behavior. 
- **`CrashReportPrompt`**: Introduce a `selectedChannel` state variable defaulting to `ReportChannel.EMAIL`.

## UI Components (`CrashReportPrompt`)
- **Main Dialog**: Standard Material 3 `AlertDialog`.
- **Text Slot**: Displays the existing body text, followed by a `Spacer`, followed by a `SingleChoiceSegmentedButtonRow`. The segmented row iterates over `ReportChannel.entries` to display "Email" and "GitHub Issue" buttons, bound to `selectedChannel`.
- **Confirm Button Slot**: A single "Send" button. When clicked, it evaluates `selectedChannel` and executes either the email or GitHub intent logic, then calls `consumeReport()`. The oversized GitHub report fallback remains unchanged.
- **Dismiss Button Slot**: A `FlowRow` containing two buttons:
  - **"Clear"**: Calls `clearReport()`, styled with `MaterialTheme.colorScheme.error`. Permanently deletes the crash log without sending.
  - **"Not now"**: Calls `dismiss()`. Hides the dialog for the current session but leaves the log on disk.
- **Dependencies**: Use `FlowRow` from `androidx.compose.foundation.layout.FlowRow` to ensure proper wrapping on narrow devices.

## Resource Updates (`strings.xml`)
- Add `crash_report_dialog_send` ("Send").
- Add `crash_report_dialog_clear` ("Clear").
- Update `crash_report_dialog_send_email` to just "Email".
- Update `crash_report_dialog_send_github` to just "GitHub Issue".

## Testing
- **`CrashReportViewModelTest`**: Add a `clearReportHidesPromptAndDeletesReport` test to verify that `clearReport()` properly updates the state flows and calls `delete()` on the store.
