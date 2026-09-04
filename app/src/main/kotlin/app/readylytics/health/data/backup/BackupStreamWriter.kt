package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.data.preferences.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupStreamWriter
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val layoutRepositories: RestoreLayoutRepositories,
    ) {
        private val json = Json { encodeDefaults = true }

        suspend fun writeJsonStreaming(outputStream: OutputStream) {
            val writer = outputStream.bufferedWriter()
            writer.write("{\n")
            writer.write("  \"schemaVersion\": ${HealthDatabase.DATABASE_VERSION},\n")
            writer.write("  \"exportedAt\": \"${Instant.now()}\",\n")

            val rowCounts = collectRowCounts()
            writer.write("  \"rowCounts\": ${json.encodeToString(rowCounts)},\n")

            writer.write("  \"preferences\": ")
            writePreferences(writer)
            writer.write(",\n")

            writeCoreTables(writer)
            writeActivityTables(writer)
            writeBodyVitalsTables(writer)
            writeOtherVitalsTables(writer)

            writer.write("\n}\n")
            writer.flush()
        }

        private suspend fun collectRowCounts(): Map<String, Int> =
            coroutineScope {
                val counts =
                    listOf(
                        "sleepSessions" to async { healthDatabase.sleepSessionDao().count() },
                        "heartRateRecords" to async { healthDatabase.heartRateDao().count() },
                        "hrvRecords" to async { healthDatabase.hrvDao().count() },
                        "workouts" to async { healthDatabase.workoutDao().count() },
                        "workoutRoutePoints" to async { healthDatabase.workoutRoutePointDao().count() },
                        "dailySummaries" to async { healthDatabase.dailySummaryDao().count() },
                        "weightRecords" to async { healthDatabase.weightRecordDao().count() },
                        "bodyFatRecords" to async { healthDatabase.bodyFatRecordDao().count() },
                        "bloodPressureRecords" to async { healthDatabase.bloodPressureRecordDao().count() },
                        "oxygenSaturationRecords" to async { healthDatabase.oxygenSaturationRecordDao().count() },
                        "bodyTemperatureRecords" to async { healthDatabase.bodyTemperatureRecordDao().count() },
                        "stepRecords" to async { healthDatabase.stepRecordDao().count() },
                        "healthSourceRecords" to async { healthDatabase.sourceRecordDao().count() },
                        "hrMinuteBuckets" to async { healthDatabase.minuteBucketMaintenanceDao().count() },
                        "vo2MaxRecords" to async { healthDatabase.vo2MaxRecordDao().count() },
                    )
                counts.associate { (key, deferred) -> key to deferred.await() }
            }

        private suspend fun writeCoreTables(writer: BufferedWriter) {
            val sleepSessionDao = healthDatabase.sleepSessionDao()
            val sourceRecordDao = healthDatabase.sourceRecordDao()

            var sleepAfterTs = Long.MIN_VALUE
            var sleepAfterId = ""
            writeTable<SleepSessionEntity>(
                writer,
                "sleepSessions",
                page = { sleepSessionDao.pageAfter(0, sleepAfterTs, sleepAfterId, 100) },
                advance = {
                    sleepAfterTs = it.startTime
                    sleepAfterId = it.id
                },
            )
            writer.write(",\n")

            writer.write("  \"healthSourceRecords\": [\n")
            currentCoroutineContext().ensureActive()
            val sourceRecords = sourceRecordDao.getAll()
            var first = true
            sourceRecords.forEach {
                if (!first) writer.write(",\n")
                writer.write("    ${json.encodeToString(it)}")
                first = false
            }
            writer.write("\n  ],\n")

            writeHeartRateTables(writer)
        }

        private suspend fun writeHeartRateTables(writer: BufferedWriter) {
            val heartRateDao = healthDatabase.heartRateDao()
            val hrvDao = healthDatabase.hrvDao()
            val minuteBucketMaintenanceDao = healthDatabase.minuteBucketMaintenanceDao()

            var hrAfterTs = Long.MIN_VALUE
            var hrAfterRef = Long.MIN_VALUE
            writeTable<HeartRateRecordEntity>(
                writer,
                "heartRateRecords",
                page = { heartRateDao.pageAfter(0, hrAfterTs, hrAfterRef, 500) },
                advance = {
                    hrAfterTs = it.timestampMs
                    hrAfterRef = it.sourceRecordRef
                },
            )
            writer.write(",\n")

            var hrvAfterTs = Long.MIN_VALUE
            var hrvAfterRef = Long.MIN_VALUE
            writeTable<HrvRecordEntity>(
                writer,
                "hrvRecords",
                page = { hrvDao.pageAfter(0, hrvAfterTs, hrvAfterRef, 500) },
                advance = {
                    hrvAfterTs = it.timestampMs
                    hrvAfterRef = it.sourceRecordRef
                },
            )
            writer.write(",\n")

            var mbAfterTs = Long.MIN_VALUE
            var mbAfterRecordType = ""
            var mbAfterSessionId = ""
            var mbAfterDeviceName = ""
            writeTable<HrMinuteBucketEntity>(
                writer,
                "hrMinuteBuckets",
                page = {
                    minuteBucketMaintenanceDao.pageAfter(
                        mbAfterTs,
                        mbAfterRecordType,
                        mbAfterSessionId,
                        mbAfterDeviceName,
                        500,
                    )
                },
                advance = {
                    mbAfterTs = it.bucketStartMs
                    mbAfterRecordType = it.recordType
                    mbAfterSessionId = it.sessionId
                    mbAfterDeviceName = it.deviceName
                },
            )
            writer.write(",\n")
        }

        private suspend fun writeActivityTables(writer: BufferedWriter) {
            val workoutDao = healthDatabase.workoutDao()
            val workoutRoutePointDao = healthDatabase.workoutRoutePointDao()

            var workoutAfterTs = Long.MIN_VALUE
            var workoutAfterId = ""
            writeTable<WorkoutRecordEntity>(
                writer,
                "workouts",
                page = { workoutDao.pageAfter(0, workoutAfterTs, workoutAfterId, 100) },
                advance = {
                    workoutAfterTs = it.startTime
                    workoutAfterId = it.id
                },
            )
            writer.write(",\n")

            var routeAfterId = Long.MIN_VALUE
            writeTable<WorkoutRoutePointEntity>(
                writer,
                "workoutRoutePoints",
                page = { workoutRoutePointDao.pageAfter(routeAfterId, 500) },
                advance = { routeAfterId = it.id },
            )
            writer.write(",\n")
        }

        private suspend fun writeBodyVitalsTables(writer: BufferedWriter) {
            val dailySummaryDao = healthDatabase.dailySummaryDao()
            val weightRecordDao = healthDatabase.weightRecordDao()
            val bodyFatRecordDao = healthDatabase.bodyFatRecordDao()

            var summaryAfterTs = Long.MIN_VALUE
            writeTable<DailySummaryEntity>(
                writer,
                "dailySummaries",
                page = { dailySummaryDao.pageAfter(0, summaryAfterTs, 100) },
                advance = { summaryAfterTs = it.dateMidnightMs },
            )
            writer.write(",\n")

            var weightAfterTs = Long.MIN_VALUE
            var weightAfterId = ""
            writeTable<WeightRecordEntity>(
                writer,
                "weightRecords",
                page = { weightRecordDao.pageAfter(0, weightAfterTs, weightAfterId, 100) },
                advance = {
                    weightAfterTs = it.timestampMs
                    weightAfterId = it.id
                },
            )
            writer.write(",\n")

            var bodyFatAfterTs = Long.MIN_VALUE
            var bodyFatAfterId = ""
            writeTable<BodyFatRecordEntity>(
                writer,
                "bodyFatRecords",
                page = { bodyFatRecordDao.pageAfter(0, bodyFatAfterTs, bodyFatAfterId, 100) },
                advance = {
                    bodyFatAfterTs = it.timestampMs
                    bodyFatAfterId = it.id
                },
            )
            writer.write(",\n")
        }

        private suspend fun writeOtherVitalsTables(writer: BufferedWriter) {
            val bloodPressureRecordDao = healthDatabase.bloodPressureRecordDao()
            val oxygenSaturationRecordDao = healthDatabase.oxygenSaturationRecordDao()
            val bodyTemperatureRecordDao = healthDatabase.bodyTemperatureRecordDao()
            val stepRecordDao = healthDatabase.stepRecordDao()

            var bpAfterTs = Long.MIN_VALUE
            var bpAfterId = ""
            writeTable<BloodPressureRecordEntity>(
                writer,
                "bloodPressureRecords",
                page = { bloodPressureRecordDao.pageAfter(0, bpAfterTs, bpAfterId, 100) },
                advance = {
                    bpAfterTs = it.timestampMs
                    bpAfterId = it.id
                },
            )
            writer.write(",\n")

            var o2AfterTs = Long.MIN_VALUE
            var o2AfterId = ""
            writeTable<OxygenSaturationRecordEntity>(
                writer,
                "oxygenSaturationRecords",
                page = { oxygenSaturationRecordDao.pageAfter(0, o2AfterTs, o2AfterId, 100) },
                advance = {
                    o2AfterTs = it.timestampMs
                    o2AfterId = it.id
                },
            )
            writer.write(",\n")

            var tempAfterTs = Long.MIN_VALUE
            var tempAfterId = ""
            writeTable<BodyTemperatureRecordEntity>(
                writer,
                "bodyTemperatureRecords",
                page = { bodyTemperatureRecordDao.pageAfter(0, tempAfterTs, tempAfterId, 100) },
                advance = {
                    tempAfterTs = it.timestampMs
                    tempAfterId = it.id
                },
            )
            writer.write(",\n")

            writeStepAndVo2MaxTables(writer)
        }

        private suspend fun writeStepAndVo2MaxTables(writer: BufferedWriter) {
            val stepRecordDao = healthDatabase.stepRecordDao()
            var stepAfterTs = Long.MIN_VALUE
            var stepAfterId = ""
            writeTable<StepRecordEntity>(
                writer,
                "stepRecords",
                page = { stepRecordDao.pageAfter(0, stepAfterTs, stepAfterId, 500) },
                advance = {
                    stepAfterTs = it.startTime
                    stepAfterId = it.id
                },
            )
            writer.write(",\n")

            val vo2MaxRecordDao = healthDatabase.vo2MaxRecordDao()
            var vo2MaxAfterTs = Long.MIN_VALUE
            var vo2MaxAfterId = ""
            writeTable<Vo2MaxRecordEntity>(
                writer,
                "vo2MaxRecords",
                page = { vo2MaxRecordDao.pageAfter(0, vo2MaxAfterTs, vo2MaxAfterId, 100) },
                advance = {
                    vo2MaxAfterTs = it.timestampMs
                    vo2MaxAfterId = it.id
                },
            )
        }

        private suspend fun writePreferences(writer: BufferedWriter) {
            val prefs = settingsRepository.userPreferences.first()
            val layouts =
                BackupLayoutSnapshots(
                    dashboardCards =
                        layoutRepositories.cardConfigurationRepository
                            .dashboardCardConfigurations()
                            .first(),
                    vitalsCards = layoutRepositories.vitalsLayoutRepository.vitalsCardConfigurations().first(),
                    vitalsCharts = layoutRepositories.vitalsLayoutRepository.vitalsChartConfigurations().first(),
                    sleepTopCards = layoutRepositories.sleepLayoutRepository.sleepTopCardConfigurations().first(),
                    sleepCharts = layoutRepositories.sleepLayoutRepository.sleepChartConfigurations().first(),
                    sleepMetricCards = layoutRepositories.sleepLayoutRepository.sleepMetricCardConfigurations().first(),
                    workoutCards = layoutRepositories.workoutsLayoutRepository.workoutCardConfigurations().first(),
                    workoutCharts = layoutRepositories.workoutsLayoutRepository.workoutChartConfigurations().first(),
                    workoutHistory = layoutRepositories.workoutsLayoutRepository.workoutHistoryConfigurations().first(),
                    workoutDetailLayouts =
                        layoutRepositories.workoutDetailLayoutRepository
                            .allLayouts()
                            .first()
                            .mapKeys { it.key.name },
                )
            val backup = buildUserPreferencesBackup(prefs, layouts)
            writer.write(json.encodeToString(backup))
        }

        private suspend inline fun <reified T> writeTable(
            writer: BufferedWriter,
            name: String,
            page: () -> List<T>,
            advance: (T) -> Unit,
        ) {
            writer.write("  \"$name\": [\n")
            var first = true
            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = page()
                if (chunk.isEmpty()) break
                for (item in chunk) {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(item)}")
                    first = false
                    advance(item)
                }
            }
            writer.write("\n  ]")
        }
    }
