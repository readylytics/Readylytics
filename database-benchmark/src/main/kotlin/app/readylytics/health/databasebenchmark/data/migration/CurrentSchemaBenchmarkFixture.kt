package app.readylytics.health.databasebenchmark.data.migration

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Fixture factory for current-schema (Room v19) database benchmarks.
 * Extracted separately from the legacy v6/v7 migration driver to ensure current-schema
 * benchmarking exercises the real Room architecture without pretending v7 migrators support v19.
 */
internal class CurrentSchemaBenchmarkFixture(
    private val context: Context,
) {
    private val createdNames = mutableSetOf<String>()

    fun createDatabase(
        name: String,
        useSqlCipher: Boolean = false,
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
        return builder.build()
    }

    fun createTemplate(
        suffix: String,
        useSqlCipher: Boolean = false,
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
    ): CurrentSchemaFixtureInstance {
        val targetName = "current-benchmark-$suffix.db"
        context.deleteDatabase(targetName)
        createdNames += targetName
        val targetFile = context.getDatabasePath(targetName)
        source.file.copyTo(targetFile, overwrite = true)
        val database = openExistingDatabase(targetFile, source.useSqlCipher)
        return CurrentSchemaFixtureInstance(targetName, targetFile, source.useSqlCipher, database)
    }

    private fun openExistingDatabase(
        file: File,
        useSqlCipher: Boolean,
    ): HealthDatabase {
        val builder =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, file.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        if (useSqlCipher) {
            val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
            builder.openHelperFactory(keyManager.getOrCreateFactory())
        }
        return builder.build()
    }

    private fun checkpointWal(database: HealthDatabase) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .close()
    }

    fun delete(fixture: CurrentSchemaFixtureInstance) {
        fixture.database.close()
        context.deleteDatabase(fixture.name)
        createdNames -= fixture.name
    }

    fun cleanUp() {
        createdNames.forEach(context::deleteDatabase)
        createdNames.clear()
    }
}

internal data class CurrentSchemaFixtureInstance(
    val name: String,
    val file: File,
    val useSqlCipher: Boolean,
    val database: HealthDatabase,
)
