package app.readylytics.health.data.migration

import android.content.Context
import app.readylytics.health.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.data.security.SqlCipherKeyManager
import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.V7MigrationResult
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Benchmark-build-only access to the production v7 migrator for the external database benchmark.
 */
class V7DatabaseBenchmarkDriver(
    context: Context,
    private val dbFile: File,
    availableBytes: (File) -> Long,
) {
    private val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
    private val migrator = V7DatabaseMigrator(keyManager, dbFile, availableBytes)

    fun migrateIfNeeded() {
        keyManager.migrateIfNeeded(dbFile)
    }

    fun <T> withWritableDatabase(block: (SQLiteDatabase) -> T): T = keyManager.withWritableDatabase(dbFile, block)

    suspend fun migrate(onProgress: suspend (DatabaseMigrationProgress) -> Unit): V7MigrationResult =
        migrator.migrate(onProgress)
}
