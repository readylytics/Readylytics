package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.response.ChangesResponse
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.IntervalKind
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

/**
 * Split out of [HealthChangeSynchronizerImplTest] (rather than added there) so neither file
 * crosses detekt's LargeClass threshold: token-capture, device-selected STEPS upserts/deletes,
 * the permission-skip-one-type-continue-for-others path, and the DISTANCE/ELEVATION_GAINED
 * interval-token lifecycle (OD-4). No behavior difference from being in one file -- this is the
 * same fixture shape as [HealthChangeSynchronizerImplTest], duplicated rather than shared because
 * JUnit doesn't share `@Before` state across classes.
 */
class HealthChangeSynchronizerDeviceAndIntervalTest {
    private val tokenStore = mockk<HealthChangeTokenStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val changeIngestionStore = mockk<HealthChangeIngestionStore>(relaxed = true)
    private val workoutReadPreparer = mockk<WorkoutReadPreparer>(relaxed = true)
    private val workoutEnrichmentRefresher = mockk<WorkoutEnrichmentRefresher>(relaxed = true)

    private val client = mockk<HealthConnectClient>(relaxed = true)

    private lateinit var synchronizer: HealthChangeSynchronizerImpl

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        val allReadPermissions =
            HealthDataType.entries.flatMap { dataType ->
                recordClassesFor(dataType).map {
                    HealthPermission.getReadPermission(it)
                }
            }.toSet()
        coEvery { client.permissionController.getGrantedPermissions() } returns allReadPermissions

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
                client = client,
                tokenStore = tokenStore,
                settingsRepo = settingsRepo,
                transactionRunner = transactionRunner,
                healthIngestionStore = healthIngestionStore,
                changeIngestionStore = changeIngestionStore,
                workoutReadPreparer = workoutReadPreparer,
                workoutEnrichmentRefresher = workoutEnrichmentRefresher,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `captureChangesTokens fetches tokens without storing them`() =
        runTest {
            coEvery { client.getChangesToken(any<ChangesTokenRequest>()) } returns "baseline-token"

            val tokens = synchronizer.captureChangesTokens()

            assertEquals(HealthDataType.entries.size, tokens.typed.size)
            coVerify(exactly = HealthDataType.entries.size + 2) {
                client.getChangesToken(any<ChangesTokenRequest>())
            }
            coVerify(exactly = 0) { tokenStore.put(any(), any(), any()) }
            coVerify(exactly = 0) { tokenStore.putAll(any(), any()) }
        }

    @Test
    fun `captureChangesTokens suspends interval token on genuine permission denial`() =
        runTest {
            coEvery {
                client.getChangesToken(match { it.recordTypes.contains(DistanceRecord::class) })
            } throws SecurityException("DISTANCE not granted")
            coEvery {
                client.getChangesToken(match { !it.recordTypes.contains(DistanceRecord::class) })
            } returns "baseline-token"

            val tokens = synchronizer.captureChangesTokens()

            assertFalse(tokens.intervals.containsKey("DISTANCE"))
            coVerify(exactly = 1) { tokenStore.suspendToken("DISTANCE") }
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
                    changeIngestionStore.affectedDatesForRecords(HealthDataType.STEPS, listOf(recordId), any())
                } returns expectedDates
                val deletionChange =
                    mockk<DeletionChange>(relaxed = true) {
                        every { this@mockk.recordId } returns recordId
                    }
                routeOneChange(dataType = HealthDataType.STEPS, change = deletionChange)

                val outcome = synchronizer.applyPendingChanges()

