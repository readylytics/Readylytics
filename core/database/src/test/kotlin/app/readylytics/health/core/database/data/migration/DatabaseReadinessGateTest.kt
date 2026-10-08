package app.readylytics.health.core.database.data.migration

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager.KeyDecryptionException
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DatabaseReadinessGateTest {
    @Test
    fun plaintextHeaderRequiresEncryptionWithoutKeyAccess() {
        val file = File.createTempFile("plaintext", ".db")
        try {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            val gate = DatabaseReadinessGate(file) { error("must not resolve key") }
            assertEquals(DatabaseReadiness.Checking, gate.readiness.value)
            assertEquals(DatabaseReadiness.EncryptionRequired, gate.inspect())
            assertEquals(DatabaseReadiness.EncryptionRequired, gate.readiness.value)
            gate.invalidate()
            assertEquals(DatabaseReadiness.Checking, gate.readiness.value)
        } finally {
            file.delete()
        }
    }

    @Test
    fun shortAndNonmatchingHeadersUseEncryptedInspection() {
        listOf("SQLite format 3", "encrypted-header").forEach { header ->
            val file = File.createTempFile("header", ".db")
            try {
                file.writeText(header)
                val gate = DatabaseReadinessGate(file) { ExistingDatabaseState(23, false) }
                assertEquals(DatabaseReadiness.Ready, gate.inspect())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun unreadableHeaderPublishesInspectionFailureWithoutOpeningCipher() {
        val directory = kotlin.io.path.createTempDirectory("unreadable-db").toFile()
        try {
            val gate = DatabaseReadinessGate(directory) { error("must not resolve key") }
            kotlin.test.assertIs<DatabaseReadiness.Failed>(gate.inspect())
            kotlin.test.assertIs<DatabaseReadiness.Failed>(gate.readiness.value)
        } finally {
            directory.delete()
        }
    }

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
        // Deliberately generic: this test verifies the fallback path taken for any
        // exception that is not KeyDecryptionException, so the exact type must not matter.
        val fakeDbFile = File.createTempFile("test", ".db").apply { writeText("dummy") }
        val gate =
            DatabaseReadinessGate(
                dbFile = fakeDbFile,
                inspectExistingDatabase = { throw IllegalStateException("disk error") },
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

    // The gate must never be stricter than the schema the app actually ships. When these drift, a
    // database Room just migrated forward reads back as "unsupported" and every launch lands on the
    // migration failure screen with the database unopenable.
    @Test
    fun `gate tracks the shipped database version`() {
        assertEquals(
            HealthDatabase.DATABASE_VERSION,
            DatabaseReadinessGate.CURRENT_DATABASE_VERSION,
        )
    }

    @Test
    fun `inspect returns Ready for every Room-managed version up to the current one`() {
        val roomManaged = DatabaseReadinessGate.ROOM_MANAGED_MIN_VERSION..HealthDatabase.DATABASE_VERSION

        for (userVersion in roomManaged) {
            assertEquals(
                DatabaseReadiness.Ready,
                gateReporting(userVersion).inspect(),
                "user_version $userVersion should be openable by Room",
            )
        }
    }

    @Test
    fun `inspect requires the external migration for pre-v7 versions`() {
        assertEquals(DatabaseReadiness.MigrationRequired(5), gateReporting(5).inspect())
        assertEquals(DatabaseReadiness.MigrationRequired(6), gateReporting(6).inspect())
    }

    @Test
    fun `inspect fails for a version newer than the shipped schema`() {
        val futureVersion = HealthDatabase.DATABASE_VERSION + 1

        assertEquals(
            DatabaseReadiness.Failed("Unsupported database version: $futureVersion"),
            gateReporting(futureVersion).inspect(),
        )
    }

    private fun gateReporting(userVersion: Int): DatabaseReadinessGate {
        val fakeDbFile = File.createTempFile("test", ".db").apply { writeText("dummy") }
        fakeDbFile.deleteOnExit()
        return DatabaseReadinessGate(
            dbFile = fakeDbFile,
            inspectExistingDatabase = {
                ExistingDatabaseState(userVersion = userVersion, hasMigrationMetadata = true)
            },
        )
    }
}
