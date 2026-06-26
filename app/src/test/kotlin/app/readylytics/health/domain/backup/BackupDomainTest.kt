package app.readylytics.health.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDomainTest {
    @Test
    fun backupLocationValidatesNotBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupLocation("")
        }
    }

    @Test
    fun backupLocationValidatesNotWhitespace() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupLocation("   ")
        }
    }

    @Test
    fun backupLocationAcceptsValidValue() {
        val location = BackupLocation("gs://bucket/backup.zip")
        assertEquals("gs://bucket/backup.zip", location.value)
    }

    @Test
    fun backupLocationToStringReturnsValue() {
        val location = BackupLocation("gs://bucket/backup.zip")
        assertEquals("gs://bucket/backup.zip", location.toString())
    }

    @Test
    fun backupFileRefCreation() {
        val location = BackupLocation("gs://bucket/backup.zip")
        val ref =
            BackupFileRef(
                name = "backup.zip",
                lastModified = 1234567890L,
                sizeBytes = 1024L,
                location = location,
            )

        assertEquals("backup.zip", ref.name)
        assertEquals(1234567890L, ref.lastModified)
        assertEquals(1024L, ref.sizeBytes)
        assertEquals(location, ref.location)
    }

    @Test
    fun backupFileRefEquality() {
        val location = BackupLocation("gs://bucket/backup.zip")
        val ref1 =
            BackupFileRef(
                name = "backup.zip",
                lastModified = 1234567890L,
                sizeBytes = 1024L,
                location = location,
            )
        val ref2 =
            BackupFileRef(
                name = "backup.zip",
                lastModified = 1234567890L,
                sizeBytes = 1024L,
                location = location,
            )

        assertEquals(ref1, ref2)
    }

    @Test
    fun restoreResultSuccessVariant() {
        val result: RestoreResult = RestoreResult.Success
        assertEquals(RestoreResult.Success, result)
    }

    @Test
    fun restoreResultSuccessRequiresRestartVariant() {
        val result: RestoreResult = RestoreResult.SuccessRequiresRestart
        assertEquals(RestoreResult.SuccessRequiresRestart, result)
    }

    @Test
    fun restoreResultFailureVariant() {
        val cause = RuntimeException("restore failed")
        val result: RestoreResult = RestoreResult.Failure(cause)

        val failure = result as RestoreResult.Failure
        assertEquals(cause, failure.cause)
    }

    @Test
    fun restoreResultFailureEquality() {
        val cause = RuntimeException("error")
        val failure1 = RestoreResult.Failure(cause)
        val failure2 = RestoreResult.Failure(cause)

        assertEquals(failure1, failure2)
    }
}
