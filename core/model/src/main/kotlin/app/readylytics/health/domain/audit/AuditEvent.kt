package app.readylytics.health.domain.audit

import java.time.Instant

data class AuditEvent(
    val id: Long = 0,
    val type: Type,
    val occurredAt: Instant,
    val detail: String?,
) {
    enum class Type(val storageKey: String) {
        BACKUP_CREATED("backup_created"),
        RESTORE_STARTED("restore_started"),
        RESTORE_COMPLETED("restore_completed"),
        RESTORE_FAILED("restore_failed"),
        KEY_CREATED("key_created"),
        KEY_ROTATED("key_rotated"),
        KEY_ROTATION_FAILED("key_rotation_failed"),
        APP_LOCK_ENABLED("app_lock_enabled"),
        APP_LOCK_DISABLED("app_lock_disabled"),
    }
}
