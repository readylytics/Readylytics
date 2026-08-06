package app.readylytics.health.data.migration

import android.content.Context
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.security.SqlCipherKeyManager
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.DatabaseReadinessInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal data class ExistingDatabaseState(
    val userVersion: Int,
    val hasMigrationMetadata: Boolean,
)

@Singleton
class DatabaseReadinessGate
    internal constructor(
        private val dbFile: File,
        private val inspectExistingDatabase: (File) -> ExistingDatabaseState,
    ) : DatabaseReadinessInspector {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            sqlCipherKeyManager: SqlCipherKeyManager,
        ) : this(
            dbFile = context.getDatabasePath(DATABASE_NAME),
            inspectExistingDatabase = { file ->
                sqlCipherKeyManager.withWritableDatabase(file) { database ->
                    val userVersion =
                        database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
                            check(cursor.moveToFirst()) { "Database has no user_version" }
                            cursor.getInt(0)
                        }
                    val hasMigrationMetadata =
                        database
                            .rawQuery(
                                """
                                SELECT 1
                                FROM sqlite_master
                                WHERE type = 'table' AND name = ?
                                LIMIT 1
                                """.trimIndent(),
                                arrayOf(MIGRATION_METADATA_TABLE),
                            ).use { cursor -> cursor.moveToFirst() }
                    ExistingDatabaseState(userVersion, hasMigrationMetadata)
                }
            },
        )

        override fun inspect(): DatabaseReadiness {
            if (!dbFile.exists()) return DatabaseReadiness.Ready

            return try {
                val state = inspectExistingDatabase(dbFile)
                when (state.userVersion) {
                    // Anything from the first Room-managed version up to the current one can be
                    // opened directly: Room applies DatabaseMigrations.all itself. Gating this on
                    // an exact match would lock the app out of its own schema the moment
                    // DATABASE_VERSION is bumped.
                    in ROOM_MANAGED_MIN_VERSION..CURRENT_DATABASE_VERSION -> DatabaseReadiness.Ready
                    // v5/v6 predate the shadow-table rebuild, which runs outside Room in
                    // V7DatabaseMigrator before the database may be opened at all.
                    5, 6 -> DatabaseReadiness.MigrationRequired(state.userVersion)
                    else ->
                        DatabaseReadiness.Failed(
                            "Unsupported database version: ${state.userVersion}",
                        )
                }
            } catch (_: SqlCipherKeyManager.KeyDecryptionException) {
                DatabaseReadiness.KeyCorrupted
            } catch (e: Exception) {
                DatabaseReadiness.Failed(e.message ?: "Database readiness inspection failed")
            }
        }

        internal companion object {
            const val DATABASE_NAME = "health_dashboard.db"

            // Tracks HealthDatabase.DATABASE_VERSION directly (both are compile-time constants, so
            // this stays a constant expression) for the same reason BackupModels.MAX_SUPPORTED_VERSION
            // does: a hand-maintained copy silently drifts on the next schema bump and turns every
            // app start into the "Updating your health database" failure screen.
            const val CURRENT_DATABASE_VERSION = HealthDatabase.DATABASE_VERSION

            // First schema version Room itself can open and migrate forward from. Below this the
            // external v7 migration must run first.
            const val ROOM_MANAGED_MIN_VERSION = 7

            const val MIGRATION_METADATA_TABLE = "readylytics_schema_migration"
        }
    }
