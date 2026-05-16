package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
    ) {
        private val backupDir = File(context.filesDir, "backups")

        suspend fun createBackup(): Result<File> =
            withContext(Dispatchers.IO) {
                runCatching {
                    backupDir.mkdirs()
                    pruneOldBackups()

                    val now = System.currentTimeMillis()
                    val sleepSessions = healthDatabase.sleepSessionDao().getSince(0)
                    val heartRateRecords = healthDatabase.heartRateDao().getSince(0)
                    val hrvRecords = healthDatabase.hrvDao().getSince(0)
                    val workouts = healthDatabase.workoutDao().getSince(0)
                    val dailySummaries = healthDatabase.dailySummaryDao().getSince(0)

                    val rowCounts =
                        mapOf(
                            "sleepSessions" to sleepSessions.size,
                            "heartRateRecords" to heartRateRecords.size,
                            "hrvRecords" to hrvRecords.size,
                            "workouts" to workouts.size,
                            "dailySummaries" to dailySummaries.size,
                        )

                    val json =
                        JSONObject()
                            .put("schemaVersion", 18)
                            .put("exportedAt", Instant.now().toString())
                            .put("rowCounts", JSONObject(rowCounts))
                            .put("preferences", buildPreferencesJson())
                            .put(
                                "sleepSessions",
                                JSONArray(
                                    sleepSessions.map { s ->
                                        JSONObject()
                                            .put("id", s.id)
                                            .put("startTime", s.startTime)
                                            .put("endTime", s.endTime)
                                            .put("durationMinutes", s.durationMinutes)
                                            .put("efficiency", s.efficiency)
                                            .put("deepSleepMinutes", s.deepSleepMinutes)
                                            .put("remSleepMinutes", s.remSleepMinutes)
                                            .put("lightSleepMinutes", s.lightSleepMinutes)
                                            .put("awakeMinutes", s.awakeMinutes)
                                            .put("sleepScore", s.sleepScore)
                                            .put("startZoneOffsetSeconds", s.startZoneOffsetSeconds)
                                            .put("endZoneOffsetSeconds", s.endZoneOffsetSeconds)
                                            .put("deviceName", s.deviceName)
                                    },
                                ),
                            ).put(
                                "heartRateRecords",
                                JSONArray(
                                    heartRateRecords.map { h ->
                                        JSONObject()
                                            .put("id", h.id)
                                            .put("timestampMs", h.timestampMs)
                                            .put("beatsPerMinute", h.beatsPerMinute)
                                            .put("recordType", h.recordType)
                                            .put("sessionId", h.sessionId)
                                            .put("deviceName", h.deviceName)
                                    },
                                ),
                            ).put(
                                "hrvRecords",
                                JSONArray(
                                    hrvRecords.map { hrv ->
                                        JSONObject()
                                            .put("id", hrv.id)
                                            .put("timestampMs", hrv.timestampMs)
                                            .put("rmssdMs", hrv.rmssdMs)
                                            .put("recordType", hrv.recordType)
                                            .put("sessionId", hrv.sessionId)
                                            .put("deviceName", hrv.deviceName)
                                    },
                                ),
                            ).put(
                                "workouts",
                                JSONArray(
                                    workouts.map { w ->
                                        JSONObject()
                                            .put("id", w.id)
                                            .put("startTime", w.startTime)
                                            .put("endTime", w.endTime)
                                            .put("exerciseType", w.exerciseType)
                                            .put("durationMinutes", w.durationMinutes)
                                            .put("zone1Minutes", w.zone1Minutes)
                                            .put("zone2Minutes", w.zone2Minutes)
                                            .put("zone3Minutes", w.zone3Minutes)
                                            .put("zone4Minutes", w.zone4Minutes)
                                            .put("zone5Minutes", w.zone5Minutes)
                                            .put("trimp", w.trimp)
                                            .put("avgHr", w.avgHr)
                                            .put("deviceName", w.deviceName)
                                    },
                                ),
                            ).put(
                                "dailySummaries",
                                JSONArray(
                                    dailySummaries.map { d ->
                                        JSONObject()
                                            .put("dateMidnightMs", d.dateMidnightMs)
                                            .put("sleepScore", d.sleepScore)
                                            .put("loadScore", d.loadScore)
                                            .put("readinessScore", d.readinessScore)
                                            .put("strainRatio", d.strainRatio)
                                            .put("nocturnalRhr", d.nocturnalRhr)
                                            .put("nocturnalHrv", d.nocturnalHrv)
                                            .put("sleepDurationMinutes", d.sleepDurationMinutes)
                                            .put("deepSleepPercent", d.deepSleepPercent)
                                            .put("remSleepPercent", d.remSleepPercent)
                                            .put("totalTrimp", d.totalTrimp)
                                            .put("rhrRatio", d.rhrRatio)
                                            .put("hrvBaseline", d.hrvBaseline)
                                            .put("restingHeartRate", d.restingHeartRate)
                                            .put("restingHrRatio", d.restingHrRatio)
                                            .put("restingHrBaseline", d.restingHrBaseline)
                                            .put("paiScore", d.paiScore)
                                            .put("totalPai", d.totalPai)
                                            .put("stepCount", d.stepCount)
                                            .put("zLnHrv", d.zLnHrv)
                                            .put("zRhr", d.zRhr)
                                            .put("recoveryFlags", d.recoveryFlags)
                                            .put("hrvSigma", d.hrvSigma)
                                            .put("rollingMu", d.rollingMu)
                                            .put("rhrDeltaBpm", d.rhrDeltaBpm)
                                            .put("lateNadir", d.lateNadir)
                                            .put("stagesSuspicious", d.stagesSuspicious)
                                            .put("isCalibrating", d.isCalibrating)
                                            .put("hrvScoreContribution", d.hrvScoreContribution)
                                            .put("rhrScoreContribution", d.rhrScoreContribution)
                                            .put("durationScoreContribution", d.durationScoreContribution)
                                            .put("architectureScoreContribution", d.architectureScoreContribution)
                                            .put("loadContribution", d.loadContribution)
                                            .put("sRest", d.sRest)
                                    },
                                ),
                            )

                    val timestamp =
                        Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)
                    val backupFile = File(backupDir, "backup_$timestamp.json")
                    backupFile.writeText(json.toString())

                    backupFile
                }
            }

        fun listBackups(): List<File> =
            backupDir
                .listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

        private suspend fun buildPreferencesJson(): JSONObject {
            val prefs = settingsRepository.userPreferences.first()
            return JSONObject()
                .put("goalSleepHours", prefs.goalSleepHours)
                .put("hrvBaselineOverride", prefs.hrvBaselineOverride ?: JSONObject.NULL)
                .put("rhrBaselineOverride", prefs.rhrBaselineOverride ?: JSONObject.NULL)
                .put("syncPreference", prefs.syncPreference.name)
                .put("syncIntervalHours", prefs.syncIntervalHours)
                .put("maxHeartRate", prefs.maxHeartRate)
                .put("hrvOptimalThreshold", prefs.hrvOptimalThreshold)
                .put("hrvWarningThreshold", prefs.hrvWarningThreshold)
                .put("rhrOptimalThreshold", prefs.rhrOptimalThreshold)
                .put("rhrWarningThreshold", prefs.rhrWarningThreshold)
                .put("restingHrBeforeMinutes", prefs.restingHrBeforeMinutes)
                .put("restingHrAfterMinutes", prefs.restingHrAfterMinutes)
                .put("appTheme", prefs.appTheme.name)
                .put("backupSchedule", prefs.backupSchedule.name)
                .put("birthDay", prefs.birthDay)
                .put("birthMonth", prefs.birthMonth)
                .put("birthYear", prefs.birthYear)
        }

        private fun pruneOldBackups() {
            val now = System.currentTimeMillis()
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
            backupDir
                .listFiles { f -> f.name.startsWith("backup_") && f.isFile }
                ?.filter { now - it.lastModified() > sevenDaysMs }
                ?.forEach { it.delete() }
        }

        companion object {
            private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        }
    }
