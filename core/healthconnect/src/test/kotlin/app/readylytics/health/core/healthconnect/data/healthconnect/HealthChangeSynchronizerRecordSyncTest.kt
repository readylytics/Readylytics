package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.SessionSpans
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class HealthChangeSynchronizerRecordSyncTest {
    private val context = mockk<Context>(relaxed = true)
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val changeIngestionStore = mockk<HealthChangeIngestionStore>(relaxed = true)

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

        coEvery { changeIngestionStore.sessionSpansOverlapping(any(), any()) } returns
            SessionSpans(emptyList(), emptyList())
        coEvery { changeIngestionStore.heartRateSamplesForMetrics(any(), any(), any()) } returns emptyList()

        synchronizer =
            HealthChangeSynchronizerImpl(
                context = context,
                tokenStore = tokenStore,
                settingsRepo = settingsRepo,
                transactionRunner = transactionRunner,
                healthIngestionStore = healthIngestionStore,
                changeIngestionStore = changeIngestionStore,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `a single heart rate record resolves its source ref exactly once`() =
        runTest {
            // WP-16 / R2-HC-003 acceptance test: a single upserted HEART_RATE record carrying 200
            // samples must persist through exactly one persistHeartRateSamples call with all 200
            // mapped inputs, not one call per sample (which is what an N+1 source-ref resolution
            // pattern in the old DAO-direct code would have produced).
            seedTokens()
            val recordId = "hr-multi-sample"
            val recordStart = Instant.parse("2026-06-20T09:00:00Z")
            val samples =
                (0 until 200).map { i ->
                    mockk<HeartRateRecord.Sample> {
                        every { time } returns recordStart.plusSeconds(i.toLong())
                        every { beatsPerMinute } returns 60L
                    }
                }
            val record =
                mockk<HeartRateRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { startTime } returns recordStart
                    every { endTime } returns recordStart.plusSeconds(200)
                    every { this@mockk.samples } returns samples
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.HEART_RATE, change = change)

            synchronizer.applyPendingChanges()

            coVerify(exactly = 1) { healthIngestionStore.persistHeartRateSamples(match { it.size == 200 }) }
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
            val expectedDates = setOf(epochDay(1000L), epochDay(2000L))
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HEART_RATE, recordId, any())
            } returns expectedDates

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(expectedDates, outcome.affectedDates)
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HEART_RATE, recordId, any())
                changeIngestionStore.deleteRecord(HealthDataType.HEART_RATE, recordId)
            }
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
            val expectedDates = setOf(epochDay(3000L), epochDay(4000L))
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HRV, recordId, any())
            } returns expectedDates

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(expectedDates, outcome.affectedDates)
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HRV, recordId, any())
                changeIngestionStore.deleteRecord(HealthDataType.HRV, recordId)
            }
        }

    @Test
    fun `applyPendingChanges replaces changed heart rate source record before upsert`() =
        runTest {
            seedTokens()
            val recordId = "hr-record"
            val oldTimestampMs = 1000L
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
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HEART_RATE, recordId, any())
            } returns setOf(epochDay(oldTimestampMs))

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldTimestampMs), sampleTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.HEART_RATE, recordId, any())
                changeIngestionStore.deleteRecord(HealthDataType.HEART_RATE, recordId)
                healthIngestionStore.persistHeartRateSamples(
                    match { it.size == 1 && it[0].timestampMs == sampleTime.toEpochMilli() },
                )
            }
        }

    @Test
    fun `applyPendingChanges replaces changed weight source record before upsert`() =
        runTest {
            seedTokens()
            val recordId = "weight-record"
            val oldTimestampMs = 1000L
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
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.WEIGHT, recordId, any())
            } returns setOf(epochDay(oldTimestampMs))

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldTimestampMs), newTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.WEIGHT, recordId, any())
                changeIngestionStore.deleteRecord(HealthDataType.WEIGHT, recordId)
                healthIngestionStore.persist(
                    match { batch -> batch.weights.map { it.id } == listOf("${recordId}_${newTime.toEpochMilli()}") },
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
                coEvery {
                    changeIngestionStore.affectedDatesForRecord(HealthDataType.WEIGHT, recordId, any())
                } returns emptySet()

                val outcome = synchronizer.applyPendingChanges()

                assertEquals(setOf(LocalDate.of(2026, 1, 2)), outcome.affectedDates)
            } finally {
                TimeZone.setDefault(originalZone)
            }
        }

    @Test
    fun `applyPendingChanges forwards freshly computed workout metrics for exercise upsertion`() =
        runTest {
            // modelTrimp/route-field preservation across re-upserts now lives inside
            // RoomHealthIngestionStore.persist() (see WorkoutModelTrimpIngestionDeterminismTest in
            // core:database) -- this class's only remaining job is to compute the fresh
            // duration/zone/TRIMP/avgHr metrics from already-stored HR and forward them. Stubbing
            // real, non-empty HR samples (rather than emptyList()) is load-bearing here: with no
            // samples, WorkoutMapper.mapExerciseSession's own durationMinutes computation alone
            // would satisfy a durationMinutes-only assertion even if the entire
            // metrics-driven .copy(...) block were deleted.
            seedTokens()

            val startTime = Instant.parse("2026-06-01T10:00:00Z")
            val endTime = Instant.parse("2026-06-01T11:00:00Z")
            val exerciseRecordId = "exercise-session-123"
            val exerciseRecord = createMockExerciseRecord(exerciseRecordId, startTime, endTime)

            // 140 bpm (zone3) for the first half hour, 160 bpm (zone4) for the second half hour,
            // against the default zone thresholds (95/114/133/152/171 from UserPreferences()):
            // zoneMinutes = [0, 0, 30, 30, 0], avgHr = 150, trimp = 30*3 + 30*4 = 210.
            val hrSamples = listOf(
                DomainHeartRateSample(time = Instant.parse("2026-06-01T10:00:00Z"), beatsPerMinute = 140),
                DomainHeartRateSample(time = Instant.parse("2026-06-01T10:30:00Z"), beatsPerMinute = 160),
            )
            coEvery {
                changeIngestionStore.heartRateSamplesForMetrics(
                    RecordType.EXERCISE.name,
                    startTime.toEpochMilli(),
                    endTime.toEpochMilli(),
                )
            } returns hrSamples

            val capturedBatches = mutableListOf<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(capturedBatches)) } returns Unit

            routeOneChange(HealthDataType.EXERCISE, UpsertionChange(exerciseRecord))

            synchronizer.applyPendingChanges()

            coVerify {
                changeIngestionStore.heartRateSamplesForMetrics(
                    RecordType.EXERCISE.name,
                    startTime.toEpochMilli(),
                    endTime.toEpochMilli(),
                )
            }
            val saved = capturedBatches.flatMap { it.workouts }.firstOrNull { it.id == exerciseRecordId }
            assertNotNull("Saved workout input should not be null", saved)
            assertEquals(exerciseRecordId, saved?.id)
            assertEquals(60, saved?.durationMinutes)
            assertEquals(150f, saved?.avgHr)
            assertEquals(210f, saved?.trimp)
            assertEquals(0f, saved?.zone1Minutes)
            assertEquals(0f, saved?.zone2Minutes)
            assertEquals(30f, saved?.zone3Minutes)
            assertEquals(30f, saved?.zone4Minutes)
            assertEquals(0f, saved?.zone5Minutes)
        }

    private fun createMockExerciseRecord(
        id: String,
        startTime: Instant,
        endTime: Instant,
    ): ExerciseSessionRecord {
        val origin =
            mockk<DataOrigin>(relaxed = true) {
                every { packageName } returns "com.example.tracker"
            }
        val meta =
            mockk<Metadata>(relaxed = true) {
                every { this@mockk.id } returns id
                every { dataOrigin } returns origin
            }
        return mockk<ExerciseSessionRecord>(relaxed = true) {
            every { metadata } returns meta
            every { this@mockk.startTime } returns startTime
            every { this@mockk.endTime } returns endTime
            every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            every { title } returns "Morning Run"
            every { notes } returns "Tempo"
            every { exerciseRouteResult } returns ExerciseRouteResult.NoData()
        }
    }

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
