package app.readylytics.health.databasebenchmark.data.migration

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

private const val BENCHMARK_DATABASE_PREFIX = "current-benchmark-"

/**
 * Fixture factory for current-schema (Room v19) database benchmarks.
 * Extracted separately from the legacy v6/v7 migration driver to ensure current-schema
 * benchmarking exercises the real Room architecture with SQLCipher encryption enabled.
 */
class CurrentSchemaBenchmarkFixture(
    private val context: Context,
) {
    private val createdNames = mutableSetOf<String>()

    // Every database this fixture opened. `cleanUp()` must close them before deleting the files:
    // Context.deleteDatabase() cannot remove a database that still has open connections, so a test
    // that left one open leaked its rows into the next test which opened the same name. That is how
    // `denseBurstsInsideSparseHistories` saw 366 rows after inserting 365 -- the surviving row came
    // from `editedValuesOnExistingKey`, which asserts a row count of exactly 1.
    private val openDatabases = mutableListOf<HealthDatabase>()

    fun createDatabase(
        name: String,
        useSqlCipher: Boolean = true,
        queryCallback: RoomDatabase.QueryCallback? = null,
    ): HealthDatabase {
        context.deleteDatabase(name)
        createdNames += name
        val dbFile = context.getDatabasePath(name)
        val builder =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, dbFile.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        if (useSqlCipher) {
            val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
            builder.openHelperFactory(keyManager.getOrCreateFactory())
        }
        if (queryCallback != null) {
            builder.setQueryCallback(queryCallback, Executors.newSingleThreadExecutor())
        }
        return builder.build().also(openDatabases::add)
    }

    fun createTemplate(
        suffix: String,
        useSqlCipher: Boolean = true,
        seed: (suspend (HealthDatabase) -> Unit)? = null,
    ): CurrentSchemaFixtureInstance {
        val name = "current-benchmark-$suffix.db"
        val database = createDatabase(name, useSqlCipher)
        if (seed != null) {
            runBlocking { seed(database) }
        }
        checkpointWal(database)
        val file = context.getDatabasePath(name)
        return CurrentSchemaFixtureInstance(name, file, useSqlCipher, database)
    }

    fun copyTemplate(
        source: CurrentSchemaFixtureInstance,
        suffix: String,
        queryCallback: RoomDatabase.QueryCallback? = null,
    ): CurrentSchemaFixtureInstance {
        val targetName = "current-benchmark-$suffix.db"
        context.deleteDatabase(targetName)
        createdNames += targetName
        val targetFile = context.getDatabasePath(targetName)
        source.file.copyTo(targetFile, overwrite = true)
        val database = openExistingDatabase(targetFile, source.useSqlCipher, queryCallback)
        return CurrentSchemaFixtureInstance(targetName, targetFile, source.useSqlCipher, database)
    }

    private fun openExistingDatabase(
        file: File,
        useSqlCipher: Boolean,
        queryCallback: RoomDatabase.QueryCallback? = null,
    ): HealthDatabase {
        val builder =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, file.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        if (useSqlCipher) {
            val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
            builder.openHelperFactory(keyManager.getOrCreateFactory())
        }
        if (queryCallback != null) {
            builder.setQueryCallback(queryCallback, Executors.newSingleThreadExecutor())
        }
        return builder.build().also(openDatabases::add)
    }

    private fun checkpointWal(database: HealthDatabase) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .close()
    }

    fun delete(fixture: CurrentSchemaFixtureInstance) {
        fixture.database.close()
        openDatabases -= fixture.database
        context.deleteDatabase(fixture.name)
        createdNames -= fixture.name
    }

    /**
     * Closes every database this fixture opened before deleting its files, then sweeps any
     * `current-benchmark-` database left behind by an earlier test class or a killed run.
     *
     * Both halves matter. Deleting without closing silently fails while connections are open, and
     * deleting only this instance's own names leaves another class's databases in place -- two test
     * classes construct separate fixtures, so neither cleans the other's files. A benchmark that
     * inherits another test's rows measures the wrong dataset.
     */
    fun cleanUp() {
        openDatabases.forEach { runCatching { it.close() } }
        openDatabases.clear()
        createdNames.forEach(context::deleteDatabase)
        createdNames.clear()
        context
            .databaseList()
            .filter { it.startsWith(BENCHMARK_DATABASE_PREFIX) }
            .forEach(context::deleteDatabase)
    }
}

data class CurrentSchemaFixtureInstance(
    val name: String,
    val file: File,
    val useSqlCipher: Boolean,
    val database: HealthDatabase,
)