                assertEquals(expectedDates, outcome.affectedDates)
                coVerifyOrder {
                    changeIngestionStore.affectedDatesForRecords(HealthDataType.STEPS, listOf(recordId), any())
                    changeIngestionStore.deleteRecords(HealthDataType.STEPS, listOf(recordId))
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

    @Test
    fun `applyPendingChanges processes distance changes and commits staged interval token`() =
        runTest {
            seedTokens()
            HealthDataType.entries.forEach { current ->
                coEvery { client.getChanges(tokenFor(current)) } returns changesResponse(emptyList())
            }

            val distPermission = HealthPermission.getReadPermission(DistanceRecord::class)
            coEvery { client.permissionController.getGrantedPermissions() } returns
                allPermissions() + distPermission

            coEvery { tokenStore.getToken("DISTANCE") } returns "dist-token-1"
            val distRecord =
                mockk<DistanceRecord>(relaxed = true) {
                    every { metadata.id } returns "dist-1"
                    every { metadata.dataOrigin.packageName } returns "com.strava"
                    every { startTime } returns Instant.parse("2026-08-31T10:00:00Z")
                    every { endTime } returns Instant.parse("2026-08-31T11:00:00Z")
                }
            val change = UpsertionChange(distRecord)
            val distResponse =
                mockk<ChangesResponse>(relaxed = true) {
                    every { changesTokenExpired } returns false
                    every { changes } returns listOf(change)
                    every { nextChangesToken } returns "dist-token-2"
                    every { hasMore } returns false
                }
            coEvery { client.getChanges("dist-token-1") } returns distResponse
            coEvery {
                workoutEnrichmentRefresher.refreshForIntervalChanges(any(), any())
            } returns setOf(LocalDate.parse("2026-08-31"))

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-08-31")))
            coVerify(exactly = 1) {
                workoutEnrichmentRefresher.refreshForIntervalChanges(
                    match { list ->
                        list.size == 1 && list[0].sourceId == "dist-1" && list[0].kind == IntervalKind.DISTANCE
                    },
                    any(),
                )
            }

            synchronizer.commitTokens(outcome.nextTokens, outcome.nextIntervalTokens)
            coVerify { tokenStore.putToken("DISTANCE", "dist-token-2", any()) }
        }

    @Test
    fun `applyPendingChanges suspends interval token when permission revoked`() =
        runTest {
            seedTokens()
            HealthDataType.entries.forEach { current ->
                coEvery { client.getChanges(tokenFor(current)) } returns changesResponse(emptyList())
            }

            // Grant all except DistanceRecord
            coEvery { client.permissionController.getGrantedPermissions() } returns allPermissions()
            coEvery { tokenStore.getToken("DISTANCE") } returns "dist-token-old"

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            coVerify { tokenStore.suspendToken("DISTANCE") }
        }

    @Test
    fun `applyPendingChanges bootstraps interval token on first authorized run without resync`() =
        runTest {
            seedTokens()
            HealthDataType.entries.forEach { current ->
                coEvery { client.getChanges(tokenFor(current)) } returns changesResponse(emptyList())
            }

            val distPermission = HealthPermission.getReadPermission(DistanceRecord::class)
            coEvery { client.permissionController.getGrantedPermissions() } returns
                allPermissions() + distPermission

            coEvery { tokenStore.getToken("DISTANCE") } returns null
            coEvery {
                client.getChangesToken(match { it.recordTypes.contains(DistanceRecord::class) })
            } returns "bootstrap-dist-token"
            coEvery { client.getChanges("bootstrap-dist-token") } returns changesResponse(emptyList())

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            synchronizer.commitTokens(outcome.nextTokens, outcome.nextIntervalTokens)
            coVerify { tokenStore.putToken("DISTANCE", "next-token", any()) }
        }

    @Test
    fun interleavedRunsOwnTheirIntervalTokens() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns
            setOf(HealthPermission.getReadPermission(DistanceRecord::class))
        coEvery { tokenStore.getToken("DISTANCE") } returns "start"
        coEvery { client.getChanges("start") } returnsMany listOf(
            ChangesResponse(emptyList(), "token-A", false, false),
            ChangesResponse(emptyList(), "token-B", false, false),
        )
        val runA = synchronizer.applyPendingChanges()
        val runB = synchronizer.applyPendingChanges()
        assertEquals(mapOf("DISTANCE" to "token-A"), runA.nextIntervalTokens)
        assertEquals(mapOf("DISTANCE" to "token-B"), runB.nextIntervalTokens)
        synchronizer.commitTokens(runA.nextTokens, runA.nextIntervalTokens)
        coVerify(exactly = 1) { tokenStore.putToken("DISTANCE", "token-A", any()) }
        coVerify(exactly = 0) { tokenStore.putToken("DISTANCE", "token-B", any()) }
    }

    @Test
    fun synchronizerHasNoCollectionSideChannel() {
        assertTrue(HealthChangeSynchronizerImpl::class.java.declaredFields.none {
            Map::class.java.isAssignableFrom(it.type) || Collection::class.java.isAssignableFrom(it.type)
        })
    }

