package app.readylytics.health.domain.repository

/**
 * Diagnostic-only WAL growth measurement for logcat instrumentation of sync WAL growth (Phase 1
 * heavy-data-sync measurement -- see `.omc/plans/heavy-data-sync-phase1.md` §2). Scoped to this
 * one diagnostic only, so the pure-domain layer stays free of SQLite-specific concerns while still
 * being able to observe them; the storage-engine-specific implementation lives in `core/database`.
 */
interface WalDiagnostics {
    /** Size of the database's `-wal` file, or a diagnostic string if it can't be measured. */
    fun walFileSizeInfo(): String
}
