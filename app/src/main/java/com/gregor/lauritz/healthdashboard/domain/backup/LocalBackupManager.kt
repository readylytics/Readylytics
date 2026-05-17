package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import androidx.documentfile.provider.DocumentFile
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
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
        private val defaultBackupDir = File(context.filesDir, "backups")

        suspend fun createBackup(): Result<File?> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val prefs = settingsRepository.userPreferences.first()
                    val customUri = prefs.backupDirectoryUri?.let { Uri.parse(it) }

                    val timestamp =
                        Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)
                    val filename = "backup_$timestamp.json"

                    val outputStream: OutputStream
                    var resultFile: File? = null

                    if (customUri != null) {
                        val dir =
                            DocumentFile.fromTreeUri(context, customUri)
                                ?: throw IllegalStateException("Could not access custom backup directory")
                        val file =
                            dir.createFile("application/json", filename)
                                ?: throw IllegalStateException("Could not create backup file in custom directory")
                        outputStream = context.contentResolver.openOutputStream(file.uri)
                            ?: throw IllegalStateException("Could not open output stream for custom backup file")
                    } else {
                        defaultBackupDir.mkdirs()
                        pruneOldBackups(defaultBackupDir)
                        val file = File(defaultBackupDir, filename)
                        outputStream = FileOutputStream(file)
                        resultFile = file
                    }

                    val sleepSessionDao = healthDatabase.sleepSessionDao()
                    val heartRateDao = healthDatabase.heartRateDao()
                    val hrvDao = healthDatabase.hrvDao()
                    val workoutDao = healthDatabase.workoutDao()
                    val dailySummaryDao = healthDatabase.dailySummaryDao()

                    val rowCounts =
                        mapOf(
                            "sleepSessions" to sleepSessionDao.count(),
                            "heartRateRecords" to heartRateDao.count(),
                            "hrvRecords" to hrvDao.count(),
                            "workouts" to workoutDao.count(),
                            "dailySummaries" to dailySummaryDao.count(),
                        )

                    outputStream.use { os ->
                        JsonWriter(OutputStreamWriter(os, "UTF-8")).use { writer ->
                            writer.setIndent("  ")
                            writer.beginObject()

                            writer.name("schemaVersion").value(HealthDatabase.DATABASE_VERSION.toLong())
                            writer.name("exportedAt").value(Instant.now().toString())

                            writer.name("rowCounts")
                            writer.beginObject()
                            rowCounts.forEach { (key, value) ->
                                writer.name(key).value(value.toLong())
                            }
                            writer.endObject()

                            writer.name("preferences")
                            writePreferences(writer)

                            writer.name("sleepSessions")
                            writer.beginArray()
                            sleepSessionDao.getSince(0).forEach { s ->
                                writeSleepSession(writer, s)
                            }
                            writer.endArray()

                            writer.name("heartRateRecords")
                            writer.beginArray()
                            heartRateDao.getSince(0).forEach { h ->
                                writeHeartRateRecord(writer, h)
                            }
                            writer.endArray()

                            writer.name("hrvRecords")
                            writer.beginArray()
                            hrvDao.getSince(0).forEach { hrv ->
                                writeHrvRecord(writer, hrv)
                            }
                            writer.endArray()

                            writer.name("workouts")
                            writer.beginArray()
                            workoutDao.getSince(0).forEach { w ->
                                writeWorkout(writer, w)
                            }
                            writer.endArray()

                            writer.name("dailySummaries")
                            writer.beginArray()
                            dailySummaryDao.getSince(0).forEach { d ->
                                writeDailySummary(writer, d)
                            }
                            writer.endArray()

                            writer.endObject()
                        }
                    }

                    resultFile
                }
            }

        private fun writeSleepSession(
            writer: JsonWriter,
            s: SleepSessionEntity,
        ) {
            writer.beginObject()
            writer.name("id").value(s.id)
            writer.name("startTime").value(s.startTime)
            writer.name("endTime").value(s.endTime)
            writer.name("durationMinutes").value(s.durationMinutes.toLong())
            writer.name("efficiency").value(s.efficiency?.toDouble() ?: 0.0)
            writer.name("deepSleepMinutes").value(s.deepSleepMinutes?.toLong() ?: 0)
            writer.name("remSleepMinutes").value(s.remSleepMinutes?.toLong() ?: 0)
            writer.name("lightSleepMinutes").value(s.lightSleepMinutes?.toLong() ?: 0)
            writer.name("awakeMinutes").value(s.awakeMinutes?.toLong() ?: 0)
            writer.name("sleepScore").value(s.sleepScore?.toDouble() ?: 0.0)
            writer.name("startZoneOffsetSeconds").value(s.startZoneOffsetSeconds?.toLong() ?: 0)
            writer.name("endZoneOffsetSeconds").value(s.endZoneOffsetSeconds?.toLong() ?: 0)
            writer.name("deviceName").value(s.deviceName)
            writer.endObject()
        }

        private fun writeHeartRateRecord(
            writer: JsonWriter,
            h: HeartRateRecordEntity,
        ) {
            writer.beginObject()
            writer.name("id").value(h.id)
            writer.name("timestampMs").value(h.timestampMs)
            writer.name("beatsPerMinute").value(h.beatsPerMinute.toLong())
            writer.name("recordType").value(h.recordType)
            writer.name("sessionId").value(h.sessionId)
            writer.name("deviceName").value(h.deviceName)
            writer.endObject()
        }

        private fun writeHrvRecord(
            writer: JsonWriter,
            h: HrvRecordEntity,
        ) {
            writer.beginObject()
            writer.name("id").value(h.id)
            writer.name("timestampMs").value(h.timestampMs)
            writer.name("rmssdMs").value(h.rmssdMs.toDouble())
            writer.name("recordType").value(h.recordType)
            writer.name("sessionId").value(h.sessionId)
            writer.name("deviceName").value(h.deviceName)
            writer.endObject()
        }

        private fun writeWorkout(
            writer: JsonWriter,
            w: WorkoutRecordEntity,
        ) {
            writer.beginObject()
            writer.name("id").value(w.id)
            writer.name("startTime").value(w.startTime)
            writer.name("endTime").value(w.endTime)
            writer.name("exerciseType").value(w.exerciseType)
            writer.name("durationMinutes").value(w.durationMinutes.toLong())
            writer.name("zone1Minutes").value(w.zone1Minutes.toLong())
            writer.name("zone2Minutes").value(w.zone2Minutes.toLong())
            writer.name("zone3Minutes").value(w.zone3Minutes.toLong())
            writer.name("zone4Minutes").value(w.zone4Minutes.toLong())
            writer.name("zone5Minutes").value(w.zone5Minutes.toLong())
            writer.name("trimp").value(w.trimp.toDouble())
            writer.name("avgHr").value(w.avgHr.toDouble())
            writer.name("deviceName").value(w.deviceName)
            writer.endObject()
        }

        private fun writeDailySummary(
            writer: JsonWriter,
            d: DailySummaryEntity,
        ) {
            writer.beginObject()
            writer.name("dateMidnightMs").value(d.dateMidnightMs)
            writer.name("sleepScore").value(d.sleepScore?.toDouble() ?: 0.0)
            writer.name("loadScore").value(d.loadScore?.toDouble() ?: 0.0)
            writer.name("readinessScore").value(d.readinessScore?.toDouble() ?: 0.0)
            writer.name("strainRatio").value(d.strainRatio?.toDouble() ?: 0.0)
            writer.name("nocturnalRhr").value(d.nocturnalRhr?.toLong() ?: 0)
            writer.name("nocturnalHrv").value(d.nocturnalHrv?.toLong() ?: 0)
            writer.name("sleepDurationMinutes").value(d.sleepDurationMinutes?.toLong() ?: 0)
            writer.name("deepSleepPercent").value(d.deepSleepPercent?.toDouble() ?: 0.0)
            writer.name("remSleepPercent").value(d.remSleepPercent?.toDouble() ?: 0.0)
            writer.name("totalTrimp").value(d.totalTrimp?.toDouble() ?: 0.0)
            writer.name("rhrRatio").value(d.rhrRatio?.toDouble() ?: 0.0)
            writer.name("hrvBaseline").value(d.hrvBaseline?.toLong() ?: 0)
            writer.name("restingHeartRate").value(d.restingHeartRate?.toLong() ?: 0)
            writer.name("restingHrRatio").value(d.restingHrRatio?.toDouble() ?: 0.0)
            writer.name("restingHrBaseline").value(d.restingHrBaseline?.toLong() ?: 0)
            writer.name("paiScore").value(d.paiScore?.toDouble() ?: 0.0)
            writer.name("totalPai").value(d.totalPai?.toDouble() ?: 0.0)
            writer.name("stepCount").value(d.stepCount?.toLong() ?: 0)
            writer.name("zLnHrv").value(d.zLnHrv?.toDouble() ?: 0.0)
            writer.name("zRhr").value(d.zRhr?.toDouble() ?: 0.0)
            writer.name("recoveryFlags").value(d.recoveryFlags)
            writer.name("hrvSigma").value(d.hrvSigma?.toDouble() ?: 0.0)

            writer.name("diagnostics")
            writer.beginObject()
            writer.name("zLnHrv").value(d.diagnostics.zLnHrv?.toDouble() ?: 0.0)
            writer.name("zRhr").value(d.diagnostics.zRhr?.toDouble() ?: 0.0)
            writer.name("lnSigma").value(d.diagnostics.lnSigma?.toDouble() ?: 0.0)
            writer.name("rollingMu").value(d.diagnostics.rollingMu?.toDouble() ?: 0.0)
            writer.name("rhrDeltaBpm").value(d.diagnostics.rhrDeltaBpm?.toDouble() ?: 0.0)
            writer.name("isCalibrating").value(d.diagnostics.isCalibrating)
            writer.name("stagesSuspicious").value(d.diagnostics.stagesSuspicious)
            writer.name("lateNadir").value(d.diagnostics.lateNadir)
            writer.name("hrvMissing").value(d.diagnostics.hrvMissing)
            writer.name("timezoneJump").value(d.diagnostics.timezoneJump)
            writer.name("configHashCode").value(d.diagnostics.configHashCode?.toLong() ?: 0)
            writer.name("phaseName").value(d.diagnostics.phaseName)
            writer.endObject()

            writer.name("contributors")
            writer.beginObject()
            writer.name("hrvScore").value(d.contributors.hrvScore?.toDouble() ?: 0.0)
            writer.name("rhrScore").value(d.contributors.rhrScore?.toDouble() ?: 0.0)
            writer.name("durationScore").value(d.contributors.durationScore?.toDouble() ?: 0.0)
            writer.name("architectureScore").value(d.contributors.architectureScore?.toDouble() ?: 0.0)
            writer.name("loadContribution").value(d.contributors.loadContribution?.toDouble() ?: 0.0)
            writer.endObject()

            writer.name("rollingMu").value(d.rollingMu?.toDouble() ?: 0.0)
            writer.name("rhrDeltaBpm").value(d.rhrDeltaBpm?.toDouble() ?: 0.0)
            writer.name("lateNadir").value(d.lateNadir ?: false)
            writer.name("stagesSuspicious").value(d.stagesSuspicious ?: false)
            writer.name("isCalibrating").value(d.isCalibrating ?: false)
            writer.name("hrvScoreContribution").value(d.hrvScoreContribution?.toDouble() ?: 0.0)
            writer.name("rhrScoreContribution").value(d.rhrScoreContribution?.toDouble() ?: 0.0)
            writer.name("durationScoreContribution").value(d.durationScoreContribution?.toDouble() ?: 0.0)
            writer.name("architectureScoreContribution").value(d.architectureScoreContribution?.toDouble() ?: 0.0)
            writer.name("loadContribution").value(d.loadContribution?.toDouble() ?: 0.0)
            writer.name("sRest").value(d.sRest?.toDouble() ?: 0.0)
            writer.endObject()
        }

        private suspend fun writePreferences(writer: JsonWriter) {
            val prefs = settingsRepository.userPreferences.first()
            writer.beginObject()
            writer.name("goalSleepHours").value(prefs.goalSleepHours.toDouble())
            if (prefs.hrvBaselineOverride != null) {
                writer.name("hrvBaselineOverride").value(prefs.hrvBaselineOverride.toDouble())
            } else {
                writer.name("hrvBaselineOverride").nullValue()
            }
            if (prefs.rhrBaselineOverride != null) {
                writer.name("rhrBaselineOverride").value(prefs.rhrBaselineOverride.toDouble())
            } else {
                writer.name("rhrBaselineOverride").nullValue()
            }
            writer.name("syncPreference").value(prefs.syncPreference.name)
            writer.name("syncIntervalHours").value(prefs.syncIntervalHours.toLong())
            writer.name("maxHeartRate").value(prefs.maxHeartRate.toLong())
            writer.name("hrvOptimalThreshold").value(prefs.hrvOptimalThreshold.toLong())
            writer.name("hrvWarningThreshold").value(prefs.hrvWarningThreshold.toLong())
            writer.name("rhrOptimalThreshold").value(prefs.rhrOptimalThreshold.toLong())
            writer.name("rhrWarningThreshold").value(prefs.rhrWarningThreshold.toLong())
            writer.name("restingHrBeforeMinutes").value(prefs.restingHrBeforeMinutes.toLong())
            writer.name("restingHrAfterMinutes").value(prefs.restingHrAfterMinutes.toLong())
            writer.name("appTheme").value(prefs.appTheme.name)
            writer.name("backupSchedule").value(prefs.backupSchedule.name)
            writer.name("birthDay").value(prefs.birthDay.toLong())
            writer.name("birthMonth").value(prefs.birthMonth.toLong())
            writer.name("birthYear").value(prefs.birthYear.toLong())
            writer.endObject()
        }

        suspend fun listBackups(): List<BackupFileInfo> =
            withContext(Dispatchers.IO) {
                val prefs = settingsRepository.userPreferences.first()
                val customUri = prefs.backupDirectoryUri?.let { Uri.parse(it) }

                if (customUri != null) {
                    val dir = DocumentFile.fromTreeUri(context, customUri)
                    dir
                        ?.listFiles()
                        ?.filter { it.name?.startsWith("backup_") == true && it.name?.endsWith(".json") == true }
                        ?.map { BackupFileInfo(it.name!!, it.lastModified(), it.length(), it.uri) }
                        ?.sortedByDescending { it.lastModified }
                        ?: emptyList()
                } else {
                    defaultBackupDir.mkdirs()
                    defaultBackupDir
                        .listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".json") }
                        ?.map { BackupFileInfo(it.name, it.lastModified(), it.length(), Uri.fromFile(it)) }
                        ?.sortedByDescending { it.lastModified }
                        ?: emptyList()
                }
            }

        private fun pruneOldBackups(dir: File) {
            val now = System.currentTimeMillis()
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
            dir
                .listFiles { f -> f.name.startsWith("backup_") && f.isFile }
                ?.filter { now - it.lastModified() > sevenDaysMs }
                ?.forEach { it.delete() }
        }

        companion object {
            private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        }
    }

data class BackupFileInfo(
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val uri: Uri,
)
