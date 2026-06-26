package app.readylytics.health.domain.audit

import org.junit.Test
import kotlin.test.assertEquals

class AuditEventTest {
    @Test
    fun backupRestoreAndKeyEventsAreStableForPersistence() {
        assertEquals("backup_created", AuditEvent.Type.BACKUP_CREATED.storageKey)
        assertEquals("restore_started", AuditEvent.Type.RESTORE_STARTED.storageKey)
        assertEquals("restore_completed", AuditEvent.Type.RESTORE_COMPLETED.storageKey)
        assertEquals("restore_failed", AuditEvent.Type.RESTORE_FAILED.storageKey)
        assertEquals("key_rotated", AuditEvent.Type.KEY_ROTATED.storageKey)
        assertEquals("key_rotation_failed", AuditEvent.Type.KEY_ROTATION_FAILED.storageKey)
    }
}
