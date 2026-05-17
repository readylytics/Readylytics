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
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class WrongBackupPasswordException : Exception("Incorrect backup password")

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val encryptionManager: EncryptionManager,
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
                    val json = readJsonFromZip(backupUri)
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
                    val json = readJsonFromZip(backupUri)

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

        private suspend fun readJsonFromZip(backupUri: Uri): JSONObject {
            val tempZipFile = File(context.cacheDir, "restore_temp.zip")
            context.contentResolver.openInputStream(backupUri)?.use { input ->
                tempZipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open backup URI")

            val prefs = settingsRepository.userPreferences.first()
            val password =
                prefs.backupPasswordHash?.let { hash ->
                    encryptionManager.decrypt(hash)
                }

            val zipFile = ZipFile(tempZipFile)
            if (zipFile.isEncrypted) {
                if (password == null) {
                    tempZipFile.delete()
                    throw WrongBackupPasswordException()
                }
                zipFile.setPassword(password.toCharArray())
            }

            return try {
                val header =
                    zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                        ?: throw IllegalStateException("No JSON file found in backup ZIP")

                val jsonString =
                    zipFile.getInputStream(header).use {
                        it.bufferedReader().readText()
                    }
                JSONObject(jsonString)
            } catch (e: ZipException) {
                // Usually indicates wrong password if it's encrypted
                if (zipFile.isEncrypted) {
                    throw WrongBackupPasswordException()
                } else {
                    throw e
                }
            } finally {
                tempZipFile.delete()
            }
        }

        private suspend fun restorePreferences(json: JSONObject) {
            if (json.has("goalSleepHours")) {
                settingsRepository.updateGoalSleepHours(json.getDouble("goalSleepHours").toFloat())
            }
            if (!json.isNull("hrvBaselineOverride")) {
                settingsRepository.updateHrvBaselineOverride(
                    json.getDouble("hrvBaselineOverride").toFloat(),
                )
            } else if (json.has("hrvBaselineOverride")) {
                settingsRepository.updateHrvBaselineOverride(null)
            }
            if (!json.isNull("rhrBaselineOverride")) {
                settingsRepository.updateRhrBaselineOverride(
                    json.getDouble("rhrBaselineOverride").toFloat(),
                )
            } else if (json.has("rhrBaselineOverride")) {
                settingsRepository.updateRhrBaselineOverride(null)
            }
            if (json.has("syncPreference")) {
                settingsRepository.updateSyncPreference(
                    com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference.valueOf(
                        json.getString("syncPreference"),
                    ),
                )
            }
            if (json.has("syncIntervalHours")) {
                settingsRepository.updateSyncIntervalHours(json.getInt("syncIntervalHours"))
            }
            if (json.has("lastSyncTimestamp")) {
                settingsRepository.updateLastSyncTimestamp(json.getLong("lastSyncTimestamp"))
            }
            if (json.has("maxHeartRate")) {
                settingsRepository.updateMaxHeartRate(json.getInt("maxHeartRate"))
            }
            if (json.has("autoCalculateMaxHr")) {
                settingsRepository.updateAutoCalculateMaxHr(json.getBoolean("autoCalculateMaxHr"))
            }
            if (json.has("manualZoneEditing")) {
                settingsRepository.updateManualZoneEditing(json.getBoolean("manualZoneEditing"))
            }
            if (json.has("zone1MinPercent") &&
                json.has("zone1MaxPercent") &&
                json.has("zone2MaxPercent") &&
                json.has("zone3MaxPercent") &&
                json.has("zone4MaxPercent")
            ) {
                settingsRepository.updateZonePercentages(
                    json.getDouble("zone1MinPercent").toFloat(),
                    json.getDouble("zone1MaxPercent").toFloat(),
                    json.getDouble("zone2MaxPercent").toFloat(),
                    json.getDouble("zone3MaxPercent").toFloat(),
                    json.getDouble("zone4MaxPercent").toFloat(),
                )
            }
            if (json.has("zone1MinBpm") &&
                json.has("zone1MaxBpm") &&
                json.has("zone2MaxBpm") &&
                json.has("zone3MaxBpm") &&
                json.has("zone4MaxBpm")
            ) {
                settingsRepository.updateZoneBpms(
                    json.getInt("zone1MinBpm"),
                    json.getInt("zone1MaxBpm"),
                    json.getInt("zone2MaxBpm"),
                    json.getInt("zone3MaxBpm"),
                    json.getInt("zone4MaxBpm"),
                )
            }
            if (json.has("age")) {
                settingsRepository.updateAge(json.getInt("age"))
            }
            if (json.has("birthDay") && json.has("birthMonth") && json.has("birthYear")) {
                settingsRepository.updateBirthday(
                    json.getInt("birthDay"),
                    json.getInt("birthMonth"),
                    json.getInt("birthYear"),
                )
            }
            if (!json.isNull("gender")) {
                settingsRepository.updateGender(json.getString("gender"))
            } else if (json.has("gender")) {
                settingsRepository.updateGender(null)
            }
            if (json.has("hrvOptimalThreshold")) {
                settingsRepository.updateHrvOptimalThreshold(json.getDouble("hrvOptimalThreshold").toFloat())
            }
            if (json.has("hrvWarningThreshold")) {
                settingsRepository.updateHrvWarningThreshold(json.getDouble("hrvWarningThreshold").toFloat())
            }
            if (json.has("rhrOptimalThreshold")) {
                settingsRepository.updateRhrOptimalThreshold(json.getDouble("rhrOptimalThreshold").toFloat())
            }
            if (json.has("rhrWarningThreshold")) {
                settingsRepository.updateRhrWarningThreshold(json.getDouble("rhrWarningThreshold").toFloat())
            }
            if (json.has("restingHrBeforeMinutes")) {
                settingsRepository.updateRestingHrBeforeMinutes(json.getInt("restingHrBeforeMinutes"))
            }
            if (json.has("restingHrAfterMinutes")) {
                settingsRepository.updateRestingHrAfterMinutes(json.getInt("restingHrAfterMinutes"))
            }
            if (json.has("appTheme")) {
                settingsRepository.updateAppTheme(
                    com.gregor.lauritz.healthdashboard.data.preferences.AppTheme.valueOf(
                        json.getString("appTheme"),
                    ),
                )
            }
            // driveAccountEmail not yet supported by SettingsRepository update methods
            if (json.has("backupSchedule")) {
                settingsRepository.updateBackupSchedule(
                    com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule.valueOf(
                        json.getString("backupSchedule"),
                    ),
                )
            }
            if (json.has("lastBackupTimestamp")) {
                settingsRepository.updateLastBackupTimestamp(json.getLong("lastBackupTimestamp"))
            }
            if (json.has("consistencyThresholdMinutes")) {
                settingsRepository.updateConsistencyThresholdMinutes(json.getInt("consistencyThresholdMinutes"))
            }
            if (json.has("consistencyEvaluationDays")) {
                settingsRepository.updateConsistencyEvaluationDays(json.getInt("consistencyEvaluationDays"))
            }
            if (json.has("consistencyBaselineDays")) {
                settingsRepository.updateConsistencyBaselineDays(json.getInt("consistencyBaselineDays"))
            }
            if (json.has("paiScalingFactor")) {
                settingsRepository.updatePaiScalingFactor(json.getDouble("paiScalingFactor").toFloat())
            }
            if (json.has("stepGoal")) {
                settingsRepository.updateStepGoal(json.getInt("stepGoal"))
            }
            if (json.has("retentionDaysEnabled")) {
                settingsRepository.updateRetentionDaysEnabled(json.getBoolean("retentionDaysEnabled"))
            }
            if (json.has("retentionDays")) {
                settingsRepository.updateRetentionDays(json.getInt("retentionDays"))
            }
            if (json.has("collapseCloudData")) {
                settingsRepository.updateCollapseCloudData(json.getBoolean("collapseCloudData"))
            }
            if (json.has("collapseHealthConnect")) {
                settingsRepository.updateCollapseHealthConnect(json.getBoolean("collapseHealthConnect"))
            }
            if (json.has("collapseBaselinesThresholds")) {
                settingsRepository.updateCollapseBaselinesThresholds(json.getBoolean("collapseBaselinesThresholds"))
            }
            if (json.has("collapseDisplay")) {
                settingsRepository.updateCollapseDisplay(json.getBoolean("collapseDisplay"))
            }
            if (json.has("collapseAdvanced")) {
                settingsRepository.updateCollapseAdvanced(json.getBoolean("collapseAdvanced"))
            }
            if (json.has("aboutDismissed")) {
                settingsRepository.updateAboutDismissed(json.getBoolean("aboutDismissed"))
            }
            if (json.has("physiologyProfile")) {
                settingsRepository.updatePhysiologyProfile(
                    com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile.valueOf(
                        json.getString("physiologyProfile"),
                    ),
                )
            }
            if (json.has("installDate")) {
                settingsRepository.updateInstallDate(json.getLong("installDate"))
            }
            if (!json.isNull("circadianThresholdOverride")) {
                settingsRepository.updateCircadianThresholdOverride(json.getString("circadianThresholdOverride"))
            } else if (json.has("circadianThresholdOverride")) {
                settingsRepository.updateCircadianThresholdOverride(null)
            }
            if (json.has("dynamicColorEnabled")) {
                settingsRepository.updateDynamicColorEnabled(json.getBoolean("dynamicColorEnabled"))
            }
            if (json.has("trimpModel")) {
                settingsRepository.updateTrimpModel(
                    com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel.valueOf(
                        json.getString("trimpModel"),
                    ),
                )
            }
            if (json.has("banisterMultiplier")) {
                settingsRepository.updateBanisterMultiplier(json.getDouble("banisterMultiplier").toFloat())
            }
            if (json.has("chengBeta")) {
                settingsRepository.updateChengBeta(json.getDouble("chengBeta").toFloat())
            }
            if (json.has("itrimB")) {
                settingsRepository.updateItrimB(json.getDouble("itrimB").toFloat())
            }
            if (!json.isNull("primaryDeviceName")) {
                settingsRepository.updatePrimaryDevice(json.getString("primaryDeviceName"))
            } else if (json.has("primaryDeviceName")) {
                settingsRepository.updatePrimaryDevice(null)
            }
            if (!json.isNull("backupDirectoryUri")) {
                settingsRepository.updateBackupDirectoryUri(json.getString("backupDirectoryUri"))
            } else if (json.has("backupDirectoryUri")) {
                settingsRepository.updateBackupDirectoryUri(null)
            }
            if (!json.isNull("backupPasswordHash")) {
                settingsRepository.updateBackupPasswordHash(json.getString("backupPasswordHash"))
            } else if (json.has("backupPasswordHash")) {
                settingsRepository.updateBackupPasswordHash(null)
            }
        }
    }
