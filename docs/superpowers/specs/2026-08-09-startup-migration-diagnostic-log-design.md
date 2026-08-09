# Startup Migration Diagnostic Log Design

**Status:** Approved design

## Goal

Give users blocked by a failed local database migration a direct way to send encrypted diagnostic logs without requiring database access or leaving the blocking recovery screen.

## Context

`MainActivity` renders `DatabaseMigrationScreen` before Room is ready. The screen currently offers retry for `DatabaseReadiness.Failed` and `DatabaseReadiness.InsufficientSpace`, but its failure text asks users to export diagnostics even though no export action exists in this startup flow.

The app already has the required logging primitives:

- `SecureFileLogSink.readLogsDecrypted()` flushes pending logs and reads the redacted diagnostic log.
- `buildLogFileShareIntent()` creates an Android chooser intent with a `FileProvider` attachment.
- Existing crash-report sharing demonstrates the app's attachment and chooser conventions.

The new path must remain safe before Room initialization and must not route through settings, crash-report state, Health Connect, or database-backed ViewModels.

## Design

### UI boundary

Add `onSendDiagnostics: () -> Unit` to `DatabaseMigrationScreen`. The screen invokes this callback from a Material 3 button labeled with a new string resource. The button is rendered for both `DatabaseReadiness.Failed` and `DatabaseReadiness.InsufficientSpace`, alongside `Retry`.

The migration screen remains blocking. Pressing the button does not dismiss or replace it; it launches the Android chooser over the current activity once the diagnostic attachment is ready.

### Activity integration

`MainActivity` owns the startup callback because it has an activity lifecycle, `lifecycleScope`, and access to the application-level log sink. It supplies the callback to both migration-screen branches. The callback:

1. Reads logs through `SecureFileLogSink.readLogsDecrypted()`, which flushes pending entries first.
2. Writes the result to a temporary text file in the app cache using the existing diagnostic-file naming convention.
3. Builds the existing `buildLogFileShareIntent()` and starts the chooser.

The implementation must not open Room or depend on any database-backed UI state. Temporary files are cache-scoped and may be overwritten or cleaned by normal cache eviction.

### Error handling

Log preparation and chooser launch run from the activity lifecycle scope. Failures are logged through the existing application logger and surfaced with a non-blocking user message; they must not crash the activity or remove the retry path. No new blocking dialog is introduced.

All visible copy, including the button label and any preparation-failure message, is defined in `app/src/main/res/values/strings.xml` and referenced with `stringResource`/`getString`.

### Data handling

The export contains the existing redacted diagnostic log only. It does not add health data, raw database contents, or a new telemetry channel. The existing `FileProvider` URI permission and chooser behavior are reused.

## Alternatives rejected

1. A migration-specific share implementation would duplicate attachment and chooser behavior.
2. Reusing the settings bug-report flow would couple pre-Room startup recovery to database-backed UI state and existing report configuration.

## Testing

- Compose UI test verifies the diagnostic button appears for `Failed` and `InsufficientSpace` states and invokes the callback.
- Unit test verifies the existing log-share intent remains an `ACTION_SEND` chooser with a text attachment URI.
- Activity/callback test verifies log preparation uses the sink, writes a temporary file, and launches the chooser; preparation failure leaves the screen usable.
- Existing migration controller and migration screen tests must continue to pass.

## Scope

This change is limited to startup migration recovery UI, activity wiring, temporary diagnostic-file preparation, strings, and focused tests. It does not alter migration behavior, database schema, logging redaction, or crash-report formats.
