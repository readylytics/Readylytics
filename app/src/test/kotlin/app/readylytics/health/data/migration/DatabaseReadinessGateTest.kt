package app.readylytics.health.data.migration

import app.readylytics.health.data.security.SqlCipherKeyManager.KeyDecryptionException
import app.readylytics.health.domain.migration.DatabaseReadiness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DatabaseReadinessGateTest {
    @Test
    fun `inspect returns KeyCorrupted when key decryption fails`() {
        val fakeDbFile = File.createTempFile("test", ".db").apply { writeText("dummy") }
        val gate =
            DatabaseReadinessGate(
                dbFile = fakeDbFile,
                inspectExistingDatabase = { throw KeyDecryptionException("test") },
            )

        assertEquals(DatabaseReadiness.KeyCorrupted, gate.inspect())

        fakeDbFile.delete()
    }

    @Test
    fun `inspect returns Failed for non-key exceptions`() {
        val fakeDbFile = File.createTempFile("test", ".db").apply { writeText("dummy") }
        val gate =
            DatabaseReadinessGate(
                dbFile = fakeDbFile,
                inspectExistingDatabase = { throw RuntimeException("disk error") },
            )

        assertEquals(
            DatabaseReadiness.Failed("disk error"),
            gate.inspect(),
        )

        fakeDbFile.delete()
    }

    @Test
    fun `inspect returns Ready when db file does not exist`() {
        val gate =
            DatabaseReadinessGate(
                dbFile = File("/nonexistent/path.db"),
                inspectExistingDatabase = { error("should not be called") },
            )

        assertEquals(DatabaseReadiness.Ready, gate.inspect())
    }
}
