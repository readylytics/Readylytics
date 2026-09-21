package app.readylytics.health.core.model.domain.backup

enum class RestorePhase {
    VALIDATED,
    DATABASE_COMMITTED,
    PREFERENCES_COMMITTED,
    CACHES_RESET,
    COMPLETE,
}
