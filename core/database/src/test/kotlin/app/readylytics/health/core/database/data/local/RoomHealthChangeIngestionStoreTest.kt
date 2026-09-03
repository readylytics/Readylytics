package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomHealthChangeIngestionStoreTest {
    private lateinit var database: HealthDatabase
    private lateinit var seedStore: RoomHealthIngestionStore
    private lateinit var changeStore: RoomHealthChangeIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        val daos =
            HealthRecordDaos(
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                workoutDao = database.workoutDao(),
                workoutRoutePointDao = database.workoutRoutePointDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                stepRecordDao = database.stepRecordDao(),
                sourceRecordDao = database.sourceRecordDao(),
                minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
            )
        seedStore =
            RoomHealthIngestionStore(
                daos = daos,
                dailySummaryDao = database.dailySummaryDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
        changeStore = RoomHealthChangeIngestionStore(daos = daos)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `affectedDatesForRecord returns the sleep session's date range`() = runTest {
        val dayStartMs = START_MS
        val session = SleepSessionInput(
            id = "hc-sleep-1", startTime = dayStartMs, endTime = dayStartMs + 1 * 3_600_000L,
            durationMinutes = 60, efficiency = 0.9f, deepSleepMinutes = 40, remSleepMinutes = 15,
            lightSleepMinutes = 5, awakeMinutes = 0, sleepScore = null,
            startZoneOffsetSeconds = null, endZoneOffsetSeconds = null, deviceName = null,
        )
        seedStore.persist(HealthIngestionBatch(
            sleepSessions = listOf(session), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = emptyList(), weights = emptyList(), bodyFatSamples = emptyList(),
            bloodPressureSamples = emptyList(), oxygenSaturationSamples = emptyList(),
            bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val dates = changeStore.affectedDatesForRecord(HealthDataType.SLEEP, "hc-sleep-1", ZoneId.of("UTC"))

        assertEquals(1, dates.size)
    }

    @Test
    fun `deleteRecord removes the heart rate record and its source ref`() = runTest {
        seedStore.persistHeartRateSamples(listOf(
            HeartRateInput(id = "hc-hr-1_1000", timestampMs = 1000L, beatsPerMinute = 60,
                recordType = "RESTING", sessionId = null, deviceName = null),
        ))
        assertEquals(1, seedStore.countHeartRateInRange(0, 2000))

        changeStore.deleteRecord(HealthDataType.HEART_RATE, "hc-hr-1")

        assertEquals(0, seedStore.countHeartRateInRange(0, 2000))
    }

    @Test
    fun `sessionSpansOverlapping returns sleep and workout spans overlapping the window`() = runTest {
        val sleep = SleepSessionInput(
            id = "s1", startTime = 1_000L, endTime = 5_000L, durationMinutes = 1,
            efficiency = 1f, deepSleepMinutes = 0, remSleepMinutes = 0, lightSleepMinutes = 1,
            awakeMinutes = 0, sleepScore = null, startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null, deviceName = null,
        )
        val workout = WorkoutInput(
            id = "w1", startTime = 10_000L, endTime = 20_000L, exerciseType = "running",
            durationMinutes = 1, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f,
            zone4Minutes = 0f, zone5Minutes = 0f, trimp = 0f, avgHr = 0f, deviceName = null,
        )
        seedStore.persist(HealthIngestionBatch(
            sleepSessions = listOf(sleep), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = listOf(workout), weights = emptyList(),
            bodyFatSamples = emptyList(), bloodPressureSamples = emptyList(),
            oxygenSaturationSamples = emptyList(), bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val spans = changeStore.sessionSpansOverlapping(0L, 25_000L)

        assertEquals(listOf("s1"), spans.sleepSessions.map { it.id })
        assertEquals(listOf("w1"), spans.workouts.map { it.id })
    }

    @Test
    fun `heartRateSamplesForMetrics filters by record type and range`() = runTest {
        seedStore.persistHeartRateSamples(listOf(
            HeartRateInput(id = "hc-hr-2_1000", timestampMs = 1000L, beatsPerMinute = 140,
                recordType = "EXERCISE", sessionId = "w1", deviceName = null),
            HeartRateInput(id = "hc-hr-3_2000", timestampMs = 2000L, beatsPerMinute = 60,
                recordType = "RESTING", sessionId = null, deviceName = null),
        ))

        val samples = changeStore.heartRateSamplesForMetrics("EXERCISE", 0L, 5000L)

        assertEquals(1, samples.size)
        assertEquals(140, samples.single().beatsPerMinute)
    }

    @Test
    fun `affectedDatesForRecord returns dates for every heart rate sample sharing the source record id`() =
        runTest {
            seedStore.persistHeartRateSamples(
                listOf(
                    HeartRateInput(
                        id = "hc-hr-4_1000", timestampMs = 1_000L, beatsPerMinute = 60,
                        recordType = "RESTING", sessionId = null, deviceName = null,
                    ),
                    HeartRateInput(
                        id = "hc-hr-4_90000000", timestampMs = 90_000_000L, beatsPerMinute = 61,
                        recordType = "RESTING", sessionId = null, deviceName = null,
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.HEART_RATE, "hc-hr-4", ZoneId.of("UTC"))

            assertEquals(2, dates.size)
        }

    @Test
    fun `affectedDatesForRecord returns dates for every hrv sample sharing the source record id`() =
        runTest {
            seedStore.persistHrvSamples(
                listOf(
                    HrvInput(
                        id = "hc-hrv-1_1000", timestampMs = 1_000L, rmssdMs = 40f,
                        recordType = "RESTING", sessionId = null, deviceName = null,
                    ),
                    HrvInput(
                        id = "hc-hrv-1_90000000", timestampMs = 90_000_000L, rmssdMs = 41f,
                        recordType = "RESTING", sessionId = null, deviceName = null,
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.HRV, "hc-hrv-1", ZoneId.of("UTC"))

            assertEquals(2, dates.size)
        }

    @Test
    fun `affectedDatesForRecord returns the workout's date range for EXERCISE`() =
        runTest {
            val workout = WorkoutInput(
                id = "hc-workout-1",
                startTime = Instant.parse("2026-03-10T23:00:00Z").toEpochMilli(),
                endTime = Instant.parse("2026-03-11T01:00:00Z").toEpochMilli(),
                exerciseType = "running", durationMinutes = 120,
                zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f,
                trimp = 0f, avgHr = 0f, deviceName = null,
            )
            seedStore.persist(batch(workouts = listOf(workout)))

            val dates = changeStore.affectedDatesForRecord(HealthDataType.EXERCISE, "hc-workout-1", ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)), dates)
        }

    @Test
    fun `affectedDatesForRecord returns the sample's date for a vitals type (WEIGHT)`() =
        runTest {
            val weightTime = Instant.parse("2026-05-01T12:00:00Z")
            seedStore.persist(
                batch(
                    weights = listOf(
                        WeightInput(
                            id = "hc-weight-1_${weightTime.toEpochMilli()}",
                            timestampMs = weightTime.toEpochMilli(),
                            weightKg = 70f, deviceName = null,
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.WEIGHT, "hc-weight-1", ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 5, 1)), dates)
        }

    @Test
    fun `affectedDatesForRecord returns both dates for a steps record crossing midnight`() =
        runTest {
            // HC-005: a steps record spanning a day boundary must resolve both dates -- this is
            // the cross-midnight case the old synchronizer-level test used to cover before this
            // date-derivation logic moved into RoomHealthChangeIngestionStore.
            val recordId = "hc-steps-1"
            seedStore.persist(
                batch(
                    stepRecords = listOf(
                        StepRecordInput(
                            id = recordId,
                            startTime = Instant.parse("2026-03-10T22:00:00Z").toEpochMilli(),
                            endTime = Instant.parse("2026-03-11T00:00:00Z").toEpochMilli(),
                            count = 200L, deviceName = null,
                        ),
                    ),
                ),
            )

            val dates = changeStore.affectedDatesForRecord(HealthDataType.STEPS, recordId, ZoneId.of("UTC"))

            assertEquals(setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)), dates)
        }

    private fun batch(
        sleepSessions: List<SleepSessionInput> = emptyList(),
        workouts: List<WorkoutInput> = emptyList(),
        weights: List<WeightInput> = emptyList(),
        stepRecords: List<StepRecordInput> = emptyList(),
    ) = HealthIngestionBatch(
        sleepSessions = sleepSessions, sleepStages = emptyList(), heartRateSamples = emptyList(),
        hrvSamples = emptyList(), workouts = workouts, weights = weights, bodyFatSamples = emptyList(),
        bloodPressureSamples = emptyList(), oxygenSaturationSamples = emptyList(),
        bodyTemperatureSamples = emptyList(), stepRecords = stepRecords,
    )

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
