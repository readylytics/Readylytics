package app.readylytics.health.data.migration

import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.V7MigrationPhase
import app.readylytics.health.domain.migration.fraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseMigrationModelsTest {
    @Test
    fun `progress fractions are monotonic across every phase`() {
        val fractions =
            V7MigrationPhase.entries.flatMap { phase ->
                listOf(
                    DatabaseMigrationProgress(phase, copiedRows = 0, totalRows = 10).fraction(),
                    DatabaseMigrationProgress(phase, copiedRows = 5, totalRows = 10).fraction(),
                    DatabaseMigrationProgress(phase, copiedRows = 10, totalRows = 10).fraction(),
                )
            }

        fractions.zipWithNext { current, next ->
            assertTrue("$current must not exceed $next", current <= next)
        }
    }

    @Test
    fun `progress fraction clamps copied rows to the valid range`() {
        val phase = V7MigrationPhase.COPY_HEART_RATE

        assertEquals(
            DatabaseMigrationProgress(phase, copiedRows = 0, totalRows = 10).fraction(),
            DatabaseMigrationProgress(phase, copiedRows = -1, totalRows = 10).fraction(),
            0f,
        )
        assertEquals(
            DatabaseMigrationProgress(phase, copiedRows = 10, totalRows = 10).fraction(),
            DatabaseMigrationProgress(phase, copiedRows = 11, totalRows = 10).fraction(),
            0f,
        )
    }

    @Test
    fun `progress fraction stays within zero and one`() {
        val fractions =
            V7MigrationPhase.entries.flatMap { phase ->
                listOf(
                    DatabaseMigrationProgress(phase, Long.MIN_VALUE, -1).fraction(),
                    DatabaseMigrationProgress(phase, Long.MAX_VALUE, 1).fraction(),
                )
            }

        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(1f, DatabaseMigrationProgress(V7MigrationPhase.COMPLETE, 0, 0).fraction(), 0f)
    }

    @Test
    fun `missing database is ready without inspection`() {
        var inspected = false
        val gate =
            DatabaseReadinessGate(
                dbFile = File("missing-${System.nanoTime()}.db"),
                inspectExistingDatabase = {
                    inspected = true
                    ExistingDatabaseState(userVersion = 6, hasMigrationMetadata = false)
                },
            )

        assertEquals(DatabaseReadiness.Ready, gate.inspect())
        assertFalse(inspected)
    }

    @Test
    fun `supported database versions require external migration`() {
        assertEquals(DatabaseReadiness.MigrationRequired(5), inspect(version = 5))
        assertEquals(DatabaseReadiness.MigrationRequired(6), inspect(version = 6))
    }

    @Test
    fun `current database is ready`() {
        assertEquals(DatabaseReadiness.Ready, inspect(version = 7))
    }

    @Test
    fun `migration metadata does not make unsupported versions resumable`() {
        listOf(0, 8).forEach { version ->
            assertEquals(
                DatabaseReadiness.Failed("Unsupported database version: $version"),
                inspect(version = version, hasMigrationMetadata = true),
            )
        }
    }

    @Test
    fun `unsupported database version fails closed`() {
        assertEquals(
            DatabaseReadiness.Failed("Unsupported database version: 8"),
            inspect(version = 8),
        )
    }

    @Test
    fun `inspection failure preserves a diagnostic message`() {
        val file =
            kotlin.io.path
                .createTempFile()
                .toFile()
        val gate =
            DatabaseReadinessGate(
                dbFile = file,
                inspectExistingDatabase = { error("cannot decrypt") },
            )

        assertEquals(DatabaseReadiness.Failed("cannot decrypt"), gate.inspect())
        file.delete()
    }

    @Test
    fun `exported v7 identities match migrator identity`() {
        val schema =
            readRepoFile(
                "core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json",
            )
        val topLevelIdentity =
            Regex(""""identityHash":\s*"([0-9a-f]+)"""")
                .find(schema)
                ?.groupValues
                ?.get(1)
                ?: error("Missing exported v7 identityHash")
        val setupQueryIdentity =
            Regex("""VALUES\(42, '([0-9a-f]+)'\)""")
                .find(schema)
                ?.groupValues
                ?.get(1)
                ?: error("Missing exported v7 room_master_table setup identity")

        assertEquals(V7_DATABASE_IDENTITY_HASH, topLevelIdentity)
        assertEquals(V7_DATABASE_IDENTITY_HASH, setupQueryIdentity)
    }

    private fun inspect(
        version: Int,
        hasMigrationMetadata: Boolean = false,
    ): DatabaseReadiness {
        val file =
            kotlin.io.path
                .createTempFile()
                .toFile()
        return try {
            DatabaseReadinessGate(
                dbFile = file,
                inspectExistingDatabase = {
                    ExistingDatabaseState(
                        userVersion = version,
                        hasMigrationMetadata = hasMigrationMetadata,
                    )
                },
            ).inspect()
        } finally {
            file.delete()
        }
    }

    private fun readRepoFile(pathFromRepoRoot: String): String {
        val candidates =
            listOf(
                File(pathFromRepoRoot),
                File("../$pathFromRepoRoot"),
                File("../../$pathFromRepoRoot"),
            )
        return requireNotNull(candidates.firstOrNull(File::exists)) {
            "Could not locate $pathFromRepoRoot from ${File(".").absolutePath}"
        }.readText()
    }
}