    @Test
    fun deniedIntervalAfterAcceptedPageKeepsStoredToken() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns
            setOf(HealthPermission.getReadPermission(DistanceRecord::class))
        coEvery { tokenStore.getToken("DISTANCE") } returns "start"
        coEvery { client.getChanges("start") } returns ChangesResponse(emptyList(), "page-two", true, false)
        coEvery { client.getChanges("page-two") } throws SecurityException("late denial")
        val outcome = synchronizer.applyPendingChanges()
        assertTrue(outcome.nextIntervalTokens.isEmpty())
        synchronizer.commitTokens(outcome.nextTokens, outcome.nextIntervalTokens)
        coVerify(exactly = 0) { tokenStore.putToken("DISTANCE", any(), any()) }
        coVerify(exactly = 0) { tokenStore.suspendToken("DISTANCE") }
    }

    @Test
    fun cancelledIntervalRunCannotLeakTokensIntoAnotherCommit() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns
            setOf(HealthPermission.getReadPermission(DistanceRecord::class))
        coEvery { tokenStore.getToken("DISTANCE") } returns "start"
        coEvery { client.getChanges("start") } returns ChangesResponse(emptyList(), "page-two", true, false)
        coEvery { client.getChanges("page-two") } throws kotlinx.coroutines.CancellationException("cancel")
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { synchronizer.applyPendingChanges() }
        synchronizer.commitTokens(emptyMap(), emptyMap())
        coVerify(exactly = 0) { tokenStore.putToken(any(), any(), any()) }
    }

    @Test
    fun promotedBaselineDoesNotReplayOldIntervalChanges() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns
            setOf(HealthPermission.getReadPermission(DistanceRecord::class))
        coEvery { client.getChangesToken(any()) } returns "baseline"
        var stored = "old"
        coEvery { tokenStore.getToken("DISTANCE") } coAnswers { stored }
        coEvery { tokenStore.putToken("DISTANCE", any(), any()) } coAnswers { stored = secondArg() }
        val captured = synchronizer.captureChangesTokens()
        synchronizer.commitTokens(emptyMap(), captured.intervals)
        var nextDailyIntervalChangeCount = 0
        coEvery { client.getChanges("baseline") } returns ChangesResponse(emptyList(), "next", false, false)
        coEvery { client.getChanges("old") } coAnswers {
            nextDailyIntervalChangeCount++
            ChangesResponse(emptyList(), "next", false, false)
        }
        synchronizer.applyPendingChanges()
        assertEquals(0, nextDailyIntervalChangeCount)
    }

    @Test
    fun intervalBudgetContinuationReturnsOwnedCursor() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns
            setOf(HealthPermission.getReadPermission(DistanceRecord::class))
        coEvery { tokenStore.getToken("DISTANCE") } returns "start"
        coEvery { client.getChanges(any()) } returns ChangesResponse(emptyList(), "page-next", true, false)
        val outcome = synchronizer.applyPendingChanges()
        assertTrue(outcome.continuationRequired)
        assertEquals(mapOf("DISTANCE" to "page-next"), outcome.nextIntervalTokens)
    }

    @Test
    fun intervalPermissionRevokedBeforeCommitDoesNotPromote() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns emptySet()
        synchronizer.commitTokens(emptyMap(), mapOf("DISTANCE" to "candidate"))
        coVerify(exactly = 0) { tokenStore.putToken(any(), any(), any()) }
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
        coEvery { tokenStore.get(HealthDataType.VO2_MAX) } returns "vo2max-token"
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
            HealthDataType.VO2_MAX -> "vo2max-token"
        }

    private fun changesResponse(
        changes: List<androidx.health.connect.client.changes.Change>,
        nextToken: String = "next-token",
        hasMore: Boolean = false,
    ) = mockk<ChangesResponse>(relaxed = true) {
        every { changesTokenExpired } returns false
        every { this@mockk.changes } returns changes
        every { nextChangesToken } returns nextToken
        every { this@mockk.hasMore } returns hasMore
    }

    private fun allPermissions(): Set<String> =
        HealthDataType.entries.flatMap { current ->
            recordClassesFor(current).map { HealthPermission.getReadPermission(it) }
        }.toSet()
}
