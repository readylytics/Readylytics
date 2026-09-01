package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
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

class HealthChangeSynchronizerImplTest {
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val changeIngestionStore = mockk<HealthChangeIngestionStore>(relaxed = true)

    private val client = mockk<HealthConnectClient>(relaxed = true)

    private lateinit var synchronizer: HealthChangeSynchronizerImpl

    @Before
    fun setup() {
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

        // Baseline plumbing stubs so tests that don't care about session spans / provisional
        // workout metrics don't need to restub these on every case.
        coEvery { changeIngestionStore.sessionSpansOverlapping(any(), any()) } returns
            SessionSpans(emptyList(), emptyList())
        coEvery { changeIngestionStore.heartRateSamplesForMetrics(any(), any(), any()) } returns emptyList()

        synchronizer =
            HealthChangeSynchronizerImpl(
                client = client,
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
    fun `applyPendingChanges does not request full resync when token missing and permission not granted`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns null
            coEvery { client.permissionController.getGrantedPermissions() } returns emptySet()

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
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
    fun `applyPendingChanges processes paginated changes without persisting candidate tokens`() =
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
            assertEquals("token3", outcome.nextTokens[dataType])
            coVerifyOrder {
                client.getChanges("token1")
                client.getChanges("token2")
            }
            coVerify(exactly = 0) { tokenStore.put(any(), any(), any()) }
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

            // Mock resolving the deleted record's affected date via the port, replacing the old
            // sleepSessionDao.getById(...)-based lookup.
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.SLEEP, recordId, any())
            } returns setOf(LocalDate.parse("2026-06-19"))

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                changeIngestionStore.deleteRecord(HealthDataType.SLEEP, recordId)
            }
        }

    @Test
    fun `applyPendingChanges handles UpsertionChange for selected device`() =
        runTest {
            seedTokens()

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

            routeOneChange(dataType = HealthDataType.SLEEP, change = upsertionChange)

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                healthIngestionStore.persist(match { it.sleepSessions.size == 1 })
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

            seedTokens()

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

            routeOneChange(dataType = HealthDataType.SLEEP, change = upsertionChange)

            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.SLEEP, "id123", any())
            } returns setOf(LocalDate.parse("2026-06-19"))

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                changeIngestionStore.deleteRecord(HealthDataType.SLEEP, "id123")
            }
            coVerify(exactly = 0) {
                healthIngestionStore.persist(any())
            }
        }

    @Test
    fun `captureChangesTokens fetches tokens without storing them`() =
        runTest {
            coEvery { client.getChangesToken(any<ChangesTokenRequest>()) } returns "baseline-token"

            val tokens = synchronizer.captureChangesTokens()

            assertEquals(HealthDataType.entries.size, tokens.size)
            coVerify(exactly = HealthDataType.entries.size) {
                client.getChangesToken(any<ChangesTokenRequest>())
            }
            coVerify(exactly = 0) { tokenStore.put(any(), any(), any()) }
            coVerify(exactly = 0) { tokenStore.putAll(any(), any()) }
        }

    @Test
    fun `applyPendingChanges persists an upserted steps record for later deletion resolution`() =
        runTest {
            seedTokens()
            val recordId = "steps-record"
            val startTime = Instant.parse("2026-06-21T08:00:00Z")
            val endTime = Instant.parse("2026-06-21T08:10:00Z")
            val record =
                mockk<StepsRecord>(relaxed = true) {
                    every { metadata.id } returns recordId
                    every { metadata.device } returns null
                    every { metadata.dataOrigin.packageName } returns "pkg"
                    every { this@mockk.startTime } returns startTime
                    every { this@mockk.endTime } returns endTime
                    every { count } returns 500L
                }
            val change =
                mockk<UpsertionChange>(relaxed = true) {
                    every { this@mockk.record } returns record
                }
            routeOneChange(dataType = HealthDataType.STEPS, change = change)
            coEvery {
                changeIngestionStore.affectedDatesForRecord(HealthDataType.STEPS, recordId, any())
            } returns emptySet()

            synchronizer.applyPendingChanges()

            coVerify {
                healthIngestionStore.persist(
                    match { batch ->
                        batch.stepRecords.size == 1 &&
                            batch.stepRecords[0].id == recordId &&
                            batch.stepRecords[0].startTime == startTime.toEpochMilli() &&
                            batch.stepRecords[0].endTime == endTime.toEpochMilli() &&
                            batch.stepRecords[0].count == 500L
                    },
                )
            }
        }

    @Test
    fun `applyPendingChanges resolves a deleted steps record's dates from the stored raw row`() =
        runTest {
            // HC-005: a steps DeletionChange must resolve affected dates via the port, not
            // emptySet(). The actual date-derivation from the stored raw row now lives in
            // RoomHealthChangeIngestionStore -- this test only verifies the synchronizer wires
            // that lookup and the subsequent delete through in the right order.
            val originalZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try {
                seedTokens()
                val recordId = "deleted-steps"
                val expectedDates = setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11))
                coEvery {
                    changeIngestionStore.affectedDatesForRecord(HealthDataType.STEPS, recordId, any())
                } returns expectedDates
                val deletionChange =
                    mockk<DeletionChange>(relaxed = true) {
                        every { this@mockk.recordId } returns recordId
                    }
                routeOneChange(dataType = HealthDataType.STEPS, change = deletionChange)

                val outcome = synchronizer.applyPendingChanges()

                assertEquals(expectedDates, outcome.affectedDates)
                coVerifyOrder {
                    changeIngestionStore.affectedDatesForRecord(HealthDataType.STEPS, recordId, any())
                    changeIngestionStore.deleteRecord(HealthDataType.STEPS, recordId)
                }
            } finally {
                TimeZone.setDefault(originalZone)
            }
        }

    @Test
    fun `applyPendingChanges skips a data type whose permission is not granted, continues for others`() =
        runTest {
            // Seed tokens for all types EXCEPT steps
            coEvery { tokenStore.get(any()) } answers {
                val dt = firstArg<HealthDataType>()
                if (dt == HealthDataType.STEPS) null else "token-for-$dt"
            }

            // Simulate: heart_rate permission is granted, steps permission is NOT granted
            val grantedPermissions =
                setOf(
                    HealthPermission.getReadPermission(HeartRateRecord::class),
                )
            val permissionController = mockk<PermissionController>(relaxed = true)
            coEvery { permissionController.getGrantedPermissions() } returns grantedPermissions
            every { client.permissionController } returns permissionController

            // Set up a real change for the heart rate type (which HAS a token + permission)
            val sampleTime = Instant.parse("2026-06-20T09:00:00Z")
            val record =
                mockk<HeartRateRecord>(relaxed = true) {
                    every { metadata.id } returns "hr-record"
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
            val response =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns listOf(change)
                    every { nextChangesToken } returns "next-hr"
                    every { hasMore } returns false
                }
            coEvery { client.getChanges(any()) } returns response

            val outcome = synchronizer.applyPendingChanges()

            // Should NOT request full resync (skipped STEPS, processed HEART_RATE)
            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.isNotEmpty())
            assertEquals("next-hr", outcome.nextTokens[HealthDataType.HEART_RATE])
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
}
