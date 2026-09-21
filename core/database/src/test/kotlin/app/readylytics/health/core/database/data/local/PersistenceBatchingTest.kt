package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import java.lang.reflect.Proxy
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class PersistenceBatchingTest {
    @Test
    fun `large sample lists are split into bounded persistence batches`() =
        runTest {
            val batchSizes = mutableListOf<Int>()

            (1..1201).toList().forEachPersistenceBatch { batch ->
                batchSizes += batch.size
            }

            assertEquals(listOf(500, 500, 201), batchSizes)
        }

    @Test
    fun `store persists metadata before bounded heart rate transactions`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val heartRateDao = recordingDao<HeartRateDao>(events, "heartRate")
            val store =
                RoomHealthIngestionStore(
                    daos =
                        HealthRecordDaos(
                            sleepSessionDao = recordingDao(events, "sleep"),
                            sleepStageDao = recordingDao(events, "sleepStage"),
                            heartRateDao = heartRateDao,
                            hrvDao = recordingDao(events, "hrv"),
                            workoutDao = recordingDao(events, "workout"),
                            workoutRoutePointDao = recordingDao(events, "routePoints"),
                            weightRecordDao = recordingDao(events, "weight"),
                            bodyFatRecordDao = recordingDao(events, "bodyFat"),
                            bloodPressureRecordDao = recordingDao(events, "bloodPressure"),
                            oxygenSaturationRecordDao = recordingDao(events, "oxygen"),
                            bodyTemperatureRecordDao = recordingDao(events, "bodyTemperature"),
                            stepRecordDao = recordingDao(events, "steps"),
                            sourceRecordDao = recordingDao(events, "sourceRecord"),
                            minuteBucketMaintenanceDao = recordingDao(events, "minuteBucket"),
                        ),
                    dailySummaryDao = recordingDao(events, "summary"),
                    transactionRunner = transactionRunner,
                    vo2MaxRecordDao = recordingDao(events, "vo2Max"),
                    scanTypeStateDao = recordingDao(events, "scanStaging"),
                )

            store.persist(
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSources = listOf(
                        SourcePayload(
                            SourceMetadata("source-1", 1L, 1_001L),
                            (1..1_001).map { heartRateInput(it).copy(sourceId = "source-1") },
                        ),
                    ),
                    hrvSources = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                ),
            )

            assertEquals(2, transactionRunner.transactionCount)
            assertEquals(
                listOf("sleep:0", "heartRate:500", "heartRate:500", "heartRate:1"),
                events.filter { it.startsWith("sleep:") || it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `persist splits heart rate sources across multiple parent source transactions via the bulk entrypoint`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            val p1 =
                SourcePayload(
                    SourceMetadata("src-1", 1L, 500L),
                    (1..500).map { heartRateInput(it).copy(sourceId = "src-1") },
                )
            val p2 =
                SourcePayload(
                    SourceMetadata("src-2", 501L, 1000L),
                    (501..1000).map { heartRateInput(it).copy(sourceId = "src-2") },
                )
            val p3 =
                SourcePayload(
                    SourceMetadata("src-3", 1001L, 1201L),
                    (1001..1201).map { heartRateInput(it).copy(sourceId = "src-3") },
                )

            store.persist(
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSources = listOf(p1, p2, p3),
                    hrvSources = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                ),
            )

            // 1 metadata transaction + 3 heart-rate parent transactions.
            assertEquals(4, transactionRunner.transactionCount)
            assertEquals(
                listOf("heartRate:500", "heartRate:500", "heartRate:201"),
                events.filter { it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `replaceHeartRateSources splits large parent into 500-sample chunks within parent transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            val payload =
                SourcePayload(
                    SourceMetadata("src-1", 1L, 1201L),
                    (1..1201).map { heartRateInput(it).copy(sourceId = "src-1") },
                )
            store.replaceHeartRateSources(listOf(payload))

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(
                listOf("heartRate:500", "heartRate:500", "heartRate:201"),
                events.filter { it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `replaceHeartRateSources at or below the batch size persists in a single chunk within parent transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            val payload =
                SourcePayload(
                    SourceMetadata("src-1", 1L, 500L),
                    (1..500).map { heartRateInput(it).copy(sourceId = "src-1") },
                )
            store.replaceHeartRateSources(listOf(payload))

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(listOf("heartRate:500"), events.filter { it.startsWith("heartRate:") })
        }

    @Test
    fun `replaceHrvSources splits large parent into multiple 500-sample upsert chunks within parent transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            val payload =
                SourcePayload(
                    SourceMetadata("src-1", 1L, 1200L),
                    (1..1200).map { hrvInput(it).copy(sourceId = "src-1") },
                )
            store.replaceHrvSources(listOf(payload))

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(listOf("hrv:500", "hrv:500", "hrv:200"), events.filter { it.startsWith("hrv:") })
        }

    @Test
    fun `replaceHrvSources at or below the batch size persists in a single chunk within parent transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            val payload =
                SourcePayload(
                    SourceMetadata("src-1", 1L, 499L),
                    (1..499).map { hrvInput(it).copy(sourceId = "src-1") },
                )
            store.replaceHrvSources(listOf(payload))

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(listOf("hrv:499"), events.filter { it.startsWith("hrv:") })
        }

    @Test
    fun `cancellation stops before next persistence batch`() =
        runTest {
            val batchSizes = mutableListOf<Int>()

            val job =
                launch {
                    (1..1000).toList().forEachPersistenceBatch { batch ->
                        batchSizes += batch.size
                        cancel()
                    }
                }
            job.join()

            assertEquals(listOf(500), batchSizes)
        }

    @Test
    fun `persist is idempotent for workout route points`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val routePointDao = FakeWorkoutRoutePointDao()
            val store = buildStore(events, transactionRunner, workoutRoutePointDao = routePointDao)

            val routePoints =
                listOf(
                    WorkoutRoutePoint(workoutId = "w1", latitude = 52.5, longitude = 13.4, timestampMs = 1000L),
                    WorkoutRoutePoint(workoutId = "w1", latitude = 52.6, longitude = 13.5, timestampMs = 2000L),
                )
            val workout =
                WorkoutInput(
                    id = "w1",
                    startTime = 1000L,
                    endTime = 2000L,
                    exerciseType = "Running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 10f,
                    avgHr = 140f,
                    deviceName = null,
                    routePoints = routePoints,
                )
            val batch =
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = emptyList(),
                    hrvSamples = emptyList(),
                    workouts = listOf(workout),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                )

            store.persist(batch)
            store.persist(batch) // Second ingestion of the same batch

            val stored = routePointDao.getRoutePoints("w1")
            assertEquals(2, stored.size)
        }

    @Test
    fun `persistSingleWorkoutRoute replaces existing route points idempotently`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val routePointDao = FakeWorkoutRoutePointDao()
            val store = buildStore(events, transactionRunner, routePointDao)

            val initialPoints =
                listOf(
                    WorkoutRoutePoint(workoutId = "w1", latitude = 52.5, longitude = 13.4, timestampMs = 1000L),
                )
            store.persistSingleWorkoutRoute(
                workoutId = "w1",
                routePoints = initialPoints,
                routeState = "IMPORTED",
                totalDistanceMeters = 1000f,
                avgSpeedKmh = 10f,
                elevationGainMeters = 5f,
            )
            assertEquals(1, routePointDao.getRoutePoints("w1").size)

            val updatedPoints =
                listOf(
                    WorkoutRoutePoint(workoutId = "w1", latitude = 52.5, longitude = 13.4, timestampMs = 1000L),
                    WorkoutRoutePoint(workoutId = "w1", latitude = 52.6, longitude = 13.5, timestampMs = 2000L),
                )
            store.persistSingleWorkoutRoute(
                workoutId = "w1",
                routePoints = updatedPoints,
                routeState = "IMPORTED",
                totalDistanceMeters = 2000f,
                avgSpeedKmh = 12f,
                elevationGainMeters = 10f,
            )

            val stored = routePointDao.getRoutePoints("w1")
            assertEquals(2, stored.size)
        }

    private class RecordingTransactionRunner(
        private val events: MutableList<String>,
    ) : TransactionRunner {
        var transactionCount = 0
            private set

        override suspend fun <R> runInTransaction(block: suspend () -> R): R {
            transactionCount++
            events += "transaction:$transactionCount"
            return block()
        }
    }

    private class FakeWorkoutRoutePointDao : WorkoutRoutePointDao {
        private val points = mutableListOf<WorkoutRoutePointEntity>()

        override suspend fun insertAll(points: List<WorkoutRoutePointEntity>) {
            this.points.addAll(points)
        }

        override suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePointEntity> =
            points.filter { it.workoutId == workoutId }.sortedBy { it.timestampMs }

        override suspend fun deleteByWorkoutId(workoutId: String): Int {
            val before = points.size
            points.removeAll { it.workoutId == workoutId }
            return before - points.size
        }

        override suspend fun deleteForWorkouts(workoutIds: List<String>): Int {
            val before = points.size
            points.removeAll { it.workoutId in workoutIds }
            return before - points.size
        }

        override suspend fun count(): Int = points.size

        override suspend fun deleteAll(): Int {
            val count = points.size
            points.clear()
            return count
        }


        override suspend fun pageAfter(
            afterId: Long,
            limit: Int,
        ): List<WorkoutRoutePointEntity> = points.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
    }

    private inline fun <reified T> recordingDao(
        events: MutableList<String>,
        name: String,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            if (method.name == "upsertAll") {
                events += "$name:${(args?.firstOrNull() as? List<*>)?.size ?: 0}"
            }
            resolveProxyReturnValue(method.name, method.returnType)
        } as T

    private fun resolveProxyReturnValue(methodName: String, returnType: Class<*>): Any? =
        when (methodName) {
            "getOrCreateSourceRef", "getSourceRef", "insertIgnore" -> 1L
            "getModelTrimpById", "getById", "getBySourceRecordId" -> null
            "getTimestampsBySourceRecordRef", "getBySourceRecordRef", "getSourcesByRecordIds" -> emptyList<Any>()
            "deleteBySourceRecordRefAndTimestamps",
            "deleteBySourceRecordRef",
            "deleteBySourceRecordId",
            "updateAuthoritativeMetadata",
            "insertIgnoreAll",
            -> 1
            else -> fallbackForType(returnType)
        }

    private fun fallbackForType(returnType: Class<*>): Any? =
        when {
            returnType == List::class.java -> emptyList<Any>()
            returnType == java.lang.Integer.TYPE -> 1
            returnType == java.lang.Long.TYPE || returnType == Long::class.javaObjectType -> 1L
            else -> Unit
        }

    private fun buildStore(
        events: MutableList<String>,
        transactionRunner: TransactionRunner,
        workoutRoutePointDao: WorkoutRoutePointDao = recordingDao(events, "routePoints"),
    ): RoomHealthIngestionStore =
        RoomHealthIngestionStore(
            daos =
                HealthRecordDaos(
                    sleepSessionDao = recordingDao(events, "sleep"),
                    sleepStageDao = recordingDao(events, "sleepStage"),
                    heartRateDao = recordingDao(events, "heartRate"),
                    hrvDao = recordingDao(events, "hrv"),
                    workoutDao = recordingDao(events, "workout"),
                    workoutRoutePointDao = workoutRoutePointDao,
                    weightRecordDao = recordingDao(events, "weight"),
                    bodyFatRecordDao = recordingDao(events, "bodyFat"),
                    bloodPressureRecordDao = recordingDao(events, "bloodPressure"),
                    oxygenSaturationRecordDao = recordingDao(events, "oxygen"),
                    bodyTemperatureRecordDao = recordingDao(events, "bodyTemperature"),
                    stepRecordDao = recordingDao(events, "steps"),
                    sourceRecordDao = recordingDao(events, "sourceRecord"),
                    minuteBucketMaintenanceDao = recordingDao(events, "minuteBucket"),
                ),
            dailySummaryDao = recordingDao(events, "summary"),
            transactionRunner = transactionRunner,
            vo2MaxRecordDao = recordingDao(events, "vo2Max"),
            scanTypeStateDao = recordingDao(events, "scanStaging"),
        )

    private fun heartRateInput(index: Int) =
        HeartRateInput(
            id = "hr-$index",
            timestampMs = index.toLong(),
            beatsPerMinute = 60,
            recordType = "SLEEP",
            sessionId = null,
            deviceName = null,
        )

    private fun hrvInput(index: Int) =
        HrvInput(
            id = "hrv-$index",
            timestampMs = index.toLong(),
            rmssdMs = 40f,
            recordType = "SLEEP",
            sessionId = null,
            deviceName = null,
        )
}
