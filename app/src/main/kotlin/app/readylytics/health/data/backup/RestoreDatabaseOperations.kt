package app.readylytics.health.data.backup

import android.util.JsonReader
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.readylytics.health.core.database.data.local.HealthDatabase
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreDatabaseOperations
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
        private val batchLoader: RestoreBatchLoader,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun executeDatabaseRestore(
            zipFile: ZipFile,
            header: FileHeader,
            validated: ValidatedBackupInventory,
        ): UserPreferencesBackup? {
            var prefsBackup: UserPreferencesBackup? = null
            healthDatabase.withTransaction {
                // Child-before-parent clearing: routes, sleep stages, HR/HRV, warm buckets,
                // vitals/steps/VO2, summaries, sleep/workout parents, source records.
                // Device-local insight dismissals and audit events are intentionally retained.
                clearDatabaseTablesChildBeforeParent()

                // Stable parent-before-child streaming independent of JSON field order:
                // Pass 1: Parents (healthSourceRecords, workouts, sleepSessions) and preferences
                zipFile.getInputStream(header).use { inputStream ->
                    val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
                    performParentsPass(reader) { parsedPreferences ->
                        prefsBackup = parsedPreferences
                    }
                }

                // Pass 2: Children and remaining tables (heartRateRecords, hrvRecords, etc.)
                zipFile.getInputStream(header).use { inputStream ->
                    val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
                    performChildrenPass(reader, validated.manifest.schemaVersion)
                }

                // Re-run observed-count validation against actual database table counts
                validateDatabaseCounts(validated.observedCounts)

                // Foreign key validation check before committing transaction
                checkForeignKeys()
            }
            return prefsBackup
        }

        private suspend fun clearDatabaseTablesChildBeforeParent() {
            healthDatabase.workoutRoutePointDao().deleteAll()
            healthDatabase.sleepStageDao().deleteAll()
            healthDatabase.heartRateDao().deleteAll()
            healthDatabase.hrvDao().deleteAll()
            healthDatabase.minuteBucketMaintenanceDao().deleteAll()
            healthDatabase.weightRecordDao().deleteAll()
            healthDatabase.bodyFatRecordDao().deleteAll()
            healthDatabase.bloodPressureRecordDao().deleteAll()
            healthDatabase.oxygenSaturationRecordDao().deleteAll()
            healthDatabase.bodyTemperatureRecordDao().deleteAll()
            healthDatabase.stepRecordDao().deleteAll()
            healthDatabase.vo2MaxRecordDao().deleteAll()
            healthDatabase.dailySummaryDao().deleteAll()
            healthDatabase.sleepSessionDao().deleteAll()
            healthDatabase.workoutDao().deleteAll()
            healthDatabase.sourceRecordDao().deleteAll()
        }

        private suspend fun performParentsPass(
            reader: JsonReader,
            onPreferences: (UserPreferencesBackup) -> Unit,
        ) {
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                when (key) {
                    "healthSourceRecords" -> batchLoader.restoreHealthSourceRecords(reader)
                    "workouts" -> batchLoader.restoreWorkouts(reader)
                    "sleepSessions" -> batchLoader.restoreSleepSessions(reader)
                    "preferences" -> {
                        val parsed = json.decodeFromString<UserPreferencesBackup>(readNextObjectAsString(json, reader))
                        onPreferences(parsed)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        private suspend fun performChildrenPass(
            reader: JsonReader,
            schemaVersion: Int,
        ) {
            val handlers =
                mapOf<String, suspend (JsonReader) -> Unit>(
                    "heartRateRecords" to { batchLoader.restoreHeartRateRecords(it, schemaVersion) },
                    "hrvRecords" to { batchLoader.restoreHrvRecords(it, schemaVersion) },
                    "hrMinuteBuckets" to { batchLoader.restoreHrMinuteBuckets(it) },
                    "dailySummaries" to { batchLoader.restoreDailySummaries(it) },
                    "workoutRoutePoints" to { batchLoader.restoreWorkoutRoutePoints(it) },
                    "weightRecords" to { batchLoader.vitalsLoader.restoreWeightRecords(it) },
                    "bodyFatRecords" to { batchLoader.vitalsLoader.restoreBodyFatRecords(it) },
                    "bloodPressureRecords" to { batchLoader.vitalsLoader.restoreBloodPressureRecords(it) },
                    "oxygenSaturationRecords" to { batchLoader.vitalsLoader.restoreOxygenSaturationRecords(it) },
                    "bodyTemperatureRecords" to { batchLoader.vitalsLoader.restoreBodyTemperatureRecords(it) },
                    "stepRecords" to { batchLoader.vitalsLoader.restoreStepRecords(it) },
                    "vo2MaxRecords" to { batchLoader.vitalsLoader.restoreVo2MaxRecords(it) },
                )

            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val handler = handlers[key]
                if (handler != null) {
                    handler(reader)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }

        private suspend fun validateDatabaseCounts(observedCounts: Map<String, Long>) {
            for ((table, expectedCount) in observedCounts) {
                val actualCount = getTableCount(table)
                check(actualCount.toLong() == expectedCount) {
                    "BACKUP_COUNT_MISMATCH: Table $table expected $expectedCount " +
                        "rows but database contains $actualCount"
                }
            }
        }

        private suspend fun getTableCount(table: String): Int =
            when (table) {
                "sleepSessions" -> healthDatabase.sleepSessionDao().count()
                "healthSourceRecords" -> healthDatabase.sourceRecordDao().count()
                "heartRateRecords" -> healthDatabase.heartRateDao().count()
                "hrvRecords" -> healthDatabase.hrvDao().count()
                "hrMinuteBuckets" -> healthDatabase.minuteBucketMaintenanceDao().count()
                "workouts" -> healthDatabase.workoutDao().count()
                "workoutRoutePoints" -> healthDatabase.workoutRoutePointDao().count()
                "dailySummaries" -> healthDatabase.dailySummaryDao().count()
                "weightRecords" -> healthDatabase.weightRecordDao().count()
                "bodyFatRecords" -> healthDatabase.bodyFatRecordDao().count()
                "bloodPressureRecords" -> healthDatabase.bloodPressureRecordDao().count()
                "oxygenSaturationRecords" -> healthDatabase.oxygenSaturationRecordDao().count()
                "bodyTemperatureRecords" -> healthDatabase.bodyTemperatureRecordDao().count()
                "stepRecords" -> healthDatabase.stepRecordDao().count()
                "vo2MaxRecords" -> healthDatabase.vo2MaxRecordDao().count()
                else -> 0
            }

        private fun checkForeignKeys() {
            val cursor = healthDatabase.query(SimpleSQLiteQuery("PRAGMA foreign_key_check"))
            cursor.use {
                if (it.moveToFirst()) {
                    val table = it.getString(0)
                    val rowid = it.getLong(1)
                    val parent = it.getString(2)
                    val fkid = it.getInt(3)
                    error(
                        "BACKUP_FOREIGN_KEY_VIOLATION: Foreign key violation in table $table " +
                            "(rowid $rowid, parent $parent, fkid $fkid)",
                    )
                }
            }
        }
    }
