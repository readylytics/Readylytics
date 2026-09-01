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
                            minuteBucketDao = recordingDao(events, "minuteBucket"),
                        ),
                    dailySummaryDao = recordingDao(events, "summary"),
                    transactionRunner = transactionRunner,
                )

            store.persist(
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = (1..1_001).map(::heartRateInput),
                    hrvSamples = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                ),
            )

            assertEquals(4, transactionRunner.transactionCount)
            assertEquals(
                listOf("sleep:0", "heartRate:500", "heartRate:500", "heartRate:1"),
                events.filter { it.startsWith("sleep:") || it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `persist splits heart rate samples above multiple batch boundaries via the bulk entrypoint`() =
        runTest {
            // persist() delegates to persistHeartRateSamples, which batches at 500 internally.
            // With input spanning three chunks (500, 500, 201), batching must upsert every row
            // in the expected batch shape.
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            store.persist(
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = (1..1201).map(::heartRateInput),
                    hrvSamples = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                ),
            )

            // 1 metadata transaction + 3 heart-rate batches (500, 500, 201).
            assertEquals(4, transactionRunner.transactionCount)
            assertEquals(
                listOf("heartRate:500", "heartRate:500", "heartRate:201"),
                events.filter { it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `persistHeartRateSamples splits inputs above the batch size into multiple transactions`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            store.persistHeartRateSamples((1..1201).map(::heartRateInput))

            assertEquals(3, transactionRunner.transactionCount)
            assertEquals(
                listOf("heartRate:500", "heartRate:500", "heartRate:201"),
                events.filter { it.startsWith("heartRate:") },
            )
        }

    @Test
    fun `persistHeartRateSamples at or below the batch size persists in a single transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            store.persistHeartRateSamples((1..500).map(::heartRateInput))

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(listOf("heartRate:500"), events.filter { it.startsWith("heartRate:") })
        }

    @Test
    fun `persistHrvSamples splits inputs above the batch size into multiple transactions`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            store.persistHrvSamples((1..1200).map(::hrvInput))

            assertEquals(3, transactionRunner.transactionCount)
            assertEquals(listOf("hrv:500", "hrv:500", "hrv:200"), events.filter { it.startsWith("hrv:") })
        }

    @Test
    fun `persistHrvSamples at or below the batch size persists in a single transaction`() =
        runTest {
            val events = mutableListOf<String>()
            val transactionRunner = RecordingTransactionRunner(events)
            val store = buildStore(events, transactionRunner)

            store.persistHrvSamples((1..499).map(::hrvInput))

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
            when {
                // suspend DAO methods surface as Object return types on the Proxy; the source-ref
                // resolvers must hand back a Long or re-ingest breaks with a ClassCastException.
                method.name == "getOrCreateSourceRef" || method.name == "getSourceRef" -> 1L
                method.name == "getModelTrimpById" || method.name == "getById" -> null
                method.returnType == java.lang.Integer.TYPE -> 1
                method.returnType == java.lang.Long.TYPE || method.returnType == Long::class.javaObjectType -> 1L
                else -> Unit
            }
        } as T

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
                    minuteBucketDao = recordingDao(events, "minuteBucket"),
                ),
            dailySummaryDao = recordingDao(events, "summary"),
            transactionRunner = transactionRunner,
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
