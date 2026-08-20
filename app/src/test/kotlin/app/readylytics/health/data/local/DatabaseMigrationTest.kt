package app.readylytics.health.data.local

import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.di.requireDatabaseReady
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationTest {
    @Test
    fun `database version matches latest migration`() {
        // DATABASE_VERSION must always equal the endVersion of the last registered Room
        // migration. This holds for every ordinary Room-managed version bump; only the
        // historical v6->v7 SQLCipher adoption (V7DatabaseMigrator) was out-of-band, and that
        // gap is asserted explicitly below rather than here.
        assertEquals(DatabaseMigrations.all.last().endVersion, HealthDatabase.DATABASE_VERSION)
    }

    @Test
    fun `database migrations are registered`() {
        assertTrue(DatabaseMigrations.all.isNotEmpty())
    }

    @Test
    fun `future migrations are registered in sequential order`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        migrations.zipWithNext { current, next ->
            // Known, intentional, documented exception: version 6->7 was a one-time
            // out-of-band SQLCipher adoption (V7DatabaseMigrator), not a Room `Migration`, so
            // the registered chain legitimately jumps from an entry ending at 6 to one
            // starting at 7. Any other gap is a real accidental bug and must still fail.
            if (current.endVersion == 6 && next.startVersion == 7) return@zipWithNext

            assertEquals(
                "Gap between migration ${current.endVersion} and ${next.startVersion}",
                current.endVersion,
                next.startVersion,
            )
        }
    }

    @Test
    fun `future migration chain starts at baseline version`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        if (migrations.isNotEmpty()) {
            assertEquals(1, migrations.first().startVersion)
        }
    }

    @Test
    fun `Room migration chain has exactly one known discontinuity at the external v6-7 bridge`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        val gaps =
            migrations.zipWithNext().filter { (current, next) -> current.endVersion != next.startVersion }

        assertEquals(
            "Expected exactly the known (5->6)->(7->8) discontinuity caused by the external " +
                "v6->v7 SQLCipher migration (V7DatabaseMigrator); any other gap is unintentional",
            listOf(6 to 7),
            gaps.map { (current, next) -> current.endVersion to next.startVersion },
        )
    }

    @Test
    fun `Room guard refuses to open before external migration completes`() {
        val gate = mockk<DatabaseReadinessGate>()
        every { gate.inspect() } returns DatabaseReadiness.MigrationRequired(6)

        val failure = runCatching { requireDatabaseReady(gate) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "HealthDatabase cannot open before the external v7 migration is complete",
            failure?.message,
        )
        verify(exactly = 1) { gate.inspect() }
    }

    @Test
    fun `Room guard accepts an externally migrated database`() {
        val gate = mockk<DatabaseReadinessGate>()
        every { gate.inspect() } returns DatabaseReadiness.Ready

        requireDatabaseReady(gate)

        verify(exactly = 1) { gate.inspect() }
    }
}
