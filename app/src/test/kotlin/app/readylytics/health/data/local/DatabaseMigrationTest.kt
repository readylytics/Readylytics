package app.readylytics.health.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationTest {
    @Test
    fun `database version matches latest migration`() {
        assertEquals(3, HealthDatabase.DATABASE_VERSION)
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
    fun `future migration chain ends at database version`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        if (migrations.isNotEmpty()) {
            assertEquals(HealthDatabase.DATABASE_VERSION, migrations.last().endVersion)
        }
    }
}
