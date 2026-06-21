package app.readylytics.health.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.data.local.dao.*
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.HealthChangeTokenStore
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

class HealthChangeSynchronizerImplTest {
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
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `applyPendingChanges returns requiresFullResync if token is missing`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns null

            val outcome = synchronizer.applyPendingChanges()

            assertTrue(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.isEmpty())
        }

    @Test
    fun `applyPendingChanges returns requiresFullResync on token expired response`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns "old_token"
            val response =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns true
                }
            coEvery { client.getChanges(any()) } returns response

            val outcome = synchronizer.applyPendingChanges()

            assertTrue(outcome.requiresFullResync)
        }

    @Test
    fun `applyPendingChanges returns requiresFullResync on SecurityException`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns "token"
            coEvery { client.getChanges(any()) } throws SecurityException("Revoked")

            val outcome = synchronizer.applyPendingChanges()

            assertTrue(outcome.requiresFullResync)
        }

    @Test
    fun `applyPendingChanges processes paginated changes and advances token only after transaction succeeds`() =
        runTest {
            val dataType = HealthDataType.SLEEP
            coEvery { tokenStore.get(any()) } returns "token1"

            val response1 =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns emptyList()
                    every { nextChangesToken } returns "token2"
                    every { hasMore } returns true
                }
            val response2 =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns emptyList()
                    every { nextChangesToken } returns "token3"
                    every { hasMore } returns false
                }

            coEvery { client.getChanges("token1") } returns response1
            coEvery { client.getChanges("token2") } returns response2

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            coVerifyOrder {
                client.getChanges("token1")
                tokenStore.put(any(), "token2", any())
                client.getChanges("token2")
                tokenStore.put(any(), "token3", any())
            }
        }

    @Test
    fun `applyPendingChanges handles DeletionChange correctly`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns "token"
            val recordId = "deleted_sleep_id"

            val deletionChange =
                mockk<DeletionChange>(relaxed = true) {
                    every { this@mockk.recordId } returns recordId
                }

            val response =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns listOf(deletionChange)
                    every { nextChangesToken } returns "next_token"
                    every { hasMore } returns false
                }

            coEvery { client.getChanges(any()) } returns response

            // Mock looking up the deleted record to determine affected date
            val mockSleepEntity =
                mockk<app.readylytics.health.data.local.entity.SleepSessionEntity>(relaxed = true) {
                    every { startTime } returns Instant.parse("2026-06-19T00:00:00Z").toEpochMilli()
                    every { endTime } returns Instant.parse("2026-06-19T08:00:00Z").toEpochMilli()
                }
            coEvery { sleepSessionDao.getById(recordId) } returns mockSleepEntity
            coEvery { sleepSessionDao.deleteById(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                sleepSessionDao.deleteById(recordId)
            }
        }

    @Test
    fun `applyPendingChanges handles UpsertionChange for selected device`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns "token"

            val mockRecord =
                mockk<SleepSessionRecord>(relaxed = true) {
                    every { metadata.id } returns "upserted_id"
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "com.google.android.apps.fitness"
                    every { startTime } returns Instant.parse("2026-06-19T01:00:00Z")
                    every { endTime } returns Instant.parse("2026-06-19T07:00:00Z")
                    every { startZoneOffset } returns null
                    every { endZoneOffset } returns null
                    every { stages } returns emptyList()
                }

            val upsertionChange =
                mockk<UpsertionChange>(relaxed = true) {
                    every { record } returns mockRecord
                }

            val response =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns listOf(upsertionChange)
                    every { nextChangesToken } returns "next_token"
                    every { hasMore } returns false
                }

            coEvery { client.getChanges(any()) } returns response

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                sleepSessionDao.upsertAll(any())
            }
        }

    @Test
    fun `applyPendingChanges deletes record if it is from a non-selected device`() =
        runTest {
            // Set selected device for sleep to "WatchA"
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(deviceByDataType = mapOf(HealthDataType.SLEEP.name to "WatchA")),
                )

            coEvery { tokenStore.get(any()) } returns "token"

            val mockRecord =
                mockk<SleepSessionRecord>(relaxed = true) {
                    every { metadata.id } returns "id123"
                    every { metadata.device } returns
                        mockk {
                            every { model } returns "WatchB"
                            every { manufacturer } returns "Brand"
                        }
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { startTime } returns Instant.parse("2026-06-19T01:00:00Z")
                    every { endTime } returns Instant.parse("2026-06-19T07:00:00Z")
                }

            val upsertionChange =
                mockk<UpsertionChange>(relaxed = true) {
                    every { record } returns mockRecord
                }

            val response =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns listOf(upsertionChange)
                    every { nextChangesToken } returns "next_token"
                    every { hasMore } returns false
                }

            coEvery { client.getChanges(any()) } returns response

            val mockSleepEntity =
                mockk<app.readylytics.health.data.local.entity.SleepSessionEntity>(relaxed = true) {
                    every { startTime } returns Instant.parse("2026-06-19T00:00:00Z").toEpochMilli()
                    every { endTime } returns Instant.parse("2026-06-19T08:00:00Z").toEpochMilli()
                }
            coEvery { sleepSessionDao.getById("id123") } returns mockSleepEntity

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                sleepSessionDao.deleteById("id123")
            }
            coVerify(exactly = 0) {
                sleepSessionDao.upsertAll(any())
            }
        }

    @Test
    fun `refreshTokensAfterFullResync fetches and stores tokens`() =
        runTest {
            coJustRun { tokenStore.put(any(), any(), any()) }

            synchronizer.refreshTokensAfterFullResync()

            coVerify(exactly = HealthDataType.entries.size) {
                client.getChangesToken(any<ChangesTokenRequest>())
                tokenStore.put(any(), any(), any())
            }
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
            coEvery { heartRateDao.getBySourceRecordId(recordId) } returns
                listOf(
                    HeartRateRecordEntity("${recordId}_1000", 1000L, 60, "SLEEP"),
                    HeartRateRecordEntity("${recordId}_2000", 2000L, 61, "SLEEP"),
                )
            coEvery { heartRateDao.deleteBySourceRecordId(recordId) } returns 2

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(setOf(epochDay(1000L), epochDay(2000L)), outcome.affectedDates)
            coVerifyOrder {
                heartRateDao.getBySourceRecordId(recordId)
                heartRateDao.deleteBySourceRecordId(recordId)
            }
            coVerify(exactly = 0) { heartRateDao.deleteById(any()) }
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
            coEvery { hrvDao.getBySourceRecordId(recordId) } returns
                listOf(
                    HrvRecordEntity("${recordId}_3000", 3000L, 40f, "SLEEP"),
                    HrvRecordEntity("${recordId}_4000", 4000L, 41f, "SLEEP"),
                )
            coEvery { hrvDao.deleteBySourceRecordId(recordId) } returns 2

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(setOf(epochDay(3000L), epochDay(4000L)), outcome.affectedDates)
            coVerifyOrder {
                hrvDao.getBySourceRecordId(recordId)
                hrvDao.deleteBySourceRecordId(recordId)
            }
            coVerify(exactly = 0) { hrvDao.deleteById(any()) }
        }

    @Test
    fun `applyPendingChanges replaces changed heart rate source record before upsert`() =
        runTest {
            seedTokens()
            val recordId = "hr-record"
            val oldEntity = HeartRateRecordEntity("${recordId}_1000", 1000L, 55, "SLEEP")
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
            coEvery { heartRateDao.getBySourceRecordId(recordId) } returns listOf(oldEntity)
            coEvery { heartRateDao.deleteBySourceRecordId(recordId) } returns 1

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(
                setOf(epochDay(oldEntity.timestampMs), sampleTime.atZone(ZoneId.systemDefault()).toLocalDate()),
                outcome.affectedDates,
            )
            coVerifyOrder {
                heartRateDao.getBySourceRecordId(recordId)
                heartRateDao.deleteBySourceRecordId(recordId)
                heartRateDao.upsertAll(
                    match {
                        it.map(HeartRateRecordEntity::id) == listOf("${recordId}_${sampleTime.toEpochMilli()}")
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

    private fun seedTokens() {
        coEvery { tokenStore.get(HealthDataType.SLEEP) } returns "sleep-token"
        coEvery { tokenStore.get(HealthDataType.HEART_RATE) } returns "heart-token"
        coEvery { tokenStore.get(HealthDataType.HRV) } returns "hrv-token"
        coEvery { tokenStore.get(HealthDataType.EXERCISE) } returns "exercise-token"
        coEvery { tokenStore.get(HealthDataType.WEIGHT) } returns "weight-token"
        coEvery { tokenStore.get(HealthDataType.BODY_FAT) } returns "bodyfat-token"
        coEvery { tokenStore.get(HealthDataType.BLOOD_PRESSURE) } returns "bp-token"
        coEvery { tokenStore.get(HealthDataType.OXYGEN_SATURATION) } returns "spo2-token"
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
