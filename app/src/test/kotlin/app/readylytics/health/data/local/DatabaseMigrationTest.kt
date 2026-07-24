package app.readylytics.health.data.local

import app.readylytics.health.data.migration.DatabaseReadiness
import app.readylytics.health.data.migration.DatabaseReadinessGate
import app.readylytics.health.di.requireDatabaseReady
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationTest {
    @Test
    fun `database version matches latest migration`() {
        assertEquals(7, HealthDatabase.DATABASE_VERSION)
    }

    @Test
    fun `database migrations are registered`() {
        assertTrue(DatabaseMigrations.all.isNotEmpty())
    }

    @Test
    fun `future migrations are registered in sequential order`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        migrations.zipWithNext { current, next ->
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
    fun `Room migration chain ends at six while database version remains seven`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        if (migrations.isNotEmpty()) {
            assertEquals(6, migrations.last().endVersion)
            assertEquals(7, HealthDatabase.DATABASE_VERSION)
        }
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
