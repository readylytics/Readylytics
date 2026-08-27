package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.databaseschema.data.local.dao.*
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class HealthChangeSynchronizerRecordSyncTest {
    private val context = mockk<Context>(relaxed = true)
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val sleepStageDao = mockk<SleepStageDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val stepRecordDao = mockk<StepRecordDao>(relaxed = true)
    private val sourceRecordDao = mockk<SourceRecordDao>(relaxed = true)

    private val client = mockk<HealthConnectClient>(relaxed = true)

    private lateinit var synchronizer: HealthChangeSynchronizerImpl

    @Before
    fun setup() {
        mockkObject(HealthConnectClient)
        every { HealthConnectClient.getOrCreate(any()) } returns client

        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        coEvery { client.readRecords<Record>(any()) } returns
            mockk {
                every { records } returns emptyList()
                every { pageToken } returns null
            }

        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())

        synchronizer =
            HealthChangeSynchronizerImpl(
                context = context,
                tokenStore = tokenStore,
                settingsRepo = settingsRepo,
                transactionRunner = transactionRunner,
                sleepSessionDao = sleepSessionDao,
                sleepStageDao = sleepStageDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                workoutDao = workoutDao,
                weightRecordDao = weightRecordDao,
                bodyFatRecordDao = bodyFatRecordDao,
                bloodPressureRecordDao = bloodPressureRecordDao,
                oxygenSaturationRecordDao = oxygenSaturationRecordDao,
                bodyTemperatureRecordDao = bodyTemperatureRecordDao,
                stepRecordDao = stepRecordDao,
                sourceRecordDao = sourceRecordDao,
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `applyPendingChanges deletes all heart rate rows for one source record id`() =
        runTest {
            seedTokens()
            val recordId = "hr-record"
            val change =
                mockk<DeletionChange>(relaxed = true) {
                    every { this@mockk.recordId } returns recordId
                }
            routeOneChange(dataType = HealthDataType.HEART_RATE, change = change)
            coEvery { sourceRecordDao.getSourceRef(recordId) } returns 1L
            coEvery { heartRateDao.getBySourceRecordRef(1L) } returns
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 2000L,
                        beatsPerMinute = 61,
                        recordType = "SLEEP",
                    ),
                )
            coEvery { heartRateDao.deleteBySourceRecordRef(1L) } returns 2
            coEvery { sourceRecordDao.deleteBySourceRecordId(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(setOf(epochDay(1000L), epochDay(2000L)), outcome.affectedDates)
            coVerifyOrder {
                heartRateDao.getBySourceRecordRef(1L)
                heartRateDao.deleteBySourceRecordRef(1L)
            }
            coVerify(exactly = 0) { heartRateDao.deleteByRef(any()) }
        }

    @Test
    fun `applyPendingChanges deletes all hrv rows for one source record id`() =
        runTest {
            seedTokens()
            val recordId = "hrv-record"
            val change =
                mockk<DeletionChange>(relaxed = true) {
                    every { this@mockk.recordId } returns recordId
                }
            routeOneChange(dataType = HealthDataType.HRV, change = change)
            coEvery { sourceRecordDao.getSourceRef(recordId) } returns 1L
            coEvery { hrvDao.getBySourceRecordRef(1L) } returns
                listOf(
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 3000L, rmssdMs = 40f, recordType = "SLEEP"),
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 4000L, rmssdMs = 41f, recordType = "SLEEP"),
                )
            coEvery { hrvDao.deleteBySourceRecordRef(1L) } returns 2
            coEvery { sourceRecordDao.deleteBySourceRecordId(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(setOf(epochDay(3000L), epochDay(4000L)), outcome.affectedDates)
            coVerifyOrder {
                hrvDao.getBySourceRecordRef(1L)
                hrvDao.deleteBySourceRecordRef(1L)
            }
            coVerify(exactly = 0) { hrvDao.deleteByRef(any()) }
        }

    @Test
    fun `applyPendingChanges replaces changed heart rate source record before upsert`() =
        runTest {
            seedTokens()
            val recordId = "hr-record"
            val oldEntity =
                HeartRateRecordEntity(
                    sourceRecordRef = 1L,
                    timestampMs = 1000L,
                    beatsPerMinute = 55,
                    recordType = "SLEEP",
                )
            val sampleTime = Instant.parse("2026-06-20T09:00:00Z")
            val record =
                mockk<HeartRateRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { startTime } returns sampleTime
                    every { endTime } returns sampleTime
                    every { samples } returns
                        listOf(
                            mockk {
                                every { time } returns sampleTime
                                every { beatsPerMinute } returns 63L
                            },
                        )
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.HEART_RATE, change = change)
            coEvery { sourceRecordDao.getSourceRef(recordId) } returns 1L
            coEvery { sourceRecordDao.getOrCreateSourceRef(recordId, "HEART_RATE", any()) } returns 1L
            coEvery { heartRateDao.getBySourceRecordRef(1L) } returns listOf(oldEntity)
            coEvery { heartRateDao.deleteBySourceRecordRef(1L) } returns 1
            coEvery { sourceRecordDao.deleteBySourceRecordId(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldEntity.timestampMs), sampleTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            coVerifyOrder {
                heartRateDao.getBySourceRecordRef(1L)
                heartRateDao.deleteBySourceRecordRef(1L)
                heartRateDao.upsertAll(
                    match {
                        it.map(HeartRateRecordEntity::sourceRecordRef) == listOf(1L) &&
                            it.map(HeartRateRecordEntity::timestampMs) == listOf(sampleTime.toEpochMilli())
                    },
                )
            }
        }

    @Test
    fun `applyPendingChanges replaces changed weight source record before upsert`() =
        runTest {
            seedTokens()
            val recordId = "weight-record"
            val oldEntity = WeightRecordEntity("${recordId}_1000", 1000L, 70f)
            val newTime = Instant.parse("2026-06-21T09:00:00Z")
            val record =
                mockk<WeightRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { time } returns newTime
                    every { weight.inKilograms } returns 72.5
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.WEIGHT, change = change)
            coEvery { weightRecordDao.getBySourceRecordId(recordId) } returns listOf(oldEntity)
            coEvery { weightRecordDao.deleteBySourceRecordId(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldEntity.timestampMs), newTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            coVerifyOrder {
                weightRecordDao.getBySourceRecordId(recordId)
                weightRecordDao.deleteBySourceRecordId(recordId)
                weightRecordDao.upsertAll(
                    match {
                        it.map(WeightRecordEntity::id) == listOf("${recordId}_${newTime.toEpochMilli()}")
                    },
                )
            }
        }

    @Test
    fun `applyPendingChanges uses scoring zone from preferences for affected dates`() =
        runTest {
            val originalZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try {
                every { settingsRepo.userPreferences } returns
                    flowOf(UserPreferences(scoringZoneId = "Pacific/Kiritimati"))
                seedTokens()
                val recordId = "weight-zone-record"
                val recordTime = Instant.parse("2026-01-01T12:30:00Z")
                val record =
                    mockk<WeightRecord>(relaxed = true) {
                        every { metadata.id } returns recordId
                        every { metadata.device } returns null
                        every { metadata.dataOrigin.packageName } returns "pkg"
                        every { time } returns recordTime
                        every { weight.inKilograms } returns 72.5
                    }
                val change =
                    mockk<UpsertionChange>(relaxed = true) {
                        every { this@mockk.record } returns record
                    }
                routeOneChange(dataType = HealthDataType.WEIGHT, change = change)
                coEvery { weightRecordDao.getBySourceRecordId(recordId) } returns emptyList()
                coEvery { weightRecordDao.deleteBySourceRecordId(recordId) } returns 0

                val outcome = synchronizer.applyPendingChanges()

                assertEquals(setOf(LocalDate.of(2026, 1, 2)), outcome.affectedDates)
            } finally {
                TimeZone.setDefault(originalZone)
            }
        }

    @Test
    fun `applyPendingChanges preserves existing modelTrimp and routeState for exercise upsertion`() =
        runTest {
            seedTokens()

            val startTime = Instant.parse("2026-06-01T10:00:00Z")
            val endTime = Instant.parse("2026-06-01T11:00:00Z")
            val exerciseRecordId = "exercise-session-123"

            val exerciseRecord = createMockExerciseRecord(exerciseRecordId, startTime, endTime)
            val existingEntity = createExistingWorkoutEntity(exerciseRecordId, startTime, endTime)

            coEvery { workoutDao.getById(exerciseRecordId) } returns existingEntity
            coEvery { heartRateDao.getByTypeAndTimeRange(any(), any(), any()) } returns emptyList()

            val capturedWorkouts = mutableListOf<List<WorkoutRecordEntity>>()
            coEvery { workoutDao.upsertAll(capture(capturedWorkouts)) } returns Unit

            routeOneChange(HealthDataType.EXERCISE, UpsertionChange(exerciseRecord))

            synchronizer.applyPendingChanges()

            val saved = capturedWorkouts.flatten().firstOrNull { it.id == exerciseRecordId }
            assertNotNull("Saved workout entity should not be null", saved)
            assertEquals("modelTrimp must be preserved", 52.5f, saved?.modelTrimp)
            assertEquals(
                "routeState must be preserved when fresh record has no route",
                RouteState.IMPORTED,
                saved?.routeState,
            )
            assertEquals("totalDistanceMeters must be preserved", 10000.0f, saved?.totalDistanceMeters)
            assertEquals("avgSpeedKmh must be preserved", 10.0f, saved?.avgSpeedKmh)
            assertEquals("elevationGainMeters must be preserved", 50.0f, saved?.elevationGainMeters)
        }

    private fun createMockExerciseRecord(
        id: String,
        startTime: Instant,
        endTime: Instant,
    ): ExerciseSessionRecord =
        mockk<ExerciseSessionRecord>(relaxed = true) {
            every { metadata } returns
                mockk(relaxed = true) {
                    every { this@mockk.id } returns id
                    every { dataOrigin } returns mockk(relaxed = true) {
                        every { packageName } returns "com.example.tracker"
                    }
                }
            every { this@mockk.startTime } returns startTime
            every { this@mockk.endTime } returns endTime
            every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            every { title } returns "Morning Run"
            every { notes } returns "Tempo"
            every { exerciseRouteResult } returns ExerciseRouteResult.NoData()
        }

    private fun createExistingWorkoutEntity(
        id: String,
        startTime: Instant,
        endTime: Instant,
    ): WorkoutRecordEntity =
        WorkoutRecordEntity(
            id = id,
            startTime = startTime.toEpochMilli(),
            endTime = endTime.toEpochMilli(),
            exerciseType = "RUNNING",
            durationMinutes = 60,
            zone1Minutes = 10f,
            zone2Minutes = 20f,
            zone3Minutes = 20f,
            zone4Minutes = 10f,
            zone5Minutes = 0f,
            trimp = 45.0f,
            modelTrimp = 52.5f,
            avgHr = 150f,
            deviceName = "Pixel Watch",
            routeState = RouteState.IMPORTED,
            totalDistanceMeters = 10000.0f,
            avgSpeedKmh = 10.0f,
            elevationGainMeters = 50.0f,
        )

    private fun seedTokens() {
        coEvery { tokenStore.get(HealthDataType.SLEEP) } returns "sleep-token"
        coEvery { tokenStore.get(HealthDataType.HEART_RATE) } returns "heart-token"
        coEvery { tokenStore.get(HealthDataType.HRV) } returns "hrv-token"
        coEvery { tokenStore.get(HealthDataType.EXERCISE) } returns "exercise-token"
        coEvery { tokenStore.get(HealthDataType.WEIGHT) } returns "weight-token"
        coEvery { tokenStore.get(HealthDataType.BODY_FAT) } returns "bodyfat-token"
        coEvery { tokenStore.get(HealthDataType.BLOOD_PRESSURE) } returns "bp-token"
        coEvery { tokenStore.get(HealthDataType.OXYGEN_SATURATION) } returns "spo2-token"
        coEvery { tokenStore.get(HealthDataType.BODY_TEMPERATURE) } returns "bodytemp-token"
        coEvery { tokenStore.get(HealthDataType.STEPS) } returns "steps-token"
    }

    private fun routeOneChange(
        dataType: HealthDataType,
        change: androidx.health.connect.client.changes.Change,
    ) {
        HealthDataType.entries.forEach { current ->
            val token = tokenFor(current)
            val changes =
                if (current == dataType) {
                    listOf(change)
                } else {
                    emptyList()
                }
            coEvery { client.getChanges(token) } returns changesResponse(changes)
        }
    }

    private fun tokenFor(dataType: HealthDataType): String =
        when (dataType) {
            HealthDataType.SLEEP -> "sleep-token"
            HealthDataType.HEART_RATE -> "heart-token"
            HealthDataType.HRV -> "hrv-token"
            HealthDataType.EXERCISE -> "exercise-token"
            HealthDataType.WEIGHT -> "weight-token"
            HealthDataType.BODY_FAT -> "bodyfat-token"
            HealthDataType.BLOOD_PRESSURE -> "bp-token"
            HealthDataType.OXYGEN_SATURATION -> "spo2-token"
            HealthDataType.BODY_TEMPERATURE -> "bodytemp-token"
            HealthDataType.STEPS -> "steps-token"
        }

    private fun changesResponse(changes: List<androidx.health.connect.client.changes.Change>) =
        mockk<ChangesResponse>(relaxed = true) {
            every { changesTokenExpired } returns false
            every { this@mockk.changes } returns changes
            every { nextChangesToken } returns "next-token"
            every { hasMore } returns false
        }

    private fun epochDay(timestampMs: Long): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
}
