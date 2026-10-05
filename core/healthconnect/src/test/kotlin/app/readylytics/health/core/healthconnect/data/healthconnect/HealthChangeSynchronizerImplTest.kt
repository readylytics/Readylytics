package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
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
import kotlin.test.assertFailsWith

class HealthChangeSynchronizerImplTest {
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
    fun `transient SecurityException for a granted type keeps its token and avoids full resync`() =
        runTest {
            val allPerms =
                HealthDataType.entries.flatMap { current ->
                    recordClassesFor(current).map {
                        HealthPermission.getReadPermission(it)
                    }
                }.toSet()
            coEvery { client.permissionController.getGrantedPermissions() } returns allPerms
            coEvery { tokenStore.get(any()) } returns "token"
            coEvery { client.getChanges(any()) } throws SecurityException("Background read refused")

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            // Suspending would delete the token, and the next sync would escalate to a full resync.
            coVerify(exactly = 0) { tokenStore.suspendType(any()) }
            assertTrue(outcome.nextTokens.isEmpty())
        }

    @Test
    fun `failed permission lookup fails the sync instead of suspending every token`() =
        runTest {
            coEvery { tokenStore.get(any()) } returns "token"
            coEvery { client.permissionController.getGrantedPermissions() } throws
                java.io.IOException("Health Connect unavailable")

            assertFailsWith<java.io.IOException> { synchronizer.applyPendingChanges() }
            coVerify(exactly = 0) { tokenStore.suspendType(any()) }
        }

    @Test
    fun `grant sync revoke repeat twice regrant lifecycle suspends type and bootstraps upon regrant`() =
        runTest {
            val allPerms = allPermissions()
            val permsWithoutSteps = allPerms - stepsPermissions()

            val inMemoryTokens = HealthDataType.entries.associateWith { "token-$it" }.toMutableMap()
            val suspended = mutableSetOf<HealthDataType>()
            setupFakeTokenStore(inMemoryTokens, suspended)

            HealthDataType.entries.forEach { current ->
                coEvery { client.getChanges("token-$current") } returns changesResponse(emptyList())
            }

            // 1. Grant & sync
            coEvery { client.permissionController.getGrantedPermissions() } returns allPerms
            val outcome1 = synchronizer.applyPendingChanges()
            assertFalse("Initial sync should succeed without full resync", outcome1.requiresFullResync)

            // 2. Revoke STEPS & sync
            coEvery { client.permissionController.getGrantedPermissions() } returns permsWithoutSteps
            val outcome2 = synchronizer.applyPendingChanges()
            assertFalse("Revoked sync should not enter full resync loop", outcome2.requiresFullResync)
            coVerify(exactly = 1) { tokenStore.suspendType(HealthDataType.STEPS) }

            // 3. Repeat sync (first repeat)
            val outcome3 = synchronizer.applyPendingChanges()
            assertFalse("Repeated sync should not request full resync", outcome3.requiresFullResync)

            // 4. Repeat sync (second repeat)
            val outcome4 = synchronizer.applyPendingChanges()
            assertFalse("Second repeated sync should not request full resync", outcome4.requiresFullResync)

            // 5. Regrant STEPS
            coEvery { client.permissionController.getGrantedPermissions() } returns allPerms
            val outcome5 = synchronizer.applyPendingChanges()
            assertTrue(
                "Regrant must request full resync to bootstrap the newly regranted type",
                outcome5.requiresFullResync,
            )

            // 6. Resync captures baseline tokens (including newly regranted STEPS)
            coEvery { client.getChangesToken(any()) } answers {
                val request = firstArg<ChangesTokenRequest>()
                "fresh-token-${request.recordTypes.first().simpleName}"
            }
            val baselineTokens = synchronizer.captureChangesTokens()
            assertTrue(
                "Baseline tokens must include newly regranted STEPS",
                baselineTokens.containsKey(HealthDataType.STEPS),
            )

            // 7. Commit baseline tokens, clearing suspension and bootstrapping delta sync
            synchronizer.commitTokens(baselineTokens)
            baselineTokens.forEach { (_, token) ->
                coEvery { client.getChanges(token) } returns changesResponse(emptyList())
            }

            // 8. Subsequent delta sync resumes without requiring full resync
            val outcome6 = synchronizer.applyPendingChanges()
            assertFalse(
                "Subsequent sync after regrant and baseline commit should not request full resync",
                outcome6.requiresFullResync,
            )
        }

