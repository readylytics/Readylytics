package app.readylytics.health.domain.scoring.components

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditTrailTest {
    @Test
    fun `audit trail stores all required fields`() {
        val date = LocalDate.of(2026, 6, 1)
        val audit = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = date
        )
        assertEquals(12345, audit.configHashCode)
        assertEquals("PHASE_1", audit.phaseName)
        assertEquals(date, audit.appliedAt)
    }

    @Test
    fun `audit trail optional fields default to null`() {
        val audit = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now()
        )
        assertNull(audit.appliedSf)
        assertNull(audit.physiologyProfile)
        assertNull(audit.rasTotalPre)
        assertNull(audit.rasTotalPost)
    }

    @Test
    fun `audit trail stores optional appliedSf`() {
        val audit = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now(),
            appliedSf = 1.05f
        )
        assertEquals(1.05f, audit.appliedSf)
    }

    @Test
    fun `audit trail stores optional physiologyProfile`() {
        val audit = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now(),
            physiologyProfile = "ACTIVE"
        )
        assertEquals("ACTIVE", audit.physiologyProfile)
    }

    @Test
    fun `audit trail stores RAS totals`() {
        val audit = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now(),
            rasTotalPre = 50.0f,
            rasTotalPost = 55.0f
        )
        assertEquals(50.0f, audit.rasTotalPre)
        assertEquals(55.0f, audit.rasTotalPost)
    }

    @Test
    fun `audit trail can be created with all fields populated`() {
        val date = LocalDate.of(2026, 6, 1)
        val audit = AuditTrail(
            configHashCode = 99999,
            phaseName = "TEST_PHASE",
            appliedAt = date,
            appliedSf = 1.02f,
            physiologyProfile = "MODERATE",
            rasTotalPre = 45.0f,
            rasTotalPost = 52.0f
        )
        assertEquals(99999, audit.configHashCode)
        assertEquals("TEST_PHASE", audit.phaseName)
        assertEquals(date, audit.appliedAt)
        assertEquals(1.02f, audit.appliedSf)
        assertEquals("MODERATE", audit.physiologyProfile)
        assertEquals(45.0f, audit.rasTotalPre)
        assertEquals(52.0f, audit.rasTotalPost)
    }

    @Test
    fun `audit trail handles zero hash code`() {
        val audit = AuditTrail(
            configHashCode = 0,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now()
        )
        assertEquals(0, audit.configHashCode)
    }

    @Test
    fun `audit trail handles negative hash code`() {
        val audit = AuditTrail(
            configHashCode = -1,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.now()
        )
        assertEquals(-1, audit.configHashCode)
    }

    @Test
    fun `audit trail is a data class with copy support`() {
        val original = AuditTrail(
            configHashCode = 12345,
            phaseName = "PHASE_1",
            appliedAt = LocalDate.of(2026, 6, 1),
            appliedSf = 1.0f
        )
        val copy = original.copy(phaseName = "PHASE_2")
        assertEquals("PHASE_1", original.phaseName)
        assertEquals("PHASE_2", copy.phaseName)
        assertEquals(12345, copy.configHashCode)
    }
}
