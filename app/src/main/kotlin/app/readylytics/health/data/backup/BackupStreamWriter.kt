package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    ) {
        private val json = Json { encodeDefaults = true }

        suspend fun writeJsonStreaming(
            outputStream: OutputStream,
            preferences: UserPreferencesBackup,
            identity: BackupSnapshotIdentity,
        ) = writeJsonStreaming(outputStream, preferences, identity, pageHook = null)

        suspend fun writeJsonStreaming(
            outputStream: OutputStream,
            preferences: UserPreferencesBackup,
            identity: BackupSnapshotIdentity,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
            val writer = outputStream.bufferedWriter()
            writer.write("{\n")
            writer.write("  \"schemaVersion\": ${identity.schemaVersion},\n")
            writer.write("  \"exportedAt\": \"${Instant.ofEpochMilli(identity.exportedAtEpochMs)}\",\n")
            writer.write("  \"sourceGeneration\": ${identity.sourceGeneration},\n")
            writer.write("  \"scoringSnapshotId\": \"${identity.scoringSnapshotId}\",\n")

            val rowCounts = collectRowCounts()
            writer.write("  \"rowCounts\": ${json.encodeToString(rowCounts)},\n")

            writer.write("  \"preferences\": ")
            writer.write(json.encodeToString(preferences))
            writer.write(",\n")

            writeCoreTables(writer, pageHook)
            writeActivityTables(writer, pageHook)
            writeBodyVitalsTables(writer, pageHook)
            writeOtherVitalsTables(writer, pageHook)

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

        private suspend fun writeCoreTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
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
                pageHook = pageHook,
            )
            writer.write(",\n")

            var sourceAfterId = 0L
            writeTable<HealthSourceRecordEntity>(
                writer,
                "healthSourceRecords",
                page = { sourceRecordDao.pageAfter(sourceAfterId, 500) },
                advance = { sourceAfterId = it.id },
                pageHook = pageHook,
            )
            writer.write(",\n")

            writeHeartRateTables(writer, pageHook)
        }

        private suspend fun writeHeartRateTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
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
                pageHook = pageHook,
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
                pageHook = pageHook,
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
                pageHook = pageHook,
            )
            writer.write(",\n")
        }

        private suspend fun writeActivityTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
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
                pageHook = pageHook,
            )
            writer.write(",\n")

            var routeAfterId = Long.MIN_VALUE
            writeTable<WorkoutRoutePointEntity>(
                writer,
                "workoutRoutePoints",
                page = { workoutRoutePointDao.pageAfter(routeAfterId, 500) },
                advance = { routeAfterId = it.id },
                pageHook = pageHook,
            )
            writer.write(",\n")
        }

        private suspend fun writeBodyVitalsTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
            val dailySummaryDao = healthDatabase.dailySummaryDao()
            val weightRecordDao = healthDatabase.weightRecordDao()
            val bodyFatRecordDao = healthDatabase.bodyFatRecordDao()

            var summaryAfterTs = Long.MIN_VALUE
            writeTable<DailySummaryEntity>(
                writer,
                "dailySummaries",
                page = { dailySummaryDao.pageAfter(0, summaryAfterTs, 100) },
                advance = { summaryAfterTs = it.dateMidnightMs },
                pageHook = pageHook,
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
                pageHook = pageHook,
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
                pageHook = pageHook,
            )
            writer.write(",\n")
        }

        private suspend fun writeOtherVitalsTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
            val bloodPressureRecordDao = healthDatabase.bloodPressureRecordDao()
            val oxygenSaturationRecordDao = healthDatabase.oxygenSaturationRecordDao()
            val bodyTemperatureRecordDao = healthDatabase.bodyTemperatureRecordDao()

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
                pageHook = pageHook,
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
                pageHook = pageHook,
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
                pageHook = pageHook,
            )
            writer.write(",\n")

            writeStepAndVo2MaxTables(writer, pageHook)
        }

        private suspend fun writeStepAndVo2MaxTables(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
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
                pageHook = pageHook,
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
                pageHook = pageHook,
            )
        }

        private suspend inline fun <reified T> writeTable(
            writer: BufferedWriter,
            name: String,
            page: () -> List<T>,
            advance: (T) -> Unit,
            noinline pageHook: (suspend (tableName: String) -> Unit)? = null,
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
                pageHook?.invoke(name)
            }
            writer.write("\n  ]")
        }
    }
