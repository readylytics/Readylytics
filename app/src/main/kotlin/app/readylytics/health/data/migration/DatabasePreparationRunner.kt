package app.readylytics.health.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabasePreparationRunner internal constructor(
    private val dbFile: File,
    private val gate: DatabaseReadinessGate,
    private val keyManager: SqlCipherKeyManager,
    private val v7Migrator: Lazy<V7DatabaseMigrator>,
    private val ioDispatcher: CoroutineDispatcher,
    private val availableBytes: (File) -> Long,
    private val readPlaintextVersion: (File) -> Int,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        gate: DatabaseReadinessGate,
        keyManager: SqlCipherKeyManager,
        v7Migrator: Lazy<V7DatabaseMigrator>,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        context.getDatabasePath(DatabaseReadinessGate.DATABASE_NAME),
        gate,
        keyManager,
        v7Migrator,
        ioDispatcher,
        { StatFs(it.parentFile!!.absolutePath).availableBytes },
        ::readPlaintextUserVersion,
    )

    suspend fun run(onProgress: suspend (DatabaseMigrationProgress) -> Unit): V7MigrationResult =
        withContext(ioDispatcher) {
            try {
                prepare(onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                V7MigrationResult.Failed(e.message ?: "Database preparation failed")
            }
        }

    private suspend fun prepare(onProgress: suspend (DatabaseMigrationProgress) -> Unit): V7MigrationResult {
        currentCoroutineContext().ensureActive()
        val readiness = gate.inspect()
        return if (readiness == DatabaseReadiness.EncryptionRequired) {
            val result = encryptPlaintext()
            if (result == V7MigrationResult.Complete) prepareEncrypted(gate.inspect(), onProgress) else result
        } else {
            prepareEncrypted(readiness, onProgress)
        }
    }

    private suspend fun prepareEncrypted(
        readiness: DatabaseReadiness,
        onProgress: suspend (DatabaseMigrationProgress) -> Unit,
    ): V7MigrationResult {
        if (readiness is DatabaseReadiness.MigrationRequired) {
            val result = v7Migrator.get().migrate(onProgress)
            if (result != V7MigrationResult.Complete) return result
        }
        currentCoroutineContext().ensureActive()
        return readinessResult(gate.inspect())
    }

    private suspend fun encryptPlaintext(): V7MigrationResult {
        val version = readPlaintextVersion(dbFile)
        if (version !in 5..DatabaseReadinessGate.CURRENT_DATABASE_VERSION) {
            return V7MigrationResult.Failed("Unsupported database version: $version")
        }
        val sourceBytes = dbFile.length() + File("${dbFile.absolutePath}-wal").length()
        val requiredBytes = sourceBytes + sourceBytes / 4 + EXPORT_RESERVE_BYTES
        val freeBytes = availableBytes(dbFile)
        return if (freeBytes < requiredBytes) {
            V7MigrationResult.InsufficientSpace(requiredBytes, freeBytes)
        } else {
            gate.invalidate()
            val context = currentCoroutineContext()
            context.ensureActive()
            keyManager.migrateIfNeeded(dbFile) { context.ensureActive() }
            V7MigrationResult.Complete
        }
    }

    private fun readinessResult(readiness: DatabaseReadiness): V7MigrationResult =
        when (readiness) {
            DatabaseReadiness.Ready -> V7MigrationResult.Complete
            is DatabaseReadiness.InsufficientSpace ->
                V7MigrationResult.InsufficientSpace(readiness.requiredBytes, readiness.availableBytes)
            is DatabaseReadiness.Failed -> V7MigrationResult.Failed(readiness.message)
            else -> V7MigrationResult.Failed("Database preparation did not reach Ready: $readiness")
        }

    private companion object {
        const val EXPORT_RESERVE_BYTES = 64L * 1024L * 1024L
    }
}

private fun readPlaintextUserVersion(file: File): Int =
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery("PRAGMA user_version", null).use { cursor ->
            check(cursor.moveToFirst()) { "Plaintext database has no user_version" }
            cursor.getInt(0)
        }
    }
