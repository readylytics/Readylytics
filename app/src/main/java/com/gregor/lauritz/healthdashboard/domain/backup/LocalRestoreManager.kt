package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
    ) {
        data class BackupManifest(
            val schemaVersion: Int,
            val exportedAt: String,
            val rowCounts: Map<String, Int>,
        )

        sealed class RestoreResult {
            data object Success : RestoreResult()

            data object SuccessRequiresRestart : RestoreResult()

            data class Failure(
                val cause: Throwable,
            ) : RestoreResult()
        }

        suspend fun validate(backupUri: Uri): Result<BackupManifest> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val jsonString =
                        context.contentResolver.openInputStream(backupUri)?.use {
                            it.bufferedReader().readText()
                        } ?: throw IllegalStateException("Could not read backup file")
                    val json = JSONObject(jsonString)

                    val schemaVersion = json.getInt("schemaVersion")
                    if (schemaVersion != HealthDatabase.DATABASE_VERSION) {
                        throw IllegalStateException(
                            "Backup schema version $schemaVersion does not match database version ${HealthDatabase.DATABASE_VERSION}",
                        )
                    }

                    val rowCounts = json.getJSONObject("rowCounts")
                    val expectedCounts =
                        mapOf(
                            "sleepSessions" to json.getJSONArray("sleepSessions").length(),
                            "heartRateRecords" to json.getJSONArray("heartRateRecords").length(),
                            "hrvRecords" to json.getJSONArray("hrvRecords").length(),
                            "workouts" to json.getJSONArray("workouts").length(),
                            "dailySummaries" to json.getJSONArray("dailySummaries").length(),
                        )

                    expectedCounts.forEach { (key, actualCount) ->
                        val declaredCount = rowCounts.optInt(key, -1)
                        if (actualCount != declaredCount) {
                            throw IllegalStateException(
                                "Row count mismatch for $key: declared=$declaredCount, actual=$actualCount",
                            )
                        }
                    }

                    val exportedAt = json.getString("exportedAt")
                    BackupManifest(schemaVersion, exportedAt, expectedCounts)
                }
            }

        suspend fun applyRestore(backupUri: Uri): RestoreResult =
            withContext(Dispatchers.IO) {
                runCatching {
                    val jsonString =
                        context.contentResolver.openInputStream(backupUri)?.use {
                            it.bufferedReader().readText()
                        } ?: throw IllegalStateException("Could not read backup file")
                    val json = JSONObject(jsonString)

                    healthDatabase.withTransaction {
                        val sleepSessionDao = healthDatabase.sleepSessionDao()
                        val heartRateDao = healthDatabase.heartRateDao()
                        val hrvDao = healthDatabase.hrvDao()
                        val workoutDao = healthDatabase.workoutDao()
                        val dailySummaryDao = healthDatabase.dailySummaryDao()

                        // Clear all tables
                        sleepSessionDao.deleteAll()
                        heartRateDao.deleteAll()
                        hrvDao.deleteAll()
                        workoutDao.deleteAll()
                        dailySummaryDao.deleteAll()

                        // Insert all rows from backup
                        val sleepSessions =
                            json
                                .getJSONArray("sleepSessions")
                                .let { arr ->
                                    (0 until arr.length()).map { i ->
                                        SleepSessionEntity.fromJson(arr.getJSONObject(i))
                                    }
                                }
                        sleepSessionDao.upsertAll(sleepSessions)

                        val heartRateRecords =
                            json
                                .getJSONArray("heartRateRecords")
                                .let { arr ->
                                    (0 until arr.length()).map { i ->
                                        HeartRateRecordEntity.fromJson(arr.getJSONObject(i))
                                    }
                                }
                        heartRateDao.upsertAll(heartRateRecords)

                        val hrvRecords =
                            json
                                .getJSONArray("hrvRecords")
                                .let { arr ->
                                    (0 until arr.length()).map { i ->
                                        HrvRecordEntity.fromJson(arr.getJSONObject(i))
                                    }
                                }
                        hrvDao.upsertAll(hrvRecords)

                        val workouts =
                            json
                                .getJSONArray("workouts")
                                .let { arr ->
                                    (0 until arr.length()).map { i ->
                                        WorkoutRecordEntity.fromJson(arr.getJSONObject(i))
                                    }
                                }
                        workoutDao.upsertAll(workouts)

                        val dailySummaries =
                            json
                                .getJSONArray("dailySummaries")
                                .let { arr ->
                                    (0 until arr.length()).map { i ->
                                        DailySummaryEntity.fromJson(arr.getJSONObject(i))
                                    }
                                }
                        dailySummaryDao.upsertAll(dailySummaries)

                        // Restore preferences
                        restorePreferences(json.getJSONObject("preferences"))
                    }

                    RestoreResult.SuccessRequiresRestart
                }.getOrElse { RestoreResult.Failure(it) }
            }

        private suspend fun restorePreferences(json: JSONObject) {
            if (json.has("goalSleepHours")) {
                settingsRepository.updateGoalSleepHours(json.getDouble("goalSleepHours").toFloat())
            }
            if (!json.isNull("hrvBaselineOverride")) {
                settingsRepository.updateHrvBaselineOverride(
                    json.getDouble("hrvBaselineOverride").toFloat(),
                )
            }
            if (!json.isNull("rhrBaselineOverride")) {
                settingsRepository.updateRhrBaselineOverride(
                    json.getDouble("rhrBaselineOverride").toFloat(),
                )
            }
            if (json.has("maxHeartRate")) {
                settingsRepository.updateMaxHeartRate(json.getInt("maxHeartRate"))
            }
            if (json.has("restingHrBeforeMinutes")) {
                settingsRepository.updateRestingHrBeforeMinutes(json.getInt("restingHrBeforeMinutes"))
            }
            if (json.has("restingHrAfterMinutes")) {
                settingsRepository.updateRestingHrAfterMinutes(json.getInt("restingHrAfterMinutes"))
            }
            if (json.has("birthDay") && json.has("birthMonth") && json.has("birthYear")) {
                settingsRepository.updateBirthday(
                    json.getInt("birthDay"),
                    json.getInt("birthMonth"),
                    json.getInt("birthYear"),
                )
            }
        }
    }
