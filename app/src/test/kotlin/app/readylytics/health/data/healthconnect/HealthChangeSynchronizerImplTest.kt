package app.readylytics.health.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.data.local.dao.*
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
}