    @Test
    fun `applyPendingChanges processes paginated changes without persisting candidate tokens`() =
        runTest {
            val dataType = HealthDataType.SLEEP
            // Distinct tokens per type (rather than one token for all 11 types) keep this
            // test's total page count under MAX_CHANGE_PAGES_PER_RUN: only SLEEP's chain below
            // spans two pages, every other type's unstubbed single call is a relaxed no-op page.
            seedTokens()
            coEvery { tokenStore.get(dataType) } returns "token1"

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

            // Mock resolving the deleted record's affected date via the batched port -- one page
            // resolves/deletes its whole page of IDs in a single plural call (brief Step 3).
            coEvery {
                changeIngestionStore.affectedDatesForRecords(HealthDataType.SLEEP, listOf(recordId), any())
            } returns setOf(LocalDate.parse("2026-06-19"))

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                changeIngestionStore.deleteRecords(HealthDataType.SLEEP, listOf(recordId))
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
                changeIngestionStore.affectedDatesForRecords(HealthDataType.SLEEP, listOf("id123"), any())
            } returns setOf(LocalDate.parse("2026-06-19"))

            val outcome = synchronizer.applyPendingChanges()

            assertFalse(outcome.requiresFullResync)
            assertTrue(outcome.affectedDates.contains(LocalDate.parse("2026-06-19")))
            coVerify {
                changeIngestionStore.deleteRecords(HealthDataType.SLEEP, listOf("id123"))
            }
            coVerify(exactly = 0) {
                healthIngestionStore.persist(any())
            }
        }

    @Test
    fun `excluded exercise upsertion deletes stored workout and marks its day affected`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(deviceByDataType = mapOf(HealthDataType.EXERCISE.name to "WatchA")))
            seedTokens()
            val recordId = "workout-1"
            val storedDay = LocalDate.parse("2026-06-19")
            val record = exerciseRecord(recordId, "WatchB")
            routeOneChange(HealthDataType.EXERCISE, UpsertionChange(record))
            coEvery {
                changeIngestionStore.affectedDatesForRecords(HealthDataType.EXERCISE, listOf(recordId), any())
            } returns setOf(storedDay)

            val outcome = synchronizer.applyPendingChanges()

            assertEquals(setOf(storedDay), outcome.affectedDates)
            coVerifyOrder {
                changeIngestionStore.affectedDatesForRecords(HealthDataType.EXERCISE, listOf(recordId), any())
                changeIngestionStore.deleteRecords(HealthDataType.EXERCISE, listOf(recordId))
            }
            coVerify(exactly = 0) { changeIngestionStore.persistPreparedWorkouts(any()) }
            coVerify(exactly = 0) { healthIngestionStore.persist(any()) }
        }

    private fun exerciseRecord(recordId: String, deviceModel: String): ExerciseSessionRecord =
        mockk<ExerciseSessionRecord>(relaxed = true) {
            every { metadata } returns
                mockk<Metadata> {
                    every { id } returns recordId
                    every { device } returns mockk<Device> {
                        every { model } returns deviceModel
                        every { manufacturer } returns null
                    }
                    every { dataOrigin } returns mockk<DataOrigin> { every { packageName } returns "pkg" }
                }
            every { startTime } returns Instant.parse("2026-06-19T10:00:00Z")
            every { endTime } returns Instant.parse("2026-06-19T11:00:00Z")
            every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
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

    /**
     * Stateful [tokenStore] stub shared by the token-lifecycle and page-batching tests: [get]
     * reflects [tokens] (or null once suspended), [suspendType]/[putAll]/[put] mutate those same
     * maps so a later `applyPendingChanges()` call in the same test sees the effect of an earlier
     * one. A [HealthDataType] absent from [tokens] resolves to a null (missing) token -- since
     * EXERCISE is first in [HealthDataType.entries], a test that seeds only EXERCISE still
     * exercises exactly that type's page-batching before the loop hits the next type's missing
     * token and short-circuits with [HealthChangeSyncOutcome.fullResync].
     */
    private fun setupFakeTokenStore(
        tokens: MutableMap<HealthDataType, String>,
        suspended: MutableSet<HealthDataType>,
    ) {
        coEvery { tokenStore.get(any()) } answers {
            val type = firstArg<HealthDataType>()
            if (type in suspended) null else tokens[type]
        }
        coEvery { tokenStore.suspendType(any()) } answers {
            val type = firstArg<HealthDataType>()
            suspended.add(type)
            tokens.remove(type)
        }
        coEvery { tokenStore.isSuspended(any()) } answers { firstArg<HealthDataType>() in suspended }
        coEvery { tokenStore.put(any(), any(), any()) } answers {
            tokens[firstArg()] = secondArg()
        }
        coEvery { tokenStore.putAll(any(), any()) } answers {
            val newTokens = firstArg<Map<HealthDataType, String>>()
            tokens.putAll(newTokens)
            suspended.removeAll(newTokens.keys)
        }
    }

    private fun allPermissions(): Set<String> =
        HealthDataType.entries.flatMap { current ->
            recordClassesFor(current).map { HealthPermission.getReadPermission(it) }
        }.toSet()

    private fun stepsPermissions(): Set<String> =
        recordClassesFor(HealthDataType.STEPS).map { HealthPermission.getReadPermission(it) }.toSet()
}
