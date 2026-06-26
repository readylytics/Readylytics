package app.readylytics.health.domain.audit

import org.junit.Test
import kotlin.test.assertEquals

class AuditEventTest {
    @Test
    fun allEventTypesAreStableForPersistence() {
        assertEquals(
            mapOf(
                AuditEvent.Type.BACKUP_CREATED to "backup_created",
                AuditEvent.Type.RESTORE_STARTED to "restore_started",
                AuditEvent.Type.RESTORE_COMPLETED to "restore_completed",
                AuditEvent.Type.RESTORE_FAILED to "restore_failed",
                AuditEvent.Type.KEY_CREATED to "key_created",
                AuditEvent.Type.KEY_ROTATED to "key_rotated",
                AuditEvent.Type.KEY_ROTATION_FAILED to "key_rotation_failed",
                AuditEvent.Type.APP_LOCK_ENABLED to "app_lock_enabled",
                AuditEvent.Type.APP_LOCK_DISABLED to "app_lock_disabled",
                AuditEvent.Type.UNKNOWN to "unknown",
            ),
            AuditEvent.Type.entries.associateWith { it.storageKey },
        )
    }
}
