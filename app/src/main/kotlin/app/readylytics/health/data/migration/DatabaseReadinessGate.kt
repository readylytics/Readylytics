package app.readylytics.health.data.migration

import android.content.Context
import app.readylytics.health.data.security.SqlCipherKeyManager
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
    ) {
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

        fun inspect(): DatabaseReadiness {
            if (!dbFile.exists()) return DatabaseReadiness.Ready

            return try {
                val state = inspectExistingDatabase(dbFile)
                when {
                    state.userVersion == CURRENT_DATABASE_VERSION -> DatabaseReadiness.Ready
                    state.userVersion == 5 -> DatabaseReadiness.MigrationRequired(5)
                    state.userVersion == 6 -> DatabaseReadiness.MigrationRequired(6)
                    state.hasMigrationMetadata -> DatabaseReadiness.MigrationRequired(6)
                    else ->
                        DatabaseReadiness.Failed(
                            "Unsupported database version: ${state.userVersion}",
                        )
                }
            } catch (e: Exception) {
                DatabaseReadiness.Failed(e.message ?: "Database readiness inspection failed")
            }
        }

        private companion object {
            const val DATABASE_NAME = "health_dashboard.db"
            const val CURRENT_DATABASE_VERSION = 7
            const val MIGRATION_METADATA_TABLE = "readylytics_schema_migration"
        }
    }
